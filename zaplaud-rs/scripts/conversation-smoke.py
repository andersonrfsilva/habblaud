#!/usr/bin/env python3
"""Exercita clipboard, reabertura no fim, SSE único e rolagem longa na UI real por XTest."""
import argparse
import ctypes as C
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


class Clipboard:
    def __init__(self, desktop):
        self.desktop = desktop
        x = desktop.x
        x.XInternAtom.argtypes = [C.c_void_p, C.c_char_p, C.c_int]
        x.XInternAtom.restype = C.c_ulong
        x.XCreateSimpleWindow.argtypes = [C.c_void_p, C.c_ulong, C.c_int, C.c_int, C.c_uint, C.c_uint, C.c_uint, C.c_ulong, C.c_ulong]
        x.XCreateSimpleWindow.restype = C.c_ulong
        x.XConvertSelection.argtypes = [C.c_void_p, C.c_ulong, C.c_ulong, C.c_ulong, C.c_ulong, C.c_ulong]
        x.XGetWindowProperty.argtypes = [C.c_void_p, C.c_ulong, C.c_ulong, C.c_long, C.c_long, C.c_int, C.c_ulong, C.POINTER(C.c_ulong), C.POINTER(C.c_int), C.POINTER(C.c_ulong), C.POINTER(C.c_ulong), C.POINTER(C.POINTER(C.c_ubyte))]
        x.XPending.argtypes = [C.c_void_p]
        x.XPending.restype = C.c_int
        x.XNextEvent.argtypes = [C.c_void_p, C.c_void_p]
        self.window = x.XCreateSimpleWindow(desktop.d, desktop.root, 0, 0, 1, 1, 0, 0, 0)
        self.selection = x.XInternAtom(desktop.d, b'CLIPBOARD', 0)
        self.target = x.XInternAtom(desktop.d, b'UTF8_STRING', 0)
        self.property = x.XInternAtom(desktop.d, b'ZAPLAUD_CONVERSATION_TEST', 0)

    def text(self):
        desktop = self.desktop
        x = desktop.x
        while x.XPending(desktop.d):
            event = (C.c_long * 24)()
            x.XNextEvent(desktop.d, C.byref(event))
        x.XConvertSelection(desktop.d, self.selection, self.target, self.property, self.window, 0)
        x.XSync(desktop.d, 0)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            if x.XPending(desktop.d):
                event = (C.c_long * 24)()
                x.XNextEvent(desktop.d, C.byref(event))
                if C.cast(C.byref(event), C.POINTER(C.c_int))[0] == 31:
                    break
            time.sleep(.01)
        else:
            raise AssertionError('Clipboard X11 não respondeu')
        actual = C.c_ulong()
        fmt = C.c_int()
        count = C.c_ulong()
        remaining = C.c_ulong()
        data = C.POINTER(C.c_ubyte)()
        x.XGetWindowProperty(desktop.d, self.window, self.property, 0, 1_000_000, 0, 0, C.byref(actual), C.byref(fmt), C.byref(count), C.byref(remaining), C.byref(data))
        try:
            return C.string_at(data, count.value).decode() if data else ''
        finally:
            if data:
                x.XFree(data)


def probe_matrix(binary, display):
    fixture = Path(__file__).resolve().parents[1] / 'crates/zaplaud-ui/tests/fixtures/transcript-layout.json'
    with tempfile.TemporaryDirectory(prefix='zaplaud-layout-matrix-') as directory:
        runtime = Path(directory) / 'runtime'
        runtime.mkdir(mode=0o700)
        for renderer in ('winit-software', 'winit-skia-software'):
            for scale in (1, 2):
                for width in (420, 840, 1440):
                    for expanded in (False, True):
                        label = f'{renderer}-{scale}-{width}-{expanded}'
                        env = dict(os.environ, DISPLAY=display, WINIT_UNIX_BACKEND='x11',
                                   SLINT_BACKEND=renderer, SLINT_SCALE_FACTOR=str(scale),
                                   XDG_RUNTIME_DIR=str(runtime), ZAPLAUD_KEYRING='memory',
                                   ZAPLAUD_DATA_DIR=str(Path(directory) / 'state'))
                        command = [binary, str(fixture), '--size', f'{width}x900',
                                   '--output', str(Path(directory) / label), '--page-up', '6']
                        if expanded:
                            command.append('--expand-all')
                        subprocess.run(command, env=env, check=True, timeout=90)
                        print(f'layout: {label}: PASS', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary')
    parser.add_argument('--probe', help='Executável transcript_probe: verifica a matriz de layout antes do smoke')
    parser.add_argument('--probe-only', action='store_true')
    parser.add_argument('--display', default='localhost:87')
    parser.add_argument('--port', type=int, default=14787)
    parser.add_argument('--theme', choices=['light', 'dark'], default='light')
    parser.add_argument('--switches', type=int, default=20)
    args = parser.parse_args()
    if args.probe:
        probe_matrix(args.probe, args.display)
    if args.probe_only:
        if not args.probe:
            parser.error('--probe-only exige --probe')
        return
    if not args.binary:
        parser.error('--binary é obrigatório sem --probe-only')
    desktop = comparison.Desktop(args.display)
    assert not desktop.mapped(), 'Display ocupado por outro Zaplaud'
    clipboard = Clipboard(desktop)
    fixture = smoke.Fixture(args.port)
    result = {'theme': args.theme, 'switches': []}
    try:
        with tempfile.TemporaryDirectory(prefix='zaplaud-conversation-regression-') as directory:
            data = Path(directory)
            (data / 'runtime').mkdir(mode=0o700)
            env = dict(os.environ, DISPLAY=args.display, WINIT_UNIX_BACKEND='x11', ZAPLAUD_THEME=args.theme, ZAPLAUD_KEYRING='memory', ZAPLAUD_DATA_DIR=str(data / 'state'), XDG_RUNTIME_DIR=str(data / 'runtime'))
            with open(f'/tmp/zaplaud-rs-fin-conversation-regression-{args.theme}.log', 'w') as log:
                app = subprocess.Popen([args.binary], env=env, stdout=log, stderr=log)
                try:
                    smoke.wait_for(desktop.mapped, 'Janela não abriu', 30)
                    desktop.resize(1100)
                    desktop.x.XSetInputFocus(desktop.d, desktop.mapped()[0], 1, 0)
                    desktop.fill(167, f'http://127.0.0.1:{args.port}')
                    desktop.fill(243, 'android-test')
                    desktop.fill(319, 'android-test')
                    desktop.click(550, 383)
                    time.sleep(.4)
                    desktop.click(760, 432)
                    smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) == smoke.palette(args.theme)['outlineVariant'], 'Login não terminou', 30)
                    desktop.click(150, 250)
                    time.sleep(1)

                    def command(text):
                        fixture.process.stdin.write(text + '\n')
                        fixture.process.stdin.flush()
                        return json.loads(fixture.process.stdout.readline())

                    latest = 'Carga fictícia 220.'
                    transcript = next(Path(fixture.ready['home']).glob('.omp/sessions/**/*.jsonl'))
                    with transcript.open('a') as stream:
                        for index in range(220):
                            stream.write(json.dumps({'type': 'message', 'id': str(uuid.uuid4()), 'timestamp': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'message': {'role': 'assistant' if index % 2 else 'user', 'content': [{'type': 'text', 'text': f'Carga fictícia {index + 1}.'}]}}) + '\n')
                    time.sleep(4)
                    caches = [json.loads(path.read_text()) for path in (data / 'state/data').glob('conversation-*.json')]
                    result['retained_entries'] = max(len(cache['entries']) for cache in caches)
                    assert result['retained_entries'] >= 220, result

                    def copy_last():
                        desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 700, 644)
                        desktop.x.XSync(desktop.d, 0)
                        time.sleep(.15)
                        desktop.click(950, 639)
                        return clipboard.text()

                    desktop.image().save(f'/tmp/zaplaud-rs-fin-conversation-regression-initial-{args.theme}.png')
                    # Runner lento (build debug, 2 núcleos): a lista segue o fim, mas pode levar alguns segundos.
                    copied = ''
                    deadline = time.monotonic() + 20
                    while copied != latest and time.monotonic() < deadline:
                        copied = copy_last()
                        if copied != latest:
                            time.sleep(1)
                    assert copied == latest, f'A primeira carga não acompanhou o fim: {copied!r}'
                    # Reabrir cache grande provoca estimativas intermediárias do ListView. Elas não são gestos do leitor.
                    for _ in range(3):
                        desktop.click(150, 155)
                        desktop.click(150, 250)
                        time.sleep(.5)
                        assert copy_last() == latest, 'Reabertura do cache saiu do fim'
                    result['baseline'] = smoke.memory(app.pid)
                    for index in range(args.switches):
                        desktop.click(150, 155 if index % 2 == 0 else 250)
                        time.sleep(.3)
                        if index % 2:
                            assert copy_last() == latest, f'Reabertura {index + 1} saiu do fim'
                        stats = command('stats')
                        sample = {'switch': index + 1, **smoke.memory(app.pid), 'terminal': stats['terminal'], 'sse': stats['sse']}
                        assert stats['terminal'] == 1, sample
                        result['switches'].append(sample)
                    result['final'] = smoke.memory(app.pid)
                    assert result['final']['pss_kib'] <= result['baseline']['pss_kib'] + 1024, result

                    def wheel(button, count):
                        desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 900, 300)
                        for _ in range(count):
                            for pressed in (1, 0):
                                desktop.xt.XTestFakeButtonEvent(desktop.d, button, pressed, 0)
                        desktop.x.XSync(desktop.d, 0)

                    wheel(4, 400)
                    time.sleep(.5)
                    desktop.image().save(f'/tmp/zaplaud-rs-fin-conversation-regression-start-{args.theme}.png')
                    before = smoke.ticks(app.pid)
                    start = time.monotonic()
                    for _ in range(80):
                        wheel(5, 5)
                        time.sleep(.085)
                    result['scroll_seconds'] = time.monotonic() - start
                    result['scroll_cpu_percent'] = (smoke.ticks(app.pid) - before) / os.sysconf('SC_CLK_TCK') / result['scroll_seconds'] * 100
                    assert copy_last() == latest, 'Rolagem não chegou à última entrada'
                    result['scroll_memory'] = smoke.memory(app.pid)
                    desktop.image().save(f'/tmp/zaplaud-rs-fin-conversation-regression-end-{args.theme}.png')
                finally:
                    app.terminate()
                    app.wait(timeout=10)
    finally:
        fixture.stop()
    Path(f'/tmp/zaplaud-rs-fin-conversation-regression-{args.theme}.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
