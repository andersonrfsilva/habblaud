#!/usr/bin/env python3
"""App completo, cliente separado e auxiliar real; nenhum dado ou serviço de produção."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

IMAGE = 'docker.io/library/node:24-bookworm-slim'
SIZE = 15 * 1024 * 1024
ROOT = Path(__file__).resolve().parents[1]
CLIENT = r'''
const http = require('node:http');
const { once } = require('node:events');
const base = 'http://127.0.0.1:4747';
const size = 15 * 1024 * 1024;
async function json(path, body) {
  const response = await fetch(base + path, body === undefined ? {} : { method:'POST', headers:{'content-type':'application/json'}, body:JSON.stringify(body) });
  const value = await response.json();
  if (!response.ok) throw Error(JSON.stringify({path,status:response.status,value}));
  return value;
}
async function upload(index, route='messages', partial=false) {
  const req = http.request(base + '/api/' + route + '/attachments', {method:'POST',headers:{'content-type':'application/octet-stream','content-length':size,'x-habblaud-file-name':encodeURIComponent('smoke-'+index+'.bin'),'x-habblaud-file-type':'application/octet-stream'}});
  const response = new Promise((resolve,reject)=> {req.on('response',res=> {let data='';res.setEncoding('utf8');res.on('data',c=>data+=c);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(data)}));});req.on('error',reject);});
  if (partial) {req.write(Buffer.from([index])); response.catch(()=>{}); return {req,response};}
  const chunk = Buffer.alloc(64*1024,index);
  for(let n=0;n<size;n+=chunk.length) if (!req.write(chunk)) await once(req,'drain');
  req.end();
  const result = await response;
  if(result.status!==201) throw Error(JSON.stringify(result));
  return result.body;
}
(async()=> {
  for(let n=0;;n++) {try {await json('/api/health');break;}catch(e){if(n>100)throw e;await new Promise(r=>setTimeout(r,100));}}
  if(process.argv.includes('--mixed')) {
    const partial=[];
    for(let n=0;n<8;n++) partial.push(await upload(n,n<5?'messages':'spawn',true));
    await new Promise(r=>setTimeout(r,300));
    const response=await fetch(base+'/api/messages/attachments',{method:'POST',headers:{'content-type':'application/octet-stream','x-habblaud-file-name':'extra.bin'},body:Buffer.from('x')});
    if(response.status!==429)throw Error('mixed expected429 got'+response.status);
    for(const item of partial)item.req.destroy();
    await Promise.allSettled(partial.map(item=>item.response));
    await new Promise(r=>setTimeout(r,300));
    console.log(JSON.stringify({mixed:{reservations:8,messages:5,spawn:3,extra:429,cancelled:8}}));
  }
  const snapshot=await json('/api/snapshot');
  const agent=snapshot.agents.find(a=>a.canMessage);
  if(!agent)throw Error('no demo agent canMessage');
  const files=[];
  for(let n=1;n<=5;n++)files.push(await upload(n));
  const message=await json('/api/messages',{agentId:agent.id,text:'',attachments:files.map(f=>f.id)});
  let status;
  for(let n=0;n<600;n++) {status=await json('/api/messages/'+message.id);if(status.status==='delivered')break;if(status.status==='failed')throw Error(JSON.stringify(status));await new Promise(r=>setTimeout(r,100));}
  if(status.status!=='delivered')throw Error('delivery timeout');
  console.log(JSON.stringify({message:status,bytes:files.reduce((n,f)=>n+f.size,0),files}));
})().catch(e=>{console.error(e);process.exitCode=1;});
'''

# Observa somente o fetch real do auxiliar; não cria polling nem ACK de laboratório.
BRIDGE_TRACE = r'''
const fs = require('node:fs');
const originalFetch = globalThis.fetch;
let observed = false;
globalThis.fetch = async function(input, init) {
  const response = await originalFetch(input, init);
  const url = new URL(typeof input === 'string' ? input : input.url);
  if (!observed && init?.method === 'POST' && url.pathname === '/api/host/files/poll' && response.status === 200) {
    observed = true;
    const marker = '/home/test/files-poll-ready.json';
    fs.writeFileSync(marker + '.new', JSON.stringify({url:url.href,status:response.status,at:Date.now(),observer:'real-helper-fetch-response'}));
    fs.renameSync(marker + '.new', marker);
  }
  return response;
};
'''

def run(*args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError(f'{args[0:3]} exit {result.returncode}: {result.stderr}\n{result.stdout}')
    return result.stdout.strip()


def inspect(name):
    return json.loads(run('podman', 'inspect', name))[0]


def metrics(name):
    info = inspect(name)
    pid = info['State']['Pid']
    cgroup = Path('/proc') / str(pid) / 'cgroup'
    relative = cgroup.read_text().strip().split('0::', 1)[1]
    path = Path('/sys/fs/cgroup') / relative.lstrip('/')
    values = {key: (path / key).read_text().strip() for key in ['memory.max', 'memory.peak', 'memory.events']}
    stat = dict(line.split() for line in (path / 'memory.stat').read_text().splitlines())
    values['memory.stat'] = {key: int(stat[key]) for key in ['anon', 'file', 'shmem']}
    values['OOMKilled'] = info['State']['OOMKilled']
    values['ExitCode'] = info['State']['ExitCode']
    return values


def round_trip(args, number, mixed=False):
    tag = 'habblaud-030-' + uuid.uuid4().hex[:12]
    server, helper, client = tag+'-server', tag+'-bridge', tag+'-client'
    names = [client, helper, server]
    started = time.monotonic()
    # /tmp do host pode ser tmpfs: o lote deve residir no filesystem persistente da worktree.
    if run('stat', '-f', '-c', '%T', str(ROOT)) in ['tmpfs', 'ramfs']:
        raise RuntimeError('worktree must be on persistent storage')
    with tempfile.TemporaryDirectory(prefix=tag+'-', dir=ROOT) as temp:
        tmp = Path(temp)
        for name in ['home', 'data', 'attachments', 'host-attachments', 'helper-home']:
            (tmp / name).mkdir(mode=0o700)
        (tmp / 'client.cjs').write_text(CLIENT)
        (tmp / 'bridge-trace.cjs').write_text(BRIDGE_TRACE)
        first_files_poll = None
        mounts = ['-v', f'{ROOT}:/app:ro', '-v', f'{tmp}/home:/home/test:ro', '-v', f'{tmp}/data:/data:rw']
        env = {'HOME':'/home/test','HABBLAUD_IN_DOCKER':str(int(args.topology=='bridge')),'HABBLAUD_DEMO':'1','HABBLAUD_WORLD':'0','HABBLAUD_TIMELINE':'0','HABBLAUD_UPDATE_CHECK':'0','HABBLAUD_OMP':'0','HABBLAUD_CODEX':'0','HABBLAUD_HOST':'127.0.0.1','HABBLAUD_BIND':'127.0.0.1','HABBLAUD_DATA_DIR':'/data','HABBLAUD_USAGE_DIR':'/data/usage','HABBLAUD_ATTACHMENTS_DIR':'/attachments','HABBLAUD_PTY':'1'}
        if args.topology == 'direct': mounts += ['-v', f'{tmp}/attachments:/attachments:rw']
        envargs = [item for key,value in env.items() for item in ['-e',key+'='+value]]
        common = ['--read-only','--userns=keep-id','--tmpfs','/tmp:rw,size=64m,mode=1777','--security-opt','no-new-privileges']
        try:
            run('podman','run','-d','--name',server,'--network=none',*common,'--memory',args.memory,'--memory-swap',args.memory,*mounts,*envargs,IMAGE,'node','/app/dist/server/index.js','--demo')
            if args.topology == 'bridge':
                run('podman','run','-d','--name',helper,'--network=container:'+server,*common,'-v',f'{ROOT}:/app:ro','-v',f'{tmp}/helper-home:/home/test:rw','-v',f'{tmp}/host-attachments:/attachments:rw','-v',f'{tmp}/bridge-trace.cjs:/bridge-trace.cjs:ro','-e','HOME=/home/test','-e','HABBLAUD_ATTACHMENTS_DIR=/attachments','-w','/app',IMAGE,'node','--require','/bridge-trace.cjs','--import','tsx','/app/scripts/codex-bridge.ts','--port','4747')
                marker = tmp / 'helper-home/files-poll-ready.json'
                deadline = time.monotonic() + 60
                while not marker.is_file():
                    if time.monotonic() >= deadline:
                        raise RuntimeError('real helper did not complete its first files poll with HTTP 200')
                    time.sleep(0.05)
                first_files_poll = json.loads(marker.read_text())
            output = run('podman','run','--name',client,'--network=container:'+server,*common,'-v',f'{tmp}/client.cjs:/client.cjs:ro',IMAGE,'node','/client.cjs',*(['--mixed'] if mixed else []))
            target = tmp / ('host-attachments' if args.topology=='bridge' else 'attachments')
            files = sorted(path for path in target.rglob('smoke-*.bin') if '.pending' not in path.parts)
            if len(files) != 5: raise RuntimeError(f'expected 5 finals, got {files}')
            hashes = []
            for file in files:
                index = int(file.stem.split('-')[1])
                digest = hashlib.sha256()
                with file.open('rb') as handle:
                    while chunk := handle.read(64*1024): digest.update(chunk)
                expected = hashlib.sha256(bytes([index])*SIZE).hexdigest()
                if file.stat().st_size != SIZE or digest.hexdigest() != expected: raise RuntimeError(f'wrong bytes/hash: {file}')
                hashes.append({'name':file.name,'bytes':SIZE,'sha256':expected})
            measured = metrics(server)
            if measured['OOMKilled'] or any(int(line.split()[1]) for line in measured['memory.events'].splitlines() if line.split()[0] in ['oom','oom_kill']): raise RuntimeError('OOM: '+json.dumps(measured))
            result = {'topology':args.topology,'round':number,'mixed':mixed,'node':run('podman','exec',server,'node','--version'),'seconds':round(time.monotonic()-started,3),'metrics':measured,'hashes':hashes,'client':output,'firstFilesPoll':first_files_poll,'helper':run('podman','logs',helper,check=False) if args.topology=='bridge' else None}
            print(json.dumps(result),flush=True)
            return result
        except Exception:
            print(json.dumps({'failed':tag,'server':run('podman','logs',server,check=False),'helper':run('podman','logs',helper,check=False)}),flush=True)
            raise
        finally:
            for name in names: run('podman','rm','-f',name,check=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--memory',default='256m')
    parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--topology',choices=['direct','bridge'],required=True)
    args=parser.parse_args()
    if not (ROOT/'dist/server/index.js').is_file(): raise SystemExit('Run npm run build first')
    run('podman','image','exists',IMAGE)
    for number in range(1,args.repeats+1): round_trip(args,number)
    round_trip(args,'mixed',True)

if __name__=='__main__': main()
