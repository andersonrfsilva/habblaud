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
| 023  | Ver e baixar os arquivos da pasta de trabalho pelo botão "Arquivos" da gaveta (salas + `~/projetos`) | P2 | L | — | DONE (merge 5205df7 + correção a10d232: clique no espaço vazio fechava a janela; em produção e nos usuários, release a10d2323cf46 + sync em 2026-10-26) |

Status values: TODO | IN PROGRESS | DONE | BLOCKED (with one-line reason) | REJECTED (with one-line rationale)

## Dependency notes

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

## Não auditado

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
