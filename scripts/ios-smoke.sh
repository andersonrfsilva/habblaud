#!/usr/bin/env bash
set -euo pipefail
[[ $(uname -s) == Darwin ]] || { echo 'iOS smoke requires macOS and Xcode.' >&2; exit 1; }
command -v xcodebuild >/dev/null || { echo 'Xcode is not installed or selected.' >&2; exit 1; }
cd "$(dirname "$0")/.."
python3 - <<'PY'
import json
import os
from pathlib import Path
import plistlib
import selectors
import signal
import subprocess
import time

root = Path.cwd()
out = root / 'ios/build/smoke'
out.mkdir(parents=True, exist_ok=True)
fixture = None
simulator = None
report = {'passed': False}

def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout

def receive(timeout=60):
    selector = selectors.DefaultSelector()
    selector.register(fixture.stdout, selectors.EVENT_READ)
    try:
        if not selector.select(timeout):
            raise RuntimeError('Fixture output timed out')
        line = fixture.stdout.readline()
        if not line:
            raise RuntimeError('Fixture exited unexpectedly')
        return json.loads(line)
    finally:
        selector.close()

def interrupt(signum, frame):
    raise RuntimeError(f'Interrupted by signal {signum}')

for sig in (signal.SIGINT, signal.SIGTERM):
    signal.signal(sig, interrupt)
try:
    devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '-j'))['devices']
    candidates = [(runtime, device) for runtime, group in devices.items() if '.iOS-' in runtime
                  for device in group if device.get('isAvailable') and device['name'].startswith('iPad')]
    if not candidates:
        raise RuntimeError('Install an iOS simulator runtime with an available iPad in Xcode.')
    runtime, template = sorted(candidates, reverse=True, key=lambda pair: pair[0])[0]
    # A fresh isolated device avoids erasing the user's app, Keychain, or draft state.
    simulator = run('xcrun', 'simctl', 'create', 'Zaplaud isolated smoke', template['deviceTypeIdentifier'], runtime).strip()
    run('xcrun', 'simctl', 'boot', simulator)
    run('xcrun', 'simctl', 'bootstatus', simulator, '-b')
    with (out / 'fixture.stderr.log').open('w') as errors:
        fixture = subprocess.Popen(['npx', '--no-install', 'tsx', 'scripts/android-smoke.ts', '--port', '0',
                                    '--allow', 'terminal,permissions,messages'],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors,
                                   text=True, start_new_session=True)
        ready = receive()
        if ready.get('ready') is not True:
            raise RuntimeError('Fixture did not report readiness')
        from urllib.parse import urlparse
        for key in ('url', 'localUrl'):
            url = urlparse(ready[key])
            if url.scheme != 'http' or url.hostname != '127.0.0.1' or not url.port:
                raise RuntimeError(f'Invalid isolated fixture URL: {key}')
        assert urlparse(ready['url']).port != urlparse(ready['localUrl']).port
        report['fixture'] = {'url': ready['url'], 'localUrl': ready['localUrl']}
        destination = f'platform=iOS Simulator,id={simulator}'
        subprocess.run(['xcodebuild', '-project', 'ios/Zaplaud.xcodeproj', '-scheme', 'Zaplaud',
                        '-configuration', 'Debug', '-destination', destination,
                        '-derivedDataPath', str(out / 'DerivedData'), 'ARCHS=arm64', 'ONLY_ACTIVE_ARCH=YES',
                        'CODE_SIGNING_ALLOWED=NO', 'CODE_SIGNING_REQUIRED=NO', 'build-for-testing'], check=True)
        files = list((out / 'DerivedData/Build/Products').glob('*.xctestrun'))
        if len(files) != 1:
            raise RuntimeError('Expected exactly one generated Xcode test run configuration')
        config = plistlib.loads(files[0].read_bytes())
        configurations = config.get('TestConfigurations', [])
        targets = [target for configuration in configurations for target in configuration.get('TestTargets', [])]
        if not configurations:
            targets = [value for key, value in config.items() if not key.startswith('__') and isinstance(value, dict)]
        if not targets:
            raise RuntimeError('Generated Xcode configuration contains no UI test target')
        for target in targets:
            target.setdefault('EnvironmentVariables', {})['ZAPLAUD_FIXTURE_URL'] = ready['url']
            target['EnvironmentVariables']['ZAPLAUD_FIXTURE_LOCAL_URL'] = ready['localUrl']
        test_run = files[0]
        test_run.write_bytes(plistlib.dumps(config))
        results = out / f'Results-{int(time.time())}.xcresult'
        test = subprocess.run(['xcodebuild', 'test-without-building', '-xctestrun', str(test_run),
                               '-destination', destination, '-resultBundlePath', str(results)])
        run('xcrun', 'simctl', 'io', simulator, 'screenshot', str(out / 'final.png'))
        subprocess.run(['xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(results),
                        '--output-path', str(out / 'attachments')], check=True)
        subprocess.run(['xcrun', 'xcresulttool', 'export', 'diagnostics', '--path', str(results),
                        '--output-path', str(out / 'diagnostics')], check=True)
        test.check_returncode()
        fixture.stdin.write('stats\n')
        fixture.stdin.flush()
        stats = receive()
        report['stats'] = stats
        requests = stats.get('messageRequests', [])
        matching = [request for request in requests if request.get('text') == 'ios-smoke-message']
        assert len(matching) == 1, f'Expected exactly one real message POST, got {matching}'
        assert matching[0].get('expectedSessionId') == '00000000-0000-4000-8000-000000000001'
        assert matching[0]['status'] == 201, matching[0]
        assert not any(request.get('text') == 'ios-smoke-unsent-draft' for request in requests)
        assert stats.get('posts', {}).get('/api/messages') == 1, stats
        report['results'] = str(results)
        report['passed'] = True
finally:
    if fixture is not None:
        if fixture.poll() is None:
            os.killpg(fixture.pid, signal.SIGTERM)
            try:
                fixture.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(fixture.pid, signal.SIGKILL)
                fixture.wait()
    if simulator:
        subprocess.run(['xcrun', 'simctl', 'shutdown', simulator], check=False)
        subprocess.run(['xcrun', 'simctl', 'delete', simulator], check=False)
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'Real simulator smoke passed; evidence: {out}')
PY
