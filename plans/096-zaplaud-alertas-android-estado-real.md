# Plano 096: Zaplaud Android: o interruptor de avisos mostra se o aviso chega de verdade, o serviço não cai com "erro de armazenamento" e reiniciar não perde eventos

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 096 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (mexe em quando o serviço em primeiro plano inicia; errar deixa o usuário sem avisos)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Os avisos de aprovação, pergunta e fim de turno são o motivo de ter o app no celular. Três defeitos fazem o
usuário achar que será avisado quando não será:

1. O interruptor em Configurações grava "ligado" **antes** de o Android responder ao pedido de permissão. Se a
   permissão é negada e a tela é recriada no meio (rotação, por exemplo), o "ligado" fica gravado e nenhum aviso
   chega. O interruptor também ignora notificações bloqueadas nas configurações do sistema ou no canal.
2. Na abertura, o serviço é iniciado depois de esperar a rede. Se o usuário já saiu do app, o Android 12+ recusa
   o início (`ForegroundServiceStartNotAllowedException`); a exceção cai no `catch` genérico e o app mostra
   "erro de armazenamento", sem avisos.
3. Cada chamada de início reinicia a vigilância e joga fora a "foto" anterior do escritório; o que mudou entre a
   parada e a nova conexão não gera aviso. Isso acontece em todo login e toda volta de configuração.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt:227-235`:

```kotlin
notifications?.let { on ->
    SwitchRow(stringResource(...), on) {
        notifications = !on
        scope.launch { store.setNotificationsEnabled(!on) }          // grava antes da resposta do sistema
        notificationsPlatform.setEnabledFromSettings(!on) { granted ->
            notifications = granted
            if (!granted && !on) scope.launch { store.setNotificationsEnabled(false) }
        }
    }
```

`:80-83`: o valor inicial vem só de `store.notificationsEnabled()`.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt:12-18`:

```kotlin
class NotificationPlatform(
    val available: Boolean,
    val setEnabled: (Boolean) -> Unit,
    val openBackgroundSettings: () -> Unit,
    val setEnabledFromSettings: (Boolean, (Boolean) -> Unit) -> Unit,
    val whileOpenOnly: Boolean = false,
)
```

Construída em três lugares: `android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:48-53` (Android), `android/shared/src/iosMain/.../MainViewController.kt:27`
(iOS: `NotificationPlatform(false, {}, {}, { _, _ -> error(...) })`) e no desktop obsoleto
(`android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt`).

`android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:32-47`:

```kotlin
var permissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) NotificationService.start(applicationContext)
    permissionResult?.invoke(granted)
    permissionResult = null
}
fun changeNotifications(enabled: Boolean, result: ((Boolean) -> Unit)?) {
    when {
        !enabled -> { NotificationService.stop(applicationContext); result?.invoke(false) }
        NotificationService.allowed(applicationContext) -> { NotificationService.start(applicationContext); result?.invoke(true) }
        Build.VERSION.SDK_INT >= 33 -> { permissionResult = result; ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}
```

`android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`:

```kotlin
// :46-51
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val previous = job
    previous?.cancel()
    job = scope.launch { previous?.join(); watch() }      // toda chamada recomeça do zero
    return START_STICKY
}
// :81-84
fun start(context: Context) { if (allowed(context)) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java)) }
fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
fun stop(context: Context) = context.stopService(Intent(context, NotificationService::class.java))
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt:21-33`: a comparação usa `var previous: Map<SessionKey, AgentWatch>? = null`, local à
chamada de `watchAlerts`; a primeira foto de cada chamada só serve de base e não avisa.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`:

```kotlin
// :84-105  abertura
LaunchedEffect(Unit) {
    try {
        servers = store.servers()
        for (origin in servers) { ... api.access() ... }
        if (servers.isNotEmpty() && store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        message = strings(Res.string.storage_error)
    } finally { loaded = true }
}
// :125-126 (remoção de servidor)  notificationsPlatform.setEnabled(false); if (...) notificationsPlatform.setEnabled(true)
// :150 (login novo)               if (store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
```

Os pontos `:125-126` e `:150` **precisam** reiniciar a vigilância, porque a lista de servidores mudou.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-096 -b advisor/096-zaplaud-alertas-android-estado-real producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-096`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. No desktop Rust os avisos só existem com o app aberto e não há permissão do sistema; nada a portar.
- **CHANGELOG**: toda mudança visível ao usuário ganha uma linha em "Não lançado" no `CHANGELOG.md`.
- **Emulador, simulador e aparelhos do dono só com autorização expressa dele.** Sem ela, o aceite deste plano é
  compilar e passar os testes de host; registre no índice o que ficou sem verificação em aparelho.
- O desktop Compose (`android/desktop`, `android/shared/src/jvmMain`, `jvmTest`) está obsoleto: não recebe
  melhorias. Só toque nele se uma assinatura `expect` mudar e o `actual` do JVM deixar de compilar.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes de host, lint e APK debug | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` (base: 141 testes, 0 falhas; lint 0 erros) |

## Escopo

**Pode modificar**:
- `android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt`, `android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`, `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`, `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt`
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt` e
  `android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt`: só para continuar compilando se a
  classe `NotificationPlatform` ganhar parâmetro (dê valor padrão ao parâmetro novo para não precisar tocar).
- `android/shared/src/commonMain/composeResources/values/strings.xml` (texto do aviso bloqueado)
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/` (teste novo, só compilação sem autorização)
- `CHANGELOG.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt` e `NotificationRules.kt`: a regra de quando avisar não muda.
- Receptor de boot, push (FCM) ou reinício automático depois de reiniciar o celular: é direção de produto
  (plano 113), não este plano.
- O texto da notificação fixa ("conectado") e os canais.

## Passos

### Passo 1: `allowed` reflete o que o sistema entrega

Em `NotificationService.allowed`, exija também que as notificações do app e o canal de avisos não estejam
bloqueados:

```kotlin
fun allowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return false
    return manager.getNotificationChannel(CHANNEL_ALERTS)?.importance != NotificationManager.IMPORTANCE_NONE
}
```

(um canal ainda não criado devolve `null` e conta como permitido; ele é criado em `onCreate`.)

### Passo 2: início do serviço que não derruba nem mente

`NotificationService.start` passa a devolver `Boolean` e a capturar a recusa do sistema:

```kotlin
fun start(context: Context, restart: Boolean = false): Boolean {
    if (!allowed(context)) return false
    return try {
        ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java).putExtra(EXTRA_RESTART, restart)); true
    } catch (_: IllegalStateException) { false }   // inclui ForegroundServiceStartNotAllowedException (API 31+)
}
```

Em `MainActivity.onStart()` (hoje só `visible = true`), tente de novo quando o app volta à frente: leia
`store.notificationsEnabled()` e a lista de servidores numa corrotina (`lifecycleScope` ou o escopo que a
Activity já usa para `store`; veja como `store` é criado no topo de `onCreate`) e, se ligado e com servidor,
chame `NotificationService.start(applicationContext)`. Com o passo 3, chamar com o serviço já rodando não
reinicia nada.

Em `ZaplaudApp.kt:101`, mova a chamada `notificationsPlatform.setEnabled(true)` para **fora** do `try` que
converte exceção em `storage_error` (logo depois do bloco, dentro do mesmo `LaunchedEffect`, protegida por
`servers.isNotEmpty()`), para uma falha de início nunca aparecer como erro de armazenamento.

### Passo 3: reinício só quando a lista de servidores muda

Em `onStartCommand`, mantenha a vigilância em andamento, a menos que o pedido traga `EXTRA_RESTART = true`:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (job?.isActive == true && intent?.getBooleanExtra(EXTRA_RESTART, false) != true) return START_STICKY
    val previous = job
    previous?.cancel()
    job = scope.launch { previous?.join(); watch() }
    return START_STICKY
}
```

`intent == null` (reinício pelo sistema depois de matar o processo) cai no caminho que inicia, porque `job`
é nulo no processo novo.

Propague o reinício pelos chamadores: `NotificationPlatform.setEnabled` continua `(Boolean) -> Unit`; acrescente
à classe um parâmetro com valor padrão `val restart: () -> Unit = {}` e use-o em `ZaplaudApp.kt:125-126` (no
lugar do par `setEnabled(false)`/`setEnabled(true)` quando ainda há servidores) e em `:150`. Em `MainActivity`,
`restart = { NotificationService.start(applicationContext, restart = true) }`. O valor padrão mantém iOS e
desktop compilando sem mudança.

### Passo 4: interruptor grava depois da resposta e mostra o bloqueio

Em `SettingsScreen.kt:228-235`, ao **ligar**, não grave antes: chame
`notificationsPlatform.setEnabledFromSettings(true) { granted -> notifications = granted; scope.launch { store.setNotificationsEnabled(granted) } }`.
Ao **desligar**, grave e pare como hoje.

Acrescente à `NotificationPlatform` `val deliverable: () -> Boolean = { true }` (Android:
`{ NotificationService.allowed(applicationContext) }`). Em `SettingsScreen`, quando o valor guardado é "ligado"
mas `deliverable()` é `false`, mostre o interruptor **desligado** e, abaixo, um `Hint` com a string nova
`settings_notifications_blocked` ("As notificações do Zaplaud estão bloqueadas nas configurações do Android.").
Recalcule ao voltar para a tela: a tela já usa `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)`
(`:92-93`); leia `deliverable()` dentro de um bloco desses para atualizar ao retornar das configurações do
sistema.

Em `MainActivity.changeNotifications`, no ramo final do `when` falta o caso "API < 33 e bloqueado": acrescente
`else -> result?.invoke(false)`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 5: teste instrumentado (compilar; executar só com autorização) e registro

Em `android/app/src/androidTest/java/br/com/xlinx/habblaud/`, crie `NotificationServiceRestartInstrumentedTest.kt`
(padrão: `NotificationPrivacyInstrumentedTest.kt`, mesmo `ApplicationProvider`): com a permissão concedida
(`UiAutomation.grantRuntimePermission`), `NotificationService.start(context)` devolve `true`; com a permissão
revogada em API 33+, devolve `false` e não lança.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:compileDebugAndroidTestKotlin`
→ `BUILD SUCCESSFUL`.

`CHANGELOG.md` ("Não lançado"): uma linha sobre o interruptor mostrar o bloqueio do sistema e os avisos não
perderem eventos ao trocar de servidor.

## Plano de testes

- O teste instrumentado do passo 5 (compila sem autorização; roda com).
- Roteiro manual para o dono, no M35, registrado no índice como pendente até ele fazer: (a) negar a permissão no
  primeiro pedido → interruptor desligado; (b) bloquear as notificações do app no Android → interruptor
  desligado com o texto de bloqueio; (c) com dois servidores, fazer login num terceiro → a notificação fixa
  continua e um pedido de aprovação criado em seguida avisa.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'EXTRA_RESTART' android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt` → presente em `start` e `onStartCommand`
- [ ] `grep -n 'areNotificationsEnabled' android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt` → 1 ocorrência
- [ ] Em `ZaplaudApp.kt`, a chamada de início dos avisos na abertura está fora do `try` que grava `storage_error`
- [ ] `grep -n 'settings_notifications_blocked' android/shared/src/commonMain/composeResources/values/strings.xml` → 1 linha
- [ ] Comando de host completo e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] `CHANGELOG.md` atualizado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 096 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `MainActivity` não tiver como lançar corrotina em `onStart` sem dependência nova (por exemplo, sem
  `lifecycleScope` disponível): relate; não acrescente biblioteca.
- A mudança em `NotificationPlatform` quebrar a compilação do iOS e não houver Mac: use só parâmetros com valor
  padrão no fim da lista; se ainda assim não for possível garantir, pare.

## Notas de manutenção

- Qualquer chamador novo que mude a lista de servidores deve usar `restart`, senão a vigilância continua com a
  lista antiga.
- Depois de reiniciar o celular os avisos só voltam quando o app é aberto; isso continua assim e deve ser dito
  ao usuário (plano 102, documentação) ou resolvido por decisão de produto (plano 113).
- O revisor deve conferir no aparelho que a notificação fixa aparece uma vez só depois de vários `start`.
