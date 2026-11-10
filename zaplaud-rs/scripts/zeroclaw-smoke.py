#!/usr/bin/env python3
"""Exercita ZeroClaw por XTest e guarda capturas reais, com fixture isolada."""
import argparse
import importlib.util
import json
from pathlib import Path
import time
from urllib.parse import urlparse

spec = importlib.util.spec_from_file_location('keyboard', Path(__file__).with_name('keyboard-smoke.py'))
keyboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keyboard)


def run(binary, display, theme):
    session = keyboard.Session(binary, display, 0, theme)
    session.port = urlparse(session.fixture.ready['url']).port
    d = session.desktop
    command = lambda text: keyboard.command(session.fixture, text)
    prefix = f'/tmp/zaplaud-align-rszc-{theme}'
    def save(name):
        time.sleep(.3)
        d.image().save(prefix + '-' + name + '.png')

    try:
        command('runtime zeroclaw')
        command('zc-reset')
        session.login(tabs=True)
        time.sleep(1)
        save('list')
        # A busca usa o provider real, sem alterar o estado interno da UI.
        d.key('f', ('Control_L',))
        keyboard.type_text(d, 'zeroclaw')
        time.sleep(.7)
        d.click(140, 250)
        time.sleep(1)
        save('conversation')
        context_pixels = None
        for value in ['none', '0', '120000']:
            command('zc-context ' + value)
            time.sleep(.4)
            current = d.image().crop((940, 700, 999, 750))
            if context_pixels is not None:
                assert keyboard.comparison.ImageChops.difference(context_pixels, current).getbbox() is None, 'ZeroClaw exibiu contexto não confiável'
            context_pixels = current
            save('context-' + value)
        d.click(650, 32)
        save('session-info-no-context')
        d.key('Escape')
        d.click(1072, 32)
        save('controls')
        d.click(980, 133)
        save('delivery')
        d.click(920, 184)
        d.click(600, 725)
        keyboard.type_text(d, 'Mensagem ZeroClaw')
        keyboard.smoke.wait_for(lambda: d.image().getpixel((1066, 740)) == keyboard.smoke.palette(theme)['primary'], 'Compositor não habilitou envio', 10)
        d.click(1066, 725)
        keyboard.smoke.wait_for(lambda: len(command('stats')['messageRequests']) == 1, 'Mensagem ZeroClaw não enviada', 10)
        stats = command('stats')
        old = stats['zeroclaw']['sessionId']
        assert stats['messageRequests'][-1]['deliverAs'] == 'steer'
        assert stats['messageRequests'][-1]['expectedSessionId'] == old
        time.sleep(1)
        command('zc-working')
        time.sleep(.8)
        command('zc-no-active')
        d.click(1024, 725)
        keyboard.smoke.wait_for(lambda: len(command('stats')['controlRequests']) == 1, 'STOP ZeroClaw não enviado', 10)
        stats = command('stats')
        assert stats['controlRequests'][-1]['stop'] is True
        assert stats['controlRequests'][-1]['expectedSessionId'] == old
        time.sleep(2)
        save('stop-no-active')
        # O rascunho da sessão anterior não deve aparecer no UUID novo.
        d.click(600, 725)
        keyboard.type_text(d, 'Rascunho anterior')
        d.click(1072, 32)
        d.click(976, 184)
        save('new-confirm')
        d.click(755, 446)
        keyboard.smoke.wait_for(lambda: command('stats')['zeroclaw']['sessionId'] != old, 'Nova sessão não aberta', 10)
        time.sleep(1)
        save('new-session')
        assert command('stats')['controlRequests'][-1]['command'] == 'new'
        d.click(316, 716)
        save('cast')
        d.click(400, 272)
        d.click(440, 260)
        keyboard.smoke.wait_for(lambda: len(command('stats')['castRequests']) == 1, 'Elenco ZeroClaw não chamado', 10)
        stats = command('stats')
        assert stats['runtime'] == 'zeroclaw'
        assert stats['castRequests'][-1]['path'] == '/api/cast/new'
        assert stats['castRequests'][-1]['status'] == 202
        save('cast-called')
        d.click(328, 32)
        d.click(375, 88)
        time.sleep(1)
        save('settings-summary-http')
        summary_before = d.image().crop((1030, 625, 1085, 660))
        d.click(1058, 645)
        keyboard.smoke.wait_for(lambda: keyboard.comparison.ImageChops.difference(summary_before, d.image().crop((1030, 625, 1085, 660))).getbbox() is not None, 'Resumo HTTP ficou desabilitado sem Tiny', 10)
        save('settings-summary-http-on')
        d.key('Escape')
        command('brand neutral')
        time.sleep(.5)
        save('neutral')
        print(json.dumps({'theme': theme, 'passed': ['login', 'list', 'context-hidden', 'controls', 'steer-guarded', 'stop-no-active', 'new-session', 'cast-zeroclaw', 'summary-http', 'neutral'], 'screenshots': prefix + '-*.png'}))
    finally:
        session.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--display', default='localhost:98')
    parser.add_argument('--theme', choices=['light', 'dark'], required=True)
    args = parser.parse_args()
    run(args.binary, args.display, args.theme)


if __name__ == '__main__':
    main()
