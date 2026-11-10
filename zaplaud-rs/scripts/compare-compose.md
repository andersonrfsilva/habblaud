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

## Fechamento visual — 10/11/2026

Estado: **incompleto**. Esta rodada não certifica paridade por pixels.

### Referências reais novas

O aplicativo Compose da worktree foi executado com `./gradlew :desktop:run --max-workers=3`,
SDK local temporário, Xvfb `:89` e fixture real na porta `14789`, permitindo
`terminal,messages,permissions,spawn`. As capturas ficam em
`/tmp/zaplaud-ref-compose-<tela>-<tema>.png`, com temas `light` e `dark`:

- `conversation-idle`, `conversation-working`, `conversation-attachments`, `conversation-status-banner`;
- `permission-bash`, `permission-question-single`, `permission-question-multi`, `permission-question-omp`;
- `settings-appearance`, `settings-roles`, `settings-role-dialog`, `settings-quotas`;
- `aside`, `session-info`, `conversation-menu`, `list-menu`;
- `cast-picker`, `cast-folders`, `cast-dismiss`, `cast-resume-picker`, `cast-resume`.

As capturas intermediárias foram feitas por XTest e incluem diálogos reais. Ainda falta
auditar todos os arquivos, capturar novamente o Rust integrado e produzir todos os pares
`/tmp/zaplaud-rs-fin-visual-compare-*.png`. Não confundir arquivo existente com tela certificada.

### Correções implementadas

- Login: campos com a margem vertical do OutlinedTextField e botão de 40 px, sem esticar.
- Compositor: ações centralizadas verticalmente quando o campo cresce; container desabilitado usa alpha Material.
- Banner: ícone Info de 18 px, padding 12 × 8 e altura mínima de 34 px.
- Configurações: RadioRow de 44 px, indicador de 24 px com intervalo de 12 px; papéis de 60 px e switches de 52 px.
- Elenco: seletor de 560 px, pastas de 44 px, contatos de 64 px e ícone Novo de 24 px dentro da caixa de 40 px.
- Aside: RadioButton indica tópico selecionado; edição rejeita mais de 20.000 unidades UTF-16.
  A falha de envio mantém o rascunho; edição concorrente não é apagada após sucesso.
- Kit: ações AT-SPI padrão nos botões/toggles; descrição das opções de pergunta e nomes nas sheets.
- Permissões: input completo com quebra por caractere e rolagem; marcador localizado de linhas adicionais.

### Fontes e emoji: evidência e decisão

`/proc/<pid-compose>/maps` mostrou Noto Sans Regular/Medium, Noto Sans Mono e DejaVu Sans Mono,
além de fontes usadas pelo chooser nativo. `fc-match sans-serif` escolheu Noto Sans.
O texto normal permanece do sistema. Noto Sans Mono foi embutida para tornar o nome explícito
usado pelo Markdown disponível também no macOS. O macOS não foi executado nesta máquina.

As amostras abaixo são execuções isoladas, não distribuições estatísticas. Foram obtidas
na mesma fixture, com a conversa aberta. Revisões de UI diferem entre amostras; o último
binário também inclui o recurso femtovg compilado. Não atribuir toda diferença às fontes.

| Variante experimental | Binário (bytes) | Abertura (ms) | PSS (KiB) | RSS (KiB) |
| --- | ---: | ---: | ---: | ---: |
| Sistema + software | 34.023.944 | 202,84 | 31.371 | 38.240 |
| Fonte mesclada externa + software | 34.023.944 | 155,73 | 31.901 | 38.536 |
| Fonte mesclada embutida + software, femtovg compilado | 37.133.440 | 103,17 | 33.439 | 40.020 |

O experimento mescla Noto Sans com Noto Emoji de contorno. O gerador reproduzível
`compare-compose.fonts.py` requer FontTools e uharfbuzz. Seu self-check confirmou uma ligatura
para cada amostra `🚀`, `👩🏽‍💻`, `✅`, `⚠️`, `🇧🇷`, `🇺🇸`.
Entretanto, o renderer software ainda mostrou componentes separados para ZWJ/pele e letras
separadas nas bandeiras. Por isso a fonte mesclada **não foi selecionada para produção**.

Evidências: `/tmp/zaplaud-rs-fin-visual-system-emoji.png`,
`/tmp/zaplaud-rs-fin-visual-merged-emoji.png`,
`/tmp/zaplaud-rs-fin-visual-bundled-emoji.png`.
Foi acrescentada uma mensagem com a mesma sequência ao transcript temporário da fixture,
sem alterar o servidor ou as fontes Kotlin.

Em Slint 1.18.1, `i-slint-renderer-software/fonts/vectorfont.rs:207` pede somente
`Source::Outline` e `Format::Alpha`: esse caminho não pinta COLR, CBDT ou sbix coloridos.
O teste inicialmente chamado femtovg ainda executou software porque `main.rs` fixava
`.renderer_name(\"software\")`; seus números **não são uma medição válida de femtovg**.
A seleção fixa foi removida para permitir o `SLINT_BACKEND` padrão nos builds experimentais.
O Cargo padrão continua com somente software. Ainda faltam medição real de femtovg/Skia,
emoji colorido/ZWJ correto e uma estratégia final de fallback portátil.

### Diferenças e verificações pendentes

- A comparação não está limitada à rasterização: menus, sheets, quotas e espaçamentos precisam de nova captura integrada.
- Aside/permissão permanecem painéis direitos, adaptação desktop aceita; falta certificar integralmente seu conteúdo interno.
- Não foi executado o novo percurso AT-SPI com lista, bolhas, compositor e sheets em sessão D-Bus privada.
- `cargo fmt --check` passou. Clippy e testes não foram concluídos na última revisão visual; o último build release passou antes das últimas alterações de kit/aside.
- O build experimental anterior encontrou dependências incompletas do grupo de correção; os commits dependentes foram integrados depois.
- `ProgressRing` continua estático; emoji completo e suporte macOS não estão certificados.


## Certificação integrada — fin-certify

**Estado: incompleto; as imagens desta rodada não constituem aceite visual.**

O runner `certify-compose.py` cobre o inventário de 28 estados e produz
`/tmp/zaplaud-rs-certify-<tela>-<tema>.png`, com Compose à esquerda e Rust à direita.
Ele não redimensiona imagens. Uma captura intermediária produziu 28 pares claros,
mas a inspeção encontrou estados incorretos em parte das capturas: permissões ainda
mostravam o menu, anexos ainda mostravam o seletor e os papéis estavam fora da posição
esperada de rolagem. Esses arquivos não devem ser usados como prova de paridade.
O runner recebeu validação do scrim modal e da confirmação real de dispensa para
rejeitar esses falsos positivos. Falta concluir sua execução sobre o renderer final.

Correções desta rodada:

- O shell desabilita seu `FocusScope` enquanto overlays externos estão abertos e restaura foco ao reabilitá-lo.
- Lista recupera foco ao fechar conversa, configurações e busca; o diálogo de papéis desabilita o fundo das configurações.
- Permissão usa a ação de atenção na barra, com ícones e cor extraídos do Kotlin, em vez de ocupar uma linha no transcript.
- Menu de conversa usa popup ancorado, sem scrim; menu da lista usa itens de 48 px e alinhamento do Compose.
- Barras de 64 px usam surface e centralizam avatares/ações; títulos de conversa usam 22 px.
- Linha selecionada conserva secondaryContainer e aplica a camada de hover/pressão por cima.
- Lista usa os espaçamentos tipográficos Material para título, subtítulo e horário.
- Markdown não mostra linguagem no cabeçalho do código; copiar aparece somente em hover.
- Marcador de lista Markdown não reserva 28 px fixos.
- Diálogo não reserva texto vazio; switches desabilitados não conservam a cor primária.
- Papéis têm nome acessível, campo visível de provedor/modelo e altura limitada ao conteúdo.
- Informações da sessão e bolhas expõem nomes e ações acessíveis.

### Execução reproduzível

```sh
python3 zaplaud-rs/scripts/keyboard-smoke.py \
  --binary /caminho/zaplaud --display localhost:89 --port 14789 --theme light
python3 zaplaud-rs/scripts/accessibility-smoke.py \
  --binary /caminho/zaplaud --display localhost:89 --port 14789
python3 zaplaud-rs/scripts/certify-compose.py \
  --rust-binary /caminho/zaplaud --display localhost:89 --port 14789 --theme both
```

Acessibilidade cria sessão D-Bus privada, inicia o registro AT-SPI e usa diretórios
isolados. O teste semeia somente um marcador de leitura fictício anterior às respostas,
para exigir título, estado e quantidade não lida no nome da linha. O runner Linux precisa
de `at-spi2-core`, `libatspi2.0-0`, `dbus-x11`, `xvfb`, `libxtst6` e `python3-pil`.
O workflow não foi alterado: os novos percursos ainda falham e não devem bloquear o CI antes de sua conclusão.

O Kotlin oculta o FAB de elenco quando há conversa selecionada
(`CastActions.kt:157-163`), mesmo que `ReadingScreen.kt` monte a lambda nessa situação.
Por isso os testes de seletor/pastas de elenco começam pela lista. Não houve alteração
dos guardas do controller para permitir uma ação ausente no Kotlin.

### Diferenças residuais e auditoria necessária por tela

Nenhuma linha abaixo está certificada na revisão final. Tempos relativos e portas da
fixture diferem entre execuções; não são diferenças de desenho. Além da rasterização,
`StyledText` do Slint 1.18.1 não expõe `letter-spacing` ou `line-height-factor`, ao
contrário de `Text`; a métrica de parágrafos Markdown multilinha permanece uma limitação
concreta a avaliar. Não autoriza diferenças em outros controles.

| Tela | Verificação restante |
| --- | --- |
| login | Recapturar claro/escuro; comparar campos, botão e fonte. |
| http-warning | Conferir altura dos botões e scrim após correção do diálogo. |
| list | Conferir alturas finais e fundo vazio: Compose/AWT mantém cinza não temático, inclusive no escuro. |
| search | Recapturar com campo aberto e validar recuperação de foco ao fechar. |
| two-servers | Conferir largura dos chips e origem ativa. |
| narrow | Conferir barra, voltar e compositor com 600 × 760. |
| relogin | Recapturar banners dos dois servidores sem chaveiro persistente. |
| conversation-idle | Conferir bolhas, Markdown, marcadores e horários finais. |
| conversation-working | Conferir linha de estado, compositor e parada. |
| conversation-attachments | Exigir chip real; captura intermediária ainda mostrava o seletor. |
| conversation-status-banner | Conferir texto queued e geometria do banner. |
| conversation-menu | Conferir popup final, subpáginas e chevrons no renderer final. |
| list-menu | Conferir popup de 150 px após mudança de posição. |
| permission-bash | Exigir folha real; painel direito é adaptação aceita. |
| permission-question-single | Exigir folha real, opções e controles nomeados. |
| permission-question-multi | Exigir folha real e múltiplas escolhas. |
| permission-question-omp | Exigir folha real com as três perguntas. |
| settings-appearance | Conferir switches desabilitados, rádios e barra final. |
| settings-roles | Conferir posição de rolagem calibrada por arraste do scrollbar. |
| settings-role-dialog | Conferir campo de modelo e altura após correção. |
| settings-quotas | Exigir configurações ainda abertas e cards reais da fixture. |
| aside | Conferir conteúdo e tópicos; painel direito é adaptação aceita. |
| session-info | Conferir valores, detalhes e nomes; painel direito é adaptação aceita. |
| cast-picker | Conferir seletor a partir da lista, sem conversa selecionada. |
| cast-folders | Exigir título/pastas após escolher Novo; não aceitar captura do seletor anterior. |
| cast-dismiss | Conferir largura do diálogo e confirmação real. |
| cast-resume-picker | Exigir dispensa consumida pela fixture e seletor real. |
| cast-resume | Exigir confirmação após escolher contato; não aceitar menu ou seletor anterior. |

Os registros anteriores sobre limitações de emoji são históricos. A decisão final
de renderer/fontes pertence à seção “Renderer e emoji” do README e à rodada `FinEmoji`;
esta rodada ainda não recapturou a interface sobre esse resultado.

### Últimos checks desta worktree

- `cargo build --release --locked -j 6`, `cargo fmt --check`,
  `cargo clippy --workspace --all-targets --locked -j 6 -- -D warnings` e
  `ZAPLAUD_KEYRING=memory cargo test --workspace --locked -j 6` passaram.
  Resultado observado: 193 testes, 19 suites; um ignorado e 35 filtrados pelo runner.
- `keyboard-smoke.py --theme light` passou pelos percursos de informações, menu,
  aside, dispensa, confirmação de shake, configurações, papel, seletor de anexo e
  permissão. Falhou depois em `cast-folders: Esc não fechou diálogo`; não há aceite
  global de teclado.
- AT-SPI abriu e leu a árvore real, mas o novo caso de não lidas falhou em
  `Linha sem título/estado/não lidas`. O marcador fictício precisa ser validado
  contra a conta real emitida pela fixture antes de atribuir a falha à UI.
- `conversation-smoke.py` foi executado nos dois temas. Ambos falharam em
  `A primeira carga não acompanhou o fim: ''`. A nova geometria exige investigar
  o alvo XTest de copiar e comprovar o acompanhamento real; não foi suprimida a assertiva.
- Logs completos: `/tmp/zaplaud-rs-certify-check-*.log`;
  resumo: `/tmp/zaplaud-rs-certify-checks.json`.
- Binário desta worktree: `/home/anderson/dados/zaplaud-rs-target/fin-certify/release/zaplaud`.
  Ele ainda usa o renderer anterior; o resultado final de `FinEmoji` não foi integrado aqui.
