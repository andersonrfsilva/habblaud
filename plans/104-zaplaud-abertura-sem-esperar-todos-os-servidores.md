# Plano 104: Zaplaud: a lista abre sem esperar a resposta de cada servidor

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 104 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: MED (a abertura decide quem precisa de login; errar mostra tela de login indevida)
- **Depende de**: 090 (gate)
- **Categoria**: perf
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Na abertura, o app consulta `/api/access` de cada servidor, **um depois do outro**, e só então mostra a lista.
Um servidor desligado ou lento (Tailscale fora, notebook dormindo) segura a tela inicial pelo tempo do limite
de rede, mesmo com os outros servidores saudáveis. Com dois ou três servidores isso é o caso comum, não a
exceção. Não foi medido; o custo no pior caso é o tempo limite de conexão multiplicado pelos servidores fora do ar.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt:84-106`:

```kotlin
LaunchedEffect(Unit) {
    try {
        servers = store.servers()
        for (origin in servers) {
            val api = withContext(Dispatchers.IO) { HabblaudApi(origin) }
            apis[origin] = api
            if (api.cookies.requestHeader("$origin/api/access") == null) {
                needsLogin = needsLogin + origin
            }
            try {
                api.access()                         // espera a rede, em série
                needsLogin = needsLogin - origin
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is ApiException && e.status == 401) needsLogin = needsLogin + origin
            }
        }
        if (servers.isNotEmpty() && store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        message = strings(Res.string.storage_error)
    } finally { loaded = true }
}
```

A decisão "precisa de login" já tem uma parte local e imediata (não há cookie guardado). A parte de rede
(`api.access()`) só confirma ou descobre um cookie vencido (401).

Depois de carregado, cada servidor tem um `ReadingController` (`ReadingScreen.kt:131-146`) que, ao iniciar,
chama `repository.refresh()` (que faz `/api/access` de novo) e trata 401 chamando `onUnauthorized(origin)`, que
marca o servidor como "precisa de login". Ou seja: o 401 já é descoberto pelo controlador, sem a consulta da
abertura.

Se o plano 096 já foi mesclado, a chamada `notificationsPlatform.setEnabled(true)` está fora do `try`; respeite
a posição que encontrar.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-104 -b advisor/104-zaplaud-abertura-sem-esperar-todos-os-servidores producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-104`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Confira como o Rust abre com vários servidores; registre em `docs/zaplaud-paridade.md` só se o comportamento visível divergir.
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt` (só o `LaunchedEffect` de abertura)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `ReadingScreen.kt`, `ReadingController.kt`, `SessionRepository.kt`.
- O fluxo de login e de remoção de servidor no mesmo arquivo.

## Passos

### Passo 1: medir antes (opcional, com autorização)

Se o dono autorizar o emulador: com dois servidores cadastrados e um deles desligado, meça o tempo do toque no
ícone até a lista aparecer (`adb shell am start -W` não serve aqui; use
`adb logcat` com um `println` temporário ou cronometre). Anote. Sem autorização, pule e diga no índice que não
houve medição.

### Passo 2: mostrar a lista depois da parte local; confirmar pela rede em paralelo

Reorganize o efeito em duas fases:

1. Fase local, em série e rápida: para cada servidor, crie o `HabblaudApi`, guarde em `apis` e marque
   `needsLogin` se não houver cookie. Ao fim, `loaded = true`.
2. Fase de rede, em paralelo: `coroutineScope { servers.forEach { origin -> launch { ... api.access() ... } } }`,
   com o mesmo tratamento de hoje por servidor (sucesso tira de `needsLogin`; 401 põe; outras falhas não
   mudam nada).

Mantenha o `try/catch` que transforma falha de **armazenamento** em `storage_error` em volta da fase local
apenas; falha de rede já é tratada por servidor. `needsLogin` é estado do Compose lido e escrito na thread
principal (o `LaunchedEffect` roda nela), então as atualizações concorrentes das corrotinas filhas são seguras
desde que não troquem de dispatcher; não use `Dispatchers.IO` em volta das atribuições.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 3: conferir os instrumentados de login (com autorização)

`HttpLoginInstrumentedTest.savedHttpOriginStillRequiresConfirmation`
(`android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt:161`) espera a marca de
"entrar de novo" depois de um 401 em `/api/access` e confere que essa é a primeira requisição. Com a mudança,
pode haver uma segunda requisição a `/api/access` vinda do controlador antes do 401 ser processado. Se o dono
autorizar, rode `python3 scripts/android-instrumented-ci.py`; sem autorização, confirme que os testes compilam
(`:app:compileDebugAndroidTestKotlin`) e registre "instrumentados não executados".

## Plano de testes

Não há teste de host para este efeito de composição. A proteção são os instrumentados de login e de várias
origens (`HttpLoginInstrumentedTest`, `FixtureUiInstrumentedTest`), que só rodam com autorização.

## Critérios de pronto

Todos precisam valer:

- [ ] Em `ZaplaudApp.kt`, `loaded = true` é atribuído antes de qualquer `api.access()`
- [ ] As consultas `api.access()` da abertura rodam em corrotinas filhas (`launch`), não num `for` em série
- [ ] Comando de host completo e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 104 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A tela principal (`ReadingScreen`) depender de `needsLogin` estar completo no primeiro quadro de um modo que
  mostre "entrar" para um servidor com cookie válido: relate.
- Com autorização, algum instrumentado de login falhar depois da mudança: não ajuste o teste; relate.

## Notas de manutenção

- Há duas consultas a `/api/access` por servidor na abertura (esta e a do controlador). Remover a da abertura por
  completo é possível, mas muda quem descobre o 401; não faça sem os instrumentados rodando.
- Prioridade P3: faça depois dos planos de correção (091 a 099).
