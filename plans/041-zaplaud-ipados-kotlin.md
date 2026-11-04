# Plano 041: portar o Zaplaud para iPadOS com Kotlin e distribuir pelo SideStore

> Execute em uma worktree do clone de desenvolvimento, nunca em produção. Este documento planeja a migração; não autoriza deploy, commit, push ou acesso à conta Apple. Leia os critérios de aceite antes de começar. Estado de execução canônico: `plans/README.md`.

## Prioridade e base

- Prioridade P2; esforço L; risco HIGH; categoria migration/direction.
- Planejado em 2026-11-03 contra `6019c62`.
- Base funcional: Android presente neste commit, incluindo mudanças posteriores ao plano 034. Não reconstruir o aplicativo a partir do texto antigo do 034.
- Sem dependência de concluir o plano 033; preservar os limites atuais dos modos OMP. Os planos 035–040 registram correções já integradas e limitações de verificação, não etapas a repetir.
- Antes de editar: `git diff --stat 6019c62..HEAD -- android ios scripts .github/workflows README.pt-BR.md CHANGELOG.md plans`. Compare também alterações locais nos arquivos que tocar; não sobrescreva trabalho do usuário. Havendo mudança de contrato, atualize este plano antes da execução.
- Esta sessão fez leitura estática e consulta às documentações oficiais. Não executou builds, testes nem compilação iOS; não existe baseline verde novo.
- Execução autorizada em 2026-11-03: worktree `../habblaud-041`, branch `advisor/041-zaplaud-ipados-kotlin`. O usuário autorizou publicar somente essa branch no fork público `andersonrfsilva/habblaud` e executar GitHub Actions macOS, sem merge ou deploy. Essa autorização substitui a restrição de publicação no cabeçalho apenas para essa finalidade.

## Objetivo e decisões do usuário

Entregar o Zaplaud nativo no iPad, mantendo Kotlin para regras e interface com Kotlin Multiplatform (KMP) e Compose Multiplatform. O artefato final é um `.ipa` instalável pelo SideStore, não APK, WebView ou PWA. Uma entrada pequena em Swift/SwiftUI ou UIKit para hospedar `ComposeUIViewController` é permitida; não reescrever as telas em Swift.

O usuário aceita expressamente não ter serviços em segundo plano no iPadOS. Não adicionar APNs, Background Modes, tarefas periódicas, áudio silencioso, Network Extension ou truques para manter o processo vivo. Ao sair do primeiro plano, parar streams, polling e uploads; ao voltar, revalidar acesso e recuperar estado do servidor. A execução dos agentes no servidor continua independente do aplicativo.

Essa concessão vale para iPadOS, não autoriza remover o serviço de notificações Android existente. Os demais recursos atuais ficam no escopo. Login/SSE/envio constituem um marco intermediário, nunca a entrega final reduzida.

## Estado atual e evidência para o executor

Todos os caminhos abaixo são relativos à raiz do repositório.

| Evidência | Consequência para a migração |
|---|---|
| `android/settings.gradle.kts:1-4`; `android/app/build.gradle.kts:1-18,50-72` | Existe apenas `:app`, plugin Android application, API mínima 26 e compile/target 36. Manter esse módulo como host Android. |
| `android/gradle/libs.versions.toml:1-35` | AGP 9.0.1, Kotlin 2.2.10, Compose BOM 2025.10.00; markdown já usa artefato multiplataforma. Não substituir biblioteca sem verificar seus targets. |
| `android/app/src/main/java/br/com/xlinx/habblaud/MainActivity.kt:38-107,171-190` | Tema/login dependem de Context; logout cancela API, limpa estado e para serviço. Separar host de conteúdo compartilhado. |
| `android/app/src/main/java/br/com/xlinx/habblaud/ReadingScreen.kt:62-93,103-145` | Controlador inicia/para pelo lifecycle; picker, recursos `R`, BackHandler e notificação prendem a tela ao Android. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/HabblaudApi.kt:22-57,59-94` | OkHttp, JSONObject e Context; redirects recusados, POST sem retry, 401 limpa cookie, upload cancelável. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/ConversationRepository.kt:19-90` | SSE com cancelamento, limite de evento, overflow, backoff, Retry-After e status terminais. Preservar contrato, não só trocar imports. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/ReadingController.kt:54-112,190-257` | Seleção protegida por geração, cache incremental `after`/`at`, verificação da sessão após `init`. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/MessageController.kt:28-108,244-370` | Injeção de request/store já existe; polling, revisão do rascunho e `expectedSessionId` não podem se perder. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/LocalStateStore.kt:22-46,78-98,190-288` | DataStore Android, preferências separadas da conta, epochs/sequences e geração de cache; resumo tiny e opções por sessão já existem. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/SessionCookieStore.kt:17-74` | Cookie `habblaud_remote` criptografado no Android, limitado à origem exata e à validade. |
| `android/app/src/main/java/br/com/xlinx/habblaud/data/Attachments.kt:15-90` | Cinco anexos, 15 MiB cada, streaming de 8192 bytes, URIs e permissões persistentes Android. |
| `android/app/src/test/java/br/com/xlinx/habblaud/MessageSendTest.kt:15-86` | Exemplo existente de store em memória e servidor real de laboratório; perda da resposta deixa operação ambígua sem reenvio. |
| `.github/workflows/android.yml:16-36,38-79`; `README.pt-BR.md:262-326` | Comandos reais de build/teste e fixture; CI instrumentado depende de KVM. |
| `scripts/android-smoke.ts:5-25` | Fixture isola HOME e anexos fora da pasta real; não usar servidor de produção como fixture. |

Excertos para conferir drift:

```kotlin
// HabblaudApi.kt:24-25
internal fun httpClient(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).readTimeout(45, TimeUnit.SECONDS).build()

// MessageController.kt:66-67
fun start() { visible = true; state.value.key?.let { open(it) } }
fun stop() { visible = false; poll?.cancel(); controlPoll?.cancel(); poll = null; uploadJobs.values.toList().forEach { it.cancel() } }

// LocalStateStore.kt:41-44
interface MessageStore {
    suspend fun message(key: SessionKey): MessageLocalState
    suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
}
```

Siga esse padrão de dependências explícitas e `StateFlow`. Não introduza framework de DI, navegação nova, banco de dados ou arquitetura genérica de plugins. Preserve os comentários de invariantes ao mover código. Algumas seções do README descrevem versões anteriores dos recursos; o comportamento atual e os testes têm precedência, e o aceite deve cobrir os controles atuais.

## Arquitetura de destino

Manter o wrapper e a raiz Gradle em `android/`; o nome da pasta não justifica quebrar todos os scripts.

- `android/app`: aplicação Android, Manifest, assinatura, MainActivity, NotificationService e integração Android de picker/permissões. Continua gerando APK no caminho atual.
- `android/shared`: novo módulo `:shared`; `commonMain` com regras, modelos, controladores e telas; `androidMain` com armazenamento/serviços de plataforma Android; `iosMain` com integrações Apple. Testes puros em `commonTest`; testes dependentes de plataforma nos source sets correspondentes.
- `ios/Zaplaud.xcodeproj`: projeto e scheme compartilhado `Zaplaud`; host iPad com framework estático `ZaplaudShared`, gerado por `:shared`. Targets Kotlin `iosArm64` e `iosSimulatorArm64`. Não adicionar desktop, web ou simulador Intel sem necessidade demonstrada.
- Android continua com `com.android.application`; shared usa `org.jetbrains.kotlin.multiplatform`, `org.jetbrains.compose`, compiler Compose e `com.android.kotlin.multiplatform.library`. AGP 9 exige separar aplicação e biblioteca KMP. Não usar flags de compatibilidade antigas como solução permanente.
- Ktor para contrato HTTP/SSE comum, engine OkHttp no Android e Darwin no iOS. Usar o suporte SSE da versão escolhida, sem escrever outro parser. Validar antes os limites de memória e a política de reconexão; não deixar dois laços de retry concorrentes.
- Esclarecimento de execução: o parser SSE de Ktor 3.6.1 acumula linhas em `StringBuilder` sem limite de evento. É permitido limitar bytes/fronteiras de eventos no canal de entrada antes do parser nativo; esse limitador não deve decodificar ou despachar SSE. Cobrir LF, CRLF, fronteiras de chunks e comentários/dados excessivos. Fonte: `https://raw.githubusercontent.com/ktorio/ktor/3.6.1/ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/sse/DefaultClientSSESession.kt`.
- JSON comum com `kotlinx.serialization.json`, já adotado no projeto. Remover `org.json`, `java.*`, `android.*`, `R` e tipos OkHttp das assinaturas comuns; usar APIs multiplataforma e adaptadores pequenos apenas onde necessário.
- Recursos comuns com Compose Resources, preservando traduções/plurais e arte existente. Recursos do serviço/Manifest ficam Android. Reutilizar o renderer Markdown atual se a versão tiver variantes iOS compatíveis.
- Android conserva arquivos, chaves, criptografia e formato persistido atuais. iOS usa Keychain para cookie, preferências nativas para configurações simples e arquivos privados para rascunhos/cache. Não compartilhar armazenamento por iCloud nem grupos de Keychain.

A versão mínima do iPadOS deve ser a menor suportada pelo conjunto de dependências efetivamente escolhido. Não elevá-la arbitrariamente para simplificar código. Documentar versão mínima e combinação exata Kotlin/Compose/Ktor/AGP/Gradle/Xcode após a etapa 1; não usar versões dinâmicas nem EAP. A documentação atual informa iOS 14 para Compose 1.12.2, mas isso não prova compatibilidade da combinação completa do projeto.

## Escopo e limites

Permitido alterar na execução:

- `android/**`, incluindo consumidores, testes e recursos afetados pela extração.
- Novos `ios/**`, `.github/workflows/ios.yml`, `scripts/ios-build.sh`, `scripts/ios-smoke.sh` e, se necessário para o laboratório, `scripts/ios-smoke.ts`.
- `.github/workflows/android.yml` e scripts Android existentes somente para ajustar consumidores da migração, preservando seus comandos públicos.
- `server/test/android-fixture.ts` e auxiliares de fixture/teste diretamente usados por ele, somente para cenários compartilhados e isolamento iOS. Ler antes; nenhuma mudança em handlers produtivos.
- `.gitignore` somente para artefatos Xcode/Gradle e credenciais locais; `README.pt-BR.md`, `CHANGELOG.md` e este plano/índice para registrar resultado.

Fora de escopo: servidor produtivo, contrato HTTP, web client, extensão OMP, deploy, PWA, publicação na App Store, TestFlight, compra de conta Apple, sincronização de dados Android–iPad, notificações iOS, execução offline de comandos e redesenho visual. Swift mínimo de host não significa portar lógica para Swift.

## Etapas de execução e gates

### 1. Fixar ferramenta e provar compilação dupla

1. Registrar baseline Android com os comandos existentes abaixo. Falhas já conhecidas no índice não devem ser tratadas como regressão nova nem apagadas para obter verde.
2. Conferir matriz oficial e escolher versões estáveis compatíveis, com a menor atualização necessária. O catálogo atual não contém plugin KMP nem Compose Multiplatform.
3. Criar `:shared`, host Xcode e entrada Compose mínima temporária. Manter `:app` consumindo shared e compilando. Essa tela é apenas prova local e deve desaparecer na migração das telas reais.
4. Ativar explicitamente testes host do plugin Android-KMP; eles vêm desativados por padrão. Mover testes de símbolos `internal` para o módulo proprietário, sem tornar APIs públicas só para testes.
5. Criar scripts de build com saída estável e erros explícitos para ausência de macOS/Xcode/SDK. `scripts/ios-build.sh simulator` gera app para simulador; `device` gera IPA para reassinatura, ambos em `ios/build/` ignorado.

**Gates:** executar comandos Android existentes; no Mac `xcodebuild -version`, `xcrun --sdk iphoneos --show-sdk-version`, `android/gradlew -p android :shared:tasks --all`, `bash scripts/ios-build.sh simulator` e `bash scripts/ios-build.sh device`. Esperado: exit 0, tasks host/native presentes, app de simulador e IPA contendo binário device arm64. Registrar nomes reais das tasks geradas antes de configurar CI; não assumir que as tasks Android antigas existem em shared.

O Mac pode ser um runner GitHub Actions. Não é necessário comprar hardware, mas sua disponibilidade e permissões não foram verificadas. Se não houver Mac local, terminar todo trabalho verificável localmente e usar CI macOS autorizado; não declarar compilação iOS validada sem execução.

### 2. Extrair regras e transporte sem mudar protocolo

1. Migrar modelos e regras para shared; mover testes puros correspondentes com `kotlin.test`. Não duplicar implementações Android/iOS.
2. Adaptar `HabblaudApi`, `ConversationRepository`, `SessionRepository`, `ReadingController` e `MessageController` para APIs comuns. Retirar tipos de plataforma dos construtores; manter `MessageStore` como ponto de injeção existente.
3. Preservar: origem canônica, porta remota autenticada, cookie isolado, recusa de redirects HTTP e HTTPS, validação TLS normal, 401/403/404/409/429, `Retry-After` numérico e HTTP-date, limites de eventos e cancelamento real do socket.
4. Não habilitar retry geral de Ktor/engine. GET pode manter o retry já existente; POST e uploads nunca são reenviados automaticamente, inclusive depois de suspensão. As duas engines devem impedir redirects e replay próprios.
5. Preservar `messageSessionGuard`, `expectedSessionId`, identidade completa da sessão, estados queued/delivered/failed/unknown, limites dos modos OMP, revisão do rascunho e restauração por ID de operação. HTTP 200/202 não significa entrega concluída.
6. Manter o limite da conversa e semântica init/append/after. O limite de tamanho precisa evitar acumulação ilimitada durante recepção, não apenas rejeitar um evento após alocar toda a entrada.

**Gates:** `android/gradlew -p android :shared:allTests :app:testDebugUnitTest` no Mac, com relatório comprovando execução host e `iosSimulatorArm64`, não apenas tasks SKIPPED. Rodar os contratos de rede também contra as engines reais: redirects, resposta perdida após POST, SSE interrompido e cookie por origem. Confirmar contador da fixture: uma tentativa de POST, não duas.

### 3. Preservar dados Android e implementar armazenamento iOS

1. Conservar nomes de DataStore, chaves, hash de sessão, schema JSON e arquivo `remote-session` do Android. A extração não é autorização para limpar dados de usuários.
2. Extrair apenas o contrato de armazenamento necessário aos controladores. Manter mutações atômicas, mutex, epoch, sequence e invalidação de cache após logout/troca de origem.
3. iOS: guardar apenas cookie selecionado em Keychain com acesso quando desbloqueado e escopo do aplicativo, sem senha ou iCloud. Falha ao salvar sessão deve falhar de modo explícito, nunca cair para texto puro. Desligar armazenamento automático de cookies/credenciais da sessão Darwin para não manter cópia fora desse controle.
4. Rascunhos e cache em arquivos privados com escrita atômica e proteção de dados; preferências simples em armazenamento nativo. Separar preferências do aparelho de dados da conta como no Android. Aplicar mesma janela de 500 entradas e mesmos limites já existentes.
5. Logout/troca de servidor invalidam jobs e cookies antes de permitir dados novos. Uma gravação tardia não pode recriar dados da conta anterior. Cookie ausente após reassinatura exige login, não recuperação insegura.
6. Validar atualização Android com a mesma assinatura sobre instalação preenchida: configuração, rascunho, sessão selecionada e login continuam legíveis. Não desinstalar para esconder erro de migração.

**Gates:** suíte comum mais testes de armazenamento em ambas as plataformas; executar app antigo e novo no mesmo Android e registrar estado antes/depois. No iOS, relançar o processo, conferir rascunho/configuração, depois logout e troca de origem com escrita pendente. Nenhum dado da origem anterior reaparece.

### 4. Migrar interface e integrações de iPad

1. Mover conteúdo de MainActivity, ReadingScreen, SettingsScreen, PermissionSheet, CastActions e componentes associados para commonMain, lendo cada arquivo antes da extração. Android MainActivity permanece host e ponte de notificações.
2. Usar Compose Resources para texto/plurais/arte; manter temas, Markdown selecionável, links, avatares, busca, recentes e posição de rolagem. Não habilitar HTML nem imagens remotas no Markdown.
3. Fornecer integrações pequenas para abrir URL, clipboard quando utilizado, picker, ciclo de vida e navegação de retorno. No iPad não depender de botão Back Android. Evitar interfaces gigantes com funções vazias para notificações: controles exclusivos Android não são exibidos no iOS.
4. Preservar modos de envio em submenu, shake, modelo por provedor, raciocínio, contexto em uso, filtros de conversa, horários, chamadas/dispensas/retomadas de contatos, perguntas e permissões, leitura de subagentes e interrupção OMP.
5. Preservar configuração de resumo tiny: desligável, resposta resumida em até cinco frases pelo servidor, toque expande original. Usar endpoint existente; nunca embutir chave de modelo ou chamar LLM diretamente do aparelho.
6. Layout redimensionável no iPad: retrato, paisagem, Split View e janela estreita. Reutilizar navegação atual de uma coluna; duas colunas não são requisito. Respeitar safe areas, teclado virtual/físico, tamanho de fonte e VoiceOver; controles de envio e diálogos devem continuar acessíveis.
7. Picker iOS com documentos via API Apple, copiando por stream para área privada enquanto o recurso externo estiver acessível. Limitar antes/durante cópia, fechar security-scoped access e remover cópias quando nenhum rascunho/upload as referenciar. Preservar picker Android sem cópia desnecessária. Arquivo sumido ou download iCloud falho produz erro recuperável.
8. Upload deve continuar binário, por stream, sem base64 e sem materializar arquivo inteiro em memória. Verificar engine Darwin escolhida: se ela bufferizar, usar upload de arquivo nativo no adaptador iOS, com os mesmos cookies, cancelamento e política sem retry. Não alterar servidor para contornar engine.

**Gates:** builds Android/simulador; executar UI com fixture e capturar ambas as orientações, janela estreita, temas e teclado. Selecionar cinco arquivos válidos; sexto, vazio e arquivo maior que 15 MiB são recusados sem upload parcial aceito como mensagem. Consultar fixture para confirmar bytes/nome/MIME e sessão destinatária. Testar VoiceOver no dispositivo, não apenas screenshots.

### 5. Implementar primeiro plano e recuperação segura

1. Conectar lifecycle do host iOS ao start/stop já existentes; chamar start uma única vez por ativação e stop ao entrar em background. Picker e diálogos transitórios não devem destruir o rascunho.
2. Parar streams, timers de reconexão, polling e uploads ao sair. Não iniciar novos requests em background. Cancelamento de uma requisição já enviada não prova que o servidor deixou de processá-la.
3. Persistir rascunhos durante edição, não depender de callback final ao encerrar. Operação potencialmente enviada permanece unknown até conciliação; com ID, consultar status após retorno; sem ID, não reenviar. Revisão mais recente do rascunho nunca é apagada por resposta atrasada.
4. Ao voltar, revalidar acesso, atualizar lista, conferir identidade da sessão e retomar SSE pelo cursor existente; init completo continua sendo caminho válido. Mensagens novas no servidor aparecem sem duplicação. Se a sessão mudou, bloquear envio e preservar rascunho.
5. iOS não oferece configurações de notificação/serviço que não pode executar. Documentar que não avisa com app fechado. Não solicitar permissão de notificação nem adicionar entitlements de push.

**Gates:** laboratório conta conexões/requests. Após background e encerramento das requisições em voo, não surgem novas requisições durante 30 segundos. Gerar eventos na fixture enquanto oculto; retorno recupera dados uma vez. Repetir com POST cuja resposta foi perdida, upload interrompido, sessão trocada e cookie revogado. Android mantém seu comportamento de serviço fora da tela.

### 6. Empacotar IPA e integrar CI macOS

1. Projeto Xcode com família iPad (`TARGETED_DEVICE_FAMILY=2`), bundle ID estável, versão/build e orientação/multitarefa configuradas. Sem app extensions ou entitlements que dependam de conta paga. Sem credenciais, certificados ou provisioning profiles versionados.
2. Build device Release com destino `generic/platform=iOS`, `CODE_SIGNING_ALLOWED=NO` e `CODE_SIGNING_REQUIRED=NO`; empacotar app e recursos em `Payload/Zaplaud.app` dentro de `ios/build/Zaplaud.ipa`. Esse IPA é para reassinatura pelo SideStore, não diretamente instalável sem assinatura.
3. Usar framework Kotlin estático para reduzir partes a reassinar; conferir frameworks dinâmicos remanescentes e recursos empacotados. Não confundir binário arm64 de simulador com arm64 de aparelho.
4. `scripts/ios-build.sh` deve imprimir caminhos dos artefatos, SHA-256 e versões da toolchain. Verificar plist, `LC_BUILD_VERSION` via `xcrun vtool -show-build` e conteúdo ZIP; scripts falham se saída não for dispositivo iOS ou recursos estiverem ausentes.
5. CI macOS: testes compartilhados/native, build simulador e build device; upload do IPA como artefato, sem publicação automática. Fixar runner/Xcode compatíveis e actions por SHA, como CI Android. Não armazenar Apple ID no CI; o usuário autentica no SideStore no próprio dispositivo.
6. Documentar instalação inicial/Developer Mode conforme versão do iPadOS, importação IPA, atualização mantendo bundle ID/conta e refresh. Conta gratuita: normalmente três apps ativos incluindo SideStore, validade de sete dias. Não prometer refresh infalível.
7. Validar acesso HTTP escolhido pelo usuário, HTTPS e rede local/Tailscale no iPad. Preservar confirmação a cada login HTTP. Configurar ATS e descrição de acesso à rede local conscientemente; não desligar verificação TLS. Se aceitar origens HTTP arbitrárias exigir exceção ATS ampla, justificar no plist/documentação e manter origem/redirects restritos na aplicação.
8. SideStore usa VPN local para instalação/refresh. Testar convivência ou alternância com Tailscale no dispositivo; não presumir que ambos túneis podem ficar ativos. Após refresh, restabelecer Tailscale e provar reconexão sem vazamento de cookie para outra origem.

**Gates:** `bash scripts/ios-build.sh device`, `unzip -t ios/build/Zaplaud.ipa` e hash SHA-256 passam. Instalação real pelo SideStore abre app, autentica e envia mensagem; atualizar IPA por cima conserva estado. Testar refresh sem prometer aguardar sete dias como gate. A instalação no aparelho é obrigatória: simulador não prova assinatura, Keychain pós-reassinatura nem VPN.

### 7. Aceitar paridade e registrar resultado

Rodar a matriz abaixo sobre o IPA final e sobre APK atualizado. Remover tela temporária, implementações comuns duplicadas, aliases de transição e dependências sem consumidores; manter código Android realmente específico. Atualizar README e changelog somente com comportamento comprovado. Atualizar índice para DONE apenas quando todos os gates obrigatórios tiverem evidência; se faltar Mac/iPad/SideStore, registrar BLOCKED com pré-requisito exato, sem chamar o pacote de concluído.

## Comandos e verificação

Comandos existentes, observados no repositório; executar a partir da raiz:

```bash
android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
android/gradlew -p android :app:testReleaseUnitTest --tests '*UrlPolicyTest*' --tests '*VpnGuardTest*'
npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages
```

Esperado: Gradle exit 0; fixture imprime `ready: true`. Ausência de testes de um filtro não prova cobertura. Fixture requer dependências Node já instaladas ou instalação de laboratório autorizada. Para instrumentação Android seguir `README.pt-BR.md:283-317`, com `adb reverse` da porta 14748 e da porta local dinâmica, nunca uma porta de produção.

Comandos novos, contratos a implementar neste plano (não existem nem foram executados nesta sessão):

```bash
android/gradlew -p android :shared:allTests :app:testDebugUnitTest
bash scripts/ios-build.sh simulator
bash scripts/ios-smoke.sh
bash scripts/ios-build.sh device
unzip -t ios/build/Zaplaud.ipa
```

`ios-smoke.sh` seleciona um simulador iPad disponível, instala/abre o app real e executa cenários contra fixture isolada. Deve usar porta local dinâmica publicada pela fixture, limpar processos ao sair, devolver exit não zero em falha e salvar relatório/capturas em `ios/build/smoke/`. Não requer credenciais de produção. Se fixtures Android não cobrirem resumo/controles recentes, ampliar apenas laboratório; não aceitar testes que apenas ecoam mocks como prova de entrega.

## Matriz de aceite e testes permanentes

| Caso | Evidência exigida |
|---|---|
| Login HTTPS, erro TLS, HTTP recusado/confirmado, 429 e logout offline | Requests observados na fixture; nenhuma credencial sai antes de confirmar HTTP, nenhum redirect recebe cookie. |
| Lista, recentes, conversa viva/histórica, subagente e filtros | Interface real e estado de fixture concordam; cache não troca destinatário. |
| Envio em aside/followUp/steer, anexos e interrupção | Corpo recebido mantém guard/identidade; status acompanha operação sem confundir accepted e delivered. |
| Shake, modelos por provedor, raciocínio, contexto e horários | Controles reais disponíveis conforme capacidades; resposta perdida não provoca repetição. |
| Chamar, dispensar, retomar contato e decidir perguntas/permissões | Ação registrada na fixture e reflected no app; guard permanece ativo. |
| Resumo tiny e expansão | Configuração persiste; resumo/original correspondem à entrada correta, inclusive após atualização do texto. |
| Suspensão, retorno e processo encerrado | Rascunho salvo; sem novo request em background; cursor e status retomam sem duplicar POST. |
| Logout/troca de servidor durante cache/upload | Dados antigos não reaparecem; anexos liberados apenas após cessar uso. |
| Atualização Android sobre instalação existente | Mesma assinatura e identidade; dados continuam legíveis, serviço segue funcional. |
| iPad retrato/paisagem/janela estreita, teclado e VoiceOver | Capturas e interação no app; enviar/voltar/confirmar permanecem acessíveis. |
| SideStore instalar, atualizar, refresh e Tailscale | IPA device real abre; estado conserva quando identidade permite; acesso ao servidor volta após alternância de VPN. |

Migrar testes existentes em vez de duplicá-los. Novos casos permanentes devem cobrir riscos introduzidos: lifecycle com POST ambíguo, isolamento de cookies Darwin, persistência Keychain, cache tardio após logout e upload iOS limitado/cancelável. Usar `MessageSendTest` como exemplo de comportamento observado; testes puros em `commonTest`, integração native em `iosTest` e UI no target Xcode de testes quando necessário. Testes determinísticos não dependem de conta Apple, produção ou Tailscale real; esses pontos ficam no smoke de dispositivo com evidência separada.

## Rollback e critérios de parada

- Trabalhar em branch `advisor/041-zaplaud-ipados-kotlin`; não publicar automaticamente. Se houver commits autorizados, seguir estilo existente `feat(android): ...`/`feat(ios): ...` por unidade funcional.
- Manter identificador e schema persistido Android. Rollback de código consiste em reverter a migração e recompilar com a mesma assinatura e versionCode de atualização apropriado; não exigir downgrade por adb ou apagar dados. Provar que estado escrito pelo novo código continua legível pelo antigo antes de afirmar reversibilidade.
- iOS é instalação nova. Conservar IPA anterior após cada entrega. Reinstalar pelo mesmo SideStore/conta/bundle ID pode preservar dados, mas remoção do app ou mudança de identidade de assinatura não tem essa garantia. Não desinstalar como procedimento de atualização.
- Não há migração de servidor nem rollback de produção neste plano.
- Parar e reportar se o port exigir mudar contrato de servidor, remover um recurso atual além do background iOS aprovado, enfraquecer TLS/isolamento de cookies, apagar dados Android, ou usar entitlement pago indispensável.
- Se a combinação de dependências não compilar para ambos targets, resolver primeiro a matriz; não manter fork inteiro do app para ocultar incompatibilidade.
- Falta de Mac, iPad acessível, conta SideStore configurada ou permissão para rodar CI bloqueia o gate correspondente, não justifica alegar validação. Terminar as partes verificáveis e registrar exatamente o que falta.

## Fontes externas e manutenção

Consultadas em 2026-11-03:

- [Compatibilidade Compose Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html): matriz e compiler alinhado ao Kotlin.
- [Plugin Android-KMP](https://developer.android.com/kotlin/multiplatform/plugin): aplicação separada, biblioteca multiplataforma e testes desativados por padrão.
- [SideStore FAQ](https://docs.sidestore.io/docs/faq): reassinatura, VPN local, validade e limite de apps gratuitos.

Revisões futuras precisam verificar a combinação de toolchain inteira, não atualizar Kotlin/Compose/Xcode independentemente. Qualquer novo controle Android compartilhado deve aparecer também no iPad, exceto integrações explicitamente exclusivas de plataforma. Nenhum comportamento em segundo plano deve entrar por acidente ao trocar bibliotecas de lifecycle ou rede.
