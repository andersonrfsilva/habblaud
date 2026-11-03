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
NIGHT=
TEST_A=habblaud-exemplo.txt
TEST_B="habblaud-${TMP##*.}-b.txt"

cleanup() {
    local status=$?
    set +e
    if [[ -n "$SERIAL" ]]; then
        if [[ -n "$SIZE" ]]; then "$ADB" -s "$SERIAL" shell wm size "$SIZE"; else "$ADB" -s "$SERIAL" shell wm size reset; fi
        if [[ -n "$DENSITY" ]]; then "$ADB" -s "$SERIAL" shell wm density "$DENSITY"; else "$ADB" -s "$SERIAL" shell wm density reset; fi
        if [[ "$FONT" == null || -z "$FONT" ]]; then "$ADB" -s "$SERIAL" shell settings delete system font_scale; else "$ADB" -s "$SERIAL" shell settings put system font_scale "$FONT"; fi
        if [[ -n "$NIGHT" ]]; then "$ADB" -s "$SERIAL" shell cmd uimode night "$NIGHT"; fi
        "$ADB" -s "$SERIAL" reverse --remove tcp:14748
        "$ADB" -s "$SERIAL" reverse --remove tcp:14749
        "$ADB" -s "$SERIAL" shell rm -f "/sdcard/Download/$TEST_A" "/sdcard/Download/$TEST_B"
    fi
    if (( status != 0 )) && [[ -n "$FIXTURE_PID" ]] && kill -0 "$FIXTURE_PID" 2>/dev/null; then
        printf 'stats\n' >&3
        sleep 1
        python3 - "$TMP/fixture.log" <<'PY' >&2
import json, pathlib, sys
lines = pathlib.Path(sys.argv[1]).read_text().splitlines()
ready = next((json.loads(line) for line in lines if line.startswith('{"ready":true')), None)
if ready:
    for key in ('stagingDir', 'deliveredDir'):
        root = pathlib.Path(ready[key])
        files = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()} if root.exists() else {}
        print(f'{key}: {files}')
PY
    fi
    [[ -z "$FIXTURE_PID" ]] || kill "$FIXTURE_PID" 2>/dev/null
    [[ -z "$READONLY_PID" ]] || kill "$READONLY_PID" 2>/dev/null
    [[ -z "$FIXTURE_PID" ]] || wait "$FIXTURE_PID" 2>/dev/null
    [[ -z "$READONLY_PID" ]] || wait "$READONLY_PID" 2>/dev/null
    if (( status != 0 )); then
        [[ ! -f "$TMP/fixture.log" ]] || cat "$TMP/fixture.log" >&2
        [[ ! -f "$TMP/readonly.log" ]] || cat "$TMP/readonly.log" >&2
    fi
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
NIGHT=$("$ADB" -s "$SERIAL" shell cmd uimode night | awk '{print $3}' | tr -d '\r')
"$ADB" -s "$SERIAL" shell wm size 1080x2340
"$ADB" -s "$SERIAL" shell wm density 420
"$ADB" -s "$SERIAL" shell settings put system font_scale 1.0
"$ADB" -s "$SERIAL" reverse tcp:14748 tcp:14748
"$ADB" -s "$SERIAL" reverse tcp:14749 tcp:14749
ANDROID_HOME="$ANDROID_HOME" android/gradlew -p android :app:assembleDebug
"$ADB" -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk

mkfifo "$TMP/control" "$TMP/readonly-control"
exec 3<>"$TMP/control" 4<>"$TMP/readonly-control"

adb() { "$ADB" -s "$SERIAL" "$@"; }
dump() {
    for _ in $(seq 1 5); do
        adb shell rm -f /sdcard/habblaud-window.xml
        if adb shell uiautomator dump /sdcard/habblaud-window.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/habblaud-window.xml >"$TMP/window.xml" 2>/dev/null && grep -q '^<?xml' "$TMP/window.xml"; then return; fi
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
enter_text() {
    tap "$1"
    for _ in $(seq 1 20); do
        dump
        if python3 - "$TMP/window.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
raise SystemExit(not any(node.get('class') == 'android.widget.EditText' and node.get('focused') == 'true' for node in root.iter('node')))
PY
        then break; fi
        sleep 1
    done
    sleep 2
    adb shell input text "$2"
    adb shell input keyevent 4
}
shot() { sleep 1; adb exec-out screencap -p >"$OUT/$1-$THEME.png"; echo "$OUT/$1-$THEME.png"; }
reset_app() { adb shell pm clear "$PACKAGE" >/dev/null; adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null; wait_text 'URL do servidor'; }
fill_login() {
    enter_text 'URL do servidor' "$1"
    enter_text 'Usuário' android-test
    enter_text 'Senha' "$2"
    tap 'Entrar'
}
fixture_check() {
    local expected_messages=$1 expected_uploads=$2 expected_files=$3
    for _ in $(seq 1 30); do
        printf 'stats\n' >&3
        sleep 1
        if python3 - "$TMP/fixture.log" "$TMP" "$expected_messages" "$expected_uploads" "$expected_files" "$TEST_A" "$TEST_B" <<'PY'
import json, pathlib, sys
log, temporary, messages, uploads, expected_files, first, second = sys.argv[1:]
lines = pathlib.Path(log).read_text().splitlines()
records = [json.loads(line) for line in lines if line.startswith('{')]
ready = next(item for item in records if item.get('ready'))
stats = next((item for item in reversed(records) if 'posts' in item), {})
posts = stats.get('posts', {})
if posts.get('/api/messages', 0) != int(messages) or posts.get('/api/messages/attachments', 0) != int(uploads):
    raise SystemExit(1)
requests = stats.get('messageRequests', [])
expected_requests = [
    ('Primeira linha ficticia\nSegunda linha ficticia', 0),
    ('caso-a\nsegunda-linha', 1),
    ('caso-b\nsegunda-linha', 0),
    ('caso-c\nsegunda-linha', 1),
][:int(messages)]
if len(requests) != len(expected_requests) or any(
    item.get('status') != 201 or item.get('text') != text or item.get('attachments') != count
    or item.get('expectedSessionId') != '00000000-0000-4000-8000-000000000001'
    for item, (text, count) in zip(requests, expected_requests)
):
    raise SystemExit(1)
delivered = pathlib.Path(ready['deliveredDir'])
staging = pathlib.Path(ready['stagingDir'])
actual = {p.name: p.read_bytes() for p in delivered.rglob('*') if p.is_file()} if delivered.exists() else {}
expected = {name: (pathlib.Path(temporary) / name).read_bytes() for name in (first, second)[:int(expected_files)]}
if actual != expected or (staging.exists() and any(p.is_file() for p in staging.rglob('*'))):
    raise SystemExit(1)
print(f"fixture stats: {json.dumps(posts, sort_keys=True)}; respostas: {[item['status'] for item in requests]}; staging: vazio; entregues: {actual}")
PY
        then return; fi
    done
    echo "Fixture não atingiu o estado esperado: mensagens=$expected_messages uploads=$expected_uploads arquivos=$expected_files" >&2
    return 1
}
select_test_file() {
    tap 'Anexar arquivos (até 5)'
    tap "$1"
    wait_text 'Anexar arquivos (até 5)'
    wait_text "$1"
}
type_test_message() {
    tap Mensagem
    for _ in $(seq 1 20); do
        dump
        if python3 - "$TMP/window.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
raise SystemExit(not any(node.get('class') == 'android.widget.EditText' and node.get('focused') == 'true' for node in root.iter('node')))
PY
        then break; fi
        sleep 1
    done
    sleep 1
    adb shell input text "$1"
    adb shell input keyevent KEYCODE_ENTER
    adb shell input text segunda-linha
    dump
    python3 - "$TMP/window.xml" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
assert any(node.get('class') == 'android.widget.EditText' and node.get('text') == sys.argv[2] + '\nsegunda-linha' for node in root.iter('node')), 'Rascunho digitado incompleto'
PY
    adb shell input keyevent 4
}
wait_draft_cleared() {
    for _ in $(seq 1 20); do
        dump
        if python3 - "$TMP/window.xml" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
raise SystemExit(any(sys.argv[2] in (node.get('text') or '') for node in root.iter('node') if node.get('class') == 'android.widget.EditText'))
PY
        then return; fi
        sleep 1
    done
    echo "Rascunho não foi limpo após envio: $1" >&2
    return 1
}

for THEME in claro escuro; do
if [[ "$THEME" == claro ]]; then adb shell cmd uimode night no; else adb shell cmd uimode night yes; fi
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

reset_app
shot conexao-vazia
enter_text 'URL do servidor' http://100.64.0.1:14748
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
tap 'Buscar por título, projeto ou agente'; adb shell input keyevent KEYCODE_MOVE_END
for _ in $(seq 1 20); do adb shell input keyevent KEYCODE_DEL KEYCODE_FORWARD_DEL; done
adb shell input keyevent 4
tap Abertas
tap 'projeto · parado'
wait_text 'Bash(echo fixture)'
shot conversa-fim
tap 'Pensando…'
wait_text $'Primeira linha do pensamento.\nSegunda linha do pensamento.\nTerceira linha do pensamento.'
shot pensamento-expandido
tap 'Pensando…'
for _ in $(seq 1 18); do adb shell input swipe 500 500 500 1850 150; done
shot conversa-topo
wait_text 'Relatório fictício'
shot markdown-rico
adb shell input swipe 500 1650 500 900 400
shot markdown-detalhes
tap 'Ir para o fim'
wait_text 'Bash(echo fixture)'
tap 'Bash(echo fixture)'
adb shell input swipe 500 1750 500 900 400
shot ferramenta-expandida
tap 'Mensagem'
adb shell input text 'Primeira%slinha%sficticia'; adb shell input keyevent KEYCODE_ENTER; adb shell input text 'Segunda%slinha%sficticia'
shot composer-teclado
adb shell input keyevent 4
tap Enviar
wait_text 'Aceita e na fila; ainda não entregue.'
shot estado-envio
printf 'Documento fictício para o teste visual.\n' >"$TMP/habblaud-exemplo.txt"
adb push "$TMP/habblaud-exemplo.txt" /sdcard/Download/habblaud-exemplo.txt >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/habblaud-exemplo.txt >/dev/null
select_test_file "$TEST_A"
shot anexos-selecionados
tap Remover
printf 'Arquivo B ficticio\n' >"$TMP/$TEST_B"
adb push "$TMP/$TEST_B" "/sdcard/Download/$TEST_B" >/dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/$TEST_B" >/dev/null
fixture_check 1 0 0
type_test_message caso-a
select_test_file "$TEST_A"
tap Enviar
fixture_check 2 1 1
wait_draft_cleared caso-a
type_test_message caso-b
select_test_file "$TEST_A"
tap Remover
tap Enviar
fixture_check 3 1 1
wait_draft_cleared caso-b
type_test_message caso-c
select_test_file "$TEST_A"
select_test_file "$TEST_B"
tap Remover
wait_text "$TEST_B"
dump
if coords "$TEST_A" >/dev/null; then echo 'O primeiro anexo não foi removido.' >&2; exit 1; fi
tap Enviar
fixture_check 4 2 2
wait_draft_cleared caso-c
wait_text 'Aceita e na fila; ainda não entregue.'
printf 'deliver fixture:claude\n' >&3
for _ in $(seq 1 20); do
    dump
    if ! coords 'Aceita e na fila; ainda não entregue.' >/dev/null; then break; fi
    sleep 1
done
if coords 'Aceita e na fila; ainda não entregue.' >/dev/null || coords 'Entregue à sessão; isso não significa que foi lida ou concluída.' >/dev/null || coords 'Envio não confirmado; confira a conversa antes de reenviar.' >/dev/null; then
    echo 'Banner inesperado após confirmação de entrega.' >&2
    exit 1
fi
shot entrega-confirmada
for _ in $(seq 1 18); do adb shell input swipe 500 500 500 1850 150; done
printf 'append fixture:claude\n' >&3
wait_text '1'
shot novas-entradas
tap 'Ir para o fim'
printf 'permission fixture:claude bash\n' >&3
wait_text 'Aprovar'
tap 'Aprovar'
wait_text 'Permitir'
shot aprovacao-aberta
adb shell input keyevent 4
adb shell settings put system font_scale 1.3
sleep 2
shot conversa-fonte-1-3
adb shell settings put system font_scale 1.0
tap 'Voltar às sessões'
tap 'caderno · trabalhando'
wait_text 'Parar agente'
wait_text 'Resposta OMP fictícia'
printf 'permission fixture:omp question-omp\n' >&3
wait_text 'Responder'
shot pergunta-omp-botao-1
sleep 1
shot pergunta-omp-botao-2
tap 'Responder'
wait_text 'Azul'
dump
if ! coords 'Caderno' >/dev/null; then
    read -r handle_x handle_y <<<"$(coords 'Drag handle')"
    adb shell input swipe "$handle_x" "$handle_y" "$handle_x" 300 500
fi
tap 'Laranja'
tap 'Caderno'
tap 'Livro'
adb shell input swipe 500 1900 500 950 400
wait_text 'Qual observação fictícia?'
dump
xy=$(python3 - "$TMP/window.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
fields = [n for n in ET.parse(sys.argv[1]).iter('node') if n.get('class') == 'android.widget.EditText' and n.get('bounds') != '[0,0][0,0]']
node = fields[-1]
x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
print((x1+x2)//2, (y1+y2)//2)
PY
)
adb shell input tap $xy
adb shell input text 'xyz123'
adb shell input keyevent 4
shot pergunta-omp-preenchida
tap 'Responder'
wait_text 'Parar agente'
shot pergunta-omp-respondida
printf 'stats\n' >&3
sleep 1
python3 - "$TMP/fixture.log" <<'PY'
import json, pathlib, sys
records = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines() if line.startswith('{')]
stats = next(item for item in reversed(records) if 'permissionResults' in item)
expected = [{'question': 0, 'options': [1]}, {'question': 1, 'options': [0, 2]}, {'question': 2, 'other': 'xyz123'}]
assert any(result.get('behavior') == 'answer' and result.get('answers') == expected for result in stats['permissionResults'].values()), stats['permissionResults']
PY
dump
if coords 'Existe conversa anterior que não foi carregada. Não há paginação.' >/dev/null || coords 'Acrescentar agora' >/dev/null || coords 'Limites da entrega' >/dev/null; then
    echo 'Aviso de histórico ou seletor de modo visível no fim da conversa OMP.' >&2
    exit 1
fi
shot omp-stop
for _ in $(seq 1 35); do adb shell input swipe 500 500 500 1850 150; done
wait_text 'Existe conversa anterior que não foi carregada. Não há paginação.'
shot omp-historico-truncado
tap 'Voltar às sessões'
tap 'Chamar personagem'
wait_text 'Escolha um personagem'
shot elenco-personagem
tap 'Caio Fictício'
wait_text 'Escolha a pasta do projeto'
shot elenco-pasta
tap '/fixture/jardim'
wait_text 'Pedido enviado. Aguardando o servidor.'
shot elenco-aguardando
printf 'cast-consume\n' >&3
wait_text 'Pedido concluído.'
tap 'Caio Fictício'
wait_text 'Dispensar personagem'
tap 'Dispensar personagem'
wait_text 'Dispensar este personagem e fechar sua sessão no servidor?'
shot elenco-dispensar
tap Confirmar
wait_text 'Pedido enviado. Aguardando o servidor.'
printf 'cast-consume\n' >&3
wait_text 'Pedido concluído.'
tap 'Voltar às sessões'
tap Recentes
enter_text 'Buscar por título, projeto ou agente' encerrada
tap 'Conversa encerrada fictícia'
wait_text 'Retomar conversa'
shot conversa-retomar-icone
tap 'Retomar conversa'
wait_text Confirmar
shot elenco-retomar
tap Confirmar
wait_text 'Pedido enviado. Aguardando o servidor.'
printf 'cast-consume\n' >&3
wait_text 'Pedido concluído.'
tap 'Voltar às sessões'
tap Recentes
enter_text 'Buscar por título, projeto ou agente' jardim
tap 'Planejamento de jardim'
tap 'Retomar conversa'
wait_text 'Escolha um personagem'
shot elenco-retomar-personagem
adb shell input keyevent 4
reset_app
fill_login http://127.0.0.1:14749 android-test
wait_text Abertas
tap 'projeto · parado'
wait_text 'O servidor precisa ser atualizado para enviar mensagens.'
shot modo-leitura
kill "$FIXTURE_PID" "$READONLY_PID"
wait "$FIXTURE_PID" 2>/dev/null || true
wait "$READONLY_PID" 2>/dev/null || true
FIXTURE_PID=
READONLY_PID=
done
