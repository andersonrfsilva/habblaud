# Plano 122: Zaplaud: suíte instrumentada do app passa no emulador do CI (API 35) e deixa de falhar por tempo no emulador local

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 122 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat f50b599..HEAD -- android/app/src/androidTest scripts/android-instrumented-ci.py .github/workflows/android.yml`
> Se algum arquivo do escopo mudou desde `f50b599`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (testes de tela dependentes de tamanho e tempo; exige emulador)
- **Depende de**: nenhum
- **Categoria**: tests
- **Planejado em**: commit `f50b599`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O job `instrumentado` do workflow Android está vermelho no GitHub desde pelo menos 2026-11-14, então o CI não
protege nada: uma regressão real passaria despercebida no meio das falhas de sempre. O runner tem KVM e o emulador
sobe; o que falha são quatro testes de tela. No emulador local a suíte também é instável (1 execução limpa em 5).

## Estado atual

**No CI** (run do commit `f50b599`, emulador `reactivecircus/android-emulator-runner` com `api-level: 35`, perfil
padrão da ação; os 187 testes de aparelho do `shared` passam; 4 de 38 do app falham):

- `FixtureUiInstrumentedTest.olderSummaryExpandsWithoutMovingTappedHeader` e
  `newestSummaryExpandsWithoutMovingTappedHeader`: `The component with Text ... contains 'Mostrar o resumo' is not displayed!`
  em `expandLongSummary` (`FixtureUiInstrumentedTest.kt:705`).
- `searchWithoutMatchesOffersClear`: `'Nenhuma sessão corresponde à busca.' is not displayed!`
- `searchRevealsOnlyAfterLongPullAndStaysPinned`: `TestTag = 'session-search' is not displayed!`

**No emulador local** `habblaud034` (API 36, 320×640 px a 160 dpi) esses quatro passam. Falhas locais vistas em
2026-11-18, em 5 execuções da suíte: `thinkingExpandsAllLinesAndCollapses` esgotou 15 s esperando
"Terceira linha do pensamento." 2 vezes (`FixtureUiInstrumentedTest.kt:~317`);
`StatusLineInstrumentedTest.textUpdatesOncePerSecondAndPausesWhileStopped` 1 vez (depende de relógio real);
1 execução travou depois de 8/38 testes; e o erro interno do Compose em
`zeroClawControlsSendStopAndNewKeepSessionDraftsSeparate` 1 vez (**fora de escopo**: decisão do dono de não investigar).

Hipótese a confirmar, não a assumir: os quatro do CI dependem do tamanho da tela (o emulador do CI tem outra
resolução/densidade e outra versão) — o item existe mas fica fora da área visível, ou atrás do teclado. Nota
relacionada já registrada: em 320×640 dp o botão "Limpar busca" fica atrás do teclado.

O roteiro `scripts/android-instrumented-ci.py` sobe a fixture Node, roda
`./gradlew --max-workers=4 :shared:connectedAndroidDeviceTest :app:connectedDebugAndroidTest` e valida os XML.
Testes que mudam o tamanho de tela usam `wm size`/`wm density` e restauram no `@After` (veja `resizeDisplay`).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-122 -b advisor/122-zaplaud-suite-instrumentada-estavel-no-ci-e-local producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-122`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só testes; nenhum comportamento muda. Se a investigação mostrar defeito real do app, PARE e relate.
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
- `android/app/src/androidTest/**` (testes e utilitários de teste)
- `scripts/android-instrumented-ci.py` e `.github/workflows/android.yml` (só se a causa for o ambiente do emulador:
  fixar perfil/tamanho/densidade de tela para o CI e o local coincidirem)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código do app (`android/app/src/main`, `android/shared/src/*Main`). Defeito real do app é condição de PARADA.
- O erro interno do Compose em `zeroClawControls...`.
- Desligar, ignorar (`@Ignore`) ou afrouxar um teste só para passar: proibido. Tempo de espera maior só com
  explicação do porquê o prazo atual é curto para uma operação legítima.

## Passos

### Passo 1: reproduzir o ambiente do CI no emulador local

Descubra a tela do emulador do CI: no log do job (`gh run list --repo andersonrfsilva/habblaud --workflow Android --limit 1`,
depois `gh run view <id> --repo andersonrfsilva/habblaud --log | grep -i 'hw.lcd\|skin\|density\|Physical size'`) ou
na documentação da ação para o perfil padrão. Aplique no emulador local com `adb shell wm size WxH` e
`adb shell wm density D` e rode só os quatro testes
(`-Pandroid.testInstrumentationRunnerArguments.class=br.com.xlinx.habblaud.FixtureUiInstrumentedTest#<nome>`; a
fixture precisa estar no ar: veja como o roteiro a sobe e reutilize).

**Verificação**: pelo menos um dos quatro falha localmente com a mesma mensagem do CI. Se nenhum falhar em
nenhuma combinação razoável (inclusive imagem API 35, se já estiver instalada em `$ANDROID_HOME/system-images`),
relate o que tentou e siga para o passo 3; o passo 2 fica para a evidência do CI.

### Passo 2: corrigir a causa nos quatro testes

Para cada teste, determine por que o nó não está visível (captura com `adb exec-out screencap -p`, árvore com
`compose.onRoot().printToLog`). Correções aceitas: rolar até o nó antes de afirmar (`performScrollTo`,
`performScrollToNode`), fechar o teclado quando o teste não é sobre ele, ou fixar o tamanho de tela que o teste
pressupõe com `resizeDisplay(...)` (restaurado no `@After`), dizendo no nome/comentário qual tela o teste cobre.
Se o nó não puder ser alcançado por um usuário naquela tela (ex.: fica atrás do teclado sem como rolar), isso é
defeito do app: PARE e relate com a captura.

**Verificação**: os quatro passam 3 vezes seguidas no tamanho do CI e 3 vezes seguidas em 320×640 a 160 dpi.

### Passo 3: instabilidade local por tempo

`thinkingExpandsAllLinesAndCollapses`: descubra por que "Terceira linha" não aparece depois do clique (o clique
acerta o nó? a lista ainda está rolando? o estado expandido é perdido numa recomposição?). Corrija a espera/ação
do teste para depender de um estado observável, não de sorte de tempo. Se o clique é dado e o app não expande,
é defeito do app: PARE.
`StatusLineInstrumentedTest.textUpdatesOncePerSecondAndPausesWhileStopped`: troque a dependência de relógio real
por espera de condição com margem (o texto muda pelo menos uma vez em N segundos; não muda enquanto parado),
sem tocar no código do app.

**Verificação**: cada um dos dois passa 10 vezes seguidas isolado (laço no shell), com a máquina ocupada por
um `./gradlew :shared:testAndroidHostTest --rerun-tasks` em paralelo em pelo menos 3 dessas vezes.

### Passo 4: suíte completa

Rode `python3 scripts/android-instrumented-ci.py` 3 vezes seguidas no emulador local.

**Verificação**: 3 execuções com `exit=0`, ou falhas apenas em `zeroClawControlsSendStopAndNewKeepSessionDraftsSeparate`
com `ComposeRuntimeError` (anote quantas). Uma execução que trave por mais de 15 minutos conta como falha:
colete `adb logcat -d | tail -300` e o último teste iniciado, e relate.

## Plano de testes

Os próprios testes instrumentados. Nenhum teste novo, salvo se dividir um teste em dois tamanhos de tela.

## Critérios de pronto

Todos precisam valer:

- [ ] Os quatro testes que falham no CI passam no tamanho de tela do CI e em 320×640 a 160 dpi
- [ ] `thinking...` e `StatusLine...` passam 10 vezes seguidas
- [ ] Suíte completa: 3 execuções seguidas sem falha fora da exceção conhecida
- [ ] Nenhum `@Ignore`, nenhum arquivo de `main`/`*Main` alterado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 122 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `f50b599`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Um teste falhar porque o app realmente não mostra/alcança o elemento (defeito do app).
- Não conseguir reproduzir nem explicar uma falha depois de esgotar o passo correspondente: relate a evidência, não chute.
- O emulador não subir ou ficar instável a ponto de impedir a medição.

## Notas de manutenção

- A prova final é o job `instrumentado` verde no GitHub depois do merge; o revisor confere.
- Se o CI e o local continuarem com telas diferentes, vale fixar o perfil do emulador nos dois.
