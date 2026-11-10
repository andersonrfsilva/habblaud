# Plan 069: Conectar sessões ZeroClaw ao escritório e preservar o transcript em JSONL

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 694938f..HEAD -- shared/types.ts server/accounts/detect.ts server/accounts/accounts.test.ts server/config.ts server/test/config-zeroclaw.test.ts server/index.ts server/sources/zeroclaw client/src/ui/provider.ts client/src/ui/provider.test.ts server/README.md CHANGELOG.md plans/README.md`
> Ordem fixa de merge Habblaud: **068 → 073 → 069 → 070 → 071 → 072**; omp-tenant: **039 → 040 → 041 → 042 → 043**. São ESPERADAS as mudanças dos planos Habblaud 068 e 073 em arquivos compartilhados, especialmente `server/config.ts`, `server/index.ts`, `server/README.md`, `CHANGELOG.md` e `plans/README.md`. Preserve-as. Se `shared/types.ts`, `server/http/app.ts` ou `README.md` também mudarem por esses planos, compare a origem no diff antes de integrar; esses dois últimos continuam fora deste escopo. Compare trechos com o código vivo e só trate como STOP uma divergência que não venha desses planos anteriores.

## Status

- **Priority**: P1.
- **Effort**: L.
- **Risk**: HIGH: presença, reconexão e persistência de conversas entre dois processos.
- **Depends on**: nenhuma implementação anterior no Habblaud. Para uso em tenant, requer **omp-tenant 040 — Runtime ZeroClaw por tenant** (`040-runtime-zeroclaw-por-tenant.md`). O gateway falso permite executar este plano sem iniciar tenants.
- **Ordem de integração**: executar/mesclar depois de **Habblaud 068 — Resumo sem processo OMP** e **073 — Cartão de uso pela cota**; isso ordena mudanças compartilhadas, não cria dependência funcional entre fonte e resumidor.
- **Category**: migration.
- **Planned at**: Habblaud commit `694938f`, branch `producao`, 2026-11-09. Ambiente do tenant planejado em omp-tenant commit `4c978c2`.

## Why this matters

O Habblaud observa arquivos do Claude Code, Codex e OMP, mas não conhece sessões do ZeroClaw. O novo runtime guarda sessões no próprio banco e transmite ferramentas pelo gateway WebSocket. Esta fonte transforma esses eventos no contrato já usado pelo escritório, sem adicionar uma camada de runtime e sem executar OMP. O espelho local preserva ferramentas que o histórico HTTP do ZeroClaw não consegue recuperar.

## Current state

Recon realizado em `/home/anderson/Desenvolvimento/habblaud`; nunca trabalhar em `/home/anderson/apps/habblaud`, que é produção. Os tenants estão pausados. Este plano não inicia processos de tenant, não executa `omp-tenant sync` e não publica nada.

### Contratos e exemplos locais relidos

`shared/types.ts:73-80` define a ferramenta de agentes, contas, sessões e pedidos. Ausência de `provider` continua significando Claude Code:

```ts
export type Provider = 'claude' | 'codex' | 'omp';
```

`server/sources/source.ts:22-38` é o contrato de fonte; o parser de terminal ausente usa Claude Code:

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

`server/sources/source.ts:128-142` exige `list()` assíncrono, mas `resolve()` síncrono. Portanto, materialize os arquivos durante `list()`/boot; não coloque `fetch` dentro de `resolve()`:

```ts
list(): Promise<RecentSession[]>;
resolve(account: string, sessionId: string): HistoryResolveResult;
```

`server/accounts/service.ts:132-149` já registra contas externas sem mudança no serviço:

```ts
setProviderAccounts(provider: Exclude<Provider, 'claude'>, list: readonly ProviderAccountInput[]): readonly AccountEntry[] {
```

O serviço devolve o ID final, acrescentando sufixo em colisões. Use esse retorno; não suponha que o ID solicitado será o ID final. Registre uma conta ZeroClaw mesmo com zero sessões, para o cartão de cota independente do plano 073.

`server/index.ts:176-180,242-246` mostra a ligação a seguir:

```ts
const agents = new SourceSet([claude]);
const codexDirs = config.codex ? discoverCodexDirs(process.env, config.home) : [];
const codex = codexDirs.length ? new CodexSource({ accounts, office, dirs: codexDirs, env: process.env, home: config.home }) : undefined;
if (codex) agents.add(codex);
```

```ts
if (history && codex) history.add(new CodexHistory({ accounts: () => accounts.entriesOf('codex'), openAgentOf: (acc, sid) => openMainAgent(office.list(), acc, sid) }));
```

`server/sources/codex/source.ts:442-460,652-658,687-697` é o exemplo de `office.addMain`, `setStatus`, `addActivity(..., {feed: live})` e `applyTranscript`. Carga histórica não gera feed novo. `sources(): SourceInfo[]` e `transcriptPathOf()` ficam em `254-270`. Copie o contrato, não a descoberta por locks do Codex.

`server/config.ts:248-271` monta configuração de fontes e diretório de dados:

```ts
const omp = !env.HABBLAUD_OMP?.trim() || isTruthy(env.HABBLAUD_OMP);
```

```ts
dataDir: resolve(env.HABBLAUD_DATA_DIR?.trim() || (inDocker ? '/data' : join(home, '.habblaud'))),
```

Há dois mapas exaustivos encontrados na recon: `server/accounts/detect.ts:224-245` (`ALIAS_TOOLS: Record<Provider, ...>` e desestruturação direta em `parseToolAliases`) e `client/src/ui/provider.ts:12`:

```ts
export const PROVIDER_NAME: Record<Provider, string> = { claude: unbrand('Claude Code'), codex: unbrand('Codex'), omp: unbrand('omp') };
```

### Formato Claude realmente consumido

`server/test/fixtures.ts:19-28,34-55,59-69` constrói as fixtures sintéticas existentes:

```ts
const rec: Record<string, unknown> = {
  type,
  uuid: uuid(),
  timestamp: ts(o.at ?? Date.now()),
  sessionId: o.sessionId ?? 'sess-teste',
  cwd: '/projetos/demo',
  gitBranch: o.gitBranch ?? 'main',
  isSidechain: !!o.agentId,
};
```

```ts
tool: (id: string, name: string, input: Record<string, unknown>) => ({ type: 'tool_use', id, name, input }),
```

```ts
const blocks: Array<Record<string, unknown>> = [{ type: 'tool_result', tool_use_id: id, content, ...(o.error ? { is_error: true } : {}) }];
```

`server/sources/transcript.ts:326-350` despacha `assistant`, `user`, `system`, `custom-title` e `cost-state`. `405-448` lê `message.id`, `message.model`, `message.usage`, `stop_reason` e blocos; `535-558` casa resultados. O fim precisa também limpar o estado do turno:

```ts
// server/sources/transcript.ts:561-565
private interrupted(): void {
  this.s.ended = true;
  this.s.pendingTools.clear();
  this.out.signals.push({ type: 'turnEnd' });
  this.push(SPECIAL.interrupted(), { suffix: ':int' });
```

```ts
// server/sources/transcript.ts:682-686
case 'turn_duration': {
  const ms = typeof j.durationMs === 'number' ? j.durationMs : undefined;
  this.s.ended = true;
  this.out.signals.push({ type: 'turnEnd' });
  this.push(SPECIAL.turnDone(ms), ms !== undefined ? { durationMs: ms } : {});
```

O parser de terminal é outro consumidor do mesmo arquivo. `server/sources/terminal.ts:633-683` deduplica blocos por `message.id` e conteúdo. Não reutilize o mesmo ID para deltas iguais legítimos. `712-738` aceita linhas `system` com `subtype:'informational'`, `content` e `level`.

### Gateway: contrato de destino e limites

Código relido em `/home/anderson/Desenvolvimento/zeroclaw-gluon/src/crates/zeroclaw-gateway/src/`:

- `ws.rs:86-100,178-203`: `/ws/chat?agent=<alias>&session_id=<UUID>&name=<nome>&cwd=<pasta>`; alias explícito obrigatório. Use `URL`/`searchParams`, não concatenação manual.
- `ws.rs:151-165`: autenticação depende de `require_pairing`. **omp-tenant 040 decidiu `require_pairing=false`**, gateway somente `127.0.0.1:42617` na rede de um tenant, sem porta publicada. `HABBLAUD_ZEROCLAW_URL=http://127.0.0.1:42617`; não criar token ou arquivo de token neste plano.
- `ws.rs:482-494`: `session_start` informa `session_id`, `resumed`, `message_count` e, opcionalmente, `name`; não informa o projeto.
- `ws.rs:2027-2040`: `chunk.content`, `thinking.content`, `tool_call.{id,name,args}`, `tool_result.{id,name,output}`. Resultado não transporta flag confiável de erro; não invente `is_error` pelo texto.
- `ws.rs:1679-1700`: `done` contém `full_response`, tokens, modelo, provider e custo. Valores de entrada de Codex já vieram zerados em laboratório. Não preencher `AgentInfo.context` nem tratar esses campos como extrato de cota.
- `api.rs:1783-1823`: `GET /api/sessions` lista `{session_id,session_key,created_at,last_activity,message_count,agent_alias,channel_id,name?}`. **Não há `cwd` nessa resposta.** Guarde o projeto nas aberturas locais; uma sessão externa sem metadados locais pode aparecer no histórico sem `project`, nunca numa sala inventada.
- Protocolo estabelecido: `GET /api/sessions/<id>/messages` contém somente user/assistant; não recupera chamadas e resultados de ferramentas. `POST .../messages` apenas acrescenta uma nota assistant; não gera inferência. `POST .../abort` interrompe; desconectar não interrompe.
- `api.rs:2140-2205,2222-2256`: `GET /api/sessions/<id>/state` responde `{session_id,state,turn_id?,turn_started_at?}`; `/api/sessions/running` lista sessões rodando. Abort de sessão parada responde **200 `{"status":"no_active_response"}` sem frame WS**. Isso é confirmação terminal, diferente de HTTP 200 `{"status":"aborted"}`, que apenas confirma pedido de cancelamento. `ws.rs:1794,2167,2347,2389` grava estados `running`, `idle` e `error`.
- Binário alvo: v0.8.7 + Gluon. Em reload, WS fecha com **1001**; gateway em desligamento devolve **503**, `Retry-After: 1`. Não tratar esses sinais como sessão apagada.
- Não há cursor de replay garantido no fio. Reconectar com o mesmo UUID não autoriza reenviar prompts nem afirmar que todo evento do turno perdido foi recuperado.

O PR upstream `https://github.com/marmottajr/habblaud/pull/127` foi consultado com `gh pr diff 127 -R marmottajr/habblaud`: separa fonte, metadados/presença, espelho Claude, configuração, wiring, mensagens, permissões e cliente. Reutilize essa separação; não transplante o plugin OpenCode, a camada runtime do PR #119 ou seus endpoints de eventos.

## Commands you will need

Execute na raiz `/home/anderson/Desenvolvimento/habblaud`. `package.json:13-27` exige Node >=22.12, define `typecheck` e `test`; não há script lint. Os testes usam Vitest e não exigem binário ZeroClaw.

| Finalidade | Comando | Resultado esperado |
|---|---|---|
| Ambiente | `node --version` | >=22.12 |
| Referência de camadas | `gh pr diff 127 -R marmottajr/habblaud --name-only` | lista inclui `server/sources/opencode/source.ts`, `shared/types.ts`, `server/index.ts` |
| Tipos | `npm run typecheck` | exit 0, nenhuma falha |
| Fonte nova | `npx vitest run server/sources/zeroclaw` | todos passam |
| Config e mapas | `npx vitest run server/test/config-zeroclaw.test.ts server/accounts/accounts.test.ts client/src/ui/provider.test.ts` | todos passam |
| Regressões de leitura | `npx vitest run server/sources/transcript.test.ts server/sources/terminal.test.ts server/sources/source.test.ts server/sources/codex server/sources/omp` | todos passam |
| Suíte | `npm test -- --maxWorkers=2` | todos passam |

Não instalar dependências novas. Node já fornece `fetch` e `WebSocket`. O fake WS segue a implementação RFC6455 em `server/test/fake-world.ts`, usada em `server/world/uplink.test.ts`; não adicionar pacote `ws` por causa do teste. Não executar build, instalação de mods ou comandos Docker na recon/execução deste plano.

## Scope

**In scope** (únicos caminhos que a implementação pode modificar):

- `shared/types.ts`.
- `server/accounts/detect.ts`.
- `server/accounts/accounts.test.ts`.
- `server/config.ts`.
- `server/test/config-zeroclaw.test.ts` (criar).
- `server/index.ts`.
- `server/sources/zeroclaw/` (criar `connector.ts`, `mirror.ts`, `history.ts`, `source.ts`, seus `*.test.ts` e `test-gateway.ts`, helper exclusivamente de testes).
- `client/src/ui/provider.ts` e `client/src/ui/provider.test.ts` — somente entrada mínima do mapa para compilar a união nova.
- `server/README.md` e `CHANGELOG.md`.

- `plans/README.md` — o executor atualiza somente a própria linha de status ao terminar; preservar todas as linhas dos demais planos.

**Out of scope**:

- `server/messages/`, rotas de mensagem, STOP e `/new`: **Habblaud 070 — ZeroClaw: mensagens e controles** (`070-zeroclaw-mensagens-e-controles.md`). Aqui só primitivas de transporte e teste de ponta a ponta pelo conector, sem rota pública de envio.
- `server/cast/`: **Habblaud 071 — Elenco com ZeroClaw** (`071-elenco-com-zeroclaw.md`). Este plano expõe o conector e aceita metadados de elenco; não chama nem dispensa personagens pela UI.
- UI, Android, capacidades, rótulos completos e barra de contexto: **Habblaud 072 — ZeroClaw no cliente web e Android** (`072-zeroclaw-no-cliente-web-e-android.md`), exceto o mapa tipado citado.
- Resumidor: **Habblaud 068 — Resumo sem processo OMP** (`068-resumo-sem-processo-omp.md`). Cartão de uso: **Habblaud 073 — Cartão de uso pela cota** (`073-cartao-de-uso-pela-cota.md`).
- Proxy e runtime: **omp-tenant 039 — Cota no fio Anthropic Messages** e **040 — Runtime ZeroClaw por tenant**. Script de imagem: **omp-tenant 042 — Imagem por script nos tenants**.
- Rollout, deploy e prova com tenant real: **omp-tenant 043 — Piloto e rollout ZeroClaw** (`043-piloto-e-rollout-zeroclaw.md`), exclusivamente com autorização do operador.
- Alterar o ZeroClaw, seu SQLite, `server/sources/transcript.ts`, `server/sources/terminal.ts` ou fontes anteriores. Não continuar JSONL OMP em ZeroClaw; arquivos antigos ficam intocados e somente leitura.
- Implementar aprovação interativa de ferramentas ou subagentes ZeroClaw como personagens. O frame de aprovação fica visível como aviso; não conceder permissões automaticamente.

## Git workflow

- Trabalhar somente no checkout de desenvolvimento, a partir de `producao`.
- Branch: `advisor/069-fonte-zeroclaw`.
- Preservar alterações e planos não relacionados já presentes; não limpar a árvore do usuário.
- Commit por unidade lógica, Conventional Commits em pt-BR. Exemplo observado: `feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo`. Para este plano: `feat(zeroclaw): conectar fonte e espelhar sessões do gateway`.
- Não fazer push, PR, deploy ou reinício de serviço.

## Steps

### Step 1: Acrescentar provider e configuração opt-in sem quebrar fontes atuais

1. Acrescentar `'zeroclaw'` à união `Provider` e documentar seu significado, sem mudar o default ausente.
2. Em `ALIAS_TOOLS`, restringir o domínio às três ferramentas com aliases de shell, usando `Partial<Record<Provider, ...>>`; `parseToolAliases` retorna `[]` se não houver descritor. Não fabricar uma variável de diretório ZeroClaw para satisfazer o tipo.
3. Acrescentar somente `zeroclaw: unbrand('ZeroClaw')` ao `PROVIDER_NAME`. Demais UX fica no 072. Testar lookup e modo sem marca com o padrão de `provider.test.ts`.
4. Em `ServerConfig`/`loadConfig`, acrescentar `zeroclawUrl?: string`. Ausência ou string vazia de `HABBLAUD_ZEROCLAW_URL` mantém tudo desligado, sem rede, timers ou criação de diretórios. URL presente precisa usar HTTP, host loopback, sem usuário/senha, query ou fragmento e sem caminho além de `/`; normalizar barra final. URL inválida deve falhar configuração com erro claro, sem imprimir credenciais. Não aceitar gateway remoto com pairing desligado.
5. O contrato 040 é sem pairing; não criar `HABBLAUD_ZEROCLAW_TOKEN_FILE`. Se o 040 mudar essa decisão, STOP para atualizar ambos os contratos antes de executar.
6. Escrever `server/test/config-zeroclaw.test.ts` com env isolado e sem acesso à configuração real. Não copiar `process.env` inteiro nem ler tokens.

**Verify**: `npm run typecheck && npx vitest run server/test/config-zeroclaw.test.ts server/accounts/accounts.test.ts client/src/ui/provider.test.ts` → exit 0; opt-in ausente não liga ZeroClaw e aliases existentes continuam passando.

### Step 2: Implementar um conector e uma intenção persistida por sessão

Criar `server/sources/zeroclaw/connector.ts`. Usar WebSocket nativo do Node, `fetch` com timeout/AbortController e temporizadores injetáveis para testes. Uma instância por Habblaud, um socket por sessão intencionalmente aberta. O conector não observa sessões de outros clientes.

Contrato público que os planos 070 e 071 usarão:

```ts
type OpenSession = {
  sessionId: string; agent: string; name: string; cwd: string;
  cast?: string; officeAgentId?: string;
};
type SessionDescriptor = OpenSession & { connected: boolean; busy: boolean; continuityLost: boolean };
// ZeroClawConnector:
openSession(input: OpenSession): Promise<void>;
closeSession(sessionId: string, options?: { replacement?: true }): Promise<void>;
session(sessionId: string): SessionDescriptor | undefined;
openSessions(): SessionDescriptor[];
metadataOf(sessionId: string): OpenSession | undefined;
sendMessage(sessionId: string, content: string): Promise<void>;
abortSession(sessionId: string): Promise<{ status: 'aborted' | 'no_active_response'; terminalConfirmed: boolean }>;
refreshTurnState(sessionId: string): Promise<{ busy: boolean; continuityLost: boolean }>;
onEvent(listener: (event: ConnectorEvent) => void): () => void;
```

- `session-open` carrega `sessionId`, descriptor, `resumed` e `continuityLost`; emitir somente após `session_start` validar o UUID esperado. `session-close` carrega `sessionId`, `intentional`, `replacement`, código e motivo sanitizado. `frame` carrega `sessionId` e frame validado. Estados mudam antes de notificar listeners.
- `openSession` resolve depois do `session_start`, não do evento TCP `open`. UUID global por sessão; validar UUID, campos string, pasta absoluta e parâmetros antes de gravar ou abrir rede. Alias é explícito, não é nome de modelo arbitrário. Chamadas repetidas com metadados incompatíveis para o mesmo UUID falham; chamadas compatíveis não duplicam socket.
- Persistir somente as intenções em `<dataDir>/zeroclaw/open-sessions.json`: versão do formato e array `OpenSession`, sem credenciais. Escrita atômica por temporário + rename no mesmo diretório, em série; atualizar memória somente conforme resultado da persistência. Restaurar essas intenções no boot. Arquivo inválido gera erro e nenhuma abertura automática; não substituir pelo array vazio.
- `closeSession` remove a intenção persistida e fecha o socket sem retry e sem DELETE no gateway. Não aborta inferência silenciosamente. Descriptor desaparece somente no fechamento intencional. `stop()` fecha sockets/timers sem apagar as intenções, para reinício do Habblaud.
- `sendMessage` usa o socket existente e envia exatamente `{type:'message',content}`. Desconectado = rejeição, nunca fila oculta. Marcar `busy=true` antes de resolver. Node `WebSocket.send()` não tem callback de entrega: resolver significa apenas aceitação no buffer local sem exceção, não confirmação de rede ou do modelo. Persistir o prompt local uma única vez nesse ponto, através do evento local `message-sent` com ID único; 070 não escreverá outra cópia.
- `abortSession` usa `POST /api/sessions/<UUID>/abort`, valida HTTP e corpo. `{status:'no_active_response'}` confirma ausência de turno mesmo sem frame: limpar `busy`/`continuityLost`, emitir `turn-state` com `terminalConfirmed:true,source:'abort-no-active'` e retornar confirmação. `{status:'aborted'}` apenas confirma pedido: retornar `terminalConfirmed:false`; aguardar `aborted` no socket atual ou `refreshTurnState` provar estado parado. Timeout, corpo desconhecido ou 503 não provam término. Não fingir que desconectar aborta nem fabricar frame `done`.
- `done`/`aborted` do socket atual põem `busy=false` e `continuityLost=false`; estado HTTP comprovadamente parado e `no_active_response` também podem limpar ambos pelo evento `turn-state`. Esse evento contém `sessionId`, `busy`, `continuityLost`, `terminalConfirmed` e origem; 070/071 consomem essa confirmação sem depender de um frame inexistente. Erros de fila/steering não significam término de inferência; não atribuir erro sem correlação a uma mensagem específica.
- Antes de abrir/reabrir, consultar `GET /api/sessions` também como prova HTTP de disponibilidade; 503 respeita `Retry-After` (segundos ou data válida) e backoff. Isso é necessário porque WebSocket nativo não expõe o status HTTP do upgrade rejeitado. Nunca repetir upgrade em laço apertado.
- Backoff exponencial: 1 s, 2 s, 4 s, até 30 s, jitter limitado e injetável; `Retry-After` é espera mínima. Um retry por sessão, reset só depois de `session_start`. Código 1001 e falha de rede mantêm intenção; 401/403 ou alias recusado interrompem retries e mostram erro de configuração. `stop`/fechamento intencional cancela fetch, timeout e retry pendentes, inclusive callbacks atrasados.
- Ping/pong sozinho não vira presença: exigir sessão intencional conectada e gateway alcançável. Não usar idade do JSONL, `last_activity` ou existência no SQLite como presença.
- Reconexão não reenvia prompt. Em queda durante turno ou reidratação, iniciar `busy=true,continuityLost=true` e registrar lacuna. Após `session_start`, executar `refreshTurnState` com `GET /api/sessions/<UUID>/state`: `running` mantém `busy=true` e lacuna; `idle` ou `error` confirma ausência de turno, limpa ambos e emite `turn-state` terminal, sem simular sucesso/done nem recuperar ferramentas perdidas. Enquanto continuar `running`, repetir a consulta no polling de 10 s até estado terminal. Falha/404/corpo inválido não libera o estado desconhecido. O estado parado permite novos envios; followUps anteriores à queda continuam falhos, nunca são reproduzidos. O 070 permite STOP e usa a confirmação retornada por `abortSession`/`refreshTurnState`; o 071 libera dispensa segura pela mesma prova. Invalidar respostas HTTP iniciadas antes de novo envio ou troca de socket usando geração local, para um `idle` atrasado não limpar um turno novo.

Criar `test-gateway.ts` somente para testes: HTTP local em porta efêmera, upgrade RFC6455 seguindo `server/test/fake-world.ts`, registro de mensagens/sockets, frames programáveis, close 1001, 503 + Retry-After e rotas sessions/messages/state/running/abort. Configurar separadamente estado `running`/`idle`/`error`, abort `aborted` e abort parado `no_active_response` sem frame. Nenhuma chamada real ao modelo. Não importar o helper no código de produção.

**Verify**: `npx vitest run server/sources/zeroclaw/connector.test.ts` → cobre socket único, sessão retomada, backoff, abort, fechamento intencional, persistência e cancelamento; todos passam.

### Step 3: Escrever o espelho Claude por frame, com IDs e tabela explícitos

Criar `mirror.ts`. Raiz `<config.dataDir>/zeroclaw/`; conversa em `sessions/<UUID>.jsonl`; metadados de sessões em `sessions.json` (índice versionado), separados das intenções abertas. Guardar `sessionId`, alias, nome, cwd quando conhecido, cast e handle do Office. O conector expõe `metadataOf(sessionId)` delegando ao índice local: devolve `OpenSession` somente quando alias, nome e cwd estiverem completos, senão `undefined`. Isso permite ao plano 071 validar retomada/alias sem inventar projeto para sessões externas. O histórico pode manter metadados parciais internamente.

Todas as linhas recebem `uuid` local único, `timestamp` ISO, `sessionId`, `isSidechain:false`, `cwd` somente quando conhecido e `provider:'zeroclaw'`. Não inventar `gitBranch`, modelo, tokens, custo ou ferramenta ausentes. `message.id` é único por frame; não emitir `message.usage` nesta fonte. Serializar `JSON.stringify(record) + '\n'`; queue de append por sessão, sem reescrever o arquivo a cada delta. Publicar uma linha ao parser de atividades somente depois que seu append termina. Um erro de disco precisa falhar a operação e aparecer no diagnóstico, não perder a conversa silenciosamente.

Na tabela abaixo, `B` representa esses campos comuns. `A(blocks, extra)` significa `{...B,type:'assistant',message:{id:<id único>,role:'assistant',content:blocks,...extra}}`; `U(content)` significa `{...B,type:'user',message:{role:'user',content}}`. Essas são formas de implementação, não tipos novos no protocolo público.

| Frame/evento | Linha JSONL exata, além de B | Efeito e regra |
|---|---|---|
| `session_start` | `{type:'custom-title',customTitle:<name>}` se nome existir; na retomada sem alteração, nenhuma linha nova | Atualizar índice/connected, não escrever prompt nem reimportar conversa existente |
| `message-sent` local | `U(<content>)` | Uma cópia do prompt aceito no buffer local; `uuid` do envio estável para a mesma operação |
| `chunk {content}` | `A([{type:'text',text:content}],{stop_reason:null})` | Um delta, não o acumulado; IDs distintos permitem deltas iguais consecutivos sem sumirem |
| `thinking {content}` | `A([{type:'thinking',thinking:content}],{stop_reason:null})` | Sem pensamento inventado quando `content` vier vazio |
| `tool_call {id,name,args}` | `A([{type:'tool_use',id:<id>,name:<name>,input:<args objeto>}],{stop_reason:'tool_use'})` | Mesmo ID é usado no resultado; preservar nomes ZeroClaw; parser tem apresentação genérica para ferramenta desconhecida |
| `tool_result {id,output}` | `U([{type:'tool_result',tool_use_id:<id>,content:<output string>}])` | JSON não string vira texto JSON; não inferir `is_error` de uma saída que apenas menciona erro |
| `done` com chunks anteriores | `A([],{stop_reason:'end_turn',model:<model se existir>})`, depois `{type:'system',subtype:'turn_duration',durationMs:<tempo local válido>}` | Não reescrever `full_response`, que duplicaria resposta já espelhada; fim mesmo com resposta vazia |
| `done` sem chunks anteriores | `A([{type:'text',text:<full_response>}],{stop_reason:'end_turn',model:<model se existir>})`, depois a mesma linha `turn_duration` | Se `full_response` vazio, usar `content:[]` |
| `aborted` | `U('[Request interrupted by user]')` | Forma reconhecida pelos dois parsers; limpa ferramentas pendentes; nenhum `done` inventado |
| `error {code,message}` | `{type:'system',subtype:'informational',level:'error',content:<code + mensagem>}` | Terminal mostra erro; fonte aplica atividade de erro sem fingir sucesso ou replay |
| `approval_request` | `{type:'system',subtype:'informational',level:'warning',content:<aviso de aprovação pendente, sem conceder>}` | Fonte usa `waiting`; não acoplar com permissões Claude |
| queda durante turno/reconexão com lacuna | `{type:'system',subtype:'informational',level:'warning',content:<aviso de lacuna no acompanhamento>}` | Histórico parcial continua parcial; nenhuma ferramenta reconstruída |
| `turn-state` terminal por HTTP (`idle`/`error` ou abort `no_active_response`) | `{type:'system',subtype:'informational',level:'info',content:<ausência de turno ativo confirmada pelo gateway>}` somente na transição desconhecido→parado | Fonte limpa ferramentas pendentes e marca `ended=true` no estado local; não inventa `done`, resposta, duração ou interrupção pelo usuário |
| frame desconhecido | nenhuma linha | Diagnóstico limitado pelo tipo; não logar frame bruto nem credenciais |

Detalhes obrigatórios:

- `done` não injeta `context`, uso do plano ou custo na conta. Nesta primeira fonte, omitir `message.usage` e `cost-state`: tokens de entrada já falharam no laboratório e a cota do 073 é a fonte contábil. Preservar o modelo informado, não o tratar como alias de agente.
- `args` inválido não derruba o processo; registrar erro de protocolo e não chamar `parseLine` com input inválido. IDs de ferramenta entram como valores JSON, nunca como nomes de arquivos.
- Estado por turno separa chunks anteriores de turno anterior; não suprimir `full_response` de uma resposta sem deltas após uma resposta com deltas.
- O mirror deduplica somente a própria operação local já confirmada, não todo texto igual. O gateway não fornece ID global de evento/replay; não prometer exactly-once entre falhas de processo.
- No boot, ler o espelho existente para reconstruir `TranscriptState`, sem feed. Aceitar última linha incompleta causada por crash: remover somente o fragmento final inválido antes do próximo append, preservando todas as linhas completas e registrando diagnóstico. Não truncar arquivo inteiro nem ignorar erro no meio do arquivo.
- Para histórico HTTP sem espelho, importar user/assistant uma única vez, com timestamps e IDs determinísticos da posição no snapshot inicial. Não misturar essa importação com streaming da mesma sessão: concluir bootstrap antes de abrir WS. Depois de existir espelho, não anexar a conversa HTTP inteira novamente. Lacunas posteriores ficam sinalizadas; não fabricar tools ausentes.

**Verify**: `npx vitest run server/sources/zeroclaw/mirror.test.ts` → cada linha da tabela tem teste; saída é lida por `parseLine` real e `createTerminalParser` real, sem modificar esses parsers; nenhum texto final duplicado.

### Step 4: Registrar fonte, presença e histórico sem converter inventário em agentes abertos

Criar `source.ts` e `history.ts`:

1. `ZeroClawSource implements AgentSource`, `provider='zeroclaw'`. Registrar uma conta externa via `setProviderAccounts` usando raiz do mirror e metadados próprios. Conta independe de sessões; nenhuma chamada a OMP ou `/usage`.
2. Boot assíncrono entre `office.beginBoot()` e `office.endBoot()` no `finally`, mesmo com gateway indisponível. Falha de fonte não derruba Claude/Codex/OMP. Limitar tempos de rede para não manter o Office eternamente em boot.
3. Reabrir somente intenções persistidas. `GET /api/sessions` lista histórico, não presença. UUID de sessão nova sempre vem do chamador; não importar OMP, canal composto ou qualquer registro remoto como sessão aberta.
4. `session_start` após disponibilidade HTTP recente torna a sessão presente: `addMain` com provider, conta final, UUID, cwd local, cast e status. ID padrão `zeroclaw:<account>:<UUID>`; aceitar `officeAgentId` persistido para o 070 manter identidade ao trocar UUID. Só entrar numa sala com cwd local válido.
5. Em replacement: `closeSession(...,{replacement:true})` emite fechamento intencional mas a fonte não chama `closeMain` nesse evento. Ao novo `session_start`, se o handle existe com UUID anterior, chamar `office.switchSession(handle,UUID)` antes de atualizar o rastreador. O 070 resolve falha da nova abertura chamando `office.closeMain` por callback; não deixar personagem fantasma.
6. Presença = intenção aberta + socket confirmado + gateway alcançável. Consultar HTTP com intervalo limitado (padrão 10 s), sem sobreposição. Queda/reachability perdida fecha presença no Office com seu período de graça existente, mas conserva intenção para reconectar. `SourceInfo.ok=false` e erro sanitizado explicam desconexão; `sessions` conta somente sessões presentes.
7. Linhas persistidas passam por `parseLine(createTranscriptState(), ...)`, no mesmo estado por sessão; aplicar atividades com `addActivity`, resumo com `applyTranscript` e status por bordas. Prompts/chunks/tools = working; aprovação = waiting; done/aborted = idle; desconectado = offline. Evento `turn-state` terminal confirmado por HTTP também torna idle, limpa `pendingTools` e marca `ended=true` no rastreador, sem criar atividade de sucesso. Não derivar `busy=false` de erro não terminal. O contexto fica ausente.
8. `transcriptPathOf` aponta mirror somente para agentes desta fonte; omitir `terminalParser`, pois o padrão Claude já serve. O JSONL permanece no disco depois que o personagem sai.
9. `ZeroClawHistory implements HistoryProvider`: `hasAccount` compara o ID final da conta; `list()` usa `GET /api/sessions`, limita por `HISTORY_LIMIT`/`HISTORY_MAX_AGE_MS`, ordena por `last_activity`, mantém somente sessões gateway com UUID e alias conhecido no índice remoto. Filtrar canais não suportados, sem abrir WS. Importar mensagens somente para candidatos sem espelho, com concorrência limitada e timeout; um arquivo grande não deve disparar importações ilimitadas.
10. `resolve()` valida UUID e conta, resolve caminho real dentro de `sessions/`, rejeita symlink de fuga e traversal, devolve 400/404 ou `{path,createParser:createTerminalParser}` sem HTTP. `RecentSession.provider='zeroclaw'`; `open`/`agentId` vêm do Office, `project` somente de metadados locais, `projectDir` usa `encodeCwd(cwd)` quando conhecido, senão `'sessions'`. `size` é o tamanho real do arquivo local.
11. Gateway offline: preservar histórico local e sua leitura; `list()` pode devolver cache local com diagnóstico stale, nunca `open:true` por estar no cache. Não apagar mirror se a API remota falhar. `metadataOf` lê o índice local sem abrir rede e não retorna sessão OMP.
12. Em `server/index.ts`, criar uma única instância do connector/mirror, acrescentar fonte ao `SourceSet` se `config.zeroclawUrl`, e acrescentar histórico ao `HistorySet` somente com a trava `config.terminal`, como Codex. Connector fica disponível para injeção nos planos 070/071; não criar endpoint público novo. Incluir fechamento e limpeza na rotina existente de shutdown da fonte.

**Verify**: `npx vitest run server/sources/zeroclaw/source.test.ts server/sources/zeroclaw/history.test.ts && npm run typecheck` → fonte e histórico passam com gateway falso; lista histórica sozinha não cria personagem; boot/endBoot sempre equilibrados.

### Step 5: Provar integração local e documentar limites reais

Adicionar teste integrado em `source.test.ts`: Office e AccountsService reais, diretório temporário, gateway falso real em loopback. Abrir sessão pelo conector; chamar `sendMessage` diretamente no teste, sem registrar rota de mensagens. Gateway emite ferramenta, resultado, dois chunks iguais legítimos e done. Conferir personagem/presença, arquivo, leitura pelo parser de terminal e `history.resolve` após fechamento. O teste não usa MessageRegistry, CastLauncher, modelo externo nem tenant.

Documentar `HABBLAUD_ZEROCLAW_URL`, pasta do mirror, intenção versus histórico, proteção loopback, ausência de pairing neste desenho e comportamento de reconexão em `server/README.md`. Registrar em `CHANGELOG.md` sob "Não lançado". Explicar que 070/071/072 completam operação pelo produto; este plano não anuncia envio pela UI antes deles.

**Verify**: `npx vitest run server/sources/zeroclaw server/test/config-zeroclaw.test.ts server/accounts/accounts.test.ts client/src/ui/provider.test.ts && npm run typecheck` → todos passam; depois `npm test -- --maxWorkers=2` → nenhuma regressão. Nenhum processo de tenant foi iniciado.

## Test plan

Padrões: `server/sources/codex/source.test.ts`, `server/sources/codex/history.test.ts`, construtores Claude em `server/test/fixtures.ts` e fake WS em `server/test/fake-world.ts`/`server/world/uplink.test.ts`. Testes novos usam dados sintéticos e encerram sockets, servidores, timers e diretórios temporários em `afterEach`.

- `connector.test.ts`: UUID/alias/cwd válidos e inválidos; encode de espaços/caracteres de query; mesmo socket em dois envios; dois UUIDs com sockets distintos; `session_start` incorreto; timeout de handshake; open duplicado; close voluntário; stop sem perder intenção; reinício reidrata apenas intenções; estado persistido inválido não é sobrescrito; escrita atômica falha sem falsa abertura.
- Reinício/reconexão com gateway falso: intenção restaurada + `/state` idle libera envio e dispensa sem frame; `/state` running mantém busy/lacuna e nenhum prompt é repetido; mudança posterior para idle libera estado pelo polling; abort parado retorna `no_active_response` sem WS e confirma término ao 070/071. Abort `aborted` sem prova terminal não libera; falha HTTP/404 mantém estado desconhecido. Resposta idle atrasada de geração anterior não limpa envio novo.
- Reconexão: close 1001 preserva UUID/alias/cwd; HTTP 503 respeita Retry-After sem upgrade imediato; jitter/backoff sob relógio injetado; socket antigo não publica frames depois da troca; close intencional/stop impede retry atrasado; 401/403 não gira retry. Nunca reenvia `message` depois de queda.
- `mirror.test.ts`: uma asserção por mapeamento da tabela; aspas/quebras/unicode válidos; IDs ferramenta casam; tool desconhecida genérica; saída não string; dois deltas iguais preservados; done não duplica chunks; done sem chunks inclui resposta; resposta vazia; aborted limpa pending; approval não aprova; error visível; cache/tokens não inventados; dois turnos não compartilham marcador de chunks; append em série; erro de disco; último fragmento incompleto sem destruir linhas anteriores.
- `source.test.ts`: conta existe sem sessão; boot/endBoot no finally inclusive falha de rede; nenhuma presença derivada de GET sessions; cwd remoto ausente não inventa sala; status working/waiting/idle/offline; cast repassado; transcript recuperado sem feed duplicado; reconnect não usa socket antigo; replacement conserva handle e muda UUID; SourceInfo e contexto ausente; coexistência com fonte fake de outro provider.
- `history.test.ts`: list remoto mais cache local; filtro de canais; ordenação/idade/limite; materialização HTTP user/assistant uma única vez; sem reconstrução fictícia de tools; `resolve()` síncrono; conta desconhecida; UUID inválido; traversal; symlink fora da raiz; histórico preservado com 503; sessão fechada continua legível; metadataOf não aceita sessão OMP.
- Integração real de transporte em Vitest: uma conexão do fake recebe `{type:'message',content}`; emite tool_call/tool_result/chunk/done; os dois parsers reais leem o arquivo; fechar remove presença mas não o histórico. Nenhum endpoint de produto precisa ser criado para provar esse caminho.
- Regressões existentes: `npx vitest run server/sources/transcript.test.ts server/sources/terminal.test.ts server/sources/source.test.ts server/sources/codex server/sources/omp` → todas passam sem alterar fixtures existentes para esconder falhas.

## Done criteria

- [ ] `npm run typecheck` sai 0, inclusive mapa tipado no cliente.
- [ ] `npx vitest run server/sources/zeroclaw server/test/config-zeroclaw.test.ts server/accounts/accounts.test.ts client/src/ui/provider.test.ts` sai 0.
- [ ] Teste fake WS cobre ponta a ponta envio direto do conector, mirror e parsers reais, sem MessageRegistry.
- [ ] Cada linha da tabela frame→JSONL possui teste; `done` não duplica texto e `aborted` não produz sucesso.
- [ ] Testes demonstram backoff em 1001/503, ausência de replay de prompt, stop/close sem retry e lacuna explícita.
- [ ] Teste demonstra conta sem sessão, GET sessions sem presença e histórico com gateway fora do ar.
- [ ] Fake gateway prova reinício com sessão parada e rodando, reconciliação por `/state` e abort `no_active_response` sem frame, sem deadlock de controles.
- [ ] `npm test -- --maxWorkers=2` sai 0.
- [ ] README e changelog refletem URL, dados persistidos, ausência de token neste desenho e limites de histórico.
- [ ] Nenhum arquivo fora de Scope foi alterado; comparar a lista de arquivos alterados ao escopo, sem apagar alterações prévias do usuário.
- [ ] Executor atualizou somente sua linha em `plans/README.md`; demais status permanecem preservados.
- [ ] Nenhum build, sync, publicação ou reinício de tenant executado.

## STOP conditions

Pare e reporte, sem improvisar, se:

- Trechos de Current state divergirem do código vivo por mudanças que não sejam dos planos Habblaud 068 e 073, anteriores na ordem fixa de merge.
- O 040 passar a exigir pairing, publicar gateway fora do loopback ou mudar a semântica mono-tenant. Não desativar segurança para fazer teste passar.
- O gateway alvo não aceitar `agent` + UUID explícitos, não emitir os frames documentados ou não suportar aborto HTTP. Não trocar para ACP/chat completions/POST messages como atalho.
- Presença depender de abrir automaticamente todas as sessões do inventário, adivinhar cwd ou lançar processo OMP/tmux.
- O executor precisar modificar parsers compartilhados, UI além do mapa mínimo, CastLauncher ou MessageRegistry para concluir este plano.
- A recuperação exigir apagar/regravar conversas, concatenar conversa OMP em ZeroClaw ou afirmar recuperação de ferramentas ausentes no HTTP.
- A prova exigir um pacote WS novo, segredos reais, conexão externa ou iniciar tenant pausado; use o fake conforme o exemplo do repositório.
- Um gate falhar duas vezes após tentativa de correção razoável. Registrar comando e erro decisivo; não reduzir o teste ou aceitar scaffold.

## Maintenance notes

- Revisar especialmente a diferença entre arquivo histórico, intenção aberta e presença confirmada. Confundir os três ressuscita personagens fechados.
- O socket único é contrato de steering do 070. Não abrir segundo socket para entregar mensagem à mesma sessão.
- 070 compõe `/new` com abort, fechamento `replacement`, UUID novo e handle estável; 071 usa open/close, `openSessions` e `metadataOf` para elenco. Não duplicar connector nesses serviços.
- A API de histórico não contém cwd nem ferramentas. O índice local e o mirror são dados duráveis; não tratá-los como cache descartável.
- Não há confirmação por mensagem no WS nem replay de eventos garantido. Resolver `sendMessage` não prova inferência; queda durante turno exige lacuna explícita e proíbe drenar followUp como se houvesse done.
- Não usar `done.input_tokens` para contexto nem cota. O plano 072 oculta contexto sem dado confiável; 073 lê a cota pelo proxy.
- Rollback é voltar o código/configuração que não define `HABBLAUD_ZEROCLAW_URL`; preservar `zeroclaw/` para não perder histórico. Corte real e tratamento de inferências ainda em execução pertencem ao omp-tenant 043.
