# Implementation Plans

Gerado pela skill improve. 001: 2026-10-24 (variante `plan`). 002–006: 2026-10-24, auditoria `deep` no commit
`4931abc` (baseline: `npm run typecheck` ok, `npm test` 173 arquivos / 2080 testes ok, `npm run build` ok,
`npm audit --omit=dev` 0 vulnerabilidades). 007–009: 2026-10-25, auditoria `deep` com foco em desempenho do
escritório animado, no commit `7af1def` (medição no Chromium sem GPU contra o Habblaud de produção; números em cada
plano). 010–013: 2026-10-25, auditoria `deep` da CPU da interface web com a economia de energia ligada (M1 a
20–30%), no commit `31368cb` (`fork/main`), **medida com GPU de verdade** (Chromium com
`--ozone-platform=headless --use-gl=angle --use-angle=gl-egl`, Intel UHD 630, 1440×900 em densidade 2). 014:
2026-10-25 (variante `plan`), no commit `dfbdde3` (baseline: `npm run typecheck` ok, `npm test` 176 arquivos / 2119
testes ok). 015: 2026-10-25 (variante `plan`, a partir do spike 013). 016–022: 2026-10-25, auditoria `deep` geral no
commit `af73a23` (baseline: `npm run typecheck` ok, `npm test` 181 arquivos / 2180 testes ok, `npm audit` 0
vulnerabilidades); o plano 034 da mesma auditoria fica no repositório `omp-tenant`. 023: 2026-10-25 (variante
`plan`), no commit `e689895` (baseline: `npm run typecheck` ok); decisões do dono no próprio plano.

**Onde executar:** sempre neste clone de desenvolvimento (`~/Desenvolvimento/habblaud`) ou numa worktree dele, nunca
em `~/apps/habblaud`, que é o Habblaud de produção (o `omp-tenant release` lê os commits de lá). A produção só recebe
um plano pronto e revisado, pelo operador. Cada executor: leia o plano inteiro antes de começar, respeite as STOP
conditions e atualize sua linha ao terminar.

## Execution order & status

| Plan | Title | Priority | Effort | Depends on | Status |
|------|-------|----------|--------|------------|--------|
| 001  | Trocar modelo e reasoning de um agente omp pela gaveta | P2 | M | — | DONE (commit a2d25fd) |
| 002  | Guard de borda só aceita Origin da mesma origem | P1 | S | — | DONE (merge 1cc3e74) |
| 003  | Extensão do omp não entrega na sessão nova o que buscou para a anterior | P1 | S | — | DONE (merge 5f22b5a) |
| 004  | Revogação remota falha fechada; verificador de versão não derruba o servidor | P3 | S | — | DONE (merge fba172b) |
| 005  | Traduções completas e `i18n:check --strict` no CI | P3 | S | — | DONE (merge 69828b3) |
| 006  | Introdução dos READMEs sem "nenhuma chamada externa" | P3 | S | — | TODO |
| 007  | Escritório parado a 30 qps (15 sem foco); 60 só com câmera em movimento ou entrada | P1 | S | — | DONE (merge f0e2e60) |
| 008  | Opção "Economia de energia": escritório desenhado em 1× (pixelated) | P2 | M | — | DONE (merge 994d2c4) |
| 009  | Telas, janelas e relógios repintados só quando mudam (cache por item) | P3 | M | — | SUPERSEDED por 012 (medido sem GPU, onde o ganho não aparece; branch apagado) |
| 010  | "Economia de energia" a 10 qps parado (5 sem foco), laço dormindo num timer | P1 | S | — | DONE (merge 9c4d4d0, em produção) |
| 011  | 30 qps só quando algo anda na tela; sem movimento, 10 (5 sem foco) | P1 | M | 010 | DONE (merge 9c4d4d0, em produção) |
| 012  | Telas, janelas e relógios em cache (refeito do 009, medido com GPU) | P2 | M | — | REJECTED (2026-10-25: −2% a 30 qps, dentro do ruído; ver "Considered and rejected") |
| 013  | Spike: móveis e props desenhados em lote (atlas, camadas) | P3 | L | — | DONE (2026-10-25; `plans/013-resultado.md`). B+C: −14 pontos a 30 qps. Virou o plano 015 (branch do protótipo apagado) |
| 014  | Bate-papo paralelo com um agente do omp numa janela lateral do terminal, com histórico em disco | P2 | L | — | DONE (merge 47aae29) |
| 015  | Móveis do prédio numa camada única e props estáticos em duas camadas (B + C do 013) | P3 | L | 013 | DONE (merge fbf77db, em produção; 72,8% → 58,5% a 30 qps, 31,1% → 25,6% a 10 qps) |
| 016  | Pergunta paralela cancelada na fila não roda; confirmação de entrega repetida | P1 | S | — | DONE (merge 1da57cc + 5b7dca2, em produção e nos usuários; vale nas sessões do omp reabertas) |
| 017  | Erro ao montar o snapshot não derruba o servidor; desligamento sempre termina | P1 | S | — | DONE (merge 799cefd, em produção e nos usuários) |
| 018  | Teclas do terminal interativo sempre no pty certo ao trocar de agente | P2 | S | — | DONE (merge 015f259, em produção; verificação manual com dois agentes não feita) |
| 019  | Título da sessão do omp reescrito não duplica o histórico (medido: ≤ 120 ms de releitura) | P3 | M | — | DONE (merge a625399, em produção; só a Parte A) |
| 020  | Login remoto: freio por origem (proxy confiável) e cookie `Secure` atrás de HTTPS | P1 | M | — | DONE (merge 799cefd, em produção e nos usuários). Medido: o pod vê o Traefik como `10.89.0.2` (contêiner `cockpit-traefik`), **não** como o IP do host; o passo 8 deste plano, que mede com `curl` do host, dá o valor errado. Provado pela internet: origem bloqueada (429, 31 s) enquanto outra origem recebe 401; cookie com `Secure` |
| 021  | Content-Security-Policy na página | P2 | S | — | DONE (merge b580ba9, em produção; 0 violações na página do dono) |
| 022  | Docs: rotas da API nos READMEs e no `server/README.md`; AGENTS.md cita o omp | P3 | S | — | DONE (merge b0170b2) |
| 023  | Ver e baixar os arquivos da pasta de trabalho pelo botão "Arquivos" da gaveta (salas + `~/projetos`) | P2 | L | — | DONE (merge 5205df7 + 0c72d86: a janela fecha só pelo X ou Esc, nunca por clique; em produção e nos usuários, release 0c72d866449d + sync em 2026-10-26) |
| 024  | Estrela para favoritar sessões no Histórico e na gaveta (agente e sala), fixadas no topo do Histórico (só cliente, `localStorage`) | P3 | S | — | DONE (merge 592f170 em `producao`; em produção e nos usuários, release 7eeea6e48ff3 + instalar + sync em 2026-10-26) |
| 025  | Olho em pixel art na faixa do terminal liga e desliga o advisor do omp (`/advisor on` / `off`; estado = último comando confirmado, só cliente) | P3 | S | — | DONE (branch `advisor/025-olho-do-advisor`, worktree `../habblaud-025`; não mesclado nem publicado) |
| 026  | Timelapse e janela do Habblaud World baixados só quando abertos (issue upstream #73, seção 4) | P3 | M | — | DONE (branch `advisor/026-bundle-sob-demanda`, worktree `../habblaud-026`, `5cfd229` + `53e6a57`; não mesclado). JS na abertura 879.994 → 822.375 B (294.100 → 278.043 gzip). Com o mundo configurado (padrão), `settings-world` baixa logo após abrir: o ganho grande é para quem não usa o World |
| 027  | Cache de sprites descarta a quarta parte mais antiga ao encher, em vez de zerar (issue upstream #73, seção 3) | P3 | S | — | DONE (branch `advisor/027-cache-de-sprites`, worktree `../habblaud-027`; não mesclado) |
| 028  | Botão no canto inferior direito pausa e retoma as animações do escritório (não salvo; some no timelapse e na cidade) | P3 | S | — | DONE (branch `advisor/028-pausar-animacoes`, worktree `../habblaud-028`; revisão moveu os avisos do canto para cima do botão; não mesclado) |
| 029  | Anexos no prompt: seletor, colar e arrastar, armazenamento centralizado por instalação e mounts isolados por tenant | P2 | L | 030 | DONE (ambiente pessoal e cinco tenants publicados em `7e164a5`; mounts `0ed95c9` integrados em `omp-tenant/main` por `20dac54`; upload HTTP, leitura RO e isolamento verificados em todos. Ver `030-resultado.md`) |
| 030  | Upload binário por stream para disco, orçamento compartilhado e auxiliar sem base64 | P1 | L | — (base obrigatória: candidata 029 `c19afb0`) | DONE (código `155e734`, merge `0697614`; revisão `7e164a5` publicada no ambiente pessoal e nos cinco tenants, todos saudáveis. Ver `030-resultado.md`; sem push) |
| 031  | Elenco de personagens fixos (principais, advisor, subagentes por tipo) com salas permitidas; ocupado = sorteio; salas só organizam | P2 | L | — | DONE (branch `advisor/031-elenco`, `5a2e258`..`ba8bf49`, mesclado no branch `rpg` em `2ff0e3d` (worktree `../habblaud-rpg`); fora de `producao`. Revisão: 188 arquivos / 2283 testes, typecheck, i18n estrito e build ok; smoke de migração e API refeito pelo revisor. Desvios autorizados: montagem da tela em `client/src/ui/index.ts`; fixtures `custom`→`cast` só em `shared/world/projection.test.ts`. `HABBLAUD_CAST` de ponta a ponta não provado com omp real: a extensão instalada é a de produção) |
| 032  | Chamar (Continuar/Nova) e dispensar personagens do elenco: omp no host via auxiliar + tmux `hb-<id>`, perfil (modelo, instruções), ociosidade; reserva apagada no lounge | P2 | L | 031 | DONE (branch `advisor/032-chamar-dispensar`, `514a1ef`..`5676f9a`, mesclado em `rpg` em `023c71d`. Revisão: 190 arquivos / 2296 testes (1 falha intermitente não identificada em 1 de 4 rodadas), typecheck, i18n estrito e build ok. Prova com omp real: Nova, Dispensar, Continuar (mesmo `sessionId`), 409, auxiliar ausente e reserva no lounge nos dois prédios; ociosidade só por teste com relógio falso. Desvios autorizados: `office.ts`; `-e HABBLAUD_PORT` no tmux; prompt/modelo com `-` inicial recusados; reserva em pufe→poltrona→sofá a 0,55; cartão "escritório vazio" some com reserva. Unidade `deploy/podman/habblaud-bridge.service` não ativada) |
| 033 | Modos de envio OMP: Acrescentar agora (padrão), Enfileirar e Mudar direção nas caixas da gaveta e do terminal | P2 | M | — | IN PROGRESS (commit `484ac82` integrado em `producao` e publicado somente no standalone em 2026-11-01 por autorização explícita, mesmo com limite de `steer`; aguardando teste do usuário. Tenants intactos; detalhes no plano) |
| 034 | Cliente Android nativo produtivo: sessões, conversa, mensagens/anexos, aprovações e interrupção OMP, sem escritório/WebView | P2 | L | Proteção de destinatário no próprio plano; limite do 033 preservado | BLOCKED (branch `advisor/034-cliente-android-produtivo`, worktree `../habblaud-034`, `9073015`..`f9a5fad`; não mesclado. Etapas 0–6 feitas; revisão completa até `1e2bbae` e incremental até `f9a5fad` sem blocker de produção. Unit 41/41 debug e release, lint, assembleDebug, gates Node da etapa 0 ok. Pendências: (a) instrumentação completa instável — revisor: 1 falha VPN em 1 rodada; executor: 2 rodadas 11/11 e 1 travada em `imeDraftSurvivesRecreationRotationAndLargeFont`; revisão incremental aponta latches de `TestVpnService` reaproveitados entre testes e teste de queda sem espera de rota; (b) queda de VPN durante chamada HTTP lenta à fixture não provada; (c) etapa 7 sem aparelho físico, Tailscale no celular, medição real e chave. `uplink.test.ts`: `esperando: tema` também falha na base; `esperando: reconectou` não reproduzido na base) |
| 035 | Investigar gravação tardia do cache após limpeza | P1 | M | — | DONE (commit `ef13ee5` mesclado no branch Android; falha de gravação do DataStore não simulada) |
| 036 | Executar testes instrumentados Android no CI | P1 | M | — | DONE (implementação mesclada; 13 testes passaram localmente; execução no GitHub Actions adiada) |
| 037 | Confirmar login HTTP sem proibir LAN/VPN | P1 | M | — | DONE (implementação mesclada; validação no celular adiada; dois testes falharam em viewport pequeno) |
| 038 | Investigar ordenação da última sessão Android | P2 | M | 035 | DONE (commit `ef13ee5` mesclado; 24 testes instrumentados passaram antes da integração) |
| 039 | Ocultar detalhes de notificações na tela bloqueada | P1 | M | — | DONE (implementação mesclada; teste instrumentado aprovado; validação visual no celular adiada) |
| 040 | Monitorar dependências Gradle no Dependabot | P2 | S | — | DONE (commit `4150994` mesclado; YAML validado; validação do serviço remoto pendente) |
| 041 | [Zaplaud no iPadOS com Kotlin Multiplatform e instalação pelo SideStore](041-zaplaud-ipados-kotlin.md) | P2 | L | Base Android atual; sem dependência de concluir 033 | BLOCKED (merge `2aa8847` em `producao`, [PR #1](https://github.com/andersonrfsilva/habblaud/pull/1); 79 testes host, 105 instrumentados Android, 68 testes nativos, smoke Apple integrado e IPA atualizados aprovados. iPad pareado; aguardando Modo de Desenvolvedor e instalação física. Evidências em [041-resultado.md](041-resultado.md). Sem deploy) |
| 042 | [Zaplaud conectado a vários servidores ao mesmo tempo: lista única com filtro, login/saída por servidor, notificações de todos](042-zaplaud-multiplos-servidores.md) | P2 | L | — | DONE (revisão aprovada em 2026-11-04: branch `advisor/042-zaplaud-multiplos-servidores`, worktree `../habblaud-042`, `ccf58f7`..`fcd2649`; não mesclado. Linux: host/unit/lint/APK e compilação dos testes iOS ok; 101 instrumentados shared; fixture completa 117/117 antes das duas últimas correções, depois 16 testes de interface (HTTP, notificação e fixture sem `imeDraftSurvivesRecreationRotationAndLargeFont`, que trava desde antes). Mac da Mira: `scripts/ios-native-tests.sh` 77/77; `scripts/ios-smoke.sh` falhou no branch até `fcd2649` (toque em `http-confirm` não chegava com o login aninhado em `Column > Box(weight)`); depois, 2 de 3 rodadas passaram. A falha restante ("Neither element nor any descendant has keyboard focus" em `server-url`) também aparece em `c468bc1` (1 de 3 rodadas): instabilidade preexistente. Prova manual com duas origens e atualização por cima de `c468bc1` preservou login e rascunho. Desvios autorizados: gates de build dos passos 4–5 adiados para depois do 6; `change_*` removidos também de `app/src/main/res/values/strings.xml`; aviso global de erro virou diálogo) |
| 043 | [Zaplaud: impedir crashes de avatares, corrigir URL e retirar cache da thread da interface](043-zaplaud-estabilidade-avatares.md) | P1 | M | Base 041/042 já integrada | BLOCKED (implementação `76b6e584`, branch `advisor/043-zaplaud-estabilidade-avatares`, incluída na integração `advisor/zaplaud-integracao-044-052`; sem merge/push. O gate host foi fechado pelo 047: 95/95 na integração, 139 instrumentados. M35: nenhum crash de 06:08 a 10:55 de 2026-11-07, contra 9 crashes de rede entre 05:42 e 06:02 no debug antigo. Pendente: testes iOS, Mac offline) |
| 044 | [Zaplaud: indicadores de "trabalhando" sem CPU ociosa nem com tela bloqueada](044-zaplaud-indicadores-sem-consumo-ocioso.md) | P1 | M | Base 043 (`778466839`) | DONE (`3a334a9` + `32eaaa7` na integração, instalado no M35. Medido no M35 em 2026-11-07, carregando, sem serviços de acessibilidade, release `speed-profile`, antes = `7784668`: lista parada 22,9 → 1,1 qps e 55,7% → 5,2% de um núcleo (aceite ≤ 25% do antes); tela bloqueada, activity `STOPPED`, 23,8% → 0,3% (aceite ≤ 3%). Depois, por decisão do dono, `ffed6ac` removeu a animação de "digitando" e pôs o avatar parado na linha de estado: conversa parada com agente trabalhando 74,5% → 7,5% e 60 → 1,5 qps; suíte 95 host + 139 instrumentados. Pendente fora do aceite Android: iOS) |
| 046 | [Zaplaud: release assinado com a chave atual no celular pessoal (R8 opcional)](046-zaplaud-release-na-distribuicao-pessoal.md) | P1 | S | — | DONE (chave `~/.config/habblaud/zaplaud-release.jks`, mesmo SHA-256 `82de…a7eb`, senha só em `~/.gradle/gradle.properties` 600; backup só local, por decisão do dono. R8 em `d2c691d`: APK 15,3 → 2,3 MB, smoke no emulador sem crash. Instalado no M35 com login preservado e sem `DEBUGGABLE`. Passo 4 no M35, duas rodadas do roteiro de rolagem: janky 1,34% e 1,30% (aceite ≤ 6%), p99 24 e 25 ms (≤ 50); partida a frio 281/203/211 ms, mediana 211 (≤ 400)) |
| 047 | [Zaplaud: teste de interrupção espera a operação real (fecha gate da 043)](047-zaplaud-teste-interrupcao-sincronizado.md) | P1 | S | Branch 043 | DONE (`3b62041`; 20/20 execuções do teste alvo; host 84/84 na branch, 95/95 na integração) |
| 048 | [Zaplaud: servidor offline não bloqueia ações de outro servidor](048-zaplaud-acoes-por-servidor-isoladas.md) | P2 | S | — | DONE (`c7c4148`; 5 testes de comportamento. Reprodução no emulador com duas fixtures: depois de encerrar B, "Chamar contato" de A continuou visível por 60 s; o probe da auditoria, que esperava o botão sumir, expirou) |
| 049 | [Zaplaud: falha ao gravar estado local não derruba o app](049-zaplaud-falha-de-gravacao-local-nao-derruba.md) | P2 | S | — | DONE (`a85c7f5`; `PersistenceFailureInstrumentedTest` 3/3 no emulador com DataStore sem escrita. Pendente: iOS) |
| 053 | [Zaplaud: armazenamento iOS fora da thread da UI](053-zaplaud-ios-armazenamento-fora-da-ui.md) | P2 | M | Mac acessível | BLOCKED (Mac `macbook-air-de-mira` offline durante toda a execução) |
| 045 | [Zaplaud: árvore de acessibilidade menor e estável](045-zaplaud-semantica-acessibilidade-estavel.md) | P3 | M | 044 | REJECTED (executado na branch `advisor/045-zaplaud`, não integrado. Depois do 044, a lista gerou só 1 evento de acessibilidade em 20 s; o cronômetro em minutos não reduziu eventos (1 → 2) nem nós (62 → 62), e tiraria os segundos do TalkBack. A mesclagem de bolhas foi descartada: TalkBack 16 não validou leitura, links e seleção) |
| 050 | [Zaplaud: medir e, se valer, tirar parse do snapshot da UI](050-zaplaud-snapshot-fora-da-thread-da-ui.md) | P3 | S–M | 044 | REJECTED (medido: 20 snapshots/min de ~53 KB no servidor real; parse no ART do M35 0,57 ms p50 / 0,64 ms p95, abaixo do limite de 2 ms e 30/min) |
| 051 | [Zaplaud: remover gate VPN vazio do CI e corrigir README do app](051-zaplaud-gate-ci-e-readme-movel.md) | P3 | S | — | DONE (`e40e4c2`; `i18n:check --strict` ok; YAML não validado por parser) |
| 052 | [Zaplaud: agrupamento linear de pensamentos e ferramentas](052-zaplaud-agrupamento-linear.md) | P3 | S | — | DONE (`0c52efa` testes de caracterização antes, `46f9dfd` implementação; 11/11) |
| 054 | [Zaplaud: roteiro de capturas confirma login HTTP e usa rótulos atuais](054-zaplaud-roteiro-de-capturas-funcionando.md) | P2 | S | — | BLOCKED (`7248b3e` + `e376ab9`/`20058cc` na integração: login HTTP, contato, conversa e menu corrigidos; 29 capturas claras até `elenco-aguardando`. Para em "Retomar conversa": o botão também não aparece no APK de `dab677d` com a fixture, então é divergência anterior entre roteiro e fixture/elenco; tema escuro não gerado pelo roteiro) |
| 055 | [Zaplaud: ícones Material no lugar de caracteres de texto](055-zaplaud-icones-material.md) | P2 | S | 054 | IN PROGRESS (implementado `2aac91a`; gate "roteiro exit 0" pendente do 054; capturas equivalentes feitas à mão) |
| 056 | [Zaplaud: ferramentas e pensamentos em linhas compactas com seta](056-zaplaud-ferramentas-e-pensamentos-compactos.md) | P2 | S | 055 | IN PROGRESS (implementado `9d48faa`; `conversa-fim`, `pensamento-expandido` e `ferramenta-expandida` geradas no claro; escuro à mão; gate do roteiro pendente do 054) |
| 057 | [Zaplaud: "Abertas" e "Recentes" em botão segmentado](057-zaplaud-abertas-recentes-segmentado.md) | P3 | S | — | IN PROGRESS (implementado `f1dd022`; lista conferida nos dois temas e com fonte 1,3; gate do roteiro pendente do 054) |
| 058 | [Zaplaud: cores de conexão, contexto e cota vêm do tema](058-zaplaud-cores-de-status-do-tema.md) | P3 | S | — | IN PROGRESS (implementado `d4ad8cc`; ponto e anel em alerta conferidos; texto de cota desatualizada não visto; gate do roteiro pendente do 054) |
| 059 | [Zaplaud: busca oculta, revelada por puxada longa, fixa no topo e com "Limpar busca"](059-zaplaud-busca-oculta-e-fixa.md) | P2 | M | 054, 055, 057 | IN PROGRESS (implementado `00583e7` + `8d3d1ee`; 2 testes novos passando; `busca-vazia` e `busca-aberta` geradas pelo roteiro no claro; gate do roteiro pendente do 054) |
| 060 | [Zaplaud: título da sessão na lista em até duas linhas](060-zaplaud-titulo-da-sessao-em-duas-linhas.md) | P3 | S | — | IN PROGRESS (implementado `f0222de`; fonte 1,3 conferida em 1080×1920: 4 sessões visíveis, sem sobreposição; gate do roteiro pendente do 054) |
| 061 | [Zaplaud: anexo e uso de contexto dentro do campo de mensagem](061-zaplaud-campo-de-mensagem-integrado.md) | P3 | S | 055, 058, 059 | IN PROGRESS (implementado `3e91278`; fixture informa contexto do omp em `28efc0e`; anel 60% visto com descrição acessível; gate do roteiro pendente do 054) |
| 062 | [Zaplaud: título grande na lista que encolhe ao rolar (opcional)](062-zaplaud-barra-superior-que-encolhe.md) | P3 | M | 059 | REJECTED (dono achou o espaço superior grande no M35; revertido em `84546ff`, `6482a01`, `6ebbc29`) |
| 063 | [Zaplaud desktop: alvo JVM do Compose, janela no Linux e no macOS com as telas do celular](063-zaplaud-desktop-alvo-jvm.md) | P2 | L | — | DONE (branch `advisor/063-zaplaud-desktop-alvo-jvm`, `f968c77`; 063–066 mesclados em `producao` em `e214452`. 95 testes JVM e 99 host ok, revisor reconferiu o JVM. Passo 7 em Xvfb por XTest: login HTTP, lista, markdown, Esc, envio e anexo entregues (31 bytes idênticos). Sem chaveiro nesta máquina: reabrir pede login, como previsto. macOS e Secret Service não testados) |
| 064 | [Zaplaud: lista e conversa lado a lado em tela larga (desktop, iPad, tablet), estilo Telegram Desktop](064-zaplaud-duas-colunas-em-tela-larga.md) | P2 | M | 063 | DONE (branch `advisor/064-zaplaud-duas-colunas`, `6992f13`, sobre o 063. 145 instrumentados sem falha, igual ao baseline; capturas do celular iguais ao baseline (as duas param no mesmo ponto do roteiro, como no 054); `TwoPaneWide/NarrowUiTest` ok com Xvfb; tablet 2560×1600 com duas colunas. iPad não testado) |
| 065 | [Zaplaud desktop: avisos de aprovação, pergunta e fim de turno com a janela em segundo plano (sem bandeja)](065-zaplaud-desktop-notificacoes.md) | P2 | M | 063 | DONE (branch `advisor/065-zaplaud-desktop-notificacoes`, `cc5a727`, sobre o 063. `NotificationPrivacyInstrumentedTest` ok. Avisos verificados com `notify-send` **simulado** (argv, foco, "Abrir" → sessão, desligado); daemon real, minimizar real (Xvfb sem gerenciador de janelas), Wayland e macOS não verificados) |
| 066 | [Zaplaud desktop: pacotes deb/rpm/dmg, instalação no Arch, flags da JVM e CI](066-zaplaud-desktop-pacotes-e-ci.md) | P3 | M | 063 (melhor depois de 064 e 065) | DONE (branch `advisor/066-zaplaud-desktop-pacotes`, `3c6f248`, sobre o 063. ProGuard desligado com aprovação do revisor: 50 referências opcionais do OkHttp não resolvidas. Distribuição 192 MiB (tar.gz 110 MiB); app empacotado enviou mensagem (201). Script de instalação validado num HOME temporário. `.deb`/`.rpm` não gerados: sem `dpkg-deb`/`rpmbuild` aqui; `.dmg` sem Mac. CI com `xvfb-run` não disparado) |
| 067 | [Zaplaud desktop: medir o app real e decidir se vale Rust + Slint](067-zaplaud-desktop-medir-e-decidir-rust.md) | P3 | S (+L condicional) | 066 + uso real | DONE ([067-resultado.md](067-resultado.md); medido na integração `advisor/zaplaud-desktop-063-066` `3255ff4`, Xvfb com renderização por software. App real: abre em 1020 ms, PSS parado 339 MiB (heap Java 91 MiB, resto nativo), 0,10% de CPU minimizado. Nenhuma flag compensou: AppCDS dinâmico corta a abertura para 524 ms, mas exige `.jsa` por build (não integrado). Portão aberto por PSS > 300 MiB. Spike Rust + Slint: markdown com tabela e código selecionáveis, acentos pt-BR no X11 e SSE ao vivo funcionaram, com 22 MiB de PSS e 114 ms. Paridade estimada em 27 capacidades (7 S / 14 M / 6 L). Recomendação: **não reescrever agora**. Uso real do dono, Wayland e macOS não medidos) |
| 068 | [Resumo por HTTP (Anthropic Messages), sem processos `omp --mode rpc` quando configurado](068-resumo-sem-processo-omp.md) | P1 | M | — (tenants: omp-tenant 039) | DONE no branch `advisor/zeroclaw-tenants` (`ce598ab`), mesclado em `producao` em 2026-11-10 (`e08c52b`); configurar o servidor pessoal é passo do operador |
| 069 | [Fonte ZeroClaw: sessões do gateway no escritório, transcript espelhado em JSONL](069-fonte-zeroclaw.md) | P1 | L | — (tenants: omp-tenant 040) | DONE no branch `advisor/zeroclaw-tenants` (`e5e7e55`), mesclado em `producao` em 2026-11-10 (`e08c52b`). Correções do piloto no `anderson` (2026-11-10): terminal segue a sessão atual depois do /new (`3a93680`); deltas viram um item por bloco (`38647e9`, `4814a23`), com perda do bloco em andamento se o socket cair (sem replay; a resposta segue no histórico do gateway). Em produção no `anderson` como release `4814a2350188` |
| 070 | [ZeroClaw: mensagens (parado, steer, followUp) e controles (STOP, /new) pelo gateway](070-zeroclaw-mensagens-e-controles.md) | P1 | L | 069 | DONE no branch `advisor/zeroclaw-tenants` (`5ccf749`), mesclado em `producao` em 2026-11-10 (`e08c52b`) |
| 071 | [Elenco com ZeroClaw: chamar e dispensar personagens pelo gateway, sem auxiliar nem tmux](071-elenco-com-zeroclaw.md) | P1 | L | 069, 070 | DONE no branch `advisor/zeroclaw-tenants` (`619e63a`), mesclado em `producao` em 2026-11-10 (`e08c52b`). Smoke com modelo real (Sonnet) e ZeroClaw 0.8.7: chamar, ferramentas, steer, STOP, dispensar e reinício sem sessão presa |
| 072 | [ZeroClaw no cliente web e no Android: rótulos, controles permitidos, sem barra de contexto não confiável](072-zeroclaw-no-cliente-web-e-android.md) | P1 | L | 070, 071 (e 068 para o interruptor de resumo no Android) | DONE no branch `advisor/zeroclaw-tenants` (`bcf122c`), mesclado em `producao` em 2026-11-10 (`e08c52b`). Desvio aprovado: `client/src/net/store.ts` manda `expectedSessionId` para ZeroClaw. Chromium 5/5, instrumentados 27/27; iOS não compilado |
| 073 | [Cartão de uso lido do `GET /v1/cota`, sem `omp usage`](073-cartao-de-uso-pela-cota.md) | P1 | M | — | DONE no branch `advisor/zeroclaw-tenants` (`552b62f`), mesclado em `producao` em 2026-11-10 (`e08c52b`) |
| 074 | [Zaplaud desktop em Rust + Slint: roteiro por fases (27 capacidades, portões, harmonia gerada do Kotlin)](074-zaplaud-cliente-rust-slint.md) | P3 | L | decisão do dono (067: portão aberto, recomendação "não agora") | TODO (aguardando decisão do dono) |

Integração 054–062 (2026-11-08): branch `advisor/zaplaud-modernizacao-054-062`, mesclada em `producao` e enviada a
`fork/main` a pedido do dono. Instrumentados 141 testes, 0 falhas em `28efc0e` (antes de reverter o 062) e 142 em
`f47c461`; host + lint + APK ok depois da reversão. Release assinado com a chave atual instalado no M35 com
autorização do dono: 21:19 com o 062, 21:35 sem ele, 22:06 com o shake e 22:26 com o símbolo do modelo, sempre por
cima, sem limpar dados. "Retomar sessão" sumido: a versão `dab677d` também não mostra o ▶ na mesma sessão no M35
(nenhum contato livre na pasta).
Pedido do dono (2026-11-08): retomar sessão omp encerrada com contato **Novo** sorteado quando nenhum contato livre
trabalha na pasta. `87810aa` (`POST /api/cast/new` aceita `session`; ▶ sempre em sessão omp encerrada com pasta; teste
em `launcher.test.ts`). Publicado no servidor pessoal (`localhost/habblaud:producao` = `deploy-87810aa`; rollback
`rollback-9426ebc-20261108`) e no M35 às 21:57. Tenants não atualizados. Retomada real pelo celular não conferida.
Também a pedido do dono: toque longo no anel de contexto pede shake (`f47c461`); símbolo da família do modelo junto do
avatar (`f5eb112`, fixture com modelo em `329ed1d`). `ConversationStreamTest` (2 testes) estoura o tempo em host test
também em `f47c461`, sem relação com essas mudanças; não investigado.
Os planos 055–062 só viram DONE quando o roteiro de capturas terminar com exit 0 (bloqueio do 054). Causa provável:
a sessão fechada da fixture (`CLOSED`) é Claude, mas o roteiro espera "Retomar conversa", que só aparece para sessões
omp [INFERÊNCIA a partir de `CastActions.kt:133`].

Em 2026-10-31 o branch `rpg` (elenco 031/032 e o que veio depois) foi promovido a `producao` por fast-forward
(`759c0f9`): o standalone (`~/apps/habblaud` no branch `producao`) e os cinco tenants rodam essa versão. As notas
"fora de `producao`" acima valem até essa data.

Status values: TODO | IN PROGRESS | DONE | BLOCKED (with one-line reason) | REJECTED (with one-line rationale)

## Dependency notes

- 068–073: tenants passam do OMP para o ZeroClaw (auditoria `deep`, 2026-11-09, commit `694938f`; o lado do pod e
  do proxy de cota são os planos 039–043 em `~/Desenvolvimento/omp-tenant/plans/`). Ordem de merge fixa:
  068 → 073 → 069 → 070 → 071 → 072, porque todos mexem em `server/config.ts`, `server/index.ts`, `server/README.md`
  e `CHANGELOG.md`; cada drift check declara como esperadas as mudanças dos anteriores. 068 e 073 já servem fora dos
  tenants (068 tira os dois `omp --mode rpc` do resumo, ~470 MiB no servidor pessoal, quando configurado). 072 também
  cobre o interruptor de resumo do Android, que hoje exige o papel `tiny` do OMP (contrato `summaryHttp` do 068).
  Contratos compartilhados com o omp-tenant 040:
  `HABBLAUD_ZEROCLAW_URL`, `HABBLAUD_ZEROCLAW_AGENTS` (JSON `{alias, model}`, alias = `m_` + 12 hex do SHA-256 do id),
  `HABBLAUD_ZEROCLAW_DEFAULT_AGENT`, `HABBLAUD_SUMMARY_*`, `HABBLAUD_QUOTA_*`; gateway sem pairing, só no loopback do
  pod. Decisões, para não reabrir: transporte `/ws/chat` (não ACP: sem steer nem lista de sessões); o histórico HTTP do
  gateway não traz ferramentas, por isso o espelho JSONL; o gateway não confirma entrega por mensagem, então
  `delivered` quer dizer "aceito pelo socket local"; conversas do OMP não continuam no ZeroClaw.

- 063–067: cliente desktop do Zaplaud (variante `deep` de direção, 2026-11-09, commit `694938f`). Decisões do dono:
  **Compose agora, Rust depois** (só se o 067 abrir o portão), **paridade sem bandeja** (avisos só com o app aberto),
  **duas colunas em toda tela larga** (≥ 840 dp; celular inalterado). Medição das pilhas (Compose, Rust + Slint,
  Go + Fyne) em [063-medicao-pilhas-desktop.md](063-medicao-pilhas-desktop.md). Ordem: 063 → 064 e 065 (independentes
  na lógica, mas os dois mexem em `strings.xml`, `UiPlatform.kt` e no `Main.kt` do desktop: mescle um antes de começar
  o outro) → 066 → 067. O 064 muda a tela do celular por extração e precisa dos instrumentados sem falha nova.
  Worktrees em `/tmp/habblaud-NNN`. 063 e 064 passaram por leitura fria de um revisor (19 apontamentos corrigidos).
  **063–066 integrados em `advisor/zaplaud-desktop-063-066` (`3255ff4`) e mesclados em `producao` em `e214452`**
  (2026-11-09, a pedido do dono; sem push). Documentação: [docs/zaplaud-desktop.md](../docs/zaplaud-desktop.md).
  Rust: roteiro no 074, só com decisão do dono.

- 054–062: modernização visual do Zaplaud (variante `plan`, skills `improve` e `improve-ui`), commit `dab677d`,
  2026-11-08. Evidência: código e capturas do emulador `habblaud034` com a fixture, só tema escuro. Execute na ordem
  numérica: todos menos 054 mexem em `ReadingScreen.kt`, e a ordem evita conflitos. 054 vem primeiro porque os outros
  verificam com `scripts/android-screens.sh`. 059 depende do 057 (mesma região) e cria `pillFieldColors()`, usado
  pelo 061; 058 cria `warningColor()`, usado pelo 061; 062 ajusta a puxada da busca do 059. Decisão do dono: busca
  oculta, revelada só com esforço e fixa depois de aberta. Cada plano acrescenta uma linha em `CHANGELOG.md`.

- 044–053: auditoria deep do Zaplaud com medições no M35 em 2026-11-07, base `778466839` (branch da 043, não mesclada). Evidências em [auditoria-zaplaud-deep-2026-11-07.md](auditoria-zaplaud-deep-2026-11-07.md). 047 fecha o gate host da 043. 045 e 050 dependem do 044 (o custo dos indicadores mascara os demais). 046 segue decisão do dono: manter a chave atual como identidade do app pessoal. 053 exige Mac. Os números de "antes" foram medidos com os serviços de acessibilidade APH e Reading mode ativos; o dono os removeu depois. Remeça o "antes" no estado atual. Instalação no M35 sempre com autorização; nunca desinstalar nem `pm clear`.

- 043: variante `plan`, HEAD `9426ebc`, 2026-11-07. Investigação no M35 correlacionou GET de avatar em socket encerrado, RST e crash; APK instalado inspecionado. Inclui URL `parts` truncada, fallback, recuperação limitada de GET sem replay de POST e cache fora da main thread. Engasgos exigem medição antes/depois; instalação no celular depende de autorização. Não altera backend nem dados.

- 042: variante `plan`, commit `c468bc1`, 2026-11-04. Decisões do dono: lista única misturada com filtro por servidor; um login por servidor (origem = identidade). Risco HIGH na migração do estado local de um servidor para a lista (rascunhos e cookie precisam sobreviver à atualização). Sem mudança no servidor Node. Gate iOS (`:shared:allTests` no Mac) obrigatório para DONE.

- 041: usuário aceita iPadOS apenas em primeiro plano e autorizou publicação desta branch no fork e CI macOS. Android e seus dados devem ser preservados. DONE exige IPA instalado pelo SideStore, paridade funcional e gates do plano; build de simulador não basta.

- 035–040: auditoria Android no commit `9469c0b`, 2026-11-03. Todos os commits foram mesclados em `producao` por `8fb1fdd`. A pedido do usuário, validação no celular e execução do CI no GitHub ficam para depois; 037 ainda tem duas falhas em viewport pequeno.

- 034: [plano Android](034-cliente-android-produtivo.md), variante `plan deep` em `84afde2` (2026-11-02), revisado no mesmo dia. Executar proteção `expectedSessionId` no backend antes do cliente gravar; depois ambiente, login, leitura, envio, anexos, controles e APK assinado. Etapas 0–6 usam fixture; a prova real (etapa 7) usa o endpoint atual `http://100.83.131.11:4747` pelo Tailscale (decisão do usuário), com HTTP restrito ao IP confirmado, consentimento e sockets presos à VPN. HTTPS fica como migração futura do operador. Não depende de concluir o 033 e não promete que `steer` pule ferramentas. SDK/dispositivo/assinatura não provisionados.
- 033: plano em `033-modos-de-envio-omp.md`, escrito em `a6b6664` (2026-11-01). Somente mensagens principais OMP; `aside` não é o chat paralelo. Publicação de servidor/cliente e extensão é coordenada pelo operador, sem reiniciar sessões automaticamente.
- 029: isolamento integrado e publicado por `omp-tenant sync` nos cinco tenants; servidor RW e contêiner OMP RO para a mesma raiz de anexos. Credenciais e configurações individuais preservadas; rollback registrado em `030-resultado.md`.
- Investigação do OOM do 029: `029-upload-lab-results.md`; implementação e provas do servidor completo aprovadas no 030: `030-resultado.md`. O pico máximo independente foi 126,8 MiB, incluindo pagecache, nas cargas descritas.
- 030 substituiu a arquitetura de upload da candidata `c19afb0`; o código resultante `155e734` foi mesclado e publicado como revisão `7e164a5`. O aceite integrado do 029 com os mounts `0ed95c9` foi concluído nos tenants.
- 023 é independente. Usa a trava `terminal`, que os pods já liberam em `HABBLAUD_REMOTE_ALLOW`: nada muda no
  `omp-tenant`. Publicação (produção e `omp-tenant release` + `sync`) é do operador.
- 014 é independente dos outros. Usa `ctx.runEphemeralTurn` do omp 18.12.6, confirmado no binário de
  `/opt/omp-tenant/omp`. A publicação (levar o commit a `~/apps/habblaud`, `omp-tenant release` + `sync`, reabrir as
  sessões do omp) é do operador, fora do plano.
- 016–022 são independentes entre si. 016 e 017 primeiro (bugs com efeito real). 020 tem a parte do servidor; a
  configuração dos pods (`HABBLAUD_REMOTE_TRUST_PROXY`) é o passo 6 do plano 034 do `omp-tenant`, bloqueado até o
  020 entrar. 016 muda a extensão do omp: depois do merge, `npm run omp:install` (dono) ou `omp-tenant release` +
  `instalar` + `sync` (usuários) e reabrir as sessões do omp. 020 e 021 só mudam o servidor.
- 002–009: nenhum plano depende de outro; 002 e 003 primeiro (segurança e bug com efeito real).
- 004 cria 3 textos novos em `tr()`: se 005 já tiver entrado, o CI cobra as traduções deles (o plano 004 já manda
  traduzir).
- 003: depois do merge, `npm run omp:install` e reabrir as sessões omp para a extensão nova valer.
- 002 e 003 tiveram a correção provada numa cópia descartável do repositório (teste novo falha antes, passa depois;
  suíte inteira verde).
- 007, 008 e 009 foram medidos sem GPU. Com GPU, a economia do 008 (cenário em 1×) quase não muda a CPU: 85% → 79%
  em produção, 82% → 82% em `fork/main`. O custo é por quadro, e não por pixel. Daí os planos 010 e 011 (menos
  quadros) e a refeitura do 009 como 012.
- Medições de 2026-10-25 com GPU, a 30 qps: 82–85%. Forçando o limite, 15 qps deram 50%, 10 qps 37–38% (34% com
  timer) e 1 qps 9,6% (custo só do rAF a 60 Hz). Sem o desenho animado das telas, 72,5%. Sem móveis e props, 59%.
  Sem nenhum conteúdo (71 chamadas por quadro), 46%. Com o escritório suspenso, 1,5%.
- 011 depende do 010: usa `STILL_FPS`, `powerSave` em `frameCapFor` e o timer `schedule`/`wake`. 010 e 011 mexem em
  `settings.ts` e nas traduções (a descrição da economia muda nos dois).
- 012 é independente e se mede a 30 qps a partir de `fork/main`. Depois de 010/011, o escritório passa a maior parte
  do tempo a 10 qps, e o ganho do 012 cai (as telas repintam quase todo quadro a 10 qps); só janelas e relógios
  ainda ganham.
- 013 é investigação: o produto é `plans/013-resultado.md`; se recomendar uma variante, vira o próximo plano livre.
- Planos 010–013 partem de `fork/main` (o `producao` local está 5 commits atrás).

## Findings considered and rejected

- Zaplaud desktop em Go + Fyne (2026-11-09): medido a 166 MiB RSS parado e 1,2% de CPU parada, perto da JVM e com
  CPU ociosa maior; reescrita sem ganho. Wails (web) e Tauri: exigiriam uma terceira interface (web) além de
  Compose. Rust + Slint não foi rejeitado: está condicionado ao portão do 067.
- Zaplaud desktop com bandeja, iniciar com o sistema e rodar com a janela fechada: adiado por decisão do dono (065).
- Store do desktop sobre DataStore + `org.json` (porta do Android): `org.json` é embutido no Android e duplicaria
  classes num conjunto de fontes comum; o desktop porta o store iOS (JSON + `kotlinx.serialization`) no 063.
- Modernização do Zaplaud (054–062), não planejado: cores dinâmicas do Android 12+ (apagam o verde que identifica o
  app); fonte nova (exige decisão de marca inexistente); animações extras (o app já pagou caro em GPU com o
  "digitando", ver 044); componente `SearchBar` do Material 3 (abre busca expandida, conflita com a busca fixa
  pedida). Adiado por decisão do dono: dica de primeira utilização da busca oculta (059).
- Proibir HTTP no Android: contraria a escolha documentada para LAN/VPN; 037 prevê aviso sem remover suporte.
- Logout deixa de limpar após erro de rede: `HabblaudApi.logout()` captura `IOException` e retorna `false`; falha de rede, isoladamente, não sustenta o achado.
- Filtro release `VpnGuardTest` inexistente quebraria CI: comando com ambos filtros passou offline; não comprova cobertura VPN.
- Atualizar dependências do Zaplaud (Markdown 0.38→0.46, DataStore 1.1.7→1.3.0, Activity 1.11→1.13, Serialization 1.9→1.12, AGP 9.1.1→9.5.1): OSV sem alertas em 284 coordenadas; defasagem sem impacto conhecido. Atualizar com medição junto do 045, não como plano próprio.
- Memoizar a projeção da conversa com `remember(entries)`: a lista tem identidade estável e é mutada no lugar; congelaria a tela.

- Avisar sobre o repositório de atualização do fork (`package.json` `repository` aponta para marmottajr): o cartão só
  informa release nova do upstream, sem atualizar nada sozinho; ao sincronizar o fork com o upstream a versão também
  sobe e o aviso some. Funciona como lembrete de sincronizar. Para desligar: `HABBLAUD_UPDATE_CHECK=0` no quadlet.
- Guard nos upgrades de WebSocket: o servidor de produção não usa WebSocket (só SSE); o único upgrade é o HMR do Vite
  no modo dev.
- SSE removido no `close` do `req`: testado no Node 26, o `close` do `IncomingMessage` não dispara enquanto a resposta
  SSE segue aberta.
- Permissões/mensagens forjadas por processo local sem token: o modelo de confiança é o usuário da máquina (loopback);
  processo do mesmo usuário já pode tudo. O risco real (página de outra porta) é o plano 002.
- Corpo acima do limite continua sendo drenado (`readJson`, login remoto): limitado pelo `requestTimeout` padrão do
  Node (300 s) e as portas são locais/Tailscale; custo de mudar > ganho.
- `loadSecret` cria segredo só em memória quando o arquivo é ilegível: já falha seguro (sessões antigas invalidadas).
- Cache de dias da linha do tempo sem poda (`server/history/timeline.ts`): cresce ~1 entrada por dia; irrelevante.
- Config do instalador do omp em modo 0644: guarda só a porta.
- `decodeURIComponent` sem try nas rotas de spawn/ditado: o erro cai no catch do servidor (500 em vez de 400); cosmético.
- Comandos da `world-stream` perdidos numa reconexão durante o POST: repetir às cegas duplicaria comandos; precisa de
  idempotência no servidor do World (fora do repositório).
- Dividir módulos grandes (`server/index.ts`, `server/http/app.ts`, `client/src/world/sim/sim.ts` 1995 linhas,
  `client/src/ui/drawer.ts`): a suíte é forte e o custo/risco da extração não se paga agora.
- Lint/formatter: o repositório não tem e o estilo está consistente; adicionar é decisão do upstream.
- CI só em Node 22.12 com imagens em Node 24: a mínima suportada é a que mais quebra; baixo valor.
- Trocar Canvas2D por WebGL/PixiJS: reescreveria `renderer.ts`, `overlay.ts` e `art/` inteiros. Com GPU, ~55% do
  custo é fixo por quadro (46% de CPU com só 71 chamadas por quadro), e isso uma troca de biblioteca não tira; menos
  quadros (010/011) tira. (Revisto em 2026-10-25 com GPU; a justificativa antiga, medida sem GPU, não vale mais.)
- Desenhar num Worker com `OffscreenCanvas`: tira o trabalho da thread principal, mas não reduz energia.
- Pré-compor pisos, paredes e móveis numa camada estática: reaberto como spike (013). Com GPU, tirar móveis e props
  do quadro baixa 85% → 59%, e o custo de cobrir a tela (motivo antigo da rejeição) não pesa com GPU.
- Luz (`drawLighting`), nomes e balões (`overlay.draw`) e luzes de Halloween (`drawSeasonalLights`): tirá-los do
  quadro, medido com GPU, não mudou a CPU (86,7%, 83,0% e 89,7% contra 85,0%).
- Cache de telas, janelas e relógios (plano 012, executado em 2026-10-25 a partir de `31368cb`, a 30 qps com GPU):
  o executor mediu 80,6% → 79,0% (medianas de 3); o revisor, em pares alternados, 83,2/74,4/56,1% sem cache contra
  82,2/78,7/77,2% com cache. Não há ganho fora do ruído. O limite de 72,5% medido antes tirava o desenho por
  inteiro. Com cache, as telas ainda repintam a cada 66 ms, um quadro sim, outro não, e cada item continua com um
  `drawImage`. A 10 qps, ritmo atual depois de 010/011, o ganho seria ainda menor. Branch apagado.
- Desenhar a economia em 1× (plano 008) com GPU: não ajuda, mas também não piora o bastante para remover; ajuda em
  aparelhos que desenham o canvas sem GPU. Fica.
- `backdrop-filter` dos painéis por cima do canvas: medido sem ele, nenhuma diferença (67–72% contra 71%).
- Limitar o laço com `setTimeout` + rAF em vez de pular quadros do rAF **a 30 qps**: medido sem GPU, mesma CPU
  (36–39%). Abaixo de 30 qps vale: com GPU, 38% → 34% a 10 qps (o plano 010 usa o timer só abaixo de 30).
- Decorações de Halloween/Natal (~120 chamadas por quadro): pequeno e sazonal.
- Auditoria de `af73a23`, achados vetados e não planejados:
  - Snapshot e stream sem login na porta principal: a porta principal fica em 127.0.0.1 nas duas implantações; pela
    Tailscale e pelo Traefik só se chega à porta remota, que responde 401 sem cookie (conferido com `ss -ltn` e um GET).
    Expor a porta principal (`HABBLAUD_HOST=0.0.0.0`) é opção documentada e desliga terminal, mensagens e permissões.
  - Entregas leem qualquer caminho que o agente cite (modo direto): só pelo id de uma entrega que o próprio agente
    citou, o leitor é o dono da sessão, há lista de extensões, bloqueio de pastas secretas e `realpath`; HTML/SVG vão
    com CSP sandbox. Sem saída para terceiros.
  - Agente do pod aprova as próprias permissões pelo loopback do pod: é o modelo "processo local é confiável"; não
    cruza para outro usuário nem para o host (nft bloqueia a saída).
  - "Sem marca" ainda expõe `provider` e caminhos na API: é máscara de interface; o usuário vê a própria casa.
  - Todos os pods com o mesmo uid (`omp-tenants`): decisão de arquitetura do `omp-tenant`, fora deste repositório.
  - ffmpeg do ditado sem lista de protocolos: o ditado não está na imagem dos usuários; endurecimento sem caso real.
  - Chave TLS remota 0644: protegida pela pasta 0700 e pelo `nameConstraints` da CA.
  - Corridas do painel da pergunta paralela no cliente (`sending` compartilhado entre agentes, resposta velha ao
    reabrir o mesmo agente): efeito cosmético, sem perda de dado.
  - Tracker do omp sem `session_shutdown` que nunca é limpo, `.tmp` de asides, linha parcial no timeline após disco
    cheio, mapas pequenos sem poda (`SessionTracker.known`, `warnOnce`): crescimento lento ou caso raro.
  - Caminho do omp no Windows com maiúsculas diferentes, extração do ciclo de vida comum às três fontes, alinhar
    `engines`/alvo do bundle/Node da imagem, prazo para retirar `server/legacy.ts`: valor baixo agora.
  - Medir a cidade e a partida do servidor: sem sintoma relatado.

- Issue upstream #73 (2026-10-26, variante `plan`, commit `450e635`):
  - Seção 1, timelapse servido por intervalos (keyframe + deltas): os dias reais têm 0,76 MB, 1,2 MB e 1,57 MB
    (`~/.habblaud/timeline/`), longe do limite de 30 MB (`HARD_LIMIT_BYTES`); pela conta da issue (~110 ms a 30 MB),
    o parse custa ~6 ms. Rever se um dia passar de ~10 MB.
  - Seção 2: coberta pelos planos 007, 008, 010, 011 e 015 (upstream: PRs #191, #192, #199 e #204).
  - Seção 4, editor de personagem sob demanda: ~6 kB minificado, não compensa.

## Não auditado

- Zaplaud desktop (063–067): Wayland real, GNOME/KDE com Secret Service, macOS e acessibilidade (leitor de tela na
  JVM) não foram testados; a máquina de análise não tinha sessão gráfica nem chaveiro (medição em Xvfb com
  renderização por software). `java-keyring` 1.0.4 não foi exercitado.
- Modernização 054–062: tema claro, iPad, teclado aberto, TalkBack, fonte grande e paisagem não foram inspecionados
  antes dos planos; só capturas no tema escuro, em 1080×1920.
- Arte em pixel (`client/src/art/**`) e textos das traduções.
- O servidor do Habblaud World (fora do repositório); `shared/world/` e `server/world/` só quanto a rotas e guard.
- Testes no Windows (CI cobre).
- Desempenho da cidade (`client/src/world/city/**`) e da visita: mesmo padrão de laço e densidade do escritório
  (`city/index.ts:218,639`), não medidos.
- Medição num Mac (M1, Safari ou Chrome com Metal) e num celular de verdade: 007–009 foram medidos sem GPU, 010–013
  com GPU Intel no Linux. A proporção (custo por quadro contra custo por chamada) deve valer no Mac
  [INFERÊNCIA], mas os números absolutos não. O aviso do Habblaud World da primeira visita (desfoque de fundo)
  derruba a página para ~28 qps no navegador de teste; já registrado em `docs/terminal-celular-desempenho.md`, não
  planejado.
- Interface fora do escritório (DOM, SSE, painéis): com o escritório suspenso, a página inteira fica em 1,5% de CPU;
  não há o que planejar ali.
- Auditoria de `af73a23`: `server/world/**` (cliente do Habblaud World), `scripts/docker-up.ts`, scripts de gancho
  do Codex, o proxy de cota `omp-tenant-cota` (onde a lista de modelos é de fato imposta), Traefik/TLS e ACLs da
  Tailscale. Testes do `server/sources/codex/rollout.ts` sem arquivo próprio (o dono usa pouco o Codex).
