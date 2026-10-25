# Plan 014: Bate-papo paralelo com um agente do omp numa janela lateral do terminal, com histórico em disco

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Where to work**: only in the development clone `~/Desenvolvimento/habblaud` (or a `git worktree` of it). NEVER
> edit, build, run `npm`/`git` commands (except the read-only `git -C ~/apps/habblaud status --short` of the done
> criteria), or install anything from `~/apps/habblaud`: that is the production Habblaud
> (`omp-tenant release` reads commits from it). Never run `npm run omp:install`/`omp-tenant` either: they change
> the omp sessions and pods in use. If you find yourself in `~/apps/habblaud`, STOP.
>
> **Drift check (run first)**: `git diff --stat dfbdde3..HEAD -- mod/habblaud-omp/habblaud.ts shared/types.ts shared/omp-events.ts server/messages/registry.ts server/messages/http.ts server/http/app.ts server/index.ts client/src/net/store.ts client/src/ui/terminal.ts client/src/ui/icons.ts client/src/ui/styles.css`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: MED (novo caminho extensão → servidor → página; a extensão roda dentro do omp e um erro solto derruba a
  sessão inteira)
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `dfbdde3`, 2026-10-25

## Why this matters

No terminal do omp, `/btw <pergunta>` faz uma pergunta lateral sobre a conversa sem entrar no histórico dela e sem
esperar o turno acabar: o agente continua trabalhando. No Habblaud não há isso. A caixa de mensagem
(`POST /api/messages`) entra na sessão como prompt de verdade (vai para o contexto e espera o turno atual). Quem
acompanha o agente pelo escritório (inclusive pelo celular, pela porta remota) não consegue perguntar "o que você
está fazendo agora?" sem atrapalhar. O omp 18.12.6 dá às extensões a mesma maquinaria do `/btw`:
`ctx.runEphemeralTurn`. Este plano liga essa API a um **bate-papo numa janela lateral do terminal do Habblaud**: uma
coluna à direita da conversa do agente, com balões de pergunta e resposta e uma caixa própria. Não há comando
`/btw` nem botão na faixa do omp: o usuário pediu explicitamente uma janela de bate-papo. As conversas ficam em
disco, por sessão, e sobrevivem a recarregar a página e a reiniciar o servidor.

## Current state

### A API do omp (`ctx.runEphemeralTurn`)

Confirmada no binário que os pods usam: `grep -c runEphemeralTurn /opt/omp-tenant/omp ~/.local/bin/omp` dá 18 em
cada um (omp 18.12.6). Da documentação do omp (`omp://extensions.md`, seção "Ephemeral side turns"):

> Run the same side-turn pipeline as `/btw` using the current model and conversational context. The question and
> response are not appended to session history, and the request can run while the main turn is active. The snapshot
> may include in-flight assistant text.

```ts
const { replyText } = await ctx.runEphemeralTurn({
  promptText: remoteQuestion,
  tools: false,
  maxTokens: 4096,
  maxContextBytes: 1_048_576,
  onTextDelta: delta => sendRemoteChunk(delta),
  signal: requestAbortController.signal,
});
```

Fatos que importam aqui (da mesma seção e do código do omp):

- `dedupeReply` vale `true` por padrão e corta a resposta em 4 KiB. **Passe `dedupeReply: false`**: o corte passa a
  ser do Habblaud (`ASIDE_ANSWER_MAX`, abaixo).
- **Não passe `maxTokens` nem `tools: false`.** Vários transportes recusam `maxTokens` antes de começar (Codex,
  Cursor, Ollama Cloud, Antigravity), e `tools: false` quebra o reaproveitamento do cache. O `/btw` do próprio omp
  também não passa nenhum dos dois.
- `conversationKey`: perguntas do mesmo tópico ficam numa linhagem só no provedor. Depois de um cancelamento ou de uma
  falha, a chave precisa mudar. Use `` `hb:${topicId}:${turnIndex}` ``: o índice muda a cada pergunta, e isso já cobre
  a troca.
- `history`: mensagens de um tópico anterior. O `/btw` do omp monta assim (código do omp, `session/btw-turn.ts`):

```ts
// para cada pergunta anterior do tópico:
{ role: 'user', content: [{ type: 'text', text: question }], attribution: 'agent', timestamp: createdAt }
// e, se houve resposta:
{ role: 'assistant', content: [{ type: 'text', text: answer }], api: model.api, provider: model.provider, model: model.id,
  usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
           cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
  stopReason: 'stop', timestamp: updatedAt }
```

  O `model` vem de `ctx.models.current()`, que devolve o modelo ativo da sessão (com `provider`, `id` e `api`).
- Erros que dá para repetir: "Active model changed during ephemeral turn; retry." e "Active session changed during
  ephemeral turn; retry." Viram falha da pergunta, com o texto do erro.
- Uma exceção solta (timer ou promise sem `catch`) dentro de uma extensão **derruba a sessão do omp inteira**
  (`omp://extensions.md`, "Background work"). Todo código assíncrono novo na extensão precisa de `try/catch`.

### Como uma mensagem da página chega ao omp hoje

1. Página: `POST /api/messages` ou `POST /api/messages/control` (`server/messages/http.ts`). Quem guarda é o
   `MessageRegistry` (`server/messages/registry.ts`), com a situação `queued → sent → delivered/failed`.
2. Extensão (`mod/habblaud-omp/habblaud.ts`): a cada 2 s com o agente livre, ou 5 s com ele ocupado, chama
   `POST /api/mod/inbox {session}`. Recebe `InboxMessage[]`, entrega cada uma e confirma em
   `POST /api/mod/inbox/ack {session, results: [{id, ok, error?}]}`.

`mod/habblaud-omp/habblaud.ts:664-683`, a entrega (a rodada **espera** cada `deliverOne`):

```ts
  async function deliver(session: string, messages: readonly InboxMessage[]): Promise<AckResult[]> {
    let idle = idleNow();
    const results: AckResult[] = [];
    for (const m of messages) results.push(sessionId === session ? await deliverOne(m) : { id: m.id, ok: false, error: SESSION_CHANGED });
    return results;

    async function deliverOne(m: InboxMessage): Promise<AckResult> {
      if (m.control) return applyControl(m.id, m.control);
      if (!m.text.trim()) return { id: m.id, ok: false, error: 'mensagem vazia' };
      ...
```

Por isso a pergunta paralela **não pode** ser esperada dentro de `deliverOne`: travaria a caixa de entrada por todo
o tempo da resposta. `deliverOne` só a inicia e confirma na hora. A resposta segue por outra rota.

`server/messages/registry.ts:325-338`, `control()`: o molde do novo `aside()`:

```ts
  control(raw: unknown): SendResult {
    const { agentId, ...change } = parseControl(raw);
    const real = this.opts.office.get(agentId);
    const demo = real ? undefined : this.opts.demoAgent?.(agentId);
    const agent = real ?? demo;
    if (!agent) return { error: 'not-found' };
    if (agent.provider !== 'omp') return { error: 'unavailable', reason: tr('só agentes do omp trocam de modelo e raciocínio pela gaveta') };
    const reason = unavailableReason(agent, real ? this.canMessage(agentId) : false);
    if (reason) return { error: 'unavailable', reason };
    if (this.openCount(agentId) >= MAX_OPEN) return { error: 'too-many' };
    const entry = this.entry(agentId, agent.sessionId, { text: '', control: change });
    this.messages.set(entry.msg.id, entry);
    return { message: { ...entry.msg } };
  }
```

`server/messages/registry.ts:556-571`: toda mensagem termina em `finish` (direto ou por `fail`). É o ponto do novo
gancho `onSettled`:

```ts
  private finish(e: Entry, status: 'delivered' | 'failed', now: number): void {
    this.setStatus(e, status, now);
    delete e.late;
    ...
    e.text = '';
  }

  private fail(e: Entry, now: number, error: string): void {
    e.msg.error = error;
    this.finish(e, 'failed', now);
  }
```

`server/messages/registry.ts:386` (dentro de `inbox()`): o item que vai à extensão:

```ts
      out.push({ id: e.msg.id, text: e.text, ...(e.control ? { control: e.control } : {}) });
```

`shared/types.ts:794-800`:

```ts
/** Mensagem entregue ao plugin habblaud-mensagens (POST /api/mod/inbox). */
export interface InboxMessage {
  id: string;
  /** O texto como foi digitado: o plugin o manda à sessão como se você o tivesse digitado. Vazio quando há `control`. */
  text: string;
  control?: OmpControl;
}
```

A extensão tem a **própria cópia** desse tipo e do leitor em `mod/habblaud-omp/habblaud.ts:229-274`
(`InboxMessage`, `parseInbox`), porque roda fora do projeto. As duas precisam do campo novo.

### Travas de acesso (`server/http/app.ts`)

- `isLocalOnlyPath` (linhas 131-141) já barra na porta remota tudo que começa com `/api/omp/`. Uma rota nova
  `/api/omp/aside` herda isso sem mudança.
- `/api/omp/events` (linhas 510-517) é o molde da trava da rota da extensão:

```ts
    if (path === '/api/omp/events') {
      // Eventos da extensão do omp: só observam, mas só valem vindos do próprio computador (a mesma trava do Codex).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
        sendJson(res, 403, { error: denied(req, tr('eventos do omp só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
      } else handleOmpEvent(req, res, { live: deps.ompLive }).catch((err) => fail(res, err));
      return true;
    }
```

- Rotas da página: `trusted(req, feature)` (linha 283). Na porta remota, só passa com `HABBLAUD_REMOTE_ALLOW`
  contendo a feature (`'permissions' | 'messages' | 'terminal' | 'spawn'`, `server/http/remote.ts:33`). Os pods
  do omp-tenant rodam com `HABBLAUD_REMOTE_ALLOW=terminal,permissions,messages`.
  - **Mandar e cancelar perguntas** manda texto ao modelo: `trusted(req, 'messages')`, a mesma trava do bloco
    `isMessagesPath` (linhas 661-673).
  - **Ler perguntas e respostas** mostra conteúdo da conversa: `trusted(req, 'terminal')`, a mesma trava de ler a
    conversa no terminal (linha 439).
  - As rotas só existem com as mensagens ligadas (`config.messages`, o mesmo `if` que cria o `MessageRegistry` em
    `server/index.ts:285`). Desligadas: 403 com o mesmo texto do bloco das mensagens.

### Gravação em disco: o padrão do repositório

`config.dataDir` (`server/config.ts:249`): `HABBLAUD_DATA_DIR`, `/data` no Docker ou `~/.habblaud`. Nos pods do
omp-tenant é `/habblaud/<nome>/habblaud-dados`. Os arquivos são gravados assim: escreve em `.tmp` e faz
`renameSync`, e um arquivo ilegível vira só um aviso no log. Exemplo a copiar: `server/model/room-aliases.ts`
(`load()`, `save()`). O que tem conteúdo sensível grava com `{ mode: 0o600 }`, como `server/http/remote.ts:206-207`.

### Cliente

- `client/src/ui/terminal.ts:694-705`: a barra de cima do terminal (`bar`) tem `ui-term__who`, `reconnEl`,
  `findBtn` (a lupa, criada na linha 691 com `iconButton(ICONS.search, …, 'ui-icon-btn--sm ui-term__find')`), as
  ações, maximizar e fechar. O botão do bate-papo entra ali, logo depois de `findBtn`, no mesmo formato.
- `client/src/ui/terminal.ts:767-775`: o elemento do terminal é uma coluna (`bar`, `tabs`, `tools`,
  `ui-term__body`, `ui-term__foot`). Em `render()` (linhas 842-854), `kind === 'ready'` (o retorno de
  `this.composer.render`) quer dizer que a extensão do omp está conectada.
- `client/src/ui/styles.css:4618-4640`: `.ui-term` é `position: fixed`, `display: flex; flex-direction: column`, com
  largura `--term-w: min(920px, 72vw, …)`. Em `@media (max-width: 900px)` (linha ~5616) ocupa a área abaixo da barra
  superior; em `@media (max-width: 600px)`, a tela cheia.
- `client/src/ui/icons.ts`: os ícones SVG em string (`ICONS.search`, `ICONS.close`, …). Não há ícone de bate-papo.
- `client/src/net/store.ts:344-356`, `controlAgent`: o molde dos novos métodos de rede (`fetch`, JSON e
  `{error}` com a mensagem do servidor).
- `client/src/ui/markdown.ts:431`, `renderMarkdown(src): DocumentFragment`: a resposta é desenhada com ele, como
  `terminal.ts:555` faz com o texto do assistente.
- `client/src/ui/dom.ts`: `h`, `setText`, `setHidden`, `setTitle`. Não use `innerHTML`.

### Convenções (de `AGENTS.md` e do código)

- Todo texto visível passa por `tr('texto em PT')`. As traduções ficam em `shared/locales/{en,es,fr}.ts`, e o CI roda
  `npm run i18n:check -- --strict`.
- A extensão **não** usa `tr()`: os textos dela vão em português fixo (comentário em `habblaud.ts:46`).
- Toda mudança visível entra em "Não lançado" no `CHANGELOG.md` (seção `### Adicionado`).
- O servidor não tem dependências de runtime: só `node:*`.
- Comentários e nomes seguem o estilo do arquivo vizinho: português, com um cabeçalho que lista as rotas, como em
  `server/messages/http.ts:1-11`.

## Commands you will need

| Purpose    | Command                                  | Expected on success |
|------------|------------------------------------------|---------------------|
| Install    | `npm ci`                                 | exit 0              |
| Typecheck  | `npm run typecheck` (inclui a extensão)  | exit 0              |
| One file   | `npx vitest run <arquivo>`               | all pass            |
| All tests  | `npm test`                               | all pass (baseline em `dfbdde3`: 176 arquivos, 2119 passed, 2 skipped) |
| i18n       | `npm run i18n:check -- --strict`         | exit 0              |
| Build      | `npm run build`                          | exit 0              |

## Scope

**In scope**:
- `shared/asides.ts` (criar): tipos, limites e o leitor do corpo da extensão.
- `shared/asides.test.ts` (criar)
- `shared/types.ts`: o campo `aside` em `InboxMessage`.
- `server/messages/registry.ts`: o método `aside()` e a opção `onSettled`.
- `server/messages/registry.test.ts`
- `server/asides/store.ts` (criar): `AsideStore`, com memória e disco.
- `server/asides/store.test.ts` (criar)
- `server/asides/http.ts` (criar): as rotas da página e da extensão.
- `server/asides/http.test.ts` (criar)
- `server/http/app.ts`: as travas e o encaminhamento das rotas novas.
- `server/index.ts`: a ligação.
- `mod/habblaud-omp/habblaud.ts`: iniciar, transmitir e cancelar a pergunta paralela.
- `server/test/omp-extension.test.ts`
- `client/src/net/store.ts`: `asides()`, `askAside()`, `cancelAside()` e `deleteAside()`.
- `client/src/ui/aside-model.ts` (criar) e `client/src/ui/aside-model.test.ts` (criar): a lógica pura da janela.
- `client/src/ui/aside.ts` (criar): a janela lateral de bate-papo.
- `client/src/ui/terminal.ts`: o botão na barra de cima e a coluna lateral.
- `client/src/ui/icons.ts`: o ícone `chat`.
- `client/src/ui/styles.css`
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`
- `CHANGELOG.md`

**Out of scope** (do NOT touch):
- `mod/habblaud-mensagens/**` (plugin do Claude Code): o Claude Code não tem uma API equivalente. A pergunta paralela
  fica **só** para agentes do omp.
- O `btw-history/` do omp e os comandos `/btw` da tela do omp: o histórico do Habblaud é separado e não aparece no
  `/btw` do terminal, nem o contrário. Não tente ler nem gravar os arquivos do omp.
- **Nenhum comando `/btw`** na caixa de mensagem (`client/src/ui/composer.ts`) e **nenhum botão** na faixa do omp
  (`client/src/ui/omp-bar.ts`): decisão do usuário. O único acesso é o botão do bate-papo na barra do terminal.
- Agentes do demo (`?mock=1`) e do Codex: o botão não aparece.
- `shared/world/**` e `server/world/**`: o AVISO do `AGENTS.md` vale. Nada de pergunta paralela vai ao Habblaud World.
- A gaveta do agente (`client/src/ui/drawer.ts`): a janela fica só no terminal.
- O transporte da página: nada de evento novo no SSE (`server/http/sse.ts`). O stream vai a qualquer página
  conectada, inclusive à porta remota sem a feature `terminal`. A página busca o histórico por GET (Step 7).
- `omp-tenant` (`~/Desenvolvimento/omp-tenant`): só o deploy, depois do merge (ver Maintenance notes).

## Git workflow

- Branch: `advisor/014-bate-papo-paralelo` a partir de `producao` (o branch do clone de desenvolvimento no commit
  `dfbdde3`; `git -C ~/Desenvolvimento/habblaud rev-parse --short producao` → `dfbdde3`).
- Um commit por step, no estilo do repo (português, minúsculas, o que fica verdadeiro). Exemplo de
  `git log`: `escritório a 30 qps só quando algo anda na tela; sem movimento, 10 (5 sem foco)`. Exemplo para este
  plano: `pergunta paralela: registro guarda o pedido e avisa quando ele se resolve`.
- Do NOT push, merge into `producao`, open a PR, or touch `~/apps/habblaud` unless the operator instructed it. Levar o
  trabalho para a produção é tarefa do operador, depois da revisão.

## Contrato (todas as peças usam isto)

`shared/asides.ts` (código puro, sem `node:*` nem DOM; a extensão também o importa):

```ts
export const ASIDE_QUESTION_MAX = 20_000;        // igual a MESSAGE_MAX
export const ASIDE_ANSWER_MAX = 64 * 1024;       // caracteres; o que passar é cortado com '\n…'
export const ASIDE_TOPICS_MAX = 20;              // conversas por sessão; a mais antiga sai ao criar a 21ª
export const ASIDE_TURNS_MAX = 50;               // perguntas por conversa; a 51ª dá 409
export const ASIDE_PROGRESS_MS = 500;            // a extensão manda o texto acumulado no máximo a cada 0,5 s...
export const ASIDE_HEARTBEAT_MS = 1_000;         // ...e ao menos a cada 1 s (mesmo sem texto novo)
export const ASIDE_LOST_MS = 30_000;             // em andamento sem notícia da extensão por 30 s: falha

export type AsideStatus = 'queued' | 'running' | 'complete' | 'failed' | 'cancelled';
export interface AsideTurn { id: string; question: string; answer: string; status: AsideStatus; error?: string; createdAt: number; updatedAt: number; }
export interface AsideTopic { id: string; createdAt: number; updatedAt: number; turns: AsideTurn[]; }

/** Item da caixa de entrada (InboxMessage.aside): o que a extensão precisa para rodar a pergunta. */
export interface AsideRequest {
  topicId: string;
  turnId: string;
  /** Posição da pergunta no tópico (0 = primeira): entra na conversationKey. */
  index: number;
  question: string;
  /** Perguntas anteriores do tópico, na ordem (answer vazio = sem resposta). */
  history: Array<{ question: string; answer: string; createdAt: number; updatedAt: number }>;
}

/** Corpo de POST /api/omp/aside (extensão → servidor). `text` = a resposta acumulada até agora (não um delta). */
export interface AsideProgress { sessionId: string; turnId: string; text: string; state: 'running' | 'complete' | 'failed' | 'cancelled'; error?: string; }
export function parseAsideProgress(raw: unknown): AsideProgress | undefined; // fora do formato = undefined
export function isFinalAside(s: AsideStatus): boolean; // complete | failed | cancelled
```

Por que mandar o texto **acumulado** e não um delta: um POST que se perde não estraga a resposta, e a ordem de
chegada não importa. O servidor guarda o maior `text` que chegou para aquela pergunta. Com o corte de 64 KiB, o
custo fica limitado.

Rotas:

```
Página (trava 'terminal' para ler e 'messages' para mudar; só com config.messages):
  GET    /api/asides?agentId=<id>              200 {topics: AsideTopic[]}  (da sessão atual do agente, mais novo primeiro) | 400 | 404
  POST   /api/asides  {agentId, question, topicId?}
                                               201 {topic: AsideTopic} | 400 | 404 | 409 {error} | 429
  POST   /api/asides/<topicId>/cancel {agentId} 200 {ok: true} | 404 | 409
  DELETE /api/asides/<topicId>?agentId=<id>    200 {ok: true} | 404 | 409 (com pergunta em andamento)
Extensão (trava de /api/omp/events; a porta remota já é barrada por isLocalOnlyPath):
  POST   /api/omp/aside  <AsideProgress>        200 {cancel: boolean} | 400
```

Arquivo em disco: `<dataDir>/asides/<sessionId>.json`, pasta `0o700` e arquivo `0o600`, no formato
`{"version":1,"topics":[AsideTopic...]}`. **`sessionId` só vira nome de arquivo depois de passar por
`OMP_SESSION_ID`** (`shared/omp-events.ts`, uuid). Nunca monte o caminho com um id que não passou por esse teste
(path traversal).

## Steps

### Step 1: `shared/asides.ts` e o campo `aside` na caixa de entrada

1. Crie `shared/asides.ts` com o contrato acima. Use `parseOmpEvent` (`shared/omp-events.ts`) como molde do leitor:
   `str()` com limite e `undefined` para corpo fora do formato. Regras de `parseAsideProgress`:
   - `sessionId` precisa casar com `OMP_SESSION_ID`;
   - `turnId` é uma string de 1 a 200 caracteres;
   - `text` é uma string, cortada em `ASIDE_ANSWER_MAX`;
   - `state` é um dos 4 valores;
   - `error` é opcional, uma string cortada em 500 caracteres.
2. Em `shared/types.ts`, acrescente `aside?: AsideRequest` em `InboxMessage`, com doc de uma linha: "Pergunta do
   bate-papo paralelo para a extensão do omp: sem texto e sem atividade no feed".
3. Crie `shared/asides.test.ts`: corpo válido; cada campo inválido devolve `undefined`; `text` acima do limite é
   cortado; `sessionId` como `../x` é recusado.

**Verify**: `npx vitest run shared/asides.test.ts` → all pass; `npm run typecheck` → exit 0.

### Step 2: registro: `aside()` e o gancho `onSettled`

Em `server/messages/registry.ts`:

1. Em `MessageRegistryOptions`, acrescente
   `onSettled?: (id: string, status: 'delivered' | 'failed', error?: string) => void`.
   Doc: "Toda mensagem resolvida (entregue ou falha), inclusive a confirmação atrasada que corrige uma falha".
2. No fim de `finish()`, chame `this.opts.onSettled?.(e.msg.id, status, e.msg.error)` dentro de
   `try { } catch (err) { log.warnOnce(...) }`, para um erro do gancho nunca quebrar o relógio do registro.
3. Em `Entry`, acrescente `aside?: AsideRequest`. Em `inbox()`, troque a linha 386 para levar também o
   `aside: e.aside` (só quando existir, como `control`).
4. Crie `aside(agentId: string, request: AsideRequest): SendResult`, copiando `control()`. As diferenças:
   - não há `parseControl` (quem valida o pedido é o `AsideStore`);
   - um agente que não é do omp dá `{ error: 'unavailable', reason: tr('só agentes do omp respondem perguntas paralelas') }`;
   - agente do demo dá `unavailable` com o mesmo `unavailableReason(agent, false)` de `control()`;
   - a entrada é `this.entry(agentId, agent.sessionId, { text: '' })`, e depois `entry.aside = request`.
5. Testes em `server/messages/registry.test.ts`, em `describe('MessageRegistry: agentes do omp')`, seguindo os
   testes de `control` que já estão lá:
   - `aside()` a um agente do omp conectado sai em `inbox()` com `aside` e `text: ''`;
   - um agente do Claude dá `unavailable`;
   - `ack` ok chama `onSettled(id, 'delivered', undefined)`;
   - o prazo `queued` vencido chama `onSettled(id, 'failed', ERR_NOT_FETCHED)`;
   - um `onSettled` que lança não impede a mensagem de virar `failed`.

**Verify**: `npx vitest run server/messages/registry.test.ts` → all pass.

### Step 3: `AsideStore` (`server/asides/store.ts`)

Uma classe, no estilo de `RoomAliases` e `MessageRegistry` (relógio injetável `now`, `start()`/`stop()` com
`setInterval` + `unref()`, `log.warnOnce` nos erros). Opções:

```ts
export interface AsideStoreOptions {
  /** Pasta dos arquivos (<dataDir>/asides); null = só memória (testes). */
  dir: string | null;
  office: Pick<OfficeLike, 'get'>;               // de server/messages/registry.ts
  queue: (agentId: string, req: AsideRequest) => SendResult; // = registry.aside
  now?: () => number;
  lostMs?: number;                                // padrão ASIDE_LOST_MS
  tickMs?: number;                                // padrão 1_000
}
```

O estado vive em `Map<sessionId, AsideTopic[]>`, carregado do disco na primeira vez que a sessão é usada. Ao lado
ficam três mapas: `turnId → {sessionId, topicId}`, `messageId → turnId` e `turnId → lastProgressAt`, mais um
`Set<turnId>` dos cancelamentos pedidos. Métodos:

- `list(agentId)`: `{topics}` da sessão atual do agente (`office.get(agentId)?.sessionId`), do mais novo para o mais
  velho. Agente desconhecido, ou que não é do omp: `undefined` (a rota responde 404).
- `ask(raw: unknown)`: `{ topic } | { error: 'not-found' | 'too-many' } | { error: 'unavailable' | 'busy' | 'full'; reason: string }`.
  Lança `InvalidRequest` (importe de `server/messages/registry.ts`) para corpo inválido: `agentId` ausente, pergunta
  vazia ou acima de `ASIDE_QUESTION_MAX`, `topicId` desconhecido para essa sessão. Regras:
  1. O agente precisa ser do omp, `kind === 'main'`, e a sessão precisa casar com `OMP_SESSION_ID`.
  2. Já há uma pergunta `queued`/`running` nessa sessão: `busy`, com
     `tr('o bate-papo paralelo ainda está respondendo: espere ou pare')`.
  3. Pergunta nova num tópico que já tem `ASIDE_TURNS_MAX` perguntas: `full`, com
     `tr('esta conversa chegou a {0} perguntas: comece uma nova', [ASIDE_TURNS_MAX])`.
  4. Sem `topicId`, cria um tópico. Se a sessão já tem `ASIDE_TOPICS_MAX` tópicos, o mais antigo sai.
  5. Monta o `AsideRequest`: `history` são as perguntas anteriores do tópico, inteiras, na ordem, e `index` é a
     posição da nova. Chama `queue(agentId, req)`. Se a resposta não for `{message}`, **desfaz** o tópico ou a
     pergunta criados e devolve o erro (`unavailable`/`not-found`/`too-many`). Se for, grava
     `messageId → turnId` e devolve o tópico.
  6. Grava em disco (`save(sessionId)`).
- `settled(messageId, status, error?)`: o gancho do registro. `failed` com a pergunta ainda `queued` vira `failed`,
  com o `error`. `delivered` vira `running` e marca `lastProgressAt = now`. Pergunta já final: ignora. Grava em
  disco quando muda.
- `progress(raw: unknown)`: `{ cancel: boolean }`. Usa `parseAsideProgress` e lança `InvalidRequest` quando ele
  devolve `undefined`. Pergunta desconhecida, de outra sessão, já final ou com cancelamento pedido: `{cancel: true}`,
  sem mudar nada. Se o pedido de cancelamento chegou antes de a extensão começar, a próxima notícia dela recebe o
  `cancel` e ela aborta. Fora isso:
  - atualiza `answer`, só se o texto novo for maior ou igual ao guardado, e `lastProgressAt`;
  - `queued` vira `running`, porque a notícia pode chegar antes do ack;
  - `state` final grava `status`/`error`/`updatedAt` e salva em disco.

  Uma notícia `running` **não** grava em disco: o texto parcial fica só na memória. Responda `{cancel: false}`.
- `cancel(topicId, raw)`: `{agentId}`. A pergunta em andamento do tópico entra no `Set` de cancelamentos. Se ela
  ainda está `queued`, vira `cancelled` na hora. Sem pergunta em andamento: `{ error: 'idle' }` (409). Grava em
  disco quando muda.
- `remove(topicId, agentId)`: apaga o tópico. Se ele tem pergunta `queued`/`running`: `{ error: 'busy' }` (409).
- `tick()`: pergunta `running` com `now - lastProgressAt >= lostMs` vira `failed`, com
  `tr('a extensão do omp parou de mandar notícias da resposta')`.
- `load(sessionId)` (privado): lê o arquivo. Pergunta `queued`/`running` vinda do disco vira `failed`, com
  `tr('interrompida: o Habblaud reiniciou')`. Arquivo ilegível: renomeia para `.corrupt`, como
  `server/history/daystats.ts:225`, avisa no log e começa vazio. Descarte itens fora do formato, um por um, e corte
  os campos nos limites do contrato.
- `save(sessionId)` (privado): `mkdirSync(dir, { recursive: true, mode: 0o700 })`, depois
  `writeFileSync(tmp, json, { mode: 0o600 })` e `renameSync(tmp, file)`. Falha de escrita: `log.warnOnce`, sem
  lançar (o estado em memória continua valendo).

IDs: `` `t-${now.toString(36)}-${randomBytes(6).toString('base64url')}` `` para tópico e o mesmo com `q-` para
pergunta, seguindo `registry.entry()`.

Testes em `server/asides/store.test.ts`. Use `tempDir()` de `server/test/fixtures.ts`, um `office` falso (um `Map`)
e um `queue` falso que guarda as chamadas:

- `ask` sem `topicId` cria o tópico, chama `queue` com `index: 0, history: []` e grava
  `<dir>/<sessionId>.json` com modo `0o600`. Confira com `statSync(file).mode & 0o777`. Pule a checagem de modo no
  Windows, como os outros testes do repositório fazem com `process.platform`.
- A segunda pergunta no mesmo tópico leva `index: 1` e `history` com a primeira, inclusive a resposta.
- `busy` com uma pergunta em andamento; `full` na 51ª; o 21º tópico tira o mais antigo.
- `queue` que devolve `unavailable` desfaz o tópico e nada vai para o disco.
- `settled(..., 'failed', 'x')` deixa a pergunta `failed`/`'x'`.
- `progress`: texto crescendo; um texto menor que chega depois é ignorado; `complete` grava em disco; pergunta
  desconhecida dá `{cancel: true}`; depois de `cancel()`, a próxima notícia dá `{cancel: true}`.
- `tick` depois de `lostMs` sem notícia deixa a pergunta `failed`.
- Reinício: um store novo na mesma pasta lê o tópico, e o que estava `running` vira `failed` (interrompida).
- Arquivo com JSON quebrado: vira `.corrupt` e a sessão começa vazia.
- Agente com `sessionId` que não casa com `OMP_SESSION_ID` (por exemplo `../../etc`): `ask` dá `unavailable` e
  **nenhum** arquivo é criado fora de `dir`.

**Verify**: `npx vitest run server/asides/store.test.ts` → all pass.

### Step 4: rotas (`server/asides/http.ts`) e travas (`server/http/app.ts`)

1. `server/asides/http.ts`: copie a forma de `server/messages/http.ts` (cabeçalho com as rotas, `fail()` que
   converte `HttpError`/`InvalidRequest`, `methodNotAllowed`). Exporte duas funções:
   - `createAsideRoutes(store)`, para a página: `(req, res, path, url) => void`, com as quatro rotas da página.
     `topicId` do caminho passa por `decodeURIComponent` dentro de `try`, e um erro dá 400 (como em
     `messages/http.ts`). `ask` responde 201/404/409/429. Os 409 levam `{error: reason}`. O 429 leva
     `tr('já há {0} mensagens esperando a entrega para este agente: espere a sessão buscar', [MAX_OPEN])`, o mesmo
     texto de `messages/http.ts`.
   - `handleAsideProgress(req, res, store)`, para a extensão: `readJson`, depois `store.progress`, e responde 200
     `{cancel}`. Corpo inválido dá 400.
2. `AppDeps` em `app.ts`: acrescente
   `asides?: { page: ReturnType<typeof createAsideRoutes>; progress: (req, res) => Promise<void> }`, com doc no
   estilo de `messages`.
3. Encaminhamento em `app.ts`. As duas rotas entram **antes** do bloco `isMessagesPath`:
   - `path === '/api/omp/aside'`: a mesma trava de `/api/omp/events` (só POST,
     `trusted(req)` + loopback fora do Docker). Sem `deps.asides`: 403 com o texto das mensagens desligadas, igual
     ao da linha 665.
   - `path === '/api/asides' || path.startsWith('/api/asides/')`: sem `deps.asides`, o mesmo 403. Em GET, exija
     `trusted(req, 'terminal')`. Em POST/DELETE, `trusted(req, 'messages')`. Negado:
     `denied(req, tr('perguntas paralelas só pelo próprio computador (http://localhost ou http://127.0.0.1)'))`.
4. `server/asides/http.test.ts`: copie o arranjo de `server/omp/http.test.ts` (servidor de verdade com
   `createRequestGuard` + `createApiHandler` e `request` de `server/test/permission-server`). Casos:
   - `POST /api/omp/aside` com corpo válido chega ao store;
   - corpo inválido dá 400;
   - `GET /api/asides` sem `agentId` dá 400;
   - agente desconhecido dá 404;
   - sem `deps.asides`, as duas rotas dão 403;
   - pela porta remota, `/api/omp/aside` dá 403. Para montar a porta remota, procure como `server/http/remote.test.ts`
     ou `server/http/http.test.ts` fazem. Se o arranjo passar de ~40 linhas, pule este caso e registre isso no
     relatório: `isLocalOnlyPath` já cobre `/api/omp/` e tem os próprios testes.

**Verify**: `npx vitest run server/asides` → all pass; `npm run typecheck` → exit 0.

### Step 5: ligação em `server/index.ts`

Logo depois de `const messages = ...` (linha 285):

```ts
const asides = messages
  ? new AsideStore({ dir: join(config.dataDir, 'asides'), office, queue: (id, req) => messages.aside(id, req) })
  : undefined;
asides?.start();
```

Para o registro avisar o store, passe `onSettled: (id, status, error) => asides?.settled(id, status, error)` nas
opções do `new MessageRegistry`. A closure só roda depois que o `const asides` existe, então a declaração vir depois
não causa problema. Em `createApiHandler({...})` (perto da linha 384):
`asides: asides ? { page: createAsideRoutes(asides), progress: (req, res) => handleAsideProgress(req, res, asides) } : undefined`.
Procure onde `messages.start()` e o desligamento chamam `messages.stop()`, e faça o mesmo com `asides`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → all pass.

### Step 6: extensão do omp

Em `mod/habblaud-omp/habblaud.ts`:

1. Tipos:
   - em `OmpContext`, acrescente
     `runEphemeralTurn?(opts: { promptText: string; history?: unknown[]; conversationKey?: string; dedupeReply?: boolean; onTextDelta?: (delta: string) => void; signal?: AbortSignal }): Promise<{ replyText: string }>`;
   - em `models`, acrescente `current?(): { provider: string; id: string; api?: string } | undefined`;
   - na cópia local de `InboxMessage`, acrescente `aside?: AsideRequest`, importado de `../../shared/asides`
     (o instalador do omp-tenant já copia `shared/` inteiro);
   - em `parseInbox`, aceite `aside` só se for um objeto com `topicId`, `turnId` e `question` em string, `index` em
     número e `history` em array. Fora disso, o item entra sem `aside` e sem texto, e é confirmado como falha
     "mensagem vazia".
2. Estado dentro de `create()`: `let aside: { turnId: string; abort: AbortController } | undefined`.
3. Em `deliverOne`, antes de `if (m.control)`: `if (m.aside) return startAside(m.id, m.aside);`. A função
   `startAside(id, req): AckResult` é **síncrona** e faz isto:
   - sem `mainCtx?.runEphemeralTurn`: `{ok: false, error: 'este omp não faz perguntas paralelas pela extensão (atualize o omp)'}`;
   - com `aside` em andamento: `{ok: false, error: 'já há uma pergunta paralela em andamento'}`;
   - fora isso, cria o `AbortController`, guarda em `aside`, chama `void runAside(req, controller)` e devolve
     `{id, ok: true}`.
4. `async function runAside(req, controller)`. **O corpo inteiro fica em `try { } catch { } finally { aside = undefined; clearInterval(pump) }`**:
   - `const session = sessionId` (o envio só vale para essa sessão);
   - `let text = ''; let sentLen = -1; let lastSent = 0;`;
   - `post(state, error?)`: `request('POST', '/api/omp/aside', { sessionId: session, turnId: req.turnId, text, state, ...(error ? { error } : {}) })`.
     Se a resposta for 2xx e o JSON tiver `cancel === true`, chama `controller.abort()`;
   - `pump = setInterval(() => { try { if (text.length !== sentLen || deps.now() - lastSent >= ASIDE_HEARTBEAT_MS) { sentLen = text.length; lastSent = deps.now(); void post('running').catch(() => {}); } } catch { /* nunca chega ao omp */ } }, ASIDE_PROGRESS_MS); pump.unref?.();`;
   - `history` no formato do `/btw` do omp (veja "Current state"), usando `mainCtx.models?.current?.()`. Sem modelo,
     ou sem `api`, mande `history: undefined` e ponha as perguntas anteriores no começo do `promptText`, como texto
     ("Pergunta anterior: … / Resposta: …"). Isso garante que a pergunta não falhe por um detalhe do formato;
   - `const { replyText } = await ctx.runEphemeralTurn({ promptText: req.question, history, conversationKey: \`hb:${req.topicId}:${req.index}\`, dedupeReply: false, onTextDelta: (d) => { text += d; }, signal: controller.signal })`;
   - deu certo: `text = replyText || text`, depois `await post('complete')`;
   - deu erro: `controller.signal.aborted` dá `await post('cancelled')`. Fora isso,
     `await post('failed', reasonText(err, 'falha na pergunta paralela'))`. `reasonText` já existe no arquivo.
5. `session_shutdown` e a troca de sessão (procure `session_switch` no arquivo): `aside?.abort.abort()`. A sessão
   nova não pode receber a resposta da anterior.
6. Testes em `server/test/omp-extension.test.ts`, num `describe('omp extension: pergunta paralela')` novo, usando
   `setup()`/`fakeHabblaud()` e um `ctx.runEphemeralTurn` falso que chama `onTextDelta` e resolve quando o teste
   quiser (`Promise.withResolvers`). Use `vi.useFakeTimers()` como os testes de mensagens já fazem. Casos:
   - um inbox com `aside` confirma `ok: true` **antes** de a resposta acabar, sem esperar a promise;
   - os deltas viram POSTs em `/api/omp/aside` com o `text` acumulado e `state: 'running'`; o fim manda
     `state: 'complete'` com `replyText`;
   - `runEphemeralTurn` recebe `dedupeReply: false`, `conversationKey: 'hb:<topic>:<index>'` e um `history` com
     `role: 'user'`/`'assistant'`;
   - uma resposta `{cancel: true}` aborta o `signal`, e o último POST é `state: 'cancelled'`;
   - `runEphemeralTurn` que rejeita dá `state: 'failed'` com o texto do erro;
   - sem `runEphemeralTurn` no contexto, o ack sai `ok: false` com o motivo;
   - uma segunda pergunta durante a primeira dá ack `ok: false`;
   - `fetch` que rejeita (Habblaud fora do ar) durante a resposta não lança nada: o teste só confere que nenhuma
     rejeição escapou. Use `process.on('unhandledRejection')` no teste ou o padrão que o arquivo já usa para isso.

**Verify**: `npx vitest run server/test/omp-extension.test.ts` → all pass; `npm run typecheck` → exit 0.

### Step 7: cliente (rede, modelo, janela lateral de bate-papo)

Vocabulário na interface: "bate-papo paralelo" e "conversa" (um tópico do servidor). Nunca mostre "/btw", "tópico"
ou "aside" ao usuário.

1. `client/src/net/store.ts`: quatro métodos no molde de `controlAgent`, todos com
   `if (this.mock) return { error: tr('indisponível no modo de demonstração') }`:
   - `asides(agentId)`: `{ topics } | { error }`;
   - `askAside(agentId, question, topicId?)`: `{ topic } | { error }`;
   - `cancelAside(agentId, topicId)`: `{ ok: true } | { error }`;
   - `deleteAside(agentId, topicId)`: `{ ok: true } | { error }`.
2. `client/src/ui/aside-model.ts`, com funções puras e testáveis no Node:
   - `asideRunning(topics)`: há pergunta `queued`/`running`;
   - `asidePollMs(topics, open)`: 700 com a janela aberta e pergunta em andamento; senão `null` (sem polling);
   - `asideStatusText(turn)`:
     - `queued`: `tr('esperando o omp buscar…')`;
     - `running` sem texto: `tr('pensando…')`;
     - `cancelled`: `tr('parada')`;
     - `failed`: `tr('falhou: {0}', [error])`;
     - os outros: `''`;
   - `currentTopic(topics, chosenId)`: o tópico escolhido, se ainda existir; senão o mais recente (`topics[0]`);
     sem tópicos, `undefined`. Depois de "Nova conversa", `chosenId` é `null` e a função devolve `undefined` até a
     primeira pergunta criar o tópico (a janela guarda um flag `fresh` para isso);
   - `topicLabel(topic)`: a primeira pergunta cortada em 60 caracteres, com `…`, mais a hora de `createdAt`
     (use o formatador de hora que `client/src/ui/format.ts` já tiver);
   - Testes em `client/src/ui/aside-model.test.ts`, um `it` por regra: escolhido que sumiu cai no mais recente;
     lista vazia; corte do rótulo; polling só aberto e em andamento.
3. `client/src/ui/aside.ts`: classe `AsideChat`, a janela lateral. API: `el`, `toggle(agentId)`, `close()`,
   `isOpen`, `render(agent, ready)` e `onToggle?: (open: boolean) => void` (o terminal ajusta a largura). O
   elemento: `h('aside', { class: 'ui-chat', hidden: true, attrs: { 'aria-label': tr('Bate-papo paralelo') } }, …)`.
   De cima para baixo:
   - **Cabeçalho**: título `tr('Bate-papo paralelo')`; abaixo, em letra menor, `tr('Não entra na conversa do agente nem espera ele terminar')`.
     À direita: `<select>` `tr('Conversas')` com as conversas da sessão (`topicLabel`, mais nova em cima), o botão
     `tr('Nova conversa')`, o botão `tr('Apagar conversa')` (desligado com pergunta em andamento; pede `confirm()`)
     e fechar (`iconButton(ICONS.close, …)`).
   - **Mensagens** (`role: 'log'`, `aria-live: 'polite'`, scroll próprio): para cada pergunta da conversa atual, um
     balão do usuário à direita (texto puro com `setText`) e um balão do agente à esquerda com
     `renderMarkdown(answer)`, mais a linha `asideStatusText` embaixo quando não estiver vazia. Sem conversa: um
     texto vazio centralizado, `tr('Pergunte algo sobre o que o agente está fazendo. Ele não para o que está fazendo para responder.')`.
     Quem está no fim do scroll continua no fim quando a resposta cresce; quem subiu para ler não é puxado. Ao
     redesenhar uma resposta que só cresceu, troque só o corpo daquele balão.
   - **Caixa** no rodapé da coluna: `textarea` (`maxlength` `ASIDE_QUESTION_MAX`, `placeholder` `tr('Mensagem para o bate-papo paralelo')`;
     Enter manda e Shift+Enter quebra linha, com `enterSends` de `composer-model.ts`) e botão enviar. Com pergunta
     em andamento, o botão vira `tr('Parar')` e chama `cancelAside`. A caixa continua editável durante a resposta
     (o rascunho espera).
   - Linha de erro do servidor (`role: 'status'`), acima da caixa.

   Enviar: `askAside(agentId, texto, fresh ? undefined : current?.id)`. A resposta traz o tópico; ele passa a ser o
   escolhido e `fresh` volta a `false`. Rascunho e conversa escolhida ficam guardados por agente, só na memória (um
   `Map`, como os rascunhos do `MessageComposer`).

   Polling: ao abrir e depois de cada ação, chame `asides()`. Depois, `setTimeout` com `asidePollMs`. Ao fechar, ou
   quando o agente muda, limpe o timer. Escape dentro da janela fecha a janela e devolve o foco à caixa do terminal,
   sem fechar o terminal (`e.stopPropagation()`).
4. `client/src/ui/icons.ts`: acrescente `chat`, um balão de conversa no mesmo estilo de traço dos vizinhos. Copie
   `viewBox`, `stroke-width` e atributos de `ICONS.search`.
5. `client/src/ui/terminal.ts`:
   - crie `this.chat = new AsideChat(ctx)` e `this.chatBtn = iconButton(ICONS.chat, tr('Bate-papo paralelo: pergunte sem interromper o agente'), () => this.toggleChat(), 'ui-icon-btn--sm ui-term__chat-btn')`;
     ponha `this.chatBtn` na barra, logo depois de `this.findBtn`;
   - a coluna: troque os filhos do `section` para `bar, this.tabs.el, h('div', { class: 'ui-term__split' }, h('div', { class: 'ui-term__main' }, tools, body, foot), this.chat.el)`.
     `tools`, `body` e `foot` são os mesmos elementos de hoje, só mudam de pai. Confira que nada no arquivo depende de
     eles serem filhos diretos de `this.el` (`grep -n "this.el.children\|firstElementChild\|:scope >" client/src/ui/terminal.ts`).
     Se depender, STOP;
   - `toggleChat()`: só com agente ao vivo do omp, `kind === 'main'`, pronto e fora do mock. Chama
     `this.chat.toggle(id)`, alterna a classe `is-chat` em `this.el` e marca `aria-pressed` no botão;
   - em `render()`: `setHidden(this.chatBtn, !(omp && ready && !mock))` e `this.chat.render(live ?? undefined, kind === 'ready')`.
     Se o agente deixar de ser omp ou pronto, a janela fecha e `is-chat` sai;
   - ao trocar de conversa (`start`) ou fechar o terminal, a janela fecha.
6. `client/src/ui/styles.css`:
   - `.ui-term__split { display: flex; flex: 1; min-height: 0 }` e `.ui-term__main { display: flex; flex-direction: column; flex: 1; min-width: 0 }`.
     Sem o bate-papo aberto, o terminal fica igual ao de hoje (confira a olho no Step 9);
   - `.ui-term.is-chat { --term-w: min(1320px, 94vw, …) }`: mesma fórmula de `--term-w`, com o teto maior, para a
     conversa do agente não encolher. `.ui-chat { width: 380px; border-left: 1px solid var(--ui-line-strong); display: flex; flex-direction: column }`;
   - balões: o do usuário à direita, com o fundo de destaque que o arquivo já usa em botões primários; o do agente à
     esquerda, com o fundo neutro dos cartões. Use só variáveis de cor que já existem. Não crie cores novas;
   - `@media (max-width: 900px)`: `.ui-chat` cobre a coluna principal (`position: absolute; inset: 0; width: auto`
     dentro do `.ui-term__split`, que ganha `position: relative`). Fechar volta à conversa do agente.

**Verify**: `npx vitest run client/src/ui/aside-model.test.ts` → all pass; `npm run typecheck` → exit 0;
`npm run build` → exit 0; `grep -rn "/btw" client/src` → nenhuma ocorrência.

### Step 8: traduções, CHANGELOG, suíte inteira

1. Para cada `tr()` novo, inclusive os do servidor, crie a entrada em `shared/locales/en.ts`, `es.ts` e `fr.ts`.
2. Em `CHANGELOG.md`, em "Não lançado › Adicionado", escreva uma entrada no estilo das vizinhas: **Bate-papo
   paralelo no omp:** no terminal de um agente do omp, o botão de balão na barra de cima abre uma janela lateral de
   bate-papo. Ali dá para perguntar sobre a conversa sem interromper o agente e sem entrar no contexto dele (a
   mesma maquinaria do `/btw` do omp). As conversas ficam guardadas por sessão em `<dataDir>/asides/`. Reinstale a
   extensão (`npm run omp:install`) e reabra as sessões do omp.

**Verify**: `npm run i18n:check -- --strict` → exit 0; `npm test` → all pass (2119 + os novos); `npm run typecheck`
→ exit 0; `npm run build` → exit 0.

### Step 9: prova com um omp de verdade

Os testes usam um `runEphemeralTurn` falso. Prove o caminho real uma vez:

Tudo isolado: servidor de desenvolvimento noutra porta, dados num diretório temporário e o omp carregando a extensão
do clone por `-e`, sem instalar nada em `~/.omp` nem tocar o Habblaud de produção (contêiner `habblaud`).

1. Em `~/Desenvolvimento/habblaud`: `npm run build && HABBLAUD_DATA_DIR=$(mktemp -d) HABBLAUD_PORT=4799 npm start`.
   Se as mensagens vierem desligadas, o motivo aparece no log (`messagesOffReason` em `server/config.ts`); não ligue
   nada fora deste processo.
2. Noutro terminal, num diretório descartável:
   `HABBLAUD_PORT=4799 omp --no-extensions -e ~/Desenvolvimento/habblaud/mod/habblaud-omp/habblaud.ts`. O `-e`
   carrega o `export default` da extensão (`habblaud.ts:978`); `--no-extensions` evita que a extensão instalada (a
   de produção) também carregue. Mande uma tarefa longa (por exemplo "conte de 1 a 200 devagar, uma linha por
   vez").
3. Abra `http://localhost:4799`, abra o terminal do agente e clique no botão de balão na barra de cima. **Esperado**:
   a janela lateral abre à direita da conversa e o terminal alarga. Pergunte "o que você está fazendo agora?".
   **Esperado**: a resposta aparece crescendo num balão enquanto o agente principal **continua** o turno, e nada da
   pergunta aparece na conversa do agente no omp. Mande uma segunda pergunta que dependa da primeira ("e depois
   disso?"). **Esperado**: a resposta mostra que o histórico da conversa foi usado.
4. Faça uma pergunta e clique em Parar no meio. **Esperado**: o balão fica "parada". Clique em Nova conversa,
   pergunte algo, e troque entre as duas no seletor de conversas.
5. Reinicie o servidor e reabra a janela. **Esperado**: as duas conversas continuam lá (o arquivo
   `$HABBLAUD_DATA_DIR/asides/<uuid>.json` existe, com modo `600`).
6. Feche a janela e reduza o navegador para menos de 900 px de largura. **Esperado**: sem a janela, o terminal fica
   igual a antes; com ela aberta, o bate-papo cobre a conversa e fechar volta para ela.

Registre o resultado de 3–6 no relatório final. Se não houver um omp utilizável na máquina, registre "prova real
não feita: <motivo>" e **não** marque o plano como DONE: use BLOCKED.

## Test plan

- `shared/asides.test.ts`: o leitor do corpo da extensão (válido, cada campo inválido, corte, path traversal).
- `server/messages/registry.test.ts`: `aside()` (sai no inbox, só omp) e `onSettled` (ok, prazo, gancho que lança).
- `server/asides/store.test.ts`: o ciclo de uma pergunta, os limites, o cancelamento, a perda de notícia, a
  gravação (`0o600`), o reinício, o arquivo corrompido e o id de sessão hostil. Molde: `server/model/names` e os
  testes de `daystats` para o disco.
- `server/asides/http.test.ts`: as travas e as respostas. Molde: `server/omp/http.test.ts`.
- `server/test/omp-extension.test.ts`: ack imediato, transmissão acumulada, cancelamento, falha, omp sem a API e
  nenhuma rejeição solta.
- `client/src/ui/aside-model.test.ts`: polling, situação, conversa atual e rótulo.
- Nenhum teste de DOM da janela: o repositório testa só os `*-model.ts` da UI. A prova da janela é o Step 9.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 6 arquivos de teste acima existindo e passando
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `npm run build` exits 0
- [ ] `grep -n "runEphemeralTurn" mod/habblaud-omp/habblaud.ts` → aparece no tipo e na chamada
- [ ] `grep -n "dedupeReply: false" mod/habblaud-omp/habblaud.ts` → 1 ocorrência
- [ ] `grep -n "maxTokens\|tools: false" mod/habblaud-omp/habblaud.ts` → nenhuma ocorrência
- [ ] `grep -rn "OMP_SESSION_ID" server/asides/store.ts` → usado antes de montar o caminho do arquivo
- [ ] `grep -rn "/btw" client/src` → nenhuma ocorrência (não há comando)
- [ ] `git diff --stat producao -- client/src/ui/composer.ts client/src/ui/omp-bar.ts` → vazio
- [ ] `git -C ~/apps/habblaud status --short` → igual ao de antes de começar (nada mudou na produção)
- [ ] `git status` → só arquivos da lista "In scope" modificados ou criados
- [ ] Step 9 feito, com o resultado no relatório
- [ ] Linha 014 em `plans/README.md` atualizada

## STOP conditions

Stop and report back (do not improvise) if:

- O código em "Current state" não bate com o arquivo (drift desde `dfbdde3`).
- `ctx.runEphemeralTurn` não existe no contexto real da extensão (Step 9). O tipo é do omp 18.12.6; se o omp da
  máquina for outro, informe a versão (`omp --version`).
- O omp real recusar o `history` montado e o caminho "como texto" também falhar.
- A pergunta paralela, no Step 9, atrasar ou interromper o turno principal. Isso contraria a premissa do plano.
- Para encaixar a janela, for preciso mudar `server/http/sse.ts`, `client/src/ui/drawer.ts`,
  `client/src/ui/composer.ts`, `client/src/ui/omp-bar.ts` ou qualquer arquivo de `shared/world/`/`server/world/`.
- Um step falhar na verificação duas vezes depois de uma tentativa razoável de correção.

## Maintenance notes

- **Publicação (operador, fora do plano)**: depois da revisão, levar o commit de `~/Desenvolvimento/habblaud` para
  `~/apps/habblaud`, rodar `omp-tenant release <commit>` e depois `omp-tenant sync`. O release copia
  `mod/habblaud-omp` e `shared/` do commit. Depois, reabrir as sessões do omp de cada usuário: a extensão só é lida
  quando a sessão abre. Os pods rodam `habblaud:producao`, então a imagem também precisa ser refeita (a mesma
  rotina dos planos anteriores).
- **Custo**: cada pergunta manda a conversa inteira ao provedor (como o `/btw`). Pelo proxy do omp-tenant, isso
  entra na cota do usuário. Não há limite de tokens de saída (veja "Current state": `maxTokens` quebra
  provedores). O teto prático é o `ASIDE_ANSWER_MAX` no que se guarda, não no que se paga.
- **Disco**: um arquivo por sessão que recebeu pergunta, com no máximo 20 conversas × 50 perguntas × 64 KiB por
  resposta. Arquivos de sessões antigas não são apagados. Se crescer, uma poda por idade no `start()` do store é o
  próximo passo (fora deste plano, porque ninguém pediu e o volume esperado é pequeno).
- **Privacidade**: as respostas contêm trechos da conversa e ficam em disco em `0600`, sob o `dataDir` do usuário.
  As rotas de leitura exigem a feature `terminal`, a mesma de ler a conversa no terminal. Quem revisar deve
  conferir que nenhuma resposta vai ao SSE, ao log ou ao Habblaud World.
- **Na revisão, olhe com cuidado**:
  - o `try/catch` em todo código assíncrono novo da extensão (timer de envio, `runAside`);
  - o teste de `OMP_SESSION_ID` antes de qualquer caminho;
  - o `onSettled` sem lançar;
  - a extensão abortar a pergunta quando a sessão troca.
- Se o omp passar a expor o histórico do `/btw` às extensões (há métodos RPC `get_btw_history`/`btw_cancel` no
  binário, hoje fora da API de extensão), dá para unificar os dois históricos. Fica para depois: o formato e a
  trava de concorrência do `btw-history/` são do omp e mudam sem aviso.
