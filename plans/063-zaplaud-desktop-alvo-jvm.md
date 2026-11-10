# Plano 063: Zaplaud abre numa janela no Linux e no macOS (alvo JVM do Compose Multiplatform)

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação antes do próximo. Em condição de
> PARADA, pare e relate; não improvise. Ao terminar, atualize a linha 063 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 694938f..HEAD -- android/shared/build.gradle.kts android/settings.gradle.kts android/gradle/libs.versions.toml android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data android/shared/src/iosMain android/shared/src/androidMain`.
> Se algum arquivo mudou, compare os trechos de "Estado atual" com o código vivo; divergência é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: L
- **Risco**: MED (mexe no build do módulo `shared`, usado por Android e iOS)
- **Depende de**: nenhum
- **Categoria**: direction (novo cliente desktop)
- **Planejado em**: commit `694938f`, 2026-11-09

## Por que importa

O dono quer o Zaplaud no Linux e no macOS como extensão do app do celular, com as mesmas funções e o mesmo visual.
O Zaplaud já é Kotlin Multiplatform + Compose Multiplatform: 4.118 linhas em `commonMain` (telas, controllers, API,
regras de notificação, tema) contra 540 linhas de código Android e 577 de iOS. Acrescentar um alvo JVM ao módulo
`shared` e um host de janela reaproveita tudo isso; cada recurso novo do celular chega ao desktop sem reescrita. Este
plano entrega o app numa janela, com login, lista, conversa, envio, anexos, aprovações, interrupção, elenco,
bate-papo paralelo e configurações. Duas colunas (estilo Telegram) são o plano 064; notificações, o 065; pacotes, o 066.

Medição de referência (protótipo equivalente, `/tmp/zaplaud-desktop-spike/REPORT.md`, 2026-11-09): Compose Desktop
1.12.2 abre a janela em ~0,9 s, usa ~210–250 MiB de RSS parado e gera 140 MiB com o runtime Java embutido. O dono
aceitou esse custo por ora; o plano 067 mede o app real e decide se vale um cliente Rust.

## Estado atual

- `android/settings.gradle.kts` — inclui só `:app` e `:shared`:
  ```kotlin
  rootProject.name = "Habblaud"
  include(":app", ":shared")
  ```
- `android/shared/build.gradle.kts` — alvos `android { ... }`, `iosArm64()`, `iosSimulatorArm64()`; dependências de
  `commonMain`: `compose.runtime`, `compose.foundation`, `compose.material3`, `compose.materialIconsExtended`,
  `compose.components.resources`, `libs.markdown.m3`, `kotlinx-coroutines-core:1.11.0`, `libs.serialization.json`,
  `api(libs.ktor.client.core)`, `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4`.
  `androidMain` usa `libs.ktor.client.okhttp` e `libs.okhttp`; `iosMain` usa `libs.ktor.client.darwin`.
- Versões em `android/gradle/libs.versions.toml`: Kotlin `2.4.21`, Compose Multiplatform `1.12.2`, Ktor `3.6.1`,
  OkHttp `5.5.1`, markdown `0.38.0`. Gradle `9.3.1` (`android/gradle/wrapper/gradle-wrapper.properties`).
- Artefatos JVM conferidos no Maven em 2026-11-09: `com.mikepenz:multiplatform-markdown-renderer-m3-jvm:0.38.0`
  e `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-desktop:2.9.4` existem.
- Pontos `expect` que o alvo JVM precisa implementar (todos com `actual` em `androidMain` e `iosMain`):

  | `expect` (arquivo em `commonMain`) | Android (`androidMain`) | iOS (`iosMain`) |
  |---|---|---|
  | `UiPlatform.kt:22` `PlatformBackHandler` | `UiPlatform.android.kt:19` (`BackHandler`) | `MainViewController.kt:36` (vazio) |
  | `UiPlatform.kt:23` `rememberAttachmentPicker` | `UiPlatform.android.kt:20-24` | `MainViewController.kt:47-76` |
  | `UiPlatform.kt:24` `animationsEnabled` | `UiPlatform.android.kt:25-36` | `MainViewController.kt:37-46` |
  | `UiPlatform.kt:25-28` `uiNowMillis`, `platformEntryTime`, `platformDeadline`, `retryAfterDeadline` | `UiPlatform.android.kt:37-48` (só `java.time`/`java.text`) | `MainViewController.kt:77-100` |
  | `data/HabblaudApi.kt:24,148` `httpClient()` e `platformUpload` | `data/HttpTransport.android.kt` (OkHttp, 39 linhas) | `data/HttpTransport.ios.kt` |
  | `data/Attachments.kt:38` `Attachments` | `data/Attachments.kt` (URIs do Android) | `data/Attachments.kt` (copia para pasta privada) |
  | `data/LocalStateStore.kt:53` `LocalStateStore` | `LocalStateStore.android.kt` (DataStore + `org.json`) | `data/LocalStateStore.kt` (arquivos JSON + `kotlinx.serialization`, 212 linhas) |
  | `data/SessionCookieStore.kt:8,12` `SessionLock`, `SecureSessionStorage` | `data/SessionCookieStore.kt` (AES-GCM com chave no AndroidKeyStore) | `data/SessionCookieStore.kt` (Keychain) |
  | `data/AvatarCache.kt:13` `NativeAvatarCache` | `data/AvatarCache.kt` (LruCache 4 MiB + disco 16 MiB, PNG 60×60, validade 24 h) | `data/AvatarCache.kt` |

- O `actual` Android de tempo (`UiPlatform.android.kt:37-48`) é JVM puro e serve de modelo literal:
  ```kotlin
  actual fun uiNowMillis(): Long = System.currentTimeMillis()
  actual fun platformEntryTime(at: Long, now: Long): String {
      val zone = ZoneId.systemDefault()
      val time = Instant.ofEpochMilli(at).atZone(zone)
      return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) "HH:mm" else "dd/MM HH:mm"))
  }
  ```
- O host iOS (`iosMain/.../MainViewController.kt:15-33`) é o modelo do host desktop: instala
  `LocalNotificationPlatform` e chama `ZaplaudApp()`:
  ```kotlin
  CompositionLocalProvider(
      LocalPresenter provides controller,
      LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, _ -> error("Notifications are unavailable on iOS") }),
  ) {
      if (foreground) ZaplaudApp()
  }
  ```
- O store iOS (`iosMain/.../data/LocalStateStore.kt`, 212 linhas) é o ponto de partida do desktop. Partes que usam
  Apple e precisam de troca (lista completa, conferida em `694938f`):
  - auxiliares `:15-35`: `storageHash` (CC_SHA256), `privateDirectory`, `protectedWrite`, `privateRead`;
  - `removeOrphanAttachments` (`:36-45`);
  - `StateDisk` (`:47-69`): `NSRecursiveLock` em `:49`; `NSUserDefaults.standardUserDefaults` em `:58-60` guarda as
    **configurações do aparelho** (`theme`, `summarize_replies`, `notifications`, `deliver_as`, `view`, `retry_*`,
    `notify_permission`, `notify_done`; usos em `:139-140` e `:184-195`). Essas sobrevivem ao `clear()`;
  - listagem e remoção de arquivos com `NSFileManager` em `:101-103`, `:163-165` e `:180-181`;
  - `NSUUID().UUIDString` em `:174`.
  O resto (`messageDecode`/`messageEncode`, regras da classe) é Kotlin comum com `kotlinx.serialization`.
- `android/build.gradle.kts` (raiz) declara os plugins com `apply false`; o plugin Kotlin já está no classpath por
  `alias(libs.plugins.kotlin.multiplatform) apply false`.
- Convenções: comentários e mensagens em português; código Kotlin compacto como nos arquivos existentes; todo texto
  visível vem de `android/shared/src/commonMain/composeResources/values/strings.xml` via `Res.string.*`; toda mudança
  visível entra em "Não lançado" no `CHANGELOG.md`, com prefixo `**Zaplaud:**` (ver as entradas atuais).

## Decisões já tomadas (não reabrir)

- Alvo: `jvm()` no módulo `shared` (fontes em `src/jvmMain` e `src/jvmTest`). Host num módulo novo `:desktop`
  (`android/desktop/`), como `:app` é o host Android e `ios/` o host iOS. O módulo `shared` continua biblioteca.
- HTTP: Ktor com OkHttp (mesma configuração do Android, copiada).
- Estado local: porta do store **iOS**: arquivos JSON em `java.nio`; configurações do aparelho em
  `java.util.prefs.Preferences.userRoot().node(<nó>)` (o equivalente Java do `NSUserDefaults`; no Linux fica em
  `~/.java/.userPrefs`, no macOS num plist). Nó padrão `br/com/xlinx/zaplaud`; a propriedade `zaplaud.prefsNode`
  substitui (testes). Não usar DataStore nem `org.json` no desktop.
- Cookie de sessão: chaveiro do sistema via `com.github.javakeyring:java-keyring:1.0.4` (Secret Service no Linux,
  Keychain no macOS). **Nunca** gravar o cookie em arquivo sem criptografia. Sem chaveiro disponível (Linux sem
  Secret Service), o cookie fica só na memória e o login é pedido a cada abertura.
- Anexos: o desktop referencia o arquivo escolhido pelo caminho (`file://`), como o Android referencia o URI. Não
  copia para uma pasta privada.
- Pastas: Linux `${XDG_DATA_HOME:-~/.local/share}/zaplaud` e `${XDG_CACHE_HOME:-~/.cache}/zaplaud`; macOS
  `~/Library/Application Support/Zaplaud` e `~/Library/Caches/Zaplaud`. A propriedade de sistema `zaplaud.dataDir`,
  se definida, substitui as duas (testes usam isso). Pastas criadas com permissão 700 em POSIX. Leia as propriedades
  **a cada acesso** (`val dataDir get() = ...`), nunca uma vez só.
- Testes JVM nunca tocam o chaveiro nem as preferências reais: a propriedade `zaplaud.keyring=memory` força o cookie
  só em memória, e o `jvmTest` roda com `forkEvery = 1` (um processo por classe; `SessionMemory` e `StateDisk` são
  globais).
- Voltar: tecla Esc na janela chama o último `PlatformBackHandler` habilitado.
- Notificações: neste plano o desktop instala `NotificationPlatform(available = false, ...)`. O plano 065 liga.

## Comandos que você vai usar

Rode de `android/` (o wrapper está lá). Use uma worktree em `/tmp` (regra do dono: nada temporário em
`~/Desenvolvimento`): `git worktree add /tmp/habblaud-063 -b advisor/063-zaplaud-desktop-alvo-jvm`.

| Propósito | Comando | Esperado |
|---|---|---|
| Testes host Android (regressão) | `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest` | exit 0 (exceto `ConversationStreamTest`, que já estoura o tempo em `f47c461`; ver `plans/README.md`) |
| Lint e APK (regressão) | `./gradlew :app:lintDebug :app:assembleDebug` | exit 0 |
| Compilar o alvo JVM | `./gradlew :shared:compileKotlinJvm` | exit 0 |
| Testes JVM | `./gradlew :shared:jvmTest` | exit 0 |
| Rodar o desktop | `./gradlew :desktop:run` | janela "Zaplaud" abre |
| Servidor de teste | `node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions` (da raiz do repo, depois de `npm ci`) | imprime uma linha JSON com `"ready":true`, usuário e senha `android-test` |

Sem tela (máquina sem `$DISPLAY`/`$WAYLAND_DISPLAY`): use `xvfb-run -a ./gradlew :desktop:run`. Se `xvfb-run` não
existir, peça ao operador para instalar (`xorg-server-xvfb` no Arch); não instale nada sem autorização.

## Escopo

**Dentro do escopo** (só estes arquivos):
- `android/settings.gradle.kts`
- `android/build.gradle.kts` (raiz; só para declarar `kotlin.jvm` com `apply false`)
- `android/gradle/libs.versions.toml`
- `android/shared/build.gradle.kts`
- `android/shared/src/jvmMain/**` (criar)
- `android/shared/src/jvmTest/**` (criar)
- `android/desktop/build.gradle.kts` e `android/desktop/src/main/kotlin/**` (criar)
- `CHANGELOG.md` (uma entrada em "Não lançado")
- `android/README.md` ou a seção do Zaplaud no `README.md` em português, se existir uma seção "Zaplaud" com comandos de
  build: acrescente `./gradlew :desktop:run`. Se não existir, não crie.

**Fora do escopo** (não toque):
- Qualquer arquivo em `commonMain`, `androidMain`, `iosMain`, `android/app`, `ios/`. Se um `actual` desktop parecer
  exigir mudança num `expect` ou em código comum, é condição de PARADA.
- Layout em duas colunas (plano 064), notificações (065), pacotes `.deb`/`.rpm`/`.dmg` (066).
- `server/`, `client/`, `shared/` (TypeScript).
- `.github/workflows/*` (o plano 066 trata do CI).

## Git

- Branch `advisor/063-zaplaud-desktop-alvo-jvm`, worktree `/tmp/habblaud-063`.
- Commits por passo, no estilo do repositório, por exemplo: `feat(desktop): alvo JVM no módulo shared`,
  `feat(desktop): armazenamento local em arquivos JSON`.
- Não faça push, merge nem PR sem ordem do operador.

## Passos

### Passo 1: alvo JVM e módulo `:desktop` compilando com `actual` provisórios mínimos

1. Em `libs.versions.toml`, acrescente na seção `[libraries]`:
   ```toml
   java-keyring = { module = "com.github.javakeyring:java-keyring", version = "1.0.4" }
   ```
   e na seção `[plugins]`:
   ```toml
   kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
   ```
   Em `android/build.gradle.kts` (raiz), acrescente `alias(libs.plugins.kotlin.jvm) apply false` junto dos outros.
   Para HTTP, reuse os aliases existentes `libs.ktor.client.okhttp` e `libs.okhttp`.
2. Em `android/shared/build.gradle.kts`, dentro de `kotlin { }`, acrescente o alvo e as dependências:
   ```kotlin
   jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
   // em sourceSets { ... }
   jvmMain.dependencies {
       implementation(compose.desktop.common)
       implementation(libs.okhttp)
       implementation(libs.ktor.client.okhttp)
       implementation(libs.java.keyring)
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
   }
   jvmTest.dependencies {
       implementation(kotlin("test"))
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
       implementation(compose.desktop.currentOs)
       implementation(compose.desktop.uiTestJUnit4)
   }
   ```
   e, fora de `kotlin { }`, isole os testes JVM (um processo por classe, sem chaveiro nem preferências reais):
   ```kotlin
   tasks.named<Test>("jvmTest") {
       forkEvery = 1
       systemProperty("zaplaud.keyring", "memory")
       systemProperty("zaplaud.prefsNode", "br/com/xlinx/zaplaud-test")
       systemProperty("zaplaud.dataDir", layout.buildDirectory.dir("jvmTest-data").get().asFile.absolutePath)
   }
   ```
3. Crie `android/desktop/build.gradle.kts`:
   ```kotlin
   plugins {
       alias(libs.plugins.kotlin.jvm)
       alias(libs.plugins.compose.multiplatform)
       alias(libs.plugins.compose)
   }
   kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
   java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }  // como android/app
   dependencies {
       implementation(project(":shared"))
       implementation(compose.desktop.currentOs)
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
   }
   compose.desktop {
       application {
           mainClass = "br.com.xlinx.habblaud.desktop.MainKt"
           nativeDistributions { packageName = "Zaplaud"; packageVersion = "1.0.0" }
       }
   }
   ```
   e inclua `":desktop"` em `android/settings.gradle.kts`.
4. Crie em `jvmMain` um `actual` para cada `expect` da tabela, inicialmente com o comportamento final mais simples
   possível só para os que são triviais (tempo e `animationsEnabled() = true`), e `TODO()` **apenas temporariamente**
   para os demais. Nenhum `TODO()` pode sobrar ao fim do plano (critério de pronto).

**Verificar**: `./gradlew :shared:compileKotlinJvm :desktop:compileKotlin` → exit 0. Depois
`./gradlew :shared:testAndroidHostTest :app:assembleDebug` → exit 0 (o alvo novo não quebrou o Android).

### Passo 2: tempo, rede e voltar (`UiPlatform.jvm.kt`, `data/HttpTransport.jvm.kt`)

- `UiPlatform.jvm.kt`: copie literalmente `uiNowMillis`, `platformEntryTime`, `platformDeadline` e
  `retryAfterDeadline` de `androidMain/.../UiPlatform.android.kt:37-48` (mesmos imports `java.time`/`java.text`).
  `animationsEnabled()` retorna `true`.
- `PlatformBackHandler`: mantenha uma pilha global de handlers e exponha uma função pública para o host:
  ```kotlin
  private class BackEntry(var enabled: Boolean, var onBack: () -> Unit)
  private val backStack = mutableListOf<BackEntry>()
  @Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
      val entry = remember { BackEntry(enabled, onBack) }
      SideEffect { entry.enabled = enabled; entry.onBack = onBack }
      DisposableEffect(entry) { backStack += entry; onDispose { backStack -= entry } }
  }
  /** Chamada pelo host ao soltar Esc; true se algum handler consumiu. */
  fun dispatchDesktopBack(): Boolean = backStack.lastOrNull { it.enabled }?.let { it.onBack(); true } ?: false
  ```
  (Toda a composição roda na thread de UI do Swing; a lista não precisa de lock.)
- `data/HttpTransport.jvm.kt`: copie `androidMain/.../data/HttpTransport.android.kt` inteiro (mesmo `HttpClient(OkHttp)`,
  `followRedirects = false`, interceptor one-shot, `cookieJar(NO_COOKIES)`, `install(SSE) { maxReconnectionAttempts = 0 }`,
  `.also { it.installBoundedSse() }`, e `platformUpload` delegando a `api.streamedUpload`). Comentário na primeira
  linha: `// Cópia de HttpTransport.android.kt: mantenha as duas iguais.`

**Verificar**: `./gradlew :shared:compileKotlinJvm` → exit 0.

### Passo 3: pastas e estado local (`data/DesktopDirs.kt`, `data/LocalStateStore.jvm.kt`)

1. `DesktopDirs.kt`: `internal val dataDir: Path get() = ...` e `internal val cacheDir: Path get() = ...` conforme
   "Decisões" (com `System.getProperty("zaplaud.dataDir")` definido: dados em `<dir>/data`, cache em `<dir>/cache`).
   Crie as pastas com `PosixFilePermissions.fromString("rwx------")` quando o sistema de arquivos for POSIX. No mesmo
   arquivo, `internal val settingsNode: Preferences get() = Preferences.userRoot().node(System.getProperty("zaplaud.prefsNode") ?: "br/com/xlinx/zaplaud")`.
2. `LocalStateStore.jvm.kt`: copie `iosMain/.../data/LocalStateStore.kt` e troque **cada** uso de Apple:
   - `storageHash` → SHA-256 com `java.security.MessageDigest`, hex minúsculo (mesmo formato).
   - `privateDirectory` → `dataDir.toString()`.
   - `protectedWrite(path, text)` → grava em `path + ".tmp"` e faz `Files.move(tmp, path, ATOMIC_MOVE, REPLACE_EXISTING)`.
   - `privateRead(path)` → `null` se não existe; senão `Files.readString`; erro de leitura lança, como no iOS.
   - `NSRecursiveLock()` (`:49`) → `java.util.concurrent.locks.ReentrantLock()` (mesmos `lock()`/`unlock()`).
   - `NSUserDefaults.standardUserDefaults` (`:58`) → `settingsNode`; `stringForKey(k)` → `get(k, null)`;
     `boolForKey(k)` → `getBoolean(k, false)`; `objectForKey(k) != null` → `get(k, null) != null`;
     `setObject(v: String, k)` → `put(k, v)`; `setBool(v, k)` → `putBoolean(k, v)`; `retry_*` com `NSNumber` →
     `getLong(k, 0)`/`putLong(k, v)`. Chame `flush()` depois de cada escrita. `clear()` **não** mexe nesse nó.
   - `NSFileManager...contentsOfDirectoryAtPath` + `removeItemAtPath` (`:101-103`, `:163-165`, `:180-181`) →
     `Files.list(dataDir).use { ... }` filtrando pelo mesmo prefixo de nome + `Files.delete`; falha lança, como no iOS.
   - `NSUUID().UUIDString` (`:174`) → `java.util.UUID.randomUUID().toString()`.
   - Remova `removeOrphanAttachments` e suas chamadas (o desktop não copia anexos; ver passo 5). **Mantenha**
     `usesAttachment` como está: é membro do `expect` (`commonMain/.../data/LocalStateStore.kt:54`).
   - Remova `@file:OptIn(ExperimentalForeignApi::class)` e os imports `kotlinx.cinterop`/`platform.*`.
   Comentário no topo: `// Porta de iosMain/.../LocalStateStore.kt (mesmo formato em disco). Mudanças de regra valem nos dois.`

**Verificar**: crie `jvmTest/.../data/LocalStateStoreJvmTest.kt` (ver "Plano de testes") e rode
`./gradlew :shared:jvmTest --tests '*LocalStateStoreJvmTest*'` → todos passam.

### Passo 4: cookie de sessão (`data/SessionCookieStore.jvm.kt`)

- `SessionLock`: delegue a `java.util.concurrent.locks.ReentrantLock` (como o Android faz em
  `androidMain/.../data/SessionCookieStore.kt:9-13`).
- `SecureSessionStorage`: serviço `br.com.xlinx.zaplaud`, conta `remote-session`, campo
  `@Volatile private var memory: String? = null` e um chaveiro aberto uma vez:
  `private val keyring = if (System.getProperty("zaplaud.keyring") == "memory") null else runCatching { Keyring.create() }.getOrNull()`
  (`BackendNotSupportedException` ou qualquer erro ao criar → `null` = modo só memória).
  - `read()`: com `keyring`, `runCatching { keyring.getPassword(serviço, conta) }.getOrNull() ?: memory`
    (`PasswordAccessException` significa entrada ausente ou inacessível; nos dois casos vale a memória); sem
    `keyring`, `memory`.
  - `write(value)`: `memory = value`; com `keyring`, `runCatching { setPassword(...) }` e, se falhar, uma linha em
    `System.err` **sem o valor** ("Zaplaud: chaveiro indisponível, login só nesta execução"). **Nunca** em arquivo.
  - `clear()`: `memory = null`; com `keyring`, `runCatching { deletePassword(...) }` (ausente não é erro).
  - Nunca registre o valor em log, mensagem de erro ou exceção.

**Verificar**: `./gradlew :shared:compileKotlinJvm` → exit 0. Teste manual no passo 7 (login sobrevive a fechar e abrir
com chaveiro; sem chaveiro, pede login de novo).

### Passo 5: anexos e seletor (`data/Attachments.jvm.kt`, `rememberAttachmentPicker`)

- `rememberAttachmentPicker`: abre `java.awt.FileDialog(null as Frame?, título, FileDialog.LOAD)` com
  `isMultipleMode = true`, roda fora da composição (`SwingUtilities.invokeLater` não é necessário: o clique já está na
  thread do Swing; o `FileDialog` é modal) e chama `onResult(files.map { it.toPath().toUri().toString() })`. Cancelado
  → `onResult(emptyList())`. Título: use um texto existente de `Res.string` (`attach_files`), lido com
  `stringResource` antes de devolver o lambda.
- `Attachments`:
  - `select(uris, existing)`: para cada URI novo (ignore repetidos e os que já estão em `existing`), aplique
    `checkAttachmentCount(existing.size, novos.size)`; resolva `Path.of(URI(uri))`; exija arquivo regular legível com
    tamanho em `1..MAX_ATTACHMENT_SIZE` (senão `error("invalid attachment")`); MIME por
    `Files.probeContentType(path) ?: "application/octet-stream"`; devolva
    `existing + Attachment(uri, path.fileName.toString(), mime, size, persistent = true)`.
  - `available(a)`: o arquivo existe, é legível e o tamanho atual é igual a `a.size`.
  - `persisted(a)`: `a.persistent && available(a)`.
  - `release(a)`: não faz nada (o arquivo é do usuário). Comentário de uma linha dizendo isso.
  - `body(a, progress)`: `AttachmentBody(a.size, { FileInputStream adaptado a AttachmentSource }, progress)`, com
    `read` retornando `-1` no fim, como o iOS (`iosMain/.../data/Attachments.kt`, função `body`).

**Verificar**: `./gradlew :shared:jvmTest --tests '*AttachmentsJvmTest*'` → passa (ver testes).

### Passo 6: cache de avatar (`data/AvatarCache.jvm.kt`)

Mesmo contrato e limites do Android (`androidMain/.../data/AvatarCache.kt`): memória LRU de 4 MiB (use
`LinkedHashMap(16, 0.75f, true)` com contagem `largura × altura × 4`, protegida por `synchronized`), disco em
`cacheDir/avatars`, nome do arquivo = SHA-256 hex da chave, validade 24 h, arquivo > 1 MiB ignorado, só aceita PNG
60×60, poda do disco acima de 16 MiB (mais antigos primeiro). Decodifique com
`org.jetbrains.skia.Image.makeFromEncoded(bytes)` e converta com `toComposeImageBitmap()`.

**Verificar**: `./gradlew :shared:compileKotlinJvm` → exit 0.

### Passo 7: host da janela (`android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt`)

```kotlin
fun main() = application {
    val state = rememberWindowState(size = DpSize(1100.dp, 760.dp))
    Window(onCloseRequest = ::exitApplication, state = state, title = "Zaplaud",
        onKeyEvent = { e -> e.type == KeyEventType.KeyUp && e.key == Key.Escape && dispatchDesktopBack() }) {
        CompositionLocalProvider(
            LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, result -> result(false) }),
        ) { ZaplaudApp() }
    }
}
```
Use `onKeyEvent` (não `onPreviewKeyEvent`), para que campos de texto e diálogos tratem o Esc antes.
Ícone da janela: `icon = painterResource(Res.drawable.ic_zaplaud)` no `Window(...)`. O vetor já existe em
`android/shared/src/commonMain/composeResources/drawable/ic_zaplaud.xml` (o pacote `Res` é `br.com.xlinx.habblaud.resources`).
O ícone dos pacotes `.deb`/`.rpm`/`.dmg` fica para o plano 066.

**Verificar** (com o servidor de teste rodando em outro terminal):
1. `./gradlew :desktop:run` → janela "Zaplaud" com a tela de login.
2. Login em `http://127.0.0.1:14748`, usuário/senha `android-test`, confirmando o aviso de HTTP → lista de sessões.
3. Abrir uma sessão → conversa com markdown; Esc → volta para a lista.
4. Escrever e enviar uma mensagem numa sessão omp da fixture → o aviso de estado da mensagem some (entregue; o
   `ChatBanner` com `testTag("message-status-*")` só fica visível enquanto não é `delivered`). Anexar um arquivo
   pequeno de `/tmp` e enviar → aparece um arquivo novo dentro do `deliveredDir` informado na linha `ready` do servidor.
5. Fechar e abrir de novo: com Secret Service (GNOME Keyring/KWallet) ou macOS, continua logado; sem chaveiro, pede login.

## Plano de testes

Testes em `android/shared/src/jvmTest/kotlin/br/com/xlinx/habblaud/data/`, no estilo de
`android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/SessionIdentityTest.kt` (`kotlin.test`, `runTest`).
O `jvmTest` já roda com `forkEvery = 1` e com `zaplaud.dataDir`, `zaplaud.prefsNode` e `zaplaud.keyring=memory`
definidos no Gradle (passo 1). Em `@BeforeTest`, comece limpo: `LocalStateStore().clear()`,
`settingsNode.removeNode(); settingsNode.flush()` e apague o conteúdo de `zaplaud.dataDir`. Não troque a propriedade
dentro do teste.

- `LocalStateStoreJvmTest` (porte de casos de `iosTest/.../data/NativeStorageTest.kt`; leia esse arquivo e porte os
  casos que não dependem de Foundation):
  - servidor adicionado aparece em `servers()` numa instância nova do store (persistiu em disco);
  - `removeServer` apaga rascunhos daquele servidor e mantém os do outro;
  - rascunho (`updateMessage`) sobrevive a instância nova;
  - `clear()` apaga estado de conta e preserva configurações (tema), como no iOS;
  - gravação atômica: depois de `setTheme`, não sobra arquivo `*.tmp` na pasta.
- `AttachmentsJvmTest`:
  - arquivo de 3 bytes vira `Attachment` com nome, tamanho 3 e `persistent = true`; `body` lê os 3 bytes;
  - arquivo vazio e arquivo maior que `MAX_ATTACHMENT_SIZE` são recusados;
  - seis arquivos de uma vez estouram `checkAttachmentCount`;
  - `available` fica `false` depois de apagar ou mudar o tamanho do arquivo.

**Verificar**: `./gradlew :shared:jvmTest` → todos passam.

## Critérios de pronto

- [ ] `./gradlew :shared:compileKotlinJvm :desktop:compileKotlin :shared:jvmTest` sai com 0.
- [ ] `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` sai com 0
      (ressalva conhecida: `ConversationStreamTest`; registre se falhar igual ao `main`).
- [ ] `grep -rn "TODO()" android/shared/src/jvmMain android/desktop` não encontra nada.
- [ ] `grep -rn "remote-session\|writeText\|writeString" android/shared/src/jvmMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.jvm.kt`
      não mostra gravação em arquivo.
- [ ] Os 5 itens do passo 7 conferidos à mão e registrados na linha 063 do `plans/README.md`.
- [ ] `git status` só mostra arquivos do escopo.
- [ ] Entrada `**Zaplaud:**` em "Não lançado" no `CHANGELOG.md` (ex.: "versão para Linux e macOS numa janela, com
      as mesmas telas do celular; notificações e pacotes vêm depois").

## Condições de PARADA

- Algum `actual` exige mudar `expect` ou código de `commonMain`/`androidMain`/`iosMain`.
- Gradle não resolve uma dependência JVM de `commonMain` (markdown, lifecycle, material3, resources) na versão fixada:
  relate o erro exato; não troque versões.
- O alvo `jvm()` conflita com o plugin `com.android.kotlin.multiplatform.library` (erro de configuração do Gradle).
- `ZaplaudApp()` lança exceção na JVM por causa de `LocalLifecycleOwner` ou de recurso ausente.
- `java-keyring` 1.0.4 não carrega no macOS ou no Linux com Secret Service: relate; não grave cookie em arquivo como
  alternativa.
- Qualquer teste Android que passava no `main` passa a falhar.

## Notas de manutenção

- Três cópias deliberadas: `HttpTransport.jvm.kt` ≈ `HttpTransport.android.kt`; `LocalStateStore.jvm.kt` ≈
  `iosMain/.../LocalStateStore.kt`; funções de tempo ≈ Android. Revisões de qualquer uma devem olhar a outra. Quando o
  Mac estiver acessível, vale um plano para mover o store iOS+desktop para um conjunto de fontes intermediário comum.
- `animationsEnabled()` ignora a preferência "reduzir animações" do GNOME/macOS (não há API Java estável).
- `java-keyring` 1.0.4 é de 2023 e usa JNA. Se parar de funcionar numa versão nova do JDK, a troca fica isolada em
  `SessionCookieStore.jvm.kt`.
- O ciclo de vida (`LocalLifecycleOwner`) da janela decide quando `ReadingController.start/stop` rodam. Minimizar pode
  parar os streams. [INFERÊNCIA] Confira no passo 7 e registre o que observou.
