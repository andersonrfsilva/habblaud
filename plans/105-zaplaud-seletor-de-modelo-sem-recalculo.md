# Plano 105: Zaplaud: o seletor de modelo não reordena o catálogo a cada recomposição

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 105 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Roles.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: 090 (gate)
- **Categoria**: perf
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O catálogo de modelos anunciado por um agente pode ter até 1.000 itens (o limite subiu de 200 para 1.000,
segundo o `CHANGELOG.md`). Os dois seletores (modelo da sessão e modelo de um papel) chamam `roleModelGroups`
**direto no corpo do composable**, sem `remember`: o filtro, o agrupamento e a ordenação rodam de novo a cada
recomposição, não só a cada tecla. A comparação usada na ordenação compila duas expressões regulares por
chamada, ou seja, milhares de vezes por ordenação. O efeito esperado é digitação travando no campo de filtro em
catálogos grandes. Não foi medido; a correção é barata e sem risco, por isso vem antes de qualquer lista
virtualizada.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Roles.kt`:

```kotlin
// :108-126
fun roleModelGroups(models: List<String>, keep: String? = null, query: String = ""): List<Pair<String, List<String>>> {
    val all = (models + listOfNotNull(keep)).filter { '/' in it }.toSet()
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    ...
    return shown.groupBy(::modelGroupKey).entries.sortedWith { ... }
        .map { (provider, list) -> provider to list.sortedWith { a, b -> compareModelNames(key(a), key(b)).takeIf { it != 0 } ?: a.compareTo(b) } }
}
private val DATED = Regex("-\\d{8}$")
private val HIDDEN_VARIANT = Regex(":(?:batch|free|extended)$")
// :132-134
private fun compareModelNames(a: String, b: String): Int {
    val x = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }     // regex nova a cada comparação
    val y = b.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:300` (dentro do menu da conversa):
`val groups = roleModelGroups(selected!!.models, keep = currentModel, query = modelQuery)`.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt:311` (dentro de `RoleDialog`):
`val groups = roleModelGroups(models, keep = currentModel, query = filter)`.

Testes da função: `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/RolesTest.kt` (ordem, variantes
ocultas, modelo atual preservado). Eles definem o comportamento que não pode mudar.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-105 -b advisor/105-zaplaud-seletor-de-modelo-sem-recalculo producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-105`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. A função equivalente no Rust é gerada à parte; nada a portar (sem mudança de comportamento).
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Roles.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (só a linha 300)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt` (só a linha 311)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Trocar o menu por lista virtualizada (`LazyColumn`): só se, depois deste plano, uma medição em aparelho ainda
  mostrar travamento. Um `LazyColumn` dentro de `DropdownMenu`/`AlertDialog` com rolagem exige altura fixa e
  muda o layout; não é o corte simples.
- A ordem e o filtro dos modelos (comportamento coberto por `RolesTest`).

## Passos

### Passo 1: expressões regulares compiladas uma vez

Em `Roles.kt`, crie ao lado de `DATED`: `private val NAME_PARTS = Regex("[^a-z0-9]+")` e
`private val SPACES = Regex("\\s+")`; use-as em `compareModelNames` e na linha 110.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*RolesTest*'` → passa sem alteração nos testes.

### Passo 2: resultado lembrado entre recomposições

`ReadingScreen.kt:300`:
`val groups = remember(selected.models, currentModel, modelQuery) { roleModelGroups(selected.models, keep = currentModel, query = modelQuery) }`
(preserve o `!!` existente se `selected` for anulável naquele ponto).

`SettingsScreen.kt:311`:
`val groups = remember(models, currentModel, filter) { roleModelGroups(models, keep = currentModel, query = filter) }`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

## Plano de testes

Sem teste novo: `RolesTest` já fixa o resultado da função, e a mudança não altera resultado. Rode-o inteiro.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -c 'Regex(' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Roles.kt` não conta nenhuma ocorrência dentro de `compareModelNames` nem de `roleModelGroups`
- [ ] `grep -n 'remember(.*roleModelGroups\|roleModelGroups' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt` → as duas chamadas estão dentro de `remember(...)`
- [ ] `RolesTest` passa sem edição; comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 105 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `selected.models` não for uma lista estável (por exemplo, recriada a cada snapshot com o mesmo conteúdo): o
  `remember` continua correto, só recalcula mais; não é motivo de parada. Pare apenas se `RolesTest` mudar de
  resultado.

## Notas de manutenção

- Se ainda houver travamento com 1.000 modelos depois disto, o próximo passo é medir no aparelho e então
  limitar o número de linhas desenhadas (por exemplo, mostrar os 100 primeiros e pedir filtro).
