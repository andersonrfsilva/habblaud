#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Uso: $0 <ic_zaplaud.svg gerado por build.rs> [diretório de saída]" >&2
    exit 2
fi
source_icon=$1
output=${2:-"$(dirname "${BASH_SOURCE[0]}")/icons"}
[[ -f $source_icon ]] || { echo "SVG gerado não encontrado: $source_icon" >&2; exit 1; }
command -v rsvg-convert >/dev/null || { echo 'Instale librsvg (rsvg-convert) para gerar os ícones.' >&2; exit 1; }
for size in 16 24 32 48 64 128 256 512 1024; do
    destination="$output/hicolor/${size}x${size}/apps"
    mkdir -p "$destination"
    rsvg-convert --width "$size" --height "$size" --output "$destination/zaplaud.png" "$source_icon"
done
