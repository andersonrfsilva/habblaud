# Plano 035: Impedir gravação de cache de época anterior após limpeza

> Executor: leia o plano inteiro. Reproduza a hipótese antes de corrigir. Se não reproduzir, PARE e relate; não declare defeito comprovado. Execute as verificações e os critérios. Não faça push nem commit sem instrução do operador. Atualize a linha deste plano em `plans/README.md` ao concluir, salvo se outro responsável mantiver o índice.
>
> Drift inicial, na raiz: `git diff --stat 9469c0b..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/data/ReadingController.kt android/app/src/main/java/br/com/xlinx/habblaud/data/LocalStateStore.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/PersistenceInstrumentedTest.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/LocalStateStoreTest.kt CHANGELOG.md` e `git status --short`. Examine alterações locais e arquivos não rastreados individualmente. Compare os trechos abaixo; divergência pertinente exige PARAR e reportar.

## Estado

- Prioridade: P1; esforço: M; risco: alto (perda ou exposição de conversas).
- Categoria: correção; dependência: nenhuma; planejado em `9469c0b`, 2026-11-03.
- Hipótese não reproduzida nesta análise: uma gravação já agendada pode recriar conversa depois de logout ou troca de origem. A origem também pode voltar a ser igual após novo login, portanto comparar apenas origem não basta.

## Contexto conferido

- `android/app/src/main/java/br/com/xlinx/habblaud/data/ReadingController.kt:14-15,107-122`: `persistScope` é global e sobrevive à tela; `stop()` e `close()` chamam `persist()`; `save` captura snapshot e executa `persistScope.launch { store.setConversation(key, entries, truncated) }`.
- `LocalStateStore.kt:84-102`: `conversationLock` é propriedade de instância; `setConversation` cria diretório, escreve `.tmp` e renomeia; `clearConversations` usa esse mutex. Duas instâncias apontam ao mesmo diretório, mas não compartilham o mutex.
- `LocalStateStore.kt:116-123,158`: `setConnection` apaga conversas antes de alterar `origin`; `clear()` migra configurações, apaga conversas e depois limpa DataStore. O lock não cobre a operação completa. `updateMessage` em linhas 67–75 valida origem dentro do `edit`; isso não invalida gravações de conversa da mesma origem de uma época anterior.
- `MainActivity.kt:146-153`: logout aguarda `store.clear()` antes de `connected = false`; `ReadingScreen.kt:68-75` chama `controller.stop()` no descarte. Portanto, `stop()` pode ser posterior à limpeza; apenas aguardar trabalhos já enfileirados não cobre trabalhos futuros do controlador antigo.
- Convenção: `PersistenceInstrumentedTest.kt:19-27,44-58` usa `AndroidJUnit4`, `ApplicationProvider`, `runBlocking`, limpeza em `@Before/@After` e nova instância para conferir persistência. `LocalStateStoreTest.kt:14-26` testa troca de instância e `clear`. Cache guarda até 500 entradas e sinaliza truncamento (`LocalStateStore.kt:175-193`); preserve o formato e comportamento.

## Escopo

Somente `ReadingController.kt`, `LocalStateStore.kt`, `PersistenceInstrumentedTest.kt`, `LocalStateStoreTest.kt` nos caminhos Android acima; pode adicionar classe de teste instrumentado em `android/app/src/androidTest/java/br/com/xlinx/habblaud/` se necessário. Acrescente entrada factual em `CHANGELOG.md` na seção corrente, preservando alterações locais existentes. Atualização de índice conforme instrução do executor. Não altere mensagens, cookies, protocolo, servidores, configurações do aparelho, nem apague conversas em `stop`, `close` ou troca normal de sessão. Não toque alterações preexistentes no manifesto e drawable.

## Comandos

Da raiz, com JDK 17, SDK Android e dispositivo/emulador conectado: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.PersistenceInstrumentedTest,br.com.xlinx.habblaud.LocalStateStoreTest` deve terminar com exit 0. Se criar classe adicional, acrescente seu nome à lista. `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug` deve terminar com exit 0. Não foi executado nesta análise; se ambiente não tiver dispositivo ou SDK, PARE e relate o bloqueio.

## Passos

1. **Caracterize deterministicamente antes da correção.** Em teste instrumentado, use duas instâncias `LocalStateStore` e barreiras explícitas (`CompletableDeferred` ou latches, com timeout), nunca `sleep`. Introduza somente o ponto mínimo de controle necessário para pausar um save real antes de acessar arquivo. Inicie save de A, aguarde a barreira, conclua `clear()` na outra instância, libere e aguarde o trabalho; confira via terceira instância e arquivo que A não reapareceu. Repita com `setConnection(B)` e com logout seguido de `setConnection(A)` antes de liberar: mesma origem não autoriza trabalho antigo. Inclua cenário do controlador: execute `clear`, depois dispare seu `stop()` real com conversa carregada e aguarde conclusão; cache não deve reaparecer. A pré-correção deve falhar em pelo menos uma sequência e a saída deve identificar o assert, não timeout ou erro de fixture. Se não houver reprodução determinística ou a instrumentação alterar a ordem real, PARE e reporte o resultado, sem corrigir por suposição.
   **Verificação:** execute o comando instrumentado filtrando a classe de reprodução; esperado antes da correção: falha no assert de conteúdo antigo presente; demais falhas são STOP.
2. **Corrija a barreira de época e exclusão mútua.** Faça a autorização de persistência pertencer à época da conexão capturada pelo controlador antes de qualquer limpeza, não à hora em que a coroutine tardia começa. Uma geração compartilhada por processo e por diretório deve ser invalidada na linearização de `clear` e de troca efetiva de origem, inclusive relogin na mesma origem; serialize a validação da geração, a gravação e a operação completa de limpeza/troca, inclusive atualização do DataStore, com coordenação compartilhada entre instâncias. Se o trabalho já gravou, limpeza posterior remove; se limpeza venceu, save antigo não grava. Não segure um mutex de modo que bloqueie para sempre a barreira do teste. Garanta que `stop()` após limpeza não obtenha uma autorização nova para estado antigo. Não descarte saves válidos ao fechar tela, mudar sessão ou recriar store; `setConnection` com mesma origem sem logout não deve apagar cache. Se não puder garantir atomicidade inclusive em erro/cancelamento e chamadas concorrentes, PARE e relate em vez de implementar solução parcial.
   **Verificação:** comando instrumentado do passo 1 deve passar sem alterar seus asserts; ausência de travamento e exit 0.
3. **Cubra interoperabilidade.** Teste save que vence antes de clear, save bloqueado após clear, duas instâncias salvando mesma chave sem colisão `.tmp`, clear concorrente com mudança A→B, save válido B, A→B→A, chave de outra origem e gravações normais em `close`/`stop`. Confirme conteúdo, `truncated`, sessões isoladas e restauração após nova instância. Inclua agendamento invertido de duas gravações válidas da mesma conversa para não regredir snapshot mais recente; se o contrato existente não permitir determinar ordem, PARE e reporte. Mantenha limpeza dos testes em `finally`/`@After` e timeouts finitos.
   **Verificação:** comando instrumentado completo e comando unitário/build acima retornam 0. Acrescente ao changelog somente o comportamento confirmado.

## Critérios e STOP

- Testes novos falham pelo defeito antes e passam depois; nenhum arquivo antigo ressurge após clear ou troca, inclusive relogin mesma origem, instâncias distintas e `stop` tardio.
- Cache legítimo sobrevive ao fechamento/recriação; sessão e origem permanecem isoladas; limite de 500 e truncamento mantidos; testes existentes passam.
- `git status --short` mostra apenas arquivos em escopo e alterações preexistentes identificadas; não sobrescreva trabalho alheio.
- PARE se hipótese não se confirmar, se houver processo Android adicional acessando os arquivos sem coordenação, se ocorrer perda de conversa válida, ou se uma mudança fora do escopo for necessária. PARE após duas falhas de verificação não explicadas; relate comando e saída relevante.

## Manutenção

Revisar toda nova gravação de conversa e nova entrada de limpeza para usar a mesma época e coordenação. O plano 038 depende deste e deve refazer drift após seu merge.
