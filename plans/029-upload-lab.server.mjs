import http from 'node:http';
import { readFileSync, createWriteStream, createReadStream, mkdirSync } from 'node:fs';
import { rename } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import { performance } from 'node:perf_hooks';
import { AttachmentStore, parseAttachment, writeAttachments, ATTACHMENT_BODY_MAX } from '/candidate/server/spawn/attachments.ts';

const mode = process.env.MODE;
const store = new AttachmentStore();
const ids = [], metadata = [], samples = [];
let phase = 'before', index = -1, received = 0, uploaded = 0, started = performance.now();
mkdirSync('/data/staging', { recursive: true });
mkdirSync('/data/committed', { recursive: true });
function sample(label = phase) {
  const mem = process.memoryUsage();
  const stat = Object.fromEntries(readFileSync('/sys/fs/cgroup/memory.stat', 'utf8').trim().split('\n').map(s => { const [k,v] = s.split(' '); return [k, Number(v)]; }));
  samples.push({ ms: performance.now() - started, phase: label, index, received, uploaded, ...mem,
    current: Number(readFileSync('/sys/fs/cgroup/memory.current','utf8')), peak: Number(readFileSync('/sys/fs/cgroup/memory.peak','utf8')),
    anon: stat.anon, file: stat.file, shmem: stat.shmem, file_dirty: stat.file_dirty, file_writeback: stat.file_writeback });
}
const timer = setInterval(() => sample(), 10);
function jsonBody(req) {
  // Faithful successful-body reproduction of app.ts:217-242, with stage snapshots.
  // No custom JSON parser. Content type, body cap and ordinary error handling are retained.
  if (req.headers['content-type'] !== 'application/json') throw Error('content type');
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', c => { size += c.length; received += c.length; if (size > ATTACHMENT_BODY_MAX) return reject(Error('size')); chunks.push(c); });
    req.on('end', () => {
      try {
        sample('json-chunks');
        const raw = Buffer.concat(chunks).toString('utf8'); sample('json-string');
        const parsed = JSON.parse(raw); sample('json-parsed'); resolve(parsed);
      } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}
async function hashFile(path) {
  const h = createHash('sha256'); for await (const c of createReadStream(path)) h.update(c); return h.digest('hex');
}
const server = http.createServer(async (req,res) => {
  try {
    if (req.url === '/upload') {
      phase = 'receive'; index++; received = 0; sample('receive-start');
      let record;
      if (mode === 'A' || mode === 'D') {
        const body = await jsonBody(req);
        const attachment = parseAttachment(body); if ('error' in attachment) throw Error(attachment.error);
        sample('decoded');
        const hash = createHash('sha256').update(attachment.data).digest('hex');
        if (mode === 'A') { const result = store.put(attachment); if ('error' in result) throw Error(result.error); ids.push(result.id); }
        else { const [path] = writeAttachments('/data/staging', 'lab', [attachment]); metadata.push({path, hash, size:attachment.data.length}); }
        record = { hash, size: attachment.data.length };
      } else if (mode === 'B') {
        const chunks = []; for await (const c of req) { chunks.push(c); received += c.length; }
        sample('binary-chunks');
        const data = Buffer.concat(chunks); sample('binary-concat');
        const hash = createHash('sha256').update(data).digest('hex');
        const result = store.put({ name: `file-${index}.bin`, type:'application/octet-stream', data });
        if ('error' in result) throw Error(result.error); ids.push(result.id);
        record = { hash, size:data.length };
      } else if (mode === 'C') {
        const path = `/data/staging/file-${index}.bin`, hash = createHash('sha256'); let size = 0;
        // pipeline propagates disk backpressure; no full-file buffer or JSON/base64.
        const tap = new Transform({ transform(c, _enc, cb) { size += c.length; received += c.length; if (size > 15*1024*1024) return cb(Error('size')); hash.update(c); cb(null,c); } });
        await pipeline(req, tap, createWriteStream(path, { flags:'wx', mode:0o600, highWaterMark:64*1024 }));
        record = { hash:hash.digest('hex'), size }; metadata.push({path,...record});
      } else throw Error('mode');
      uploaded += record.size; phase = 'retained'; sample('upload-retained');
      res.writeHead(201, {'content-type':'application/json'}); res.end(JSON.stringify(record));
    } else if (req.url === '/commit') {
      phase = 'commit'; sample('commit-before');
      let paths;
      if (mode === 'A' || mode === 'B') {
        const files = store.take(ids,5); if (!Array.isArray(files)) throw Error(files.error);
        sample('store-held'); paths = writeAttachments('/data/committed', 'lab', files);
        sample('disk-written'); store.release(files.reduce((n,f)=>n+f.data.length,0));
      } else {
        paths = []; for (let i=0; i<metadata.length; i++) { const path = `/data/committed/file-${i}.bin`; await rename(metadata[i].path,path); paths.push(path); }
      }
      sample('commit-after'); phase = 'verify';
      const hashes = []; for (const path of paths) hashes.push(await hashFile(path));
      phase = 'after'; sample('verified'); res.end(JSON.stringify({hashes}));
    } else if (req.url === '/results') {
      sample('final'); clearInterval(timer);
      res.end(JSON.stringify({mode, node:process.version, samples})); server.close();
    } else { res.end('ready'); }
  } catch(e) { console.error(e); res.statusCode = 500; res.end(String(e)); }
});
server.listen(4779,'0.0.0.0',()=> { sample('ready'); console.log('READY'); });
