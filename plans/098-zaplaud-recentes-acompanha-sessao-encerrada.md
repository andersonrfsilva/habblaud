# Plano 098: Zaplaud: a sessão que termina com o app aberto continua em Recentes

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 098 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: LOW (uma requisição a mais quando uma sessão sai da lista ao vivo)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

A aba Recentes é montada com uma leitura de `/api/sessions/recent` feita **uma vez**, quando o controlador
conecta. Uma sessão que começa e termina enquanto o app está aberto não estava nessa leitura; quando ela sai da
lista ao vivo, some das duas abas e só reaparece depois de o app reconectar (sair e voltar, perder a rede).
Quem deixa o app aberto o dia todo perde de vista exatamente as conversas que acabaram de terminar.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt`:

```kotlin
// :55-69
suspend fun refresh() {
    val access = wireJson.parseToJsonElement(api.access()).jsonObject
    terminalAllowed = access.flag("terminal")
    ...
    recent = if (terminalAllowed) {
        try {
            wireJson.parseToJsonElement(api.request("/api/sessions/recent")).jsonObject.objects("sessions").mapNotNull { item(it, null) }
        } catch (e: ApiException) { if (e.status == 403) { terminalAllowed = false; emptyList() } else throw e }
    } else emptyList()
    cast = ...
}
// :71-81  cada snapshot substitui `open`
fun snapshot(data: String): List<SessionItem> { ...; open = agents.filter { ... }.mapNotNull { ... }; return sessions() }
// :83-90  a lista final = abertas + recentes que não estão abertas
fun sessions(): List<SessionItem> {
    val live = open.associateBy { it.key }
    val historical = recent.associateBy { it.key }
    return open.map { ... } + recent.filterNot { live.containsKey(it.key) }.map { ... }
}
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`:

```kotlin
// :141-142  única chamada de refresh(), antes de abrir o stream
while (true) {
    try { repository.refresh(); break } catch (e: Exception) { ... }
// :156-157  tratamento de cada snapshot
is StreamEvent.Data -> if (event.type == "snapshot") {
    val sessions = repository.snapshot(event.data)
```

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-098 -b advisor/098-zaplaud-recentes-acompanha-sessao-encerrada producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-098`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Confira no Rust (`zaplaud-rs/crates/zaplaud-core`) quando a lista de recentes é relida; corrija junto ou registre a pendência em `docs/zaplaud-paridade.md`, linha "Lista: abertas/recentes".
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
- Testes novos: `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/` (regra pura, executado) e
  `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/RecentRefreshTest.kt` (fluxo inteiro, só compila)
- `CHANGELOG.md`; paridade conforme "Convenções"

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- O servidor (`/api/sessions/recent`).
- Paginação ou busca no histórico.

## Passos

### Passo 1: repositório relê só os recentes e diz quando precisa

Em `SessionRepository`, extraia de `refresh()` a leitura de recentes para
`suspend fun refreshRecent()` (mesmo corpo do bloco `recent = ...`, incluindo o tratamento do 403) e faça
`refresh()` chamá-la. Faça `snapshot()` registrar se alguma chave que estava em `open` antes deixou de estar e
não existe em `recent`: guarde em `var recentStale = false` (atribua `true` nesse caso). `refreshRecent()`
zera `recentStale` ao terminar com sucesso.

### Passo 2: controlador relê quando uma sessão some, uma vez por vez

Em `ReadingController`, depois de `repository.snapshot(event.data)`: se `repository.recentStale` e não houver
releitura em andamento, lance no `scope` uma corrotina (guarde o `Job` num campo e cancele-o em `stop()`) que
chama `repository.refreshRecent()` e, ao terminar, publica `mutable.value = mutable.value.copy(sessions = repository.sessions())`
seguido de `publishMarks()` e `refreshUnread()`. Em falha (`ApiException`/`IOException`), não faça nada: o
próximo snapshot com `recentStale` ainda `true` tenta de novo. Não chame `refresh()` inteiro (ele refaz
`/api/access` e o elenco).

### Passo 3: teste

(Corrigido na primeira execução: `HabblaudApi` não pode ser criado em teste de host, porque o construtor usa o
contexto Android do armazenamento. `ConversationStreamTest` usa `ConversationRepository`, não `HabblaudApi`.)

Extraia a decisão para uma função pura em `data/SessionRepository.kt`:
`internal fun recentIsStale(before: Collection<SessionKey>, now: Collection<SessionKey>, recent: Collection<SessionKey>): Boolean`
(alguma chave de `before` não está em `now` nem em `recent`); `snapshot()` a chama.

- Teste de host, **executado**, em `commonTest` (`kotlin.test`, sem `HabblaudApi`): sumiu e não está em recentes
  → `true`; sumiu mas já está em recentes → `false`; ninguém sumiu → `false`; primeiro snapshot (`before` vazio)
  → `false`.
- `RecentRefreshTest.kt` em `androidDeviceTest`, no padrão de `LastSessionOrderInstrumentedTest.kt`
  (`HabblaudApi` real + `MockWebServer` com `Dispatcher`): `refresh()` com recentes vazio; snapshot com X aberto;
  snapshot sem X → `recentStale == true` e `sessions()` sem X; recentes passa a devolver X; `refreshRecent()` →
  `sessions()` contém X com `agentId == null` e `recentStale == false`. **Só compila**
  (`:shared:compileAndroidDeviceTest`); executar exige emulador autorizado.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*RecentStale*'`
→ passa; `:shared:compileAndroidDeviceTest` → `BUILD SUCCESSFUL`. Depois o comando de host completo.

### Passo 4: registro

`CHANGELOG.md` ("Não lançado"): "Zaplaud: a conversa que termina com o app aberto aparece em Recentes sem precisar reconectar". Paridade conforme "Convenções".

## Plano de testes

Os dois testes do passo 3. Os JSON de snapshot e de recentes podem ser copiados de `SessionIdentityTest.kt` e `LastSessionOrderInstrumentedTest.kt`.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'refreshRecent' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt` → definição e uso
- [ ] Teste de host de `recentIsStale` passa; `RecentRefreshTest` (aparelho) compila
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] `CHANGELOG.md` atualizado; paridade feita ou registrada
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 098 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A sessão encerrada já aparecer em `sessions()` sem releitura (por exemplo, `liveOrClosed` mantê-la): o defeito
  não existe como descrito; relate.
- O servidor demorar a incluir a sessão em `/api/sessions/recent` logo depois do encerramento (confira
  `server/` antes de assumir): se houver atraso, relate em vez de inventar repetição por tempo.

## Notas de manutenção

- Se o servidor passar a mandar os recentes pelo stream, esta releitura sai.
- O revisor deve conferir que um snapshot comum (ninguém saiu) não dispara requisição.
