# Plano 059: busca de sessões oculta, revelada por puxada longa, fixa no topo e com estado vazio útil

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 059 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/composeResources/values/strings.xml android/app/src/main/res/values/strings.xml android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt scripts/android-screens.sh README.pt-BR.md`.
> Planos 054–058 mudam alguns destes arquivos; isso é esperado. Compare os trechos abaixo pelo conteúdo;
> divergência na lógica da lista ou da busca é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (gesto novo e mudança na estrutura da lista; afeta testes e o roteiro de capturas)
- **Depende de**: `055` (ícones), `057` (segmentado, mesma região do código), `054` (roteiro de capturas)
- **Categoria**: direction (modernização visual e comportamento pedido pelo dono)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

Decisão do dono em 2026-11-08: a busca da lista deve ficar **oculta**, aparecer **só com esforço** e, depois de
aparecer, ficar **fixa no topo** enquanto a lista rola. Hoje a busca é o primeiro item da lista, escondido acima da
tela: qualquer rolagem até o topo a revela sem intenção e, ao rolar para baixo, ela some junto com a lista. Ela
também é um campo retangular com contorno, diferente do campo de mensagem arredondado. Além disso, quando a busca
não encontra nada, o app mostra a mesma frase de lista vazia ("Nenhuma sessão encontrada.") e não oferece como
voltar; a pessoa precisa apagar o texto à mão.

Resultado esperado:

1. Ao abrir a lista, a busca não aparece.
2. No topo da lista, uma puxada curta para baixo não revela a busca; uma puxada longa (120 dp acumulados) revela e
   dá um toque háptico.
3. A busca aparece como uma pílula preenchida acima da lista, fora dela: rolar a lista não a move.
4. O X da busca e o botão Voltar limpam o texto e escondem a busca.
5. Sem resultados para um texto digitado: "Nenhuma sessão corresponde à busca." e o botão "Limpar busca".

## Estado atual

Arquivo: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (números de `dab677d`).

- Estado da lista, `:139-145`:

  ```kotlin
  // Estado da lista de conversas fora do `if`: voltar de uma conversa mantém a aba, a busca e a posição.
  var query by remember { mutableStateOf("") }
  var recent by remember { mutableStateOf(false) }
  // A busca é o primeiro item e começa fora da tela: aparece ao puxar a lista para baixo no topo.
  val sessionsList = rememberLazyListState(initialFirstVisibleItemIndex = 1)
  /** Conversa que estava no topo na última composição (sem ser estado: mudar não recompõe). */
  val sessionsTop = remember { arrayOfNulls<String>(1) }
  ```

- Voltar da conversa, `:166`: `PlatformBackHandler(enabled = selected != null && permissionSession == null && !menuExpanded) { controller.close() }`.
- Troca de aba, `:280-281` (o plano 057 troca só o componente das abas, não esta função):

  ```kotlin
  // Trocar de aba volta ao topo, com a busca escondida.
  fun showTab(value: Boolean) { recent = value; if (query.isEmpty()) scope.launch { withFrameNanos { }; sessionsList.scrollToItem(1) } }
  ```

- Filtro e manutenção do topo, `:288-302`:

  ```kotlin
  val items = byLastMessage(state.sessions.filter { (recent || it.agentId != null) && listOfNotNull(it.title, ...).any { value -> value.contains(query, true) } })
  val top = items.firstOrNull()?.key?.serialize()
  if (top != sessionsTop[0]) {
      if (sessionsTop[0] != null && sessionsList.firstVisibleItemIndex == 1) sessionsList.requestScrollToItem(1, sessionsList.firstVisibleItemScrollOffset)
      sessionsTop[0] = top
  }
  val density = androidx.compose.ui.platform.LocalDensity.current
  // Lista curta não rola: um espaço no fim completa a altura para a busca caber acima da tela.
  val fillerPx by remember { derivedStateOf { ... } }
  ```

- Lista, `:303-305` e `:332`:

  ```kotlin
  LazyColumn(state = sessionsList, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item(key = "search") { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.search_sessions)) }, singleLine = true) }
      if (items.isEmpty()) item(key = "empty") { Text(stringResource(Res.string.sessions_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge) }
      ...
      item(key = "filler") { Spacer(Modifier.height(with(density) { fillerPx.toDp() })) }
  }
  ```

- Strings existem em **dois** arquivos que devem ficar iguais: `android/shared/src/commonMain/composeResources/values/strings.xml`
  (usado pelo app, `Res.string.*`) e `android/app/src/main/res/values/strings.xml` (usado pelos testes instrumentados,
  `R.string.*`). Hoje: `search_sessions` = "Buscar por título, projeto ou agente", `sessions_empty` = "Nenhuma sessão encontrada.".
- Teste que usa a busca, `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt:110-114`:

  ```kotlin
  compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.search_sessions)))
  compose.onNodeWithText(text(R.string.search_sessions)).performTextInput("Ana Fictícia")
  android.os.ParcelFileDescriptor.AutoCloseInputStream(
      InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
  ).use { it.readBytes() }
  ```

- Roteiro de capturas, `scripts/android-screens.sh` (números de `dab677d`): `:277-282` digita
  "busca-impossivel", espera `'Nenhuma sessão encontrada.'`, captura `busca-vazia` e apaga o texto com 20 teclas
  DEL; `:445` e `:458` usam `enter_text 'Buscar por título, projeto ou agente' <texto>` depois de tocar Recentes.
- Documentação do app: `README.pt-BR.md`, seção do Android (parágrafo que começa em "O aplicativo acompanha o tema
  claro ou escuro do sistema", perto da linha 253). Os READMEs em inglês e francês não descrevem o app.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas (a falha conhecida `imeDraftSurvivesRecreationRotationAndLargeFont` pode ser registrada e ignorada) |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |
| Strings iguais | `diff <(grep -E 'search_\|sessions_empty' android/shared/src/commonMain/composeResources/values/strings.xml) <(grep -E 'search_\|sessions_empty' android/app/src/main/res/values/strings.xml)` | sem saída |

No terminal, digite o `diff` sem as barras invertidas da tabela (`'search_|sessions_empty'`).

Abrir o emulador: `$HOME/Android/Sdk/emulator/emulator -avd habblaud034 -no-window -no-audio -no-snapshot-save -no-boot-anim &`.

## Escopo

**Pode modificar**: `ReadingScreen.kt` (estado da lista, `showTab`, bloco da lista, novo `PlatformBackHandler`),
os dois `strings.xml`, `FixtureUiInstrumentedTest.kt`, `scripts/android-screens.sh`, `README.pt-BR.md` (uma frase) e
`CHANGELOG.md`.

**Fora**: filtro em si (campos buscados, `contains(query, true)`), ordem `byLastMessage`, chips de servidor, botão
segmentado, conversa aberta, barra superior (o plano 062 mexe nela depois), `SettingsScreen`. Não use o componente
`SearchBar` do Material 3: ele abre uma tela de busca expandida que conflita com a busca fixa dentro da lista.

## Git

- Branch: `advisor/059-zaplaud-busca-oculta-fixa`.
- Commits sugeridos: `test(android): busca revelada por puxada longa e fixa` (teste primeiro, falhando) e
  `feat(android): busca oculta, revelada por puxada longa e fixa no topo`.
- Não faça push. Não instale no M35 sem autorização; nunca `pm clear` no M35.

## Passos

### Passo 1: strings novas nos dois arquivos

Acrescente, logo após `search_sessions`, nos dois `strings.xml`:

```xml
<string name="search_close">Fechar busca</string>
<string name="search_clear">Limpar busca</string>
<string name="search_no_match">Nenhuma sessão corresponde à busca.</string>
```

**Verificar**: o `diff` "Strings iguais" não imprime nada; Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: escrever os testes instrumentados (devem falhar agora)

Em `FixtureUiInstrumentedTest.kt`, acrescente dois testes no padrão de
`mergedSessionsShowBothOriginsAndServerChipsFilterThem` (mesmo arquivo: `ActivityScenario.launch`, `await(...)`,
`compose.waitUntil`). Use a tag `session-search` no campo e a descrição `R.string.search_close` no X.

1. `searchRevealsOnlyAfterLongPullAndStaysPinned`:
   - abre a lista (`await(R.string.open_sessions)`); `onAllNodesWithTag("session-search")` tem 0 nós;
   - puxada curta: `compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeDown(startY = top + 20f, endY = top + 20f + 40.dp.toPx(), durationMillis = 400) }` → ainda 0 nós;
   - puxada longa: `swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600)` → 1 nó exibido;
   - `swipeUp` na lista → o campo continua exibido (`assertIsDisplayed`);
   - clique em `onNodeWithContentDescription(text(R.string.search_close))` → 0 nós.
2. `searchWithoutMatchesOffersClear`:
   - revela a busca com a puxada longa, digita `busca-impossivel` em `onNodeWithTag("session-search")`;
   - `onNodeWithText(text(R.string.search_no_match)).assertIsDisplayed()` e `R.string.sessions_empty` não existe;
   - clique em `text(R.string.search_clear)` → linhas de sessão voltam (`onAllNodesWithTag(...)` com prefixo
     `session-row:` não vazio; use `compose.onAllNodes(SemanticsMatcher("row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-row:") == true })`).

Atualize também as linhas 110-111 do teste existente: troque a rolagem até o texto pela puxada longa e digite em
`onNodeWithTag("session-search")`.

**Verificar**: instrumentados → os dois testes novos e o teste atualizado **falham** (a tag ainda não existe).
Registre a falha no commit de teste.

### Passo 3: tirar a busca da lista

No estado da lista (`:139-145`):

- `val sessionsList = rememberLazyListState()` (sem índice inicial);
- `var searchOpen by remember { mutableStateOf(false) }` ao lado de `query`;
- atualize os dois comentários: a busca fica acima da lista, oculta até a puxada longa.

No bloco da lista:

- apague `item(key = "search")`, `item(key = "filler")`, `fillerPx` e `density` (só existiam para esconder a busca
  acima da tela);
- na manutenção do topo, troque `firstVisibleItemIndex == 1` e `requestScrollToItem(1, ...)` por índice `0`;
- `showTab`: `recent = value; if (query.isEmpty()) { searchOpen = false; scope.launch { withFrameNanos { }; sessionsList.scrollToItem(0) } }`.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`;
`grep -n -E 'key = "(search|filler)"|fillerPx|scrollToItem\(1' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` → nenhuma linha.

### Passo 4: campo em pílula acima da lista

Logo antes do `LazyColumn`, quando `searchOpen`:

```kotlin
if (searchOpen) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    TextField(
        query, { query = it },
        Modifier.fillMaxWidth().focusRequester(focus).testTag("session-search"),
        placeholder = { Text(stringResource(Res.string.search_sessions)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            val closeLabel = stringResource(Res.string.search_close)
            IconButton(onClick = { query = ""; searchOpen = false }, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = pillFieldColors(),
    )
}
```

E, no fim do arquivo, uma função reutilizável pelo plano 061:

```kotlin
/** Campo em pílula preenchida, sem linha de foco: busca e (plano 061) mensagem. */
@Composable
internal fun pillFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
```

Se `query` não estiver vazio quando a tela for recomposta (por exemplo, ao voltar de uma conversa), `searchOpen` deve
continuar `true`; como os dois ficam fora do `if (selected == null)`, isso já acontece.

Voltar: perto do `PlatformBackHandler` existente (`:166`), acrescente
`PlatformBackHandler(enabled = selected == null && searchOpen) { query = ""; searchOpen = false }`.

Imports novos: `androidx.compose.ui.focus.FocusRequester`, `androidx.compose.ui.focus.focusRequester`,
`androidx.compose.material.icons.filled.Search` (e `Close`, se o plano 055 ainda não importou).

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 5: revelar com puxada longa

Acrescente ao `LazyColumn` um `Modifier.nestedScroll(reveal)`, com a conexão criada antes dele:

```kotlin
val revealPx = with(LocalDensity.current) { SEARCH_REVEAL_PULL.toPx() }
val haptics = LocalHapticFeedback.current
val reveal = remember(revealPx) {
    object : NestedScrollConnection {
        var pulled = 0f
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Só sobra rolagem para baixo quando a lista já está no topo: é a "puxada" além do começo.
            if (source == NestedScrollSource.UserInput && available.y > 0f && !searchOpen) {
                pulled += available.y
                if (pulled >= revealPx) { pulled = 0f; searchOpen = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            } else if (available.y < 0f || consumed.y != 0f) pulled = 0f
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
    }
}
```

E a constante, junto das outras do arquivo (perto de `REPLIES_SHOWN`):

```kotlin
/** Quanto puxar além do topo da lista para revelar a busca: grande o bastante para não acontecer por acidente. */
private val SEARCH_REVEAL_PULL = 120.dp
```

`onPreFling` zera o acumulado quando o dedo solta: só uma puxada contínua conta. Imports: `NestedScrollConnection`,
`NestedScrollSource`, `nestedScroll` (`androidx.compose.ui.input.nestedscroll.*`), `Offset`
(`androidx.compose.ui.geometry.Offset`), `Velocity` (`androidx.compose.ui.unit.Velocity`), `LocalHapticFeedback`
(`androidx.compose.ui.platform`), `HapticFeedbackType` (`androidx.compose.ui.hapticfeedback`).

**Verificar**: instrumentados → `searchRevealsOnlyAfterLongPullAndStaysPinned` passa.

### Passo 6: estado vazio com ação

Troque o item `"empty"` por:

```kotlin
if (items.isEmpty()) item(key = "empty") {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (query.isNotBlank()) {
            Text(stringResource(Res.string.search_no_match), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { query = "" }) { Text(stringResource(Res.string.search_clear)) }
        } else {
            Text(stringResource(Res.string.sessions_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

"Limpar busca" apaga o texto e mantém a busca aberta, com o foco onde estava.

**Verificar**: instrumentados → `searchWithoutMatchesOffersClear` e o teste atualizado passam; suíte sem falhas
novas.

### Passo 7: roteiro de capturas

Em `scripts/android-screens.sh`:

1. Crie `open_search() { adb shell input swipe 540 700 540 1800 600; wait_text 'Fechar busca'; }`, junto das outras
   funções.
2. Linhas da busca vazia (`:277-282` em `dab677d`): chame `open_search`, digite `busca-impossivel` no campo
   (`tap 'Buscar por título, projeto ou agente'; adb shell input text busca-impossivel; adb shell input keyevent 4`),
   espere `'Nenhuma sessão corresponde à busca.'`, capture `busca-vazia`, toque `'Limpar busca'`, capture
   `busca-aberta` e toque `'Fechar busca'`. Apague o laço de 20 teclas DEL.
3. Antes de cada `enter_text 'Buscar por título, projeto ou agente' ...` (`:445` e `:458`): se a busca já estiver
   aberta com texto de uma etapa anterior, toque `'Fechar busca'`; depois chame `open_search`.

**Verificar**: `bash scripts/android-screens.sh` com exit 0; existem `busca-vazia-*.png` e `busca-aberta-*.png` nos dois
temas.

### Passo 8: documentação e changelog

- `README.pt-BR.md`, no parágrafo do aplicativo citado em "Estado atual", acrescente: "Para buscar sessões, puxe a
  lista para baixo com firmeza estando no topo. A busca fica fixa acima da lista até ser fechada pelo X ou pelo
  Voltar."
- `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
  `- **Zaplaud:** a busca da lista fica oculta, aparece com uma puxada longa no topo e permanece fixa enquanto a lista rola. Sem resultados, o app oferece "Limpar busca".`

**Verificar**: `grep -n 'puxe a lista para baixo com firmeza' README.pt-BR.md` e `grep -n 'puxada longa' CHANGELOG.md`
retornam 1 linha cada.

## Plano de testes

- Novos: `searchRevealsOnlyAfterLongPullAndStaysPinned` e `searchWithoutMatchesOffersClear` (Passo 2). Cobrem o
  limiar (curta não revela, longa revela), a fixação, o fechamento e o estado vazio com ação.
- Atualizado: `mergedSessionsShowBothOriginsAndServerChipsFilterThem` (linhas 110-111).
- Não teste o valor de 120 dp nem a chamada háptica.

## Critérios de pronto

- [ ] Strings iguais nos dois arquivos.
- [ ] Grep do Passo 3 sem resultados.
- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados: os dois testes novos passam; nenhuma falha nova.
- [ ] Roteiro com exit 0 e capturas `busca-vazia` e `busca-aberta` nos dois temas.
- [ ] README e CHANGELOG atualizados.
- [ ] `git status --short` só com arquivos do escopo e `plans/README.md`.
- [ ] Linha 059 atualizada.

## Condições de PARADA

- Com a lista curta (todas as linhas cabem na tela), a puxada longa não chega ao `onPostScroll` com `available.y > 0`
  (teste falha só nesse caso). Não volte ao truque do espaço no fim da lista; relate.
- O `KEYCODE_BACK` do teste existente (linha 113) passa a fechar a busca em vez de só esconder o teclado. Relate
  antes de trocar por outra forma de fechar o teclado.
- `NestedScrollSource.UserInput` não existe na versão do Compose do projeto (versões antigas usam `Drag`). Use
  `Drag` só se `UserInput` não compilar e registre.
- A puxada também aciona algum outro gesto do sistema no emulador.

## Notas de manutenção

- Descoberta: sem ícone de busca, quem não conhece o gesto não acha a busca. Decisão do dono: esforço intencional.
  Uma dica de primeira utilização fica registrada como adiada no índice.
- O plano 062 (barra superior que encolhe) também usa a rolagem da lista; ele deve fazer a puxada contar só com a
  barra totalmente expandida.
- iPad: o gesto usa a mesma API comum; validar no Mac junto do próximo gate iOS.
