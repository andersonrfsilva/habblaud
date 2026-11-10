#!/usr/bin/env python3
"""Certifica nomes e papéis AT-SPI numa sessão D-Bus privada, inclusive em CI headless."""
import argparse
import ctypes as C
import ctypes.util
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

spec = importlib.util.spec_from_file_location('keyboard', Path(__file__).with_name('keyboard-smoke.py'))
keyboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(keyboard)


class Tree:
    def __init__(self):
        library = ctypes.util.find_library('atspi')
        if not library:
            raise RuntimeError('libatspi indisponível; acessibilidade não verificada')
        self.atspi = C.CDLL(library)
        signatures = {
            'atspi_init': ([], C.c_int),
            'atspi_get_desktop': ([C.c_int], C.c_void_p),
            'atspi_accessible_get_child_count': ([C.c_void_p, C.c_void_p], C.c_int),
            'atspi_accessible_get_child_at_index': ([C.c_void_p, C.c_int, C.c_void_p], C.c_void_p),
            'atspi_accessible_get_name': ([C.c_void_p, C.c_void_p], C.c_void_p),
            'atspi_accessible_get_role_name': ([C.c_void_p, C.c_void_p], C.c_void_p),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.atspi, name)
            function.argtypes = arguments
            function.restype = result
        self.glib = C.CDLL(ctypes.util.find_library('glib-2.0'))
        self.glib.g_free.argtypes = [C.c_void_p]
        self.objects = C.CDLL(ctypes.util.find_library('gobject-2.0'))
        self.objects.g_object_unref.argtypes = [C.c_void_p]
        assert self.atspi.atspi_init() == 0, 'Falha ao iniciar AT-SPI'

    def snapshot(self):
        result = []
        def string(function, node):
            pointer = function(node, None)
            if not pointer:
                return ''
            text = C.string_at(pointer).decode('utf-8', errors='replace')
            self.glib.g_free(pointer)
            return text
        def walk(node, depth=0):
            if not node or depth > 30:
                return
            result.append({'role': string(self.atspi.atspi_accessible_get_role_name, node),
                           'name': string(self.atspi.atspi_accessible_get_name, node)})
            for index in range(max(0, self.atspi.atspi_accessible_get_child_count(node, None))):
                child = self.atspi.atspi_accessible_get_child_at_index(node, index, None)
                if child:
                    walk(child, depth + 1)
                    self.objects.g_object_unref(child)
        walk(self.atspi.atspi_get_desktop(0))
        return result

    def require(self, predicate, label):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            nodes = self.snapshot()
            if predicate(nodes):
                return nodes
            time.sleep(.25)
        raise AssertionError(f'{label}: {nodes!r}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--display', default='localhost:89')
    parser.add_argument('--port', type=int, default=14789)
    parser.add_argument('--inside-bus', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.inside_bus:
        with tempfile.TemporaryDirectory(prefix='zaplaud-atspi-session-') as home:
            env = dict(os.environ, HOME=home, XDG_CONFIG_HOME=home + '/config',
                       XDG_DATA_HOME=home + '/data', XDG_CACHE_HOME=home + '/cache')
            return subprocess.call(['dbus-run-session', '--', sys.executable, __file__,
                                    '--binary', str(Path(args.binary).resolve()), '--display', args.display,
                                    '--port', str(args.port), '--inside-bus'], env=env)
    subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.a11y.Bus',
                    '--object-path', '/org/a11y/bus', '--method', 'org.freedesktop.DBus.Properties.Set',
                    'org.a11y.Status', 'IsEnabled', '<true>'], check=True)
    candidates = ['/usr/lib/at-spi2-registryd', '/usr/libexec/at-spi2-registryd', '/usr/lib/at-spi2-core/at-spi2-registryd']
    registry_path = next((path for path in candidates if Path(path).exists()), None)
    if not registry_path:
        raise RuntimeError('at-spi2-registryd indisponível; instale at-spi2-core no runner')
    registry = subprocess.Popen([registry_path], env=dict(os.environ, DISPLAY=args.display), stdout=subprocess.DEVNULL)
    session = None
    result = {}
    try:
        tree = Tree()
        session = keyboard.Session(args.binary, args.display, args.port, 'light')
        def labels(*required):
            return lambda nodes: all(any(node['name'] == label for node in nodes) for label in required)
        result['login'] = tree.require(labels('Zaplaud', 'Endereço do servidor', 'Usuário', 'Senha', 'Entrar'), 'Login sem nomes')
        origin = f'http://127.0.0.1:{args.port}'
        key = json.dumps({'origin': origin, 'provider': 'claude', 'account': '.claude',
                          'sessionId': '00000000-0000-4000-8000-000000000001'}, separators=(',', ':'))
        state_path = session.data / 'state/data/local_state.json'
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state = json.loads(state_path.read_text()) if state_path.exists() else {}
        state['seen_replies_' + hashlib.sha256(origin.encode()).hexdigest()[:16]] = {hashlib.sha256(key.encode()).hexdigest(): 0}
        state_path.write_text(json.dumps(state))
        session.login()
        result['list'] = tree.require(lambda nodes: any(node['role'] == 'list item' and 'parado' in node['name'] and 'não lida' in node['name'] for node in nodes), 'Linha sem título/estado/não lidas')
        d = session.desktop
        d.click(150, 250)
        result['conversation'] = tree.require(lambda nodes: any('Resposta OMP fictícia' in node['name'] for node in nodes) and any(node['role'] == 'entry' and node['name'] == 'Mensagem' for node in nodes) and labels('Enviar', 'Anexar arquivos (até 5)')(nodes), 'Bolhas/compositor sem texto')
        d.click(650, 32)
        result['info'] = tree.require(lambda nodes: any(node['role'] == 'panel' and node['name'] for node in nodes) and any('detalhes' in node['name'].lower() for node in nodes), 'Informações sem título/controles')
        d.key('Escape')
        d.click(1072, 32)
        d.click(990, 280)
        result['aside'] = tree.require(lambda nodes: any(node['role'] == 'panel' and node['name'] == 'Bate-papo paralelo' for node in nodes) and any(node['role'] == 'entry' and 'Pergunta' in node['name'] for node in nodes), 'Aside sem título/campo')
        d.key('Escape')
        keyboard.command(session.fixture, 'permission fixture:omp bash')
        time.sleep(.5)
        d.click(1024, 32)
        result['permission'] = tree.require(lambda nodes: any('echo fixture' in node['name'] for node in nodes) and any('Permitir' in node['name'] for node in nodes), 'Permissão sem comando/controles')
        d.key('Escape')
        d.click(328, 36)
        d.click(375, 88)
        result['settings'] = tree.require(lambda nodes: any(node['role'] == 'radio button' and node['name'] for node in nodes) and any('Configurações' in node['name'] for node in nodes), 'Configurações sem controles')
        keyboard.scroll_roles(d)
        d.click(170, 248)
        result['role-dialog'] = tree.require(lambda nodes: any(node['role'] == 'panel' and 'Task' in node['name'] for node in nodes) and any(node['role'] == 'entry' and 'provedor/modelo' in node['name'] for node in nodes), 'Papel sem título/campo de modelo')
        d.key('Escape')
        d.key('Escape')
        d.key('Escape')
        d.click(316, 717)
        result['cast'] = tree.require(labels('Escolha um contato', 'Novo', 'Cancelar'), 'Elenco sem título/controles')
        path = Path('/tmp/zaplaud-rs-certify-accessibility.json')
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'passed': list(result), 'evidence': str(path)}))
    finally:
        if session:
            session.close()
        registry.terminate()
        registry.wait(timeout=10)
    return 0


if __name__ == '__main__':
    sys.exit(main())
