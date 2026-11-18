# Plano 117: Zaplaud Kotlin: lista de entregas da sessão com download dos arquivos (opção A do spike 112)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 117 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3927246..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/FilesSheet.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`
> Se algum arquivo do escopo mudou desde `3927246`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (resposta do servidor pode ser grande; conteúdo é do agente e não é confiável)
- **Depende de**: nenhum (decisão do dono em 2026-11-18)
- **Categoria**: direction → feature
- **Planejado em**: commit `3927246`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O caso de uso central do app no celular é "o que ficou pronto?". Hoje, tocar no aviso de fim de turno leva à
conversa, e o usuário rola a saída de ferramentas para achar o resultado; para ver um arquivo, navega pela pasta
de trabalho. O servidor já monta **entregas**: o fim de cada pedido em que o agente trabalhou e respondeu, com
manchete, resumo e os arquivos entregues. A página usa isso; o Zaplaud não. O spike 112
(`plans/112-resultado.md`) comparou opções e o dono escolheu a A: uma lista de entregas da sessão aberta, somente
leitura, com download pelos meios que já existem. Sem prévia de arquivo e sem mudar o destino do aviso.

## Estado atual

**Servidor** (não muda). `GET /api/deliveries` devolve `{ "deliveries": [...] }`; cada item (`shared/types.ts:735-762`):

```ts
id: string;            // "<conta>:<sessão>:<entrada do pedido>"
account: string; provider?: string /* ausente = claude */; sessionId: string;
agentId?: string; agentName?: string; project?: string;
request: string;       // o que foi pedido, uma linha (até 240)
headline: string;      // primeira frase da resposta (até 180)
summary?: string;      // parágrafo simples (até 600)
text: string;          // a resposta inteira em markdown (até 40.000)
startedAt: number; finishedAt: number;   // epoch ms
tools: number; edited: number;
attachments: { id: string; name: string; kind: 'image'|'pdf'|'video'|'page'|'text'|'file'; caption?: string; capture?: boolean }[];
```

Sem paginação nem filtro: vêm até 60 entregas de todas as sessões, mais recentes primeiro, com o `text` de cada
uma (resposta grande). `GET /api/deliveries/file/<id do anexo>` devolve os bytes do anexo (até 25 MiB), com o nome
em `Content-Disposition`; id desconhecido dá 404. As duas rotas exigem login e a permissão `terminal`; sem ela,
403; sem login, 401.

**App.** `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt`:

```kotlin
// :32-41  download em blocos, sem guardar o arquivo na memória
suspend fun HabblaudApi.downloadFile(path: String, write: suspend (ByteArray, Int) -> Unit, progress: (Long, Long?) -> Unit = { _, _ -> }) {
    client().prepareGet(origin + "/api/files/raw") { parameter("path", path); parameter("download", "1"); authorize(this) }
        .execute { response -> receive(response); streamFileDownload(response, write, progress) }
}
// :43  internal suspend fun streamFileDownload(response: HttpResponse, write, progress)
// :24  internal fun parseFileListing(json: String): FileListing   (função pura, testada em FilesTest.kt)
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/FilesSheet.kt`: a folha de arquivos. `:24-32` classe `FileDownload` (progresso, salvo, erro, cancelado);
`:67-85` `download(entry, parent)` cria o destino com `createDownload(name)` (definido em `FileDownloads.kt`, com
`actual` por plataforma: Downloads no Android, pasta do app no iPad), chama `api.downloadFile`, trata 401 com
`onUnauthorized()` e aborta o destino em falha ou cancelamento; `:86-88` usa `DownloadPermissionGate` (permissão de
armazenamento no Android 8/9).

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`: o menu da conversa tem o item de arquivos
(`DropdownMenuItem(modifier = Modifier.testTag("files-menu"), text = { Text(stringResource(Res.string.files_title)) }, ...)`)
e a folha é desenhada com `if (filesOpen) FilesSheet(api, selected, { onUnauthorized(api.origin) }) { filesOpen = false }`.
`selected` é o `SessionItem` aberto; a chave é `selected.key` (`origin`, `provider`, `account`, `sessionId`).

Regras já decididas no projeto para conteúdo do agente (`docs/zaplaud-paridade.md`, "Imagens da conversa", e planos
091/092): nada de WebView; nada de interpretar HTML; imagem remota só com toque; SVG só pelo leitor protegido.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-117 -b advisor/117-zaplaud-entregas-da-sessao producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-117`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O Rust não tem entregas. A parte Rust é o plano 119; registre em `docs/zaplaud-paridade.md` a linha "Entregas da sessão: lista e download | sim | pendente no Rust (plano 119)".
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt` (modelo, leitura e download de entregas)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/DeliveriesSheet.kt` (criar)
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/FilesSheet.kt` **somente** para tornar reutilizável o que a folha nova precisa (a classe de progresso e a
  rotina de download), sem mudar o comportamento da folha de arquivos
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` (item de menu e a folha)
- `android/shared/src/commonMain/composeResources/values/strings.xml`
- `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/DeliveriesTest.kt` (criar) e
  `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/DeliveriesApiTest.kt` (criar)
- `CHANGELOG.md`, `docs/zaplaud-paridade.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Servidor (`server/deliveries/`, rotas): sem filtro novo nem paginação neste plano.
- Prévia de arquivo dentro do app, miniaturas e qualquer leitura automática de anexo.
- O destino do toque no aviso de fim de turno: continua abrindo a conversa.
- Lista global de entregas, selo de "novas" e consulta periódica.
- `zaplaud-rs/` (plano 119).

## Passos

### Passo 1: modelo e leitura pura

Em `data/Files.kt`: `data class DeliveryAttachment(val id: String, val name: String, val kind: String, val caption: String?)`
e `data class Delivery(val id: String, val provider: String, val account: String, val sessionId: String, val request: String, val headline: String, val summary: String?, val text: String, val finishedAt: Long, val attachments: List<DeliveryAttachment>)`.

`internal fun parseDeliveries(json: String): List<Delivery>`, no estilo de `parseFileListing`: `provider` ausente
vira `"claude"`; item sem `id`, `account` ou `sessionId` é descartado; anexo sem `id` ou `name` é descartado;
campos de texto ausentes viram vazio. `internal fun deliveriesOf(all: List<Delivery>, key: SessionKey): List<Delivery>`
filtra por `provider`, `account` e `sessionId` da chave, mantendo a ordem do servidor (mais recentes primeiro).

**Verificação**: `DeliveriesTest` do "Plano de testes" passa.

### Passo 2: leitura limitada e download do anexo

`suspend fun HabblaudApi.deliveries(): List<Delivery>`: GET `/api/deliveries` com `client().prepareGet(...)`,
`authorize(this)`, `receive(response)`, erro de status como nas outras rotas (`apiError(...)`), e corpo lido com
**teto de 8 MiB** (acima disso, lance `IOException("Resposta grande demais")`); depois `parseDeliveries`. Não
use `request()`, que lê o corpo sem limite. Extraia a leitura limitada para uma função `internal` que recebe o
`HttpResponse`, para poder testá-la em host (o `HabblaudApi` não pode ser criado em teste de host).

`suspend fun HabblaudApi.downloadDelivery(attachmentId: String, write: suspend (ByteArray, Int) -> Unit, progress: (Long, Long?) -> Unit = { _, _ -> })`:
igual a `downloadFile`, com a URL `origin + "/api/deliveries/file/" + attachmentId.encodeURLPathPart()` e sem
parâmetros; reutiliza `streamFileDownload`.

**Verificação**: `DeliveriesApiTest` do "Plano de testes" passa.

### Passo 3: folha de entregas

Crie `DeliveriesSheet.kt` com `internal fun DeliveriesSheet(api: HabblaudApi, selected: SessionItem, onUnauthorized: () -> Unit, onClose: () -> Unit)`,
um `ModalBottomSheet` no molde de `FilesSheet` (mesmo cabeçalho com título e botão de fechar, mesma altura,
`testTag("deliveries-sheet")`):

- Ao abrir, e no botão "Atualizar", chama `api.deliveries()` e guarda `deliveriesOf(todas, selected.key)`. Estados:
  carregando (`deliveries_loading`), vazio (`deliveries_empty`), erro. Erro 401 → `onUnauthorized()`; 403 →
  `deliveries_blocked`; qualquer outra falha → `deliveries_error`. Nenhuma exceção pode escapar do efeito
  (relance só `CancellationException`).
- Lista (`LazyColumn`, chave = `id`): por entrega, a manchete (`headline`, até 2 linhas), o horário
  (`platformEntryTime(finishedAt, uiNowMillis())`, já existente em `UiPlatform.kt`), o `summary` quando houver
  (até 3 linhas) e "N arquivos" quando houver anexos.
- Tocar numa entrega expande no lugar: o pedido (`request`), a resposta (`text`) como **texto simples
  selecionável** (sem markdown, sem carregar imagens: `SelectionContainer { Text(...) }`), e a lista de anexos com
  nome, legenda e um botão "Baixar".
- "Baixar" usa a mesma rotina de progresso, cancelamento, gravação e aborto da folha de arquivos, chamando
  `api.downloadDelivery(attachment.id, ...)` e `createDownload(attachment.name)`, dentro do mesmo
  `DownloadPermissionGate`. Para isso, em `FilesSheet.kt`, torne `internal` a classe `FileDownload` e mova o
  corpo de `download` para uma função `internal` reutilizável que recebe a ação de download como parâmetro; a
  folha de arquivos passa a chamá-la, sem mudar o que faz.

Textos novos em `strings.xml`: `deliveries_title` ("Entregas da sessão"), `deliveries_menu` ("Entregas"),
`deliveries_loading` ("Carregando entregas…"), `deliveries_empty` ("Nenhuma entrega recente nesta sessão."),
`deliveries_error` ("Não foi possível carregar as entregas."), `deliveries_blocked` ("Entregas bloqueadas pelo
servidor."), `deliveries_refresh` ("Atualizar"), `deliveries_request` ("Pedido"), `deliveries_download`
("Baixar") e o plural `deliveries_files` ("%1$d arquivo" / "%1$d arquivos").

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`; `:shared:compileKotlinJvm` → `BUILD SUCCESSFUL`.

### Passo 4: entrada pelo menu da conversa

Em `ReadingScreen.kt`, ao lado do estado `filesOpen`, crie `deliveriesOpen` com a mesma chave
(`remember(api.origin, selected?.key)`). No menu **da conversa** (não o da lista), logo depois do item de
arquivos, acrescente `DropdownMenuItem(modifier = Modifier.testTag("deliveries-menu"), text = { Text(stringResource(Res.string.deliveries_menu)) }, onClick = { deliveriesOpen = true; close() })`,
visível só com conversa aberta de sessão principal (`selected != null && !selected.sub`). Desenhe a folha ao
lado da de arquivos: `if (deliveriesOpen && selected != null) DeliveriesSheet(api, selected, { onUnauthorized(api.origin) }) { deliveriesOpen = false }`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`; `:app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`.

### Passo 5: teste instrumentado e registro

Em `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`, acrescente
`deliveriesMenuOpensSheet`: abre uma conversa, abre o menu, toca em `deliveries-menu` e espera o nó
`deliveries-sheet` com um dos três estados (lista, vazio ou bloqueado) visível. Os textos que o teste procurar
precisam existir também em `android/app/src/main/res/values/strings.xml` (cópia usada pelos testes; acrescente
só as chaves que o teste usa, com o mesmo texto).

`CHANGELOG.md` ("Não lançado", "Adicionado"): "Zaplaud: o menu da conversa abre as Entregas da sessão, com o
pedido, a resposta e os arquivos de cada trabalho concluído, e baixa os arquivos." `docs/zaplaud-paridade.md`:
linha conforme "Convenções".

## Plano de testes

- `DeliveriesTest.kt` (commonTest, padrão `FilesTest.kt`): JSON com dois itens de sessões diferentes e um sem
  `provider` → `parseDeliveries` devolve os campos certos e `provider == "claude"` no terceiro; item sem `id`
  descartado; anexo sem `name` descartado; `deliveriesOf` devolve só os da chave pedida, na ordem do servidor;
  JSON que não é objeto → lança.
- `DeliveriesApiTest.kt` (androidHostTest, `httpClient` + `MockWebServer`, padrão `FilesApiTest.kt`): corpo dentro do
  limite → lista; corpo acima de 8 MiB → `IOException`; download de anexo grava bytes idênticos e chama a URL
  `/api/deliveries/file/<id>` com o id codificado; 404 no download → `ApiException` com status 404.
- O instrumentado do passo 5: compila; a execução é do revisor, no emulador.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'fun parseDeliveries\|fun deliveriesOf\|fun HabblaudApi.deliveries\|fun HabblaudApi.downloadDelivery' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt` → as quatro
- [ ] `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/DeliveriesSheet.kt` existe e não usa `Markdown(` nem `MarkdownImages(`
- [ ] `grep -c 'name="deliveries_' android/shared/src/commonMain/composeResources/values/strings.xml` → `10`
- [ ] `DeliveriesTest` e `DeliveriesApiTest` passam; comando de host completo, `:shared:compileKotlinJvm` e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] `FilesTest`, `FilesApiTest` e os testes existentes da folha de arquivos seguem passando sem edição
- [ ] `CHANGELOG.md` e `docs/zaplaud-paridade.md` atualizados
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 117 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3927246`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Tornar a rotina de download reutilizável exigir mudar o comportamento da folha de arquivos.
- O servidor de teste (`scripts/android-smoke.ts` / `server/test/android-fixture.ts`) não servir `/api/deliveries`:
  o instrumentado do passo 5 então só pode esperar o estado "bloqueado" ou "erro"; relate qual e não mexa na fixture.
- `platformEntryTime` não existir com essa assinatura.

## Notas de manutenção

- A lista vem sem filtro do servidor (até 60 entregas de todas as sessões, com a resposta inteira de cada uma).
  Se o peso incomodar em uso real, o próximo passo é um filtro por sessão e uma resposta compacta no servidor,
  não cache no app.
- A resposta é mostrada como texto simples de propósito. Mostrar markdown aqui exige as mesmas regras de imagem
  da conversa.
- Se um dia o aviso de fim de turno abrir a entrega certa, o servidor precisa mandar o id da entrega no stream.
