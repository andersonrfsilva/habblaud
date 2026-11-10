# Plan 071: Chame e dispense personagens pelo gateway ZeroClaw, sem auxiliar nem tmux

> **Executor instructions**: Siga este plano passo a passo. Execute cada comando de
> verificação e confirme o resultado esperado antes de avançar. Se ocorrer uma
> condição de STOP, pare e relate; não improvise. Ao terminar, atualize somente a linha
> deste plano em `plans/README.md`, conforme o padrão do índice.
>
> **Drift check (run first)**: execute em `/home/anderson/Desenvolvimento/habblaud`:
> `git diff --stat 694938f..HEAD -- server/cast/launcher.ts server/cast/launcher.test.ts server/cast/zeroclaw.ts server/cast/zeroclaw.test.ts server/config.ts server/test/config-zeroclaw.test.ts server/index.ts server/http/app.ts server/http/http.test.ts server/README.md CHANGELOG.md plans/README.md`
> Ordem de merge fixa: omp-tenant 039 → 040 → 041 → 042 → 043; Habblaud 068 → 073 → 069 → 070 → 071 → 072.
> São ESPERADAS as mudanças dos planos anteriores nessa ordem em `server/config.ts`,
> `server/index.ts`, `server/README.md`, `server/http/app.ts`, `CHANGELOG.md`, `shared/types.ts`,
> `README.md` e no índice. Identifique-as antes de comparar os trechos de “Current state”.
> Apenas divergências não provenientes desses planos anteriores são STOP.

## Status

- **Priority**: P1.
- **Effort**: L.
- **Risk**: MED — sessão desconectada continua executando; uma dispensa incorreta deixaria trabalho ativo invisível.
- **Depends on**: Habblaud **069 — Fonte ZeroClaw** (`plans/069-fonte-zeroclaw.md`) e **070 — ZeroClaw: mensagens e controles** (`plans/070-zeroclaw-mensagens-e-controles.md`). Contrato de implantação: omp-tenant **040 — Runtime ZeroClaw por tenant** (`/home/anderson/Desenvolvimento/omp-tenant/plans/040-runtime-zeroclaw-por-tenant.md`).
- **Category**: migration.
- **Planned at**: Habblaud commit `694938f`, branch `producao`; omp-tenant commit `4c978c2`; 2026-11-09.

## Why this matters

Hoje, chamar um personagem exige que um auxiliar no host abra um processo OMP dentro do tmux. No tenant ZeroClaw, um daemon atende várias sessões e esse processo não deve existir. Este plano preserva o elenco, o limite de personagens e a dispensa por ociosidade, trocando somente a operação de abertura e fechamento. A conversa continua guardada depois de dispensar; histórico OMP não se transforma em conversa ZeroClaw.

## Current state

Trechos relidos no checkout de desenvolvimento; os números abaixo são anteriores aos pré-requisitos.

- `server/cast/launcher.ts:10-28` recebe callbacks, relógio e limite; disponibilidade depende do auxiliar:

```ts
interface Options {
  dataDir: string;
  member(id: string): CastMember | undefined;
  agents(): AgentInfo[];
  account(id?: string): string | undefined;
  changed(): void;
  idle?(agent: AgentInfo): void;
  now?: () => number;
  /** Máximo de personagens abertos ao mesmo tempo (as "salas" de um tenant); ausente = sem limite. */
  max?: number;
}
```

```ts
available(): boolean { return this.now() - this.hostSeen <= HOST_PRESENCE_MS; }
```

- `server/cast/launcher.ts:49-55` calcula a retomada e exige conta OMP antes de enfileirar:

```ts
const resume = body.mode === 'resume' ? (explicit ? body.session as string : member.last![room]) : undefined;
if (resume !== undefined && !/^[0-9a-f][0-9a-f-]{7,199}$/i.test(resume)) return { status: 422, error: tr('pedido inválido') };
if (!this.available()) return { status: 503, error: tr('auxiliar do host desligado (npm run codex:bridge)') };
// A conversa mora na conta em que foi gravada: `account` do histórico vence a do personagem.
if (body.account !== undefined && typeof body.account !== 'string') return { status: 422, error: tr('pedido inválido') };
const agentDir = this.opts.account((body.account as string | undefined) ?? member.account);
if (!agentDir) return { status: 422, error: tr('conta não encontrada') };
```

- `server/cast/launcher.ts:56-66` grava instruções em arquivo e produz `CastJob` para o auxiliar. `startNew`, nas linhas 73–85, também exige auxiliar e conta. `poll/ack`, nas linhas 135–173, tratam os trabalhos do host. `dismiss`, nas linhas 91–95, só aceita personagens presentes em `opened`.
- `server/cast/launcher.ts:195-199` já tem a regra correta de ociosidade:

```ts
const idle = this.opts.member(cast)?.idleMinutes ?? CAST_IDLE_MINUTES;
if (idle && agent.status === 'idle' && now - agent.statusSince >= idle * 60_000 && !this.state(cast)) {
  this.dismiss(agent);
  this.opts.idle?.(agent);
}
```

- `shared/types.ts:32-41` mantém `CastMember.model?: string`, `instructions?: string`, `last?: Record<string,string>` e `CAST_IDLE_MINUTES = 120`. Não crie outro padrão de tempo.
- `server/model/cast.ts:26-30` já aceita alias no campo `model`; instruções têm limite de 8.000 caracteres. `server/model/office.ts:910-913` guarda a última sessão ao vestir o membro:

```ts
private rememberCast(info: AgentInfo): void {
  const member = info.cast && this.cast.get(info.cast);
  if (!member || member.kind !== 'main' || member.last?.[info.roomId] === info.sessionId) return;
  this.cast.put({ ...member, last: { ...member.last, [info.roomId]: info.sessionId } });
}
```

- `server/index.ts:153-164` cria `CastLauncher` somente com terminal habilitado, resolve contas por `accounts.entriesOf('omp')` e repassa `config.castMax`. `server/config.ts:56-57,234-238,268` já interpreta `HABBLAUD_CAST_MAX` de 1 a 50.
- `server/http/app.ts:554-607` protege jobs/start/new/dismiss com `deps.terminal` e `trusted(req, 'terminal')`; chamadas síncronas devolvem 202 com job. Preserve esse contrato HTTP e suas travas.
- `client/src/ui/cast-screen.ts:26-36` alimenta o seletor pelos `agent.models` observados. Isso não fornece catálogo antes da primeira sessão. A troca do consumidor pertence ao plano 072; este plano fornece o endpoint.
- `scripts/codex-bridge.ts:409-458` faz poll de `/api/host/cast/poll`, valida `agentDir`, cria `hb-<cast>` com tmux e executa OMP. Este caminho continua intacto para OMP.
- `server/messages/registry.ts:330-361` oferece `send(raw)`, recebe `agentId`, `text`, `deliverAs`, `expectedSessionId` e devolve `{message}` ou `{error}`. A validação existente aceita até 20.000 caracteres (`registry.ts:200`), suficiente para perfil + prompt de 8.000 caracteres cada.
- Padrão de testes: `server/cast/launcher.test.ts:8-13` injeta relógio, membros e agentes; linhas 90–110 cobrem ociosidade e linhas 150–167 cobrem limite. `server/http/http.test.ts:27-60` sobe HTTP em porta efêmera com `tempDir` e dependências falsas.
- `package.json:26-28` define `typecheck`, `test` e `i18n:check`; não há script de lint. `server/README.md` documenta configuração; `CHANGELOG.md:11-13` tem a seção “Não lançado / Adicionado”.

### Contratos dos pré-requisitos e decisões deste plano

1. Um daemon por tenant, gateway HTTP em `http://127.0.0.1:42617`, sem porta publicada. O plano 040 usa `require_pairing=false` apenas nessa rede compartilhada e isolada. Não acrescente bearer ao navegador nem implemente pairing aqui.
2. Runtime do elenco = ZeroClaw quando a configuração validada `HABBLAUD_ZEROCLAW_URL` do 069 está presente. Configuração inválida não pode fazer fallback para OMP. Sem essa configuração, comportamento OMP permanece igual.
3. O plano 069 fornece `ZeroClawConnector.openSession({sessionId,agent,name,cwd,cast?,officeAgentId?}): Promise<void>`, resolvido após `session_start`; `closeSession(sessionId): Promise<void>` fecha intencionalmente, sem reconectar, abortar nem apagar histórico; `session(sessionId)`, `openSessions()` e `metadataOf(sessionId)` fornecem descritores. Metadados incluem `sessionId/agent/name/cwd/cast/officeAgentId`; sessões abertas também têm `connected/busy`. `onEvent` emite `session-open`, `session-close {intentional}` e frames. A intenção de abertura fica persistida em `<dataDir>/zeroclaw/open-sessions.json`; somente essas intenções são reabertas no boot.
4. O plano 070 oferece `ZeroClawDelivery.cancelSession(sessionId, reason)`, `stopSession(sessionId)` e `newSession(sessionId)`. `stopSession` aceita como fim de turno `aborted`, `done` concorrente ou HTTP 200 `{"status":"no_active_response"}`; este último não produz frame e deve resolver imediatamente, sem espera adicional. A mensagem inicial passa por `MessageRegistry.send`, não por HTTP `/messages` do gateway. `/new` pertence ao 070: `closeSession(old,{replacement:true})` não encerra o personagem no Office; a abertura nova mantém `agent/name/cwd/cast/officeAgentId`, e o Source chama `Office.switchSession` no mesmo agente. `newSession` retorna o UUID novo. O launcher concilia `cast -> sessionId` pelo novo `session-open`, preservando uma vaga, sem outra implementação de `/new`.
5. Catálogo vem da configuração, não de descoberta remota. O plano 040 gera `HABBLAUD_ZEROCLAW_AGENTS` como JSON `[{"alias":"...","model":"provedor/modelo"}]`, na ordem de `tenant.json`, e `HABBLAUD_ZEROCLAW_DEFAULT_AGENT` com alias padrão. Cada alias é `m_` + os primeiros 12 dígitos hexadecimais de SHA-256 do identificador `model` completo em UTF-8. Esse mesmo alias nomeia `[agents.<alias>]` e `[providers.models.anthropic.<alias>]`. Não derive alias por slug, índice ou remoção de pontuação.
6. No runtime ZeroClaw, `member.model` novo guarda o alias. Um valor legado igual exatamente a `model` de uma entrada é traduzido para seu alias em memória. Não remova sufixo de raciocínio, não faça aproximação e não regrave todos os perfis. Campo ausente usa DEFAULT_AGENT. Alias/modelo desconhecido devolve 422, não escolhe outro modelo.
7. Instruções do personagem viram contexto textual explícito, não system prompt: no modo `new`, se houver instruções, o primeiro texto enviado é `Instruções do personagem:\n<instructions>` seguido, quando houver prompt, por `\n\nPedido inicial:\n<prompt>`. Sem instruções, envie só o prompt; sem ambos, apenas abra a sessão, sem inferência. No `resume`, não repita instruções; envie somente o prompt opcional. Não prometa prioridade de system para esse texto. Os limites existentes de 8.000 caracteres por campo permanecem; a composição fica abaixo de 20.000.
8. Dispensa manual: bloqueie novos envios e cancele pendências; se busy, use `stopSession` e aguarde confirmação terminal; só então `closeSession`. Se idle, feche sem abort. Desconectar não interrompe o turno. Nunca use DELETE: a retomada precisa do histórico. Falha de abort mantém sessão aberta, job failed e vaga ocupada.

## Commands you will need

Execute no checkout de desenvolvimento, nunca em `/home/anderson/apps/habblaud`.

| Purpose | Command | Expected on success |
|---|---|---|
| Tipos | `npm run typecheck` | exit 0, sem erros |
| Elenco e catálogo | `npx vitest run server/cast/launcher.test.ts server/cast/zeroclaw.test.ts` | todos passam |
| Configuração | `npx vitest run server/test/config-zeroclaw.test.ts` | todos passam |
| HTTP | `npx vitest run server/http/http.test.ts` | todos passam |
| Integração dos pré-requisitos | `npx vitest run server/cast server/sources/zeroclaw server/messages server/http/http.test.ts` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0; nenhuma chave faltante/excedente |
| Suíte final | `npm test -- --maxWorkers=2` | todos passam |

Comandos conferidos pelos scripts de `package.json` e pelos testes existentes; não executados na autoria do plano. Não instale dependências, não faça build/publicação nem ligue tenants para executar este plano.

## Scope

**In scope** — únicos arquivos que o executor deve modificar:

- `server/cast/launcher.ts`
- `server/cast/launcher.test.ts`
- `server/cast/zeroclaw.ts` (criar: catálogo e operação concreta ZeroClaw)
- `server/cast/zeroclaw.test.ts` (criar)
- `server/config.ts`
- `server/test/config-zeroclaw.test.ts` (criado pelo plano 069; ampliar)
- `server/index.ts`
- `server/http/app.ts`
- `server/http/http.test.ts`
- `server/README.md`
- `CHANGELOG.md`
- `plans/README.md` (somente a linha de status do plano 071)

**Out of scope**:

- `server/sources/zeroclaw/*`, espelho JSONL e persistência de conexões — Habblaud 069, Fonte ZeroClaw.
- `server/messages/*`, STOP e `/new` — Habblaud 070, ZeroClaw: mensagens e controles.
- Cliente web/Android, rótulos e seletor visual — Habblaud 072, ZeroClaw no cliente web e Android.
- Resumidor e cartão de cota — Habblaud 068 e 073, respectivamente.
- Quadlets, aliases gerados, providers e daemon — omp-tenant 040, Runtime ZeroClaw por tenant.
- Piloto, sync, restart, deploy e rollout — omp-tenant 043, Piloto e rollout ZeroClaw; tenants continuam pausados.
- `scripts/codex-bridge.ts`, código OMP, formato `CastJob` público e dados/sessões OMP: preservar para rollback. Nenhum shell/tmux/binário OMP no caminho ZeroClaw.
- Alterar o armazenamento do elenco ou expirar/apagar sessões: reutilizar `Office.rememberCast` e os metadados 069.
- Outros planos e outras linhas de `plans/README.md`: não alterar.

## Git workflow

- Trabalhe em `/home/anderson/Desenvolvimento/habblaud`, a partir de `producao` contendo 069 e 070.
- Branch: `advisor/071-elenco-com-zeroclaw`.
- Commits convencionais em pt-BR. Exemplo observado: `feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo` (`694938f`). Para esta unidade: `feat(elenco): abre e dispensa sessões ZeroClaw pelo gateway`.
- Não faça push, merge, release, alteração em produção ou reinício de serviços.
- Preserve alterações preexistentes do usuário, inclusive arquivos não rastreados em `plans/`.

## Steps

### Step 1: Valide o catálogo e exponha o modelo selecionável

Crie `server/cast/zeroclaw.ts` com parser puro do catálogo e resolução `member.model -> alias`. Use `createHash('sha256')` da biblioteca padrão. Valide JSON não vazio, estrutura string, alias no formato `m_[0-9a-f]{12}`, hash correto, modelos/aliases únicos e DEFAULT_AGENT pertencente ao catálogo. Identificadores de modelo não podem conter espaço, controle ou wildcard. Recuse configuração parcial; não aceite lista vazia, default implícito no Habblaud ou colisão de alias. Não registre o conteúdo bruto do ambiente.

Integre o resultado tipado em `server/config.ts`, somente quando o runtime ZeroClaw estiver configurado. A validação de URL e o objeto de conexão já vêm do 069: reutilize-os. Acrescente testes em `server/test/config-zeroclaw.test.ts`, criado pelo 069, e `server/cast/zeroclaw.test.ts`; configuração OMP sem essas variáveis permanece válida. Não calcule hash a cada envio.

**Verify**: `npx vitest run server/test/config-zeroclaw.test.ts server/cast/zeroclaw.test.ts` → todos passam, incluindo catálogo inválido, hash e tradução legada exata.

### Step 2: Separe a execução dos trabalhos sem trocar o contrato HTTP

Em `server/cast/launcher.ts`, mantenha validação de membro, sala, prompt, duplicidade, limites e respostas síncronas `{job}`/`{status,error}`. Acrescente uma dependência opcional concreta ZeroClaw, implementada em `server/cast/zeroclaw.ts`, sem criar uma camada genérica de runtimes.

Depois das validações comuns, desvie para ZeroClaw antes de `account()`, gravação de `instructionsFile` e enfileiramento do host. Trabalhos ZeroClaw usam entrada interna discriminada, não um `CastJob` com `agentDir` falso. `jobs()` conserva `CastJobInfo`; `poll()` devolve somente trabalhos OMP. `poll/ack` recebidos por engano não podem concluir trabalhos ZeroClaw nem reconstruir seu `opened`. `rolesDir` não consulta conta OMP nesse runtime; `setRoles` recusa operação OMP-only sem job nem execução externa.

Use o mesmo caminho para `startNew`: identificador `novo-…`, alias padrão, sala validada, mesma cota, sem depender de conta ou bridge. Preserve suporte OMP existente a `body.account`. No ZeroClaw, perfis legados com `member.account` podem existir, mas esse campo não resolve conta OMP; `body.account` explícito aceita apenas o identificador de conta ZeroClaw fornecido pelo 069, recusando outras contas.

A chamada aceita devolve job 202 e reserva membro/vaga antes da primeira operação assíncrona. Disponibilidade ZeroClaw reflete conector pronto, sem `hostSeen`; indisponível devolve 503 com motivo ZeroClaw. Um único mapa de trabalhos controla estados: queued, sent durante abertura, waiting até presença confirmada, done; erros viram failed sem exceção não tratada. Preserve retenção `KEEP_MS` e timeout de confirmação de 60 segundos. Não reutilize texto “auxiliar” em erros desse caminho.

**Verify**: `npx vitest run server/cast/launcher.test.ts server/cast/zeroclaw.test.ts` → regressões OMP passam e dois starts concorrentes não ultrapassam o limite.

### Step 3: Abra e retome pela conexão do 069

Em `server/cast/zeroclaw.ts`, implemente abertura com `randomUUID()` para `new`. Para `resume`, use `body.session` explícito ou `member.last[room]`, mas exija `metadataOf(sessionId)` ZeroClaw, mesma sala normalizada e alias ainda permitido. UUID OMP, sessão inexistente e conta incompatível devolvem 422 antes de conectar. Na retomada, use o alias original da sessão; se o modelo escolhido explicitamente no perfil resolve para outro alias, devolva 422 e exija Nova. Não mude modelo de conversa existente silenciosamente.

Execute `openSession({sessionId,agent:alias,name:member.name,cwd:room,cast:id})`. Para `startNew`, use o identificador temporário e o comportamento de nome/visual sorteado do Office; não crie membro persistente fictício. O conector registra presença com `cast`, e `Office.rememberCast` atualiza `last` somente após abertura confirmada. Não regrave `last` ao simplesmente receber o pedido.

Depois de `session_start` e registro no Office, envie o texto inicial pelo `MessageRegistry.send({agentId,text,expectedSessionId:sessionId})` do 070. Localize `agentId` pela presença real com provider ZeroClaw e UUID, não por concatenação inventada. Não envie diretamente com `sendMessage`, não use POST `/api/sessions/<id>/messages` e não emita prompt novamente durante reconnect. `{error}` no envio falha o job, com cleanup da abertura e mensagem concreta; não mostre sucesso quando o prompt não entrou na fila. O job não espera a resposta inteira do modelo.

Trate timeout e abertura tardia: se um start já falhou, uma resolução atrasada não pode restaurar presença/vaga ou enviar prompt. Feche somente a conexão dessa tentativa; se já iniciou turno, use cancel/stop do 070 antes. Uma retomada malsucedida nunca apaga histórico nem troca a referência `last` por UUID inventado.

**Verify**: `npx vitest run server/cast/launcher.test.ts server/cast/zeroclaw.test.ts` → testes com promises controladas confirmam UUID novo, resume, envio único, falha e abertura atrasada sem prompt órfão.

### Step 4: Dispense, preserve cotas e reconstrua presença

Implemente a ordem de dispensa definida no contrato: marque `dismissing`, barre novos envios, `cancelSession`, `stopSession` somente se busy e `closeSession`. Rejeite dispensa de sessão não aberta pelo Habblaud. Não remova `opened` nem solte vaga antes do close intencional confirmado. Para ZeroClaw, diferente do comportamento histórico OMP, `dismissing` continua contando contra `HABBLAUD_CAST_MAX` até confirmação: desconectar antes do abort deixaria trabalho fora do limite.

Exponha `CastLauncher.isClosing(sessionId): boolean` pelo vínculo cast/sessão e injete esse callback no `ZeroClawDelivery` criado em `server/index.ts`. O 070 fornece essa opção, com padrão false, e verifica o callback na admissão e imediatamente antes do dispatch de mensagem/controle. O `stopSession` interno continua permitido durante dispensa. Ao falhar, remova o estado de dispensa sem remover a intenção aberta; não deixe um bloqueio permanente. Não tente usar apenas `cancelSession` como trava: esse método cancela pendências, mas não impede novos pedidos.

Reutilize o `tick` e `CAST_IDLE_MINUTES` existentes: somente `idle`, nunca `working/waiting/shell`, respeitando `idleMinutes: 0`. A notificação de dispensa por ociosidade deve ocorrer somente quando a dispensa foi aceita, sem repetição por tick.

Na ligação em `server/index.ts`, injete callbacks tardios de connector, delivery e registry, pois o launcher é criado antes das fontes/mensagens. Não crie outra conexão nem outro serviço de mensagens. Reconstitua `opened` a partir de `openSessions()` persistidos no 069, incluindo intenções temporariamente desconectadas. Queda 1001/503 mantém vaga e não equivale a dispensa. Fechamento intencional remove `opened`; substituição por `/new` acompanha o UUID novo mantendo uma única vaga por cast. Não considere todas as sessões de GET `/api/sessions` como abertas.

Use `connected/busy/continuityLost` do descritor, não apenas o status visual do Office. O 069 reidrata intenções com `busy=true/continuityLost=true` até confirmar término ou abort: reconectar não prova que o turno anterior terminou. Não dispense por ociosidade enquanto houver `continuityLost`; uma dispensa manual nesse estado usa `stopSession`. HTTP 200 `{"status":"no_active_response"}` confirma sessão parada, não emite frame e autoriza `closeSession` imediatamente. `aborted` ou `done` concorrente também confirmam término. O 069 consulta `GET /api/sessions/{id}/state` ou `GET /api/sessions/running` para reconciliar estado após reinício; reutilize essa informação, sem implementar outro cliente HTTP aqui. Se não conseguir confirmar término, preserve intenção e vaga, com erro observável. Teste a sessão já parada com busy conservador: dispensa deve concluir sem timeout nem frame.

**Verify**: `npx vitest run server/cast server/sources/zeroclaw server/messages` → testes passam para busy/idle, falha de abort, reconnect, reidratação e `/new` sem duplicação.

### Step 5: Publique o catálogo para o cliente e documente o comportamento

Adicione `GET /api/cast/models` em `server/http/app.ts`, na mesma trava `terminal` + `trusted(req, 'terminal')` dos outros endpoints do elenco. No ZeroClaw, devolva exatamente `{provider:'zeroclaw',models:[{alias,model}],defaultModel:alias}`, na ordem configurada. `model` é o identificador legível exibido no seletor; `alias` é a chave técnica enviada e armazenada. No OMP, devolva `{provider:'omp',models:[]}`; o consumidor 072 mantém a descoberta OMP existente. Nenhuma URL, credencial ou TOML no JSON. Não dependa de uma sessão ativa para devolver o catálogo.

Amplie `server/http/http.test.ts` com a estrutura `start(extra)` existente: GET autorizado funciona antes de qualquer sessão, método inválido recebe 405, terminal desligado/sem permissão recebe 403; start e dismiss preservam 202/jobs. Não afrouxe as rotas existentes.

Atualize `server/README.md` com as três variáveis, formato do catálogo, algoritmo de alias, seleção por runtime, instruções como contexto textual e fechamento preservando histórico. Acrescente entrada curta em `CHANGELOG.md`, “Não lançado / Adicionado”, sem anunciar rollout. Nenhuma mudança visual: o consumidor está no Habblaud 072.

**Verify**: `npx vitest run server/http/http.test.ts server/cast server/test/config-zeroclaw.test.ts` → todos passam. Depois `npm run typecheck` e `npm run i18n:check -- --strict` → exit 0.

## Test plan

Use `server/cast/launcher.test.ts` como padrão: relógio injetado, lista mutável de agentes e callbacks espiados. Em `server/cast/zeroclaw.test.ts`, use conector/delivery/registry falsos com promises controladas; nenhum daemon, rede externa, shell, OMP ou tmux real.

Casos obrigatórios:

- Catálogo válido, default válido, hash conhecido; JSON inválido, entradas repetidas, colisão/hash divergente, wildcard e default inexistente recusados.
- Modelo alias, modelo legado exato e ausência de modelo; desconhecido/sufixo não configurado não viram fallback.
- `new` com UUID novo; `resume` preserva UUID e alias, verifica histórico ZeroClaw e sala; histórico OMP não é retomado.
- Sala/membro inválidos, duplicidade, prompt acima do limite, conta explícita incompatível e conector indisponível recusados antes de abrir.
- Instruções + prompt preservados em uma mensagem abaixo do limite; sem prompt/instruções não há inferência; resume não duplica perfil.
- Prompt inicial só após `session_start`, com `expectedSessionId`; reconnect não reenvia; rejeição do registry e abertura tardia são falhas observáveis.
- `startNew` usa default e `novo-…`, pode retomar sessão ZeroClaw válida, e conta na mesma cota.
- Limite inclui queued/open/disconnected/dismissing; dispensa só libera após close; sem limite mantém comportamento existente.
- Idle padrão de 120 minutos, limite personalizado, `0`, estados working/waiting/shell e uma única notificação.
- Dispensa idle não chama abort; busy chama cancel, stop e close nessa ordem; falha do stop não fecha nem libera vaga.
- Sessão não aberta pelo Habblaud recebe 404; dispensa duplicada recebe 409; DELETE nunca chamado.
- Reidratação preserva vínculo e uma vaga; `/new` troca UUID sem perder `castOpened`; poll/ack do host não altera ZeroClaw.
- Catálogo HTTP disponível sem sessão, permissões/métodos mantidos e resposta sem informações privadas.
- Todos os testes OMP existentes continuam passando sem mudança no auxiliar.

**Verification**: `npx vitest run server/cast server/test/config-zeroclaw.test.ts server/http/http.test.ts server/sources/zeroclaw server/messages` → todos passam, incluindo os casos acima. Final: `npm test -- --maxWorkers=2` → todos passam.

## Done criteria

- [ ] `npm run typecheck` sai com 0.
- [ ] `npm test -- --maxWorkers=2` sai com 0; testes obrigatórios novos passam sem serviços externos.
- [ ] `npm run i18n:check -- --strict` sai com 0.
- [ ] Testes comprovam que `account()` e escrita de `instructionsFile` não são chamados no caminho ZeroClaw.
- [ ] Testes comprovam dispensa busy com cancel/stop/close ordenados, sem DELETE e sem liberação precoce de vaga; `no_active_response` sem frame conclui e fecha sem timeout.
- [ ] Testes comprovam uma única entrega inicial, sem repetição após reconnect ou resolução tardia.
- [ ] `GET /api/cast/models` funciona antes da primeira sessão e publica `provider`, `defaultModel` e `models:[{alias,model}]`, sem informações privadas.
- [ ] `git diff --exit-code -- scripts/codex-bridge.ts shared/types.ts server/model/cast.ts server/model/office.ts` sai com 0 para as mudanças deste plano.
- [ ] `git status --short` contém somente os arquivos de Scope atribuíveis a este trabalho; alterações preexistentes permanecem intactas.
- [ ] `server/README.md` e `CHANGELOG.md` descrevem a mudança; a própria linha em `plans/README.md` está atualizada.
- [ ] Nenhum `omp-tenant sync`, deploy ou restart foi executado; tenants permanecem pausados.

## STOP conditions

Pare e relate, sem improvisar, se:

- O código divergir dos trechos sem explicação pelos planos anteriores na ordem de merge declarada no drift check.
- O 069 não fornecer abertura confirmada, close intencional, lista persistida de intenções ou metadados que distingam histórico ZeroClaw de OMP.
- O 070 não permitir bloquear/cancelar pendências e aguardar abort terminal antes de fechar uma sessão ocupada.
- Os aliases/variáveis gerados pelo omp-tenant 040 divergirem do contrato SHA-256 descrito aqui. Não crie segunda convenção.
- For necessário trocar system prompt por personagem com garantia de prioridade: o gateway confirmado aqui não oferece esse contrato; instruções são texto contextual.
- Um passo exigir editar arquivo fora de Scope, mudar bridge/tmux, acessar credenciais, ligar tenant ou alterar dados de produção.
- O caminho ZeroClaw tentar resolver conta OMP, criar processo, apagar histórico ou continuar silenciosamente com modelo diferente.
- Uma verificação falhar duas vezes após uma tentativa razoável de correção. Relate comando e erro, sem reduzir o teste.

## Maintenance notes

- O alias é chave de agente do gateway, não nome apresentado ao usuário. Sua estabilidade depende do modelo completo e do algoritmo do 040.
- Revisão deve conferir especialmente corridas start/timeout/reconnect, cancelamento de fila antes da dispensa e cota durante abort pendente.
- `member.last` pode conter sessão OMP histórica; formato UUID não prova runtime. O catálogo persistido do 069 é a autoridade para retomar.
- Fechar WS não interrompe inferência. Preserve a sequência abort terminal e close nas futuras mudanças de dispensa.
- Cliente e rótulos são do Habblaud 072; piloto e publicação são do omp-tenant 043. Este plano não autoriza nenhuma dessas operações.
