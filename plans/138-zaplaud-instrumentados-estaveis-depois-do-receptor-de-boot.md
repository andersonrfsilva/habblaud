# Plano 138: Zaplaud Android: os testes do receptor de boot deixam de derrubar o teste seguinte e a suíte instrumentada do CI volta a terminar

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 138 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 67ebfd6..HEAD -- android/app/src/androidTest/java/br/com/xlinx/habblaud/BootReceiverInstrumentedTest.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureFilesInstrumentedTest.kt`
> Se algum arquivo do escopo mudou desde `67ebfd6`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: MED (pode ser efeito real do serviço de avisos sobre a abertura do app)
- **Depende de**: 131 (mesclado)
- **Categoria**: tests
- **Planejado em**: commit `67ebfd6`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Desde que o plano 131 entrou, o job `instrumentado` do GitHub falhou em 2 de 3 execuções, sempre no mesmo ponto:
o primeiro teste que roda **logo depois** dos quatro testes novos do receptor de boot não consegue abrir a tela do
app. Na última execução a suíte ainda travou mais adiante e foi cancelada pelo limite de 45 minutos. Com isso o CI
do Android voltou a não ser confiável.

## Estado atual

Execuções do workflow `Android`, job `instrumentado` (emulador API 35, 42 testes do app):

| commit | resultado |
|---|---|
| `3decd97` (antes do 131) | verde |
| `0f3a213` (com 131) | `FixtureFilesInstrumentedTest.navigatesSubfolderAndSavesExactBytesInPublicDownloads` falhou: `IllegalStateException: No compose hierarchies found in the app` (teste 5 de 42) |
| `a4c5b2c` | verde |
| `67ebfd6` (run `44521923971`) | mesma falha no teste 5 de 42; depois parou em 12 de 42 e foi cancelado aos 45 min |

A ordem das classes é alfabética: `BootReceiverInstrumentedTest` (4 casos, plano 131) roda primeiro e
`FixtureFilesInstrumentedTest` vem logo depois. O teste de boot prepara avisos ligados e um servidor cadastrado,
chama `BootReceiver.onReceive` (que inicia `NotificationService` em primeiro plano de forma assíncrona, com
`goAsync` e prazo de 5 s) e limpa com `NotificationService.stop(context)` no `@Before`/`@After`
(`BootReceiverInstrumentedTest.kt:38-50,79`). No emulador local (API 36) a suíte completa passou uma vez com o 131
(229 casos). Hipóteses a testar: (a) o `@After` devolve o controle com o serviço ainda subindo ou parando (o
início é assíncrono), e o teste seguinte abre a Activity no meio disso; (b) sobra estado no armazenamento local
(avisos ligados, servidor) que muda o caminho de abertura; (c) na API 35 o início/parada do serviço em primeiro
plano mostra algo por cima da Activity (diálogo de permissão de notificação, por exemplo).
O travamento em 12 de 42 não tem causa conhecida: descubra qual teste estava rodando (log do run).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-138 -b advisor/138-zaplaud-instrumentados-estaveis-depois-do-receptor-de-boot producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-138`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só testes, salvo se a causa for o serviço de avisos atrapalhar a abertura do app em uso real (aí PARE).
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
- os dois arquivos de teste acima e utilitários de teste do mesmo diretório (`FixtureWait.kt`)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código do app (`android/app/src/main`, `shared`). `@Ignore`, reordenar classes para esconder o problema, aumentar prazo sem causa. Workflows. `plans/`.

## Passos

### Passo 1: reproduzir

No emulador (API 36 local; se houver imagem API 35 em `$ANDROID_HOME/system-images`, prefira-a criando um AVD
temporário, sem baixar nada), com a fixture no ar como o roteiro do CI faz: rode em laço só as duas classes na
ordem do CI (`-Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.BootReceiverInstrumentedTest,br.com.xlinx.habblaud.FixtureFilesInstrumentedTest`),
20 vezes, e `FixtureFilesInstrumentedTest` sozinha 10 vezes como controle. Baixe o log completo do run
`44521923971` (`gh run view 44521923971 --repo andersonrfsilva/habblaud --log`) e identifique o teste que estava
em execução quando parou em 12 de 42, e o que o `logcat` do artefato de diagnóstico mostra em torno da falha do
teste 5 (`gh run download 44521923971 --repo andersonrfsilva/habblaud -D /tmp/ci-138`).

**Verificação**: taxa de falha com e sem a classe de boot antes; teste do travamento identificado (ou "não
identificável pelo log", com o trecho).

### Passo 2: causa

Com `adb shell dumpsys activity services br.com.xlinx.habblaud` e `dumpsys activity activities` entre os testes
(e `logcat`), veja o estado ao fim de cada caso de boot: o serviço ainda existe? está iniciando? há janela do
sistema por cima? o armazenamento local voltou ao estado inicial? Decida entre as hipóteses.

**Verificação**: causa com evidência (saída de `dumpsys`/`logcat` salva em `~/dados/habblaud/plano-138/`).

### Passo 3: corrigir no teste

O teste de boot passa a terminar só quando o efeito que provocou terminou: esperar por condição que o serviço
subiu (quando o caso espera que suba) e, na limpeza, que ele **parou de fato** (não existe mais em
`ActivityManager`/notificações ativas) e que o receptor concluiu (`goAsync` finalizado), além de restaurar o
armazenamento local. Use `FixtureWait.kt`; não bloqueie a thread do teste esperando o app. Se a causa for o app
(por exemplo, o serviço em primeiro plano realmente impede ou atrasa a abertura da Activity para um usuário):
PARE e relate com a evidência.

**Verificação**: as duas classes em sequência 20 vezes sem falha; suíte completa
(`python3 scripts/android-instrumented-ci.py`, limite 1200 s) 3 vezes sem falha e sem travar.

## Plano de testes

Os próprios testes instrumentados.

## Critérios de pronto

Todos precisam valer:

- [ ] Taxa antes/depois nas duas classes em sequência
 - [ ] Causa com evidência
 - [ ] Suíte completa 3 vezes sem falha nem travamento
 - [ ] Teste do travamento identificado ou declarado não identificável
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 138 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `67ebfd6`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A causa for comportamento do app em uso real.
 - Não reproduzir localmente em 20 sequências nem na API 35: entregue a limpeza determinística justificada pela leitura e diga que a prova é o CI.

## Notas de manutenção

- Prova final: job `instrumentado` verde em pelo menos duas execuções seguidas no GitHub.
