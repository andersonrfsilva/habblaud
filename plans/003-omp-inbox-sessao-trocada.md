# Plan 003: A extensão do omp nunca entrega na sessão nova o que buscou para a sessão anterior

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4931abc..HEAD -- mod/habblaud-omp/habblaud.ts server/test/omp-extension.test.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW (só muda o caminho em que a sessão trocou no meio da rodada)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `4931abc`, 2026-10-24

## Why this matters

A extensão do Habblaud no omp (`mod/habblaud-omp/habblaud.ts`) busca a cada poucos segundos as mensagens da página
para a sessão atual (`POST /api/mod/inbox`), entrega com `pi.sendUserMessage` (ou aplica uma troca de modelo,
raciocínio ou comando `/new`/`/shake`) e confirma em `POST /api/mod/inbox/ack`. Desde o commit 4931abc a extensão
segue `/new` e `/resume` na hora (evento `session_switch`), o que troca `sessionId` no meio do processo. Se a troca
acontece enquanto a busca está em andamento, a rodada entrega à sessão **nova** as mensagens da sessão **anterior**
e confirma com o id da anterior. O servidor aceita essa confirmação (`server/messages/registry.ts`, `ack()`, compara
só com a sessão que buscou) e marca "entregue". Resultado: um prompt (ou troca de modelo) cai na conversa errada e a
página mostra sucesso. O servidor já tem a regra "cada mensagem vale só para a sessão em que foi mandada"
(`ERR_SESSION_CHANGED`); a extensão precisa respeitá-la.

## Current state

`mod/habblaud-omp/habblaud.ts:707-726` — a rodada (o `session` capturado não chega a `deliver`):

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
          const results = await deliver(messages);
          await request('POST', '/api/mod/inbox/ack', { session, results });
        }
      }
    } catch {
      next = INBOX_OFFLINE_MS;
    }
    scheduleInbox(next);
  }
```

`mod/habblaud-omp/habblaud.ts:634-650` — `deliver` entrega na ordem; cada item pode esperar (`applyControl` faz
`await pi.setModel(...)`), então a sessão também pode trocar **entre** itens:

```ts
  async function deliver(messages: readonly InboxMessage[]): Promise<AckResult[]> {
    let idle = idleNow();
    const results: AckResult[] = [];
    for (const m of messages) results.push(await deliverOne(m));
    return results;

    async function deliverOne(m: InboxMessage): Promise<AckResult> {
      if (m.control) return applyControl(m.id, m.control);
      if (!m.text.trim()) return { id: m.id, ok: false, error: 'mensagem vazia' };
      ...
```

`sessionId` (`let sessionId`, linha 437) muda em `track()` (linhas ~502-520) quando chega `session_switch` ou outro
evento com id diferente, e vira `undefined` em `session_shutdown` (linha ~603).

`AckResult` (linha 220): `{ id: string; ok: boolean; error?: string }`. Erros da extensão são texto em português
direto, sem `tr()` (ex.: `'mensagem vazia'`, `'comando desconhecido'`) — siga isso.

Testes: `server/test/omp-extension.test.ts`. Helpers no topo: `fakeHabblaud(answer)` (responde por rota, de forma
síncrona, e guarda as chamadas), `setup({ home, answer, pi? })` (devolve `fire`, `sent`, `habblaud`), `makeCtx({ id })`.
Teste-modelo — `'/new (session_switch): anuncia a sessão nova na hora e a caixa de entrada passa a ser a dela'`
(linha ~215) e o primeiro teste de `describe('omp extension: mensagens')` (linha ~235):

```ts
  const inbox = (messages: unknown[]) => (c: Call) => (c.path === '/api/mod/inbox' ? { status: 200, json: { messages } } : undefined);

  it('agente livre: a primeira inicia o turno ...', async () => {
    const t = setup({ home: tmp.dir, answer: inbox([{ id: 'm1', text: 'rode os testes' }, { id: 'm2', text: 'depois commite' }]) });
    const ctx = makeCtx({ idle: true });
    t.fire('session_start', ctx);
    await vi.waitFor(() => expect(t.habblaud.of('/api/mod/inbox/ack')).toHaveLength(1));
    ...
```

## Commands you will need

| Purpose   | Command                                            | Expected on success |
|-----------|----------------------------------------------------|---------------------|
| Install   | `npm ci`                                           | exit 0              |
| Typecheck | `npm run typecheck` (inclui a extensão via `server/tsconfig.json`) | exit 0 |
| Tests     | `npx vitest run server/test/omp-extension.test.ts` | all pass            |
| All tests | `npm test`                                         | all pass            |

## Scope

**In scope**:
- `mod/habblaud-omp/habblaud.ts` (só `inboxRound` e `deliver`)
- `server/test/omp-extension.test.ts`
- `CHANGELOG.md` (uma linha em "Não lançado")

**Out of scope**:
- `server/messages/registry.ts` — o servidor já está certo; não mudar o contrato do ack.
- `mod/habblaud-mensagens` (plugin do Claude Code) — não troca de sessão no mesmo processo.
- Qualquer outra parte da extensão (permissões, ask, batimento).

## Git workflow

- Branch: `fix/omp-inbox-sessao-trocada` a partir de `main`.
- Um commit, estilo do repo: `omp: mensagem buscada para uma sessão nunca vai para a sessão que entrou no lugar dela`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Teste que reproduz a corrida (deve FALHAR antes da correção)

Em `describe('omp extension: mensagens')`, adicione:

```ts
  it('sessão trocada durante a busca: nada é entregue à nova e o ack da anterior diz que a conversa mudou', async () => {
    let t: ReturnType<typeof setup> | undefined;
    const t0 = setup({
      home: tmp.dir,
      answer: (c) => {
        if (c.path !== '/api/mod/inbox' || c.body?.session !== 'a') return undefined;
        // /new chega enquanto a resposta da busca da sessão "a" está a caminho.
        t?.fire('session_switch', makeCtx({ id: 'b' }), { reason: 'new' });
        return { status: 200, json: { messages: [{ id: 'm1', text: 'oi' }, { id: 'k1', text: '', control: { model: 'x/y' } }] } };
      },
    });
    t = t0;
    t0.fire('session_start', makeCtx({ id: 'a' }));
    await vi.waitFor(() => expect(t0.habblaud.of('/api/mod/inbox/ack')).toHaveLength(1));
    expect(t0.sent).toEqual([]);
    const ack = t0.habblaud.of('/api/mod/inbox/ack')[0].body;
    expect(ack?.session).toBe('a');
    expect(ack?.results).toEqual([
      { id: 'm1', ok: false, error: SESSION_CHANGED },
      { id: 'k1', ok: false, error: SESSION_CHANGED },
    ]);
    await t0.fire('session_shutdown', makeCtx({ id: 'b' }));
  });
```

Defina no topo do `describe` (ou use o valor literal) `const SESSION_CHANGED = 'a conversa mudou (/new ou /resume) antes da entrega';`
— é o mesmo texto do Step 2.

**Verify**: `npx vitest run server/test/omp-extension.test.ts -t "sessão trocada durante a busca"` → **falha**
(`t0.sent` contém `{ text: 'oi', ... }`). Se passar antes da correção, STOP (a premissa está errada).

### Step 2: Corrigir `deliver` e `inboxRound`

1. Logo acima de `deliver`, crie a constante:
   ```ts
   /** A conversa trocou entre a busca e a entrega: cada mensagem vale só para a sessão em que foi mandada. */
   const SESSION_CHANGED = 'a conversa mudou (/new ou /resume) antes da entrega';
   ```
2. Mude `deliver` para receber a sessão da rodada e conferir antes de cada item:
   ```ts
   async function deliver(session: string, messages: readonly InboxMessage[]): Promise<AckResult[]> {
     let idle = idleNow();
     const results: AckResult[] = [];
     for (const m of messages) results.push(sessionId === session ? await deliverOne(m) : { id: m.id, ok: false, error: SESSION_CHANGED });
     return results;
     ...
   ```
   (o corpo de `deliverOne` não muda).
3. Em `inboxRound`, troque `await deliver(messages)` por `await deliver(session, messages)`. O ack continua com o
   `session` da rodada (é o que o servidor espera).
4. Atualize o JSDoc de `deliver` com uma frase: item de uma sessão que já não é a atual vira falha, sem entrega.
5. Confirme que não há outro chamador: `grep -n "deliver(" mod/habblaud-omp/habblaud.ts` → só a definição e a
   chamada em `inboxRound` (mais `deliverOne`).

**Verify**: `npx vitest run server/test/omp-extension.test.ts` → all pass, incluindo o teste novo.

### Step 3: Typecheck, suíte, CHANGELOG

Adicione em "Não lançado" do `CHANGELOG.md`, no bloco do omp se houver, algo como:
"omp: mensagem ou troca de modelo mandada pela página logo antes de um /new ou /resume não cai na conversa nova;
ela aparece como não entregue (a conversa mudou)."

**Verify**: `npm run typecheck` → exit 0; `npm test` → all pass.

## Test plan

- Novo teste do Step 1 (falha antes, passa depois): troca durante a busca → nada em `sent`, ack da sessão anterior com
  as duas falhas (texto e controle).
- Os testes existentes de mensagens (entrega normal, controle de modelo, comando, ocupado, 403/fora do ar) continuam
  passando sem mudança — provam que o caminho comum não mudou.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; o teste "sessão trocada durante a busca" existe e passa
- [ ] `grep -n "await deliver(session, messages)" mod/habblaud-omp/habblaud.ts` → 1 linha
- [ ] `git status` mostra só os 3 arquivos do escopo (mais `plans/README.md`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- `inboxRound` ou `deliver` não batem com os trechos acima.
- O teste do Step 1 passa antes da correção.
- A correção parece exigir mudar `server/messages/registry.ts` ou o formato do ack.
- `fire('session_switch', ...)` dentro do `answer` não troca a sessão (por exemplo, `track()` mudou): reporte em vez de
  reescrever o helper de teste.

## Maintenance notes

- Qualquer novo `await` dentro de `deliverOne` reabre a janela entre itens; a checagem por item em `deliver` cobre isso.
- Depois de instalar, a extensão em uso é a cópia carregada pelo omp: rode `npm run omp:install` e reabra as sessões
  omp para valer (confirme com `npm run omp:status`).
- Revisar: o servidor também falha a mensagem por conta própria quando a conversa muda (`ERR_SESSION_CHANGED`); o ack
  com `ok:false` só corrige o status mais cedo e com o motivo certo.
