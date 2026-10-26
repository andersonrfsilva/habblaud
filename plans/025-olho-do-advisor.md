# Plan 025: Botão de olho em pixel art liga e desliga o advisor do omp na faixa do terminal

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 75fa2b0..HEAD -- shared/omp-events.ts client/src/ui/omp-bar.ts client/src/ui/omp-bar.test.ts client/src/ui/styles.css shared/locales server/test/omp-extension.test.ts mod/habblaud-omp/habblaud.ts server/messages/registry.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `75fa2b0`, 2026-10-26

## Why this matters

O dono quer ligar e desligar o advisor do omp (o revisor que comenta o trabalho do agente) sem ir até o TUI. O omp tem
`/advisor on` e `/advisor off`: valem só para a sessão atual e não mudam o `advisor.enabled` gravado no config. A faixa
do terminal do Habblaud já digita comandos no omp (`/new`, `/shake`) pela extensão; basta mais um botão, com um olho em
pixel art (aberto = ligado, fechado = desligado), no estilo dos ícones do Habblaud.

Limite conhecido e aceito: a API de extensões do omp (18.12.6) **não** expõe se o advisor está ligado
(`isAdvisorEnabled()` existe só em `AgentSession`, que a extensão não recebe). Por isso o olho mostra o último comando
**confirmado** para aquele agente, guardado em memória no cliente. Antes do primeiro clique o estado é desconhecido.

## Current state

- `shared/omp-events.ts:28-29` — lista de comandos que o servidor aceita e que a extensão digita:
  ```ts
  export const OMP_COMMANDS = { new: ['/new'], shake: ['/shake elide', '/shake thinking'] } as const;
  export type OmpCommand = keyof typeof OMP_COMMANDS;
  ```
  O servidor valida com `Object.hasOwn(OMP_COMMANDS, r.command)` (`server/messages/registry.ts:209`) e a extensão
  digita `OMP_COMMANDS[control.command]` (`mod/habblaud-omp/habblaud.ts:865`). **Nenhum dos dois precisa mudar.**
- `client/src/ui/omp-bar.ts` — classe `OmpBar`, a faixa do omp no rodapé do terminal:
  - `BUTTONS` (linhas 15-18): pares `[OmpCommand, dica]` que viram botões `/new` e `/shake` no construtor (linha 64).
    Com novos comandos no `OMP_COMMANDS`, o tipo `OmpCommand` cresce mas `BUTTONS` só lista o que tiver botão de texto.
  - `run(command)` (linhas 122-125) e `send(change, label)` (linhas 128-167). `send` hoje devolve `Promise<void>`; ele
    só sabe que deu certo na linha 157 (`m.status === 'delivered'` ou `!m`), onde chama
    `this.say(tr('{0} enviado ao omp.', [label]))`.
  - `render(agent, ready)` (linhas 78-99): desabilita todos os `button` de `this.buttons` com `this.busy` (linha 96).
  - `this.agentId` é o agente atual; muda em `render` (linhas 80-83).
- `client/src/ui/icons.ts:27-31` — `pixelIcon(rows, palette?, cls?)` gera um SVG `crispEdges` a partir de linhas de
  texto (`'#'` = pixel em `currentColor`, `'.'` = vazio), 2 px de tela por pixel. Exemplo de uso: `ICONS.overview`.
  Use este helper; não escreva SVG à mão.
- `client/src/ui/styles.css:5175-5182` — `.ui-term__omp` (flex). Os botões usam as classes
  `ui-btn ui-btn--sm ui-term__cmd`. O estilo `.ui-term__cmd` está nas linhas seguintes do mesmo bloco.
- `client/src/ui/omp-bar.test.ts` — testa só a função pura `contextView`. Modelo para testes de função pura.
- `server/test/omp-extension.test.ts:392-410` — teste do `/shake` pela extensão (inbox com `control: { command:
  'shake' }`, confere o que foi digitado). Modelo para o teste do `/advisor on`.
- i18n: todo texto da UI passa por `tr('texto em pt-BR')` e cada texto novo precisa de entrada em
  `shared/locales/en.ts`, `es.ts` e `fr.ts` (chave = texto pt-BR exato). `shared/i18n.test.ts` confere isso.
- Comentários e textos do repositório são em português; commits no estilo `area: descrição` em minúsculas
  (ex.: `terminal: botões STOP, /new, /shake e /resume no mesmo estilo discreto do rodapé`).

## Commands you will need

| Purpose   | Command                                        | Expected on success |
|-----------|------------------------------------------------|---------------------|
| Install   | `npm ci`                                       | exit 0              |
| Typecheck | `npm run typecheck`                            | exit 0              |
| Tests     | `npx vitest run client/src/ui/omp-bar.test.ts server/test/omp-extension.test.ts shared/i18n.test.ts` | all pass |
| All tests | `npm test`                                     | all pass            |
| Build     | `npm run build:client`                         | exit 0              |

## Scope

**In scope** (the only files you should modify):
- `shared/omp-events.ts`
- `client/src/ui/omp-bar.ts`
- `client/src/ui/omp-bar.test.ts`
- `client/src/ui/styles.css` (só se precisar de regra nova para o ícone)
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`
- `server/test/omp-extension.test.ts`

**Out of scope**:
- `mod/habblaud-omp/habblaud.ts` e `server/messages/registry.ts` — já aceitam qualquer chave de `OMP_COMMANDS`.
- Mostrar o estado real do advisor vindo do omp (heartbeat, `AgentInfo`) — a extensão não tem como ler; não invente.
- Guardar o estado em `localStorage` ou no servidor.
- Deploy, imagens e pods.

## Git workflow

- Branch: `advisor/025-olho-do-advisor`
- Um commit: `terminal: olho em pixel art liga e desliga o advisor do omp`
- Não faça push nem PR.

## Steps

### Step 1: comandos no `OMP_COMMANDS`

Em `shared/omp-events.ts:28`, acrescente `advisorOn: ['/advisor on']` e `advisorOff: ['/advisor off']`.

**Verify**: `npm run typecheck` → exit 0.

### Step 2: teste da extensão

Em `server/test/omp-extension.test.ts`, ao lado do teste do `/shake` (linha ~392), copie a estrutura dele para um
teste `'/advisor: digita /advisor on'`: inbox com `control: { command: 'advisorOn' }`, espera `typed` igual a
`['/advisor on']` e ack `{ ok: true }`. Siga exatamente os helpers que o teste do `/shake` usa (`setup`, `inbox`,
`onEnter`).

**Verify**: `npx vitest run server/test/omp-extension.test.ts` → all pass.

### Step 3: ícones e estado puro

Em `client/src/ui/omp-bar.ts`:
1. Importe `pixelIcon` de `./icons`.
2. Defina dois ícones de olho com `pixelIcon`, mesma largura e altura (por exemplo 12×8): `EYE_OPEN` (contorno de
   olho com pupila) e `EYE_SHUT` (só a linha das pálpebras fechadas com cílios). Use apenas `'#'` e `'.'`.
3. Exporte uma função pura que decide o próximo comando a partir do estado conhecido:
   ```ts
   /** Advisor do omp: ligado (true), desligado (false) ou ainda não sabido (undefined; a extensão não lê o estado). */
   export function advisorNext(on: boolean | undefined): 'advisorOn' | 'advisorOff' {
     return on ? 'advisorOff' : 'advisorOn';
   }
   ```
   Estado desconhecido liga: é o que o usuário quer ao clicar num olho que não está aberto.

**Verify**: `npm run typecheck` → exit 0.

### Step 4: botão na faixa

Em `client/src/ui/omp-bar.ts`:
1. Campo `private advisor = new Map<string, boolean>();` (estado confirmado por agente) e `private eyeBtn: HTMLButtonElement;`.
2. No construtor, crie `eyeBtn` com classes `ui-btn ui-btn--sm ui-term__cmd`, `type: 'button'`, e coloque-o em
   `this.buttons` logo depois do `stopBtn`. O conteúdo é o SVG (atribua `innerHTML` com o ícone, como os outros usos de
   `ICONS` no cliente; confira com `grep -rn "ICONS\." client/src/ui | head`). Clique: `void this.toggleAdvisor()`.
3. `send` passa a devolver `Promise<boolean>`: `true` só no caminho da linha 157 (entregue); `false` em todo o resto
   (inclusive o `return` antecipado da linha 130). Os chamadores atuais usam `void this.send(...)` e continuam
   funcionando.
4. Novo método:
   ```ts
   private async toggleAdvisor(): Promise<void> {
     const id = this.agentId;
     if (!id) return;
     const next = advisorNext(this.advisor.get(id));
     if (await this.send({ command: next }, next === 'advisorOn' ? '/advisor on' : '/advisor off')) this.advisor.set(id, next === 'advisorOn');
     this.ctx.invalidate();
   }
   ```
5. Em `render`, depois da linha 96, atualize o olho para o agente atual: `const on = this.agentId ? this.advisor.get(this.agentId) : undefined;`
   - ícone: `EYE_OPEN` se `on === true`, senão `EYE_SHUT` (só troque `innerHTML` quando mudar, para não refazer o DOM a cada render);
   - `aria-pressed`: `'true'`, `'false'` ou sem atributo quando `undefined` (use `setAttr`; confira a assinatura em `./dom`);
   - `title` via `setTitle` com `tr(...)`:
     - ligado: `'Advisor do omp ligado nesta sessão. Clique para desligar (/advisor off)'`
     - desligado: `'Advisor do omp desligado nesta sessão. Clique para ligar (/advisor on)'`
     - desconhecido: `'Liga o advisor do omp nesta sessão (/advisor on). O Habblaud não vê se ele já está ligado; o olho mostra o último comando enviado daqui'`
   - `aria-label` fixo: `tr('Advisor do omp')`.
6. Atualize o comentário do topo do arquivo (linhas 1-5) para citar o olho do advisor.

**Verify**: `npm run typecheck` → exit 0; `npm run build:client` → exit 0.

### Step 5: traduções

Adicione as 4 strings novas (3 títulos + `'Advisor do omp'`) em `shared/locales/en.ts`, `es.ts` e `fr.ts`, no mesmo
formato das entradas vizinhas.

**Verify**: `npx vitest run shared/i18n.test.ts` → all pass.

### Step 6: teste da função pura

Em `client/src/ui/omp-bar.test.ts`, teste `advisorNext`: `undefined` → `'advisorOn'`, `false` → `'advisorOn'`,
`true` → `'advisorOff'`.

**Verify**: `npx vitest run client/src/ui/omp-bar.test.ts` → all pass.

## Test plan

- `server/test/omp-extension.test.ts`: `/advisor on` digitado pela extensão (step 2).
- `client/src/ui/omp-bar.test.ts`: `advisorNext` (step 6).
- `npm test` inteiro passa.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 2 testes novos
- [ ] `npm run build:client` exits 0
- [ ] `grep -n "advisorOn" shared/omp-events.ts` acha a entrada
- [ ] `git diff --name-only 75fa2b0` lista só arquivos do Scope (mais `plans/README.md`)

## STOP conditions

- O teste do `/shake` em `server/test/omp-extension.test.ts` não existe ou não usa `setup`/`inbox` como descrito.
- O servidor ou a extensão recusam `advisorOn` mesmo com a chave no `OMP_COMMANDS` (precisaria mexer em arquivo fora do escopo).
- `shared/i18n.test.ts` exige algo além das três traduções.

## Maintenance notes

- Se uma versão nova do omp expuser o estado do advisor às extensões, troque o `Map` do cliente por um campo vindo do
  heartbeat (`AgentInfo`) e apague o título "não vê se ele já está ligado".
- O estado se perde ao recarregar a página e não acompanha um `/advisor` digitado direto no TUI. Isso foi aceito.
- Para chegar aos usuários é preciso uma imagem nova do Habblaud **e** reinstalar a extensão `mod/habblaud-omp`
  (ela embute `OMP_COMMANDS`); com a extensão antiga o botão recebe "comando desconhecido".
