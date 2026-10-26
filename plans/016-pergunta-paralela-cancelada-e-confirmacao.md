# Plan 016: Pergunta paralela cancelada na fila nunca chega ao omp, e a extensão repete a confirmação que se perdeu

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- server/asides/store.ts server/asides/store.test.ts server/asides/http.test.ts server/messages/registry.ts server/messages/registry.test.ts server/index.ts mod/habblaud-omp/habblaud.ts server/test/omp-extension.test.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: basta `## [Não lançado]` e um
> `### Corrigido` dentro dele existirem.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `af73a23` (`producao`), 2026-10-25

## Why this matters

Dois defeitos no caminho das mensagens ao omp, ambos vistos pelo usuário:

1. **Pergunta paralela cancelada ainda é respondida.** No bate-papo paralelo, o botão de parar uma pergunta que ainda
   está `queued` só marca o turno como `cancelled` no `AsideStore`. A mensagem correspondente continua `queued` no
   `MessageRegistry`, e a próxima rodada da extensão (a cada 5 s com o agente ocupado) a recebe e roda um
   `runEphemeralTurn` de verdade: gasta tokens com uma pergunta que o usuário desistiu e ocupa a vaga única de pergunta
   paralela. Se o usuário já fez outra pergunta, ela vem no mesmo lote e é recusada ("já há uma pergunta paralela em
   andamento"), então o usuário vê a pergunta nova falhar.
2. **Confirmação perdida vira "não entregue".** A extensão manda a confirmação (`ack`) de cada lote uma única vez e
   ignora o resultado; `request` engole erros com prazo de 2 s. O servidor falha uma mensagem `sent` depois de 30 s
   ("a sessão não confirmou a entrega"). Um solavanco de rede de 2 s faz uma mensagem **entregue** aparecer como
   falha; o usuário reenvia e o agente recebe a mensagem duas vezes.

Depois deste plano: cancelar uma pergunta na fila a retira da fila (a extensão nunca a recebe), e a extensão repete o
`ack` algumas vezes, só quando o pedido não foi bem-sucedido, dentro dos 30 s do servidor.

## Current state

Arquivos:

- `server/asides/store.ts` — `AsideStore`: conversas e perguntas do bate-papo paralelo; `cancel()` para a pergunta.
- `server/messages/registry.ts` — `MessageRegistry`: fila de mensagens aos agentes; `inbox()` entrega à extensão;
  `ack()` recebe a confirmação (aceita a atrasada).
- `server/index.ts` — liga os dois (`onSettled` do registro chama `asides.settled`; o store recebe `queue`).
- `mod/habblaud-omp/habblaud.ts` — a extensão do omp: `inboxRound` busca e confirma; `startAside` roda a pergunta.
- `server/asides/store.test.ts`, `server/asides/http.test.ts`, `server/messages/registry.test.ts`,
  `server/test/omp-extension.test.ts` — testes.

### Defeito 1 — `AsideStore.cancel` não toca o registro

`server/asides/store.ts:31-41` (opções) e `:62-76`:

```ts
export interface AsideStoreOptions {
  /** Pasta dos arquivos (<dataDir>/asides); null = só memória (testes). */
  dir: string | null;
  office: Pick<OfficeLike, 'get'>;
  /** = MessageRegistry.aside */
  queue: (agentId: string, req: AsideRequest) => SendResult;
  now?: () => number;
  ...
}
...
  private readonly turns = new Map<string, { sessionId: string; topicId: string }>();
  private readonly byMessage = new Map<string, string>();
```

`byMessage` é `messageId → turnId`. `server/asides/store.ts:136-147` (`ask`) preenche:

```ts
    const sent = this.opts.queue(agentId, req);
    if (!('message' in sent)) return sent;
    ...
    this.turns.set(turn.id, { sessionId, topicId: topic.id });
    this.byMessage.set(sent.message.id, turn.id);
```

`server/asides/store.ts:151-168` (`settled`, chamado pelo registro quando a mensagem se resolve): apaga a entrada de
`byMessage` e só age se `turn.status === 'queued'`.

`server/asides/store.ts:203-216` (**o defeito**):

```ts
  /** Para a pergunta em andamento da conversa. `idle` = nada a parar. */
  cancel(topicId: string, raw: unknown): { ok: true } | { error: 'not-found' | 'idle' } {
    const found = this.findTopic(topicId, raw);
    if (!found) return { error: 'not-found' };
    const turn = found.topic.turns.find((q) => !isFinalAside(q.status));
    if (!turn) return { error: 'idle' };
    if (turn.status === 'queued') {
      this.finishTurn({ sessionId: found.sessionId, topic: found.topic, turn }, 'cancelled', undefined, this.now());
      this.save(found.sessionId);
    } else {
      this.cancels.add(turn.id);
    }
    return { ok: true };
  }
```

`server/messages/registry.ts:349-362` (`aside`): cria a entrada na fila, com `entry.aside = request`:

```ts
    const entry = this.entry(agentId, agent.sessionId, { text: '' });
    entry.aside = request;
    this.messages.set(entry.msg.id, entry);
    return { message: { ...entry.msg } };
```

`server/messages/registry.ts:386-413` (`inbox`): entrega toda mensagem `queued` do agente, perguntas paralelas
incluídas:

```ts
    const out: InboxMessage[] = [];
    for (const e of this.messages.values()) {
      if (out.length >= INBOX_BATCH) break;
      if (e.demo || e.codex || e.msg.agentId !== agent.id || e.msg.status !== 'queued' || e.target !== session) continue;
      this.setStatus(e, 'sent', now);
      e.session = session;
      e.sentAt = now;
      out.push({ id: e.msg.id, text: e.text, ...(e.control ? { control: e.control } : {}), ...(e.aside ? { aside: e.aside } : {}) });
    }
```

`isFinal(e)` (`registry.ts:155`) = `delivered` ou `failed`; `openCount` (`:364-368`) conta as não finais, e
`tick()` (`:487-491`) apaga as finais depois de `KEEP_MS`.

`server/index.ts:287-302` (a ligação):

```ts
const messages = config.messages
  ? new MessageRegistry({
      office,
      ...
      onSettled: (id, status, error) => asides?.settled(id, status, error),
    })
  : undefined;
// Bate-papo paralelo: as conversas ficam em <dataDir>/asides/ (uma por sessão do omp). Só com as mensagens ligadas.
const asides = messages
  ? new AsideStore({ dir: join(config.dataDir, 'asides'), office, queue: (id, req) => messages.aside(id, req) })
  : undefined;
```

Como a extensão reage (`mod/habblaud-omp/habblaud.ts:725-733`): `startAside` confirma na hora e roda a pergunta; com
uma já em andamento recusa a seguinte:

```ts
  function startAside(id: string, req: AsideRequest): AckResult {
    const ctx = mainCtx;
    if (!ctx?.runEphemeralTurn) return { id, ok: false, error: 'este omp não faz perguntas paralelas pela extensão (atualize o omp)' };
    if (aside) return { id, ok: false, error: 'já há uma pergunta paralela em andamento' };
    const controller = new AbortController();
    aside = { turnId: req.turnId, abort: controller };
    void runAside(ctx, req, controller);
    return { id, ok: true };
  }
```

Quando a pergunta já está `sent` (a extensão a buscou, o `ack` ainda não chegou) o caminho **já funciona**: o
`cancel()` marca `cancelled`, o `ack` chega, `settled` não faz nada (turno não é `queued`), e o primeiro `progress` da
extensão recebe `{cancel: true}` (`store.ts:175`: turno final) e aborta. Só a fatia `queued` vaza. O plano mexe só nela.

### Defeito 2 — `ack` enviado uma vez

`mod/habblaud-omp/habblaud.ts:59-62` (constantes) e `:498-513` (`request` nunca lança; devolve `undefined` em erro de
rede ou prazo):

```ts
export const FETCH_TIMEOUT_MS = 2_000;
export const INBOX_IDLE_MS = 2_000;
export const INBOX_BUSY_MS = 5_000;
export const INBOX_OFFLINE_MS = 30_000;
```

```ts
  async function request(method: 'GET' | 'POST', path: string, body?: unknown, signal?: AbortSignal, timeoutMs = FETCH_TIMEOUT_MS): Promise<Reply | undefined> {
    try {
      ...
      return { status: res.status, text: await res.text() };
    } catch {
      return undefined;
    }
  }

  const ok = (r: Reply | undefined): r is Reply => !!r && r.status >= 200 && r.status < 300;
```

`sleep(ms, signal?)` (`:453`) já existe. `closed` (`:490`) vira `true` no encerramento da sessão (`:656`).

`mod/habblaud-omp/habblaud.ts:882-902` (**o defeito**):

```ts
  /** Uma rodada: pergunta, entrega, confirma. Uma por vez (o próximo agendamento só sai no fim). */
  async function inboxRound(): Promise<void> {
    let next = busy ? INBOX_BUSY_MS : INBOX_IDLE_MS;
    try {
      const session = sessionId;
      if (!session) {
        next = INBOX_IDLE_MS;
      } else {
        const res = await request('POST', '/api/mod/inbox', { session });
        const messages = ok(res) ? parseInbox(res.text) : undefined;
        if (!messages) next = INBOX_OFFLINE_MS;
        else if (messages.length) {
          const results = await deliver(session, messages);
          await request('POST', '/api/mod/inbox/ack', { session, results });
        }
      }
    } catch {
      next = INBOX_OFFLINE_MS;
    }
    scheduleInbox(next);
  }
```

Lado do servidor (**não muda**): `registry.ts:35` `SENT_TIMEOUT_MS = 30_000`; `tick()` (`:505-508`) falha a mensagem
`sent` e põe `e.late = true`; `ack()` (`:420-432`) é idempotente — ignora id que não está `sent` (nem `failed` com
`late`) — e uma confirmação atrasada ainda corrige a situação. Logo repetir o `ack` é seguro.

Convenções: comentários em português explicando o porquê; constantes exportadas em `habblaud.ts` com JSDoc de uma
linha; testes `it('frase em português', ...)`; mudanças visíveis no `CHANGELOG.md` em `## [Não lançado]` ›
`### Corrigido`. Sem texto novo em `tr(...)`: `npm run i18n:check` não é necessário.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes (tudo) | `npm test` | exit 0 (linha de base em `af73a23`: 181 arquivos / 2180 testes passam, 2 pulados) |
| Registro | `npx vitest run server/messages/registry.test.ts` | todos passam |
| Asides | `npx vitest run server/asides` | todos passam |
| Extensão | `npx vitest run server/test/omp-extension.test.ts` | todos passam |

## Scope

**In scope** (the only files you should modify):

- `server/messages/registry.ts`
- `server/asides/store.ts`
- `server/index.ts` (só a criação do `AsideStore`)
- `mod/habblaud-omp/habblaud.ts`
- `server/messages/registry.test.ts`
- `server/asides/store.test.ts`
- `server/asides/http.test.ts` (só o `new AsideStore({...})` de `serve`)
- `server/test/omp-extension.test.ts`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- O contrato do `ack`/`inbox` no servidor (`ack()`, `SENT_TIMEOUT_MS`): a correção do defeito 2 é só na extensão.
- O caso da pergunta já `sent` (descrito acima): já funciona pelo `progress` com `cancel: true`.
- `mod/habblaud-mensagens/**` (plugin do Claude Code, outro código de confirmação).
- Mensagens e controles (`send`, `control`): só a pergunta paralela é retirada da fila.
- `~/apps/habblaud` (produção): nunca mexer.

## Git workflow

- Worktree: `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-016 -b advisor/016-pergunta-paralela-cancelada producao`
  e `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-016/node_modules` (**nunca** commite o
  symlink; confira com `git status` antes de cada commit). Trabalhe em `/tmp/hb-016`.
- Um commit por passo de código (passos 1–2 juntos, passos 3–4 juntos); assunto em português, minúsculo, como no
  `git log --oneline`. Exemplos: `cancelar pergunta paralela na fila tira a mensagem do registro`;
  `extensão do omp repete o ack da caixa de entrada quando o pedido falha`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: `MessageRegistry.discard` — retirar da fila uma pergunta paralela ainda não buscada

Em `server/messages/registry.ts`, logo depois de `aside(...)` (termina em `:362`) e antes de `private openCount`,
acrescente:

```ts
  /**
   * Retira da fila uma pergunta paralela que a extensão ainda não buscou (o usuário a cancelou): sem isto, a próxima
   * rodada a entregaria e o omp gastaria tokens com uma pergunta já desistida. Já buscada (`sent`), resolvida ou que
   * não é pergunta paralela: nada muda (a extensão é parada pelo `progress`, em AsideStore). Devolve true se retirou.
   */
  discard(id: string): boolean {
    const e = this.messages.get(id);
    if (!e?.aside || e.msg.status !== 'queued') return false;
    return this.messages.delete(id);
  }
```

Não chame `onSettled` aqui: quem cancelou (o `AsideStore`) já encerrou o turno. Apagar a entrada também libera a vaga
de `openCount`.

**Verify**: `npm run typecheck` → exit 0.

### Step 2: `AsideStore.cancel` retira a mensagem da fila

Em `server/asides/store.ts`:

1. Em `AsideStoreOptions`, depois de `queue`:

   ```ts
     /** = MessageRegistry.discard: tira da fila a mensagem de uma pergunta cancelada antes de a extensão buscá-la. */
     discard: (messageId: string) => boolean;
   ```

2. Em `cancel()`, no ramo `turn.status === 'queued'`, **antes** de `finishTurn`, descubra o id da mensagem, apague-o
   de `byMessage` (assim `settled` não age sobre um turno já cancelado) e retire-a da fila:

   ```ts
       if (turn.status === 'queued') {
         // Sem isto a mensagem ficaria na fila e a extensão responderia (com tokens) a uma pergunta cancelada.
         for (const [messageId, turnId] of this.byMessage) {
           if (turnId !== turn.id) continue;
           this.byMessage.delete(messageId);
           this.opts.discard(messageId);
           break;
         }
         this.finishTurn({ sessionId: found.sessionId, topic: found.topic, turn }, 'cancelled', undefined, this.now());
         this.save(found.sessionId);
       } else {
   ```

   Se `discard` devolver `false` (a mensagem já foi buscada, `sent`), nada mais a fazer: o turno fica `cancelled` e o
   `progress` da extensão receberá `cancel: true`, como hoje. Não trate o retorno.

3. Em `server/index.ts:301`, passe a função:

   ```ts
     ? new AsideStore({ dir: join(config.dataDir, 'asides'), office, queue: (id, req) => messages.aside(id, req), discard: (id) => messages.discard(id) })
   ```

4. Em `server/asides/store.test.ts` (função `setup`, `new AsideStore({...})`, linhas ~36-45) e em
   `server/asides/http.test.ts` (`serve`, `new AsideStore({...})`, linhas ~37-41) acrescente `discard` para o
   typecheck passar. Em `store.test.ts`, registre as chamadas para o passo 5:
   `const discarded: string[] = [];` ao lado de `calls`, `discard: (id) => (discarded.push(id), true),` no store e
   `discarded` no objeto devolvido por `setup`. Em `http.test.ts`: `discard: () => true,`.

**Verify**: `npm run typecheck` → exit 0; `npx vitest run server/asides` → todos passam (os testes novos vêm no
passo 5).

### Step 3: A extensão repete o `ack` quando o pedido falha

Em `mod/habblaud-omp/habblaud.ts`:

1. Constante, depois de `INBOX_OFFLINE_MS` (linha 62):

   ```ts
   /** Esperas antes de repetir o ack da caixa de entrada que não chegou: o servidor falha a mensagem sem ack em 30 s. */
   export const ACK_RETRY_MS: readonly number[] = [300, 1_000, 3_000];
   ```

   (pior caso: 4 pedidos de até 2 s + 4,3 s de espera ≈ 12,3 s, dentro dos 30 s.)

2. Função, logo antes de `inboxRound` (`/** Uma rodada: ...`):

   ```ts
   /**
    * Confirma o lote. Repete só quando o pedido não chegou ou o servidor falhou (rede, prazo, 5xx): um 4xx é recusa
    * definitiva e repetir não muda nada. O servidor ignora ack repetido, então repetir é seguro; sem a repetição, um
    * ack perdido faz uma mensagem entregue aparecer como "não confirmada" e o usuário a reenvia.
    */
   async function sendAck(session: string, results: readonly AckResult[]): Promise<void> {
     for (let attempt = 0; ; attempt++) {
       const r = await request('POST', '/api/mod/inbox/ack', { session, results });
       if (r && r.status < 500) return;
       const wait = ACK_RETRY_MS[attempt];
       if (wait === undefined || closed) return;
       await sleep(wait);
     }
   }
   ```

3. Em `inboxRound`, troque
   `await request('POST', '/api/mod/inbox/ack', { session, results });` por `await sendAck(session, results);`.

**Verify**: `npm run typecheck` → exit 0; `grep -n "inbox/ack" mod/habblaud-omp/habblaud.ts` → uma única linha (dentro
de `sendAck`).

### Step 4: Changelog

Em `CHANGELOG.md`, em `## [Não lançado]` › o `### Corrigido` que já existe, acrescente no início da lista:

```markdown
- **Pergunta paralela cancelada ainda gastava tokens:** parar uma pergunta do bate-papo paralelo que ainda esperava
  na fila não a tirava dela, e o omp a respondia assim mesmo (e recusava a pergunta seguinte). Agora ela sai da fila
  na hora do cancelamento.
- **Mensagem entregue aparecendo como "não confirmada":** se a confirmação de entrega da extensão do omp se perdia
  por um instante (rede ou servidor ocupado), a mensagem aparecia como falha e o reenvio chegava duas vezes ao
  agente. A extensão agora repete a confirmação algumas vezes. Reinstale a extensão (`npm run omp:install`) e reabra
  as sessões do omp.
```

**Verify**: `git diff --stat -- CHANGELOG.md` → só `CHANGELOG.md`, só linhas adicionadas.

### Step 5: Testes

Veja a seção "Test plan" e escreva os testes. Cada teste novo **deve falhar sem a correção**. Depois de escrevê-los
(com os passos 1–3 já commitados), quebre a correção temporariamente, um ponto por vez, e confira que o teste
correspondente falha: (a) em `registry.ts`, troque o corpo de `discard` por `return false;` → testes 1 e 3 falham;
(b) em `store.ts`, comente a linha `this.opts.discard(messageId);` → testes 3 e 4 falham; (c) em `habblaud.ts`,
troque `ACK_RETRY_MS` por `[]` → testes 6 e 8 falham. Restaure cada arquivo com `git checkout -- <arquivo>` (os
testes novos ainda não commitados ficam em arquivos de teste diferentes, que o `checkout` não toca). Se um teste
não falhar, ele está errado: reescreva.

**Verify**: `npx vitest run server/messages/registry.test.ts server/asides server/test/omp-extension.test.ts` →
todos passam; `npm run typecheck` → exit 0; `npm test` → exit 0, sem testes quebrados fora dos novos.

## Test plan

**`server/messages/registry.test.ts`**, no `describe('aside e onSettled', ...)` (padrão: o teste "aside a um omp
conectado sai em inbox() ..." logo acima; `setupOmp`, `sent`, `registry.inbox`):

1. `discard retira a pergunta paralela queued: inbox() não a entrega mais, a vaga volta e get() não a conhece` —
   `registry.inbox({ session: OMP_SESSION })` (presença), `id = sent(registry.aside(OMP_A, req))`,
   `expect(registry.discard(id)).toBe(true)`, `expect(registry.inbox({ session: OMP_SESSION })).toEqual([])`,
   `expect(registry.get(id)).toBeUndefined()`. Depois encha `MAX_OPEN` (importe de `./registry`) mensagens, descarte
   uma e confira que um novo `registry.aside(...)` volta a ser aceito (a vaga de `openCount` foi liberada).
2. `discard não mexe em pergunta já buscada, em mensagem comum nem em id desconhecido` — pergunta buscada por
   `inbox()` (status `sent`) → `false` e `registry.get(id)?.status` segue `'sent'`; `registry.send({ agentId: OMP_A,
   text: 'oi' })` → `discard(id)` `false` e a mensagem ainda sai em `inbox()`; `discard('m-nao')` → `false`.
3. `cancelar pela AsideStore uma pergunta queued: a extensão nunca a recebe` (integração registro + store; importe
   `AsideStore` de `../asides/store`): monte com `let store: AsideStore | undefined;`, `setupOmp({ onSettled: (...a) =>
   store?.settled(...a) })`, depois `store = new AsideStore({ dir: null, office, queue: (id, r) =>
   registry.aside(id, r), discard: (id) => registry.discard(id) })`. `registry.inbox({ session: OMP_SESSION })`;
   `store.ask({ agentId: OMP_A, question: 'a' })` (devolve `{ topic }`; pegue `topic`) → `store.cancel(topic.id, { agentId: OMP_A })` →
   `{ ok: true }`; `registry.inbox({ session: OMP_SESSION })` → `[]`. Em seguida
   `store.ask({ agentId: OMP_A, question: 'b', topicId: topic.id })` e `registry.inbox(...)` entrega **só** a pergunta
   `b` (`aside.question === 'b'`). Esse é o cenário real do defeito (a segunda não pode ser recusada pela primeira).

**`server/asides/store.test.ts`**, junto de `it('cancel: queued vira cancelled na hora; ...')` (linha ~192; `setup`,
`topicOf`):

4. `cancel de pergunta queued retira a mensagem da fila, uma vez, e não deixa settled mexer no turno` — `ask`,
   `cancel` → `s.discarded` igual a `['m-1']`; `s.store.settled('m-1', 'delivered')` depois → o turno segue
   `cancelled` (não vira `running`); segundo `cancel` → `{ error: 'idle' }` sem nova chamada a `discard`.
5. `cancel de pergunta já entregue (running) não chama discard; o cancelamento sai pelo progress` — `ask`,
   `s.store.settled('m-1', 'delivered')`, `cancel` → `s.discarded` vazio e o próximo `progress` com `state:
   'running'` devolve `{ cancel: true }`.

**`server/test/omp-extension.test.ts`**, no `describe('omp extension: mensagens', ...)` (padrão: o teste "403 ... ou
fora do ar", que usa `vi.useFakeTimers()` e `advanceTimersByTimeAsync`; helper `inbox` e `setup`). Use uma caixa que
entrega o lote **uma vez** (senão cada rodada reentrega e o teste conta acks de mais): defina no teste
`let delivered = false;` e um `answer` que, em `/api/mod/inbox`, devolve `{ status: 200, json: { messages: delivered ?
[] : [{ id: 'm1', text: 'oi' }] } }` marcando `delivered = true`; em `/api/mod/inbox/ack`, devolve o que o teste
pedir (`'down'`, `{ status: 503, json: {} }`, `{ status: 400, json: {} }` ou `undefined` = 200):

6. `ack perdido é repetido com o mesmo corpo até chegar` — as duas primeiras chamadas a `/api/mod/inbox/ack` dão
   `'down'`, a terceira `undefined` (200). `session_start`, `await vi.advanceTimersByTimeAsync(2_000)`; esperado:
   `t.habblaud.of('/api/mod/inbox/ack')` tem 3 chamadas com `body` igual (`{ session: 'sess-1', results: [{ id: 'm1',
   ok: true }] }`); a mensagem foi entregue uma única vez (`t.sent` com 1 item); avançar mais 20 s não gera 4º ack
   (`toHaveLength(3)`). `await t.fire('session_shutdown', ctx)` no fim.
7. `ack que o servidor recusou com 4xx não é repetido` — ack responde `{ status: 400, json: {} }`;
   `advanceTimersByTimeAsync(10_000)` → exatamente 1 chamada de ack.
8. `ack que nunca chega para depois de ACK_RETRY_MS.length + 1 tentativas e a caixa de entrada segue funcionando` —
   ack sempre `{ status: 503, json: {} }`; `advanceTimersByTimeAsync(10_000)` → exatamente
   `ACK_RETRY_MS.length + 1` (= 4) chamadas (importe `ACK_RETRY_MS` do módulo da extensão) e
   `t.habblaud.of('/api/mod/inbox').length` ≥ 2 (a rodada seguinte foi agendada: o laço não travou na repetição).
9. `encerrar a sessão no meio das repetições para de repetir` — ack sempre `'down'`; depois da 1ª chamada
   (`advanceTimersByTimeAsync(10)`), `await t.fire('session_shutdown', ctx)`, `advanceTimersByTimeAsync(10_000)` →
   o número de acks não passa de 2 (a espera em curso pode concluir um pedido, nenhum outro depois).

Verificação final: `npx vitest run server/messages/registry.test.ts server/asides server/test/omp-extension.test.ts`
→ todos passam, com os 9 testes novos; `npm test` → exit 0.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; os 9 testes novos do "Test plan" existem e passam (e falham sem a correção, passo 5)
- [ ] `grep -n "inbox/ack" mod/habblaud-omp/habblaud.ts` → exatamente 1 linha
- [ ] `grep -n "discard" server/index.ts server/asides/store.ts server/messages/registry.ts` mostra o uso nos três
- [ ] `git diff --stat producao..HEAD` lista só arquivos do "In scope" (mais `plans/`); o symlink `node_modules` não
      está commitado
- [ ] `CHANGELOG.md` tem as duas entradas em `## [Não lançado]` › `### Corrigido`
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código (deriva desde `af73a23`).
- Algum teste existente fora dos listados quebra por causa da nova opção obrigatória `discard` de `AsideStoreOptions`
  (procure com `grep -rn "new AsideStore" server`): só `store.test.ts`, `http.test.ts` e `index.ts` deveriam
  construí-lo; outro lugar = ampliar o escopo, reporte.
- O teste 3 (integração) mostra que a pergunta cancelada ainda sai em `inbox()` depois do passo 2: a premissa de que
  `byMessage` guarda o id dessa mensagem está errada; reporte o que viu.
- Um passo falha na verificação duas vezes depois de uma correção razoável.
- A correção parecer exigir mexer em `ack()`/`SENT_TIMEOUT_MS` no servidor, no plugin `habblaud-mensagens` ou em
  qualquer arquivo fora do escopo.

## Maintenance notes

- **A mudança da extensão só vale depois de reinstalar**: depois do merge e do deploy, rode `npm run omp:install` (no
  host e, para as contas multiusuário, na liberação das contas/tenants) e **reabra as sessões do omp**: a extensão
  carregada numa sessão aberta continua sendo a antiga, sem a repetição do `ack`. A correção do defeito 1 é só do
  servidor e vale assim que o Habblaud reiniciar.
- `ACK_RETRY_MS` soma ≈ 4,3 s de espera + até 4×2 s de pedidos; precisa ficar bem abaixo de `SENT_TIMEOUT_MS` (30 s,
  `registry.ts`). Se um deles mudar, confira a soma.
- A repetição do `ack` segura a rodada da caixa de entrada por até ~12 s no pior caso (o próximo agendamento só sai
  no fim, de propósito: uma rodada por vez). Em compensação, nada é entregue duas vezes.
- `MessageRegistry.discard` só vale para perguntas paralelas `queued`. Se outro tipo de mensagem ganhar botão de
  cancelar, decida lá se também sai da fila (hoje mensagens e controles não têm cancelamento).
- Revisor: confira que o `byMessage.delete` em `cancel()` vem **antes** de `finishTurn` e de `discard` (senão
  `settled` poderia reabrir o turno), e que `discard` não chama `onSettled`.
- Fora deste plano: a pergunta já buscada (`sent`) e cancelada continua rodando até o primeiro `progress` da
  extensão (até `ASIDE_PROGRESS_MS`); é o comportamento atual e é barato.
