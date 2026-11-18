# Plano 116: Zaplaud: achar a causa dos dois testes instrumentados que falham desde antes de 2026-11-18 e consertar o que estiver errado (teste ou app)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 116 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat ee7bb0f..HEAD -- android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`
> Se algum arquivo do escopo mudou desde `ee7bb0f`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S–M
- **Risco**: LOW se for teste desatualizado; MED se revelar defeito do app na busca
- **Depende de**: emulador `habblaud034` autorizado pelo dono
- **Categoria**: tests / bug
- **Planejado em**: commit `ee7bb0f`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

A suíte instrumentada do app tem dois testes que falham de forma determinística, e por isso ela nunca fica
verde: o gate do CI (plano 090) reprova qualquer execução, e uma falha nova se esconde atrás das duas antigas.
Medido no emulador `habblaud034` em 2026-11-18, com a mesma suíte em três commits: `7236378` (antes das mudanças
do dia), `74da13b` e `ee7bb0f`; os dois falham nos três. Um deles pode ser só teste desatualizado; o outro pode
ser defeito real da busca de sessões ("Limpar busca" não traz a lista de volta).

## Estado atual

`android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`:

**`tabletKeepsCastAndUsesFullConversationWidth`** (`:721`). Falha medida:

```text
java.lang.AssertionError: Failed to inject touch input.
Reason: Expected exactly '1' node but could not find any node that satisfies:
(Text + InputText + EditableText contains '/fixture/projeto' (ignoreCase: false))
```

O teste força tela de tablet (`wm size 1920x1600`, `wm density 160`), confere a largura da conversa, tira
capturas nos dois temas e, mais abaixo, toca num campo de texto que contém `/fixture/projeto`. Esse texto é a
pasta do elenco. Em 2026-11-11 o dono mandou remover o controle de pasta ao chamar contatos ("deve sempre
começar no workspace padrão"); `grep -n 'folder\|cast_folder\|fixture/projeto' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` não acha
nada. Hipótese: o teste ainda procura um campo que o app não tem mais.

**`searchWithoutMatchesOffersClear`** (`:205-220`). Falha medida:
`ComposeTimeoutException: Condition still not satisfied after 15000 ms` na linha 217.

```kotlin
compose.onNode(hasScrollToNodeAction()).performTouchInput {
    swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600)
}
compose.waitUntil(15_000) { compose.onAllNodesWithTag("session-search").fetchSemanticsNodes().size == 1 }   // :217
compose.onNodeWithTag("session-search").performTextInput("busca-impossivel")
...
compose.onNodeWithText(text(R.string.search_clear)).performClick()
```

A linha 217 espera o campo de busca aparecer depois de uma puxada para baixo na lista. A busca é oculta e
revelada por "puxada longa" no topo (plano 059). Ou o gesto do teste não é mais suficiente para revelar a busca
(limiar mudou, ou a lista compacta do commit `7236378` mudou a geometria), ou a busca não abre mais por puxada no
app, o que seria defeito para o usuário. No app, a revelação fica em `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (procure
`searchOpen` e a conexão de rolagem aninhada perto de `sessionsList`); o botão de limpar é a linha
`TextButton(onClick = { query = "" }) { Text(stringResource(Res.string.search_clear)) }`.

Como rodar um teste só: o script `scripts/android-instrumented-ci.py` sobe a fixture Node, faz os `adb reverse` e
chama o Gradle com os argumentos de instrumentação. Para um teste isolado, suba a fixture como o script faz
(leia-o) e acrescente ao Gradle
`-Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.FixtureUiInstrumentedTest#<nome>`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-116 -b advisor/116-zaplaud-dois-instrumentados-que-ja-falhavam producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-116`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Este plano não muda comportamento compartilhado com o Rust.
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
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt` (os dois testes)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` **somente** se a investigação provar defeito do app na revelação da busca, e só nesse trecho
- `CHANGELOG.md` se houver correção no app

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Recolocar o controle de pasta do elenco: foi removido por ordem do dono.
- Outros testes da suíte, a fixture e `scripts/android-instrumented-ci.py`.
- `StatusLineInstrumentedTest.textUpdatesOncePerSecondAndPausesWhileStopped`: falhou 1 vez em 3 por depender de
  tempo real; anote se falhar de novo, não conserte aqui.

## Passos

### Passo 1: reproduzir cada falha isolada

Rode cada um dos dois testes sozinho no emulador e guarde a mensagem de falha e uma captura de tela do momento
da falha (`adb exec-out screencap -p > /tmp/habblaud-116-scratch/<nome>.png` logo depois, ou a captura que o
relatório do Gradle guardar).

**Verificação**: os dois falham isolados com as mensagens de "Estado atual". Se algum passar isolado, a falha
depende da ordem dos testes: anote qual teste anterior deixa o estado sujo e pare para relatar.

### Passo 2: tablet: confirmar que o campo não existe mais e atualizar o teste

Leia o teste inteiro e ache o trecho que usa `/fixture/projeto`. Confirme no app (`CastActions.kt` e o histórico:
`git log --oneline -5 -S'fixture/projeto' -- android/`) que o campo de pasta foi removido de propósito. Se sim,
tire do teste só os passos que dependem desse campo, mantendo tudo o que o nome do teste promete: elenco visível
em tela larga, conversa mais larga que 840 dp, capturas nos dois temas e o envio do pedido de chamar contato sem
o campo de pasta (confira o corpo do POST que a fixture recebe, como os outros testes de elenco do arquivo
fazem).

**Verificação**: o teste passa isolado 2 vezes seguidas.

### Passo 3: busca: descobrir se o defeito é do teste ou do app

Com a captura do passo 1, veja se a puxada revelou a busca. Leia em `ReadingScreen.kt` a regra de revelação
(distância mínima, condição de estar no topo) e compare com o gesto do teste (`swipeDown` de `top + 20` a
`bottom - 20` em 600 ms, sobre o primeiro nó com ação de rolar: confirme que esse nó ainda é a lista de
sessões, e não outro contêiner rolável que tenha passado a existir antes dela).

- Se o gesto do teste não atinge mais o alvo ou o limiar, mas a puxada real funciona: ajuste o teste para mirar
  a lista pelo que a identifica (procure uma `testTag` da lista; se não houver, use a primeira linha
  `session-row:` como referência) e mantenha a asserção.
- Se a busca de fato não abre mais por puxada no app: é defeito para o usuário. Corrija no trecho de revelação,
  com a menor mudança, e acrescente ao `CHANGELOG.md` a linha da correção.

**Verificação**: o teste passa isolado 2 vezes seguidas; `BUILD SUCCESSFUL`.

### Passo 4: suíte inteira uma vez

`timeout 1500 python3 scripts/android-instrumented-ci.py` → saída 0 (o validador do plano 090 exige as classes
executadas e zero falhas). Se só `StatusLineInstrumentedTest` falhar, rode a suíte mais uma vez e relate as
duas execuções.

## Plano de testes

Os dois testes existentes, corrigidos. Nenhum teste novo, a menos que o passo 3 corrija o app; nesse caso o próprio teste da busca é a regressão.

## Critérios de pronto

Todos precisam valer:

- [ ] `tabletKeepsCastAndUsesFullConversationWidth` e `searchWithoutMatchesOffersClear` passam isolados, 2 vezes cada
- [ ] `python3 scripts/android-instrumented-ci.py` sai com 0 (ou só o teste de tempo real falha, relatado)
- [ ] O relato diz, para cada teste, se a causa era o teste ou o app, com arquivo e linha
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 116 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `ee7bb0f`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A falha de um teste depender da ordem de execução (passa isolado).
- A correção da busca no app exigir mexer fora do trecho de revelação.
- O emulador ficar com tamanho ou densidade alterados depois de uma falha: restaure com `wm size reset` e
  `wm density reset` antes de continuar, e relate.

## Notas de manutenção

- Com a suíte verde, vale rodá-la antes de cada merge de plano que toque telas; hoje ela só roda à mão, porque o
  runner do GitHub não tem KVM.
- Texto de interface que muda quebra estes testes; eles leem os textos de `android/app/src/main/res/values/strings.xml`.
