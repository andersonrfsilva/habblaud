# Plano 108: Zaplaud: testes para a vigilância de avisos, o login refeito e a confirmação de envio incerto na tela

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 108 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/androidHostTest android/app/src/androidTest`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: LOW (só testes)
- **Depende de**: 093, 094 e 096 mesclados; autorização do dono para emulador
- **Categoria**: tests
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Três caminhos que o usuário percorre todo dia não têm teste que os exerça de ponta a ponta:

- **Vigilância de avisos** (`watchAlerts`): o laço que mantém um stream por servidor, compara fotos do escritório
  e decide avisar. Os testes existentes cobrem a regra pura (`NotificationRulesTest`) e a montagem da
  notificação (`NotificationPrivacyInstrumentedTest`), mas não o laço: reconectar e avisar o que mudou durante a
  queda, não avisar na primeira foto, parar no 401.
- **Login refeito**: `HttpLoginInstrumentedTest.savedHttpOriginStillRequiresConfirmation` para na confirmação de
  HTTP; nenhum teste completa o login de um servidor com sessão vencida e confere que a lista volta.
- **Envio incerto na tela**: o controlador é bem testado (`MessageSendTest`), mas nenhum teste confere que a
  tela pede confirmação depois de um envio sem resposta.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AlertWatcher.kt:11-45`:

```kotlin
suspend fun watchAlerts(store: LocalStateStore, onServers: (Int) -> Unit, notify: (AgentEvent) -> Unit) {
    val origins = store.servers().filter { UrlPolicy.origin(it) == it }
    if (origins.isEmpty() || !store.notificationsEnabled()) return
    onServers(origins.size)
    coroutineScope {
        origins.map { origin -> launch {
            val api = HabblaudApi(origin)
            try {
                val sessions = SessionRepository(api)
                val streams = ConversationRepository(api)
                var previous: Map<SessionKey, AgentWatch>? = null
                while (currentCoroutineContext().isActive) {
                    var unauthorized = false
                    streams.stream("/api/stream").collect { event -> ... }
                    if (unauthorized) return@launch
                    delay(30_000)
```

A função recebe o `LocalStateStore` real e cria `HabblaudApi(origin)` por conta própria; por isso o teste
precisa de um servidor HTTP de verdade (`MockWebServer`) e do armazenamento real, o que no Android significa
teste de aparelho. Padrões prontos: `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/PersistenceInstrumentedTest.kt`
(armazenamento real) e `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/ConversationStreamTest.kt`
(stream SSE servido por `MockWebServer`, com reconexão).

`android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt:161-175`: o caso de sessão
vencida (auxiliares `credentials()`, `click(...)`, `await(...)`, `server.enqueue(...)` do próprio arquivo).

`android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`: testes de tela contra a
fixture Node (`scripts/android-smoke.ts`), incluindo envio e o aviso de mensagem.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-108 -b advisor/108-zaplaud-testes-dos-caminhos-sem-cobertura producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Compilar testes de aparelho do shared | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest` | `BUILD SUCCESSFUL` |
| Compilar instrumentados do app | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL` |
| Suíte instrumentada (com autorização) | `python3 scripts/android-instrumented-ci.py` | sai com 0 |

## Escopo

**Pode modificar**:
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/AlertWatcherInstrumentedTest.kt` (criar)
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt` (caso novo)
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt` (caso novo), e
  `server/test/android-fixture.ts` só se faltar um comando para derrubar a resposta de um envio
- `scripts/android-instrumented-ci.py` (incluir a classe nova em `required_shared`)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código de produção: se um teste novo falhar por defeito do app, pare e relate; não conserte aqui.
- Testes de imagens e SVG (já previstos nos planos 091 e 092).
- iOS.

## Passos

### Passo 1: vigilância de avisos

`AlertWatcherInstrumentedTest.kt`: `MockWebServer` servindo `/api/stream` como `text/event-stream` (copie de
`ConversationStreamTest.kt` a forma de montar o corpo SSE e de encerrar a conexão para forçar reconexão);
`LocalStateStore` real com o servidor cadastrado (`addServer`) e avisos ligados; chame `watchAlerts` numa
corrotina com `notify` acumulando eventos numa lista.

Casos:
- `firstSnapshotIsBaselineOnly`: uma foto com um agente pedindo permissão → nenhum evento.
- `permissionAppearingNotifies`: foto sem pedido, depois foto com pedido → um `AgentEvent.Permission`.
- `changeDuringReconnectNotifies`: foto sem pedido, conexão cai, nova conexão já traz o pedido → um evento
  (o comentário do código promete isso: "o que mudou nela também avisa").
- `unauthorizedStopsWatching`: resposta 401 → `watchAlerts` retorna.

Os JSON de foto podem ser copiados de `NotificationRulesTest`/`SessionIdentityTest`.
A espera entre reconexões do laço é a de `ConversationRepository.stream` (2 s); use `withTimeout` de 15 s.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest` → `BUILD SUCCESSFUL`.

### Passo 2: login refeito até a lista voltar

Em `HttpLoginInstrumentedTest.kt`, acrescente `expiredSessionReloginRestoresList`, partindo de
`savedHttpOriginStillRequiresConfirmation`: depois de `await(R.string.http_login_title)`, confirme o HTTP
(veja como os outros casos do arquivo confirmam), enfileire a resposta de `/api/remote/login` com `Set-Cookie`
válido e `{"ok":true}` e as respostas seguintes que o app pede ao entrar (`/api/access`,
`/api/sessions/recent`, `/api/cast`, `/api/stream`: use um `Dispatcher` do `MockWebServer` em vez de fila se a
ordem variar); afirme que o texto das abas da lista (`R.string` correspondente a "Abertas") aparece e que a
marca `relogin:<origem>` some.

**Verificação**: `... :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`.

### Passo 3: confirmação de envio incerto na tela

Em `FixtureUiInstrumentedTest.kt`, acrescente `uncertainSendAsksBeforeResending`: abra uma conversa de agente
que aceita mensagem, digite, e faça a fixture derrubar a conexão do `POST /api/messages` sem responder (procure
na fixture um comando existente para isso; se não houver, acrescente um comando `drop-next-message` no mesmo
bloco de comandos de `server/test/android-fixture.ts:430-560`, que fecha o socket do próximo POST). Toque em
Enviar de novo e afirme: aparece o texto de confirmação (a string do erro `confirm`; ache a chave em
`strings.xml` procurando onde `error == "confirm"` é traduzido em `ReadingScreen.kt`) e a fixture **não**
recebeu um segundo POST (conte pelo comando de estatística da fixture ou acrescente um contador).

**Verificação**: `... :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`; `npm run typecheck` → sai com 0
se a fixture mudou.

### Passo 4: gate e execução

Em `scripts/android-instrumented-ci.py`, acrescente `'AlertWatcherInstrumentedTest'` a `required_shared`.

Com autorização do dono: `python3 scripts/android-instrumented-ci.py` → saída 0. Rode três vezes para pegar
instabilidade; um teste que falha uma vez em três não entra (relate).

## Plano de testes

São os próprios testes dos passos 1 a 3.

## Critérios de pronto

Todos precisam valer:

- [ ] `AlertWatcherInstrumentedTest` existe com os 4 casos
- [ ] `expiredSessionReloginRestoresList` e `uncertainSendAsksBeforeResending` existem
- [ ] `:shared:compileAndroidDeviceTest` e `:app:compileDebugAndroidTestKotlin` terminam em `BUILD SUCCESSFUL`
- [ ] Com autorização: `python3 scripts/android-instrumented-ci.py` sai com 0 em três execuções seguidas
- [ ] Nenhum arquivo de produção do app foi alterado (`git diff --stat` só mostra testes, a fixture e o script)
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 108 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Um teste novo falhar porque o app se comporta diferente do descrito (por exemplo, a reconexão não avisa o que
  mudou): isso é um achado; relate com a saída, não mude o teste para passar nem o app.
- `watchAlerts` não puder ser exercitado sem esperar os 30 s do laço externo: relate; tornar a espera injetável é
  mudança de produção e precisa de outro plano.
- Sem autorização para emulador: entregue os testes compilando e marque o plano como "escrito, não executado".

## Notas de manutenção

- Estes testes dependem de tempos reais (reconexão de 2 s). Se ficarem instáveis no CI, o caminho é injetar o
  relógio em `ConversationRepository`, não aumentar esperas.
