# Plano 092: Zaplaud Android: SVG da conversa sem entidades internas, resposta do Mermaid com limite e log sem conteúdo

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 092 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/MermaidPainter.android.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MermaidCodeFence.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: LOW (SVG que dependa de entidade interna ou DOCTYPE deixa de ser desenhado e volta a código)
- **Depende de**: 090 (gate)
- **Categoria**: security
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Blocos ` ```svg ` e imagens `.svg` escritos pelo agente são entregues ao AndroidSVG 1.4 sem preparo. Essa
biblioteca vem com entidades internas XML **ligadas** (conferido no bytecode: o inicializador estático de
`com.caverock.androidsvg.SVG` grava `true` em `enableInternalEntities`). Um SVG pequeno com entidades aninhadas
expande para um documento enorme na thread da interface e trava ou derruba o app; o limite de 200.000
caracteres vale antes da expansão. Além disso, a resposta de `POST /api/mermaid` é lida inteira para a memória
sem limite, e as mensagens de erro (que incluem trechos do conteúdo) vão para o log do sistema no release.

## Estado atual

`android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/MermaidPainter.android.kt:12-13`:

```kotlin
internal actual fun mermaidPainter(bytes: ByteArray, density: Density): Painter {
    val svg = SVG.getFromString(bytes.decodeToString())
```

A biblioteca oferece `SVG.setInternalEntitiesEnabled(boolean)` (método estático público).

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MermaidCodeFence.kt`:

```kotlin
// :95   limites de entrada: svg 200.000 caracteres, mermaid 20.000
if ((!svg && !language.equals("mermaid", ignoreCase = true)) || !closed || source.isBlank() || source.length > if (svg) 200_000 else 20_000) {
// :100  SVG da resposta vai direto ao leitor; Mermaid passa antes pelo servidor
var bytes by remember(key) { mutableStateOf(if (svg) svgThemeColors(source).encodeToByteArray() else mermaidCache[key]) }
// :109-113
val painter = remember(bytes, density) {
    try { bytes?.let { mermaidPainter(it, density) } }
    catch (e: Exception) {
        if (e is CancellationException) throw e
        println("Zaplaud mermaid: SVG inválido: $e")
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt:116-123`:

```kotlin
suspend fun mermaid(source: String): ByteArray? = try {
    request("/api/mermaid", buildJsonObject { put("source", source) }).encodeToByteArray()
} catch (e: Exception) {
    currentCoroutineContext().ensureActive()
    // O bloco volta a código; o motivo fica no log (logcat System.out no Android).
    println("Zaplaud mermaid: pedido falhou: $e")
    null
}
```

`request()` usa `response.bodyAsText()` (`HabblaudApi.kt:67`), sem limite de tamanho. O padrão de leitura
limitada do projeto está em `HabblaudApi.avatarBytes` (logo abaixo, `:125-140`) e em
`data/Files.kt` (`boundedImageBytes`).

O iOS usa `decodeToSvgPainter` do Compose Resources (`MermaidPainter.ios.kt`); não é alvo deste plano.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-092 -b advisor/092-zaplaud-svg-e-mermaid-sem-entrada-ilimitada producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-092`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O Rust desenha Mermaid localmente com outra biblioteca; nada a portar. Não mude a tabela de paridade.
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
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/MermaidPainter.android.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MermaidCodeFence.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt` (só `mermaid`)
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/` (teste novo `MermaidResponseLimitTest.kt`)
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/` (teste novo `SvgEntitiesInstrumentedTest.kt`)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Trocar o AndroidSVG por outra biblioteca: considerado e rejeitado na auditoria (sem defeito além deste).
- `server/http/app.ts` (limite de saída do `/api/mermaid` no servidor): o cliente se protege sozinho.
- `MermaidPainter.ios.kt` e `MermaidPainter.jvm.kt`.
- `MarkdownImages.kt` (plano 091); ele chama `mermaidPainter`, então herda a proteção sem mudança.

## Passos

### Passo 1: entidades internas desligadas e DOCTYPE recusado

Em `MermaidPainter.android.kt`, antes de `SVG.getFromString`:

```kotlin
val text = bytes.decodeToString()
// Conteúdo da conversa: sem DOCTYPE não há entidade para expandir (e o leitor não as resolve).
require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) { "SVG com DOCTYPE" }
SVG.setInternalEntitiesEnabled(false)
val svg = SVG.getFromString(text)
```

`require` lança `IllegalArgumentException`, que os dois chamadores já capturam (`MermaidCodeFence.kt:111`,
`MarkdownImages.kt:80`) e tratam voltando ao bloco de código ou ao link.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 2: resposta do Mermaid com teto de bytes e tipo conferido

Em `HabblaudApi.mermaid`, não use `request()`. Faça a chamada com `client().preparePost(...)`/`execute`,
seguindo o formato de `avatarBytes` no mesmo arquivo: `authorize(this)`, corpo JSON, `receive(response)`,
exigir status de sucesso e `Content-Type` começando com `image/svg+xml`, e ler no máximo
`MERMAID_SVG_MAX_BYTES = 2 * 1024 * 1024` bytes (acima disso, `null`). Um erro 401 deve continuar passando por
`receive(response)`, que já trata a sessão vencida.

**Verificação**: teste `MermaidResponseLimitTest` do "Plano de testes".

### Passo 3: log sem conteúdo

Troque os dois `println` por mensagens fixas com a classe da exceção, sem a mensagem dela:
`println("Zaplaud mermaid: pedido falhou (${e::class.simpleName})")` e
`println("Zaplaud mermaid: SVG recusado (${e::class.simpleName})")`. A mensagem de exceção do leitor XML cita
trechos do documento.

**Verificação**: `grep -n 'println' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MermaidCodeFence.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt`
→ nenhuma linha contém `$e"` nem `: $e`.

## Plano de testes

- `MermaidResponseLimitTest.kt` (androidHostTest, `MockWebServer`, padrão `FilesApiTest.kt`): resposta 200
  `image/svg+xml` pequena → bytes iguais; resposta de `MERMAID_SVG_MAX_BYTES + 1` bytes → `null`; resposta 200
  com `Content-Type: text/html` → `null`; resposta 422 → `null`.
- `SvgEntitiesInstrumentedTest.kt` (androidDeviceTest, porque AndroidSVG precisa das classes do Android; padrão
  `AvatarCacheInstrumentedTest.kt`): (a) SVG simples com `viewBox` → `mermaidPainter` devolve painter com
  `intrinsicSize` positivo; (b) SVG com `<!DOCTYPE svg [<!ENTITY a "x">]>` → lança `IllegalArgumentException`.
  Este teste só roda em emulador: escreva-o e confirme que **compila**
  (`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest`); a
  execução depende de autorização do dono.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'setInternalEntitiesEnabled(false)' android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/MermaidPainter.android.kt` → 1 ocorrência
- [ ] `MermaidResponseLimitTest` existe com 4 casos e passa
- [ ] `:shared:compileAndroidDeviceTest` termina em `BUILD SUCCESSFUL`
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] Nenhum `println` do escopo interpola a exceção inteira
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 092 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `SVG.setInternalEntitiesEnabled` não existir na versão resolvida do AndroidSVG (a auditoria conferiu a 1.4).
- O servidor de produção devolver, para diagramas válidos comuns, SVG com `<!DOCTYPE`: confira
  `server/` (`mermaidSvg`) antes; se devolver, pare, porque o passo 1 quebraria todos os diagramas.
- O nome da tarefa `:shared:compileAndroidDeviceTest` não existir: liste com
  `android/gradlew -p android :shared:tasks --all | grep -i devicetest` e use a de compilação; não rode `connected*`.

## Notas de manutenção

- `setInternalEntitiesEnabled` é global ao processo; como o app não tem outro uso do AndroidSVG, não há efeito
  colateral. Se surgir um uso com SVG confiável que precise de entidades, isole-o.
- Ao atualizar o AndroidSVG, repita a conferência do valor padrão.
