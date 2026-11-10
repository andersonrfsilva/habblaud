#!/usr/bin/env bash
set -euo pipefail
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $(uname -s) == Linux ]] || fail 'Este instalador exige Linux.'
[[ $# -le 1 ]] || fail "Uso: $0 [caminho/do/zaplaud | --uninstall]"
: "${HOME:?HOME ausente}"
[[ $HOME == /* ]] || fail 'HOME deve ser um caminho absoluto.'
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
[[ $data_home == /* ]] || fail 'XDG_DATA_HOME deve ser um caminho absoluto.'
binary_dir="$HOME/.local/bin"
destination="$binary_dir/zaplaud"
applications="$data_home/applications"
icons="$data_home/icons/hicolor"
sizes=(16 24 32 48 64 128 256 512 1024)
mkdir -p "$data_home"
command -v flock >/dev/null || fail 'flock (util-linux) não encontrado.'
exec 9>"$data_home/zaplaud-install.lock"
flock -n 9 || fail 'Outro instalador Zaplaud está em execução.'
# O inode identifica o executável instalado, mesmo com argv[0] alterado.
if [[ -e $destination ]]; then
    [[ -r /proc/self/exe ]] || fail 'Não foi possível consultar executáveis em /proc.'
    for executable in /proc/[0-9]*/exe; do
        if [[ $executable -ef $destination ]]; then
            fail 'Feche o Zaplaud instalado antes de atualizar ou desinstalar.'
        fi
    done
fi
if [[ ${1:-} == --uninstall ]]; then
    rm -f -- "$destination" "$applications/zaplaud.desktop"
    for size in "${sizes[@]}"; do
        rm -f -- "$icons/${size}x${size}/apps/zaplaud.png"
    done
    printf '%s\n' 'Zaplaud desinstalado. Dados, cache e credenciais foram preservados.'
else
    script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
    if [[ -d $script_dir/packaging ]]; then
        packaging="$script_dir/packaging"
        source_binary=${1:-"$script_dir/zaplaud"}
    else
        packaging="$script_dir/../packaging"
        source_binary=${1:-"$script_dir/../target/release/zaplaud"}
    fi
    [[ -f $source_binary && -x $source_binary ]] || fail "Executável não encontrado: $source_binary"
    [[ -f $packaging/zaplaud.desktop ]] || fail 'Arquivo desktop ausente no pacote.'
    for size in "${sizes[@]}"; do
        [[ -f $packaging/icons/hicolor/${size}x${size}/apps/zaplaud.png ]] || fail 'Ícones ausentes: execute packaging/generate-icons.sh antes de instalar.'
    done
    mkdir -p "$binary_dir" "$applications"
    temporary=$(mktemp "$binary_dir/.zaplaud.XXXXXX")
    desktop_temporary=$(mktemp "$applications/.zaplaud.XXXXXX")
    trap 'rm -f -- "$temporary" "$desktop_temporary"' EXIT
    install -m 0755 -- "$source_binary" "$temporary"
    # Exec usa a sintaxe do arquivo desktop, não a sintaxe do shell.
    escaped=${destination//\\/\\\\}
    escaped=${escaped//\"/\\\"}
    escaped=${escaped//\`/\\\`}
    escaped=${escaped//\$/\\\$}
    escaped=${escaped//%/%%}
    while IFS= read -r line; do
        case $line in
            Exec=*) printf 'Exec="%s"\n' "$escaped" ;;
            TryExec=*) printf 'TryExec=%s\n' "$destination" ;;
            *) printf '%s\n' "$line" ;;
        esac
    done < "$packaging/zaplaud.desktop" > "$desktop_temporary"
    chmod 0644 "$desktop_temporary"
    for size in "${sizes[@]}"; do
        install -Dm0644 -- "$packaging/icons/hicolor/${size}x${size}/apps/zaplaud.png" "$icons/${size}x${size}/apps/zaplaud.png"
    done
    mv -f -- "$temporary" "$destination"
    mv -f -- "$desktop_temporary" "$applications/zaplaud.desktop"
    printf 'Zaplaud instalado em %s\n' "$destination"
fi
if command -v update-desktop-database >/dev/null && [[ -d $applications ]]; then
    update-desktop-database "$applications" || printf '%s\n' 'Aviso: não foi possível atualizar o cache desktop.' >&2
fi
if command -v gtk-update-icon-cache >/dev/null && [[ -d $icons ]]; then
    gtk-update-icon-cache --force --ignore-theme-index "$icons" || printf '%s\n' 'Aviso: não foi possível atualizar o cache de ícones.' >&2
fi
