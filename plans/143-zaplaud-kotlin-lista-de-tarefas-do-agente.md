# Plano 143: Zaplaud Kotlin: a lista de tarefas do agente aparece na folha da sessão e o progresso acima do compositor

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: confirme que o plano 142 está na sua base:
> `grep -n "abandoned" shared/types.ts` e `grep -n "phase" server/test/android-fixture.ts` devolvem linhas. Se não,
> é condição de PARADA (este plano depende do contrato e da fixture do 142).

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: LOW-MED (só leitura; telas existentes ganham uma seção e uma linha)
- **Depende de**: 142 (contrato `tasks` com `phase` e `abandoned`; fixture com tarefas)
- **Categoria**: feature
- **Planejado em**: commit `3547691`, 2026-11-19 (pedido do dono: "implementar o todo do OMP")

## Por que importa

O dono acompanha os agentes pelo Zaplaud. Os agentes mantêm uma lista de tarefas (omp: ferramenta `todo`, com
fases; Claude Code: `TodoWrite`; Codex: `update_plan`), e o servidor a publica no snapshot em `agents[].tasks`. O
Zaplaud não lê esse campo: o dono não vê o que o agente planejou, o que está fazendo agora nem quanto falta.

## Estado atual

Referências: `K` = `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud`.

- **Contrato** (`shared/types.ts`, depois do plano 142): `agents[].tasks: TaskItem[]`, sempre presente (pode ser
  `[]`). `TaskItem = { id: string, title: string, status: 'pending'|'in_progress'|'completed'|'abandoned', activeForm?: string, phase?: string }`.
  A ordem é a do agente. Com fases, tarefas da mesma fase vêm juntas.
- `K/data/SessionRepository.kt` `item()` (~`:104-123`) monta `SessionItem` a partir do agente e não lê `tasks`;
  `SessionItem` está em `K/data/ApiModels.kt` (~`:37-50`). Subagentes vêm de `activeSubagents()`.
- Folha de informações da sessão: `SessionInfoSheet`, com `InfoSection`/`InfoLine` (`K/ReadingScreen.kt` ~`:1209-1283`);
  abre pelo cabeçalho da conversa (`conversationTopBar`, ~`:333-385`). A seção "Contexto" já tem uma barra de progresso.
- Faixa acima do compositor: `StatusLine` (`K/ReadingScreen.kt` ~`:882-909`), mostrada quando o agente trabalha
  (`:509`, `:659-678`).
- Textos: `android/shared/src/commonMain/composeResources/values/strings.xml` (único idioma; o Rust gera os dele a
  partir deste arquivo).
- Fixture dos testes (plano 142): o agente omp principal emite duas fases e os quatro estados.

Confirme os números de linha: vários planos mexeram em `ReadingScreen.kt` nesta semana.

## Convenções do repositório (valem para este plano)

- Kotlin Multiplatform; a lógica nova fica em função pura testável em `androidHostTest` (padrão dos testes de
  `Unread.kt`, `NotificationRules.kt`); `HabblaudApi` não pode ser construído em teste de host.
- Paridade Kotlin/Rust obrigatória: registre em `docs/zaplaud-paridade.md` (o Rust é o plano 144; marque "pendente").
- Textos só em `strings.xml`; uma linha em `CHANGELOG.md` ("Não lançado").
- Nada pode lançar exceção em escopo de corrotina sem tratador; JSON inesperado nunca derruba o snapshot.
- Testes instrumentados usam `androidx.compose.ui.test.junit4.v2.createEmptyComposeRule` e não bloqueiam a thread do
  teste esperando o app (`android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureWait.kt`).

## Escopo

**Dentro:** `K/data/ApiModels.kt`, `K/data/SessionRepository.kt`, um arquivo novo pequeno para a regra de progresso
(por exemplo `K/data/Tasks.kt`), `K/ReadingScreen.kt` (folha e faixa), `strings.xml`, testes de host e um teste
instrumentado, `docs/zaplaud-paridade.md`, `CHANGELOG.md`.

**Fora:** servidor, página, Rust (`zaplaud-rs/**`); editar tarefas; contador na lista de sessões; notificações;
tarefas de subagentes (só a sessão principal selecionada).

## Passos

### Passo 1: modelo e regras (testes primeiro)

- `SessionItem` ganha `tasks: List<AgentTask> = emptyList()`, com `AgentTask(id, title, status, activeForm, phase)`.
  `item()` lê `tasks` de forma tolerante: não é lista → vazio; item sem `id` ou `title` → descartado; `status`
  desconhecido → descartado; nunca lança.
- Regras puras em arquivo próprio:
  - `taskProgress(tasks)`: `done` = concluídas, `total` = todas menos abandonadas, `current` = a primeira
    `in_progress` (texto: `activeForm` se houver, senão `title`). `null` quando `total == 0`.
  - `taskPhases(tasks)`: grupos na ordem recebida; nome `null` para tarefas sem fase.
- Testes de host: leitura tolerante (cada caso acima), progresso (abandonadas fora do total; sem tarefa em
  andamento; lista vazia), agrupamento (ordem preservada; sem fases → um grupo sem nome).

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --max-workers=3`
— os testes novos passam.

### Passo 2: folha de informações

Em `SessionInfoSheet`, uma seção "Tarefas" (`tasks_title`) quando a sessão selecionada tem tarefas: cabeçalho com
"{feitas} de {total}" e a barra no padrão da seção "Contexto"; depois as fases (subtítulo com o nome, quando houver)
e as tarefas, cada uma com marcador e texto: pendente ○, em andamento ◐ (destaque; usa `activeForm` se houver),
concluída ✓ (atenuada), abandonada riscada e atenuada. Cada linha tem descrição de acessibilidade com o estado por
extenso (`tasks_state_pending`, `..._in_progress`, `..._completed`, `..._abandoned`). A seção rola com a folha;
sem tarefas, a seção não existe.

**Verificação**: compila (`:app:assembleDebug`); `testTag`s `tasks-section` e `task:<id>` presentes para o teste.

### Passo 3: progresso acima do compositor

Uma linha curta junto da faixa de estado, quando há progresso (`taskProgress != null`): "{feitas}/{total} · {tarefa
em andamento}", uma linha, com reticências; tocar abre a folha de informações. **Aparece também com o agente
parado** (a faixa atual some quando ele para; a linha de tarefas não deve depender dessa condição) e some quando
todas estão concluídas ou a lista está vazia. Não empurre o compositor para fora da tela em 320×640 dp com o teclado
aberto: uma linha só.

**Verificação**: compila; `testTag` `tasks-progress`.

### Passo 4: testes de tela, paridade e notas

- Teste instrumentado (em `FixtureUiInstrumentedTest` ou classe nova no mesmo padrão): abrir a sessão omp da
  fixture; a linha `tasks-progress` mostra "1/4" e o texto da tarefa em andamento (a fixture do 142 tem 1 concluída,
  1 abandonada, 1 em andamento, 2 pendentes → total 4); tocar abre a folha com as duas fases e as cinco tarefas;
  uma sessão sem tarefas não mostra a linha.
- `docs/zaplaud-paridade.md`: linha "Lista de tarefas do agente" (Kotlin: sim; Rust: pendente, plano 144).
- `CHANGELOG.md`: "Zaplaud (Android/iPad): a lista de tarefas do agente aparece nas informações da sessão, com o
  progresso acima do campo de mensagem."

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:assembleDebug :app:lintDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin --max-workers=3`
→ `BUILD SUCCESSFUL`; se o emulador foi concedido, o teste novo passa 3 vezes e a suíte completa
(`python3 scripts/android-instrumented-ci.py`) passa uma vez.

## Plano de testes

Testes de host das regras e da leitura; um teste instrumentado da folha e da linha de progresso.

## Critérios de pronto

- [ ] `tasks` lido de forma tolerante; regras testadas
- [ ] Folha mostra fases, estados e progresso; linha de progresso acima do compositor, inclusive com o agente parado
- [ ] Sessão sem tarefas: nada novo na tela
- [ ] Build, lint e testes de host ok; instrumentado ok (ou só compilado, se sem emulador, dito no relatório)
- [ ] Paridade e CHANGELOG atualizados

## Condições de PARADA

- A linha de progresso não couber em 320×640 dp sem esconder o compositor ou a última mensagem.
- Precisar mudar o servidor ou a fixture.
- A fixture do 142 não emitir a lista esperada.

## Notas de manutenção

- O código é compartilhado com o iPad (`commonMain`): só o CI compila o iOS.
- Tarefas de subagentes e contador na lista de sessões ficaram de fora por decisão.

## Comandos de verificação

| O quê | Comando | Esperado |
|---|---|---|
| Build e host | o comando do passo 4 | `BUILD SUCCESSFUL` |
