# Plan 078: O Habblaud conversa com o Antigravity CLI (`agy`) pelo ACP: abrir, mandar mensagem, aprovar, parar, dispensar

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat d7f9e4c..HEAD -- shared/types.ts server/config.ts server/index.ts server/http/app.ts server/messages/registry.ts server/permissions/registry.ts server/model/office.ts client/src/ui/provider.ts client/src/ui/widgets.ts client/src/ui/spawn.ts client/src/ui/styles.css package.json`
> Se algum mudou, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: MED no código (mexe em `messages/registry.ts` e `permissions/registry.ts`); risco de conta descrito em
  "Regras de mecânica"
- **Depends on**: plans/077-spike-agy-pelo-acp.md (DONE; resultado em `plans/077-resultado.md`)
- **Category**: direction
- **Planned at**: commit `d7f9e4c`, 2026-11-14

## Why this matters

O dono usa o Antigravity CLI (`agy`, do Google) e quer conversar com ele pelo escritório como faz com o omp: abrir um
agente, mandar mensagens, aprovar ou negar comandos, parar um turno, começar conversa nova, dispensar. Decisão dele:
um mecanismo só, o **ACP** (`agy acp`: o `agy` roda como subprocesso e fala JSON-RPC por stdin/stdout). O spike 077
mediu no `agy` 1.5.9 que isso funciona. Este plano liga o ACP ao Habblaud: uma fonte de agentes nova (`antigravity`),
entrega de mensagens, pedidos de permissão no cartão do escritório, e a entrada "Novo agente" na página.

## Regras de mecânica (não negociáveis)

O dono não quer correr risco de bloqueio da conta Google. Termos do Antigravity (<https://antigravity.google/terms>, item 6):
"Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity OAuth) is
a breach of this Agreement." A documentação do `agy` descreve o ACP para "any editor that supports the Agent Client
Protocol" e "Other clients"; que isso torne um cliente próprio compatível com o item 6 é leitura, não garantia. As
regras abaixo reduzem a exposição e **nenhuma é disfarce**. Um teste (Step 9) prende as que são de código.

1. Só o binário oficial `agy` fala com o Google. O Habblaud fala com o `agy` **só** por stdin/stdout de `agy acp`
   (direto, ou por um soquete Unix local que o relé do host liga a esse stdin/stdout).
2. O Habblaud nunca lê, copia, monta nem envia credenciais do `agy`: nenhum código deste plano abre arquivo sob
   `~/.gemini/`, e o quadlet do Habblaud **não** monta `~/.gemini`.
3. A linha de comando do `agy` é fixa: `agy acp`, mais `--dangerously-skip-permissions` **só** quando o dono liga
   `HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS=1`. Nenhuma outra flag, nunca: em especial `--override_user_agent_name`,
   `--cloud_code_endpoint`, `--disable-telemetry`, `--disable_telemetry`, `--is_google3_workspace`,
   `--is_google_internal`, `--gemini-dir`, `--gemini_dir`, `--app-data-dir`, `--app_data_dir`. Nenhum argumento vem de
   pedido HTTP nem do soquete.
4. O cliente se identifica com o nome verdadeiro: `clientInfo: { name: 'habblaud', version: <versão do Habblaud> }`.
5. Sem a flag do item 3, toda permissão é respondida por uma pessoa no escritório. O Habblaud nunca responde
   "permitir" sozinho.
6. Desligado por padrão (`HABBLAUD_ANTIGRAVITY=1` liga). Nunca nos tenants do SaaS.
7. Nada de laço automático de prompts: cada `session/prompt` nasce de uma mensagem que uma pessoa mandou.
8. Se o `agy` devolver erro de autenticação, o Habblaud mostra "entre na conta com `agy`" e para. Não chama
   `authenticate`, não abre navegador, não tenta outro método.

## Current state

Baseline em `d7f9e4c`: `npm run typecheck` ok; `npm test` 204 arquivos, 2806 testes passando, 7 pulados.

### O protocolo, medido no `agy` 1.5.9 (cópia do essencial de `plans/077-resultado.md`)

Uma mensagem JSON por linha. O cliente manda `initialize`, depois usa:

| Método | Parâmetros | Resposta / efeito |
|---|---|---|
| `initialize` | `{protocolVersion: 1, clientCapabilities: {fs: {readTextFile: false, writeTextFile: false}, terminal: false}, clientInfo}` | `agentInfo.version`, `agentCapabilities.sessionCapabilities` (`resume`, `close`, `load`, `list`) |
| `session/new` | `{cwd, mcpServers: []}` | `{sessionId (UUID), modes, models: {currentModelId, availableModels[]}, configOptions[], _meta.antigravity.tier.name}` |
| `session/resume` | `{sessionId, cwd, mcpServers: []}` | mesmo corpo; não reenvia histórico; o contexto continua |
| `session/prompt` | `{sessionId, prompt: [{type: 'text', text}]}` | só responde no fim do turno: `{stopReason: 'end_turn' \| 'cancelled', usage: {inputTokens, outputTokens, ...}}` |
| `session/cancel` (aviso, sem `id`) | `{sessionId}` | o `session/prompt` em curso responde `stopReason: 'cancelled'` |
| `session/close` | `{sessionId}` | `{}` |
| `session/list` | `{}` | `{sessions: [{sessionId, cwd, title, updatedAt}]}`; o filtro `cwd` **não filtra** |

Avisos `session/update` (`params: {sessionId, update}`), pelo campo `update.sessionUpdate`:

- `user_message_chunk`, `agent_message_chunk`, `agent_thought_chunk`: `content: {type: 'text', text}`.
- `tool_call` e `tool_call_update`: `toolCallId`, `name` (ex.: `run_command`), `title` (ex.: o comando), `kind`
  (`execute`), `status` (`in_progress` → `pending` enquanto espera permissão → `in_progress` → `completed` com
  `rawOutput.output`, ou `failed`), `rawInput` (`CommandLine`, `toolSummary`).
- `session_info_update`: `title`. `usage_update`: `used`, `size` (tokens do contexto e janela).
- `available_commands_update`: ignorar.

Pedido **do agente para o cliente** (tem `id`; o turno fica parado até a resposta):

```json
{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"…",
 "toolCall":{"toolCallId":"call_53…","name":"run_command","status":"pending","kind":"execute",
   "title":"echo spike-a > a.txt","rawInput":{"CommandLine":"echo spike-a > a.txt","toolSummary":"Write spike-a to a.txt"}},
 "options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}
```

Respostas: `{"outcome":{"outcome":"selected","optionId":"<id da opção cujo kind é allow_once ou reject_once>"}}`; ao
cancelar o turno, `{"outcome":{"outcome":"cancelled"}}` para cada pedido pendente. Escolha a opção pelo **`kind`**,
nunca pelo texto do `optionId`. Outros pedidos do agente (`fs/*`, `terminal/*`) recebem erro `-32601`.

Outros fatos medidos: uma conexão serve várias sessões e turnos simultâneos; cada processo custa ~179 MiB e ~25 MiB
por sessão a mais; fechar o stdin encerra o processo em menos de 1 s, mesmo no meio de um turno; sem login,
`session/new` devolve `{"code": -32000, "message": "Authentication required"}`; com
`--dangerously-skip-permissions`, nenhum `session/request_permission` chega; **não há** campo de cota.

### O Habblaud deste fork

- **Onde o servidor roda.** No computador do dono o servidor roda num contêiner Podman só leitura
  (`deploy/podman/habblaud.container`), que monta `~/.habblaud` (rw), `~/.omp`, o binário do omp e
  `~/Desenvolvimento` (ro), com `Network=host` e `UserNS=keep-id`. O `agy` e o login dele ficam **fora**. Por isso o
  servidor tem dois modos de alcançar o `agy`: abrir o processo (modo direto: `npm start`, desenvolvimento) ou
  conectar num soquete Unix dentro de `~/.habblaud/` que um relé no host liga a um `agy acp` (modo soquete).
- `shared/types.ts:83`: `export type Provider = 'claude' | 'codex' | 'omp' | 'zeroclaw';`
- `server/sources/source.ts:19-32`, o contrato de uma fonte:
  ```ts
  export interface AgentSource {
    readonly provider: Provider;
    start(): void | Promise<void>;
    stop(): void;
    sources(): SourceInfo[];
    transcriptPathOf(agentId: string): string | undefined;
    terminalParser?(agentId: string): TerminalParser | undefined;
  }
  ```
- **Exemplar de fonte "empurrada" (leia antes de escrever a sua):** `server/sources/zeroclaw/source.ts` (215 linhas)
  usa `office.addMain`, `office.setStatus`, `office.addActivity`, `office.applyTranscript`, `office.closeMain`,
  `office.switchSession`. `server/model/office.ts:111-123`:
  ```ts
  export interface MainInput { id: string; provider?: Provider; account: string; sessionId: string; cwd: string;
    role: string; cast?: string; startedAt: number; status: AgentStatus; waitingFor?: string; }
  ```
  e `TranscriptSummary` (`office.ts:95-109`) tem `title`, `model`, `models`, `context: {tokens, window}`.
- **Terminal da página.** O texto da conversa chega ao painel de terminal por um arquivo JSONL que a fonte aponta em
  `transcriptPathOf`. O ZeroClaw grava o dele em `<dataDir>/zeroclaw/sessions/<sessionId>.jsonl`
  (`server/sources/zeroclaw/mirror.ts`), no formato do transcript do Claude Code: linhas
  `{type: 'user', message: {role: 'user', content}}`, `{type: 'assistant', message: {id, role: 'assistant',
  content: [{type: 'text', text} | {type: 'thinking', thinking} | {type: 'tool_use', id, name, input}]}}` e
  `{type: 'user', message: {content: [{type: 'tool_result', tool_use_id, content}]}}`. **Abra `mirror.ts` e copie a
  forma exata das linhas** (campos como `uuid`, `timestamp`, `sessionId`), para o leitor existente aceitar.
- **Contas.** Uma fonte registra a própria conta com `accounts.setProviderAccounts(provider, [{dir, detected: {id,
  provider, configDir, short, name, color}}])` (`server/accounts/service.ts`; uso real em
  `server/sources/zeroclaw/source.ts`).
- **Mensagens.** `server/messages/registry.ts` (789 linhas) tem um caminho "empurrado" só para o ZeroClaw:
  `MessageRegistryOptions.zeroclaw?: ZeroClawDelivery` (linha 91), entradas marcadas `zeroclaw?: true` (linha 145),
  `pumpZeroClaw()` (linhas 673-708), `zeroClawReason`, `updateZeroClawPresence`, o ramo de `control()` (linhas
  418-441) e `canMessage` (linha 332). O que o registro usa do entregador: `onEvent(listener)`, `available(id,
  control?)`, `busy(id)`, `continuityLost(id)`, `sendMessage(id, content)`, `stopSession(id)`, `newSession(id,
  officeAgentId?)` (`server/messages/zeroclaw.ts`). O trecho central:
  ```ts
  if (!delivery.available(e.target, !!e.control)) continue;
  if (!e.control && !steer && delivery.busy(e.target)) continue;
  this.setStatus(e, 'sent', this.now());
  const work = e.control
    ? (e.control.stop ? delivery.stopSession(e.target) : delivery.newSession(e.target, agent.id)).then(() => ({ ok: true as const }))
    : delivery.sendMessage(e.target, e.text);
  ```
- **Permissões.** `server/permissions/registry.ts` já tem API em processo: `register(raw): RegisterResult`
  (`{id, expiresAt} | {skip: 'no-viewers' | 'unknown-session' | 'unsupported-tool' | 'too-many'}`),
  `wait(id, ms): {result: Promise<WaitResult>, cancel} | undefined` e
  ```ts
  export type WaitResult =
    | { status: 'pending' }
    | { status: 'decided'; behavior: 'allow' | 'deny'; message?: string; interrupt?: boolean; suggestion?: number }
    | { status: 'decided'; behavior: 'answer'; answers: PermissionAnswer[] }
    | { status: 'released'; reason: ReleaseReason };
  ```
  `register` recebe o formato do hook: `{provider?, session_id, tool_name, tool_input, cwd?, timeout_ms?}`.
  `parseHookInput` (linhas 280-309) só aceita `provider` `'codex' | 'omp'`; `register` (linhas 410-470) resolve o
  agente com `resolveOmpAgent(req.sessionId)` para o omp e trata `codex || omp` como `cli` (`hookOnly`, sem sugestões,
  sem procurar a resposta no transcript). Um pedido sem ninguém esperando há 8 s (`ORPHAN_MS`) é descartado: quem
  registra precisa chamar `wait` em seguida e de novo a cada resposta `pending`.
- **Dispensar.** O botão da gaveta aparece quando `AgentInfo.castOpened` é verdadeiro e chama
  `POST /api/agents/:id/dismiss` (`client/src/ui/drawer.ts:625,364`, `server/http/app.ts:553-573`).
  `server/model/office.ts:1046`:
  `for (const a of real) if (a.kind === 'main' && a.cast && launcher?.isOpened(a.cast)) a.castOpened = true;`
- **Novo agente.** O popover `client/src/ui/spawn.ts` (400 linhas) escolhe a pasta e o pedido e chama
  `POST /api/spawn` (`submit()`, linhas 343-398). No servidor a rota exige `trusted(req, 'spawn')`
  (`server/http/app.ts:679-708`).
- **Cliente, por ferramenta** (`client/src/ui/provider.ts`): `runtimeAllowsMessages` (linha 12),
  `runtimeAllowsControl` (linha 16), `runtimeAllowsDeliveryModes` (linha 20), `PROVIDER_NAME: Record<Provider,
  string>` com `unbrand(...)` (linha 73), `accountProvider` (linha 102), `fallbackShort` (linha 113).
  `client/src/ui/widgets.ts:61` (título do selo), `client/src/ui/styles.css:344,364` (chip vazado do Codex).
- **Travas HTTP.** `trusted(req, feature?)` e `denied(req, msg)` em `server/http/app.ts:318-321`; modelo de rota local:
  a do Codex (linhas 628-636).
- **Config.** `server/config.ts`: campos por ferramenta na interface (linhas 84-97) e em `loadConfig` (linha 334:
  `codex: !env.HABBLAUD_CODEX?.trim() || isTruthy(env.HABBLAUD_CODEX),`). `config.home`, `config.dataDir`,
  `config.inDocker` existem.
- **Convenções** (`AGENTS.md`, `CONTRIBUTING.md`): servidor **sem dependência de runtime** (só `node:*`); todo texto
  visível em português dentro de `tr('...')` com tradução em `shared/locales/{en,es,fr}.ts`
  (`npm run i18n:check -- --strict`); mudança visível entra em `## [Não lançado]` do `CHANGELOG.md`; mudança de
  comportamento que os clientes Zaplaud não recebem vira pendência em `docs/zaplaud-paridade.md`; commits como
  `feat(zeroclaw): ...`, em português; testes `*.test.ts` ao lado do código, com Vitest.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Instalar (worktree nova) | `npm ci` | exit 0 |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes do recorte | `npx vitest run antigravity` | todos passam |
| Suíte | `npm test` | exit 0, ≥ 2806 passando |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**Criar**:
- `server/antigravity/acp.ts` — transporte e cliente ACP (processo ou soquete)
- `server/antigravity/acp.test.ts`
- `server/antigravity/fake-agy.mjs` — `agy acp` falso para os testes (só `node:*`)
- `server/antigravity/permissions.ts` — ponte pedido ACP → `PermissionRegistry`
- `server/antigravity/permissions.test.ts`
- `server/antigravity/http.ts` — rotas `/api/antigravity/*`
- `server/antigravity/http.test.ts`
- `server/sources/antigravity/source.ts`, `server/sources/antigravity/mirror.ts` e os `.test.ts` deles
- `server/messages/antigravity.ts` e `server/messages/antigravity.test.ts`
- `server/test/antigravity-mecanica.test.ts`, `server/test/config-antigravity.test.ts`
- `scripts/antigravity-relay.ts` e `server/test/antigravity-relay.test.ts`
- `deploy/podman/habblaud-antigravity.service`
- `client/src/ui/provider-antigravity.test.ts`

**Editar**: `shared/types.ts`, `server/config.ts`, `server/index.ts`, `server/http/app.ts`,
`server/messages/registry.ts`, `server/permissions/registry.ts`, `server/model/office.ts` (só a linha do
`castOpened`), `client/src/ui/provider.ts`, `client/src/ui/widgets.ts`, `client/src/ui/styles.css`,
`client/src/ui/spawn.ts`, `client/src/net/store.ts` (só se precisar de um método para as rotas novas),
`shared/locales/{en,es,fr}.ts`, `package.json` (scripts), `deploy/podman/habblaud.container` (só comentário e uma
linha `Environment` comentada), `CHANGELOG.md`, `README.pt-BR.md`, `README.md`, `server/README.md`,
`docs/zaplaud-paridade.md`.

**Out of scope** (NÃO toque):
- `server/cast/**` (elenco): o Antigravity entra pelo "Novo agente", não pelo elenco. Personagens fixos do elenco
  com Antigravity ficam para depois.
- `server/sources/zeroclaw/**`, `server/messages/zeroclaw.ts`: são exemplares; não mudam.
- `server/spawn/**`, `server/pty/**`, `server/world/**`, `shared/world/**`.
- `android/**`, `zaplaud-rs/**`: só a pendência em `docs/zaplaud-paridade.md`.
- `~/apps/habblaud` (produção), `~/.config/containers/systemd/`, `~/.gemini/`, qualquer `systemctl`: instalar é do dono.
- Histórico (`HistoryProvider`), cota, troca de modelo, anexos, "steer", subagentes do `agy`.
- Rodar o `agy` de verdade: os testes usam `fake-agy.mjs`. A fumaça com o `agy` real é do revisor.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-078 -b advisor/078-antigravity-acp producao`.
- Um commit por passo, estilo `feat(antigravity): cliente ACP por processo ou soquete`.
- Não faça push nem abra PR.

## Steps

### Step 1: tipo e configuração

- `shared/types.ts`: `Provider` ganha `| 'antigravity'`, com a linha de comentário
  `- antigravity: Antigravity CLI (\`agy\`), conduzido pelo protocolo ACP (\`agy acp\`).`
- `server/config.ts`, interface e `loadConfig`:
  - `antigravity: boolean` — `isTruthy(env.HABBLAUD_ANTIGRAVITY)` (**desligado por padrão**).
  - `antigravityBin: string` — `env.HABBLAUD_ANTIGRAVITY_BIN?.trim() || 'agy'`.
  - `antigravitySocket?: string` — `env.HABBLAUD_ANTIGRAVITY_SOCKET?.trim()`; se preenchido precisa ser caminho
    absoluto, senão `loadConfig` lança erro com mensagem em `tr()`.
  - `antigravitySkipPermissions: boolean` — `isTruthy(env.HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS)`.
- `server/test/config-antigravity.test.ts` (modelo: `server/test/config-zeroclaw.test.ts`): desligado sem a variável;
  ligado com `1`; soquete relativo lança; `skipPermissions` só com `1`.

**Verify**: `npx vitest run config-antigravity` passa. `npm run typecheck` falha **só** onde falta `antigravity` em
`Record<Provider, …>` do cliente (corrigido no Step 7).

### Step 2: cliente ACP (`server/antigravity/acp.ts`)

Só `node:child_process`, `node:net`, `node:readline`, `node:events`. Exporte:

```ts
/** A linha de comando inteira do agy. Nada além disto pode chegar ao binário (regra 3). */
export function agyArgs(opts: { skipPermissions: boolean }): string[]  // ['acp'] ou ['acp', '--dangerously-skip-permissions']

export type AcpEvent =
  | { type: 'session-open'; sessionId: string; cwd: string; resumed: boolean; info: SessionInfo }
  | { type: 'session-close'; sessionId: string; reason: 'closed' | 'process-exit' }
  | { type: 'update'; sessionId: string; update: Record<string, unknown> }
  | { type: 'turn-state'; sessionId: string; busy: boolean; stopReason?: string }
  | { type: 'permission'; sessionId: string; requestId: number | string; toolCall: Record<string, unknown>; options: { optionId: string; kind: string; name: string }[] }
  | { type: 'permission-gone'; sessionId: string; requestId: number | string }
  | { type: 'diagnostic'; message: string };

export interface SessionInfo { model?: string; models: string[]; tier?: string }

export class AntigravityClient {
  constructor(opts: { bin: string; socket?: string; skipPermissions: boolean; version: string; env?: NodeJS.ProcessEnv;
    connect?: () => Duplex /* testes */ });
  onEvent(listener: (e: AcpEvent) => void): () => void;
  /** Abre (ou retoma, com `resume`) uma sessão; liga o processo se for a primeira. Rejeita com AuthRequired no -32000. */
  open(input: { cwd: string; resume?: string }): Promise<{ sessionId: string }>;
  session(id: string): { sessionId: string; cwd: string; busy: boolean } | undefined;
  sessions(): { sessionId: string; cwd: string; busy: boolean }[];
  /** Começa um turno; resolve quando o agy aceita a linha (não quando o turno acaba). Rejeita se já há turno. */
  prompt(sessionId: string, text: string): Promise<void>;
  /** session/cancel + resposta `cancelled` a todo pedido de permissão pendente da sessão; resolve quando o turno termina. */
  cancel(sessionId: string): Promise<void>;
  answerPermission(sessionId: string, requestId: number | string, kind: 'allow_once' | 'reject_once'): boolean;
  close(sessionId: string): Promise<void>;
  list(): Promise<{ sessionId: string; cwd: string; title?: string; updatedAt?: string }[]>;
  stop(): void;
}
export class AuthRequired extends Error {}
```

Comportamento:

- Conexão preguiçosa: no primeiro `open` (ou `list`), se `socket` está definido, `net.connect(socket)`; senão
  `spawn(bin, agyArgs({skipPermissions}), { stdio: ['pipe', 'pipe', 'ignore'], env })`. Nunca `shell: true`. Em
  seguida `initialize` com `clientInfo: { name: 'habblaud', version }`.
- Uma conexão para todas as sessões. Quando a última sessão fecha, encerra a conexão (fecha o stdin ou o soquete).
- Processo ou soquete que cai: emite `session-close` com `reason: 'process-exit'` para cada sessão, rejeita os
  pedidos pendentes, e a próxima chamada reconecta.
- `prompt`: marca `busy`, emite `turn-state {busy: true}`, manda `session/prompt`; quando a resposta chega (ou
  falha), emite `turn-state {busy: false, stopReason}`.
- `session/request_permission`: guarda o `id` JSON-RPC por sessão e emite `permission`. `answerPermission` escolhe
  a opção cujo `kind` bate e responde `{outcome: {outcome: 'selected', optionId}}`; devolve `false` se o pedido já
  não existe ou se não há opção com esse `kind`. Pedido que deixa de existir (turno cancelado, sessão fechada,
  conexão caída) emite `permission-gone`.
- Qualquer outro pedido vindo do agente (mensagem com `id` e `method`) recebe erro `-32601`.
- Linha que não é JSON, ou acima de 8 MiB: descarta e emite `diagnostic`. Tempo limite de 30 s para `initialize`,
  `session/new`, `session/resume`, `session/close`, `session/list`; **sem** tempo limite para `session/prompt`.
- `list` filtra nada (o `agy` ignora `cwd`); quem chama filtra.

`server/antigravity/fake-agy.mjs`: lê linhas JSON do stdin e responde como o `agy` medido. Comandos por prompt, para
os testes dirigirem: texto `ok` → um `agent_message_chunk` e `end_turn`; texto começando com `tool:` → `tool_call`,
`tool_call_update` `pending`, `session/request_permission` com as duas opções, e depois `completed` ou `failed`
conforme a resposta; texto `sleep` → fica em turno até receber `session/cancel` e responde `cancelled`. Com
`FAKE_AGY_AUTH=0`, `session/new` devolve `-32000 Authentication required`. Com `--dangerously-skip-permissions` em
`process.argv`, o `tool:` roda sem pedir. Grava `process.argv.slice(2)` em `FAKE_AGY_ARGV_FILE` se definido. Sai
quando o stdin fecha.

`server/antigravity/acp.test.ts`, com o falso (`bin: process.execPath` não serve porque `agyArgs` não leva o caminho
do script: use `connect` injetando um `Duplex` ligado a `spawn(process.execPath, [fake])`, e **um** teste do caminho
de processo de verdade com um executável temporário `#!/usr/bin/env node` que importa o falso):

1. `agyArgs` devolve exatamente `['acp']` e `['acp', '--dangerously-skip-permissions']`.
2. Abre sessão, manda `ok`, recebe `update` com `agent_message_chunk` e `turn-state` `busy` true depois false.
3. Duas sessões na mesma conexão recebem os avisos com o `sessionId` certo.
4. `tool:` → evento `permission`; `answerPermission(…, 'allow_once')` → `tool_call_update` `completed`;
   `'reject_once'` → `failed`.
5. `cancel` com pedido pendente: o falso recebe `session/cancel` **e** a resposta `cancelled`; `permission-gone` sai.
6. `FAKE_AGY_AUTH=0` → `open` rejeita com `AuthRequired`.
7. Fechar a última sessão encerra o processo (o falso sai) e nenhum filho sobra.
8. Matar o processo no meio → `session-close` com `process-exit` e `turn-state` `busy: false`.
9. O executável temporário recebe exatamente `acp` (lido de `FAKE_AGY_ARGV_FILE`), e com `skipPermissions`
   exatamente `acp --dangerously-skip-permissions`.

**Verify**: `npx vitest run server/antigravity/acp` → 9 testes passam.

### Step 3: fonte e espelho (`server/sources/antigravity/`)

- `mirror.ts`: grava `<dataDir>/antigravity/sessions/<sessionId>.jsonl` (pasta `0o700`, arquivo `0o600`), uma linha
  por registro, **no mesmo formato de linha do `server/sources/zeroclaw/mirror.ts`** (abra e copie a forma). Entradas:
  `user_message_chunk` → linha `user`; `agent_message_chunk` e `agent_thought_chunk` → acumulados numa linha
  `assistant` fechada quando chega outra coisa ou o turno termina; `tool_call` → `tool_use` (`id: toolCallId`, `name`
  e `input` traduzidos pela tabela abaixo); `tool_call_update` `completed`/`failed` → `tool_result` com
  `rawOutput.output` (cortado em 20 000 caracteres). Passe todo texto por `maskSecrets` de `shared/activity`.
- Tabela de ferramentas (uma constante exportada, usada também pela ponte de permissão):
  `run_command` → `Bash` com `{command: rawInput.CommandLine}`; `view_file` → `Read` com `{file_path:
  rawInput.AbsolutePath}`; `write_to_file` → `Write` com `{file_path: rawInput.TargetFile}`;
  `replace_file_content` e `multi_replace_file_content` → `Edit` com `{file_path: rawInput.TargetFile}`;
  `grep_search` → `Grep` com `{pattern: rawInput.Query, path: rawInput.SearchPath}`; `list_dir` → `LS` com `{path:
  rawInput.DirectoryPath}`; qualquer outra → o próprio nome com `{summary: title}`.
- `source.ts`: `AntigravitySource implements AgentSource`, `provider = 'antigravity'`, id do agente
  `antigravity:<sessionId>`, conta única `antigravity` (nome `Antigravity`, cor `#6c8cff`, letra `G`), registrada no
  `start()` com `accounts.setProviderAccounts`. Ouve o `AntigravityClient`:
  - `session-open` → `office.addMain({id, provider: 'antigravity', account, sessionId, cwd, role: 'Agente principal
    (Antigravity)', startedAt, status: 'idle'})`.
  - `turn-state` → `office.setStatus(id, busy ? 'working' : 'idle')`.
  - `permission` → `office.setStatus(id, 'waiting', <título do comando>)`; `permission-gone` → volta a `working` se
    o turno segue.
  - `update` → espelho; `tool_call` também vira `office.addActivity` (use `describeTool` de `shared/activity` com o
    nome traduzido); `session_info_update` e `usage_update` → `office.applyTranscript(id, {title, model, context:
    {tokens: used, window: size}, tasks: [], stats: <zerado como o ZeroClaw faz>})`.
  - `session-close` → `office.closeMain(id)`.
  - `transcriptPathOf(agentId)` → o caminho do espelho, se existe.
  - `sources()` → `[{label: 'antigravity', provider: 'antigravity', path: '', sessions: <abertas>, ok: true}]`.
- Testes com um `AntigravityClient` falso (objeto que emite eventos) e `Office`/`AccountsService` reais, no modelo de
  `server/sources/zeroclaw/source.test.ts`: entra no escritório; status trabalhando/parado/esperando; atividade de
  `run_command` aparece como Bash; título e contexto aplicados; sai ao fechar; o espelho de um turno com texto e
  ferramenta é lido sem erro pelo leitor de terminal que o ZeroClaw usa.

**Verify**: `npx vitest run server/sources/antigravity` passa.

### Step 4: mensagens

- `server/messages/registry.ts`: declare
  ```ts
  /** O que o registro usa de um entregador que empurra a mensagem para a sessão (ZeroClaw, Antigravity). */
  export interface PushDelivery {
    onEvent(listener: (event: { type: string; sessionId?: string; continuityLost?: boolean }) => void): () => void;
    available(id: string, control?: boolean): boolean;
    busy(id: string): boolean;
    continuityLost(id: string): boolean;
    sendMessage(id: string, content: string): Promise<{ ok: true } | { ok: false; error: string }>;
    stopSession(id: string): Promise<void>;
    newSession(id: string, officeAgentId?: string): Promise<string>;
  }
  ```
  `MessageRegistryOptions` ganha `antigravity?: PushDelivery`. Onde hoje o código testa `provider === 'zeroclaw'` ou
  usa `this.opts.zeroclaw`, passe a escolher o entregador pela ferramenta do agente (`'zeroclaw'` →
  `opts.zeroclaw`, `'antigravity'` → `opts.antigravity`). A marca `zeroclaw?: true` da entrada continua sendo a marca
  de "empurrada" (pode ganhar um campo irmão com a ferramenta, se ajudar). **O comportamento do ZeroClaw não muda**:
  `server/messages/zeroclaw.test.ts` e os testes do registro passam sem edição. Diferenças do Antigravity:
  `deliverAs: 'steer'` e `'aside'` são recusados com `InvalidRequest` (texto novo em `tr()`: `Antigravity só recebe
  a mensagem quando o turno termina`); controles aceitos: só `stop: true` ou `command: 'new'`, isolados (mesma regra
  do ZeroClaw); as mensagens de erro mostradas para o Antigravity não podem dizer "ZeroClaw" (textos novos em `tr()`).
- `server/messages/antigravity.ts`: `AntigravityDelivery implements PushDelivery` sobre o `AntigravityClient`:
  `available` = sessão aberta; `busy` = turno em curso; `continuityLost` = sempre `false`; `sendMessage` =
  `client.prompt` (`{ok: false, error}` se rejeitar); `stopSession` = `client.cancel` (resolve na hora se não há
  turno); `newSession(id, officeAgentId)` = a sequência descrita no item seguinte; devolve o id novo;
  `onEvent` repassa `session-open`, `session-close` e `turn-state`.
- Sessão trocada pelo "conversa nova": a fonte (Step 3) precisa manter o **mesmo agente** no escritório, e o id da
  sessão nova só existe depois do `session/new`. Por isso a ordem é **abrir a nova antes de fechar a velha**, com a
  fonte adotando a nova. A adoção é **amarrada à abertura específica**, nunca a "o próximo `session-open`": aberturas
  podem correr ao mesmo tempo (um "Novo agente" durante uma "conversa nova") e as respostas do `agy` podem chegar em
  qualquer ordem.
  - `AntigravityClient.open` aceita `replaces?: string` (o id da sessão que esta abertura substitui) e o repassa,
    sem interpretar, no evento: `{ type: 'session-open', …, replaces?: string }`.
  - A fonte mantém um mapa sessão → id do agente e um conjunto `replacing` de sessões velhas com troca em curso, e
    expõe
    ```ts
    /** Troca a sessão de um agente: `open` abre a nova (com `replaces: oldSessionId`); o agente continua o mesmo. */
    replace(oldSessionId: string, open: () => Promise<{ sessionId: string }>): Promise<string>
    ```
    que: (1) rejeita se a sessão velha não está aberta ou já está em `replacing`; (2) põe a velha em `replacing`;
    (3) chama `open()`; (4) no fim, com sucesso ou erro, tira a velha de `replacing`.
  - No `session-open`: se `event.replaces` está em `replacing` **e** o agente dessa sessão velha existe, **não**
    chama `addMain`: chama `office.switchSession(agentId, novoId)` (como `server/sources/zeroclaw/source.ts` faz),
    aponta o mapa do id novo para esse agente e marca a velha como substituída (o `session-close` dela é ignorado).
    Em **qualquer outro caso** (sem `replaces`, ou `replaces` fora de `replacing`) é um agente novo, com id
    `antigravity:<sessionId novo>`. Um `session-open` sem `replaces` nunca herda o agente de outra sessão.
  - Trocas de sessões diferentes podem correr ao mesmo tempo; duas trocas da mesma sessão, não.
  - Se `open()` rejeitar, o agente continua na sessão velha, aberta e utilizável.
  `AntigravityDelivery.newSession(id)` faz: `await client.cancel(id)` → `novo = await source.replace(id, () =>
  client.open({cwd: <o da sessão velha>, replaces: id}))` → `await client.close(id)` (falha ao fechar a velha não
  desfaz a troca; vira `diagnostic`) → devolve `novo`. O entregador recebe a fonte por uma interface mínima
  (`{ replace: AntigravitySource['replace'] }`) no construtor.
  Testes obrigatórios (em `server/sources/antigravity/source.test.ts` e `server/antigravity/acp.test.ts`): (a) com
  uma troca em curso, um `session-open` **sem** `replaces` chega antes do da troca → vira agente novo com id
  próprio, e o da troca, chegando depois, adota o agente antigo; (b) a mesma coisa na ordem inversa; (c) depois
  disso, fechar a sessão do agente novo não tira o agente trocado, e vice-versa; (d) duas aberturas concorrentes no
  cliente cujas respostas `session/new` chegam em ordem invertida entregam cada `sessionId` a quem pediu e emitem
  `replaces` só na abertura que o passou (o `fake-agy.mjs` ganha um jeito de atrasar a resposta de um `session/new`).
- `server/messages/antigravity.test.ts`: mensagem com a sessão parada vira `session/prompt`; com turno em curso fica
  na fila e sai quando o turno acaba; `stop` cancela; `new` troca a sessão e o agente continua; `steer` é recusado;
  sessão fechada → indisponível.

**Verify**: `npx vitest run server/messages` passa inteiro (os testes antigos sem edição).

### Step 5: permissões

- `server/permissions/registry.ts`: `parseHookInput` aceita `provider: 'antigravity'`; `register` trata
  `antigravity` como `cli` (igual ao omp: `hookOnly`, sem sugestões, sem busca no transcript) e resolve o agente com
  um método novo `resolveAntigravityAgent(sessionId)`, modelado em `resolveOmpAgent`, procurando `provider ===
  'antigravity'`. O tipo `PermissionRequestInfo.provider` em `shared/types.ts` ganha `'antigravity'` se for uma união
  fechada. Decisão com sugestão ("sempre permitir") ou `interrupt` para `antigravity` devolve `unsupported`, como
  para o Codex e o omp.
- `server/antigravity/permissions.ts`: `class AntigravityPermissions` que ouve o cliente. Em `permission`:
  1. `registry.register({provider: 'antigravity', session_id, tool_name: <nome traduzido>, tool_input: <input
     traduzido>, cwd, timeout_ms: 24 * 60 * 60_000})`.
  2. Se veio `{skip: ...}`: tenta de novo a cada 3 s enquanto o pedido existir (sem página aberta não há quem
     responda; o turno espera).
  3. Se veio `{id}`: chama `registry.wait(id, 25_000)` em laço. `pending` → espera de novo, sem intervalo. `decided`
     com `allow` → `client.answerPermission(…, 'allow_once')`; `deny` → `'reject_once'`. `released` → volta ao passo 1
     depois de 3 s (o pedido do `agy` continua de pé).
  4. Em `permission-gone`: cancela a espera e, se havia `id`, retira o pedido do registro pelo caminho que o omp usa
     para "respondido no terminal" (`decide(id, {behavior: 'terminal'})`; confira o nome exato em `parseDecision`).
  **Nunca** responde `allow_once` sem um `decided` `allow` vindo do registro (regra 5).
- `server/antigravity/permissions.test.ts`, com `PermissionRegistry` real (modelo: os testes do registro) e cliente
  falso: pedido aparece em `registry.snapshot()` para o agente certo com o comando no título; permitir e negar chegam
  ao cliente com o `kind` certo; sem página (`viewers: () => 0`) nada é respondido e o registro é tentado de novo
  quando `viewers` passa a 1; `permission-gone` retira o pedido; nenhum caminho responde sem decisão.

**Verify**: `npx vitest run server/permissions server/antigravity/permissions` passa inteiro.

### Step 6: rotas e ligação no servidor

- `server/antigravity/http.ts`, rotas (todas exigem `trusted(req, 'spawn')`, como `/api/spawn`; sem a fonte ligada,
  a rota não existe e cai no 404):
  - `GET /api/antigravity` → `{available: true, skipPermissions: boolean | null}` (`null` no modo soquete: quem
    decide é o relé).
  - `POST /api/antigravity/sessions` com `{folder: string, prompt?: string, session?: string}` → abre (ou retoma, com
    `session` em formato UUID) na pasta `folder` (caminho absoluto, normalizado como `server/cast/launcher.ts` faz com
    `resumeRoom`), manda `prompt` (até `PROMPT_MAX` de `server/spawn/spawn.ts`) como primeira mensagem pelo
    `MessageRegistry.send` depois que o agente está no escritório, e devolve `201 {agentId, sessionId}`.
    `AuthRequired` → `409 {error: tr('entre na conta do Antigravity no computador: rode agy e faça o login')}`.
    Binário ausente (`ENOENT`) ou soquete recusado → `503` com texto que diz qual dos dois e como resolver.
  - `GET /api/antigravity/sessions` → `{sessions}` de `client.list()`, para retomar depois (sem interface neste plano).
- `server/http/app.ts`: `ApiDeps.antigravity?: {...}` e o despacho das rotas; `/api/health` ganha `antigravity:
  true` quando ligado. `POST /api/agents/:id/dismiss`: **antes** de chamar o `launcher`, se `office.get(id)?.provider
  === 'antigravity'`, fecha a sessão (`client.close`) e responde `202 {}`; a trava continua a do elenco
  (`deps.terminal && trusted(req, 'terminal')`), mas sem exigir `launcher`.
- `server/model/office.ts:1046`: acrescente, no mesmo laço ou logo depois, `castOpened = true` para todo agente
  `kind === 'main'` com `provider === 'antigravity'` que não esteja `offline`/`done` (é o que mostra o botão
  Dispensar).
- `server/index.ts`: com `config.antigravity`, cria `AntigravityClient` (`bin`, `socket`, `skipPermissions`,
  `version: config.version`), `AntigravitySource` (entra em `agents`), `AntigravityDelivery` (entra no
  `MessageRegistry` como `antigravity`), `AntigravityPermissions`, e passa as rotas ao `createApiHandler`. No
  encerramento do servidor, `client.stop()`. No log de início, uma linha: Antigravity ligado, modo (processo ou
  soquete) e, se `skipPermissions`, o aviso `tr('Antigravity: permissões automáticas ligadas
  (HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS): o agy roda comandos sem perguntar')`.
- `server/antigravity/http.test.ts` (modelo de montagem: um teste de rota existente em `server/http/`, com
  `createApiHandler` e `createRequestGuard`): 404 desligado; 403 pela porta remota sem `spawn`; abrir cria o agente
  `antigravity:<id>` no `office`; `prompt` vira mensagem entregue; `AuthRequired` → 409; dispensar fecha e o agente
  sai; `castOpened` verdadeiro no snapshot.

**Verify**: `npx vitest run antigravity` passa; `npm run typecheck` só reclama do cliente.

### Step 7: cliente web

- `client/src/ui/provider.ts`: `PROVIDER_NAME.antigravity = unbrand('Antigravity')`; `runtimeAllowsMessages` e
  `runtimeAllowsControl` aceitam `'antigravity'`; `runtimeAllowsDeliveryModes` **não**; `isAntigravity`;
  `looksLikeAntigravityId` (`/(?:^|[:/\\])\.?antigravity(?:[-_.~]|$)/i`) em `accountProvider`; `fallbackShort` inclui
  `antigravity` no regex do prefixo e devolve `'G'`.
- Os controles do omp que o Antigravity não tem (modelo, raciocínio, `/shake`, `/resume`, conversa paralela) não
  podem aparecer para ele: procure os usos de `runtimeAllowsControl` e de `provider === 'zeroclaw'` em `client/src`
  e dê ao Antigravity o mesmo conjunto do ZeroClaw (**STOP** e **conversa nova**), sem a barra de modos de entrega.
- `client/src/ui/widgets.ts:61`: título do selo `tr('Agente do Antigravity (Google)')`.
- `client/src/ui/styles.css:344,364`: os seletores do chip vazado do Codex ganham `[data-provider='antigravity']`.
- `client/src/ui/spawn.ts`: ao abrir o popover, `GET /api/antigravity`; se `available`, mostra um seletor
  "Ferramenta" com `Claude Code` (padrão) e `Antigravity`. Com `Antigravity` escolhido: o botão de anexos fica
  escondido, e `submit()` chama `POST /api/antigravity/sessions` com `{folder, prompt}`, mostra
  `tr('Antigravity aberto: o agente aparece no escritório em instantes.')` no sucesso e o `error` do servidor na
  falha. Se `skipPermissions === true`, mostra sob o seletor o aviso `tr('Permissões automáticas ligadas: o
  Antigravity roda comandos sem perguntar.')`. No modo sem marca (`isNeutralBrand()`), o seletor não aparece.
- `client/src/ui/provider-antigravity.test.ts`: nome, `accountProvider('antigravity')`, `fallbackShort` `'G'`,
  mensagens e controle permitidos, modos de entrega não.
- Traduções em `shared/locales/{en,es,fr}.ts` para todo `tr()` novo.

**Verify**: `npm run typecheck` exit 0; `npm run i18n:check -- --strict` exit 0; `npx vitest run client/src/ui` passa.

### Step 8: relé do host

- `scripts/antigravity-relay.ts` (roda no host com `tsx`; só `node:*`): escuta num soquete Unix (argumento `--socket
  <caminho>` ou `HABBLAUD_ANTIGRAVITY_SOCKET`; padrão `~/.habblaud/antigravity-acp.sock`). Antes de escutar: garante
  que a pasta é do próprio usuário e não é gravável por outros; remove soquete velho **só se** for soquete; depois do
  `listen`, `chmod 0600`. Uma conexão por vez (a segunda é fechada na hora). Para cada conexão: `spawn(bin,
  agyArgs({skipPermissions}))` com `bin` de `HABBLAUD_ANTIGRAVITY_BIN` (padrão `agy`) e `skipPermissions` de
  `HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS` **do ambiente do relé**; liga soquete ⇄ stdin/stdout; quando um lado fecha,
  fecha o outro (stdin do `agy` fechado = ele sai). **Nada lido do soquete vira argumento, variável de ambiente ou
  caminho.** `SIGTERM`/`SIGINT`: fecha a conexão, espera o filho sair (até 5 s, depois `SIGKILL`), apaga o soquete.
  Importa `agyArgs` de `server/antigravity/acp.ts` (a mesma função, uma fonte só).
- `package.json`: `"antigravity:relay": "tsx scripts/antigravity-relay.ts"`.
- `deploy/podman/habblaud-antigravity.service`: unit de usuário no modelo de
  `deploy/podman/habblaud-bridge.service` (`WorkingDirectory=%h/apps/habblaud`, `ExecStart=/usr/bin/env npm run
  antigravity:relay`, `Restart=on-failure`), com `Environment=HABBLAUD_ANTIGRAVITY_BIN=%h/.local/bin/agy` e, comentada,
  a linha `# Environment=HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS=1`.
- `deploy/podman/habblaud.container`: um bloco de comentário explicando o modo soquete e duas linhas comentadas
  `# Environment=HABBLAUD_ANTIGRAVITY=1` e
  `# Environment=HABBLAUD_ANTIGRAVITY_SOCKET=%h/.habblaud/antigravity-acp.sock`. **Não** acrescente `Volume` de
  `~/.gemini` nem do binário `agy`.
- `server/test/antigravity-relay.test.ts`: sobe o relé num soquete de pasta temporária com `HABBLAUD_ANTIGRAVITY_BIN`
  apontando para um executável temporário que roda o `fake-agy.mjs`; um `AntigravityClient` em modo soquete abre
  sessão e recebe a resposta de `ok`; o soquete tem modo `0600`; a segunda conexão é recusada; fechar a conexão
  encerra o filho; o `argv` gravado pelo falso é exatamente `acp`.

**Verify**: `npx vitest run antigravity-relay` passa.

### Step 9: teste que prende as regras de mecânica

`server/test/antigravity-mecanica.test.ts`. Lê como texto todo arquivo **não-teste** sob `server/antigravity/`,
`server/sources/antigravity/`, mais `server/messages/antigravity.ts` e `scripts/antigravity-relay.ts`, e afirma:

1. Nenhum contém `.gemini`, `oauth`, `googleapis`, `google.com`, `keyring`, `authenticate` (regras 1, 2 e 8).
2. Nenhum contém as flags proibidas da regra 3 (lista literal no teste).
3. `spawn(` só aparece em `server/antigravity/acp.ts` e `scripts/antigravity-relay.ts`, e em ambos o segundo
   argumento é a chamada `agyArgs(`; nenhum contém `shell: true`, `exec(` ou `execSync(`.
4. `agyArgs({skipPermissions: false})` é `['acp']` e `agyArgs({skipPermissions: true})` é
   `['acp', '--dangerously-skip-permissions']`.
5. `clientInfo` mandado no `initialize` tem `name: 'habblaud'` (capturado com o `fake-agy.mjs`).
6. `deploy/podman/habblaud.container` não contém `.gemini` numa linha `Volume=`.
7. `loadConfig({HOME: '/home/x'}, []).antigravity` é `false`.

**Verify**: `npx vitest run antigravity-mecanica` → 7 testes passam.

### Step 10: documentação

- `CHANGELOG.md`, em `## [Não lançado]`: "**Antigravity pelo escritório.**" — o que faz (Novo agente → Antigravity,
  mensagens, aprovar/negar, STOP, conversa nova, Dispensar, terminal), como liga (`HABBLAUD_ANTIGRAVITY=1`; no
  Podman, o relé `npm run antigravity:relay` e `HABBLAUD_ANTIGRAVITY_SOCKET`), o que **não** tem (elenco, histórico,
  cota, modelo, anexos, mensagem no meio do turno, clientes Zaplaud), e a versão do `agy` em que foi medido (1.5.9).
- `README.pt-BR.md` e `README.md`: seção "Antigravity" com os passos de ligar nos dois modos, as quatro variáveis na
  tabela de ambiente (junto de `HABBLAUD_OMP`: `README.pt-BR.md:1109`, `README.md:854`), e este aviso, sem suavizar:
  "O Habblaud abre o `agy` oficial pelo protocolo ACP e não usa o login dele por conta própria. Os Termos do
  Antigravity proíbem acessar o serviço com software de terceiros; se um cliente ACP próprio está dentro ou fora
  dessa regra é interpretação, e o risco de bloqueio da conta é de quem usa. `HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS=1`
  faz o `agy` rodar comandos e editar arquivos sem perguntar: use só em pasta ou máquina descartável."
- `server/README.md`: seção "## Antigravity" (arquitetura: cliente ACP, fonte, espelho, entrega, permissões, rotas,
  relé) e as variáveis.
- `docs/zaplaud-paridade.md`: pendência "Antigravity: Android e desktop Rust mostram o agente e (se a caixa de
  mensagem não filtrar por ferramenta) mandam mensagem; faltam o selo, STOP/conversa nova e o 'Novo agente' com
  Antigravity". Confira em `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt` e
  `zaplaud-rs/crates/zaplaud-core/src/rules/composer.rs` o que de fato acontece e escreva isso, não o palpite.

**Verify**: `npm run i18n:check -- --strict && npm run build && npm test` → exit 0.

## Test plan

Listado em cada passo. Resumo do que os testes novos precisam provar: o cliente ACP fala o protocolo medido
(sessão, turno, permissão, cancelar, queda do processo, sem login); a linha de comando é só `acp` (+ a flag do dono);
a fonte põe e tira o agente e alimenta status, atividade e terminal; mensagens esperam o turno; nenhuma permissão é
concedida sem decisão humana; as rotas respeitam as travas; o relé não aceita argumento de fora; as regras de
mecânica não regridem. O `agy` de verdade não roda em teste nenhum.

## Done criteria

- [ ] `npm run typecheck` exit 0
- [ ] `npm test` exit 0, com mais de 2806 testes passando; `server/messages/zeroclaw.test.ts` e os testes
      existentes de `server/messages/registry` e `server/permissions/registry` **sem diff**
      (`git diff --stat producao -- server/messages/zeroclaw.test.ts` vazio)
- [ ] `npx vitest run antigravity` passa, incluindo `antigravity-mecanica` (7 testes)
- [ ] `npm run i18n:check -- --strict` exit 0
- [ ] `npm run build` exit 0
- [ ] `git diff --stat producao -- server/cast server/sources/zeroclaw server/messages/zeroclaw.ts server/spawn server/pty server/world shared/world android zaplaud-rs` vazio
- [ ] `grep -rn "gemini" deploy/podman/habblaud.container | grep -c "^.*Volume="` → `0`
- [ ] `grep -rnE "override_user_agent|cloud_code_endpoint|disable.telemetry" server scripts mod --include=*.ts --include=*.mjs | grep -v "\.test\.ts"` sem resultado
- [ ] `git status --short` limpo; `plans/README.md` atualizado (ou deixado ao revisor)

## STOP conditions

Pare e relate se:

- Um trecho de "Current state" não bate com o código.
- Generalizar `messages/registry.ts` exigir mudar um teste existente do ZeroClaw ou do registro para ele passar.
- O leitor de terminal não aceitar o espelho no formato do `mirror.ts` do ZeroClaw e a saída parecer ser escrever um
  `terminalParser` novo com mais de ~80 linhas: relate o que falta.
- Qualquer passo parecer exigir: outra flag no `agy`; ler algo de `~/.gemini`; chamar `authenticate`; responder
  permissão sem decisão; montar `~/.gemini` ou o `agy` no contêiner; tocar em `server/cast/**`.
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- Você se vir prestes a rodar o `agy` de verdade.

## Maintenance notes

- **Instalar é do dono**, depois da revisão: levar o commit a `~/apps/habblaud`, reconstruir a imagem, instalar
  `deploy/podman/habblaud-antigravity.service` em `~/.config/systemd/user/`, descomentar as duas linhas do quadlet,
  `systemctl --user daemon-reload` e reiniciar. O login do `agy` já existe no host.
- O `agy` se atualiza sozinho e o protocolo foi medido no 1.5.9. Sintoma de mudança: agente que não sai de
  "trabalhando", ou permissão que não aparece. O `fake-agy.mjs` é a especificação viva do que foi medido.
- `HABBLAUD_ANTIGRAVITY_SKIP_PERMISSIONS` vale para o processo inteiro (todas as sessões) e, no modo soquete, é lido
  pelo **relé**, não pelo contêiner: quem controla o contêiner não consegue ligá-la.
- Quem revisar olha primeiro: `agyArgs` e seus dois usos; a ponte de permissões (nenhum `allow_once` sem `decided`);
  o diff de `messages/registry.ts` (ZeroClaw idêntico); as travas das rotas; o relé (uma conexão, nada do soquete
  vira argumento).
- Adiado, com motivo no `plans/README.md`: elenco com Antigravity, histórico pelo `session/list` + `session/load`,
  troca de modelo (`session/set_config_option`, não medido), clientes Zaplaud, anexos.
- Os tenants continuam sem isto: plano 054 do `omp-tenant` grava `HABBLAUD_ANTIGRAVITY=0` no quadlet deles.
