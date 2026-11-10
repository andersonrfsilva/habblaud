#!/usr/bin/env python3
"""Mede abertura e recursos do cliente Linux real com fixture externa.

Usa cinco aberturas isoladas e mede recursos na última execução. A primeira
amostra é fria só para o processo, não para o page cache do kernel.
Mede lista, conversa e retorno após vinte aberturas/fechamentos por XTest.
JSON: KiB para PSS/RSS, bytes para binário, CPU percentual de um núcleo.
Wakeups são a diferença dos context switches voluntários de todas as threads:
um proxy de despertares, não uma contagem de interrupções do kernel.
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


def unfocus(desktop):
    desktop.x.XSetInputFocus(desktop.d, desktop.root, 1, 0)
    desktop.x.XWarpPointer(desktop.d, 0, desktop.root, 0, 0, 0, 0, 0, 0)
    desktop.x.XSync(desktop.d, 0)


def context_switches(pid):
    total = 0
    for task in Path(f'/proc/{pid}/task').iterdir():
        try:
            for line in (task / 'status').read_text().splitlines():
                if line.startswith('voluntary_ctxt_switches:'):
                    total += int(line.split()[1])
        except FileNotFoundError:
            pass
    return total


def font_memory(pid):
    font_mapping = False
    pss = 0
    mappings = 0
    for line in Path(f'/proc/{pid}/smaps').read_text().splitlines():
        if '-' in line.split()[0]:
            font_mapping = line.lower().endswith(('.ttf', '.otf', '.ttc'))
            mappings += int(font_mapping)
        elif font_mapping and line.startswith('Pss:'):
            pss += int(line.split()[1])
    return {'mapped_font_pss_kib': pss, 'font_mappings': mappings}


class DamageMonitor:
    class Rectangle(C.Structure):
        _fields_ = [('x', C.c_short), ('y', C.c_short), ('width', C.c_ushort), ('height', C.c_ushort)]

    def __init__(self, desktop):
        self.desktop = desktop
        self.library = C.CDLL('libXdamage.so.1')
        self.library.XDamageQueryExtension.argtypes = [C.c_void_p, C.POINTER(C.c_int), C.POINTER(C.c_int)]
        self.library.XDamageCreate.argtypes = [C.c_void_p, C.c_ulong, C.c_int]
        self.library.XDamageCreate.restype = C.c_ulong
        self.library.XDamageDestroy.argtypes = [C.c_void_p, C.c_ulong]
        desktop.x.XPending.argtypes = [C.c_void_p]
        desktop.x.XNextEvent.argtypes = [C.c_void_p, C.c_void_p]
        self.event_base, error = C.c_int(), C.c_int()
        if not self.library.XDamageQueryExtension(desktop.d, C.byref(self.event_base), C.byref(error)):
            raise RuntimeError('Extensão XDamage indisponível')
        self.damage = self.library.XDamageCreate(desktop.d, desktop.mapped()[0], 0)
        desktop.x.XSync(desktop.d, 0)

    def rectangles(self):
        class Event(C.Structure):
            _fields_ = [('type', C.c_int), ('serial', C.c_ulong), ('send_event', C.c_int),
                        ('display', C.c_void_p), ('drawable', C.c_ulong), ('damage', C.c_ulong),
                        ('level', C.c_int), ('more', C.c_int), ('timestamp', C.c_ulong),
                        ('area', DamageMonitor.Rectangle), ('geometry', DamageMonitor.Rectangle)]
        rectangles = []
        buffer = C.create_string_buffer(24 * C.sizeof(C.c_long))
        while self.desktop.x.XPending(self.desktop.d):
            self.desktop.x.XNextEvent(self.desktop.d, buffer)
            event = Event.from_buffer(buffer)
            if event.type == self.event_base.value and event.damage == self.damage:
                area = event.area
                rectangles.append([area.x, area.y, area.width, area.height])
        return rectangles

    def close(self):
        self.library.XDamageDestroy(self.desktop.d, self.damage)
        self.desktop.x.XSync(self.desktop.d, 0)


def sample(process, desktop, helpers, seconds, capture_damage=False):
    monitor = DamageMonitor(desktop) if capture_damage else None
    unfocus(desktop)
    time.sleep(2)
    memory = helpers.memory(process.pid)
    memory['threads'] = len(list(Path(f'/proc/{process.pid}/task').iterdir()))
    memory.update(font_memory(process.pid))
    if monitor:
        monitor.rectangles()
    before_ticks = helpers.ticks(process.pid)
    before_switches = context_switches(process.pid)
    started = time.perf_counter()
    started_unix = time.time()
    rectangles = []
    if monitor:
        while time.perf_counter() - started < seconds:
            rectangles.extend(monitor.rectangles())
            time.sleep(.01)
        rectangles.extend(monitor.rectangles())
        monitor.close()
    else:
        time.sleep(seconds)
    elapsed = time.perf_counter() - started
    memory.update(
        cpu_percent_one_core=100 * (helpers.ticks(process.pid) - before_ticks) / os.sysconf('SC_CLK_TCK') / elapsed,
        wakeups_per_30s=(context_switches(process.pid) - before_switches) * 30 / elapsed,
        wakeups_source='voluntary_context_switches_proxy',
        sample_seconds=elapsed,
        sample_started_unix=started_unix,
        sample_finished_unix=time.time(),
        damage_rectangles=rectangles if capture_damage else None,
    )
    return memory

def scroll_probe(desktop):
    desktop.x.XSetInputFocus(desktop.d, desktop.mapped()[0], 1, 0)
    desktop.x.XWarpPointer(desktop.d, 0, desktop.mapped()[0], 0, 0, 0, 0, 800, 400)
    desktop.x.XSync(desktop.d, 0)
    before = desktop.image().crop((370, 80, 1050, 650)).tobytes()
    started = time.perf_counter()
    for _ in range(20):
        for pressed in (1, 0):
            desktop.xt.XTestFakeButtonEvent(desktop.d, 4, pressed, 0)
    desktop.x.XSync(desktop.d, 0)
    while time.perf_counter() - started < 5:
        if desktop.image().crop((370, 80, 1050, 650)).tobytes() != before:
            elapsed = (time.perf_counter() - started) * 1000
            for _ in range(20):
                for pressed in (1, 0):
                    desktop.xt.XTestFakeButtonEvent(desktop.d, 5, pressed, 0)
            desktop.x.XSync(desktop.d, 0)
            return elapsed
        time.sleep(.005)
    raise RuntimeError('A rolagem não alterou o transcript em 5 segundos')



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
        if args.profile_render:
            env['SLINT_DEBUG_PERFORMANCE'] = 'refresh_lazy,console'
        trace_path = Path(f'/tmp/zaplaud-rs-fin-perf-{args.label}-strace-{index}.log')
        command = [str(args.binary)]
        if args.strace:
            command = [str(args.strace), '-D', '-f', '-ttt', '-T', '-o', str(trace_path),
                       '-e', 'trace=epoll_wait,epoll_pwait,poll,ppoll,timerfd_settime,futex,openat,connect',
                       *command]
        with (root / 'client.log').open('w') as log:
            started = time.perf_counter()
            process = subprocess.Popen(
                command, env=env, cwd=root, stdout=log,
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
                if index < args.runs:
                    return {'run': index, 'launch_to_mapped_ms': mapped_ms}
                desktop.resize(1100)
                fill(desktop, 167, args.fixture_url)
                fill(desktop, 243, 'android-test')
                fill(desktop, 319, 'android-test')
                desktop.click(550, 383)
                if args.fixture_url.startswith('http:'):
                    desktop.click(760, 432)
                colors = helpers.palette('light')
                helpers.wait_for(
                    lambda: desktop.image().getpixel((1000, 700)) == colors['surfaceContainerLow'],
                    'O login da fixture não abriu a lista',
                )
                helpers.wait_for(
                    lambda: len(set(desktop.image().crop((12, 140, 65, 600)).getdata())) > 30,
                    'A fixture não publicou linhas/avatares',
                )
                time.sleep(args.settle)
                idle = sample(process, desktop, helpers, args.seconds, args.damage)
                desktop.click(180, 160)
                if args.fixture:
                    time.sleep(1)
                    for _ in range(210):
                        args.fixture.process.stdin.write('append fixture:claude\n')
                        args.fixture.process.stdin.flush()
                        response = json.loads(args.fixture.process.stdout.readline())
                        if not response.get('ok'):
                            raise RuntimeError('A fixture recusou append')
                    helpers.wait_for(
                        lambda: any(len(json.loads(path.read_text()).get('entries', [])) >= 200
                                    for path in (root / 'zaplaud' / 'data').glob('conversation-*.json')),
                        'A conversa não persistiu 200 entradas reais', seconds=20,
                    )
                time.sleep(args.settle)
                desktop.image().save(f'/tmp/zaplaud-rs-fin-perf-{args.label}-conversation-{index}.png')
                large_conversation = sample(process, desktop, helpers, args.seconds, args.damage)
                large_conversation['scroll_to_first_changed_pixels_ms'] = scroll_probe(desktop)
                if args.fixture:
                    large_conversation['persisted_entry_count'] = max(
                        len(json.loads(path.read_text()).get('entries', []))
                        for path in (root / 'zaplaud' / 'data').glob('conversation-*.json')
                    )
                desktop.key('Escape')
                desktop.click(180, 240)
                time.sleep(args.settle)
                desktop.image().save(f'/tmp/zaplaud-rs-fin-perf-{args.label}-working-{index}.png')
                conversation = sample(process, desktop, helpers, args.seconds, args.damage)
                desktop.key('Escape')
                for cycle in range(20):
                    desktop.click(180, 160)
                    time.sleep(.3)
                    desktop.click(600, 30)
                    if cycle == 0:
                        desktop.image().save(f'/tmp/zaplaud-rs-fin-perf-{args.label}-cycle-info.png')
                    desktop.key('Escape')
                    desktop.click(1070, 30)
                    if cycle == 0:
                        desktop.image().save(f'/tmp/zaplaud-rs-fin-perf-{args.label}-cycle-menu.png')
                    desktop.key('Escape')
                    desktop.key('Escape')
                    time.sleep(.1)
                after_cycles = sample(process, desktop, helpers, args.seconds, args.damage)
                return {
                    'run': index,
                    'launch_cache': 'cold-ish-process' if index == 1 else 'warm',
                    'launch_to_mapped_ms': mapped_ms,
                    'idle': idle,
                    'working_conversation': conversation,
                    'large_conversation': large_conversation,
                    'after_20_open_close': after_cycles,
                    'sheet_cycle_coverage': ['session-info', 'conversation-menu'],
                    'idle_cpu_percent_one_core': idle['cpu_percent_one_core'],
                    'window_pixels': list(desktop.mapped()[1:]),
                    'strace_path': str(trace_path) if args.strace else None,
                }
            finally:
                stop(process)
                if args.profile_render:
                    Path(f'/tmp/zaplaud-rs-fin-perf-{args.label}-render-{index}.log').write_text((root / 'client.log').read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--binary', type=Path, required=True, help='Binário Zaplaud já construído')
    parser.add_argument('--display', required=True, help='Display X11 exclusivo, por exemplo localhost:85')
    fixture_group = parser.add_mutually_exclusive_group(required=True)
    fixture_group.add_argument('--fixture-url', type=fixture_origin, help='Origem de fixture externa; não semeia 200 entradas')
    fixture_group.add_argument('--fixture-port', type=int, help='Inicia fixture real e acrescenta 210 entradas durante a conversa')
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--seconds', type=float, default=30)
    parser.add_argument('--settle', type=float, default=10)
    parser.add_argument('--label', default='candidate')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--profile-render', action='store_true')
    parser.add_argument('--damage', action='store_true', help='Captura regiões realmente apresentadas por XDamage')
    parser.add_argument('--strace', type=Path, help='Perfil separado; altera tempo de abertura e CPU')
    args = parser.parse_args()
    if args.runs < 1 or args.seconds <= 0 or args.settle < 0:
        parser.error('--runs e --seconds devem ser positivos; --settle não pode ser negativo')
    args.binary = args.binary.expanduser().resolve()
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        parser.error('--binary precisa apontar para um arquivo executável')
    helpers = load_helpers()
    args.fixture = helpers.Fixture(args.fixture_port) if args.fixture_port is not None else None
    if args.fixture:
        args.fixture_url = args.fixture.ready['url']
    desktop = helpers.Desktop(args.display)
    try:
        runs = [measure_run(args, helpers, desktop, index) for index in range(1, args.runs + 1)]
        resource_runs = [run for run in runs if 'idle' in run]
        median = statistics.median
        result = {
            'binary_size_bytes': args.binary.stat().st_size,
            'launch_to_mapped_median_ms': median(run['launch_to_mapped_ms'] for run in runs),
            'launch_coldish_ms': runs[0]['launch_to_mapped_ms'],
            'launch_warm_median_ms': median(run['launch_to_mapped_ms'] for run in runs[1:] or runs),
            'state_medians': {
                state: {
                    key: median(run[state][key] for run in resource_runs)
                    for key in ('pss_kib', 'rss_kib', 'threads', 'cpu_percent_one_core', 'wakeups_per_30s')
                }
                for state in ('idle', 'working_conversation', 'large_conversation', 'after_20_open_close')
            },
            'runs': runs,
        }
        encoded = json.dumps(result, indent=2)
        if args.output:
            args.output.write_text(encoded + '\n')
        print(encoded, flush=True)
    finally:
        if args.fixture:
            args.fixture.stop()
        desktop.x.XCloseDisplay.argtypes = [C.c_void_p]
        desktop.x.XCloseDisplay.restype = C.c_int
        desktop.x.XCloseDisplay(desktop.d)


if __name__ == '__main__':
    try:
        main()
    except (AssertionError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
