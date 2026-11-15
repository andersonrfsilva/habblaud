# Plan 084: Contato mantém nome e id na troca de ferramenta; conversa nova do Antigravity abre vazia, sem erro; cartão de permissão e fala da pessoa corretos (servidor)

> **REMOVIDO em 2026-11-15 por ordem do dono. NÃO EXECUTE.** A integração com o Antigravity CLI (`agy`) foi
> apagada do código e da máquina por decisão do dono, para não expor a conta Google dele a risco.
> Este arquivo fica só como registro histórico. Nenhum agente deve reintroduzir, propor ou testar essa integração.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat 924199e..HEAD -- server/cast/launcher.ts server/model/office.ts server/model/names.ts server/sources/antigravity server/messages/antigravity.ts server/antigravity/acp.ts server/antigravity/fake-agy.mjs`
> Se algum mudou, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (mexe na identidade dos contatos "Novo" e no lançador)
- **Depends on**: none (083 e 079 estão em `producao`)
- **Category**: bug
- **Planned at**: commit `924199e`, 2026-11-14

## Why this matters

O dono testou a troca de ferramenta (omp ⇄ Antigravity CLI, `agy`) pelo Zaplaud e achou "inconsistente". Um teste
dirigido no Mac, contra o servidor de produção, mostrou cinco defeitos cuja causa está no servidor: (1) um contato
"Novo" chamado Diego virou "Zoe", com outro avatar, ao trocar de ferramenta; (2) virou "Renata" ao pedir "Nova
sessão" no Antigravity; (3) toda conversa nova do Antigravity aparece nos clientes como "Conversa indisponível ou não
encontrada" até existir uma mensagem; (4) o cartão de permissão para ler um arquivo mostra `read_file` sem dizer qual
arquivo; (5) o balão da pessoa mostra texto que ela não digitou ("A conversa anterior… está no arquivo anexo", ou as
instruções do personagem). Além disso, cada troca para o Antigravity custa um "Permitir" para o `agy` ler o arquivo
de contexto; foi medido que isso some se a sessão declarar a pasta do contexto.

## Regras de mecânica (não negociáveis; as mesmas dos planos 078, 079 e 083)

1. Só o binário oficial `agy` fala com o Google, por stdin/stdout de `agy acp`. Nenhum código lê `~/.gemini`.
   `agyArgs` não muda: **nenhuma flag nova**. O que este plano acrescenta vai por parâmetro do protocolo.
2. Nenhum turno automático; nenhuma permissão respondida sem decisão de uma pessoa.
3. `server/test/antigravity-mecanica.test.ts` continua passando sem edição.

## Current state

Baseline em `924199e`: `npm run typecheck` ok; `npm test` 219 arquivos, 2945 testes passando, 7 pulados (um teste,
`server/world/uplink.test.ts` "sem resposta ao ping", oscila raramente; não é deste plano).

### Nome e visual

- `server/model/office.ts:404-432` (`addMain`): agente sem personagem recebe
  `this.deps.names.assign(p.sessionId, …)`; para "Novo", `keepNewCast(info, p.cast)` guarda o id `novo-…` no agente.
- `server/model/office.ts:883-896` (`applyCast`):
  `const person = member ?? this.deps.names.assign(info.kind === 'main' ? info.sessionId : info.id, this.takenNames(info.id));`
- `server/model/office.ts:432-449` (`switchSession`):
  ```ts
  if (!info.cast) this.deps.names.remember(sessionId, { name: info.name, look: info.look });
  info.sessionId = sessionId;
  this.applyCast(info, this.castFor({ id, session: sessionId }));
  ```
  Um "Novo" tem `info.cast` (`novo-…`), então o nome **não** é lembrado e a sessão nova sorteia outro: causa do (2).
- `server/model/names.ts:44-55`: `NameStore.assign(key, used)` reusa o que está guardado para a chave, senão
  `pickName(key, used)` (`shared/names.ts:37-51`, determinístico pela chave). Há `remember(key, person)`.
- `server/cast/launcher.ts:134-156` (`startNewInternal`): sempre
  ``const cast = `${NEW_CAST_PREFIX}${randomUUID().slice(0, 8)}`;``. `switchTool` (linhas ~168-199) chama
  `startNewInternal({}, input)` para "Novo" e depois `handoff.move(cast, opening.job.cast)`: o contato reabre com
  **outro id** e, portanto, outra chave de nome: causa do (1).

### Conversa vazia

- `server/http/terminal.ts:253-270`: sem caminho de transcript, responde 404 (`transcript do agente não encontrado`).
- `server/sources/antigravity/source.ts:44-49`: `transcriptPathOf` só devolve o caminho se o espelho existe.
- `server/sources/antigravity/mirror.ts:32-70`: o arquivo `<dataDir>/antigravity/sessions/<sessionId>.jsonl` só é
  criado no primeiro registro (por exemplo `user_message_chunk`). O ZeroClaw cria o espelho ao preparar a sessão
  (`server/sources/zeroclaw/source.ts:45-60`); o omp já nasce com arquivo.

### Cartão de permissão

- `server/sources/antigravity/mirror.ts:6-24`: tabela de tradução com `run_command`, `view_file`, `write_to_file`,
  `replace_file_content`, `multi_replace_file_content`, `grep_search`, `list_dir`; ferramenta desconhecida devolve
  `{ name, input: { summary: maskSecrets(text(call.title)) } }`.
- Medido no `agy` 1.5.9: a leitura de arquivo chega como `toolCall: { name: 'read_file', kind: 'read', title: 'File
  ../../x/ctx.md', rawInput: { Path: '/caminho/absoluto/ctx.md' } }`; `run_command` chega com `rawInput.CommandLine`.
  Os outros nomes da tabela **não foram observados** (vieram de documentação antiga).
- `server/antigravity/permissions.ts:28-34` passa `tool.name`/`tool.input` ao `PermissionRegistry.register`;
  `server/permissions/registry.ts:409-443` e `describeTool` (`shared/activity.ts:690-793`) montam o cartão: ferramenta
  desconhecida vira "Usando {nome}" e `input.summary` não aparece.

### Fala da pessoa

- `server/messages/antigravity.ts:35-47` (`sendMessage`):
  ```ts
  const request = instructions?.pending ? `Instruções do personagem:\n${instructions.text}\n\nPedido inicial:\n${content}` : content;
  const text = path ? `${tr('A conversa anterior, feita em outra ferramenta, está no arquivo anexo. Leia antes de responder.')}\n\n${request}` : request;
  ```
- `server/sources/antigravity/mirror.ts:42-47`: cada `user_message_chunk` vira uma linha `user` com
  `update.content.text`; o bloco `resource_link` (sem `text`) vira uma linha `user` **vazia**, e o bloco de texto vira
  a fala com os prefixos.

### Permissão para ler o contexto

- Medido no `agy` 1.5.9 em 2026-11-14: com `cwd` numa pasta e o arquivo em outra, sob a pasta pessoal, o `agy` pede
  permissão para `read_file`. Com `session/new { cwd, mcpServers: [], additionalDirectories: ['<pasta do arquivo>'] }`
  a mesma leitura acontece **sem pedido**. `initialize` anuncia `sessionCapabilities.additionalDirectories`.
- `server/antigravity/acp.ts`: `open(input)` monta o `session/new`/`session/resume`; `HandoffStore` grava em
  `<dataDir>/handoff` (`server/cast/handoff.ts`).

Convenções: texto visível em `tr('…')` com tradução em `shared/locales/{en,es,fr}.ts`; `CHANGELOG.md` em
`## [Não lançado]`; testes `*.test.ts` ao lado (Vitest); `agy` falso: `server/antigravity/fake-agy.mjs`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Instalar (worktree nova) | `npm ci` | exit 0 |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes do recorte | `npx vitest run antigravity server/cast server/model server/messages server/http/terminal` | todos passam |
| Suíte | `npm test` | exit 0, ≥ 2945 passando |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**Editar**: `server/model/office.ts`, `server/model/names.ts` (só se precisar de um método), `server/cast/launcher.ts`,
`server/sources/antigravity/{source,mirror}.ts`, `server/messages/antigravity.ts`, `server/antigravity/acp.ts`,
`server/antigravity/fake-agy.mjs`, `server/index.ts` (só fiação), os `.test.ts` correspondentes (acrescentando
casos), `shared/locales/{en,es,fr}.ts` se houver texto novo, `CHANGELOG.md`, `server/README.md`.

**Out of scope** (NÃO toque):
- `client/**`, `android/**`, `zaplaud-rs/**` (planos 085, 086 e 087).
- `agyArgs`, `scripts/antigravity-relay.ts`, `server/sources/omp/**`, `mod/**`, ZeroClaw.
- O texto que o **omp** recebe com o caminho do contexto: fica como está (mesma convenção dos anexos).
- Rodar o `agy` de verdade; `~/apps/habblaud`, `systemctl`, `podman`.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-084 -b advisor/084-identidade-conversa producao`.
- Um commit por passo, estilo `fix(antigravity): …`. Não faça push nem abra PR.

## Steps

### Step 1: "Novo" mantém o id na troca de ferramenta

`server/cast/launcher.ts`: o caminho interno de abertura do "Novo" (`startNewInternal(raw, internal)`) passa a
aceitar `internal.cast` e a usá-lo em vez de sortear outro; `switchTool` o passa com o `cast` do agente e deixa de
chamar `handoff.move` (o id não muda mais). A ordem continua: dispensar a sessão atual, esperar o job terminar,
abrir de novo. Confira que a checagem de "personagem ocupado" não barra a reabertura (o agente antigo já está
`offline`; `opened` já foi limpo). O caminho HTTP (`startNew`) continua sorteando id novo.

Testes (acrescente em `server/cast/launcher.test.ts`): troca omp → `agy/…` de um "Novo": o agente reaberto tem o
**mesmo** `cast`; idem na volta; o arquivo de contexto continua pendente sob o mesmo id e é entregue.

**Verify**: `npx vitest run server/cast` passa, sem editar afirmações existentes (o teste 7 do plano 083, "o
contexto segue o id novo", passa a afirmar o **mesmo** id: essa é a única afirmação antiga que muda, e deve mudar).

### Step 2: nome e visual do "Novo" pela identidade do contato

`server/model/office.ts`: para agente principal com `cast` começando por `novo-`, a chave do `NameStore` passa a
ser o **id do contato** (`info.cast`), em `addMain`, `applyCast` e `switchSession`. Regra de migração, para não
renomear quem já está aberto: se não há nada guardado para o `cast` mas há para o `sessionId`, copie
(`names.remember(cast, <o guardado>)`) e use. Em `switchSession`, um "Novo" mantém nome e visual. Personagens do
elenco (nome do membro) e agentes sem `cast` não mudam de comportamento.

Testes (em `server/model/` e `server/sources/antigravity/source.test.ts`): "Novo" mantém nome e visual quando a
sessão troca ("conversa nova" do Antigravity, com `replaces`); dois agentes sucessivos com o mesmo `novo-…` (omp
depois Antigravity) têm o mesmo nome e visual; um "Novo" já nomeado pela sessão mantém o nome depois da mudança
(migração); agente sem `cast` e personagem do elenco: testes existentes sem edição.

**Verify**: `npx vitest run server/model server/sources/antigravity` passa.

### Step 3: conversa nova do Antigravity abre vazia

`server/sources/antigravity/mirror.ts` (ou a fonte): ao tratar `session-open` (abertura, retomada e adoção por
`replaces`), garanta que o arquivo do espelho existe (cria vazio, pasta `0700`, arquivo `0600`, sem truncar um
existente). `transcriptPathOf` passa a devolver o caminho para toda sessão aberta.

Teste: depois de `session-open`, `transcriptPathOf` devolve um arquivo existente e vazio; retomar não trunca; em
`server/http/terminal.test.ts` (ou onde a rota é testada), um agente do Antigravity sem mensagens responde com o
`init` vazio em vez de 404.

**Verify**: `npx vitest run server/sources/antigravity server/http/terminal` passa.

### Step 4: cartão de permissão com o que será feito

`server/sources/antigravity/mirror.ts`, tabela de tradução: acrescente `read_file: { name: 'Read', fields: {
file_path: 'Path' } }`. Para ferramenta desconhecida, o `input` deixa de ser `{ summary }` e passa a levar o que dá
para mostrar: `{ description: <title mascarado>, ...<campos de rawInput que são texto, número ou booleano, cada texto
cortado em 500 caracteres, mascarados> }`, de modo que o cartão (campo `input` do pedido) nunca fique vazio. Confira
em `server/permissions/registry.ts` como `view.input` é derivado e ajuste **só a tradução**, não o registro.

Testes (`mirror.test.ts`, `server/antigravity/permissions.test.ts`): `read_file` com `Path` vira `Read` com
`file_path`, e o pedido registrado tem título com o caminho; ferramenta desconhecida tem `input` não vazio com o
`title` e os campos de `rawInput`; segredo em `rawInput` sai mascarado.

**Verify**: `npx vitest run server/sources/antigravity server/antigravity/permissions` passa.

### Step 5: a fala gravada é a que a pessoa digitou

- `server/messages/antigravity.ts` (`sendMessage`): além de mandar `text`, avise o espelho do par
  `{ sent: text, shown: content }` daquela sessão (um método novo no espelho/fonte, por exemplo
  `expectUser(sessionId, sent, shown)`; a entrega recebe a fonte por interface mínima, como já recebe `replace`).
- `mirror.ts`, ao tratar `user_message_chunk`: bloco sem texto (o `resource_link`) **não** gera linha; bloco cujo
  texto é igual ao `sent` esperado grava `shown` e consome a expectativa; qualquer outro texto é gravado como veio.
  A expectativa de uma sessão fechada é descartada.
- O arquivo de contexto (`renderHandoff`) passa, por consequência, a levar a fala limpa.

Testes: mensagem com instruções pendentes e com contexto anexo → o espelho tem **uma** linha `user` com exatamente o
que a pessoa mandou; mensagem comum inalterada; nenhuma linha `user` vazia.

**Verify**: `npx vitest run server/messages/antigravity server/sources/antigravity` passa.

### Step 6: o `agy` lê o contexto sem pedir permissão

`server/antigravity/acp.ts`: `AntigravityClient` recebe por opção `additionalDirectories?: string[]` (caminhos
absolutos) e os manda em **todo** `session/new` e `session/resume` (`additionalDirectories` nos parâmetros). Fiação
em `server/index.ts`: só a pasta do `HandoffStore` (`<dataDir>/handoff`), criada antes (`0700`). Nada mais entra na
lista. `fake-agy.mjs`: grava o que recebeu para os testes; passa a pedir permissão para `read_file` de arquivo fora
do `cwd` **e** fora de `additionalDirectories`, como o `agy` medido.

Testes (`acp.test.ts`): o parâmetro vai no `session/new` e no `session/resume`; com a pasta declarada o falso não
pede permissão para o link do contexto; sem ela, pede.

**Verify**: `npx vitest run server/antigravity` passa;
`git diff producao -- server/antigravity/acp.ts | grep -c "agyArgs"` → `0`.

### Step 7: documentação

`CHANGELOG.md` (`## [Não lançado]`, em "Corrigido"): os seis itens, em linguagem de usuário. `server/README.md`: a
regra do nome do "Novo", o espelho criado na abertura e `additionalDirectories`.

**Verify**: `npm run typecheck && npm run i18n:check -- --strict && npm run build && npm test` → exit 0.

## Test plan

Listado nos passos. O essencial: o mesmo contato mantém `cast`, nome e visual na troca de ferramenta e na conversa
nova; a rota do terminal nunca dá 404 para uma sessão aberta do Antigravity; o cartão de permissão mostra o arquivo;
o espelho não contém texto que a pessoa não digitou.

## Done criteria

- [ ] `npm run typecheck`, `npm test`, `npm run i18n:check -- --strict`, `npm run build` com exit 0
- [ ] `git diff --stat producao -- server/test/antigravity-mecanica.test.ts client android zaplaud-rs scripts mod server/sources/omp server/sources/zeroclaw` vazio
- [ ] `git diff producao -- server/antigravity/acp.ts | grep -c "agyArgs"` → `0`
- [ ] Afirmações de testes existentes alteradas: só a do teste do 083 sobre o id novo do "Novo" (liste no relato)
- [ ] `git status --short` limpo

## STOP conditions

- Um trecho de "Current state" não bate com o código.
- Manter o `cast` do "Novo" na troca exigir mudar o protocolo `poll`/`ack` do auxiliar do omp ou `scripts/**`.
- A chave de nome pelo `cast` renomear um personagem do elenco ou um agente sem `cast` (algum teste existente de
  nomes falhar).
- Qualquer passo parecer exigir flag do `agy`, leitura de `~/.gemini` ou turno automático.

## Maintenance notes

- Contrato para os clientes (planos 085–087): depois de uma troca de ferramenta, o sucessor é o agente principal
  aberto com o **mesmo `cast`** e outro `id`. Vale para personagem e, depois deste plano, para "Novo".
- `additionalDirectories` foi medido só para leitura no `agy` 1.5.9; a lista leva só a pasta do contexto.
- Quem revisar olha: a migração do nome (ninguém aberto muda de nome ao publicar); que o caminho HTTP do "Novo"
  continua sorteando id; que nenhuma linha `user` vazia sobra no espelho.
- Fumaça do revisor em produção, pelo Zaplaud do Mac: "Novo" no omp → trocar para `agy/…` → mesmo nome; conversa
  abre vazia; primeira mensagem sem pedido de permissão e com a fala limpa; "Nova sessão" mantém o nome.
