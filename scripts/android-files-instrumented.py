import json
import os
from pathlib import Path
import selectors
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
TEST = 'br.com.xlinx.habblaud.FixtureFilesInstrumentedTest'


def start_fixture(directory, allow):
    child = subprocess.Popen(
        ['node', '--import', 'tsx', 'scripts/android-smoke.ts', '--port', '0',
         '--allow', allow, '--files', str(directory)],
        cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    )
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            deadline = time.monotonic() + 60
            buffer = b''
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining):
                    raise RuntimeError('Fixture readiness timed out')
                chunk = os.read(child.stdout.fileno(), 4096)
                if not chunk:
                    raise RuntimeError('Fixture exited before readiness')
                buffer += chunk
                if len(buffer) > 65536:
                    raise RuntimeError('Fixture readiness output exceeded limit')
                while b'\n' in buffer:
                    line, buffer = buffer.split(b'\n', 1)
                    ready = json.loads(line)
                    if ready.get('ready') is True:
                        origin = ready['url']
                        if not origin.startswith('http://127.0.0.1:'):
                            raise RuntimeError('Invalid fixture URL')
                        port = int(origin.rsplit(':', 1)[1])
                        if not 1 <= port <= 65535:
                            raise RuntimeError('Invalid fixture port')
                        threading.Thread(target=lambda: child.stdout.read(), daemon=True).start()
                        return child, origin, port
    except BaseException:
        stop_fixture(child)
        raise


def stop_fixture(child):
    child.stdin.close()
    try:
        child.wait(timeout=10)
    except subprocess.TimeoutExpired:
        child.terminate()
        try:
            child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()


def main():
    fixtures = []
    reverses = []
    with tempfile.TemporaryDirectory(prefix='zaplaud-files-') as temporary:
        workspace = Path(temporary) / 'workspace'
        documents = workspace / 'documentos'
        documents.mkdir(parents=True)
        name = f'aceitacao-{uuid.uuid4().hex}.txt'
        (documents / name).write_bytes('Arquivo fictício de aceitação.\n'.encode())
        try:
            for allow in ('terminal,messages,permissions', 'messages,permissions'):
                child, origin, port = start_fixture(workspace, allow)
                fixtures.append((child, origin))
                subprocess.run(['adb', 'reverse', f'tcp:{port}', f'tcp:{port}'], check=True)
                reverses.append(port)
            subprocess.run([
                './gradlew', '--max-workers=4', ':app:connectedDebugAndroidTest',
                '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
                f'-Pandroid.testInstrumentationRunnerArguments.class={TEST}',
                '-Pandroid.testInstrumentationRunnerArguments.fixture=true',
                f'-Pandroid.testInstrumentationRunnerArguments.filesOrigin={fixtures[0][1]}',
                f'-Pandroid.testInstrumentationRunnerArguments.filesDeniedOrigin={fixtures[1][1]}',
                f'-Pandroid.testInstrumentationRunnerArguments.filesName={name}',
            ], cwd=ROOT / 'android', check=True)
            cases = []
            for report in (ROOT / 'android/app/build/outputs/androidTest-results/connected').rglob('*.xml'):
                cases.extend(case for case in ET.parse(report).getroot().iter('testcase')
                             if case.get('classname') == TEST)
            expected = {'navigatesSubfolderAndSavesExactBytesInPublicDownloads',
                        'fixtureWithoutTerminalRejectsFilesWith403'}
            executed = {case.get('name') for case in cases
                        if not any(case.find(tag) is not None for tag in ('skipped', 'failure', 'error'))}
            if not expected <= executed:
                raise RuntimeError(f'Files acceptance tests did not pass: {sorted(expected - executed)}')
            if any(child.poll() is not None for child, _ in fixtures):
                raise RuntimeError('Fixture exited during acceptance tests')
            for theme in ('light', 'dark'):
                subprocess.run([
                    'adb', 'pull',
                    f'/sdcard/Android/data/br.com.xlinx.habblaud/files/files-phone-{theme}.png',
                    f'/tmp/zaplaud-align-kfiles-phone-{theme}.png',
                ], check=True)
                subprocess.run([
                    'adb', 'shell', 'rm',
                    f'/sdcard/Android/data/br.com.xlinx.habblaud/files/files-phone-{theme}.png',
                ], check=True)
            print('Files acceptance: public Downloads bytes and terminal-disabled 403 passed.')
        finally:
            for port in reversed(reverses):
                subprocess.run(['adb', 'reverse', '--remove', f'tcp:{port}'], check=False)
            for child, _ in reversed(fixtures):
                stop_fixture(child)


if __name__ == '__main__':
    main()
