# Plan 075: Spike — o contrato dos hooks do `agy` 1.5.9 está medido antes de o Habblaud depender dele

> **REJECTED em 2026-11-14. Não execute.** O dono escolheu integrar o `agy` só pelo ACP: ver
> `plans/077-spike-agy-pelo-acp.md`. Este arquivo fica como registro.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md`.
>
> **Drift check (rode primeiro)**: este plano não altera código do repositório. Confira só a versão do binário:
> `agy --version` → `1.5.9`. Outra versão não é STOP: anote a versão real no resultado e siga.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: direction (spike)
- **Planned at**: commit `d7f9e4c`, 2026-11-14

## Why this matters

O plano 076 leva ao escritório os agentes do Antigravity CLI (`agy`) por um hook que **só observa**. Esse hook foi
escrito e medido no `agy` 1.3.3 (PR #80 do upstream `marmottajr/habblaud`). O `agy` instalado nesta máquina é o 1.5.9,
e o comportamento dos hooks já mudou entre versões: no 1.3.3 a saída `{}` no `PreToolUse` aprovava o comando; no 1.4.1
e no 1.4.2 passou a recusar (medido nos comentários do PR #81). A documentação atual diz que `decision` é
**obrigatório** na saída do `PreToolUse`, e o hook do PR não imprime nada. Antes de portar, é preciso medir no 1.5.9
se um hook mudo deixa o `agy` exatamente como estaria sem hook. Se não deixar, o hook interfere na conversa, e isso
quebra a regra central da integração (abaixo).

## Regras de mecânica (valem para este plano e para todos os da integração)

O dono não quer correr risco de bloqueio da conta Google. Os Termos do Antigravity (<https://antigravity.google/terms>, item 6)
dizem: "Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity
OAuth) is a breach of this Agreement." A integração só é aceitável se o Habblaud **não acessar o serviço**: quem fala
com o Google é só o binário oficial `agy`, aberto por uma pessoa. Nada aqui é para esconder uso; é para o uso ser, de
fato, o uso normal do `agy` com um recurso documentado dele (hooks: <https://antigravity.google/docs/hooks>).

1. Nenhum programa (script, agente de IA, Habblaud, ZeroClaw, omp) inicia o `agy`, escreve no terminal dele ou roda
   `agy -p`. **Neste spike, quem digita no `agy` é o dono, à mão.** O executor só prepara arquivos e lê o resultado.
2. Nada lê nem copia credenciais: nem `~/.gemini/**` (exceto o que este plano cria numa pasta de `/tmp`), nem o
   chaveiro do sistema, nem variáveis `GEMINI_API_KEY`.
3. O hook não decide nada e não injeta nada na conversa.
4. O hook não abre conexão de rede neste spike (grava em arquivo local).
5. Nada de disfarce: o hook tem nome claro e aparece no `/hooks` do `agy`.

## Current state

- `agy` 1.5.9 instalado em `~/.local/bin/agy` (instalador oficial, 2026-11-14). Ainda **sem login**.
- Hooks do `agy` (documentação oficial, lida em 2026-11-14):
  - Arquivos: `.agents/hooks.json` na raiz do workspace (nível do projeto) ou `~/.gemini/config/hooks.json` (global).
    Este spike usa **só o do workspace**, numa pasta de `/tmp`: o arquivo global não é tocado.
  - Eventos: `SessionStart`, `PreInvocation`, `PostInvocation`, `PreToolUse`, `PostToolUse`, `Stop`. (O PR #80 só
    conhece cinco; `SessionStart` é novo.)
  - `PreToolUse` e `PostToolUse` usam `[{ "matcher": "*", "hooks": [ {handler} ] }]`; os outros usam a lista de
    handlers direto. Handler: `{ "type": "command", "command": "...", "timeout": <segundos> }`.
  - Entrada pelo stdin, em JSON: `conversationId`, `workspacePaths`, `transcriptPath`, `artifactDirectoryPath`,
    `modelName`, `lastUserInput`; no `PreToolUse`/`PostToolUse`, `toolCall.name`, `toolCall.args`, `stepIdx`; no
    `Stop`, `fullyIdle`, `terminationReason`.
  - Saída do `PreToolUse`: `decision` "Required" (`allow` = segue para o próximo hook ou a política padrão; `deny`;
    `ask`; `auto_approve`; `force_ask`; `deny_unless_prior_grant`).
- Medições anteriores (comentários do PR #81 do upstream): no 1.4.2, saída vazia = fluxo normal do `agy`; `{}` =
  comando recusado sem prompt.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Versão | `agy --version` | `1.5.9` (ou anote a real) |
| Ver capturas | `ls /tmp/agy-spike/capturas/` | um arquivo por evento disparado |

## Scope

**In scope** (os únicos arquivos que você cria ou altera):
- `/tmp/agy-spike/**` (workspace descartável, hook de sonda, capturas)
- `plans/075-resultado.md` (criar)
- `plans/README.md` (só a linha deste plano)

**Out of scope** (NÃO toque):
- `~/.gemini/**`, inclusive `~/.gemini/config/hooks.json`: o spike usa hooks de workspace.
- Qualquer código do repositório: nada de `server/`, `client/`, `mod/`, `scripts/`.
- O login do `agy`: é do dono. O executor não roda `agy`, não abre a URL de login e não lida com o código.

## Git workflow

- Branch: `advisor/075-spike-hooks-agy`.
- Um commit só, com o resultado: `docs(planos): resultado do spike dos hooks do agy 1.5.9`.
- Não faça push nem abra PR.

## Steps

### Step 1: preparar o workspace e o hook de sonda

Crie `/tmp/agy-spike/` com:

- `sonda.mjs`: lê o stdin inteiro, grava em `/tmp/agy-spike/capturas/<epoch-ms>-<evento>.json` um JSON
  `{ "evento": argv[2], "chaves": [chaves de primeiro nível do stdin], "toolName": toolCall?.name,
  "argsChaves": [chaves de toolCall.args], "stepIdx", "fullyIdle", "terminationReason",
  "workspacePaths", "temConversationId": boolean }`. **Não grave** `lastUserInput`, valores de `toolCall.args` nem
  `transcriptPath`. Conforme `process.env.SONDA_SAIDA`: ausente ou `vazio` = não imprime nada; `allow` = imprime
  `{"decision":"allow"}`; `objeto` = imprime `{}`. Sai sempre com código 0. Não usa rede.
- Três variantes do `.agents/hooks.json`, em `hooks-vazio.json`, `hooks-allow.json`, `hooks-objeto.json`: um hook
  chamado `sonda` com os seis eventos; comando `env SONDA_SAIDA=<variante> node /tmp/agy-spike/sonda.mjs <Evento>`,
  `timeout` 10. Nos eventos que não são `PreToolUse`, use sempre a variante `vazio` (a saída só varia no `PreToolUse`).
- `LEIA.md` com o roteiro do Step 2, para o dono.

**Verify**: `echo '{"conversationId":"x","toolCall":{"name":"run_command","args":{"CommandLine":"ls"}}}' | node /tmp/agy-spike/sonda.mjs PreToolUse; echo "saida=$?"; ls /tmp/agy-spike/capturas | wc -l`
→ nenhuma linha no stdout antes de `saida=0`; contagem `1`. Apague essa captura de teste depois.

### Step 2: PARE e entregue o roteiro ao dono

O executor **não roda o `agy`**. Escreva ao dono o roteiro abaixo e espere ele avisar que terminou.

Roteiro (o dono faz à mão, num terminal dele, depois de entrar na conta com `agy`):

1. Rodada A, sem hook: `cd /tmp/agy-spike && rm -rf .agents && agy`. Peça: `rode o comando: echo spike-a > a.txt`.
   Anote: apareceu o pedido "Run this command?"; depois de aprovar, `a.txt` existe. Saia.
2. Rodada B, hook mudo: `mkdir -p .agents && cp hooks-vazio.json .agents/hooks.json && agy`. Digite `/hooks` e
   confira que `sonda` aparece. Peça: `rode o comando: echo spike-b > b.txt`. Anote as mesmas duas coisas. Saia.
3. Rodada C, `allow`: `cp hooks-allow.json .agents/hooks.json && agy`. Peça `echo spike-c > c.txt`. Anote. Saia.
4. Rodada D, `{}`: `cp hooks-objeto.json .agents/hooks.json && agy`. Peça `echo spike-d > d.txt`. Anote. Saia.
5. Em qualquer rodada com hook, feche o terminal sem `/exit` uma vez (para ver se algum evento marca o fim).

**Verify**: `ls /tmp/agy-spike/capturas | sed 's/^[0-9]*-//' | sort | uniq -c` → pelo menos `PreInvocation`,
`PreToolUse`, `PostToolUse`, `Stop` com contagem ≥ 1.

### Step 3: escrever `plans/075-resultado.md`

Com as capturas e as anotações do dono, responda, uma linha cada, com a evidência:

1. **Hook mudo é neutro?** Na rodada B o `agy` pediu aprovação igual à rodada A e o arquivo foi criado? (sim/não)
2. **`{"decision":"allow"}` é neutro?** Idem, rodada C.
3. **`{}`**: o que aconteceu na rodada D.
4. **Saída escolhida para o hook do plano 076**: a que for neutra. Se a muda e a `allow` forem as duas neutras,
   escolha a **muda** (é a do PR #80, sem mudança de código). Se só a `allow` for neutra, registre "076 precisa
   imprimir `{"decision":"allow"}` no `PreToolUse`". Se nenhuma for, registre "076 não registra `PreToolUse` nem
   `PostToolUse`: só `PreInvocation`, `PostInvocation` e `Stop`".
5. **Campos presentes** em cada evento (lista de chaves), e se `stepIdx`, `fullyIdle`, `workspacePaths` e
   `toolCall.name` vieram como o hook do PR #80 espera.
6. **`SessionStart`** disparou? Há algum evento ao fechar o terminal?
7. **Caminho com espaço**: o comando do hook foi partido nos espaços (o `env SONDA_SAIDA=...` funcionou)? Se não
   funcionou, registre a forma que funcionou.
8. Versão do `agy` e data.

**Verify**: `grep -c "^[1-8]\." plans/075-resultado.md` → `8`.

### Step 4: limpar

`rm -rf /tmp/agy-spike`. **Verify**: `test ! -e /tmp/agy-spike && echo limpo` → `limpo`.

## Test plan

Sem testes novos: é medição. O resultado é o arquivo `plans/075-resultado.md`.

## Done criteria

- [ ] `plans/075-resultado.md` existe com as 8 respostas, cada uma com evidência (nome do arquivo de captura ou
      anotação do dono).
- [ ] `git status --short` mostra só `plans/075-resultado.md` e `plans/README.md`.
- [ ] `test ! -e /tmp/agy-spike`.
- [ ] `git diff --stat d7f9e4c -- server client mod scripts shared` vazio.
- [ ] Linha do 075 atualizada em `plans/README.md`.

## STOP conditions

Pare e relate se:

- O dono ainda não entrou na conta no `agy`. Não tente entrar por ele, não sugira chave nem token de outra ferramenta.
- O `agy` 1.5.9 não carregar hooks de `.agents/hooks.json` (o `/hooks` não mostra `sonda`). Não passe para o arquivo
  global por conta própria: relate.
- Nenhuma das três saídas for neutra **e** os eventos sem ferramenta também alterarem o comportamento: a integração
  por hook fica inviável nesta versão.
- Qualquer passo parecer exigir que um programa rode ou controle o `agy`.

## Maintenance notes

- O resultado vale para a versão medida. O `agy` se atualiza sozinho: o plano 076 grava a versão conferida no
  instalador (`TESTED_AGY`) e o `antigravity:status` avisa quando a versão instalada é outra.
- Fica fora, de propósito: medir aprovação pelo hook (`auto_approve`, `deny`). Ver "Findings considered and rejected"
  no `plans/README.md`.
