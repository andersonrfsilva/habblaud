import http from 'node:http';
import { randomBytes, createHash } from 'node:crypto';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
const mode = process.env.MODE;
const sleep = ms => new Promise(r=>setTimeout(r,ms));
async function request(path, body, type) {
  const req = http.request({host:'127.0.0.1',port:4779,path,method:body?'POST':'GET',headers:body?{'content-type':type,'content-length':body.length}:{}});
  const response = new Promise((resolve,reject)=> { req.on('response', async res=> { try { let raw=''; for await(const c of res) raw+=c; if(res.statusCode>=400) throw Error(raw); resolve(JSON.parse(raw)); } catch(e) {reject(e);} }); req.on('error',reject); });
  let drains=0;
  if(body) for(let i=0;i<body.length;i+=64*1024) { if(!req.write(body.subarray(i,i+64*1024))) { drains++; await once(req,'drain'); } }
  req.end(); return {result:await response,drains};
}
const expected=[], timings=[];
await sleep(300);
for(let i=0;i<5;i++) {
  // Independent incompressible cryptographic random data, generated only in the separate client cgroup.
  const data=randomBytes(15*1024*1024), hash=createHash('sha256').update(data).digest('hex'); expected.push(hash);
  const body=mode==='A'||mode==='D'?Buffer.from(JSON.stringify({name:`file-${i}.bin`,type:'application/octet-stream',data:data.toString('base64')})):data;
  const start=performance.now(); const {result,drains}=await request('/upload',body,mode==='A'||mode==='D'?'application/json':'application/octet-stream');
  if(result.hash!==hash||result.size!==data.length) throw Error('upload bytes/hash mismatch');
  timings.push({index:i,ms:performance.now()-start,wireBytes:body.length,drains});
}
await sleep(1000);
const start=performance.now(), {result}=await request('/commit');
const commitMs=performance.now()-start;
if(JSON.stringify(result.hashes)!==JSON.stringify(expected)) throw Error('persisted hash mismatch');
await sleep(1000);
const {result:server}=await request('/results');
console.log(JSON.stringify({mode,expected,upload:timings,commitMs,server,verified:true}));
