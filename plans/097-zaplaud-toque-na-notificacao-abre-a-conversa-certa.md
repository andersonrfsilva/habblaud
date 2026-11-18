# Plano 097: Zaplaud: o toque na notificação abre a conversa tocada, mesmo com a última conversa em outro servidor

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 097 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Com dois ou mais servidores, cada um tem seu controlador e cada controlador restaura sozinho "a última conversa
aberta" quando recebe a primeira lista. Ao tocar numa notificação com o app fechado, o controlador do servidor
tocado recebe a conversa da notificação, mas o controlador do **outro** servidor ainda restaura a última
conversa dele quando a lista chega. Ficam duas conversas selecionadas e a tela mostra a do primeiro servidor da
lista, que pode não ser a tocada. O usuário toca num pedido de aprovação e cai em outra conversa.
Achado por leitura do código; não foi reproduzido em aparelho.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:154-162`:

```kotlin
val pending by pendingSession.collectAsStateWithLifecycle()
LaunchedEffect(pending) { pending?.let { target ->
    controllers[target.origin]?.let { controller ->
        controllers.filterKeys { it != target.origin }.values.forEach { if (it.state.value.selected != null) it.close() }
        controller.openKey(target)
    }
    pendingSession.value = null
} }
val selectedOrigin = states.entries.firstOrNull { it.value.selected != null }?.key     // o primeiro com seleção vence
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`:

```kotlin
// :131-132
private var restore: SessionKey? = null
private var restoreLoaded = false
// :150  (dentro de start(), antes de abrir o stream)
if (!restoreLoaded) { restore = store.lastSession()?.takeIf { it.origin == api.origin }; restoreLoaded = true }
// :170  (a cada snapshot)
restore?.let { key -> sessions.find { it.key == key }?.let { item -> restore = null; open(item) } }
// :187  close() já faz `restore = null`, mas não marca restoreLoaded
// :266-270
fun openKey(key: SessionKey) {
    if (key.origin != api.origin) return
    val item = mutable.value.sessions.find { it.key == key }
    if (item != null) open(item) else { restore = key; restoreLoaded = true }
}
```

O efeito em `ReadingScreen` só fecha os outros controladores que **já** têm seleção. Um controlador que ainda
não carregou a restauração (`restoreLoaded == false`) vai carregá-la depois, na linha 150, e abrir a conversa
antiga na linha 170.

A função `open(item)` da própria tela (`ReadingScreen.kt:150-153`) também fecha os outros antes de abrir; a
corrida só existe com a restauração automática.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-097 -b advisor/097-zaplaud-toque-na-notificacao-abre-a-conversa-certa producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-097`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. No Rust o aviso abre a sessão pela ação "Abrir"; confira se a restauração da última conversa pode competir e registre em `docs/zaplaud-paridade.md` se não corrigir junto.
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt` (um método novo)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (o efeito das linhas 155-161)
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/LastSessionOrderInstrumentedTest.kt` (caso novo; só compilação sem autorização)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `MainActivity.kt` (como a rota sai do `Intent`).
- A regra de restaurar a última conversa na abertura normal.

## Passos

### Passo 1: controlador sabe desistir da restauração

Em `ReadingController`, perto de `openKey`:

```kotlin
/** Outra conversa foi pedida em outro servidor: este não restaura a última dele. */
fun cancelRestore() { restore = null; restoreLoaded = true }
```

### Passo 2: a rota da notificação cancela a restauração dos outros

No efeito de `ReadingScreen.kt:155-161`, para cada controlador de outro servidor, chame `cancelRestore()`
sempre e `close()` se houver seleção:

```kotlin
controllers.filterKeys { it != target.origin }.values.forEach { it.cancelRestore(); if (it.state.value.selected != null) it.close() }
```

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 3: teste de aparelho (compilar; executar só com autorização)

`LastSessionOrderInstrumentedTest.kt` já monta `ReadingController` com `LocalStateStore` real e servidor
falso; leia-o e acrescente o caso `notificationTargetBeatsRestoreOnAnotherServer`: grave como última sessão
uma conversa do servidor B; crie os controladores de A e de B; chame `b.cancelRestore()` e `a.openKey(alvoA)`;
inicie os dois e entregue as listas; afirme que `a.state.value.selected?.key == alvoA` e
`b.state.value.selected == null`.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest`
→ `BUILD SUCCESSFUL`.

## Plano de testes

O caso do passo 3. Sem emulador autorizado, o aceite é compilar; registre no índice que não foi executado.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'cancelRestore' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` → definição e uso
- [ ] Comando de host completo e `:shared:compileAndroidDeviceTest` terminam em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 097 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `LastSessionOrderInstrumentedTest.kt` não permitir montar dois controladores com servidores distintos sem
  reescrever a infraestrutura do teste: entregue os passos 1 e 2 e relate o teste como pendente.

## Notas de manutenção

- Se a rota da notificação passar a sobreviver à morte do processo, ela deve continuar tendo prioridade sobre a
  restauração.
- Reteste manual sugerido ao dono: dois servidores, última conversa no servidor 1, tocar numa notificação do
  servidor 2 com o app fechado.
