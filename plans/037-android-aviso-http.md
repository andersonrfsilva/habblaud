# Plano 037: Confirmar autenticação em HTTP sem proibir LAN/VPN

> Executor: siga as etapas e os testes; em divergência, pare e relate. Não altere o índice sem coordenação com seu responsável. Antes: `git diff --stat 9469c0b..HEAD -- android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt android/app/src/main/res/values/strings.xml android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt README.pt-BR.md CHANGELOG.md`; compare também `git diff` e `git status --short` desses caminhos com os trechos abaixo. Preserve alterações preexistentes. Execute na raiz do repositório.

## Estado e motivação

- Prioridade P1; esforço M; risco médio; categoria segurança/UX; dependências: nenhuma. Planejado em `9469c0b`, 2026-11-03. O manifesto tinha alteração local alheia ao plano na leitura; não o modifique.
- HTTP é requisito deliberado para LAN/VPN, inclusive em endereços arbitrários. Sem proteção adicional de transporte, senha e sessão podem ser interceptadas ou alteradas na rede. O aviso informa o risco; não afirma que LAN ou VPN garanta proteção.
- `android/app/src/main/java/br/com/xlinx/habblaud/data/UrlPolicy.kt:6-16` canonicaliza origens e aceita explicitamente ambos `http` e `https`. Não altere essa política.
- `MainActivity.kt:101-105,114-117`: `connect()` calcula `val origin = UrlPolicy.origin(url)`, limpa `password`, e depois chama `current.login(user, secret)`; `HabblaudApi.kt:84-86` envia usuário e senha para `/api/remote/login`.
- `MainActivity.kt:145,173-176`: confirmação de troca chama `connect()` diretamente; botão entra diretamente ou abre confirmação de troca. Qualquer solução precisa cobrir ambos os caminhos.
- `MainActivity.kt:67-69` mantém senha em `remember`, não `rememberSaveable`. `strings.xml:18-38` contém textos em português e `confirm`/`cancel`; somente `values/strings.xml` foi encontrado. `MainActivity.kt:145` exemplifica `AlertDialog` com `onDismissRequest`, confirmar e cancelar.
- `AndroidManifest.xml:7` permite cleartext. Preserve isso, redirects e validação de origem. A conexão automática `MainActivity.kt:79-98` usa sessão salva e `access()`, não autenticação por senha; não acrescente diálogo nessa retomada.

## Escopo

Modificar somente `android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt`, `android/app/src/main/res/values/strings.xml`, `README.pt-BR.md`, `CHANGELOG.md` e criar `android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt`. Não mudar política de URL, manifesto, cliente HTTP, armazenamento, servidor ou configuração de rede. Não salvar senha, nem decisão de aceitar HTTP, em disco, `rememberSaveable`, argumentos de intent ou logs. Não registrar conteúdo de credenciais nos testes.

## Etapas

1. Centralize o encaminhamento para login: após validar `UrlPolicy.origin(url)`, preserve primeiro o diálogo existente de troca de servidor quando aplicável. Depois, para origem canônica `http://`, apresente `AlertDialog` com título e texto novos em `strings.xml`: senha e sessão trafegam sem criptografia HTTP; terceiros na rede podem interceptá-las; HTTPS é recomendado. Ofereça confirmar e cancelar. Para HTTPS, chame `connect()` sem novo diálogo. URL inválida mantém erro atual. A confirmação de troca deve passar pela mesma decisão HTTP, não chamar `connect()` diretamente.
   - Não capture uma senha em estado adicional: a senha permanece apenas no estado atual do campo até confirmação, e `connect()` a limpa como hoje. Cancelar, dispensar ou recriar a atividade não envia login nem altera a origem salva. Na confirmação valide novamente a origem e garanta que ela seja a mesma exibida no aviso; se mudou, cancele e exija novo clique. Não deixe o diálogo aprovar outra origem por mudança de campo ou recomposição. Uma confirmação vale somente para aquela tentativa; falhas e novas tentativas exigem novo aviso.
   - Gate: `git diff -- android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt android/app/src/main/res/values/strings.xml` deve mostrar os dois caminhos passando pelo aviso e nenhuma mudança de persistência ou política.
2. Crie teste instrumentado com `createEmptyComposeRule`, `ActivityScenario` e `MockWebServer` já disponível em `android/app/build.gradle.kts:64-69`, seguindo o ciclo de vida de `FixtureUiInstrumentedTest.kt:40-76`. Limpe estado de teste antes/depois, feche servidor e atividade, use credenciais fictícias. Use `requestCount`/`takeRequest` com timeout e respostas controladas. Para HTTPS sem dependência adicional de certificado, com URL `https://` e servidor inacessível, prove que não há diálogo e que o fluxo inicia (`busy`/erro de rede); não desative validação TLS nem trate a falha como login bem-sucedido.
   - Gate: `test -f android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt && git status --short -- android/app/src/androidTest/java/br/com/xlinx/habblaud/HttpLoginInstrumentedTest.kt` confirma a presença e situação do arquivo, inclusive se não rastreado.
3. Em `README.pt-BR.md:318-325`, explique que cada autenticação HTTP exige confirmação e não bloqueia LAN/VPN; preserve o aviso de risco e não diga que VPN garante proteção. Em `CHANGELOG.md` na seção `Não lançado`, registre a confirmação; corrija a afirmação preexistente nas linhas 39-42 que limita HTTP a uma faixa VPN, pois contraria `UrlPolicy`. Não altere itens não relacionados.
   - Gate: `git diff -- README.pt-BR.md CHANGELOG.md` mostra confirmação, riscos e remoção da restrição incorreta, sem mudanças alheias.

## Testes e critérios de conclusão

- HTTP sem origem salva: clicar Entrar mostra aviso antes de qualquer POST; cancelar e dispensar não fazem POST nem gravam origem; confirmar envia exatamente um POST de login ao servidor de teste. Uma nova tentativa volta a pedir confirmação.
- Troca para HTTP: cancelar troca não envia; confirmar troca abre aviso HTTP, ainda sem POST nem mudança de origem; cancelar aviso preserva origem anterior; confirmar ambos envia uma vez. Cubra também mesma origem HTTP salva e URL inválida. Verifique que rotação/recriação com aviso aberto não envia e não restaura a senha.
- HTTPS: nenhum aviso HTTP; tentativa ocorre sem confirmação adicional. Teste fluxo sem dependência de rede externa. Não simule sucesso TLS com certificado não confiável.
- Rode, com SDK Android e emulador/dispositivo conectado: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.HttpLoginInstrumentedTest` → código 0 e todos os testes da classe aprovados. O comando e resultados não foram executados durante o planejamento.
- `git status --short` mostra apenas os cinco caminhos de escopo alterados pelo executor, além de alterações anteriores preservadas. Os testes cobrem os casos acima e não armazenam senha.

## STOP e riscos

Pare se os trechos não corresponderem ao estado atual, se a troca exigir mudar armazenamento ou se os testes não conseguirem isolar rede/estado sem enfraquecer TLS. Pare após duas falhas do mesmo gate e relate. O aviso não protege tráfego HTTP, inclusive cookies de sessão; o usuário escolhe conscientemente usar LAN/VPN. Revise novamente esse fluxo se surgirem outros pontos de autenticação por senha.

## Execução bloqueada em 2026-11-03

- Trabalho não commitado: `/tmp/habblaud-037-android-http`. Não foi mesclado.
- Unitários e build passaram. Na última rodada instrumentada, quatro de seis testes passaram; os dois restantes não encontraram o botão Cancelar visível em um AVD com viewport de 122 dp.
- Próximo executor: corrigir ou diagnosticar a acessibilidade do diálogo nesse tamanho, sem enfraquecer os testes, e repetir os critérios. O aviso deve continuar bloqueando o envio até confirmação.
