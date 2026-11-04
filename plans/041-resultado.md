# Execução do plano 041

Estado canônico: linha 041 de `plans/README.md`. Branch `advisor/041-zaplaud-ipados-kotlin`, worktree `../habblaud-041`; revisão base `6019c62`. PR de rascunho autorizado: https://github.com/andersonrfsilva/habblaud/pull/1. Sem merge ou deploy.

## Implementação e decisões de integração

- Extração KMP/Compose para `android/shared`; host Android e serviço de notificações preservados; host UIKit, Keychain, arquivos privados, picker com security scope e lifecycle iPadOS implementados.
- Interface iOS não foi compilada nem executada. Presença de código não comprova funcionamento no aparelho.
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

O dispatch retornou `HTTP 422: Actions has been disabled for this repository.` A API de permissões reportava enabled, mas ativação por `PUT actions/permissions` e habilitação do workflow não liberaram o dispatch. Não há run macOS nem log de compilação Apple para esta branch. O Mac listado na tailnet estava offline; não há acesso SSH configurado. O usuário escolheu **continuar localmente**, sem exigir novo caminho remoto nesta execução.

## Gates ainda não comprovados

- Compilação Kotlin/Native e Xcode, testes `iosSimulatorArm64Test`, smoke do app no simulador iPad e IPA device arm64.
- Instalação, atualização e refresh pelo SideStore, Keychain após reassinatura e alternância/convivência com Tailscale.
- VoiceOver, seletor de documentos real/iCloud e comportamento de teclado/janelas no iPad.
- Atualização do APK antigo para o novo preservando instalação preenchida e mesma assinatura; testes de schema/persistência não substituem essa prova de atualização.
- Nenhum IPA foi gerado. O plano não pode ser marcado DONE nem o PR aprovado para merge com esses gates pendentes.

Para retomar, disponibilizar macOS/Xcode ou resolver o bloqueio de Actions. Executar o workflow Android registrado com `--ref advisor/041-zaplaud-ipados-kotlin` para chamar o job iPadOS, ou rodar os scripts iOS no Mac. Depois executar os gates físicos do plano, sem confundir build de simulador com instalação pelo SideStore.
