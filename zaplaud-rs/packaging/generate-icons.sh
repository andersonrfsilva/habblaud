#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Uso: $0 <ic_zaplaud.svg gerado por build.rs> [diretório de saída]" >&2
    exit 2
fi
source_icon=$1
output=${2:-"$(dirname "${BASH_SOURCE[0]}")/icons"}
[[ -f $source_icon ]] || { echo "SVG gerado não encontrado: $source_icon" >&2; exit 1; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cargo build --manifest-path "$root/Cargo.toml" --release --locked -p zaplaud-ui --example export_icon
exporter=${CARGO_TARGET_DIR:-$root/target}/release/examples/export_icon
for size in 16 24 32 48 64 128 256 512 1024; do
    destination="$output/hicolor/${size}x${size}/apps"
    mkdir -p "$destination"
    "$exporter" "$source_icon" "$destination/zaplaud.png" "$size"
done
