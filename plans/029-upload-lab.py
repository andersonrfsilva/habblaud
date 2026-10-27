#!/usr/bin/env python3
"""Disposable Node 24 upload lab. No product files are modified."""
import argparse
import json
import pathlib
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parent
CANDIDATE = ROOT.parent.parent / 'habblaud-029'
IMAGE = 'docker.io/library/node:24-bookworm-slim'

def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs)

def experiment(mode, limit, repeat):
    name = 'upload029-' + uuid.uuid4().hex[:12]
    client = name + '-client'
    data = pathlib.Path(tempfile.mkdtemp(prefix='029-upload-lab-data-', dir=ROOT))
    commands = []
    def command(*args, **kwargs):
        commands.append(list(args))
        return run(*args, **kwargs)
    try:
        command('podman', 'run', '-d', '--name', name, '--memory='+limit, '--memory-swap='+limit,
                '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--userns=keep-id', '--user', str(__import__('os').getuid())+':'+str(__import__('os').getgid()),
                '-v', str(ROOT)+':/lab:ro', '-v', str(CANDIDATE)+':/candidate:ro', '-v', str(data)+':/data:rw',
                '-e', 'MODE='+mode, IMAGE, 'node', '--import', '/candidate/node_modules/tsx/dist/loader.mjs', '/lab/029-upload-lab.server.mjs')
        for _ in range(100):
            logs = run('podman','logs',name)
            if 'READY' in logs.stdout:
                break
            state=json.loads(run('podman','inspect',name).stdout)[0]['State']
            if not state['Running']:
                raise RuntimeError('server exited: '+logs.stdout+logs.stderr)
            time.sleep(.1)
        else:
            raise RuntimeError('server readiness timeout')
        output=command('podman','run','--rm','--name',client,'--memory=1g','--memory-swap=1g',
                '--network=container:'+name,'--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',
                '-v',str(ROOT)+':/lab:ro','-e','MODE='+mode,IMAGE,'node','/lab/029-upload-lab.client.mjs',timeout=120)
        result=json.loads(output.stdout)
        result.update(limit=limit,repeat=repeat,commands=commands,
                      state=json.loads(run('podman','inspect',name).stdout)[0]['State'])
        return result
    finally:
        subprocess.run(['podman','rm','-f',client,name],capture_output=True)
        shutil.rmtree(data)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',default=str(ROOT/'029-upload-lab-results.json'))
    parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--modes',default='ABCD')
    parser.add_argument('--limit',default='1g')
    args=parser.parse_args()
    results=[]
    try:
        for mode in args.modes:
            for repeat in range(1,args.repeats+1):
                results.append(experiment(mode,args.limit,repeat))
                print(mode,args.limit,repeat,'verified',flush=True)
    finally:
        pathlib.Path(args.output).write_text(json.dumps({'image':IMAGE,'results':results},indent=2)+'\n')
if __name__=='__main__':
    main()
