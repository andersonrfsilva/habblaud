# Plano 052: agrupar pensamentos e ferramentas sem recopiar o acumulado

> **Instruções ao executor**: siga os passos e verificações. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 052 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/ConversationViewTest.kt`.

## Status

- **Prioridade**: P3 (limpeza; medição real mostrou custo pequeno)
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum (base `778466839`)
- **Categoria**: perf / tech-debt
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

`groupTurn` é quadrático no texto dos pensamentos de um turno. Medido no ART do M35, nas 16 conversas reais em cache (até 500 entradas): no máximo 3,08 ms por chamada, com o pior turno real tendo 38 pensamentos e 20,7 mil caracteres. No perfil de rolagem, ficou abaixo de 0,4% da CPU. Em dados sintéticos extremos (1.000 × 4 KiB), 1,48 s. A função roda a cada recomposição da conversa. Vale tornar linear se o custo for baixo e o risco zero.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:713-739`:

```kotlin
internal fun groupTurn(entries: List<TerminalEntry>): List<EntryRow> {
    ...
        when {
            entry.kind == "thinking" && thinking >= 0 -> {
                val row = rows[thinking]
                val first = row.entry
                rows[thinking] = row.copy(entry = first.copy(text = listOf(first.text, entry.text).filter { it.isNotBlank() }.joinToString("\n\n"), at = entry.at ?: first.at), count = row.count + 1)
            }
            entry.kind == "tool" && tools >= 0 -> {
                val row = rows[tools]
                rows[tools] = row.copy(entry = row.entry.copy(at = entry.at ?: row.entry.at), count = row.count + 1, tools = row.tools + entry)
            }
            ...
```

Testes existentes: `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/ConversationViewTest.kt`.

## Passos

### Passo 1: acumular e materializar uma vez

Para cada grupo aberto de `thinking`, mantenha um `StringBuilder` com os textos não vazios separados por `"\n\n"`, o último `at` e a contagem. Para cada grupo de `tool`, mantenha um `MutableList<TerminalEntry>`. Materialize o `EntryRow` uma vez por grupo, no fim, com o mesmo resultado de hoje: `id` do primeiro, `at` do último que tiver `at`, `count`, `tools` em ordem, e os resets em `user`.

Detalhe que precisa ser preservado: hoje o filtro `isNotBlank` se aplica ao texto acumulado e ao novo a cada passo. Com o primeiro texto vazio, o resultado não começa com `"\n\n"`. Reproduza isso.

### Passo 2: testes de equivalência

Em `ConversationViewTest.kt`, adicione casos com resultado exato:

- Três pensamentos seguidos, o do meio em branco.
- Pensamentos intercalados com ferramentas no mesmo turno.
- Primeiro pensamento vazio.
- `at` nulo em alguns.
- Novo turno depois de `user`.

Antes de mudar o código, rode os testes novos contra a implementação atual: devem passar, fixando o comportamento de hoje.

Comando: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*ConversationViewTest*'` → passa antes e depois.

### Passo 3: suíte

`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL`.

## Critérios de pronto

- [ ] Testes de equivalência verdes antes e depois.
- [ ] Suíte verde; linha 052 atualizada.

## Condições de PARADA

- Algum teste de equivalência não puder passar na implementação atual (comportamento atual ambíguo): relate antes de mudar.

## Notas de manutenção

- Não memoize a projeção com `remember(entries)`: `ConversationState.snapshot()` devolve a mesma `SnapshotStateList` mutada no lugar, e a memoização congelaria a tela.
