# Implementation Plans

Gerado pela skill improve. 001: 2026-10-24 (variante `plan`). 002–006: 2026-10-24, auditoria `deep` no commit
`4931abc` (baseline: `npm run typecheck` ok, `npm test` 173 arquivos / 2080 testes ok, `npm run build` ok,
`npm audit --omit=dev` 0 vulnerabilidades). Cada executor: leia o plano inteiro antes de começar, respeite as STOP
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

Status values: TODO | IN PROGRESS | DONE | BLOCKED (with one-line reason) | REJECTED (with one-line rationale)

## Dependency notes

- Nenhum plano depende de outro; 002 e 003 primeiro (segurança e bug com efeito real).
- 004 cria 3 textos novos em `tr()`: se 005 já tiver entrado, o CI cobra as traduções deles (o plano 004 já manda
  traduzir).
- 003: depois do merge, `npm run omp:install` e reabrir as sessões omp para a extensão nova valer.
- 002 e 003 tiveram a correção provada numa cópia descartável do repositório (teste novo falha antes, passa depois;
  suíte inteira verde).

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

## Não auditado

- Arte em pixel (`client/src/art/**`) e textos das traduções.
- O servidor do Habblaud World (fora do repositório); `shared/world/` e `server/world/` só quanto a rotas e guard.
- Testes no Windows (CI cobre).
