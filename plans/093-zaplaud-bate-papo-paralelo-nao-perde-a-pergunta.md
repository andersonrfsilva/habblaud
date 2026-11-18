# Plano 093: Zaplaud: o bate-papo paralelo não perde a pergunta, sobrevive a sair e voltar do app e retoma depois de falha de rede

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 093 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AsideController.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/AsideSheet.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S–M
- **Risco**: LOW (a consulta tem de continuar parada em segundo plano)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Três defeitos no bate-papo paralelo (perguntas a um agente do omp sem interrompê-lo), todos confirmados no código:

1. O botão Enviar apaga o texto digitado logo depois de chamar `ask`, mesmo quando `ask` não envia nada (sem
   destinatário, pergunta longa demais, outra em andamento) ou quando o servidor recusa. A pergunta some.
2. Ao sair do app e voltar (Android `ON_STOP`), o controlador de leitura chama `asides.close()`, que zera o
   destinatário; a folha continua aberta na tela. A partir daí Enviar descarta tudo em silêncio até o usuário
   fechar e reabrir a folha.
3. Enquanto uma resposta está sendo gerada, uma única falha de consulta encerra o laço: a resposta fica
   congelada pela metade e o botão Parar continua visível, mesmo com a rede de volta.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/AsideSheet.kt:103`:

```kotlin
FilledIconButton(onClick = { asides.ask(draft); draft = ""; following = true }, enabled = draft.isNotBlank() && !state.sending, ...
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AsideController.kt`:

```kotlin
// :69-78
fun open(agentId: String) {
    poller?.cancel(); poller = null
    mutable.value = AsideState(agentId = agentId, loading = true)
    poll()
}
fun close() {
    poller?.cancel(); poller = null
    mutable.value = AsideState()
}
// :84-100
fun ask(question: String) {
    val current = mutable.value
    val agentId = current.agentId ?: return
    val text = question.trim()
    if (text.isEmpty() || text.length > ASIDE_QUESTION_MAX || current.sending || current.topic?.turns?.any { it.running } == true) return
    val topicId = current.topic?.id
    mutable.value = current.copy(sending = true, error = null)
    scope.launch {
        val result = call(agentId) { request("/api/asides", ...) }
        val topic = result?.let { ... parseAsideTopic ... }
        val now = mutable.value
        if (now.agentId != agentId) return@launch
        mutable.value = if (topic == null) now.copy(sending = false)
        else now.copy(sending = false, topics = listOf(topic) + now.topics.filter { it.id != topic.id }, topicId = topic.id, fresh = false)
        if (topic != null) poll()
    }
}
// :113-131
private fun poll() {
    dirty = true
    if (poller?.isActive == true) return
    val agentId = mutable.value.agentId ?: return
    poller = scope.launch {
        while (true) {
            dirty = false
            val body = call(agentId) { request("/api/asides?agentId=...", null) }
            val now = mutable.value
            if (now.agentId != agentId) return@launch
            val topics = body?.let { ... }
            mutable.value = if (topics == null) now.copy(loading = false) else now.copy(loading = false, topics = topics)
            if (dirty) continue
            if (topics == null || !mutable.value.running) break      // :127 falha encerra o laço
            delay(ASIDE_POLL_MS)
        }
    }
}
// :135-142  `call` devolve null em qualquer falha, põe `error` no estado e chama unauthorized() no 401
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`:

```kotlin
// :133-137
fun start() {
    if (active) return
    active = true
    accessReady = false
    messages.start()
// :179-184
fun stop() {
    if (!active) return
    persist(); saveSeen(0); active = false; accessReady = false; generation++
    stateJob?.cancel(); conversationJob?.cancel()
    ...
    messages.stop(); asides.close(); api.cancelCalls()
```

`close()` (fechar a conversa, `:187`) e `open(item)` (`:279`) também chamam `asides.close()`. O de `close()` está
certo e continua. O de `open(item)` precisa de uma condição (achado na primeira execução deste plano): depois de
`stop()`/`start()`, o primeiro snapshot reabre a **mesma** conversa (`:171`,
`if (firstSnapshot && conversationJob?.isActive != true) mutable.value.selected?.let(::open)`), e esse `open`
apagaria o destinatário do bate-papo logo depois do `resume()`.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:259`: `var asideOpen by remember(selected?.key) { mutableStateOf(false) }` (a folha vive
enquanto a conversa selecionada for a mesma); `:332` desenha a folha com `controller.asides`.

Testes existentes: `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/AsideControllerTest.kt`
(controlador com `request` falso; use como padrão).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-093 -b advisor/093-zaplaud-bate-papo-paralelo-nao-perde-a-pergunta producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-093`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O Rust tem o mesmo recurso (`zaplaud-rs/crates/zaplaud-core`, controlador de aside). Confira se ele limpa o rascunho antes da confirmação e se para de consultar depois de uma falha; se sim, corrija na mesma entrega ou registre a pendência em `docs/zaplaud-paridade.md`, linha "Bate-papo paralelo".
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AsideController.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/AsideSheet.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt` (só as chamadas a `asides` em `start()`, `stop()` e `open(item)`)
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/AsideControllerTest.kt`
- `CHANGELOG.md`; `docs/zaplaud-paridade.md` e `zaplaud-rs/` só para a paridade descrita em "Convenções"

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `ReadingScreen.kt`: a vida da folha (`asideOpen`) não muda.
- `server/asides/**`: o contrato do servidor não muda.
- Reenviar a pergunta automaticamente depois de falha: proibido (regra do projeto: nada de reenvio sem o usuário).

## Passos

### Passo 1: o rascunho só é apagado quando o servidor aceita

Dê a `ask` um segundo parâmetro `onAccepted: () -> Unit = {}` e chame-o no ponto em que `topic != null` (o
servidor devolveu o tópico com a pergunta). Nos retornos antecipados e quando `topic == null`, não chame.

Em `AsideSheet.kt:103`, troque o `onClick` por:

```kotlin
onClick = { val sent = draft; asides.ask(sent) { if (draft == sent) draft = "" }; following = true }
```

(o `if` evita apagar um texto que o usuário já mudou enquanto o pedido estava no ar.) O botão já fica
desabilitado por `state.sending`, então não há duplo envio.

**Verificação**: teste `rejectedQuestionKeepsDraftCallback` do "Plano de testes".

### Passo 2: pausar em vez de fechar ao sair do app

Em `AsideController`, acrescente:

```kotlin
/** App em segundo plano: para de consultar, mas mantém o agente e a conversa para a volta. */
fun pause() { poller?.cancel(); poller = null }
fun resume() { if (mutable.value.agentId != null) poll() }
```

Em `ReadingController.stop()`, troque `asides.close()` por `asides.pause()`. Em `ReadingController.start()`,
depois de `messages.start()`, chame `asides.resume()`. Não mexa no `asides.close()` de `close()`.

Em `ReadingController.open(item)`, feche o bate-papo só quando a conversa muda. No começo da função, antes de
qualquer atribuição a `mutable.value`, guarde `val sameSession = mutable.value.selected?.key == item.key`; na
linha que hoje é `messages.open(item.key); asides.close()`, troque por
`messages.open(item.key); if (!sameSession) asides.close()`. Não mude mais nada em `open`. A folha na tela
(`ReadingScreen.kt`, `asideOpen` em `remember(selected?.key)`) já segue a mesma regra: vive enquanto a chave da
conversa for a mesma.

Atenção ao `sending`: se o app sair no meio de um POST, `api.cancelCalls()` cancela a chamada e o `launch`
termina sem zerar `sending`. Em `pause()`, zere também: `mutable.value = mutable.value.copy(sending = false)`.

**Verificação**: teste `pauseKeepsAgentAndResumePollsAgain`.

### Passo 3: a consulta tenta de novo depois de falha

No laço de `poll()`, troque a linha 127 por: se `topics == null` e o estado ainda indica pergunta em andamento
(`mutable.value.running`), espere e continue; se `topics == null` sem pergunta em andamento, encerre como hoje.

```kotlin
if (topics == null) { if (!mutable.value.running) break; delay(ASIDE_RETRY_MS); continue }
if (!mutable.value.running) break
delay(ASIDE_POLL_MS)
```

com `private const val ASIDE_RETRY_MS = 3_000L`. No sucesso seguinte, limpe o erro de rede: ao gravar
`topics`, use `now.copy(loading = false, topics = topics, error = null)`. Um 401 já chama `unauthorized()`,
que leva à tela de login e para o controlador (`pause()` cancela o laço), então o laço não fica rodando sem
sessão.

**Verificação**: teste `pollingSurvivesOneFailure`.

### Passo 4: registro e paridade

`CHANGELOG.md` ("Não lançado"): uma linha ("Bate-papo paralelo: a pergunta não some quando o envio falha, a
janela continua funcionando depois de sair e voltar ao app, e a resposta volta a atualizar depois de uma
falha de rede"). Trate a paridade com o Rust conforme "Convenções".

## Plano de testes

Em `AsideControllerTest.kt`, seguindo `pollsWhileRunningThenStops` (mesma fábrica `topic(...)`, `waitFor`,
escopo com `SupervisorJob`):

- `rejectedQuestionKeepsDraftCallback`: `request` lança `ApiException` de status 409 no POST → `onAccepted` não
  é chamado e `state.error` fica preenchido; com POST aceito (201 com `{"topic":...}`) → `onAccepted` chamado
  uma vez. (Veja no próprio arquivo como os testes existentes constroem `ApiException`.)
- `pauseKeepsAgentAndResumePollsAgain`: `open("a")`, espera a primeira consulta, `pause()`, confirma
  `state.agentId == "a"` e que não há consulta nova em 1 s; `resume()` → há uma consulta nova.
- `pollingSurvivesOneFailure`: respostas em sequência `running`, falha (`IOException`), `complete` → o estado
  chega a `complete` e `error` termina `null`.

Verificação: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*AsideControllerTest*'`
→ todos passam, incluindo os 3 novos. Depois o comando de host completo.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'asides.close()' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt` → 2 ocorrências (em `close()` e, sob `if (!sameSession)`, em `open()`), nenhuma em `stop()`
- [ ] `grep -n 'draft = ""' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/AsideSheet.kt` só aparece dentro do retorno de `ask`
- [ ] `AsideControllerTest` passa com os 3 casos novos
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] `CHANGELOG.md` atualizado; paridade com o Rust feita ou registrada
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 093 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `AsideState` não tiver os campos `agentId`, `sending`, `running`, `error` usados acima.
- O teste de pausa mostrar que o laço continua consultando em segundo plano.

## Notas de manutenção

- A espera de 3 s entre tentativas é fixa. Se o servidor passar a mandar `Retry-After` para `/api/asides`, use
  `retryAfterMillis`, como `ReadingController` faz.
- O revisor deve conferir que nenhum caminho reenvia a pergunta sozinho.
