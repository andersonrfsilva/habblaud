# Plano 099: Zaplaud: confirmações de elenco fecham quando a conversa muda e a decisão de permissão não é cancelada ao fechar a folha

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 099 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S–M
- **Risco**: MED (diálogos que fecham sozinhos não podem interromper um pedido já enviado)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

1. **Elenco**: os diálogos "Retomar conversa" e "Dispensar contato" guardam a escolha em estado que não depende
   do servidor nem da conversa aberta, mas na hora de confirmar leem a conversa **atual** com `selected!!`. Se
   uma notificação ou uma atualização trocar a conversa com o diálogo aberto, Confirmar retoma outra sessão,
   manda o "Dispensar" de um agente para o servidor errado, ou derruba o app (`selected` nulo).
2. **Permissão**: a decisão (permitir/negar) é enviada num escopo que morre quando a folha fecha. Voltar,
   deslizar ou tocar em Cancelar durante o envio cancela a chamada depois de o servidor possivelmente tê-la
   recebido; o estado "incerto" fica só na folha, que acabou de ser descartada. O usuário não sabe se o agente
   foi liberado.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`:

```kotlin
// :81-90
var availability by remember(api) { mutableStateOf("loading") }
...
var dialog by remember { mutableStateOf("") }
var choice by remember { mutableStateOf<CastChoice?>(null) }
// Quem o "Dispensar" vai fechar: a conversa aberta, ou o contato escolhido pelo menu da lista.
var target by remember { mutableStateOf<SessionItem?>(null) }
// :279-287
if (dialog == "dismiss" || dialog == "resume") AlertDialog(onDismissRequest = { dialog = "" }, ..., text = {
    Text(if (dialog == "dismiss") listOfNotNull(target?.let { it.characterName ?: it.title }, ...).joinToString("\n") else listOfNotNull(choice?.name, selected?.project).joinToString("\n"))
}, confirmButton = {
    TextButton(enabled = !sending, onClick = {
        if (dialog == "dismiss") target?.agentId?.let { submit("/api/agents/${it.encodeURLPathPart()}/dismiss", buildJsonObject {}) }
        else if (choice!!.id.isEmpty()) submit("/api/cast/new", buildJsonObject { put("room", selected!!.project); put("session", selected.key.sessionId); put("account", selected.key.account) })
        else submit("/api/cast/${choice!!.id.encodeURLPathPart()}/start", buildJsonObject { put("mode", "resume"); put("room", selected!!.project); ... })
```

`submit` usa o `api` atual do composable (o parâmetro `api` de `CastActions`), que em `ReadingScreen.kt:163-164`
é o do servidor da conversa aberta ou do filtro.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt`:

```kotlin
// :71
val scope = rememberCoroutineScope()
// :74-76
var busy by remember(id) { mutableStateOf(false) }
var ambiguous by remember(id) { mutableStateOf(false) }
// :105-115
busy = true
scope.launch {
    try { api.request("$path/decision", body); onClose() }
    catch (e: Exception) {
        if (e is CancellationException) { ambiguous = true; throw e }
        ...
    } finally { busy = false }
}
// :117
val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
// :124
ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
// :196
TextButton(onClick = onClose) { Text(stringResource(Res.string.cancel)) }
```

Quem fecha a folha por fora: `ReadingScreen.kt:186-188` zera `permissionSession` quando o pedido some da lista
com o servidor conectado (o servidor já processou). Esse caminho está certo.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-099 -b advisor/099-zaplaud-confirmacoes-presas-ao-alvo producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-099`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Confira no Rust se o diálogo de retomar/dispensar guarda o alvo de quando abriu; registre em `docs/zaplaud-paridade.md` se não corrigir junto.
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `ReadingScreen.kt`.
- O conteúdo da decisão (`permissionDecision`) e as rotas do servidor.
- Reenviar decisão automaticamente.

## Passos

### Passo 1: diálogos de elenco valem só para o servidor e a conversa em que abriram

Em `CastActions.kt:86-89`, amarre o estado dos diálogos ao servidor e à conversa:

```kotlin
var dialog by remember(api, selected?.key) { mutableStateOf("") }
var choice by remember(api, selected?.key) { mutableStateOf<CastChoice?>(null) }
var target by remember(api, selected?.key) { mutableStateOf<SessionItem?>(null) }
```

Trocar de conversa ou de servidor fecha o diálogo aberto (estado volta ao inicial), eliminando a confirmação
com alvo trocado. O "Dispensar" pelo menu da lista acontece com `selected == null` e continua funcionando
(a chave é `null` e estável).

No `onClick` de confirmar, troque os três `selected!!`/`choice!!` por leitura segura no início:
`val s = selected; val c = choice` e `if (dialog == "resume" && (s == null || c == null)) { dialog = ""; return@TextButton }`.

Um pedido **já enviado** não é afetado: `submit` já foi chamado e o acompanhamento (`jobs`, `result`) não
depende desses três estados. Confirme lendo `submit` no mesmo arquivo.

**Verificação**: `grep -n '!!' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` →
nenhuma ocorrência de `selected!!` nem `choice!!`. Comando de host completo → `BUILD SUCCESSFUL`.

### Passo 2: a folha de permissão não fecha durante o envio

Em `PermissionSheet.kt`:

- `:117`: `rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })`
  (importe `androidx.compose.material3.SheetValue`); como `busy` é estado, envolva com `rememberUpdatedState`
  se o compilador avisar captura obsoleta.
- `:124`: `onDismissRequest = { if (!busy) onClose() }`.
- `:196`: `TextButton(onClick = onClose, enabled = !busy)`.

Assim o pedido termina (sucesso, erro ou tempo esgotado do cliente HTTP) antes de a folha poder fechar, e o
aviso de "incerto" que já existe (`ambiguous`, `error = "unknown"`) é mostrado em vez de se perder.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

## Plano de testes

Não há teste de host para composables neste projeto além dos de função pura. Os testes instrumentados existentes
(`FixtureUiInstrumentedTest`: permitir/negar, chamar/retomar contato) cobrem os caminhos felizes e devem
continuar passando; execute-os só com autorização do dono
(`python3 scripts/android-instrumented-ci.py`). Sem ela, confirme que compilam:
`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -nE 'selected!!|choice!!' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` → nenhuma ocorrência
- [ ] `grep -n 'remember(api, selected?.key)' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` → 3 ocorrências
- [ ] `grep -n 'if (!busy) onClose()' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/PermissionSheet.kt` → 1 ocorrência
- [ ] Comando de host completo e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 099 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `submit` em `CastActions.kt` depender de `dialog`, `choice` ou `target` **depois** do envio (para mostrar o
  resultado, por exemplo): amarrar esses estados à conversa apagaria o acompanhamento; relate.
- `SheetValue`/`confirmValueChange` não existirem na versão do Material 3 em uso.

## Notas de manutenção

- Diálogo novo de confirmação em `CastActions` deve usar a mesma chave `remember(api, selected?.key)`.
- Reteste manual sugerido: abrir "Retomar", receber uma notificação de outra conversa e tocar nela → o diálogo
  some; nada é enviado.
