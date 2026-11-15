# Plan 077: Spike — o que o `agy acp` 1.5.9 entrega a um cliente ACP está medido, para o Habblaud falar com o `agy` só por ele

> **REMOVIDO em 2026-11-15 por ordem do dono. NÃO EXECUTE.** A integração com o Antigravity CLI (`agy`) foi
> apagada do código e da máquina por decisão do dono, para não expor a conta Google dele a risco.
> Este arquivo fica só como registro histórico. Nenhum agente deve reintroduzir, propor ou testar essa integração.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md`.
>
> **Drift check (rode primeiro)**: este plano não altera código do repositório. Confira o binário:
> `agy --version` → `1.5.9`. Outra versão não é STOP: anote a versão real no resultado e siga.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW para o código; o risco de conta está descrito em "Regras de mecânica"
- **Depends on**: login do dono no `agy` (ele faz, à mão) e autorização expressa dele para rodar os Steps 3 a 6
- **Category**: direction (spike)
- **Planned at**: commit `d7f9e4c`, 2026-11-14

## Why this matters

O dono quer conversar com o Antigravity CLI (`agy`, do Google) pelo Habblaud como faz com o omp e o ZeroClaw: chamar
um personagem, mandar mensagens, aprovar ou negar pedidos, cancelar, retomar. Decisão dele em 2026-11-14: **um
mecanismo só, o ACP** (`agy acp`), sem hooks. O ACP é a interface que o próprio `agy` oferece a clientes: um
subprocesso que fala JSON-RPC por stdin e stdout. Antes de desenhar a fonte e o elenco em cima disso, é preciso medir
o que o servidor ACP do 1.5.9 realmente faz: formato dos avisos, pedidos de permissão, lista e retomada de sessões,
modelo de processo e memória. O produto deste plano é `plans/077-resultado.md`; o plano de implementação (078) só é
escrito depois dele.

## Regras de mecânica (não negociáveis; valem para este plano e para o 078)

O dono não quer correr risco de bloqueio da conta Google. Os Termos do Antigravity (<https://antigravity.google/terms>, item 6)
dizem: "You must not abuse, harm, interfere with, or disrupt the Service. This includes, but is not limited to, using
the Service in connection with products not provided by us. Using third party software, tools, or services to access
the Service (e.g. using OpenClaw with Antigravity OAuth) is a breach of this Agreement."

**O que é fato e o que é leitura.** Fato: a documentação do `agy` descreve o ACP para "any editor that supports the
Agent Client Protocol", tem uma seção "Other clients" e lista o login pessoal (`oauth-personal`) entre os métodos
(<https://antigravity.google/docs/ide/extensions/acp>). Leitura, não garantida: que isso torna um cliente ACP próprio
compatível com o item 6. Documentação de produto não é contrato, e a frase "in connection with products not provided
by us" é larga. Nenhum modo de uso, inclusive com chave da Gemini API, tem garantia contra bloqueio. As regras abaixo
reduzem a exposição; não a eliminam, e nenhuma delas é disfarce.

1. O único programa que fala com o Google é o binário oficial `agy`, sem modificação. O cliente só fala com o `agy`,
   por stdin e stdout do subprocesso `agy acp`.
2. O cliente nunca lê, copia nem envia credenciais: nada de `~/.gemini/**`, chaveiro do sistema ou token. O login é
   do dono, feito por ele com `agy` ou `agy acp --login`.
3. Flags proibidas no `agy acp`: `--override_user_agent_name`, `--cloud_code_endpoint`, `--disable-telemetry` (e
   `--disable_telemetry`), `--is_google3_workspace`, `--is_google_internal`, `--gemini-dir`, `--app-data-dir`,
   `--dangerously-skip-permissions`. O `agy` se identifica e reporta ao Google como ele quiser.
4. O cliente se identifica com o nome verdadeiro no `initialize`: `clientInfo.name = "habblaud"`.
5. Toda permissão é respondida por uma pessoa. Neste spike, as respostas a `session/request_permission` são fixas no
   roteiro (uma permitir, uma negar) para comandos inofensivos escritos pelo próprio roteiro.
6. Só na conta e na máquina do dono. Nunca nos tenants do SaaS (`omp-tenant`), onde a conta seria usada por clientes.
7. Uso contido: o spike manda no máximo 8 prompts curtos, uma vez. Nada de laço, repetição automática ou paralelismo.

## Current state

- `agy` 1.5.9 em `~/.local/bin/agy`, sem login em 2026-11-14.
- Medido em 2026-11-14, **sem login e sem mandar prompt** (só o `initialize`):
  ```json
  {"protocolVersion":1,
   "agentInfo":{"name":"Antigravity ACP Server","version":"1.5.9"},
   "agentCapabilities":{"loadSession":true,
     "promptCapabilities":{"image":true,"audio":false,"embeddedContext":true},
     "sessionCapabilities":{"resume":{},"close":{},"load":{},"list":{},"additionalDirectories":{}},
     "auth":{"logout":{}},
     "_meta":{"antigravity":{"tier":true,"systemPrompt":{"append":true}}}},
   "authMethods":[{"id":"oauth-personal"},{"id":"oauth-business"},{"id":"gemini-api-key"},{"id":"agent-platform"}]}
  ```
  O pedido usado: uma linha JSON por mensagem no stdin,
  `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false},"terminal":false},"clientInfo":{"name":"habblaud-probe","version":"0"}}}`.
  O processo saiu com 0 quando o stdin fechou; nada no stderr.
- Protocolo: <https://agentclientprotocol.com/protocol/v1/overview.md> (versão 1). Métodos que interessam:
  `initialize`, `session/new`, `session/prompt`, aviso `session/update`, pedido do agente
  `session/request_permission`, aviso `session/cancel`, `session/load`, `session/resume`, `session/list`,
  `session/close`. O esquema está em <https://agentclientprotocol.com/protocol/v1/schema.md>: **leia antes de
  escrever a sonda**; não adivinhe nome de campo.
- Flags documentadas do `agy acp` úteis aqui: `--process-per-session` (um processo por sessão), `--add-dir`,
  `--sandbox`, `--log-file`.
- O Habblaud não tem dependência de runtime no servidor (`AGENTS.md`): a sonda usa só `node:child_process` e
  `node:readline`, sem instalar SDK de ACP.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Versão | `agy --version` | `1.5.9` (ou anote a real) |
| Login feito? | `printf '%s\n' '<initialize acima>' \| timeout 15 agy acp \| jq -r .result.agentInfo.version` | `1.5.9` |
| Memória de um processo | `ps -o rss= -p <pid>` | número em kB |

## Scope

**In scope**:
- `/tmp/agy-acp-spike/**` (workspace descartável, sonda `sonda.mjs`, capturas)
- `plans/077-resultado.md` (criar)
- `plans/README.md` (só a linha deste plano)

**Out of scope** (NÃO toque):
- Qualquer código do repositório: `server/`, `client/`, `shared/`, `mod/`, `scripts/`.
- `~/.gemini/**`: não leia, não liste, não copie. O que o spike precisa saber vem das respostas do ACP.
- O login do `agy`: é do dono.
- Qualquer tenant e o repositório `omp-tenant`.

## Git workflow

- Branch: `advisor/077-spike-agy-acp`.
- Um commit: `docs(planos): resultado do spike do agy pelo ACP`.
- Não faça push nem abra PR.

## Steps

### Step 1: escrever a sonda

Crie `/tmp/agy-acp-spike/sonda.mjs`: abre `agy acp` (com os argumentos extras que receber) com
`cwd=/tmp/agy-acp-spike/ws`, manda uma mensagem JSON-RPC por linha, grava **toda** linha recebida em
`capturas/<cenario>.ndjson` com a hora, e executa um cenário escolhido por argumento (Steps 3 a 6). Nas capturas,
troque por `"<omitido>"` o texto das respostas do modelo com mais de 200 caracteres. Responde a
`session/request_permission` conforme o cenário. Pedidos do agente que o cliente não suporta (`fs/*`, `terminal/*`)
recebem erro JSON-RPC `-32601`. Limite de 120 s por cenário; no fim fecha o stdin e espera o processo sair.

**Verify**: `node /tmp/agy-acp-spike/sonda.mjs init && jq -r 'select(.id==1) | .result.agentInfo.version' /tmp/agy-acp-spike/capturas/init.ndjson`
→ `1.5.9`.

### Step 2: PARE — login e autorização do dono

Peça ao dono: (a) entrar na conta com `agy` (ou `agy acp --login`); (b) autorizar o spike, dizendo que ele manda até
8 prompts curtos pela conta dele através do `agy acp`. Sem as duas coisas, não siga.

**Verify**: o dono respondeu que entrou e que autoriza.

### Step 3: sessão, prompt e avisos

Cenário `basico`: `initialize` → `session/new` (`cwd` = o workspace, sem servidores MCP) → `session/prompt` com
`Responda só: ok`. Depois um segundo prompt na mesma sessão: `Qual foi a palavra que respondi antes? Só a palavra.`

Anote: forma do `sessionId` (é UUID?); os valores de `sessionUpdate` vistos em `session/update`; o `stopReason`; se
vieram `modes`, `configOptions` ou modelos na resposta do `session/new`; se há uso de tokens ou cota em algum campo
(inclusive `_meta`).

**Verify**: `jq -r 'select(.method=="session/update") | .params.update.sessionUpdate' capturas/basico.ndjson | sort | uniq -c`
→ pelo menos `agent_message_chunk`.

### Step 4: permissão (permitir, negar) e cancelar

Cenário `permissao`: prompt `Rode o comando: echo spike-a > a.txt`. Ao receber `session/request_permission`, grave o
pedido inteiro e responda com a opção de **permitir uma vez**. Depois, prompt `Rode o comando: echo spike-b > b.txt`
e responda com a opção de **negar**. Depois, prompt `Rode o comando: sleep 60` e, ao chegar o pedido de permissão,
mande `session/cancel` em vez de responder.

Anote: os campos do pedido (`toolCall`, `options` com `optionId` e `kind`); o que veio depois de permitir e de negar;
o `stopReason` depois do cancelamento; se algum pedido chegou sem `request_permission` (regra já permitida).

**Verify**: `test -f ws/a.txt && test ! -e ws/b.txt && echo ok` → `ok`.

### Step 5: listar, carregar, retomar, fechar

Cenário `sessoes`, num processo **novo**: `session/list` (anote os campos de cada item e se a sessão do Step 3
aparece); `session/load` da sessão do Step 3 (anote se o histórico volta como `session/update` e quantos avisos);
em outro processo novo, `session/resume` da mesma sessão seguido de um prompt `Responda só: de volta`; `session/close`.

Peça ao dono, uma vez: abrir `agy` no terminal em `/tmp/agy-acp-spike/ws`, mandar `Responda só: terminal`, sair com
`/exit`. Rode `session/list` de novo e anote se a conversa do terminal aparece e se `session/resume` dela funciona.

**Verify**: `jq -c 'select(.id and .result.sessions) | .result.sessions | length' capturas/sessoes.ndjson` → número ≥ 1.

### Step 6: processo, memória e fim

Cenário `processo`: (a) um processo com duas sessões (`session/new` duas vezes, um prompt em cada, um depois do
outro): funciona? RSS do processo parado, 10 s depois do último turno; (b) o mesmo com `--process-per-session`: quantos
processos filhos, RSS de cada; (c) feche o stdin no meio de um turno: o processo sai? em quanto tempo? sobra filho
(`pgrep -f "agy acp"`)?; (d) rode o `initialize` + `session/new` com `HOME` apontando para uma pasta vazia de `/tmp`
(simula "sem login"): grave o erro exato (código e mensagem).

**Verify**: `pgrep -f "agy acp" | wc -l` → `0` ao final.

### Step 7: escrever `plans/077-resultado.md` e limpar

Uma seção por pergunta, cada uma com a resposta e o nome do arquivo de captura que a prova:

1. Sessão: forma do id; o que `session/new` devolve (modos, opções, modelos).
2. Avisos: lista dos `sessionUpdate` e, para cada um, os campos que o Habblaud usaria (texto, ferramenta, estado).
3. Permissão: forma do pedido, opções, efeito de permitir e de negar.
4. Cancelar: o que acontece com o turno e com o pedido pendente.
5. Lista e retomada: campos de `session/list`; `load` reenvia o histórico?; `resume` continua a conversa?
6. Sessão do terminal: aparece na lista? retoma pelo ACP?
7. Processo: uma conexão serve várias sessões? RSS parado nos dois modos; saída limpa ao fechar o stdin.
8. Sem login: erro exato.
9. Uso e cota: algum campo traz tokens, cota ou plano?
10. Versão do `agy`, data e quantos prompts foram mandados no total.
11. Recomendação para o 078: um processo por personagem ou um processo para todos; o que do elenco, da caixa de
    mensagens, das permissões e do histórico dá para ligar; o que **não** dá.

Depois: `rm -rf /tmp/agy-acp-spike`.

**Verify**: `grep -c "^## [0-9]" plans/077-resultado.md` → `11`; `test ! -e /tmp/agy-acp-spike && echo limpo` → `limpo`.

## Test plan

Sem testes novos: é medição. O resultado é `plans/077-resultado.md`.

## Done criteria

- [ ] `plans/077-resultado.md` com as 11 seções, cada resposta com a captura que a prova
- [ ] No máximo 8 prompts mandados (seção 10)
- [ ] `pgrep -f "agy acp" | wc -l` → `0`
- [ ] `test ! -e /tmp/agy-acp-spike`
- [ ] `git diff --stat d7f9e4c -- server client shared mod scripts` vazio
- [ ] Linha do 077 atualizada em `plans/README.md`

## STOP conditions

Pare e relate se:

- O dono não entrou na conta ou não autorizou (Step 2). Não use chave, token nem login de outra ferramenta.
- Qualquer resposta do `agy` falar em violação de termos, uso não permitido, conta restrita ou cliente não suportado:
  pare na hora, não repita o pedido, e entregue a mensagem exata ao dono.
- Um passo parecer exigir uma flag proibida (regra 3), ler `~/.gemini/**` ou responder a uma permissão que não é de
  um dos comandos do roteiro.
- O total de prompts passar de 8, ou um cenário precisar ser repetido mais de uma vez.
- `session/request_permission` nunca chegar no Step 4 (o `agy` executou sem perguntar ou recusou sozinho): anote e
  siga para o Step 5, mas marque a seção 3 como "não medido".

## Maintenance notes

- O 078 (implementação) é escrito a partir da seção 11 do resultado, no modelo da fonte ZeroClaw deste fork
  (`server/sources/zeroclaw/`, `server/cast/zeroclaw.ts`, `server/messages/zeroclaw.ts`), e leva as mesmas "Regras de
  mecânica", com um teste que falha se uma flag proibida entrar na linha de comando do `agy acp`.
- Com ACP só, as conversas que o dono abre direto no terminal do `agy` não aparecem ao vivo no escritório. Se a
  seção 6 mostrar que elas entram na lista e retomam, o Habblaud pode oferecê-las para continuar; ao vivo, não.
- O resultado vale para a versão medida. O `agy` se atualiza sozinho.
- Os planos 075 e 076 (observar por hook) foram substituídos por este caminho; ver `plans/README.md`.
