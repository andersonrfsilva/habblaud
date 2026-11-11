# Distribuição desktop

## Linux: pacote do usuário

O artefato `zaplaud-linux-x86_64.tar.gz` inclui o binário, os ícones, `install-linux.sh` e as licenças das fontes embutidas Noto Sans Mono (`OFL-NotoSans.txt`), Noto Emoji (`OFL-NotoEmoji.txt`) e DejaVu (`LICENSE-DejaVu.txt`). Extraia o arquivo e execute `bash install-linux.sh` dentro da pasta extraída. Não use `sudo`. O instalador não baixa nem compila código.

- O executável fica em `~/.local/bin/zaplaud`; acrescente `~/.local/bin` ao `PATH` para iniciar pelo terminal. O lançador usa caminho absoluto e não depende desse ajuste.
- Desktop e ícones respeitam `XDG_DATA_HOME`, com fallback `~/.local/share`. Reutilize o mesmo valor na desinstalação.
- Reexecutar o instalador atualiza a instalação. Feche o executável instalado antes de atualizar ou remover; o instalador recusa operações enquanto seu inode aparece em `/proc/*/exe`. A consulta exige `/proc` visível para o usuário. Não inicie o aplicativo durante a instalação.
- `bash install-linux.sh --uninstall` remove somente binário, lançador e ícones próprios. Não apaga servidores, sessões, mensagens, cache ou credenciais do chaveiro.
- A instalação usa Bash, coreutils e `flock` (util-linux). Atualizadores de cache desktop/ícones são opcionais.

## Bibliotecas de execução

O pacote portátil não inclui glibc ou bibliotecas gráficas. O CI usa Ubuntu 24.04 x86_64: o artefato produzido nessa base requer **glibc 2.39 ou posterior**. O tarball leve construído localmente em Arch Linux requer **glibc 2.43 ou posterior**, conforme `readelf --version-info`, sem símbolos `GLIBCXX`/`CXXABI`. Não use esse tarball local em Ubuntu 24.04, Ubuntu 22.04 ou Debian 12; construa na própria distribuição ou em uma base compatível. Não é um executável musl.

O padrão é `software` do Slint, com emoji de contorno embutido e sem Skia, OpenGL, GTK, Qt, JVM ou OpenSSL. Skia é opt-in de compilação (`--features skia`) e execução (`ZAPLAUD_RENDERER=skia-software` para CPU, `ZAPLAUD_RENDERER=skia` para Metal no macOS). `SLINT_BACKEND=winit-software` continua funcionando e tem precedência. Winit/softbuffer carregam bibliotecas X11/Wayland em execução: `ldd` sozinho não lista todos esses requisitos.

No build leve Linux, `readelf -d` lista diretamente `libfontconfig.so.1`, `libgcc_s.so.1`, `libm.so.6` e `libc.so.6`. `ldd` também observa FreeType, Expat, zlib, bzip2, libpng e Brotli transitivos de Fontconfig. `libstdc++.so.6` só era exigida pelo build Skia. Confira cada artefato com `ldd zaplaud` na distribuição de build.

Instale os pacotes abaixo com o gerenciador da distribuição. São nomes de runtime, não os pacotes `-dev` usados na compilação:

| Distribuição | Pacotes |
| --- | --- |
| Ubuntu 24.04 / Debian 13 | `libc6 libgcc-s1 libfreetype6 libfontconfig1 libx11-6 libx11-xcb1 libxcursor1 libxrandr2 libxi6 libxcb1 libxcb-render0 libxcb-shm0 libxkbcommon0 libxkbcommon-x11-0 libwayland-client0 fonts-dejavu-core` |
| Fedora | `glibc libgcc freetype fontconfig libX11 libXcursor libXrandr libXi libxcb libxkbcommon libxkbcommon-x11 wayland-libs dejavu-sans-fonts` |
| Arch Linux / CachyOS | `glibc gcc-libs freetype2 fontconfig libx11 libxcursor libxrandr libxi libxcb libxkbcommon libxkbcommon-x11 wayland ttf-dejavu` |

A tabela cobre o build leve e não garante compatibilidade ABI com o tarball local. No build Skia opcional, acrescente libstdc++ e fonte colorida: `fonts-noto-color-emoji` (Debian/Ubuntu), `google-noto-color-emoji-fonts` (Fedora) ou `noto-fonts-emoji` (Arch). Sem ela, Noto Emoji embutida mantém contornos.

Uma sessão gráfica X11 ou Wayland é necessária. D-Bus de sessão, um serviço `org.freedesktop.Notifications` e um observador StatusNotifier habilitam notificações e bandeja; sua ausência não impede abrir o aplicativo. Nenhum `notify-send` ou `libdbus` C é exigido. Para manter cookies entre execuções no Linux, disponibilize um provedor Secret Service desbloqueado, como GNOME Keyring ou KeePassXC com essa integração ativada. Sem ele, a sessão fica apenas em memória. No macOS, usa-se o Keychain nativo.

O seletor de anexos tenta o portal `org.freedesktop.portal.FileChooser`, depois `zenity`/`kdialog` e, sem esses serviços/comandos, o diálogo interno de caminhos. Para o seletor integrado ao desktop, disponibilize `xdg-desktop-portal` e o backend da sessão (`xdg-desktop-portal-gtk`, `xdg-desktop-portal-gnome` ou `xdg-desktop-portal-kde`). Esses componentes são opcionais; não tornam GTK uma dependência obrigatória do cliente.

## Integração da sessão gráfica

Fechar a janela encerra o processo: a bandeja não mantém execução em segundo plano. Uma segunda abertura usa `XDG_RUNTIME_DIR/zaplaud/instance.sock` para trazer a primeira janela à frente. Sem `XDG_RUNTIME_DIR`, usa um diretório privado em `TMPDIR`. Um socket obsoleto é removido somente quando não atende e seu tipo é socket.

`window.json`, no diretório de dados, conserva tamanho, posição normal e estado maximizado. Em Wayland, restaura tamanho e maximização, não posição. O identificador gráfico é `zaplaud` em Wayland e X11. O ícone da bandeja é rasterizado do mesmo SVG gerado durante o build, sem depender da instalação dos ícones hicolor.

Alertas consomem os snapshots já recebidos pelo aplicativo: não abrem outro `/api/stream`. O primeiro snapshot cria a baseline; reconexões comparam com o último snapshot anterior. As preferências são consultadas por publicação; somente mudanças recebidas sem foco geram alertas. Título e corpo seguem `DesktopAlerts.kt`, sem texto de mensagens ou ferramentas. No Linux, a ação `Abrir` traz a janela à frente e seleciona a sessão; permissões usam o evento do recurso de permissões. A espera pela ação é assíncrona e limitada a dez segundos, sem threads bloqueadas por notificação.

No macOS, usa-se o backend nativo de `notify-rust`; a ação e o clique na notificação abrem a sessão. A espera nativa pelo clique usa uma thread bloqueada até interação ou dispensa; o processo não espera essa thread ao sair. Esse comportamento não foi validado no Linux. A bandeja macOS foi omitida. A restauração Wayland exige validação na plataforma correspondente. O evento de oclusão do winit permite suspender recursos em X11/macOS; o backend Wayland não fornece esse evento.

O `Cargo.lock` mantém `blocking` em 1.6.2. Na versão 1.7.0, a thread ociosa do pool acorda a cada 500 ms sem encerrar enquanto está abaixo do limite de threads; `strace` observou 60 esperas `futex` expiradas em 30 segundos. A versão 1.6.2 encerra a thread após a primeira espera ociosa. Preserve esse pin ao atualizar dependências até verificar uma correção upstream. A bandeja usa o backend async-io já usado pelo Slint; notificações usam o Tokio existente. Misturar o backend Tokio do zbus 5 com o consumidor async-io do Slint criava outro pool de 16 threads.

Para reproduzir abertura, memória e CPU com fixture já iniciada: `python3 scripts/measure.py --binary /caminho/zaplaud --display localhost:92 --fixture-url http://127.0.0.1:14793`. O JSON contém três amostras, mediana de mapeamento, PSS/RSS, threads, tamanho do binário e CPU por 30 segundos em cada execução. Use um display exclusivo; cada execução isola HOME, dados e runtime.

## Gerar ícones e pacotes Linux

Execute da raiz `zaplaud-rs/`, com Rust da toolchain fixada e dependências nativas de desenvolvimento instaladas. O exemplo `export_icon` usa resvg já presente no projeto; não exige librsvg nem Homebrew.

```sh
export PATH="$HOME/.cargo/bin:$PATH"
cargo build --release --locked -p zaplaud-ui --bin zaplaud
svg=$(find "${CARGO_TARGET_DIR:-target}/release/build" -path '*/zaplaud-ui-*/out/ic_zaplaud.svg' -print -quit)
test -n "$svg"
bash packaging/generate-icons.sh "$svg"
python3 packaging/test-install.py
cargo install cargo-deb --locked
cargo install cargo-generate-rpm --locked
cargo deb -p zaplaud-ui --no-build
cargo generate-rpm -p crates/zaplaud-ui
```

O SVG vem de `build.rs`/`resource_gen.rs`, que leem `android/shared/src/commonMain/composeResources/drawable/ic_zaplaud.xml`. Não desenhe outro ícone nem versione PNGs derivados. O script rasteriza o SVG real nos tamanhos hicolor 16, 24, 32, 48, 64, 128, 256, 512 e 1024 px. Ícones gerados ficam ignorados pelo Git. Uma árvore de build limpa evita selecionar SVG antigo deixado por outro build. Com `--target`, inclua o triple entre o diretório target e `release` ao localizar o SVG.

No checkout, `bash scripts/install-linux.sh /caminho/absoluto/zaplaud` instala um binário já construído. O argumento é obrigatório quando `CARGO_TARGET_DIR` aponta para fora de `zaplaud-rs/target`.

DEB e RPM usam os metadados em `crates/zaplaud-ui/Cargo.toml`. A detecção automática de dependências cobre vínculos ELF; as dependências gráficas carregadas dinamicamente são declaradas explicitamente. Os pacotes nativos destinam-se à instalação pelo gerenciador do sistema, diferente do instalador por usuário. O job `empacotar-linux` publica DEB, RPM, tarball e `SHA256SUMS` após os gates; não cria release pública nem assina pacotes.

## macOS: construir um `.app`

Execute em um Mac; um binário ELF Linux não vira aplicativo macOS. Instale Xcode Command Line Tools (`xcode-select --install`) e Rust/rustup. Não exige Homebrew. Da raiz `zaplaud-rs/`:

```sh
export PATH="$HOME/.cargo/bin:$PATH"
export MACOSX_DEPLOYMENT_TARGET=11.0
cargo build --release --locked -p zaplaud-ui --bin zaplaud
bash packaging/bundle-macos.sh
mkdir -p "$HOME/Applications"
cp -R "${CARGO_TARGET_DIR:-target}/release/bundle/osx/Zaplaud.app" "$HOME/Applications/"
open "$HOME/Applications/Zaplaud.app"
```

O bundle usa `br.com.xlinx.zaplaud`, ícone `.icns` renderizado do SVG canônico por resvg e macOS mínimo 11.0. Quick Look não rasteriza mais o SVG: produzia um quadrado branco. Construa nativamente para Apple Silicon ou Intel; um pacote universal requer ambos os targets e `lipo`. A receita faz assinatura ad hoc local, não Developer ID nem notarização. Distribuição a terceiros exige assinatura/notarização da Apple; não recomende desativar Gatekeeper.
