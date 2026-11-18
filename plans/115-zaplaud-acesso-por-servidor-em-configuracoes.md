# Plano 115: Zaplaud: Configurações mostra, por servidor, o que esta conexão pode fazer (opção A do spike 111)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 115 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 74da13b..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`
> Se algum arquivo do escopo mudou desde `74da13b`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW (somente leitura de `/api/access`; nenhuma regra de permissão muda)
- **Depende de**: nenhum (decisão do dono em 2026-11-18: opção A do 111)
- **Categoria**: direction → feature
- **Planejado em**: commit `74da13b`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O servidor decide, por conexão, o que o acesso remoto pode fazer: ler conversas (`terminal`), mandar mensagens
(`messages` com `messageSessionGuard`), responder aprovações (`permissions`) e navegar arquivos (`files`). O app
reage escondendo botões ou mostrando um aviso genérico no ponto do bloqueio. Quem configura um servidor novo e
esquece uma permissão vê um app "que não deixa mandar mensagem" sem saber o que pedir ao operador. O spike 111
(`plans/111-resultado.md`) comparou opções e o dono escolheu a A: uma seção fixa em Configurações, por servidor,
dizendo o que está liberado e o que conferir. Não muda login, não cria tela e não libera nada.

## Estado atual

Servidor, `server/http/app.ts:480-495`: `GET /api/access` devolve um objeto JSON com, entre outros, os campos
booleanos `permissions`, `messages`, `messageSessionGuard`, `terminal` e `files`
(`files: !!deps.browse && trusted(req, 'terminal')`). Servidores antigos podem não mandar `files` nem
`messageSessionGuard`.

App, `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt`: `suspend fun access(): String = request("/api/access")` (devolve o corpo JSON).
`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt:55-60` já lê `terminal`, `messages`, `messageSessionGuard` e `permissions` com
`access.flag("...")` (extensão do projeto sobre `JsonObject`); isso não muda.

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`:

```kotlin
// :58-62  funções puras de leitura de corpo JSON, testadas em commonTest (padrão a seguir)
internal fun catalogRuntime(body: String): String? =
    wireJson.parseToJsonElement(body).jsonObject.string("provider")?.takeIf { it == "omp" || it == "zeroclaw" }
internal fun httpSummaryAvailable(body: String): Boolean =
    wireJson.parseToJsonElement(body).jsonObject["summaryHttp"]?.toString() == "true"
// :69-71  servidor escolhido no topo da tela
var origin by remember { mutableStateOf(apis.keys.first()) }
val api = apis[origin] ?: apis.values.first()
// consultas periódicas usam lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) e tratam 401
// marcando `authenticated = false` (veja os dois LaunchedEffect logo abaixo dos estados de cotas)
// :295-302  seção de servidores, hoje só nome e Sair
Section(Res.string.settings_server)
apis.keys.forEach { server ->
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(serverLabel(server), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onLogout(server) }) { Text(stringResource(Res.string.logout), color = MaterialTheme.colorScheme.error) }
    }
}
// :308-311  componentes de texto da tela
private fun Section(label: StringResource) = Text(...titleSmall, cor primary)
private fun Hint(label: StringResource) = Text(...bodySmall, cor onSurfaceVariant)
```

Textos que já existem em `android/shared/src/commonMain/composeResources/values/strings.xml` e devem ser
reutilizados: `files_title` ("Arquivos"), `message_guard_required` ("O servidor precisa ser atualizado para
enviar mensagens."), `network_error`.

Limite que o texto tem de respeitar (do spike): um `false` não diz o motivo (item ausente em
`HABBLAUD_REMOTE_ALLOW`, recurso desligado no servidor, ou Docker no caso de arquivos). O app diz o que
**conferir**, nunca afirma qual ajuste falta.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-115 -b advisor/115-zaplaud-acesso-por-servidor-em-configuracoes producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-115`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. O Rust lê os mesmos campos (`zaplaud-rs/crates/zaplaud-core/src/model.rs`) e não tem esta seção: registre em `docs/zaplaud-paridade.md` uma linha nova "Acesso por servidor em Configurações | sim | pendente no Rust".
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
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`
- `android/shared/src/commonMain/composeResources/values/strings.xml`
- `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/ServerAccessTest.kt` (criar)
- `CHANGELOG.md`, `docs/zaplaud-paridade.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `SessionRepository.kt`, `ReadingScreen.kt`, `CastActions.kt`, `FilesSheet.kt`: as travas reais das ações não mudam.
- Login (`ZaplaudApp.kt`), aviso depois do login e erros de login mais precisos: é a opção B, não escolhida.
- `spawn` e `world`: não são ações do Zaplaud; não listar.
- Guardar o resultado no aparelho: o diagnóstico é sempre lido na hora.
- `zaplaud-rs/`.

## Passos

### Passo 1: leitura pura do acesso

Em `SettingsScreen.kt`, ao lado de `httpSummaryAvailable`:

```kotlin
/** O que esta conexão pode fazer; `null` = o servidor não informou o campo (versão antiga). */
internal data class ServerAccess(val reading: Boolean?, val messaging: Boolean?, val guard: Boolean?, val permissions: Boolean?, val files: Boolean?)
internal fun serverAccess(body: String): ServerAccess {
    val o = wireJson.parseToJsonElement(body).jsonObject
    fun flag(name: String): Boolean? = when (o[name]?.toString()) { "true" -> true; "false" -> false; else -> null }
    return ServerAccess(flag("terminal"), flag("messages"), flag("messageSessionGuard"), flag("permissions"), flag("files"))
}
/** Envio só conta como liberado com as duas flags; mensagens ligadas sem a proteção é incompatibilidade. */
internal fun messagingState(access: ServerAccess): String = when {
    access.messaging == null -> "unknown"
    access.messaging == false -> "blocked"
    access.guard == true -> "allowed"
    else -> "guard"
}
```

**Verificação**: teste `ServerAccessTest` do "Plano de testes" passa.

### Passo 2: consulta do servidor escolhido

Na tela, estado por servidor escolhido: `var access by remember(api) { mutableStateOf<ServerAccess?>(null) }` e
`var accessError by remember(api) { mutableStateOf(false) }`. Consulte dentro de um
`LaunchedEffect(api, authenticated, lifecycleOwner)` com `repeatOnLifecycle(Lifecycle.State.STARTED)`, uma vez
por entrada na tela (sem laço periódico): `access = serverAccess(api.access()); accessError = false`. Trate as
falhas como os efeitos vizinhos: `CancellationException` relançada; `ApiException` 401 marca
`authenticated = false`; qualquer outra `Exception` (inclusive JSON malformado) vira `accessError = true`.
Nada pode escapar do efeito.

### Passo 3: seção na tela

Logo antes de `Section(Res.string.settings_server)`, acrescente a seção nova para o servidor escolhido no topo
da tela (o mesmo `origin` das cotas e dos papéis):

- `Section(Res.string.access_title)`.
- Sem login válido (`!authenticated`): `Hint(Res.string.access_login_required)`.
- `access == null && !accessError`: `Hint(Res.string.access_loading)`; `accessError`: `Hint(Res.string.access_unavailable)`.
- Com `access`: quatro linhas no formato da linha de servidor (`Row` com o rótulo em `bodyLarge` e o estado à
  direita em `bodyMedium`): `access_reading`, `access_messaging`, `access_permissions` e `files_title`. O
  estado é `access_allowed`, `access_blocked` ou `access_not_reported` (campo `null`). Para mensagens use
  `messagingState`: `"guard"` mostra `access_blocked` e, abaixo da linha, `Hint(Res.string.message_guard_required)`.
  Cor do estado: `MaterialTheme.colorScheme.onSurfaceVariant` para liberado e não informado,
  `MaterialTheme.colorScheme.error` para bloqueado. O estado não pode depender só da cor: o texto já diz.
- Se alguma linha estiver bloqueada: `Hint(Res.string.access_allow_hint)`; se arquivos estiver bloqueado com
  leitura liberada, também `Hint(Res.string.access_files_hint)`.

Textos novos em `strings.xml` (perto de `settings_server`):

```xml
<string name="access_title">Acesso neste servidor</string>
<string name="access_reading">Conversas e recentes</string>
<string name="access_messaging">Mensagens e anexos</string>
<string name="access_permissions">Responder aprovações e perguntas</string>
<string name="access_allowed">Liberado</string>
<string name="access_blocked">Bloqueado</string>
<string name="access_not_reported">Não informado pelo servidor</string>
<string name="access_loading">Consultando acesso…</string>
<string name="access_unavailable">Não foi possível consultar o acesso.</string>
<string name="access_login_required">Entre novamente para consultar o acesso.</string>
<string name="access_allow_hint">Peça ao operador do servidor para conferir a lista HABBLAUD_REMOTE_ALLOW e se o recurso está ligado.</string>
<string name="access_files_hint">Arquivos exigem a permissão terminal e um servidor fora do Docker.</string>
```

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`;
`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileKotlinJvm` → `BUILD SUCCESSFUL`.

### Passo 4: registro

`CHANGELOG.md` ("Não lançado", em "Adicionado"): "Zaplaud: Configurações mostra, por servidor, o que a conexão
pode fazer (conversas, mensagens, aprovações, arquivos) e o que pedir ao operador quando algo está bloqueado."
`docs/zaplaud-paridade.md`: linha nova conforme "Convenções".

## Plano de testes

`ServerAccessTest.kt` em `commonTest` (padrão: `ZeroClawCapabilitiesTest.kt`, que testa `catalogRuntime` e
`httpSummaryAvailable` com corpos JSON):

- corpo completo com tudo `true` → os cinco campos `true`; `messagingState == "allowed"`;
- `messages:true` sem `messageSessionGuard` → `"guard"`; `messages:false, messageSessionGuard:true` → `"blocked"`;
- corpo sem `files` e sem `messages` → `files == null`, `messagingState == "unknown"`;
- `terminal:false, permissions:true` → `reading == false`, `permissions == true`;
- corpo que não é objeto JSON → lança (o chamador captura; confirme com `assertFailsWith`).

Verificação: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --tests '*ServerAccessTest*'`
→ passa; depois o comando de host completo, uma vez.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'fun serverAccess\|fun messagingState' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt` → as duas funções
- [ ] `grep -c 'name="access_' android/shared/src/commonMain/composeResources/values/strings.xml` → `12`
- [ ] `ServerAccessTest` passa com os 5 casos
- [ ] Comando de host completo e `:shared:compileKotlinJvm` terminam em `BUILD SUCCESSFUL`
- [ ] `CHANGELOG.md` e `docs/zaplaud-paridade.md` atualizados
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 115 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `74da13b`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- `HabblaudApi.access()` não devolver mais o corpo JSON como `String`.
- A consulta nova fizer a tela pedir login para um servidor com sessão válida (um 401 vindo só desta rota).

## Notas de manutenção

- Campo novo em `/api/access` que vire ação no Zaplaud ganha uma linha aqui.
- A seção mostra o servidor escolhido no topo da tela, como cotas e papéis; com vários servidores, o usuário
  troca pelo seletor que já existe.
- Reteste do dono: um servidor com `HABBLAUD_REMOTE_ALLOW=permissions` deve mostrar três linhas bloqueadas e a
  dica do operador.
