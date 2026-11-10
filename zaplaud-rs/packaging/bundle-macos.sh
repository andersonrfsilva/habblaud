#!/usr/bin/env bash
# Monta Zaplaud.app a partir do binário release, só com ferramentas do macOS (sips, iconutil, qlmanage).
# Uso (da raiz zaplaud-rs/, depois de `cargo build --release --locked -p zaplaud-ui --bin zaplaud`):
#   bash packaging/bundle-macos.sh [diretório de saída]
set -euo pipefail
[[ $(uname -s) == Darwin ]] || { echo 'Execute num Mac.' >&2; exit 1; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
target=${CARGO_TARGET_DIR:-$root/target}
binary=$target/release/zaplaud
[[ -x $binary ]] || { echo "Binário release não encontrado: $binary" >&2; exit 1; }
output=${1:-$target/release/bundle/osx}
app=$output/Zaplaud.app
version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$root/Cargo.toml" | head -1)
[[ -n $version ]] || { echo 'Versão do workspace não encontrada.' >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Ícone: sempre do SVG gerado pelo build.rs a partir do vetor Kotlin; nada de PNG copiado à mão.
svg=$(find "$target/release/build" -path '*/zaplaud-ui-*/out/ic_zaplaud.svg' -print -quit)
[[ -n $svg ]] || { echo 'SVG do ícone não encontrado; compile o release antes.' >&2; exit 1; }
if command -v rsvg-convert >/dev/null; then
    rsvg-convert --width 1024 --height 1024 --output "$work/1024.png" "$svg"
else
    qlmanage -t -s 1024 -o "$work" "$svg" >/dev/null 2>&1
    mv "$work/$(basename "$svg").png" "$work/1024.png"
fi
iconset=$work/Zaplaud.iconset
mkdir "$iconset"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$work/1024.png" --out "$iconset/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" "$work/1024.png" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done

rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
iconutil --convert icns --output "$app/Contents/Resources/Zaplaud.icns" "$iconset"
cp "$binary" "$app/Contents/MacOS/zaplaud"
cp "$root/crates/zaplaud-ui/ui/fonts/"*.txt "$app/Contents/Resources/"
cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>Zaplaud</string>
    <key>CFBundleDisplayName</key><string>Zaplaud</string>
    <key>CFBundleIdentifier</key><string>br.com.xlinx.zaplaud</string>
    <key>CFBundleExecutable</key><string>zaplaud</string>
    <key>CFBundleIconFile</key><string>Zaplaud</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>$version</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>LSMinimumSystemVersion</key><string>11.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.social-networking</string>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST
plutil -lint "$app/Contents/Info.plist" >/dev/null
# Assinatura local (ad hoc): exigida no Apple Silicon e dá identidade estável ao app para Keychain e notificações.
codesign --force --sign - "$app"
codesign --verify "$app"
echo "$app"
