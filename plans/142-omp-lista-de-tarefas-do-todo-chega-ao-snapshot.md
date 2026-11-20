# Plano 142: omp: a lista de tarefas da ferramenta `todo` chega ao snapshot, à conversa e à página

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3547691..HEAD -- server/sources/omp shared/types.ts server/test/android-fixture.ts client/src/ui/drawer.ts`
> Se algum arquivo do escopo mudou desde `3547691`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (leitor de transcript de todas as sessões omp; contrato do snapshot lido por três clientes)
- **Depende de**: nenhum
- **Categoria**: feature
- **Planejado em**: commit `3547691`, 2026-11-19 (pedido do dono: "implementar o todo do OMP")

## Por que importa

Os agentes do omp mantêm uma lista de tarefas com a ferramenta `todo` (fases, tarefa em andamento, concluídas).
O dono acompanha os agentes pelo Zaplaud e pela página, e hoje não vê essa lista: o servidor mostra só "Usando todo"
e, na conversa, o JSON cru da chamada. Para sessões do Claude Code a lista já existe (`TodoWrite`). Este plano faz o
servidor entender o `todo` do omp; os planos 143 (Kotlin) e 144 (Rust) mostram a lista nos clientes Zaplaud.

## Estado atual

**O que o omp grava** (omp 18.22.2; confirme na implementação: `oss/oh-my-pi/packages/coding-agent/src/tools/todo.ts`
no checkout local, se existir, ou na tag `v18.22.2` de `can1357/oh-my-pi`; e nas formas de um transcript real em
`~/.omp/agent/sessions/**.jsonl` — só as formas, nunca copie textos de tarefas do dono para o repositório):

- Chamada: `message.role="assistant"`, bloco `{type:"toolCall", id, name:"todo", arguments}`. `arguments.op` é um de
  `init`, `start`, `done`, `rm`, `drop`, `append`, `view`. `init` leva `list:[{phase, items:string[]}]` ou
  `items:string[]`; `start`/`done`/`rm`/`drop` levam `task` (texto, ou lista de textos) e/ou `phase`.
- Resultado: `message` com `{role:"toolResult", toolCallId, toolName:"todo", isError, content, details}`.
  `details = {op, storage, phases:[{name, tasks:[{content, status}]}], completedTasks?}`. **`details.phases` é a
  lista inteira depois da operação**, em toda operação (conferido em 6 resultados reais: `init`, `start`, `done`).
  `status` é `pending`, `in_progress`, `completed` ou `abandoned` (`drop` marca `abandoned`; `rm` remove).
  Tarefas não têm id: são identificadas pelo texto. A fase padrão chama-se `Tasks`.
- O usuário também pode editar a lista no omp; isso grava uma entrada `type:"custom"` (`user_todo_edit`). Há ainda
  `custom` do tipo `todo_hud_state` (visto num transcript; conteúdo não verificado). Veja como o próprio omp
  reidrata a lista (`session/todo-tracker.ts`: último resultado válido de mutação ou `user_todo_edit` no ramo ativo).

**O que o servidor faz hoje:**

- `server/sources/omp/parser.ts`: `createOmpState()` nasce com `tasks: []` (`:304-311`) e nada o altera; `todo` cai
  no `default` do mapeamento de ferramentas (`:199-204`) e vira a atividade "Usando todo". O resultado
  (`toolResult`) só retira a chamada das pendências (`:539-589`). `applySummary()` em `server/sources/omp/source.ts`
  (`:690-719`) já publica `s.tasks` no Office.
- `server/sources/omp/terminal.ts` (`:65-105,184-220`): a chamada vira entrada `tool` genérica (título com o primeiro
  argumento textual, o resto em JSON) e o resultado vira `result` com o texto de `content`.
- Contrato (`shared/types.ts:174-183`): `TaskItem { id: string; title: string; status: TaskStatus; activeForm?: string }`,
  `TaskStatus = 'pending' | 'in_progress' | 'completed'`. Não há fase nem `abandoned`.
- Referência de como o Claude Code é tratado: `TodoWrite` substitui a lista (`server/sources/transcript.ts:480-484`,
  `todosToTasks`) e vira texto ☒/◐/☐ com título `TodoWrite(d/t concluídas)` (`server/sources/terminal.ts:323-326`,
  `todoList`). Títulos cortados em 120 caracteres.
- Página: `client/src/ui/drawer.ts:108-124,429-437` mostra a seção "Tarefas" (contador e barra) sem agrupar;
  `client/src/ui/model.ts:274-291` agrega por sala.
- Servidor de teste dos clientes: `server/test/android-fixture.ts:65-159` emite `tasks: []` para todos os agentes.

## Convenções do repositório (valem para este plano)

- TypeScript; testes com Vitest ao lado do código (`*.test.ts`); `npm ci` antes; `npm test` roda tudo.
- Textos visíveis passam por `tr(...)` com chave em português; `npm run i18n:check -- --strict` valida as chaves.
- Mudança visível ao usuário: uma linha em `CHANGELOG.md`, seção "Não lançado".
- Commits em português, um por passo lógico (`feat(omp): ...`, `test(omp): ...`).

## Escopo

**Dentro:**

- `shared/types.ts` (contrato), `shared/activity.ts` (se o texto da atividade do `todo` mudar)
- `server/sources/omp/parser.ts`, `server/sources/omp/terminal.ts` e seus testes
- `client/src/ui/drawer.ts`, `client/src/ui/model.ts`, `client/src/ui/styles.css` e testes (agrupar por fase; estado abandonada)
- `server/test/android-fixture.ts` (emitir tarefas num agente da fixture)
- `shared/locales/**` (chaves novas), `CHANGELOG.md`, `server/README.md` se descrever `tasks`

**Fora:**

- Clientes Zaplaud (`android/**`, `zaplaud-rs/**`): planos 143 e 144.
- A extensão `mod/habblaud-omp` e o contrato `/api/omp/events`: a lista vem do transcript, que já é lido.
- Editar tarefas pelo Habblaud (só leitura). Tarefas de subagentes do omp além do que o leitor já separa por sessão.
- Mudar o tratamento de Claude Code, Codex e ZeroClaw.

## Passos

### Passo 1: contrato

Em `shared/types.ts`: `TaskStatus` ganha `'abandoned'`; `TaskItem` ganha `phase?: string` (nome da fase; ausente
quando o runtime não tem fases). Levante todos os consumidores de `TaskStatus`/`TaskItem`
(`grep -rn 'TaskStatus\|TaskItem\|in_progress' shared server client --include=*.ts`) e trate `abandoned` onde houver
`switch`/mapa exaustivo: tarefa abandonada **não** conta como concluída nem como pendente no progresso
(o total exibido é o de tarefas não abandonadas).

**Verificação**: `npx tsc --noEmit -p .` (ou o comando de tipos que `package.json` define) sai com 0.

### Passo 2: leitor do omp preenche `tasks` (testes primeiro)

Em `server/sources/omp/parser.ts`, ao processar um `toolResult` com `toolName === 'todo'`:

- **Reconheça o resultado pelo próprio `toolName`**, sem depender da chamada pendente em memória: ao reler um
  transcript (reinício do servidor, janela final do arquivo) o resultado pode aparecer sem a chamada.
- `isError` verdadeiro → **mantém** a lista atual.
- `details.phases` ausente ou malformado (não é lista; fase sem `tasks` em lista) → **mantém** a lista atual.
- `details.phases` é lista válida, **inclusive vazia** → **substitui** a lista (vazia = o agente limpou as tarefas).
- Conversão: para cada fase, para cada tarefa com `content` texto não vazio e `status` conhecido →
  `{ id, title: truncate(content, 120), status, phase }`. `phase` é omitido quando há uma única fase com o nome
  padrão `Tasks`. `id` precisa ser estável entre resultados e único na lista: derive de fase + texto (e um sufixo de
  ordem para textos repetidos na mesma fase); não use índice global puro, senão remover uma tarefa renomeia as outras.
  Status desconhecido → descarta a tarefa (não invente estado). Mantenha a ordem do omp.
- Aplique o mesmo às entradas `custom` de edição pelo usuário (`user_todo_edit`), **se** a implementação do omp
  confirmar que carregam a lista completa no mesmo formato; se o formato for outro, trate-o conforme a
  implementação; se não der para confirmar, deixe de fora e diga em NOTES. `todo_hud_state`: leia a implementação e
  diga em NOTES o que é; só use se for a lista.
- `op: 'view'` não muda nada no omp; tratar seu resultado como os outros é inofensivo (mesma lista).
- Limite defensivo: no máximo 200 tarefas publicadas (corte no fim); registre em NOTES.

Testes em `server/sources/omp/parser.test.ts` (siga os vizinhos; linhas JSONL sintéticas, sem texto real do dono):
`init` com duas fases; `start`/`done` mudando estados; `drop` → `abandoned`; `rm` removendo; `phases: []` limpa;
`isError` mantém; `phases` ausente mantém; `phases` malformado mantém; **replay sem a chamada** (`toolResult`
de `todo` como primeira linha) preenche; textos repetidos têm ids distintos e estáveis; título cortado em 120;
uma fase `Tasks` única não gera `phase`. E um teste em `server/sources/omp/source.test.ts` de que a lista chega ao
Office/snapshot.

**Verificação**: `npx vitest run server/sources/omp` — os testes novos falham antes da implementação e passam depois.

### Passo 3: cartão legível na conversa

Em `server/sources/omp/terminal.ts`: a entrada `tool` do `todo` ganha título legível (por exemplo
`todo(done)`/`todo(init)` com a contagem quando conhecida) e a entrada `result` do `todo` mostra a lista como texto
com os mesmos marcadores do `TodoWrite` (☒ concluída, ◐ em andamento, ☐ pendente; escolha um marcador distinto
para abandonada, por exemplo ⊘), agrupada por fase, montada a partir de `details.phases` quando válido; senão, o
`content` como hoje. Reaproveite `todoList`/os marcadores de `server/sources/terminal.ts` se der sem mudar o
comportamento do Claude; não duplique a tabela de marcadores.

**Verificação**: testes em `server/sources/omp/terminal.test.ts` (ou o arquivo de testes do terminal do omp) para
`init` e `done`; `npx vitest run server/sources` passa.

### Passo 4: página

`client/src/ui/drawer.ts`: quando alguma tarefa tem `phase`, a seção "Tarefas" mostra um subtítulo por fase, na
ordem recebida; tarefa `abandoned` aparece riscada e atenuada, com marcador próprio, e fica fora do contador e da
barra. Sem fases, nada muda. Ajuste `client/src/ui/model.ts` (agregação por sala) para ignorar abandonadas no
progresso. Estilos em `client/src/ui/styles.css`, seguindo as classes vizinhas.

**Verificação**: testes de `client/src/ui/model.test.ts` atualizados/novos passam; `npm run build` (ou o comando de
build do cliente em `package.json`) sai com 0.

### Passo 5: servidor de teste dos clientes

`server/test/android-fixture.ts`: o agente omp principal da fixture (o que os testes dos clientes já abrem) passa a
ter uma lista fixa com duas fases e os quatro estados (por exemplo: fase "Preparação" com uma concluída e uma
abandonada; fase "Execução" com uma em andamento e duas pendentes), em textos fictícios. Os demais agentes continuam
com `tasks: []`. Confira que nenhum teste existente dos clientes depende de `tasks` vazio
(`grep -rn 'tasks' android/app/src/androidTest android/shared/src zaplaud-rs/scripts zaplaud-rs/crates --include=*.kt --include=*.rs --include=*.py | head`).

**Verificação**: `npm test` completo passa; `curl` na fixture em execução (veja `scripts/android-smoke.ts` para subir)
mostra as tarefas no snapshot — ou um teste do próprio arquivo, se houver.

### Passo 6: documentação

`CHANGELOG.md` ("Não lançado"): "omp: a lista de tarefas do agente (ferramenta `todo`, com fases) aparece na página
e no cartão da conversa." `server/README.md`: se descrever `tasks`, acrescente `phase` e `abandoned`.

**Verificação**: `npm run i18n:check -- --strict` e `npm test` saem com 0.

## Plano de testes

Os do passo 2 (regras de substituir/manter, replay, ids), do passo 3 (texto do cartão) e do passo 4 (progresso sem
abandonadas).

## Critérios de pronto

- [ ] Sessão omp com `todo` tem `tasks` preenchido no snapshot, com `phase` e os quatro estados
- [ ] `phases: []` limpa; erro, ausente e malformado mantêm; replay sem chamada funciona
- [ ] Cartão do `todo` na conversa é legível
- [ ] Página agrupa por fase e trata abandonadas
- [ ] Fixture dos clientes emite uma lista com duas fases e quatro estados
- [ ] `npm test` e `npm run i18n:check -- --strict` passam; Claude Code, Codex e ZeroClaw sem mudança de comportamento

## Condições de PARADA

- O formato real de `details.phases` diferir do descrito (confira na implementação antes de codar).
- Acrescentar `abandoned`/`phase` ao contrato quebrar um consumidor fora do escopo (clientes Zaplaud ignoram campos
  desconhecidos; confirme por leitura que um `status` desconhecido não derruba o leitor de snapshot do Kotlin
  `SessionRepository.kt` nem do Rust `model.rs` — hoje nenhum dos dois lê `tasks`).
- Algum teste dos clientes depender de `tasks: []` na fixture.

## Notas de manutenção

- Os planos 143 e 144 leem `tasks` (com `phase` e `abandoned`) nos clientes Zaplaud.
- Se o omp mudar o formato do resultado do `todo`, o leitor mantém a última lista (regra "malformado mantém"): a
  falha é silenciosa; vale um aviso único no log quando um resultado de `todo` bem-sucedido não tiver `phases` válido.
- Depois do merge: publicar o servidor (a leitura do transcript é código do servidor, não da extensão).

## Comandos de verificação

| O quê | Comando | Esperado |
|---|---|---|
| Testes | `npm test` | 0 falhas |
| Traduções | `npm run i18n:check -- --strict` | sai com 0 |
