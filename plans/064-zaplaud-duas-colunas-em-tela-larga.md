# Plano 064: em tela larga, lista de sessões à esquerda e conversa à direita (estilo Telegram Desktop)

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação antes do próximo. Em condição de
> PARADA, pare e relate; não improvise. Ao terminar, atualize a linha 064 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 694938f..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/composeResources/values/strings.xml`.
> Se `ReadingScreen.kt` mudou, compare os trechos de "Estado atual" pelo conteúdo (os números de linha podem ter
> andado); se a estrutura `if (selected == null) { lista } else { conversa }` dentro de um único `Scaffold` não existir
> mais, PARE.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (tela principal do celular; regressão de layout no telefone é o risco)
- **Depende de**: `063` (para verificar no desktop e rodar o teste de interface na JVM)
- **Categoria**: direction (cliente desktop e tablet)
- **Planejado em**: commit `694938f`, 2026-11-09

## Por que importa

O dono quer o Zaplaud desktop com interface parecida com o Telegram Desktop: lista de conversas fixa à esquerda e a
conversa aberta à direita. Hoje o Zaplaud mostra uma coisa ou outra (padrão de celular), o que desperdiça uma janela de
computador ou um iPad deitado. Decisão do dono (2026-11-09): o layout em duas colunas vale **em toda tela larga**
(desktop, iPad, tablet Android); o celular continua com uma coluna, exatamente como hoje. Como a tela é código comum,
uma mudança atende as três plataformas e mantém os apps visualmente iguais.

## Estado atual

Arquivo: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (1.003 linhas em `694938f`).

- `ReadingScreen(...)` (`:98`) cria um `ReadingController` por servidor e junta os estados. O estado mostrado é a
  conversa aberta **ou** a lista combinada (`:135-150`):
  ```kotlin
  val selectedOrigin = states.entries.firstOrNull { it.value.selected != null }?.key
  val actionOrigin = selectedOrigin ?: filter ?: apis.keys.first()
  ...
  val state = if (selectedOrigin != null) states.getValue(selectedOrigin) else states.getValue(actionOrigin).copy(
      sessions = mergeSessions(states, filter),
      unread = states.values.flatMap { it.unread.entries }.associate { it.toPair() },
      connection = combinedConnection,
      terminalAllowed = states.values.any { it.terminalAllowed },
      error = null
  )
  ```
- O controller continua atualizando `sessions` enquanto uma conversa está aberta (`data/ReadingController.kt:112-118`)
  e já exclui a conversa aberta das não lidas (`:154`). Portanto a lista pode ficar viva ao lado da conversa sem mudar
  o controller.
- Tudo fica dentro de `CastActions(...) { castActions, castFab, castStatus -> ... }` (`:200-245`) e de **um** `Scaffold`
  (`:249`):
  - `floatingActionButton`: `castFab()` quando `selected != null || filter != null || apis.size == 1`;
  - `topBar` (`:250-289`): com conversa, avatar + título + subtítulo + `castActions()` e seta de voltar
    (`controller::close`); sem conversa, nome do app + ponto de conexão + menu (Configurações, Adicionar servidor);
  - conteúdo (`:291-596`): `castStatus()`, depois `if (selected == null) { ...lista: logins pendentes, erros, filtro
    por servidor, "Abertas/Recentes", busca oculta, LazyColumn de sessões (:294-407)... } else { ...conversa: avisos,
    LazyColumn invertida, linha de estado, compositor (:408-595)... }`.
- Linha da sessão na lista (`:383`): `Row(Modifier.fillMaxWidth().testTag("session-row:${provider}:${sessionId}").clickable { open(session) }...)`.
- Voltar: `PlatformBackHandler(enabled = selected != null && permissionSession == null && !menuExpanded) { controller.close() }` (`:196`).
- Busca oculta, revelada por "puxada longa" para baixo no topo da lista (`:354-369`, decisão do dono no plano 059:
  "busca oculta, revelada só com esforço e fixa depois de aberta").
- Bate-papo paralelo abre por puxada longa para cima no fim da conversa (`:447-466`) e pelo menu (`testTag("aside-menu")`).
- Tags usadas pelos testes instrumentados (não podem sumir nem duplicar no celular): `session-row:*`,
  `conversation-list`, `message-input`, `message-send`, `session-search`, `server-filter:*`, `relogin:*`,
  `add-server`, `aside-menu`, `message-status-*`. Testes em
  `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt` e
  `android/shared/src/androidDeviceTest/`.
- Tema em `ZaplaudApp.kt:39-59` (verde sálvia: primária clara `#176B58`, escura `#9CDCC6`). Use só cores de
  `MaterialTheme.colorScheme`; nada de cor fixa nova.

## Especificação visual (estilo Telegram, com tokens do Zaplaud)

- **Largura de corte**: `maxWidth >= 840.dp` (classe "expandida" do Material) → duas colunas. Abaixo disso, a tela
  atual, sem nenhuma diferença.
- **Coluna esquerda**: largura fixa `360.dp`; fundo `colorScheme.background`; topo com a barra atual da lista (nome
  do app, ponto de conexão, menu); conteúdo atual da lista; `castFab()` no canto inferior direito **desta coluna**
  (como o lápis de "nova mensagem" do Telegram).
- **Linha selecionada**: a sessão aberta tem fundo `colorScheme.secondaryContainer` com cantos `12.dp`.
- **Divisória**: `VerticalDivider(color = colorScheme.outlineVariant)`.
- **Coluna direita sem conversa**: centro com o texto novo `select_conversation` = "Selecione uma conversa" num
  `Surface(shape = CircleShape, color = colorScheme.surfaceContainerHigh)` com `padding(horizontal = 16.dp, vertical = 8.dp)`
  (o Telegram mostra "Select a chat to start messaging" numa pílula).
- **Coluna direita com conversa**: barra superior da conversa atual **sem a seta de voltar**; conteúdo atual da
  conversa com largura máxima de `840.dp`, centralizado (`Modifier.widthIn(max = 840.dp)` dentro de um `Box` com
  `contentAlignment = Alignment.TopCenter`), para as linhas não esticarem em monitor largo.
- **Esc / voltar** em tela larga: fecha a conversa (a direita volta para "Selecione uma conversa"). Mesmo handler de hoje.
- **Busca em tela larga**: continua oculta (decisão do dono). Além da puxada, **Ctrl+F** (Cmd+F no macOS) abre a
  busca quando o foco está na janela. Não acrescente ícone de busca.
- **Enter envia** no desktop: no campo de mensagem, `Enter` sem `Shift` envia (mesma ação e mesmas condições do botão
  `message-send`); `Shift+Enter` quebra linha. Só onde `platformSendsOnEnter()` for `true` (desktop). Celular e iPad
  continuam com Enter = nova linha.

## Comandos que você vai usar

Rode de `android/`. Parta do branch de integração (`producao`) **depois** que o 063 estiver mesclado nele:
`git worktree add /tmp/habblaud-064 -b advisor/064-zaplaud-duas-colunas producao`. Se
`/tmp/habblaud-064/android/shared/src/jvmMain` não existir, PARE: o 063 ainda não foi integrado.

| Propósito | Comando | Esperado |
|---|---|---|
| Testes host | `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest` | exit 0 (ressalva conhecida: `ConversationStreamTest`) |
| Lint/APK | `./gradlew :app:lintDebug :app:assembleDebug` | exit 0 |
| Testes JVM | `./gradlew :shared:jvmTest` | exit 0 |
| Desktop | `./gradlew :desktop:run` | janela abre |
| Instrumentados (emulador `habblaud034`) | `./gradlew :app:connectedDebugAndroidTest :shared:connectedAndroidDeviceTest` (como em `scripts/android-instrumented-ci.py`) | mesmas falhas conhecidas do `main` e nenhuma nova |
| Capturas no celular | `bash scripts/android-screens.sh` | mesmo resultado do `main` (o plano 054 está BLOCKED em "Retomar conversa"; compare até esse ponto) |
| Servidor de teste | `node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions` (raiz do repo) | linha JSON com `"ready":true` |

## Escopo

**Dentro do escopo**:
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt` (só dois `expect` novos: `platformSendsOnEnter()` e `PlatformSearchShortcut`)
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/UiPlatform.android.kt` (`actual`s: `false` e vazio)
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt` (`actual`s: `false` e vazio)
- `android/shared/src/jvmMain/kotlin/br/com/xlinx/habblaud/UiPlatform.jvm.kt` (`actual`s: `true` e registro do atalho)
- `android/desktop/src/main/kotlin/br/com/xlinx/habblaud/desktop/Main.kt` (só o `onKeyEvent` da janela)
- `android/shared/src/commonMain/composeResources/values/strings.xml` (só `select_conversation`)
- `android/shared/src/jvmTest/kotlin/br/com/xlinx/habblaud/TwoPaneWideUiTest.kt` e `TwoPaneNarrowUiTest.kt` (criar)
- `CHANGELOG.md`

**Fora do escopo**:
- `ReadingController`, `MessageController`, repositórios e API: a lista já fica viva com a conversa aberta.
- Folhas inferiores (`PermissionSheet`, `AsideSheet`, `SessionInfoSheet`) e `SettingsScreen`: continuam como estão
  (em tela larga o Material já limita a largura da folha). Painel lateral para o bate-papo paralelo fica para depois.
- Redimensionar a coluna arrastando a divisória, arrastar arquivos para a janela, colar imagem: não pedidos.
- Qualquer mudança visual no celular (largura < 840 dp).

## Git

Branch `advisor/064-zaplaud-duas-colunas`. Commits no estilo `feat(zaplaud): lista e conversa lado a lado em tela larga`.
Sem push/merge/PR sem ordem do operador.

## Passos

### Passo 1: separar os dois estados sem mudar o comportamento

Em `ReadingScreen`, troque a definição de `state` pelos valores abaixo e mantenha `state` igual ao de hoje. Não use o
nome `listState`: ele já existe na conversa (`:418`).
```kotlin
fun mergedFrom(base: String) = states.getValue(base).copy(
    sessions = mergeSessions(states, filter),
    unread = states.values.flatMap { it.unread.entries }.associate { it.toPair() },
    connection = combinedConnection,
    terminalAllowed = states.values.any { it.terminalAllowed },
    error = null
)
val conversationState = selectedOrigin?.let { states.getValue(it) }
val state = conversationState ?: mergedFrom(actionOrigin)          // telefone: idêntico ao de hoje
val sessionsStateWide = mergedFrom(filter ?: apis.keys.first())   // tela larga: a lista não depende da conversa aberta
```

**Verificar**: `./gradlew :shared:compileKotlinJvm :shared:testAndroidHostTest` → exit 0.

### Passo 2: extrair o conteúdo e as barras em lambdas locais

Sem mover código para outros arquivos, transforme os dois ramos em lambdas `@Composable` locais dentro do bloco
`CastActions { castActions, castFab, castStatus -> ... }`, capturando as variáveis que já existem. Cada lambda recebe
o estado que deve mostrar e **não** decide nada por `selected` além do que está indicado:
```kotlin
// Barra da lista: título = nome do app, ponto de conexão de s.connection, menu (Configurações, Adicionar servidor).
// Sem nenhum teste de `selected` (copie só o ramo selected == null de :251-289).
val listTopBar: @Composable (s: ReadingState) -> Unit = { s -> TopAppBar(...) }
// Barra da conversa: avatar, título, subtítulo, castActions(); seta de voltar só se showBack (ramo selected != null).
val conversationTopBar: @Composable (sel: SessionItem, showBack: Boolean) -> Unit = { sel, showBack -> TopAppBar(...) }
// Corpo da lista: :295-407, com `s` no lugar de `state`.
val listContent: @Composable ColumnScope.(s: ReadingState, highlight: Boolean) -> Unit = { s, highlight -> ... }
// Corpo da conversa: a linha de erro de :292 (`s.error?.let { ... }`) no topo, depois :409-595 com `s` e `sel`.
val conversationContent: @Composable ColumnScope.(sel: SessionItem, s: ReadingState) -> Unit = { sel, s -> ... }
```
No ramo estreito (largura < 840 dp), o `Scaffold` atual passa a usar
`topBar = { if (selected == null) listTopBar(state) else conversationTopBar(selected, true) }` e, no conteúdo,
`castStatus()` seguido de `if (selected == null) { state.error?.let { ... }; listContent(state, false) } else conversationContent(selected, state)`.
O resultado no telefone deve ser idêntico (a linha de erro de `:292` continua aparecendo nos dois casos).

**Verificar**: `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug` → exit 0; depois os
instrumentados no emulador → mesmas falhas conhecidas do `main` e nenhuma nova.

### Passo 3: ramo de tela larga

Envolva a raiz com `BoxWithConstraints`. Se `maxWidth >= 840.dp`:
```kotlin
Row(Modifier.fillMaxSize()) {
    Scaffold(Modifier.width(360.dp).fillMaxHeight(), topBar = { listTopBar(sessionsStateWide) },
        floatingActionButton = { if (selected != null || filter != null || apis.size == 1) castFab() }) { p ->
        Column(Modifier.fillMaxSize().padding(p).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            castStatus(); listContent(sessionsStateWide, true)
        }
    }
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (selected == null) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { /* pílula select_conversation */ }
    else Scaffold(Modifier.weight(1f).fillMaxHeight(), topBar = { conversationTopBar(selected, false) }) { p ->
        Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().imePadding()/* mesmo fundo e padding do ramo estreito */) {
                conversationContent(selected, conversationState!!)
            }
        }
    }
}
```
Na linha da sessão (`listContent`), quando `highlight && selected?.key == session.key`, aplique
`Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))` antes do `clickable`.

**Verificar**: `./gradlew :desktop:run` com o servidor de teste; janela 1100×760 → duas colunas; abrir sessão → lista
continua à esquerda com a linha destacada; Esc → direita volta para "Selecione uma conversa"; reduzir a janela abaixo
de ~840 dp → volta ao layout de uma coluna sem perder a conversa aberta.

### Passo 4: Ctrl+F e Enter envia

- Ctrl+F/Cmd+F: o atalho precisa funcionar mesmo sem nada focado, então vem da janela, como o Esc do 063.
  Em `UiPlatform.kt`: `@Composable expect fun PlatformSearchShortcut(enabled: Boolean, onSearch: () -> Unit)`;
  `actual` vazio em Android e iOS. Na JVM, copie o padrão de `PlatformBackHandler`/`dispatchDesktopBack()` do
  `UiPlatform.jvm.kt` (pilha de entradas + `fun dispatchDesktopSearch(): Boolean`). Em `Main.kt`, no `onKeyEvent` da
  janela: `KeyDown` de `Key.F` com `isCtrlPressed || isMetaPressed` → `dispatchDesktopSearch()`. Em `ReadingScreen`,
  só no ramo largo: `PlatformSearchShortcut(enabled = true) { searchOpen = true }` (o campo já pede foco ao abrir, `:337`).
- `expect fun platformSendsOnEnter(): Boolean` em `UiPlatform.kt`; `actual` `false` em Android e iOS, `true` na JVM.
- No `TextField` do compositor (`testTag("message-input")`), `Modifier.onPreviewKeyEvent`: se `platformSendsOnEnter()`,
  `KeyDown` de `Key.Enter` **sem** `isShiftPressed` e com o botão de envio habilitado → mesma ação de `message-send`
  (`stickToEnd(); controller.messages.send(agentId)`), retorna `true`. Com envio desabilitado, retorna `true` e não faz
  nada (não insere quebra de linha).

**Verificar**: `./gradlew :shared:compileKotlinJvm :app:assembleDebug` → exit 0; no desktop, Enter envia e
Shift+Enter quebra linha; Ctrl+F abre a busca com foco.

### Passo 5: teste de interface na JVM

Crie `jvmTest/.../TwoPaneWideUiTest.kt` e `TwoPaneNarrowUiTest.kt` (ver "Plano de testes").

**Verificar**: `./gradlew :shared:jvmTest --tests '*TwoPane*'` → as duas classes passam.

### Passo 6: tablet Android

No emulador `habblaud034`: `adb reverse tcp:14748 tcp:14748` com o servidor de teste rodando no host (porta 14748),
depois `adb shell wm size 2560x1600 && adb shell wm density 240` (≈ 1707 dp de largura). Abra o app, faça login em
`http://127.0.0.1:14748` (`android-test`/`android-test`, confirmando o aviso de HTTP), abra uma sessão e capture
`adb exec-out screencap -p > /tmp/zaplaud-064-tablet.png`. Depois `adb shell wm size reset && adb shell wm density reset`
e `adb reverse --remove tcp:14748`. (O mesmo roteiro está em `scripts/android-screens.sh:92,269-273`.)

**Verificar**: a captura mostra as duas colunas; `wm size reset` executado (confirme com `adb shell wm size` →
só "Physical size").

## Plano de testes

Duas classes em `android/shared/src/jvmTest/kotlin/br/com/xlinx/habblaud/`: `TwoPaneWideUiTest` (porta 14760) e
`TwoPaneNarrowUiTest` (porta 14761). O `jvmTest` já roda com `forkEvery = 1`, `zaplaud.keyring=memory` e
`zaplaud.dataDir` (plano 063), então cada classe começa sem login. Anote as classes com
`@OptIn(ExperimentalTestApi::class)`.
- `@BeforeClass`: `Assume.assumeTrue("node ausente", runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))`;
  inicie o servidor de teste com
  `ProcessBuilder("node", "--import", "tsx", "scripts/android-smoke.ts", "--port", "<porta>", "--allow", "terminal,messages,permissions")`
  com diretório de trabalho na raiz do repositório (`File("../..")` a partir de `android/shared`; confirme com
  `File("../../package.json").exists()`), leia o stdout até a linha com `"ready":true` (tempo limite 60 s). `@AfterClass`
  encerra o processo (`destroy()` e `waitFor`).
- Conteúdo de cada teste:
  ```kotlin
  CompositionLocalProvider(LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, r -> r(false) })) { ZaplaudApp() }
  ```
- Use `runDesktopComposeUiTest(width = 1280, height = 800)` (largo) e `(400, 800)` (estreito), de
  `androidx.compose.ui.test`. Se essa função não existir nesta versão, use `runComposeUiTest` com o conteúdo dentro de
  `Box(Modifier.requiredSize(<largura>.dp, 800.dp))`.
- Largo: login pela interface (`server-url` com `http://127.0.0.1:14760`, `username`, `password`, `login-submit`,
  `http-confirm`), `waitUntil` até existir um nó com tag que começa por `session-row:`; clique nele; afirme que existem
  ao mesmo tempo um `session-row:*` e `conversation-list`. Depois `runOnIdle { dispatchDesktopBack() }` e afirme que
  `conversation-list` sumiu e o texto "Selecione uma conversa" aparece.
- Estreito: mesmo login (porta 14761); abra a sessão; afirme que `conversation-list` existe e **nenhum** `session-row:*`
  existe (tela única, como no celular).

## Critérios de pronto

- [ ] `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :shared:jvmTest` → exit 0 (ressalva conhecida: `ConversationStreamTest`).
- [ ] `TwoPaneWideUiTest` e `TwoPaneNarrowUiTest` passam.
- [ ] Instrumentados no emulador sem falha nova em relação ao `main`.
- [ ] `scripts/android-screens.sh` gera as mesmas capturas que no `main` até o ponto em que o `main` para; compare
      `lista-*` e `conversa-*` visualmente e registre na linha 064 que o celular não mudou.
- [ ] Captura de tablet `/tmp/zaplaud-064-tablet.png` com duas colunas, citada na linha 064.
- [ ] `git status` só mostra arquivos do escopo; `select_conversation` existe em `strings.xml`.
- [ ] Entrada `**Zaplaud:**` em "Não lançado" no `CHANGELOG.md`: lista e conversa lado a lado em telas largas
      (desktop, iPad, tablet), Esc fecha a conversa, Ctrl+F busca, Enter envia no desktop.

## Condições de PARADA

- Qualquer teste instrumentado que passa no `main` falha depois do passo 2 (a extração mudou o celular).
- Uma lambda extraída precisa de estado que só existe dentro do outro ramo (dependência cruzada entre lista e
  conversa que não seja `selected`, `open()`, `permissionSession`, `controller`, `scope` ou `store`).
- Em tela larga, abrir uma conversa faz a lista perder a posição de rolagem ou piscar ao receber snapshot (sinal de
  que `sessionsList`/`sessionsTop` estão sendo recriados). Relate com captura.
- `runDesktopComposeUiTest`/`runComposeUiTest` não funcionam sem tela e o CI não tem `xvfb`: relate; não remova o teste.

## Notas de manutenção

- Daqui em diante, qualquer mudança na lista ou na conversa precisa ser conferida nas duas larguras. Para isso,
  `TwoPaneWideUiTest` e `TwoPaneNarrowUiTest` precisam continuar passando.
- O destaque da linha e a pílula "Selecione uma conversa" são as únicas peças novas de visual; o resto reaproveita a
  tela do celular. Se o dono quiser colunas redimensionáveis, troque o `width(360.dp)` por um estado arrastável.
- Bate-papo paralelo, aprovação e informações da sessão continuam como folhas inferiores. Em tela larga, um painel à
  direita seria mais próximo do Telegram; fica como melhoria futura, se o dono pedir.
