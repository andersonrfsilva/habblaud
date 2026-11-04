# Plano 036: Executar testes instrumentados Android na CI

> Execute na raiz do repositório. Confira cada resultado antes de avançar. Não publique nem faça commit sem autorização. Se uma condição STOP ocorrer, interrompa e relate. Atualize a linha deste plano em `plans/README.md` quando concluído, salvo orientação diferente do responsável pelo índice.
>
> **Deriva:** `git diff --stat 9469c0b..HEAD -- .github/workflows/android.yml android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt scripts/android-smoke.ts server/test/android-fixture.ts README.pt-BR.md plans/README.md` e `git status --short -- .github/workflows/android.yml android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt scripts/android-smoke.ts server/test/android-fixture.ts README.pt-BR.md plans/README.md`. Compare mudanças com os trechos abaixo; preserve alterações preexistentes e pare se afetarem premissas.

## Status

- **Prioridade:** P1; **esforço:** M; **risco:** médio; **categoria:** testes/CI.
- **Dependências:** nenhuma. Planejado em `9469c0b`, 2026-11-03.

## Problema e estado atual

A CI Android compila e roda testes locais, mas não executa instrumentação em dispositivo. Assim, regressões de integração da interface com a fixture não são exercitadas nessa CI. Não se afirma que os testes atuais falhem ou passem: não foram executados nesta análise.

- `.github/workflows/android.yml:16-33`: job Ubuntu, Java 17, SDK 36, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` e testes unitários release filtrados; usa actions fixadas por SHA e `permissions: contents: read`.
- `android/app/build.gradle.kts:17,64-69`: runner `androidx.test.runner.AndroidJUnitRunner`, dependências de teste Compose e runner Android.
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt:44-65`: origin fixa `http://127.0.0.1:14748`; `@Before` exige argumento `fixture=true` e autentica na fixture. A ausência da fixture falha, não pula o teste.
- `scripts/android-smoke.ts:5-22` cria diretório temporário fora do HOME real e troca `HOME` e diretório de anexos antes de importar o servidor. `server/test/android-fixture.ts:42-52,239-242,284-300,375-393` escuta a porta remota solicitada em loopback, escolhe dinamicamente a porta local, encerra ao fechar stdin ou receber sinal e emite JSON `ready`, `url` e `localUrl`.
- `README.pt-BR.md:286-316` documenta `npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages`, espera pelo JSON, dois `adb reverse`, e `:app:connectedDebugAndroidTest` com argumentos `fixture=true` e `fixtureLocalPort=PORTA_LOCAL`. As duas portas são exclusivas de laboratório.
- `.github/workflows/ci.yml:26-31` usa `actions/setup-node` fixado por SHA, Node `22.12` e `npm ci`. Reutilize essa convenção.

## Escopo

Modificar somente `.github/workflows/android.yml`, `README.pt-BR.md` e, ao concluir, `plans/README.md`. Criar script de orquestração em `scripts/android-instrumented-ci.*` somente se necessário para preservar stdin, limpeza e verificação. Não mudar a fixture, testes, código Android, configurações de produção, credenciais ou demais workflows. Não usar servidor real nem segredos de assinatura.

## Passos e verificações

1. Acrescente job independente no workflow Android, com timeout explícito, checkout, Java 17, Node 22.12, `npm ci`, SDK e emulador Android x86_64 com aceleração KVM em runner Ubuntu compatível. Use uma action de emulador mantida, fixada por SHA completo verificado no repositório oficial; configure boot e espera limitados, sem `continue-on-error`. Reutilize SHAs existentes quando aplicável. Se KVM não estiver disponível no runner escolhido, STOP; não aceite execução falsa nem desabilite testes.
   **Verificação:** `git diff -- .github/workflows/android.yml` deve mostrar job adicional, sem remover verificações existentes, com ações fixadas e sem segredos.
2. No comando executado com emulador ligado, inicie `npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages` como processo filho com stdin aberto durante toda a execução. Capture stdout sem imprimi-lo integralmente: o JSON contém credenciais fictícias e caminhos temporários. Aguarde com prazo finito uma linha JSON válida com `ready === true`; valide `url` como `http://127.0.0.1:14748` e extraia de `localUrl` somente porta numérica de `http://127.0.0.1:<porta>` entre 1 e 65535. Recuse URL externa, ausência de JSON, processo encerrado e porta inválida. Não escolha a porta local antecipadamente: a fixture a escolhe.
   **Verificação:** inspecione `git diff -- .github/workflows/android.yml` e, caso tenha criado script ainda não rastreado, leia seu conteúdo diretamente; `git diff` não mostra arquivos não rastreados. Confirme espera limitada, validação de loopback e retenção de stdin. Uma execução posterior deve registrar apenas prontidão e porta, sem JSON ou credenciais.
3. Rode `adb reverse tcp:14748 tcp:14748` e `adb reverse tcp:$PORTA_LOCAL tcp:$PORTA_LOCAL`, com a porta obtida no passo anterior; execute de `android/`:

   ```bash
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.fixture=true \
     -Pandroid.testInstrumentationRunnerArguments.fixtureLocalPort="$PORTA_LOCAL"
   ```

   Preserve o código de saída do Gradle. Em limpeza garantida para sucesso, erro e cancelamento normal, remova os dois forwards com `adb reverse --remove`, feche stdin/termine a fixture e aguarde o filho; não use `adb reverse --remove-all` em aparelho compartilhado. Mantenha o job restrito a fixture local, sem segredos, release signing ou destino de produção. Em falha, não imprima logcat, respostas HTTP, JSON de prontidão nem diretório HOME; relatórios só podem ser publicados após revisão de conteúdo e necessidade.
   **Verificação:** `adb reverse --list` durante a execução deve mostrar os dois mapeamentos; depois da limpeza, não deve mostrar esses dois mapeamentos. Gradle deve sair 0 e gerar XML de resultados em `android/app/build/outputs/androidTest-results/connected/`.
4. Atualize `README.pt-BR.md` na seção de testes para descrever job, requisitos e porta dinâmica, mantendo procedimento local. Explique que a fixture é exclusiva de laboratório e que não se usa produção.
   **Verificação:** `git diff -- README.pt-BR.md` deve mostrar essas instruções sem credenciais nem mudanças fora da seção.

## Plano de teste e critérios de conclusão

- Em emulador isolado com Node 22.12, Java 17, SDK e KVM, execute o mesmo script/comando do job. Exija saída 0, resultados XML com número de testes maior que zero, nenhum erro/falha e presença de `FixtureUiInstrumentedTest` nos XML. Confira também as demais classes instrumentadas: não filtre a suíte para simular sucesso.
- Inspecione XML com parser, não apenas exit code: some `tests`, `failures` e `errors` dos elementos `testsuite`; exija `tests > 0`, `failures == 0`, `errors == 0` e ao menos um `testcase` com `classname` contendo `FixtureUiInstrumentedTest`. Se o formato AGP diferir, STOP e relate antes de mudar o critério.
- Em execução isolada negativa, interrompa a fixture antes do Gradle: a orquestração deve falhar com saída não zero em tempo limitado, sem usar outra origem; verifique limpeza dos reverses e processo filho. Não rode contra servidor real.
- A validação definitiva é um run GitHub Actions do workflow alterado em push/PR: job instrumentado verde, XML com os critérios acima e jobs existentes verdes. Execução local não prova disponibilidade de KVM nem sucesso no GitHub. Se não puder disparar ou observar run, reporte explicitamente como pendente; não declare CI validada.
- `git status --short` mostra somente arquivos permitidos, além de alterações preexistentes preservadas. `plans/README.md` atualizado quando autorizado.

## STOP e riscos

Pare se os trechos divergirem, se a porta 14748 estiver ocupada, se o emulador/KVM não iniciar, se `localUrl` não for loopback, se a fixture fechar por EOF, se a suíte falhar ou executar zero testes, ou se precisar de credenciais reais. Não remova validações para tornar o job verde. Não exponha JSON, segredos, dados pessoais ou logs indiscriminadamente. Em cancelamento forçado do runner a limpeza pode não executar; use runner efêmero e relate essa limitação. Revise periodicamente SHA da action e imagem de sistema; atualizações podem alterar desempenho ou comportamento do emulador.

## Execução bloqueada em 2026-11-03

- Trabalho não commitado: `/tmp/habblaud-plan036`. Não foi mesclado.
- Suíte real no emulador: 13 testes; sete falharam inicialmente por ausência de hierarquia Compose. Após abrir a atividade manualmente, esse sintoma desapareceu sem mudança permanente, mas cinco testes ainda falharam: dois timeouts, dois elementos não exibidos e uma decisão pendente.
- Esses cinco cenários foram investigados na rodada seguinte; resultados e pendências estão abaixo.

## Correções locais em 2026-11-03

- A permissão de notificação era solicitada sobre a activity durante testes; a instrumentação concede a permissão antes de abrir a tela.
- O teste acompanha o contador no rótulo de pensamento e abre o submenu de visualização. No fluxo de recusa, fecha o teclado e garante que os botões estejam visíveis antes do clique.
- Três execuções completas posteriores passaram, incluindo revisão independente: 13 testes, zero falhas e zero erros, no emulador API 36 com largura de 320 dp. Node local 26 e Java local 21 diferem de Node 22.12 e Java 17 configurados no CI.
- O cenário negativo confirmou erro quando a fixture é interrompida e remoção dos dois encaminhamentos. Uma tentativa anterior expirou por causa não identificada; processos e encaminhamentos foram removidos.
- A worktree `/tmp/habblaud-plan036` permanece sem commit e sem merge. A validação do GitHub Actions continua pendente.
