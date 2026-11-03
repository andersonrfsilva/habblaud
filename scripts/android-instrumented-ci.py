import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET


class Interrupted(Exception):
    pass


def interrupt(signum, frame):
    raise Interrupted(f'Interrupted by signal {signum}')


def drain(stream):
    while stream.read(4096):
        pass


def main():
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, interrupt)

    fixture = None
    gradle = None
    reverses = []
    try:
        fixture = subprocess.Popen(
            ['npx', 'tsx', 'scripts/android-smoke.ts', '--port', '14748',
             '--allow', 'terminal,permissions,messages'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        selector = selectors.DefaultSelector()
        selector.register(fixture.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + 60
        buffer = b''
        port = None
        while port is None:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                raise RuntimeError('Fixture readiness timed out')
            chunk = os.read(fixture.stdout.fileno(), 4096)
            if not chunk:
                raise RuntimeError('Fixture exited before readiness')
            buffer += chunk
            if len(buffer) > 65536:
                raise RuntimeError('Fixture readiness output exceeded limit')
            while b'\n' in buffer:
                line, buffer = buffer.split(b'\n', 1)
                try:
                    ready = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    continue
                if not isinstance(ready, dict) or ready.get('ready') is not True:
                    continue
                if ready.get('url') != 'http://127.0.0.1:14748':
                    raise RuntimeError('Invalid fixture URL')
                local_url = ready.get('localUrl')
                match = re.fullmatch(r'http://127\.0\.0\.1:([0-9]+)', local_url) if isinstance(local_url, str) else None
                if not match or not 1 <= int(match.group(1)) <= 65535:
                    raise RuntimeError('Invalid fixture local URL')
                port = int(match.group(1))
                break
        selector.close()
        threading.Thread(target=drain, args=(fixture.stdout,), daemon=True).start()
        print(f'Fixture ready; local port: {port}', flush=True)
        if fixture.poll() is not None:
            raise RuntimeError('Fixture exited after readiness')
        for value in dict.fromkeys((14748, port)):
            subprocess.run(['adb', 'reverse', f'tcp:{value}', f'tcp:{value}'], check=True)
            reverses.append(value)
        print('Reverse mappings established:', flush=True)
        subprocess.run(['adb', 'reverse', '--list'], check=True)
        gradle = subprocess.Popen([
            './gradlew', ':app:connectedDebugAndroidTest',
            '-Pandroid.testInstrumentationRunnerArguments.fixture=true',
            f'-Pandroid.testInstrumentationRunnerArguments.fixtureLocalPort={port}',
        ], cwd='android', start_new_session=True)
        while gradle.poll() is None:
            if fixture.poll() is not None:
                raise RuntimeError('Fixture exited during tests')
            time.sleep(0.5)
        if gradle.returncode:
            return gradle.returncode
        if fixture.poll() is not None:
            raise RuntimeError('Fixture exited during tests')
        files = list(Path('android/app/build/outputs/androidTest-results/connected').rglob('*.xml'))
        suites = []
        for file in files:
            root = ET.parse(file).getroot()
            if root.tag not in ('testsuite', 'testsuites'):
                raise RuntimeError('Unexpected Android test XML format')
            suites.extend([root] if root.tag == 'testsuite' else root.findall('.//testsuite'))
        tests = sum(int(s.attrib['tests']) for s in suites)
        failures = sum(int(s.attrib['failures']) for s in suites)
        errors = sum(int(s.attrib['errors']) for s in suites)
        has_fixture = any('FixtureUiInstrumentedTest' in case.get('classname', '')
                          for suite in suites for case in suite.iter('testcase'))
        if not suites or tests <= 0 or failures or errors or not has_fixture:
            raise RuntimeError('Android test XML does not meet acceptance criteria')
        print(f'Android test results: {tests} tests, {failures} failures, {errors} errors')
        return 0
    finally:
        if gradle is not None and gradle.poll() is None:
            os.killpg(gradle.pid, signal.SIGTERM)
            gradle.wait()
        for value in reversed(reverses):
            subprocess.run(['adb', 'reverse', '--remove', f'tcp:{value}'], check=False)
        if fixture is not None:
            fixture.stdin.close()
            try:
                fixture.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(fixture.pid, signal.SIGTERM)
                try:
                    fixture.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(fixture.pid, signal.SIGKILL)
                    fixture.wait()


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (Interrupted, RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, KeyError, ValueError) as error:
        print(f'Instrumented CI failed: {error}', file=sys.stderr)
        sys.exit(1)
