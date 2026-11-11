# Zaplaud: contrato de telas, tema, recursos e fixture

## Convenções de referência

Repositório consultado, somente leitura: `/home/anderson/Desenvolvimento/habblaud`.

Neste documento, referências `ReadingScreen.kt:123` e os demais arquivos de UI resolvem para `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`. Referências `data/X.kt:123` resolvem para o subdiretório `data/` desse mesmo diretório. `strings.xml` resolve para `android/shared/src/commonMain/composeResources/values/strings.xml`; `drawable/` resolve para `android/shared/src/commonMain/composeResources/drawable/`. Referências `server/`, `scripts/`, `android/desktop/` e `android/app/` são relativas à raiz do repositório.

- Em tabelas, `—` em Tag significa que **o Kotlin não atribui testTag**; não inventar tags como se fossem contrato existente.
- Chaves de texto são nomes de `Res.string`, salvo indicação `Res.plurals`. Texto de servidor, título, nome, caminho e modelo não têm chave de recurso.
- Medidas são dp; valores percentuais são frações da largura disponível. Componentes Material 3 sem tamanho, cor, padding ou fonte explícitos usam o padrão da biblioteca, não medidas inventadas neste documento.
- Não foram executados app, testes, fixture nem capturas. As condições abaixo foram lidas no código. Credenciais não são reproduzidas.

## 1. Raiz, login, adicionar servidor e sair

A raiz carrega strings, tema, servidores e APIs. Exibe spinner central enquanto strings/armazenamento carregam. Sem servidores, ou com `showLogin`, abre `LoginForm`; caso contrário abre `ReadingScreen`. Cada servidor tem API própria; entrada bem-sucedida adiciona/substitui API, salva origem e remove necessidade de login. (`ZaplaudApp.kt:31-95`, `ZaplaudApp.kt:124-155`.)

### Login

Scaffold com TopAppBar `app_name`. Conteúdo vertical rolável, padding 20, espaçamento 12; título `connection` em `headlineMedium`. (`ZaplaudApp.kt:251-266`.)

| Controle / texto | Chave | Tag | Visibilidade / habilitação | Efeito |
|---|---|---|---|---|
| Cancelar no topo | `cancel` | — | Somente quando já existe servidor; desabilitado durante busy | Fecha formulário; não remove servidores |
| URL, uma linha | `server_url` | `server-url` | Sempre; habilitado `!busy` | Edita URL |
| Usuário, uma linha | `user` | `username` | Sempre; não recebe `enabled=!busy` | Edita usuário |
| Senha mascarada, uma linha | `password` | `password` | Sempre; não recebe `enabled=!busy` | Edita senha |
| Botão largura total | `login` | `login-submit` | `!busy && user.isNotEmpty() && password.isNotEmpty()`; URL vazia não desabilita | `requestLogin()` |
| Spinner | — | — | busy | Aguarda autenticação |
| Erro abaixo | ver lista seguinte | — | message não vazia | Texto `colorScheme.error` |

Fontes: `ZaplaudApp.kt:173-238`, `ZaplaudApp.kt:251-266`. A senha é apagada antes de conectar, inclusive para URL inválida. A URL é normalizada por `UrlPolicy.origin`. `connect()` consulta prazo local de rate limit; chama `HabblaudApi.login` e `access`, depois `store.addServer`. Rotas: POST `/api/remote/login` com `{user,password}` e GET `/api/access`. (`ZaplaudApp.kt:184-230`; `data/HabblaudApi.kt:109-113`.)

Erros: `invalid_url`, `redirect_error`, `login_error` (401), `forbidden` (403), `rate_limited` (429 ou prazo local), `http_error`, `storage_error`, `network_error`. `Retry-After` é persistido por origem. (`ZaplaudApp.kt:185-224`; `strings.xml:25-32`.)

### Aviso HTTP

AlertDialog somente quando origem válida começa com `http://`; título `http_login_title`, corpo `http_login_warning(origin)`. Confirmar `confirm`, tag `http-confirm`, fecha aviso e conecta **somente se origem atual ainda é a origem confirmada**. Cancelar `cancel`, tag `http-cancel`, fecha sem conectar. Dismiss externo também fecha. (`ZaplaudApp.kt:233-249`; textos `strings.xml:41-44`.)

### Adicionar servidor

Menu da lista: `add_server`, tag `add-server`; configurações também têm `add_server`, sem tag. Ambas chamam `onLogin(null)`. Reutiliza LoginForm, com Cancelar quando há servidor existente. Relogin usa URL inicial da origem selecionada. (`ReadingScreen.kt:267-268`, `ReadingScreen.kt:300-304`; `SettingsScreen.kt:231`; `ZaplaudApp.kt:127-145`.)

### Logout e alerta global

Configurações chama `onLogout(server)`; raiz ignora enquanto busy. AlertDialog `logout`, corpo `logout_server_warning(serverLabel(origin))`; Confirmar `confirm` habilitado `!busy`, Cancelar `cancel`, sem tags. Confirmar tenta POST `/api/remote/logout`, remove dados locais do servidor via `store.removeServer`, fecha API, remove origem/lista de login e recalcula notificações. Outros servidores permanecem. Falha local usa `storage_error`; revogação não confirmada usa `logout_unconfirmed`. AlertDialog global exibe mensagem e botão `confirm`; dismiss limpa mensagem. (`ZaplaudApp.kt:23-27`, `ZaplaudApp.kt:96-121`, `ZaplaudApp.kt:154-160`; `data/HabblaudApi.kt:116`; `strings.xml:36-40`.)

## 2. Estrutura responsiva e navegação desktop

- `maxWidth >= 840.dp`: duas colunas; lista com largura **360**, altura completa; divisor `outlineVariant`; conversa restante, centralizada no topo, largura máxima **840**. Sem seleção, pill `select_conversation`, `surfaceContainerHigh`, CircleShape, padding horizontal 16 / vertical 8. (`ReadingScreen.kt:617-640`.)
- Duas colunas: lista preserva lista e highlight da seleção `secondaryContainer`, raio 12; conversa não mostra botão voltar. (`ReadingScreen.kt:388`, `ReadingScreen.kt:621-639`.)
- Abaixo de 840: um Scaffold, alterna lista/conversa; conversa mostra voltar. (`ReadingScreen.kt:645-654`.)
- Lista: fundo `background`. Conversa: `surfaceContainerLowest` se luminância de background < 0,5; caso contrário `surfaceContainerLow`. Conteúdo horizontal 12, espaçamento vertical 8, padding do Scaffold e IME. (`ReadingScreen.kt:623`, `ReadingScreen.kt:638`, `ReadingScreen.kt:649`.)
- Janela JVM inicial 1100 × 760. Esc em **KeyUp** chama último BackHandler habilitado; Ctrl+F/Cmd+F em **KeyDown** chama último SearchHandler habilitado. (`android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt:40-60`; `android/shared/src/jvmMain/kotlin/br/com/xlinx/habblaud/UiPlatform.jvm.kt:17-36`.)
- BackHandler fecha conversa se selecionada e sem permission sheet nem `menuExpanded`; na lista com busca aberta limpa query e fecha busca. Settings tem BackHandler próprio. (`ReadingScreen.kt:200-203`; `SettingsScreen.kt:64`.)
- **Ctrl/Cmd+F só é registrado no ramo largo**, não no layout estreito. (`ReadingScreen.kt:618-619`.)
- Composer principal JVM: Enter KeyDown sem Shift tenta enviar, consome tecla mesmo quando não pode enviar; Shift+Enter passa ao campo para nova linha. Nenhum tratamento equivalente foi declarado no campo de aside. (`ReadingScreen.kt:580-588`; `AsideSheet.kt:67-72`; `UiPlatform.jvm.kt:35`.)
- JVM desliga gesto de puxar para aside, pois roda do mouse não termina gesto; busca mantém detector de nested scroll no código comum. (`ReadingScreen.kt:480-481`; `UiPlatform.jvm.kt:36`.)

## 3. Lista de sessões

### Barra superior e conexão

TopAppBar: `app_name`, título com uma linha/ellipsis e peso sem fill, espaçamento 8, ponto circular 10. Descrição acessível do ponto: `reading_connected`, `reading_connecting` ou `reading_offline`. Cor connected=`primary`; connecting=`warningColor`; demais=`error`. Menu vertical (`menu`) contém `settings` e `add_server` (tag `add-server`). (`ReadingScreen.kt:253-272`; `StatusColors.kt:10-18`.)

Conexão combinada: qualquer origem necessitando login ou estado offline produz offline; senão qualquer connecting produz connecting; senão connected. (`ReadingScreen.kt:140-150`.)

### Controles, estados e filtros

| Controle / estado | Chaves | Tag | Condição / ação |
|---|---|---|---|
| Linha de login pendente por origem | `relogin`; nome do servidor dinâmico | `relogin:<origin>` | Cada origem em `needsLogin`; chama `onLogin(origin)` |
| Erro por origem | `session_changed`, `terminal_blocked`, `reading_missing`, `reading_oversize`, `network_error` | — | `serverState.error != null`; prefixa servidor quando múltiplos; cor error |
| Filtro Todos | `filter_all` | `server-filter:all` | Somente >1 servidor; limpa filtro |
| Filtro servidor | nome dinâmico; rótulos de conexão | `server-filter:<serverLabel(origin)>` | Somente >1 servidor; seleciona origem; ponto 10 com estado da origem |
| Segmento abertas | `open_sessions` | — | Sempre; recent=false |
| Segmento recentes | `recent_sessions` | — | Sempre; recent=true; inclui abertas e históricas |
| Bloqueio leitura | `terminal_blocked` | — | `!terminalAllowed` |
| Campo busca pill, uma linha | `search_sessions` | `session-search` | Somente searchOpen; filtra localmente |
| Fechar busca | `search_close` | — | Limpa texto e fecha |
| Vazio com busca | `search_no_match`, `search_clear` | — | Lista vazia + query não branca; limpar zera query |
| Vazio sem busca | `sessions_empty` | — | Lista vazia + query branca |

Fontes: `ReadingScreen.kt:299-385`; mapeamento de erro `ReadingScreen.kt:86-92`. Os logins pendentes são linhas de relogin; **não há segundo componente autônomo de “pending logins”** nesse bloco.

Segmentos ocupam largura total com shape Material `itemShape(0/1,count=2)`. Trocar aba, quando query vazia, esconde busca e retorna ao topo. Busca preserva valor/posição ao voltar de conversa; compara sem diferenciar maiúsculas em title, characterName, agentName, project, provider e account. Abas abertas exigem agentId; recentes não. Ordenação `byLastMessage`. (`ReadingScreen.kt:172-179`, `ReadingScreen.kt:320-338`.)

Busca começa oculta; puxar além do topo **120 dp** revela, foca se query vazia e dispara haptic LongPress. Campo CircleShape, cores pill sem indicadores. Lista ocupa restante, espaçamento 8. Vazio tem padding 32, spacing 8, bodyLarge centralizado. (`ReadingScreen.kt:340-385`, `ReadingScreen.kt:782-783`, `ReadingScreen.kt:1056-1065`.)

### Anatomia da linha

Linha clicável `session-row:<provider>:<sessionId>`, padding vertical 6, spacing horizontal 12, alinhamento central; abre `ReadingController.open(session)` e fecha seleção de outras origens. Avatar **48**; título titleMedium, até duas linhas, ellipsis; título e subtítulo em bold quando unread existe. Subtítulo bodyMedium, uma linha: personagem se diferente do título, basename do projeto, status traduzido, servidor quando vários sem filtro; separados por ` · `. Neste último caso tag `session-server:<serverLabel(origin)>`. (`ReadingScreen.kt:124-126`, `ReadingScreen.kt:386-407`.)

Status: idle=`status_idle`; working/busy/running=`status_working`; waiting=`status_waiting`; offline/closed/stopped=`status_closed`; outro não mostra tradução. Agente aberto em working exibe `StatusLine`. Pedido pendente adiciona TextButton `permission_pending`, texto error; abre PermissionSheet. (`ReadingScreen.kt:395-396`, `ReadingScreen.kt:685-692`.)

Coluna à direita: alinhada ao fim, spacing 6. Tempo usa última reply, senão lastAt; labelSmall, primary se unread, senão onSurfaceVariant. Menos de 1 min `time_now`; <60 `time_minutes`; <1440 `time_hours`; senão `time_days`. Badge primary/onPrimary, labelMedium, contagem até 98, **99+ para count >=99**; descrição plural `unread_replies`. (`ReadingScreen.kt:398-405`, `ReadingScreen.kt:759-767`, `ReadingScreen.kt:780-781`; `strings.xml:244-247`.)

### Avatar e família do modelo

Avatar circular; fallback primeira letra uppercase em titleMedium, `#002117`, fundo escolhido por `(name.hashCode() and Int.MAX_VALUE) % 5` entre `#D3EEE3`, `#DCE7F7`, `#F7E4D6`, `#EAE0F4`, `#F5EABF`. Imagem sem suavização (`FilterQuality.None`). (`CharacterAvatar.kt:51-62`.)

Avatar de sessão com tamanho >=32 e `modelBrand` conhecido ganha badge inferior direito de **42%** do tamanho; fundo surface, círculo; ícone 70% do badge; tag `model-badge:claude`, `model-badge:gemini` ou `model-badge:openai`. Claude=`#D97757`; Gemini=`#8E75B2`; OpenAI=`onSurface`. Descrição do ícone é modelo recebido, sem chave. (`CharacterAvatar.kt:32-47`.)

### Subagentes/advisors

Só `kind=sub && status=working` entra em galhos; parentId associa ao principal; role `__advisor` diferencia advisor. (`data/SessionRepository.kt:20-29`, `data/SessionRepository.kt:63-71`.)

Linha sob principal: altura 36, canvas largura 40, tronco x=24, espessura 1,5, `outlineVariant`; último ramo termina na metade. Avatar 24 sem badge; coluna padding start 8; nome labelLarge, uma linha; advisor usa `advisor_name(name)`; atividade labelSmall onSurfaceVariant. Clicar abre `subSession(parent,sub)`. Sem tag. Galhos somem quando deixam working. (`ReadingScreen.kt:408-409`, `ReadingScreen.kt:694-709`.)

## 4. Conversa

### Barra superior e menu

TopAppBar: cabeçalho clicável com descrição `session_info` abre info sheet. Avatar 36; spacing 8; título uma linha; subtítulo labelMedium com personagem diferente do título, basename do projeto e status, ` · `; uma linha/ellipsis. Voltar (`back_sessions`) chama controller.close só no layout estreito. Ações à direita vêm de CastActions. (`ReadingScreen.kt:274-297`.)

Menu em páginas, não uma lista plana: página principal possui grupos com valor secundário bodySmall onSurfaceVariant e seta direita; submenu começa `back`, labelLarge, ícone 20. Escolhas têm RadioButton. (`ReadingScreen.kt:204-246`.)

| Item | Chaves | Tag | Condição | Ação |
|---|---|---|---|---|
| Visualização | `view_menu`; `view_all`, `view_prompts`, `view_no_tools` | — | Grupo habilitado após carregar preferências da sessão | Atualiza `viewFor`, `store.setSessionView(key,value)` e fecha menu |
| Modo de envio | `deliver_mode`; `deliver_aside`, `deliver_follow_up`, `deliver_steer` | — | Somente OMP principal aberto; loaded | Salva `aside`, `followUp`, `steer` por sessão via `setSessionDeliverAs`; `messages.deliverAs` recebe modo |
| Modelo | `model_menu`; nomes dinâmicos | — | OMP principal aberto, models não vazia; canControl | `messages.setModel(agentId,model)` |
| Raciocínio | `thinking_menu`; `thinking_off/minimal/low/medium/high/xhigh/max` | — | OMP principal aberto; canControl | `messages.setThinking(agentId,level)` |
| Shake | `shake` | — | OMP principal aberto; canControl | `messages.shake(agentId)` **direto, sem diálogo neste caminho** |
| Aside | `aside_menu` | `aside-menu` | OMP principal aberto; canControl | `controller.asides.open(agentId)`, abre AsideSheet |
| Dispensar | `cast_dismiss` | — | Regras CastActions abaixo | Abre confirmação |

`omp` significa provider omp, agentId não nulo e !sub. `canControl = omp && canMessage && messagesAllowed && messageGuard && connected`. Modelos agrupados via `roleModelGroups`, preserva atual; cabeçalho de provedor labelLarge primary, padding start/end16 top12 bottom4; seleção remove prefixo de provedor só para exibição. Preferências carregam por sessão, defaults all/aside antes de carregadas. (`ReadingScreen.kt:180-194`, `ReadingScreen.kt:205-245`, `ReadingScreen.kt:1041-1053`.)

Model/thinking/shake enviam POST `/api/messages/control` com agentId, expectedSessionId e respectivamente model/thinking/command=shake; acompanham GET `/api/messages/<id>` por até 15 tentativas espaçadas 2s. Delivered vira commandResult sent; failed permanece failed; demais unknown. Não há retry automático do POST. (`data/MessageController.kt:292-325`.)

### Avisos de leitura

Erro geral usa `readingErrorResource` em error; histórico sem agentId mostra `history_read_only`; !terminalAllowed mostra `terminal_blocked`; readingError forbidden/missing/oversize/outro usa `terminal_blocked`/`reading_missing`/`reading_oversize`/`reading_offline`, cor error. reduced mostra `window_reduced`; truncated adiciona `window_truncated` no início do histórico carregado, full width, padding8, bodySmall central, onSurfaceVariant. Não há paginação. (`ReadingScreen.kt:414-422`, `ReadingScreen.kt:499-500`.)

### Lista e acompanhamento

`conversation-list`: LazyColumn invertida, índice zero mais novo, contentPadding vertical8, spacing8 alinhado embaixo. Abre no fim; ao enviar retorna a índice zero. Ao acompanhar o fim, novas linhas mantêm índice zero. Fora do fim, soma novas entradas visíveis ao contador. FAB pequeno circular no canto inferior direito, padding8, descrição `go_to_end`; ícone seta abaixo e badge numérico quando >0; só aparece se !following e rows não vazia. Clique zera contador e rola ao fim. Chave `new_entries` existe no catálogo, mas não é usada nesse botão. (`ReadingScreen.kt:423-453`, `ReadingScreen.kt:479-507`; `strings.xml:65`.)

### Tipos de entrada e tratamento visual

| Tipo / situação | Renderização | Interação / chave |
|---|---|---|
| user | À direita, Surface largura85%, raio16, primaryContainer, padding12, Markdown onPrimaryContainer | Selecionável; horário labelSmall ao fim, onPrimaryContainer alpha0,7 |
| assistant e outros tipos não system/tool/thinking | À esquerda, largura85%, raio16, surface, padding12, Markdown onSurface | Selecionável; horário labelSmall onSurfaceVariant alpha0,7 |
| thinking | À esquerda, largura85%, transparente, borda1 outlineVariant, raio12, padding horizontal12/vertical8 | Cabeçalho `thinking (count)`, Psychology16, labelLarge onSurfaceVariant, hora labelSmall, chevron20; clique expande; texto italic onSurfaceVariant |
| tool | Mesmo container de thinking | Cabeçalho `tools (count)`, Build16; expandir mostra ferramentas em coluna spacing12 com scroll horizontal; nome labelLarge primary, input/result monospace; `tool_result`, `result_truncated` |
| result com tool correspondente | Não vira linha separada | Vai dentro da ferramenta pelo toolUseId |
| result órfão | Segue renderer genérico de bolha esquerda | Não é descartado |
| system | Fora de bolha, texto selecionável centralizado full width padding8 bodySmall | Texto original + hora ` · `; onSurface |
| erro system recuperado | Mesmo aviso central | `response_interrupted`, onSurfaceVariant, preserva bolha de texto anterior |
| erro system sem continuação | Aviso central com erro original | Não é ocultado e não vira toast |
| entrada truncada | Complemento textual | `result_truncated` |

Fontes: `ReadingScreen.kt:791-887`, `ReadingScreen.kt:899-955`, `ReadingScreen.kt:972-984`.

Agrupamento é por turno delimitado por **user**, não apenas itens consecutivos: todos thinking viram um grupo na posição do primeiro; idem tools. Thinking concatena textos não brancos com duas quebras; contagem inclui entradas do grupo. Cabeçalho tool usa maior timestamp entre tools/resultados. Clique de expansão conserva posição do cabeçalho e corrige scroll por até 20 frames, parando se usuário rolar. (`ReadingScreen.kt:485-495`, `ReadingScreen.kt:811-840`, `ReadingScreen.kt:892-955`.)

Filtro prompts mostra user+assistant; no_tools remove tool+result, mas mantém thinking/system. Erro recuperado é system com id terminado `:err` seguido por assistant/tool/thinking antes de outro user. (`ReadingScreen.kt:963-984`.)

Markdown usa biblioteca mikepenz; imagens são desabilitadas (`image = {}`). Overrides: h1 headlineSmall Semibold; h2 titleLarge Semibold; h3 titleMedium Semibold; h4 titleSmall Semibold; h5 bodyLarge Semibold; h6 bodyMedium Semibold. Tabelas, links, listas e código vêm do renderer, não parser textual próprio neste arquivo. (`ReadingScreen.kt:863-877`.)

### Resumos

Somente assistant com >500 caracteres, configuração resumir ligada e callback disponível. Envia somente primeiros 32Ki caracteres a `controller.summarize`/POST `/api/summaries` `{text}`, usa summary cache. Antes da resposta mostra `summary_loading`, bodySmall italic onSurfaceVariant; resumo pronto usa onSurface + `summary_label` labelSmall primary. Clique revela original; `summary_collapse` labelSmall primary recolhe. Falha retorna original, sem mensagem extra. Resumo pode ser expandido enquanto ainda carrega. (`ReadingScreen.kt:786-789`, `ReadingScreen.kt:842-862`; `data/ReadingController.kt:165-183`; `data/HabblaudApi.kt:114-115`.)

Com resumir desligado, assistant com >500 caracteres mostra o começo dela sem pedido ao servidor (`replyBrief`/`reply_brief`): ignora blocos de código; começa no primeiro parágrafo em prosa (não título, tabela, citação nem lista); junta parágrafos seguintes sem títulos, tabelas, marcadores de lista, `**` e crases até passar de 160 caracteres; corta em 400 no último espaço com `…`. Mesmo visual e rótulos do resumo pronto, sem `summary_loading`. Sem texto restante (só código), mostra o original. Resumo já guardado no cache continua tendo prioridade.

### Linha de estado

Só selected aberto em working. Avatar20, padding horizontal4, spacing6; StatusLine ocupa restante. Lista também usa StatusLine no principal working. Spinner texto primary monospace bodyMedium, largura14; atividade com reticências bodySmall primary; cronômetro bodySmall onSurfaceVariant, `fontFeatureSettings=tnum`. Frames Codex braille; demais `· ✢ ✳ ✶ ✻ ✽ ✻ ✶ ✳ ✢`; atualiza a cada segundo, somente lifecycle STARTED. Sem animações: Codex `•`, demais `✻`; relógio continua. Atividade vazia cai em `status_line_working`; remove reticência final pré-existente. Cronômetro `m:ss` ou `h:mm:ss`, nunca negativo. (`ReadingScreen.kt:510-514`, `ReadingScreen.kt:712-756`.)

## 5. Composer principal

Composer existe somente se composer.key == seleção.key, agentId != null e !sub. `enabled = canMessage && messagesAllowed && messageGuard && connected`. O campo **não** é desabilitado por esse enabled: apenas `!composer.loading`; permite preparar rascunho offline/bloqueado. (`ReadingScreen.kt:515-518`, `ReadingScreen.kt:589`.)

| Controle / estado | Recurso | Tag | Condição / efeito |
|---|---|---|---|
| Campo texto | `message_text` | `message-input` | Campo peso1, máximo6 linhas; `messages.edit(text)` |
| Contador caracteres | literal `<n>/20000` | — | Exibe a partir de18000; controller recusa >20000 |
| Anexar | `attach_files` | — | LeadingIcon AttachFile; habilitado !loading e attachments<MAX_ATTACHMENTS(5); abre picker |
| Chip de arquivo | nome dinâmico; `attachment_remove` na descrição do X | — | InputChip selected=false; clique `messages.remove(attachment)` |
| Arquivo inacessível | `attachment_reselect` | — | URI em unavailable; error |
| Arquivo temporário | `attachment_temporary` | — | !persistent e não inacessível; bodySmall |
| Progresso upload | `attachment_progress` | — | composer.progress != null; bodySmall |
| Enviar | `send_message` | `message-send` | FilledIconButton Send; habilitação abaixo; `messages.send(agentId)` |
| Parar | `stop_agent` | — | Somente OMP status working/busy/shell; `messages.interrupt(agentId)` |
| Anel de contexto | `context_usage` | — | contextTokens e contextWindow não nulos; long press pode pedir shake |

Fontes: `ReadingScreen.kt:529-554`, `ReadingScreen.kt:576-612`; limite texto `data/MessageController.kt:113-116`. Picker só aceita callback se sessão capturada ainda corresponde ao composer. (`ReadingScreen.kt:165-169`.)

Campo: shape24, container `surfaceContainerHigh` nos estados focused/unfocused/disabled; todos indicadores transparentes. Linha espaçada4, centralizada; container de composer spacing4; chips em scroll horizontal spacing6; ícone remover18. (`ReadingScreen.kt:517`, `ReadingScreen.kt:549-552`, `ReadingScreen.kt:576-599`, `ReadingScreen.kt:1056-1065`.)

Enviar habilitado se enabled, !loading, !sending, unavailable vazia, e texto não branco **ou** anexos não vazios. Antes de enviar chama stickToEnd. `messages.send` envia anexos por POST `/api/messages/attachments`; depois POST `/api/messages` `{agentId,text,expectedSessionId,deliverAs?,attachments?}`; deliverAs apenas provider omp. (`ReadingScreen.kt:578-579`; `data/MessageController.kt:169-217`; `data/HabblaudApi.kt:98-106`.)

### Banners e confirmação de envio

ChatBanner: Surface full width secondaryContainer raio10; Row tag **`chat-banner`**, padding horizontal12/vertical8, spacing8; Info18; texto bodySmall onSecondaryContainer. Podem existir várias instâncias com mesma tag. (`ReadingScreen.kt:770-777`.)

- Bloqueios em ordem: !messagesAllowed=`messages_blocked`; senão !messageGuard=`message_guard_required`; senão !canMessage=`message_unavailable`. (`ReadingScreen.kt:530-532`.)
- Operação não delivered: queued=`message_queued`, sent=`message_sent`, failed=`message_failed`, demais=`message_unknown`. Tag exterior **`message-status-<status>`**, além de chat-banner interior. (`ReadingScreen.kt:557-562`.)
- commandResult: pending/sent/failed/unknown usa `command_pending/command_sent/command_failed/command_unknown` com `model_menu`, `thinking_menu` ou `shake_short`; sent desaparece após4s. (`ReadingScreen.kt:563-567`.)
- composer.error exceto confirm: storage=`storage_error`, unknown=`message_unknown`, attachment=`attachment_error`, attachments_expired=`attachments_expired`, demais=`message_send_error`; cor error. (`ReadingScreen.kt:568`.)
- error=confirm abre AlertDialog `message_retry_title`, `message_retry_warning`; `confirm` chama stickToEnd e send(confirmed=true); `cancel`/dismiss chama dismissConfirmation. Sem tags. (`ReadingScreen.kt:569-574`.)

### Parar e shake

Parar: FilledIconButton error/onError; habilitado enabled && !loading && !sending && control.status não queued/sent. POST `/api/messages/control` `{agentId,stop:true,expectedSessionId}`. Primeiro pedido não pede confirmação. Se estado de controle anterior unknown, controller exige confirmação explícita; diálogo `stop_agent`, `stop_unknown`, confirm chama interrupt(confirmed=true), cancel chama dismissControlConfirmation. (`ReadingScreen.kt:533-546`, `ReadingScreen.kt:600-608`; `data/MessageController.kt:256-287`.)

Controle não delivered: queued/sent=`stop_pending`, failed=`stop_failed`, demais=`stop_unknown`. Erro bruto de controle é exibido salvo unknown/failed/confirm; failed recebe texto `stop_failed` error. (`ReadingScreen.kt:533-540`.)

Anel: fração tokens/window clamp0..1; percentual inteiro truncado; >=80 error, >=50 warningColor, senão primary. Indicador20, área40, stroke2,5, track outlineVariant; descrição `context_usage(pct,tokens/1000,window/1000)`. Long press só se composer enabled e OMP; haptic LongPress; diálogo `shake`, `shake_confirm`, `confirm` envia shake, `cancel` fecha. Acessibilidade expõe ação long click `shake`. Menu Shake não usa esse diálogo. (`ReadingScreen.kt:519-527`, `ReadingScreen.kt:598`, `ReadingScreen.kt:662-681`.)

## 6. PermissionSheet: aprovação e perguntas

Abre por botão de sessão na lista ou ação de atenção na conversa. Revalida permissionId na lista viva; se pedido sumiu com conexão connected fecha automaticamente. Busca GET `/api/permissions/<id codificado>`; decisões POST `/api/permissions/<id>/decision`. Só busca quando connected e allowed. Sheet skipPartiallyExpanded=true, expande após detail; conteúdo rolável/navigationBarsPadding, padding start/end20 bottom24, spacing6. Cabeçalho avatar48, spacing12, personagem titleMedium, título `question_pending` se há perguntas, senão `permission_pending` titleLarge, subtítulo projeto/agente/título únicos. **Nenhum controle desta sheet tem testTag.** (`ReadingScreen.kt:156-164`; `PermissionSheet.kt:64-93`, `PermissionSheet.kt:112-128`.)

| Controle / texto | Recurso | Condição / ação |
|---|---|---|
| Bloqueio | `permissions_blocked` | !allowed |
| Offline | `reading_offline` | !connected |
| Nome ferramenta | `permission_tool` + valor dinâmico | Sem perguntas; rótulo labelMedium, valor titleMedium |
| Input / comando | `permission_command` se tool equals bash case-insensitive, senão `permission_input` | Sem perguntas; texto monospace selecionável, scroll horizontal, background surfaceContainerHighest raio8, padding12 |
| Prazo | `permission_deadline` | expiresAt presente; bodySmall; data/hora localizada |
| Pergunta/opção/descrição | Dinâmicos | Cada questão, inclusive OMP; linha toggleable Role.Checkbox + Checkbox visual |
| Outra resposta | `permission_other` | Campo por questão; digitar em single limpa seleção |
| Sempre permitir sugestão | `permission_suggestion`; destinos abaixo | Claude e sem perguntas; checkbox alterna índice de sugestão |
| Interromper ao recusar | `permission_interrupt` | Claude, sem perguntas e showDeny |
| Motivo | `permission_reason` | Sem perguntas e showDeny; opcional |
| Responder | `permission_answer` | Há perguntas; decide(answer) |
| Permitir | `permission_allow` | Sem perguntas; decide(allow) |
| Recusar | `permission_deny` | Sem perguntas; primeiro clique revela motivo, segundo decide(deny) |
| Terminal | `permission_terminal` | Sempre; decide(terminal), **não abre terminal local** |
| Cancelar | `cancel` | Sempre habilitado; fecha sem decisão |

Fonte: `PermissionSheet.kt:129-185`. Destinos de sugestão session/localSettings/projectSettings/userSettings/outro usam `permission_destination_session/local/project/user/unknown`. (`PermissionSheet.kt:157-170`.)

Decisão habilitada só `allowed && connected && item!=null && !expired && !busy && !ambiguous`; decide também checa deadline atual. Opções/Outra resposta não recebem enabled dessa expressão. Prazo vencido mostra `permission_expired`; validação respostas mostra `permission_answers`; decisão ambígua mostra `permission_unknown`; outros erros `network_error`, cor error. 401 chama unauthorized; 404/409 fecha. POST ambíguo bloqueia novas decisões, sem repetição automática. (`PermissionSheet.kt:97-109`, `PermissionSheet.kt:174-185`.)

Single: checkbox pode selecionar exatamente uma opção; selecionar limpa other; digitar other limpa opções. Multi: pode somar/remover opções e combinar com other. Todas questões devem ter opção válida ou texto livre; single exige exatamente uma entre opções e other. UI usa Checkbox inclusive single, **não RadioButton**. Payload answer: `{behavior:"answer",answers:[{question:index,options:[índices ordenados]?,other:texto.trim()?}]}`. Allow pode incluir suggestion; deny pode incluir message não branca e interrupt só Claude. (`PermissionSheet.kt:31-59`, `PermissionSheet.kt:141-172`.)

## 7. AsideSheet

Somente OMP principal aberto e canControl via menu, ou puxada **180dp** iniciada no fim da conversa, em plataforma com pull gestures. Mudar seleção fecha aside. (`ReadingScreen.kt:201-202`, `ReadingScreen.kt:244`, `ReadingScreen.kt:454-477`, `ReadingScreen.kt:784-785`.)

Sheet skipPartiallyExpanded, altura90%, horizontal16, spacing8, padding de navegação+IME. Cabeçalho `aside_title` titleLarge, `aside_hint` bodySmall onSurfaceVariant; seletor à direita. Carregando sem tópicos mostra spinner central; conversa vazia `aside_empty` central onSurfaceVariant; lista tag `aside-turns`, spacing12. Mudança de tópico/contagem/tamanho da resposta rola ao último turno. (`AsideSheet.kt:38-64`.)

| Controle | Chave | Tag | Condição / ação |
|---|---|---|---|
| Seletor tópicos | `aside_topics` descrição, `aside_topics_count` texto | — | Abre menu |
| Nova conversa | `aside_new` | — | `asides.startNew()` local; não cria no servidor antes de perguntar |
| Tópico existente | Primeira pergunta compactada + hora | — | RadioButton indica atual; `asides.choose(id)` |
| Campo | `aside_input` | `aside-input` | Máximo4 linhas, shape24, pill colors; aceita edição até20000 caracteres |
| Perguntar | `aside_send` | `aside-send` | Se tópico atual sem turno running; enabled draft não branco && !sending; ask(draft), limpa draft imediatamente |
| Parar pergunta | `aside_stop` | — | Substitui enviar se turno do tópico atual running; `asides.cancel()`; errorContainer/onErrorContainer |
| Fechar sheet | Sem chave própria | — | Dismiss chama close no controller |

Fontes: `AsideSheet.kt:65-110`; `data/AsideController.kt:69-108`. Pergunta enviada é trim, não vazia, <=20000; POST `/api/asides` `{agentId,question,topicId?}`. GET `/api/asides?agentId=<codificado>` ao abrir e a cada700ms enquanto qualquer tópico running; cancelar POST `/api/asides/<topicId>/cancel` `{agentId}`. (`data/AsideController.kt:43-45`, `data/AsideController.kt:84-128`.)

Pergunta: bolha direita85%, raio16 primaryContainer, padding12, texto onPrimaryContainer selecionável. Resposta: Markdown full width onSurface, imagens desabilitadas; sem Surface de bolha adicional. Coluna spacing6. Status queued=`aside_queued`; running sem resposta=`aside_thinking`; cancelled=`aside_cancelled`; failed=`aside_failed(error)`; labelMedium, failed error, demais onSurfaceVariant; ponto8 primary quando running. Erro de estado network traduz `reading_offline`, demais exibe texto bruto error bodySmall. (`AsideSheet.kt:64`, `AsideSheet.kt:113-135`.)

## 8. CastActions: chamar, dispensar e retomar

Disponibilidade depende de origem sem login pendente, conexão connected e terminalAllowed ou selectedPermission. Com múltiplas origens sem filtro e sem seleção, FAB não aparece; é necessário selecionar origem/conversa. (`ReadingScreen.kt:94-95`, `ReadingScreen.kt:204`, `ReadingScreen.kt:621-622`, `ReadingScreen.kt:646`.)

GET `/api/cast/jobs` a cada 5 s enquanto STARTED, available decide availability. Ao disponível carrega `/api/cast` (somente members kind=main); recarrega ao abrir escolha/após pedido. Jobs queued/sent/waiting tornam contato ocupado. Free exclui cast já aberto e jobs ativos.

| Controle | Recurso | Tag | Visível / habilitado / ação |
|---|---|---|---|
| FAB chamar | `cast_start` tooltip/descrição | — | available e selected==null, mais condição de origem acima; ícone ic_person_add; abre start se !sending |
| Retomar no topo | `cast_resume` | — | available, sessão fechada OMP, UUID aceito por regex e projeto absoluto; !sending; abre resume-select |
| Atenção aprovação/pergunta | `permission_approve` ou `permission_answer` | — | selected.permission; abre PermissionSheet |
| Menu conversa | `menu` | — | selected != null |
| Dispensar no menu | `cast_dismiss` | — | available, agente aberto, castOpened, cast conhecido ou prefixo novo-; enabled !sending&&!activeJob; abre confirmação |
| Escolher Novo | `cast_new` | — | Primeira linha de start/resume-select; contato fora do elenco |
| Escolher contato | nome dinâmico | — | Apenas free, dono primeiro na retomada; start envia imediatamente |
| Confirmar dismiss/resume | `cast_confirm` | — | !sending; envia pedido |
| Cancelar diálogos | `cancel` | — | Fecha diálogo |

Fontes: `CastActions.kt:124-220`. Regex de sessão retomável `^[0-9a-fA-F][0-9a-fA-F-]{7,199}$`. Retomar **não depende de haver contato livre**, pois Novo sempre existe. (`CastActions.kt:37-38`, `CastActions.kt:130-138`.)

Diálogos start/resume-select: título `cast_select_character`, lista rolável maxHeight360; linhas padding12, spacing12, avatar40, Novo com caixa40+ic_person_add. Start envia ao escolher, sem etapa de pasta. Confirmação dismiss: título `cast_dismiss`, corpo `cast_dismiss_confirm`; resume: título `cast_resume`, corpo nome e projeto separados por newline.

Rotas:
- Novo: POST `/api/cast/new` `{}`; o host resolve `~` como pasta padrão.
- Contato existente novo: POST `/api/cast/<id>/start` `{mode:"new"}`; mesma pasta padrão.
- Novo retomando: POST `/api/cast/new` `{room,session,account}`.
- Contato existente retomando: POST `/api/cast/<id>/start` `{mode:"resume",room,session,account}`.
- Dispensar: POST `/api/agents/<agentId>/dismiss` `{}`.

Fontes: `CastActions.kt:189-191`, `CastActions.kt:215-218`. POST bem-sucedido fecha diálogo e mostra pending; jobs done/failed atualizam resultado. 409=busy,403=forbidden,outros4xx=failed,demais=unknown. Textos bodySmall: `cast_pending`, `cast_done`, `cast_busy`, `cast_forbidden`, `cast_failed`, `cast_unknown`; availability unavailable/forbidden mostra `cast_unavailable`/`cast_forbidden`. (`CastActions.kt:82-90`, `CastActions.kt:103-121`, `CastActions.kt:165-168`.)

Botão de atenção tem ícone ic_help para pergunta ou ic_verified_user para permissão, foreground branco, container animado `#984000`↔`#B44C00`, tween1200ms reverse infinito; sem animações `#984000`. (`CastActions.kt:232-245`.)

## 9. SessionInfoSheet

Abre ao clicar cabeçalho da conversa. Sheet skipPartiallyExpanded, scroll, navigationBarsPadding, start/end20 bottom24, spacing16. Cabeçalho central spacing4, avatar72, título titleLarge central max2/ellipsis, subtítulo bodyMedium onSurfaceVariant. Pill de estado secondaryContainer RoundedCornerShape(50%), padding12×4, labelMedium: fechado `info_closed`; senão status traduzido ou `info_open`. (`ReadingScreen.kt:987-1001`.)

Cartão surfaceContainerHigh raio16, padding16, spacing12:
- `model_menu` com modelo após `/`; `thinking_menu` com thinkingLabel.
- `info_context` com percentual e milhares; progress linear full width sem stop indicator.
- `info_last_activity` com relativeTime.
- `info_loaded` = `info_loaded_value(prompts,respostas)`; adiciona `info_partial` se truncated.
- Botão `info_show_details` / `info_hide_details` alterna detalhes, sem rede.
- Detalhes selecionáveis spacing8: `info_project`, `info_provider` (provider · account), `info_session`, `info_server`.

InfoLine: row spacing12 alinhada top; label bodyMedium onSurfaceVariant, valor bodyMedium peso1 alinhado à direita. Sem tags e sem botão fechar explícito; dismiss da sheet fecha. (`ReadingScreen.kt:1003-1038`.)

## 10. Configurações e RoleDialog

Settings substitui ReadingScreen inteira. TopAppBar `settings`, voltar `back`; BackHandler fecha. Coluna full size, scroll, bottom24. Se múltiplos servidores, FilterChips horizontais com padding16/spacing8 selecionam origem para cotas/papéis. Preferências de tema/view/delivery/notificações são locais ao aparelho, não POSTs. **Não há testTags nos controles de SettingsScreen.** (`ReadingScreen.kt:195-198`; `SettingsScreen.kt:58-75`, `SettingsScreen.kt:126-137`.)

| Seção / controle | Chaves | Condição / ação |
|---|---|---|
| Tema | `settings_appearance`; `theme_system`, `theme_light`, `theme_dark` | Ao theme carregado; RadioRow → store.setTheme(system/light/dark) |
| Visualização padrão | `settings_view_default`; `view_all`, `view_prompts`, `view_no_tools` | Ao view carregado; setDefaultView |
| Envio padrão | `settings_deliver_default`; `deliver_aside`, `deliver_follow_up`, `deliver_steer` | Ao deliver carregado; setDeliverAs |
| Explicação por sessão | `settings_per_session` | Hint |
| Resumos | `settings_summaries`; `settings_summarize` | Switch só habilitado se roles["tiny"] != null; checked=preferência&&hasTiny; setSummarize |
| Explicação resumo | `settings_summarize_hint` / `settings_summarize_needs_tiny` | Conforme hasTiny |
| Papéis globais | `settings_roles`; `role_task`, `role_plan`, `role_tiny`, `role_advisor` | Linha clicável só rolesStatus available e !rolesPending; abre RoleDialog |
| Notificações | `notifications`; `settings_notifications_open` ou `settings_notifications_on` | Só notificationPlatform.available; texto open se whileOpenOnly; atualiza preferência e permissão da plataforma |
| Pedidos/perguntas | `settings_notify_permission` | Habilitado somente toggle principal on; setNotifyEvents |
| Turno concluído | `settings_notify_done` | Mesmo gate; setNotifyEvents |
| Liberar segundo plano | `background_settings` | Só !whileOpenOnly; openBackgroundSettings |
| Cotas | `settings_quotas` e textos abaixo | Snapshot por origem |
| Servidores | `settings_server`; `logout` | Uma linha por servidor, nome bodyLarge, logout texto error → onLogout(server) |
| Adicionar | `add_server` | onLogin(null) |

Fonte: `SettingsScreen.kt:138-231`. Desktop integra notificações available=true, whileOpenOnly=true, logo usa `settings_notifications_open` e não mostra liberar segundo plano. (`android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt:71-75`.)

Estilos compartilhados:
- Section: titleSmall primary, padding start/end16 top20 bottom4.
- Hint: bodySmall onSurfaceVariant, padding horizontal16 vertical4.
- RadioRow: full width, selectable Role.RadioButton; padding16×10, spacing12, RadioButton sem callback próprio; texto bodyLarge.
- SwitchRow: full width clickable Role.Switch, padding16×10; texto bodyLarge peso1, onSurface ou alpha0,38 desabilitado; Switch sem callback próprio.
- Papel: row padding16×10, nome bodyLarge; descrição bodySmall onSurfaceVariant com provedor/modelo/raciocínio ou `role_default`.

Fontes: `SettingsScreen.kt:175-178`, `SettingsScreen.kt:236-255`.

### Papéis: carga e diálogo

GET `/api/cast/roles` ao abrir, depois10s ou1,5s enquanto pending. 403=`cast_forbidden`; primeiro erro=`roles_load_error`; unavailable=`cast_unavailable`; pending=`roles_saving`; falha save=`roles_error`; descrição `roles_hint`. Falha transitória com roles existentes mantém lista. Save POST `/api/cast/roles` `{roles:{<role>:<value>}}`. Modelos vêm de snapshot, recarregado a cada60s. (`SettingsScreen.kt:76-123`, `SettingsScreen.kt:166-189`.)

RoleDialog AlertDialog título do papel, coluna rolável max420. Passo1 `role_pick_model`, grupos de provedor labelLarge primary padding top12 bottom2, modelos RadioRows; vazio `role_no_models`. Campo manual `role_type_model`, full width padding top8, uma linha, isError se não branco inválido; trailing TextButton `role_use` habilitado somente ROLE_MODEL válido e sem thinking suffix. Clique seleciona modelo, **ainda não salva**. Passo2 `role_pick_thinking`, `role_default` ou sete níveis off/minimal/low/medium/high/xhigh/max; clicar chama onSave(joinRole(model,level)) e fecha. Botão cancel `cancel`; sem confirmButton explícito. (`SettingsScreen.kt:258-291`.)

### Cotas

Loading=`quota_loading`, erro=`quota_error`, vazia=`quota_none`; erro com dados antigos mantém cards e adiciona hint. Snapshot GET `/api/snapshot` a cada60s. Card: padding externo horizontal16 vertical6, raio16, surfaceContainerHigh; padding interno16, spacing10. Header nome/plano titleMedium; stale=`quota_stale` labelSmall warning; disabled=`quota_disabled` labelSmall onSurfaceVariant; noQuota=`quota_no_quota` error bodyMedium. Provedor/plano labelLarge primary; nota bodySmall onSurfaceVariant; fetched `quota_fetched(entryTime)` labelSmall. (`SettingsScreen.kt:76-88`, `SettingsScreen.kt:215-222`, `SettingsScreen.kt:294-317`.)

Cada limite: label mapeia `quota_five_hour`, `quota_seven_day`, `quota_seven_day_opus`, `quota_seven_day_sonnet`, senão literal. Percentual arredonda metade para fora de zero; null=`—`. Progress fração clamp0..1; error se exhausted ou used>=90; warning se>=70; senão primary. Só progress quando used não nulo; resets=`quota_resets`, labelSmall onSurfaceVariant. Spacing4, row spacing8, textos bodyMedium. (`SettingsScreen.kt:319-339`.)

## 11. Tema completo declarado no aplicativo

A seleção system segue isSystemInDarkTheme; light/dark força variante. `MaterialTheme` recebe **apenas colorScheme**; não passa `shapes` nem `typography`. Portanto não existem overrides globais próprios de shapes ou Typography. Overrides locais foram listados por componente acima. As funções lightColorScheme/darkColorScheme completam papéis não passados com defaults Material 3; esses hex **não constam em ZaplaudApp.kt** e não são inventados aqui. (`ZaplaudApp.kt:37-59`.)

Tabela completa de todos os valores explicitamente declarados nesse arquivo; `padrão M3` significa argumento omitido, não cor transparente:

| Papel | Claro | Escuro |
|---|---|---|
| primary | #176B58 | #9CDCC6 |
| onPrimary | #FFFFFF | #00382A |
| primaryContainer | #D3EEE3 | #195342 |
| onPrimaryContainer | #002117 | #B8F5DD |
| secondary | #416A60 | #ACD2C4 |
| onSecondary | padrão M3 | #17372D |
| secondaryContainer | #DCEFE6 | #294D40 |
| onSecondaryContainer | #173C31 | #C9EEE0 |
| surface | #FFFFFF | #171F1B |
| onSurface | padrão M3 | #E0E9E2 |
| background | #F7FAF8 | #101814 |
| onBackground | padrão M3 | #E0E9E2 |
| surfaceVariant | #E2EEE7 | #3E4A42 |
| onSurfaceVariant | #3C4A43 | #C1CEC4 |
| surfaceContainerLowest | #FFFFFF | #0B120F |
| surfaceContainerLow | #F3F8F4 | #171F1B |
| surfaceContainer | #EBF3EE | #1B241F |
| surfaceContainerHigh | #E3EFE7 | #253029 |
| surfaceContainerHighest | #DBEAE1 | #303B34 |

Fontes: escuro `ZaplaudApp.kt:39-49`; claro `ZaplaudApp.kt:50-58`.

Demais papéis (incluindo tertiary/onTertiary/tertiaryContainer/onTertiaryContainer, error/onError/errorContainer/onErrorContainer, outline/outlineVariant, inverseSurface/inverseOnSurface/inversePrimary, scrim, surfaceTint e quaisquer papéis acrescentados pela versão Material) são defaults herdados, não overrides do projeto. [INFERENCE] Para igualar pixels desses papéis omitidos em Slint, é necessário usar os tokens da mesma versão Material 3 resolvida pelo build, não escolher outra paleta.

Cores fora do esquema: warningColor dark=`#FFB870`, light=`#8A5100`, decisão por luminância de background<0,5. Fallbacks de avatar, cores de marcas e laranja animado de permissão estão documentados nas respectivas seções. (`StatusColors.kt:8-18`; `CharacterAvatar.kt:37-40`, `CharacterAvatar.kt:58-60`; `CastActions.kt:235-242`.)

## 12. Inventário de drawables XML

| Arquivo | Geometria / origem | Uso exato |
|---|---|---|
| `drawable/ic_model_claude.xml` | 24dp, viewport24; path preto; Simple Icons CC0; linhas1-6 | Badge modelo Claude, tint #D97757 (`CharacterAvatar.kt:37-46`) |
| `drawable/ic_model_gemini.xml` | 24dp, viewport24; path preto; Simple Icons CC0; linhas1-6 | Badge Gemini, tint #8E75B2 (`CharacterAvatar.kt:37-46`) |
| `drawable/ic_model_openai.xml` | 24dp, viewport24; path preto; Simple Icons CC0; linhas1-6 | Badge OpenAI, tint onSurface (`CharacterAvatar.kt:37-46`) |
| `drawable/ic_verified_user.xml` | 24dp, viewport24; Material Icons Apache2; linhas1-6 | Botão de atenção de aprovação (`CastActions.kt:240-243`) |
| `drawable/ic_help.xml` | 24dp, viewport24; Material Icons Apache2; linhas1-6 | Botão de atenção de pergunta (`CastActions.kt:240-243`) |
| `drawable/ic_person_add.xml` | 24dp, viewport24; Material Icons Apache2; linhas1-6 | FAB chamar e linhas Novo nos dois seletores (`CastActions.kt:159-161`, `CastActions.kt:175-177`, `CastActions.kt:199-201`) |
| `drawable/ic_zaplaud.xml` | 108dp/viewport108; fundo #087D66, balão #F5FFF9 e Z/brilho #087D66; linhas1-10 | Ícone janela e bandeja desktop (`android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt:49-55`) |

Não substituir vetores de marcas por letras: badge possui dimensões, fundo e tint próprios. Demais ícones de tela vêm de Material Icons importados no Kotlin, não desses sete XML. (`ReadingScreen.kt:28-42`; `AsideSheet.kt:13-17`; `CastActions.kt:16-19`.)

## 13. Fixture: processo, opções e protocolo

### Isolamento e execução

`scripts/android-smoke.ts` cria diretório temporário `habblaud-android-*` fora do HOME real, verifica realpath, redefine HOME e HABBLAUD_ATTACHMENTS_DIR **antes de importar módulos do servidor**. Encaminha argumentos a startAndroidFixture; erro remove diretório. Não é um navegador nem executor de UI: inicia fixture e espera stdin. (`scripts/android-smoke.ts:1-26`.)

Opções aceitas:
- `--port <n>`: padrão14748; inteiro0..65535, zero pede porta efêmera.
- `--allow <csv>`: encaminhado a parseAllow.
- `--no-session-guard`: remove `messageSessionGuard` da resposta `/api/access` para testar cliente antigo/incompatível.
- Opção desconhecida/incompleta falha; porta inválida falha.

Fontes: `server/test/android-fixture.ts:44-54`, `server/test/android-fixture.ts:240-254`.

`--allow` reconhece `permissions,messages,terminal,spawn`; separa por vírgula, trim+lowercase, ignora desconhecidos; omitido ou branco significa **somente permissions**. `none` gera conjunto vazio. Não há opção wildcard/all. Para superfície funcional completa usar `--allow terminal,messages,permissions,spawn`. (`server/http/remote.ts:34-36`, `server/http/remote.ts:114-121`.)

Remote HTTP é loopback127.0.0.1, passa por guard e login; localUrl é outra porta loopback sem gate remoto, restrita às rotas de extensão/hook listadas adiante. keepAliveTimeout=3600000 para evitar socket ocioso fechado pelo adb reverse. (`server/test/android-fixture.ts:193-199`, `server/test/android-fixture.ts:226`, `server/test/android-fixture.ts:258-262`, `server/test/android-fixture.ts:274-308`.)

### Linha ready

Uma linha JSON em stdout, campos exatos:

| Campo | Significado |
|---|---|
| ready | true |
| url | `http://127.0.0.1:<porta remota>` |
| localUrl | `http://127.0.0.1:<porta local efêmera>` |
| user | Usuário de teste; ler do processo, não reproduzido neste documento |
| password | Senha de teste; ler do processo, não reproduzida neste documento |
| home | Diretório temporário isolado |
| stagingDir | Diretório attachments/.pending |
| deliveredDir | Diretório attachments/delivered |
| agents | Lista dos seis ids AGENTS iniciais |

Fonte: `server/test/android-fixture.ts:415`. Encerrar stdin, SIGINT ou SIGTERM fecha listeners/streams/timers e remove HOME temporário. (`server/test/android-fixture.ts:397-414`.)

### Comandos stdin

Cada linha é trim e split whitespace; parser usa três tokens `[command,agentId,kind]`. Resposta é uma linha JSON; erro `{ok:false,error:<mensagem>}`. Não pressupor suporte a JSON de entrada. (`server/test/android-fixture.ts:322-326`, `server/test/android-fixture.ts:393-395`.)

| Comando | Efeito / retorno | Fonte |
|---|---|---|
| `stats` | `{sse,terminal,posts,messageRequests,lastHeaders,permissionResults,localAcks,castJobs}`; posts conta caminhos POST; messageRequests guarda status,text,attachments count,expectedSessionId; lastHeaders guarda origin/referer/host/content-type | `server/test/android-fixture.ts:195-213`, `server/test/android-fixture.ts:327` |
| `cast-consume` | Consome/ack positivo de jobs; start cria `fixture:cast:<cast>` e transcript Claude ou retoma arquivo existente; registra inbox; dismiss marca offline; retorna ok,consumed,jobs | `server/test/android-fixture.ts:328-345` |
| `drop-next-response` | Marca próximo POST com status2xx para destruir resposta, **após tratamento**; retorna ok; um POST não2xx não consome flag | `server/test/android-fixture.ts:215-224`, `server/test/android-fixture.ts:347` |
| `expire-attachments` | Avança relógio de staging por ATTACHMENT_TTL_MS+1; retorna ok | `server/test/android-fixture.ts:148-149`, `server/test/android-fixture.ts:348` |
| `stop-streams` | terminals.stop, hub.stop e hub.start; testa queda/reconexão; retorna ok | `server/test/android-fixture.ts:349` |
| `bind-omp <sessionId>` | Valida OMP_SESSION_ID e ausência de terceiro token; semeia transcript OMP, troca sessão de fixture:omp e registra inbox; retorna ok,agentId,sessionId | `server/test/android-fixture.ts:350-357` |
| `clear <agentId>` | Novo UUID/transcript para agente existente; mantém provider; troca sessão/inbox; retorna ok,sessionId | `server/test/android-fixture.ts:359-366` |
| `append <agentId>` | Só agente Claude com path; adiciona assistant `Nova resposta fictícia.`; retorna ok | `server/test/android-fixture.ts:367-372` |
| `deliver <agentId>` | Busca inbox e ack ok=true de todas mensagens/controles; retorna ok,ids,controls | `server/test/android-fixture.ts:373-378` |
| `fail <agentId>` | Mesmo fluxo com ok=false/error fictício; retorna ok,ids,controls | `server/test/android-fixture.ts:373-378` |
| `permission <agentId> bash` | Registra pedido ferramenta, input echo; Claude inclui sugestão allow de sessão | `server/test/android-fixture.ts:379-392` |
| `permission <agentId> question-single` | Uma questão, multiSelect=false, duas opções com descrição | `server/test/android-fixture.ts:382-389` |
| `permission <agentId> question-multi` | Uma questão, multiSelect=true, duas opções com descrição | `server/test/android-fixture.ts:382-389` |
| `permission <agentId> question-omp` | Três questões: cor single2, itens multi3, observação single2; provider registrado depende do agente escolhido | `server/test/android-fixture.ts:384-389` |

Permissões retornam `{ok:true,id}` e registram resultado em stats após resolução; espera é renovada a cada25s enquanto pending. (`server/test/android-fixture.ts:165-173`, `server/test/android-fixture.ts:390-392`.)

### Porta local de extensão

Apenas `/api/mod/inbox`, `/api/mod/inbox/ack`, `/api/omp/events`, `/api/permissions` e seus subcaminhos são encaminhados; demais404. Ack local válido200 com resultado realmente reflected no MessageRegistry entra em localAcks. Essa porta permite extensão/test harness de OMP real, não deve ser usada como URL de login remoto. (`server/test/android-fixture.ts:274-304`.)

## 14. Sessões semeadas

Todos agentes iniciais são criados com `office.addMain`, role `Agente principal`, com startedAt separado por5min; status alterna idle/working pelo índice. (`server/test/android-fixture.ts:34-42`, `server/test/android-fixture.ts:123-135`.)

| Agent id | Session id | Provider / conta | Projeto | Estado / cast |
|---|---|---|---|---|
| fixture:claude | 00000000-0000-4000-8000-000000000001 | claude / .claude | /fixture/projeto | idle; Ana Fictícia/ana |
| fixture:omp | 00000000-0000-4000-8000-000000000002 | omp / .omp | /fixture/caderno | working; Davi Fictício/davi |
| fixture:analise | 00000000-0000-4000-8000-000000000004 | claude / .claude | /fixture/loja | idle |
| fixture:revisao | 00000000-0000-4000-8000-000000000005 | claude / .claude | /fixture/biblioteca | working |
| fixture:interface | 00000000-0000-4000-8000-000000000006 | claude / .claude | /fixture/jardim | idle |
| fixture:testes | 00000000-0000-4000-8000-000000000007 | claude / .claude | /fixture/viagem | working |
| sem agent aberto | 00000000-0000-4000-8000-000000000003 | claude / .claude | /fixture/projeto | histórico, título Conversa encerrada fictícia |
| sem agent aberto | 00000000-0000-4000-8000-000000000020 até ...000000000025 | claude / .claude | projetos acima, mesma ordem | históricos, título Planejamento de <basename> |

Fontes: `server/test/android-fixture.ts:31-42`, `server/test/android-fixture.ts:82-84`, `server/test/android-fixture.ts:123-139`.

### Conteúdo dos transcripts

**Todos transcripts Claude**, inclusive históricos e os criados posteriormente por seed, incluem: prompt inicial;16 pares user/assistant; no primeiro assistant, heading, bold, italic, inline code, listas aninhadas, lista numerada, blockquote, link, **tabela Markdown** e fenced sh com linha longa; demais etapas também têm código. Depois: Bash tool-list com20 linhas resultado, Read tool-read com resultado, uma entrada assistant com thinking multiline+texto+tool-fixture, system `Aviso fictício`, resultado final. (`server/test/android-fixture.ts:79-120`.)

Só fixture:claude inicial segura resultado final: primeiro GET `/api/agents/<id>/terminal` permitido agenda append do resultado após1s; exercita atualização de resultado dentro de grupo de ferramenta. initEntries=50. (`server/test/android-fixture.ts:118-124`, `server/test/android-fixture.ts:181`, `server/test/android-fixture.ts:227-237`.)

OMP: título `Conversa OMP fictícia`, registro session, prompt `Pedido OMP fictício`,60 etapas alternando user/assistant, resposta final. Não contém tools/thinking/tabela no seed OMP. Modelo explícito `claude-opus-5-5`, contexto120000/200000, anel60% warning e badge Claude. Não há thinking level nem lista de modelos explicitamente anunciados nessa configuração inicial. (`server/test/android-fixture.ts:64-76`, `server/test/android-fixture.ts:132-135`.)

Cast inicial: ana ocupada; bia livre e last apontando histórico ...003; caio livre; davi ocupado. Personagens não restringem pastas. O last ...003 é sessão **Claude**, portanto não confundir com cenário retomável OMP. Não há sessão OMP fechada semeada inicialmente.

**Subagentes/advisors: nenhum é criado pelo seed e não há comando stdin específico para criá-los.** Os seis agentes são principais. Não afirmar que screenshots de fixture cobrem galhos/subagentes sem injetar eventos adicionais pela API local adequada. (`server/test/android-fixture.ts:131`, `server/test/android-fixture.ts:325-393`.)

## 15. Exercícios por funcionalidade e limites reais da fixture

| Funcionalidade | Como exercitar / limite |
|---|---|
| Login e alerta HTTP | Usar ready.url e credenciais lidas do ready; HTTP dispara confirmação antes de autenticar. Login inválido permite observar erro; segredo não copiado neste documento. |
| Lista, segmentação, busca | Login com terminal permitido; abertas mostra6 agentes; recentes acrescenta históricos; puxar120dp ou Ctrl/Cmd+F no layout largo. |
| Duas colunas/highlight | Janela >=840; selecionar linha; lista360 e conversa max840. |
| Multisservidor/chips/relogin | Iniciar dois processos com portas distintas e adicionar ambos; remover/invalidar cookie fora da UI ou mudar serviço para obter401. Não existe comando stdin logout/expire-session. |
| Markdown/tabela/código/links | Abrir qualquer transcript Claude; primeiro relatório contém tabela/linha longa. Como janela inicial50 entradas, considerar histórico efetivamente carregado; não presumir paginação. |
| Ferramentas/thinking/system | Abrir fixture:claude e expandir ambos grupos; esperar1s para tool result tardio. |
| Somente prompts/sem ferramentas | Menu Visualização na sessão Claude; confirmar filtros diferentes para thinking/system. |
| Unread e tempo relativo | `append fixture:claude` enquanto outra conversa está selecionada ou lista ativa; retorno à sessão marca lida. |
| Jump-to-end/contador | Rolar conversa Claude para cima, executar append repetidamente; botão reaparece e conta entradas novas. |
| Troca de sessão | `clear fixture:claude` ou `bind-omp <UUID>` com sessão aberta; cliente deve detectar changed, sem enviar ao destinatário antigo. |
| Queda/reconexão | `stop-streams`; também encerrar processo para offline. Não há comando “connecting/offline” direto. |
| Mensagem texto/anexo | --allow messages,terminal; enviar a agente; `stats` inspeciona POST; `deliver <id>` ou `fail <id>` resolve operação. Texto não é automaticamente acrescentado ao transcript por deliver. |
| Ambiguidade POST/reenvio | `drop-next-response`, depois enviar; observar unknown e confirmação explícita antes de reenviar. Flag pode ser consumida por outro POST2xx, inclusive upload/login. |
| Anexo expirado | Upload precisa existir antes de `expire-attachments`; depois enviar id expirado. Comando antes de upload só desloca relógio, não expira upload futuro automaticamente. |
| Permissão ferramenta | `permission fixture:claude bash`; testar allow, sugestão, deny em dois passos, motivo/interromper e terminal. |
| Pergunta single | `permission fixture:claude question-single`; testar opção versus other mutuamente exclusivos. |
| Pergunta multi | `permission fixture:claude question-multi`; testar duas opções e other combinados. |
| Perguntas OMP | `permission fixture:omp question-omp`; três questões, seleção single/multi e resposta livre. |
| Stop | fixture:omp começa working; clicar Parar, `deliver fixture:omp` ou `fail fixture:omp`; ack não altera automaticamente status da Office. |
| Modo de envio | OMP: selecionar aside/followUp/steer no menu e enviar; campo deliverAs transmitido. Fixture não simula efeito cognitivo desses modos. |
| Shake | OMP: menu direto ou long press no anel+confirmação; `deliver fixture:omp` ack confirma; fixture não reduz transcript/contexto automaticamente. |
| Modelo/raciocínio | Raciocínio OMP pode enviar controle; `deliver fixture:omp` confirma. Menu de modelo só aparece se models não vazia, ausente no seed; exige anúncio via extensão/evento local. |
| Aside | Abrir menu OMP, perguntar; fixture responde após500ms com primeira parte e completa1500ms depois; seletor Nova conversa/temas, cancelar enquanto running. (`server/test/android-fixture.ts:151-164`.) |
| Chamar contato | Com spawn permitido, FAB; escolher bia/caio/Novo e pasta; executar `cast-consume`; novo agente aparece. Sem consume job fica pendente. |
| Dispensar | Abrir contato cast gerenciável, confirmar dismiss, `cast-consume`; agente vira offline. Não presumir que chamar criou provider OMP: consume usa Claude. |
| Retomar OMP/Novo | **Não coberto por seed direto**. É necessário produzir sessão OMP fechada via extensão/estado adicional; histórico ...003 é Claude e não mostra ação retomar. |
| Session info | Clique cabeçalho; OMP mostra modelo/contexto; detalhes técnicos alternam sem rede. |
| Settings tema/defaults/notificações | Abrir menu Configurações; testar localmente. Desktop notifica somente com janela em segundo plano. |
| Roles e resumos | UI rotas reais existem, mas fixture não emula consumidor de jobs de roles nem provedor Tiny. Não prometer conclusão de save/resumo só com stdin; é necessária integração de auxiliar/provedor real adicional. |
| Quotas | Fixture usa AccountsService isolado; não semeia explicitamente valores de quota/plano/janelas. Cobertura de barra/exaustão exige dados adicionais, não comando stdin existente. |
| Oversize/reduced/recovered-error | Seed não cria tamanho excessivo nem system id :err recuperado; exige transcript/evento adicional. |
| Subagentes | Seed e stdin não criam; UI precisa ser coberta com eventos/dados adicionais, não suposta como exercitada. |

Evidências dos comandos e ausência de emulação extra: `server/test/android-fixture.ts:127-188`, `server/test/android-fixture.ts:325-393`. As afirmações de capacidade de UI são condicionais ao código de tela acima; não constituem resultado de execução.

## 16. Regras adicionais de modelos e integração do host

- `modelBrand` ignora caixa e prefixos até a última `/`: começa com claude → Claude; gpt/codex ou regex `^o\d` → OpenAI; gemini → Gemini; desconhecido → nenhum badge. A família não depende de provider da sessão. (`data/Roles.kt:41-49`.)
- Ordem real dos papéis em Settings é **task, plan, advisor, tiny**. (`data/Roles.kt:8-9`.)
- Modelo manual de papel deve casar `^[A-Za-z0-9][\w.-]*/[^\s\x00-\x1f\x7f]{1,200}$`; `splitRole` só interpreta sufixos de raciocínio conhecidos após a última `/`. `joinRole` acrescenta `:<level>` se não nulo. (`data/Roles.kt:11-21`.)
- Modelos do snapshot são strings de `models` de todos agentes, distinct e sorted. Não existe lista fixa embutida de modelos. (`data/Roles.kt:23-27`.)
- Grupos de modelos exigem `/`, acrescentam o atual mesmo que ausente, removem `:batch` e variante datada `-\d{8}` quando base sem data existe, **exceto o atual preservado**. Provedores em ordem alfabética case-insensitive; modelos tokenizados por partes alfanuméricas, números descendentes, texto alfabético. Versão numérica adicional vem antes; sufixo textual adicional vem depois. (`data/Roles.kt:52-83`.)
- Labels de provedor: anthropic=`Anthropic`; openai-codex=`OpenAI Codex`; google-antigravity=`Google Antigravity`; openrouter=`OpenRouter`; demais preservam id. (`data/Roles.kt:29-39`.)
- Android usa edge-to-edge, status/navigation bars transparentes seguindo tema local. NotificationPlatform é available=true e whileOpenOnly fica false por padrão; portanto Settings mostra `settings_notifications_on` e botão de configuração de bateria. (`android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:23-54`; `UiPlatform.kt:12-18`.)
- Android ligar notificações inicia serviço quando já permitido; API33+ solicita POST_NOTIFICATIONS quando necessário. Desligar para serviço. Liberar segundo plano abre `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`. (`android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:32-52`.)
- Intent com extra `session` desserializa SessionKey em pendingSession e remove extra. ReadingScreen abre seleção correspondente quando controllers estão disponíveis, fechando seleção de outras origens. Isso é navegação pendente de notificação, distinta das linhas `needsLogin`. (`android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:57-69`; `ReadingScreen.kt:128-135`.)

### Limite das cores herdadas

As duas paletas acima são completas quanto aos argumentos efetivamente escritos em `ZaplaudApp.kt`. Os papéis Material omitidos não possuem hex declarado nesse arquivo. A busca por arquivos `material3*-sources.jar` no cache Gradle local não encontrou fontes para citar esses defaults; não foi feita resolução de dependências nem execução de build. Logo, este documento não transforma valores herdados não lidos em números supostamente exatos.
