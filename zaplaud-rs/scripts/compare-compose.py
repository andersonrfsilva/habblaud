#!/usr/bin/env python3
"""Captura Compose/Slint reais por XTest e compara os pixels, sem gerar telas sintéticas."""
import argparse
import ctypes as C
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
from PIL import Image, ImageChops, ImageStat

spec = importlib.util.spec_from_file_location('shell_smoke', Path(__file__).with_name('shell-smoke.py'))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
ROOT = smoke.ROOT
SCREENS = ('login', 'http-warning', 'list', 'list-unread', 'search', 'two-servers', 'narrow', 'relogin')


class Desktop(smoke.Desktop):
    def image(self):
        # Diálogos do Compose são janelas X11 separadas; capturar a raiz inclui a sobreposição real.
        wid, width, height = self.mapped()
        attributes = smoke.Attributes()
        self.x.XGetWindowAttributes(self.d, wid, C.byref(attributes))
        raw = self.x.XGetImage(self.d, self.root, attributes.x, attributes.y, width, height, 0xffffffff, 2)
        if not raw:
            raise RuntimeError('Falha ao capturar janela e diálogos X11')
        try:
            return Image.frombytes('RGB', (width, height), C.string_at(raw.contents.data, raw.contents.bytes_per_line * height), 'raw', 'BGRX', raw.contents.bytes_per_line)
        finally:
            self.x.XDestroyImage(raw)
    def __init__(self, display):
        super().__init__(display)
        self.x.XMoveWindow.argtypes = [C.c_void_p, C.c_ulong, C.c_int, C.c_int]

    def click(self, px, py):
        # Não roubar foco do diálogo modal do AWT antes de clicar na confirmação.
        wid = self.mapped()[0]
        self.x.XWarpPointer(self.d, 0, wid, 0, 0, 0, 0, px, py)
        for pressed in (1, 0):
            self.xt.XTestFakeButtonEvent(self.d, 1, pressed, 0)
        self.x.XSync(self.d, 0)
        time.sleep(.35)



def stop(process):
    if process is not None and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def capture(args, desktop, kind, theme):
    prefix = '/tmp/zaplaud-ref-compose' if kind == 'compose' else '/tmp/zaplaud-rs-polish-compare'
    fixtures = []
    process = None
    with tempfile.TemporaryDirectory(prefix=f'zaplaud-compare-{kind}-{theme}-') as temporary:
        work = Path(temporary)
        (work / 'runtime').mkdir(mode=0o700)
        env = os.environ.copy()
        env.update(DISPLAY=args.display, HOME=str(work), XDG_RUNTIME_DIR=str(work / 'runtime'), XDG_DATA_HOME=str(work / 'data'), XDG_CACHE_HOME=str(work / 'cache'), XDG_CONFIG_HOME=str(work / 'config'), ZAPLAUD_THEME=theme, SLINT_BACKEND='winit-software', WINIT_UNIX_BACKEND='x11')
        env.update(ZAPLAUD_DATA_DIR=str(work / 'rust'), ZAPLAUD_KEYRING='memory')
        if kind == 'compose':
            # Preferences do JVM, não ZAPLAUD_THEME, selecionam o tema do Kotlin.
            preferences = work / 'prefs/.java/.userPrefs/br/com/xlinx/zaplaud'
            preferences.mkdir(parents=True)
            (preferences / 'prefs.xml').write_text(f'<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd"><map MAP_XML_VERSION="1.0"><entry key="theme" value="{theme}"/></map>')
            env['JAVA_TOOL_OPTIONS'] = f'-Djava.util.prefs.userRoot={work / "prefs"} -Dzaplaud.dataDir={work / "compose"} -Dzaplaud.keyring=memory -Duser.language=pt -Duser.country=BR -Dsun.java2d.uiScale=1'
        log_path = f'{prefix}-{theme}.log'
        with open(log_path, 'w') as log:
            def launch():
                nonlocal process
                command = [args.compose_binary] if kind == 'compose' else [args.rust_binary]
                process = subprocess.Popen(command, env=env, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                def mapped():
                    if process.poll() is not None:
                        raise RuntimeError(f'{kind} terminou com código {process.returncode}; veja {log_path}')
                    return desktop.mapped()
                smoke.wait_for(mapped, f'Janela {kind} não abriu; veja {log_path}', seconds=args.timeout)
                desktop.resize(1100)
                # AWT inicia em (48, 48); 760 pixels excederiam a raiz de 800 pixels.
                desktop.x.XMoveWindow(desktop.d, desktop.mapped()[0], 0, 0)
                desktop.x.XSync(desktop.d, 0)
                desktop.x.XSetInputFocus(desktop.d, desktop.mapped()[0], 1, 0)
                time.sleep(1)

            def save(screen):
                time.sleep(args.settle)
                path = f'{prefix}-{screen}-{theme}.png'
                desktop.image().save(path)
                print(path, flush=True)

            def login(port, warning=False):
                desktop.fill(168 if kind == 'compose' else 167, f'http://127.0.0.1:{port}')
                desktop.fill(244 if kind == 'compose' else 243, 'android-test')
                desktop.fill(320 if kind == 'compose' else 319, 'android-test')
                desktop.click(550, 384 if kind == 'compose' else 383)
                time.sleep(.6)
                if warning:
                    save('http-warning')
                # Compose posiciona a confirmação à direita; Slint mantém a caixa própria.
                desktop.click(758 if kind == 'compose' else 760, 437 if kind == 'compose' else 432)
                colors = smoke.palette(theme)
                divider = colors['outlineVariant']
                try:
                    smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) == divider, f'{kind}: login não abriu a lista', seconds=args.timeout)
                except AssertionError:
                    save('failed-login')
                    raise
                smoke.wait_for(lambda: len(set(desktop.image().crop((12, 140, 65, 600)).getdata())) > 30, f'{kind}: lista sem avatares', seconds=args.timeout)

            try:
                fixtures = [smoke.Fixture(args.port, allow='terminal,messages,permissions,spawn'), smoke.Fixture(args.port + 1, allow='terminal,messages,permissions,spawn')]
                launch()
                save('login')
                login(args.port, warning=True)
                save('list')
                fixtures[0].process.stdin.write('reply fixture:claude\n')
                fixtures[0].process.stdin.flush()
                assert json.loads(fixtures[0].process.stdout.readline())['ok']
                time.sleep(1)
                save('list-unread')
                desktop.key('f', ('Control_L',))
                save('search')
                desktop.key('Escape')
                time.sleep(.5)
                desktop.click(328, 36)
                desktop.click(380, 136)
                smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) != smoke.palette(theme)['outlineVariant'], 'Adicionar servidor não abriu formulário', seconds=args.timeout)
                login(args.port + 1)
                save('two-servers')
                desktop.click(180, 205)
                desktop.resize(600)
                if desktop.mapped()[1:] != (600, 760):
                    raise RuntimeError('Janela não aceitou 600 × 760')
                save('narrow')
                # Chaveiro em memória: reabrir mostra relogin para ambos, sem depender do backoff SSE.
                stop(process)
                smoke.wait_for(lambda: desktop.mapped() is None, 'Janela antiga não fechou')
                fixtures[1].restart()
                launch()
                smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) == smoke.palette(theme)['outlineVariant'], 'Relogin não terminou de carregar', seconds=args.timeout)
                save('relogin')
            finally:
                stop(process)
                for fixture in fixtures:
                    fixture.stop()


def compare(themes):
    results = []
    for theme in themes:
        for screen in SCREENS:
            reference = Path(f'/tmp/zaplaud-ref-compose-{screen}-{theme}.png')
            candidate = Path(f'/tmp/zaplaud-rs-polish-compare-{screen}-{theme}.png')
            if not reference.exists() or not candidate.exists():
                raise RuntimeError(f'Par ausente: {reference} / {candidate}')
            with Image.open(reference) as left, Image.open(candidate) as right:
                if left.size != right.size:
                    raise RuntimeError(f'Dimensões diferentes: {reference}: {left.size}; {candidate}: {right.size}')
                difference = ImageChops.difference(left.convert('RGB'), right.convert('RGB'))
                changed = sum(pixel != (0, 0, 0) for pixel in difference.getdata())
                results.append({'theme': theme, 'screen': screen, 'pixels': list(left.size), 'changed_fraction': changed / (left.width * left.height), 'mean_absolute_channel_error': sum(ImageStat.Stat(difference).mean) / 3})
                side_by_side = Image.new('RGB', (left.width + right.width, max(left.height, right.height)))
                side_by_side.paste(left, (0, 0))
                side_by_side.paste(right, (left.width, 0))
                side_by_side.save(f'/tmp/zaplaud-rs-polish-compare-side-by-side-{screen}-{theme}.png')
                difference.save(f'/tmp/zaplaud-rs-polish-compare-diff-{screen}-{theme}.png')
    path = Path('/tmp/zaplaud-rs-polish-compare.json')
    path.write_text(json.dumps(results, indent=2) + '\n')
    print(path, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', choices=('compose', 'rust', 'both', 'none'), default='both')
    parser.add_argument('--compose-binary', default=str(ROOT / 'android/desktop/build/compose/binaries/main/app/Zaplaud/bin/Zaplaud'))
    parser.add_argument('--rust-binary', default=str(ROOT / 'zaplaud-rs/target/debug/zaplaud'))
    parser.add_argument('--display', default='localhost:93')
    parser.add_argument('--port', type=int, default=14794)
    parser.add_argument('--theme', choices=('light', 'dark', 'both'), default='both')
    parser.add_argument('--timeout', type=float, default=40)
    parser.add_argument('--settle', type=float, default=1)
    parser.add_argument('--compare', action='store_true', help='Compara todos os pares solicitados; falha se faltar imagem')
    args = parser.parse_args()
    themes = ('light', 'dark') if args.theme == 'both' else (args.theme,)
    if args.capture != 'none':
        desktop = Desktop(args.display)
        if desktop.mapped():
            parser.error('Já existe uma janela Zaplaud no display; reserve um display exclusivo')
        for kind in ('compose', 'rust') if args.capture == 'both' else (args.capture,):
            binary = args.compose_binary if kind == 'compose' else args.rust_binary
            if not Path(binary).is_file() or not os.access(binary, os.X_OK):
                parser.error(f'Executável ausente: {binary}; o helper não faz builds')
            for theme in themes:
                capture(args, desktop, kind, theme)
    if args.compare:
        compare(themes)


if __name__ == '__main__':
    main()
