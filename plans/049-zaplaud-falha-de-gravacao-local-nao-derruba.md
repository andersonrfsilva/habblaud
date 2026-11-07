# Plano 049: falha ao gravar estado local não derruba o app

> **Instruções ao executor**: siga os passos e verificações. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 049 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum (base `778466839`)
- **Categoria**: bug
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

Reproduzido no emulador: com `files/datastore` do app sem permissão de escrita, a gravação real dos marcadores de "lido" (`saveSeen`) derrubou o processo na primeira tentativa:

```text
FATAL EXCEPTION: DefaultDispatcher-worker-8
java.io.FileNotFoundException: /data/data/br.com.xlinx.habblaud/files/datastore/local_state.preferences_pb.tmp: open failed: EACCES (Permission denied)
    at androidx.datastore.core.FileWriteScope.writeData(FileStorage.kt:199)
```

A mesma injeção no cache de conversa não derrubou, porque o Android já captura essa falha. No iOS, a gravação do cache lança exceção (`protectedWrite` usa `check`) e tem o mesmo risco. O gatilho real é raro (disco cheio ou falha de I/O), mas o efeito é o app fechando.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`:

- `:19`: `private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, sem `CoroutineExceptionHandler`. `SupervisorJob` isola os filhos, mas não trata exceção não capturada: ela vai para o handler padrão da thread e encerra o processo.
- `:144-148`: `seenJob = persistScope.launch { delay(delayMs); store.setSeenReplies(api.origin, marks, conversationEpoch) }`.
- `:180-186`: `persistScope.launch { store.setConversation(key, entries, truncated, write, kept) }`.
- `:129` e `:209`: `scope.launch { store.setLastSession(...) }` (escopo da tela, mesmo risco).

Lojas:

- Android `LocalStateStore.android.kt:297-304`: `context.localState.edit { ... }` propaga a `IOException`. `:152-174`: `setConversation` captura com `runCatching`.
- iOS `iosMain/.../LocalStateStore.kt:27-30`: `protectedWrite` com `check(...)`; `:132-137` `setConversation` e `:206-211` `setSeenReplies` propagam.

O estado em memória (`seen`, `conversation`) já é a fonte para a tela. A gravação é só persistência.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Suíte host | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `python3 scripts/android-instrumented-ci.py` | zero falhas |
| iOS (Mac) | `bash scripts/ios-native-tests.sh` | sem falhas |

## Escopo

**Pode modificar**: `ReadingController.kt` e um teste novo em `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/` (padrão: `ConversationEpochInstrumentedTest.kt`, que já usa `LocalStateStore` real). **Fora**: as lojas Android e iOS. A falha continua sendo lançada por elas; a contenção fica no chamador, que sabe que a gravação é secundária.

## Passos

### Passo 1: um ponto de lançamento contido

Em `ReadingController.kt`, crie `internal fun launchPersist(scope: CoroutineScope, block: suspend () -> Unit): Job` (privada ao arquivo ou `internal` para teste). Ela executa `block` e captura `Exception`, relançando `CancellationException`.

Na falha, não apague nada: os dados continuam em memória e a próxima gravação (próximo snapshot, `persist(3_000)` ou fechamento) tenta de novo. Use esse ponto nas quatro chamadas (`:129`, `:148`, `:186`, `:209`), mantendo `delay`, epochs e escopos como estão.

Não capture `Throwable` (OOM e erros de programação continuam visíveis).

### Passo 2: teste de dispositivo com falha real

Em `androidDeviceTest`:

1. Crie `LocalStateStore` real e adicione uma origem.
2. Torne `File(context.filesDir, "datastore")` e seus arquivos não graváveis (`setWritable(false, false)`; o teste roda no processo do app).
3. Chame `launchPersist(scope) { store.setSeenReplies(origin, mapOf("x" to 1L), epoch) }` e aguarde o `Job`.
4. Afirme que não houve exceção não capturada: instale um `Thread.setDefaultUncaughtExceptionHandler` de teste e restaure o anterior no `finally`.
5. No `finally`, restaure a permissão.

Um segundo caso: depois de restaurar a permissão, uma nova gravação persiste o valor.

### Passo 3: suíte e iOS

Rode os comandos da tabela. No Mac, rode os testes nativos. Sem Mac, registre como pendente.

## Critérios de pronto

- [ ] Testes novos verdes e suíte verde.
- [ ] `rg -n 'persistScope.launch|scope.launch \{ store\.' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt` → nenhuma ocorrência fora de `launchPersist`.
- [ ] Linha 049 atualizada.

## Condições de PARADA

- O teste não consegue impedir a escrita sem root.
- A contenção esconderia falha de cancelamento ou de epoch (comportamento dos planos 035/038).

## Notas de manutenção

- Gravação secundária disparada sem espera deve passar por `launchPersist`.
- Se um dia houver aviso ao usuário de armazenamento cheio, ele nasce aqui.
