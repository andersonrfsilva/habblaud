# Plan 079: Um personagem roda no Antigravity quando o modelo dele começa com `agy/` (servidor)

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat ba5a285..HEAD -- server/cast server/antigravity server/sources/antigravity server/messages/antigravity.ts server/model/cast.ts server/model/office.ts server/http/app.ts server/index.ts shared/types.ts`
> Se algum mudou, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED (mexe em `server/cast/launcher.ts`, que hoje supõe um runtime só por instalação)
- **Depends on**: plans/078-antigravity-pelo-acp.md (DONE, mesclado em `ecf3b94`)
- **Category**: direction
- **Planned at**: commit `ba5a285`, 2026-11-14 (reescrito no mesmo dia: o runtime deixou de ser um campo do
  personagem e passou a vir do modelo, a pedido do dono)

## Why this matters

Desde o plano 078 o Habblaud conversa com o Antigravity CLI (`agy`, do Google) pelo protocolo ACP, mas a única
entrada é um seletor no popover "Novo agente" da página, que só aparece por `http://localhost`. O dono trabalha pelo
**elenco** (personagens fixos) e pelos clientes Zaplaud, e pediu uma coisa transparente: **escolher um modelo do
`agy` e pronto**, sem campo de "ferramenta". Este plano faz o servidor tratar todo modelo cujo nome começa com
`agy/` como "roda no Antigravity": no personagem, no "Novo" e ao chamar. Personagens do omp (ou do ZeroClaw) e do
Antigravity convivem na mesma instalação. O plano 083 acrescenta a troca com o agente aberto e a passagem de
contexto; os planos 080, 081 e 082 são as telas.

## Regras de mecânica (não negociáveis; as mesmas do plano 078)

O dono não quer correr risco de bloqueio da conta Google. Os Termos do Antigravity
(<https://antigravity.google/terms>, item 6) proíbem acessar o serviço com software de terceiros; o ACP é a interface
que o `agy` documenta para clientes, e se um cliente próprio cabe na regra é leitura, não garantia. Por isso:

1. Só o binário oficial `agy` fala com o Google; o Habblaud só fala com ele por stdin/stdout de `agy acp` (direto ou
   pelo relé). Nenhum código lê `~/.gemini`.
2. A linha de comando do `agy` continua sendo só `agyArgs(...)` (`server/antigravity/acp.ts`): `acp`, mais
   `--dangerously-skip-permissions` quando o dono liga `HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS=1`. **Este plano não
   acrescenta flag nenhuma**; modelo e pasta vão por mensagens do protocolo.
3. Nenhuma permissão é respondida sem decisão de uma pessoa (a ponte `server/antigravity/permissions.ts` não muda).
4. **Nenhum turno automático.** Chamar um personagem sem pedido não manda nada ao `agy`: as instruções do
   personagem ficam guardadas e vão **junto da primeira mensagem que a pessoa mandar**.
5. O teste `server/test/antigravity-mecanica.test.ts` continua passando sem edição das afirmações.
6. `agy/` não é `google-antigravity/`. Os modelos `google-antigravity/...` do omp usam o login do Antigravity por
   dentro do omp, que é o caso que os termos citam como quebra. Este plano não os toca, não os converte e nunca
   trata um como o outro.

## Current state

Baseline em `ba5a285`: `npm run typecheck` ok; `npm test` 214 arquivos, 2857 testes passando, 7 pulados.

### Medido no `agy` 1.5.9 em 2026-11-14 (além do `plans/077-resultado.md`)

- `session/set_config_option {sessionId, configId: 'model', value: <valor>}` troca o modelo e devolve
  `{configOptions: [...]}` com o `currentValue` novo; chega também um aviso `session/update` com
  `sessionUpdate: 'config_option_update'`. Valor desconhecido: erro `{"code": -32602, "message": "Unknown model ID: …"}`.
- Os valores e nomes de modelo vêm na resposta do `session/new`, em `configOptions[id == 'model'].options`:
  `[{value: 'gemini-4-argon', name: 'Gemini 4 Argon'}, {value: 'gemini-4-hydrogen', …}, {value:
  'gemini-pro-orchestrator-g1-pro', …}, {value: 'gemini-3.8-flash', …}, {value: 'claude-opus-5-5', …}, {value:
  'claude-sonnet-5-5', …}, {value: 'gpt-oss-120b-medium', …}]`; o atual em `currentValue`. **Não há como listar
  modelos sem abrir uma sessão.**
- `session/resume` de uma sessão já fechada com `session/close`, em outro processo, **funciona**, mas o **modelo
  volta ao padrão**: depois de retomar é preciso mandar o `set_config_option` de novo.

### O que o 078 deixou pronto

- `server/antigravity/acp.ts`: `AntigravityClient` com `open(input: { cwd: string; resume?: string; replaces?:
  string })`, `prompt(sessionId, text)`, `cancel`, `answerPermission`, `close`, `list`, `session(id)`, `sessions()`,
  `onEvent`; evento `{ type: 'session-open'; sessionId; cwd; resumed; info: SessionInfo; replaces?: string }`.
- `server/sources/antigravity/source.ts`: agente `antigravity:<sessionId>`, conta `antigravity`; `replace(old,
  open)` mantém o agente quando a sessão troca ("conversa nova"); espelho da conversa em
  `<dataDir>/antigravity/sessions/<sessionId>.jsonl`.
- `server/messages/antigravity.ts`: `AntigravityDelivery` (`PushDelivery`): `sendMessage(id, content)` =
  `client.prompt`, `stopSession`, `newSession`.
- `server/model/office.ts` (laço do `castOpened`, perto da linha 1046): hoje marca `castOpened` para todo agente
  principal `antigravity` vivo. `POST /api/agents/:id/dismiss` fecha a sessão direto quando o agente é `antigravity`
  (`server/http/app.ts`, antes do ramo do `launcher`).

### O elenco hoje

- `shared/types.ts`, `CastMember`: `id`, `name`, `look`, `seed`, `parts?`, `kind: 'main' | 'advisor' | 'sub'`,
  `match?`, `agent?` (alias de agente ZeroClaw), `model?`, `instructions?`, `idleMinutes?`, `account?`, `last?:
  Record<string, string>` (última sessão por sala). Validação em `server/model/cast.ts:22-29` (devolve `null` quando
  inválido; `model` é texto de até 200 caracteres, sem espaço, sem começar com `-`). `last` é gravado por
  `server/model/office.ts:905-907` quando um agente com `cast` aparece.
- `server/cast/launcher.ts` (367 linhas): **um runtime por instalação**. Quase todo método começa com
  `if (this.opts.zeroclaw) …` e cai no caminho do omp (trabalho mandado ao auxiliar do host por `poll`/`ack`):
  ```ts
  type Entry = ({ runtime: 'omp'; job: CastJob } | { runtime: 'zeroclaw'; job: ZeroClawCastJob }) & {
    info: CastJobInfo; at: number; started: number; delivering?: boolean; opening?: boolean; };
  private readonly opened = new Map<string, number | string>();   // omp: instante; zeroclaw: sessionId
  available(): boolean { return this.opts.zeroclaw ? this.opts.zeroclaw.available() : this.now() - this.hostSeen <= HOST_PRESENCE_MS; }
  models(): … { return this.opts.zeroclaw?.models() ?? { provider: 'omp', models: [] }; }
  ```
  O caminho "empurrado" do ZeroClaw: `startZeroClaw` → `enqueueZeroClaw` (abre a sessão, espera o agente aparecer em
  `finishZeroClawStart`, entrega o pedido inicial com `deliver`) → `failZeroClaw`/`cleanupZeroClaw`; `syncZeroClaw` e
  `zeroClawEvent` mantêm `opened`; `dismiss` enfileira um `stop`; `tick` dispensa por ociosidade.
- **Três armadilhas para um segundo runtime na mesma instalação** (o código supõe que não existe):
  1. `poll()` (auxiliar do omp): `for (const cast of [...this.opened.keys()]) if (!open.includes(cast) &&
     !this.state(cast)) { this.opened.delete(cast); … }` apagaria todo personagem aberto que o auxiliar do omp não
     conhece.
  2. `tick()`: `if (!agent) { if (!this.opts.zeroclaw && !this.state(cast)) { this.opened.delete(cast); … } continue; }`.
  3. `dismiss()`, `openCount()`, `rolesDir()`, `setRoles()`, `start()`, `startNew()` decidem o caminho por
     `this.opts.zeroclaw`, não pelo personagem.
- `server/cast/zeroclaw.ts`: `ZeroClawCast` com `available()`, `models()`, `session(id)`, `openSessions()`,
  `observe(listener)`, `prepare(input)`, `open(session)`, `deliver(job, agentId)`, `dismiss(id)`. O texto inicial
  dele: `` `Instruções do personagem:\n${instructions}${prompt ? `\n\nPedido inicial:\n${prompt}` : ''}` ``.
- `server/index.ts:168-190` monta o `CastLauncher`. Rotas em `server/http/app.ts:553-582`: `GET /api/cast/models`
  devolve `launcher.models()`; `POST /api/cast/new` → `launcher.startNew(body)`; `POST /api/cast/:id/start` →
  `launcher.start(id, body)`; `POST /api/agents/:id/dismiss` → `launcher.dismiss(agent)`. Trava: `deps.terminal &&
  trusted(req, 'terminal')`.
- Convenções: servidor sem dependência de runtime; texto visível em `tr('…')` com tradução em
  `shared/locales/{en,es,fr}.ts`; `CHANGELOG.md` em `## [Não lançado]`; commits `feat(antigravity): …` em português;
  testes `*.test.ts` ao lado do código (Vitest); o `agy` falso dos testes é `server/antigravity/fake-agy.mjs`.

## O contrato novo (os planos 083, 080, 081 e 082 dependem dele; não mude nomes)

1. **Nome de modelo.** Um modelo cujo id começa com `agy/` roda no Antigravity. `agy/<valor>` usa o valor de modelo
   do `agy` (ex.: `agy/gemini-3.8-flash`); `agy/default` usa o padrão do `agy`. Qualquer outro id é do runtime da
   instalação, como hoje. A regra mora numa função pura em `shared/` (Step 1), usada por servidor e página.
2. **Personagem.** `CastMember.model` pode ser `agy/...`. Nesse caso: só `kind: 'main'`; `agent` e `account` são
   recusados; `instructions` e `idleMinutes` valem. Não existe campo novo no personagem.
3. **Chamar.** `POST /api/cast/:id/start`: o runtime vem do modelo efetivo (o `model` do corpo, em conversa nova,
   vence o do personagem). `POST /api/cast/new` com `model: "agy/..."` abre um "Novo" no Antigravity.
4. **Retomar.** Em `mode: "resume"` (ou `session` no "Novo"), o runtime é o da **conversa**: se existe o espelho
   `<dataDir>/antigravity/sessions/<sessão>.jsonl`, é do Antigravity; senão segue o caminho de hoje. O cliente não
   precisa informar nada.
5. **Catálogo.** `GET /api/cast/models` ganha, com o Antigravity ligado (`HABBLAUD_ANTIGRAVITY=1`):
   `"agyModels": ["agy/default", "agy/gemini-4-argon", "agy/gemini-3.8-flash", …]`. `agy/default` vem sempre
   primeiro; os demais só depois da primeira sessão do Antigravity nesta instalação. Sem o Antigravity, o campo não
   existe.
6. **Agente aberto.** `provider: 'antigravity'`, `cast`, `castOpened: true`, `model: "agy/<valor atual>"` e
   `models: ["agy/…", …]` (os do item 5, sem `agy/default`). Dispensar: `POST /api/agents/:id/dismiss`, com `202
   {job}`.
7. `/api/cast/jobs` e os estados (`queued`, `sent`, `waiting`, `done`, `failed`) não mudam.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Instalar (worktree nova) | `npm ci` | exit 0 |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes do recorte | `npx vitest run antigravity server/cast server/model/cast shared` | todos passam |
| Suíte | `npm test` | exit 0, ≥ 2857 passando |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**Criar**: `shared/agy-model.ts` (+ teste), `server/cast/antigravity.ts` (+ teste),
`server/sources/antigravity/catalog.ts` (+ teste).

**Editar**: `server/model/cast.ts` (validação; + teste), `server/cast/launcher.ts`, `server/cast/launcher.test.ts`
(só **acrescentar** casos), `server/antigravity/acp.ts` (+ teste e `fake-agy.mjs`),
`server/sources/antigravity/source.ts` (+ teste), `server/messages/antigravity.ts` (+ teste),
`server/model/office.ts` (só o laço do `castOpened`), `server/http/app.ts` (só o ramo do dismiss),
`server/index.ts`, `shared/locales/{en,es,fr}.ts`, `CHANGELOG.md`, `server/README.md`, `README.pt-BR.md`,
`README.md`.

**Out of scope** (NÃO toque):
- `client/**`, `android/**`, `zaplaud-rs/**`: planos 080, 081 e 082.
- Trocar o modelo ou a ferramenta de um agente **já aberto** e passar contexto: plano 083.
- `server/cast/zeroclaw.ts`, `server/cast/zeroclaw.test.ts`, `server/sources/zeroclaw/**`,
  `server/messages/zeroclaw.ts`: o ZeroClaw não muda; os testes dele não são editados.
- `scripts/codex-bridge.ts` (auxiliar do omp no host) e o protocolo `poll`/`ack`.
- `agyArgs` e `scripts/antigravity-relay.ts`: nenhuma flag nova.
- Advisor e subagente no Antigravity, agente fixo (`agent`), nível de raciocínio, histórico.
- Rodar o `agy` de verdade, `~/apps/habblaud`, `systemctl`, `podman`: fumaça e publicação são do revisor e do dono.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-079 -b advisor/079-elenco-antigravity producao`.
- Um commit por passo. Não faça push nem abra PR.

## Steps

### Step 1: a regra do nome, num lugar só

`shared/agy-model.ts`:

```ts
export const AGY_PREFIX = 'agy/';
export const AGY_DEFAULT = 'agy/default';
/** Modelo que roda no Antigravity CLI. `google-antigravity/...` (do omp) não é. */
export function isAgyModel(model: string | undefined): boolean
/** Valor a mandar ao agy; `undefined` para `agy/default` (usa o padrão dele). Só chame com isAgyModel verdadeiro. */
export function agyModelValue(model: string): string | undefined
export function toAgyModel(value: string): string   // 'gemini-3.8-flash' → 'agy/gemini-3.8-flash'
```

Teste: `agy/x` sim; `google-antigravity/x`, `Agy/x`, `agy`, `agy/`, `undefined` não; ida e volta; `agy/default`.

**Verify**: `npx vitest run shared/agy-model` passa.

### Step 2: cliente ACP leva personagem e modelo

Em `server/antigravity/acp.ts`:

- `open(input: { cwd: string; resume?: string; replaces?: string; cast?: string; model?: string })` (`model` é o
  **valor do `agy`**, sem prefixo).
- Depois do `session/new` **ou** do `session/resume`, se `model` veio: `session/set_config_option {sessionId,
  configId: 'model', value: model}`. Se falhar, fecha a sessão recém-aberta (`session/close`), **não** emite
  `session-open` e rejeita com `class UnknownModel extends Error` (exportada) quando o código é `-32602`.
- O evento `session-open` ganha `cast?: string` (repassado sem interpretar, como `replaces`); `info` ganha
  `modelOptions: { value: string; name: string }[]`, e `info.model` passa a ser o `currentValue` de
  `configOptions[id == 'model']` **depois** do `set_config_option`.
- `session(id)` e `sessions()` passam a devolver também `cast?: string` e `model?: string` (valor atual).

`server/antigravity/fake-agy.mjs`: `session/new` e `session/resume` devolvem `configOptions` com a opção `model`
(dois valores: `fake-a`, padrão, e `fake-b`); implementa `session/set_config_option` (valor fora da lista →
`-32602`, `Unknown model ID: <valor>`); `session/resume` devolve o modelo **padrão**, mesmo que a sessão tivesse
outro.

Testes novos em `acp.test.ts`: abrir com `model: 'fake-b'` → `session-open.info.model === 'fake-b'` e o falso
recebeu o `set_config_option` depois do `session/new`; modelo desconhecido → `UnknownModel`, nenhum `session-open`,
sessão fechada no falso; `resume` com `model` reaplica; `cast` chega no evento e em `session(id)`.

**Verify**: `npx vitest run server/antigravity/acp` → todos passam, os antigos sem edição.

### Step 3: catálogo aprendido; agente com `agy/…`

- `server/sources/antigravity/catalog.ts`: `class AntigravityCatalog` que guarda `{ values: string[] }` em
  `<dataDir>/antigravity/models.json` (pasta `0o700`, arquivo `0o600`, arquivo temporário + `rename`).
  `learn(modelOptions)` grava só se mudou; `agyModels()` devolve `[AGY_DEFAULT, ...values.map(toAgyModel)]`. Arquivo
  ilegível ou malformado = só `agy/default`, sem derrubar o servidor.
- `server/sources/antigravity/source.ts`: no `session-open` sem adoção, `office.addMain({ …, cast: event.cast })`
  quando `event.cast` vem (na adoção por `replaces`, o agente mantém o `cast`); chama `catalog.learn`; o
  `applyTranscript` passa `model: toAgyModel(info.model)` e `models: info.modelOptions.map(o => toAgyModel(o.value))`.
- Testes: catálogo aprende, persiste, relê, ignora corrompido; agente entra com `cast`, `model` e `models` com o
  prefixo.

**Verify**: `npx vitest run server/sources/antigravity` passa.

### Step 4: instruções junto da primeira mensagem

Em `server/messages/antigravity.ts` (`AntigravityDelivery`):

- `setInstructions(sessionId: string, instructions: string | undefined): void` guarda as instruções **pendentes**
  daquela sessão.
- `sendMessage(id, content)`: se há instruções pendentes, o texto mandado é
  `` `Instruções do personagem:\n${instructions}\n\nPedido inicial:\n${content}` `` e a pendência é consumida **só
  se o `client.prompt` aceitar**; se rejeitar, continua pendente.
- `newSession` ("conversa nova"): a sessão nova abre com o mesmo `cast` e o mesmo `model` da velha
  (`client.session(id)` os tem) e **rearma** as instruções do personagem para ela.
- Sessão fechada: a pendência dela é descartada.
- Testes: primeira mensagem leva as instruções, a segunda não; falha no `prompt` não consome; "conversa nova"
  preserva `cast` e modelo e rearma as instruções; nada é mandado ao `agy` enquanto ninguém manda mensagem (o falso
  não recebe `session/prompt`).

**Verify**: `npx vitest run server/messages/antigravity` passa.

### Step 5: `AntigravityCast`

`server/cast/antigravity.ts`, modelado em `server/cast/zeroclaw.ts` (leia-o inteiro antes):

```ts
export type AntigravityCastJob = { id: string; op: 'start' | 'stop'; cast: string;
  session: { sessionId?: string; cwd: string; model?: string; resume?: string }; instructions?: string; text?: string };
export class AntigravityCast {
  constructor(opts: { client: () => AntigravityClient | undefined;
    delivery: () => Pick<AntigravityDelivery, 'stopSession' | 'setInstructions'> | undefined;
    messages: () => Pick<MessageRegistry, 'send'> | undefined; catalog: AntigravityCatalog; home: string; dataDir: string });
  available(): boolean;                       // cliente presente (a conexão é preguiçosa: não exige processo aberto)
  agyModels(): string[];                      // catalog.agyModels()
  owns(sessionId: string): boolean;           // existe <dataDir>/antigravity/sessions/<sessionId>.jsonl
  session(id: string): { sessionId: string; cwd: string; busy: boolean; cast?: string } | undefined;
  openSessions(): { sessionId: string; cast?: string }[];
  observe(listener: (e: { type: 'session-open'; sessionId: string; cast?: string; replaces?: string }
                       | { type: 'session-close'; sessionId: string }) => void): boolean;
  prepare(input: { cast: string; room?: string; model?: string /* agy/… */; resume?: string; instructions?: string; prompt?: string }):
    AntigravityCastJob['session'] & { instructions?: string; text?: string };
  open(job: AntigravityCastJob): Promise<string>;   // devolve o sessionId
  deliver(job: AntigravityCastJob, agentId: string, sessionId: string): void;
  dismiss(sessionId: string): Promise<void>;        // cancela turno em curso e fecha
}
```

- `prepare`: pasta = `room ?? home`, recusada se tiver caractere de controle ou não for absoluta; `model` convertido
  com `agyModelValue` (não é validado contra o catálogo, que pode estar vazio ou velho: quem valida é o `agy`, no
  `open`); `instructions` (até 8000 caracteres) só em conversa nova; `text` = o pedido, **sem** as instruções.
- `open`: `client.open({ cwd, resume, cast, model })`; em seguida `delivery.setInstructions(sessionId,
  instructions)`. `UnknownModel` → erro com `tr('modelo do Antigravity desconhecido: {0}', [model])`; `AuthRequired`
  → a mensagem que o 078 já usa (`entre na conta do Antigravity no computador: rode agy e faça o login`; reaproveite
  a mesma chave).
- `deliver`: só quando há `text`: `messages.send({ agentId, text, expectedSessionId: sessionId })` (as instruções
  entram pelo Step 4); erro vira exceção, como no ZeroClaw. Sem `text`, não manda nada.
- `dismiss`: `delivery.stopSession(id)` se há turno, depois `client.close(id)`.

Testes com `AntigravityClient` real sobre o `fake-agy.mjs`: abre com modelo; pedido inicial chega com as instruções
na frente; **sem pedido, o falso não recebe `session/prompt`**; retomar reaplica o modelo e não rearma instruções;
modelo desconhecido e sem login dão os erros acima; `dismiss` cancela um turno em curso e fecha; `owns` segue o
espelho.

**Verify**: `npx vitest run server/cast/antigravity` passa.

### Step 6: validação do personagem

`server/model/cast.ts`: se `isAgyModel(model)`: só com `kind === 'main'`, sem `agent` e sem `account` (senão
`null`). Nada muda para os outros modelos. Em `server/http/app.ts:373` há uma limpeza ao mudar `kind`: não acrescente
nada ali; a validação já recusa a combinação inválida. Testes (acrescente): aceita `agy/x` e `agy/default`; recusa
com `advisor`, com `agent`, com `account`; `google-antigravity/x` continua sendo modelo comum.

**Verify**: `npx vitest run server/model/cast` passa.

### Step 7: o lançador escolhe o runtime pelo modelo

Em `server/cast/launcher.ts`:

- `Options.antigravity?: AntigravityCast`; `Entry` ganha `{ runtime: 'antigravity'; job: AntigravityCastJob }`.
- **De quem é cada personagem aberto.** Acrescente `private readonly openedRuntime = new Map<string,
  'antigravity'>()` (ou equivalente). Toda vez que um `cast` sai de `opened`, sai daqui também.
- **Escolha do caminho**, numa função privada única, usada por `start` e `startNew`:
  - conversa nova: `isAgyModel(modelo do corpo ?? modelo do personagem)`;
  - retomar: `opts.antigravity?.owns(sessão)`.
  Dando Antigravity: vai para `startAntigravity(...)` **antes** de qualquer ramo `this.opts.zeroclaw`/omp; sem
  `opts.antigravity` → `503` com `tr('Antigravity desligado neste Habblaud')`. As validações comuns de hoje (modo,
  sessão, pedido, personagem ocupado, limite `max`) continuam valendo e vêm antes. `validModel` passa a aceitar
  `agy/…` em conversa nova, sem mudar o resultado para omp e ZeroClaw. Personagem `advisor`/`sub` com modelo `agy/…`
  no corpo → `422`.
- **O caminho.** Espelhe o do ZeroClaw (`enqueueZeroClaw` → abrir → `waiting` → agente aparece → `deliver` → `done`;
  falha → limpar; prazo de 60 s), com estas diferenças: o `sessionId` só existe depois do `open` — guarde-o na
  entrada quando o `open` resolver e só então `opened.set(cast, sessionId)`; o agente a esperar é `provider ===
  'antigravity' && sessionId === <o da entrada> && cast === job.cast`. **Prefira generalizar** os métodos privados
  do ZeroClaw para receberem um adaptador comum em vez de copiá-los; se a generalização exigir mexer em qualquer
  afirmação de `server/cast/launcher.test.ts` ou `zeroclaw.test.ts`, copie.
- **`dismiss(agent)`**: se o `cast` do agente é do Antigravity, enfileira o `stop` do Antigravity e devolve `{ job
  }`; os outros ramos ficam como estão.
- **As três armadilhas de "Current state"**: em `poll()`, o laço que apaga de `opened` o que o auxiliar do omp não
  relatou **pula** os `cast` do Antigravity; em `tick()`, a limpeza `if (!agent) …` e a dispensa por ociosidade
  tratam o `cast` do Antigravity pelo adaptador dele (sessão existe? `busy`?), nunca pelo ramo do omp; `openCount()`
  conta os runtimes juntos (o limite `max` é da instalação).
- **"Conversa nova"** (`session-open` com `replaces`): o lançador troca o `sessionId` de `opened` para o novo,
  mantendo o `cast`. O `session-close` da sessão velha não tira o personagem.
- **Queda do `agy`** (`session-close` de todas as sessões): os `cast` do Antigravity saem de `opened`.
- `models()`: devolve o que devolve hoje **mais** `agyModels: antigravity.agyModels()` quando `opts.antigravity`
  existe. `rolesDir()`/`setRoles()` (papéis do omp) não mudam.
- `server/model/office.ts`, laço do `castOpened`: agente `antigravity` **com** `cast` segue a regra geral
  (`launcher.isOpened(a.cast)`); **sem** `cast` (aberto pelo "Novo agente" do 078) continua marcado como hoje.
- `server/http/app.ts`, ramo do dismiss: agente `antigravity` **com** `cast` aberto no lançador vai para
  `launcher.dismiss(agent)` (`202 {job}`); sem `cast`, o atalho do 078 (`client.close`) continua.
- `server/index.ts`: passa `antigravity: new AntigravityCast({...})` ao `CastLauncher` quando `config.antigravity`.

Testes novos em `server/cast/launcher.test.ts` (modelo: os casos do ZeroClaw no mesmo arquivo), com
`AntigravityCast` sobre o `fake-agy.mjs` e **o omp como runtime da instalação** (sem `zeroclaw`):

1. Personagem com `model: 'agy/fake-b'` abre sem o auxiliar do omp presente; o job vai de `queued` a `done`; o
   agente tem `cast`, `castOpened`, `model: 'agy/fake-b'`.
2. Chamar com pedido: a primeira mensagem leva instruções + pedido. Chamar sem pedido: nenhum `session/prompt`.
3. `model: 'agy/fake-b'` no corpo vence um personagem do omp (abre no Antigravity); `model` do omp no corpo vence um
   personagem `agy/…` (vai para o caminho do omp).
4. `startNew({ model: 'agy/default' })` cria `novo-…` no Antigravity, com o modelo padrão do falso.
5. Um `poll({ open: [] })` do auxiliar do omp **não** tira o personagem do Antigravity de `opened`; um personagem
   do omp aberto ao mesmo tempo continua sendo tirado quando o auxiliar deixa de relatá-lo.
6. Dispensar fecha a sessão e o job chega a `done`; dispensa por ociosidade (`idleMinutes`) funciona; não dispensa
   com turno em curso.
7. "Conversa nova" mantém o personagem aberto e dispensável.
8. Limite `max: 1` com um personagem do omp aberto recusa o do Antigravity com `409`.
9. Retomar (`mode: 'resume'`) uma sessão que tem espelho vai para o Antigravity **mesmo que o personagem hoje tenha
   modelo do omp**, e reaplica o modelo `agy/…` se o personagem tiver um; retomar uma sessão sem espelho segue o
   caminho de hoje.
10. `models()` traz `agyModels` começando por `agy/default`.
11. Modelo desconhecido → job `failed` com o texto do erro; nada fica em `opened`.
12. Sem `opts.antigravity`, modelo `agy/…` → `503`.

**Verify**: `npx vitest run server/cast` → todos passam; `git diff --stat producao -- server/cast/zeroclaw.test.ts`
vazio; nenhum teste antigo de `launcher.test.ts` foi editado (`git diff producao -- server/cast/launcher.test.ts |
grep -c '^-[^-]'` → `0`).

### Step 8: documentação

`CHANGELOG.md` (`## [Não lançado]`): modelos `agy/…` no elenco e no "Novo", instruções junto da primeira mensagem,
catálogo aprendido, e a diferença para `google-antigravity/…`. `server/README.md`: a seção "O contrato novo".
`README.pt-BR.md`/`README.md`: uma frase na seção do Antigravity. Traduções de todo `tr()` novo.

**Verify**: `npm run typecheck && npm run i18n:check -- --strict && npm run build && npm test` → exit 0.

## Test plan

Listado nos passos. O essencial: o runtime sai do **modelo** (ou da conversa, ao retomar); omp e Antigravity
convivem sem um apagar o estado do outro; nada é mandado ao `agy` sem uma mensagem da pessoa; o modelo é aplicado ao
abrir e ao retomar. O `agy` de verdade não roda em teste.

## Done criteria

- [ ] `npm run typecheck` exit 0; `npm test` exit 0 com mais de 2857 testes; `npm run i18n:check -- --strict` e
      `npm run build` exit 0
- [ ] `git diff --stat producao -- server/test/antigravity-mecanica.test.ts` vazio e o teste passa
- [ ] `git diff --stat producao -- server/cast/zeroclaw.ts server/cast/zeroclaw.test.ts server/sources/zeroclaw server/messages/zeroclaw.ts client android zaplaud-rs scripts` vazio
- [ ] `git diff producao -- server/cast/launcher.test.ts | grep -c '^-[^-]'` → `0`
- [ ] `git diff producao -- shared/types.ts | grep -c "runtime"` → `0` (nenhum campo novo no personagem)
- [ ] `grep -rn "google-antigravity" server shared --include=*.ts | grep -v "\.test\.ts" | grep -vc "agy-model.ts"` não
      cresceu em relação a `producao`
- [ ] `git status --short` limpo

## STOP conditions

- Um trecho de "Current state" não bate com o código.
- Fazer omp e Antigravity conviverem exigir mudar `scripts/codex-bridge.ts`, o protocolo `poll`/`ack` ou um teste
  existente do lançador ou do ZeroClaw.
- Qualquer passo parecer exigir flag nova no `agy`, leitura de `~/.gemini`, ou mandar um turno sem mensagem da
  pessoa.
- `member.last` não for gravado para agentes `antigravity` com `cast` (confira `server/model/office.ts:905`): relate
  antes de mexer no `office.ts` além do laço do `castOpened`.
- Uma verificação falhar duas vezes depois de uma tentativa razoável.

## Maintenance notes

- Fumaça do revisor com o `agy` real (poucos turnos, na conta do dono, com autorização dele): salvar um personagem
  com `model: "agy/gemini-3.8-flash"` por `PUT /api/cast/:id`, chamar sem pedido (nenhum turno), mandar uma mensagem
  (instruções na frente), conversa nova, dispensar, retomar; um personagem do omp aberto ao mesmo tempo não é afetado.
- `agy/default` existe para a primeira vez: o catálogo só enche depois de uma sessão.
- No modo soquete (produção do dono) há **um** `agy acp` para todas as sessões, e o relé aceita uma conexão por
  vez: nada neste plano abre uma segunda conexão.
- Quem revisar olha primeiro: as três armadilhas no `launcher.ts`; que `agyArgs` não mudou; que sem pedido nenhum
  `session/prompt` sai; que `google-antigravity/…` nunca é tratado como `agy/…`.
- O plano 083 troca o modelo e a ferramenta de um agente aberto e passa o contexto.
