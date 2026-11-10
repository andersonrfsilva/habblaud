# Plano 065: Zaplaud desktop avisa aprovação, pergunta e fim de turno com a janela aberta em segundo plano

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação antes do próximo. Em condição de
> PARADA, pare e relate; não improvise. Ao terminar, atualize a linha 065 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 694938f..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/NotificationRules.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`.
> Divergência dos trechos abaixo é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (refatora o laço do serviço de notificação do Android)
- **Depende de**: `063`
- **Categoria**: direction (cliente desktop)
- **Planejado em**: commit `694938f`, 2026-11-09

## Por que importa

No Android, o Zaplaud avisa quando um agente pede aprovação, faz uma pergunta ou termina o turno. Sem isso, o desktop
obriga a pessoa a ficar olhando a janela. Decisão do dono (2026-11-09): **sem ícone na bandeja nesta etapa**; o desktop
avisa enquanto o app estiver aberto, quando a janela não está em foco (minimizada ou atrás de outra). Bandeja e início
automático ficam para depois.

## Estado atual

- Regra de quando avisar já é comum: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/NotificationRules.kt`
  (`agentEvents(previous, current)` e `watchOf(sessions)`; `AgentEvent.Permission` e `AgentEvent.TurnDone`).
- O laço que abre `/api/stream` em cada servidor está **dentro do serviço Android**
  (`android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`, função `watch()`):
  ```kotlin
  private suspend fun watch() {
      val store = LocalStateStore(applicationContext)
      val origins = store.servers().filter { UrlPolicy.origin(it) == it }
      if (origins.isEmpty() || !store.notificationsEnabled()) { stopSelf(); return }
      multipleServers = origins.size > 1
      coroutineScope {
          origins.map { origin -> launch {
              val api = HabblaudApi(origin)
              synchronized(apis) { apis.add(api) }
              try {
                  val sessions = SessionRepository(api)
                  val streams = ConversationRepository(api)
                  var previous: Map<SessionKey, AgentWatch>? = null
                  while (currentCoroutineContext().isActive) {
                      var unauthorized = false
                      streams.stream("/api/stream").collect { event ->
                          if (event is StreamEvent.Failure && event.status == 401) unauthorized = true
                          // O snapshot depois de uma reconexão compara com o último antes da queda: o que mudou nela também avisa.
                          if (event is StreamEvent.Data && event.type == "snapshot") {
                              val current = sessions.snapshot(event.data)
                              previous?.let { before ->
                                  val (permission, done) = store.notifyEvents()
                                  agentEvents(before, current).filter { if (it is AgentEvent.Permission) permission else done }.forEach(::notify)
                              }
                              previous = watchOf(current)
                          }
                      }
                      // Fim do stream: login vencido (401) ou erro definitivo. Sem login, para; senão tenta de novo daqui a pouco.
                      if (unauthorized) return@launch
                      delay(30_000)
                  }
              } finally {
                  synchronized(apis) { apis.remove(api) }
                  api.close()
              }
          } }.joinAll()
      }
      if (currentCoroutineContext().isActive) stopSelf()
  }
  ```
  `notify(event)` não avisa se `MainActivity.visible`; o texto vem de `alertNotification(...)` no mesmo arquivo:
  título `session.characterName ?: session.title`; texto `notify_question` / `notify_permission` / `notify_done`;
  subtexto `projeto · servidor` (servidor só com mais de um); toque abre a sessão via `pendingSession`.
- Os textos já existem no recurso comum `android/shared/src/commonMain/composeResources/values/strings.xml`:
  `notify_permission` ("Pede sua aprovação"), `notify_question` ("Tem uma pergunta para você"), `notify_done`
  ("Terminou o turno"), `settings_notifications_on` ("Avisar com o app fechado").
- `UiPlatform.kt:12-17`:
  ```kotlin
  class NotificationPlatform(
      val available: Boolean,
      val setEnabled: (Boolean) -> Unit,
      val openBackgroundSettings: () -> Unit,
      val setEnabledFromSettings: (Boolean, (Boolean) -> Unit) -> Unit,
  )
  ```
- `SettingsScreen.kt:192-210`: com `available`, mostra a chave `settings_notifications_on`, as duas chaves de tipo de
  evento e **sempre** o botão `background_settings` (ajuste de bateria do Android).
- `ZaplaudApp.kt:90,114-115,139` liga/desliga `notificationsPlatform.setEnabled(...)` ao carregar, sair e entrar.
- `pendingSession` (`UiPlatform.kt:10`) abre uma sessão quando a lista carrega (`ReadingScreen.kt:127-134`).
- O host desktop (plano 063, `android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt`) instala hoje
  `NotificationPlatform(false, {}, {}, { _, result -> result(false) })`.

## Decisões já tomadas

- Laço extraído para `commonMain` como `suspend fun watchAlerts(store: LocalStateStore, onServers: (Int) -> Unit, notify: (AgentEvent) -> Unit)`
  em `data/AlertWatcher.kt`, com o **mesmo** corpo (sem `stopSelf`, sem `synchronized(apis)`: o chamador cuida do ciclo).
  O serviço Android passa a chamá-lo; comportamento do Android inalterado.
- Desktop entrega a notificação pelo sistema, sem dependência nova:
  - Linux: `notify-send --app-name=Zaplaud --action=open=Abrir --wait <título> <texto>` via `ProcessBuilder` (argumentos
    em lista, **nunca** por shell). Se a saída for `open`, define `pendingSession` e traz a janela para a frente.
    Sem `notify-send` no PATH: registra uma vez em `System.err` e não avisa (não lança).
  - macOS: `osascript -e 'on run argv' -e 'display notification (item 2 of argv) with title (item 1 of argv)' -e 'end run' <título> <texto>`
    (texto por argumento, sem montar script com o conteúdo). Limitação aceita: clicar não abre a sessão.
- Só avisa quando a janela **não** está em foco (equivalente ao `MainActivity.visible`).
- `NotificationPlatform` ganha `val whileOpenOnly: Boolean = false` (valor padrão: hosts Android e iOS não mudam).
  Com `true`, a configuração mostra o texto novo `settings_notifications_open` = "Avisar com a janela em segundo plano"
  e esconde o botão `background_settings`.

## Comandos que você vai usar

Rode de `android/`. Worktree `/tmp/habblaud-065`, branch `advisor/065-zaplaud-desktop-notificacoes` a partir do 063.

| Propósito | Comando | Esperado |
|---|---|---|
| Testes host | `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest` | exit 0 (ressalva: `ConversationStreamTest`) |
| Lint/APK | `./gradlew :app:lintDebug :app:assembleDebug` | exit 0 |
| JVM | `./gradlew :shared:jvmTest :desktop:compileKotlin` | exit 0 |
| Instrumentado de notificação | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.NotificationPrivacyInstrumentedTest` | passa |
| Servidor de teste | `node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions` | `"ready":true`; aceita no stdin a linha `permission <agentId> <kind>` (ver `scripts/android-screens.sh:264`) |

## Escopo

**Dentro do escopo**:
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt` (criar)
- `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt` (só `watch()` passa a chamar `watchAlerts`)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt` (só o campo `whileOpenOnly`)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt` (só o bloco `:192-210`)
- `android/shared/src/commonMain/composeResources/values/strings.xml` (só `settings_notifications_open`)
- `android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/**`
- `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/NotificationTextTest.kt` (criar, se fizer o passo 2)
- `CHANGELOG.md`

**Fora do escopo**:
- Ícone na bandeja, iniciar com o sistema, rodar com a janela fechada (decisão do dono: depois).
- `NotificationRules.kt` (regra de quando avisar não muda).
- Notificações no iOS.
- Bibliotecas novas (DBus, JNA para `UNUserNotificationCenter`, `terminal-notifier`).

## Git

Commits no estilo `refactor(android): laço de avisos em commonMain` e `feat(desktop): avisos com a janela em segundo plano`.
Sem push/merge/PR sem ordem do operador.

## Passos

### Passo 1: extrair o laço

Crie `data/AlertWatcher.kt` com `watchAlerts(store, onServers, notify)` contendo o corpo de `watch()` acima, trocando:
`stopSelf()` → `return`; `multipleServers = origins.size > 1` → `onServers(origins.size)`; `forEach(::notify)` →
`forEach(notify)`; o registro em `apis` some (cada `launch` fecha o seu `api` no `finally`, como hoje). Mantenha os
dois comentários existentes. No Android, `watch()` vira:
```kotlin
private suspend fun watch() {
    watchAlerts(LocalStateStore(applicationContext), onServers = { multipleServers = it > 1 }, notify = ::notify)
    if (currentCoroutineContext().isActive) stopSelf()
}
```
e a lista `apis` sai do serviço só se não for mais usada em `onDestroy` (o `scope.cancel()` encerra os `launch`, que
fecham os clientes no `finally`).

**Verificar**: `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → exit 0;
o instrumentado `NotificationPrivacyInstrumentedTest` → passa.

### Passo 2: texto do aviso em código comum

Crie em `commonMain` (mesmo `AlertWatcher.kt`) `fun alertText(event: AgentEvent): StringResource` com a mesma escolha
do Android (`Permission` + `permissionQuestion` → `notify_question`; `Permission` → `notify_permission`;
`TurnDone` → `notify_done`) e `fun alertSubtitle(event: AgentEvent, multipleServers: Boolean): String?` (projeto ·
servidor, como `alertNotification`). Não mude `alertNotification` no Android (fora do escopo; ele usa os recursos do
`app`).

**Verificar**: `NotificationTextTest` (ver testes) passa com `./gradlew :shared:testAndroidHostTest`.

### Passo 3: configuração com `whileOpenOnly`

Acrescente `val whileOpenOnly: Boolean = false` ao fim de `NotificationPlatform`. Em `SettingsScreen.kt`, use
`if (notificationsPlatform.whileOpenOnly) Res.string.settings_notifications_open else Res.string.settings_notifications_on`
como rótulo da chave e só mostre o `TextButton` de `background_settings` quando `!whileOpenOnly`.

**Verificar**: `./gradlew :shared:compileKotlinJvm :app:assembleDebug` → exit 0.

### Passo 4: entrega no desktop

No módulo `:desktop`, crie `DesktopAlerts.kt`:
- `class DesktopAlerts(private val focused: () -> Boolean, private val bringToFront: () -> Unit)` com um
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)` e `fun setEnabled(on: Boolean)`: ligar cancela o job anterior e
  inicia `watchAlerts(LocalStateStore(), onServers = { multiple = it > 1 }, notify = ::show)`; desligar cancela.
- `show(event)`: se `focused()`, não faz nada. Título `event.session.characterName ?: event.session.title`; texto
  `getString(alertText(event))` (`org.jetbrains.compose.resources.getString`, suspenso) mais `alertSubtitle` depois de
  ` · ` quando houver. Linux/macOS conforme "Decisões". O processo do `notify-send --wait` roda em `launch` próprio;
  ao ler `open`, `pendingSession.value = event.session.key` e `bringToFront()` na thread do Swing
  (`withContext(Dispatchers.Main)`).
- Detecção de SO: `System.getProperty("os.name").lowercase().startsWith("mac")`.
- Em `Main.kt`: crie `DesktopAlerts({ window.isFocused }, { window.isMinimized = false; window.toFront() })` dentro de
  `Window { }` (use `window` do `FrameWindowScope`) e instale
  `NotificationPlatform(available = true, setEnabled = alerts::setEnabled, openBackgroundSettings = {}, setEnabledFromSettings = { on, result -> alerts.setEnabled(on); result(on) }, whileOpenOnly = true)`.
  Cancele o escopo no `onCloseRequest` antes de `exitApplication()`.

**Verificar** (servidor de teste rodando, desktop logado nele, notificações ligadas em Configurações):
1. Minimize a janela; no stdin do servidor digite `permission <agentId> bash` (use um `agentId` da linha `ready`) →
   aparece a notificação "Pede sua aprovação" com o nome do agente.
2. Linux: clique em "Abrir" → a janela volta e a sessão do agente está aberta.
3. Com a janela em foco, repita com outro agente → nenhuma notificação.
4. Desligue em Configurações e repita minimizado → nenhuma notificação.

## Plano de testes

- `NotificationTextTest` em `commonTest`, no estilo de `commonTest/.../SessionIdentityTest.kt`: os três casos de
  `alertText` e `alertSubtitle` com um e com vários servidores (sem servidor quando é um só; `null` sem projeto nem
  servidor). Monte `SessionItem` como os testes existentes montam (procure um construtor de teste em `commonTest`).
- Não escreva teste que simule `notify-send`; a prova é a verificação manual do passo 4.

## Critérios de pronto

- [ ] `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :shared:jvmTest :desktop:compileKotlin` → exit 0 (ressalva: `ConversationStreamTest`).
- [ ] `NotificationPrivacyInstrumentedTest` passa no emulador.
- [ ] `grep -n "streams.stream(\"/api/stream\")" android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt` → nada (o laço saiu do serviço).
- [ ] `grep -rn "sh -c\|bash -c\|Runtime.getRuntime().exec(\"" android/desktop` → nada.
- [ ] Os 4 itens do passo 4 conferidos e registrados na linha 065 (macOS: itens 1, 3 e 4, se houver Mac acessível;
      senão registre "macOS não verificado").
- [ ] Entrada `**Zaplaud:**` em "Não lançado": no desktop, avisos de aprovação, pergunta e fim de turno com a janela em
      segundo plano; no Linux, "Abrir" leva à sessão.

## Condições de PARADA

- O instrumentado de notificação ou o comportamento do serviço Android muda depois do passo 1.
- `watchAlerts` precisa de algo do `Context` Android.
- `notify-send --wait` não retorna a ação no ambiente do dono (por exemplo, servidor de notificação sem ações): relate
  e entregue sem o "Abrir", só com o aviso.
- `window.isFocused` não reflete o foco real no Wayland: relate antes de inventar outra detecção.

## Notas de manutenção

- O desktop abre uma segunda conexão `/api/stream` por servidor para os avisos, além da que a tela usa. É o mesmo
  modelo do Android (serviço separado da tela). Se o custo pesar, a alternativa é derivar eventos do estado do
  `ReadingController`, mas isso acopla aviso à tela.
- No macOS, notificações do `osascript` aparecem como do "Script Editor" e clicar abre o Script Editor
  [INFERÊNCIA; confira num Mac]. Notificação nativa com clique exige app assinado e `UNUserNotificationCenter`
  (fica para o plano de bandeja/pacote).
- Bandeja e "continuar rodando com a janela fechada" foram adiados por decisão do dono em 2026-11-09.
