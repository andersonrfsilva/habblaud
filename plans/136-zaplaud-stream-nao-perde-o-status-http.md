# Plano 136: Zaplaud Kotlin: o stream não perde o status HTTP quando a conexão cai depois dos cabeçalhos

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 136 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat a4c5b2c..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ConversationRepository.kt android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/ConversationStreamTest.kt`
> Se algum arquivo do escopo mudou desde `a4c5b2c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (caminho de reconexão e de login vencido de todas as conversas)
- **Depende de**: 135 (diagnóstico)
- **Categoria**: bug
- **Planejado em**: commit `a4c5b2c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Quando o servidor responde a um stream com 401, 403, 404 ou 500 e a conexão é cortada antes de o corpo da resposta
terminar, o Zaplaud trata como falha de rede: não pede login de novo no 401 (fica reconectando para sempre), e
trata 403/404 como passageiros. Um proxy, o Tailscale ou o próprio servidor encerrando rápido bastam para isso. O
defeito foi achado porque os testes de host falham por ele no runner macOS do GitHub (e 1 em 3 vezes aqui com 2
núcleos), o que hoje impede o workflow `iPadOS` de compilar o iOS.

## Estado atual

Diagnóstico do plano 135 (evidências em `~/dados/habblaud/plano-135/`):

- Ktor 3.6.1, plugin SSE: diante de resposta que não é um stream válido, `SSE.kt:200-204` chama `response.saved()`
  (lê o corpo inteiro) **antes** de criar a `SSEClientException` que carrega a resposta; se a leitura do corpo lança
  (`SavedCall.kt:34-38`), `builders.kt:1197,1209-1225` embrulha numa exceção **sem resposta**.
- `ConversationRepository.kt:48-58,79-83` tira o status de `exception.response?.status`; sem resposta emite
  `StreamEvent.Failure(null)` e segue a política de falha transitória (tenta de novo; só avisa na terceira seguida).
  O callback de não autorizado não é chamado.
- Reproduzido com o cliente de produção e `MockWebServer`: cabeçalhos 401 e 500 confirmados no OkHttp, corpo
  truncado → `Failure(null)` nos dois, após três pedidos. Corpo vazio, atraso, envio parcelado, `Connection: close`
  e desconexão depois da resposta completa preservam o status.
- Falhas do CI macOS: `ConversationStreamTest.transientFailuresBecomeNoticeOnlyOnTheThirdInARow`
  (`expected:<500> but was:<null>`, run `44512351558`) e `unauthorizedStopsWithoutRetry` (5 s, run `44514754669`).
  Não ficou provado que foi corpo truncado que causou essas duas; é o único mecanismo encontrado que produz o sintoma.

Outros consumidores: procure todos os usos de SSE e de `exception.response?.status` no `shared`
(`grep -rn 'SSEClientException\|sseSession\|\.sse(' android/shared/src`): o vigia de avisos (`AlertWatcher`) e o
stream de sessões podem ter o mesmo padrão.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-136 -b advisor/136-zaplaud-stream-nao-perde-o-status-http producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-136`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Confira o Rust (`zaplaud-rs/crates/zaplaud-core/src/api/` — leitura de SSE com `reqwest`): o status vem dos cabeçalhos antes do corpo? Se o Rust tiver a mesma perda, PARE essa parte e relate (plano próprio); se não tiver, registre em NOTES com arquivo:linha.
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
- `ConversationRepository.kt` e, se tiverem o mesmo padrão, os outros consumidores de SSE do `shared` (liste-os)
- `ConversationStreamTest.kt` e testes de host vizinhos
- `CHANGELOG.md` (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Trocar de biblioteca HTTP ou de versão do Ktor. Mudar a política de reconexão (quantas falhas até avisar, atrasos). iOS além do que compartilha em `commonMain`. `plans/`.

## Passos

### Passo 1: testes que falham hoje

Em `ConversationStreamTest`, com `MockWebServer` e o cliente de produção: para 401, 403, 404 e 500, responder com
os cabeçalhos e truncar o corpo (use a política que o executor do 135 usou; veja `~/dados/habblaud/plano-135/`).
Esperado: 401 chama o callback de não autorizado e encerra sem nova tentativa (1 pedido); 403 e 404 emitem
`Failure(status)` definitivo; 500 emite `Failure(500)` segundo a política atual (na terceira seguida).

**Verificação**: os casos novos falham na base (status nulo), com a mensagem registrada.

### Passo 2: capturar o status nos cabeçalhos

Faça o repositório conhecer o status da resposta **sem depender da leitura do corpo**: por exemplo, registrando o
status da última resposta daquele pedido num interceptador do cliente (`HttpSend`/pipeline de recepção) ou
fazendo o pedido de forma que os cabeçalhos sejam inspecionados antes de entregar ao plugin SSE; quando a exceção
chegar sem resposta, use o status capturado. Um status capturado só vale para o pedido que o gerou (sem estado
compartilhado entre streams concorrentes: cada chamada de `stream` tem o seu). Falha antes dos cabeçalhos continua
sendo `Failure(null)` (rede). Aplique o mesmo aos outros consumidores listados.

**Verificação**: os testes do passo 1 passam; os testes existentes da classe passam; acrescente o caso
"conexão recusada/cortada antes dos cabeçalhos → `Failure(null)` e nova tentativa".

### Passo 3: estabilidade e regressão

`ANDROID_HOME=/home/anderson/Android/Sdk taskset -c 0,1 android/gradlew -p android :shared:testAndroidHostTest --rerun-tasks --max-workers=1`
20 vezes seguidas (10 com carga de CPU sua num dos dois núcleos; mate-a depois). Depois o comando completo:
`:shared:testAndroidHostTest :app:assembleDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin --max-workers=3`.

**Verificação**: 20/20 sem falha em nenhuma classe; `BUILD SUCCESSFUL`. Se `ConversationStreamTest` ainda falhar
por outro motivo, relate a mensagem exata: não é este defeito.

## Plano de testes

Casos de corpo truncado para 401/403/404/500 e o de falha antes dos cabeçalhos.

## Critérios de pronto

Todos precisam valer:

- [ ] Corpo truncado: 401 pede login e não tenta de novo; 403/404/500 mantêm o status
- [ ] Falha antes dos cabeçalhos continua sendo falha de rede
- [ ] 20/20 sob 2 núcleos; build ok
- [ ] Outros consumidores de SSE conferidos; Rust conferido por leitura
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 136 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `a4c5b2c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Capturar o status exigir trocar a versão do Ktor ou abandonar o plugin SSE.
- O Rust tiver a mesma perda (relate; não corrija aqui).

## Notas de manutenção

- Prova final: passo "Shared Android host tests" verde no workflow `iPadOS`, que volta a compilar o iOS.
- Se o Ktor corrigir a ordem em `SSE.kt` numa versão futura, a captura própria pode sair.
