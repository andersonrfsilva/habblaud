# Plano 053: armazenamento local do iOS fora da thread da UI

> **Instruções ao executor**: exige Mac com Apple Silicon, Xcode e o runner `scripts/ios-native-tests.sh`. Sem Mac acessível, marque BLOCKED e pare. Atualize a linha 053 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt`.

## Status

- **Prioridade**: P2 (sem medição; só iOS)
- **Esforço**: M
- **Risco**: MED (concorrência do armazenamento, epochs e migração)
- **Depende de**: nenhum (base `778466839`)
- **Categoria**: perf
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

No iOS, ler o cache de conversa e o `local_state.json` executa Foundation e decode de JSON no dispatcher de quem chama. Ao abrir uma conversa, quem chama é o escopo da tela, ou seja, a thread da UI:

- `ReadingScreen.kt:71`: `rememberCoroutineScope()`.
- `ReadingController.kt:210-213`: `scope.launch { val cached = store.conversation(item.key) ... }`.

No Android, o mesmo caminho usa `withContext(Dispatchers.IO)` (`LocalStateStore.android.kt:141-148`). Nos caches reais do M35 (até 527 KiB), o decode custou até 13,5 ms no ART. No iPad, o valor não foi medido: o Mac esteve offline durante a auditoria.

## Estado atual

`android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt`:

- `:27-35`: `protectedWrite`/`privateRead` síncronos (Foundation).
- `:61-62`: `StateDisk.read()`/`save()` leem e gravam `local_state.json` inteiro.
- `:108-109`: `read`/`change` só usam `StateDisk.lock.withLock`, sem trocar de dispatcher.
- `:129`: `conversation(key)` lê e decodifica o arquivo da conversa.
- `:132-137`, `:120-125`, `:142-183`: `setConversation`, `setLastSession`, `addServer`/`removeServer`/`clear` fazem I/O sob o lock.
- `kotlinx.coroutines.IO` já é importado em código comum (`ReadingController.kt:6`), então `Dispatchers.IO` existe em Kotlin/Native.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Testes nativos | `bash scripts/ios-native-tests.sh` (Mac) | sem falhas |
| Smoke UI | `bash scripts/ios-smoke.sh` (Mac) | relatório ok |
| Suíte Android (regressão comum) | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:assembleDebug` | `BUILD SUCCESSFUL` |

## Escopo

**Pode modificar**: `iosMain/.../data/LocalStateStore.kt` e testes nativos existentes do armazenamento. **Fora**: código comum, Android, formato dos arquivos, epochs e migração.

## Passos

### Passo 1: medir no iPad ou simulador

Com uma conversa grande em cache, meça o tempo de `store.conversation(key)` (log temporário com `NSDate`, sem commit) e observe se a abertura engasga (Instruments: Time Profiler na thread principal). Registre os números.

### Passo 2: I/O em `Dispatchers.IO` dentro da loja

Envolva o corpo de `read`, `change`, `conversation`, `setConversation`, `setLastSession`, `addServer`, `removeServer` e `clear` em `withContext(Dispatchers.IO)`, mantendo o `StateDisk.lock` em volta, como hoje. Não altere a ordem lock → leitura → escrita, nem as verificações de epoch e `sequence`. `NSUserDefaults` é thread-safe e pode continuar como está.

### Passo 3: testes

Rode os testes nativos e o smoke. Se houver teste de concorrência do armazenamento, ele precisa passar sem mudança. Se não houver, adicione um que dispare `setConversation` e `clear` concorrentes e verifique que não sobra arquivo de epoch antiga (padrão: o equivalente Android `ConversationEpochInstrumentedTest.kt`).

### Passo 4: medir de novo

Repita o Passo 1. Registre antes e depois.

## Critérios de pronto

- [ ] Medição antes e depois registrada.
- [ ] Testes nativos e smoke verdes.
- [ ] Suíte Android verde.
- [ ] Linha 053 atualizada.

## Condições de PARADA

- Mac indisponível (BLOCKED).
- `StateDisk` exige thread específica em algum ponto.
- A medição do Passo 1 mostrar custo desprezível: marque REJECTED com os números.
