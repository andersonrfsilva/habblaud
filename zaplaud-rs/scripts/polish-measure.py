#!/usr/bin/env python3
"""Mede processo real e exercita lista, cronômetros e cache de avatar via XTest."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare-compose.py'))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)
smoke = comparison.smoke


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True)
    parser.add_argument('--label', default='system-font')
    parser.add_argument('--display', default='localhost:93')
    parser.add_argument('--port', type=int, default=14794)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--font-path')
    args = parser.parse_args()
    desktop = comparison.Desktop(args.display)
    assert not desktop.mapped(), 'Display ocupado por outro Zaplaud'
    fixture = smoke.Fixture(args.port)
    result = {'label': args.label, 'binary_bytes': Path(args.binary).stat().st_size}
    try:
        # A fixture continua real; só o transcript fictício recebe o caso Unicode antes do refresh.
        transcript = next(Path(fixture.ready['home']).glob('**/00000000-0000-4000-8000-000000000001.jsonl'))
        with transcript.open('a') as stream:
            stream.write(json.dumps({'type': 'custom-title', 'customTitle': 'Ação, café, pão — 😀 🚀 🧑🏽‍💻', 'uuid': str(uuid.uuid4())}, ensure_ascii=False) + '\n')
        with tempfile.TemporaryDirectory(prefix='zaplaud-polish-measure-') as directory:
            runtime = Path(directory) / 'runtime'
            runtime.mkdir(mode=0o700)
            env = dict(os.environ, DISPLAY=args.display, ZAPLAUD_THEME='light',
                       SLINT_BACKEND='winit-software', WINIT_UNIX_BACKEND='x11',
                       ZAPLAUD_KEYRING='memory', ZAPLAUD_DATA_DIR=directory,
                       XDG_RUNTIME_DIR=str(runtime))
            if args.font_path:
                env['SLINT_FONT_PATH'] = args.font_path
            with open(f'/tmp/zaplaud-rs-polish-{args.label}.log', 'w') as log:
                start = time.monotonic()
                app = subprocess.Popen([args.binary], env=env, stdout=log, stderr=log)
                try:
                    smoke.wait_for(desktop.mapped, 'Janela não abriu')
                    result['launch_ms'] = (time.monotonic() - start) * 1000
                    desktop.resize(1100)
                    def sample(label):
                        desktop.x.XSetInputFocus(desktop.d, desktop.root, 1, 0)
                        desktop.x.XWarpPointer(desktop.d, 0, desktop.root, 0, 0, 0, 0, 1200, 790)
                        desktop.x.XSync(desktop.d, 0)
                        time.sleep(3)
                        before = smoke.ticks(app.pid)
                        begin = time.monotonic()
                        time.sleep(args.seconds)
                        elapsed = time.monotonic() - begin
                        result[label] = dict(smoke.memory(app.pid),
                            idle_cpu_percent=(smoke.ticks(app.pid) - before) / os.sysconf('SC_CLK_TCK') / elapsed * 100,
                            threads=len(list(Path(f'/proc/{app.pid}/task').iterdir())),
                            descriptors=len(list(Path(f'/proc/{app.pid}/fd').iterdir())))
                    sample('login')
                    desktop.x.XSetInputFocus(desktop.d, desktop.mapped()[0], 1, 0)
                    desktop.fill(162, f'http://127.0.0.1:{args.port}')
                    desktop.fill(230, 'android-test')
                    desktop.fill(298, 'android-test')
                    desktop.click(550, 359)
                    time.sleep(.5)
                    desktop.click(700, 442)
                    smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) == smoke.palette('light')['outlineVariant'], 'Login não terminou')
                    time.sleep(2)
                    desktop.image().save(f'/tmp/zaplaud-rs-polish-{args.label}-emoji.png')
                    sample('working_list')
                    def stats():
                        fixture.process.stdin.write('stats\n')
                        fixture.process.stdin.flush()
                        return json.loads(fixture.process.stdout.readline())
                    result['connections_before'] = stats()
                    desktop.x.XSetInputFocus(desktop.d, desktop.mapped()[0], 1, 0)
                    for _ in range(5):
                        desktop.click(150, 155)
                        time.sleep(.2)
                        desktop.key('Escape')
                    time.sleep(3)
                    result['connections_after'] = stats()
                    result['after_close'] = smoke.memory(app.pid)
                    result['threads_after_close'] = len(list(Path(f'/proc/{app.pid}/task').iterdir()))
                    result['descriptors_after_close'] = len(list(Path(f'/proc/{app.pid}/fd').iterdir()))
                    assert result['connections_before']['sse'] == result['connections_after']['sse']
                    assert result['connections_before']['terminal'] == result['connections_after']['terminal']
                finally:
                    app.terminate()
                    app.wait(timeout=10)
    finally:
        fixture.stop()
    path = Path(f'/tmp/zaplaud-rs-polish-{args.label}-metrics.json')
    path.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
