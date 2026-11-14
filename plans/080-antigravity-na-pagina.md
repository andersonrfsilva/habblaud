# Plan 080: Na página, os modelos `agy/…` aparecem onde já se escolhe modelo; trocar de ferramenta avisa antes

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat ba5a285..HEAD -- client/src/ui/cast-screen.ts client/src/ui/provider.ts client/src/ui/drawer.ts client/src/ui/usage.ts client/src/ui/spawn.ts`
> As mudanças dos planos 079 e 083 (servidor e `shared/`) são esperadas e não tocam nesses arquivos. Se algum deles
> mudou, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW
- **Depends on**: plans/079-elenco-com-antigravity.md e plans/083-troca-de-ferramenta-com-contexto.md (mesclados)
- **Category**: direction
- **Planned at**: commit `ba5a285`, 2026-11-14 (reescrito no mesmo dia: saiu o campo "Ferramenta"; o modelo decide)

## Why this matters

O dono abriu o Habblaud e "não encontrou nenhum lugar para usar o Antigravity". Ele pediu que seja transparente:
**trocar o modelo** e pronto. Com os planos 079 e 083 o servidor trata modelos `agy/…` como "roda no Antigravity
CLI", troca a ferramenta de um agente aberto e anexa a conversa anterior à próxima mensagem. Falta a página: os
modelos `agy/…` precisam aparecer no editor de personagem e na gaveta do agente, a troca de ferramenta precisa de
um aviso (ela abre conversa nova na outra ferramenta), e a pessoa precisa ver que o contexto está pendente. De
quebra, o cartão de uso da conta Antigravity perde o botão "Como ativar", que não leva a nada útil.

**Validação visual é do dono.** Este plano termina em typecheck, testes de unidade e build. Não abra navegador,
emulador, Xvfb nem máquina virtual; não escreva teste que precise de um.

## Current state

Baseline em `ba5a285`: `npm run typecheck` ok; `npm test` 2857 passando.

### O contrato do servidor (planos 079 e 083)

- Modelo cujo id começa com `agy/` roda no Antigravity; `agy/default` = padrão do `agy`. Funções puras em
  `shared/agy-model.ts`: `isAgyModel`, `AGY_DEFAULT`. `google-antigravity/…` é modelo do omp e **não** é `agy/`.
- Personagem com `model: 'agy/…'`: só `kind: 'main'`; o servidor recusa `agent` e `account` junto.
- `GET /api/cast/models` traz, com o Antigravity ligado, `agyModels: ['agy/default', 'agy/gemini-4-argon', …]`
  (só `agy/default` antes da primeira sessão do Antigravity).
- Agente aberto pelo elenco (`castOpened`): `models` traz os modelos das **duas** ferramentas; agente do Antigravity
  tem `provider: 'antigravity'` e `model: 'agy/<valor>'`.
- `POST /api/messages/control {agentId, model}`: mesma resposta de hoje. Modelo da mesma ferramenta: troca na
  conversa. Modelo da outra ferramenta: **troca de ferramenta** (dispensa a sessão, abre o mesmo personagem na outra,
  conversa nova). Só com o agente parado e aberto pelo elenco; senão a mensagem de controle volta `unavailable`/
  `failed` com o motivo em texto.
- Depois de uma troca, o agente novo tem `handoff: true` até a pessoa mandar a primeira mensagem (a conversa
  anterior vai anexada a ela).
- `thinking` para agente do Antigravity: recusado.

### A página hoje

- `client/src/ui/provider.ts:12-24`: `runtimeAllowsMessages`, `runtimeAllowsControl` (omp, zeroclaw, antigravity),
  `runtimeAllowsDeliveryModes` (omp, zeroclaw), `runtimeAllowsOmpTools`. `PROVIDER_NAME.antigravity` e
  `isAntigravity` existem (plano 078). `parseCastModelCatalog` (linhas 44-58) lê `/api/cast/models`.
- `client/src/ui/drawer.ts:826-848`, seletores de modelo e raciocínio da gaveta: aparecem quando `editable`; as
  opções vêm de `a.models` e o atual de `models.find((m) => m === a.model || m.replace(/^[^/]+\//, '') === a.model)`;
  `modelGroups(models, current)` agrupa pelo prefixo antes da `/`. A troca chama `store` →
  `POST /api/messages/control` (`client/src/net/store.ts:389-394`).
- `client/src/ui/cast-screen.ts`: o editor de personagem. Numa instalação do omp, o campo Modelo é um seletor
  agrupado montado de `this.knownModels` (linhas 34-36: modelos anunciados por agentes do omp abertos; linhas
  224-236: `h('option', { text: tr('Padrão do omp'), attrs: { value: '' } })` + grupos), ou texto livre quando não há
  lista. Há os campos "Conta" (só omp) e `kind`. Ao salvar monta `body: Omit<CastMember, 'id'>`.
- `client/src/ui/usage.ts:597-606`: sem números o cartão mostra a mensagem e o botão `r.how` ("Como ativar");
  `setHidden(r.how, state === 'noquota' || omp);`.
- `client/src/ui/spawn.ts`: popover "Novo agente" com o seletor "Ferramenta" (Claude Code / Antigravity) do 078.
- Convenções: texto visível em `tr('texto em PT')` com tradução em `shared/locales/{en,es,fr}.ts`
  (`npm run i18n:check -- --strict`); nomes de ferramenta por `PROVIDER_NAME`/`unbrand()`; no modo sem marca
  (`isNeutralBrand()`) nada cita "Antigravity"; parte pura e testável em `provider.ts`/`*-model.ts`, com `*.test.ts`
  ao lado (Vitest, sem navegador); `CHANGELOG.md` em `## [Não lançado]`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Instalar (worktree nova) | `npm ci` | exit 0 |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes da página | `npx vitest run client/src/ui` | todos passam |
| Suíte | `npm test` | exit 0 |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**Editar**: `client/src/ui/provider.ts` (+ `provider-antigravity.test.ts`), `client/src/ui/cast-screen.ts`,
`client/src/ui/drawer.ts`, `client/src/ui/usage.ts`, `client/src/ui/spawn.ts` (só o padrão do seletor),
`shared/locales/{en,es,fr}.ts`, `CHANGELOG.md`, `README.pt-BR.md`, `README.md`.

**Out of scope** (NÃO toque):
- `server/**`, `shared/types.ts`, `shared/agy-model.ts`: o contrato é dos planos 079 e 083. Faltou algo: STOP.
- `android/**`, `zaplaud-rs/**`: planos 081 e 082.
- Um campo "Ferramenta" no personagem, um botão "Novo no Antigravity": **não existem** neste desenho.
- Qualquer teste que abra navegador, Playwright, Xvfb ou emulador. `scripts/shot.mjs` e afins não rodam.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-080 -b advisor/080-antigravity-pagina producao`.
- Um commit por passo, estilo `feat(antigravity): modelos agy no editor de personagem`.
- Não faça push nem abra PR.

## Steps

### Step 1: partes puras

Em `client/src/ui/provider.ts`:

- `CastModelCatalog` ganha `agyModels: string[]` (vazio quando o campo não vem); `parseCastModelCatalog` aceita só
  textos que passem em `isAgyModel` e ignora o resto, sem invalidar o catálogo.
- `runtimeAllowsModelChoice(provider)`: `omp` e `antigravity`. `runtimeAllowsThinking(provider)`: só `omp`.
- `crossesTool(agentProvider, model)`: verdadeiro quando `isAgyModel(model) !== (agentProvider === 'antigravity')` e
  o agente é `omp` ou `antigravity`.
- `agyGroupLabel()`: `PROVIDER_NAME.antigravity` (no modo sem marca, o texto neutro que `unbrand` já dá), para o
  grupo `agy` em `modelGroups`. `agy/default` aparece como `tr('Padrão do Antigravity')`.
- Testes em `provider-antigravity.test.ts`: catálogo com e sem `agyModels`; item inválido ignorado
  (`google-antigravity/x` não entra); `crossesTool` nas quatro combinações e falso para `claude`/`codex`/`zeroclaw`;
  escolha de modelo liberada para o Antigravity e raciocínio não.

**Verify**: `npx vitest run client/src/ui/provider` passa.

### Step 2: editor de personagem

Em `client/src/ui/cast-screen.ts`, numa instalação do omp (`this.catalog.provider === 'omp'`) e fora do modo sem
marca:

- O seletor de Modelo passa a incluir os `catalog.agyModels`, no grupo `agyGroupLabel()`, **mesmo quando
  `knownModels` está vazio** (hoje, sem lista, o campo vira texto livre: com `agyModels` presente ele é seletor, com
  o modelo atual do personagem como opção extra se não estiver na lista, para nunca ser perdido ao salvar).
- Com um modelo `agy/…` escolhido: `kind` trava em "Principal" e o campo "Conta" some (mesmo mecanismo que já
  esconde campos quando há "Agente fixo": `modelField.hidden = instructionsField.hidden = bound;`), e uma linha de
  ajuda aparece sob o seletor: `tr('Este personagem roda no Antigravity (agy). As instruções vão junto da primeira
  mensagem.')`.
- Ao salvar com modelo `agy/…`, o corpo **não** leva `account` nem `agent`.
- Extraia para função pura exportada a montagem do corpo (`castBody(...)`) e teste: modelo `agy/…` sem `account`;
  modelo do omp com `account`; `agy/default`.
- Na lista do elenco: para personagem com modelo `agy/…`, o botão de chamar não depende do auxiliar do omp
  (`HOST_OFF()` não o desabilita) e o texto de espera é `tr('Chamando…')` em vez de `'Abrindo o omp no host…'`; o
  `confirm` de dispensar usa o texto neutro (sem "A sessão do omp fecha…"). Use o `provider` do agente aberto, quando
  houver, e o modelo do personagem, quando não.

**Verify**: `npx vitest run client/src/ui` passa; `npm run typecheck` exit 0.

### Step 3: gaveta do agente

Em `client/src/ui/drawer.ts`:

- O seletor de **modelo** aparece para agentes do omp e do Antigravity (`runtimeAllowsModelChoice`); o de
  **raciocínio**, só para o omp (`runtimeAllowsThinking`). Hoje os dois andam juntos no laço das linhas 826-829:
  separe.
- Ao escolher um modelo com `crossesTool(a.provider, modelo)`: antes de enviar, `confirm(tr('Trocar para {0} muda de
  ferramenta: a conversa atual fecha e abre outra. A conversa anterior vai anexada à sua próxima mensagem.
  Continuar?', [rótulo do modelo]))`. Cancelou: o seletor volta ao valor atual e nada é enviado. Confirmou: o mesmo
  `POST /api/messages/control {agentId, model}` de hoje. Se o agente não tem `castOpened`, a opção da outra
  ferramenta nem aparece (o servidor já não a manda; não acrescente por conta própria).
- O erro devolvido pelo controle (agente trabalhando, destino indisponível…) aparece onde os erros de controle já
  aparecem na gaveta. Não invente um aviso novo.
- Com `a.handoff === true`, uma linha discreta acima da caixa de mensagem: `tr('A conversa anterior vai anexada à
  sua próxima mensagem.')`. Some quando `handoff` some.
- Extraia o que for decisão para função pura (por exemplo `modelChange(agent, model)` → `{ confirm: boolean }`) e
  teste junto do Step 1.

**Verify**: `npm run typecheck` exit 0; `npx vitest run client/src/ui` passa.

### Step 4: cartão de uso e padrão do "Novo agente"

- `client/src/ui/usage.ts`: para conta `antigravity`, sem números, a mensagem é `tr('o Antigravity não informa
  cota')` e o botão "Como ativar" fica escondido (`setHidden(r.how, state === 'noquota' || omp || antigravity)`); a
  dica (`title`): `tr('O agy não entrega cota nem uso do plano ao Habblaud; o contexto de cada conversa aparece na
  gaveta do agente.')`. Se o arquivo tem função pura para a mensagem, acrescente o caso e o teste.
- `client/src/ui/spawn.ts`: quando o "Novo agente" do Claude Code está indisponível (resposta não-ok de
  `GET /api/spawn` ou `available: false`) e o Antigravity está disponível, o seletor "Ferramenta" abre em
  **Antigravity**. Com os dois disponíveis, continua em Claude Code.

**Verify**: `npx vitest run client/src/ui` passa.

### Step 5: textos e documentação

Traduções de todo `tr()` novo. `CHANGELOG.md` (`## [Não lançado]`): modelos `agy/…` no editor e na gaveta, o aviso
da troca de ferramenta, a linha do contexto pendente, o cartão de uso. `README.pt-BR.md`/`README.md`, seção do
Antigravity: "para usar, escolha um modelo `agy/…`: no personagem (Elenco → editar) ou na gaveta de um agente
aberto", e que pelo acesso remoto isso funciona com `terminal` e `messages` em `HABBLAUD_REMOTE_ALLOW`.

**Verify**: `npm run i18n:check -- --strict && npm run typecheck && npm test && npm run build` → exit 0.

## Test plan

Só testes de unidade das partes puras. Nenhum teste de navegador. Roteiro para o dono conferir à mão (escreva-o no
corpo do último commit): editar um personagem e escolher `agy/…` (Conta some, tipo trava); chamar e mandar mensagem;
na gaveta de um agente do omp, escolher um modelo `agy/…` → aviso → o personagem reabre no Antigravity com a linha
"a conversa anterior vai anexada…"; mandar "do que falávamos?"; trocar de volta para um modelo do omp; trocar de
modelo dentro do Antigravity sem aviso e sem perder a conversa; cartão de uso sem botão.

## Done criteria

- [ ] `npm run typecheck`, `npm test`, `npm run i18n:check -- --strict`, `npm run build` com exit 0
- [ ] `git diff --stat producao -- server shared/types.ts shared/agy-model.ts android zaplaud-rs` vazio
- [ ] `git diff producao --name-only | grep -cE "playwright|shot\.mjs|\.spec\."` → `0`
- [ ] Existem testes de `crossesTool` e de `castBody` com modelo `agy/…`
- [ ] `git status --short` limpo

## STOP conditions

- `shared/agy-model.ts` não existe, ou `/api/cast/models` não traz `agyModels`, ou `AgentInfo.handoff` não existe
  (079/083 fora de `producao`): pare; não simule o contrato no cliente.
- Um trecho de "Current state" não bate com o código.
- Separar modelo de raciocínio na gaveta exigir reescrever o componente além das linhas dos dois seletores.
- Você se vir prestes a abrir um navegador, Playwright, Xvfb ou emulador para "conferir a tela".

## Maintenance notes

- A lista de modelos da gaveta vem pronta do servidor; a página só decide **avisar** antes de cruzar de ferramenta.
- `agy/default` existe para a primeira vez (o catálogo só enche depois de uma sessão do Antigravity).
- Quem revisar olha: o corpo salvo (nunca `account`/`agent` com `agy/…`), o modo sem marca, e que cancelar o aviso
  não envia nada.
