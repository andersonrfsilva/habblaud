# Plan 019: Trocar o título de uma sessão do omp não duplica o histórico do agente (e, se medido, não relê o transcript inteiro)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Este é um plano de INVESTIGAÇÃO + correção condicional.** A Parte A (dedupe do histórico) é sempre feita. A Parte B
> (releitura só do cabeçalho) só é feita se a medição do Passo 2 passar do limite do "portão de decisão" do Passo 3. Os
> números de quem escreveu o plano apontam para **só a Parte A**; confirme com a sua própria medição.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- server/model/office.ts server/model/model.test.ts server/sources/omp/source.ts server/sources/omp/source.test.ts server/sources/tail.ts server/sources/tail.test.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: basta o `## [Não lançado]` › `### Corrigido`
> existir.)

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: LOW (Parte A) / MED (Parte B, só se o portão mandar)
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `af73a23` (branch `producao`), 2026-10-25

## Why this matters

O omp reescreve a linha 1 do transcript (o "slot" do título, 256 bytes de largura fixa) no lugar sempre que o título da
sessão muda. O `FileTail` vê o começo do arquivo mudar e manda reler tudo (`reset`); o `OmpSource` então refaz o `load()`
do arquivo inteiro e reenvia ao escritório até 400 atividades do backlog. O `Office.addActivity` só descarta repetidas
olhando as últimas 30 (`recent`), então o **histórico do agente** (`GET /api/agents/:id`, 200 entradas) recebe cópias de
tudo: numa sessão real de 95 atividades, o histórico foi de 95 para 190 e depois para 200 entradas, só 95 únicas. O
cliente descarta repetidas por id, então o que o usuário vê é uma linha do tempo mais curta do que deveria (as 200
vagas se enchem de cópias). Quanto custa a releitura em si foi **medido**: 108–117 ms no pior transcript real (45,6 MB),
2–6 ms nos de tamanho comum, então a parada do laço de eventos é pequena. Este plano corrige a duplicação (Parte A) e deixa
a releitura mais barata (Parte B) apenas se o seu próprio número disser que vale.

## Current state

Arquivos:

- `server/sources/tail.ts` — `FileTail`: leitura incremental de JSONL; decide quando o arquivo "foi trocado" (`reset`).
- `server/sources/tail.test.ts` — testes dele (Vitest, grupo "reescrita no mesmo inode").
- `server/sources/omp/source.ts` — `OmpSource`: `load()` (carga inteira de um transcript) e `readNew()` (leitura do que cresceu).
- `server/sources/omp/source.test.ts` — teste de ponta a ponta da fonte (agent dir temporária + `Office` de verdade).
- `server/model/office.ts` — `Office.addActivity` (onde as atividades entram no `recent` e no histórico).
- `server/model/model.test.ts` — testes do `Office` (`makeOffice`, `act`).
- `CHANGELOG.md`.

### O mecanismo (lido no commit `af73a23`)

`server/sources/tail.ts:200-207` — o que conta como "arquivo trocado":

```ts
  private replaced(fd: number, st: { ino: number; birthtimeMs: number; mtimeMs: number }): boolean {
    if (this.ino === undefined) return false;
    if (st.ino !== this.ino) return true;
    const born = this.birthtimeMs > 0 && st.birthtimeMs > 0 && st.birthtimeMs !== this.birthtimeMs;
    const written = st.mtimeMs !== this.mtimeMs;
    if (!born && !written) return false;
    return !!this.head && !readHead(fd, this.head.length).equals(this.head);
  }
```

`this.head` são os primeiros `HEAD_BYTES = 256` bytes do arquivo (`tail.ts:19`). **Nos transcripts reais do omp a linha 1
(slot do título) tem exatamente 256 bytes com o `\n`** (medido em 73 arquivos recentes: todos 256), então o `head`
guardado é o slot inteiro e nada mais. Reescrever o slot muda o `head` → `replaced()` verdadeiro.

`server/sources/tail.ts:220-225` (em `read()`): o `reset` zera o offset e devolve o primeiro pedaço (até 8 MiB) já lido:

```ts
      if (this.replaced(fd, st) || st.size < this.offset) {
        this.offset = 0;
        this.pending.reset();
        this.head = null;
        reset = true;
      }
```

`server/sources/omp/source.ts:613-640` (`readNew`) — o `reset` joga fora as linhas que acabou de ler e chama `load()`:

```ts
      t.lastWriteAt = tail.mtimeMs;
      if (r.reset) {
        // O slot do título (linha 1) foi reescrito, ou o arquivo foi trocado: relê como histórico.
        this.load(t, tail.path);
        return;
      }
```

`server/sources/omp/source.ts:587-611` (`load`) — relê o arquivo inteiro, de forma síncrona, e reenvia o backlog:

```ts
  private load(t: Tracker, path: string): void {
    const head = readOmpHead(path);
    if (!head.header) return;
    t.head = head;
    t.path = path;
    t.state = createOmpState(head);
    t.backlog = [];
    const tail = new FileTail(path);
    tail.offset = head.bytes;
    t.tail = tail;
    for (let i = 0; i < READ_ROUNDS; i++) {
      const r = tail.read();
      if (r.missing) break;
      for (const line of r.lines) this.apply(t, parseOmpLine(t.state, line, { idPrefix: t.key, now: this.now() }), false);
      if (!r.more) break;
    }
    t.lastWriteAt = tail.mtimeMs;
    this.settleStatus(t);
    if (t.inOffice) {
      this.flushBacklog(t);
      this.statusToOffice(t);
    }
    this.applySummary(t);
  }
```

Constantes (`source.ts:56` e `58`): `BACKLOG_MAX = 400` e `READ_ROUNDS = 64`. `flushBacklog` (`source.ts:671-675`) chama
`office.addActivity(..., { feed: false })` para cada resultado do backlog.

`server/model/office.ts:45` `RECENT_LIMIT = 30`, `:52` `HISTORY_LIMIT = 200`. `server/model/office.ts:728-733` — **o defeito**:

```ts
    // Releitura de um transcript regravado: não duplica o que já está no histórico.
    if (info.recent.some((a) => a.id === activity.id)) return;
    if (activity.kind !== 'done') delete rec.synthDone;
    info.recent = [...info.recent, activity].slice(-RECENT_LIMIT);
    rec.history.push(activity);
    if (rec.history.length > HISTORY_LIMIT) rec.history.splice(0, rec.history.length - HISTORY_LIMIT);
```

O comentário diz "histórico", mas a checagem olha só `recent` (30 entradas). Na releitura, as atividades entram da mais
velha para a mais nova; cada uma que não está em `recent` é empurrada para `recent` e expulsa as mais novas, que então
também deixam de ser reconhecidas. Resultado: com N atividades, 30 < N < 200, **todas** voltam a entrar em
`rec.history`. Com N ≤ 30 o dedupe funciona; com N ≥ 200 o corte de `HISTORY_LIMIT` esconde o problema.

Os ids são determinísticos (`server/sources/omp/parser.ts:326`: `${idPrefix}#${key}`, derivado do id da linha), por isso
o dedupe por id é seguro: um arquivo realmente substituído tem ids novos e entra normalmente.

### O que foi medido (quem escreveu o plano, nesta máquina, no commit `af73a23`)

Script descartável (o do Passo 2) sobre uma **cópia** do transcript: carga inicial, depois 3 reescritas do slot na mesma
largura, com `poll()` após cada uma.

| Transcript | Tamanho | Linhas | Carga inicial | `poll` após reescrever o slot (3 vezes) | Histórico depois |
|---|---|---|---|---|---|
| maior de todos (`-Desenvolvimento/2026-10-24T23-22-33-571Z_01a17039-…`) | 45,6 MB | 999 | 151 ms | 115 / 117 / 108 ms | 200 únicos de 200 (já saturado, não aparece) |
| 2º maior sessão principal (`-Desenvolvimento-Livros/2026-09-21T23-10-13-939Z_…`) | 7,1 MB | 2096 | 86 ms | 76 / 63 / 65 ms | 200 de 200 (saturado) |
| sessão com 95 atividades (`-Desenvolvimento/2026-10-17T10-35-37-591Z_01a1496e-…`) | 0,2 MB | — | 19 ms | 6,2 / 6,1 / 5,0 ms | **190 → 200 entradas, só 95 únicas** |
| sessões com 14 e 23 atividades | 0,1–0,2 MB | — | 14 ms | 1–2 ms | 14 e 23 únicas (dedupe funciona, ≤ 30) |
| `poll` sem mudança (referência) | — | — | — | 0,1 ms | — |

Conclusões: (1) a duplicação é **real** para toda sessão entre 31 e 199 atividades (a maioria); (2) a parada do laço
de eventos pelo `load()` é de ~110 ms no pior transcript que existe e de poucos ms nos comuns; a extensão do omp tem
timeout de 2 s, então hoje há folga de ~17×.

### Fato que vale para a Parte B (medido nos dados reais)

Em 449 sessões de `~/.omp/agent/sessions`, 387 têm título no slot. Em **todas as 387**, o título do slot é igual ao do
último `title_change` do próprio arquivo. O omp reescreve o slot **e** acrescenta uma linha `title_change` ao final
(`parser.ts:364-371` já trata essa linha e vence o slot: "title_change > slot da linha 1"). Ou seja, reler o arquivo
por causa do slot não traz informação nenhuma: o título novo chega pela linha acrescentada, que o caminho normal
(`readNew`) já lê.

### Convenções

- Comentários em português explicando o porquê; testes no estilo `it('frase em português', ...)`.
- Teste do `Office`: `server/model/model.test.ts:203-217` (`makeOffice`, `act`) e o teste "feed traz a conta do agente…"
  (`:241-260`) como molde.
- Teste da fonte do omp: `server/sources/omp/source.test.ts:191-209` ("título do slot mudou no lugar…") como molde;
  helpers `setup`, `L.*`, `c.session`, `c.append`, `c.poll` no começo do arquivo.
- Teste do `FileTail`: `server/sources/tail.test.ts:77-124` (grupo "reescrita no mesmo inode", com `tick()`) como molde.
- Mudanças visíveis no `CHANGELOG.md` em `## [Não lançado]`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes (tudo) | `npm test` | exit 0; baseline em `af73a23`: 181 arquivos / 2180 testes passam, 2 ignorados |
| Só o Office | `npx vitest run server/model/model.test.ts` | todos passam |
| Só a fonte do omp | `npx vitest run server/sources/omp/source.test.ts` | todos passam |
| Só o tail | `npx vitest run server/sources/tail.test.ts` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0 (só precisa se algum `tr('…')` mudar; este plano não muda) |
| Maiores transcripts | `find ~/.omp/agent/sessions -name '*.jsonl' -printf '%s %p\n' \| sort -rn \| head -5` | 5 linhas "bytes caminho" |
| Maiores sessões **principais** | `find ~/.omp/agent/sessions -maxdepth 2 -name '*.jsonl' -printf '%s %p\n' \| sort -rn \| head -5` | idem, sem os `__advisor.jsonl`/subagentes |

## Scope

**In scope** (os únicos arquivos que você deve modificar):

- `server/model/office.ts` (Parte A)
- `server/model/model.test.ts` (Parte A)
- `server/sources/omp/source.test.ts` (Parte A; e Parte B se executada)
- `CHANGELOG.md`
- Só se o portão do Passo 3 mandar a Parte B: `server/sources/tail.ts`, `server/sources/tail.test.ts`,
  `server/sources/omp/source.ts`.
- `/tmp/hb019/medir.mts` (script descartável, **fora do repositório**; nunca faça commit dele).

**Out of scope** (do NOT touch):

- `server/sources/codex/*`, `server/sources/transcript.ts` e qualquer outra fonte (Claude Code, Codex): o dedupe vale
  para todas pelo `Office`, mas elas não são o foco e a Parte B é só do omp.
- `READ_ROUNDS`/`BACKLOG_MAX`/`HISTORY_LIMIT`/`RECENT_LIMIT`: não mude os limites.
- Tornar o `load()` assíncrono ou em pedaços: é outra decisão (muda a ordem de entrada no escritório).
- `~/apps/habblaud` (produção): nunca.
- O cliente (`client/`): ele já descarta repetidas por id; não é o defeito.

## Git workflow

- Worktree (recomendado): `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-019 -b advisor/019-titulo-releitura producao` e
  depois `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-019/node_modules` (**nunca** faça commit do symlink; confira com `git status`).
- Branch: `advisor/019-titulo-releitura`, a partir de `producao`.
- Um commit por passo lógico, mensagem em português, minúscula, no estilo de `git log --oneline -15`
  (ex.: `c9f102b lógica pura das camadas de móveis e props, com testes`). Exemplos: `histórico do agente não duplica na releitura do transcript`,
  `changelog: histórico do omp sem cópias ao trocar o título`.
- Não faça push nem abra PR.

## Steps

### Step 1: Ambiente e linha de base

Na worktree, rode o drift check do cabeçalho (sem saída = sem mudanças), depois `npm run typecheck` e `npm test`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0 (181 arquivos / 2180 testes, 2 ignorados; se o número de testes
for diferente porque a `producao` andou, o que vale é "tudo passa").

### Step 2: Medir (investigação, sem alterar o repositório)

1. Ache os maiores transcripts **principais** (só leitura): `find ~/.omp/agent/sessions -maxdepth 2 -name '*.jsonl' -printf '%s %p\n' | sort -rn | head -5`.
   Se a máquina do executor não tiver sessões reais do omp (o comando não imprime nada), vá para o Passo 3 com o
   resultado "sem medição" → faça só a Parte A e registre isso no relatório.
2. Crie `/tmp/hb019/medir.mts` com o conteúdo abaixo (fora do repositório). Ele **só lê** o transcript: trabalha numa
   cópia numa pasta temporária, sobe a fonte do omp de verdade (`OmpSource` + `Office`), reescreve a linha 1 no lugar
   três vezes (mesma largura, como o omp faz) e mede cada `poll()`. Com `STRIP_EXIT=1` a cópia perde as linhas
   `session_exit` (sessões retomadas têm várias e, com elas, o agente nem entra no escritório).

```ts
// Plano 019: mede a releitura (load) quando o omp reescreve o slot do título no lugar. Descartável, fora do repositório.
// Uso, na raiz do repositório: [STRIP_EXIT=1] npx tsx /tmp/hb019/medir.mts <transcript.jsonl>
import { closeSync, copyFileSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, rmSync, statSync, utimesSync, writeFileSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { performance } from 'node:perf_hooks';

const R = process.cwd();
const { AccountsService } = await import(`${R}/server/accounts/service`);
const { setQuiet } = await import(`${R}/server/log`);
const { NameStore } = await import(`${R}/server/model/names`);
const { Office } = await import(`${R}/server/model/office`);
const { OmpSource } = await import(`${R}/server/sources/omp/source`);

setQuiet(true);
const src = process.argv[2];
if (!src) throw new Error('passe o caminho do transcript (só é lido; a medição usa uma cópia)');
const tmpDir = mkdtempSync(join(tmpdir(), 'hb019-'));
const dir = join(tmpDir, '.omp', 'agent');
mkdirSync(join(dir, 'sessions', '-proj'), { recursive: true });
const dst = join(dir, 'sessions', '-proj', basename(src));
if (process.env.STRIP_EXIT === '1') {
  const keep = readFileSync(src, 'utf8').split('\n').filter((l) => !l.includes('"customType":"session_exit"'));
  writeFileSync(dst, keep.join('\n'));
} else copyFileSync(src, dst);
const touch = () => utimesSync(dst, new Date(), new Date());
touch();

const now = () => Date.now();
const late: { office?: InstanceType<typeof Office> } = {};
const accounts = new AccountsService({ dirs: [], home: tmpDir, env: {}, now, onChange: () => late.office?.markDirty() });
const office = new Office({ names: new NameStore(null), version: 'medicao', startedAt: Date.now(), accounts: (s: any) => accounts.list(s), sources: () => [], accountName: (id: string) => accounts.find(id)?.detected.name, now });
late.office = office;
const source = new OmpSource({ accounts, office, dirs: [dir], env: {}, home: tmpDir, now, watch: false });

const agentId = () => office.commit().snapshot.agents.find((a: any) => a.id.startsWith('omp:'))?.id as string | undefined;
const hist = (id: string) => {
  const h = office.detail(id)!.history;
  return { total: h.length, unicos: new Set(h.map((a: any) => a.id)).size };
};
const ms = (t0: number) => (performance.now() - t0).toFixed(1);

let t = performance.now();
source.boot();
office.tick();
source.poll();
office.tick();
const bootMs = ms(t);
const id = agentId();
console.log(`arquivo: ${(statSync(dst).size / 1e6).toFixed(1)} MB; carga inicial (boot+poll): ${bootMs} ms; agente: ${id ?? 'NENHUM'}`);
if (!id) {
  rmSync(tmpDir, { recursive: true, force: true });
  process.exit(2);
}
console.log('histórico após a carga:', hist(id));

// Reescreve a linha 1 (slot do título) no lugar e na mesma largura, como o omp faz.
function reescreverSlot(n: number) {
  const fd = openSync(dst, 'r+');
  const buf = Buffer.alloc(4096);
  readSync(fd, buf, 0, 4096, 0);
  const nl = buf.indexOf(0x0a);
  const slot = JSON.parse(buf.subarray(0, nl).toString('utf8'));
  slot.title = `${String(slot.title ?? '').slice(0, 10)} #${n}`;
  slot.updatedAt = new Date().toISOString();
  slot.pad = '';
  const line = Buffer.from(JSON.stringify(slot));
  if (line.length > nl) throw new Error('slot novo não cabe na largura');
  writeSync(fd, Buffer.concat([line, Buffer.alloc(nl - line.length, 0x20)]), 0, nl, 0);
  closeSync(fd);
  touch();
}
for (let i = 1; i <= 3; i++) {
  reescreverSlot(i);
  t = performance.now();
  source.poll();
  const gasto = ms(t);
  office.tick();
  console.log(`poll após reescrever o slot #${i}: ${gasto} ms; histórico:`, hist(id));
}
t = performance.now();
source.poll();
console.log(`poll sem mudança (referência): ${ms(t)} ms`);
source.stop();
rmSync(tmpDir, { recursive: true, force: true });
```

3. Rode (na raiz da worktree, que tem o `node_modules` ligado) o maior transcript principal e **uma sessão pequena com
   mais de 30 e menos de 200 atividades** (o caso da duplicação; um arquivo de 100–300 KB de um dia recente costuma servir:
   `find ~/.omp/agent/sessions -maxdepth 2 -name '*.jsonl' -newermt 2026-10-15 -size +100k -size -300k | head -3`):

   `STRIP_EXIT=1 npx tsx /tmp/hb019/medir.mts <caminho>` (uma vez por arquivo; ignore as linhas `npm notice`).

4. Anote, para o relatório final: tamanho do arquivo, carga inicial, os 3 tempos de `poll` após a reescrita (use a
   **mediana**) e o `histórico` antes/depois (`total` vs `unicos`).

**Verify**: o script imprime `histórico após a carga: { total: N, unicos: N }` e, na sessão de 31–199 atividades, após o
primeiro `poll` da reescrita `total` **maior** que `unicos` (confirma o defeito). Se, nessa faixa de tamanho, `total` ficar
igual a `unicos` em todas as reescritas → STOP (o defeito não reproduz aqui). Sai com código 2 e `NENHUM`? Tente outro
arquivo; se nenhum entrar, STOP.

### Step 3: Portão de decisão (numérico, sem julgamento)

Use a mediana do `poll` após a reescrita **no maior transcript principal**:

| Mediana medida | Decisão |
|---|---|
| **< 250 ms** (≈ 1/8 do timeout de 2 s da extensão) | Faça **só a Parte A** (Passos 4–7 e 12). A Parte B **não** se faz; registre o número no relatório. *(Resultado de quem escreveu o plano: 115 ms → só A.)* |
| **≥ 250 ms** | Faça a Parte A **e** a Parte B (Passos 8–11). |
| Sem sessões reais na máquina | Só a Parte A; registre "sem medição". |

Escreva a decisão e o número no corpo do commit do Passo 7. Não escolha outro limite.

### Step 4 (Parte A): Escrever os testes que falham (Office)

Em `server/model/model.test.ts`, dentro de `describe('Office', …)`, logo depois do teste "feed traz a conta do agente…"
(termina em `:260`), acrescente dois testes no mesmo molde (`makeOffice`, `act`, `office.addMain({ id: 'acc:1', … })`):

1. `it('releitura do transcript com mais de 30 atividades não duplica o histórico, não mexe na atividade atual e não refaz o feed', …)`
   - Crie 100 atividades `a0…a99` (use `advance(1_000)` + `act(id, now())`, guarde os objetos num array) e adicione-as com `current = true`.
   - `office.commit()` (consome o feed). Guarde `before = office.detail('acc:1')!.history.map((a) => a.id)` e `lastEventAt`/`activity.id` do agente.
   - Releia: `for (const a of acts) office.addActivity('acc:1', a, false, { feed: false })`.
   - Espere: `history.map(id)` **igual a `before`** (100 entradas, mesma ordem); `office.commit().feed` igual a `[]`; `activity?.id` ainda `'a99'`.
   - Depois adicione uma atividade nova `a100` e espere que ela **entre** no fim do histórico (101 entradas): o dedupe não pode engolir atividade nova.
2. `it('releitura de um transcript maior que o histórico (250 atividades) mantém as 200 mais novas, em ordem e sem cópias', …)`
   - 250 atividades `b0…b249`, depois a mesma releitura. Espere `history.map(id)` igual a `b50…b249` (200 entradas, em ordem).

**Verify**: `npx vitest run server/model/model.test.ts` → o teste 1 **falha** (histórico com 200 entradas em vez de 100/101);
o teste 2 pode já passar (o corte de 200 o esconde) — isso é esperado e ele serve de trava contra a regressão do Passo 5.

### Step 5 (Parte A): Teste de ponta a ponta na fonte do omp (falhando)

Em `server/sources/omp/source.test.ts`, logo depois do teste "título do slot mudou no lugar…" (`:191-209`), acrescente
`it('título trocado no lugar numa sessão com mais de 30 atividades: o histórico do detalhe não duplica e mantém a ordem', …)`,
copiando o corpo do teste vizinho, com estas diferenças:

- sessão criada com `L.model(c)`, `L.user(c)` e 40 chamadas de ferramenta: `Array.from({ length: 40 }, (_, i) => [L.tool(c, \`toolu_${i}\`, 'bash', { command: \`echo ${i}\`, i: \`Passo ${i}\` }), L.result(c, \`toolu_${i}\`, 'bash')]).flat()`;
- depois de `c.source.boot()`, `const before = c.office.detail(KEY)!.history.map((a) => a.id)` e `expect(before.length).toBeGreaterThan(30)`
  (trava: se a conta de atividades por chamada mudar e isso falhar, ajuste o 40 para cima, não remova a trava);
- o resto igual ao teste vizinho (acrescenta `title_change`, reescreve a linha 1 na mesma largura, `c.poll()` duas vezes);
- no fim: `expect(c.office.detail(KEY)!.history.map((a) => a.id)).toEqual(before)` e `expect(c.agent()!.title).toBe('Título novo')`.

**Verify**: `npx vitest run server/sources/omp/source.test.ts` → o teste novo **falha** (histórico duplicado) e os demais passam.

### Step 6 (Parte A): Corrigir o `addActivity`

Em `server/model/office.ts:728-729` troque a checagem para olhar também o histórico (200), mantendo a de `recent`:

```ts
    // Releitura de um transcript regravado (ex.: o omp reescreveu o slot do título): `recent` guarda só 30, então
    // sozinho ele deixa passar as mais antigas e o histórico (200) recebia cópias de tudo.
    if (rec.history.some((a) => a.id === activity.id) || info.recent.some((a) => a.id === activity.id)) return;
```

Nada mais muda nessa função (o ramo do `done` sintetizado, acima, fica como está).

**Verify**: `npx vitest run server/model/model.test.ts server/sources/omp/source.test.ts` → todos passam, inclusive os 3 novos.

### Step 7 (Parte A): Verificação ampla, changelog e commit

1. Em `CHANGELOG.md`, `## [Não lançado]` › `### Corrigido` (`:84`), acrescente no fim da seção (mantenha a quebra em ~120 colunas dos itens vizinhos):

```md
- **Histórico do agente do omp com atividades repetidas:** quando o omp trocava o título da sessão, o Habblaud relia o
  transcript e copiava de novo as atividades já conhecidas para o histórico do agente, que enchia de repetidas e mostrava
  uma linha do tempo mais curta. Agora cada atividade entra uma vez só.
```

2. `npm run typecheck` e `npm test`. Rode `npx vitest run server/sources` também (inclui `tail`, outras fontes que chamam `addActivity`).
3. Commit (corpo com a decisão do Passo 3 e os números medidos).

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0, com 3 testes a mais que a linha de base. Se o Passo 3 mandou **só a Parte A**, pule para o Passo 12 (os Passos 8–11 não se aplicam; no relatório, diga "Parte B não executada: mediana X ms < 250 ms").

---

### Parte B — só se o portão do Passo 3 mandou (mediana ≥ 250 ms)

**Ideia**: parar de tratar a reescrita da linha 1 como troca de arquivo, no `FileTail` do omp. O título novo já chega pela
linha `title_change` acrescentada (ver "Fato que vale para a Parte B"), então basta continuar lendo de onde parou. Para
não perder a detecção de troca verdadeira, o "começo" comparado passa a ser o que vem **depois** da primeira linha, e
uma segunda checagem confere os últimos bytes lidos (guarda), porque sem o slot o `head` sozinho não pega uma
reescrita do corpo no mesmo inode.

**Por que dá para distinguir** (e onde não dá): o slot tem largura fixa (256 bytes) e a linha 2 (`{"type":"session",…}`) tem o
id e o `timestamp` da sessão, únicos por arquivo. Uma troca por outro arquivo muda o inode ou a linha 2; uma reescrita do
corpo (compactação, migração de versão) muda os bytes logo antes do offset lido. Os dois casos são pegos pela guarda. O que
**não** é detectável: reescrita que preserve a linha 2 **e** os últimos 64 bytes lidos, mas mude bytes no meio — se o
Passo 8 mostrar que o omp faz isso, pare (STOP).

### Step 8 (Parte B): Confirmar que o omp só reescreve o slot

Procure no código do omp instalado (o pacote do omp na máquina; `command -v omp` e siga o link) onde grava o slot
(`"type":"title"` / `pad`) e se algum outro caminho reescreve o **corpo** do transcript no lugar (compactação,
migração `version`). Se não achar o código-fonte, use os dados: para as 387 sessões com slot, confirme que
`título do slot == último title_change` (o script Python abaixo, só leitura, imprime `slot≠último title_change: 0`):

```python
import glob, json, os
os.chdir(os.path.expanduser('~/.omp/agent/sessions'))
diff = tot = 0
for f in glob.glob('*/*.jsonl'):
    with open(f, 'rb') as fh:
        try: slot = json.loads(fh.readline())
        except Exception: continue
        if slot.get('type') != 'title' or not slot.get('title'): continue
        last = None
        for line in fh:
            if b'"type":"title_change"' in line[:200]:
                try: last = json.loads(line).get('title')
                except Exception: pass
    tot += 1
    if last != slot['title']: diff += 1
print(tot, 'sessões com slot; slot≠último title_change:', diff)
```

**Verify**: imprime `… slot≠último title_change: 0`. Qualquer valor > 0 → STOP (há título que só chega pelo slot; ignorar o slot o perderia).

### Step 9 (Parte B): `FileTail` com opção `skipFirstLine` (testes primeiro)

Em `server/sources/tail.test.ts`, no grupo "reescrita no mesmo inode" (`:77-124`), acrescente (molde: os testes vizinhos, com `await tick()`),
usando `new FileTail(file, { skipFirstLine: true })` nos três primeiros:

1. `it('linha 1 reescrita no lugar e linhas acrescentadas: não recomeça e entrega só as novas', …)` — arquivo `slot-A␣␣␣\nsessao-1\nx\n` (linha 1 de largura fixa), `read()`, `await tick()`, reescreve a linha 1 (mesma largura) e acrescenta `y\n` → `reset: false`, `lines: ['y']`.
2. `it('corpo reescrito no lugar com a mesma linha 2 recomeça do início', …)` — mesmo começo, corpo diferente e de tamanho maior ou igual → `reset: true`.
3. `it('arquivo trocado (outro inode) continua recomeçando', …)` — `rmSync` + novo arquivo com a linha 1 igual → `reset: true`.
4. `it('sem a opção, linha 1 reescrita continua recomeçando (Claude Code e Codex não mudam)', …)` — `new FileTail(file)` → `reset: true`.

**Verify**: `npx vitest run server/sources/tail.test.ts` → só o teste 1 **falha** (falta a opção); os testes 2, 3 e 4 já passam sem a implementação e servem de trava contra falsos positivos da detecção nova. Se o 1 passar antes de implementar, STOP (a premissa do plano está errada).

### Step 10 (Parte B): Implementar em `tail.ts` e usar no `load()` do omp

Em `server/sources/tail.ts`:

- Opção `skipFirstLine?: boolean` em `FileTail` (JSDoc em português: "a linha 1 é reescrita no lugar pelo dono do arquivo (slot do título do omp) e não conta como troca").
- Com a opção, o `head` guardado passa a ser os até `HEAD_BYTES` bytes **depois do primeiro `\n`** (guarde também `headFrom`, a posição desse primeiro byte; se o arquivo ainda não tem `\n`, `head = null` e a detecção por começo fica desligada até ter). `replaced()` compara `readHead(fd, len, headFrom)` (estenda `readHead` com um `from = 0`).
- Guarda: depois de cada `read()` que avançou o `offset`, guarde `guard` = até 64 bytes imediatamente antes de `offset` (e o `offset` a que pertencem). Em `replaced()`, quando `written` e `offset > 0`, releia esses bytes e, se diferirem, devolva `true`. Sem a opção, a guarda não é usada (o comportamento de hoje fica idêntico).
- `server/sources/omp/source.ts:595`: `const tail = new FileTail(path, { skipFirstLine: true });` com comentário em português: "o omp reescreve o slot do título (linha 1) no lugar a cada título novo e acrescenta um title_change; reler o arquivo por isso custa até ~100 ms de laço parado e não traz nada novo".

**Verify**: `npx vitest run server/sources/tail.test.ts server/sources/omp/source.test.ts` → todos passam (incluindo o teste vizinho "título do slot mudou no lugar…", que continua exigindo o título novo e as mesmas estatísticas).

### Step 11 (Parte B): Repetir a medição

Rode de novo o Passo 2 no maior transcript principal.

**Verify**: mediana do `poll` após a reescrita < 10 ms e `histórico` ainda sem cópias. Se continuar ≥ 250 ms, STOP e relate (a releitura vem de outra causa).

### Step 12: Fechar

`npm run typecheck`, `npm test`, `git status` (nada fora do Scope; sem o symlink do `node_modules`), atualizar `plans/README.md`
(linha do plano 019: `DONE (só Parte A: mediana X ms)` ou `DONE (A+B)`), commit final.

**Verify**: `npm test` → exit 0; `git status --short` → só os arquivos do Scope.

## Test plan

- **Office** (`server/model/model.test.ts`, molde `:241-260`): releitura de 100 atividades (31–199: o caso real) não
  duplica, mantém ordem, atividade atual e feed; atividade nova depois dela entra. Releitura de 250 (> `HISTORY_LIMIT`)
  mantém as 200 mais novas em ordem (trava contra o efeito de sobrescrever o corte).
- **Fonte do omp** (`server/sources/omp/source.test.ts`, molde `:191-209`): título trocado no lugar com 40 chamadas de
  ferramenta; o histórico do detalhe (`office.detail`) fica idêntico e o título novo aparece.
- **Só se a Parte B for feita** (`server/sources/tail.test.ts`, molde `:77-124`): linha 1 reescrita + linhas novas = sem
  reset; corpo reescrito, troca de inode e ausência da opção = reset.
- Verificação: `npm test` → tudo passa, com 3 testes novos (Parte A) ou 7 (A+B).
- Os testes que cobrem o defeito (Passo 4, teste 1; Passo 5; Passo 9, teste 1) precisam **falhar antes da correção**; os de trava (Passo 4, teste 2; Passo 9, testes 2–4) já passam antes e devem continuar passando.

## Done criteria

ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; os testes novos do Office e da fonte do omp existem e passam (e falhavam antes do Passo 6)
- [ ] `grep -n "rec.history.some" server/model/office.ts` mostra a checagem nova dentro de `addActivity`
- [ ] O relatório traz os números do Passo 2 (tamanho, carga inicial, mediana do `poll`, histórico antes/depois) e a decisão do Passo 3
- [ ] Se a Parte B **não** foi feita: `git diff --stat producao..HEAD -- server/sources/tail.ts server/sources/omp/source.ts` vazio
- [ ] Se a Parte B foi feita: `grep -n "skipFirstLine" server/sources/tail.ts server/sources/omp/source.ts` acha a opção nos dois
- [ ] `CHANGELOG.md` tem o item em `## [Não lançado]` › `### Corrigido`
- [ ] Nenhum arquivo fora do Scope foi modificado (`git status`); `/tmp/hb019/` e o symlink `node_modules` fora do commit
- [ ] `plans/README.md` com a linha do plano 019 atualizada

## STOP conditions

Stop and report back (do not improvise) if:

- O código nos locais de "Current state" não bate com os trechos (drift desde `af73a23`).
- No Passo 2, a sessão de 31–199 atividades **não** mostra `total > unicos` depois da reescrita (o defeito não reproduz).
- O teste novo da fonte do omp (Passo 5) passa **antes** da correção do Passo 6 (a premissa de que o dedupe de 30 entradas é a causa está errada).
- Um passo falha a verificação duas vezes depois de uma tentativa razoável de conserto.
- A correção parece exigir mexer em arquivo fora do Scope (por exemplo, no cliente ou em outra fonte).
- Parte B: o Passo 8 mostra qualquer sessão cujo título só existe no slot (sem `title_change` igual), **ou** o omp reescreve o corpo do
  transcript no lugar de um jeito que preserve a linha 2 e os 64 bytes antes do offset, **ou** o teste 2 do Passo 9 (corpo reescrito) não consegue ser feito
  falhar/passar de forma estável: distinguir "linha 1 reescrita" de "troca de arquivo" não é confiável. Nesse caso, **não** entregue a Parte B: entregue só
  a Parte A e relate a medição.
- Parte B: depois de implementada, a mediana do `poll` continua ≥ 250 ms (causa errada), ou algum teste existente de `tail.test.ts` muda de resultado.

## Maintenance notes

- Se o `HISTORY_LIMIT` (200) ou o `BACKLOG_MAX` (400) mudarem, os dois testes do Passo 4 (100 e 250 atividades) precisam
  acompanhar: o primeiro precisa ficar entre `RECENT_LIMIT` e `HISTORY_LIMIT`, o segundo acima de `HISTORY_LIMIT`.
- O `addActivity` agora faz uma busca linear de até 200 ids por atividade (trocar por um `Set` por agente só se um
  perfil mostrar custo; hoje são ~80 mil comparações por releitura de 400 do backlog, sub-milissegundo).
- O mesmo `reset` vale para Claude Code e Codex quando o arquivo é de fato reescrito; o dedupe agora os protege também,
  porque está no `Office`.
- O `load()` da primeira entrada de uma sessão grande custa ~150 ms (45 MB) e continua síncrono: é o mesmo laço, e fazê-lo
  em pedaços muda a ordem em que o agente entra no escritório. Fica **fora** deste plano de propósito.
- Revisor: confira que o dedupe não engole atividade nova (teste do Passo 4, `a100`), que o ramo do `done` sintetizado
  (`office.ts:716-727`) não mudou, e que o plano não tocou nos limites. Se a Parte B entrou, olhe a guarda de 64 bytes
  (falso positivo = releitura a mais, inofensiva; falso negativo = leitura no meio de uma linha, grave).
- Adiado: tornar o `load()` incremental/assíncrono; guardar o `Set` de ids por agente.
