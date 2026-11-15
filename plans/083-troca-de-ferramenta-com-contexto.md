# Plan 083: Trocar o modelo de um agente aberto troca de ferramenta (omp ⇄ Antigravity) e leva o contexto (servidor)

> **REMOVIDO em 2026-11-15 por ordem do dono. NÃO EXECUTE.** A integração com o Antigravity CLI (`agy`) foi
> apagada do código e da máquina por decisão do dono, para não expor a conta Google dele a risco.
> Este arquivo fica só como registro histórico. Nenhum agente deve reintroduzir, propor ou testar essa integração.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat ba5a285..HEAD -- server/messages/registry.ts server/messages/http.ts server/sources/terminal.ts server/sources/source.ts server/spawn/attachments.ts`
> As mudanças do plano 079 (em `server/cast`, `server/antigravity`, `server/sources/antigravity`,
> `server/messages/antigravity.ts`, `shared/agy-model.ts`) são esperadas. Se um dos arquivos listados acima mudou,
> compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: MED (fecha uma sessão e abre outra em nome da pessoa; mexe em `server/messages/registry.ts`)
- **Depends on**: plans/079-elenco-com-antigravity.md (precisa estar mesclado em `producao`)
- **Category**: direction
- **Planned at**: commit `ba5a285`, 2026-11-14

## Why this matters

O dono quer que usar o Antigravity CLI (`agy`) seja só **trocar o modelo**: escolher um modelo `agy/…` num agente
do omp passa a conversa para o `agy`, e escolher um modelo do omp num agente do `agy` volta. O plano 079 já faz isso
ao chamar um personagem; este plano faz com o agente **aberto**. O problema é o contexto: omp e `agy` são programas
diferentes e uma sessão não passa de um para o outro. A solução deste plano: ao trocar, o Habblaud grava a conversa
anterior num arquivo e o **anexa à primeira mensagem que a pessoa mandar** na ferramenta nova. Não há turno extra,
nada é mandado sozinho, e o custo só existe quando a pessoa fala.

## Regras de mecânica (não negociáveis; as mesmas dos planos 078 e 079)

O dono não quer correr risco de bloqueio da conta Google; os Termos do Antigravity
(<https://antigravity.google/terms>, item 6) proíbem acessar o serviço com software de terceiros, e o ACP é a
interface que o `agy` documenta para clientes (leitura, não garantia). Por isso:

1. Só o binário oficial `agy` fala com o Google, por stdin/stdout de `agy acp`. Nenhum código lê `~/.gemini`.
   `agyArgs` não muda: nenhuma flag nova.
2. **Nenhum turno automático.** A troca não manda prompt nenhum. O arquivo de contexto vai como anexo de uma
   mensagem que a pessoa digitou. Não existe "resuma a conversa" disparado pelo Habblaud.
3. Nenhuma permissão é respondida sem decisão de uma pessoa.
4. `agy/` não é `google-antigravity/`: um modelo `google-antigravity/…` continua sendo modelo do omp.
5. O arquivo de contexto leva a conversa de uma ferramenta para o provedor da outra (do omp para o Google, ou do
   `agy` para o provedor do modelo do omp). Isso é o que o dono pediu; a documentação tem de dizer com essas palavras.

## Current state

Baseline em `ba5a285`: `npm test` 2857 passando (o 079 acrescenta testes).

### Medido no `agy` 1.5.9 em 2026-11-14

- Um bloco `{"type": "resource", "resource": {"uri", "mimeType", "text"}}` no `session/prompt` **não funciona** como
  contexto embutido: o `agy` o converte em `resource_link`, ignora o `text` e tenta ler o caminho do `uri`. Com o
  arquivo inexistente, o modelo respondeu que não tinha o conteúdo.
- Um bloco `{"type": "resource_link", "uri": "file:///caminho/absoluto.md", "name": "conversa-anterior.md",
  "mimeType": "text/markdown"}` apontando para um arquivo **que existe, fora da pasta da sessão**, seguido de um
  bloco `{"type": "text", …}`: o `agy` leu o arquivo com a ferramenta `read_file`, **sem pedir permissão**, e
  respondeu com o conteúdo dele. Um turno, `stopReason: end_turn`.
- `session/set_config_option {sessionId, configId: 'model', value}` troca o modelo de uma sessão aberta e mantém a
  conversa (medido no plano 079).

### O que o 079 deixa pronto

- `shared/agy-model.ts`: `isAgyModel`, `agyModelValue`, `toAgyModel`, `AGY_DEFAULT`.
- `server/antigravity/acp.ts`: `open({cwd, resume?, replaces?, cast?, model?})`, `prompt(sessionId, text)`,
  `session(id)` com `cast` e `model`.
- `server/messages/antigravity.ts`: `AntigravityDelivery` com `setInstructions(sessionId, instructions)` e
  `sendMessage` que põe as instruções pendentes na frente da primeira mensagem.
- `server/cast/launcher.ts`: `start(id, raw)`, `startNew(raw)`, `dismiss(agent)` devolvem `{ job: CastJobInfo } |
  { status: number; error: string }`; o runtime sai do modelo (`agy/…` = Antigravity); `state(cast)`; `isOpened`.
  `GET /api/cast/models` traz `agyModels`. Agente do Antigravity: `model: 'agy/<valor>'`, `models: ['agy/…']`.

### O que existe e este plano usa

- `server/messages/registry.ts`, `control(raw)` (linhas 438-475): para `zeroclaw` e `antigravity` só aceita
  `stop: true` ou `command: 'new'` (senão `unavailable` com `ERR_ANTIGRAVITY_CONTROL`); para os demais:
  ```ts
  if (agent.provider !== 'omp') return { error: 'unavailable', reason: tr('só agentes do omp trocam de modelo e raciocínio pela gaveta') };
  …
  const entry = this.entry(agentId, agent.sessionId, { text: '', control: change });
  this.messages.set(entry.msg.id, entry);
  return { message: { ...entry.msg } };
  ```
  (a extensão do omp busca o controle e confirma). O corpo é `POST /api/messages/control {agentId, model?,
  thinking?, command?, stop?, resume?, expectedSessionId?}` e a resposta é uma `OutboxMessage` (`201`), que os
  clientes já sabem ler. **A forma da resposta não pode mudar.**
- `send(raw)` (linhas 371-400): mensagens com anexos passam por `store.take(...)` e o texto final é montado com
  `promptWithAttachments(prompt, paths)` (`server/spawn/attachments.ts:186-191`), que acrescenta
  `Anexo enviado pelo Habblaud (leia antes de começar):\n- <caminho>`. O omp lê esses caminhos do disco.
- `server/sources/source.ts`: o `SourceSet` (`agents` em `server/index.ts`) tem `transcriptPathOf(agentId)` e
  `terminalParser(agentId)` (linha 111: `owner?.terminalParser?.(agentId) ?? createTerminalParser()`), o mesmo par
  que o painel de terminal usa (`server/http/terminal.ts`). `TerminalParser.push(line): TerminalEntry[]`
  (`server/sources/terminal.ts:20-23`); `TerminalEntry` está em `shared/types.ts` (leia a união inteira antes de
  escrever o conversor). O espelho do Antigravity (`<dataDir>/antigravity/sessions/<id>.jsonl`) usa o formato do
  Claude Code e é lido pelo parser padrão; o omp tem `createOmpTerminalParser`.
- `maskSecrets` em `shared/activity` (usado em todo texto que sai para a tela).
- Onde os dados ficam: `config.dataDir` (`~/.habblaud` no computador do dono). No Podman, `~/.habblaud` é montado no
  mesmo caminho dentro e fora do contêiner (`deploy/podman/habblaud.container:36`), então um arquivo gravado pelo
  servidor é legível pelo `agy` e pelo omp, que rodam no host. Os anexos já dependem disso.
- `server/model/office.ts`, laço perto da linha 1046: onde `castOpened` é posto em cada agente do snapshot.
- Convenções: texto visível em `tr('…')` com tradução em `shared/locales/{en,es,fr}.ts`; `CHANGELOG.md` em
  `## [Não lançado]`; testes `*.test.ts` ao lado (Vitest); `agy` falso: `server/antigravity/fake-agy.mjs`.

## O contrato novo (os planos 080, 081 e 082 dependem dele; não mude nomes)

1. `POST /api/messages/control {agentId, model}`, mesma resposta de hoje (`201` com a mensagem; ela termina
   `delivered` ou `failed` com o motivo):

   | Agente | Modelo pedido | O que acontece |
   |---|---|---|
   | omp | do omp | como hoje |
   | Antigravity | `agy/…` | troca o modelo **na mesma conversa** (`session/set_config_option`); o contexto fica |
   | omp | `agy/…` | **troca de ferramenta** |
   | Antigravity | do omp | **troca de ferramenta** |

2. **Troca de ferramenta** só para agente aberto pelo elenco (`castOpened`, personagem ou "Novo"), principal,
   parado (nem trabalhando nem esperando permissão), com a ferramenta de destino disponível. Fora disso:
   `unavailable` com o motivo (textos no Step 4). O servidor: grava o arquivo de contexto, dispensa a sessão atual,
   abre o mesmo personagem na outra ferramenta, na mesma pasta, com o modelo pedido, em conversa nova e **sem
   pedido**. O `model` salvo no personagem não muda.
3. **Contexto.** A primeira mensagem que a pessoa mandar ao agente novo leva a conversa anterior como anexo. Até lá
   o agente tem `handoff: true` no snapshot (`AgentInfo.handoff?: boolean`), para as telas avisarem. Trocar de novo
   antes de mandar mensagem não perde nada: o arquivo é cumulativo.
4. **Lista de modelos.** Para agente com `castOpened`, `AgentInfo.models` passa a trazer também os da outra
   ferramenta: agente do omp ganha os `agyModels` (com `agy/default`); agente do Antigravity ganha a última lista de
   modelos anunciada por um agente do omp nesta execução do servidor (pode faltar, se nenhum agente do omp apareceu
   ainda). Os seletores de modelo que já existem passam a oferecer a troca sem código novo de lista.
5. `thinking` para agente do Antigravity continua recusado.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Instalar (worktree nova) | `npm ci` | exit 0 |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes do recorte | `npx vitest run handoff antigravity server/messages server/cast` | todos passam |
| Suíte | `npm test` | exit 0 |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**Criar**: `server/cast/handoff.ts` (+ `handoff.test.ts`).

**Editar**: `server/antigravity/acp.ts` (+ teste, `fake-agy.mjs`), `server/messages/antigravity.ts` (+ teste),
`server/messages/registry.ts` (+ testes novos; os existentes não são editados), `server/cast/launcher.ts` (+ casos
novos no teste), `server/cast/antigravity.ts`, `server/model/office.ts` (só o laço do `castOpened`),
`server/index.ts`, `shared/types.ts` (só `AgentInfo.handoff?: boolean`), `shared/locales/{en,es,fr}.ts`,
`CHANGELOG.md`, `server/README.md`, `README.pt-BR.md`, `README.md`.

**Out of scope** (NÃO toque):
- `client/**`, `android/**`, `zaplaud-rs/**` (planos 080, 081, 082).
- `server/sources/omp/**`, a extensão do omp (`mod/**`), `scripts/codex-bridge.ts`: o omp recebe o contexto pelo
  texto da mensagem, como já recebe anexos.
- `server/sources/zeroclaw/**`, `server/cast/zeroclaw.ts`, `server/messages/zeroclaw.ts` e seus testes.
- Resumo feito por modelo, compactação, retomar a sessão nativa antiga ao voltar para a ferramenta de origem,
  troca de ferramenta de agentes abertos no terminal (o Habblaud não é dono do processo deles), ZeroClaw.
- Rodar o `agy` de verdade; `~/apps/habblaud`, `systemctl`, `podman`.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-083 -b advisor/083-troca-com-contexto producao` (com o 079 em
  `producao`).
- Um commit por passo. Não faça push nem abra PR.

## Steps

### Step 1: o arquivo de contexto (`server/cast/handoff.ts`)

```ts
export const HANDOFF_MAX = 150_000;          // caracteres do arquivo
export const HANDOFF_HEAD = 20_000;          // do começo, quando precisa cortar
export const HANDOFF_KEEP_MS = 7 * 24 * 3600_000;
/** Converte as entradas do terminal de uma conversa em Markdown. Pura. */
export function renderHandoff(input: { from: string; model?: string; cwd: string; at: number;
  entries: TerminalEntry[]; previous?: string }): string
export class HandoffStore {
  constructor(opts: { dir: string /* <dataDir>/handoff */; now?: () => number });
  /** Grava o arquivo (0600, pasta 0700, temporário + rename); ele vira o `last` do personagem e fica pendente. */
  put(cast: string, markdown: string): string;
  pending(cast: string): string | undefined;      // caminho ainda não entregue
  /** Último arquivo do personagem, **entregue ou não**: é a base (`previous`) da próxima troca. */
  last(cast: string): string | undefined;
  take(cast: string): string | undefined;         // devolve e marca como entregue; o `last` continua
  restore(cast: string, path: string): void;      // a entrega falhou: volta a ficar pendente
  move(from: string, to: string): void;           // "Novo" muda de id de personagem na troca (leva `last` e pendência)
  /** Conversa nova pedida pela pessoa: esquece o `last`. Com entrega pendente, não faz nada. */
  forget(cast: string): void;
  sweep(): void;                                  // apaga arquivos mais velhos que HANDOFF_KEEP_MS e os tira do índice
}
/** Lê o transcript de um agente pelo mesmo parser do painel de terminal. */
export async function readEntries(path: string, parser: TerminalParser): Promise<TerminalEntry[]>
```

- `renderHandoff`: cabeçalho (`# Conversa anterior`, de qual ferramenta e modelo, pasta, data, e a frase "Gerado
  pelo Habblaud a partir da conversa; saídas de ferramentas foram cortadas."); depois, em ordem: falas da pessoa
  (`## Pessoa`) e do agente (`## Agente`) **na íntegra**; ferramentas em uma linha (`- ferramenta: <título> →
  ok|erro`, mais até 300 caracteres da saída); **sem** raciocínio (`thinking`) e sem entradas de sistema. `previous`
  é o conteúdo do **último arquivo do personagem, entregue ou não** (`store.last(cast)`), e entra antes, separado por
  `---`: é isso que torna o arquivo cumulativo. Usar só o pendente perderia a conversa original no caminho de ida →
  mensagem entregue → volta, porque a sessão do meio só registra o **link** do arquivo (o espelho do Antigravity
  grava o `resource_link` sem o texto, e o omp só tem o caminho na fala), não o conteúdo dele. Passe tudo por
  `maskSecrets`. Acima de `HANDOFF_MAX`: mantém os primeiros `HANDOFF_HEAD` caracteres e o fim, com uma linha
  `[… N caracteres omitidos …]` no meio.
- Estado do `HandoffStore` (`last` e pendência por personagem) em `<dir>/index.json` (0600, temporário + `rename`),
  relido na subida: reiniciar o servidor entre a troca e a primeira mensagem não pode perder o contexto. Índice
  ilegível = vazio; entrada cujo arquivo sumiu é descartada.
- `forget(cast)` é chamado quando a **pessoa** começa conversa nova nesse personagem fora de uma troca de
  ferramenta: controle `command: 'new'` e `start`/`startNew` que não vêm do `switchTool`. Sem isso, uma troca
  futura arrastaria uma conversa que a pessoa encerrou de propósito.
- `readEntries`: lê o arquivo linha a linha (arquivos grandes: só os últimos 4 MiB, descartando a primeira linha
  parcial), `parser.push(line)` em cada uma. Arquivo ausente = `[]`.
- Nome do arquivo: `<cast sanitizado para [a-z0-9-]>-<epoch ms>.md`. Nada do pedido HTTP entra no caminho.
- Testes: falas inteiras, ferramenta em uma linha, sem raciocínio; segredo mascarado; corte com começo e fim
  preservados; `previous` na frente; `put`/`pending`/`last`/`take`/`restore`/`move`/`forget` (inclusive: `take` não
  apaga o `last`; `forget` com pendência não faz nada); o índice sobrevive a um `HandoffStore` novo na mesma pasta;
  modos `0600`/`0700`; `sweep` apaga só
  os velhos; um transcript real do omp e um do espelho do Antigravity (use os fixtures que os testes de
  `server/sources/omp/terminal.test.ts` e `server/sources/antigravity/mirror.test.ts` já têm) viram Markdown não
  vazio.

**Verify**: `npx vitest run server/cast/handoff` passa.

### Step 2: mensagem com anexo de contexto, nas duas direções

- `server/antigravity/acp.ts`: `prompt(sessionId, text, links?: { path: string; name: string }[])`. Com `links`, o
  `prompt` do `session/prompt` leva, **antes** do bloco de texto, um bloco por arquivo:
  `{ type: 'resource_link', uri: pathToFileURL(path).href, name, mimeType: 'text/markdown' }`. Só caminhos
  absolutos. `fake-agy.mjs`: grava os blocos recebidos onde os testes possam ler, e o prompt `ok` continua valendo.
- `server/messages/antigravity.ts`: `AntigravityDelivery` recebe o `HandoffStore` e um jeito de saber o `cast` da
  sessão (`client.session(id).cast`). Em `sendMessage`: `const path = handoff.take(cast)`; manda
  `client.prompt(id, texto, path ? [{ path, name: 'conversa-anterior.md' }] : undefined)`, com
  `tr('A conversa anterior, feita em outra ferramenta, está no arquivo anexo. Leia antes de responder.')` como
  primeira linha do texto quando há anexo (antes das instruções do personagem, se houver); se o `prompt` rejeitar,
  `handoff.restore(cast, path)`.
- `server/messages/registry.ts`, em `send`, **só para agente do omp com `cast`**: se `handoff.take(agent.cast)`
  devolve um caminho, o texto da entrada ganha, no fim,
  `` `\n\n${tr('Conversa anterior, feita em outra ferramenta (leia antes de responder):')}\n- ${path}` `` (mesma
  forma do `promptWithAttachments`). O `HandoffStore` chega por `MessageRegistryOptions.handoff?`. Se a mensagem
  falhar ou for cancelada antes de a extensão buscá-la, `restore`. Sem `handoff` nas opções, nada muda (os testes
  existentes passam sem edição).
- Testes: Antigravity recebe o `resource_link` antes do texto, uma vez só; falha devolve a pendência; omp recebe o
  caminho no texto, uma vez só; agente sem `cast` e agentes de outras ferramentas não recebem nada.

**Verify**: `npx vitest run server/messages server/antigravity/acp` → todos passam; `git diff producao --
server/messages/zeroclaw.test.ts` vazio.

### Step 3: troca de modelo dentro do Antigravity

- `server/antigravity/acp.ts`: `setModel(sessionId, value): Promise<void>` (`session/set_config_option`; `-32602` →
  `UnknownModel`); o evento `update` com `config_option_update` já chega à fonte, que atualiza `model` do agente
  (acrescente o tratamento em `server/sources/antigravity/source.ts` se o 079 não o fez).
- `PushDelivery` (em `registry.ts`) ganha o método **opcional** `setModel?(id: string, model: string):
  Promise<void>`; `AntigravityDelivery` o implementa com `agyModelValue`. (`agy/default` → `UnknownModel`: não há
  "voltar ao padrão" numa sessão aberta; o texto do erro diz para escolher um modelo.)
- `registry.control`, ramo do Antigravity: além de `stop` e `command: 'new'`, aceita `model` **sozinho** quando
  `isAgyModel(model)`; a entrada entra na mesma fila "empurrada" e espera o turno acabar, como uma mensagem.
  `thinking` continua recusado.
- Testes: troca aplicada com a sessão parada; com turno em curso, espera; modelo desconhecido → `failed` com o
  texto; `thinking` recusado; ZeroClaw inalterado.

**Verify**: `npx vitest run server/messages` passa.

### Step 4: troca de ferramenta

- `server/cast/launcher.ts`: `switchTool(agent: AgentInfo, model: string): Promise<void>`. Recusa, sem fechar nada,
  com erro de mensagem em `tr()`:
  - agente sem `cast` aberto no lançador, ou subagente/advisor: `tr('só agentes abertos pelo elenco trocam de
    ferramenta; dispense e chame de novo com o outro modelo')`;
  - agente `working` ou `waiting`: `tr('espere o turno terminar (ou use STOP) para trocar de ferramenta')`;
  - destino indisponível (auxiliar do omp ausente, Antigravity desligado, limite não se aplica porque uma sessão
    fecha antes de a outra abrir): o motivo do destino;
  - personagem `advisor`/`sub` indo para `agy/…`: recusado pela regra do 079.
  Depois: (1) `entries = await readEntries(transcriptPathOf(agent.id), terminalParser(agent.id))`; (2) `markdown =
  renderHandoff({ from: <nome da ferramenta atual>, model: agent.model, cwd: agent.cwd, at: now, entries, previous:
  <conteúdo de handoff.last(cast), se houver> })`; se `entries` está vazio e não há `last`, **não grava arquivo** (conversa
  nova de verdade); (3) `handoff.put(cast, markdown)`; (4) dispensa a sessão atual pelo caminho de `dismiss` e
  **espera o job terminar**; (5) abre o mesmo personagem com `{ mode: 'new', model, room: agent.cwd }` (personagem:
  `start(cast, …)`; "Novo": `startNew({ model, room })`, e então `handoff.move(castAntigo, castNovo)`); (6) espera o
  job de abertura chegar a `done` (rejeita com o `error` se `failed`). Se (5) ou (6) falharem, o personagem fica
  fechado e o arquivo continua pendente para o `cast`: ao chamar de novo, o contexto vai junto. Uma troca por
  personagem de cada vez.
  O lançador recebe por opções `transcriptPathOf`, `terminalParser` e o `HandoffStore`.
- `registry.control`: quando o modelo pedido é de outra ferramenta (`isAgyModel(model) !== (agent.provider ===
  'antigravity')`) e o agente é do omp ou do Antigravity, cria a entrada de controle como hoje (para a resposta ter
  a mesma forma), marca `sent` e chama `opts.switchTool(agent, model)`; ao resolver, `delivered`; ao rejeitar,
  `failed` com a mensagem. Não vai para a fila da extensão do omp. Sem `opts.switchTool`, responde `unavailable`
  (`tr('troca de ferramenta indisponível neste Habblaud')`). `model` junto de outro campo (`thinking`, `command`…)
  numa troca de ferramenta → `InvalidRequest`.
- `server/index.ts`: cria o `HandoffStore` (`<dataDir>/handoff`), chama `sweep()` na subida, e o entrega ao
  lançador, ao `MessageRegistry` e à `AntigravityDelivery`; liga `switchTool`.
- Quando o agente novo aparece, uma linha no feed dele (`office.addActivity`, como as outras linhas especiais em
  `server/model/office.ts`): `tr('Ferramenta trocada: {0} → {1}. A conversa anterior vai anexada à sua próxima
  mensagem.', [de, para])`, ou, sem arquivo, `tr('Ferramenta trocada: {0} → {1}. Conversa nova.', [de, para])`.
- Testes em `server/cast/launcher.test.ts` e `server/messages/registry.test.ts` (acrescentar), com o `fake-agy.mjs`
  e o auxiliar do omp falso que os testes do lançador já usam:
  1. omp → `agy/fake-b`: a sessão do omp é dispensada, o personagem abre no Antigravity com esse modelo, o controle
     termina `delivered`, **nenhum `session/prompt` saiu**, `handoff: true` no agente.
  2. A primeira mensagem depois da troca chega ao falso com o `resource_link` e o arquivo existe e contém as falas
     da conversa do omp; a segunda não leva anexo; `handoff` some do agente.
  3. Antigravity → modelo do omp: o job do omp sai para o auxiliar com o modelo, e a primeira mensagem ao agente do
     omp leva o caminho no texto.
  4. Ida e volta sem mandar mensagem: o arquivo final contém as duas conversas (cumulativo).
  4b. **Ida → mensagem entregue → volta**: conversa no omp com a fala "ALFA"; troca para `agy/…`; uma mensagem é
      entregue (o anexo é consumido) e o agente responde; troca de volta para o omp. O arquivo final contém "ALFA"
      (a sessão original), a mensagem e a resposta do trecho no Antigravity, nessa ordem. O mesmo no sentido
      inverso (começando no Antigravity).
  4c. Depois de uma troca com mensagem entregue, a pessoa pede "conversa nova" e troca de ferramenta: o arquivo
      **não** contém a conversa anterior ao "conversa nova".
  4d. Troca, reinício do `HandoffStore` (objeto novo na mesma pasta), primeira mensagem: o anexo ainda é entregue.
  5. Agente trabalhando → recusado, nada fechado. Agente aberto no terminal (sem `cast`) → recusado.
  6. Destino falha ao abrir (modelo `agy/` desconhecido): controle `failed`, personagem fechado, arquivo ainda
     pendente; chamar o personagem de novo e mandar mensagem entrega o contexto.
  7. "Novo" (`novo-…`) troca de ferramenta e o contexto segue o id novo.
  8. Conversa vazia: troca sem arquivo e sem `handoff`.

**Verify**: `npx vitest run server/cast server/messages` passa; nenhum teste antigo editado
(`git diff producao -- server/cast/launcher.test.ts server/messages/registry.test.ts | grep -c '^-[^-]'` → `0`).

### Step 5: lista de modelos das duas ferramentas

- `server/cast/launcher.ts` (ou um módulo pequeno ao lado): guarda em memória a última lista `models` anunciada por
  um agente do omp (`rememberOmpModels(list)`, chamado de onde o snapshot é montado).
  `// shortcut: só em memória, some ao reiniciar o servidor; persistir quando isso atrapalhar.`
- `server/model/office.ts`, no laço do `castOpened`: para agente principal com `castOpened`: omp → `models = [...
  (a.models ?? []), ...agyModels]`; Antigravity → `models = [...(a.models ?? []), ...últimaListaDoOmp]`; e `handoff
  = true` quando `handoffStore.pending(a.cast)`. Sem Antigravity ligado, nada muda. Sem duplicatas.
- Testes: as duas direções; sem `castOpened`, lista intacta; Antigravity desligado, lista intacta.

**Verify**: `npx vitest run server/model` passa.

### Step 6: documentação

`CHANGELOG.md` (`## [Não lançado]`): "Trocar de modelo troca de ferramenta" — o que acontece, que a conversa
anterior vai anexada à próxima mensagem, os limites (só elenco, agente parado, saídas de ferramentas cortadas, 150
mil caracteres, arquivos apagados depois de 7 dias) e a frase da regra 5. `server/README.md`: o contrato e o formato
do arquivo. `README.pt-BR.md`/`README.md`: seção do Antigravity. Traduções.

**Verify**: `npm run typecheck && npm run i18n:check -- --strict && npm run build && npm test` → exit 0.

## Test plan

Listado nos passos. O essencial: a troca nunca manda turno sozinha; o contexto chega exatamente uma vez, na primeira
mensagem, nas duas direções; falha no meio não perde o contexto; ZeroClaw e o controle de modelo do omp não mudam.

## Done criteria

- [ ] `npm run typecheck`, `npm test`, `npm run i18n:check -- --strict`, `npm run build` com exit 0
- [ ] `git diff --stat producao -- server/test/antigravity-mecanica.test.ts server/sources/omp mod scripts client android zaplaud-rs server/sources/zeroclaw server/cast/zeroclaw.ts server/messages/zeroclaw.ts` vazio
- [ ] `git diff producao -- server/antigravity/acp.ts | grep -c "agyArgs"` → `0`
- [ ] Existe teste em que a troca acontece e o `fake-agy.mjs` não recebe `session/prompt` até a pessoa mandar
      mensagem
- [ ] `git status --short` limpo

## STOP conditions

- O 079 não está em `producao` (`shared/agy-model.ts` ausente).
- Um trecho de "Current state" não bate com o código.
- O texto da mensagem do omp não passar pelo ponto descrito no Step 2 (a extensão buscar o texto por outro caminho),
  de modo que anexar o caminho exija mexer em `mod/**` ou `server/sources/omp/**`.
- `TerminalEntry` não trouxer o texto das falas na íntegra (o parser corta em `TEXT_MAX` = 20 000 por fala: isso é
  aceito; se cortar muito menos, relate).
- Esperar o job de dispensa ou de abertura exigir mudar o protocolo `poll`/`ack` do auxiliar do omp.
- Qualquer passo parecer exigir um turno automático, uma flag do `agy` ou leitura de `~/.gemini`.

## Maintenance notes

- Fumaça do revisor com o `agy` real, poucos turnos e com autorização do dono: personagem do omp com alguma
  conversa → trocar para `agy/gemini-3.8-flash` → conferir que nenhum turno saiu → mandar "do que falávamos?" →
  o `agy` lê o arquivo e responde com o contexto; voltar para um modelo do omp e repetir.
- O `agy` leu o arquivo **sem pedir permissão** na versão 1.5.9, no modo padrão. Se uma versão futura passar a
  pedir, a pessoa verá um cartão de permissão para `read_file` desse arquivo: é só permitir. Não contorne.
- O contexto é texto: a ferramenta nova não herda arquivos abertos, memória de ferramentas nem o raciocínio. As
  saídas de ferramentas vão cortadas; o que importa de um arquivo precisa ser relido lá.
- Voltar para a ferramenta de origem abre conversa nova com o arquivo cumulativo; retomar a sessão nativa antiga
  (com cache e contexto completos) e anexar só o trecho do meio é uma melhoria possível, adiada.
- Quem revisar olha primeiro: que nenhum `session/prompt` sai na troca; a ordem dispensar → abrir e o que sobra
  quando a abertura falha; que a resposta de `/api/messages/control` não mudou de forma.
