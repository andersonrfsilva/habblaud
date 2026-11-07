# Auditoria deep do Zaplaud — 2026-11-07 (com medições)

## Base, ambiente e limites

- Código: `/home/anderson/Desenvolvimento/habblaud-043`, implementação `76b6e584`, HEAD documental `778466839`, já com a correção 043.
- Aparelho: Samsung M35 (`SM_M356B`, Android 16, arm64, tela 120 Hz), via ADB pela Tailscale. Medições com o celular no carregador, sem uso pelo dono, economia de energia desligada temporariamente. No fim foram restaurados: build debug `d0313c45…`, `low_power=1`, `stay_on_while_plugged_in=0`, serviços de acessibilidade originais; temporários removidos do aparelho; cópias locais do cache de conversas apagadas.
- Builds comparados, mesmo código: debug instalado (`debuggable`, ART `run-from-apk`) e release assinado com a mesma chave debug (`82de…a7eb`), instalado por cima sem perder login. O release do projeto não tem minificação (`android/app/build.gradle.kts:32-36`). Para atribuição em release foi usado um release com `<profileable android:shell="true"/>` apenas num worktree descartável, já revertido.
- Serviços de acessibilidade **do próprio usuário** ativos no M35: Google Reading mode e APH (NLL, gravação de chamadas). Eles fazem o Compose manter a árvore semântica, e isso aparece no custo medido. Não são artefato da medição: o `uiautomator` não estava ativo durante os traces.
- Ferramentas: `dumpsys gfxinfo`, Perfetto (FrameTimeline, sched, atrace), `simpleperf` com callstack, probe ART em `app_process` dentro do sandbox do app, `/proc/<pid>/stat`.
- Fora do alcance: iOS (Mac `macbook-air-de-mira` offline durante toda a sessão). A4 continua sem medição.
- Cada configuração de rolagem teve 1 rodada; release com perfil teve 2. Os números indicam magnitude, não significância estatística. Os testes de CPU ociosa usaram 20–60 s por estado.

Roteiro fixo de rolagem (≈65 s, `run_measure`): lista (2 vai-e-volta) e depois três conversas reais abertas pela busca (`ADB`, `RAG`, `MESH`). Em cada conversa: 6 flings para cima, 6 para baixo e voltar.

## Medições principais

### Rolagem: debug × release × acessibilidade

| Configuração | Quadros | Janky (FrameTimeline) | Janky legado | p95 (ms) | p99 (ms) | UI thread lenta |
|---|---|---|---|---|---|---|
| Debug recém-instalado (JIT frio) | 1.798 | 16,57% | 30,37% | 65 | 150 | 288 |
| Debug aquecido | 2.141 | 7,05% | 15,23% | 30 | 61 | 140 |
| Debug aquecido, acessibilidade desligada | 2.205 | 5,49% | 10,20% | 24 | 61 | 109 |
| Release recém-instalado (`verify`) | 2.494 | 4,85% | 8,02% | 22 | 44 | 104 |
| Release AOT `speed-profile` | 2.451 | 4,81% | 8,12% | 21 | 38 | 96 |

Partida a frio (`am start -W`, `TotalTime`, 3 amostras): debug **1.055 / 918 / 911 ms**; release **280 / 240 / 238 ms**.

Perfetto, debug aquecido, 70 s: 951 compilações JIT "baseline" (1.052 ms no pool JIT), 15 GCs de compactação (567 ms). `simpleperf` da thread principal no debug: **41,9% das amostras dentro de `libart.so`** (interpretador e pontes), contra 47,5% em código JIT.

### CPU e quadros sem ninguém tocar

| Estado | Build | CPU (% de 1 núcleo) | Quadros/s |
|---|---|---|---|
| Lista parada, agentes trabalhando | debug | 43,0 | 10–14 |
| Lista parada, agentes trabalhando | release | 24,7 | 9,2 |
| Conversa aberta com agente trabalhando | debug | 86,5 | 60 |
| Conversa aberta com agente trabalhando | release | 76,7 | 60,3 |
| **Tela bloqueada**, activity `STOPPED`, app deixado na lista | release | **27,4** (60 s) | — |

### Atribuição por função (simpleperf, thread principal, % inclusivo)

| Cenário | Acessibilidade do Compose | Outros destaques |
|---|---|---|
| Rolagem, debug | `getCurrentSemanticsNodes` 14,2; `checkForSemanticsChanges` 9,8; eventos de rolagem 5,6 | Medida da `LazyColumn` 9,1; layout de texto 4,2; `EntryView` 3,1; Markdown 1,7; `groupTurn` < 0,4 |
| Lista parada, debug | `checkForSemanticsChanges` 25,6; envio de eventos estruturais 20,6 | `StatusLine` 8,6; medida de texto 13,6; snapshot do stream tratado na UI 8,6 |
| Tela bloqueada, release | lambda do verificador semântico 58,1 | `Choreographer.doFrame` 25,2; `StatusLine` 10,8; medida de texto 16,5 |
| Conversa parada, release | ~30 (inclui `boundsUpdatesEventLoop` 9,7) | `InfiniteTransition` 13,0; RenderThread 34,5% e driver Mali 17,3% do processo |

### Projeções da conversa no ART do M35 (caches reais)

Probe `app_process` dentro do sandbox, funções produtivas `decodeConversation`, `visibleEntries`, `groupTurn` e `recoveredErrors`, 16 conversas reais (até 500 entradas, 527 KiB):

- Projeção completa por recomposição: **0,005–3,46 ms**; `groupTurn` sozinho: 0,001–3,08 ms (pior caso: conversa com 38 pensamentos e 20,7 mil caracteres num turno).
- Decodificação do cache: 0,07–13,5 ms (roda em `Dispatchers.IO` no Android).
- Maior turno real: 92 pensamentos e 4,6 mil caracteres. A medição sintética anterior (1.000 × 4 KiB) não representa o uso real.

## Achados reclassificados

| ID | Achado | Prioridade | Evidência medida | Confiança |
|---|---|---|---|---|
| B1 | Indicadores contínuos mantêm a UI renderizando, inclusive com tela bloqueada | P1 | 24,7% de um núcleo na lista parada; 76,7% e 60 qps na conversa parada; 27,4% com tela bloqueada (release) | ALTA |
| B2 | Build instalado é debug: interpretador, sem AOT e JIT frio a cada processo novo | P1 | Janky 16,6% → 4,8%; p99 150 → 38–44 ms; partida 0,9–1,06 s → 0,24 s | ALTA |
| B3 | Árvore semântica grande e instável multiplica o custo com acessibilidade ligada | P1 | 25–58% da CPU da thread principal; desligar acessibilidade: janky 7,05% → 5,49%, p95 30 → 24 ms | ALTA (causa); MÉDIA (ganho do fix) |
| A3 | Servidor offline remove ações de outro servidor saudável | P2 | Reproduzido em emulador com duas fixtures; FAB some ao matar B | ALTA |
| A5 | Falha ao gravar "lidos" derruba o app | P2 | Crash 1/1 com DataStore sem escrita; cache de conversa não derruba (0/1) | ALTA no caminho; gatilho real raro |
| A2 | Teste de interrupção aceita placeholder | P2 | 14/50 falhas (28%); variante esperando o ID: 0/50 | ALTA |
| B4 | Snapshot do stream tratado na thread da UI | P3 | 8,6% da thread principal com a lista parada (debug) | MÉDIA |
| A6 | Gate `VpnGuardTest` vazio (`NO-SOURCE`) | P3 | Execução 043 | ALTA |
| A7 | README diz "uma instalação por vez" e diverge no SDK | P3 | `README.pt-BR.md:249,264,300` | ALTA |
| A1 | `groupTurn` quadrático | P3 (antes P1) | ≤ 3,1 ms em dados reais; < 0,4% da CPU no perfil | ALTA |
| A4 | Leitura de cache/estado iOS na thread da UI | sem medição | Mac offline | caminho estático ALTA; impacto desconhecido |

### B1 — Indicadores contínuos e tela bloqueada

Código:

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:534-549`: `StatusLine` usa `LaunchedEffect(animate) { while (true) { delay(120); now = … } }`. Isso escreve estado 8,3 vezes por segundo, recompõe e remede o texto do cronômetro.
- Mesmo arquivo, `316`: uma `StatusLine` por sessão trabalhando na lista. `405`: outra na conversa aberta.
- Mesmo arquivo, `562-577`: `WorkingIndicator` com `rememberInfiniteTransition` (avatar pulando e três pontos), quadro a quadro enquanto o agente trabalha. A translação do avatar muda os limites do nó a cada quadro e alimenta `boundsUpdatesEventLoop` da acessibilidade.
- O laço não depende do ciclo de vida. Com a activity `STOPPED` e a tela apagada, o perfil ainda mostra `Choreographer.doFrame` → recomposição da `StatusLine` → verificação semântica.

Impacto: CPU contínua com o app aberto ou bloqueado sobre ele; aquecimento e bateria. O valor bloqueado (27,4%) foi medido com a lista na tela antes de bloquear e agentes trabalhando.

Correção indicada:

- Atualizar o cronômetro no ritmo do que ele mostra (segundos). O spinner pode girar via `graphicsLayer`/draw, sem escrever estado de composição.
- Suspender os laços abaixo de `STARTED`, por exemplo com `repeatOnLifecycle` ou equivalente multiplataforma já usado no projeto.
- Limitar a animação do `WorkingIndicator` a desenho, sem mudar limites do layout: mover o conteúdo dentro de um nó de tamanho fixo ou animar no `drawBehind`. Manter o respeito a `animationsEnabled()`.

Aceite: na lista parada com agentes trabalhando, ≤ 2 qps e CPU perto de zero; na conversa parada, quadros apenas na frequência escolhida para o indicador; com a tela bloqueada, CPU ≈ 0 no processo, salvo stream de notificações. Medir com o mesmo roteiro.

### B2 — Distribuir release em vez de debug

- O APK no M35 desde 2026-11-04 é `debuggable` (`aapt`: `application-debuggable`), sem compilação AOT (`run-from-apk`). O release do projeto não tem `isMinifyEnabled`. Não existe chave de release configurada nesta máquina; o README exige as quatro variáveis `HABBLAUD_*`.
- Trocar para release assinado com outra chave exige desinstalar e perde login e rascunhos. Assinar o release com a mesma chave atual preserva os dados, e foi isso que a medição fez.
- Decisão do dono: manter a chave debug como identidade do app pessoal (simples, sem migração) ou criar chave de release definitiva e fazer uma reinstalação planejada. Em ambos os casos, adicionar R8 com teste de fumaça, porque KMP e serialization podem exigir regras.

Ganho medido: maior efeito isolado desta auditoria, especialmente após o processo morrer (partida e primeiros minutos em JIT frio).

### B3 — Reduzir o custo semântico sem prejudicar acessibilidade

- Com acessibilidade ativa, o Compose refaz o mapa semântico de toda a tela e compara mudanças a cada atualização. Bolhas com `SelectionContainer` e Markdown geram muitos nós.
- Mudanças que reduzem churn: B1 (menos atualizações); cronômetro e spinner com semântica estável, sem texto mudando a cada tick; bolhas com `semantics(mergeDescendants = true)` onde a leitura por partes não for necessária.
- Não remover semântica útil para leitores de tela. Validar com TalkBack: conteúdo lido, botões acionáveis.
- Atualizar Compose só com medição: versões novas mudam a semântica, e a dependência atual (BOM 2026.09.00, UI 1.12.1) coincide com a estável consultada.
- Lado do usuário (decisão dele): o serviço APH declara `eventTypes` vazio e mesmo assim ativa a acessibilidade de todos os apps. Desativá-lo, se não for usado, reduz o custo em todos os apps Compose.

### Demais achados

- **A3**: usar o estado e as permissões de `actionOrigin` para ações desse servidor (`ReadingScreen.kt:108-121,168`; `CastActions.kt:63-64,148-153`). Reproduzido com servidor offline; 401 não testado.
- **A5**: `ReadingController.kt:144-148` lança `setSeenReplies` em `persistScope` sem tratar erro; `LocalStateStore.android.kt:301-303` propaga `FileNotFoundException`. Tratar falha localmente: manter marcas em memória, tentar de novo depois e não apagar dados. O cache de conversa já captura (`LocalStateStore.android.kt:160-168`). iOS sem medição.
- **A2**: o teste deve esperar `control.id == "stop1"` e depois `status == "unknown"` (`MessageSendTest.kt:149-161`). Com isso: 0/50 falhas, contra 14/50 no original. É o que bloqueia o aceite da 043.
- **B4**: `ReadingController.kt:96-115` trata cada snapshot do `/api/stream` no dispatcher da UI (parse e montagem da lista). Mover para fora da main exige cuidado com o estado do controlador; fazer depois de B1, que tem ganho maior.
- **A1**: manter apenas como limpeza oportunista (`StringBuilder` por grupo); não justifica plano próprio.

## Segurança e dependências

- Revisão de segurança sem vulnerabilidade alcançável sustentada pelo código (origem, cookies, redirects, TLS, armazenamento seguro, manifesto, notificações).
- Dependências: 284 coordenadas Maven resolvidas (release Android, shared Android e iOS arm64) consultadas no OSV (`api.osv.dev/v1/querybatch`): **zero alertas**. Evidências em `/home/anderson/Desenvolvimento/habblaud-deps/deps-evidence/`. `npm audit --omit=dev`: zero.
- Defasagens sem impacto de segurança conhecido: Markdown renderer 0.38.0 → 0.46.0, DataStore 1.1.7 → 1.3.0, Activity 1.11.0 → 1.13.0, Serialization declarada 1.9.0 → 1.12.0 (Android resolve 1.11.0; iOS 1.9.0), AGP 9.1.1 → 9.5.1.

## Evidências

- Traces Perfetto: `/tmp/zprobe/{debug1,debug2,debug3,debug4,release1,release2}.pftrace`; análise `/tmp/zprobe/analyze.py` (venv `/tmp/ptrace-venv`).
- Perfis simpleperf resumidos: `/tmp/zprobe/children.txt` (rolagem debug), `bg-children.txt` (lista parada debug), `relbg.txt` (tela bloqueada release).
- Probe ART: `/tmp/zprobe/ZProbe.java`.
- Reproduções A2/A3/A5: worktree `/home/anderson/Desenvolvimento/habblaud-audit` (testes descartáveis, não commitados) e `/tmp/zaplaud-audit/`.
- Diretórios em `/tmp` são temporários; copiar o que precisar ser preservado.

## Planos gerados

Status canônico em `plans/README.md`. Mapeamento: B1 → 044, B3 → 045, B2 → 046 (chave atual mantida, decisão do dono), A2 → 047, A3 → 048, A5 → 049, B4 → 050, A6+A7 → 051, A1 → 052, A4 → 053.

Depois da medição, o dono removeu os serviços APH e Reading mode do M35. O custo de acessibilidade medido (B3) não se aplica mais ao aparelho dele; por isso o 045 caiu para P3. No M35, os ganhos esperados vêm de 044 e 046.
