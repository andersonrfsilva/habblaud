#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
distribution="$root/android/desktop/build/compose/binaries/main-release/app/Zaplaud"
install_dir="$HOME/.local/opt/zaplaud"
applications="$HOME/.local/share/applications"

if [[ ! -x "$distribution/bin/Zaplaud" ]]; then
  echo 'Build the distribution first: cd android && ./gradlew :desktop:createReleaseDistributable' >&2
  exit 1
fi
mkdir -p "$HOME/.local/opt" "$applications"
staging="$(mktemp -d "$HOME/.local/opt/.zaplaud-XXXXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
cp -a -- "$distribution" "$staging/Zaplaud"
rm -rf -- "$install_dir"
mv -- "$staging/Zaplaud" "$install_dir"

# Desktop entry values are not shell-expanded; write absolute paths for this user.
exec_path="${install_dir//\\/\\\\\\\\}"
exec_path="${exec_path//\"/\\\\\\\"}"
exec_path="${exec_path//\$/\\\\\$}"
exec_path="${exec_path//\`/\\\\\`}"
while IFS= read -r line; do
  case "$line" in
    Exec=*) printf 'Exec="%s/bin/Zaplaud"\n' "$exec_path" ;;
    Icon=*) printf 'Icon=%s/lib/Zaplaud.png\n' "${install_dir//\\/\\\\}" ;;
    *) printf '%s\n' "$line" ;;
  esac
done < "$root/android/desktop/zaplaud.desktop" > "$applications/zaplaud.desktop"
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$applications"
fi
printf 'Installed Zaplaud in %s\n' "$install_dir"
