# Plano 135: Zaplaud Kotlin: `ConversationStreamTest` deixa de falhar por tempo no runner macOS

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 135 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat a4c5b2c..HEAD -- android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/ConversationStreamTest.kt android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/ReadingStateConcurrencyTest.kt`
> Se algum arquivo do escopo mudou desde `a4c5b2c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: LOW-MED
- **Depende de**: 129 (mesclado)
- **Categoria**: tests
- **Planejado em**: commit `a4c5b2c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O workflow `iPadOS` roda os testes de host do `shared` num runner macOS antes de compilar o iOS. Desde o lote com o
plano 129 ele falhou duas vezes seguidas nesse passo, cada vez num teste diferente da mesma classe, e por isso o
iOS nem chega a ser compilado: o plano 103 (iPad) e qualquer mudança de código compartilhado ficam sem prova.

## Estado atual

Falhas no passo "Shared Android host tests" do job `native` (macOS):

- run `44512351558` (commit `0f3a213`): `ConversationStreamTest.transientFailuresBecomeNoticeOnlyOnTheThirdInARow` — `AssertionError` (`ConversationStreamTest.kt:94` é a linha do `runBlocking`).
- run `44514754669` (commit `a4c5b2c`): `ConversationStreamTest.unauthorizedStopsWithoutRetry` — `TimeoutCancellationException` (o `withTimeout(5000)` em `:127`).

Nos mesmos commits o job `verificar` do Android (Ubuntu) passou os mesmos testes, e nesta máquina passam (178, 0
falhas). Último verde do `iPadOS`: `3decd97`. Entre `3decd97` e `a4c5b2c`, `ConversationRepository.kt` e
`ConversationStreamTest.kt` **não mudaram**; em `android/shared/src` mudaram só `ReadingController.kt` e dois testes
novos: `ReadingStateConcurrencyTest` (plano 129: 200 corridas em `Dispatchers.Default`) e
`ConversationCacheScheduleTest` (plano 130, tempo virtual).

`ConversationStreamTest` usa `MockWebServer` e cliente HTTP reais, com `runBlocking` e prazos de 5 s e 15 s de
relógio de parede. Hipóteses a testar, não a assumir: (a) `ReadingStateConcurrencyTest` deixa trabalho vivo (rotinas
ou threads do `Dispatchers.Default` ainda ocupadas, escopo não cancelado) que disputa CPU com os testes seguintes
num runner de 3 núcleos; (b) os prazos de parede de `ConversationStreamTest` já eram justos demais para o macOS e
a suíte ficou um pouco mais pesada; (c) ordem de execução das classes mudou.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-135 -b advisor/135-zaplaud-testes-de-host-estaveis-no-runner-macos producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-135`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só testes.
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
- os dois arquivos de teste acima (e `ConversationCacheScheduleTest.kt` se a causa estiver nele)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código de produção. Aumentar prazo como única correção; `@Ignore`. Workflows. `plans/`.

## Passos

### Passo 1: reproduzir com poucos núcleos

Rode os testes de host presos a 2 núcleos, em laço:
`ANDROID_HOME=/home/anderson/Android/Sdk taskset -c 0,1 android/gradlew -p android :shared:testAndroidHostTest --rerun-tasks --max-workers=1 -Dorg.gradle.workers.max=1`
(10 vezes; some também uma carga de CPU num dos dois núcleos em 5 delas, e mate-a depois). Anote quais testes
falham. Veja no relatório XML a ordem das classes e os tempos (`android/shared/build/test-results/**`).

**Verificação**: pelo menos uma falha de `ConversationStreamTest` reproduzida, ou 0 em 10 relatado com os tempos de cada teste da classe (para ver quão perto do prazo ficam).

### Passo 2: achar a causa

(a) Leia `ReadingStateConcurrencyTest`: toda rotina lançada é aguardada (`join`/`coroutineScope`) e o escopo é
cancelado ao fim? Rode a suíte com a classe excluída (`--tests` com filtro, ou renomeando temporariamente) e
compare a taxa de falha. (b) Em `ConversationStreamTest`, veja o que cada teste que falhou espera: há algo que
dependa de tempo real além do prazo (reconexão com `delay` real? `server.shutdown()` com conexões abertas? cliente
não fechado entre testes — a classe guarda `clients`)? Meça quanto tempo cada um leva sozinho sob 2 núcleos.

**Verificação**: causa identificada com evidência (taxa com/sem a classe nova; tempos; ou recurso vazando).

### Passo 3: corrigir

Vazamento → o teste que vaza passa a esperar/cancelar tudo o que lança. Dependência de tempo real nos testes de
stream → troque por tempo virtual onde o repositório aceitar (veja como o primeiro teste da classe usa
`currentTime`/`MockEngine`), ou feche clientes/servidor de forma determinística; prazo maior só se a operação
legítima (subir `MockWebServer`, primeira conexão) medir perto do limite, com o número medido no comentário.

**Verificação**: 20 execuções seguidas presas a 2 núcleos (10 com carga) sem falha em nenhuma classe; o teste de
corrida do 129 continua provando a corrida (falha se a correção do 129 for desfeita localmente: confira uma vez e reverta).

## Plano de testes

Os próprios testes.

## Critérios de pronto

Todos precisam valer:

- [ ] Causa com evidência
 - [ ] 20/20 sob 2 núcleos
 - [ ] Nenhum `@Ignore`; teste do 129 ainda acusa a corrida
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 135 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `a4c5b2c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A causa for código de produção (cliente HTTP não fecha, repositório não respeita cancelamento): relate com arquivo:linha.

## Notas de manutenção

- Prova final: passo "Shared Android host tests" verde no workflow `iPadOS`.
