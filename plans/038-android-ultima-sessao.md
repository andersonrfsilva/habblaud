# Plano 038: Preservar a ordem das escolhas da última sessão

> Executor: execute depois da conclusão do plano 035. Se 035 for rejeitado por hipótese não reproduzida, leia seu resultado e adapte este plano ao estado real; não presuma uma barreira de época inexistente. Reproduza esta hipótese antes de corrigir; se não reproduzir, PARE e reporte, sem afirmar defeito comprovado. Não faça push nem commit sem ordem. Atualize a linha deste plano em `plans/README.md` ao terminar, salvo se outro responsável mantiver o índice.
>
> Drift obrigatório após conclusão do 035, na raiz: `git diff --stat 9469c0b..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/data/ReadingController.kt android/app/src/main/java/br/com/xlinx/habblaud/data/LocalStateStore.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/PersistenceInstrumentedTest.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/LocalStateStoreTest.kt CHANGELOG.md`. Leia o resultado do 035, o diff e os arquivos atuais; preserve qualquer barreira de época incorporada. Qualquer outra divergência pertinente ou conflito conceitual exige PARAR e reportar. Use `git status --short` e examine também alterações locais e arquivos não rastreados; não sobrescreva trabalho alheio.

## Estado e hipótese

- Prioridade: P2; esforço: M; risco: médio; categoria: correção.
- Depende de `plans/035-android-cache-apos-logout.md`; planejado em `9469c0b`, 2026-11-03.
- Hipótese ainda não reproduzida: `open` e `close` lançam gravações independentes; ordem de execução pode divergir da ordem de ações e restaurar sessão fechada, sessão anterior ou dados de época antiga. Isso depende do escalonamento real e deve ser provado.

## Estado conferido antes do 035

- `android/app/src/main/java/br/com/xlinx/habblaud/data/ReadingController.kt:107-108`: `close()` faz `scope.launch { store.setLastSession(null) }` depois de `persist()` e cancelar a conversa.
- `ReadingController.kt:135-147`: `open(item)` chama `persist`, seleciona item e retorna se inativo ou sem permissão; caso contrário, `if (!item.sub) scope.launch { store.setLastSession(item.key) }`. Subagentes não atualizam última sessão.
- `ReadingController.kt:84-85,99`: início carrega `store.lastSession()?.takeIf { it.origin == api.origin }`, e restaura quando snapshot contém a chave.
- `android/app/src/main/java/br/com/xlinx/habblaud/data/LocalStateStore.kt:64,77-80`: `last_session` é preferência e `setLastSession` usa `context.localState.edit { if (key == null) it.remove(sessionKey) else it[sessionKey] = key.serialize() }`; DataStore serializa edições que chegam, mas o código citado não estabelece a ordem de chegada das coroutines lançadas.
- `ReadingScreen.kt:62-75,114`: controlador usa escopo Compose; ciclo `ON_STOP`/descarte chama `stop`, botão voltar chama `close`. Preserve `stop` sem apagar a seleção persistida.
- Padrão de teste: `PersistenceInstrumentedTest.kt:19-27,44-58` usa `AndroidJUnit4`, `ApplicationProvider`, `runBlocking`, `@Before/@After` e instâncias diferentes; `LocalStateStoreTest.kt:14-26` testa persistência e `clear`. Fixture UI em `FixtureUiInstrumentedTest.kt:52-66` exige servidor externo e `fixture=true`; não a rode sem seu ambiente explícito.

## Escopo

Somente os dois fontes e dois testes citados no drift; pode criar classe de teste instrumentado em `android/app/src/androidTest/java/br/com/xlinx/habblaud/` se necessário. Acrescente entrada factual em `CHANGELOG.md` na seção corrente, preservando alterações locais existentes. Índice de planos conforme instrução do executor. Não altere API de rede, cookies, formato de `SessionKey`, mensagens ou limites de cache. Não reverta nem enfraqueça garantias de época, isolamento entre instâncias e limpeza concorrente do 035.

## Comandos e pré-requisitos

JDK 17, SDK Android e dispositivo/emulador conectado. Da raiz: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.PersistenceInstrumentedTest,br.com.xlinx.habblaud.LocalStateStoreTest` deve passar; inclua classe nova na lista se criada. `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` deve passar. Esses comandos não foram executados durante elaboração. Se não houver SDK/dispositivo, PARE e reporte. A seleção explícita evita a classe de fixture externa; não rode toda instrumentação sem configurá-la.

## Passos

1. **Reproduza sem depender de tempo.** Em teste instrumentado, configure origem e controlador ativo, com `terminalAllowed` verdadeiro usando fixture controlada no próprio teste, sem serviço externo. Utilize dispatcher controlado ou barreiras explícitas para atrasar a coroutine lançada por `open(A)` antes de entrar no `setLastSession`, executar `close()` até sua gravação concluir, depois liberar a gravação de `open(A)`; aguarde ambas com timeout e leia `lastSession` por outra instância. Valor correto é `null`. Registre falha pré-correção somente se o assert observar A após o fechamento; timeout, fixture, permissão ou falha de compilação não provam a hipótese. Também exercite `open(A)`, `open(B)` com A atrasada e B concluída, esperando B. Controle o agendamento sem substituir a lógica real de `open`/`close` por chamadas diretas ao store. Se o fluxo de produção não puder ser controlado de forma fiel ou a inversão não ocorrer, PARE e reporte a evidência, sem corrigir por hipótese.
   **Verificação:** rode o comando instrumentado filtrando a classe de reprodução. Antes da correção, pelo menos o primeiro assert deve falhar com valor A; nenhum timeout ou erro de fixture conta.
2. **Ordene as decisões na origem.** Capture a ordem ao invocar `open`/`close`, não quando suas coroutines começam. Serialização ou número monotônico deve fazer a decisão mais recente vencer mesmo se um trabalho antigo começar ou concluir tarde. Preserve qualquer proteção de época do 035; se não existir, impeça que `clear`, troca de origem e relogin na mesma origem permitam gravações antigas ou que `close` antigo apague sessão nova. A validação e escrita devem ser atômicas em relação a limpeza e outras instâncias, inclusive inversão de término; cancelar coroutine isoladamente não basta. Mantenha restauração após `stop`, regra de subagente e ausência de gravação quando `open` retorna antes da permissão. Não introduza limpeza de conversas.
   **Verificação:** reprodução do passo 1 passa sem alteração de asserts e com exit 0.
3. **Cubra sequências completas.** Teste `open(A)→close`, `open(A)→open(B)`, `close→open(B)`, repetição de `close`, `stop` e recriação, subagente, permissão negada, A→logout→mesma origem→B com trabalho antigo liberado depois, troca A→B e controladores/stores distintos. Confira tanto escrita antiga quanto `null` antigo chegando tarde. Use barreiras e timeout, nova instância para leitura e limpeza em `@After`; não use `sleep`. Repita os testes de cache e limpeza do 035. Atualize changelog somente após confirmar a correção.
   **Verificação:** ambos comandos completos retornam exit 0; confirme os testes novos também na saída do runner.

## Critérios e STOP

- Reprodução pré-correção falha pelo valor incorreto e passa após ajuste; a última decisão válida é restaurada, inclusive frente a trabalhos invertidos e épocas antigas.
- `stop` preserva restauração; `close` limpa; subagentes e permissão seguem comportamento anterior; garantias e testes do 035 passam.
- `git status --short` lista somente alterações em escopo e alterações preexistentes; confira individualmente arquivos não rastreados.
- PARE se hipótese não se confirmar, se 035 não estiver concluído ou rejeitado com resultado documentado, se nova semântica exigir alterar outros arquivos, se não houver fixture controlável fiel ou se houver perda de dados. PARE após duas falhas de verificação não explicadas e relate comando e saída.

## Manutenção

Toda nova ação que alterar última sessão deve participar da mesma ordenação e época. Revisão deve verificar a linearização de gravações e de limpeza entre instâncias, não apenas a ordem de lançamento das coroutines.
