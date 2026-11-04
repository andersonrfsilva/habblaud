# Execução do plano 041

Estado canônico: linha 041 de `plans/README.md`. Branch `advisor/041-zaplaud-ipados-kotlin`, worktree `../habblaud-041`; revisão base `6019c62`. PR de rascunho autorizado: https://github.com/andersonrfsilva/habblaud/pull/1. Sem merge ou deploy.

## Implementação e decisões de integração

- Extração KMP/Compose para `android/shared`; host Android e serviço de notificações preservados; host UIKit, Keychain, arquivos privados, picker com security scope e lifecycle iPadOS implementados.
- Interface iOS compilada e smoke de login/envio aprovado no simulador do Mac da Mira. IPA Release arm64 gerado; funcionamento no iPad físico ainda não comprovado.
- Dependências exigiram Kotlin 2.4.21, Compose 1.12.2, Ktor 3.6.1, coroutines 1.11.0, OkHttp/MockWebServer 5.5.1, AGP 9.1.1 e Gradle 9.3.1. Metadados AAR exigem compileSdk 37.0; targetSdk 36 e minSdk 26 permanecem.
- Pacote real SDK: `platforms;android-37.0`; `platforms;android-37` não existe no catálogo observado. Gradle usa `release(37) { minorApiLevel = 0 }`.
- SSE Ktor precisa de limitador incremental antes do parser, normalização dos separadores CRLF sintetizados para LF e cancelamento explícito do contexto da requisição quando a coleta termina antes dos headers. Teste de socket NO_RESPONSE comprovou a correção.
- Revisão detectou e corrigiu serviço Android sem cookie SSE após troca de engine, estado de permissão de notificações, escopo nativo do picker, cache de cookie mascarando corrupção e path vazio do cookie. Testes de ordenação foram convertidos de contagem de coroutines para transições observáveis de seleção persistida.
- Não houve mudança do contrato do servidor produtivo. A fixture aceita porta zero e informa a porta real; smoke iOS espera o HTTP 201 real do envio.

## Evidência executada localmente

Ambiente: Linux, JDK 21, SDK em `/home/anderson/Android/Sdk`, emulador Android 16 `habblaud034`.

| Gate | Resultado observado |
|---|---|
| `:shared:tasks --all` | Tasks reais `testAndroidHostTest`, `connectedAndroidDeviceTest` e `assembleAndroidDeviceTest` descobertas. |
| `:shared:testAndroidHostTest` | 76 testes, zero falhas/erros/skips na rodada aprovada. |
| `:app:lintDebug :app:assembleDebug` | Build aprovado; APK debug gerado no caminho Android existente. |
| `:shared:assembleAndroidDeviceTest :app:assembleDebugAndroidTest` | Ambos APKs instrumentados compilados. |
| `scripts/android-instrumented-ci.py` | 102 testes aprovados: 87 no shared e 15 no aplicativo; zero falhas/erros. |
| `npm run typecheck` | Aprovado, incluindo fixture TypeScript alterada. |
| Fixture com `--port 0` | Duas portas distintas válidas, login HTTP 200 com cookie e consulta de estatísticas real. |
| Smoke manual do APK | Login HTTP exige confirmação; lista e conversa aparecem; envio gera exatamente um POST 201 com `expectedSessionId` correto; entrega confirmada pela fixture; Home/retorno não repete POST. |
| `bash -n scripts/ios-build.sh scripts/ios-smoke.sh` | Sintaxe aprovada. Execução `ios-build.sh device` no Linux recusa corretamente ausência de macOS/Xcode. |

Rodada final após todas as correções de cookie/estado: `env ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug`, `BUILD SUCCESSFUL in 34s`; XML confirma os 76 testes host acima, sem falhas, erros ou skips.

`:app:testDebugUnitTest` não contém testes após a migração; não é evidência adicional de testes executados. Os testes comuns também são incluídos na instrumentação shared; não somar as contagens como casos únicos.

Relatórios locais:

- `android/shared/build/reports/tests/testAndroidHostTest/`
- `android/shared/build/reports/androidTests/connected/androidMain/`
- `android/app/build/reports/androidTests/connected/debug/`
- `android/app/build/reports/lint-results-debug.html`

O primeiro gate instrumentado encontrou falhas reais de persistência/fixtures de controlador, corrigidas antes da rodada aprovada. As falhas de visibilidade do aplicativo ocorreram com o laboratório em 320×640 pixels e override de densidade 420, largura efetiva de aproximadamente 122 dp. A rodada completa aprovada usou 1080×1920 pixels e densidade 420. Não foram removidos testes de UI para obter verde. O tamanho original foi restaurado, o override anterior preservado e o emulador encerrado.

## Bloqueio externo e autorização

O usuário autorizou publicar a branch no fork público e depois abrir PR de rascunho para disparar CI. A branch e o PR foram criados. O GitHub não iniciou runs por push ou PR; o workflow novo não estava registrado na branch padrão. Foi adicionada entrada `workflow_dispatch` ao workflow Android já registrado, chamando o workflow iOS reutilizável na branch de trabalho, sem alterar `main`.

O bloqueio inicial `HTTP 422: Actions has been disabled for this repository.` foi resolvido pelo usuário na interface do GitHub. Os runs macOS passaram a executar. O usuário também autorizou usar o MacBook da Mira sem atualizar o macOS.

### Correções e evidências após habilitar Actions

- Corrigidos imports de `kotlinx.coroutines.IO`, remoção condicional de mapa comum, bindings `UniformTypeIdentifiers`/`CoreCrypto`, opt-in de interop e hash de entrada vazia.
- Teste SSE de reset do backoff usa relógio virtual e sessão SSE explícita; preserva os intervalos esperados sem depender da velocidade do CI.
- Os 65 testes nativos passam com serviços do simulador ativo, runner instalado e entitlements XML/DER incorporados no Mach-O. O runner standalone não disponibilizava corretamente Keychain/UTType.
- Xcode compila o app arm64 do simulador, com recursos Compose e validação do binário. Corrigidos argumentos do `lipo` e arquitetura do build de smoke.
- `CADisableMinimumFrameDurationOnPhone` foi adicionado ao Info.plist após crash do Compose durante entrada de texto.
- Run [41822070113](https://github.com/andersonrfsilva/habblaud/actions/runs/41822070113): login chegou à mensagem de erro de armazenamento seguro; gravação visual exportada confirmou. Smoke passou a usar assinatura ad-hoc do Xcode, sem credenciais Apple, em vez de desabilitar assinatura. Login e recuperação de sessão foram posteriormente aprovados no Mac da Mira com essa correção.
- No mesmo run ocorreu crash intermitente Android de reentrada em layout durante teste de permissão. Não há causa comprovada; não foi mascarado nem houve mudança especulativa do produto. CI agora preserva relatórios e logcat em falhas.
- Último run [41827054409](https://github.com/andersonrfsilva/habblaud/actions/runs/41827054409), commit `eb7d164`: gates Android aprovados, build do simulador e testes nativos aprovados; smoke bloqueado por `Fixture output timed out`, antes de exercitar a assinatura ajustada. Nenhum IPA gerado.

### Mac da Mira

SSH autorizado e funcional; macOS 15.7.9, Apple Silicon. Checkout isolado em `/Users/mira/Developer/zaplaud-041`. Java 17 e Node 22 instalados e executados; instalador `xcodes` instalado. Homebrew reportou falhas em alguns post-installs de dependências, embora os executáveis Java/Node funcionem.

Xcode 26.3, build 17C529, instalado em `/Users/mira/Applications/Xcode-26.3.0.app`. O usuário aceitou a licença e concluiu `-runFirstLaunch`; o runtime iOS 26.3.1 arm64 foi instalado. Não houve atualização do macOS, downgrade de dependências ou troca global de `xcode-select`. Os comandos usam `DEVELOPER_DIR` por processo.

Com o código `9ae4b38`, Kotlin 2.4.21 compilou o framework do simulador em 12m57s, incluindo download inicial de Gradle/LLVM/Kotlin Native. `npm ci --ignore-scripts` e `npm run typecheck` passaram. A sequência `ios-build.sh simulator`, `ios-native-tests.sh` e `ios-smoke.sh` passou em 469 segundos:

- Build Xcode do simulador arm64, SDK 26.2.
- 65 testes nativos, zero falhas, incluindo Keychain, arquivos e anexos.
- XCTest de login HTTP, leitura em retrato e paisagem, envio HTTP 201 único, retorno do segundo plano e recuperação do rascunho após encerrar o processo. Asserções da fixture confirmaram ausência de replay.
- Capturas `conversation-landscape` e `draft-restored` inspecionadas. Resultado em `ios/build/smoke/Results-1793797118.xcresult`; relatório nativo em `ios/build/native-tests/report.json`.

O build Release de dispositivo excedeu o heap Gradle de 2 GiB na otimização Kotlin/Native. O limite foi ajustado para 4 GiB, sem desativar otimizações. O Mac possui 8 GiB de RAM; o consumo deve ser considerado ao compilar Release.

Com 4 GiB, `ios-build.sh device` concluiu em 492 segundos: Release arm64, plataforma `IOS` (não simulador), mínimo declarado 14.0, SDK 26.2, recursos Compose presentes e integridade ZIP aprovada. IPA sem assinatura, 14.446.132 bytes:

- Mac: `/Users/mira/Downloads/Zaplaud-b0779f67.ipa`.
- Origem: `/Users/mira/Developer/zaplaud-041/ios/build/Zaplaud.ipa`.
- SHA-256: `b0779f6730e82f3f55d5e8a195d2e390f72e82e00eecd9139ee3642af61f509e`; cópia no Linux conferida.

## Integração autorizada em producao

O usuário autorizou o merge mesmo com gates físicos pendentes. A branch incorporou `producao` em `563c29a`, preservando a linha de estado, cronômetro e correção de agrupamento de ferramentas adicionados após a base do port. Os helpers novos foram adaptados ao código comum, com testes de arredondamento de relógio e recursos Compose compartilhados.

Após resolver os conflitos: 79 testes host, lint, builds Android e 105 testes instrumentados (90 shared + 15 app) aprovados localmente. No run [41856332980](https://github.com/andersonrfsilva/habblaud/actions/runs/41856332980), ambos os jobs Android também passaram.

A revalidação Apple dessa integração não executou: Mac da Mira offline, SSH expirou; GitHub informou `The job was not acquired by Runner of type hosted even after multiple attempts` e falta de capacidade de runners macOS arm64. Isso não é falha de compilação observada, mas deixa o código integrado sem novo smoke Apple. O IPA `b0779f67` é anterior à integração da linha de estado; não representa o conteúdo final do merge.

## Gates ainda não comprovados

- Instalação, atualização e refresh pelo SideStore, Keychain após reassinatura e alternância/convivência com Tailscale.
- VoiceOver, seletor de documentos real/iCloud e comportamento de teclado/janelas no iPad.
- Atualização do APK antigo para o novo preservando instalação preenchida e mesma assinatura; testes de schema/persistência não substituem essa prova de atualização.
- Plano permanece sem DONE até validar os gates físicos. O merge foi autorizado separadamente pelo usuário e não comprova reassinatura ou instalação.

Manter os gates físicos do plano: build e smoke de simulador não comprovam instalação pelo SideStore, reassinatura ou acesso ao servidor real pela tailnet.
