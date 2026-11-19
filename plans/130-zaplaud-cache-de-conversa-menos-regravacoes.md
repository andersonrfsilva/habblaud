# Plano 130: Zaplaud Kotlin: a conversa aberta deixa de regravar o arquivo de cache a cada evento

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 130 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: MED (persistência da conversa e da posição de leitura)
- **Depende de**: 129 (mesmo arquivo; executar depois)
- **Categoria**: perf
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Medição do passo 4 do plano 106 no emulador (2026-11-18), com uma conversa ativa: **28 gravações do arquivo de
cache por minuto**, cada uma reescrevendo a conversa inteira (mediana 26 ms de escrita; leitura contida 63 ms).
Pela regra daquele plano, mais de uma gravação a cada poucos segundos pede plano próprio: é desgaste de
armazenamento e bateria num telefone que fica com a conversa aberta, e cresce com o tamanho da conversa (o teto é
64 MiB por servidor).

## Estado atual

`ReadingController.kt`: `persist(delayMs)` (`:263-267` na base) cancela o `saveJob` anterior e grava na hora
quando `delayMs == 0`, ou depois do atraso; é chamado por expansão de resumo, mudança de posição de leitura,
chegada de entradas e resumos. Levante todos os chamadores e o atraso que cada um passa
(`grep -n 'persist(' ReadingController.kt`). A gravação em si está em `LocalStateStore` (`conversationWrite`,
arquivo `conversation-*.json` reescrito por inteiro, com trava e contagem para o teto a cada 20 gravações).

Dados que não podem ser perdidos: ao fechar a conversa, trocar de conversa, ir para segundo plano ou encerrar a
tela, o que está em memória tem de ser gravado na hora (hoje `close()` chama `persist()` sem atraso).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-130 -b advisor/130-zaplaud-cache-de-conversa-menos-regravacoes producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-130`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Compare com o Rust (`zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs`: agenda a gravação 1 s ou 3 s depois do evento e junta as pendentes). O objetivo é o Kotlin ficar com política equivalente; registre em `docs/zaplaud-paridade.md` se a tabela tiver linha de cache.
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
- os dois arquivos acima
 - testes de host correspondentes
 - `CHANGELOG.md` (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Formato do arquivo, gravação incremental/append, teto de 64 MiB, iOS além do que já compartilha em `commonMain`.

## Passos

### Passo 1: medir a base de forma repetível

Sem emulador: teste de host que conte chamadas de gravação do cache (substitua o armazenamento por um falso que
conta) num roteiro determinístico: abrir conversa, receber 60 entradas em 60 s de tempo virtual
(`kotlinx-coroutines-test`), mudar a posição de leitura 20 vezes, expandir 3 resumos. Se o controlador não puder
ser montado em host, meça a função de agendamento isolada e diga em NOTES.

**Verificação**: o teste imprime o número de gravações da base (espera-se dezenas).

### Passo 2: juntar gravações

Política: toda mudança marca "sujo" e agenda **uma** gravação para daqui a N ms se não houver uma agendada
(não reinicia o prazo a cada evento, senão um fluxo contínuo nunca grava); N entre 2 000 e 5 000 ms, o mesmo para
todos os gatilhos não críticos. Gravação imediata e garantida em: fechar/trocar conversa, `stop()`/descartar a
tela, app indo para segundo plano (veja o que já é chamado no ciclo de vida) e antes de ler o mesmo arquivo de
volta (`writeJob?.join()` já existe na abertura). Nada pode ser perdido se o processo morrer depois do prazo N.

**Verificação**: no roteiro do passo 1 as gravações caem para no máximo 1 a cada N ms mais as imediatas (mostre o
número antes e depois); testes novos provam: (a) fluxo contínuo de eventos por 30 s grava pelo menos a cada N ms;
(b) fechar grava na hora o último estado; (c) reabrir logo depois de fechar lê o estado novo.

### Passo 3: regressão

Comando de build e testes de host do repositório (o mesmo do plano 129).

**Verificação**: `BUILD SUCCESSFUL`, 0 falhas.

## Plano de testes

Os três testes do passo 2 e a contagem do passo 1.

## Critérios de pronto

Todos precisam valer:

- [ ] Número de gravações antes e depois no mesmo roteiro
 - [ ] Testes (a), (b), (c) passam
 - [ ] Build e host ok; CHANGELOG com uma linha
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 130 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Garantir a gravação no encerramento exigir mexer no ciclo de vida do app Android/iOS além de chamar o que já existe.
 - A política mudar o que o usuário vê ao reabrir uma conversa.

## Notas de manutenção

- O revisor mede de novo no emulador (gravações por minuto com conversa ativa) e roda a suíte instrumentada.
