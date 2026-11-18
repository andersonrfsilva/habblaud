# Plano 094: Zaplaud: a consulta de uma mensagem antiga não libera reenvio sem confirmação nem fecha outra conversa

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 094 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: MED (mexe na proteção contra mensagem duplicada; errar aqui duplica ou trava o envio)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O Zaplaud nunca reenvia uma mensagem sozinho: quando a resposta do servidor se perde, o envio fica "incerto" e o
próximo toque em Enviar exige confirmação, porque a primeira tentativa pode ter chegado. Dois furos nessa
proteção:

1. Envie A (fica na fila do agente) e depois B. Se a resposta do POST de B se perder, B fica "incerto". Mas a
   consulta periódica de A, ainda rodando, grava o estado de A por cima do de B (ela só confere a sessão, não a
   operação). O "incerto" some e um novo toque em Enviar manda B de novo sem pedir confirmação.
2. Um envio ou comando feito na conversa A que volta com `session_changed` depois que o usuário já abriu a
   conversa B (mesmo servidor) fecha a conversa B, que não mudou.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt`:

```kotlin
// :181  a proteção olha só a operação mostrada no estado
if (current.operation?.status == "unknown" && !confirmed) { mutable.value = current.copy(error = "confirm"); return }
// :246-247  envio incerto: operação sem id, status "unknown"; conflito chama changed() sem conferir a conversa aberta
if (state.value.key == key) mutable.value = state.value.copy(operation = if (dispatched && !definite) MessageOperation(status = "unknown", revision = draft.revision) else previous, error = ...)
if (conflict) changed() else if (e is ApiException && e.status == 401) unauthorized()
// :298  o mesmo nos comandos (STOP, conversa nova)
if (e is ApiException && e.code == "session_changed") changed() else if (e is ApiException && e.status == 401) unauthorized()
// :368-395  consulta periódica
private fun poll(key: SessionKey, operation: MessageOperation?) {
    poll?.cancel()
    ...
    poll = scope.launch {
        var op: MessageOperation = operation
        while (visible && state.value.key == key) {
            delay(2000)
            try {
                val id = op.id ?: break
                ...
                op = op.copy(status = requireNotNull(response.string("status")))
                store.updateMessage(key) { if (it.operation?.id == id) it.copy(operation = op) else it }   // disco: confere o id
                if (state.value.key == key) mutable.value = state.value.copy(operation = op)               // :381 tela: só a sessão
                ...
            } catch (e: Exception) {
                ...
                if (e is ApiException && e.status == 404) {
                    op = op.copy(status = "unknown")
                    store.updateMessage(key) { if (it.operation?.id == op.id) it.copy(operation = op) else it }
                    if (state.value.key == key) mutable.value = state.value.copy(operation = op)           // :389 idem
```

`MessageOperation` (`data/LocalStateStore.kt:9`):
`data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0, val error: String? = null)`.
A operação "incerta" criada em `:246` tem `id == null`.

`changed` é o callback que `ReadingController` passa; lá (`ReadingController.kt:260`) ele é
`private fun changed() { close(); mutable.value = mutable.value.copy(error = "changed") }`: fecha a conversa
que estiver aberta **agora**. `state.value.key` do `MessageController` é a conversa aberta (definida por
`messages.open(item.key)`).

Testes: `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt` (`MockWebServer`,
`MemoryStore`, auxiliar `controller(scope, store, server, changed)`); casos próximos:
`ambiguousFailureAndRestartNeverResendWithoutConfirmation` (`:85`),
`sessionConflictPreservesDraftAndClosesConversation` (`:112`),
`zeroClawControlResponseStaysWithOriginalSessionDuringSwitch` (`:246`).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-094 -b advisor/094-zaplaud-reenvio-so-com-confirmacao producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-094`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O Rust tem o mesmo controlador (`zaplaud-rs/crates/zaplaud-core/src/controller/message.rs`). Confira se a consulta de status dele é amarrada ao id da operação e se o conflito de sessão confere a sessão aberta; corrija junto ou registre em `docs/zaplaud-paridade.md`, linha "Tentar de novo após erro de envio".
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt`
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt`
- `docs/zaplaud-paridade.md` e `zaplaud-rs/` só para a paridade descrita em "Convenções"

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `ReadingController.kt`: a assinatura de `changed` não muda; a conferência fica no ponto de chamada.
- `pollControl` (consulta dos comandos, `:340-366`): já grava por id no disco; só mexa se o teste novo de
  comando mostrar o mesmo defeito, e então com a mesma correção de uma linha.
- Qualquer forma de reenvio automático.

## Passos

### Passo 1: teste que reproduz o reenvio sem confirmação

Em `MessageSendTest.kt`, escreva `olderPollDoesNotClearUncertainSend`: servidor aceita o POST de A com status
`queued` e id `opA`; o teste envia B e o servidor derruba a conexão do POST de B (veja como
`ambiguousFailureAndRestartNeverResendWithoutConfirmation` simula a resposta perdida); o GET
`/api/messages/opA` responde `queued`. Espere mais de 2 s (um ciclo da consulta) e afirme:
`state.operation?.status == "unknown"`; chamar `send(agentId)` sem `confirmed` resulta em
`state.error == "confirm"` e **nenhum** POST novo em `/api/messages`.

**Verificação**: rode só esse teste → **falha** antes da correção (prova que reproduz).
`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*MessageSendTest.olderPollDoesNotClearUncertainSend'`

### Passo 2: a consulta só publica a operação que ainda é a atual

Em `poll`, nas linhas 381 e 389, publique na tela só quando a operação mostrada ainda é a consultada:

```kotlin
if (state.value.key == key && state.value.operation?.id == id) mutable.value = state.value.copy(operation = op)
```

(na linha 389 use `op.id`). Não cancele a consulta de A quando B fica incerto: se B for recusado em definitivo,
o estado volta para `previous` (a operação A) e a consulta de A precisa continuar valendo.

**Verificação**: o teste do passo 1 passa.

### Passo 3: conflito de sessão só fecha a conversa de origem

Na linha 247: `if (conflict) { if (state.value.key == key) changed() } else if (...)`. Na linha 298, o mesmo:
`if (e is ApiException && e.code == "session_changed") { if (state.value.key == key) changed() } else if (...)`.

Teste novo `staleConflictDoesNotCloseAnotherConversation`: abra A, dispare `send`, com o servidor segurando a
resposta; abra B no mesmo controlador (`open(c, keyB)`); solte a resposta de A como 409 com código
`session_changed` (copie o corpo usado em `sessionConflictPreservesDraftAndClosesConversation`); afirme que o
contador de `changed` continua 0. O teste existente `sessionConflictPreservesDraftAndClosesConversation` deve
continuar passando (conflito na conversa aberta ainda fecha).

**Verificação**: `... :shared:testAndroidHostTest --tests '*MessageSendTest*'` → todos passam.

### Passo 4: paridade

Trate a paridade com o Rust conforme "Convenções". Não há mudança visível para o CHANGELOG além de correção de
defeito: acrescente uma linha curta em "Não lançado" ("Zaplaud: envio incerto continua pedindo confirmação
mesmo com outra mensagem na fila").

## Plano de testes

Os dois testes descritos nos passos 1 e 3, em `MessageSendTest.kt`. Verificação final: comando de host completo
→ `BUILD SUCCESSFUL`.

## Critérios de pronto

Todos precisam valer:

- [ ] `olderPollDoesNotClearUncertainSend` e `staleConflictDoesNotCloseAnotherConversation` existem e passam
- [ ] `grep -n 'changed()' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt` → as chamadas estão sob `state.value.key == key`
- [ ] Todos os testes de `MessageSendTest` passam (nenhum foi alterado para passar)
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 094 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O teste do passo 1 **passar antes** da correção: o defeito não se reproduz como descrito; pare e relate em vez
  de mudar o código.
- Algum teste existente de `MessageSendTest` falhar depois da correção: não ajuste o teste; relate.

## Notas de manutenção

- Toda escrita de `operation` no estado a partir de trabalho assíncrono deve conferir o id da operação, não só a
  sessão. Vale para qualquer consulta nova.
- O revisor deve ler o diff de `MessageController.kt` linha a linha: é a proteção contra mensagem duplicada.
