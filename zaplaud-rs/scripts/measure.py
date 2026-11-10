#!/usr/bin/env python3
"""Mede o cliente Linux real em três execuções isoladas, sem iniciar a fixture.

Uso (fixture já em execução; display sem outra janela Zaplaud)::

    python3 zaplaud-rs/scripts/measure.py --binary /caminho/zaplaud \
        --display localhost:85 --fixture-url http://127.0.0.1:14785

Requer /proc, X11/XTest e Pillow, como shell-smoke.py. Autentica somente com
android-test, confirma HTTP e espera a lista real. Cada execução aguarda 10 s
após o login e mede CPU durante 30 s, sem foco nem ponteiro sobre a janela.
O JSON usa KiB para PSS/RSS, bytes para o binário e percentual de um núcleo
para CPU. A mediana de abertura contém exatamente três amostras; não mede
primeiro frame nem separa cache frio/quente. HOME, XDG e dados do cliente são
temporários e apagados ao terminar. Logs do cliente não são publicados.
"""

import argparse
import ctypes as C
import importlib.util
import json
import os
from pathlib import Path
import signal
import statistics
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit


def fixture_origin(value):
    """Aceita somente origem, nunca credenciais ou parâmetros na linha de comando.

    >>> fixture_origin('http://127.0.0.1:14785/')
    'http://127.0.0.1:14785'
    >>> fixture_origin('http://[::1]:14785')
    'http://[::1]:14785'
    >>> for value in ('file:///tmp/fixture', 'http://user:password@localhost', 'https://localhost/api', 'http://localhost:99999'):
    ...     try:
    ...         fixture_origin(value)
    ...     except argparse.ArgumentTypeError:
    ...         pass
    ...     else:
    ...         raise AssertionError('Origem inválida aceita')
    """
    try:
        url = urlsplit(value)
        valid = (
            url.scheme in ('http', 'https') and url.hostname
            and url.username is None and url.password is None
            and url.path in ('', '/') and not url.query and not url.fragment
            and (url.port is None or 0 < url.port < 65536)
            and value.isascii()
            and all(char.isprintable() and not char.isspace() for char in value)
        )
    except ValueError:
        valid = False
    if not valid:
        raise argparse.ArgumentTypeError('Informe uma origem HTTP(S), sem credenciais, caminho ou parâmetros')
    return f'{url.scheme}://{url.netloc}'


def load_helpers():
    path = Path(__file__).with_name('shell-smoke.py')
    spec = importlib.util.spec_from_file_location('zaplaud_shell_smoke', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fill(desktop, y, text):
    """Usa XTest também para pontuação de origens IPv6 e nomes ASCII."""
    desktop.click(140, y)
    desktop.key('a', ('Control_L',))
    symbols = {
        ':': ('colon', ('Shift_L',)), '/': ('slash', ()),
        '.': ('period', ()), '-': ('minus', ()),
        '[': ('bracketleft', ()), ']': ('bracketright', ()),
        '_': ('underscore', ('Shift_L',)), '%': ('percent', ('Shift_L',)),
    }
    for char in text:
        if char in symbols:
            name, modifiers = symbols[char]
        elif char.isupper():
            name, modifiers = char.lower(), ('Shift_L',)
        elif char.isalnum():
            name, modifiers = char, ()
        else:
            raise RuntimeError('A origem da fixture contém um caractere não suportado pelo XTest')
        desktop.key(name, modifiers)


def stop(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)


def measure_run(args, helpers, desktop, index):
    if desktop.mapped():
        raise RuntimeError('O display já contém uma janela Zaplaud; use um display exclusivo')
    with tempfile.TemporaryDirectory(prefix='zaplaud-measure-') as temporary:
        root = Path(temporary)
        for name in ('home', 'data', 'cache', 'config', 'state', 'tmp', 'runtime'):
            (root / name).mkdir(mode=0o700)
        env = os.environ.copy()
        env.update(
            DISPLAY=args.display, HOME=str(root / 'home'),
            XDG_DATA_HOME=str(root / 'data'), XDG_CACHE_HOME=str(root / 'cache'),
            XDG_CONFIG_HOME=str(root / 'config'), XDG_STATE_HOME=str(root / 'state'),
            XDG_RUNTIME_DIR=str(root / 'runtime'),
            TMPDIR=str(root / 'tmp'), ZAPLAUD_DATA_DIR=str(root / 'zaplaud'),
            ZAPLAUD_KEYRING='memory', ZAPLAUD_THEME='light',
            SLINT_BACKEND='winit-software', WINIT_UNIX_BACKEND='x11',
        )
        with (root / 'client.log').open('w') as log:
            started = time.perf_counter()
            process = subprocess.Popen(
                [str(args.binary)], env=env, cwd=root, stdout=log,
                stderr=subprocess.STDOUT, start_new_session=True,
            )
            try:
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'O cliente terminou antes do mapeamento (código {process.returncode})')
                    window = desktop.mapped()
                    if window:
                        mapped_ms = (time.perf_counter() - started) * 1000
                        break
                    if time.perf_counter() - started > 30:
                        raise RuntimeError('A janela não mapeou em 30 segundos')
                    time.sleep(.005)
                desktop.resize(1100)
                fill(desktop, 162, args.fixture_url)
                fill(desktop, 230, 'android-test')
                fill(desktop, 298, 'android-test')
                desktop.click(550, 359)
                if args.fixture_url.startswith('http:'):
                    desktop.click(700, 442)
                colors = helpers.palette('light')
                helpers.wait_for(
                    lambda: desktop.image().getpixel((1000, 700)) == colors['surfaceContainerLow'],
                    'O login da fixture não abriu a lista',
                )
                helpers.wait_for(
                    lambda: len(set(desktop.image().crop((12, 140, 65, 600)).getdata())) > 30,
                    'A fixture não publicou linhas/avatares',
                )
                desktop.x.XSetInputFocus(desktop.d, desktop.root, 1, 0)
                desktop.x.XWarpPointer(desktop.d, 0, desktop.root, 0, 0, 0, 0, 0, 0)
                desktop.x.XSync(desktop.d, 0)
                time.sleep(10)
                idle = helpers.memory(process.pid)
                idle['threads'] = len(list(Path(f'/proc/{process.pid}/task').iterdir()))
                before = helpers.ticks(process.pid)
                cpu_started = time.perf_counter()
                time.sleep(30)
                after = helpers.ticks(process.pid)
                elapsed = time.perf_counter() - cpu_started
                return {
                    'run': index,
                    'launch_to_mapped_ms': mapped_ms,
                    'idle': idle,
                    'idle_cpu_percent_one_core': 100 * (after - before) / os.sysconf('SC_CLK_TCK') / elapsed,
                    'cpu_sample_seconds': elapsed,
                    'window_pixels': list(desktop.mapped()[1:]),
                }
            finally:
                stop(process)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--binary', type=Path, required=True, help='Binário Zaplaud já construído')
    parser.add_argument('--display', required=True, help='Display X11 exclusivo, por exemplo localhost:85')
    parser.add_argument('--fixture-url', type=fixture_origin, required=True, help='Origem da fixture já em execução')
    args = parser.parse_args()
    args.binary = args.binary.expanduser().resolve()
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        parser.error('--binary precisa apontar para um arquivo executável')
    helpers = load_helpers()
    desktop = helpers.Desktop(args.display)
    try:
        runs = [measure_run(args, helpers, desktop, index) for index in range(1, 4)]
        median = statistics.median
        print(json.dumps({
            'binary_size_bytes': args.binary.stat().st_size,
            'launch_to_mapped_median_ms': median(run['launch_to_mapped_ms'] for run in runs),
            'idle_median': {
                key: median(run['idle'][key] for run in runs)
                for key in ('pss_kib', 'rss_kib', 'threads')
            },
            'idle_cpu_percent_one_core_median': median(run['idle_cpu_percent_one_core'] for run in runs),
            'runs': runs,
        }, indent=2), flush=True)
    finally:
        desktop.x.XCloseDisplay.argtypes = [C.c_void_p]
        desktop.x.XCloseDisplay.restype = C.c_int
        desktop.x.XCloseDisplay(desktop.d)


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
