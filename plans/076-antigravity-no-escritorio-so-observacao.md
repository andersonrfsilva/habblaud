# Plan 076: Agentes do Antigravity CLI (`agy`) aparecem no escritório, só por observação

> **REJECTED em 2026-11-14. Não execute.** O dono escolheu integrar o `agy` só pelo ACP: ver
> `plans/077-spike-agy-pelo-acp.md`. A regra 1 abaixo ("o Habblaud nunca executa o `agy`") não vale mais: abrir o
> binário oficial pela interface ACP documentada é o caminho escolhido. Este arquivo fica como registro.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md`.
>
> **Drift check (rode primeiro)**:
> `git diff --stat d7f9e4c..HEAD -- shared/types.ts server/config.ts server/index.ts server/http/app.ts client/src/ui/provider.ts client/src/ui/widgets.ts client/src/ui/composer-model.ts client/src/ui/styles.css package.json`
> Se algum desses arquivos mudou, compare os trechos de "Current state" com o código antes de seguir; divergência é
> STOP.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (o código é pequeno; o risco é de conta: ver "Regras de mecânica")
- **Depends on**: plans/075-spike-contrato-dos-hooks-do-agy.md (precisa de `plans/075-resultado.md`)
- **Category**: direction
- **Planned at**: commit `d7f9e4c`, 2026-11-14

## Why this matters

O dono passou a usar o Antigravity CLI (`agy`, do Google) ao lado do Claude Code, do Codex e do omp, e quer ver essas
sessões no escritório. O upstream tem isso pronto no PR #80 (`marmottajr/habblaud`, autor `lpalvess`), mas o PR
depende da pilha do OpenCode e não entra limpo neste fork (15 arquivos em conflito). Este plano porta **só os commits
próprios do #80**: os arquivos novos entram como estão; a ligação nos arquivos compartilhados é refeita à mão, no
padrão deste fork. Aprovar pedidos, mandar mensagens, cota, terminal e histórico ficam **fora**.

## Regras de mecânica (não negociáveis)

O dono não quer correr risco de bloqueio da conta Google. Os Termos do Antigravity (<https://antigravity.google/terms>, item 6):
"Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity OAuth) is
a breach of this Agreement. Such actions may be grounds for suspension or termination". A integração só é aceitável
porque o Habblaud **não acessa o serviço**: o `agy` oficial, aberto por uma pessoa, é o único que fala com o Google, e
ele avisa o Habblaud pelo recurso documentado de hooks (<https://antigravity.google/docs/hooks>). O objetivo não é
esconder nada: é não fazer nada que seja acesso por terceiro.

1. **O Habblaud nunca executa o `agy`.** Nada de `spawn`, pty, `agy -p`, "Novo agente", "Assumir", "Encerrar",
   elenco, fila de mensagens. Nenhum arquivo deste plano pode conter chamada que rode o binário.
2. **O Habblaud nunca lê credenciais nem conversas do `agy`.** De `~/.gemini/` só se toca em
   `~/.gemini/config/hooks.json`, e só na chave `habblaud`. Nunca `settings.json`, chaveiro, token, `brain/`,
   `transcript.jsonl`. O hook recebe `transcriptPath` e `lastUserInput` no stdin e **descarta os dois**.
3. **O hook só observa.** Não devolve `decision` de aprovar ou recusar, não devolve `injectSteps`, não devolve
   `"decision":"continue"` no `Stop`. Sai sempre com código 0. A única saída permitida é a que o
   `plans/075-resultado.md` (resposta 4) registrou como neutra.
4. **O hook só fala com `127.0.0.1`.** Nenhum código deste plano abre conexão com domínio do Google.
5. **Sem `agy` nos tenants do SaaS.** Lá a conta seria a do dono usada por clientes. O plano 054 do repositório
   `omp-tenant` desliga a rota neles.
6. **Sem disfarce.** O hook se chama `habblaud`, aparece no `/hooks` do `agy`, e a documentação diz o que ele manda.

## Current state

Baseline medido em `d7f9e4c`: `npm run typecheck` ok; `npm test` 204 arquivos, 2806 testes passando, 7 pulados.

**Origem do código** (upstream, PR #80): cabeça `384f423dbc2baa814d5ab6f3910caa509d7b3038`; base da pilha (PR #79)
`1cd2a65f92f682e1fec04b28f1ad40e86b7efb33`. Os 11 commits próprios são `1cd2a65..384f423`. Nenhum dos 13 arquivos
novos cita OpenCode (conferido com `grep -c -i opencode`).

**Este fork** difere do upstream:

- `shared/types.ts:83`:
  ```ts
  export type Provider = 'claude' | 'codex' | 'omp' | 'zeroclaw';
  ```
- `server/config.ts:84-89` (interface) e `:334-336` (objeto devolvido por `loadConfig`):
  ```ts
  /** Observa as sessões do Codex (sources/codex/); HABBLAUD_CODEX=0 desliga. */
  codex: boolean;
  ...
  /** Observa as sessões do omp (sources/omp/); HABBLAUD_OMP=0 desliga. */
  omp: boolean;
  ```
  ```ts
  codex: !env.HABBLAUD_CODEX?.trim() || isTruthy(env.HABBLAUD_CODEX),
  ```
- `server/index.ts:198-211`: as fontes entram em `agents` (um `SourceSet`), uma por ferramenta:
  ```ts
  const agents = new SourceSet([claude]);
  ...
  if (codex) agents.add(codex);
  const codexLive: CodexLive | undefined = codex;
  ...
  if (omp) agents.add(omp);
  const ompLive: OmpLive | undefined = omp;
  ```
  e `createApiHandler({ ... inDocker: config.inDocker, ... codexLive, ... })` (linhas 488 e 511).
- `server/http/app.ts`: `ApiDeps` tem `codexLive?: CodexLive` (linha 83) e `ompLive?: OmpLive` (linha 90);
  `/api/health` devolve `codexEvents: !!deps.codexLive, ompEvents: !!deps.ompLive` (linhas 464-465). A rota de eventos
  do Codex (linhas 628-636) é o **modelo de trava** a copiar:
  ```ts
  if (path === '/api/codex/events') {
    if (method !== 'POST') methodNotAllowed(res, 'POST');
    else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
      sendJson(res, 403, { error: denied(req, tr('eventos do Codex só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
    } else handleCodexEvent(req, res, { live: deps.codexLive, entries: () => accounts.entriesOf('codex') }).catch((err) => fail(res, err));
    return true;
  }
  ```
  `trusted(req)` sem segundo argumento devolve `false` para qualquer pedido da porta remota (linhas 318-319): é isso
  que impede a rota de valer pelo acesso remoto. O PR #80 usa `isLoopbackHost(req.headers.host)` direto; **não copie
  essa forma**, use `trusted(req)` e `denied(req, ...)` como acima.
- `client/src/ui/provider.ts`:
  - linha 12-14: `runtimeAllowsMessages` lista `claude`, `codex`, `omp`, `zeroclaw`. **Não acrescente
    `antigravity`**, nem em `runtimeAllowsControl` / `runtimeAllowsDeliveryModes`.
  - linha 73: `PROVIDER_NAME: Record<Provider, string> = { claude: unbrand('Claude Code'), codex: unbrand('Codex'), omp: unbrand('omp'), zeroclaw: unbrand('ZeroClaw') }`.
    Todo nome passa por `unbrand()` (modo sem marca).
  - linha 102-106, `accountProvider`: `return looksLikeCodexId(fallbackId) ? 'codex' : looksLikeOmpId(fallbackId) ? 'omp' : 'claude';`
  - linha 113-121, `fallbackShort`: o regex do prefixo é `/^\.?(?:claude|codex|omp)(?=[-_.]|$)[-_.]?/i` e o fim é
    `return provider === 'codex' ? 'X' : provider === 'omp' ? 'O' : '?';`
  - `emptyOfficeHint` (linha 141) já filtra `providerOf(a) === 'claude'`: não precisa mudar.
- `client/src/ui/widgets.ts:25`: `setAttr(chip, 'data-provider', p === 'claude' ? null : p);` (já serve para qualquer
  ferramenta). Linha 61, título do selo:
  `setTitle(tag, provider === 'zeroclaw' ? tr('Agente do ZeroClaw') : provider === 'omp' ? tr('Agente do omp (oh-my-pi)') : tr('Agente do Codex (OpenAI)'));`
- `client/src/ui/styles.css:344` e `:364`: o chip vazado do Codex, seletores `.ui-acc-chip[data-provider='codex']` e
  `.ui-acc-chip--lg[data-provider='codex']`.
- `client/src/ui/composer-model.ts:36-40`: `composerMode` devolve `off` cedo para timelapse, subagente e sessão
  encerrada, e depois testa `runtimeAllowsMessages`.
- `server/accounts/detect.ts:224`: `ALIAS_TOOLS` é `Partial<Record<Provider, ...>>` neste fork. **Não mexa**: o
  trecho do PR para esse arquivo não é necessário aqui.
- `package.json:44-46`: os scripts `omp:install`, `omp:uninstall`, `omp:status`; os novos entram logo depois.
- `scripts/statusline-install.ts:247` exporta `tildify`, que o instalador do PR importa.

**Convenções** (`AGENTS.md`, `CONTRIBUTING.md`): todo texto visível em português dentro de `tr('...')`, com tradução
em `shared/locales/{en,es,fr}.ts` (`npm run i18n:check -- --strict` falha se faltar ou sobrar); toda mudança visível
entra em `## [Não lançado]` do `CHANGELOG.md` (linha 11); commits no estilo `feat(zeroclaw): ...`, em português.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Buscar o PR | `git fetch upstream pull/80/head` | exit 0 |
| Conferir o commit | `git cat-file -e 384f423dbc2baa814d5ab6f3910caa509d7b3038^{commit} && echo ok` | `ok` |
| Typecheck | `npm run typecheck` | exit 0 |
| Testes do recorte | `npx vitest run antigravity` | todos passam |
| Suíte | `npm test` | exit 0, ≥ 2806 passando |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**In scope**:
- Criar, copiados do commit `384f423`: `mod/habblaud-antigravity/hook.mjs`, `scripts/antigravity-install.ts`,
  `server/antigravity/http.ts`, `server/sources/antigravity/{activity,live,source}.ts`,
  `server/sources/antigravity/{activity,source}.test.ts`, `server/http/antigravity-route.test.ts`,
  `server/test/antigravity-{config,hook,install}.test.ts`, `client/src/ui/provider-antigravity.test.ts`
- Editar: `shared/types.ts`, `server/config.ts`, `server/index.ts`, `server/http/app.ts`,
  `client/src/ui/provider.ts`, `client/src/ui/widgets.ts`, `client/src/ui/composer-model.ts`,
  `client/src/ui/styles.css`, `shared/locales/{en,es,fr}.ts`, `package.json`, `CHANGELOG.md`, `README.pt-BR.md`,
  `README.md`, `server/README.md`, `docs/zaplaud-paridade.md`, `plans/README.md`
- Criar: `server/test/antigravity-mecanica.test.ts`

**Out of scope** (NÃO toque):
- Os PRs #81 (aprovar pelo escritório) e #82 (cota pela statusline) do upstream: nenhum commit deles.
- `server/spawn/**`, `server/pty/**`, `server/cast/**`, `server/messages/**`, `server/permissions/**`: o Antigravity
  não entra em nenhum caminho que aja sobre uma sessão.
- `server/accounts/detect.ts`, `server/world/**`, `shared/world/**`.
- `android/**` e `zaplaud-rs/**`: os clientes Zaplaud tratam `provider` como texto livre e já negam controles a
  qualquer ferramenta que não seja `omp` ou `zeroclaw` (`MessageController.kt:24`, `rules/composer.rs:7`). Só a
  pendência entra em `docs/zaplaud-paridade.md`.
- `~/apps/habblaud` (produção) e o `~/.gemini/` de verdade: a instalação real é do dono, depois da revisão.

## Git workflow

- Trabalhe numa worktree em `/tmp`: `git worktree add /tmp/habblaud-076 -b advisor/076-antigravity-observacao producao`.
- Um commit por passo; estilo `feat(antigravity): fonte de agentes por eventos`.
- No primeiro commit, credite a origem no corpo: `Portado do PR #80 de marmottajr/habblaud (lpalvess), commits 1cd2a65..384f423.`
- Não faça push nem abra PR.

## Steps

### Step 1: trazer os arquivos novos, sem alterar

Depois do `git fetch`, para cada um dos 13 arquivos da lista "Criar, copiados", rode
`git show 384f423:<caminho> > <caminho>` (crie as pastas antes).

**Verify**: `for f in $(git ls-tree -r --name-only 384f423 | grep -i antigravity); do git show 384f423:$f | cmp -s - $f || echo DIFERE $f; done`
→ nenhuma linha.

### Step 2: tipo, configuração e fonte no servidor

- `shared/types.ts`: acrescente `| 'antigravity'` ao `Provider` e uma linha no comentário:
  `- antigravity: Antigravity CLI (\`agy\`), visto só pelos hooks dele.`
- `server/config.ts`: campo `antigravity: boolean;` na interface, depois de `omp`, com o comentário
  `/** Aceita os eventos do hook do Antigravity CLI (sources/antigravity/); HABBLAUD_ANTIGRAVITY=0 desliga. */`; no
  objeto de `loadConfig`, `antigravity: !env.HABBLAUD_ANTIGRAVITY?.trim() || isTruthy(env.HABBLAUD_ANTIGRAVITY),`.
- `server/index.ts`: depois de `const ompLive ...`:
  ```ts
  // Antigravity (sources/antigravity/): só por eventos do hook dele; nenhum arquivo do agy é lido e o agy nunca é executado.
  const antigravity = config.antigravity ? new AntigravitySource({ accounts, office }) : undefined;
  if (antigravity) agents.add(antigravity);
  ```
  e, no `createApiHandler`, junto de `codexLive,`: `antigravityEvents: config.antigravity, antigravityLive: antigravity,`.
- `server/http/app.ts`: importe `handleAntigravityEvent` e `AntigravityLive`; em `ApiDeps`,
  `antigravityEvents?: boolean; antigravityLive?: AntigravityLive;`; no `/api/health`, depois de `ompEvents`:
  `...(deps.antigravityEvents ? { antigravityEvents: true, antigravitySource: !!deps.antigravityLive } : {}),`; e a
  rota, logo depois da do Codex, **com a trava do fork**:
  ```ts
  if (path === '/api/antigravity/events' && deps.antigravityEvents) {
    // Eventos do hook do Antigravity: só observam e só valem vindos do próprio computador (mesma trava do Codex).
    if (method !== 'POST') methodNotAllowed(res, 'POST');
    else if (!trusted(req) || (!deps.inDocker && !isLoopbackAddress(req.socket.remoteAddress))) {
      sendJson(res, 403, { error: denied(req, tr('eventos do Antigravity só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)')) });
    } else handleAntigravityEvent(req, res, { live: deps.antigravityLive }).catch((err) => fail(res, err));
    return true;
  }
  ```

**Verify**: `npm run typecheck` → falha **só** em `client/src/ui/provider.ts` (falta `antigravity` no
`Record<Provider, string>`); nenhum erro em `server/`.

### Step 3: cliente

- `client/src/ui/provider.ts`:
  - `PROVIDER_NAME`: acrescente `antigravity: unbrand('Antigravity')`.
  - Acrescente `isAntigravity` (igual a `isOmp`) e
    `looksLikeAntigravityId(id) => /(?:^|[:/\\])\.?antigravity(?:[-_.~]|$)/i.test(id)`.
  - `accountProvider`: `... looksLikeOmpId(fallbackId) ? 'omp' : looksLikeAntigravityId(fallbackId) ? 'antigravity' : 'claude'`.
  - `fallbackShort`: inclua `antigravity` no regex do prefixo e devolva `'G'` para `provider === 'antigravity'` no fim.
  - **Não** altere `runtimeAllowsMessages`, `runtimeAllowsControl`, `runtimeAllowsDeliveryModes`.
- `client/src/ui/composer-model.ts`: antes da linha do `runtimeAllowsMessages`, acrescente
  `if (agent.provider === 'antigravity') return { kind: 'off', text: tr('Para responder, use o terminal do Antigravity') };`
- `client/src/ui/widgets.ts:61`: o título do selo ganha o caso `provider === 'antigravity' ? tr('Agente do Antigravity (Google)')`.
- `client/src/ui/styles.css`: acrescente `.ui-acc-chip[data-provider='antigravity']` ao seletor da linha 344 e
  `.ui-acc-chip--lg[data-provider='antigravity']` ao da linha 364 (mesmo chip vazado do Codex).
- `shared/locales/{en,es,fr}.ts`: traduções das três chaves novas (`Para responder, use o terminal do Antigravity`,
  `eventos do Antigravity só são aceitos pelo próprio computador (http://localhost ou http://127.0.0.1)`,
  `Agente do Antigravity (Google)`) e de todo `tr()` novo de `scripts/antigravity-install.ts` que o `i18n:check`
  apontar. As do PR estão em `git diff 1cd2a65 384f423 -- shared/locales/`.

**Verify**: `npm run typecheck` → exit 0. `npm run i18n:check -- --strict` → exit 0.

### Step 4: instalador, versão conferida e a saída do hook

- `package.json`: depois de `omp:status`, os scripts `antigravity:install`, `antigravity:uninstall`,
  `antigravity:status` → `tsx scripts/antigravity-install.ts <comando>`.
- Abra `plans/075-resultado.md`:
  - `scripts/antigravity-install.ts`: troque `TESTED_AGY = '1.3.3'` pela versão do resultado (resposta 8).
  - Resposta 4 = "muda": não mude o hook.
  - Resposta 4 = "`allow`": em `mod/habblaud-antigravity/hook.mjs`, o `main()` imprime `{"decision":"allow"}` **só**
    quando `process.argv[2] === 'PreToolUse'`, sempre (mesmo com o Habblaud fora do ar); atualize o cabeçalho do
    arquivo e o teste `server/test/antigravity-hook.test.ts` que afirma saída vazia.
  - Resposta 4 = "sem `PreToolUse`/`PostToolUse`": em `scripts/antigravity-install.ts`, `EVENTS_GROUPED` fica vazio;
    ajuste os testes do instalador.
  - Se a resposta 5 disser que um campo mudou de nome, é STOP.
- Atualize os comentários que citam "agy 1.3.3" nos arquivos portados para a versão conferida.

**Verify**: `npx vitest run antigravity` → todos passam. Se um teste portado falhar por diferença do arcabouço deste
fork (assinatura de `createApiHandler`, `Office`, `AccountsService`), ajuste **só a montagem** do teste, nunca a
afirmação.

### Step 5: teste que prende as regras de mecânica

Crie `server/test/antigravity-mecanica.test.ts` (modelo de estrutura: `server/test/antigravity-config.test.ts`). Ele
lê como texto `mod/habblaud-antigravity/hook.mjs`, `scripts/antigravity-install.ts`, `server/antigravity/http.ts` e
os três `.ts` de `server/sources/antigravity/` que não são teste, e afirma:

1. Nenhum deles importa `node:child_process` nem contém `spawn(`, `exec(`, `execFile(` (regra 1).
2. O hook não contém `injectSteps`, `terminationBehavior`, `auto_approve`, `"deny"`, `"continue"` (regra 3).
3. Toda URL `http` literal no hook começa com `http://127.0.0.1:`; nenhum dos arquivos contém `googleapis`,
   `google.com` nem `oauth` (regra 4; comentário com o endereço da documentação fica de fora do hook).
4. No instalador, o único caminho sob `.gemini` é `join(home, '.gemini', 'config', 'hooks.json')`: o texto não contém
   `settings.json`, `antigravity-cli`, `brain`, `keyring`, `transcript` fora de comentário (regra 2).
5. `eventBody('PreToolUse', {...})`, importado do hook, com `lastUserInput`, `transcriptPath`,
   `artifactDirectoryPath`, `modelName` e `toolCall.args.CodeContent` preenchidos na entrada, devolve um objeto cujo
   `JSON.stringify` não contém nenhum desses valores (regra 2).

E um teste de rota, no arquivo `server/http/antigravity-route.test.ts`: um agente `antigravity:<uuid>` criado por
evento não aceita `POST /api/messages` (use o helper de servidor do próprio arquivo; espere status 4xx e nenhuma
mensagem enfileirada). Se não der para montar a rota de mensagens nesse helper sem tocar em `server/messages/**`,
omita esse teste e registre em "Maintenance notes" do commit.

**Verify**: `npx vitest run antigravity-mecanica` → 5 testes passam.

### Step 6: documentação

- `CHANGELOG.md`, em `## [Não lançado]`: uma entrada "**Antigravity no escritório.**" dizendo o que entra (sala por
  pasta, personagem por conversa, status e atividade), como ligar (`npm run antigravity:install`), que o hook só
  observa e só fala com o próprio computador, que nenhum arquivo do `agy` é lido, e o que **não** tem (aprovar,
  mensagens, cota, terminal, histórico, elenco). Cite a versão conferida do `agy`.
- `README.pt-BR.md` e `README.md`: seção do Antigravity (adapte a do PR: `git diff 1cd2a65 384f423 -- README.pt-BR.md README.md`,
  sem as menções ao OpenCode) e a linha `HABBLAUD_ANTIGRAVITY | ligado | 0 desliga ...` nas tabelas de variáveis
  (junto de `HABBLAUD_OMP`, `README.pt-BR.md:1109`, `README.md:854`). Inclua um aviso curto: "O Habblaud não inicia o
  `agy` nem usa o login dele; os Termos do Antigravity proíbem acessar o serviço por ferramentas de terceiros."
- `server/README.md`: a seção "## Antigravity" do PR (`git diff 1cd2a65 384f423 -- server/README.md`), com "Quarta
  fonte" trocado pelo que vale neste fork e a versão conferida.
- `docs/zaplaud-paridade.md`: pendência "Antigravity: os clientes Zaplaud mostram o agente sem selo próprio; sem
  controles (correto)".

**Verify**: `npm run i18n:check -- --strict && npm run build` → exit 0.

### Step 7: fumaça local, sem `agy` e sem a produção

Suba o servidor da worktree numa porta e pasta de dados descartáveis e mande um evento sintético:

```sh
HABBLAUD_PORT=4799 HABBLAUD_DATA_DIR=/tmp/habblaud-076-dados HABBLAUD_WORLD=0 HABBLAUD_UPDATE_CHECK=0 npm start &
curl -s http://127.0.0.1:4799/api/health | jq '.antigravityEvents'
curl -s -X POST http://127.0.0.1:4799/api/antigravity/events -H 'Content-Type: application/json' \
  -d '{"event":"PreToolUse","conversationId":"08a163a1-c091-4c62-8146-ac8553c45d55","workspacePaths":["/tmp/loja"],"stepIdx":1,"tool":{"name":"run_command","head":"npm test"}}'
```

**Verify**: o primeiro `curl` → `true`; o segundo → `{"ok":true}`. Depois, com `HABBLAUD_ANTIGRAVITY=0`, o mesmo
`POST` → HTTP 404. Mate o servidor e apague `/tmp/habblaud-076-dados`.

## Test plan

- Portados do PR (Step 1): fonte, atividade, rota, hook, instalador, config e cliente.
- Novo: `server/test/antigravity-mecanica.test.ts` (Step 5), que falha se alguém acrescentar execução do `agy`,
  decisão no hook, rede fora do loopback, leitura de outros arquivos de `~/.gemini` ou vazamento do texto do pedido.
- Verificação: `npm test` → exit 0, com mais testes que os 2806 do baseline.

## Done criteria

- [ ] `npm run typecheck` exit 0
- [ ] `npm test` exit 0; `npx vitest run antigravity` passa, incluindo `antigravity-mecanica`
- [ ] `npm run i18n:check -- --strict` exit 0
- [ ] `npm run build` exit 0
- [ ] `grep -rnE "child_process|spawn\(|execFile\(" mod/habblaud-antigravity server/antigravity server/sources/antigravity scripts/antigravity-install.ts` sem resultado
- [ ] `grep -n "antigravity" client/src/ui/provider.ts | grep -c "runtimeAllows"` → `0`
- [ ] `git diff --stat producao -- server/spawn server/pty server/cast server/messages server/permissions android zaplaud-rs` vazio
- [ ] Fumaça do Step 7 com os três resultados esperados
- [ ] `plans/README.md` atualizado

## STOP conditions

Pare e relate se:

- `plans/075-resultado.md` não existe, ou a resposta 1 a 4 diz que nenhuma saída é neutra.
- O `git fetch upstream pull/80/head` não trouxer o commit `384f423` (o PR foi reescrito): não use outra cabeça sem
  revisão.
- Um trecho de "Current state" não bate com o código.
- Para um teste portado passar, parecer necessário mudar uma afirmação dele ou tocar em arquivo fora do escopo.
- Qualquer passo parecer pedir que o Habblaud rode o `agy`, leia outro arquivo de `~/.gemini/` ou devolva decisão
  pelo hook. Isso não é detalhe de implementação: é a fronteira que protege a conta do dono.

## Maintenance notes

- **Instalar de verdade é do dono**, depois da revisão e de a produção (`~/apps/habblaud`) receber o commit:
  `npm run antigravity:install -- --dry-run`, depois sem `--dry-run`, e `/hooks` dentro do `agy` para conferir. A
  produção desta máquina roda com `docker: false` em `127.0.0.1:4747` (medido em 2026-11-14), então o hook alcança o
  servidor pelo loopback.
- O `agy` se atualiza sozinho. Quando `npm run antigravity:status` avisar que a versão instalada difere da conferida,
  refaça o spike 075 antes de confiar. Enquanto isso o pior caso é o agente sumir do escritório; o hook nunca
  trava o `agy` por mais de 3 s.
- Quem revisar o PR deve olhar: a trava da rota (`trusted(req)` e loopback), o `eventBody` do hook (o que sai do
  stdin) e que nenhum arquivo de `server/spawn`, `server/pty`, `server/cast` ou `server/messages` foi tocado.
- Adiado de propósito: cota pela statusline (PR #82, baixo risco, é outro recurso documentado do `agy`). Recusado:
  aprovar pelo escritório (PR #81) e qualquer forma de o Habblaud iniciar ou conduzir o `agy`. Motivos no
  `plans/README.md`.
- Presença: uma conversa sai 30 minutos depois do último evento. Se o spike 075 mostrou `SessionStart` ou um evento
  de fechamento, dá para melhorar depois; não faz parte deste plano.
