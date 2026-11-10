#!/usr/bin/env python3
"""Confere a árvore AT-SPI real numa sessão D-Bus privada, sem leitor de tela instalado."""
import argparse
import ctypes as C
import ctypes.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True)
    parser.add_argument('--display', default='localhost:93')
    parser.add_argument('--inside-bus', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.inside_bus:
        with tempfile.TemporaryDirectory(prefix='zaplaud-atspi-session-') as home:
            env = dict(os.environ, HOME=home, XDG_CONFIG_HOME=home + '/config',
                       XDG_DATA_HOME=home + '/data', XDG_CACHE_HOME=home + '/cache')
            return subprocess.call(['dbus-run-session', '--', sys.executable, __file__,
                                    '--binary', args.binary, '--display', args.display, '--inside-bus'], env=env)
    library = ctypes.util.find_library('atspi')
    if not library:
        raise RuntimeError('libatspi indisponível; acessibilidade não verificada')
    atspi = C.CDLL(library)
    atspi.atspi_init.restype = C.c_int
    atspi.atspi_get_desktop.argtypes = [C.c_int]
    atspi.atspi_get_desktop.restype = C.c_void_p
    atspi.atspi_accessible_get_child_count.argtypes = [C.c_void_p, C.c_void_p]
    atspi.atspi_accessible_get_child_count.restype = C.c_int
    atspi.atspi_accessible_get_child_at_index.argtypes = [C.c_void_p, C.c_int, C.c_void_p]
    atspi.atspi_accessible_get_child_at_index.restype = C.c_void_p
    atspi.atspi_accessible_get_name.argtypes = [C.c_void_p, C.c_void_p]
    atspi.atspi_accessible_get_name.restype = C.c_void_p
    glib = C.CDLL(ctypes.util.find_library('glib-2.0'))
    glib.g_free.argtypes = [C.c_void_p]
    objects = C.CDLL(ctypes.util.find_library('gobject-2.0'))
    objects.g_object_unref.argtypes = [C.c_void_p]
    env = dict(os.environ, DISPLAY=args.display, ZAPLAUD_KEYRING='memory',
               SLINT_BACKEND='winit-software', WINIT_UNIX_BACKEND='x11')
    subprocess.run(['gdbus', 'call', '--session', '--dest', 'org.a11y.Bus',
                    '--object-path', '/org/a11y/bus', '--method', 'org.freedesktop.DBus.Properties.Set',
                    'org.a11y.Status', 'IsEnabled', '<true>'], check=True)
    # O registro do Arch usa ativação systemd; sessões D-Bus privadas não têm user manager.
    registry = subprocess.Popen(['/usr/lib/at-spi2-registryd'], env=env, stdout=subprocess.DEVNULL)
    if atspi.atspi_init() != 0:
        registry.terminate()
        raise RuntimeError('Falha ao iniciar AT-SPI')
    with tempfile.TemporaryDirectory(prefix='zaplaud-accessibility-') as directory:
        env['ZAPLAUD_DATA_DIR'] = directory
        runtime = Path(directory) / 'runtime'
        runtime.mkdir(mode=0o700)
        env['XDG_RUNTIME_DIR'] = str(runtime)
        with open('/tmp/zaplaud-rs-polish-accessibility.log', 'w') as log:
            app = subprocess.Popen([str(Path(args.binary).resolve())], env=env, stdout=log, stderr=log)
            try:
                names = []
                def walk(node, depth=0):
                    if not node or depth > 15:
                        return
                    name = atspi.atspi_accessible_get_name(node, None)
                    if name:
                        text = C.string_at(name).decode('utf-8', errors='replace')
                        glib.g_free(name)
                        if text:
                            names.append(text)
                    for index in range(max(0, atspi.atspi_accessible_get_child_count(node, None))):
                        child = atspi.atspi_accessible_get_child_at_index(node, index, None)
                        if child:
                            walk(child, depth + 1)
                            objects.g_object_unref(child)
                deadline = time.monotonic() + 20
                while time.monotonic() < deadline:
                    if app.poll() is not None:
                        raise RuntimeError('Aplicativo encerrou antes da árvore AT-SPI')
                    names.clear()
                    walk(atspi.atspi_get_desktop(0))
                    if all(label in names for label in ['Zaplaud', 'Endereço do servidor', 'Usuário', 'Senha', 'Entrar']):
                        break
                    time.sleep(0.5)
                else:
                    raise AssertionError(f'Árvore de login incompleta: {names!r}')
                print(json.dumps({'verified': True, 'names': names}, ensure_ascii=False))
            finally:
                app.terminate()
                app.wait(timeout=10)
                registry.terminate()
                registry.wait(timeout=10)
    return 0


if __name__ == '__main__':
    sys.exit(main())
