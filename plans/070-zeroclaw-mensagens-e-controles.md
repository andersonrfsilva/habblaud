# Plan 070: Entregar mensagens e controles ao ZeroClaw pelo gateway

> **Executor instructions**: Siga este plano passo a passo. Execute cada comando de
> verificação e confirme o resultado esperado antes de avançar. Se ocorrer uma
> condição de STOP, pare e reporte; não improvise. Ao terminar, atualize somente a
> linha deste plano em `plans/README.md`.
>
> **Drift check (run first)**: no repositório `/home/anderson/Desenvolvimento/habblaud`, execute
> `git diff --stat 694938f..HEAD -- server/messages/registry.ts server/messages/zeroclaw.ts server/messages/zeroclaw.test.ts server/messages/http.ts server/messages/http.test.ts server/index.ts shared/locales/en.ts shared/locales/es.ts shared/locales/fr.ts server/README.md CHANGELOG.md plans/README.md`
> Compare os trechos de "Current state" com o código vivo antes de editar se houver mudanças.
> Ordem de merge fixa: Habblaud **068 → 073 → 069 → 070 → 071 → 072**;
> omp-tenant **039 → 040 → 041 → 042 → 043**.
> São **ESPERADAS** as mudanças dos planos anteriores 068, 073 e 069 nos arquivos compartilhados
> `server/config.ts`, `server/index.ts`, `server/README.md`, `server/http/app.ts`, `CHANGELOG.md`,
> `shared/types.ts`, `README.md`, nos catálogos de tradução e no índice, quando previstas nesses planos.
> Esta lista não amplia o Scope: dos arquivos compartilhados, edite somente os autorizados abaixo.
> Compare com os planos/commits anteriores; somente divergência que não venha deles é STOP.
> Arquivos novos deste plano não devem já conter outra implementação concorrente.

## Status

- **Priority**: P1.
- **Effort**: L.
- **Risk**: HIGH: duplicar uma entrega ou trocar uma sessão errada pode executar trabalho no contexto errado.
- **Depends on**: Habblaud **069 — Fonte ZeroClaw** (`plans/069-fonte-zeroclaw.md`).
- **Category**: migration.
- **Planned at**: Habblaud commit `694938f`, branch `producao`, 2026-11-09. Contexto de orquestração: omp-tenant commit `4c978c2`.

## Why this matters

O registro atual entrega ao OMP pela extensão e ao Codex por um entregador próprio. Apenas observar o ZeroClaw não permite enviar mensagens, interromper turnos ou abrir uma conversa nova. Este plano liga o registro ao socket já mantido pelo plano 069, preservando isolamento de sessão, anexos, limites e estados públicos. Não tenta simular recursos do OMP que o ZeroClaw não oferece.

## Current state

### Arquivos e trechos confirmados na base

Todos os caminhos abaixo são relativos a `/home/anderson/Desenvolvimento/habblaud`.

- `server/messages/registry.ts` concentra validação, presença, fila, prazos e resolução. `OfficeLike`, linhas 66–72, permite `get`, `list`, `markDirty`, `addActivity`; não existe dependência de ZeroClaw.
- `server/messages/codex.ts:23-33,77-98` é o padrão de entregador específico: trabalho tipado, resultado explícito e dependências injetáveis. Reutilize esse desenho, não o processo `codex queue`.
- `server/messages/registry.test.ts:37-60` usa relógio injetado e `Office` real com dados sintéticos. `server/messages/codex.test.ts:95-132,150-197` demonstra runner controlado e resolução assíncrona.
- `server/messages/http.ts:33-47` conserva o contrato HTTP: mensagem aceita = 201, indisponível = 409, limite = 429; o registro continua síncrono na admissão e resolve a entrega depois.
- `server/index.ts:326-338` monta `MessageRegistry`, o entregador Codex e `writeAttachments`. Após 069, use a mesma instância de `ZeroClawConnector` da fonte, nunca uma conexão paralela.

`server/messages/registry.ts:330-349`:

```ts
  send(raw: unknown): SendResult {
    const { agentId, text, attachments, deliverAs, expectedSessionId } = parseSend(raw);
    const real = this.opts.office.get(agentId);
    const demo = real ? undefined : this.opts.demoAgent?.(agentId);
    const agent = real ?? demo;
    if (!agent) return { error: 'not-found' };
    if (expectedSessionId !== undefined && expectedSessionId !== agent.sessionId) return { error: 'unavailable', reason: ERR_SESSION_CHANGED, code: 'session_changed' };
    if (deliverAs !== undefined && agent.provider !== 'omp') throw new InvalidRequest('deliverAs só é aceito para OMP');
    const reason = unavailableReason(agent, real ? this.canMessage(agentId) : demo!.canMessage === true);
    if (reason) return { error: 'unavailable', reason };
    if (this.openCount(agentId) >= MAX_OPEN) return { error: 'too-many' };
    const store = this.opts.attachments;
    if (attachments?.length && (!store || !this.opts.writeAttachments)) throw new InvalidRequest(tr('um anexo expirou: anexe de novo'), true);
    const files = store?.take(attachments, MESSAGE_ATTACHMENTS_MAX, 'messages') ?? [];
    if ('error' in files) throw new InvalidRequest(files.error, files.attachmentsExpired);

    const entry = this.entry(agentId, agent.sessionId, { text, activity: describeMessage(text) });
    if (agent.provider === 'omp') entry.msg.deliverAs = deliverAs ?? 'followUp';
    if (demo) entry.demo = true;
    else if (real?.provider === 'codex') entry.codex = true;
```

`server/messages/registry.ts:392-405`:

```ts
  control(raw: unknown): SendResult {
    const { agentId, expectedSessionId, ...change } = parseControl(raw);
    const real = this.opts.office.get(agentId);
    const demo = real ? undefined : this.opts.demoAgent?.(agentId);
    const agent = real ?? demo;
    if (!agent) return { error: 'not-found' };
    if (expectedSessionId !== undefined && expectedSessionId !== agent.sessionId) return { error: 'unavailable', reason: ERR_SESSION_CHANGED, code: 'session_changed' };
    if (agent.provider !== 'omp') return { error: 'unavailable', reason: tr('só agentes do omp trocam de modelo e raciocínio pela gaveta') };
    const reason = unavailableReason(agent, real ? this.canMessage(agentId) : false);
    if (reason) return { error: 'unavailable', reason };
    if (this.openCount(agentId) >= MAX_OPEN) return { error: 'too-many' };
    const entry = this.entry(agentId, agent.sessionId, { text: '', control: change });
    this.messages.set(entry.msg.id, entry);
    return { message: { ...entry.msg } };
```

Outros pontos que precisam entrar no mesmo corte:

- `registry.ts:304-316`: `reachable()`/`canMessage()` reconhecem o entregador Codex; os demais dependem de poll do plugin.
- `registry.ts:369-383`: `prepareAttachments()` espera a gravação, confere novamente agente e sessão e usa `promptWithAttachments()`.
- `registry.ts:412-424`: `aside()` recusa todo provedor diferente de OMP.
- `registry.ts:460-490,500-510`: `inbox()`/`ack()` excluem Codex; também precisarão excluir ZeroClaw, inclusive agentes com UUID coincidente.
- `registry.ts:566-603`: `tick()` aplica prazos, saída do agente, mudança de sessão e limpeza, terminando com `pumpCodex()`.
- `registry.ts:664-683`: `finish()` apaga o texto, publica atividade somente quando apropriado e chama `onSettled`; não crie uma resolução paralela.

`server/spawn/attachments.ts:185-191`:

```ts
/** O pedido com a lista dos anexos no fim (o agente lê os arquivos pelos caminhos). */
export function promptWithAttachments(prompt: string, paths: readonly string[]): string {
  if (!paths.length) return prompt;
  const list = paths.map(p => `- ${p}`).join('\n');
  const head = paths.length === 1 ? tr('Anexo enviado pelo Habblaud (leia antes de começar):') : tr('Anexos enviados pelo Habblaud (leia antes de começar):');
  return `${prompt.trim() ? `${prompt.trimEnd()}\n\n` : ''}${head}\n${list}`;
}
```

`server/model/office.ts:390-405` já oferece a operação de identidade necessária. Reutilize-a pela fonte do 069; não edite `Office`:

```ts
  /** Mesmo processo, sessão nova (/clear, /resume): o personagem continua, tarefas e números zeram. */
  switchSession(id: string, sessionId: string): void {
    const rec = this.agents.get(id);
    if (!rec || rec.info.sessionId === sessionId) return;
    const info = rec.info;
    const bound = this.cast.binding(info.sessionId);
    if (bound) this.cast.bind(sessionId, bound);
    if (!info.cast) this.deps.names.remember(sessionId, { name: info.name, look: info.look });
    info.sessionId = sessionId;
    this.applyCast(info, this.castFor({ id, session: sessionId, room: info.roomId }));
    this.castFallbackNotice(info);
    info.tasks = [];
    info.stats = zeroStats();
    delete info.title;
    this.addActivity(id, { id: `${id}#clear:${++this.seq}`, at: this.now(), ...SPECIAL.cleared() }, true);
```

`shared/omp-events.ts:28` contém `new`, `shake`, `advisorOn`, `advisorOff`. O formato atual de controle pode representar `command: 'new'`, mas isso não torna os demais comandos compatíveis com ZeroClaw. `shared/types.ts:903-909` contém `OmpControl` com `model`, `thinking`, `command`, `stop`, `resume`; não renomeie o tipo nem altere o fio público neste plano.

Textos de erro seguem `tr()`. `shared/i18n.ts:1-12` define pt-BR como origem; traduções ficam em `shared/locales/{en,es,fr}.ts`. Siga `shared/locales/en.ts:1-16`, com a chave portuguesa literal e os mesmos placeholders. `CHANGELOG.md:11-13` tem a seção `Não lançado`; `server/README.md:65-88` documenta a API.

### Contrato obrigatório trazido pelo plano 069

Os arquivos desta subseção são **resultado esperado da dependência**, não código existente no commit-base. Confira as assinaturas no início da execução. Se não existirem com a semântica abaixo, pare antes de implementar.

`server/sources/zeroclaw/connector.ts` exporta `ZeroClawConnector`, compartilhado entre fonte e entregador:

- `openSession({sessionId, agent, name, cwd, cast?, officeAgentId?}): Promise<void>` só resolve após `session_start`.
- `closeSession(sessionId, {replacement?: true}?): Promise<void>` fecha intencionalmente, cancela reconexão e remove a intenção persistida. Não aborta turno nem apaga histórico. `replacement: true` impede a fonte de dar `closeMain` durante `/new`.
- `sendMessage(sessionId, content): Promise<void>` escreve `{"type":"message","content":...}` no **mesmo socket**. Rejeita desconectado. Como o transporte é WebSocket nativo do Node, resolver significa que `send()` aceitou o buffer local, não confirmação de rede ou de aplicação.
- `abortSession(sessionId): Promise<{status:'aborted'|'no_active_response'; terminalConfirmed:boolean}>` chama `POST /api/sessions/<id>/abort`; nunca descarta o corpo nem envia frame `stop`. `no_active_response` devolve `terminalConfirmed:true` sem frame; `aborted` devolve false até confirmação terminal. `refreshTurnState(sessionId): Promise<{busy:boolean;continuityLost:boolean}>` consulta `GET /api/sessions/<id>/state`; estado `idle`/`error` limpa as duas flags e emite `turn-state` com `{sessionId,busy,continuityLost,terminalConfirmed,source}`. `running`/falha/404/corpo desconhecido não confirmam fim nem limpam incerteza; não trate erro como idle.
- `session(sessionId)` fornece descritor com `connected`, `busy`, `continuityLost`, `sessionId`, `agent`, `name`, `cwd`, `cast?`, `officeAgentId`; `busy` vira true sincronamente ao enviar a primeira mensagem e false em `done`/`aborted`. Depois de uma queda, `busy=false` sozinho não comprova término.
- `onEvent(listener)` retorna unsubscribe; eventos `frame`, `session-open`, `session-close` incluem `sessionId`; `frame` contém frame bruto, após atualização do estado. `session-close` distingue `intentional` e `replacement`. Reconexão sem replay/cursor informa `session-open` com `continuityLost:true`.
- Em queda inesperada, o descritor e a intenção permanecem para a reconexão; não se assume que o turno foi abortado. O gateway não reproduz eventos perdidos no socket antigo.
- A fonte mantém `officeAgentId` estável em substituição de sessão e chama `Office.switchSession()` antes de conciliar o novo `session_start`.
- `server/sources/zeroclaw/test-gateway.ts` é a fixture compartilhada do gateway falso; importe-a apenas em testes.
- `onEvent` também publica `message-sent` com id único; o plano 069 já espelha o prompt no JSONL uma única vez. O entregador 070 não grava outra mensagem user nem espelha novamente eventos do gateway.

### Prova HTTP de ausência de turno

Recon atualizado: `/home/anderson/Desenvolvimento/zeroclaw-gluon/src/crates/zeroclaw-gateway/src/api.rs:2222-2256` retorna `{"status":"no_active_response"}` quando não encontra token ativo de cancelamento; esse caso não emite frame `aborted`. O mesmo arquivo, linhas 2175–2217, responde `GET /api/sessions/{id}/state` com `{session_id, state, ...}` ou erro HTTP; `GET /api/sessions/running`, linhas 2141–2171, lista UUIDs em execução. Os estados são `idle`, `running`, `error`, conforme `crates/zeroclaw-infra/src/session_backend.rs:400-408`.

Portanto, fim de turno pode ser comprovado por `done`/`aborted`, por abort validado `no_active_response` ou por estado HTTP `idle`/`error` da sessão exata. `running`, lista vazia sem persistência, HTTP 404 e estado desconhecido não são prova de idle. Não espere frame inexistente depois de `no_active_response`; essa distinção recupera STOP e `/new` após reinício do Habblaud.

### Decisões de transporte e comportamento

Estas decisões estão fechadas; não substituir por ACP, CLI ou bridge:

| Situação | Ação |
|---|---|
| Sessão idle, modo omitido/`followUp`/`steer` | Enviar frame `message` agora. |
| Sessão busy + `steer` | Enviar `message` no mesmo socket; ZeroClaw aplica steering entre iterações. |
| Sessão busy + modo omitido/`followUp` | Manter no registro até `done` ou `aborted`, sujeito aos mesmos limites/prazos da fila. |
| `deliverAs: 'aside'` ou pergunta de `AsideStore` | Recusar com erro explícito: não existe equivalente de conversa paralela. |
| `{stop:true}` | Abort HTTP do UUID capturado, sem fechar socket; aguardar término observado. |
| `{command:'new'}` sozinho | Abrir UUID novo, mantendo personagem, sala e alias; ver passo 3. |
| `model`, `thinking`, `resume`, outros `command` ou combinações | Recusar antes de enfileirar ou fazer I/O. Modelo é o alias escolhido ao chamar, no plano 071. |

O gateway não oferece ack correlacionado por mensagem. `delivered` neste provedor significa **aceita pelo buffer do socket**, não resposta concluída nem steering aplicado. Nunca fabrique um ack de aplicação. Erros `STEERING_QUEUE_FULL`/`STEERING_CLOSED` não carregam o id Habblaud: a fonte mostra o erro da sessão, sem atribuí-lo arbitrariamente a uma mensagem já resolvida. Não reenviar automaticamente depois de uma queda; isso poderia executar o pedido duas vezes.

## Commands you will need

Execute na cópia de desenvolvimento `/home/anderson/Desenvolvimento/habblaud`, nunca em `/home/anderson/apps/habblaud`.

Scripts confirmados por leitura de `package.json:16-28`; seleção de testes confirmada por `vitest.config.ts:3-6`. Não há script de lint no pacote.

| Finalidade | Comando | Resultado esperado |
|---|---|---|
| Tipos | `npm run typecheck` | exit 0; os dois `tsc --noEmit` sem erros |
| Entregador novo | `npm test -- server/messages/zeroclaw.test.ts --maxWorkers=2` | todos passam; gateway falso, sem runtime real |
| Regressões e integração da fonte | `npm test -- server/messages server/sources/zeroclaw --maxWorkers=2` | todos passam |
| Suíte completa | `npm test -- --maxWorkers=2` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0; nenhuma chave faltante/excedente |

Os comandos foram conferidos em recon por leitura, não executados pelo autor. Não é necessário instalar dependência nem construir imagem. Se a instalação local estiver ausente, pare e informe o pré-requisito; não altere lockfile.

## Scope

**In scope** (os únicos arquivos que o executor pode modificar):

- `server/messages/registry.ts` — admissão, fila, presença, prazos e resolução do provedor.
- `server/messages/zeroclaw.ts` — criar o entregador e controles usando o conector existente.
- `server/messages/zeroclaw.test.ts` — criar testes do entregador, registro e ciclo de sessão.
- `server/messages/http.ts` — corrigir somente comentários do contrato; não mudar rotas/status/guards.
- `server/messages/http.test.ts` — adicionar cobertura HTTP do ZeroClaw.
- `server/index.ts` — injetar entregador com o conector já criado pelo 069 e encerrar subscriptions.
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts` — somente traduções dos novos erros.
- `server/README.md`, `CHANGELOG.md` — contrato e nota em `Não lançado`.
- `plans/README.md` — somente status da linha 070, salvo se o revisor mantiver o índice.

**Out of scope** (não alterar):

- Implementação da fonte/conector, provider e espelho JSONL: **Habblaud 069 — Fonte ZeroClaw**. Este plano só consome o contrato pronto.
- `server/cast/launcher.ts`, alias/modelos e chamar/dispensar: **Habblaud 071 — Elenco com ZeroClaw** (`plans/071-elenco-com-zeroclaw.md`).
- Cliente web e Android, rótulos e controles visíveis: **Habblaud 072 — ZeroClaw no cliente web e Android** (`plans/072-zeroclaw-no-cliente-web-e-android.md`).
- Resumidor e uso/cota: **Habblaud 068 — Resumo sem processo OMP** e **073 — Cartão de uso pela cota**.
- Quadlets, proxy, montagens e runtime: **omp-tenant 039 — Cota fio Anthropic Messages** e **040 — Runtime ZeroClaw por tenant**.
- Piloto/deploy/rollback operacional: **omp-tenant 043 — Piloto e rollout ZeroClaw**.
- Alterar `OmpControl`, tipos públicos, comportamento OMP/Claude/Codex, gateway Rust ou adicionar compatibilidade fictícia.
- Histórico do OMP, arquivos de tenants e credenciais. Não executar `omp-tenant sync`, iniciar pods, reiniciar serviços ou publicar release. Todos os tenants estão pausados.

## Git workflow

- Trabalhe em `/home/anderson/Desenvolvimento/habblaud`, partindo de `producao` com 069 integrado.
- Branch: `advisor/070-zeroclaw-mensagens-e-controles`.
- Preserve alterações locais e planos não relacionados; não faça reset/clean nem inclua tudo com `git add .`.
- Commits por unidade lógica, Conventional Commits em português. Exemplo observado: `694938f feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo`.
- Mensagem adequada: `feat(messages): entrega mensagens e controles ao ZeroClaw`.
- Não faça push, merge, PR ou deploy sem instrução do operador.

## Steps

### Step 1: Criar o adaptador de entrega e seus testes de transporte

1. Confirme o contrato 069 acima e leia sua fixture. Reutilize a instância do conector e seu fake gateway; não implemente outro cliente WS/HTTP.
2. Crie `server/messages/zeroclaw.ts` com `ZeroClawDelivery`, recebendo o conector, `isClosing(sessionId): boolean` (padrão false) e callbacks injetáveis para invalidar fila e marcar agente offline se `/new` falhar. Mantenha o adaptador específico e pequeno, no padrão de resultados explícitos de `codex.ts`. O plano 071 liga `isClosing` ao estado `dismissing` do launcher em `server/index.ts`; consulte essa guarda na admissão e antes de despachar texto/controle público. O método interno `stopSession()` continua disponível para a própria dispensa.
3. Exponha consulta de disponibilidade/busy por UUID e envio de texto. Resultado positivo de envio é somente `sendMessage()` resolvido; exceção vira resultado de falha com mensagem curta. Não registre texto, tokens, URL com segredo ou conteúdo dos anexos em logs/erros.
4. Exponha `onEvent`/unsubscribe para o registro acordar a fila em término/reconexão. Não mantenha uma segunda fila de followUp no adaptador; o registro é o único dono das entradas e prazos.
   O conector/fonte 069 já registra o prompt via `message-sent`; não grave transcript no entregador. O teste deve contar uma única mensagem user no espelho para cada envio aceito.
5. Crie `server/messages/zeroclaw.test.ts`, com dados temporários e o gateway falso. Cubra envio idle exato, busy steer no mesmo socket, indisponibilidade e ausência de segundo WS para a mesma sessão. Confira ausência de qualquer frame `stop`.

**Verify**: `npm test -- server/messages/zeroclaw.test.ts --maxWorkers=2` → todos passam, incluindo testes de conexão única e rejeição desconectada.

### Step 2: Integrar a fila, presença, anexos e resolução ao registro

1. Adicione opção específica `zeroclaw` a `MessageRegistryOptions` e marcador correspondente em `Entry`. Não reutilize `codex` nem envie pelo inbox.
2. Atualize `reachable()`, `canMessage()` e `unavailableReason()`: principal presente, sessão correspondente, socket conectado, continuidade conhecida e `isClosing=false` podem receber texto. Sessão sem entregador, reconectando, em dispensa ou com continuidade perdida deve mostrar motivo ZeroClaw, nunca pedir plugin OMP. Mudanças reais de presença chamam `markDirty()`.
3. Em `send()`, aceite `deliverAs` para OMP e ZeroClaw. Para ZeroClaw, recuse `aside` com `InvalidRequest` antes de consumir anexos; omissão = `followUp`. Preserve a rejeição de `deliverAs` nos demais provedores.
4. Antes de qualquer I/O, valide `expectedSessionId`; mantenha `session_changed`, `MAX_OPEN=5`, máximo de texto/anexos e o retorno síncrono 201 com cópia `queued`.
5. Implemente bombeamento específico do ZeroClaw. Confira novamente agente, UUID capturado, estado da entrada e preparação dos anexos. `queued` vira `sent` imediatamente antes da chamada; `sent` vira `delivered` quando o transporte aceita o buffer, ou `failed` se rejeitar. Use `finish()`/`fail()` existentes e preserve `onSettled`/atividade mascarada.
6. Busy + followUp permanece `queued`. Um `steer` posterior pode ultrapassar followUps que aguardam o término; isso é a intenção expressa do modo. Preserve ordem entre followUps e entre steers, sem fazer um followUp bloquear STOP ou `/new`.
7. Após primeiro envio idle, o `busy` síncrono do conector impede que o próximo followUp entre como steering acidental. Ao receber `done`/`aborted` ou `turn-state` com `terminalConfirmed:true`, despache só o próximo followUp ainda válido; não descarregue toda a fila no socket recém-ocioso. Outro agente pode avançar em paralelo. Respeite as travas de substituição/dispensa antes desse bombeamento.
8. Preserve `preparing`, retenção/liberação dos anexos e conferência de sessão em `prepareAttachments()`. Quando a gravação terminar, acorde também a fila ZeroClaw. Texto usa `promptWithAttachments()` sem conversão base64 nem upload ao gateway. Nos tenants, o caminho absoluto já é o mesmo nos dois contêineres.
9. Atualize `tick()` para expirar entradas ZeroClaw com erro próprio, sem mencionar plugin ou Codex. Preserve os prazos existentes de 60 s em `queued`, 30 s em `sent` e retenção de 10 min. Mensagem que expira esperando `done` nunca é enviada depois. Timeout de transporte pode ter resultado incerto; não tente reenviar.
10. Em perda de socket, suspenda bombeamento. Não derive idle apenas de disconnect/reconnect; entradas `sent` nunca voltam a `queued`. Ao `session-open` com `continuityLost:true`, falhe pendências textuais ainda não enviadas com erro claro de continuidade perdida e bloqueie novos textos. Não há replay dos frames do turno antigo. Limpe a incerteza somente com terminal observado ou prova HTTP de ausência de turno da sessão exata. STOP e `/new` continuam admissíveis com socket conectado, mesmo quando `canMessage=false` por essa incerteza. Estado HTTP `running`, erro ou desconhecido mantém o bloqueio; se o controle não obter prova terminal dentro do prazo, falhe sem abrir novo turno.
11. Exclua ZeroClaw explicitamente de `inbox()` e `ack()`, assim como Codex. Ack de plugin, mesmo com UUID coincidente, não pode resolver entrada ZeroClaw. Não faça `codexPoll()` ou `codexAck()` enxergarem suas entradas.
12. Traduza novos erros em en/es/fr. Adicione testes de cada regra neste passo, inclusive preparação assíncrona de anexos seguida por troca de UUID e liberação única.

**Verify**: `npm test -- server/messages --maxWorkers=2` → testes novos e regressões OMP/Claude/Codex passam. `npm run typecheck` → exit 0.

### Step 3: Implementar STOP e conversa nova sem simular comandos OMP

1. Em `control()`, ramifique somente quando `provider === 'zeroclaw'`; mantenha o caminho OMP intacto. Aceite exclusivamente `{stop:true}` ou `{command:'new'}` sem outros campos de controle. `expectedSessionId` continua permitido. Recuse combinações, `model`, `thinking`, `resume`, `shake`, `advisorOn` e `advisorOff` com motivo explícito, antes de criar entrada ou fazer I/O. Para os dois controles suportados, exija sessão presente/conectada e `isClosing=false`, mas não rejeite somente por `continuityLost`; eles são a tentativa explícita de recuperar a sessão.
2. Em `aside()`, retorne indisponível com motivo específico ZeroClaw. Não converta pergunta paralela em steer/followUp. Recusa não cria entrada nem atividade.
3. Exponha `ZeroClawDelivery.stopSession(sessionId): Promise<void>`. Se busy ou com `continuityLost`, assine os eventos terminais **antes** do POST abort para não perder o frame rápido. Aceite `aborted` ou `done` concorrente do mesmo UUID, ou evento `turn-state` com `terminalConfirmed:true`. Inspecione o resultado do POST: `no_active_response` com `terminalConfirmed:true` é sucesso terminal imediato, sem esperar frame; o conector limpa `busy`/`continuityLost`. `status:'aborted'` com false confirma solicitação, não término: espere frame ou chame `refreshTurnState(sessionId)` sob o mesmo prazo. Estado HTTP `idle`/`error` comprovado por esse método também confirma fim, inclusive depois de reinício; `running` mantém espera, 404/erro/corpo desconhecido não prova sucesso. Idle com continuidade conhecida = sucesso sem criar turno. Sem prova terminal no prazo = falha explícita, sem reenviar abort nem fechar socket.
4. Use prazo de controle compatível com `SENT_TIMEOUT_MS=30_000`, relógio/timer injetável e remoção garantida do listener em qualquer saída. STOP não espera followUps; impede novo dispatch durante a interrupção. Ao confirmar término, resolve o controle e libera próximo followUp ainda válido. STOP isolado não cancela followUps: esse é o contrato pedido de entregar depois de `aborted`.
5. Exponha `ZeroClawDelivery.cancelSession(sessionId, reason): void`, ligado a callback do registro. Ele falha todas as entradas ainda `queued` daquele UUID, exceto o próprio controle `/new` em andamento, e as remove da elegibilidade de envio. O plano 071 usa essa operação **antes** de dispensar, para não executar um followUp ao abortar a sessão que está saindo.
6. Exponha `ZeroClawDelivery.newSession(sessionId): Promise<string>`. Capture descritor e identidade antes de fechar qualquer coisa. Marque a sessão em substituição e bloqueie novos envios/controles concorrentes para o UUID; invalide pendências antigas com `ERR_SESSION_CHANGED`.
7. Se busy ou com `continuityLost`, execute `stopSession()` com bombeamento suspenso; nem frame terminal nem confirmação HTTP podem disparar a fila antiga. `no_active_response`/estado HTTP terminal liberam a substituição sem esperar frame. Só depois feche `closeSession(old, {replacement:true})`. Gere `crypto.randomUUID()` e abra `openSession()` com UUID novo e os mesmos `agent`, `name`, `cwd`, `cast` e `officeAgentId`. Não mande a string `/new` ao modelo e não use `DELETE /api/sessions`.
8. A fonte 069 aplica `Office.switchSession()` em `session_start`; isso mantém personagem/visual/sala e limpa tarefas/estatísticas. Só confirme a entrada de controle `/new` depois de `openSession()` resolver. Não migre followUps, texto ou anexos pendentes ao novo contexto. O histórico antigo fica disponível, sem alterações.
9. Se abortar falhar, não feche/abra outra sessão. Se abrir a nova falhar após fechar a antiga, falhe o controle e chame callback `onReplacementFailed(officeAgentId)` que `server/index.ts` liga a `office.closeMain()`. Não restaure silenciosamente o UUID antigo, não finja sucesso e não deixe personagem fantasma como conectado. Capture e encerre listeners no caminho de erro.
10. Operações `/new`/STOP são por sessão, não uma trava global. Coalesça a operação terminal interna concorrente ou recuse claramente segunda mutação do mesmo UUID; nunca faça dois aborts/replacements concorrentes. Callback atrasado de outro UUID não resolve o controle atual. A própria entrada `/new` em `sent` não deve falhar só porque ela provocou a troca de sessão.
11. O plano 071 consome `cancelSession`, `stopSession` e `newSession`; ele deve atualizar o vínculo do personagem pelo `session-open` novo com o mesmo `cast`/`officeAgentId`. Não altere o launcher neste plano.

**Verify**: `npm test -- server/messages/zeroclaw.test.ts --maxWorkers=2` → testes de STOP, `/new`, falhas e recusas passam. `npm run typecheck` → exit 0.

### Step 4: Ligar a composição, provar HTTP e registrar o limite de entrega

1. Em `server/index.ts`, construa `ZeroClawDelivery` com o conector já usado pela fonte 069 e injete-o no registro. Ligue cancelamento de fila sem dependência circular de módulos, por callback depois da construção. No shutdown, remova subscriptions/timers do entregador e do registro; não destrua conector compartilhado duas vezes.
2. Mantenha `config.messages` e guards atuais; recurso desligado não oferece mensagens ou controles por um caminho alternativo. Não crie endpoint sem autenticação para atingir o gateway.
3. Em `server/messages/http.test.ts`, componha fonte/conector/registro com fake gateway e `Office` real. Teste 201 e consulta de estado, 400 aside, 409 recursos incompatíveis/sessão alterada/indisponível e 429 excesso. Corpo inválido continua 400; agente ausente continua 404. Use os testes existentes como padrão, sem editar `http/app.ts`.
4. Atualize comentários de `server/messages/http.ts` e a seção de mensagens em `server/README.md`. Escreva a matriz de suporte e a diferença entre entrada em buffer, aplicação de steering e resposta concluída. Registre a ausência de ack de aplicação e de replay automático. Anexos permanecem caminhos; confirme que documentação não promete suporte de mídia nativa.
5. Adicione nota curta em `CHANGELOG.md` sob `Não lançado` e atualize traduções restantes. Ao terminar, atualize somente a linha 070 no índice.

**Verify**: `npm test -- server/messages server/sources/zeroclaw --maxWorkers=2` → todos passam. `npm run typecheck` e `npm run i18n:check -- --strict` → exit 0. `npm test -- --maxWorkers=2` → suíte completa passa.

## Test plan

Crie testes nomeados em `server/messages/zeroclaw.test.ts`, seguindo `server/messages/codex.test.ts` para composição e `registry.test.ts` para relógio/limites. Use o fake gateway 069; não runtime real, proxy real, credenciais ou pods. Controle os frames e registre sockets, requests HTTP e frames recebidos. Limpe servidores, listeners e diretórios temporários em `afterEach`.

Casos obrigatórios:

1. Idle envia exatamente um frame `message`; preserva espaços/texto e usa UUID esperado.
2. Busy steer chega ao mesmo socket antes do `done`, sem conexão nova.
3. Busy followUp não envia; `done` libera apenas um; segundo espera outro término.
4. `aborted` também libera followUp no STOP comum; `/new` e dispensa cancelada não liberam a fila antiga.
5. Steer ultrapassa followUp bloqueado; agentes diferentes não se bloqueiam.
6. `queued → sent → delivered` significa buffer aceito, não `done`; rejeição síncrona/assíncrona vira `failed`, `onSettled` e atividade ocorrem conforme o registro.
7. Erro de steering sem correlação aparece pela fonte e não marca arbitrariamente uma entrega anterior como falha.
8. Queda após buffer aceito nunca faz replay; `continuityLost` na reconexão falha followUps pendentes e bloqueia texto. Busy falso sem terminal/prova HTTP não libera a fila. Após reinício, STOP com sessão parada recebe `no_active_response`, não recebe frame e mesmo assim resolve, limpa incerteza e libera a sessão. `/new` usa a mesma prova e abre UUID novo. Estado HTTP `idle`/`error` resolve; `running`, 404 e corpo inválido não fabricam sucesso. `isClosing=true` recusa novos textos/controles entre cancelamento e fechamento, sem bloquear `stopSession()` interno da dispensa.
9. Anexo é somente lista de caminhos absolutos no conteúdo; nada envia antes de `writeAttachments` resolver. Falha, saída/troca de sessão e expiração liberam reserva uma vez e não entregam depois.
10. `expectedSessionId` antigo recusa texto e controle sem consumir anexo nem fazer I/O; troca de sessão entre admissão e despacho falha a entrada antiga.
11. STOP registra um POST `/api/sessions/<UUID>/abort`, zero frames `stop`; resolve em `aborted`, `done` concorrente, `no_active_response` sem frame ou estado HTTP terminal validado. `status:'aborted'` com estado `running` não resolve antes da prova de término. POST recusado, timeout e desconexão sem prova HTTP não fabricam sucesso. Sessão idle não abre turno.
12. `/new` preserva personagem, nome/look/cast/sala e alias; UUID muda, transcript anterior permanece. É confirmado somente em `session_start`; `tasks`/estatísticas reiniciam por `Office.switchSession`. UUIDs diferentes não se bloqueiam.
13. `/new` busy assina término antes do abort; abort falho não abre UUID novo; open falho não deixa agente fantasma; callback atrasado não resolve controle errado. Dois `/new` concorrentes não abrem duas sessões.
14. `aside` por `deliverAs` e por `AsideStore`, model, thinking, shake, advisor, resume e combinações são recusados, sem mensagem, socket novo, POST ou atividade.
15. Limites `MAX_OPEN`, 60 s queued, 30 s sent, limpeza e ausência/retorno de presença. FollowUp expirado não sai após `done` tardio.
16. Inbox/plugin ack e Codex poll/ack não entregam nem confirmam entradas ZeroClaw, mesmo com UUID igual entre provedores.
17. Desligar mensagens conserva guards HTTP. Testes existentes OMP/Claude/Codex continuam intactos.

Em `server/messages/http.test.ts`, cubra os status públicos indicados no passo 4 e consulta GET sem texto/anexos/credenciais na resposta. Não reduza a suíte para conseguir passar.

**Verificação**: `npm test -- server/messages server/sources/zeroclaw --maxWorkers=2` → todos os casos acima e regressões passam; `npm test -- --maxWorkers=2` → suíte completa passa.

## Done criteria

Todos devem valer:

- [ ] `npm run typecheck` termina com exit 0.
- [ ] `npm test -- server/messages server/sources/zeroclaw --maxWorkers=2` termina com exit 0, com cobertura dos 17 grupos acima.
- [ ] `npm test -- --maxWorkers=2` termina com exit 0.
- [ ] `npm run i18n:check -- --strict` termina com exit 0.
- [ ] Testes comprovam socket único para steer, FIFO de followUp por término, abort HTTP e UUID novo preservando personagem.
- [ ] Testes comprovam zero I/O em recusas e ausência de replay após envio/queda.
- [ ] GET de estado continua sem texto; estados e callbacks usam o registro existente.
- [ ] Docs declaram que delivered é buffer local, não confirmação do gateway ou conclusão do turno.
- [ ] `git diff --name-only` e `git status --short` mostram somente alterações deste trabalho nos arquivos in-scope, descontadas alterações pré-existentes registradas no início.
- [ ] Linha 070 de `plans/README.md` atualizada pelo executor ao terminar.
- [ ] Nenhum pod/serviço foi iniciado/reiniciado; nenhum `omp-tenant sync`, deploy, migração de histórico ou alteração de credenciais foi executado.

## STOP conditions

Pare e reporte, sem improvisar, se:

- Código vivo diverge dos trechos da base sem ser mudança esperada e identificada dos planos anteriores 068, 073 e 069, na ordem de merge fixada no drift check.
- 069 não está integrado, fake gateway não está disponível ou seu conector não fornece estado HTTP/resultado de abort, conciliação de `busy`/`continuityLost`, eventos terminais e identidade estável para substituição.
- `closeSession(...,{replacement:true})` fecha o personagem no escritório ou a fonte não aplica `Office.switchSession()` no novo UUID. Não corrija isso editando a fonte fora do escopo.
- O gateway real/fake exige outro WS para steering ou produz `done` duplicado que faz a fila despachar duas vezes. Ausência de frame em `no_active_response` é comportamento esperado, não STOP. Pare se resultado HTTP e estado não puderem ser conciliados sem assumir idle ou ampliar escopo.
- A integração exige garantia de confirmação de aplicação por mensagem. O protocolo atual não oferece isso; não invente ack nem prometa entrega ao modelo.
- O mesmo caminho absoluto de anexo não fica visível no contêiner ZeroClaw do tenant. Essa correção pertence ao omp-tenant 040; não copie o arquivo por uma rota nova.
- É necessário tocar qualquer arquivo fora do escopo, alterar gateway Rust, dependência, lockfile, credencial, guard ou contrato HTTP público.
- Um gate falha duas vezes após tentativa de correção fundamentada; reporte comando e menor erro decisivo.
- Alguma verificação exigir reativar tenants, usar credenciais reais, executar sync ou reiniciar produção. O piloto autorizado pertence ao omp-tenant 043.

## Maintenance notes

- `busy` vem do conector, não do status visual do escritório; divergências entre eles não podem transformar followUp em steering.
- `/new` muda contexto, não identidade do personagem. Revise qualquer mudança futura no vínculo `officeAgentId`/`cast` junto com os planos 069 e 071.
- Dispensa cancela pendências antes do abort; STOP comum mantém followUps. Não una essas duas operações sem preservar a diferença.
- Falta ack por mensagem no ZeroClaw. Se upstream adicionar id e confirmação, revise a semântica delivered e os testes; não acrescente replay baseado só em reconnect.
- Rollback deste código não apaga conversas nem retoma sessão OMP: mantenha espelhos/históricos. A reversão operacional e a escolha de runtime pertencem ao omp-tenant 043.
- UI que esconde controles incompatíveis pertence ao 072; as recusas do servidor continuam obrigatórias, inclusive para clientes antigos.
