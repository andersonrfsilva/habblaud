# Plano 043: impedir crashes de avatares e remover trabalho de cache da thread da interface

> Execute na cópia de desenvolvimento ou em worktree, nunca na instalação de produção. Este plano não autoriza deploy, push, limpeza de dados nem instalação automática no celular pessoal. O status canônico fica em `plans/README.md`.

## Prioridade e referência

- Prioridade: P1. Esforço: M. Risco: MED (transporte compartilhado Android/iOS e concorrência do cache).
- Categoria: bug/performance. Dependências: nenhuma nova; a base já contém os planos 041/042.
- Planejado em 2026-11-07, HEAD `9426ebc`, sobre os arquivos presentes no workspace. Alterações locais podem existir: os trechos abaixo também fazem parte da verificação de desvio.
- Antes de editar: `git diff --stat 9426ebc..HEAD -- android/shared android/app plans`. Compare também os arquivos atuais com os trechos deste plano; preserve alterações do usuário. Não reverta correções já presentes.
- Branch sugerida: `advisor/043-zaplaud-estabilidade-avatares`. Não criar camada de transporte nova nem adicionar dependências.

## Evidências e limites

Investigação ADB no Samsung M35 (`SM_M356B`, Android 16), pacote `br.com.xlinx.habblaud`, em 2026-11-07. O APK instalado foi extraído e inspecionado com `apkanalyzer`; não se presumiu que ele fosse igual ao HEAD. SHA-256 do APK: `f30f59a9ff531fcb753726ecd12961c64e0fd057168a764c70ac00469bbee3b4`. Versão exposta: `1.0`, código `1`, atualizado em 2026-11-04 21:36:58. Nenhum APK disponível em Downloads tinha esse hash.

Sequência observada na captura TCP e no logcat, horários UTC−03:

1. 05:39:50.564: servidor `100.83.131.11:4747` enviou FIN à conexão do celular na porta 57172; celular confirmou.
2. 05:40:13.750: celular reutilizou essa conexão para `GET /api/avatar.png`; servidor respondeu RST, sem resposta HTTP.
3. 05:40:14.148: PID 23900 terminou com `FATAL EXCEPTION: main`, `java.io.IOException: unexpected end of stream`, originada no OkHttp e propagada para um `LaunchedEffect` do Compose.
4. O bytecode instalado confirma que `CharacterAvatar` chama `AvatarCache.load`, que chama `avatarBytes`, sem capturar falha de rede. É um caminho concreto que derruba o processo.

Também observado: três pedidos de avatar com `parts=%7B%22skin%22:%22`, isto é, JSON truncado em `{"skin":"`, receberam HTTP 400. Hipótese a provar no teste: `#` de cores hexadecimais vira fragmento de URL por codificação inadequada. Não tratar a hipótese como prova, nem aceitar JSON truncado no servidor.

Desempenho: PID 23470, 340 quadros, 71 janky (20,88%), p95 150 ms, p99 400 ms; GPU p99 5 ms. O sistema informou nenhum ANR desde o boot. São métricas de uma amostra de uso com inicialização, não benchmark controlado. Cache de avatar faz disco e decodificação na thread da interface; seu peso nos engasgos ainda não foi isolado. Não afirmar que essa correção resolve toda lentidão antes de medir.

Servidor permaneceu ativo desde 2026-11-05; resposta HTTP real anuncia `Keep-Alive: timeout=5`. Não há motivo para reiniciar servidor, alterar Tailscale ou aumentar timeout como correção do cliente.

## Estado atual a preservar

Caminhos relativos ao repositório:

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CharacterAvatar.kt:37-40`:
  ```kotlin
  val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, avatar, api) {
      value = null
      value = avatar?.let { cache.load(it) }
  }
  ```
  A UI já mostra a inicial do nome quando `image == null`; reutilizar esse comportamento, sem novo diálogo.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AvatarCache.kt:13-22`: monta a query com `encodeURLQueryComponent()`, consulta cache, chama `api.avatarBytes(path)` e salva a imagem.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/HabblaudApi.kt:69-90`: `request()` já tenta novamente um GET quando ocorre IOException antes de receber resposta. Não duplicar essa correção. `avatarBytes()` usa outro caminho (`prepareGet`, linhas 114-129), sem essa proteção.
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/HttpTransport.android.kt:15`: `retryOnConnectionFailure(false)`. Os corpos de escrita são one-shot. Isso protege POST contra repetição ambígua; não ligar retry global.
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/AvatarCache.kt:17-34`: `decodeFile`, `writeBytes`, `listFiles` e ordenação dos arquivos são síncronos. Memória compartilhada de 4 MiB; disco de 16 MiB; validade de 24 h; imagens aceitas de 60×60; download limitado a 1 MiB. Preservar esses limites.
- Padrão de exceção: `SettingsScreen.kt:95-100` relança `CancellationException`, depois representa falha na UI. Seguir o contrato de cancelamento, sem captura global de todas as falhas do aplicativo.
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt:85-100` já verifica que falha ambígua de POST não reenvia sozinha.
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/AvatarCacheInstrumentedTest.kt:30-46` fornece servidor local e PNGs reais; testa reaproveitamento e rejeição de dimensão incorreta.

## Escopo

Modificar somente o carregamento de avatares acima, seu transporte HTTP de leitura e testes correspondentes. O ponto preferido de contenção é `AvatarCache.load`: falha recuperável de imagem resulta em `null`, cancelamento continua sendo cancelamento. `CharacterAvatar` permanece responsável apenas pelo estado visual.

Arquivos permitidos:

- Os quatro arquivos de avatares/API citados em commonMain/androidMain; `HttpTransport.android.kt` somente se necessário para garantir uma nova conexão na segunda tentativa GET sem afetar POST/SSE.
- Cache nativo em `iosMain/.../data/AvatarCache.kt` apenas se a mudança de execução exigir ajuste de segurança de thread, após leitura do arquivo.
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/AvatarTransportTest.kt` (novo), teste instrumentado de cache existente e, se necessário, teste Compose no conjunto existente de `android/app/src/androidTest`.
- `plans/043-zaplaud-estabilidade-avatares.md` para evidências finais e `plans/README.md` para status.

Fora do escopo: backend Node, autenticação, cookies, permissões, esquema de armazenamento, migração de dados, parser de conversa, redesign, biblioteca de imagens, upgrade de Ktor/OkHttp, retry global, telemetria permanente e refatoração geral. Não limpar cache, rascunhos ou login para esconder a falha.

## Etapas de implementação

### 1. Provar o problema da URL e corrigir o parâmetro

No teste de transporte real com MockWebServer, envie um avatar cujo `parts` contenha cores com `#`, além de caracteres `&`, `+`, `%`, aspas e Unicode. Leia `RecordedRequest.requestUrl.queryParameter("parts")`; deve ser exatamente o JSON original e não deve haver fragmento. Não testar apenas uma string montada sem passar por Ktor/OkHttp.

Corrija a codificação no ponto único de construção do pedido: preferir parâmetros do construtor de URL do Ktor, ou codificação de componente que escape todos os delimitadores reservados. Escolha a menor mudança compatível com a API atual. Não aplicar dupla codificação; preserve origem, seed, look, kind, scale e cache por origem. Se o teste não reproduzir truncamento, investigue o trajeto antes de alterar o encoder.

Aceite: servidor recebe o JSON inteiro; PNG válido chega ao consumidor e é reutilizado pelo cache. HTTP 400 continua sendo erro, nunca uma imagem falsa.

### 2. Recuperar GET interrompido e conter falha de avatar

Em `avatarBytes()`, admitir no máximo duas tentativas totais, somente para erro de transporte anterior aos headers. Não repetir HTTP 4xx/5xx, corpo interrompido após headers, redirect recusado ou cancelamento. Use a mesma regra já presente em `request()`, sem criar framework de retries.

Provar que a segunda tentativa não reutiliza o socket morto. Se o engine já remove o socket após IOException, não modificar o pool; se não remove, ajustar apenas esse GET de forma restrita. Não esvaziar o pool compartilhado nem cancelar os streams de conversa para recuperar uma imagem.

Em `AvatarCache.load`, capturar a falha recuperável após esgotar a tentativa e devolver `null`, preservando a inicial já existente na UI. Relançar `CancellationException`; não transformar bugs de programação, OOM ou qualquer `Throwable` em imagem ausente. Manter proteções de 401 e geração de cookie existentes na API.

Aceite: falha temporária seguida de PNG retorna imagem; falha persistente retorna ausência de imagem sem matar o escopo da UI; a próxima chamada independente pode recuperar. POST ambíguo continua enviado uma única vez.

### 3. Retirar disco e decodificação da thread principal

Mover leitura e gravação/decodificação do cache para `Dispatchers.IO`, usando o mecanismo existente de coroutines multiplataforma. Atualizar `value` do Compose somente no contexto original. Não lançar escopo que sobreviva à tela para baixar avatar nem usar `NonCancellable`.

Antes de introduzir paralelismo, verificar a segurança do cache nativo Android/iOS. Proteger apenas a seção compartilhada de disco se necessário: dois loaders não podem ler arquivo parcialmente escrito ou disputar limpeza/gravação. Não manter lock durante HTTP; não acrescentar fila, cache negativo ou deduplicação sem demonstração de necessidade.

Preservar cancelamento ao trocar de avatar/origem; resposta antiga não substitui avatar novo. Preservar fallback, dimensões, validade e limites de cache. Registrar leituras/gravações com StrictMode ou trace temporário no teste para demonstrar ausência de I/O de avatar na main thread; retirar instrumentação temporária após a prova.

Aceite: cache frio, quente e gravação funcionam; carregamentos concorrentes não corrompem imagens; cancelamento não publica resultado antigo.

### 4. Rodar gates uma vez após as mudanças integradas

Comandos existentes, a partir da raiz, SDK configurado e JDK 17 ou superior conforme ambiente de build:

```sh
ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
android/gradlew -p android :app:testReleaseUnitTest --tests '*VpnGuardTest*'
# Em emulador/dispositivo dedicado a testes, NÃO no celular pessoal com dados reais:
android/gradlew -p android :shared:connectedAndroidDeviceTest :app:connectedDebugAndroidTest
# No Mac, pois commonMain é compartilhado:
android/gradlew -p android :shared:allTests
```

Esperado: `BUILD SUCCESSFUL`, regressões novas executadas, testes nativos iOS executados e não simplesmente SKIPPED. As tasks foram conferidas em `.github/workflows/android.yml`, `scripts/android-instrumented-ci.py` e plano 042; não foram executadas na elaboração deste plano. Usar `scripts/ios-native-tests.sh` quando o ambiente Apple exigir o runner com entitlements já adotado pelo projeto.

Não executar builds/testes a cada edição. Para prova falha-antes/passa-depois, isolar a regressão em ambiente descartável, sem resetar arquivos do usuário. Registrar falhas preexistentes separadamente, sem apagar testes para obter verde.

## Regressões obrigatórias

Usar MockWebServer/SocketPolicy e timeouts limitados; não acessar produção nos testes.

1. `parts` chega integralmente ao servidor com os caracteres reservados descritos; PNG 60×60 retorna e uma segunda leitura usa cache.
2. Conexão encerrada antes dos headers: GET recupera em no máximo duas tentativas; falha persistente mantém fallback e UI utilizável.
3. Cancelamento durante leitura não provoca nova tentativa e não publica imagem tardia.
4. HTTP 401, 403, 404 e 500 não são repetidos automaticamente; 401 mantém o comportamento atual de sessão.
5. Corpo interrompido, resposta maior que 1 MiB, MIME errado, PNG inválido e dimensão diferente de 60×60 não entram no cache como imagem válida.
6. Teste existente de POST ambíguo continua comprovando ausência de reenvio automático. Não adicionar teste que apenas confira o valor de `retryOnConnectionFailure` no código-fonte.
7. Teste instrumentado carrega avatar a partir de `Dispatchers.Main`, com cache frio, e verifica que operações de disco não ocorrem ali. Repetir cache quente e concorrência usando origens de servidor isoladas por teste.

Usar o teste instrumentado existente como padrão para armazenamento/Bitmap. Testes host não devem fingir `Bitmap` Android. Se precisar validar fallback Compose, usar o runner já existente no módulo app; não adicionar Compose test ao shared por conveniência.

## Smoke e medição no M35

1. Guardar baseline antes de atualizar: versão/hash do APK, crash recente, `dumpsys gfxinfo` e cenário usado. O APK antigo extraído ficou em `/tmp/zaplaud-m35-investigation.apk` na máquina da investigação; confirmar existência, não depender dessa cópia temporária como único backup.
2. Primeiro demonstrar a falha e a recuperação em fixture isolada com servidor que encerra sockets. Não interromper a rede ou o servidor de produção para provocar falha.
3. Só após autorização de instalação, atualizar por `adb install -r <apk-validado>` com a mesma assinatura. Nunca desinstalar nem executar `pm clear`. Preservar contas, sessão aberta, notificações e rascunhos.
4. ADB nesta máquina: `/home/anderson/Android/Sdk/platform-tools/adb`; M35 observado em `100.70.204.23:5555`. Confirmar `devices -l` e autorização antes de usar; não presumir conectividade futura.
5. Repetir três rodadas iguais de abrir lista, entrar/sair de conversa e rolar por contatos, incluindo retorno após ociosidade superior a 5 segundos. Não enviar mensagens nem dispensar agentes reais.
6. Antes de cada rodada, `adb -s <serial> shell dumpsys gfxinfo br.com.xlinx.habblaud reset`; depois, coletar `dumpsys gfxinfo br.com.xlinx.habblaud`. Separar inicialização de interação. Registrar quadros totais, janky, p95/p99, duração, APK e cenário. Comparar a mediana das três rodadas antes/depois; a amostra de 20,88% não serve como alvo universal.
7. Coletar `logcat -b crash` por intervalo novo e `dumpsys activity exit-info br.com.xlinx.habblaud`. Exigir zero novas exceções fatais no cenário e operação da tela após falha controlada de avatar. Não limpar logs históricos.
8. Se engasgos persistirem, coletar trace curto de CPU/UI no mesmo cenário e identificar funções responsáveis. Não ampliar este plano para renderização de Markdown sem evidência. Registrar hipótese remanescente e bloqueio de aceite de desempenho, em vez de prometer que o cache resolveu tudo.

## Critérios de aceite e parada

- Todos os casos de regressão acima passam e os gates executados têm resultado registrado.
- Captura do pedido prova `parts` íntegro; fallback e recuperação demonstrados com falha real de socket.
- POST nunca é repetido automaticamente; streams, cancelamento e isolamento por origem preservados.
- I/O/decodificação de avatar fora da thread principal comprovados; medição antes/depois registrada, sem regressão consistente de p95/janky no mesmo cenário.
- Smoke no M35 executado com autorização, sem perda de login/rascunho. Sem essa prova, registrar BLOCKED, não DONE.
- Código compartilhado validado no Mac; falta de Mac é limite explícito, não razão para presumir paridade iOS.
- Registrar resultados e pendências neste plano e atualizar somente a linha 043 no índice. Não criar documentação duplicando o status.

Parar e relatar se a correção exigir retry de POST, relaxar autenticação/validação de imagem, apagar dados, trocar assinatura do APK, mudar backend ou ampliar escopo. Divergência de código deve ser reconciliada antes de editar. Se a URL não reproduzir o truncamento ou o novo trace apontar outro gargalo, registrar a evidência e ajustar o plano antes de implementar uma hipótese.

## Decisões de manutenção

Não resolver por aumento do keep-alive do servidor: redes também encerram conexões. Não instalar biblioteca de imagens para um carregador pequeno. Não esconder crashes com handler global. A correção precisa manter a imagem opcional, a falha de rede recuperável e operações de escrita sem replay. Engasgos fora do cache permanecem investigação baseada em trace, não autorização para refatoração geral.


## Evidências de execução em worktree — 2026-11-07

- Base `9426ebc`; branch `advisor/043-zaplaud-estabilidade-avatares`; worktree `/home/anderson/Desenvolvimento/habblaud-043`. O índice canônico é mantido pelo agente principal, fora deste commit.
- A regressão host passou por MockWebServer e Ktor/OkHttp. Antes da correção, `parts` chegou como `{"skin":"`, não como o JSON completo contendo `#`, `&`, `+`, `%`, aspas e Unicode. A falha original foi `ComparisonFailure` em `reservedPartsRoundTripThroughTransport` (2026-11-07T08:53:00Z). A primeira tentativa do teste usou `HabblaudApi` no host e falhou por armazenamento Android não inicializado; a prova host foi corrigida para usar o transporte real, sem simular Bitmap ou armazenamento.
- A recuperação usa o mesmo contrato de GET anterior aos headers. Não foi alterado `HttpTransport.android.kt`, o pool nem o retry global de POST/SSE.
- O mutex compartilhado em commonMain protege somente operações nativas de cache, entre instâncias e origens, em Android/iOS. HTTP não mantém esse mutex. Por isso, o cache iOS não precisou de uma segunda convenção de locks.
- O diretório Android passou a ser lazy: `Context.cacheDir` também pode criar diretórios e não deve ser consultado ao construir o loader no Compose.
- Os dois primeiros gates integrados falharam no D8 porque artefatos copiados para a worktree ainda continham caminhos absolutos do parent. `--no-build-cache --rerun-tasks` não bastou. `android/gradlew -p android clean --no-build-cache` limpou apenas outputs da worktree; o gate seguinte passou.
- Gate integrado: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android --no-build-cache :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:testReleaseUnitTest --tests '*VpnGuardTest*'`: `BUILD SUCCESSFUL`, 106 tasks, 25 s. Shared: 85 testes, zero falhas/erros/skips, incluindo a regressão host e o teste existente de POST ambíguo.
- Limite do gate acima: ambas as tasks unitárias de `app` foram `NO-SOURCE`. `VpnGuardTest` não existe na árvore base; não há prova de execução desse teste. Nenhum teste existente foi removido para produzir o resultado.
- APK gerado: `android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `d0313c45ef8ef7e60b76a41e8e3b93b79ac061d0dfa08a47136c9094f23b3971`.
- `apksigner verify --print-certs` confirmou a mesma assinatura no APK novo e em `/tmp/zaplaud-m35-investigation.apk`: SHA-256 do certificado `82de0034cceeab999e300600bf4841fdb69e67d8cb4f8aa1f912f375e0e3a7eb`. O backup existe e mantém SHA-256 `f30f59a9ff531fcb753726ecd12961c64e0fd057168a764c70ac00469bbee3b4`.
- Apple: o runner `scripts/ios-native-tests.sh` exige Apple Silicon/macOS/Xcode. Não há alias Mac em `~/.ssh/config`; a única tentativa `ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=yes 100.94.162.61 uname -sm` expirou, código 255. Não foram executados testes nativos iOS, e Linux não prova paridade iOS.
- M35: após autorização explícita do usuário e aprovação do review de código/escopo, o agente principal comparou as assinaturas com `apksigner` e instalou o APK de SHA-256 `d0313c45ef8ef7e60b76a41e8e3b93b79ac061d0dfa08a47136c9094f23b3971` com `adb install -r`, resultado `Success`. O agente principal não desinstalou o app, não limpou dados e não enviou mensagens. Este executor não operou o dispositivo pessoal.
- Smoke no M35, observado pelo agente principal em 2026-11-07: `MainActivity` aberta às 06:08:39 UTC−03; screenshot mostrou lista autenticada e avatares de Mira/Didiz carregados, seguida de navegação em conversa existente. O logcat de crash desde 06:08:39 estava vazio; PID `5829` às 06:10. Não havia baseline explícito de rascunho, portanto a preservação de rascunho preexistente não foi comprovada.
- Desempenho no M35: não foram obtidas três rodadas comparáveis. A navegação do dispositivo mudou durante a coleta e os contatos reordenaram. A nova queda relatada pelo agente principal no APK antigo comprova falha anterior, mas não constitui benchmark comparável. Não atribuir ganho percentual de jank ou p95/p99 ao patch.

- Review identificou que a regressão host copiava o encoder, sem chamar o construtor de query produtivo. O arquivo temporário `AvatarTransportTest.kt` foi removido; a regressão permanente `partsRoundTripAndCacheReuse` chama `AvatarCache.load` e valida PNG real e cache no Android.
- Gate instrumentado: `ANDROID_HOME=/home/anderson/Android/Sdk ANDROID_SERIAL=emulator-5580 PATH=/home/anderson/Android/Sdk/platform-tools:/usr/local/bin:/usr/bin:/bin python scripts/android-instrumented-ci.py`: `BUILD SUCCESSFUL`, 123 tasks, 1 min 24 s; 124 testes (107 shared + 17 app), zero falhas/erros/skips. XML: `android/shared/build/outputs/androidTest-results/connected/androidMain/TEST-habblaud034(AVD) - 16-_shared-.xml` e `android/app/build/outputs/androidTest-results/connected`.
- `AvatarCacheInstrumentedTest`: sete casos passaram: reaproveitamento/dimensão original; query reservada real; socket EOF e segunda conexão com `sequenceNumber == 0`; fallback persistente e recuperação independente; HTTP 400/401/403/404/500/302, corpo interrompido, tamanho, MIME, PNG e dimensões; cancelamento sem publicação; StrictMode na main com cache frio/quente e 12 loaders concorrentes; POST real ambíguo enviado uma vez. Os subcenários estão agrupados em sete métodos, sem remover o teste original.
- StrictMode foi usado somente no teste: nenhum acesso de disco do cache foi registrado na main. O teste chama o consumidor a partir de `Dispatchers.Main`; disco e Bitmap continuam dentro de `withContext(Dispatchers.IO)` no código produtivo. Não foi adicionada telemetria permanente.
- Gate final após a remoção do teste host temporário: mesmo comando de host/unit/lint/APK, sem `--no-build-cache`: **84 testes, uma falha** em `MessageSendTest.expiredStopStatusAlsoRequiresConfirmation`, não alterado neste plano. Erro em `MessageSendTest.kt:157`: `expected:<confirm> but was:<null>`. O teste havia passado no gate anterior e no dispositivo. Não foi reexecutado para obter verde nem alterado fora do escopo. Esse gate permanece bloqueado separadamente dos sete casos de avatar verdes.
- Smoke manual adicional, fora do runner: APK validado instalado somente no emulador `emulator-5580` (Android 16). Fixture isolada em `127.0.0.1:14748`; proxy temporário em `127.0.0.1:14747` encerrava sockets de `/api/avatar.png` antes de responder. O proxy registrou 12 conexões encerradas para seis avatares. A lista mostrou as iniciais; foi possível abrir a conversa de Ana Fictícia, rolar e voltar à lista. O PID permaneceu `5599`.
- No mesmo smoke, o proxy foi reiniciado sem injetar falhas, mantendo a mesma origem. Ao reabrir a conversa, o upstream respondeu `200 image/png`; o XML da conversa continuou contendo `Resposta fictícia` e deixou de conter a inicial isolada `A`. O PID permaneceu `5599`; `adb -s emulator-5580 logcat -b crash -d -v threadtime` não retornou registros. Nenhuma mensagem foi enviada e nenhum agente real foi acessado.
- Evidências locais temporárias do smoke: `/tmp/avatar043-fallback.png`, `/tmp/avatar043-recovered.png`, `/tmp/avatar043-conversation.xml` e `/tmp/avatar043-recovered.xml`. O proxy foi removido e ambos os serviços de fixture foram encerrados após a prova. Essas imagens não são backup permanente nem substituem as regressões versionadas.
- Aceite integral continua **BLOCKED**: falha do gate host existente descrita acima; execução nativa Apple indisponível; baseline/medição de desempenho comparável no M35 não obtidos. O smoke funcional autorizado no M35 passou nos limites registrados acima; a preservação de rascunho preexistente não foi comprovada. Não há evidência suficiente para atribuir melhora global de jank ou p95/p99 a este patch.

## Parecer da execução — 2026-11-07

- Implementação em `../habblaud-043`, branch `advisor/043-zaplaud-estabilidade-avatares`, commit de código `76b6e584`. O plano nessa worktree contém as evidências detalhadas do executor. Sem merge ou push.
- Revisão confirmou escopo, contenção de IOException, cancelamento, retry limitado ao GET e mutex sem HTTP dentro da seção crítica. Um teste host que copiava o encoder foi removido; a regressão instrumentada exercita o carregador produtivo.
- XMLs conferidos pelo revisor: 107 testes shared + 17 app, zero falhas/erros/skips. Sete métodos de regressão de avatar passaram. Smoke no emulador comprovou fallback com sockets interrompidos e posterior recuperação do bitmap.
- Gate host final: 84 testes, uma falha em `MessageSendTest.expiredStopStatusAlsoRequiresConfirmation`, linha 157: `expected:<confirm> but was:<null>`. O arquivo não foi alterado; a origem da falha não foi investigada neste escopo. Não houve repetição para obter verde. Testes nativos Apple não executados: Mac inacessível.
- Usuário autorizou instalação no M35. Revisor confirmou assinatura igual e executou `adb install -r` com sucesso; abriu o app às 06:08:39 UTC−03. Login e lista de contatos permaneceram disponíveis; avatares carregaram e uma conversa existente ficou navegável. PID 5829; logcat de crash desde a abertura vazio até a conferência às 06:10.
- Não foi comprovada preservação de um rascunho específico, pois não havia baseline desse dado. Nada foi desinstalado ou limpo; nenhuma mensagem foi enviada pelo revisor.
- Medição comparável de desempenho permanece pendente: baseline antigo caiu, e a navegação mudou durante a coleta posterior. Não atribuir ganho percentual ao patch. O status canônico e os impedimentos de aceite estão na linha 043 de `plans/README.md`.
