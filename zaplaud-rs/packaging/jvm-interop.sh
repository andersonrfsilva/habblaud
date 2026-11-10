#!/usr/bin/env bash
set -euo pipefail
: "${ZAPLAUD_JVM_INTEROP_DIR:?Informe um diretório vazio e exclusivo para o round-trip}"
root=$(cd "$(dirname "$0")/../.." && pwd)
test_file="$root/android/shared/src/jvmTest/kotlin/br/com/xlinx/habblaud/data/JvmRustInteropTest.kt"
if [[ -e "$test_file" ]]; then
    printf 'Teste temporário já existe: %s\n' "$test_file" >&2
    exit 1
fi
trap 'rm -f "$test_file"' EXIT
mkdir -p "$(dirname "$test_file")" "$ZAPLAUD_JVM_INTEROP_DIR"
cp "$root/zaplaud-rs/packaging/JvmRustInteropTest.kt" "$test_file"
cd "$root/android"
./gradlew :shared:jvmTest --tests br.com.xlinx.habblaud.data.JvmRustInteropTest --max-workers=3 --rerun-tasks --console=plain
