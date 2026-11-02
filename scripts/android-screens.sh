#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
OUT=/tmp/android-screens
TMP=$(mktemp -d /tmp/habblaud-screens.XXXXXX)
PACKAGE=br.com.xlinx.habblaud
SERIAL=
FIXTURE_PID=
READONLY_PID=
EMULATOR_PID=
SIZE=
DENSITY=
FONT=

cleanup() {
    set +e
    if [[ -n "$SERIAL" ]]; then
        if [[ -n "$SIZE" ]]; then "$ADB" -s "$SERIAL" shell wm size "$SIZE"; else "$ADB" -s "$SERIAL" shell wm size reset; fi
        if [[ -n "$DENSITY" ]]; then "$ADB" -s "$SERIAL" shell wm density "$DENSITY"; else "$ADB" -s "$SERIAL" shell wm density reset; fi
        if [[ "$FONT" == null || -z "$FONT" ]]; then "$ADB" -s "$SERIAL" shell settings delete system font_scale; else "$ADB" -s "$SERIAL" shell settings put system font_scale "$FONT"; fi
        "$ADB" -s "$SERIAL" reverse --remove tcp:14748
        "$ADB" -s "$SERIAL" reverse --remove tcp:14749
        "$ADB" -s "$SERIAL" shell rm -f /sdcard/Download/habblaud-exemplo.txt
    fi
    [[ -z "$FIXTURE_PID" ]] || kill "$FIXTURE_PID" 2>/dev/null
    [[ -z "$READONLY_PID" ]] || kill "$READONLY_PID" 2>/dev/null
    [[ -z "$FIXTURE_PID" ]] || wait "$FIXTURE_PID" 2>/dev/null
    [[ -z "$READONLY_PID" ]] || wait "$READONLY_PID" 2>/dev/null
    [[ -z "$EMULATOR_PID" ]] || "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1
    rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

cd "$ROOT"
mkdir -p "$OUT"
for device in $("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device/{print $1}'); do
    if "$ADB" -s "$device" emu avd name | tr -d '\r' | grep -qx habblaud034; then SERIAL=$device; break; fi
done
if [[ -z "$SERIAL" ]]; then
    "$EMULATOR" -avd habblaud034 -no-window -no-audio -no-snapshot-save -no-boot-anim -skin 1080x2340 >"$TMP/emulator.log" 2>&1 &
    EMULATOR_PID=$!
    for _ in $(seq 1 120); do
        for device in $("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device/{print $1}'); do
            if "$ADB" -s "$device" emu avd name | tr -d '\r' | grep -qx habblaud034; then SERIAL=$device; break; fi
        done
        [[ -z "$SERIAL" ]] || break
        kill -0 "$EMULATOR_PID"
        sleep 2
    done
fi
[[ -n "$SERIAL" ]] || { echo 'Emulador habblaud034 não encontrado' >&2; exit 1; }
"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 120); do
    [[ $("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r') == 1 ]] && break
    sleep 2
done
[[ $("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
SIZE=$("$ADB" -s "$SERIAL" shell wm size | awk '/Override size:/{print $3}' | tr -d '\r')
DENSITY=$("$ADB" -s "$SERIAL" shell wm density | awk '/Override density:/{print $3}' | tr -d '\r')
FONT=$("$ADB" -s "$SERIAL" shell settings get system font_scale | tr -d '\r')
"$ADB" -s "$SERIAL" shell wm size 1080x2340
"$ADB" -s "$SERIAL" shell wm density 420
"$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
"$ADB" -s "$SERIAL" reverse tcp:14748 tcp:14748
"$ADB" -s "$SERIAL" reverse tcp:14749 tcp:14749
ANDROID_HOME="$ANDROID_HOME" android/gradlew -p android :app:assembleDebug
"$ADB" -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk

mkfifo "$TMP/control" "$TMP/readonly-control"
exec 3<>"$TMP/control" 4<>"$TMP/readonly-control"
node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions <"$TMP/control" >"$TMP/fixture.log" 2>&1 &
FIXTURE_PID=$!
node --import tsx scripts/android-smoke.ts --port 14749 --allow terminal,messages,permissions --no-session-guard <"$TMP/readonly-control" >"$TMP/readonly.log" 2>&1 &
READONLY_PID=$!
for _ in $(seq 1 60); do
    grep -qs '"ready":true' "$TMP/fixture.log" && grep -qs '"ready":true' "$TMP/readonly.log" && break
    kill -0 "$FIXTURE_PID" "$READONLY_PID"
    sleep 1
done
grep -q '"ready":true' "$TMP/fixture.log"
grep -q '"ready":true' "$TMP/readonly.log"
printf 'permission fixture:analise bash\n' >&3

adb() { "$ADB" -s "$SERIAL" "$@"; }
dump() {
    for _ in $(seq 1 5); do
        adb shell rm -f /sdcard/habblaud-window.xml
        if adb shell uiautomator dump /sdcard/habblaud-window.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/habblaud-window.xml >"$TMP/window.xml" 2>/dev/null && [[ -s "$TMP/window.xml" ]]; then return; fi
        sleep 1
    done
    echo 'Não foi possível ler a árvore da interface.' >&2
    return 1
}
coords() {
    python3 - "$TMP/window.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter('node'):
    if sys.argv[2] in (node.get('text'), node.get('content-desc')):
        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                break
else:
    sys.exit(1)
PY
}
wait_text() {
    local label=$1
    for _ in $(seq 1 20); do
        dump
        if coords "$label" >/dev/null; then return; fi
        sleep 1
    done
    echo "Elemento não encontrado: $label" >&2
    cat "$TMP/window.xml" >&2
    return 1
}
tap() { wait_text "$1"; local xy; xy=$(coords "$1"); adb shell input tap $xy; }
shot() { sleep 1; adb exec-out screencap -p >"$OUT/$1.png"; echo "$OUT/$1.png"; }
reset_app() { adb shell pm clear "$PACKAGE" >/dev/null; adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null; wait_text 'URL do servidor'; }
fill_login() {
    tap 'URL do servidor'; adb shell input text "$1"; adb shell input keyevent 4
    tap 'Usuário'; adb shell input text android-test; adb shell input keyevent 4
    tap 'Senha'; adb shell input text "$2"; adb shell input keyevent 4
    tap 'Entrar'
}

reset_app
shot conexao-vazia
tap 'URL do servidor'; adb shell input text http://100.64.0.1:14748; adb shell input keyevent 4
tap 'Revisar aviso de segurança'
shot consentimento-http
reset_app
fill_login http://127.0.0.1:14748 senha-incorreta
wait_text 'Login inválido ou sessão expirada.'
shot conexao-erro
reset_app
fill_login http://127.0.0.1:14748 android-test
wait_text 'Abertas'
wait_text 'Aprovação pendente'
shot sessoes-abertas
tap Recentes
shot sessoes-recentes
tap 'Buscar por título, projeto ou agente'; adb shell input text busca-impossivel; adb shell input keyevent 4
wait_text 'Nenhuma sessão encontrada.'
shot busca-vazia
adb shell input tap 300 450; adb shell input keyevent KEYCODE_MOVE_END; adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A; adb shell input keyevent KEYCODE_DEL; adb shell input keyevent 4
tap Abertas
tap 'projeto · parado'
wait_text 'Bash(echo fixture)'
shot conversa-fim
for _ in $(seq 1 18); do adb shell input swipe 500 500 500 1850 150; done
shot conversa-topo
dump
if coords 'Novas entradas' >/dev/null; then tap 'Novas entradas'; else tap 'Ir para o fim'; fi
wait_text 'Bash(echo fixture)'
tap 'Bash(echo fixture)'
adb shell input swipe 500 1750 500 900 400
shot ferramenta-expandida
tap 'Mensagem'
adb shell input text 'Primeira%slinha%sficticia'; adb shell input keyevent KEYCODE_ENTER; adb shell input text 'Segunda%slinha%sficticia'
shot composer-teclado
adb shell input keyevent 4
printf 'Documento fictício para o teste visual.\n' >"$TMP/habblaud-exemplo.txt"
adb push "$TMP/habblaud-exemplo.txt" /sdcard/Download/habblaud-exemplo.txt >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/habblaud-exemplo.txt >/dev/null
tap 'Anexar arquivos (até 5)'
wait_text 'habblaud-exemplo.txt'
tap 'habblaud-exemplo.txt'
wait_text 'habblaud-exemplo.txt'
shot anexos-selecionados
tap Enviar
wait_text 'Aceita e na fila; ainda não entregue.'
shot estado-envio
printf 'permission fixture:claude bash\n' >&3
wait_text 'Aprovação pendente'
tap 'Aprovação pendente'
wait_text 'Permitir'
shot aprovacao-aberta
adb shell input keyevent 4
adb shell settings put system font_scale 1.3
sleep 2
shot conversa-fonte-1-3
adb shell settings put system font_scale 1.0
reset_app
fill_login http://127.0.0.1:14749 android-test
wait_text Abertas
tap 'projeto · parado'
wait_text 'O servidor precisa ser atualizado para enviar mensagens.'
shot modo-leitura
