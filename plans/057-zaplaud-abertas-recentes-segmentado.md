# Plano 057: "Abertas" e "Recentes" viram um botão segmentado

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 057 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`.
> Os planos 055 e 056 mudam outras partes deste arquivo; isso é esperado. Compare o trecho do `TabRow` abaixo pelo
> conteúdo; divergência é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum (execute antes do 059, que mexe na mesma região)
- **Categoria**: direction (modernização visual) / tech-debt
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

As abas da lista usam `TabRow`, que o compilador marca como obsoleto ("Replaced with PrimaryTabRow and
SecondaryTabRow", aviso no build de 2026-11-08). Na captura do emulador, a faixa das abas fica recuada 12 dp das
bordas, com fundo diferente do resto da tela, e parece um bloco solto entre a barra superior e a lista. Um botão
segmentado de duas opções cabe no recuo atual, combina com os chips de servidor logo acima e tira o componente
obsoleto.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:256`: a coluna da tela inteira tem
  `.padding(horizontal = 12.dp)`, por isso o `TabRow` não chega às bordas.
- `ReadingScreen.kt:280-285`:

  ```kotlin
  // Trocar de aba volta ao topo, com a busca escondida.
  fun showTab(value: Boolean) { recent = value; if (query.isEmpty()) scope.launch { withFrameNanos { }; sessionsList.scrollToItem(1) } }
  TabRow(selectedTabIndex = if (recent) 1 else 0) {
      Tab(selected = !recent, onClick = { showTab(false) }, text = { Text(stringResource(Res.string.open_sessions)) })
      Tab(selected = recent, onClick = { showTab(true) }, text = { Text(stringResource(Res.string.recent_sessions)) })
  }
  ```

- Chips de servidor no mesmo bloco, padrão Material 3 já usado, `ReadingScreen.kt:269-279` (`FilterChip`).
- Testes que procuram as abas por texto: `FixtureUiInstrumentedTest.kt:154-155,166-167` e
  `HttpLoginInstrumentedTest.kt:141` (`await(R.string.open_sessions)` e `onNodeWithText(...).assertIsDisplayed()`).
  O roteiro `scripts/android-screens.sh` toca `Abertas` e `Recentes` por texto. Os textos não mudam.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL`, sem o aviso `'fun TabRow(...)' is deprecated` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas (a falha conhecida `imeDraftSurvivesRecreationRotationAndLargeFont` pode ser registrada e ignorada) |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

## Escopo

**Pode modificar**: `ReadingScreen.kt` (só o bloco do `TabRow`) e `CHANGELOG.md`.

**Fora**: `showTab` e o comportamento de rolagem (o plano 059 muda), chips de servidor, filtro e ordem da lista,
textos.

## Git

- Branch: `advisor/057-zaplaud-segmentado`.
- Mensagem: `feat(android): Abertas e Recentes em botão segmentado`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: trocar o `TabRow`

Substitua o `TabRow` e seus `Tab` por:

```kotlin
SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    SegmentedButton(selected = !recent, onClick = { showTab(false) }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) {
        Text(stringResource(Res.string.open_sessions))
    }
    SegmentedButton(selected = recent, onClick = { showTab(true) }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) {
        Text(stringResource(Res.string.recent_sessions))
    }
}
```

Os três nomes vêm de `androidx.compose.material3` (o arquivo já importa `androidx.compose.material3.*`). Se o build
exigir `@OptIn(ExperimentalMaterial3Api::class)`, a função `ReadingScreen` já tem essa anotação.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`;
`grep -n -E '\b(TabRow|Tab)\(' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` → nenhuma linha.

### Passo 2: testes e capturas

Rode os instrumentados e o roteiro. Abra `sessoes-abertas-*` e `sessoes-recentes-*` nos dois temas.

**Verificar**: zero falhas novas; exit 0; o segmento selecionado aparece preenchido com marca de seleção, sem faixa
de fundo diferente.

### Passo 3: changelog

Em `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
`- **Zaplaud:** "Abertas" e "Recentes" usam um botão segmentado no lugar das abas.`

**Verificar**: `grep -n 'botão segmentado' CHANGELOG.md` retorna 1 linha.

## Plano de testes

Sem teste novo. Os testes existentes que esperam `open_sessions` visível continuam valendo; se algum usar
`assertIsSelected` numa aba, ele funciona com `SegmentedButton` (papel de seleção única).

## Critérios de pronto

- [ ] Nenhum `TabRow(`/`Tab(` em `ReadingScreen.kt` e build com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas novas.
- [ ] Capturas da lista nos dois temas com o segmentado.
- [ ] `git status --short` só com `ReadingScreen.kt`, `CHANGELOG.md` e `plans/README.md`.
- [ ] Linha 057 atualizada.

## Condições de PARADA

- `SingleChoiceSegmentedButtonRow` ou `SegmentedButton` não existem na versão do Compose Multiplatform do projeto
  (`composeMultiplatform = "1.12.2"` em `android/gradle/libs.versions.toml`). Não atualize dependências; relate.
- O texto de um segmento quebra em duas linhas na largura de 1080 px com fonte 1,3 (captura `conversa-fonte-1-3` é
  da conversa; confira a lista com `adb shell settings put system font_scale 1.3`).

## Notas de manutenção

- O plano 059 muda `showTab` (a busca deixa de ser o item 0 da lista). Não altere `showTab` aqui.
