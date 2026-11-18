# Plano 091: Zaplaud: imagens do markdown com memória limitada, sem redirecionamento e com falha visível

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 091 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownLinks.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA. **Atenção**: `MarkdownImages.kt` e as mudanças em `data/Files.kt`/`MarkdownLinks.kt` ainda não estavam commitados em `7236378` (trabalho em andamento do dono). O diff vai mostrar esses arquivos como novos; compare os trechos abaixo com o arquivo.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (reduzir a imagem pode piorar a tela cheia; recusar redirecionamento pode quebrar imagem servida por CDN)
- **Depende de**: 090 (gate); precisa do trabalho de imagens commitado
- **Categoria**: bug / security / perf
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O Zaplaud passou a desenhar imagens citadas no markdown das respostas. O conteúdo vem do agente, portanto não é
confiável. Hoje: (1) a imagem é decodificada no tamanho original, dentro da composição (thread da interface); o
limite de 10 MiB vale para o arquivo, não para os pixels, e um PNG pequeno de 6.000 × 4.000 ocupa ~92 MiB
decodificado; (2) o cache guarda até 32 arquivos de até 10 MiB cada, ou seja, até 320 MiB; (3) cada download
aloca um buffer de 10 MiB mesmo para uma imagem de 20 KB; (4) o cliente HTTP das imagens remotas segue
redirecionamentos, então o toque em "Carregar imagem" autoriza um destino e a requisição pode ir a outro; (5)
quando a carga ou a leitura falha, nada aparece. Em uso real isso vira travamento ao rolar e fechamento por
falta de memória.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt`:

```kotlin
// :27-28
// A composição e seus efeitos acessam o cache na thread da interface, como nos diagramas.
private val imageCache = mutableMapOf<Pair<HabblaudApi, String>, ByteArray>()
// :58-63  cache por quantidade (32), não por bytes
            if (loaded != null) {
                imageCache.remove(key)
                if (imageCache.size >= 32) imageCache.remove(imageCache.keys.first())
                imageCache[key] = loaded
                bytes = loaded
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e      // :64-65 falha some sem estado
        } finally { loading = false }
// :70-85  decodificação dentro de remember, na composição, em tamanho real
    val painter = remember(bytes, density) {
        try {
            bytes?.let {
                ...
                (if (svg) mermaidPainter(it, density) else BitmapPainter(it.decodeToImageBitmap())).takeIf { ... }
// :92-97  sem painter: só a remota mostra algo (o botão); data: e arquivo do host ficam em branco
    } else if (kind == ImageKind.REMOTE) {
        TextButton(onClick = { scope.launch { load() } }, enabled = !loading) { Text(stringResource(Res.string.image_load)) }
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt`:

```kotlin
// :77-80
internal const val IMAGE_MAX_BYTES = 10 * 1024 * 1024
// Cliente separado: URLs da conversa nunca recebem os cookies ou a autorização da API.
private val imageClient by lazy { HttpClient() }          // padrão do Ktor: segue redirecionamentos
// :97-99
internal suspend fun remoteImageBytes(url: String): ByteArray? = try {
    if (!isHttpMarkdownLink(url)) null
    else imageClient.prepareGet(url).execute { boundedImageBytes(it) }
// :106-117
private suspend fun boundedImageBytes(response: HttpResponse): ByteArray? {
    if (!response.status.isSuccess() || (response.contentLength() ?: 0) > IMAGE_MAX_BYTES) return null
    val channel = response.bodyAsChannel()
    val bytes = ByteArray(IMAGE_MAX_BYTES + 1)             // 10 MiB por download, sempre
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownLinks.kt:10-16` (`isHttpMarkdownLink`) aceita autoridade com `usuario:senha@`:
`authority.substringAfterLast('@').substringBefore(':').isBlank()`.

O cliente da API (`HabblaudApi`) já recusa redirecionamento: `HabblaudApi.kt:58`
`if (response.status.value in 300..399) throw RedirectException()`; os motores são criados com
`followRedirects = false` nos `HttpTransport.*.kt`. Siga esse padrão.

Decisões já tomadas e que devem ser preservadas (`docs/zaplaud-paridade.md`, "Imagens da conversa"): imagem
remota só carrega depois do toque e sem cookies do Habblaud; `data:` e caminho absoluto do host carregam
sozinhos; limite de 10 MB; falha deixa o link no texto.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-091 -b advisor/091-zaplaud-imagens-do-markdown-com-limites producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-091`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O cliente Rust tem a mesma função (`zaplaud-rs/crates/zaplaud-ui/src/images.rs` e `markdown/images.rs`, também não commitados). Este plano só cobre o Kotlin; registre em `docs/zaplaud-paridade.md`, na linha "Imagens do markdown", que os limites de pixels, o cache por bytes e a recusa de redirecionamento estão **pendentes de conferência no Rust**.
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt`
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownLinks.kt` (só `isHttpMarkdownLink`)
- `android/shared/src/commonMain/composeResources/values/strings.xml` (uma string de falha)
- `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/MarkdownLinksTest.kt`
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MarkdownImagesTransportTest.kt` (criar)
- `docs/zaplaud-paridade.md`, `CHANGELOG.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `MermaidCodeFence.kt` e `MermaidPainter.*.kt`: SVG e Mermaid são o plano 092.
- `zaplaud-rs/`: a conferência do Rust vira pendência na tabela de paridade.
- A regra de consentimento (toque para imagem remota) e o limite de 10 MiB do arquivo.
- `README.pt-BR.md`: a frase errada sobre imagens é corrigida no plano 102.

## Passos

### Passo 1: cliente de imagem sem redirecionamento e sem credenciais na URL

Em `data/Files.kt`, crie o cliente com redirecionamento desligado: `HttpClient { followRedirects = false }`.
Em `boundedImageBytes`, uma resposta 3xx já cai em `!response.status.isSuccess()` e devolve `null` (a imagem
fica só como link). Não implemente "seguir com nova validação": recusar é a opção simples e segura.

Em `MarkdownLinks.kt`, faça `isHttpMarkdownLink` devolver `false` quando a autoridade contiver `@`
(`if ('@' in authority) return false`) ou a URL contiver barra invertida.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*MarkdownLinksTest*'` → passa, com os casos novos do "Plano de testes".

### Passo 2: leitura sem buffer fixo de 10 MiB

Reescreva `boundedImageBytes` para acumular em blocos pequenos (por exemplo `ByteArray(64 * 1024)` reutilizado
e um acumulador que cresce), parando e devolvendo `null` ao passar de `IMAGE_MAX_BYTES`. Quando
`contentLength()` vier e couber, pode dimensionar o destino por ele. Mantenha `ensureActive()` no laço e o
resultado `null` para corpo vazio.

**Verificação**: teste de transporte do "Plano de testes" (corpo maior que o limite → `null`; corpo de 20 KB →
bytes idênticos).

### Passo 3: cache limitado por bytes

Em `MarkdownImages.kt`, mantenha o mapa mas controle a soma: constante `IMAGE_CACHE_MAX_BYTES = 24 * 1024 * 1024`;
ao inserir, remova as entradas mais antigas (`keys.first()`) até `soma + novo <= limite`; uma imagem maior
que o limite não entra no cache (é mostrada, mas recarrega depois). Para `data:`, não guarde no cache: a
chave seria o próprio texto base64 (duplicaria a memória) e decodificar de novo é barato.

### Passo 4: decodificação fora da composição e com teto de pixels

Troque o `remember(bytes, density) { ...decodeToImageBitmap()... }` por estado preenchido num
`LaunchedEffect(bytes)` que decodifica em `Dispatchers.Default` (`withContext`). Antes de decodificar um raster,
leia só as dimensões e recuse acima de 16 milhões de pixels (4.096 × 4.096): nesse caso trate como falha de
leitura. Use um `expect fun imageDimensions(bytes: ByteArray): Pair<Int, Int>?` em commonMain com `actual` em
`androidMain` (`BitmapFactory.Options().inJustDecodeBounds = true`) e em `iosMain`/`jvmMain` (Skia:
`org.jetbrains.skia.Image.makeFromEncoded(bytes)` e `width`/`height`; no JVM é só para continuar compilando).
SVG continua indo para `mermaidPainter` (os limites dele são o plano 092).

Não implemente redução de tamanho (downsampling): recusar acima do teto é o corte simples. Deixe o comentário
`// shortcut: acima de 16 MP a imagem não é desenhada; reduzir na decodificação se usuários reclamarem`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 5: falha visível

Acrescente estado `failed` em `MarkdownImage` (carga que devolveu `null`, exceção, dimensão recusada ou
decodificação que falhou). Com `failed`, mostre uma linha de texto com a string nova `image_failed`
("Não foi possível mostrar a imagem") em `strings.xml`, no estilo `bodySmall` com a cor
`MaterialTheme.colorScheme.onSurfaceVariant`; para imagem remota, mantenha o botão "Carregar imagem" ao lado
para tentar de novo. O link continua no texto da mensagem, como hoje.

**Verificação**: `grep -n 'image_failed' android/shared/src/commonMain/composeResources/values/strings.xml` → 1
linha; comando de host completo → `BUILD SUCCESSFUL`.

### Passo 6: registro

`CHANGELOG.md` ("Não lançado"): uma linha dizendo que imagens grandes demais ou que falham agora mostram um
aviso e que imagens remotas não seguem redirecionamento. `docs/zaplaud-paridade.md`: na linha das imagens do
markdown, acrescente a pendência de conferência no Rust descrita em "Convenções".

## Plano de testes

- `MarkdownLinksTest.kt` (padrão do próprio arquivo): `isHttpMarkdownLink("https://user:pw@example.com/a.png")`
  → `false`; `isHttpMarkdownLink("https://example.com\\@evil/a.png")` → `false`; os casos atuais seguem passando.
- `MarkdownImagesTransportTest.kt` em `androidHostTest`, com `MockWebServer` (padrão:
  `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/FilesApiTest.kt`): (a) resposta 302 com
  `Location` para outra rota do mesmo servidor → `remoteImageBytes` devolve `null` e o servidor registra **uma**
  requisição; (b) a requisição de `remoteImageBytes` não leva cabeçalho `Cookie` nem `Authorization`; (c) corpo
  de 20 KB → bytes idênticos; (d) corpo de `IMAGE_MAX_BYTES + 1` → `null`.
- Verificação: comando de host completo → `BUILD SUCCESSFUL`, com os testes novos na contagem.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'followRedirects = false' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt` → 1 ocorrência
- [ ] `grep -n 'ByteArray(IMAGE_MAX_BYTES' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt` → nenhuma ocorrência
- [ ] `grep -n 'decodeToImageBitmap' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt` não aparece dentro de `remember(` (decodificação num efeito)
- [ ] `MarkdownImagesTransportTest` existe com os 4 casos e passa
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL`
- [ ] `CHANGELOG.md` e `docs/zaplaud-paridade.md` atualizados
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 091 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `MarkdownImages.kt` não existir no branch `producao` (o dono ainda não commitou o trabalho de imagens): pare e
  peça o commit, ou a ordem expressa de trabalhar direto no clone do dono.
- O `actual` de `imageDimensions` no iOS não compilar e não houver Mac para conferir: pare e relate; não entregue
  um `actual` que devolve sempre `null`.

## Notas de manutenção

- Se um dia entrar redução de tamanho, o teto de 16 MP vira o tamanho alvo da prévia e a tela cheia pede uma
  decodificação maior.
- O revisor deve conferir que nenhuma requisição de imagem remota sai antes do toque e que o cliente de imagem
  continua separado do cliente da API.
- No iOS, o cliente Darwin padrão usa o armazenamento de credenciais do sistema; se isso for problema, o cliente
  de imagem deve ganhar a mesma configuração do `HttpTransport.ios.kt`. Não verificado (sem Mac).
