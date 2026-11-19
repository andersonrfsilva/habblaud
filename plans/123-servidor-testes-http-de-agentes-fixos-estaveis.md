# Plano 123: Servidor: os dois testes de `http.test.ts` de agentes fixos deixam de falhar (ECONNRESET intermitente e modo de diretório no Windows)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 123 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat f50b599..HEAD -- server/http/http.test.ts server/http`
> Se algum arquivo do escopo mudou desde `f50b599`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: LOW-MED (um dos dois pode esconder defeito real do servidor)
- **Depende de**: nenhum
- **Categoria**: tests
- **Planejado em**: commit `f50b599`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O workflow `CI` (`npm test` em Ubuntu e Windows) está vermelho no GitHub desde pelo menos 2026-11-14 por dois
testes do mesmo bloco. Com o CI sempre vermelho, ninguém nota uma regressão nova entre os 2.800 testes.

## Estado atual

Bloco `agentes fixos: persona e Telegram` em `server/http/http.test.ts`:

1. `lê arquivo ausente, grava persona e recusa tamanho excessivo e acesso sem confiança` falha com
   `Error: read ECONNRESET` — no Ubuntu e no Windows do CI, e de forma intermitente nesta máquina (visto em
   2026-11-18 na `producao`). Hipótese a confirmar: o teste envia um corpo maior que o limite; o servidor responde
   413 e fecha a conexão antes de o cliente terminar de enviar, e o cliente vê o corte em vez da resposta. Se for
   isso, decidir onde está o erro: o servidor deveria drenar/encerrar de forma que o cliente receba o 413, ou o
   teste deveria aceitar os dois desfechos? Um cliente real (navegador, app) na mesma situação mostra o quê?
2. `valida pedidos, grava atomicamente com modos privados e nunca devolve token` falha **só no Windows**:
   `expect(statSync(directory).mode & 0o777).toBe(0o700)` recebe `0o666` (`http.test.ts:284`). No Windows o Node
   não representa modos POSIX; a afirmação não tem como valer lá.

Rodar: `npx vitest run server/http/http.test.ts` (ou o comando que `package.json` usa em `test`); a suíte toda é `npm test`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-123 -b advisor/123-servidor-testes-http-de-agentes-fixos-estaveis producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Arquivo | `npx vitest run server/http/http.test.ts` | todos passam |
| Suíte | `npm test` | 0 falhas |

## Escopo

**Pode modificar**:
- `server/http/http.test.ts`
 - o arquivo do servidor que trata o limite de corpo dessas rotas, **só se** a investigação do item 1 mostrar defeito no servidor

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Outros testes e rotas. Mudar o limite de tamanho. `plans/`. Ignorar (`.skip`) um teste: proibido.

## Passos

### Passo 1: reproduzir e explicar o ECONNRESET

Rode o teste 1 isolado em laço (ex.: 40 vezes) e conte as falhas. Leia o teste e o tratador da rota: em que
pedido do teste o erro acontece (o de tamanho excessivo?), o que o servidor faz ao exceder o limite (responde e
destrói o soquete? para de ler?).

**Verificação**: você consegue dizer, com a linha do servidor e a do teste, por que a conexão é cortada, e
reproduzir a falha (ou explicar por que só acontece sob carga).

### Passo 2: corrigir no lugar certo

Se o servidor corta a conexão sem garantir a entrega da resposta de erro: corrija o servidor para o cliente
receber o status (padrão comum: responder com `Connection: close` e só encerrar depois de enviar a resposta,
sem continuar aceitando o corpo indefinidamente), mantendo a proteção contra corpo gigante. Veja se outras rotas
do mesmo arquivo já resolvem isso e use o mesmo helper. Se o servidor já faz o certo e o corte é inerente ao
cliente de teste, ajuste o teste para afirmar o que importa (o pedido grande é recusado e nada é gravado),
aceitando 413 **ou** corte de conexão, com um comentário de uma linha explicando.

**Verificação**: o teste 1 passa 100 vezes seguidas em laço.

### Passo 3: modo de diretório no Windows

No teste 2, faça as afirmações de modo POSIX (`0o700` do diretório e `0o600` do arquivo, se houver) valerem só
fora do Windows (`process.platform !== 'win32'`), mantendo todas as outras afirmações do teste nos dois
sistemas. Procure no arquivo se já existe um padrão para isso e use o mesmo.

**Verificação**: `npx vitest run server/http/http.test.ts` passa aqui; por leitura, nenhuma afirmação de modo
roda em `win32`.

### Passo 4: suíte

`npm test` completo.

**Verificação**: 0 falhas (base: 2 falhas possíveis, as deste plano).

## Plano de testes

Os dois testes corrigidos; se o servidor mudar, um teste que prove que o cliente recebe o status de erro no corpo grande.

## Critérios de pronto

Todos precisam valer:

- [ ] Teste 1 passa 100 vezes seguidas
 - [ ] Teste 2 não afirma modo POSIX no Windows e mantém o resto
 - [ ] `npm test` sem falhas
 - [ ] nenhum `.skip`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 123 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `f50b599`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A correção exigir mudar o limite de tamanho ou o contrato da rota.
 - A causa do ECONNRESET não for a hipótese e não ficar clara em uma hora de investigação: relate a evidência.

## Notas de manutenção

- A prova final é o workflow `CI` verde no GitHub (Ubuntu e Windows) depois do merge.
