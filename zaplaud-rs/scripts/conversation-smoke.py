#!/usr/bin/env python3
"""Exercita seleção, clipboard, reabertura no fim, SSE único e rolagem longa por XTest."""
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


def last_assistant_bubble(image, surface):
    # O segundo ponto passa fora de cartões de código e tabelas.
    def inside(y):
        return any(image.getpixel((x, y)) == surface for x in (700, 970))

    top = bottom = 620
    if not inside(top):
        return None
    while top > 64 and inside(top - 1):
        top -= 1
    while bottom + 1 < image.height and inside(bottom + 1):
        bottom += 1
    return top, bottom


def link_probe_env(data, url_file):
    # O xdg-open real usa o manipulador local da fixture, sem tocar no perfil do navegador.
    browser = data / 'fixture-browser'
    browser.write_text('#!/bin/sh\nprintf "%s\\n" "$1" > "$ZAPLAUD_FIXTURE_LINK"\n')
    browser.chmod(0o700)
    applications = data / 'applications'
    applications.mkdir()
    (applications / 'zaplaud-fixture-browser.desktop').write_text(
        f'[Desktop Entry]\nType=Application\nName=Fixture\nExec={browser} %u\nMimeType=x-scheme-handler/http;\n'
    )
    (data / 'mimeapps.list').write_text(
        '[Default Applications]\nx-scheme-handler/http=zaplaud-fixture-browser.desktop;\n'
    )
    return dict(XDG_CURRENT_DESKTOP='X-Generic', XDG_CONFIG_HOME=str(data), XDG_DATA_HOME=str(data),
                BROWSER=str(browser), ZAPLAUD_FIXTURE_LINK=str(url_file))


def probe_selection(desktop, clipboard, colors, source, url, opened, captures, theme):
    def warp(x, y):
        desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, x, y)
        desktop.x.XSync(desktop.d, 0)

    def button(number, pressed):
        desktop.xt.XTestFakeButtonEvent(desktop.d, number, pressed, 0)
        desktop.x.XSync(desktop.d, 0)

    def key_copy():
        desktop.key('c', ('Control_L',))
        return clipboard.text()

    def key_copy_all():
        desktop.key('a', ('Control_L',))
        return key_copy()

    warp(1000, 400)
    time.sleep(.1)
    formatted = desktop.image()
    bounds = last_assistant_bubble(formatted, colors['surface'])
    assert bounds, 'Balão do teste de seleção ausente'
    top, bottom = bounds
    content = (380, top, 810, top + 155)
    formatted = formatted.crop(content).tobytes()
    expected_drag = 'Começo do parágrafo.\n\n• Item da lista\n\nfim_do_codigo\n'
    expected_all = expected_drag + '\nLink local'

    warp(420, top + 24)
    for _ in range(2):
        button(1, 1)
        button(1, 0)
        time.sleep(.075)
    assert desktop.image().crop(content).tobytes() != formatted, 'Duplo clique não mudou a apresentação'
    assert key_copy_all() == expected_all, 'Ctrl+A não selecionou todo o fragmento após duplo clique'
    desktop.key('Left')
    assert key_copy() == source, 'Ctrl+C sem trecho selecionado não copiou o Markdown original'

    warp(389, top + 23)
    button(1, 1)
    time.sleep(.1)
    warp(535, top + 125)
    time.sleep(.1)
    button(1, 0)
    assert key_copy() == expected_drag, 'O arraste não selecionou parágrafo, lista e código'
    desktop.image().save(captures / f'zaplaud-selecao-arraste-{theme}.png')
    warp(1000, 400)
    desktop.key('Escape')
    smoke.wait_for(lambda: desktop.image().crop(content).tobytes() == formatted,
                   'Esc não restaurou o texto formatado na mesma posição')
    desktop.image().save(captures / f'zaplaud-selecao-escape-{theme}.png')

    desktop.click(860, bottom - 42)
    assert key_copy_all() == expected_all, 'Botão Selecionar não abriu o campo'
    desktop.click(1000, top + 24)
    smoke.wait_for(lambda: desktop.image().crop(content).tobytes() == formatted,
                   'O clique fora não saiu do modo de seleção')
    desktop.click(950, bottom - 42)
    assert clipboard.text() == source

    warp(420, top + 24)
    button(3, 1)
    button(3, 0)
    time.sleep(.2)
    desktop.key('Up')
    desktop.key('Return')
    assert key_copy_all() == expected_all, 'Selecionar texto deixou de funcionar no menu de contexto'
    warp(420, top + 24)
    button(3, 1)
    button(3, 0)
    time.sleep(.2)
    desktop.key('Up')
    desktop.key('Return')
    smoke.wait_for(lambda: desktop.image().crop(content).tobytes() == formatted,
                   'Texto formatado deixou de funcionar no menu de contexto')

    assert not opened.exists(), 'O link abriu sem receber um clique'
    desktop.click(419, top + 146)
    smoke.wait_for(opened.is_file, 'Clique simples não abriu o link no manipulador do sistema')
    assert opened.read_text().strip() == url
    assert desktop.image().crop(content).tobytes() == formatted, 'Clique no link entrou em seleção'

    desktop.click(860, bottom - 42)
    assert key_copy_all() == expected_all, 'A roda precisa começar sobre o campo ativo'
    before_scroll = desktop.image().crop(content).tobytes()
    warp(420, top + 24)
    for _ in range(6):
        button(4, 1)
        button(4, 0)
    smoke.wait_for(lambda: desktop.image().crop(content).tobytes() != before_scroll,
                   'A roda sobre o campo não rolou a lista')
    return ['duplo-clique', 'ctrl-c-sem-seleção', 'ctrl-a', 'arraste-entre-blocos', 'escape-e-âncora',
            'botão', 'clique-fora', 'menu-entrar-e-sair', 'link', 'roda']


def probe_matrix(binary, display, output=None):
    fixture = Path(__file__).resolve().parents[1] / 'crates/zaplaud-ui/tests/fixtures/transcript-layout.json'
    with tempfile.TemporaryDirectory(prefix='zaplaud-layout-matrix-') as directory:
        runtime = Path(directory) / 'runtime'
        runtime.mkdir(mode=0o700)
        captures = Path(output).resolve() if output else Path(directory)
        captures.mkdir(parents=True, exist_ok=True)
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
                                   '--output', str(captures / label), '--page-up', '6']
                        if expanded:
                            command.append('--expand-all')
                        subprocess.run(command, env=env, check=True, timeout=90)
                        print(f'layout: {label}: PASS', flush=True)
        # Mensagens mais altas que 32767 px físicos abortavam o renderizador por software (coordenadas i16).
        tall = captures / 'transcript-tall.json'
        prose = '\n'.join(f'Linha {i} de uma resposta muito longa, com texto suficiente para quebrar.' for i in range(1500))
        code = '```text\n' + '\n'.join(f'saida {i}' for i in range(2500)) + '\n```'
        tall.write_text(json.dumps({'truncated': False, 'entries': [
            {'id': 'u1', 'kind': 'user', 'text': 'Pedido curto.', 'at': 1_700_000_000_000},
            {'id': 'a1', 'kind': 'assistant', 'text': prose, 'at': 1_700_000_001_000},
            {'id': 'a2', 'kind': 'assistant', 'text': code, 'at': 1_700_000_002_000},
            {'id': 'thinking1', 'kind': 'thinking', 'text': prose, 'at': 1_700_000_002_100},
            {'id': 'tool1', 'kind': 'tool', 'title': 'Ferramenta longa', 'text': prose, 'at': 1_700_000_002_200},
            {'id': 'result1', 'kind': 'result', 'toolUseId': 'tool1', 'text': code, 'at': 1_700_000_002_300},
            {'id': 'u2', 'kind': 'user', 'text': 'Fim.', 'at': 1_700_000_003_000},
        ]}))
        for renderer in ('winit-software', 'winit-skia-software'):
            for scale in (1, 2, 3):
                for width in (420, 1440):
                    for expanded in (False, True):
                        for position, args in (
                            ('top', ['--fraction', '0', '--page-up', '0']),
                            ('end', ['--fraction', '1', '--page-up', '0']),
                            ('middle', ['--fraction', '0.5', '--page-up', '0']),
                            ('page-up', ['--fraction', '1', '--page-up', '6']),
                        ):
                            label = f'tall-{renderer}-{scale}-{width}-{"expanded" if expanded else "collapsed"}-{position}'
                            env = dict(os.environ, DISPLAY=display, WINIT_UNIX_BACKEND='x11',
                                       SLINT_BACKEND=renderer, SLINT_SCALE_FACTOR=str(scale),
                                       XDG_RUNTIME_DIR=str(runtime), ZAPLAUD_KEYRING='memory',
                                       ZAPLAUD_DATA_DIR=str(Path(directory) / 'state'))
                            command = [binary, str(tall), '--size', f'{width}x900',
                                       '--output', str(captures / label), '--snapshot-only', *args]
                            if expanded:
                                command.append('--expand-all')
                            subprocess.run(command, env=env, check=True, timeout=90)
                            print(f'layout: {label}: PASS', flush=True)
        print('layout: tall matrix: PASS (96 configurations; 2 renderers; scales 1/2/3; widths 420/1440; expanded/collapsed; top/end/middle/page-up)', flush=True)
        if output:
            print(f'layout: capturas e fixture preservadas em {captures}', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary')
    parser.add_argument('--probe', help='Executável transcript_probe: verifica a matriz de layout antes do smoke')
    parser.add_argument('--probe-only', action='store_true')
    parser.add_argument('--probe-output', help='Diretório persistente para fixture alta, PNGs e geometria da matriz')
    parser.add_argument('--display', default='localhost:87')
    parser.add_argument('--port', type=int, default=14787)
    parser.add_argument('--theme', choices=['light', 'dark'], default='light')
    parser.add_argument('--switches', type=int, default=20)
    parser.add_argument('--output-dir', type=Path, default=Path('/tmp'))
    args = parser.parse_args()
    if args.probe:
        probe_matrix(args.probe, args.display, args.probe_output)
    if args.probe_only:
        if not args.probe:
            parser.error('--probe-only exige --probe')
        return
    if not args.binary:
        parser.error('--binary é obrigatório sem --probe-only')
    desktop = comparison.Desktop(args.display)
    captures = args.output_dir
    captures.mkdir(parents=True, exist_ok=True)
    assert not desktop.mapped(), 'Display ocupado por outro Zaplaud'
    clipboard = Clipboard(desktop)
    fixture = smoke.Fixture(args.port)
    result = {'theme': args.theme, 'switches': []}
    try:
        with tempfile.TemporaryDirectory(prefix='zaplaud-conversation-regression-') as directory:
            data = Path(directory)
            (data / 'runtime').mkdir(mode=0o700)
            env = dict(os.environ, DISPLAY=args.display, WINIT_UNIX_BACKEND='x11', ZAPLAUD_THEME=args.theme, ZAPLAUD_KEYRING='memory', ZAPLAUD_DATA_DIR=str(data / 'state'), XDG_RUNTIME_DIR=str(data / 'runtime'))
            opened = data / 'opened-link'
            env.update(link_probe_env(data, opened))
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
                    colors = smoke.palette(args.theme)
                    smoke.wait_for(lambda: desktop.image().getpixel((360, 700)) == colors['outlineVariant'], 'Login não terminou', 30)
                    claude_row, omp_row = 205, 294
                    smoke.wait_for(
                        lambda: desktop.image().crop((12, omp_row - 20, 60, omp_row + 20)).getcolors(30) is None,
                        'Avatar da conversa OMP não apareceu',
                    )
                    desktop.click(150, omp_row)
                    smoke.wait_for(
                        lambda: desktop.image().crop((370, 690, 1090, 755)).getcolors(1) is None,
                        'Compositor da conversa OMP não apareceu',
                    )

                    def command(text):
                        fixture.process.stdin.write(text + '\n')
                        fixture.process.stdin.flush()
                        return json.loads(fixture.process.stdout.readline())

                    latest = 'Carga fictícia 220.'
                    transcript = next(Path(fixture.ready['home']).glob('.omp/sessions/**/*.jsonl'))

                    def write_entry(stream, text, role):
                        stream.write(json.dumps({'type': 'message', 'id': str(uuid.uuid4()), 'timestamp': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), 'message': {'role': role, 'content': [{'type': 'text', 'text': text}]}}) + '\n')

                    with transcript.open('a') as stream:
                        for index in range(220):
                            write_entry(stream, f'Carga fictícia {index + 1}.', 'assistant' if index % 2 else 'user')
                    cache_dir = data / 'state/data'
                    deadline = time.monotonic() + 20
                    while True:
                        counts = {path.name: len(json.loads(path.read_text())['entries'])
                                  for path in cache_dir.glob('conversation-*.json')}
                        retained_entries = max(counts.values(), default=0)
                        if retained_entries >= 220:
                            result['retained_entries'] = retained_entries
                            break
                        if time.monotonic() >= deadline:
                            files = sorted(path.name for path in cache_dir.iterdir()) if cache_dir.is_dir() else 'diretório ausente'
                            raise AssertionError(
                                f'Cache de conversa em {cache_dir} não chegou a 220 entradas em 20 s; '
                                f'arquivos: {files!r}; entradas por cache: {counts!r}')
                        time.sleep(.5)

                    def copy_last():
                        bounds = last_assistant_bubble(desktop.image(), colors['surface'])
                        if bounds is None:
                            return ''
                        # Rodapé antes do horário (16 px) e do padding inferior (12 px).
                        desktop.click(950, bounds[1] - 42)
                        return clipboard.text()

                    desktop.image().save(captures / f'zaplaud-rs-fin-conversation-regression-initial-{args.theme}.png')
                    # Runner lento (build debug, 2 núcleos): a lista segue o fim, mas pode levar alguns segundos.
                    copied = ''
                    deadline = time.monotonic() + 20
                    while copied != latest and time.monotonic() < deadline:
                        copied = copy_last()
                        if copied != latest:
                            time.sleep(1)
                    assert copied == latest, f'A primeira carga não acompanhou o fim: {copied!r}'
                    state_path = cache_dir / 'local_state.json'

                    def load_state():
                        return json.loads(state_path.read_text())

                    initial_state = load_state()
                    omp_session = initial_state['last_session']
                    assert json.loads(omp_session)['provider'] == 'omp', 'A conversa inicial não é OMP'
                    # Até aqui só o OMP foi aberto, portanto só ele tem posição gravada.
                    omp_reading_key, = (key for key in initial_state if key.startswith('reading_'))

                    # Reabrir cache grande provoca estimativas intermediárias do ListView. Elas não são gestos do leitor.
                    for _ in range(3):
                        desktop.click(150, claude_row)
                        desktop.click(150, omp_row)
                        smoke.wait_for(lambda: copy_last() == latest, 'Reabertura do cache saiu do fim', 20)
                    result['baseline'] = smoke.memory(app.pid)
                    for index in range(args.switches):
                        desktop.click(150, claude_row if index % 2 == 0 else omp_row)
                        if index % 2:
                            smoke.wait_for(lambda: copy_last() == latest, f'Reabertura {index + 1} saiu do fim', 20)
                        else:
                            time.sleep(.3)
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

                    history_box = (377, 80, 890, 580)

                    omp_end_button_y = 614

                    def at_history(y=omp_end_button_y):
                        return desktop.image().getpixel((1040, y)) == colors['secondaryContainer']

                    def showing_claude():
                        session = load_state().get('last_session')
                        return (session and json.loads(session)['provider'] == 'claude'
                                and desktop.image().crop(history_box).getcolors(3) is None)

                    def saved_history():
                        reading = load_state().get(omp_reading_key)
                        return reading and reading.get('entry') and not reading['following']

                    def claude_at_end():
                        # O botão pode aparecer durante a espera pela posição restaurada.
                        if at_history(638):
                            desktop.click(1060, 638)
                        return not at_history(638)

                    # Salva o histórico OMP e troca de volta antes de terminar a rolagem do Claude.
                    desktop.key('Home')
                    smoke.wait_for(saved_history, 'Posição no histórico OMP não foi salva')
                    # Home ancora no aviso de janela truncada. Compare só o aviso, após o restauro,
                    # sem incluir as bolhas do ListView, que mudam de geometria enquanto assentam.
                    anchor_box = (510, 72, 950, 97)
                    anchor = desktop.image().crop(anchor_box)
                    assert load_state()[omp_reading_key]['entry'] == 'local:window-truncated', 'Home não gravou a âncora da janela truncada'
                    assert anchor.getcolors(1) is None, 'Aviso de janela truncada não apareceu ao ir ao início'
                    for attempt in range(1, 6):
                        desktop.click(150, claude_row)
                        smoke.wait_for(showing_claude, f'Troca {attempt}: conversa Claude não abriu')
                        smoke.wait_for(claude_at_end, f'Troca {attempt}: conversa Claude não chegou ao fim')
                        before = load_state()[omp_reading_key]
                        assert before.get('entry') and not before['following'], f'Troca {attempt}: OMP não estava no histórico'
                        started = time.monotonic()
                        # No limite inferior a roda para baixo não desloca a lista nem inicia o timer.
                        wheel(4, 1)
                        desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 150, omp_row)
                        for pressed in (1, 0):
                            desktop.xt.XTestFakeButtonEvent(desktop.d, 1, pressed, 0)
                        desktop.x.XSync(desktop.d, 0)
                        assert time.monotonic() - started < .22, 'A troca excedeu os 220 ms da roda'
                        time.sleep(3)
                        state = load_state()
                        assert state.get('last_session') == omp_session, f'Troca {attempt}: conversa OMP não reabriu'
                        after = state.get(omp_reading_key)
                        assert after == before, f'Troca {attempt}: a roda da conversa anterior alterou a posição gravada do OMP: {before!r} -> {after!r}'
                        assert at_history(), f'Troca {attempt}: o botão de voltar ao fim não apareceu no OMP'
                        assert desktop.image().crop(anchor_box).tobytes() == anchor.tobytes(), (
                            f'Troca {attempt}: o OMP não voltou à âncora visível')
                    result['wheel_switches'] = 5
                    desktop.image().save(captures / f'zaplaud-rs-fin-conversation-regression-restored-{args.theme}.png')
                    desktop.click(1060, omp_end_button_y)
                    smoke.wait_for(lambda: copy_last() == latest, 'Ir para o fim não voltou à última mensagem do OMP', 20)

                    # A roda no fim e o retorno pela roda devem continuar seguindo as entradas novas.
                    for number, return_to_end in ((221, False), (222, True)):
                        if return_to_end:
                            wheel(4, 6)
                            smoke.wait_for(
                                at_history,
                                'Roda para cima não saiu do fim',
                            )
                        wheel(5, 6)
                        smoke.wait_for(
                            lambda: not at_history(),
                            'Roda para baixo não voltou a seguir o fim',
                        )
                        latest = f'Carga fictícia {number}.'
                        with transcript.open('a') as stream:
                            write_entry(stream, latest, 'assistant')
                        smoke.wait_for(lambda: copy_last() == latest, 'Roda no fim impediu acompanhar a entrada nova', 20)

                    # A nova entrada deve atualizar o contador sem mover as mensagens em leitura.
                    badge_box = (1040, 596, 1080, 631)
                    for number, gesture in enumerate((lambda: wheel(4, 6), lambda: desktop.key('Home')), start=223):
                        end = desktop.image().crop(history_box).tobytes()
                        gesture()
                        smoke.wait_for(
                            at_history,
                            f'Histórico não saiu do fim antes da mensagem {number}',
                        )
                        time.sleep(.5)
                        history = desktop.image()
                        assert history.crop(history_box).tobytes() != end, 'A rolagem não mudou o histórico'
                        time.sleep(2)
                        assert desktop.image().crop(history_box).tobytes() == history.crop(history_box).tobytes(), 'Histórico voltou ao fim depois do gesto'
                        latest = f'Carga fictícia {number}.'
                        with transcript.open('a') as stream:
                            write_entry(stream, latest, 'assistant')
                        smoke.wait_for(
                            lambda: desktop.image().crop(badge_box).tobytes() != history.crop(badge_box).tobytes(),
                            'Contador de mensagens novas não atualizou', 20,
                        )
                        assert desktop.image().crop(history_box).tobytes() == history.crop(history_box).tobytes(), 'Mensagem nova moveu o histórico'
                        desktop.click(1060, omp_end_button_y)
                        smoke.wait_for(lambda: copy_last() == latest, 'Ir para o fim não chegou à mensagem nova', 20)
                    wheel(4, 400)
                    time.sleep(.5)
                    desktop.image().save(captures / f'zaplaud-rs-fin-conversation-regression-start-{args.theme}.png')
                    before = smoke.ticks(app.pid)
                    start = time.monotonic()
                    for _ in range(80):
                        wheel(5, 5)
                        time.sleep(.085)
                    result['scroll_seconds'] = time.monotonic() - start
                    result['scroll_cpu_percent'] = (smoke.ticks(app.pid) - before) / os.sysconf('SC_CLK_TCK') / result['scroll_seconds'] * 100
                    assert copy_last() == latest, 'Rolagem não chegou à última entrada'
                    result['scroll_memory'] = smoke.memory(app.pid)
                    desktop.image().save(captures / f'zaplaud-rs-fin-conversation-regression-end-{args.theme}.png')

                    url = f'http://127.0.0.1:{args.port}/fixture-selection-link'
                    source = f'**Começo do parágrafo.**\n\n- Item da lista\n\n```text\nfim_do_codigo\n```\n\n[Link local]({url})'
                    with transcript.open('a') as stream:
                        write_entry(stream, source, 'assistant')
                    smoke.wait_for(lambda: copy_last() == source, 'Mensagem para seleção não apareceu', 20)
                    result['selection'] = probe_selection(
                        desktop, clipboard, colors, source, url, opened, captures, args.theme)
                except Exception:
                    if desktop.mapped():
                        desktop.image().save(captures / f'zaplaud-rs-fin-conversation-regression-failure-{args.theme}.png')
                    raise
                finally:
                    app.terminate()
                    app.wait(timeout=10)
    finally:
        fixture.stop()
    (captures / f'zaplaud-rs-fin-conversation-regression-{args.theme}.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
