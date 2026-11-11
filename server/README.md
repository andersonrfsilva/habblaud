# Servidor do Habblaud

Node puro (sem dependências de runtime). Observa as sessões **abertas** do Claude Code em todas as
contas da máquina, mantém o modelo do escritório e transmite tudo via SSE.

```
npm run dev      # servidor + Vite (middleware, HMR) em http://127.0.0.1:4747
npm run build && npm start   # produção: serve dist/client
```

## Fontes de dados (somente leitura)

| O quê | Onde |
| --- | --- |
| Sessões abertas e status (ocupado/ocioso/esperando) | `<config>/sessions/<pid>.json` (+ PID vivo, fora do Docker) |
| Atividades, tarefas, título, números | `<config>/projects/<cwd>/<sessionId>.jsonl` (lê o último ~1 MB no boot; o começo em segundo plano) |
| Subagentes (inclusive de workflows) | `<config>/projects/<cwd>/<sessionId>/subagents/**/agent-*.jsonl` + `.meta.json` |
| Conta (e-mail, organização) e cache de uso | `~/.claude.json` (conta padrão) ou `<config>/.claude.json` — só esses campos |
| Uso ao vivo (5h e semanal) | `~/.habblaud/usage/<conta>.json`, gravado pelo mod do Habblaud (`npm run mod:install`) ou pelo `scripts/statusline-tap.mjs` (`npm run usage:install`) |
| Atalho da conta (`c`, `d`...) | linhas `alias x='... claude ...'` de `~/.zshrc`, `~/.bashrc`, `~/.zprofile`, `~/.bash_profile` |
| Uso do plano das contas do omp (5h e semanal) | `omp usage --json --redact` (comando, não arquivo; a rede é do omp, com as credenciais e o cache dele) |

**Forks** (subagentes que herdam o contexto do pai): o transcript começa com uma linha `fork-context-ref`, a
cópia da chamada `Agent` do pai (mesmo `tool_use` id do `.meta.json`) e o resultado dela ("Fork started…") junto
com a instrução do fork. O parser ignora essa cópia — senão o resultado herdado parece o fim do subagente e o fork
nunca entra no escritório — e usa o texto depois de `</fork-boilerplate>` como o pedido dele.

## ZeroClaw (opt-in)

`HABBLAUD_ZEROCLAW_URL=http://127.0.0.1:42617` liga a fonte do gateway ZeroClaw Gluon.
Ausente ou vazia, não abre rede, temporizadores nem diretórios dessa fonte. A URL aceita somente
HTTP em loopback, sem credenciais, query, fragmento ou caminho. Este desenho usa `require_pairing=false`
no gateway isolado do tenant, sem porta publicada. Não existe variável de token ZeroClaw.

Com essa URL configurada e válida, o elenco usa ZeroClaw, sem auxiliar do host, conta OMP ou tmux.
Sem a URL, o elenco continua usando OMP; configuração ZeroClaw inválida não faz fallback.
O runtime ZeroClaw exige também:

| Variável | Formato |
| --- | --- |
| `HABBLAUD_ZEROCLAW_AGENTS` | JSON não vazio `[{"alias":"m_<12 hex>","model":"provedor/modelo"}]`, na ordem do catálogo |
| `HABBLAUD_ZEROCLAW_DEFAULT_AGENT` | Alias padrão presente nesse catálogo |

O alias é `m_` seguido dos primeiros 12 dígitos hexadecimais minúsculos de SHA-256 do identificador
`model` completo em UTF-8. O mesmo alias identifica o agente e o modelo na configuração do gateway.
Não se usa slug, índice nem remoção de pontuação. Modelos e aliases devem ser únicos, sem espaços,
controles ou curingas nos modelos; catálogo parcial, hash incorreto e default desconhecido são recusados.
No perfil, `model` guarda o alias técnico. Um identificador legado exatamente igual a uma entrada
é resolvido em memória, sem regravar perfis nem remover sufixos; modelo desconhecido recebe 422.
Campo ausente usa o alias padrão. Retomar mantém UUID e alias originais, exige metadados ZeroClaw
na mesma sala e recusa modelo incompatível; histórico OMP não vira conversa ZeroClaw.

`GET /api/cast/models` funciona antes da primeira sessão, sob a mesma trava do terminal.
ZeroClaw devolve `{provider:'zeroclaw',models:[{alias,model}],defaultModel:alias}`, na ordem configurada:
`model` é o identificador legível, e `alias` é a chave enviada e armazenada.
OMP devolve `{provider:'omp',models:[]}` e mantém sua descoberta existente.
O endpoint não expõe URL, credenciais nem TOML e aceita somente GET.

Na conversa nova, instruções do personagem são contexto textual, não system prompt:
`Instruções do personagem:\n<instructions>`, seguido de `\n\nPedido inicial:\n<prompt>` quando houver pedido.
Sem instruções, envia somente o pedido; sem ambos, abre a sessão sem inferência.
Retomar envia apenas o pedido opcional, sem repetir instruções. Cada campo mantém o limite de 8.000 caracteres.

A dispensa bloqueia novos envios e cancela pendências com `cancelSession`; se houver turno ativo ou
continuidade incerta, aguarda `stopSession` confirmar término antes de `closeSession`.
Sessão ociosa fecha sem abort. Fechar conexão não interrompe inferência, e nenhuma dispensa usa DELETE:
o histórico e a referência para retomada permanecem. Falha ao parar mantém sessão aberta e vaga ocupada.
`HABBLAUD_CAST_MAX` conta também sessões desconectadas e em dispensa até o fechamento confirmado.
A dispensa por ociosidade usa o limite existente de 120 minutos ou `idleMinutes`; `0` desliga a regra.

O estado fica em `<HABBLAUD_DATA_DIR>/zeroclaw/` (por padrão `~/.habblaud/zeroclaw/`):
`open-sessions.json` guarda intenções de sessões abertas; `sessions.json` guarda metadados locais;
`sessions/<UUID>.jsonl` preserva prompts, deltas e ferramentas no formato de leitura Claude.
Somente intenções explícitas são retomadas. Inventário HTTP e arquivo histórico não são presença.
Uma sessão aparece no escritório apenas com socket confirmado, gateway alcançável e projeto local conhecido.
O histórico HTTP não informa projeto nem recupera ferramentas; sessões externas podem não ter sala.

Reconexão conserva UUID e alias, usa backoff e nunca reenvia prompts. Quedas durante turnos deixam
aviso de lacuna; `/state` reconcilia se ainda há inferência, sem inventar resposta, sucesso ou ferramentas.
Fechar socket não interrompe inferência. STOP usa aborto HTTP; `no_active_response` confirma término
mesmo sem frame WS. Tokens/custos do frame `done` não alimentam contexto nem uso da conta.
O JSONL permanece legível com gateway offline e depois do fechamento da sessão.

Esta fonte compartilha o conector com a entrega de mensagens e controles descrita abaixo.
O elenco usa esse mesmo transporte; a interface ZeroClaw depende do plano 072.
Para rollback, remova `HABBLAUD_ZEROCLAW_URL` e preserve a pasta `zeroclaw/`.


## Esperando o shell

O status `shell` (ver `shared/types.ts`) é o agente que terminou o turno com comando(s) rodando em segundo
plano. Vem do registro (`"status": "shell"`, Claude Code 2.1.292+, que não conta monitores); em versões que
não gravam `shell`, registro `idle` + Bash em segundo plano sem notificação há menos de 12 h vira `shell`.

`sources/shells.ts` rastreia, por sessão (principal e subagentes), os `ShellJob` publicados em `AgentInfo.shells`:
Bash com `run_in_background` (o id passa a ser o `backgroundTaskId` do tool_result), Bash em primeiro plano ainda
sem resultado (`background: false`; some do lugar enquanto a sessão espera aprovação) e `Monitor` (`kind: 'monitor'`).
Término: `<task-notification>` (linha `queue-operation`/`enqueue`, mensagem user ou anexo) casando `task-id` ou
`tool-use-id`; `TaskStop`/`KillShell`/`KillBash`; fim de turno/interrupção (primeiro plano); `/clear` ou sessão
encerrada; jobs anteriores ao processo atual (sessão retomada) ou com mais de 24 h. O fim de um shell em segundo
plano vira a atividade `tool: 'ShellDone'` (`error` = falhou/interrompido; o mundo comemora ou lamenta) e um aviso;
enquanto o status é `shell`, o balão é "⏳ Esperando o shell: <rótulo>" (`tool: 'ShellWait'`).

## GitHub no escritório

`sources/github.ts` acha eventos do GitHub nos transcripts, sem token e sem rede (funciona no Docker): o parser guarda as
chamadas que interessam (Bash com `git push`/`gh pr create|merge|checks`/`gh run watch|view`/`gh release create`, já sem
heredocs e com os encadeamentos separados, e ferramentas de servidores MCP do GitHub) e lê o resultado delas:

- **fonte preferida:** `toolUseResult.gitOperation`, gravado pelo próprio Claude Code (`{pr: {number, url, action:
  'created'|'merged'}, push: {branch}}`);
- **saída do comando:** URL `…/pull/<n>` do `gh pr create`; `gh pr merge` sem erro nem sinal de falha (e sem `--auto`);
  linhas `abc..def  main -> main` / `* [new branch]` depois de `To <remoto>`; cabeçalho `✓|X <branch> <workflow> · <id>`
  ou `completed with '<conclusão>'` de `gh run watch|view`, `--json`/`--jq` com a conclusão, `gh pr checks` (tabs, resumo
  ou `--json`; pendente não conta); URL `…/releases/tag/<tag>` do `gh release create` (rascunho não conta); JSON do MCP;
- **código de saída:** `gh run watch --exit-status` ou `gh pr checks` como último comando, sem pipe (senão o código é de
  outro programa) — em primeiro plano ("Exit code N") ou em segundo plano (resumo da `<task-notification>`). ≥ 128
  (morto) e 8 (`gh pr checks` pendente) não contam.

Comando com erro, bloqueado ou interrompido não gera evento (só o CI vermelho usa o erro). O sinal `github` vira
`Office.githubEvent`: atividade `tool: 'GitHub'` (`shared/github.ts`) e, só ao vivo (nunca na carga inicial nem para
linhas com mais de 2 min), aviso e efeito na sala, publicado em `RoomInfo.effect` (`{kind: 'party'|'alarm', text, at,
until, agentId}`): festa (PR aberto/mergeado, release) por 12 s; alarme (CI vermelho) até um CI verde na sala (que vira
festa) ou 10 min. Push só avisa. O mesmo CI visto de novo em 2 min não repete o aviso.

## Resumos por HTTP ou RPC

Com mensagens habilitadas, o resumidor usa Anthropic Messages HTTP quando configurado. Não exige binário,
conta OMP nem papel Tiny nesse modo. Sem configuração HTTP, mantém o pool RPC e o papel Tiny existentes.

| Variável | Valor |
| --- | --- |
| `HABBLAUD_SUMMARY_URL` | Base HTTP(S), sem `/v1/messages`, usuário, senha, query ou fragmento; aceita caminho-base e remove barras finais |
| `HABBLAUD_SUMMARY_MODEL` | ID literal de inferência autorizado no gateway; não é alias ZeroClaw nem `@tiny` |
| `HABBLAUD_SUMMARY_TOKEN_FILE` | Caminho do arquivo de token visto pelo processo; somente o caminho fica na configuração |
| `HABBLAUD_SUMMARY_AUTH` | `x-api-key` (padrão) ou `bearer`; nunca deduzido pela porta |

Todas ausentes ou vazias mantêm RPC. Qualquer variável preenchida exige URL, modelo e arquivo; configuração
parcial ou inválida impede a inicialização com erro sem valores sensíveis. Para servidores externos, use
**HTTPS obrigatório**: o token e o texto da conversa seguem para esse endpoint. HTTP destina-se a serviços locais.

O pedido é `POST <base>/v1/messages`, não-stream, com versão Anthropic `2023-06-01`, `max_tokens:512` e somente
o header de autenticação escolhido. Redirects são recusados. O prazo de 90 segundos inclui leitura do corpo.
Somente blocos de texto entram no resumo; thinking e outros blocos não são expostos. Não há retry automático
nem fallback RPC quando HTTP falha. A próxima solicitação pode tentar novamente, pois falhas saem do cache.

O arquivo é lido e aparado a cada chamada efetiva; rotação vale na próxima chamada não atendida pelo cache.
Arquivo ausente, ilegível ou vazio retorna 503 com mensagem fixa. O resumidor não lê `.env` nem token de pareamento
ZeroClaw. Cache, deduplicação e fila são compartilhados pelos modos; por padrão são dois pedidos simultâneos.
`warm()` em HTTP não abre processos nem faz rede.

Exemplo para tenant futuro (somente após omp-tenant 039 e ativação autorizada no 043; montagem pelo 040):

```sh
HABBLAUD_SUMMARY_URL=http://127.0.0.1:4000
HABBLAUD_SUMMARY_MODEL=ID_DE_MODELO_LIBERADO
HABBLAUD_SUMMARY_TOKEN_FILE=/run/secrets/summary-token
HABBLAUD_SUMMARY_AUTH=x-api-key
```

O proxy traduz o token do tenant para Bearer. O caminho deve corresponder ao arquivo montado pelo operador.

Exemplo para host, com gateway direto (não é o gateway de sessões ZeroClaw na porta 42617):

```sh
HABBLAUD_SUMMARY_URL=http://127.0.0.1:4001
HABBLAUD_SUMMARY_MODEL=ID_DE_MODELO_PERMITIDO
HABBLAUD_SUMMARY_TOKEN_FILE=/CAMINHO/ABSOLUTO/summary-token
HABBLAUD_SUMMARY_AUTH=bearer
```

O operador deve criar ou selecionar um token de cliente do gateway com grant para esse modelo. Grave-o em
arquivo 0600 legível pelo processo Habblaud, sem o publicar em comandos, logs ou repositório. Em contêiner,
monte o arquivo somente leitura; o caminho do ambiente é o visto pelo processo. `127.0.0.1` exige rede
compartilhada com o host. Configurar o ambiente de `habblaud.service` e reiniciar para aplicar são tarefas do
operador, não desta implementação. Alterações de modelo/configuração exigem reinício autorizado.

Para voltar ao RPC, remova **todas as quatro variáveis** e reinicie numa instalação que ainda possua OMP,
conta e Tiny. `/api/health.summaryHttp` anuncia apenas HTTP configurado e permissão `messages` nesta conexão,
sem URL, modelo, caminho ou token. É falso em RPC, sem resumidor ou sem autorização remota; não testa o upstream.
O plano Habblaud 072 libera o interruptor Android sem Tiny usando essa capacidade; antes dele, o backend pode
funcionar com o interruptor ainda bloqueado. Os aproximadamente 470 MiB dos processos ociosos são referência
fornecida de produção, não economia validada; medir somente após publicação.

## API

O auxiliar no host (`npm run codex:bridge`) abre sessões omp em `tmux` como `hb-<id>`. Para ver o TUI, use
`tmux attach -t hb-<id>`. A unidade de exemplo `deploy/podman/habblaud-bridge.service` não é ativada automaticamente.

| Rota | Descrição |
| --- | --- |
| `GET /api/stream` | SSE: eventos `snapshot`, `feed`, `notice` (ver `shared/types.ts`); ping a cada 15 s |
| `GET /api/snapshot` | `OfficeSnapshot` atual |
| `GET /api/agents/:id` | `AgentDetail` (histórico de até 200 atividades) |
| `GET /api/agents/:id/terminal` | SSE do terminal: eventos `init` e `append` (`TerminalMessage`); só com bind local (ver abaixo) |
| `GET /api/cast` | `{members}` sem vínculos; acesso local ou pela porta remota após login, com `terminal` em `HABBLAUD_REMOTE_ALLOW` |
| `GET /api/cast/models` | Catálogo configurado ZeroClaw com aliases e modelo padrão, ou `{provider:'omp',models:[]}`; disponível sem sessão; mesma trava do terminal |
| `POST /api/cast` | Cria membro sem `id`; 201, 400 ou 409; mesma trava do terminal |
| `PUT /api/cast/:id` | Edita membro e atualiza os agentes que o vestem; mesma trava do terminal |
| `DELETE /api/cast/:id` | Apaga membro e vínculos; agentes voltam ao sorteio; mesma trava do terminal |
| `PUT /api/agents/:id/cast` | `{cast}` veste um principal; 409 ocupado; mesma trava do terminal |
| `DELETE /api/agents/:id/cast` | Remove o vínculo e volta ao sorteio; mesma trava do terminal |
| `POST /api/cast/:id/start` | `{mode: 'new' \| 'resume', prompt?}`: enfileira uma chamada. Nova sessão abre na pasta pessoal do host; `resume` sem `session` retoma a conversa mais recente do personagem; com `{session, room, account}` retoma a conversa do histórico na pasta dela. 202, 409 (ocupado ou limite de `HABBLAUD_CAST_MAX` personagens abertos), 422 ou 503; mesma trava do terminal |
| `POST /api/agents/:id/dismiss` | Enfileira a dispensa de uma sessão aberta pelo Habblaud; mesma trava do terminal |
| `GET /api/cast/jobs` | `{jobs, available}`: estado das chamadas e disponibilidade do runtime (auxiliar no OMP, conector no ZeroClaw); mesma trava do terminal |
| `POST /api/cast/new` | `{prompt?}`: agente fora do elenco na pasta pessoal; `{room, session, account}` retoma a conversa do histórico; mesmas respostas do start |
| `POST /api/host/cast/poll`, `/api/host/cast/ack` | Auxiliar busca e confirma chamadas e dispensas; somente acesso local |
| `GET /api/sessions/recent` | `RecentSessionsResponse`: sessões dos últimos 7 dias de todas as contas (até 150); mesma trava do terminal |
| `GET /api/sessions/:conta/:sessionId/terminal` | SSE da conversa de uma sessão do histórico (mesmo protocolo do terminal); mesma trava |
| `GET /api/stats?day=AAAA-MM-DD&tz=<IANA>&source=real\|demo` | `DayStatsResponse` do "Meu dia" (ver abaixo); padrões: hoje, fuso do servidor, demo se ligado e o dia é hoje |
| `GET /api/stats/days?tz=<IANA>` | `StatsDaysResponse`: dias com dados reais (e do demo, se ligado), mais recente primeiro |
| `GET /api/timeline/days` | `{recording, days:[{day, bytes, from, to}]}`: dias gravados para o timelapse, do mais recente ao mais antigo |
| `GET /api/timeline/:dia` | o arquivo do dia (`AAAA-MM-DD`) em JSONL (`application/x-ndjson`, gzip se aceito); 400 para dia inválido, 404 sem gravação |
| `GET /api/health` | `{ok, version, demo, docker, remote, terminal, permissions, pty, messages, summaryHttp, codexEvents, ompEvents, semMarca, updates:{state, latest, available}, sources, accounts:[{id, provider?, usageStatus}]}` (`terminal`, `permissions`, `messages` e `summaryHttp` valem para a conexão que perguntou; `pty` nunca vale pela porta remota) |
| `POST /api/demo` | `{enabled: boolean}` liga/desliga agentes simulados (misturados aos reais) |
| `POST /api/rooms/rename` | `{id, name}`: renomeia a sala (nome vazio volta ao padrão, o nome da pasta); `200 {name}`, `400`, `404` (sala desconhecida), `403` (terminal desligado ou fora do acesso local); o nome fica em `rooms.json` na pasta de dados |
| `GET /api/access` | `AccessInfo`: `{remote, local, permissions, messages, messageSessionGuard?, terminal, spawn, world}`: o que esta conexão pode usar (a porta remota só vê o que `HABBLAUD_REMOTE_ALLOW` liberar) |
| `GET /api/mod/summary?account=&session=` | `ModSummary` para o mod do Claude Code: `{version, agents, working, waiting:[{id, name, room, account, waitingFor, since, answerable}]}`, sem o demo e sem a sessão de quem pergunta (ver abaixo) |
| `GET /api/updates` | `{version, ...UpdateStatus}`: versão em uso e o resultado da verificação de versão nova (ver abaixo) |
| `POST /api/updates/check` | "Verificar agora": consulta o GitHub (no máximo uma vez a cada 30 s) e devolve `{version, ...UpdateStatus}` |
| `/api/world/*` | Habblaud World (conta, personagem e prédio; ligado por padrão, `HABBLAUD_WORLD=0` desliga): só pela porta local com `Host` local; ver abaixo |
| `POST /api/permissions` | (hook) registra um pedido de permissão: `201 {id, expiresAt}` ou `200 {skip}`; só com bind local (ver abaixo) |
| `GET /api/permissions/:id/wait` | (hook) long-poll de até 25 s (`?timeout=` em segundos): `{status: 'pending' \| 'decided' \| 'released', ...}` |
| `GET /api/permissions/:id` | (página) detalhe do pedido com os argumentos (`PermissionRequestInfo` com `input`) |
| `POST /api/permissions/:id/decision` | (página) `PermissionDecision`: `{behavior: 'allow' \| 'deny' \| 'terminal' \| 'answer', message?, interrupt?, suggestion?, answers?}` |
| `POST /api/messages` | (página) `{agentId, text, attachments?: string[], deliverAs?, expectedSessionId?: string}`: até cinco IDs; aceita somente anexos; `201 OutboxMessage`; trava `messages` |
| `POST /api/summaries` | `{text}` não vazio, até 32768 caracteres: `200 {summary}`, `422` entrada inválida, `403` sem permissão `messages`, `404` sem resumidor, `503` indisponível e `502` falha upstream (inclusive 429); ver resumos abaixo |
| `POST /api/messages/attachments` | (página) bytes `application/octet-stream` com headers de nome/tipo: `201 {id, name, size}`; até 15 MiB/arquivo, mesma trava `messages` e Origin; não aceita destino |
| `GET /api/messages/:id` | (página) situação da mensagem (`OutboxMessage`: `queued` \| `sent` \| `delivered` \| `failed`) |
| `POST /api/mod/inbox` | (plugin `habblaud-mensagens`) `{session, account?}`: marca a presença da sessão e entrega `{messages: InboxMessage[]}` |
| `POST /api/mod/inbox/ack` | (plugin) `{session, results: [{id, ok, error?}]}`: confirma cada mensagem entregue (ou a falha) |
| `POST /api/messages/control` | (página) `{agentId, model?, thinking?, command?, stop?, resume?, expectedSessionId?: string}`: controles OMP; ZeroClaw aceita somente `{stop:true}` ou `{command:'new'}` isolados; mesmas respostas de `POST /api/messages` |
| `POST /api/codex/events` | (hook do Codex) `{account?, codexHome?, event}`: `200 {ok}`; só pelo computador (a porta remota recebe 403) |
| `POST /api/codex/bridge/poll` e `/ack` | (auxiliar `npm run codex:bridge`) busca as mensagens para o Codex e confirma cada entrega (`{results: [{id, ok, error?}]}`: `200 {ok: true}`); só pelo computador |
| `GET /api/codex/bridge/files/:jobId/attachments/:fileId` | bytes de anexo de mensagem ou spawn para o auxiliar; somente arquivo pertencente a um job enviado e ainda vivo, com a mesma proteção local do poll/ack; nunca pela allowlist remota de arquivos |
| `POST /api/omp/events` | (extensão do omp) `OmpEvent` (`shared/omp-events.ts`): `200 {ok}`; corpo fora do formato: `400`; só pelo computador |
| `POST /api/omp/settings` | `{roomByProject: boolean}`: Configurações › sala do omp segue o projeto; `200 {ok, roomByProject}`, `400`, `404` se desligado; só pelo computador |
| `GET /api/asides?agentId=` / `POST /api/asides` / `POST /api/asides/<topicId>/cancel` / `DELETE /api/asides/<topicId>?agentId=` | (página) bate-papo paralelo com o agente do omp; formatos e códigos em `asides/http.ts`; ler vale com a trava do terminal, perguntar com a das mensagens |
| `POST /api/omp/aside` | (extensão do omp) `AsideProgress`: o texto acumulado da resposta; `200 {cancel: boolean}` diz se a pergunta deve ser abortada; só pelo computador |
| `GET /api/spawn` / `POST /api/spawn` / `GET /api/spawn/:id` | Novo agente pelo escritório: `{available, mode}`; `201` com o pedido criado (`400`, `409`, `429` na recusa); situação do pedido. Com a trava `spawn` (`spawn/spawn.ts`) |
| `GET /api/spawn/folders` / `POST /api/spawn/attachments` | pastas sugeridas (projetos recentes) e anexos do pedido (`spawn/folders.ts`, `spawn/attachments.ts`); mesma trava |
| `GET /api/dictation` / `POST /api/dictation` / `GET /api/dictation/:id` | ditado: `{available}`; envia o áudio (JSON base64); busca o texto (`dictation/dictation.ts`); vale com a trava `spawn` ou `messages` |
| `GET /api/deliveries` / `GET /api/deliveries/file/:id` | entregas (`{deliveries}`) e o anexo de uma delas (`deliveries/deliveries.ts`); mesma trava do terminal |
| `GET /api/files` / `GET /api/files/list?path=` / `GET /api/files/raw?path=[&download=1]` | raízes (pastas das salas e `~/projetos`), conteúdo de uma pasta e um arquivo, em stream (`files/browse.ts`, `http/files.ts`); só leitura, mesma trava do terminal; desligado no Docker |
| `GET /api/pty`, `POST /api/pty`, `GET /api/pty/:id/stream`, `POST /api/pty/:id/input` \| `resize` \| `close`, `POST /api/agents/:id/stop` \| `takeover` | terminal interativo e ações nos agentes (`http/pty.ts`); só com `HABBLAUD_PTY=1`, conexão pelo loopback e `Host` local; nunca pela porta remota |
| `POST /api/host/spawn/poll` \| `ack`, `/api/host/dictation/poll` \| `ack`, `/api/host/files/poll` \| `ack` | (auxiliar do host, `npm run codex:bridge`) busca e confirma os pedidos do Novo agente, do ditado e dos arquivos das entregas; só pelo computador |

`expectedSessionId` é opcional para compatibilidade dos clientes existentes. Quando presente em uma mensagem ou
controle, deve ser uma string não vazia dentro do limite de IDs do servidor. O servidor compara esse valor à sessão
atual do agente antes de enfileirar a operação ou consumir anexos. Se divergir, retorna
`409 {error: ..., code: 'session_changed'}`. Outros conflitos 409 não recebem esse `code`; clientes devem usá-lo,
não o texto traduzido, para identificar a troca de sessão. O metadado não é enviado à extensão omp.

`AccessInfo.messageSessionGuard` anuncia a proteção como `true` somente quando mensagens estão habilitadas e
autorizadas para a conexão. O Android exige esse valor e envia `expectedSessionId` em mensagens e controles; se o campo
estiver ausente ou não for `true`, mantém essas operações indisponíveis. As travas remotas `terminal`, `permissions` e
`messages` são independentes.

Anexos de mensagens e do Novo agente compartilham um único depósito de **120 MiB**, incluindo reservas de uploads
em voo, pendentes e lotes em preparação (`held`). Cada arquivo tem até **15 MiB**; são até cinco por mensagem e oito
por Novo agente. IDs pertencem ao consumidor que fez o upload: mensagem e spawn não podem trocar IDs. Mensagens
não exigem `HABBLAUD_PTY=1`. IDs repetidos, desconhecidos ou vencidos são recusados sem consumir o lote; recusa por
agente ou fila cheia também preserva uploads. ID perdido/vencido retorna `400 {error, attachmentsExpired: true}`:
a interface mantém os `File`, invalida IDs e permite uma nova tentativa manual.

`POST /api/messages/attachments` e `POST /api/spawn/attachments` recebem **somente bytes**, com
`Content-Type: application/octet-stream` e corpo igual ao `File`, sem JSON/base64 nem multipart:

- `X-Habblaud-File-Name`: `encodeURIComponent(file.name)`, até 2048 caracteres codificados. O servidor decodifica
  uma vez e aplica `safeFileName` (NFC, sem separadores/controles, até 120 caracteres).
- `X-Habblaud-File-Type`: MIME original, até 256 caracteres; a regra existente de MIME usa
  `application/octet-stream` como fallback.
- Sucesso: `201 {id, name, size}`; o cliente nunca fornece ID, raiz ou caminho. O POST da mensagem/spawn continua
  JSON e referencia os IDs.
- Erros: `400` para metadados/corpo vazio, `413` acima de 15 MiB, `429` por orçamento ocupado e `415` para formato
  de corpo diferente. As travas existentes continuam retornando `403`; falhas internas continuam `5xx`.

O servidor reserva espaço antes de receber o corpo: `Content-Length` válido até 15 MiB quando presente, ou 15 MiB
para transferência sem tamanho. O stream confere os bytes reais e ajusta a reserva ao terminar; não retém o
conteúdo completo em RAM. Pendentes expiram após 30 minutos. Uploads parciais e falhas liberam sua reserva;
um reinício invalida IDs antigos e remove somente staging órfão próprio, nunca anexos finais.

A mensagem fica publicamente `queued`, mas não sai por inbox/Codex durante a gravação. Agente e sessão são
capturados antes dela; troca de sessão, saída, timeout ou falha de disco não entregam prompt parcial.
Textos comuns preservam a ordem durante a preparação; controles (incluindo STOP) e perguntas paralelas continuam disponíveis.
No Node/Podman o staging fica em `<attachmentsRoot>/.pending/`, no mesmo filesystem dos arquivos finais.
No Docker o servidor usa `<config.dataDir>/attachments-pending/` (no volume `/data`, nunca no tmpfs `/tmp`).
O auxiliar recebe somente descritores `{id, name, type, size}` e `job.id`, tanto para mensagens quanto para spawn.
Ele busca cada conteúdo sequencialmente pelo GET de anexos do job, valida tamanho/bytes/nomes e grava por stream
em `<attachmentsRoot do host>/.pending/`. Só o host escolhe sua raiz; campos de raiz/path do job não são autoridade.
Antes de começar qualquer polling, o auxiliar remove somente diretórios órfãos próprios `bridge-<sufixo mkdtemp>`
nessa `.pending/`, sem seguir links nem tocar pendentes do servidor ou lotes finais. Cada raiz admite um único
auxiliar escritor; a limpeza de restart não roda durante downloads.
A promoção final usa rename local, com nomes únicos; o ACK devolve somente caminhos absolutos finais.
No host, os caminhos continuam em `~/.habblaud/attachments/<UUID>/` ou `HABBLAUD_ATTACHMENTS_DIR`.
Cada lote novo usa diretório exclusivo (0700) e arquivos 0600; a raiz preexistente mantém suas permissões.
Falha/timeout fecha os streams antes de remover apenas os parciais próprios e liberar reservas.
Arquivos finais publicados nunca expiram: limpeza somente manual. ACK perdido não autoriza apagá-los.
Em agentes isolados, monte somente a raiz de anexos RO, no mesmo caminho absoluto e com o mesmo UID.
Habblaud precisa de RW; cada instalação/tenant mantém raiz própria. Não exponha o restante do estado nem monte projetos RW.


O snapshot (SSE e `GET /api/snapshot`) leva só as últimas 8 atividades de cada agente em `recent`; o histórico
de até 200 (inclusive o começo de transcripts longos, lido em segundo plano) vem de `GET /api/agents/:id`.

Borda (`http/guard.ts`, vale para API, estáticos e Vite): `Host` precisa ser `localhost`/`*.localhost`, um IP
ou um nome de `HABBLAUD_ALLOWED_HOSTS` (contra DNS rebinding), senão retorna 403. As rotas de API que alteram estado
exigem `Content-Type: application/json` (415), exceto correspondência exata de **POST + pathname de um dos dois
uploads + application/octet-stream**. Host, Origin da mesma origem ou local, login e permissões continuam
obrigatórios; a exceção não libera CORS/preflight cross-origin. OPTIONS não grava nem reserva espaço.
Respostas JSON saem com `nosniff` e `Cross-Origin-Resource-Policy: same-origin`, sem CORS.

O cutover exige atualizar servidor, navegador e auxiliar juntos, suspendendo uploads durante o reinício e
recarregando páginas antigas. O JSON legado desses dois uploads recebe 415, sem fallback. Reiniciar invalida IDs
pendentes, mas os arquivos locais selecionados permitem novo upload. Para rollback, pare os participantes,
restaure o conjunto anterior de artefatos e recarregue as páginas. Limpe somente staging próprio não referenciado
pelo processo parado; nunca remova arquivos finais antigos. Isso não autoriza publicar a versão JSON/base64 sob
o teto de memória sem sua própria validação.

## Versão nova

`updates/checker.ts` consulta `GET https://api.github.com/repos/<dono>/<nome>/releases/latest`, sem token, 5 s depois
de subir e depois a cada 6 h (1 h depois de uma falha). O repositório vem do campo `repository` do `package.json`
(um fork só precisa trocar esse campo). A consulta manda `If-None-Match` com o ETag da anterior, porque a resposta
304 não gasta o limite de 60 consultas por hora sem token. O resultado (`repo`, `checkedAt`, `etag`, `latest`, `url`,
`publishedAt`) fica em `<dataDir>/updates.json`, para um reinício não repetir a consulta antes da hora. A tag precisa
seguir o semver (`v1.2.3` ou `1.2.3-beta.1`) e é comparada com a versão do `package.json`. Respostas: 404 = nenhuma
release (`ok` sem `latest`); 403/429 = limite do GitHub (`error`). Numa falha, a última release conhecida continua
valendo. O status vai no snapshot em `meta.updates` (`UpdateStatus` em `shared/types.ts`) e, quando muda, o snapshot é
republicado. O link da release só passa se for do próprio repositório no GitHub. Uma versão nova encontrada é
avisada uma vez no log. `HABBLAUD_UPDATE_CHECK=0` desliga (`state: 'off'`, nenhuma consulta).

Cada versão tem a sua seção no `CHANGELOG.md`; um teste falha se a versão do `package.json` não tiver seção, e o
`npm run release` (`scripts/release.ts`) cria a tag e a release com o texto dela como notas.

## Terminal

`GET /api/agents/:id/terminal` (`http/terminal.ts`) transmite a conversa da sessão — prompts, respostas,
ferramentas e resultados, como o Claude Code mostra — montada do transcript por `sources/terminal.ts`, com
segredos mascarados e textos truncados. Ao conectar (e a cada reconexão) vem um `init` com as últimas 500
entradas dos ~4 MB finais do transcript (`truncated: true` se ficou conversa de fora); depois, polling de 400 ms
manda `append` com as entradas novas. Transcript truncado/substituído ou trocado (`/clear` no mesmo processo) =
`init` de novo; arquivo sumido = continua tentando. Agentes do demo não têm transcript: a conversa fictícia sai
de `shared/demo/terminal.ts` (atualizada a cada 500 ms). Ping a cada 15 s, no máximo 8 terminais ao mesmo tempo,
cliente com mais de 8 MB acumulados é desconectado. O que se digita no rodapé do terminal vai como mensagem (ver
[Mensagens pelo escritório](#mensagens-pelo-escritório)); o stream continua só de leitura.

O recurso só existe com **bind local** (`config.ts`, `terminalOffReason`), já que mostra a conversa inteira:

- Node: `HABBLAUD_HOST` loopback (127.0.0.0/8, `::1`, `localhost`); `0.0.0.0`, `::` ou IP de rede desligam;
- Docker: o processo sempre escuta em `0.0.0.0` dentro do container, então vale a porta publicada no host,
  `HABBLAUD_BIND` (o `docker-compose.yml` repassa o mesmo valor ao container): loopback liga; ausente, vazia ou
  qualquer outra desliga;
- `HABBLAUD_TERMINAL=0` desliga sempre; nenhuma variável liga o terminal com a porta exposta.

Além disso, cada requisição precisa de `Host` local (`localhost`, `*.localhost`, 127.x ou `[::1]`): IPs da rede e
nomes de `HABBLAUD_ALLOWED_HOSTS` (proxies, túneis) recebem 403. O estado sai em `meta.terminal` do snapshot e em
`terminal` no `/api/health`. Erros antes do stream respondem JSON `{error}`: 403 (desligado ou acesso que não é
local), 404 (agente ou transcript desconhecido), 405 (método que não é `GET`), 429 (terminais demais) e 500
(transcript ilegível).

## Linha do tempo (timelapse)

`history/timeline.ts` grava o escritório para o timelapse do cliente em `<HABBLAUD_DATA_DIR>/timeline/AAAA-MM-DD.jsonl`
(dia local do servidor; no Docker, `/data/timeline`). Formato e reconstrução em `shared/timeline.ts`; um registro por linha:

- `{"t":"k", at, v, every, boot?, rooms, agents, accounts}`: **keyframe**, o estado completo. Abre cada arquivo, se
  repete a cada 5 min (`every`) e marca o boot do servidor;
- `{"t":"d", at, rooms?, agents?, accounts?}`: **delta**, só o que mudou (`[id, objeto | null]` para salas e contas;
  `[id, campos alterados | null]` para agentes, com `null` = campo ou agente que saiu);
- `{"t":"end", at, reason?}`: o servidor parou (`SIGTERM`/`SIGINT`) ou o dia chegou ao limite (`reason: "limit"`).

Entra cada snapshot novo do `Hub` (`hub.onSnapshot`, ou seja, cada commit do `Office` com mudança), com throttle de
1 s e só quando muda algo que o player desenha. Por agente: id, kind, parentId, sala, nome, look, papel, conta,
status (com `statusSince`), waitingFor, atividade (`kind`, `icon`, `text`, `at`, `tool`, `error`), semente,
background, título e shells resumidos (sem o comando); agentes, salas e contas do modo demonstração (`demo:`) levam
`demo: true`. Contas sem e-mail, organização nem pasta. Nada de transcript: é a mesma exposição do `/api/snapshot`,
por isso as rotas valem com qualquer bind.

- **Limites:** acima de 20 MB no dia, delta a cada 15 s e keyframe a cada 15 min; acima de 30 MB, para até a virada.
- **Retenção:** os últimos 7 dias (contando hoje); só arquivos `AAAA-MM-DD.jsonl` são apagados.
- **Falhas de disco:** nunca derrubam o servidor: um aviso no log, nova tentativa em 1 min, recomeçando com keyframe.
- **Lacunas:** sem nenhum registro por mais de ~1,5× `every` (ou depois de um `end`), o player mostra "sem dados".
- `HABBLAUD_TIMELINE=0` desliga a gravação (as rotas continuam servindo os dias gravados).

O dia na URL só é aceito como `AAAA-MM-DD` de uma data válida e o caminho do arquivo é montado só a partir dele
(nada de `..`, barras codificadas ou bytes nulos). `scripts/demo-timeline.ts` (`npm run demo:timeline`) usa o mesmo
gravador, com relógio simulado e o `DemoSimulator`, para gerar dias inteiros só com dados fictícios.
## Meu dia (estatísticas do dia)

`history/daystats.ts` amostra o snapshot do escritório a cada 1 s e passa os agentes ao rastreador puro de
`shared/daystats.ts`, que integra, por agente, o tempo desde a amostra anterior no status em que ele estava
(`working`, `waiting`, `shell`, `idle`; `done` e `offline` não contam), partindo o intervalo em `statusSince`.
Intervalos de mais de 2 min entre amostras (servidor parado, computador dormindo) não contam. Esperas por você são
episódios de `waiting` contínuo (os de menos de 3 s ficam de fora do ranking); "tempo de relógio com alguém
esperando" une os intervalos de todos os agentes.

- **Contagens:** `AgentStats` é cumulativo por agente, então conta o que passa do maior valor já visto (releitura de
  transcript regravado não conta de novo). Agente que já existia (boot, `/resume`) vira linha de base nos primeiros
  20 s (90 s no boot, enquanto o começo dos transcripts longos é lido em segundo plano); subagente que nasce durante a
  observação e sessão nova (ou `/clear`) contam do zero. Salto impossível numa amostra (mais de 60 ferramentas ou 5 M
  de tokens) é tratado como releitura. Custo que aparece num agente antigo é o total da sessão: só vira referência.
  Prompts saem das atividades `prompt` posteriores ao início do servidor; tarefas, do que sobe no nº de concluídas.
  Agente que some e volta mantém a linha de base por 6 h.
- **Baldes de 1 hora alinhados em UTC** (por sala, por conta e quem esteve presente: sessões e subagentes), guardados
  por dia de arquivo no fuso do servidor. O dia pedido é montado na consulta, no fuso `tz` do navegador: no Docker
  (UTC) o dia do painel continua começando à meia-noite do usuário.
- **Arquivos:** `<dataDir>/stats/AAAA-MM-DD.json` (`StatsDayFile`, versão 1), gravados a cada 30 s e ao encerrar
  (temporário + `rename`); no boot carrega ontem e hoje, indexa os demais e apaga os de mais de 30 dias (de novo a
  cada virada do dia). Arquivo ilegível vira `.corrupt` e o dia recomeça; erros de disco só geram aviso no log.
- **Demo:** agentes com id `demo:` nunca entram nos dados reais. Com o demo ligado há um balde separado, só em
  memória, semeado com um histórico fictício de ontem até agora (`shared/demo/daystats.ts`) e alimentado ao vivo.
- **Exposição:** só números agregados e nomes de projeto, conta e agente (o mesmo que o `/api/snapshot`), então a rota
  não tem a trava de bind local do terminal. Erros: 400 (`day` fora do formato `AAAA-MM-DD`, data inexistente, dia no
  futuro, parâmetro repetido, `tz` ou `source` inválidos), 404 (sem dados, fora da retenção ou demo desligado), 405.

Estáticos (`http/static.ts`): `/bundle/*` (saída do Vite com hash, `build.assetsDir`) com cache `immutable` de
1 ano; o resto (`index.html`, `client/public` em `/assets/*`) com `no-cache` + `ETag`/`Last-Modified` (304).

## Histórico de sessões

`sources/history.ts` lista, sob demanda, os transcripts de primeiro nível `<config>/projects/*/<sessionId>.jsonl` de
cada conta (nome com formato de UUID; subagentes ficam de fora) modificados nos últimos 7 dias, os 150 mais recentes.
De cada arquivo lê só os primeiros 64 KB (projeto = `cwd` da primeira linha que o traz, primeira atividade, o primeiro
prompt) e os últimos 64 KB (título e última atividade; sem nenhuma linha de título ali, procura nos últimos 512 KB),
com leitura assíncrona e um cache por caminho válido enquanto mtime e tamanho não mudam. O título segue o do agente
(`custom-title`/`custom-title.json` > `agent-name` > `ai-title` > `last-prompt`) e, sem nenhum, é o primeiro prompt.
Sessão aberta (agente principal da conta e do `sessionId` no escritório) sai com `open: true` e `agentId`; uma
encerrada sem nenhuma linha com horário é omitida. A lista sai da atividade mais recente para a mais antiga.

`GET /api/sessions/:conta/:sessionId/terminal` (`http/sessions.ts`) usa o mesmo leitor do terminal do agente
(`TerminalStreams.attachSession`: `init` com as últimas 500 entradas dos ~4 MB finais, depois `append`, com polling de
2 s para o caso de a sessão ser retomada) e conta no limite de 8 terminais. Validação: a conta precisa ser uma das
conhecidas (404), o id precisa ter formato de UUID (400) e o arquivo, com links resolvidos (`realpath`), precisa ficar
dentro da pasta `projects/` da conta (404); segmentos que não decodificam respondem 400. As duas rotas exigem a mesma
trava do terminal (recurso ligado e `Host` local, senão 403) e só aceitam `GET` (a lista, também `HEAD`; senão 405).
## Responder pelo escritório

O hook `PermissionRequest` do Claude Code (`mod/habblaud-permissoes/hooks/permission-hook.mjs`, com `matcher: "*"`:
pelo plugin `habblaud-permissoes` no Claude Code 2.1.287+ ou instalado em `<conta>/settings.json` por
`npm run hooks:install`) roda **junto** com o diálogo de permissão do terminal: vale o que
responder primeiro. Em subagentes em segundo plano o Claude Code roda o hook antes e só mostra o diálogo depois que
ele sai. O hook manda o pedido (`session_id`, `agent_id`/`agent_type`, `cwd`, `tool_name`, `tool_input` com textos
cortados, `permission_suggestions` e o próprio `timeout_ms`) e espera; com `decided` imprime
`hookSpecificOutput.decision` (`allow`, com `updatedPermissions` = a sugestão original escolhida, ou `deny` com
`message`/`interrupt`); com `released`, erro ou tempo esgotado sai sem imprimir nada (vale o terminal).

Perguntas (`AskUserQuestion`) passam pelo mesmo caminho. O pedido leva `questions` (mascaradas e cortadas, com a
posição original de cada pergunta e opção em `index`; vão também no snapshot) e a página responde com
`{behavior: 'answer', answers: [{question, options?, other?}]}`, por posição. O registro confere contra o formato
original guardado (cada pergunta uma vez; sem `multiSelect`, uma opção ou o texto livre; `other` até 2.000
caracteres) e entrega `{status: 'decided', behavior: 'answer', answers}`; o hook troca as posições pelos textos
originais do stdin e imprime `allow` com `updatedInput` = a entrada original mais `answers` (`{pergunta: "Rótulo A,
Rótulo B, texto livre"}`, como a documentação dos hooks manda responder o `AskUserQuestion`). `allow` simples numa
pergunta é recusado (400); `deny` vale. Pergunta que o escritório não consegue mostrar inteira (mais de 4, ou
nenhuma válida) não é desviada (`unsupported-tool`).

`permissions/registry.ts` (`PermissionRegistry`):

- só aceita o pedido com alguma página conectada por `Host` local (`Hub.localSize`) e com a sessão conhecida
  (principal pelo `session_id`; subagente por `<session_id>:<agent_id>`, ou o principal com `subagent` = tipo);
  senão `{skip: 'no-viewers' | 'unknown-session' | 'unsupported-tool' | 'too-many'}` (máx. 32 abertos);
- publica o pedido mais antigo de cada agente em `AgentInfo.permission` (sem `input`, que só sai pelo detalhe;
  `queued` = quantos esperam depois) e põe o agente como `waiting`; atividade `PermissionRequest` no feed e aviso
  "pede permissão" (dedupe do "precisa de você"); aprovar/recusar viram atividades também;
- libera o hook (`released`) quando: `terminal` na página, tempo limite do hook + 5 s (`expired`), nenhum hook
  esperando por 8 s (`orphan`), agente que saiu (`gone`) ou resposta no próprio terminal (`answered`): o Claude Code
  não encerra o hook quando você responde lá, então o registro acha a chamada no fim do transcript
  (`permissions/transcript.ts`: nome + assinatura dos argumentos, a mais recente sem resultado) e espera o
  `tool_result` dela; sem a chamada, um principal que esteve `waiting` no registro de sessões e saiu dele há 3 s;
- decisões não buscadas ficam guardadas por 30 s; pedidos do demo (`demo:perm-…`) vão para o simulador.

As rotas (`permissions/http.ts`) seguem a trava do terminal: sem `ServerConfig.terminal` → 403 em
todas; `Host` que não é local → 403. O guard já exige JSON e `Origin` local nos `POST`. Erros: 400 (corpo, sugestão
ou resposta inválidos), 404 (pedido desconhecido, já entregue ou expirado), 405, 409 (já respondido).

## Mensagens pelo escritório

A página manda texto a um agente principal (`POST /api/messages`, pela gaveta do agente ou pelo rodapé do terminal).
No caminho do plugin, `habblaud-mensagens` busca o pedido (`POST /api/mod/inbox`, a cada 2 s) e o entrega com
`$.prompt.submit({ text, asUser: true })`: o modelo lê como se você tivesse digitado. Depois confirma
(`POST /api/mod/inbox/ack`). Detalhes do plugin em [`mod/README.md`](../mod/README.md).

`messages/registry.ts` (`MessageRegistry`):

- **presença:** cada pergunta do plugin marca a sessão (o principal com aquele `sessionId`, e a `account` igual se
  vier); vista há até 10 s, o agente sai no snapshot com `canMessage: true`. ZeroClaw usa o socket compartilhado da
  fonte: exige sessão presente, conectada, continuidade conhecida e não estar em dispensa/substituição.
  O snapshot só muda quando a presença muda;
- **fila:** até 5 mensagens em aberto por agente (429) e 20.000 caracteres por mensagem; o texto vai como foi digitado,
  nunca aparece nas respostas para a página nem no log, e é descartado quando a mensagem se resolve;
- **estados:** admissão síncrona retorna `201` com cópia `queued`; a consulta pode já mostrar `sent`, `delivered` ou
  `failed`. No caminho do plugin, `sent` significa que ele buscou e `delivered` exige confirmação do runtime.
  No ZeroClaw, `sent` começa no despacho e `delivered` significa somente aceitação pelo buffer local do socket,
  não confirmação do gateway, aplicação do steering, leitura pelo modelo nem resposta concluída.
  `queued` expira em 60 s e `sent` em 30 s; saída ou troca de sessão também falha pendências.
  Resolvidas ficam 10 min para o `GET`, sempre sem texto, conteúdo ou caminhos dos anexos;
- entregue: atividade ✉️ "Mensagem pelo Habblaud" no agente (o detalhe mascarado e cortado); agentes do demo recebem
  mensagens fictícias, entregues em ~1 s, sem sessão real.

No `POST /api/messages`, `deliverAs` é opcional para OMP e ZeroClaw; os demais provedores não aceitam o campo.
Omissão preserva `followUp` para clientes antigos. Com OMP livre, qualquer modo inicia um turno; ocupado, `aside`
entra no próximo limite de etapa sem cortar o lote comum de ferramentas, salvo esperas interruptíveis `wait`/`vibe_wait`;
`followUp` espera o fim do turno; `steer` pode pular ferramentas pendentes, mas não desfaz alterações nem garante
cancelar operação iniciada. Upload e polling da extensão, atualmente de até 5 s quando ocupada, acrescentam latência.
O `aside` da mensagem principal não é o chat paralelo efêmero de `/api/asides`. O status retorna o modo aceito.

### Suporte ZeroClaw

| Pedido | ZeroClaw |
| --- | --- |
| Texto sem modo ou `followUp`, sessão idle | Envia `message` no socket existente |
| Texto sem modo ou `followUp`, sessão busy | Aguarda `done`/`aborted` ou confirmação HTTP terminal; libera um followUp por término |
| `steer`, sessão idle ou busy | Envia no mesmo socket; ocupado, o gateway aplica entre iterações |
| `deliverAs:'aside'` | 400; não há conversa paralela equivalente |
| Pergunta de `/api/asides` | Indisponível; não converte em followUp nem steer |
| `{stop:true}` isolado | Abort HTTP da sessão exata, sem frame `stop` e sem fechar socket; mantém followUps válidos |
| `{command:'new'}` isolado | Confirma término, cancela pendências antigas e abre UUID novo com o mesmo personagem, sala e alias |
| `model`, `thinking`, `resume`, `shake`, advisor ou combinações | 409, antes de enfileirar ou fazer I/O |

`expectedSessionId` é permitido também nos dois controles suportados; UUID antigo retorna 409 com
`code:'session_changed'`, sem entregar no contexto novo. `/new` só se confirma após `session_start`.
O histórico anterior permanece; texto e anexos pendentes não migram para a nova conversa.

O gateway não fornece ack de aplicação correlacionado por mensagem. Aceitação no buffer não garante steering
aplicado nem turno concluído. Erros de steering sem ID aparecem na sessão, sem atribuição arbitrária a uma mensagem.
Não há replay nem reenvio automático após queda ou timeout: o resultado pode ser incerto, e repetir pode executar
o pedido duas vezes. Reconexão sem continuidade bloqueia textos e falha pendências ainda não enviadas.
STOP e `/new` podem recuperar uma sessão conectada com continuidade perdida, mas exigem término observado ou
prova HTTP terminal da sessão exata; desconexão, `running`, 404 ou falha HTTP não provam idle.

Anexos são gravados antes do despacho e entram no texto como lista de **caminhos absolutos**, usando
`promptWithAttachments()`. O mesmo caminho precisa estar montado nos contêineres Habblaud e ZeroClaw do tenant.
Não há upload ao gateway, conversão base64 nem promessa de suporte nativo a imagem, áudio ou vídeo.

As rotas (`messages/http.ts`) seguem a trava do terminal e mais uma chave: `ServerConfig.messages` = terminal ligado e
`HABBLAUD_MENSAGENS` não desligado (`0`, `false`, `off`, `no`). Desligado ou `Host` que não é local → 403 em todas.
Erros de `POST /api/messages`: 400 (corpo inválido, texto vazio/longo demais ou aside ZeroClaw),
404 (agente desconhecido), 409 (sessão alterada ou indisponível: subagente, saiu, plugin/socket ausente,
continuidade perdida ou em dispensa) e 429 (cinco entradas em aberto). O estado sai em `meta.messages` do
snapshot e em `messages` no `/api/health`. Controles incompatíveis retornam 409.

Qualquer processo local consegue chamar essas rotas (como as de permissão): ligar as mensagens é aceitar que um
programa da própria máquina possa digitar nas sessões disponíveis, inclusive pelo socket ZeroClaw.

## Elenco de personagens

`cast.json` na pasta de dados guarda até 200 membros e 4000 vínculos de sessão, que expiram após 60 dias. Cada membro
tem `id`, `name`, `look`, `seed`, `kind` (`main`, `advisor` ou `sub`), `rooms` e, opcionalmente, `parts`, `match` e
`home`. Salas vazias permitem todas; `home`, quando definida, deve estar nas salas permitidas. Os nomes são únicos.

O primeiro principal sem vínculo recebe o membro principal da sala; vínculos e `HABBLAUD_CAST=<id>` no ambiente do
omp têm prioridade. Membro ocupado ou fora da sala resulta em sorteio. Advisor do omp e subagentes pelos tipos
observados usam membros compatíveis. As salas organizam a escolha do personagem: não restringem ferramentas.
`HABBLAUD_CAST_MAX` (1 a 50; ausente = sem limite) limita quantos personagens o Habblaud mantém abertos ao mesmo
tempo: sessões `hb-*` e chamadas em andamento contam, quem está sendo dispensado não. No `omp-tenant` vale o número
de salas do usuário.


As rotas do elenco seguem a trava do terminal: acesso local ou pela porta remota após login, com `terminal`
em `HABBLAUD_REMOTE_ALLOW`. A migração de `names.json` › `rooms` cria `cast.json` e preserva o original em
`names.json.pre-031`. Para rollback, restaure essa cópia e remova `cast.json`; vínculos criados depois da migração serão perdidos.

## Codex

Fontes de agentes (`sources/source.ts`): `SourceSet` junta a do Claude Code (`ClaudeWatcher`) e, quando há pastas do
Codex, a do Codex (`sources/codex/`); o histórico idem (`HistorySet`). Agentes, contas, fontes e sessões do histórico
do Codex levam `provider: 'codex'` (ausente = Claude Code). Ids: `<conta>:<threadId>`, `sessionId` = threadId.

- **Contas** (`sources/codex/accounts.ts`): `HABBLAUD_CODEX_DIRS` (substitui) ou `CODEX_HOME` e `~/.codex*` com cara
  de Codex (`isCodexHome`: `thread-writer-locks/`, `archived_sessions/`, `config.toml`, `auth.json` ou
  `sessions/AAAA/`, sem `projects/`, que só o Claude Code cria). Uma pasta do Codex nunca vira conta do Claude Code.
  `auth.json` e `config.toml` nunca são lidos; o plano vem de `rate_limits.plan_type`.
- **Sessões abertas** (`sources/codex/files.ts`, `source.ts`): `thread-writer-locks/<threadId>.lock` existindo há 3 s
  (os locks rápidos de manutenção ficam de fora; o lock nunca é aberto). Lock sem rollout = sessão aberta e vazia: o
  lock não diz a pasta, então o principal só entra quando o rollout (criado no 1º prompt) ou um hook disser o cwd.
  Lock e rollout parados há 12 h, sem evento de hook = lock de crash. Sem a
  pasta de locks: rollout modificado nos últimos 30 min. Evento de hook segura a presença por 60 s.
- **Rollout** (`sources/codex/rollout.ts`): `sessions/AAAA/MM/DD/rollout-*-<threadId>.jsonl` (a pasta é a data de
  criação; sessão retomada continua no arquivo antigo) e `archived_sessions/`, lidos com `FileTail`. Formatos
  "paginated" (padrão) e "legacy"; `.zst` é ignorado com aviso. Status por `task_started`/`task_complete`/
  `turn_aborted`; atividades por `item_completed` (`CommandExecution` como o Bash, `FileChange`, `McpToolCall`,
  mensagens e raciocínio); tokens sem somar o cache de novo (no Codex ele já está dentro da entrada); sem custo; uso do
  plano por `token_count.rate_limits` (janela de 300 min = 5 h, 10080 = semana; `primary` nulo com
  `rate_limit_reached_type` = `noQuota`), empurrado com `accounts.setUsage`. Subagentes = threads com
  `source.subagent.thread_spawn.parent_thread_id`; threads internos (guardian, review, compact, memory) ficam de fora.
- **Terminal e histórico** (`sources/codex/terminal.ts`, `history.ts`): o parser do terminal é escolhido por agente
  (`SourceSet.parserFor`).
- **Eventos dos hooks** (`POST /api/codex/events`, `codex/http.ts`; o hook é `mod/habblaud-codex/hook.mjs`): só com
  `Host` local e conexão pelo loopback (fora do Docker). Vão para `CodexLive.applyHookEvent` da fonte: casam por conta
  (pela pasta `codexHome`) e `agent_id ?? session_id` (no Codex, `session_id` é o thread RAIZ). SessionStart faz o
  agente aparecer, UserPromptSubmit/PreToolUse = trabalhando (com a atividade de agora), PermissionRequest = esperando
  ("aprovar um comando"), Stop = ocioso, SessionEnd fecha.
- **Aprovar pelo escritório** (`permissions/*`, `permissions/codex.ts`): o mesmo `POST /api/permissions` com
  `provider: 'codex'`, `account` e `codexHome`; sem sugestões, sem perguntas, sem a busca no transcript; `interrupt` ou
  `suggestion` num pedido do Codex = 400. O hook espera até `permissionTimeoutS` de `~/.habblaud/codex-hook.json`
  (padrão 25 s) e imprime só `allow` ou `deny` (+`message`). O Codex só mostra a aprovação no terminal depois que o hook
  termina.
- **Mensagens** (`messages/*`, `messages/codex.ts`): para agentes do Codex, `codex queue --thread=<id> --message=<texto>`
  com `CODEX_HOME` = pasta da conta (no host). Fora do Docker o servidor roda o comando (`HABBLAUD_CODEX_BIN` ou `codex`
  do PATH); no Docker, o auxiliar do host (`npm run codex:bridge`) busca em `POST /api/codex/bridge/poll` e confirma em
  `/ack` (mesma trava das mensagens). `canMessage` = há entregador (binário achado, ou auxiliar visto há até 10 s).
- **Uso do plano do omp** (`sources/omp/usage.ts`, `bin.ts`): com uma sessão principal do omp aberta, a fonte do omp
  roda `omp usage --json --redact` (sem shell, `PI_CODING_AGENT_DIR` = agent dir da conta, timeout 20 s) a cada 5 min
  — na hora quando o provedor do modelo em uso muda, 1 min depois de uma falha, uma consulta em voo por vez — e empurra
  com `accounts.setUsage` (origem `'omp'`) as janelas compartilhadas de 5 h e 7 d do report desse provedor (o primeiro;
  limites com `tier` ficam de fora). Provedor sem essas janelas (Ollama, Codex grátis) vira uso sem janelas, com a nota
  do omp. Nunca usa `invalidate` nem `--force-refresh`; `metadata` (e-mails, ids) é descartado. `HABBLAUD_OMP_USAGE=0`
  desliga; no Docker, sempre desligado.
  Retorno 0 = entrou na fila da sessão (`delivered`); o Codex consome a fila a cada ~10 s, quando a sessão fica ociosa.

### Cota da instalação por HTTP

`HABBLAUD_QUOTA_URL` recebe o endpoint completo, por exemplo `http://127.0.0.1:4000/v1/cota`;
`HABBLAUD_QUOTA_TOKEN_FILE` recebe um caminho absoluto com somente o bearer do tenant.
Monte o arquivo somente leitura, legível pelo usuário do servidor. Ele é relido a cada consulta para aceitar rotação.
URL e caminho vêm somente do ambiente do servidor, nunca do navegador. Use loopback do pod ou HTTPS de destino
confiável, sem credenciais na URL, fragmentos ou redirects. Não use token de pareamento ZeroClaw nem credencial
administrativa do gateway. O operador do plano omp-tenant 040 configura mounts/env; este recurso não opera tenants.

A consulta independe de OMP, Docker, binários e sessões. Ela começa imediatamente e repete após 5 min de sucesso
ou 1 min de falha, com timeout total de 10 s incluindo o corpo e uma consulta em voo. A cota global substitui o uso
local em todas as contas reais, inclusive contas registradas depois da coleta; não cria conta sintética.
Os reports representam a mesma cota: seus percentuais não são somados. Antes do primeiro sucesso não há números.
Falhas mantêm o último `fetchedAt`; após 30 min os dados ficam antigos. Janelas cujo reinício passou são omitidas,
nunca convertidas em 0%. Exaustão continua 100%, com a data de renovação.

Sem as duas variáveis, o caminho legado permanece intacto. Configuração parcial ou URL/caminho inválidos impedem
a inicialização com erro sanitizado. No modo HTTP, `omp usage` fica desligado mesmo se o token estiver ilegível
ou o endpoint indisponível; não há fallback. Para voltar ao caminho legado, remova ambas as variáveis e reinicie
o servidor em uma janela operacional autorizada.

## Habblaud World

Ligado por padrão, apontando para `server.habblaud.com` (`DEFAULT_WORLD_URL` em `config.ts`); nada sai do computador
antes de a pessoa criar a conta e entrar, e `HABBLAUD_WORLD=0` desliga tudo (o que sai do computador:
`docs/world/privacidade.md`; como mexer: `shared/world/README.md`). O protocolo com o servidor do mundo é `shared/world/protocol.ts` (fonte da verdade, com
`schema.json`). Do lado daqui, `world/`:

- `settings.ts`: `<dataDir>/world.json` `{deviceId, token, email, access, player?, paused?, left?, updatedAt, theme?}`, modo 600
  e gravação atômica. O token do dispositivo só sai daqui no cabeçalho `Authorization: Bearer` das chamadas ao servidor
  do mundo e no `hello` do WebSocket: nunca no navegador, no snapshot, no log ou na URL. `theme` (a escolha de
  Aparência, `auto` por padrão) fica mesmo sem sessão (o arquivo vira só `{theme}`); `paused` e `left` (saiu do mundo,
  sempre com `paused`) são da sessão.
- `client.ts`: as rotas `/v1/*` com o `fetch` nativo (prazo de 15 s, sem seguir redirecionamento, resposta de até
  256 KB copiada campo a campo; a exportação dos dados, até 8 MB e como veio). Erro do servidor do mundo volta como
  `{status, code}` (a manutenção, `503 maintenance`); rede, prazo e resposta fora do formato ganham
  `world_unreachable`, `world_timeout` e `world_bad_response`.
- `altcha.ts`: o desafio anti-robô do cadastro e dos pedidos de e-mail, resolvido aqui por força bruta (SHA-256), em
  lotes de 5.000 tentativas que devolvem a vez ao event loop.
- `service.ts`: a conta (uma operação por vez). `/v1/me` é relido no máximo a cada 30 s (ou com `?refresh=1`) e, na
  lista de espera, sozinho a cada 30 min (`waitingPollMs`; timer que não segura o processo), para a aprovação chegar ao
  snapshot sem ninguém clicar; um 401 do servidor do mundo numa rota com token apaga a sessão daqui (`lastError:
  session_expired`); entrar de novo revoga o dispositivo anterior; sair apaga o `world.json` mesmo sem resposta do
  servidor. O `lastError` leva só códigos conhecidos (`worldLastError` em `shared/world/api.ts`): rede e prazo viram
  `network`, o resto que a interface não conhece vira `unknown`. Pausa, retomada e saída do mundo vão ao servidor do
  mundo numa fila (na ordem pedida); a retomada sem resposta (rede, prazo, 429, 5xx) tenta de novo com a espera da
  reconexão do uplink (2 s a 60 s), até dar certo ou o prédio ser pausado de novo. Excluir a conta (Fase 6): o código
  vem de `POST /v1/me/delete`; com `POST /v1/me/delete/confirm` aceito (ou um `goodbye: deleted`), fecha o uplink e
  espera, apaga o `world.json` inteiro (tema junto, `store.erase`) e a última remessa, e o status fica sem conta com
  `lastError: account_deleted`. Exportar os dados (`GET /v1/me/export`) só repassa o JSON.
- `uplink.ts`: o prédio no mundo, por WebSocket (o `WebSocket` global do Node) em `<HABBLAUD_WORLD_URL>/v1/socket`
  (`wss://`; `ws://127.0.0.1` e `ws://host.docker.internal` só no modo dev). Conecta só com a conta aprovada, o personagem criado e a trava do
  terminal; pausado ou fora do mundo, só enquanto a cena Mundo está aberta (o viewer avisa por `cityChanged`) e sem
  nenhum `uplink` (nem o do `welcome`): só `hello`, `ping`/`pong` e os comandos da cena. Manda o `hello` (`{v, version, build: {commit, dirty}, locale, token}`) e, a cada `welcome`, o
  pacote inteiro; depois, a cada mudança do escritório (`Office.onChange`) ou a cada minuto, projeta
  (`shared/world/projection.ts`) e manda se mudou: 3 s desde a primeira mudança, no máximo uma remessa a cada 5 s.
  `{t: 'ping'}` a cada 30 s (sem resposta por 75 s, reconecta). Reconexão de 2 s a 60 s (dobra, ±25 %). `goodbye`:
  `deploy` reconecta (na manutenção do mundo, `deploy` a cada `hello`: a espera cresce até o teto e só zera com um
  `welcome`); `banned`, `suspended`, `update` e `other-device` param até retomar (ou reabrir); `waiting`, `no_player`
  e `unauthorized` param até a sessão mudar e avisam a conta (`service.goodbye`); `deleted` para e a conta apaga a
  sessão. Do `welcome` guarda, só em memória e só para o status, o id da conta (`me.id`, no assunto do recurso por
  e-mail) e o selo "não verificado" do próprio prédio (`building.unverified`, que a `area`/`area.patch` da cena
  também atualiza). `uplink.ack {error:
  'left'}` (a conta saiu do mundo, aqui ou em outro computador) e o `left: true` do `/v1/me` gravam `left` e `paused`
  (`service.leftElsewhere`): nenhum pacote até o join (a conexão só fica com a cena aberta). A última remessa
  `{at, bytes, uplink, ack}` fica em memória e em `<dataDir>/world-last-uplink.json` (modo 600; só da mesma sessão,
  apagado ao sair). Avisa o snapshot (`markDirty`) ao conectar, cair, pausar, retomar e a cada ack. Tarefas e
  compactações (`signals.ts`) são observadas pelo mesmo `Office.onChange`, no máximo uma vez por segundo, com ou sem
  conexão; durante o boot do escritório (`Office.isBooting`) nada é observado nem enviado.
- `ids.ts`: os ids do mundo, só em memória (`f_` + 10 hex por sala, `a_` + 8 hex por agente, `crypto.randomBytes`),
  cada um com vida de 24 h ± 6 h sorteada por id; quem some e volta (ou o Habblaud reiniciado) ganha id novo.
- `tokens.ts`: tokens do prédio (entrada + saída) de hoje e dos 7 dias corridos, do "Meu dia" com a fonte `real` (o
  demo nunca entra), arredondados a 2 algarismos; os dias passados ficam guardados, o de hoje é relido a cada 15 s.
- `theme.ts`: o tema escolhido resolvido para o pacote (o automático segue o calendário do escritório, pela data local
  do servidor: no Docker, UTC). `signals.ts`: tarefas concluídas no dia por agente e compactações novas (o `/clear`
  não conta).
- `http.ts`: as rotas `/api/world/*` (lista e formatos em `shared/world/api.ts`). `http/app.ts` só as entrega pela
  porta local com `Host` local (a porta do celular recebe 403 `local_only`, nem com login); tudo, menos o `status`, exige
  a trava do terminal (403 `terminal_off`); com `HABBLAUD_WORLD_URL` recusada, 409 `not_configured`; `HABBLAUD_WORLD=0`, 404.
  Erros: `{error: {code, message?, retryAfter?}}`, com o status e o código do servidor do mundo quando vêm de lá.
  Do prédio: `PUT /api/world/theme` `{theme}` (com ou sem conta), `POST /api/world/pause` (para o envio antes de
  responder, fechando o uplink se a cena Mundo não está aberta, e avisa `POST /v1/me/building/pause`),
  `POST /api/world/resume` (avisa `/resume` e reconecta, ou manda o pacote inteiro pela conexão da cena),
  `POST /api/world/leave` ("Sair do mundo": grava `left` e `paused`, para o envio como a pausa e responde depois do
  `DELETE /v1/me/building`; sem resposta boa, fica só pausado e devolve o erro), `POST /api/world/join` ("Voltar ao
  mundo": `POST /v1/me/building/join` e, só com a resposta boa, tira `left` e `paused` e reabre o uplink; o 404 aqui
  não conta como feito; o `/resume` fora do mundo passa por ele e, sem ter saído, ele é o `/resume`) e
  `GET /api/world/last-uplink` (`{at, bytes, uplink, ack?}` ou `{}`, sem a trava do terminal, como o status). O
  `GET /api/world/status` ganha `building` (conta aprovada com personagem) e `theme`; sem `?refresh=1` responde da
  memória e relê `/v1/me` em segundo plano quando venceu. Da conta (Fase 6): `POST /api/world/delete-account` (`202
  {}`: o código vai ao e-mail), `POST /api/world/delete-account/confirm` `{code}` (responde o status sem conta depois de
  fechar o prédio e apagar o `world.json`), `GET /api/world/export` (o JSON como anexo `habblaud-world-dados.json`;
  como o stream, só pela própria página, porque gasta a cota do dia; `HEAD` não chama o servidor do mundo) e o
  `notifyInactivity` opcional no `PUT /api/world/visibility`.

O estado vai no snapshot em `meta.world` (`{enabled, configured, state, building?, scene?}`, nada da conta; `building` =
`{connected, paused, floors, agents, lastUplinkAt?, left?, stopped?}`, com `connected` = aceso e mandando (a conexão
só da cena, pausado, não conta) e `stopped: true` sem o motivo, que só vai no status; `scene` = `{available}` com a
conta aprovada e o personagem, `available` com a trava do terminal e sem parada de `banned`, `suspended`, `update`,
`other-device` ou `deleted`: o botão Mundo da barra) e em `GET /api/access` (`world`). O build grava
`dist/build.json` (`{commit, dirty, builtAt}`, `scripts/build-info.mjs`; sem git, `unknown`;
`HABBLAUD_BUILD_COMMIT`/`HABBLAUD_BUILD_DIRTY` substituem o git), lido por `readBuildInfo()` (`build.ts`) a cada conexão
do uplink: o `hello` leva só `{commit, dirty}`.

### Cena do mundo: stream e comandos

A cidade (Fase 3) desce pela mesma conexão do prédio e só com a cena Mundo aberta no navegador. `world/viewer.ts` é
o "viewer"; o estado e as regras ficam em `shared/world/scene.ts` (puro, o navegador pode usar o mesmo), e a geometria
em `shared/world/city.ts` (fonte da verdade; o servidor do mundo guarda a cópia exata).

- `GET /api/world/stream` (SSE, as mesmas travas das rotas da conta, e só pela própria página: `Origin` de outro host
  ou `Sec-Fetch-Site` cross-site/same-site recebem 403 `local_only`). No máximo 4 abertos (429 `too_many_streams`).
  Eventos: `world.snapshot` (`WorldSceneSnapshot` de `shared/world/api.ts`: `{stream, connected, viewing, district?,
  me?, pos?, afk, chunks: [{chunk, rev, buildings}], players, minimap}`) ao conectar e a cada recomeço (conexão aberta
  ou caída, `view` de novo): o navegador troca o estado inteiro; depois `world` com cada mensagem da cidade como veio
  (`area`, `area.patch`, `players`, `player.join`, `player.leave`, `pos`, `minimap`, `afk`, `profile`, `notice`,
  `like.update`, `greeting`, `building`, `building.patch`), na ordem e já sem salto de `rev`; `: ping` a cada 15 s.
  Cliente com mais de 1 MB acumulado é desconectado (o EventSource reconecta).
- O primeiro stream (com a conexão aceita) manda `view`; o último que fecha manda `view.off`. A cada `welcome` com
  stream aberto, `view` de novo; a cada queda, a cidade daqui é esquecida e sai um snapshot com `connected: false`.
  Pausado ou fora do mundo, a conexão só existe com a cena aberta: abrir o primeiro stream conecta (`hello`, `view`) e
  fechar o último manda `view.off` e fecha; nenhum `uplink` sai por ela.
- Regras do estado: `area` substitui o chunk e o `rev` (mesmo menor); `area.patch` só com `rev` = último + 1 (item
  com `lot` substitui o prédio, sem `lot` mescla, `removed` tira); salto, chunk desconhecido ou item parcial de prédio
  que não está aqui pedem `resync {chunk}` (no máximo um a cada 5 s por chunk) e o patch não passa; ficam só os 3×3
  chunks em volta da minha posição (o último `pos`, o meu id num `players` ou o último `move`); `players` só mexe em
  quem já está (por id); `player.join` substitui o perfil; `afk` vale até andar ou virar, `view` ou `profile`. Tudo só
  em memória, nada no log.
- `POST /api/world/cmd` `{stream, cmds}` → `202 {sent}`: 1 a 20 comandos, só `move`, `profile`, `resync`, `view`,
  `view.off`, `enter`, `leave`, `like`, `unlike`, `greet`, `block`, `unblock` e `report`, refeitos campo a campo
  (`parseSceneCommand`; `hello`, `uplink` e `ping` nunca: 400 `invalid`); de cada lote só o último `move`. Até 5 lotes
  por segundo por stream (balde de 5, recarga de 5/s: um lote a cada 200 ms nunca é recusado; 429 `rate_limited` com
  `Retry-After: 1`). Stream que não está aberto: 409 `no_stream`; sem a conexão: 409 `not_connected`. `view` e
  `view.off` dizem se aquele stream quer a cidade (o viewer decide o que vai ao mundo; `view` com a cidade aberta
  recomeça tudo).
- No navegador, `client/src/net/world-stream.ts`: `createWorldStream({mock})` com `on`, `send` (fila e um lote a cada
  200 ms) e `close`; no modo demonstração, `FakeWorldStream` (20 prédios com o meu no lote (3,1), 5 pessoas andando
  nas calçadas e uma sentada num banco da praça, minimapa, `afk` depois de 60 s; `?perf=N` enche N lotes).

## Variáveis de ambiente

| Variável | Padrão | Uso |
| --- | --- | --- |
| `HABBLAUD_PORT` | `4747` | porta HTTP |
| `HABBLAUD_HOST` | `127.0.0.1` | interface (o Docker usa `0.0.0.0`); fora do loopback, o terminal (e as permissões e mensagens) fica desligado |
| `HABBLAUD_BIND` | — (Compose: `127.0.0.1`) | só Docker: interface do host onde a porta é publicada, repassada ao container; só loopback liga o terminal |
| `HABBLAUD_TERMINAL` | — | `0` desliga o terminal, as permissões e as mensagens pelo escritório (não liga com a porta exposta) |
| `HABBLAUD_CODEX` | ligado | `0` desliga a fonte do Codex |
| `HABBLAUD_CODEX_DIRS` | — | pastas do Codex separadas por vírgula; substitui a detecção (`CODEX_HOME` e `~/.codex*`) |
| `HABBLAUD_CODEX_BIN` | `codex` do PATH | binário do Codex para o `codex queue` (modo Node e `npm run codex:bridge`) |
| `HABBLAUD_OMP_USAGE` | ligado (Docker: desligado) | `0` desliga o `omp usage --json` (uso do plano das contas do omp) |
| `HABBLAUD_OMP_BIN` | `omp` do PATH, senão `~/.bun/bin/omp` | executável do omp para o uso do plano (caminho ou nome no PATH) |
| `HABBLAUD_QUOTA_URL` | — | endpoint HTTP(S) completo da cota do tenant; exige arquivo de token e desliga a coleta por `omp usage` |
| `HABBLAUD_QUOTA_TOKEN_FILE` | — | arquivo absoluto contendo somente o bearer do tenant, montado RO; exige URL |
| `HABBLAUD_MENSAGENS` | ligado (com o terminal) | `0`, `false`, `off` ou `no` desligam só as mensagens pelo escritório |
| `HABBLAUD_CLAUDE_DIRS` | — | config dirs separados por vírgula; substitui a detecção (`~/.claude*` com `projects/` ou `sessions/` + `CLAUDE_CONFIG_DIR`) |
| `HABBLAUD_DATA_DIR` | `~/.habblaud` (Docker: `/data`) | estado do Habblaud (nomes e personagens dos projetos persistidos em `names.json`, linha do tempo em `timeline/`, estatísticas do Meu dia em `stats/`, última verificação de versão em `updates.json`) |
| `HABBLAUD_TIMELINE` | ligado | `0` desliga a gravação da linha do tempo do timelapse |
| `HABBLAUD_UPDATE_CHECK` | ligado | `0` desliga a verificação de versão nova (releases do repositório do `package.json` no GitHub, a cada 6 h) |
| `HABBLAUD_LANG` | idioma do sistema (`LC_ALL`/`LANG`/Intl) | idioma dos textos montados pelo servidor: `pt-BR` ou `fr` (shared/i18n.ts) |
| `HABBLAUD_DEMO` | desligado | `1` liga o modo demonstração ao iniciar |
| `HABBLAUD_IN_DOCKER` | auto (`/.dockerenv`) | `1` = não confere PIDs (são do host) |
| `HABBLAUD_ACCOUNTS` | — | JSON com metadados das contas vindos do host (Docker): `[{id, configDir, mountDir, short, name, email, organization, plan, color, cachedUsage}]`, casados por `id`, `mountDir` ou `configDir` |
| `HABBLAUD_USAGE_DIR` | `~/.habblaud/usage` (Docker: `/usage`) | pasta do uso capturado pelo tap de statusline ou pelo mod do Claude Code, relida a cada 5 s |
| `HABBLAUD_ALLOWED_HOSTS` | — | nomes extras aceitos no `Host`/`Origin` (vírgula); `localhost`, `*.localhost` e IPs sempre valem |
| `HABBLAUD_WORLD_URL` | `https://server.habblaud.com` | servidor do Habblaud World: só `https://` ou `wss://`, sem caminho; `http://127.0.0.1:*` só no modo dev (recusada, o World fica desligado). O prédio sobe pelo WebSocket na mesma origem (`/v1/socket`) |
| `HABBLAUD_WORLD` | ligado | `0` (`false`, `off`, `no`) desliga o Habblaud World: rotas 404, nada na interface e nada sai |
| `HABBLAUD_WORLD_DEV` | desligado (`--dev` liga) | `1` aceita o servidor do mundo em `http://127.0.0.1` ou `ws://127.0.0.1` |

No hook de permissão (ambiente do Claude Code; os argumentos `--port`/`--timeout` gravados pelo instalador têm
preferência): `HABBLAUD_PORT` (porta do Habblaud, padrão `4747`), `HABBLAUD_PERMISSION_TIMEOUT` (segundos de espera
pela resposta no Habblaud, padrão `300`, entre 5 e 1800) e `HABBLAUD_HOOK_DEBUG=1` (conta no stderr o que fez).

## Uso do plano (5h e semanal)

Duas fontes, ambas arquivos locais (nada de credenciais nem chamadas de rede); vale a de números mais recentes
(`fetchedAt`):

- `statusline` (**recomendada**, `accounts/statusline.ts`): o Claude Code envia ao comando de statusline um JSON
  com `rate_limits` (`five_hour`/`seven_day`: `used_percentage` e `resets_at` em segundos). O
  `scripts/statusline-tap.mjs`, instalado na frente do statusline de cada conta por `npm run usage:install`,
  grava só esses números em `~/.habblaud/usage/<conta>.json` (casado com a conta pelo `configDir`; senão pelo
  `accountId`). No Claude Code 2.1.287+ o mod do Habblaud grava o mesmo arquivo (ver abaixo);
- `cache`: o `cachedUsageUtilization` que o próprio Claude Code grava ao rodar `/usage`, relido a cada 60 s.

Sem nenhuma das duas, a conta fica `disabled` ("sem dados de uso"). Números com mais de 30 min aparecem como
`stale`. Uma janela cujo reinício já passou desde a coleta é omitida (a interface mostra "—") até chegarem
números novos — nunca um 0% inventado.

## Mod do Claude Code

O repositório é também um marketplace de plugins do Claude Code (`.claude-plugin/marketplace.json`, detalhes em
[`mod/README.md`](../mod/README.md)). Do lado do servidor, o mod `habblaud` usa duas coisas:

- **o arquivo de uso:** em cada `session.start` e `session.measure` ele grava `<HABBLAUD_USAGE_DIR>/<conta>.json`
  no formato do tap (`{accountId, configDir, fetchedAt, five_hour, seven_day}`, `resets_at` em segundos, a partir de
  `$.session.usage().rateLimits`) mais `source: "mod"`, que o leitor ignora: para o servidor continua sendo a fonte
  `statusline`. O mod escreve com `$.fs.write`, que não é atômico; por isso `StatuslineUsageReader` ignora uma
  leitura vazia ou pela metade, fica com o último registro bom daquele arquivo e o relê no ciclo seguinte;
- **`GET /api/mod/summary`** (`modSummary` em `http/app.ts`), perguntado a cada 5 s por sessão que desenha (30 s com
  o Habblaud fora do ar) e pelo comando `/habblaud`: só contagens e quem espera, montado do snapshot atual. Ficam de
  fora os agentes do demo (que vivem só no snapshot, não no `Office`), quem já encerrou ou entregou e, com
  `?session=` (e `?account=`, se vier), a sessão de quem pergunta: o principal com esse `sessionId` e os subagentes
  dela (o `sessionId` deles é o da sessão que os disparou; por garantia, também quem tem um ancestral dela).
  `answerable` = há pedido de permissão para responder pelo escritório (`AgentInfo.permission`). Sem parâmetros,
  nada é excluído (é o que o `/habblaud` usa, para os números baterem com a tela). Só `GET`/`HEAD` (senão 405).

## Estrutura

- `config.ts`, `log.ts`, `index.ts` — configuração, logs curtos (nunca conteúdo de conversas) e entrada.
- `accounts/` — detecção de contas (`detect.ts`, também usado pelo `docker-up`), uso (`usage.ts`), tap de statusline (`statusline.ts`), serviço (`service.ts`).
- `sources/` — registro de sessões, leitura incremental (`tail.ts`), parser de transcripts (atividades em `transcript.ts`; conversa do terminal em `terminal.ts`; eventos do GitHub em `github.ts`), subagentes, o histórico de sessões (`history.ts`) e o orquestrador (`watcher.ts`).
- `model/` — escritório (`office.ts`), salas/slots (`rooms.ts`), nomes persistidos (`names.ts`).
- `http/` — proteções de borda (`guard.ts`), rotas (`app.ts`), SSE (`sse.ts`), terminal (`terminal.ts`) e o histórico dele (`sessions.ts`), timelapse (`timeline.ts`), Meu dia (`stats.ts`), estáticos (`static.ts`).
- `history/` — gravador da linha do tempo do timelapse (`timeline.ts`) e as estatísticas do Meu dia: amostragem, persistência e retenção (`daystats.ts`; o acumulador puro fica em `shared/daystats.ts`).
- `permissions/` — responder pelo escritório (permissões e perguntas): registro dos pedidos (`registry.ts`), rotas (`http.ts`) e a busca da chamada no transcript (`transcript.ts`).
- `messages/` — mensagens pelo escritório: fila, presença das sessões e prazos (`registry.ts`) e rotas (`http.ts`).
- `updates/` — verificação de versão nova nas releases do GitHub (`checker.ts`).
- `world/` — Habblaud World: sessão em `world.json` (`settings.ts`), cliente do servidor do mundo (`client.ts`), ALTCHA (`altcha.ts`), a conta (`service.ts`), as rotas `/api/world/*` (`http.ts`) e o prédio no mundo (`uplink.ts`, com `ids.ts`, `tokens.ts`, `theme.ts` e `signals.ts`).

Testes: `npx vitest run server shared` (fixtures sintéticas em `server/test/fixtures.ts`; os scripts do host —
tap de statusline, hook de permissão (rodado como processo contra um servidor de teste), instaladores e
`docker-up` — são testados em `server/test/` com HOME e config dirs falsos).
