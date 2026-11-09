# Plano 056: grupos "Ferramentas" e "Pensando" viram linhas compactas com seta de expandir

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 056 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`.
> O plano 055 muda este arquivo (troca `Text("⚙")` por `Icon(Icons.Default.Build, ...)`); isso é esperado. Compare o
> trecho de `EntryView` abaixo pelo conteúdo; outra divergência é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: `plans/055-zaplaud-icones-material.md`
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

Na conversa, os grupos "Ferramentas (3)" e "Pensando… (1)" são desenhados como bolhas cheias, com o mesmo fundo,
tamanho e cantos das respostas do agente. Na captura do emulador em 2026-11-08 eles competem com as mensagens e não
mostram que podem ser abertos. O resumo de resposta, no mesmo arquivo, já diz "toque para ver tudo"; esses grupos não
dizem nada. Apps de chat atuais mostram etapas internas como linhas discretas com seta. A mudança deixa as respostas
em destaque e torna a expansão evidente, sem mudar o comportamento.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` — tela de sessões e conversa.
- `EntryView`, `ReadingScreen.kt:611-627` (antes do plano 055), parte que desenha a bolha e o cabeçalho do grupo:

  ```kotlin
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.kind == "user") Arrangement.End else Arrangement.Start) {
      Surface(
          modifier = Modifier.fillMaxWidth(0.85f),
          shape = RoundedCornerShape(16.dp),
          color = if (entry.kind == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
      ) {
          Column(Modifier.padding(12.dp)) {
              if (entry.kind == "tool" || entry.kind == "thinking") {
                  val label = "${stringResource(if (entry.kind == "thinking") Res.string.thinking else Res.string.tools)} ($count)"
                  ...
                  Row(Modifier.fillMaxWidth().onGloballyPositioned { headerY = it.positionInWindow().y }.clickable { val before = headerY; expanded = !expanded; keepPlace(before) { headerY } }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                      if (entry.kind == "tool") Text("⚙", color = MaterialTheme.colorScheme.primary)
                      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                      last?.let { Text(entryTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                  }
                  if (expanded) SelectionContainer { ... }
  ```

- `headerY` e `keepPlace(before) { headerY }` mantêm o cabeçalho no mesmo lugar da tela ao expandir numa lista
  invertida (comentário em `ReadingScreen.kt:379-381`). Esse mecanismo deve continuar igual.
- Teste instrumentado que expande o pensamento por texto,
  `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt:201-209`:
  `compose.onNode(hasText(text(R.string.thinking), substring = true)).performClick()`. O rótulo continua o mesmo.
- Ícones Material estão disponíveis (`compose.materialIconsExtended` em `android/shared/build.gradle.kts:41`).

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas (a falha conhecida `imeDraftSurvivesRecreationRotationAndLargeFont` pode ser registrada e ignorada) |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

## Escopo

**Pode modificar**: `ReadingScreen.kt` (só o ramo `tool`/`thinking` de `EntryView`) e `CHANGELOG.md`.

**Fora**: bolhas de `user`, `assistant` e `system`; o agrupamento (`groupTurn`); o texto das strings `thinking` e
`tools`; o conteúdo expandido (ferramentas, resultados, pensamento em itálico); o mecanismo `keepPlace`.

## Git

- Branch: `advisor/056-zaplaud-grupos-compactos`.
- Mensagem: `feat(android): ferramentas e pensamentos em linhas compactas com seta`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: desenhar o grupo sem bolha cheia

Para `entry.kind == "tool" || entry.kind == "thinking"`, o `Surface` passa a ser:

```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(0.85f),
    shape = RoundedCornerShape(12.dp),
    color = Color.Transparent,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
)
```

O conteúdo usa `Modifier.padding(horizontal = 12.dp, vertical = 8.dp)` no lugar do `padding(12.dp)`. Bolhas dos
outros tipos continuam exatamente como hoje. Faça a divisão com um `if` sobre o tipo, sem criar um composable
compartilhado novo, a menos que o ramo fique ilegível; nesse caso, extraia só o ramo do grupo para uma função privada
no mesmo arquivo.

Imports: `androidx.compose.foundation.BorderStroke`.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: cabeçalho com ícone, rótulo discreto e seta

No `Row` do cabeçalho (mantendo `onGloballyPositioned`, `clickable` e `keepPlace` como estão):

1. Ícone inicial com `Modifier.size(16.dp)` e `tint = MaterialTheme.colorScheme.onSurfaceVariant`:
   `Icons.Default.Build` para `tool` (já vem do plano 055) e `Icons.Outlined.Psychology` para `thinking`.
2. Rótulo com `style = MaterialTheme.typography.labelLarge` e `color = MaterialTheme.colorScheme.onSurfaceVariant`.
3. Horário como hoje.
4. Seta final: `Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)`.

O conteúdo expandido ganha `Modifier.padding(top = 8.dp)` para separar do cabeçalho.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`; instrumentados sem falhas novas (o teste das linhas 201-209
expande e recolhe o pensamento).

### Passo 3: conferir visualmente

Rode o roteiro. Abra `conversa-fim-*`, `pensamento-expandido-*` e `ferramenta-expandida-*` nos dois temas.

**Verificar**: exit 0; os grupos aparecem com contorno fino, sem fundo cheio, menores que as respostas, com seta
para baixo quando fechados e para cima quando abertos.

### Passo 4: changelog

Em `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
`- **Zaplaud:** ferramentas e pensamentos aparecem como linhas compactas com seta de expandir, sem competir com as respostas.`

**Verificar**: `grep -n 'linhas compactas' CHANGELOG.md` retorna 1 linha.

## Plano de testes

- Sem teste novo: a mudança é visual e o comportamento (expandir/recolher) já é coberto pelo teste das linhas
  201-209 de `FixtureUiInstrumentedTest.kt`.
- Verificação visual pelas capturas do Passo 3.

## Critérios de pronto

- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas novas.
- [ ] Capturas mostram o novo grupo nos dois temas.
- [ ] `git status --short` só com `ReadingScreen.kt`, `CHANGELOG.md` e `plans/README.md`.
- [ ] Linha 056 atualizada.

## Condições de PARADA

- Expandir passa a fazer a lista pular (o cabeçalho tocado não fica no mesmo lugar). Não mexa em `keepPlace`; relate.
- `Icons.Outlined.Psychology`, `ExpandMore` ou `ExpandLess` não resolvem no build.
- O teste de pensamento (linhas 201-209) falha e a correção exigiria mudar o texto da string.

## Notas de manutenção

- O cabeçalho do grupo é o único alvo de toque; se um dia ganhar ação de acessibilidade própria (rótulo
  "expandir/recolher"), crie strings novas nos dois `strings.xml` (`android/shared/.../composeResources/values/` e
  `android/app/src/main/res/values/`).
- Fora deste plano: renomear "Pensando…" para um rótulo de passado (o texto é usado no roteiro e no teste).
