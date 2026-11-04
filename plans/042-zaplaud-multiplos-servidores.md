# Plano 042: Zaplaud conectado a vários servidores Habblaud ao mesmo tempo, com lista única e filtro

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> do passo seguinte. Se acontecer qualquer item de "Condições de STOP", pare e relate; não improvise. Ao terminar,
> atualize a linha 042 em `plans/README.md`, salvo se um revisor disser que ele cuida do índice.
>
> **Drift check (rode primeiro)**:
> `git diff --stat c468bc1..HEAD -- android/shared/src android/app/src CHANGELOG.md`
> Se algum arquivo do escopo mudou desde `c468bc1`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de STOP.

## Status

- **Prioridade**: P2
- **Esforço**: L
- **Risco**: HIGH (migração de dados locais: rascunhos não enviados e cookie de sessão)
- **Depende de**: nenhum plano. O cliente Android (034) e o port iPad (041) já estão em `producao`.
- **Categoria**: direction (feature)
- **Planejado em**: commit `c468bc1`, 2026-11-04

## Por que importa

O Zaplaud (app Kotlin Multiplatform em `android/`, com host Android e host iPad) só guarda **um** servidor. Entrar em
outro endereço apaga os dados locais do anterior, incluindo rascunhos não enviados (`change_warning`). O dono usa mais
de um Habblaud (instalação pessoal e tenants) e quer acompanhar todos ao mesmo tempo: uma lista única com as sessões
de todos os servidores, um filtro opcional por servidor e notificações Android de todos. Cada servidor mantém o próprio
login; sair de um não pode afetar os outros.

Decisões do dono (2026-11-04), fixas para este plano:

1. **Tela principal = lista única misturada com filtro**: as sessões de todos os servidores na mesma lista, ordenadas
   pela resposta mais nova (`byLastMessage`), e acima dela uma fileira de filtros "Todos" + um por servidor.
2. **Um login por servidor**: a identidade de um servidor é a origem canônica (`UrlPolicy.origin`). Dois usuários
   no mesmo endereço ao mesmo tempo **não** são suportados.

Decisões do advisor, para não perguntar de novo:

- Rótulo do servidor = host da origem (`Url(origin).host`, mais `:porta` se não for a padrão). Apelidos: fora do escopo.
- Configurações globais (tema, visualização padrão, modo de envio padrão, resumos, notificações) continuam globais.
  Escolhas por sessão já usam `SessionKey`, que contém a origem: nada muda nelas além do nome da chave (passo 2).
- Sem limite de quantidade de servidores. Cada servidor abre um `/api/stream` (SSE) e, se houver conversa aberta
  nele, mais um stream.
- iPad: só em primeiro plano, sem notificações (decisão do plano 041). Nada muda nisso.

## Estado atual

Raiz Kotlin: `android/shared/src/` (código comum em `commonMain`, plataformas em `androidMain` e `iosMain`). Pacote
`br.com.xlinx.habblaud` (UI) e `br.com.xlinx.habblaud.data` (dados). Host Android: `android/app/src/main/java/...`.

### Já pronto para vários servidores (não mexa na lógica)

- `SessionKey` já contém a origem — `commonMain/.../data/ApiModels.kt:18`:
  ```kotlin
  data class SessionKey(val origin: String, val provider: String, val account: String, val sessionId: String) {
  ```
- `HabblaudApi(origin)` é por origem; `SessionRepository`, `ConversationRepository`, `MessageController`,
  `AvatarCache` recebem a `api` e usam `api.origin`. `ReadingController.openKey` já ignora chaves de outra origem
  (`ReadingController.kt:195`). Id de notificação já é o hash de `key.serialize()` (`NotificationService.kt:114`).

### O que impede vários servidores

1. **Cookie da sessão remota guarda uma só origem e limpa todas.** `commonMain/.../data/SessionCookieStore.kt`:
   - armazenamento seguro com uma vaga só: `SecureSessionStorage` (`read/write/clear` de **uma** string). Android:
     arquivo cifrado `filesDir/remote-session` com chave `habblaud-remote-session` no AndroidKeyStore
     (`androidMain/.../data/SessionCookieStore.kt`). iOS: um item do Keychain, serviço
     `br.com.xlinx.habblaud.remote-session`, conta `habblaud_remote` (`iosMain/.../data/SessionCookieStore.kt`).
   - formato gravado (linha 54): `{"origin": "<origem>", "cookie": "<Set-Cookie renderizado>"}`.
   - ao criar o store de outra origem, o valor persistido não bate e vira `null` (linha 34):
     ```kotlin
     if (data["origin"]?.jsonPrimitive?.content != origin) null else parseServerSetCookieHeader(...)
     ```
     e `saveFromResponse` de B **sobrescreve** o arquivo de A.
   - `clear()` (linha 74) zera os cookies em memória de **todas** as origens e o arquivo:
     ```kotlin
     try { invalidation++; SessionMemory.cookies.keys.toList().forEach { SessionMemory.cookies[it] = null }; storage.clear() } finally { lock.unlock() }
     ```
     `clear()` é chamado em 401 (`HabblaudApi.unauthorized` → `cookies.clear(generation)`, e
     `ConversationRepository` construtor secundário: `{ api.cancelCalls(); api.cookies.clear() }`) e no logout
     (`HabblaudApi.logout`). Com dois servidores, um 401 em A deslogaria B.
   - a geração de invalidação `SessionMemory.generation` é global (linhas 11 e 19–20).
2. **Estado local com uma origem só.** `LocalStateStore` (`expect` em `commonMain/.../data/LocalStateStore.kt:54-92`,
   `actual` Android em `androidMain/.../data/LocalStateStore.android.kt`, `actual` iOS em
   `iosMain/.../data/LocalStateStore.kt`):
   - `connection(): ConnectionSettings?` e `setConnection(origin)`; trocar a origem **apaga tudo** de `local_state`
     (Android, linhas 158–175):
     ```kotlin
     val changed = previous[originKey] != origin
     ...
     if (changed) { data.clear(); data[cacheGenerationKey] = generation!! }
     data[originKey] = origin
     ```
     iOS faz o mesmo em `invalidate(data, origin)` (linhas 116–122).
   - gravações conferem a origem única: `check(data[originKey] == key.origin) { "Connection changed" }`
     (Android 72; iOS 91, 137, 139) e `if (data[originKey] != key.origin) return@withLock null` (Android 106, 120;
     iOS 103, 109).
   - chaves por sessão = prefixo + SHA-256 de `key.serialize()` (`message_`, `view_`, `deliver_`; função `hashed`,
     Android linha 35; `hash`, iOS linha 86). O hash **não** revela a origem: hoje não há como apagar só os dados de
     um servidor.
   - `seen_replies` é **um** mapa `seenId → hora do servidor` para todas as sessões (Android 216–225).
   - cache de conversas: `filesDir/conversations/<geração>/<hash>.json` (Android 93–103); iOS
     `conversation-<geração>-<hash>.json` (linha 102). A época (`coordinator.epoch` / `StateDisk.epoch`) é **global**;
     `ReadingController` guarda a época na criação (`private val conversationEpoch = store.conversationEpoch()`,
     linha 67) e descarta gravações de época antiga. Se remover um servidor subir a época global, as gravações dos
     outros servidores seriam descartadas para sempre.
   - `clear()` apaga tudo (logout).
3. **UI com uma `api` só.** `commonMain/.../ZaplaudApp.kt`: estados `saved: ConnectionSettings?`,
   `api: HabblaudApi?`, `connected`; diálogo "Trocar servidor?" (`confirmChange`, strings `change_server`,
   `change_warning`); logout (linhas 154–164) chama `api.logout()`, `store.clear()` e desliga notificações.
   `ReadingScreen(api, store, onUnauthorized, onLogout)` (`ReadingScreen.kt:62`) cria **um**
   `ReadingController(api, ...)` (linha 65). `SettingsScreen(api, store, ...)` mostra a origem, cotas
   (`/api/snapshot`), papéis (`/api/cast/roles`) e "Sair" de um servidor (linhas 196–202). `CastActions(api, selected, ...)`
   (FAB "chamar personagem") usa a `api` recebida.
4. **Notificações Android de um servidor só.** `app/.../NotificationService.kt:51-77` lê `store.connection()?.origin`,
   abre um `HabblaudApi` e um stream; em 401 chama `stopSelf()`.

### Convenções do repositório

- Texto visível só por recurso Compose: `stringResource(Res.string.x)` com o texto em PT em
  `android/shared/src/commonMain/composeResources/values/strings.xml` (só existe `values/`, sem outras línguas).
  O Android nativo (canais e notificações) usa `android/app/src/main/res/values/strings.xml`.
- Comentários e mensagens de commit em português, como no código existente. Commits no estilo
  `feat(android): ...`, `fix(ios): ...` (ver `git log --oneline -10`).
- Gravações locais concorrentes usam o par época/sequência (`ConversationWrite`) e `withContext(NonCancellable)` nas
  transições; mantenha esse padrão.
- Toda mudança visível entra em `## [Não lançado]` no `CHANGELOG.md` (linha 11), como as entradas
  "**Android:** ..." já existentes.

## Comandos

Rode de dentro de `android/` (ou `android/gradlew -p android ...` da raiz).

| Propósito | Comando | Esperado |
|---|---|---|
| Host + unit + lint + APK (igual ao CI) | `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados `shared` (emulador ligado) | `./gradlew :shared:connectedAndroidDeviceTest` | `BUILD SUCCESSFUL` |
| Instrumentados `app` com fixture | ver `README.pt-BR.md` seção de testes Android (fixture `scripts/android-smoke.ts`) e `.github/workflows/android.yml` | `BUILD SUCCESSFUL` |
| iOS (só no Mac) | `./gradlew :shared:allTests` | `BUILD SUCCESSFUL`, `iosSimulatorArm64Test` executado (não SKIPPED) |

Sem Mac disponível: registre que o gate iOS não rodou; não marque DONE sem ele (ver Critérios).

## Escopo

**Dentro** (únicos arquivos a modificar):
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt`
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt` (só se o passo 1 exigir)
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt` (só se o passo 1 exigir)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt`
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.android.kt`
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ConversationRepository.kt` (só o construtor secundário)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Unread.kt` (função pura de mescla, se preferir lá)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`
- `android/shared/src/commonMain/composeResources/values/strings.xml`
- `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`
- testes em `android/shared/src/commonTest/`, `androidHostTest/`, `androidDeviceTest/`, `iosTest/` e
  `android/app/src/androidTest/` que usem `connection()`, `setConnection`, `clear()` ou o cookie (lista no passo 6)
- `CHANGELOG.md`, `plans/README.md`

**Fora** (não toque):
- Servidor Node (`server/`, `shared/`, `client/`): nada muda na API. O cookie `habblaud_remote` já é por origem.
- `HabblaudApi.kt`, `MessageController.kt`, `SessionRepository.kt`, `CastActions.kt`, `PermissionSheet.kt`,
  `AvatarCache.kt`: já são por `api`. Só passe a `api` certa a eles.
- `ios/` (projeto Xcode) e `MainViewController.kt`: o iPad recebe a mudança pelo código comum.
- Apelidos de servidor, "sair de todos", sincronizar configurações entre servidores: não pedidos.

## Git

- Branch `advisor/042-zaplaud-multiplos-servidores`, numa worktree `../habblaud-042` a partir de `producao`.
  Nunca em `~/apps/habblaud`.
- Um commit por passo, mensagem no estilo `feat(android): ...`.
- Não faça push nem PR sem instrução do operador.

## Passos

### Passo 0: linha de base

Rode o comando "Host + unit + lint + APK" e, com emulador, `:shared:connectedAndroidDeviceTest`. Anote os números.

**Verificar**: `BUILD SUCCESSFUL` nos dois. Se a linha de base já falha, STOP e relate (não corrija).

### Passo 1: cookie por origem, sem uma origem apagar a outra

Em `commonMain/.../data/SessionCookieStore.kt`:

1. Formato novo da string guardada em `SecureSessionStorage`: `{"v":2,"cookies":{"<origem>":"<Set-Cookie>", ...}}`.
   Na leitura, aceite também o formato antigo `{"origin":..,"cookie":..}` como um mapa de uma entrada (migração;
   a próxima gravação já sai no formato novo). Coloque codificar/decodificar em duas funções `internal` puras
   (`encodeCookies(Map<String,String>): String`, `decodeCookies(String): Map<String,String>`); valor ilegível =
   mapa vazio, como hoje (`runCatching`).
2. `saveFromResponse`: leia o mapa, troque só a entrada de `origin`, grave o mapa inteiro. Mantenha o resto
   (checagem de domínio, expiração, `IllegalStateException` em falha de gravação). Na falha de gravação, apague
   **só** a entrada desta origem em memória; não chame `storage.clear()` (apagaria os outros servidores) — regrave o
   mapa sem esta origem e, se nem isso der, aí sim `storage.clear()`.
3. `init`: carregue a entrada de `origin` do mapa.
4. Geração de invalidação por origem: troque `SessionMemory.generation: Long` por `mutableMapOf<String, Long>()` e
   use `generations[origin] ?: 0` em `generation()`, `saveFromResponse(..., expectedGeneration)` e
   `clear(expectedGeneration)`.
5. `clear()` passa a limpar **só** `origin`: incrementa a geração dela, zera `SessionMemory.cookies[origin]`, remove a
   entrada do mapa e grava; se o mapa ficar vazio, `storage.clear()`.
6. Mantenha o comentário de classe atualizado: "Guarda o cookie da sessão remota de cada origem conectada."

Não mude `SecureSessionStorage` (a vaga continua uma; o conteúdo é que vira mapa). Assim o arquivo `remote-session`, a
chave do AndroidKeyStore e o item do Keychain continuam os mesmos e os testes que leem `remote-session` seguem
válidos.

**Verificar**: `./gradlew :shared:testAndroidHostTest` → `BUILD SUCCESSFUL`, com os testes novos do passo 6 (item
"cookie") passando.

### Passo 2: estado local com lista de servidores

Na interface `expect` (`commonMain/.../data/LocalStateStore.kt`) troque:

| Hoje | Novo |
|---|---|
| `suspend fun connection(): ConnectionSettings?` | `suspend fun servers(): List<String>` (origens, na ordem em que foram adicionadas) |
| `suspend fun setConnection(origin: String)` | `suspend fun addServer(origin: String)` (no-op se já existe; **não** apaga nada) |
| — | `suspend fun removeServer(origin: String)` (apaga só os dados desta origem) |
| `suspend fun clear()` | mantém: apaga tudo (todos os servidores). Usado pelos testes. |
| `internal fun conversationEpoch(): Long` | `internal fun conversationEpoch(origin: String): Long` |
| `internal fun conversationWrite(epoch: Long)` | `internal fun conversationWrite(origin: String, epoch: Long)` |
| `internal suspend fun setSeenReplies(seen, epoch)` | `internal suspend fun setSeenReplies(origin: String, seen, epoch)` |
| `suspend fun seenReplies()` / `setSeenReplies(seen)` | ganham o parâmetro `origin: String` |

Apague `data class ConnectionSettings` (sem uso depois do passo 4). `ConversationWrite` ganha `val origin: String`.

Implemente igual no Android e no iOS:

1. **Lista**: chave `servers` = array JSON de origens (Android: `stringPreferencesKey("servers")` em `local_state`;
   iOS: `data["servers"]` no `StateDisk`).
2. **Marca da origem nas chaves por sessão**: `tag(origin)` = 16 primeiros hex do SHA-256 da origem. Chaves novas:
   `message_<tag>_<hash>`, `view_<tag>_<hash>`, `deliver_<tag>_<hash>`, `seen_replies_<tag>`. `seenId(key)` continua
   `hash(key)` (só muda onde o mapa fica). Cache de conversas: Android
   `conversations/<geração>/<tag>/<hash>.json`; iOS `conversation-<geração>-<tag>-<hash>.json`.
3. **Checagem de origem**: troque toda comparação `data[originKey] == key.origin` / `it.string("origin") == key.origin`
   por "`key.origin` está em `servers`". Mantenha a mensagem `"Connection changed"` no `check`.
4. **Época por origem**: troque `epoch: Long` do coordenador/`StateDisk` por `epochs: MutableMap<String, Long>`;
   `written` passa a ser por `SessionKey` como já é (a chave já tem origem). `sessionWritten` (última sessão) fica
   global, porque `last_session` é uma só para o app. `removeServer(origin)` sobe só `epochs[origin]`; `clear()` sobe
   todas.
5. **`removeServer(origin)`** (com `NonCancellable` e o lock do coordenador, como `clear()` hoje): tira a origem de
   `servers`; remove de `local_state` todas as chaves que começam com `message_<tag>_`, `view_<tag>_`,
   `deliver_<tag>_` e `seen_replies_<tag>`; remove `last_session` se a origem dela for esta; apaga a pasta/arquivos do
   cache desta `tag`; sobe a época da origem e limpa `written` das chaves desta origem.
6. **Migração do formato antigo** (uma vez, dentro do lock, na primeira chamada de qualquer método que leia
   `servers`): se existe a chave antiga `origin` e não existe `servers`: grave `servers = [origin]`; renomeie cada
   `message_<hash>`, `view_<hash>`, `deliver_<hash>` para o nome com `tag(origin)`; mova `seen_replies` para
   `seen_replies_<tag>`; apague a chave `origin`. O cache de conversas antigo pode ser apagado (é cache; o servidor
   manda de novo). **Rascunhos (`message_`) não podem ser perdidos**: é o motivo do risco HIGH.
7. Configurações globais em `appSettings`/`NSUserDefaults` (tema, view, deliver_as, resumos, notificações,
   notify_*) e `login_rate` não mudam.
8. Remova `migrateLegacySettings` do `clear()`/`addServer` **só se** continuar sem efeito; na dúvida mantenha.

**Verificar**: `./gradlew :shared:compileAndroidMain` falha **apenas** nos chamadores de `connection()`,
`setConnection` e das funções de época (serão ajustados nos passos 3–6). Liste os erros e confira que só citam
`ZaplaudApp.kt`, `ReadingController.kt`, `NotificationService.kt` e testes.

### Passo 3: `ReadingController` com a época da própria origem

Em `ReadingController.kt`: `store.conversationEpoch()` → `store.conversationEpoch(api.origin)`;
`store.conversationWrite(conversationEpoch)` → `store.conversationWrite(api.origin, conversationEpoch)`;
`store.seenReplies()` → `store.seenReplies(api.origin)`; `setSeenReplies(marks, conversationEpoch)` →
`setSeenReplies(api.origin, marks, conversationEpoch)`. Nada mais muda: o controlador continua sendo de **um**
servidor; a tela cria um por servidor (passo 4).

Em `ConversationRepository.kt`, o construtor secundário já chama `api.cookies.clear()`; depois do passo 1 isso limpa
só esta origem. Não mude.

**Verificar**: `./gradlew :shared:compileAndroidMain` sem erros em `ReadingController.kt`.

### Passo 4: UI — servidores, lista única e filtro

**`ZaplaudApp.kt`**:
1. Estado: `servers: List<String>` (de `store.servers()`), `apis: Map<String, HabblaudApi>` (um por origem) e
   `needsLogin: Set<String>` (origens com 401 ou sem cookie). Ao iniciar, para cada origem: cria `HabblaudApi`,
   chama `access()`; sucesso = conectado; 401 = entra em `needsLogin`; erro de rede = conectado mesmo assim (o
   `ReadingController` reconecta sozinho, como hoje).
2. Formulário de login (URL, usuário, senha, botão, mensagens de erro, aviso de HTTP `http-confirm`, limite 429) vira
   um `@Composable private fun LoginForm(initialUrl: String, onDone: (origin, api) -> Unit, onCancel: (() -> Unit)?)`
   com a mesma lógica de `connect()`/`requestLogin()` de hoje, **menos** a troca de servidor: login bem-sucedido chama
   `store.addServer(origin)` e acrescenta a `api`. Mantenha os `testTag` (`server-url`, `username`, `password`,
   `login-submit`, `http-confirm`). Remova `confirmChange` e o diálogo "Trocar servidor?".
3. Tela: sem servidores → `LoginForm` em tela cheia (como hoje). Com servidores → `ReadingScreen(apis, store, ...)`.
   Formulário aberto por "Adicionar servidor" ou "Entrar de novo" (passo 5) → `LoginForm` com voltar.
4. Sair de **um** servidor (`onLogout(origin)`): `api.logout()`, `store.removeServer(origin)`, `api.close()`,
   tira dos mapas. Se não sobrou servidor, desliga notificações (`notificationsPlatform.setEnabled(false)`, como
   hoje). Se sobrou, reinicia o serviço (`setEnabled(true)` quando `store.notificationsEnabled()`) para ele largar a
   origem removida. Mensagens de erro com `logoutError(cleaned, revoked)` como hoje.
5. `onUnauthorized(origin)`: põe a origem em `needsLogin`; os outros servidores seguem.

**`ReadingScreen.kt`**:
1. Assinatura: `ReadingScreen(apis: Map<String, HabblaudApi>, needsLogin: Set<String>, store, onUnauthorized: (String) -> Unit, onLogin: (String?) -> Unit, onLogout: (String) -> Unit)`.
2. Um `ReadingController` por origem, lembrado por origem (`remember(origin)` dentro de `key(origin)` ou um mapa em
   `remember`), cada um com `start()/stop()` ligado ao ciclo de vida exatamente como o bloco atual (linhas 68–76).
3. Estado combinado — escreva funções **puras** em `commonMain/.../data/Unread.kt` (ou arquivo novo
   `Servers.kt` no mesmo pacote) e teste-as no passo 6:
   - `mergeSessions(states: Map<String, ReadingState>, filter: String?): List<SessionItem>` = concatena as listas
     (filtradas pela origem quando `filter != null`); a ordenação continua no `byLastMessage` da tela.
   - `unread` combinado = união dos mapas (as chaves já são distintas por origem).
   - `serverLabel(origin): String` = host (+ `:porta` não padrão).
4. Sessão aberta: no máximo uma em todo o app. `open(item)` chama `controllers[item.key.origin].open(item)` e
   `close()` nos controladores que tenham `selected != null`. A tela usa o `state` do controlador da sessão aberta
   para conversa, compositor, erros de leitura e permissões. Todos os usos de `api` dentro da conversa
   (`CharacterAvatar`, `PermissionSheet`, `SessionInfoSheet`, `WorkingIndicator`, `SubagentBranch`, `CastActions`)
   recebem `apis.getValue(selected.key.origin)`. Na lista, cada linha usa `apis.getValue(session.key.origin)`.
5. Filtro: com 2+ servidores, uma fileira de `FilterChip` acima das abas ("Todos" + `serverLabel` de cada um), com a
   bolinha de estado (verde/laranja/vermelho, mesmas cores da linha 180) de cada servidor no chip. O filtro fica em
   `rememberSaveable` (não precisa sobreviver ao fechamento do app). Com 1 servidor, nada de chips: a tela fica igual
   à de hoje.
6. Rótulo na linha: com 2+ servidores e filtro "Todos", acrescente `serverLabel` à linha secundária da sessão
   (a que junta personagem, projeto e status, linha ~250). Use `testTag("session-server:<host>")` no texto.
7. Indicador de conexão do topo (sem sessão aberta): verde se todos conectados, laranja se algum conectando, vermelho
   se algum offline. Erros de lista (`state.error`) de cada servidor aparecem prefixados por `serverLabel` quando
   houver 2+ servidores.
8. Servidor em `needsLogin`: chip com texto "Entrar de novo" (ou uma faixa no topo da lista com o host e o botão)
   que chama `onLogin(origin)`. Os outros servidores seguem na lista.
9. FAB de chamar personagem (`CastActions` sem sessão aberta): mostre só quando o escopo tem **um** servidor (só um
   conectado, ou filtro num servidor); use a `api` dele. Com filtro "Todos" e 2+ servidores, sem FAB.
10. Notificação tocada (`pendingSession`): chame `openKey` no controlador da origem da chave; se a origem não estiver
    conectada, descarte.
11. Menu ⋮: "Sair" sai desta lista; o logout por servidor fica nas Configurações (passo 5). Acrescente
    "Adicionar servidor" (`onLogin(null)`).

**Verificar**: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

### Passo 5: Configurações por servidor

`SettingsScreen(apis, store, onClose, onLogin, onLogout)`:
- Seção "Servidores" (troque `settings_server` "Servidor" por "Servidores"): uma linha por origem com o host e "Sair"
  (`onLogout(origin)`, com o diálogo de confirmação atual, texto novo da linha abaixo), e "Adicionar servidor" no fim.
- Cotas e papéis: com 2+ servidores, uma fileira de chips escolhe o servidor (padrão: o primeiro); os blocos atuais
  de cotas e papéis usam `apis.getValue(escolhido)` e reiniciam os `LaunchedEffect` ao trocar (chave do efeito =
  origem). Com 1 servidor, sem chips.

Strings em `composeResources/values/strings.xml`:
- remova `change_server` e `change_warning` (sem uso);
- acrescente `logout_server_warning` = "Sair apaga deste aparelho os dados de %1$s, incluindo rascunhos não
  enviados. Os outros servidores continuam conectados.", `add_server` = "Adicionar servidor", `relogin` = "Entrar de
  novo", `filter_all` = "Todos";
- `settings_server` = "Servidores".
Confira com `grep -rn 'change_warning\|change_server' android/shared/src` que não sobrou uso.

**Verificar**: `./gradlew :app:assembleDebug :app:lintDebug` → `BUILD SUCCESSFUL`.

### Passo 6: notificações Android de todos os servidores

`NotificationService.watch()`:
- Leia `store.servers()`; sem servidores ou notificações desligadas → `stopSelf()` (como hoje).
- Para cada origem válida (`UrlPolicy.origin(it) == it`), lance uma coroutine filha no `scope` com o laço atual
  (stream, comparação `agentEvents`, `delay(30_000)`) e um `HabblaudApi` próprio; guarde as `api` numa lista para
  `onDestroy` fechar todas.
- 401 numa origem encerra **só** aquela coroutine. Quando todas terminaram por 401, `stopSelf()`.
- Com 2+ servidores, `alertNotification` acrescenta o host ao `setSubText` (`"projeto · host"`). A versão pública da
  tela bloqueada (`publicVersion`) não muda: nada do servidor aparece com a tela bloqueada (plano 039).

**Verificar**: `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`.

### Passo 7: testes

Ajuste os testes existentes que usam a API antiga e escreva os novos. Arquivos que hoje chamam
`connection()`/`setConnection`/`clear()`/`ConnectionSettings` (confirme com
`grep -rln 'setConnection\|connection()\|ConnectionSettings' android/shared/src android/app/src`):
`androidDeviceTest/.../ConversationEpochInstrumentedTest.kt`, `LastSessionOrderInstrumentedTest.kt`,
`LocalStateStoreTest.kt`, `PersistenceInstrumentedTest.kt`, `iosTest/.../data/NativeStorageTest.kt`,
`app/src/androidTest/.../HttpLoginInstrumentedTest.kt`, `FixtureUiInstrumentedTest.kt`.

Regras:
- `setConnection(x)` em preparação de teste → `addServer(x)`; `connection()?.origin` → `servers().singleOrNull()`.
- Testes que provam "trocar de origem invalida a gravação antiga" (`originChangeRejectsOldWrite`, o caso `"origin"` em
  `LastSessionOrderInstrumentedTest`, `PersistenceInstrumentedTest` linha ~115, `reopened.setConnection(other)`)
  pinam o comportamento antigo: reescreva para o novo — `removeServer(origin)` rejeita a gravação antiga **desta**
  origem, e `addServer(other)` **não** rejeita gravações de `origin`.
- `PersistenceInstrumentedTest` linha 58 (`api.cookies.clear()` + `assertFalse(file.exists())`) continua valendo com
  um servidor só.

Testes novos (mínimos, cada um pega um defeito plausível):
1. **commonTest** `ServersTest.kt`: `decodeCookies` lê o formato antigo e o novo; `encodeCookies`/`decodeCookies`
   ida e volta com 2 origens; `mergeSessions` com filtro e sem; `serverLabel` com porta padrão e não padrão.
2. **androidDeviceTest** (no `PersistenceInstrumentedTest` ou `SessionCookieStoreTest`): cookie de A e B gravados;
   `SessionCookieStore(A).clear()` não apaga B (em memória e após recriar o store); 401 em A
   (`clear(generation)`) não muda a geração de B.
3. **androidDeviceTest** `LocalStateStoreTest`: dois servidores; rascunho em cada um; `removeServer(A)` mantém o
   rascunho, a view e o `seen_replies` de B e apaga os de A; `last_session` de B sobrevive.
4. **androidDeviceTest** migração: grave à mão o formato antigo (`origin` + `message_<hash>` + `seen_replies` em
   `local_state`) e confira que, depois de `servers()`, o rascunho é lido por `message(key)` e `servers()` = `[origin]`.
   Faça o mesmo para o cookie antigo em `remote-session` (escreva via `SecureSessionStorage().write` o JSON antigo).
5. **iosTest** `NativeStorageTest`: os casos 2 e 3 no Keychain/arquivo do iOS.
6. **app androidTest** `FixtureUiInstrumentedTest`: com dois servidores da fixture (duas portas de
   `scripts/android-smoke.ts`, ou a mesma fixture por `127.0.0.1` e `localhost`, que são origens diferentes), a lista
   mostra sessões das duas, os chips aparecem e o filtro esconde as do outro servidor. Se a fixture não permitir duas
   origens sem mudar `scripts/`, STOP e relate (o script está fora do escopo).

**Verificar**: comando "Host + unit + lint + APK" e `:shared:connectedAndroidDeviceTest` → `BUILD SUCCESSFUL`;
instrumentados `app` com fixture → `BUILD SUCCESSFUL`.

### Passo 8: prova manual e changelog

1. Instale o APK debug num emulador. Entre em dois servidores (fixture em duas origens, ou o servidor real + a
   fixture). Confira: lista com sessões dos dois e o host em cada linha; chips filtram; abrir sessão de A e depois de
   B troca a conversa; rascunho escrito em A sobrevive a sair de B; sair de B não pede login em A; com o app em
   segundo plano, um pedido de aprovação em cada servidor gera uma notificação cada.
2. Atualização a partir da versão atual: instale o APK de `producao` (antes do branch), entre num servidor, escreva um
   rascunho sem enviar; instale o APK do branch **por cima** (`adb install -r`). O app abre já logado no mesmo
   servidor e o rascunho está lá.
3. `CHANGELOG.md`, em `## [Não lançado]`: entrada "**Zaplaud:** conecta a vários servidores Habblaud ao mesmo tempo:
   uma lista com as sessões de todos, filtro por servidor, login e saída por servidor e notificações Android de todos.
   Atualizar mantém o login e os rascunhos do servidor atual."

**Verificar**: os itens 1 e 2 observados e registrados no relatório do executor (o que foi feito e o que apareceu).

## Critérios de pronto

- [ ] `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `./gradlew :shared:connectedAndroidDeviceTest` → `BUILD SUCCESSFUL`, com os testes novos 2, 3 e 4
- [ ] Instrumentados `app` com fixture → `BUILD SUCCESSFUL`, com o teste novo 6
- [ ] `./gradlew :shared:allTests` no Mac → `iosSimulatorArm64Test` executado e verde (ou o item fica pendente no
      status: `DONE` exige este gate)
- [ ] `grep -rn 'setConnection\|ConnectionSettings\|change_warning\|change_server' android/` → nada
- [ ] `grep -n 'SessionMemory.cookies.keys' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt` → nada
- [ ] Prova manual do passo 8 (itens 1 e 2) registrada
- [ ] `git status` sem arquivos fora do escopo; `CHANGELOG.md` e `plans/README.md` atualizados

## Condições de STOP

- O código em "Estado atual" não bate com o vivo (drift).
- A migração do passo 2 perde um rascunho em qualquer teste ou na prova do passo 8 item 2. Não "simplifique"
  apagando os dados antigos.
- Fazer o passo 1 exige mudar a vaga única de `SecureSessionStorage` (por exemplo, limite de tamanho do item do
  Keychain): relate antes de criar várias vagas.
- A fixture de testes não aceita duas origens sem mudar `scripts/android-smoke.ts`.
- Algum caminho exige mudar o servidor (`server/`) ou a API HTTP.
- Uma verificação falha duas vezes depois de uma tentativa razoável de correção.
- Com 2+ servidores ligados, o Android mostra aviso de limite de serviço em primeiro plano ou o serviço é morto: relate
  com o logcat resumido (sem cookies, URLs completas de sessão ou conteúdo de notificação).

## Notas de manutenção

- Toda chave nova por sessão em `LocalStateStore` precisa da `tag` da origem, senão `removeServer` não a apaga e os
  dados de um servidor removido ficam no aparelho.
- `SessionCookieStore.clear()` agora é por origem. Quem precisar apagar tudo usa `LocalStateStore.clear()` mais um
  `clear()` por origem.
- Revisor: conferir (a) que nenhum `clear()`/`storage.clear()` restante apaga outra origem; (b) que a época é por
  origem em todos os pontos de gravação (`setConversation`, `setSeenReplies`, `setLastSession`); (c) que a tela com
  um servidor só ficou visualmente igual à de antes (sem chips, sem host nas linhas).
- Adiado: apelidos de servidor, ordem manual dos servidores, "sair de todos", vários usuários no mesmo servidor
  (decisão do dono: um login por servidor).
