# Plano 074: resultado da execução (cliente desktop do Zaplaud em Rust + Slint)

Executado em 2026-11-10 por ordem do dono, depois da medição do Zaplaud Compose no miranote (418–490 MiB de memória,
3,5–10% de CPU parado). Branch `feat/zaplaud-rs`, commit `6d19879`. Código em [`zaplaud-rs/`](../zaplaud-rs/README.md).

## Estado

As 27 capacidades da tabela de paridade do [plano 074](074-zaplaud-cliente-rust-slint.md) estão implementadas e
exercitadas contra o servidor de teste (`scripts/android-smoke.ts`), numa tela virtual (Xvfb), nos temas claro e
escuro. O que **não** foi verificado está na seção "Não verificado": é o que falta para declarar a troca concluída.

## Medição final (binário de release, mesmo método do plano 067)

| Medida | Rust, padrão (Skia por CPU) | Rust, modo leve (`SLINT_BACKEND=winit-software`) | Compose (067, servidor de teste) | Compose (miranote, uso real) |
|---|---:|---:|---:|---:|
| Abrir a janela | 108 ms | 84 ms | 1.020 ms | não medido |
| Memória parada (PSS), lista | 37 MiB | 29 MiB | 339 MiB | 418–490 MiB |
| Memória com conversa de 252 entradas | 39 MiB | 31 MiB | — | — |
| Memória com agente trabalhando | 41 MiB | 33 MiB | — | — |
| Memória depois de 20 aberturas de conversa | 42 MiB | 33 MiB | — | — |
| CPU parada, lista (30 s) | 0,83% | 0,33% | 0,10% (minimizado) | 3,5–10% |
| CPU com agente trabalhando | 0,50% | 0,53% | — | — |
| Threads | 6–9 | 6–9 | — | 48–51 |
| Tamanho | 31 MiB (um binário) | o mesmo binário | 192 MiB | 192 MiB |

Metas do plano: memória parada abaixo de 100 MiB e abertura abaixo de 300 ms: **atingidas** nos dois modos. CPU
parada "~0%": **não atingida** (0,3–0,8% de um núcleo; a origem medida são os despertares do laço de eventos, não um
temporizador do app). JSON completos em `/tmp/zaplaud-rs-release-measure-{skia,software}.json` (temporários).

O modo padrão usa o Skia desenhando por CPU porque é o único que mostra emoji colorido e sequências (bandeiras,
tons de pele) como o celular. Custa 8 MiB de memória e 10 MiB de binário a mais que o modo leve, que mostra os emoji
em preto e branco. FemtoVG e Skia com OpenGL foram medidos e reprovados (146–156 MiB).

## Como foi feito

Subagentes em paralelo, cada um numa worktree própria e dono de arquivos distintos, com um integrador por onda:

| Onda | Frentes | Resultado |
|---|---|---|
| Fase 0 | esqueleto, modelos, recursos gerados do Kotlin, CI; mapa do contrato (29 rotas, regras, telas) | 30 testes; `zaplaud-rs/docs/contrato/` |
| A | cliente HTTP/SSE, regras e redutores, store em disco e chaveiro, markdown, janela/login/lista | 116 testes |
| B | conversa, compositor, aprovação, elenco e bate-papo paralelo, configurações, sistema (avisos, bandeja, pacote), acabamento do kit | 156 testes |
| Acabamento | conversa, desempenho, visual, fixture/pacote, correções das duas revisões | 193 testes |
| Certificação | emoji/renderizador; 58 pares de telas contra o Compose real, teclado, acessibilidade | 196 testes |

Verificações que passam no commit final: `cargo fmt --check`; `cargo clippy --workspace --all-targets --locked -- -D warnings`;
`cargo test --workspace --locked` (196, 1 ignorado localmente e executado à parte); `npm test -- server` (1.271);
três roteiros gráficos (`conversation-smoke.py`, `keyboard-smoke.py`, `accessibility-smoke.py`) nos dois temas e nos
dois modos de desenho (12 de 12).

## Revisões independentes

- **Paridade** (27 capacidades contra o Kotlin): 20 iguais e 7 parciais, todas corrigidas com teste de regressão
  (trabalho bloqueante na única thread de rede, gravação a cada snapshot, aprovação presa depois de queda do stream,
  aviso que sumia em 10 s, avatar com falha pedido de novo sem parar, papel perdido ao minimizar, entre outros).
- **Segurança**: nada crítico. Corrigidos: `Retry-After` sem teto travava o login para sempre; fila de snapshots sem
  limite; pasta do socket de instância única previsível em `/tmp`; comando longo escondido na folha de aprovação
  (agora quebra linha, rola e mostra "mais N linhas"); anexo trocado depois de escolhido; arquivo de janela malformado
  derrubava a abertura; importação de preferências Java limitada às chaves conhecidas; CI com ações fixadas por hash.
  **O cliente Kotlin tem o mesmo `Retry-After` sem teto** (não alterado aqui).

## Harmonia com o Zaplaud do celular

- Textos (242), cores (48 papéis do Material 3, claros e escuros) e ícones são **gerados** de `android/shared/src/commonMain`
  na compilação. Mudou no Kotlin, muda no Rust.
- Estado em disco no mesmo formato do desktop Compose (provado nos dois sentidos com o store JVM real): dá para trocar
  de cliente sem perder servidores nem rascunhos.
- 58 pares lado a lado contra o Compose real (29 telas, claro e escuro). Diferenças que restam, por tela, em
  `zaplaud-rs/scripts/compare-compose.md`: rasterização de fonte; espaçamento de letras do markdown (limite do
  `StyledText` do Slint 1.18.1); e adaptações de desktop aceitas (aprovação, bate-papo paralelo e informações da sessão
  como painel lateral; ações da mensagem ao passar o mouse e no clique direito).

## Diferenças de comportamento assumidas

- Avisos reaproveitam o stream que a tela já usa, em vez de abrir um segundo `/api/stream` como o Android.
- Marcar como lida exige estar no fim da conversa **e** com a janela em foco.
- Seleção de texto vale dentro de um bloco; para a mensagem inteira há "Copiar mensagem" (limite do Slint).
- Instância única: abrir de novo traz a janela existente.
- Sem arrastar arquivos para a janela (o Slint 1.18.1 não repassa o evento); o seletor usa o portal do sistema, depois
  `zenity`/`kdialog`, depois um diálogo de caminhos.
- Caracteres de apresentação texto dentro das mensagens (▶, ✳) podem sair coloridos no modo padrão.

## Não verificado

- **Wayland** (o miranote usa Wayfire), **macOS**, chaveiro real (Secret Service/Keychain), notificações e bandeja
  numa sessão de verdade, seletor de arquivos nativo, leitor de tela real. Tudo foi exercitado por protocolo em
  serviços DBus falsos ou não pôde ser exercitado nesta máquina (sem tela, sem chaveiro).
- Instalação no miranote: a máquina estava desligada. Pacote pronto em
  `~/dados/zaplaud-rs-dist/final/zaplaud-linux-x86_64.tar.gz` (instala sem `sudo`; exige glibc 2.44, ok no CachyOS).
- `.deb`/`.rpm` gerados só para inspeção; o CI gera os de distribuição numa base Ubuntu 24.04. O workflow não foi disparado.
- Uso real do dono (fase 6 do plano).

## Decisões pendentes do dono

1. **Modo de desenho padrão**: Skia por CPU (emoji colorido, 37 MiB) ou modo leve (emoji em preto e branco, 29 MiB).
   Hoje o padrão é o Skia; trocar é uma variável de ambiente.
2. **Remover o desktop Compose** (`android/desktop`, `android/shared/src/jvmMain`) depois de usar o Rust no dia a dia.
   Hipótese de trabalho: sim. O layout de duas colunas em tela larga continua no Kotlin (iPad e tablet).
3. **Mesclar `feat/zaplaud-rs` em `producao`**: só acrescenta `zaplaud-rs/`, um workflow de CI e comandos opcionais na
   fixture de teste.
