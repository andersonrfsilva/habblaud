# Plano 061: campo de mensagem com anexo e uso de contexto dentro da pílula

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 061 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt scripts/android-screens.sh`.
> Os planos 055, 058 e 059 mudam este arquivo; isso é esperado. Confirme que existem `pillFieldColors()` (do 059) e
> `warningColor()` (do 058, em `StatusColors.kt`) e compare o trecho do campo abaixo pelo conteúdo.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: `055` (ícone de anexo), `058` (`warningColor()`), `059` (`pillFieldColors()`)
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

Na base da conversa, o botão de anexo fica solto à esquerda do campo, e o uso de contexto é uma barrinha vertical de
4 × 40 dp entre os dois, sem rótulo visível. O campo usa contorno, enquanto a busca (plano 059) passa a ser uma pílula
preenchida. Mensageiros atuais põem o anexo dentro do campo e mostram indicadores pequenos dentro dele. Integrar os
dois elementos à pílula deixa a barra de digitação mais limpa e coerente com a busca, sem perder nenhuma informação.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:459-463` (em `dab677d`; o 055 já
  trocou o `Text("📎")` por `Icon(Icons.Default.AttachFile, ...)`):

  ```kotlin
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      val attachLabel = stringResource(Res.string.attach_files)
      IconButton(onClick = { pickerKey = selected.key; picker() }, enabled = !composer.loading && composer.draft.attachments.size < MAX_ATTACHMENTS, modifier = Modifier.size(32.dp).semantics { contentDescription = attachLabel }) { ... }
      if (selected.contextTokens != null && selected.contextWindow != null) ContextBar(selected.contextTokens, selected.contextWindow)
      OutlinedTextField(composer.draft.text, { controller.messages.edit(text = it) }, Modifier.weight(1f).testTag("message-input"), enabled = !composer.loading, label = { Text(stringResource(Res.string.message_text)) }, supportingText = if (composer.draft.text.length >= 18000) { { Text("${composer.draft.text.length}/20000") } } else null, maxLines = 6, shape = RoundedCornerShape(24.dp))
      ... botões parar e enviar ...
  }
  ```

- `ContextBar`, `ReadingScreen.kt:487-497`:

  ```kotlin
  /** Barrinha vertical do contexto em uso (tokens / janela do modelo), cheia de baixo para cima. */
  @Composable
  private fun ContextBar(tokens: Long, window: Long) {
      val fraction = (tokens.toFloat() / window).coerceIn(0f, 1f)
      val pct = (fraction * 100).toInt()
      val color = when { pct >= 80 -> MaterialTheme.colorScheme.error; pct >= 50 -> warningColor(); else -> MaterialTheme.colorScheme.primary }
      val label = stringResource(Res.string.context_usage, pct, tokens / 1000, window / 1000)
      Box(Modifier.width(4.dp).height(40.dp).background(...).semantics { contentDescription = label }, contentAlignment = Alignment.BottomCenter) { ... }
  }
  ```

- Testes e roteiro usam: `testTag("message-input")`, o rótulo "Mensagem" (`R.string.message_text`, inclusive
  `onNodeWithText(text(R.string.message_text)).assertTextContains(...)` em `FixtureUiInstrumentedTest.kt:253`) e a
  descrição "Anexar arquivos (até 5)" (`select_test_file` em `scripts/android-screens.sh`). Os três devem continuar
  existindo. Por isso o campo mantém `label`, não `placeholder`: o placeholder some quando há texto e quebraria a
  asserção da linha 253.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas (a falha conhecida `imeDraftSurvivesRecreationRotationAndLargeFont` pode ser registrada e ignorada) |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

## Escopo

**Pode modificar**: `ReadingScreen.kt` (linha do campo de mensagem e `ContextBar`) e `CHANGELOG.md`.

**Fora**: botões parar e enviar, banners e chips de anexo acima do campo, limites (20.000 caracteres,
`MAX_ATTACHMENTS`), seletor de arquivos, texto das strings, folha de informações da sessão.

## Git

- Branch: `advisor/061-zaplaud-campo-de-mensagem`.
- Mensagem: `feat(android): anexo e contexto dentro do campo de mensagem`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: anel de contexto no lugar da barrinha

Renomeie `ContextBar` para `ContextRing` e troque o `Box` por:

```kotlin
CircularProgressIndicator(
    progress = { fraction },
    modifier = Modifier.size(20.dp).semantics { contentDescription = label },
    color = color,
    trackColor = MaterialTheme.colorScheme.outlineVariant,
    strokeWidth = 2.5.dp,
)
```

Mantenha cálculo, cores e `label`. Atualize o KDoc para "Anel do contexto em uso (tokens / janela do modelo)".

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: pílula com anexo e anel dentro

No `Row` do campo:

1. Remova o `IconButton` de anexo e a chamada `ContextBar(...)` soltos.
2. Troque `OutlinedTextField` por `TextField`, mantendo valor, `onValueChange`, `Modifier.weight(1f).testTag("message-input")`,
   `enabled`, `label`, `supportingText` e `maxLines = 6`. Use `shape = RoundedCornerShape(24.dp)` e
   `colors = pillFieldColors()`.
3. `leadingIcon`: o mesmo `IconButton` de anexo (mesmo `onClick`, `enabled` e semântica `attachLabel`), sem o
   `Modifier.size(32.dp)`, com `Icon(Icons.Default.AttachFile, contentDescription = null)`.
4. `trailingIcon`: `if (selected.contextTokens != null && selected.contextWindow != null) { { ContextRing(selected.contextTokens, selected.contextWindow) } } else null`.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`;
`grep -n -E 'ContextBar|OutlinedTextField\(composer' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`
→ nenhuma linha.

### Passo 3: testes e capturas

Rode os instrumentados e o roteiro. Abra `conversa-fim-*`, `composer-teclado-*`, `anexos-selecionados-*` e
`omp-stop-*` nos dois temas.

**Verificar**: zero falhas novas; exit 0; o clipe aparece dentro da pílula à esquerda; nas sessões omp com contexto,
o anel aparece dentro da pílula à direita; o campo cresce até 6 linhas sem cobrir os botões.

### Passo 4: changelog

Em `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
`- **Zaplaud:** o campo de mensagem é uma pílula com o anexo e o uso de contexto dentro dela.`

**Verificar**: `grep -n 'pílula com o anexo' CHANGELOG.md` retorna 1 linha.

## Plano de testes

Sem teste novo: a mudança é de disposição. Os testes existentes de rascunho (linha 253), anexo e envio cobrem o
comportamento; o roteiro cobre anexar, remover e enviar.

## Critérios de pronto

- [ ] Grep do Passo 2 sem resultados.
- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas novas.
- [ ] Roteiro com exit 0 e capturas conferidas.
- [ ] `git status --short` só com `ReadingScreen.kt`, `CHANGELOG.md` e `plans/README.md`.
- [ ] Linha 061 atualizada.

## Condições de PARADA

- `pillFieldColors()` ou `warningColor()` não existem (planos 058/059 não executados).
- O rótulo "Mensagem" deixa de ser encontrado com texto digitado (`FixtureUiInstrumentedTest.kt:253` falha).
- `CircularProgressIndicator` não aceita `progress` como lambda nesta versão do Material 3.

## Notas de manutenção

- O anel é a única indicação de contexto fora da folha de informações; ao mudar os limites de 50% e 80%, mude aqui e
  na folha (`SessionInfoSheet`).
