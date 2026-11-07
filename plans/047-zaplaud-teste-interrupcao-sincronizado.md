# Plano 047: teste de interrupção espera a operação real, não o marcador provisório

> **Instruções ao executor**: siga os passos e as verificações. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 047 em `plans/README.md` e o status do 043, se este plano liberar o aceite dele.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt`.

## Status

- **Prioridade**: P1 (desbloqueia o aceite do plano 043)
- **Esforço**: S
- **Risco**: LOW (só teste)
- **Depende de**: nenhum. Faça na branch `advisor/043-zaplaud-estabilidade-avatares` (`778466839`), para fechar o gate da 043.
- **Categoria**: tests
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

`MessageSendTest.expiredStopStatusAlsoRequiresConfirmation` falhou no gate final da 043 (`expected:<confirm> but was:<null>`). Reproduzido: **14 falhas em 50** execuções no mesmo JVM. Uma variante que espera o ID da operação passou **50 de 50**. O produto está correto; o teste avança cedo demais.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt:9`:

```kotlin
data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0, val error: String? = null)
```

`MessageController.kt:259-273`: `interrupt` grava `MessageOperation()` (id nulo, status `"unknown"`) antes do POST e coloca a chave em `controlling`. Uma segunda chamada a `interrupt` enquanto a chave está em `controlling` retorna sem pedir confirmação (`:259`). O 404 do polling também gera `unknown`, só que com ID (`:345-349`).

`android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt:149-161`:

```kotlin
@Test fun expiredStopStatusAlsoRequiresConfirmation() = runBlocking {
    ...
        server.enqueue(MockResponse().setBody("{\"id\":\"stop1\",\"status\":\"queued\"}"))
        server.enqueue(MockResponse().setResponseCode(404))
        val c = controller(this, store, server); open(c, a); c.interrupt("agent")
        waitFor { c.state.value.control?.status == "unknown" }
        c.interrupt("agent")
        assertEquals("confirm", c.state.value.controlError)
        assertEquals(2, server.requestCount)
```

O `waitFor` aceita o marcador provisório.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Teste alvo | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*MessageSendTest*' --rerun-tasks` | todos passam |
| Suíte host | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |

## Escopo

**Pode modificar**: só `MessageSendTest.kt`. **Fora**: `MessageController.kt` e todo código de produção.

## Passos

### Passo 1: corrigir a espera

Troque a linha `waitFor { c.state.value.control?.status == "unknown" }` por espera da operação aceita e depois expirada:

```kotlin
waitFor { c.state.value.control?.id == "stop1" }
waitFor { c.state.value.control?.id == "stop1" && c.state.value.control?.status == "unknown" }
```

Mantenha as asserções de `confirm` e de 2 requisições (POST + GET 404, sem novo POST).

### Passo 2: provar estabilidade

Rode o teste alvo 20 vezes (`for i in $(seq 20); do <comando>; done`). Esperado: 20 de 20. Registre a contagem neste plano.

### Passo 3: suíte

Rode a suíte host. Esperado: verde, com o mesmo número de testes de antes.

## Critérios de pronto

- [ ] 20/20 execuções do teste alvo verdes.
- [ ] Suíte host verde.
- [ ] `git diff --stat` só em `MessageSendTest.kt`.
- [ ] Linhas 047 e 043 atualizadas no índice: 043 só fica DONE se os demais pendentes dela também estiverem resolvidos (Apple e medição).

## Condições de PARADA

- O teste falhar mesmo esperando o ID: aí a falha é de produto; relate com o stack.
- A correção parecer exigir mudança em `MessageController`.

## Notas de manutenção

- Em testes de controle e envio, espere sempre pelo `id` da operação: `status == "unknown"` também é o estado provisório antes do POST.
