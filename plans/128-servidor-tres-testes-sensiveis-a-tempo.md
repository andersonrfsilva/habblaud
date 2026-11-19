# Plano 128: Servidor: três testes que falham por tempo sob carga deixam de depender de sorte (`summarizer`, `world/uplink`, `world/http`)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 128 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- server/summaries/summarizer.test.ts server/world/uplink.test.ts server/world/http.test.ts`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: LOW-MED (um deles pode esconder ordem errada de eventos no servidor)
- **Depende de**: nenhum
- **Categoria**: tests
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Depois dos planos 122 a 127 os quatro workflows do GitHub ficaram verdes, mas o `CI` (`npm test`) voltou a falhar
no Ubuntu no commit `c017628`, que não tocou no servidor: um teste estourou o prazo. Outros dois falharam nesta
máquina sob carga em 2026-11-18. Enquanto existirem, o CI fica vermelho de vez em quando sem regressão, e um
vermelho deixa de significar alguma coisa.

## Estado atual

1. `server/summaries/summarizer.test.ts:220` — `runs two HTTP jobs by default and admits waiting jobs FIFO`:
   `Error: Test timed out in 5000ms` no GitHub (run `44502421163`, Ubuntu; passou no Windows e nos commits
   vizinhos). O teste troca `fetch` por promessas controladas, dispara 4 resumos, espera `calls` chegar a 2 com
   `vi.waitFor`, resolve um e espera o terceiro pedido (`:229-235`).
2. `server/world/uplink.test.ts:362` — espera a reconexão depois de `clock.advance(FAST.backoffMaxMs!)` com
   `waitFor(() => h.world.ws.connections.length === 2, 'reconectou')`; estourou 3 s aqui sob carga. Hipótese do
   executor do plano 123, não confirmada: o relógio manual é avançado antes de o `onclose` agendar a nova
   tentativa (`server/world/uplink.ts:452,608`), então o avanço se perde.
3. `server/world/http.test.ts:1197` — depois de `await new Promise((ok) => setTimeout(ok, 400))` exige
   `tries > 2` e `< 15` conexões; viu 2 aqui sob carga. É espera por tempo de parede.

Rodar um arquivo: `npx vitest run <arquivo>`; um teste: `npx vitest run <arquivo> -t '<nome>'`. Para simular carga:
rodar o teste em laço enquanto `npm test` roda em paralelo em outro terminal, ou `stress`-like com
`yes > /dev/null &` em todos os núcleos (mate depois).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-128 -b advisor/128-servidor-tres-testes-sensiveis-a-tempo producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Suíte | `npm test` | 0 falhas |

## Escopo

**Pode modificar**:
- os três arquivos de teste
 - o arquivo do servidor correspondente, **só se** a investigação mostrar ordem errada no servidor (não no teste)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Aumentar prazo como única correção, `.skip`, `retry`. Outros testes. `plans/`.

## Passos

### Passo 1: reproduzir cada um sob carga

Para cada teste: 30 execuções isoladas sem carga e 30 com carga (todos os núcleos ocupados). Anote falhas.

**Verificação**: tabela teste × (sem carga, com carga) com contagem de falhas. Se algum não falhar nenhuma vez
em 60, explique pela leitura do código por que pode falhar no CI e trate assim mesmo.

### Passo 2: corrigir a causa de cada um

Troque espera por tempo de parede por espera por condição observável, e avanço de relógio manual por avanço
**depois** de a condição que agenda o próximo passo ter ocorrido (por exemplo: esperar o fechamento ser
observado antes de `clock.advance`). No teste 3, substitua os 400 ms fixos por esperar o número de tentativas
passar de 2 (com prazo), mantendo a afirmação do teto (`< 15`) medida numa janela que não dependa da carga
(por exemplo, contando tentativas por tempo do relógio do teste, se o módulo aceitar relógio injetado; veja como
`uplink.test.ts` injeta `clock`). No teste 1, descubra o que impede o terceiro pedido de sair (fila do
resumidor esperando um temporizador real? microtarefas?) antes de mudar qualquer coisa. Se a causa for o
servidor fazer as coisas em ordem que depende de sorte (por exemplo, agendar a reconexão num `setTimeout` real
quando recebeu relógio injetado), corrija o servidor e diga em NOTES.

**Verificação**: cada teste passa 100 vezes seguidas sem carga e 50 vezes com carga.

### Passo 3: suíte

`npm test` três vezes seguidas, uma delas com carga em paralelo.

**Verificação**: 0 falhas nas três. Se outro teste falhar por tempo, não corrija: liste nome, arquivo:linha e mensagem em NOTES.

## Plano de testes

Os três testes.

## Critérios de pronto

Todos precisam valer:

- [ ] Tabela antes/depois com contagens
 - [ ] 100/100 sem carga e 50/50 com carga para cada um
 - [ ] `npm test` 3 vezes sem falha
 - [ ] nenhum `.skip`/`retry`; prazo só aumentou com justificativa escrita
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 128 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A causa de algum deles não ficar clara: relate a evidência em vez de aumentar prazo.
 - A correção exigir mudar contrato do servidor.

## Notas de manutenção

- Outros testes sensíveis a tempo que aparecerem na suíte entram em NOTES para um próximo plano.
