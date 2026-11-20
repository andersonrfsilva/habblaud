# Plano 144: Zaplaud Rust: a lista de tarefas do agente aparece na folha da sessão e o progresso acima do compositor

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: confirme que o plano 142 está na sua base:
> `grep -n "abandoned" shared/types.ts` e `grep -n "phase" server/test/android-fixture.ts` devolvem linhas. Se não,
> é condição de PARADA. Se o plano 143 (Kotlin) já estiver mesclado, os textos `tasks_*` já existem em
> `android/shared/src/commonMain/composeResources/values/strings.xml`: use-os; se não estiver, PARE e avise (os
> textos nascem no 143).

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: LOW-MED (só leitura; a janela não tem teste de tela além dos roteiros gráficos)
- **Depende de**: 142 (contrato e fixture) e 143 (textos e comportamento de referência)
- **Categoria**: feature
- **Planejado em**: commit `3547691`, 2026-11-19 (pedido do dono: "implementar o todo do OMP")

## Por que importa

Par do plano 143. O servidor publica a lista de tarefas de cada agente em `agents[].tasks` (omp `todo` com fases,
Claude Code `TodoWrite`, Codex `update_plan`); o desktop não a lê nem mostra. A regra do projeto é os dois clientes
Zaplaud andarem juntos (`docs/zaplaud-paridade.md`).

## Estado atual

Referências: `R` = `zaplaud-rs/crates`.

- **Contrato** (`shared/types.ts`, depois do 142): `agents[].tasks: TaskItem[]`, sempre presente.
  `TaskItem = { id, title, status: 'pending'|'in_progress'|'completed'|'abandoned', activeForm?, phase? }`.
- `R/zaplaud-core/src/model.rs`: `SessionItem::parse()` (~`:262-331`) não lê `tasks`; `Snapshot.raw` guarda o JSON.
- Folha de informações: `SessionInfoSheet` e `InfoRow` em `R/zaplaud-ui/ui/conversation_view.slint` (~`:15-23,578-606`),
  linhas montadas por `info_rows()` em `R/zaplaud-ui/src/conversation_view.rs` (~`:219-372`).
- Faixa acima do compositor: `ConversationStatus` (`R/zaplaud-ui/ui/conversation_view.slint` ~`:607-619`), entre
  `ConversationView` e `ComposerPane` em `R/zaplaud-ui/ui/app.slint` (~`:98-117`).
- Textos: gerados de `android/shared/src/commonMain/composeResources/values/strings.xml` por
  `R/zaplaud-ui/resource_gen.rs` (veja como outro texto recente, por exemplo `deliveries_title`, chega ao Slint).
- Comportamento de referência (plano 143, Kotlin): `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Tasks.kt`
  (regras `taskProgress`, `taskPhases`) e a seção "Tarefas" em `ReadingScreen.kt`.
- Roteiros gráficos do CI (`zaplaud-rs/scripts/*.py`) guiam a janela por coordenadas de pixel medidas na tela atual.

Confirme as linhas: os planos 125 a 141 mexeram nesses arquivos.

## Convenções do repositório (valem para este plano)

- Regras puras em `zaplaud-core/src/rules/`, com testes de unidade; a interface só apresenta.
- `cargo fmt --check`, `cargo clippy --locked --workspace --all-targets -- -D warnings`, testes com
  `ZAPLAUD_KEYRING=memory` e `npm ci` antes (testes de contrato sobem o servidor Node).
- A janela só roda em tela virtual: `xvfb-run -a -s '-screen 0 1280x1024x24'`; uma instância por vez do mesmo binário.
- Paridade e `CHANGELOG.md` atualizados.

## Escopo

**Dentro:** `R/zaplaud-core/src/model.rs`, um módulo de regras novo (`rules/tasks.rs`) com testes,
`R/zaplaud-ui/src/conversation_view.rs`, `R/zaplaud-ui/ui/conversation_view.slint` (e `app.slint` se a faixa
precisar), `zaplaud-rs/scripts/*.py` **só** para corrigir coordenadas que a linha nova deslocar,
`docs/zaplaud-paridade.md`, `CHANGELOG.md`.

**Fora:** servidor, página, Kotlin; editar tarefas; contador na lista de sessões; tarefas de subagentes; a rolagem e
a posição de leitura da conversa (planos 126 a 134) — a linha nova não pode mudar o cálculo de fim da lista.

## Passos

### Passo 1: modelo e regras (testes primeiro)

- `SessionItem` ganha `tasks: Vec<AgentTask>`; leitura tolerante (não é lista → vazio; item sem `id`/`title` →
  descartado; `status` desconhecido → descartado; nunca entra em pânico).
- `rules/tasks.rs`: `task_progress` (feitas, total sem abandonadas, tarefa em andamento com `activeForm` de
  preferência; `None` com total 0) e `task_phases` (grupos na ordem recebida). Mesmos casos de teste do Kotlin.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -j 6` passa com os testes novos.

### Passo 2: folha de informações

Seção "Tarefas" em `SessionInfoSheet` quando há tarefas: "{feitas} de {total}", barra, fases com subtítulo, tarefas
com marcador (○ pendente, ◐ em andamento com destaque, ✓ concluída atenuada, abandonada riscada e atenuada) e nome
acessível com o estado por extenso. A folha já rola. Sem tarefas, sem seção.

**Verificação**: `cargo check --locked -p zaplaud-ui -j 6` e clippy do workspace saem com 0.

### Passo 3: progresso acima do compositor

Uma linha junto de `ConversationStatus`: "{feitas}/{total} · {tarefa em andamento}", com reticências, clicável
(abre a folha), visível também com o agente parado, ausente quando não há progresso ou tudo está concluído. A
altura da área da conversa muda quando a linha aparece ou some: confira que a lista continua terminando na última
mensagem (o roteiro de conversa verifica isso).

**Verificação**: build debug; os três roteiros gráficos passam nos dois temas sob `xvfb-run`
(`conversation-smoke`, `keyboard-smoke`, `accessibility-smoke`, como no passo do workflow
`.github/workflows/zaplaud-rs.yml`). A fixture agora tem tarefas na sessão omp: se a linha nova deslocar algum
alvo dos roteiros, corrija a coordenada medindo na captura (não afrouxe verificação). Rode a matriz também com a
fonte DejaVu (`FONTCONFIG_FILE` temporário; veja `~/dados/habblaud/plano-127b/fonts-127b.conf`), que é a do runner.

### Passo 4: verificação da tela nova, paridade e notas

- No `accessibility-smoke.py` (ou no roteiro mais adequado), acrescente uma verificação de que a sessão omp da
  fixture expõe a linha de progresso com "1/4" e que a folha lista as tarefas (pela árvore de acessibilidade, que o
  roteiro já usa), sem depender de pixel.
- `docs/zaplaud-paridade.md`: a linha "Lista de tarefas do agente" passa a "sim" nos dois.
- `CHANGELOG.md`: "Zaplaud desktop: a lista de tarefas do agente aparece nas informações da sessão, com o progresso
  acima do campo de mensagem."

**Verificação**: `cargo fmt --check`; clippy do workspace; `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`;
matriz dos roteiros (2 temas × 3) uma vez por fonte; `python3 -m py_compile zaplaud-rs/scripts/*.py`.

## Plano de testes

Unidade das regras e da leitura; verificação por acessibilidade no roteiro.

## Critérios de pronto

- [ ] Regras e leitura testadas, iguais às do Kotlin
- [ ] Folha e linha de progresso implementadas; sessão sem tarefas sem mudança
- [ ] Matriz dos roteiros verde nas duas fontes; nenhuma verificação afrouxada
- [ ] fmt, clippy e testes ok; paridade e CHANGELOG atualizados

## Condições de PARADA

- A linha nova alterar o comportamento de rolagem/fim da conversa que os roteiros verificam e a causa não for
  coordenada.
- Os textos `tasks_*` não existirem (143 não mesclado).
- Precisar mudar servidor, fixture ou Kotlin.

## Notas de manutenção

- A prova final é o workflow `Zaplaud Rust` verde no GitHub; depois, reinstalar o desktop no Mac e no miranote.
- Ninguém vê a janela na execução: o dono confere a aparência.

## Comandos de verificação

| O quê | Comando | Esperado |
|---|---|---|
| Testes | `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` | todos passam |
| Lint | `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings` | sai com 0 |
