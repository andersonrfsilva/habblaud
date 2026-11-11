# Zaplaud Kotlin: HTTP, sessão, snapshots e streams

Base auditada: `/home/anderson/Desenvolvimento/habblaud`. Repositório somente leitura. Referências abaixo são relativas à raiz.

## Convenção de referências e tipos

Para manter tabelas legíveis, `K` significa `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud`, `D` significa `K/data`, `J` significa `android/shared/src/jvmMain/kotlin/br/com/xlinx/habblaud`, `A` significa `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud`. Assim, `D/HabblaudApi.kt:72` é uma referência exata ao arquivo sob esse prefixo, não um arquivo diferente.

`S` abaixo significa a extração Kotlin `JsonObject.string`: aceita conteúdo textual de qualquer primitivo JSON não nulo, incluindo números e booleanos; objeto, array, ausência ou null resultam em null. Não equivale a um desserializador Rust estrito de `String`. `flag` usa `booleanOrNull == true`; ausente, inválido e false resultam em false. `objects` retorna apenas objetos de um array; ausência, tipo inválido ou null resultam em lista vazia. Campos desconhecidos não são lidos. A raiz esperada como objeto ainda pode causar exceção se inválida. Fonte: `D/ApiModels.kt:16,28-30`.

`?` significa opcional; defaults indicam comportamento efetivo do parser, não obrigatoriedade no modelo TypeScript. Respostas JSON abaixo mostram somente campos lidos pelo Kotlin, salvo envelopes SSE explicitamente marcados como emitidos mas ignorados.

## 1. Regras comuns de HTTP

- `HabblaudApi.request(path, body=null)` faz GET. Qualquer corpo não nulo faz POST; não há PUT/DELETE nesse cliente. Caminho deve começar por `/api/`. JSON é transmitido como `application/json; charset=utf-8`, comprimento conhecido, por `WriteChannelContent`, sem replay automático de POST. GET com `IOException` antes de qualquer resposta faz exatamente uma nova tentativa; não repete depois de receber cabeçalhos. `D/HabblaudApi.kt:72-94`.
- Cookie manual `Cookie: habblaud_remote=<token>` quando elegível. Monitor de todas as respostas recebe `Set-Cookie` com geração capturada no envio. Não há Bearer token, API key ou cabeçalho CSRF customizado. `D/HabblaudApi.kt:38-55`.
- 300–399 sempre causam `RedirectException("Redirecionamento recusado")`; não seguir nem redirecionamento de mesma origem. 401 invalida a geração do cookie e cancela outras chamadas dessa API. Demais não-2xx causam `ApiException(status, retryAfter, code, attachmentsExpired, detail)`: `Retry-After` bruto; `code` como S não vazio; `attachmentsExpired` como flag; `error` como S truncado a 500 caracteres, descartado se blank. `D/HabblaudApi.kt:22-28,57-69`.
- Resposta JSON do servidor usa `application/json; charset=utf-8`, `Cache-Control: no-store`; gzip quando JSON >1024 caracteres e pedido aceita gzip. `server/http/app.ts:191-205`.
- Guard do servidor exige Host IP literal, localhost/`*.localhost`, ou nome configurado em `HABBLAUD_ALLOWED_HOSTS`. POST exige Origin aceito se enviado; Origin ausente é aceito. POST `/api/*` exige JSON, exceto uploads binários. Kotlin não define Origin. Falhas de guard podem ser texto puro, portanto `detail` será null. `server/http/guard.ts:65-94,118-146`.
- A porta local não exige sessão remota. Porta remota exige cookie para toda API exceto login/logout. Recursos sensíveis ainda dependem de feature liberada: terminal, messages, permissions. Estar autenticado não libera tudo. `server/http/remote.ts:562-628,658-675`; `server/http/app.ts:313-321,480-495`.

## 2. Inventário completo de endpoints chamados

Todas as linhas herdam regras comuns acima. `T`, `M`, `P` nas colunas de autorização significam, respectivamente, recurso e permissão terminal, messages, permissions; local exige Host loopback, remoto exige feature correspondente.

| Método | Caminho | Query/corpo enviado | Resposta lida e defaults | Headers/autorização e status específicos | Kotlin | Handler servidor |
|---|---|---|---|---|---|---|
| POST | `/api/remote/login` | JSON `{user: string, password: string}` ambos presentes | `{ok: flag=false}`; false provoca `IOException("Login recusado")` | JSON; não exige cookie. 401 erro de login; 403 proibido; 429 persiste bloqueio por origem usando Retry-After; 3xx erro de redirect | `D/HabblaudApi.kt:109-112`; `K/ZaplaudApp.kt:184-229` | `server/http/remote.ts:572-618` |
| POST | `/api/remote/logout` | JSON `{}` | corpo ignorado | Cookie opcional no servidor; 200 apaga/revoga sessão. Cliente retorna false em IOException e sempre cancela chamadas/limpa cookie | `D/HabblaudApi.kt:116` | `server/http/remote.ts:621-628` |
| GET | `/api/access` | nenhum | `{terminal: flag=false, messages: flag=false, messageSessionGuard: flag=false, permissions: flag=false}`; login/startup só verifica sucesso | cookie remoto; 401 marca somente essa origem para novo login | `D/HabblaudApi.kt:113`; `D/SessionRepository.kt:47-52`; `K/ZaplaudApp.kt:73-89,201-202` | `server/http/app.ts:480-498` |
| GET SSE | `/api/stream` | nenhum; sem since/cursor | eventos snapshot/feed/notice; só snapshot é consumido | `text/event-stream`; cookie remoto; política SSE abaixo | `D/ReadingController.kt:108-129`; `D/AlertWatcher.kt:24-39` | `server/http/app.ts:440-444`; `server/http/sse.ts:82-98` |
| GET | `/api/snapshot` | nenhum | snapshot descrito nas seções 5 e 6 | cookie remoto; conferência de sessão reexecuta até 3 vezes para rede/408/429/5xx | `D/ReadingController.kt:245-265`; `K/SettingsScreen.kt:81-90` | `server/http/app.ts:445-449` |
| GET | `/api/sessions/recent` | nenhum | `{sessions: object[]=[], ...}`; cada item usa mesmo decoder de sessão da seção 5 | T. 403 desliga terminal e produz lista vazia; demais falhas propagam | `D/SessionRepository.kt:53-57,84-98` | `server/http/app.ts:527-533`; `server/http/sessions.ts:49-81` |
| GET | `/api/cast` | nenhum | `{members: object[]= []}`. Repository lê account?, last? mapa de S, id?, name?, seed?, look?, parts?, kind?. CastActions filtra kind=main, exige id/name, avatar opcional. Personagens não têm `rooms` nem `home` | T. Repository tolera 403/404 como elenco vazio; CastActions trata 401 como relogin e demais como falha | `D/SessionRepository.kt`; `K/CastActions.kt` | `server/http/app.ts` |
| GET | `/api/cast/jobs` | nenhum | `{available: flag=false, jobs: object[]= []}`; job exige `{cast:S,status:S}`; mapa cast→status | T. 401 encerra loop/relogin; 403 availability=forbidden; demais offline. Poll a cada 5 s | `K/CastActions.kt:67-94` | `server/http/app.ts:556-562` |
| POST | `/api/cast/new` | novo: `{}`; retomar: `{room:string, session:string, account:string}` | `{job?:object{cast:S?}}`; novo usa `~` resolvido pelo host; retomada preserva a pasta histórica; sucesso vira pending mesmo sem job/cast | T. 202. 409 busy; 403 forbidden; outros 4xx failed; rede/5xx unknown; sem retry de envio | `K/CastActions.kt` | `server/http/app.ts` |
| POST | `/api/cast/{castId}/start` | novo: `{mode:"new"}`; retomar: `{mode:"resume",room:string,session:string,account:string}` | mesmo job acima; novo usa `~` resolvido pelo host | T. Mesmo tratamento de cast/new; castId codificado em segmento | `K/CastActions.kt` | `server/http/app.ts` |
| POST | `/api/agents/{agentId}/dismiss` | `{}` | mesmo job acima | T. Mesmo tratamento de cast/new; id codificado | `K/CastActions.kt:216,103-122` | `server/http/app.ts:554-600` |
| GET | `/api/cast/roles` | nenhum | `{roles?:object{default:S?,task:S?,plan:S?,advisor:S?,tiny:S?},pending:boolean=false,available:boolean=false}`; roles null quando objeto ausente; pending/available comparados por `.toString()=="true"` | T. 401 para polling; 403 forbidden; falha transitória mantém roles anterior. Poll 1500 ms se pending, 10000 ms senão | `K/SettingsScreen.kt:97-116`; `D/Roles.kt:9` | `server/http/app.ts:556-564` |
| POST | `/api/cast/roles` | `{roles:{<um de default/task/plan/advisor/tiny>: string}}`; valor `provedor/modelo` ou `provedor/modelo:nível`; opção default remove somente sufixo de raciocínio, não envia string vazia | corpo ignorado | T. Sucesso dispara refresh; qualquer falha marca erro e desfaz pending; não repete | `K/SettingsScreen.kt:119-124,267-291`; `D/Roles.kt:15-21` | `server/http/app.ts:594-607` |
| GET SSE | `/api/agents/{agentId}/terminal` | opcional `after=<entryId codificado>&at=<Long>`; ambos só quando cache tem entrada datada | init/append da seção 4 | T; inclui subagentes. 403/404 terminal; 429 retry. Id codificado | `D/ReadingController.kt:220-279`; `D/ApiModels.kt:42-49` | `server/http/app.ts:508-525`; `server/http/terminal.ts:233-279` |
| GET SSE | `/api/sessions/{account}/{sessionId}/terminal` | mesmo after/at; não envia provider | mesmo init/append | T. Histórico resolve conta/id e valida transcript; 403/404 terminal | `D/ReadingController.kt:220-236` | `server/http/sessions.ts:49-81`; `server/http/terminal.ts:244-279` |
| POST | `/api/messages/attachments` | bytes crus; **não multipart**, nenhum field de formulário | `{id:S obrigatório}`; name/size ignorados | M. Octet-stream + X-Habblaud-File-Name percent-encoded UTF-8 + X-Habblaud-File-Type MIME. 201; 413 grande demais, 429 depósito cheio; ambos tratados como erro de anexo | `D/HabblaudApi.kt:96-107`; `D/MessageController.kt:184-198,233-240` | `server/messages/http.ts:48-51,78`; `server/spawn/attachments.ts:55-109`; `server/http/app.ts:849-859` |
| POST | `/api/messages` | `{agentId:string,text:string,expectedSessionId:string,deliverAs?:"aside"|"followUp"|"steer",attachments?:string[]}`. deliverAs só provider omp; attachments só se não vazio | `{id:S obrigatório,status:S obrigatório}` | M. 201; 409 com code=session_changed fecha conversa; 400 attachmentsExpired=true mostra attachments_expired; 4xx definidos preservam operação anterior/draft. Falha ambígua depois do dispatch vira unknown, sem retry automático | `D/MessageController.kt:169-254` | `server/messages/http.ts:33-47,77`; `server/http/app.ts:849-859` |
| POST | `/api/messages/control` | `{agentId:string,expectedSessionId:string,stop:true}` OU um campo `{command:"shake"}` OU `{model:string}` OU `{thinking:string}` | `{id:S obrigatório,status:S}`; interrupção exige status; demais status opcional e confirmado via polling | M. 201; 409/session_changed fecha; 4xx failed/definido; falha de rede unknown. Sem retry automático | `D/MessageController.kt:256-325` | `server/messages/http.ts:34-47,79` |
| GET | `/api/messages/{id}` | nenhum | `{status:S obrigatório no polling comum, error:S? lido somente por controle}` | M. 404 vira unknown e encerra polling; 401 relogin. Poll comum/stop a cada 2 s até delivered/failed/unknown. Comando shake/model/thinking no máximo 15 polls | `D/MessageController.kt:306-318,328-381` | `server/messages/http.ts:84-96` |
| GET | `/api/permissions/{id}` | nenhum | detalhe da seção 7 | P. 401 relogin; 404/409 fecha folha; demais network | `K/PermissionSheet.kt:78-96,131-169` | `server/permissions/http.ts:87-107`; `server/http/app.ts:535-544` |
| POST | `/api/permissions/{id}/decision` | decisão da seção 7 | corpo ignorado | P. 404/409 fecha; 401 relogin; demais 4xx decision; rede/5xx/cancelamento ficam ambíguos e bloqueiam novo envio | `K/PermissionSheet.kt:31-60,97-109` | `server/permissions/http.ts:59-77,99-102` |
| GET | `/api/asides?agentId={id}` | query agentId:string codificada como parâmetro | `{topics:object[]=[]}`; detalhes seção 7 | T para leitura, recurso asides deve existir. 401 relogin; demais detail do servidor ou network. Poll 700 ms enquanto queued/running | `D/AsideController.kt:113-141` | `server/asides/http.ts:58-69`; `server/http/app.ts:652-658` |
| POST | `/api/asides` | `{agentId:string,question:string,topicId?:string}`; question trim, não vazia, ≤20000 caracteres | `{topic?:object}` via decoder de tópico; ausente/sem id não altera tópicos | M. 201; 404 desconhecido, 409 conflito, 429 fila cheia; exibidos por detail/network, sem retry de POST | `D/AsideController.kt:84-99` | `server/asides/http.ts:38-49,67-69`; `server/http/app.ts:652-658` |
| POST | `/api/asides/{topicId}/cancel` | `{agentId:string}` | corpo ignorado; sucesso repete GET | M. 404 desconhecido/409 ocioso; detail/network | `D/AsideController.kt:102-109,135-141` | `server/asides/http.ts:51-56,79-82` |
| POST | `/api/summaries` | `{text:string}` | `{summary:primitive obrigatório}` lido por jsonPrimitive.content; ausência lança | M. 404 serviço ausente; 403 sem permissão; 422 texto inválido; 502 falha; 503 indisponível. Repository não aplica fallback HTTP | `D/HabblaudApi.kt:114-115`; `D/ReadingController.kt:174-183` | `server/http/app.ts:660-674` |
| GET | `/api/avatar.png` | `seed=<Long>&look=f|m&kind=main|sub&scale=3&parts?=<JSON object string>`; todos valores URL-encoded | bytes PNG, não JSON, até 1 MiB | Cookie remoto; exige Content-Type image/png sem parâmetros para comparação. Todo não-2xx retorna null depois de tratar 401/redirect. IOException antes de headers repete 1 vez | `D/AvatarCache.kt:23-38`; `D/HabblaudApi.kt:117-139` | `server/http/app.ts:416-427` |

O inventário inclui strings diretas e caminhos construídos por interpolação. Não adicionar rotas web de arquivos, dictation, spawn, health, world, PTY ou agent detail: não são chamadas por estes consumidores Kotlin. O upload é binário, não multipart.

## 3. Login, sessão e isolamento por origem

### Login e logout no servidor

Login envia exatamente `{user,password}`, ambos strings. Servidor lê até 8 KiB; trim apenas no usuário, não na senha; compara usuário em tempo constante e sempre verifica senha. Retorna 200 `{ok:true}` e Set-Cookie; credenciais ruins retornam 401 `{error:string}`; corpo grande/inválido na leitura pode retornar 400. JSON malformado cai em credenciais vazias e recusa. `server/http/remote.ts:437-447,592-617`.

Cookie: `habblaud_remote=<token>; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000`, acrescentando `Secure` com TLS direto ou proxy confiável que declara HTTPS. Sem Domain/Expires explícitos na emissão. Token tem formato `v1.<expiração epoch-ms>.<assinatura base64url>`. Validade de 30 dias, não sliding; usuário/hash da senha entram na chave HMAC, portanto troca de credenciais invalida sessões. Não reproduzir tokens reais. `server/http/remote.ts:62-63,147-198,298-305,540-542,609-610`.

Logout aceita POST mesmo sem sessão válida; revoga token válido antes de retornar 200 `{ok:true}` e cookie vazio Max-Age=0. Lista de revogados pode sobreviver a reinício. Lista ilegível rejeita tokens emitidos antes do startup; falha de escrita deixa revogação só no processo. `server/http/remote.ts:180-226,621-628`.

Freio de login por origem tem FREE_FAILURES=5, contador que começa na tentativa; ao alcançar quinta falha bloqueia 1 s, depois 2,4,8… até 300 s. Uma verificação por origem, quatro globais simultâneas, 60 falhas globais por janela de 5 minutos. 429 inclui Retry-After em segundos, mínimo 1. IPv6 agrupado por /64. `server/http/remote.ts:250-259,307-316,348-385,579-586`.

Sem cookie válido, API da porta remota responde 401, não redirect. Páginas não-API recebem 302 `/login`; `/login` autenticado recebe 302 `/`. Cliente Kotlin nunca usa páginas de login. `server/http/remote.ts:649-675`.

### CookieStore e SessionMemory

- Singleton de processo `SessionMemory`: lock reentrante, mapa de geração Long por origem e mapa de Cookie? por origem. Cada `SessionCookieStore(origin)` lê storage compartilhado e substitui cookie em memória pelo persistido; se cookie anterior existia e persistência sumiu, incrementa geração. `D/SessionCookieStore.kt:17-21,40-63`.
- Storage serializa `{v:2,cookies:{<origin>:<renderSetCookieHeader string>}}`; também lê formato legado `{origin,cookie}`. JSON inválido vira mapa vazio. `D/SessionCookieStore.kt:22-37`.
- Salvar resposta exige geração igual, origem exata e último Set-Cookie com nome `habblaud_remote`. Rejeita Domain não correspondente ao host. Converte Max-Age em expiração absoluta; cookie já vencido limpa origem. Preenche Domain pelo host, Path pelo diretório da URL se ausentes. Persiste primeiro; falha remove sessão e lança erro de armazenamento seguro. `D/SessionCookieStore.kt:64-91`.
- Envio exige origem exata incluindo esquema/porta, cookie não vencido, HTTPS quando Secure e path igual/prefixo de diretório. Não envia a outras origens nem subdomínios apenas por Domain. Retorna somente `nome=valor`. `D/SessionCookieStore.kt:92-104,124-127`.
- clear incrementa geração, limpa memória e remove só essa origem do storage. 401 de geração antiga não pode invalidar login novo; Set-Cookie de chamada antiga também não pode ressuscitar sessão encerrada. `D/SessionCookieStore.kt:105-122`; `D/HabblaudApi.kt:36-63`.
- JVM: keyring serviço `br.com.xlinx.zaplaud`, conta `remote-session`; opção `zaplaud.keyring=memory` força memória. Falha de criação ou gravação do keyring deixa login só no processo; não salvar senha. `J/data/SessionCookieStore.jvm.kt:11-30`.
- Android: arquivo privado `filesDir/remote-session`, AES/GCM/NoPadding, IV de 12 bytes prefixado, tag 128 bits, chave AndroidKeyStore `habblaud-remote-session`; erro de leitura apaga arquivo. `A/data/SessionCookieStore.kt:14-38`.

### Relogin e múltiplos servidores

Startup instancia API por servidor salvo e consulta access. Falta de cookie inicialmente marca needsLogin, mas access bem-sucedido remove marca (porta local pode funcionar sem cookie). 401 marca somente origem correspondente, não todas. Login executa login seguido de access antes de adicionar servidor. Senha é apagada do estado antes da chamada. Logout tenta revogar, remove servidor e dados locais, fecha API e reinicia notificações dos demais; falha de revogação gera aviso explícito. `K/ZaplaudApp.kt:73-94,101-118,128-154,184-229`.

429 de login usa deadline persistido por origem. Antes de novo envio, respeita retryUntil. JVM aceita segundos inteiros não negativos com proteção de overflow ou data RFC1123, data passada vira now, inválido/ausente vira now+60 s. `K/ZaplaudApp.kt:192-219`; `J/UiPlatform.jvm.kt:57-61`.

### UrlPolicy e aviso HTTP

Aceita somente origem HTTP/HTTPS, qualquer host não vazio, inclusive IP/IPv6, host LAN e nomes públicos; não há allowlist cliente. Trim externo; uma `/` final opcional; recusa path, query, fragmento, userinfo `@`, `%` na autoridade, whitespace interno, porta vazia/inválida/fora de 1..65535. Normaliza esquema e host para minúsculas, IPv6 com colchetes, remove porta padrão e barra final. `D/UrlPolicy.kt:5-23`.

Toda tentativa de login para origem canônica `http://` exige diálogo de confirmação, inclusive localhost. Não é só aviso para endereços públicos. Confirmação só conecta se URL atual ainda normaliza para a origem confirmada. HTTPS não mostra diálogo; URL inválida mostra erro e limpa senha. `K/ZaplaudApp.kt:184-188,233-249`.

## 4. SSE: framing, limites e reconexão

### URLs e envelopes

1. `/api/stream`, sem parâmetros: servidor envia `retry: 2000`, `snapshot` completo e `feed` recente (máximo 50) ao conectar. Depois snapshot com throttle padrão 200 ms, feed em lote e notice individual. Ping comentário `: ping` a cada 15 s. Kotlin ReadingController e AlertWatcher ignoram feed/notice. `server/http/sse.ts:32-50,82-98,100-130`; `D/ReadingController.kt:108-127`; `D/AlertWatcher.kt:24-34`.
2. `/api/agents/{id}/terminal` ou `/api/sessions/{account}/{sessionId}/terminal`, opcional `after` + `at`: `init` objeto `{agentId:string,entries:TerminalEntry[],truncated:boolean,after?:string}`; `append` array de TerminalEntry. Cliente ignora agentId; lê entries/truncated e presença de after, não compara valor de after. `shared/types.ts:665-682`; `D/ReadingController.kt:267-275`.
3. `snapshot` usa seção 5. `feed` emitido, não decodificado pelo Kotlin: array `{id,agentId,roomId,agentName,roomName,account?,activity}`; `notice` emitido, não decodificado: `{id:string,level:info|success|warn|alert,text:string,agentId?:string,roomId?:string,at:number}`. `shared/types.ts:556-586`.

Servidor formata cada evento como `event: <name>\ndata: <JSON.stringify>\n\n`. Sem campo id. Usa Content-Type `text/event-stream; charset=utf-8`, no-cache/no-transform, keep-alive, X-Accel-Buffering=no. Gzip com Z_SYNC_FLUSH por frame se Accept-Encoding permitir. `server/http/sse.ts:165-192`.

### Parser efetivo Kotlin

Ktor SSE é o único decoder. Wrapper só expõe evento se `data != null`; event ausente vira `message`; normaliza CRLF de data para LF. Comentários não viram StreamEvent.Data. Teste de contrato lê `: ping\r\nevent: append\r\ndata: one\r\ndata: two\r\n\r\n` como um evento append com data `one\ntwo`. `D/ConversationRepository.kt:27-44`; `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/ConversationStreamTest.kt:107-118`.

Wrapper descarta `id`, `retry` e demais metadados do ServerSentEvent. Não guarda Last-Event-ID, não fornece lastEventId ao abrir conexão nova. Reconnect externo usa nova chamada `client.sse` com mesmo path. Sem contrato próprio de BOM/campo desconhecido/linha id com NUL no repositório: [INFERENCE] comportamento detalhado desses casos depende da versão Ktor; não fingir que foi inspecionado o código da dependência. `D/ConversationRepository.kt:33-45,64-85`.

### Limites

`installBoundedSse` envolve o canal da resposta adaptada, lê buffer de 8192 bytes e conta bytes crus antes de entregar ao decoder Ktor. Limite por frame: 8*1024*1024 bytes. Conta todos bytes não CR/LF, inclusive comentários/campos desconhecidos; não é só JSON. Linha vazia zera contador. LF e CR são separadores; CRLF conta como uma quebra. Superar limite lança SseLimitException, fecha canal e cancela entrada. Não há timeout próprio na função. Timeout de leitura OkHttp é 45 s; pings do servidor são 15 s. `D/BoundedSse.kt:11-56`; `D/ApiModels.kt:84`; `J/data/HttpTransport.jvm.kt:17`.

Após decoder, ConversationState também exige soma de bytes das entradas de cada init/append ≤8 MiB e cada entrada ≤8 MiB. Mantém no máximo 1000 entradas e 8 MiB; remove mais antigas e marca reduced=true. Tamanho é UTF-8 da concatenação id+kind+text+title+toolUseId, sem overhead JSON. IDs duplicados são ignorados, não atualizados. `D/ApiModels.kt:51-84`.

Servidor limita 8 terminais simultâneos, retornando 429 JSON (sem Retry-After nessa rota). Init lê tail de 4 MiB e guarda até 500 entradas; polling agente 400 ms, demo 500 ms, histórico 2000 ms. Transcript truncado, substituído ou trocado provoca novo init. Buffer acumulado >8 MiB desconecta cliente lento; SSE geral tem mesmo limite. `server/http/terminal.ts:24-39,112-151,250-278,299-325`; `server/http/sse.ts:149-160,183-191`.

### Reconexão e falhas

`ConversationRepository.stream` faz backoff 2000,4000,8000,16000,30000,30000… ms entre handshakes falhos. Connected zera contador de falhas e backoff para 2000, mesmo se conexão cair logo. EOF é falha status null. Cada tentativa usa `max(delayMs, retryAfterMillis)`. Retry-After aceita inteiro em segundos clamped 0..86400 ou data HTTP clamped 0..86400000 ms; inválido vira 0. `D/ConversationRepository.kt:14-19,46-85`.

Terminais: -1 (frame oversized), 401,403,404, qualquer 300–399: emite falha imediatamente e encerra fluxo. Demais, incluindo 408/409/413/429/5xx e rede: tenta novamente e só emite aviso a partir da terceira falha consecutiva. Um Connected reinicia essa contagem. Cancelamento é propagado e cancela requestJob até antes do handshake. `D/ConversationRepository.kt:35-61,64-85`.

401 limpa sessão/cancela chamadas e chama relogin; UI distingue oversize, forbidden, missing, network. Falha do stream de snapshot põe connection=offline; falha de conversa põe readingError. Connected limpa só erro network, não forbidden/missing/oversize. `D/ReadingController.kt:42-48,108-111,201-204`.

AlertWatcher conserva último mapa antes da queda; snapshot depois do reconnect compara com esse mapa, permitindo avisar mudanças ocorridas offline. Primeiro snapshot não notifica. Se fluxo termina com 401, abandona origem; outros términos tentam abrir fluxo novamente após 30000 ms, mesmo se erro era terminal no repository. `D/AlertWatcher.kt:19-39`.

### Cache incremental, epochs e ausência de paging

Ao abrir conversa, generation local incrementa e request anterior é cancelado. Subagentes não usam cache persistente. Cache aparece imediatamente. Busca última entrada que possua `at`; só então URL inclui `?after=<id>&at=<at>`. A URL é calculada uma vez ao abrir: reconexões usam a mesma âncora inicial, não último append recebido. Dedupe por id evita duplicação. `D/ReadingController.kt:211-236`; `D/ApiModels.kt:74-81`.

Servidor só aceita âncora encontrada uma única vez no init atual com id e at iguais. Ausente, inválida, fora da janela ou duplicada causa init completo. Âncora válida devolve entries posteriores e campo after. `server/http/terminal.ts:343-352`.

Cliente: init com campo after presente (inclusive JSON null, pois teste é presença do elemento) e cachedKey igual soma entradas; caso contrário substitui todas e lê truncated. append antes do primeiro init é ignorado. `D/ReadingController.kt:267-279`.

Em todo init de agente principal aberto, consulta `/api/snapshot` para validar id/kind=main/account/provider/sessionId. Não fecha só porque recebeu novo init. Se confirmação diverge, fecha conversa e error=changed. Falha transitória confirma até 3 tentativas, esperas 1000/2000 ms ou Retry-After maior; 401 relogin, falha definitiva fecha com network. `D/ReadingController.kt:50-64,242-265`.

`generation` e `conversationEpoch` são proteções locais de cancelamento/persistência. Não são campos HTTP. Não há query `since`, `cursor`, `page`, epoch ou endpoint de carregar histórico anterior neste cliente. O único cursor de rede é after+at; truncated não dispara paginação. `D/ReadingController.kt:73-77,135-141,187-198,211-279`.

## 5. Snapshot completo conforme SessionRepository.snapshot

A forma de entrada lida é:

```text
{
  serverTime?: primitive(S→Long),
  rooms?: [ { id?: S, path?: S, name?: S } ],
  agents?: [ Agent ],
  // outros campos não são lidos por SessionRepository.snapshot
}
```

serverTime inválido/ausente conserva valor anterior (inicial 0). rooms cria mapa com chave id nullable e valor path senão name nullable; duplicates usam último. Só agents kind="main" e parentId extraído como null entram como sessões abertas. Subagentes ativos vêm do mesmo array, conforme tabela abaixo. `D/SessionRepository.kt:31-45,63-73`.

### Todos os campos de Agent/item lidos

| Campo JSON | Leitura/default e uso |
|---|---|
| account | S obrigatório para criar sessão; ausente/invalid/null descarta item |
| sessionId | S obrigatório; ausente descarta |
| provider | S, default "claude"; parte da SessionKey junto com origin/account/sessionId |
| id | S, fallback agentId; ambos ausentes resultam agentId=null |
| agentId | S fallback de id, usado também em sessões recentes |
| title | S; fallback name, depois sessionId |
| name | S nullable; agentName; characterName só se kind=main |
| kind | S; filtro main; usado por avatar e characterName |
| parentId | S; principal só se null |
| roomId | S; lookup em rooms |
| project | S nullable; usado quando projeto obtido de rooms é null |
| status | S nullable; "offline" força agentId=null e canMessage=false |
| activity | objeto opcional; summary:S senão text:S senão description:S; default null |
| permission | objeto opcional; presença do objeto significa permission=true, mesmo vazio |
| permission.id | S nullable |
| permission.questions | object[]=[], somente verifica se não vazio para permissionQuestion |
| canMessage | flag=false |
| lastEventAt | S senão lastAt; tenta Long; default null. lastEventAt presente mas não numérico não faz fallback para lastAt |
| lastAt | S fallback acima, incluído especialmente em recent |
| seed | S→Long dentro 0..4294967295, senão avatar=null |
| look | S exatamente "f" ou "m", senão avatar=null |
| parts | objeto opcional serializado para string JSON no Avatar; outro tipo vira null |
| cast | S nullable |
| castOpened | flag=false |
| context | objeto opcional |
| context.tokens | S→Long ≥0, senão null |
| context.window | S→Long >0, senão null |
| model | S nullable |
| thinking | S nullable |
| models | array, só primitivas com isString=true; default [] |
| replies | array, só primitivas com longOrNull válido; default []; sem filtro positivo |
| statusSince | S→Long >0; default null; converte horário do servidor ao relógio local |
| role | S; somente subagente, advisor quando "__advisor" |

Fonte exata de item: `D/SessionRepository.kt:84-98`; avatar: `D/SessionRepository.kt:109-113`; helpers: `D/ApiModels.kt:28-30`.

Para subagentes exige kind="sub",status="working",parentId e id não nulos. name default id; activity summary senão text (não description); avatar mesma regra; advisor quando role="__advisor". Agrupa por parentId e anexa ao principal id correspondente. `D/SessionRepository.kt:20-29,67-70`.

statusSince local = servidor + floor((now-serverTime)/1000.0 + 0.5)*1000 quando serverTime>0, senão valor original. Isso estabiliza diferença de relógio ao segundo. `D/SessionRepository.kt:102-107`.

### Junção com recent e cast

`recent` é carregado por refresh antes de stream, não em cada reconnect. Sessões abertas usam fallback project/lastAt do histórico com mesma key; title igual ao characterName pode ser substituído pelo título histórico. Histórico cuja key não está aberto entra com agentId=null; procura exatamente um dono no elenco: provider omp, account do membro ausente ou igual e algum valor de last igual a sessionId. Dono preenche avatar/cast/characterName. Nenhum ou mais de um dono implica sem dono. `D/SessionRepository.kt:8-11,47-61,75-81`.

## 6. Outros campos de snapshot lidos por Settings

Isto complementa, não altera, SessionRepository.snapshot. `parseAccounts` lê accounts array e `snapshotModels` lê agents[].models.

| Campo | Semântica |
|---|---|
| accounts | object[]=[], conta sem id:S descartada |
| account.name | S default id |
| account.provider | S default claude |
| account.plan | S nullable |
| account.usageStatus | S default disabled |
| account.usage | objeto opcional |
| usage.noQuota | flag=false |
| usage.fetchedAt | S→Long nullable |
| usage.ompProviders | object[]=[], se não vazio substitui grupos de janelas fixas |
| provider.provider / provider.plan / provider.note | S nullable |
| provider.limits | object[]=[], limite sem label:S descartado |
| limit.utilization | S→Double nullable; sem clamp no parser |
| limit.resetsAt | S→Long nullable |
| limit.exhausted | flag=false |
| usage.fiveHour / sevenDay / sevenDayOpus / sevenDaySonnet | objeto opcional, cada utilization:S→Double? e resetsAt:S→Long?; label é nome fixo da janela |
| usage.note | S nullable; grupo fixo só existe com janela ou note |
| agents[].models | só strings JSON, concatena, distinct, sorted; não filtra por provider/kind apesar do comentário |

Fontes: `D/Usage.kt:21-38`; `D/Roles.kt:23-27`; consumidor: `K/SettingsScreen.kt:76-90`. Nenhum outro campo de accounts/meta/reserve/ptys/rev é lido por esses parsers.

## 7. Conversa, permissões e asides: decoders completos

### Entrada de conversa Kotlin

O mesmo decoder vale para cada kind. id:S obrigatório; sem id descarta. kind:S default unknown; tipos conhecidos: user, assistant, thinking, tool, result, system. Qualquer outro vira unknown com texto vazio, mas preserva campos comuns. Para tipo conhecido, text final concatena todos os campos presentes `text:S`, `input:S`, `detail:S` nessa ordem com LF; não escolhe somente um. Isso inclui strings vazias e primitivas numéricas/booleanas aceitas pelo helper. `D/ApiModels.kt:51-60`.

Campos comuns: `title:S?=null`, `toolUseId:S?=null`, `truncated:flag=false`, `at:JsonPrimitive.longOrNull >0 senão null`. `at` usa longOrNull diretamente, não helper string; valores inválidos não causam fallback. kind=unknown também preserva title/toolUseId/truncated/at. Campos server `tool`, `inputKind`, `error`, `level` **não são lidos**. `D/ApiModels.kt:54-58`.

| Kind | Forma emitida pelo servidor | Campo de texto lido pelo Kotlin |
|---|---|---|
| user | id,at,text | text (+ input/detail se existirem no JSON) |
| assistant | id,at,text | text |
| thinking | id,at,text? | text ou vazio |
| tool | id,at,tool,title,input?,inputKind? | input; title preservado; tool/inputKind ignorados |
| result | id,at,toolUseId,text,error?,truncated? | text; toolUseId/truncated preservados, error ignorado |
| system | id,at,text,detail?,level? | text + LF + detail quando ambos presentes; level ignorado |
| desconhecido/ausente | qualquer objeto com id | kind unknown, texto vazio |

Modelo emitido: `shared/types.ts:643-658`. Não projetar union Rust mais restrita que parser Kotlin sem escolher conscientemente mudar compatibilidade.

Init limpa estado e reduced=false, usa flag truncated; append não altera truncated. Soma excessiva de bytes lança; limite de memória remove antigas e reduced=true. Qualquer exceção no processamento de dados de conversa vira readingError=oversize e cancela conversa, mesmo que fosse JSON malformado e não tamanho. `D/ApiModels.kt:63-84`; `D/ReadingController.kt:267-279`.

### Permission detail e decisão

Detalhe lido: provider:S default claude; expiresAt como jsonPrimitive.longOrNull default 0, usado diretamente com relógio local para prazo; tool:S default vazio; input:S default vazio; questions object[]=[], suggestions object[]=[]. Pergunta: index intOrNull obrigatório para exibir/enviar; question:S default vazio; multiSelect flag=false; options object[]=[]; opção index intOrNull obrigatório, label:S default vazio, description:S nullable. Suggestion index intOrNull obrigatório, rules array de contentOrNull unidos por `, ` (falha vira vazio), destination:S classificado como session/localSettings/projectSettings/userSettings ou unknown. `K/PermissionSheet.kt:79-98,131-169`.

Decisão sempre `{behavior:"allow"|"deny"|"terminal"|"answer"}`. allow opcional suggestion:int só Claude. deny opcional message:string apenas não blank (texto enviado sem trim), interrupt:true apenas selecionado e Claude. answer exige questions não vazia e envia answers array de `{question:int,options?:int[],other?:string}`; options ordenados, omitidos se vazio; other trim, omitido se vazio. Cada questão deve ter seleção ou other; índices selecionados precisam existir; single-select exige exatamente uma escolha contando other. Não-Claude proíbe suggestion e interrupt. `K/PermissionSheet.kt:31-60`.

Deadline<=now bloqueia ação. Cancelamento após decisão deixa ambiguous; falha não-4xx também; não reenviar sem reabrir. 404/409 fecha por resposta já resolvida. `K/PermissionSheet.kt:27-28,84-109`.

### Aside topic

Topic: id:S obrigatório, createdAt:JsonPrimitive.longOrNull default 0; turns object[]=[].
Turn: id:S obrigatório e status:S obrigatório; ausência descarta turno; question:S default vazio; answer:S default vazio; error:S nullable. Running apenas status queued ou running. topic atual é escolhido por topicId senão primeiro; fresh=true não tem tópico atual. `D/AsideController.kt:19-54`.

Pergunta trim, máximo 20000 caracteres, não envia se vazio/sending ou tópico atual running. Poll mantém loading false e preserva dados em erro; termina quando nenhum running, a menos que dirty indique nova consulta necessária. Erro mostra ApiException.detail ou network, 401 chama unauthorized. `D/AsideController.kt:84-99,113-141`.

## 8. Configuração do cliente HTTP JVM/Android

JVM e Android usam Ktor HttpClient(OkHttp). Configurações explícitas:

| Propriedade | Valor/effect |
|---|---|
| Ktor followRedirects | false |
| Ktor expectSuccess | false; HabblaudApi/ConversationRepository tratam status |
| OkHttp followRedirects | false |
| OkHttp followSslRedirects | false |
| OkHttp retryOnConnectionFailure | false |
| OkHttp readTimeout | 45 segundos |
| authenticator / proxyAuthenticator | NONE; não negocia autenticação implícita |
| body de qualquer request | interceptor envolve em RequestBody isOneShot=true; delega contentType/contentLength/isDuplex/writeTo |
| cookieJar | CookieJar.NO_COOKIES; somente SessionCookieStore manual |
| SSE maxReconnectionAttempts | 0; loop próprio controla reconnect |
| SSE bounded channel | instalado em todo cliente pela função installBoundedSse |
| Upload plataforma | streamedUpload, tanto JVM quanto Android |

Fontes completas: `J/data/HttpTransport.jvm.kt:9-40`; `A/data/HttpTransport.android.kt:8-39`.

Não há User-Agent customizado, HttpTimeout plugin, connectTimeout/writeTimeout/callTimeout explícitos nesses arquivos. [INFERENCE] valores herdados da versão OkHttp/Ktor não devem ser tratados como constantes do protocolo sem inspecionar dependência; em particular não inventar User-Agent Zaplaud. `J/data/HttpTransport.jvm.kt:9-38`; `A/data/HttpTransport.android.kt:8-37`.

### Upload streaming detalhado

Não há boundary multipart, disposition nem campo `file`: o corpo inteiro é um arquivo. Content-Type externo sempre application/octet-stream; MIME original vai em X-Habblaud-File-Type. Nome usa percent-encoding UTF-8 com caracteres seguros A-Z/a-z/0-9/`-_.!~*'()`, equivalente à codificação implementada, não nome cru. Content-Length vem de AttachmentBody.size nullable; tamanho desconhecido permanece null no OutgoingContent. `D/HabblaudApi.kt:98-107`; `D/Attachments.kt:10-18`.

AttachmentBody abre fonte no momento da escrita, usa buffer 8192, verifica cancelamento em cada iteração, transmite sem carregar arquivo inteiro, fecha fonte em finally. Tamanho conhecido deve estar em 1..15 MiB; desconhecido ainda limitado durante escrita. Deve terminar com >0 bytes e bater tamanho declarado. App permite até 5 anexos; envia sequencialmente antes da mensagem e lê id de cada resposta. `D/Attachments.kt:6-8,18-36`; `D/MessageController.kt:184-214`.

Servidor também transmite para arquivo de staging sem acumular corpo inteiro. Exige octet-stream, nome percent-encoded não vazio ≤2048 caracteres, MIME header ≤256; MIME inválido vira application/octet-stream. Rejeita Content-Length inválido/zero, >15 MiB com 413; reserva espaço (15 MiB para desconhecido), depósito máximo 120 MiB retorna 429; valida tamanho final. Responde 201 `{id,name,size}`, cliente lê só id. Anexos pendentes expiram após 30 minutos e têm consumidor messages separado de spawn. `server/spawn/attachments.ts:12-16,55-117`; `server/messages/http.ts:48-51`.

## 9. Limites da evidência

Documento resulta de leitura de implementação e testes de contrato citados, sem executar testes, servidor ou chamadas reais. Nenhum segredo foi lido ou reproduzido. Comportamento interno não visível de Ktor/OkHttp está marcado [INFERENCE]; todos os defaults próprios do Kotlin acima foram lidos diretamente. O contrato não exige consumir feed/notice nem criar paginação inexistente.
