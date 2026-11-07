# Plano 048: servidor offline não bloqueia ações de outro servidor saudável

> **Instruções ao executor**: siga os passos e verificações. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 048 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum (base `778466839`)
- **Categoria**: bug
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

Com dois servidores (plano 042), filtrar pelo servidor A e ter o servidor B offline faz sumir o botão "Chamar contato" de A, embora A esteja conectado. Reproduzido no emulador com duas fixtures (`scripts/android-smoke.ts` nas portas 14748 e 14749): com as duas online o botão aparece; ao encerrar B, no mesmo processo, A continua verde, B fica vermelho e o botão some. Uma chamada nova a `HabblaudApi(A).access()` depois da queda de B teve sucesso.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:107-122`:

```kotlin
val selectedOrigin = states.entries.firstOrNull { it.value.selected != null }?.key
val actionOrigin = selectedOrigin ?: filter ?: apis.keys.first()
val api = apis.getValue(actionOrigin)
val controller = controllers.getValue(actionOrigin)
val combinedConnection = when {
    needsLogin.isNotEmpty() || states.values.any { it.connection == "offline" } -> "offline"
    states.values.any { it.connection == "connecting" } -> "connecting"
    else -> "connected"
}
val state = if (selectedOrigin != null) states.getValue(selectedOrigin) else states.getValue(actionOrigin).copy(
    sessions = mergeSessions(states, filter), ...
    connection = combinedConnection,
    terminalAllowed = states.values.any { it.terminalAllowed },
    error = null
)
```

`ReadingScreen.kt:168`: `CastActions(api, selected, state.connection == "connected" && (state.terminalAllowed || selected?.permission == true) && (selected != null || filter != null || apis.size == 1), ...)`. A ação vai para `api` (= `actionOrigin`), mas a disponibilidade usa a conexão combinada de todos os servidores.

`CastActions.kt:63-64`: `connected == false` marca `availability = "offline"` e esconde o botão (`:148-153`).

O indicador combinado (`combinedConnection`) continua correto para o resumo global da lista e não deve mudar.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Suíte host (inclui `commonTest`) | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `python3 scripts/android-instrumented-ci.py` | zero falhas |

## Escopo

**Pode modificar**: `ReadingScreen.kt` e um teste novo em `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/`. **Fora**: `CastActions.kt` (o contrato `connected` continua o mesmo), controladores e servidor.

## Passos

### Passo 1: disponibilidade calculada pelo servidor da ação

Crie uma função pura `internal fun actionsAvailable(state: ReadingState, loginRequired: Boolean, selectedPermission: Boolean): Boolean`. Ela retorna `!loginRequired && state.connection == "connected" && (state.terminalAllowed || selectedPermission)`.

Na linha 168, use `states.getValue(actionOrigin)` e `actionOrigin in needsLogin` como entradas. Mantenha a condição `(selected != null || filter != null || apis.size == 1)`.

### Passo 2: teste de comportamento

Em `commonTest`, seguindo o estilo de `ReadingConnectionTest.kt`, cubra:

- A conectado com terminal, B offline: ações de A disponíveis.
- A offline: indisponível.
- A precisa de login: indisponível.
- A conectado sem `terminalAllowed` e com sessão selecionada sem permissão: indisponível.

### Passo 3: reprodução manual

No emulador, com duas fixtures e login nas duas, selecione o filtro A e encerre a fixture B. O botão "Chamar contato" deve continuar visível. Registre uma captura ou o trecho do `uiautomator dump` com `content-desc="Chamar contato"`.

## Critérios de pronto

- [ ] Testes novos verdes e suíte verde.
- [ ] Reprodução manual registrada neste plano.
- [ ] Indicador global da lista sem mudança.
- [ ] Linha 048 atualizada.

## Condições de PARADA

- O botão depende de estado de outro servidor por motivo de produto documentado nos planos 042/032.
- A correção exige mudar `CastActions`.

## Notas de manutenção

- Toda ação dirigida a um servidor deve usar o estado desse servidor, não o combinado.
- 401 em B não foi reproduzido. O caso `needsLogin` fica coberto pelo teste do Passo 2.
