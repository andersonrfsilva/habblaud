#!/usr/bin/env bash
set -euo pipefail
[[ $(uname -s) == Darwin && $(uname -m) == arm64 ]] || { echo 'Native iOS tests require Apple Silicon macOS and Xcode.' >&2; exit 1; }
cd "$(dirname "$0")/.."
android/gradlew -p android :shared:linkDebugTestIosSimulatorArm64
python3 - <<'PY'
import json
import os
import plistlib
from pathlib import Path
import re
import shutil
import signal
import subprocess

root = Path.cwd()
out = root / 'ios/build/native-tests'
out.mkdir(parents=True, exist_ok=True)
binary = root / 'android/shared/build/bin/iosSimulatorArm64/debugTest/test.kexe'
bundle_id = 'br.com.xlinx.habblaud.native-tests'
app = out / 'ZaplaudNativeTests.app'
simulator = None
report = {'passed': False, 'binary': str(binary)}

def run(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout

def interrupt(signum, frame):
    raise RuntimeError(f'Interrupted by signal {signum}')

for sig in (signal.SIGINT, signal.SIGTERM):
    signal.signal(sig, interrupt)
try:
    if not binary.is_file():
        raise RuntimeError(f'Generated native test executable is missing: {binary}')
    app.mkdir(exist_ok=True)
    executable = app / 'ZaplaudNativeTests'
    shutil.copy2(binary, executable)
    executable.chmod(0o755)
    (app / 'Info.plist').write_bytes(plistlib.dumps({
        'CFBundleIdentifier': bundle_id,
        'CFBundleExecutable': executable.name,
        'CFBundleName': 'ZaplaudNativeTests',
        'CFBundlePackageType': 'APPL',
        'CFBundleVersion': '1',
        'CFBundleShortVersionString': '1.0',
        'CFBundleSupportedPlatforms': ['iPhoneSimulator'],
        'MinimumOSVersion': '14.0',
        'LSRequiresIPhoneOS': True,
        'UIDeviceFamily': [2],
    }))
    entitlements = out / 'entitlements.plist'
    entitlements.write_bytes(plistlib.dumps({
        'application-identifier': bundle_id,
        'keychain-access-groups': [bundle_id],
    }))
    run('codesign', '--force', '--sign', '-', '--entitlements', str(entitlements), str(app))
    devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '-j'))['devices']
    candidates = [(runtime, device) for runtime, group in devices.items() if '.iOS-' in runtime
                  for device in group if device.get('isAvailable') and device['name'].startswith('iPad')]
    if not candidates:
        raise RuntimeError('Install an available iPad simulator runtime in Xcode.')
    runtime, template = sorted(candidates, reverse=True, key=lambda pair: pair[0])[0]
    # An app identity supplies real simulator Keychain and LaunchServices access; no service is mocked.
    simulator = run('xcrun', 'simctl', 'create', 'Zaplaud isolated native tests', template['deviceTypeIdentifier'], runtime).strip()
    report['simulator'] = simulator
    run('xcrun', 'simctl', 'boot', simulator)
    run('xcrun', 'simctl', 'bootstatus', simulator, '-b')
    run('xcrun', 'simctl', 'install', simulator, str(app))
    installed_app = run('xcrun', 'simctl', 'get_app_container', simulator, bundle_id, 'app').strip()
    data_container = run('xcrun', 'simctl', 'get_app_container', simulator, bundle_id, 'data').strip()
    environment = dict(os.environ, SIMCTL_CHILD_HOME=data_container, SIMCTL_CHILD_CFFIXED_USER_HOME=data_container)
    report['installedExecutable'] = str(Path(installed_app) / executable.name)
    log = out / 'tests.log'
    with log.open('w') as console:
        # Unlike Gradle's --standalone spawn, a booted device supplies securityd and LaunchServices.
        # Spawn the CLI test runner directly: it has no UIApplicationMain for SpringBoard to launch.
        result = subprocess.run(['xcrun', 'simctl', 'spawn', simulator,
                                 report['installedExecutable'], '-tr-logger=GTEST'],
                                stdout=console, stderr=subprocess.STDOUT, env=environment, timeout=600)
    text = log.read_text()
    print(text, end='')
    report['testExitCode'] = result.returncode
    # simctl success alone does not prove that the native runner completed or its assertions passed.
    started = re.findall(r'\[==========\] Running (\d+) tests? from ', text)
    finished = re.findall(r'\[==========\] (\d+) tests? from .* ran\.', text)
    passed = re.findall(r'\[  PASSED  \] (\d+) tests?\.', text)
    if result.returncode != 0:
        raise RuntimeError(f'Native test process failed with exit code {result.returncode}; see {log}')
    if len(started) != 1 or len(finished) != 1 or len(passed) != 1:
        raise RuntimeError(f'Native runner did not emit one complete GTEST summary; see {log}')
    count = int(started[0])
    if count < 65 or count != int(finished[0]) or count != int(passed[0]) or '[  FAILED  ]' in text or '[  SKIPPED ]' in text:
        raise RuntimeError(f'Not every native test passed (minimum 65, no skips); see {log}')
    report['tests'] = count
    report['passed'] = True
finally:
    if simulator:
        subprocess.run(['xcrun', 'simctl', 'shutdown', simulator], check=False)
        subprocess.run(['xcrun', 'simctl', 'delete', simulator], check=False)
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(f'App-hosted native tests passed; evidence: {out}')
PY
