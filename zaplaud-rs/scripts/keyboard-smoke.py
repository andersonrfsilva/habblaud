#!/usr/bin/env python3
"""Exercita foco e atalhos reais por XTest, com fixture isolada e sem automação interna."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare-compose.py'))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)
smoke = comparison.smoke


def command(fixture, text):
    fixture.process.stdin.write(text + '\n')
    fixture.process.stdin.flush()
    result = json.loads(fixture.process.stdout.readline())
    assert 'error' not in result, result
    return result


def type_text(desktop, text):
    for char in text:
        desktop.key({' ': 'space', '-': 'minus', '.': 'period', '/': 'slash'}.get(char, char.lower()), ('Shift_L',) if char.isupper() else ())

def context_confirmation(desktop):
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 980, 728)
    for pressed in (1, 0):
        desktop.xt.XTestFakeButtonEvent(desktop.d, 3, pressed, 0)
    desktop.x.XSync(desktop.d, 0)


def scroll_roles(desktop):
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 700, 600)
    for _ in range(4):
        for pressed in (1, 0):
            desktop.xt.XTestFakeButtonEvent(desktop.d, 5, pressed, 0)
    desktop.x.XSync(desktop.d, 0)
    time.sleep(.3)
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 1095, 430)
    desktop.xt.XTestFakeButtonEvent(desktop.d, 1, 1, 0)
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 1095, 464)
    desktop.xt.XTestFakeButtonEvent(desktop.d, 1, 0, 0)
    desktop.x.XSync(desktop.d, 0)
    time.sleep(.3)


class Session:
    def __init__(self, binary, display, port, theme):
        self.desktop = comparison.Desktop(display)
        assert not self.desktop.mapped(), 'Display ocupado por outro Zaplaud'
        self.fixture = smoke.Fixture(port, allow='terminal,messages,permissions,spawn')
        self.directory = tempfile.TemporaryDirectory(prefix='zaplaud-keyboard-')
        self.data = Path(self.directory.name)
        (self.data / 'runtime').mkdir(mode=0o700)
        env = dict(os.environ, DISPLAY=display, ZAPLAUD_KEYRING='memory',
                   ZAPLAUD_THEME=theme, ZAPLAUD_DATA_DIR=str(self.data / 'state'),
                   XDG_RUNTIME_DIR=str(self.data / 'runtime'))
        self.log = open(f'/tmp/zaplaud-rs-keyboard-{theme}.log', 'w')
        self.app = subprocess.Popen([str(Path(binary).resolve())], env=env, stdout=self.log, stderr=self.log)
        smoke.wait_for(self.desktop.mapped, 'Janela não abriu', 30)
        self.desktop.resize(1100)
        self.desktop.x.XMoveWindow(self.desktop.d, self.desktop.mapped()[0], 0, 0)
        self.desktop.x.XSetInputFocus(self.desktop.d, self.desktop.mapped()[0], 1, 0)
        self.theme = theme
        self.port = port

    def login(self, tabs=False):
        d = self.desktop
        d.fill(167, f'http://127.0.0.1:{self.port}')
        if tabs:
            d.key('Tab')
            type_text(d, 'android-test')
            d.key('Tab')
            type_text(d, 'android-test')
            d.key('Tab')
            d.key('Return')
        else:
            d.fill(243, 'android-test')
            d.fill(319, 'android-test')
            d.click(550, 383)
        time.sleep(.5)
        d.click(760, 432)
        smoke.wait_for(lambda: d.image().getpixel((360, 700)) == smoke.palette(self.theme)['outlineVariant'], 'Login/ordem Tab falhou', 30)
        time.sleep(1)

    def close(self):
        self.app.terminate()
        self.app.wait(timeout=10)
        self.fixture.stop()
        self.log.close()
        self.directory.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--display', default='localhost:89')
    parser.add_argument('--port', type=int, default=14789)
    parser.add_argument('--theme', choices=['light', 'dark'], default='light')
    args = parser.parse_args()
    session = Session(args.binary, args.display, args.port, args.theme)
    d = session.desktop
    passed = []
    try:
        session.login(tabs=True)
        passed.append('login-tab-order')
        closed = d.image().crop((400, 100, 1050, 650))

        def assert_closed(label):
            smoke.wait_for(lambda: comparison.ImageChops.difference(closed, d.image().crop((400, 100, 1050, 650))).getbbox() is None, label)

        def open_conversation():
            d.click(150, 250)
            time.sleep(.5)
            assert comparison.ImageChops.difference(closed, d.image().crop((400, 100, 1050, 650))).getbbox(), 'Conversa não abriu'

        for name, opener in [
            ('session-info', lambda: d.click(650, 32)),
            ('conversation-menu', lambda: d.click(1072, 32)),
            ('aside', lambda: (d.click(1072, 32), d.click(990, 280))),
            ('cast-dismiss', lambda: (d.click(1072, 32), d.click(1010, 328))),
            ('shake-confirm', lambda: context_confirmation(d)),
            ('settings', lambda: (d.click(328, 36), d.click(375, 88))),
            ('settings-role-dialog', lambda: (d.click(328, 36), d.click(375, 88), scroll_roles(d), d.click(170, 248))),
            ('attachment-picker', lambda: d.click(395, 730)),
            ('permission', lambda: (command(session.fixture, 'permission fixture:omp bash'), time.sleep(.5), d.click(1024, 32))),
        ]:
            open_conversation()
            previous = d.image().crop((700, 100, 1050, 400))
            opener()
            time.sleep(.5)
            delta = comparison.ImageChops.difference(previous, d.image().crop((700, 100, 1050, 400)))
            assert sum(pixel != (0, 0, 0) for pixel in delta.getdata()) > 200, f'{name}: overlay não abriu'
            d.image().save(f'/tmp/zaplaud-rs-keyboard-{name}-{args.theme}.png')
            if name == 'settings-role-dialog':
                d.key('Escape')
                time.sleep(.2)
            d.key('Escape')
            time.sleep(.25)
            d.key('Escape')
            assert_closed(f'{name}: Esc não recuperou foco do shell')
            passed.append(name)
        # O Kotlin só permite iniciar elenco sem conversa selecionada.
        for name, folder in [('cast-picker', False), ('cast-folders', True)]:
            d.click(316, 716)
            if folder:
                d.click(400, 304)
            time.sleep(.3)
            assert d.image().getpixel((20, 20)) != smoke.palette(args.theme)['surface'], f'{name}: diálogo não abriu'
            d.key('Escape')
            assert_closed(f'{name}: Esc não fechou diálogo')
            d.key('Down')
            d.key('Return')
            time.sleep(.7)
            assert comparison.ImageChops.difference(closed, d.image().crop((400, 100, 1050, 650))).getbbox(), f'{name}: lista perdeu foco'
            d.key('Escape')
            assert_closed(f'{name}: shell perdeu Esc')
            passed.append(name)
        d.click(328, 32)
        d.key('Escape')
        passed.append('list-menu')
        d.key('f', ('Control_L',))
        time.sleep(.2)
        assert d.image().getpixel((25, 160)) != smoke.palette(args.theme)['background'], 'Ctrl+F não abriu busca'
        d.key('Escape')
        d.key('Down')
        d.key('Down')
        d.key('Up')
        d.key('Return')
        time.sleep(.5)
        assert comparison.ImageChops.difference(closed, d.image().crop((400, 100, 1050, 650))).getbbox(), 'Up/Down/Enter não abriram conversa'
        passed.append('search-list-navigation')
        open_conversation()
        before = len(command(session.fixture, 'stats')['messageRequests'])
        d.click(650, 728)
        type_text(d, 'linha')
        d.key('Return', ('Shift_L',))
        type_text(d, 'dois')
        assert len(command(session.fixture, 'stats')['messageRequests']) == before, 'Shift+Enter enviou mensagem'
        d.key('Return')
        smoke.wait_for(lambda: len(command(session.fixture, 'stats')['messageRequests']) > before, 'Enter não enviou mensagem')
        request = command(session.fixture, 'stats')['messageRequests'][-1]
        assert request['text'] == 'linha\ndois', request
        passed.append('enter-send-shift-enter-newline')
        print(json.dumps({'passed': passed, 'theme': args.theme}))
    finally:
        session.close()


if __name__ == '__main__':
    main()
