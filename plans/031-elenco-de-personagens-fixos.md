# Plan 031: Elenco de personagens fixos (principais, advisors e subagentes) com salas permitidas

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 2ad0d99..HEAD -- server/model/names.ts server/model/office.ts server/http/app.ts server/sources/omp/parser.ts server/sources/omp/source.ts server/sources/watcher.ts server/sources/subagents.ts shared/types.ts shared/omp-events.ts mod/habblaud-omp/habblaud.ts client/src/ui/character-editor.ts client/src/ui/character-model.ts client/src/net/store.ts server/index.ts`
> If any in-scope file changed since this plan was written, compare the
> "Estado atual confirmado" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status e dependências

- **Priority**: P2.
- **Effort**: L.
- **Risk**: MED: troca a identidade dos personagens (dados persistidos em `names.json`) e a rota do editor.
- **Category**: direction.
- **Planned at**: commit `2ad0d99`, 2026-10-28.
- **Depends on**: nenhum plano.

## Decisões do dono (2026-10-28)

1. Personagem do elenco ocupado (já presente no escritório com outra sessão): a sessão nova recebe um personagem
   **sorteado**, como hoje. Nunca duplicar o mesmo personagem nem criar "Ana #2".
2. Limitar as salas de um personagem é **organização**, não segurança. Nada de bloquear ferramentas no `tool_call`
   da extensão, nada de sandbox (bwrap/podman). A regra só decide qual personagem uma sessão veste.

## Por que mudar

Hoje o personagem nasce da sessão: cada sessão (ou subagente) sorteia um nome guardado por `sessionId` em
`names.json`. O único vínculo fixo é um "personagem do projeto" por sala, que vai para a primeira sessão que chega.
Subagentes e o advisor do omp sempre sorteiam. O dono quer o inverso: um **elenco** de personagens que existem antes
das sessões (os dele, os advisors, os subagentes por tipo), cada um com as salas onde trabalha, e as sessões vestindo
esses personagens.

## Estado atual confirmado

- `server/model/names.ts` — `NameStore` guarda duas coisas em `<dataDir>/names.json` (`version: 1`):
  - `names: Record<chave, {name, look, at}>`: o nome sorteado por `sessionId` (principais) ou id do subagente;
    `assign(key, used)` devolve o guardado se o nome estiver livre, senão sorteia (`pickName`).
  - `rooms?: Record<roomId, StoredCharacter>` (linhas 21-28): o personagem do projeto, `{name, look, seed, parts?,
    owner?, at}`, com `character`, `claimCharacter`, `setCharacter`, `clearCharacter` e `reservedNames` (linhas
    119-147). Entradas expiram em 60 dias (`MAX_AGE_MS`).
  - Instanciado em `server/index.ts:83`: `new NameStore(join(config.dataDir, 'names.json'))`.
- `server/model/office.ts`:
  - `addMain` (linhas 322-367): `roomCharacter(roomId, id, sessionId)` (linhas 899-906) ou
    `names.assign(sessionId, ...)`; com personagem do projeto marca `info.custom = true` e copia `parts`.
  - `switchSession` (linhas 370-388): no `/clear`/`/resume`, o personagem do projeto passa para a sessão nova
    (`claimCharacter`), ou o nome sorteado é lembrado para ela (`names.remember`).
  - `addSub` (linhas 600-635): sempre `names.assign(p.id, this.takenNames())` e `seed: hash32(p.id)`.
  - `moveMain` (linha ~588): o principal trocou de pasta e muda de sala; o personagem não é reavaliado.
  - `setCharacter` / `resetCharacter` (linhas ~840-885) e `nameConflict` (linhas 912-925): o editor da gaveta grava o
    personagem da sala; nomes ocupados por agentes presentes, pelo demo e por `reservedNames` dão conflito.
- `shared/types.ts:192` — `AgentInfo.custom?: true`. Uso no cliente: `client/src/ui/character-editor.ts:88,101`
  (botão "Voltar ao sorteio").
- `server/http/app.ts:126,503-525` — `PUT|DELETE /api/agents/:id/character`, com a trava do terminal (bind local +
  `trusted(req)`); handler nas linhas ~320-345 chama `office.setCharacter` / `office.resetCharacter`.
- Cliente: `client/src/ui/character-editor.ts` (lápis na gaveta, nome, "Sortear", peças via `AppearanceFields`),
  regras puras em `client/src/ui/character-model.ts`, chamada em `client/src/net/store.ts:303-330`.
- omp, subagentes: `server/sources/omp/files.ts:122-133` (`subagentsOf`) lista **todo** `<sessão>/<Nome>.jsonl`.
  O advisor do omp grava `<sessão>/__advisor.jsonl`, então entra no escritório como subagente de nome `__advisor`
  (`server/sources/omp/source.ts:526-533`, `role: t.name`), com nome sorteado.
- omp, tipo do subagente: o cabeçalho do `.jsonl` do subagente **não** traz o tipo (só `"role":"subagent:<Nome>"`
  no `model_change`). O tipo está na chamada `task` do pai: `{"tasks":[{"name":"ReviewPR","agent":"reviewer",...}]}`
  (conferido em transcripts reais de 2026-10-2x); sem `agent`, o omp usa o agente padrão `task`. O parser do pai já
  lê `input.tasks[].name` em `server/sources/omp/parser.ts:189-193` e conta em `toolCall` (linha ~519).
- Claude Code, tipo do subagente: já chega em `role` por `subagentRole(meta, spawn?.subagentType)`
  (`server/sources/subagents.ts:131-136`, `server/sources/watcher.ts:765`).
- Extensão do omp: `mod/habblaud-omp/habblaud.ts` manda `session_start` e batimento com `sessionFields(ctx)` (linhas
  549-559); lê `env` (`process.env`) e já usa variáveis `HABBLAUD_*`. `OmpEvent` em `shared/omp-events.ts:33-50`.
- Habblaud World: `CLAUDE.md` proíbe mandar ao World agentes ou personagens que não correspondem a sessões reais.
  O World lê os agentes presentes do snapshot; ele não pode ganhar membros do elenco sem sessão.

## Escopo

**Dentro:**

1. `CastStore` (`server/model/cast.ts`) com `<dataDir>/cast.json`: membros do elenco e vínculos `sessionId → membro`.
2. Migração única de `names.json` `rooms` para o elenco; `NameStore` fica só com os nomes sorteados.
3. Escolha do personagem em `addMain`, `addSub`, `switchSession` e `moveMain` por uma função só (`castFor`).
4. Tipo do subagente do omp (`tasks[].agent`, padrão `task`) e advisor (`__advisor`) para casar com o elenco.
5. Vínculo explícito pelo ambiente do omp: `HABBLAUD_CAST=<id>` → campo `cast` no `session_start` e no batimento.
6. API do elenco e tela "Elenco" no cliente; o editor da gaveta passa a "Vestir" um membro (ou criar um a partir do
   agente).
7. Salas permitidas por membro: só decidem quem veste o quê (decisão 2).

**Fora (não fazer):**

- Bloquear ferramentas fora das salas (extensão), sandbox, mudar o `--approval-mode`.
- Chamar e dispensar personagens (abrir e encerrar sessões do omp pelo Habblaud, perfil de modelo e instruções,
  ociosidade): plano 032, que depende deste. O `PtyManager` continua abrindo só o Claude Code.
- Vínculo explícito para sessões do Claude Code ou do Codex: não há canal confirmado que leve o ambiente da sessão
  ao Habblaud. Elas usam o vínculo pela gaveta, a casa da sala e o tipo do subagente (Claude).
- Mostrar no World membros sem sessão. Qualquer mudança em `shared/world/` ou `server/world/` é STOP.

## Contrato de destino

### Dados (`<dataDir>/cast.json`)

```ts
interface CastMember {
  id: string;                 // [a-z0-9-]{1,40}, estável, gerado do nome na criação
  name: string;               // parseCharacterName; único no elenco (nameKey)
  look: 'f' | 'm';
  seed: number;
  parts?: AppearanceParts;
  kind: 'main' | 'advisor' | 'sub';
  /** sub: tipos de subagente que este membro assume (comparação sem caixa): 'reviewer', 'scout', 'Explore'... */
  match?: string[];
  /** main: sala (pathKey) em que ele veste a primeira sessão sem vínculo; substitui o "personagem do projeto". */
  home?: string;
  /** Salas (pathKey) onde trabalha; vazio = todas. `home`, se houver, precisa estar aqui. */
  rooms: string[];
}

interface CastFile {
  version: 1;
  members: CastMember[];
  /** Sessão (principal) → membro. `at` = último uso; expira em 60 dias como names.json. */
  bindings: Record<string, { cast: string; at: number }>;
}
```

Limites: no máximo 200 membros, 4000 vínculos (os mais recentes), `match` até 20 itens de até 60 caracteres, `rooms`
até 50 itens de até `FOLDER_MAX` (1024). Gravação atômica (tmp + rename) e adiada 2 s, como `NameStore`. Arquivo
ilegível: aviso uma vez e elenco vazio (não apagar o arquivo).

### Quem veste quem (`castFor`)

Um membro está **livre** se nenhum agente presente (fora do período de graça, a mesma regra de `nameConflict`) usa
o id dele. Uma sala é **permitida** se `rooms` estiver vazio ou contiver o `pathKey` da sala.

Principal (`addMain`), na ordem; a primeira regra que der um membro livre e com a sala permitida vence:

1. `MainInput.cast` (vínculo explícito pedido pela sessão, só omp): grava o vínculo da sessão.
2. Vínculo gravado de `sessionId`.
3. Membro `kind: 'main'` com `home` = sala, sem vínculo de outra sessão presente.
4. Sorteio (`NameStore.assign`), como hoje.

Se uma regra 1 ou 2 apontar um membro ocupado ou fora da sala, a sessão sorteia e o escritório avisa uma vez
(`tr('{0} está ocupado em {1}; {2} assumiu a sessão', ...)` / `tr('{0} não trabalha em {1}; {2} assumiu a sessão', ...)`).
O vínculo gravado **não** é apagado: na próxima vez que o membro estiver livre na sala certa, a sessão volta a ele.

Subagente (`addSub`): membro com `kind: 'advisor'` quando o nome do arquivo for `__advisor` (omp); senão membro com
`kind: 'sub'` cujo `match` contém `agentType`. Vários membros casando: o primeiro livre na ordem do elenco. Sala =
sala do pai. Nenhum livre: sorteio, **sem** aviso (subagentes são muitos).

- `switchSession`: o vínculo passa para a sessão nova (mesmo membro).
- `moveMain` para sala não permitida: o agente perde o membro, sorteia, e o escritório avisa; voltar a uma sala
  permitida reaplica `castFor`.
- Nomes sorteados nunca usam um nome do elenco: `takenNames` inclui todos os nomes dos membros (substitui
  `reservedNames`).
- `AgentInfo.custom?: true` vira `AgentInfo.cast?: string` (id do membro). Mesma forma de `name`, `look`, `seed`,
  `parts` no snapshot; o World não muda.

### HTTP (mesma trava de hoje: `deps.terminal` + `trusted(req)`)

| Rota | Corpo | Efeito |
|---|---|---|
| `GET /api/cast` | — | `{members}` (sem os vínculos) |
| `POST /api/cast` | membro sem `id` | cria; 409 nome repetido (no elenco ou num agente presente sorteado) |
| `PUT /api/cast/:id` | campos editáveis | edita; agentes que vestem o membro atualizam na hora |
| `DELETE /api/cast/:id` | — | apaga membro e vínculos; quem o vestia sorteia |
| `PUT /api/agents/:id/cast` | `{cast}` | só principal presente: grava o vínculo e veste agora; 409 ocupado, 422 sala não permitida |
| `DELETE /api/agents/:id/cast` | — | apaga o vínculo; o agente volta ao nome sorteado da sessão |

`PUT|DELETE /api/agents/:id/character` sai (cutover limpo; o único consumidor é `client/src/net/store.ts`).

### omp

- `OmpEvent.cast?: string` em `session_start` e batimento, validado como o id do membro (`parseOmpEvent`).
- Extensão: `sessionFields` inclui `cast: env.HABBLAUD_CAST` quando presente e válido. Subagentes não mandam eventos
  (como hoje).
- Fonte: o tracker guarda o último `cast` recebido; `addMain` recebe `cast` em `MainInput`. Chegou um `cast`
  diferente com a sessão já no escritório: reaplicar `castFor` (sessão reaberta com outra variável).
- Parser do pai: `OmpState.subTypes: Map<nome, tipo>` preenchido em `toolCall` de `task`
  (`tasks[i].agent ?? 'task'`), limite de 200 entradas; a fonte passa `agentType` em `SubInput` ao criar o
  subagente (procurando no estado do tracker pai).
- Claude: `watcher.ts` passa `agentType: meta?.agentType ?? spawn?.subagentType` em `SubInput`. Codex: sem
  `agentType` (só sorteio), fora do escopo.

## Comandos e gates

| Finalidade | Comando | Resultado esperado |
|---|---|---|
| Dependências na worktree nova, se faltarem | `npm ci` | exit 0, lockfile sem alteração |
| Tipos | `npm run typecheck` | exit 0 |
| Regressões focadas | `npx vitest run server/model server/sources/omp server/sources/watcher.test.ts server/http server/test/omp-extension.test.ts client/src/ui/character-model.test.ts client/src/net/store.test.ts` | todos passam |
| Suíte completa | `npm test -- --maxWorkers=2` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | nenhuma chave faltante/excedente |
| Build | `npm run build` | exit 0 |

`--maxWorkers=2`: mesma razão do plano 030 (timeout de socket em `world/uplink.test.ts` sob concorrência padrão).

## Passos de implementação

Executar numa worktree (`git worktree add ../habblaud-031 -b advisor/031-elenco`), nunca em `~/apps/habblaud`.

### 1. `CastStore` e migração

- Criar `server/model/cast.ts` com `CastStore` (load/flush/validação do contrato acima) e
  `server/model/cast.test.ts`: arquivo ilegível, limites, expiração dos vínculos, nome repetido, `home` fora de
  `rooms` recusado.
- Migração em `load()`: sem `cast.json` e com `names.json` contendo `rooms`, cada `rooms[roomId]` vira um membro
  `kind: 'main'`, `home: roomId`, `rooms: [roomId]`, mesmo `name/look/seed/parts`; `owner` vira vínculo. Antes de
  gravar, copiar `names.json` para `names.json.pre-031` (só se a cópia não existir).
- `NameStore`: remover `StoredCharacter`, `rooms`, `character`, `claimCharacter`, `setCharacter`,
  `clearCharacter`, `reservedNames` e a leitura de `rooms` (a migração lê o arquivo bruto por conta própria). O
  próximo `flush` grava `names.json` sem `rooms`.
- `server/index.ts`: instanciar `CastStore(join(config.dataDir, 'cast.json'))` antes do `Office` e passar em
  `OfficeDeps`.

### 2. `castFor` no `Office`

- Implementar as regras de "Quem veste quem" num método privado; `addMain`, `addSub`, `switchSession`, `moveMain`
  chamam só ele. Remover `roomCharacter`, `setCharacter`, `resetCharacter`, `editable` se ficar sem uso.
- `AgentInfo.custom` → `AgentInfo.cast` em `shared/types.ts` e em todos os usos.
- Testes de comportamento em `server/model/model.test.ts` (substituir os do personagem do projeto, não manter
  ambos): membro ocupado → sorteio + aviso; sala fora → sorteio + aviso; vínculo sobrevive a `/clear` e a reinício
  (novo `Office` com o mesmo `CastStore`); `moveMain` para sala proibida e de volta; advisor `__advisor` casa com
  `kind: 'advisor'`; dois subagentes `reviewer` simultâneos com um membro só → um veste, o outro sorteia; nome
  sorteado nunca colide com nome do elenco.

### 3. Tipo do subagente

- omp: `subTypes` no parser + `agentType` em `SubInput` na fonte (passo do contrato). Teste com uma fixture de
  transcript do pai com `tasks[{name, agent}]` e um sem `agent` (→ `task`).
- Claude: `agentType` em `watcher.ts`. Teste no `watcher.test.ts` existente que já cobre `subagentType`.

### 4. HTTP

- Rotas do contrato em `server/http/app.ts`, com a trava existente do editor. Remover a rota `/character`.
- Testes de HTTP: 403 sem trava, 409 ocupado, 422 sala, CRUD feliz, `DELETE` do membro faz o agente sortear.

### 5. Vínculo explícito pelo omp

- `OmpEvent.cast` + `parseOmpEvent`; extensão envia `HABBLAUD_CAST`; fonte guarda e repassa.
- Teste em `server/test/omp-extension.test.ts`: com `HABBLAUD_CAST=ana` no env falso, o `session_start` e o
  batimento levam `cast: 'ana'`; valor inválido não vai.
- Atualizar o comentário de cabeçalho da extensão (lista numerada "O que faz") com o item novo.

### 6. Cliente

- `client/src/net/store.ts`: métodos do elenco e de vestir/desvestir, no lugar de `saveCharacter`/`resetCharacter`.
- Gaveta (`character-editor.ts`): o lápis abre "Vestir": lista os membros `kind: 'main'` (ocupados e fora da sala
  aparecem desabilitados com o motivo), "Criar a partir deste agente" (abre o editor de membro já preenchido com o
  visual atual e `rooms: [sala atual]`) e "Voltar ao sorteio" (visível com `agent.cast`).
- Tela "Elenco" (nova, aberta pelo mesmo lugar das configurações): lista, criar, editar, apagar. Editor de membro
  reusa `AppearanceFields`; campos `kind`, `match` (texto separado por vírgula), salas (escolher entre as salas
  conhecidas e as pastas recentes de `recentFolders`; vazio = todas) e `home`.
- Regras puras (rótulos, motivo de desabilitado, validação do formulário) em `character-model.ts` com teste.
- Todo texto por `tr()`, traduções em `shared/locales/`; entrada em "Não lançado" no `CHANGELOG.md`.

### 7. Docs e prova

- `server/README.md` e as seções de API dos READMEs (as 4 línguas, como o plano 022): rotas novas, `cast.json`,
  `HABBLAUD_CAST`.
- Prova manual numa instância descartável (`HABBLAUD_DATA_DIR` temporário, porta livre), com `names.json` copiado
  de um que tenha `rooms`: (a) migração cria os membros e `names.json.pre-031`; (b) criar "Ana" com `rooms` = uma
  pasta; abrir `HABBLAUD_CAST=ana omp` nessa pasta → aparece Ana; (c) segunda sessão com a mesma variável → nome
  sorteado + aviso; (d) mesma variável noutra pasta → sorteio + aviso "não trabalha"; (e) membro `kind: 'advisor'`
  + `/advisor on` → o advisor aparece com o nome do membro; (f) membro `kind: 'sub'`, `match: ['reviewer']` + uma
  chamada `task` com `agent: reviewer` → o subagente veste o membro. Registrar a saída/prints no resultado.

## Cutover, dados e rollback

- Cutover único: a rota `/character`, `custom` e `NameStore.rooms` saem no mesmo commit em que o elenco entra.
- Dados: `names.json` perde `rooms` no primeiro `flush`; `names.json.pre-031` guarda o original. `cast.json` é novo.
- Rollback: voltar o código ao commit anterior e restaurar `names.json.pre-031` → `names.json`; apagar `cast.json`.
  Vínculos criados depois do cutover se perdem (aceito: são reconstruídos pela gaveta).
- Publicação (produção `~/apps/habblaud`, `omp-tenant release` + `sync`, `npm run omp:install` para a extensão,
  reabrir sessões do omp) é do operador, fora deste plano.

## Critérios de aceite

- Todos os gates passam.
- Uma sessão nunca veste um membro ocupado nem fora das salas dele; nesses casos ela sorteia e o aviso aparece uma
  vez (principal).
- O vínculo de uma sessão sobrevive a `/clear`, `/resume` e reinício do servidor.
- Advisor do omp e subagentes por tipo (omp `tasks[].agent`, Claude `subagent_type`) vestem os membros que casam.
- `HABBLAUD_CAST` no ambiente do omp escolhe o membro.
- `names.json` antigo com `rooms` migra sem perder personagem nem dono.
- Nenhuma mudança em `shared/world/` ou `server/world/`; o World só vê agentes presentes.
- Prova manual do passo 7 registrada.

## STOP conditions

- O drift check mostra mudança incompatível com "Estado atual confirmado".
- Algum passo exige mudar `shared/world/` ou `server/world/`, ou mandar ao World membro sem sessão.
- O transcript do omp instalado não tem `tasks[].agent` na chamada `task`, ou o arquivo do subagente não tem o nome
  da task: registrar a versão do omp e parar o passo 3 (os outros seguem).
- A extensão não consegue ler o ambiente (`env.HABBLAUD_CAST`) no omp instalado: registrar e parar o passo 5.
- Para passar é preciso bloquear ferramentas, abrir sandbox ou mudar o PTY para abrir o omp (fora da decisão 2).
- Gate relevante continua falhando após duas rodadas de correção: registrar causa e bloqueio; não marcar DONE.

## Manutenção

- Membro novo de `kind: 'sub'` precisa do nome do tipo como o omp ou o Claude grava (`reviewer`, `scout`, `task`,
  `Explore`...): a tela "Elenco" deve mostrar os tipos vistos recentemente como sugestão, nunca inventar.
- Limitar salas é só organização: um agente vestindo um membro continua podendo mexer em qualquer pasta. Se um dia
  virar segurança, o caminho é sandbox na abertura da sessão, não o editor do elenco.
