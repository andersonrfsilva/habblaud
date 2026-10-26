# Plan 017: Um erro ao montar o snapshot não derruba mais o servidor (SSE, commit, desligamento e erros sem tratamento)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- server/http/sse.ts server/model/office.ts server/index.ts shared/locales/en.ts shared/locales/es.ts shared/locales/fr.ts server/model/model.test.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `af73a23`, 2026-10-25

## Why this matters

O Hub (SSE) monta o snapshot do escritório dentro de um `setTimeout` sem `try/catch`. O snapshot junta dados de muitas
fontes (permissões, mensagens, ptys, atualizações, mundo, contas, fontes de agentes). Se qualquer uma lançar uma
exceção, ela vira `uncaughtException` e **o processo inteiro do Habblaud morre**: todas as abas perdem a conexão e
ficam pedidos de permissão, mensagens e perguntas laterais pendentes perdidos. Não há nenhum `process.on('uncaughtException')`
no servidor, então só o reinício do contêiner recupera. Depois deste plano, um erro ao montar o snapshot é registrado uma
vez no log, o servidor continua entregando o último snapshot bom e tenta de novo sozinho; o desligamento sempre chega
ao `process.exit`; e qualquer erro realmente inesperado é registrado antes de o processo sair.

## Current state

Verificado lendo o código no commit `af73a23`.

- `server/http/sse.ts` — o Hub SSE. `flush()` fecha a revisão e transmite; `schedule()` agenda o `flush()` com throttle.
- `server/model/office.ts` — `commit()` fecha a revisão; `build()` monta o snapshot.
- `server/index.ts` — ponto de entrada; `shutdown()` desliga tudo; `ticker` já é protegido.
- `server/log.ts` — `log.warnOnce(key, msg)`, `log.clearOnce(key)`, `errMsg(err)`.
- `server/history/daystats.ts:265-272` — o padrão de timer protegido a copiar (`safely`).
- Não existe `process.on('uncaughtException' | 'unhandledRejection')` em `server/` (só um teste, `server/test/omp-extension.test.ts:903`, escuta `unhandledRejection` para medir).

`server/http/sse.ts:35-39` (pinger sem proteção) e `server/http/sse.ts:92-125`:

```ts
35:  start(): void {
36:    this.unsubscribe = this.office.onChange(() => this.schedule());
37:    this.pinger = setInterval(() => this.writeAll(': ping\n\n'), this.pingMs);
38:    this.pinger.unref?.();
39:  }
...
92:  flush(): OfficeSnapshot {
93:    if (this.timer) clearTimeout(this.timer);
94:    this.timer = null;
95:    this.lastFlush = Date.now();
96:    const r = this.office.commit();
97:    if (r.changed) {
98:      this.writeAll(frame('snapshot', r.snapshot));
...
117:  private schedule(): void {
118:    if (this.timer) return;
119:    const wait = Math.max(0, this.lastFlush + this.throttleMs - Date.now());
120:    this.timer = setTimeout(() => {
121:      this.timer = null;
122:      this.flush();
123:    }, wait);
124:    this.timer.unref?.();
125:  }
```

O `flush()` também é chamado por `current()` (`sse.ts:113-115`), que serve `attach()` e o `DayStatsService`
(`server/index.ts:169`, que já protege a chamada com `safely`). Nesses chamadores um erro deve continuar subindo.

`server/model/office.ts:959-970`:

```ts
959:  /** Fecha uma revisão: devolve o snapshot (novo, se algo mudou), o feed e os avisos pendentes. */
960:  commit(): CommitResult {
961:    const feed = this.pendingFeed;
962:    const notices = this.pendingNotices;
963:    this.pendingFeed = [];
964:    this.pendingNotices = [];
965:    if (!this.dirty && this.last) return { snapshot: this.last, changed: false, feed, notices };
966:    this.rev++;
967:    this.dirty = false;
968:    this.last = this.build();
969:    return { snapshot: this.last, changed: true, feed, notices };
970:  }
```

Problemas reais confirmados aqui: se `build()` (linha 968) lançar, **o feed e os avisos pendentes já foram jogados
fora** (963-964), `rev` já subiu (966) e `dirty` já está `false` (967). Resultado: o `last` fica velho, e o próximo
`commit()` devolve "nada mudou" (965) sem refazer o snapshot, até alguma outra mudança chegar. `build()`
(`office.ts:974-1030`) chama `this.deps.permissions?.()`, `messages?.()`, `ptys?.()`, `accounts(...)`, `sources()`,
`updates?.()`, `world?.()` e `ompRoomByProject?.()`. `build()` **não** mexe em `pendingFeed`/`pendingNotices`
(`grep -n "pendingFeed\|pendingNotices" server/model/office.ts` só acha as linhas 209, 210, 939, 961-964, 1086 e 1113;
nenhuma dentro de `build`).

`server/index.ts:587-612` (`shutdown`): `stats.stop()` (593) é o primeiro desligamento e não tem proteção; se lançar,
`server.close(() => process.exit(0))` (610) e o `setTimeout(...process.exit(0))` (611) nunca rodam, e `names.flush()`
(607) é pulado:

```ts
587: let shuttingDown = false;
588: function shutdown(signal: string): void {
589:   if (shuttingDown) return;
590:   shuttingDown = true;
591:   log.info(tr('Encerrando ({0})…', [signal]));
592:   clearInterval(ticker);
593:   stats.stop();
594:   agents.stop();
...
607:   names.flush();
608:   void closeVite?.();
609:   remoteListener?.close();
610:   server.close(() => process.exit(0));
611:   setTimeout(() => process.exit(0), 1_500).unref();
612: }
613: process.on('SIGINT', () => shutdown('SIGINT'));
```

Padrão existente de timer protegido (copiar o estilo): `server/history/daystats.ts:265-272` e `server/index.ts:358-364`:

```ts
private safely(what: string, fn: () => void): void {
  try {
    fn();
    log.clearOnce(`stats-${what}-fail`);
  } catch (err) {
    log.warnOnce(`stats-${what}-fail`, tr('Falha nas estatísticas do dia ({0}): {1}', [what, errMsg(err)]));
  }
}
```

Convenções: logs sem conteúdo de conversa (`server/log.ts:1`: "nunca registre prompts, respostas ou segredos"), por isso
use `errMsg(err)` e não a stack inteira; textos de log passam por `tr('...')` e **cada texto novo precisa de entrada em
`shared/locales/{en,es,fr}.ts`** (a chave é o texto PT exato; `npm run i18n:check -- --strict` valida); comentários em
português explicando o porquê.

### Decisão: o que fazer nos handlers de processo

- **`uncaughtException`: registrar e sair com código 1.** O Node documenta que, depois de uma exceção não capturada, o
  estado do processo pode estar corrompido (um timer ou um callback no meio de uma atualização). Continuar rodando pode
  servir dados errados ou travar de um jeito que ninguém percebe. Em produção há política de reinício do contêiner, então
  sair limpo é mais seguro e recupera sozinho. Antes de sair, tentar `names.flush()` (nomes de personagens) com
  `try/catch`.
- **`unhandledRejection`: registrar e continuar.** Uma promessa rejeitada sem tratamento não interrompe código síncrono
  no meio de uma atualização, então não deixa o estado pela metade como a exceção. Hoje (Node ≥ 15) ela derruba o
  processo; para um painel que atende várias abas e guarda pedidos pendentes, derrubar por uma falha isolada de rede ou de
  arquivo é desproporcional. O log usa `warnOnce` para não inundar.
- A correção que importa é a proteção do envio SSE (passos 1 e 2): ela impede que o caso mais provável chegue ao
  handler. Os handlers são a rede de segurança, não a solução.

## Commands you will need

| Purpose     | Command                                         | Expected on success                     |
|-------------|-------------------------------------------------|-----------------------------------------|
| Typecheck   | `npm run typecheck`                             | exit 0                                  |
| Tests (all) | `npm test`                                      | all pass (baseline 181 files / 2180 tests pass, 2 skipped) |
| Tests (one) | `npx vitest run server/http/sse.test.ts`        | all pass                                |
| Tests (one) | `npx vitest run server/model/model.test.ts`     | all pass                                |
| i18n        | `npm run i18n:check -- --strict`                | exit 0                                  |

## Scope

**In scope** (the only files you should modify or create):
- `server/model/office.ts` (só `commit()`)
- `server/http/sse.ts`
- `server/index.ts` (só `shutdown()` e os handlers de processo logo depois)
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts` (só as entradas novas)
- `server/model/model.test.ts` (adicionar casos)
- `server/http/sse.test.ts` (criar)
- `CHANGELOG.md`

**Out of scope** (do NOT touch):
- `server/model/office.ts` `build()`: não proteger provedor por provedor; o plano trata a falha no ponto de consumo.
- `server/history/daystats.ts`, `server/index.ts` `ticker`: já protegidos.
- Qualquer mudança no protocolo SSE ou em `shared/types.ts`.
- `~/apps/habblaud` (produção): nunca tocar nem reiniciar.

## Git workflow

- Worktree: `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-017 -b advisor/017-servidor-nao-cai-por-erro-no-envio producao` e depois `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-017/node_modules` (nunca faça commit do link).
- Trabalhe em `/tmp/hb-017`. Um commit por passo lógico, em português, assunto em minúsculas (veja `git log --oneline -15`), por exemplo: `corrige: commit do escritório não perde feed nem revisão quando o snapshot falha`.
- Não faça push nem abra PR.

## Steps

### Step 1: `Office.commit()` só consome a revisão depois de montar o snapshot

Em `server/model/office.ts`, substitua o corpo de `commit()` (linhas 960-970) para que: (a) `build()` rode antes de
esvaziar `pendingFeed`/`pendingNotices` e antes de marcar `dirty = false`; (b) se `build()` lançar, `rev` volte ao valor
anterior, `dirty` continue `true`, `last` fique intacto e o feed/avisos pendentes sejam preservados para o próximo commit.
A exceção continua subindo. Forma alvo:

```ts
  commit(): CommitResult {
    let changed = false;
    if (this.dirty || !this.last) {
      // Se build() lançar, nada é consumido: rev, dirty, last, feed e avisos ficam como estavam
      // e o próximo commit() refaz o snapshot.
      const rev = this.rev;
      this.rev++;
      try {
        this.last = this.build();
      } catch (err) {
        this.rev = rev;
        throw err;
      }
      this.dirty = false;
      changed = true;
    }
    const feed = this.pendingFeed;
    const notices = this.pendingNotices;
    this.pendingFeed = [];
    this.pendingNotices = [];
    return { snapshot: this.last!, changed, feed, notices };
  }
```

(`this.last!` é seguro: ou já existia, ou acabou de ser atribuído.) Mantenha o comentário de documentação da linha 959.

**Verify**: `npm run typecheck` → exit 0; `npx vitest run server/model/model.test.ts` → todos passam (o comportamento em caso de sucesso não muda: mesma ordem de `rev`, mesmo `changed`).

### Step 2: Hub protege o envio agendado, tenta de novo e protege o pinger

Em `server/http/sse.ts`:

1. Importe `import { errMsg, log } from '../log';` e `import { tr } from '../../shared/i18n';` (confira o caminho relativo: `server/http/` → `../../shared/i18n`, como `server/http/app.ts` faz; use o mesmo que ele).
2. Adicione a opção `retryMs?: number` ao `opts` do construtor e um campo `private readonly retryMs: number;` (`opts.retryMs ?? 2_000`). Só serve para o teste não esperar 2 s.
3. `schedule(wait?: number)`: use `wait ?? Math.max(0, this.lastFlush + this.throttleMs - Date.now())`.
4. Em `flush()`, envolva **só** a chamada `this.office.commit()`: se lançar, agende uma nova tentativa e relance, de modo que todo chamador (o timer, `current()`/`attach()`) tenha a nova tentativa garantida e quem espera o erro (`attach`, `DayStatsService.safely`) continue recebendo-o:

```ts
    let r: ReturnType<Office['commit']>;
    try {
      r = this.office.commit();
    } catch (err) {
      // O escritório não consumiu nada (ver Office.commit): tenta de novo logo, mesmo sem outra mudança.
      this.schedule(this.retryMs);
      throw err;
    }
```

5. No `setTimeout` de `schedule()`, proteja a chamada seguindo o estilo de `daystats.safely` (uma chave fixa, `clearOnce` ao recuperar):

```ts
    this.timer = setTimeout(() => {
      this.timer = null;
      try {
        this.flush();
        log.clearOnce('sse-flush');
      } catch (err) {
        // Um erro ao montar o snapshot não pode derrubar o processo: segue com o último snapshot e flush() já reagendou.
        log.warnOnce('sse-flush', tr('Falha ao montar o snapshot do escritório: {0}. Sigo com o anterior e tento de novo.', [errMsg(err)]));
      }
    }, wait);
```

6. O pinger (linha 37) vira `setInterval(() => { try { this.writeAll(': ping\n\n'); } catch (err) { log.warnOnce('sse-ping', tr('Falha ao enviar o ping do stream: {0}', [errMsg(err)])); } }, this.pingMs)`.

Observação: `JSON.stringify` em `frame()` dentro de `flush()` (linhas 98, 107, 108) também é coberto pelo `try/catch` do timer, mas **não** é reagendado: depois de um `commit()` bem-sucedido o escritório já considera o snapshot entregue. Isso é aceitável aqui: o snapshot é só dados JSON próprios, e o próximo `markDirty` o reenvia. Não tente tratar esse caso.

**Verify**: `npm run typecheck` → exit 0.

### Step 3: Textos novos nas três línguas

Acrescente em `shared/locales/en.ts`, `es.ts` e `fr.ts`, perto da entrada `"Falha no relógio do escritório: {0}"` (en/es linha 1750, fr linha 1649), estas chaves (as que o passo 4 também usa já estão incluídas):

| Chave PT (exata) | en | es | fr |
|---|---|---|---|
| `Falha ao montar o snapshot do escritório: {0}. Sigo com o anterior e tento de novo.` | `Failed to build the office snapshot: {0}. Keeping the previous one and retrying.` | `Error al montar la instantánea de la oficina: {0}. Sigo con la anterior y lo intento de nuevo.` | `Échec de la construction de l'instantané du bureau : {0}. Je garde le précédent et je réessaie.` |
| `Falha ao enviar o ping do stream: {0}` | `Failed to send the stream ping: {0}` | `Error al enviar el ping del stream: {0}` | `Échec de l'envoi du ping du flux : {0}` |
| `Falha ao encerrar um serviço: {0}` | `Failed to stop a service: {0}` | `Error al detener un servicio: {0}` | `Échec de l'arrêt d'un service : {0}` |
| `Erro não tratado; encerrando para o reinício limpo: {0}` | `Unhandled error; exiting for a clean restart: {0}` | `Error no controlado; saliendo para un reinicio limpio: {0}` | `Erreur non gérée ; arrêt pour un redémarrage propre : {0}` |
| `Promessa rejeitada sem tratamento: {0}` | `Unhandled promise rejection: {0}` | `Promesa rechazada sin tratar: {0}` | `Promesse rejetée non gérée : {0}` |

Copie o formato (aspas e vírgula final) das linhas vizinhas.

**Verify**: `npm run i18n:check -- --strict` → exit 0 (se reclamar de chave sobrando ou faltando, a chave PT difere do texto usado em `tr()` nos passos 2 e 4; ajuste a chave, não o texto).

### Step 4: `shutdown()` sempre chega ao fim e rede de segurança de processo

Em `server/index.ts`:

1. Reescreva a parte de `shutdown()` entre `clearInterval(ticker)` e `remoteListener?.close()` (linhas 592-609) como uma lista de passos, cada um com seu `try/catch`, preservando a **ordem atual** e todos os itens (incluindo `void closeVite?.()` e `remoteListener?.close()`). Depois do laço, mantenha as linhas 610-611 como estão:

```ts
  const steps: Array<() => void> = [
    () => clearInterval(ticker),
    () => stats.stop(),
    () => agents.stop(),
    // ... todos os demais, na ordem original das linhas 594-609, cada um com a MESMA chamada de hoje
    // (ex.: `() => accounts.stop()`, `() => terminals?.stop()`, `() => ptys?.stopAll()`: não troque stop por stopAll nem o contrário) ...
    () => names.flush(),
    () => void closeVite?.(),
    () => remoteListener?.close(),
  ];
  for (const step of steps) {
    try {
      step();
    } catch (err) {
      // Um serviço que falha ao parar não pode impedir o process.exit nem os que vêm depois (names.flush grava os nomes).
      log.warn(tr('Falha ao encerrar um serviço: {0}', [errMsg(err)]));
    }
  }
```

Confira com `git diff` que a lista tem **exatamente** os mesmos 18 itens que existiam (ticker, stats, agents, accounts, hub, terminals, timeline, permissions, messages, asides, ptys, updates, worldViewer, worldUplink, world, names, closeVite, remoteListener) e nenhum a mais.

2. Logo depois dos `process.on('SIGBREAK', ...)` (linha ~616), acrescente:

```ts
// Rede de segurança. Exceção não capturada: o estado pode estar pela metade, então registra e sai (o contêiner
// reinicia); antes, tenta salvar os nomes. Promessa rejeitada: não deixa estado pela metade, então só registra.
let crashing = false;
process.on('uncaughtException', (err) => {
  if (crashing) return;
  crashing = true;
  // Só os quadros da stack (sem a mensagem completa), para o log não carregar conteúdo de conversa.
  const frames = String((err as Error)?.stack ?? '').split('\n').filter((l) => l.trimStart().startsWith('at ')).slice(0, 6).join(' | ');
  log.error(tr('Erro não tratado; encerrando para o reinício limpo: {0}', [`${errMsg(err)} ${frames}`.trim()]));
  try {
    names.flush();
  } catch {
    // saindo de qualquer jeito
  }
  process.exit(1);
});
process.on('unhandledRejection', (reason) => {
  log.warnOnce(`unhandled:${errMsg(reason)}`, tr('Promessa rejeitada sem tratamento: {0}', [errMsg(reason)]));
});
```

**Verify**: `npm run typecheck` → exit 0; `npm run i18n:check -- --strict` → exit 0.

### Step 5: Testes

Veja "Test plan". Crie `server/http/sse.test.ts` e acrescente os casos em `server/model/model.test.ts`.

**Verify**: `npx vitest run server/http/sse.test.ts server/model/model.test.ts` → todos passam, incluindo os novos.

### Step 6: Changelog

Em `CHANGELOG.md`, sob `## [Não lançado]`, crie (se não existir) a subseção `### Corrigido` (fica depois de `### Adicionado`/`### Alterado`, siga a ordem existente) com:

```md
- **Servidor não cai mais por um erro ao montar o snapshot:** uma falha numa das fontes do escritório (permissões,
  mensagens, terminais, atualizações, mundo) derrubava o processo inteiro e todas as abas. Agora o erro vai para o log
  uma vez, as abas seguem com o último estado bom e o servidor tenta de novo sozinho; o feed e os avisos pendentes não
  se perdem. O desligamento sempre termina, mesmo se um serviço falhar ao parar, e um erro realmente inesperado é
  registrado antes de o processo sair (o contêiner reinicia).
```

**Verify**: `git diff --stat` → só os arquivos do escopo; `npm test` → all pass.

## Test plan

**`server/http/sse.test.ts`** (criar). Estilo: copie o cabeçalho de `server/http/timeline.test.ts` (`setQuiet(true)`, `new Office({ names: new NameStore(null), version: 't', startedAt: Date.now(), accounts: () => [], sources: () => [], accountName: () => undefined, ...})`). Use um `permissions` que lança conforme uma flag: `let broken = true; permissions: () => { if (broken) throw new Error('falhou'); return new Map(); }`. Não precisa de servidor HTTP: observe o que o Hub transmite por `hub.onSnapshot(cb)`. Use timers reais com `new Hub(office, { throttleMs: 5, retryMs: 20 })` e `vi.waitFor` (de `vitest`) em vez de `sleep` fixo. Chame `hub.stop()` no `afterEach`.

Casos:

1. **Erro no snapshot não escapa do timer e a próxima tentativa transmite sozinha**: `hub.start()`; `office.markDirty()` com `broken = true`; espere ~60 ms (a tentativa agendada já falhou pelo menos uma vez; o vitest falha o arquivo se uma exceção escapar do timer) e confirme `snapshots.length === 0`; ponha `broken = false` **sem chamar `markDirty` de novo**; `await vi.waitFor(() => expect(snapshots).toHaveLength(1))`. Confirma o retry automático e que o escritório não ficou "limpo" pela falha.
2. **Falha não descarta feed nem avisos pendentes**: registre um cliente falso para ver o `feed` (um objeto mínimo com `write(chunk)`, `destroyed: false`, `writableEnded: false`, `writableLength: 0`, `on`/`end` como funções vazias, adicionado com `(hub as any).clients.add(fake)` — alternativa sem cast: usar `http.createServer` + `hub.attach` como em `timeline.test.ts`, e ler o corpo do stream com `http.get`). Gere uma atividade com `office.addMain(...)` + `office.addActivity(...)` (copie do primeiro caso de `describe('Office')` em `server/model/model.test.ts:220-228`), com `broken = true`; depois `broken = false`; espere o chunk que contém `event: feed` com o id da atividade. Garante que nada se perdeu.
3. **`current()` continua propagando o erro** (quem espera o erro, como `attach`/estatísticas, recebe-o): com `broken = true`, `expect(() => hub.current()).toThrow('falhou')`; e depois, com `broken = false`, `hub.current().rev` é um número e `hub.current()` não lança. Evita que alguém "engula" o erro no lugar errado.

**`server/model/model.test.ts`** — dentro de `describe('Office', ...)`, no estilo do caso `avisos com dedupe de 10 s e feed em lote` (linhas 220-239; use `makeOffice` ou crie o Office com `permissions` lançando, como acima — `makeOffice` não aceita extras, então construa o `Office` direto, copiando o corpo de `makeOffice` e acrescentando `permissions`):

4. **`commit()` que falha preserva tudo e o seguinte recupera**: `addMain` + `addActivity` (gera feed e avisos), `broken = true`; `expect(() => office.commit()).toThrow()`; `broken = false`; `const r = office.commit()`; esperado: `r.changed === true`, `r.feed.map(f => f.id)` contém o id da atividade, `r.notices` tem o aviso "Nova sala", `r.snapshot.rev === 1` (a falha não gastou número de revisão) e um terceiro `office.commit()` devolve `changed === false` com o mesmo snapshot.
5. **Dois commits com falha seguidos não acumulam revisão**: duas chamadas que lançam e uma que funciona ainda dão `rev === 1`.

Verificação: `npx vitest run server/http/sse.test.ts server/model/model.test.ts` → todos passam. Teste de sanidade (executor, sem commitar): reverta temporariamente o passo 1 (`git stash` só de `office.ts`) e confirme que os casos 2, 4 e 5 **falham**, depois restaure.

Não escreva teste para os handlers de `process` nem para `shutdown()`: ficam no `server/index.ts` (efeito colateral na carga do módulo) e testá-los exigiria simular o processo; a verificação deles é a revisão do diff do passo 4.

## Done criteria

ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; `server/http/sse.test.ts` existe com 3 casos e `server/model/model.test.ts` ganhou 2, todos passando
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `grep -n "uncaughtException\|unhandledRejection" server/index.ts` mostra os dois handlers; `grep -n "this.flush()" server/http/sse.ts` mostra a chamada dentro de um `try`
- [ ] `git diff -U0 server/model/office.ts` mostra mudança só em `commit()`
- [ ] Nenhum arquivo fora da lista de escopo foi modificado (`git status`)
- [ ] `CHANGELOG.md` tem a entrada em `### Corrigido` sob `## [Não lançado]`
- [ ] `plans/README.md` com a linha da 017 atualizada

## STOP conditions

Stop and report back (do not improvise) if:

- O código nos locais de "Current state" não bate com os trechos (o repositório mudou desde `af73a23`).
- `grep -n "pendingFeed\|pendingNotices" server/model/office.ts` mostra uso dentro de `build()` (linhas 974-1030): a reordenação do passo 1 deixaria de ser segura.
- Alguém chama `office.commit()` fora de `server/http/sse.ts` (`grep -rn "\.commit()" server --include=*.ts` hoje só acha o Hub e testes); o contrato novo (pode lançar sem consumir) precisaria ser conferido nesse chamador.
- Um passo falha na verificação duas vezes depois de uma correção razoável.
- A correção parecer exigir mexer em arquivo fora do escopo (por exemplo, para silenciar uma exceção que sempre acontece na inicialização).
- O teste 1 só passa se você aumentar muito os tempos (indício de que o retry não está agendando).

## Maintenance notes

- Contrato novo de `Office.commit()`: pode lançar, e nesse caso **não consome nada**. Quem chamar `commit()` precisa tratar a exceção (o Hub trata e reagenda).
- Se alguém adicionar um novo provedor em `OfficeDeps` consumido por `build()`, o erro dele agora aparece no log como `Falha ao montar o snapshot do escritório: ...` e o painel congela no último estado. Um congelamento persistente com esse log é o sintoma a investigar.
- Falha persistente gera uma tentativa a cada 2 s (`retryMs`) e uma única linha de log (`warnOnce`, reaberta quando volta a funcionar). Se o custo de `build()` ficar alto, avalie recuo exponencial.
- Para revisar no PR: (a) a ordem dos itens na lista de `shutdown()` é idêntica à anterior; (b) `unhandledRejection` só registra e não encerra (decisão deliberada, justificada acima); (c) `uncaughtException` sai com código 1, o que depende da política de reinício do contêiner de produção.
- Adiado de propósito: tratar `JSON.stringify` falhando em `frame()` depois de um commit bem-sucedido (dados próprios, improvável) e proteger `build()` por provedor (esconderia a falha em vez de sinalizá-la).
