#!/usr/bin/env python3
"""Captura o cliente integrado e produz pares reais com o inventário Compose, sem redimensionar."""
import argparse
import ctypes as C
import importlib.util
import json
from pathlib import Path
import time
import urllib.request
from PIL import Image, ImageChops, ImageStat

spec = importlib.util.spec_from_file_location('keyboard', Path(__file__).with_name('keyboard-smoke.py'))
keyboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keyboard)
comparison = keyboard.comparison
smoke = keyboard.smoke
FEATURES = ('conversation-idle', 'conversation-working', 'conversation-attachments',
            'conversation-status-banner', 'conversation-menu', 'list-menu',
            'permission-bash', 'permission-question-single', 'permission-question-multi',
            'permission-question-omp', 'settings-appearance', 'settings-roles',
            'settings-role-dialog', 'settings-quotas', 'aside', 'session-info',
            'cast-picker', 'cast-folders', 'cast-dismiss', 'cast-resume-picker', 'cast-resume')
SCREENS = comparison.SCREENS + FEATURES


def wheel(desktop, count):
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 700, 600)
    for _ in range(abs(count)):
        for pressed in (1, 0):
            desktop.xt.XTestFakeButtonEvent(desktop.d, 5 if count > 0 else 4, pressed, 0)
    desktop.x.XSync(desktop.d, 0)
    time.sleep(.5)


def capture_features(args, theme):
    session = keyboard.Session(args.rust_binary, args.display, args.port, theme)
    d = session.desktop
    def save(screen):
        time.sleep(args.settle)
        image = d.image()
        modal = screen in {'aside', 'session-info', 'settings-role-dialog'} or screen.startswith(('permission-', 'cast-'))
        base = smoke.palette(theme)['surface']
        alpha = .32 if screen in {'aside', 'session-info'} or screen.startswith('permission-') else .6
        expected = tuple(round(channel * (1 - alpha)) for channel in base) if modal else base
        if modal or screen in {'conversation-attachments', 'conversation-status-banner'}:
            assert max(abs(actual - wanted) for actual, wanted in zip(image.getpixel((10, 50)), expected)) <= 2, f'{screen}: estado modal incorreto'
        path = f'/tmp/zaplaud-rs-certify-candidate-{screen}-{theme}.png'
        image.save(path)
        print(path, flush=True)
    def focus():
        d.x.XSetInputFocus(d.d, d.mapped()[0], 1, 0)
    def omp():
        d.click(150, 250)
        time.sleep(.5)
    def menu():
        d.click(1072, 32)
    try:
        session.login()
        d.click(150, 160)
        save('conversation-idle')
        omp()
        save('conversation-working')
        menu()
        save('conversation-menu')
        d.click(990, 280)
        save('aside')
        d.key('Escape')
        d.click(650, 32)
        save('session-info')
        d.key('Escape')
        # Mantém a mesma mensagem queued utilizada na referência Compose.
        d.click(650, 730)
        keyboard.type_text(d, 'Teste visual')
        d.key('Return')
        save('conversation-status-banner')
        attachment = Path('/tmp/zaplaud-finvisual-attachment.txt')
        attachment.write_text('Anexo fictício para certificação visual.\n')
        d.click(395, 730)
        time.sleep(1)
        # Sem portal/chooser instalado, o diálogo de caminhos faz parte do cliente real.
        d.image().save(f'/tmp/zaplaud-rs-certify-attachment-picker-{theme}.png')
        d.click(550, 395)
        d.key('a', ('Control_L',))
        keyboard.type_text(d, str(attachment))
        d.click(710, 460)
        time.sleep(.5)
        focus()
        save('conversation-attachments')
        d.key('Escape')
        # Permissões independentes: resolve cada pedido antes do próximo.
        for kind in ('bash', 'question-single', 'question-multi', 'question-omp'):
            result = keyboard.command(session.fixture, f'permission fixture:claude {kind}')
            time.sleep(.5)
            d.click(150, 160)
            d.click(1024, 32)
            save('permission-' + kind)
            request = urllib.request.Request(session.fixture.ready['localUrl'] + '/api/permissions/' + result['id'] + '/decision',
                                             data=b'{"behavior":"terminal"}', headers={'Content-Type': 'application/json'}, method='POST')
            with urllib.request.urlopen(request) as response:
                assert response.status == 200
            d.key('Escape')
            focus()
        d.key('Escape')
        d.key('Escape')
        d.click(328, 32)
        save('list-menu')
        d.click(375, 88)
        if theme == 'dark':
            d.click(28, 218)
        save('settings-appearance')
        keyboard.scroll_roles(d)
        save('settings-roles')
        d.click(170, 248)
        save('settings-role-dialog')
        d.key('Escape')
        wheel(d, 8)
        save('settings-quotas')
        d.key('Escape')
        time.sleep(.4)
        d.key('Escape')
        time.sleep(.4)
        d.click(316, 717)
        save('cast-picker')
        d.click(400, 304)
        save('cast-folders')
        d.key('Escape')
        omp()
        menu()
        d.click(1010, 328)
        save('cast-dismiss')
        d.key('Escape')
        keyboard.command(session.fixture, 'bind-omp 12345678-1234-1234-1234-123456789abc')
        time.sleep(1)
        # A troca de sessão move Davi para o início e mostra o aviso acima da lista.
        d.click(150, 210)
        time.sleep(.5)
        menu()
        d.click(1010, 328)
        d.click(700, 426)
        for _ in range(50):
            consumed = keyboard.command(session.fixture, 'cast-consume')
            if any(job['op'] == 'stop' for job in consumed['consumed']):
                break
            time.sleep(.1)
        else:
            raise AssertionError(f'Dispensa não chegou à fixture: {consumed}')
        time.sleep(6)
        d.click(1024, 32)
        save('cast-resume-picker')
        d.click(400, 368)
        time.sleep(.3)
        assert ImageChops.difference(Image.open(f'/tmp/zaplaud-rs-certify-candidate-cast-resume-picker-{theme}.png'), d.image()).getbbox(), 'Elenco não abriu confirmação de retomada'
        save('cast-resume')
    finally:
        session.close()


def compare(themes):
    result = []
    for theme in themes:
        for screen in SCREENS:
            reference = Path(f'/tmp/zaplaud-ref-compose-{screen}-{theme}.png')
            candidate = Path(f'/tmp/zaplaud-rs-certify-candidate-{screen}-{theme}.png')
            assert reference.exists() and candidate.exists(), f'Par ausente: {screen}/{theme}'
            with Image.open(reference) as left, Image.open(candidate) as right:
                assert left.size == right.size, f'Dimensões diferentes: {screen}/{theme}: {left.size} != {right.size}'
                left, right = left.convert('RGB'), right.convert('RGB')
                pair = Image.new('RGB', (left.width + right.width, left.height))
                pair.paste(left, (0, 0))
                pair.paste(right, (left.width, 0))
                pair.save(f'/tmp/zaplaud-rs-certify-{screen}-{theme}.png')
                difference = ImageChops.difference(left, right)
                result.append({'screen': screen, 'theme': theme, 'size': list(left.size),
                               'mean_absolute_channel_error': sum(ImageStat.Stat(difference).mean) / 3})
    Path('/tmp/zaplaud-rs-certify.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'pairs': len(result)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rust-binary', required=True)
    parser.add_argument('--display', default='localhost:89')
    parser.add_argument('--port', type=int, default=14789)
    parser.add_argument('--theme', choices=['light', 'dark', 'both'], default='both')
    parser.add_argument('--settle', type=float, default=.8)
    parser.add_argument('--timeout', type=float, default=40)
    parser.add_argument('--only-compare', action='store_true')
    parser.add_argument('--features-only', action='store_true')
    args = parser.parse_args()
    themes = ['light', 'dark'] if args.theme == 'both' else [args.theme]
    if not args.only_compare:
        for theme in themes:
            if not args.features_only:
                comparison.capture(args, comparison.Desktop(args.display), 'rust', theme)
                for screen in comparison.SCREENS:
                    source = Path(f'/tmp/zaplaud-rs-polish-compare-{screen}-{theme}.png')
                    Path(f'/tmp/zaplaud-rs-certify-candidate-{screen}-{theme}.png').write_bytes(source.read_bytes())
            capture_features(args, theme)
    compare(themes)


if __name__ == '__main__':
    main()
