# Resultado do plano 077: o que o `agy acp` 1.5.9 entrega a um cliente ACP

Medido em 2026-11-14 no `homexlinx`, `agy` 1.5.9, conta pessoal do dono (plano "Google AI Pro"), com login feito por
ele. Cliente: sonda Node sem dependências, `clientInfo.name = "habblaud"`, falando só por stdin/stdout de `agy acp`.
Nenhuma flag proibida. As capturas ficaram em `/tmp/agy-acp-spike/capturas/*.ndjson` durante a medição (apagadas no
fim); os trechos abaixo são cópias delas.

Desvios do plano, com autorização do dono ("execute todos os planos", "quero com o skip permissions, numa flag"):
10 prompts em vez de 8 (entrou o cenário de `--dangerously-skip-permissions`); o passo em que o dono abriria uma
conversa no terminal não foi preciso, porque já havia uma conversa dele feita no terminal.

## 1. Sessão

- `session/new` com `{cwd, mcpServers: []}` devolve `sessionId` em formato UUID
  (`245ce580-6f9d-46f0-bea5-a5d5cd53af1b`) e mais: `modes`, `models`, `configOptions`, `_meta`.
- `modes.availableModes`: `default` ("Ask before edits and commands, per your permission settings"), `accept-edits`,
  `auto`, `plan`. Atual: `default`.
- `models`: 18 entradas (`modelId` como `gemini-4-argon-medium`); atual `gemini-4-argon-medium`.
- `configOptions` (todas `select`): `model` (`gemini-4-argon`, `gemini-4-hydrogen`,
  `gemini-pro-orchestrator-g1-pro`, `gemini-3.8-flash`, `claude-opus-5-5`, `claude-sonnet-5-5`,
  `gpt-oss-120b-medium`), `thought_level` (`low`, `medium`, `high`), `mode` (os quatro modos).
- `_meta.antigravity.tier`: `{"name":"Google AI Pro","id":"g1-pro-tier","upgradeUri":...}`.
- Captura: `basico.ndjson`.

## 2. Avisos (`session/update`)

Valores de `sessionUpdate` vistos: `available_commands_update`, `usage_update`, `session_info_update`,
`user_message_chunk`, `agent_message_chunk`, `agent_thought_chunk`, `tool_call`, `tool_call_update`.

| Aviso | Campos úteis |
|---|---|
| `user_message_chunk` | `content: {type:"text", text}`: o próprio prompt, ecoado |
| `agent_message_chunk` | `content: {type:"text", text}`: pedaços da resposta, concatenar |
| `agent_thought_chunk` | `content: {type:"text", text}`: raciocínio resumido |
| `tool_call` | `toolCallId`, `name` (`run_command`), `title` (o comando), `kind` (`execute`), `status` (`in_progress`), `rawInput` (`CommandLine`, `toolSummary`) |
| `tool_call_update` | mesmo `toolCallId`; `status` passa por `pending` (esperando permissão), `in_progress`, e termina em `completed` (com `rawOutput.output`) ou `failed` |
| `session_info_update` | `title` (ex.: "Simple Response Request"), gerado pelo `agy` depois do primeiro turno |
| `usage_update` | `used` (tokens no contexto, ex.: 8708) e `size` (janela, 1048576) |
| `available_commands_update` | `availableCommands[]` com `name` e `description` (`goal`, `compact`, `schedule`, `browser`, `plan`...) |

A resposta do `session/prompt` chega quando o turno acaba: `{stopReason, usage: {inputTokens, outputTokens,
cachedReadTokens, cachedWriteTokens, thoughtTokens, totalTokens}}`. `stopReason` vistos: `end_turn`, `cancelled`.
Capturas: `basico.ndjson`, `permissao.ndjson`.

## 3. Permissão

O `agy` manda um **pedido** JSON-RPC (com `id`) `session/request_permission`:

```json
{"sessionId":"7f9ff17c-…","toolCall":{"toolCallId":"call_5310887135360277733","name":"run_command",
  "status":"pending","kind":"execute","title":"echo spike-a > a.txt",
  "rawInput":{"CommandLine":"echo spike-a > a.txt","WaitMsBeforeAsync":5000,"toolSummary":"Write spike-a to a.txt"}},
 "options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},{"optionId":"deny","name":"Deny","kind":"reject_once"}]}
```

- Resposta para permitir: `{"outcome":{"outcome":"selected","optionId":"allow"}}` → o comando rodou, `a.txt` criado,
  `tool_call_update` com `status: "completed"`.
- Resposta para negar: `{"outcome":{"outcome":"selected","optionId":"deny"}}` → `b.txt` **não** foi criado,
  `tool_call_update` com `status: "failed"`, turno terminou com `end_turn` e sem texto do agente.
- Só duas opções vieram (`allow_once`, `reject_once`); nenhuma "sempre permitir".
- O turno fica parado enquanto o pedido não é respondido (o `session/prompt` não devolve).
- Captura: `permissao.ndjson`.

**Com `--dangerously-skip-permissions`** (`agy acp --dangerously-skip-permissions`): nenhum
`session/request_permission` chegou (0 pedidos), o comando rodou direto e `s.txt` foi criado. Captura:
`permissao-skip.ndjson`.

## 4. Cancelar

Com um pedido de permissão pendente (`sleep 60`): aviso `session/cancel {sessionId}` e, em seguida, resposta ao pedido
pendente com `{"outcome":{"outcome":"cancelled"}}` (o protocolo exige as duas coisas). O `session/prompt` devolveu
`{"stopReason":"cancelled"}`, o comando não rodou e a sessão continuou utilizável. Cancelar um comando **já em
execução** não foi medido. Captura: `permissao.ndjson`.

## 5. Lista e retomada

- `session/list {}` devolve `sessions[]` com `sessionId`, `cwd`, `title`, `updatedAt` (ISO), mais recente primeiro;
  sem `nextCursor` com 3 sessões.
- O filtro `cwd` **não filtra**: `session/list {cwd: "/tmp/agy-acp-spike/ws"}` devolveu as mesmas 3 sessões,
  inclusive a de `/home/anderson`. Quem quiser filtrar por pasta filtra no cliente.
- `session/load {sessionId, cwd, mcpServers: []}`, num processo novo: reenviou o histórico como `session/update`
  (2 `user_message_chunk` e 2 `agent_message_chunk` para uma conversa de dois turnos) e devolveu o mesmo corpo do
  `session/new`. Histórico de uma conversa **com ferramentas** não foi carregado (não medido se os `tool_call` voltam).
- `session/resume {sessionId, cwd, mcpServers: []}`, noutro processo novo: não reenviou nada, e o prompt seguinte
  ("Qual foi a primeira palavra que respondi nesta conversa?") respondeu "ok": o contexto continua.
- `session/close {sessionId}` devolveu `{}`.
- Captura: `sessoes-list-load.ndjson`, `sessoes-resume.ndjson`.

## 6. Sessão do terminal

A lista trouxe uma conversa que o dono fez no terminal do `agy` antes do spike (outra pasta, com o título que o
`agy` deu a ela). Ou seja: conversas do terminal
**aparecem** em `session/list`. Retomar essa conversa pelo ACP **não foi testado** (seria mexer numa conversa do dono).
Retomar uma conversa enquanto ela está aberta no terminal também não foi medido.

## 7. Processo

| Medida | Valor |
|---|---|
| Uma conexão, duas sessões, dois turnos **ao mesmo tempo** | funciona; cada `session/update` traz o `sessionId` certo ("um" e "dois") |
| RSS de um `agy acp` logo depois do `initialize` | 179 MiB (179080 kB) |
| RSS com 2 sessões criadas, sem turno | 228 MiB (233580 kB) |
| RSS parado, 10 s depois de um turno em cada uma das 2 sessões | 246 MiB (251792 kB) |
| `--process-per-session`, 2 sessões | 3 processos: pai 173 MiB + um `agy acp --worker` por sessão, ~228 MiB cada |
| Fechar o stdin, parado | sai com código 0 em 0,2–0,8 s |
| Fechar o stdin no meio de um turno | sai com código 0 em 0,6 s; nenhum processo sobra |
| stderr | vazio em todos os cenários |

O worker do `--process-per-session` é aberto pelo próprio `agy` com `--cloud_code_endpoint=…` na linha de comando
dele: é interno do `agy`, não é o cliente passando a flag. Captura: `processo-duas.ndjson`,
`processo-por-sessao.ndjson`, `processo-fecha-no-meio.ndjson`.

## 8. Sem login

Com `HOME` apontando para uma pasta vazia: `initialize` responde normalmente; `session/new` devolve erro JSON-RPC
`{"code":-32000,"message":"Authentication required"}`. O processo continua vivo. Captura: `sem-login.ndjson`.

## 9. Uso e cota

- Por turno: `usage` na resposta do `session/prompt` (tokens de entrada, saída, cache, raciocínio).
- Por sessão: `usage_update` com `used`/`size` (ocupação do contexto).
- Plano: `_meta.antigravity.tier.name` no `session/new`.
- **Cota** (quanto resta da semana ou das 5 horas): nenhum campo. Não há como montar barra de cota pelo ACP.

## 10. Versão, data, volume

`agy` 1.5.9; 2026-11-14; **10 prompts** no total (2 em `basico`, 3 em `permissao`, 1 em `sessoes-resume`, 2 em
`processo-duas`, 1 em `processo-fecha-no-meio`, 1 em `permissao-skip`). Nenhuma resposta do `agy` falou em termos,
uso não permitido ou cliente não suportado. Isso não prova nada sobre como o Google trata o uso; só que não houve
aviso nesses 10 turnos.

## 11. Recomendação para o 078

- **Um processo `agy acp` para o Habblaud inteiro**, aberto sob demanda (no primeiro personagem chamado) e fechado
  quando a última sessão é dispensada. Várias sessões e turnos simultâneos funcionam numa conexão; um processo por
  sessão custaria ~228 MiB cada, contra ~25 MiB por sessão a mais no processo único.
- **Sem `--process-per-session`.**
- **Dá para ligar:**
  - Elenco: chamar = `session/new` (pasta do personagem); retomar = `session/resume`; dispensar = `session/close`.
  - Mensagens: `session/prompt`. Com turno em andamento, enfileirar e mandar quando o turno acabar (o ACP não tem
    "steer"); parar = `session/cancel` + resposta `cancelled` aos pedidos pendentes.
  - Permissões: `session/request_permission` vira cartão no escritório; a resposta escolhe `allow_once` ou
    `reject_once` pelo `kind` (não pelo texto do `optionId`).
  - Status: trabalhando enquanto um `session/prompt` está sem resposta; "precisa de você" enquanto há pedido de
    permissão pendente; parado depois da resposta.
  - Atividade e terminal: `tool_call`/`tool_call_update` (título, estado, saída) e os pedaços de texto.
  - Título da sala: `session_info_update.title`. Barra de contexto: `usage_update`. Modelo: `configOptions`
    (`session/set_config_option`, não medido).
  - Histórico: `session/list` (filtrar a pasta no cliente) e `session/load` para reler.
  - Erro `-32000 Authentication required` → aviso "entre na conta com `agy`", sem tentar autenticar.
- **Flag do dono:** `--dangerously-skip-permissions` funciona pelo ACP e elimina os pedidos. Entra como opção
  desligada por padrão; vale para o processo inteiro (todas as sessões).
- **Não dá:** barra de cota; "steer" no meio do turno; ver ao vivo uma conversa aberta no terminal; "sempre
  permitir" (o `agy` só oferece permitir/negar uma vez; regras fixas ficam no `settings.json` dele, que o Habblaud
  não toca).
- **Não medido, tratar como risco no 078:** cancelar comando em execução; `session/load` de conversa com
  ferramentas; `session/set_config_option`; retomar conversa criada no terminal; comportamento quando o `agy` se
  atualiza com o processo aberto.
