# Plan 018: As teclas e o redimensionamento do terminal interativo sempre vão para o pty em que foram feitos

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- client/src/ui/ptypane.ts client/src/ui/ptyinput.ts client/src/ui/ptyinput.test.ts client/src/ui/pty.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: basta a seção
> `## [Não lançado]` › `### Corrigido` existir.)

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `af73a23` (`producao`), 2026-10-25

## Why this matters

O terminal interativo do Habblaud é uma única tela (`PtyPane`, xterm.js) que o painel religa a outro pty quando o
usuário troca de agente. A fila de teclas dela é compartilhada entre os ptys: se o usuário digita logo depois de
trocar, um envio que ficou na fila *do pty antigo* esvazia o texto digitado para o pty *novo* e o posta no antigo.
Resultado: o que era para o Claude Code de um agente (comandos, respostas a pedidos de permissão, até Enter) cai no
terminal de **outro agente**, que pode estar rodando algo diferente, em outra pasta. O mesmo vale para o
redimensionamento agendado: o temporizador de 120 ms não é cancelado ao trocar de pty e acaba mandando o tamanho para
o pty novo. Este plano amarra a fila de teclas e o temporizador a cada ligação, mantendo a ordem das teclas dentro de
um mesmo pty.

## Current state

Arquivos:

- `client/src/ui/ptypane.ts` — a tela do terminal (xterm.js) ligada a um pty: saída por SSE, teclas e tamanho por POST.
  Carregado sob demanda (import dinâmico) porque o xterm só carrega no navegador.
- `client/src/ui/pty.ts` — `PtyPanel`, o painel que cria a `PtyPane` e chama `attach`/`detach` (linhas 125, 135, 220).
- `client/src/ui/ptyapi.ts` — `postPty(url, body)`: POST JSON; lança `Error` se a rede ou o servidor falhar.
- `CHANGELOG.md` — entrada em `## [Não lançado]` › `### Corrigido` (`## [Não lançado]` está na linha 11 e `### Corrigido`
  na linha 84; a lista termina logo antes de `## [1.0.3] - 2026-10-16`, linha 117).

`client/src/ui/ptypane.ts:16-22` (estado compartilhado entre ligações):

```ts
  private es: EventSource | null = null;
  private id: string | null = null;
  private exited = false;
  /** Entrada ainda não enviada e a fila de envio (mantém a ordem das teclas). */
  private pending = '';
  private sending: Promise<void> = Promise.resolve();
  private resizeTimer: ReturnType<typeof setTimeout> | null = null;
```

`client/src/ui/ptypane.ts:48-54` e `77-81` (`attach` chama `detach`, que não limpa fila nem temporizador):

```ts
  /** Liga a tela a um pty do servidor (outro pty = tela nova). */
  attach(id: string): void {
    if (this.id === id && this.es) return;
    this.detach();
    this.id = id;
    this.exited = false;
    this.term.reset();
```

```ts
  detach(): void {
    this.es?.close();
    this.es = null;
    this.id = null;
  }
```

`client/src/ui/ptypane.ts:99-117` (o defeito):

```ts
  private send(data: string): void {
    if (!this.id || this.exited) return;
    this.pending += data;
    const id = this.id;
    this.sending = this.sending.then(async () => {
      if (!this.pending) return;
      const chunk = this.pending;
      this.pending = '';
      await postPty(`/api/pty/${encodeURIComponent(id)}/input`, { data: chunk }).catch(() => undefined);
    });
  }

  private scheduleResize(cols: number, rows: number): void {
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeTimer = setTimeout(() => {
      this.resizeTimer = null;
      if (this.id && !this.exited) void postPty(`/api/pty/${encodeURIComponent(this.id)}/resize`, { cols, rows }).catch(() => undefined);
    }, RESIZE_DEBOUNCE_MS);
  }
```

Como a corrida acontece (ordem real):

1. Pty A ligado; o usuário digita `a`: `pending='a'`, `sending` encadeia o fechamento F1 (capturou `id=A`); F1 roda,
   esvazia `pending` e fica esperando o POST de A (ainda em andamento).
2. O usuário digita `b` (ainda em A): `pending='b'`, encadeia F2 (`id=A`), que só roda quando F1 terminar.
3. O usuário troca de agente: `PtyPanel.show()` chama `pane.attach(B)` (`pty.ts:218-220`); `detach()` só fecha o
   EventSource e zera `id`.
4. O usuário digita `c` (agora em B): `pending='bc'`, encadeia F3 (`id=B`).
5. F1 termina; F2 roda: esvazia `pending` (`'bc'`) e **posta `bc` para A**. F3 encontra `pending` vazio e não faz nada.
   O `c` digitado no pty B foi para o pty A.

Confirmação de que a religação acontece na prática: `PtyPanel.show(p)` (`pty.ts:212-227`) é chamado ao abrir o
terminal de outro agente (`openAgent`, `pty.ts:122-123`) e `pane.attach(p.id)` troca de pty na **mesma** `PtyPane`
(`this.pane` é criada uma vez em `ensurePane`, `pty.ts:229-236`). `openAgent` para um agente sem pty próprio e
`close()` chamam `pane?.detach()` (`pty.ts:125`, `135`).

Convenções:

- Os testes do cliente rodam no Vitest com `environment: 'node'` (`vitest.config.ts`): não há DOM e o xterm não carrega.
  Por isso a lógica da fila vai para um módulo puro, sem import de xterm, e o teste não toca `PtyPane`. Estilo dos
  testes: `client/src/ui/deliveries.test.ts` (`describe`/`it` do `vitest`, textos em português, sem mocks de módulo).
- Comentários no código em português, explicando o porquê. Mensagens de commit em português, minúsculas (ex.:
  `git log --oneline` → "plans: 015 em produção; índice atualizado").
- Nenhum texto passado a `tr()` muda neste plano, então `i18n:check` não é necessário (rode mesmo assim no fim).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Teste novo | `npx vitest run client/src/ui/ptyinput.test.ts` | todos passam |
| Suíte | `npm test` | exit 0 (linha de base em `af73a23`: 181 arquivos / 2180 testes, 2 ignorados) |
| i18n | `npm run i18n:check -- --strict` | exit 0 |

## Scope

**In scope** (os únicos arquivos a modificar):

- `client/src/ui/ptyinput.ts` (criar) — fila de entrada de **um** pty, pura.
- `client/src/ui/ptyinput.test.ts` (criar)
- `client/src/ui/ptypane.ts`
- `CHANGELOG.md`
- `plans/README.md` (só a linha de status deste plano, se não houver revisor mantendo o índice)

**Out of scope** (NÃO tocar):

- `client/src/ui/pty.ts`, `client/src/ui/ptyapi.ts` — o painel e a API estão corretos; o defeito é só da `PtyPane`.
- `server/pty/*`, `server/http/pty.ts` — o servidor trata `input`/`resize` por id; nada a mudar.
- Qualquer mudança em `tr('...')` ou em `shared/locales/*`.
- Descartar teclas pendentes ao trocar de pty: o que foi digitado no pty A **deve** ainda chegar ao A (ver passo 2).

## Git workflow

- Worktree: `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-018 -b advisor/018-terminal-teclas-no-pty-certo producao` e
  `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-018/node_modules` (nunca commitar o symlink).
  Trabalhe dentro de `/tmp/hb-018`.
- Um commit por passo lógico (sugestão: 1 para `ptyinput` + teste, 1 para a `PtyPane` + changelog); mensagem em
  português, minúscula, ex.: `terminal: fila de teclas por pty (teclas não vazam para o pty anterior)`.
- NÃO fazer push, NÃO abrir PR, NÃO tocar em `~/apps/habblaud` (produção).

## Steps

### Step 1: Criar a fila de entrada de um pty (módulo puro)

Crie `client/src/ui/ptyinput.ts` com este conteúdo (cabeçalho de comentário em português, como os outros arquivos do
diretório):

```ts
// Fila de teclas de UM pty: junta o que é digitado enquanto um POST está em andamento e envia em ordem. Cada ligação
// da tela (PtyPane.attach) cria a sua; assim, o que foi digitado num pty nunca é esvaziado por um envio de outro.
// Puro (sem DOM nem xterm): testado em ptyinput.test.ts.

export type PostInput = (id: string, data: string) => Promise<unknown>;

export class PtyInput {
  private pending = '';
  private sending: Promise<void> = Promise.resolve();

  constructor(
    readonly id: string,
    private post: PostInput,
  ) {}

  /** Enfileira teclas; os envios saem um por vez, na ordem, e só com o texto deste pty. */
  push(data: string): void {
    if (!data) return;
    this.pending += data;
    this.sending = this.sending.then(async () => {
      if (!this.pending) return;
      const chunk = this.pending;
      this.pending = '';
      // Falha de rede não pode travar a fila: a próxima tecla tenta de novo.
      await this.post(this.id, chunk).catch(() => undefined);
    });
  }
}
```

**Verify**: `npm run typecheck` → exit 0.

### Step 2: Testes da fila (escreva antes de ligar na `PtyPane`)

Crie `client/src/ui/ptyinput.test.ts` (estilo de `client/src/ui/deliveries.test.ts`). Use um `post` falso que registra
`[id, data]` e devolve promessas que o teste resolve à mão (um array de `resolve`). Helper sugerido:
`const tick = () => new Promise((r) => setTimeout(r, 0));` para deixar as micro-tarefas rodarem. Casos:

1. **Ordem e junção dentro de um pty:** `push('a')`, `tick()` (o POST de `a` fica em andamento), `push('b')`, `push('c')`;
   resolve o primeiro POST; `tick()`; resolve o segundo → `calls` é `[['A','a'], ['A','bc']]` (teclas juntas, na ordem).
2. **Regressão do bug (dois ptys, um POST em andamento):** `const a = new PtyInput('A', post)`; `a.push('a')`, `tick()`;
   `a.push('b')` (fica na fila de A); cria `const b = new PtyInput('B', post)`; `b.push('c')`. Resolve todos os POSTs
   pendentes até a fila esvaziar (laço: enquanto houver `resolve` pendente, resolva e `tick()`). Esperado: a chamada
   com `'c'` tem id `'B'`, e **nenhuma** chamada com id `'A'` contém `'c'`; as de A somam `'ab'` na ordem.
3. **Falha não trava a fila:** `post` rejeita no primeiro envio; `push('a')`, `tick()`, `push('b')`, `tick()` → o segundo
   envio (`'b'`) acontece com o id certo e a promessa de `push` não gera "unhandled rejection".
4. **Entrada vazia:** `push('')` não chama `post`.

**Verify**: `npx vitest run client/src/ui/ptyinput.test.ts` → 4 testes passam. Prova de que o teste 2 pega o bug:
troque temporariamente, no teste, as duas instâncias por uma só `PtyInput('A', post)` chamando `push('c')` depois de
`push('b')` e confira que a asserção "nenhuma chamada de A contém `c`" falha; desfaça a troca antes de seguir.

### Step 3: Usar a fila por ligação na `PtyPane` e cancelar o redimensionamento no `detach`

Em `client/src/ui/ptypane.ts`:

a) Adicione `import { PtyInput } from './ptyinput';` junto dos outros imports (depois da linha 7, `import { postPty } ...`).

b) Substitua as linhas 19-21 (`pending`, `sending`) por um único campo e mantenha `resizeTimer`:

```ts
  /** Fila de teclas da ligação atual; cada `attach` cria a sua (ver ptyinput.ts). */
  private input: PtyInput | null = null;
  private resizeTimer: ReturnType<typeof setTimeout> | null = null;
```

c) Em `attach`, logo depois de `this.id = id;` (linha 52) acrescente:

```ts
    this.input = new PtyInput(id, (ptyId, data) => postPty(`/api/pty/${encodeURIComponent(ptyId)}/input`, { data }));
```

d) Em `detach`, depois de `this.id = null;`, acrescente (a fila antiga continua viva só dentro do próprio
   `PtyInput`: o que já foi digitado ainda chega ao pty de origem, e nada novo entra nela):

```ts
    this.input = null;
    // Um redimensionamento agendado para o pty que saiu não pode ser enviado ao novo.
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeTimer = null;
```

e) Reescreva `send` (linhas 99-109):

```ts
  private send(data: string): void {
    if (!this.input || this.exited) return;
    this.input.push(data);
  }
```

Não altere `scheduleResize`: depois do `detach` o temporizador já foi cancelado, e o `refit(true)` do evento `reset`
do pty novo agenda o tamanho certo para ele. Não altere `refit`, `focus` nem o construtor.

**Verify**: `npm run typecheck` → exit 0; `grep -n "pending\|this.sending" client/src/ui/ptypane.ts` → sem resultados.

### Step 4: Changelog

Em `CHANGELOG.md`, no fim da lista de `### Corrigido` da seção `## [Não lançado]` (antes de `## [1.0.3]`), acrescente
(texto em português, no tom das outras entradas, quebrado em ~120 colunas):

```markdown
- **Terminal interativo: teclas no terminal errado:** ao trocar de agente logo depois de digitar, as teclas do terminal
  novo podiam ser enviadas ao terminal do agente anterior (e o tamanho da tela também). Agora a fila de teclas e o
  ajuste de tamanho pertencem a cada terminal, e a ordem das teclas dentro de um terminal continua a mesma.
```

**Verify**: `git diff --stat -- CHANGELOG.md` → 1 arquivo, só linhas adicionadas; `npm run i18n:check -- --strict` → exit 0.

### Step 5: Suíte completa

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0, 182 arquivos, 2184 testes (2180 + 4 novos), 2 ignorados.

### Step 6: Verificação manual (o `PtyPane` real não roda em teste automatizado)

Suba o Habblaud de desenvolvimento a partir do worktree (nunca o de produção; veja o `README` do repositório para o
comando de dev) com dois agentes do Claude Code abertos em terminais interativos (A e B). Cenário:

1. Abra o terminal do agente A. Digite `echo AAAA` **sem** apertar Enter e, no mesmo instante, clique no cartão do
   agente B (troca de terminal).
2. No terminal de B digite `echo BBBB` e Enter.
3. Esperado: em A aparece no máximo `echo AAAA` (sem `BBBB`); em B aparece `echo BBBB`. Antes da correção, `BBBB`
   podia aparecer no A.
4. Redimensione a janela do navegador e troque de terminal em menos de 120 ms: o terminal novo continua com o tamanho
   dele (sem erro no console do navegador).

Se não houver como subir dois agentes, registre isso na resposta final em vez de pular em silêncio; os testes do
passo 2 cobrem a lógica.

## Test plan

- Arquivo novo: `client/src/ui/ptyinput.test.ts`, 4 casos descritos no passo 2 (ordem/junção; regressão com dois ptys e
  POST em andamento; falha de rede não trava a fila; entrada vazia). Modelo: `client/src/ui/deliveries.test.ts`.
- O caso 2 reproduz a corrida do bug com o `post` controlado à mão: sem a fila por ligação (uma fila compartilhada), ele
  falha.
- `PtyPane.attach/detach` e o temporizador de resize não têm teste automatizado (precisam de DOM + xterm); ficam
  cobertos pela verificação manual do passo 6.
- Verificação: `npx vitest run client/src/ui/ptyinput.test.ts` → 4 passam; `npm test` → tudo verde.

## Done criteria

ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; `client/src/ui/ptyinput.test.ts` existe e seus 4 testes passam
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `grep -n "pending\|this.sending" client/src/ui/ptypane.ts` não retorna nada
- [ ] `grep -n "this.input = null" client/src/ui/ptypane.ts` retorna 1 linha (dentro de `detach`) e `grep -n "clearTimeout" client/src/ui/ptypane.ts` retorna 2 linhas (`detach` e `scheduleResize`)
- [ ] `CHANGELOG.md` tem a entrada nova em `## [Não lançado]` › `### Corrigido`
- [ ] `git status` só mostra arquivos da lista "In scope" (o symlink de `node_modules` não é commitado)
- [ ] `plans/README.md` com a linha deste plano atualizada (se aplicável)

## STOP conditions

Stop and report back (do not improvise) if:

- O código em `ptypane.ts` não bate com os trechos de "Current state" (por exemplo, `send`/`detach` já mudaram).
- Outro lugar além de `PtyPane` lê `pending` ou `sending` (`grep -rn "\.pending\b" client/src/ui/pty*.ts`); este plano
  assume que são privados da `PtyPane`.
- Fazer o passo 3 exigir mexer em `pty.ts` ou `ptyapi.ts`.
- O teste 2 do passo 2 passa mesmo com uma fila compartilhada (então ele não prova a correção; reescreva-o antes de
  seguir, e se não conseguir em duas tentativas, pare).
- `npm test` falha duas vezes em arquivos que não são os seus.

## Maintenance notes

- Quem adicionar outro envio por pty (ex.: colar texto, enviar sinais) deve passar por `PtyInput`, não por
  `postPty` direto, para manter a ordem e o id da ligação.
- Revisor: confira que `detach()` não descarta teclas já digitadas (a fila antiga termina sozinha, enviando só ao
  próprio id) e que `attach()` do mesmo id (`this.id === id && this.es`) continua sem recriar a fila.
- Pendência deliberada: se o pty de origem já saiu, o POST falha em silêncio (`catch(() => undefined)`), como antes.
  Mostrar erro ao usuário não faz parte deste plano.
