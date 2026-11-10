# Comparação Compose × Rust por pixels

## Preparação

Execute na raiz da worktree. Reserve um display X11 exclusivo e duas portas consecutivas; os exemplos usam `localhost:93`, `14794` e `14795`. Não execute junto de `shell-smoke.py` no mesmo display. O helper não compila Rust nem Compose, não altera preferências pessoais e não desenha imagens de referência.

Pré-requisitos: Python com Pillow, `libX11.so.6`, `libXtst.so.6`, Node com dependências da raiz instaladas e um JDK 21 para gerar o aplicativo Compose. `npm ci --ignore-scripts --no-audit --no-fund` prepara a fixture.

Build Compose necessário para executar a referência:

```sh
(cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ANDROID_HOME=/home/anderson/Android/Sdk \
  ./gradlew :desktop:createDistributable --max-workers=3 --console=plain)
```

`ANDROID_HOME` evita criar `android/local.properties`. Se o ambiente exigir esse arquivo, use temporariamente `sdk.dir=/home/anderson/Android/Sdk`, preserve eventual conteúdo anterior e não o versione.

No ambiente headless dos executores, mantenha este processo aberto em outro terminal, após reservar `:93`:

```sh
ROOT=/tmp/zaplaud-desktop-spike
mkdir -p "$ROOT/temp"
bwrap --ro-bind / / --bind "$ROOT/temp" /tmp --bind "$ROOT" "$ROOT" \
  --ro-bind "$ROOT/tools/usr/bin" /usr/bin \
  --setenv LD_LIBRARY_PATH "$ROOT/tools/usr/lib" --setenv HOME "$ROOT/home" \
  /usr/bin/Xvfb :93 -screen 0 1280x800x24 -nolisten unix -listen tcp -ac -noreset
```

Esse Xvfb serve TCP sem autenticação; use somente em máquina isolada. Não abra a porta `6093` na rede. Encerre o processo depois da captura.

## Capturas e comparação

Somente referência Compose, sete telas em ambos os temas:

```sh
python zaplaud-rs/scripts/compare-compose.py --capture compose
```

Depois que o responsável pelo Rust disponibilizar um binário atualizado, capture o candidato sem executar Cargo:

```sh
python zaplaud-rs/scripts/compare-compose.py --capture rust \
  --rust-binary /caminho/absoluto/para/zaplaud --compare
```

Ou capture ambos sequencialmente no mesmo display e compare:

```sh
python zaplaud-rs/scripts/compare-compose.py --capture both \
  --rust-binary /caminho/absoluto/para/zaplaud --compare
```

Para recalcular diferenças sem executar aplicativos:

```sh
python zaplaud-rs/scripts/compare-compose.py --capture none --compare
```

Opções úteis: `--display localhost:93 --port 14794 --theme light` (ou `dark`/`both`), `--settle 1`, `--timeout 40` e `--compose-binary /caminho/para/Zaplaud`. Mantenha as mesmas opções nas duas capturas. O helper recusa uma janela `Zaplaud` já aberta no display.

## Saídas

Para cada tema `light`/`dark`, os nomes das sete telas são `login`, `http-warning`, `list`, `search`, `two-servers`, `narrow`, `relogin`:

- `/tmp/zaplaud-ref-compose-<tela>-<tema>.png`: referência real do Compose.
- `/tmp/zaplaud-rs-polish-compare-<tela>-<tema>.png`: candidato real do Rust.
- `/tmp/zaplaud-rs-polish-compare-diff-<tela>-<tema>.png`: diferença absoluta RGB, sem amplificação.
- `/tmp/zaplaud-rs-polish-compare-side-by-side-<tela>-<tema>.png`: Compose à esquerda e Rust à direita, sem redimensionar.
- `/tmp/zaplaud-rs-polish-compare.json`: fração de pixels diferentes e erro médio absoluto por canal, escala 0–255.
- `/tmp/zaplaud-ref-compose-<tema>.log` e `/tmp/zaplaud-rs-polish-compare-<tema>.log`: saída dos aplicativos.

Dimensões diferentes ou pares ausentes interrompem a comparação; não há redimensionamento, máscara, tolerância implícita ou aprovação automática. A diferença é evidência para inspeção, não critério de paridade semântica.

## Fixture e limites

- Reutiliza `Desktop`, XTest, `Fixture` e esperas de `shell-smoke.py`. Cada aplicativo/tema inicia a mesma fixture `scripts/android-smoke.ts` nas duas portas, com usuário/senha fictícios `android-test`.
- Cada execução usa diretórios temporários exclusivos, inclusive `XDG_RUNTIME_DIR` com modo `0700` para isolar o socket de instância única. Compose lê o tema por `java.util.prefs.userRoot` e `zaplaud.dataDir`; Rust recebe `ZAPLAUD_THEME` e diretórios XDG exclusivos. `Main.kt` não aceita argumentos de tela/fixture: o helper usa o launcher real e preenche a UI.
- Captura 1100 × 760; `narrow` usa 600 × 760 após abrir uma conversa. A captura vem da raiz X11 recortada na janela, incluindo os diálogos reais do Compose, que são janelas separadas.
- `list` não seleciona conversa. `search` abre Ctrl+F sem texto. `two-servers` mostra os chips sem filtro. `relogin` reinicia a segunda fixture e reabre o aplicativo com os mesmos dados locais, reconstruindo as conexões e banners de autenticação. Não mede latência de expiração SSE durante uma sessão aberta.
- Para nunca ler nem sobrescrever credenciais pessoais no serviço global `br.com.xlinx.zaplaud`, o helper força `zaplaud.keyring=memory` no Compose e `ZAPLAUD_KEYRING=memory` no Rust. Por isso `relogin` mostra os dois servidores sem autenticação após reabrir. Não comprova expiração isolada de um servidor nem persistência no chaveiro.
- Coordenadas correspondem ao layout Kotlin atual, escala 1, sem gerenciador de janelas. Não use HiDPI, decorações externas, fontes diferentes ou outro tamanho de display. Mudanças no layout exigem ajustar as coordenadas do helper, não simular imagens.
- Horários relativos, IDs gerados, ordem de respostas, caret, hover e animações podem variar entre processos. `--settle` reduz transientes, mas a fixture não congela relógio nem renderizador. Fontes e antialiasing Compose/Slint também podem produzir diferenças legítimas.
- A automação confere abertura da janela, divisor da lista, presença de conteúdo e largura estreita. Não usa OCR/acessibilidade para certificar cada rótulo: inspecione as imagens, especialmente o aviso HTTP e o banner de relogin.
- Encerramento normal/falha limpa aplicativos, fixtures e dados temporários. PNGs e logs permanecem em `/tmp`; são sobrescritos por novas capturas do mesmo tema. Não misture referências de revisões diferentes.

## Evidência desta rodada e diferenças restantes

- Referências Compose: as sete telas foram capturadas em claro e escuro, executando a distribuição real da worktree.
- Candidato Rust e pares lado a lado: sete telas claras foram capturadas antes das últimas correções de geometria de título, avatar, ponto de conexão e ícones. Essas imagens não certificam a revisão final.
- A comparação ainda não está limitada à rasterização de fontes. A tela Rust dessa captura tinha títulos fora dos limites e avatares dimensionados pelo tamanho natural; o código corrige esses dois casos, mas exige nova captura.
- Ainda falta a captura Rust escura, a comparação final dos dois temas e a revisão visual dos recursos integrados dos demais agentes.
- `ProgressRing` é um arco Material estático, sem timer. Não reproduz a animação indeterminada contínua do Compose.
- Fontes permanecem do sistema. Não foi concluída a comparação de PSS/binário com fontes embutidas nem a escolha de fallback emoji. O renderer software usa somente contornos; não há comprovação de emoji colorido.
- `scripts/accessibility-smoke.py` exercita a árvore AT-SPI real numa sessão D-Bus privada. A sessão precisa iniciar `at-spi2-registryd` explicitamente quando a ativação systemd não funciona.
- `scripts/polish-measure.py` mede lançamento, PSS, CPU por 30 segundos e conexões antes/depois de cinco aberturas. Os resultados ficam em `/tmp/zaplaud-rs-polish-<label>-metrics.json`; não confundem compilação debug com release.
- Medição release desta rodada (`--label system-font --seconds 30`): binário 25.102.696 bytes; lançamento 364,53 ms; login 20.287 KiB PSS / 0% CPU; lista trabalhando 18.842 KiB PSS / 0,6333% CPU. O alvo de abertura abaixo de 300 ms não foi atingido nessa amostra.
- Após cinco aberturas/fechamentos: PSS 29.093 KiB, sete threads e 25 descritores; SSE permaneceu 1 e terminais 0. Não houve crescimento de conexões, mas a memória não retornou ao valor anterior nessa amostra.
- `/tmp/zaplaud-rs-polish-system-font-emoji.png` mostra acentos pt-BR e uma face monocromática. O título de teste também tinha foguete e sequência de pessoa/pele/ZWJ, que não aparecem; suporte emoji completo permanece pendente.
- Gates da revisão final: `cargo fmt --check`, `cargo clippy --workspace --all-targets -j 3 -- -D warnings` e `ZAPLAUD_KEYRING=memory cargo test --workspace -j 3` passaram; 121 testes passaram.
