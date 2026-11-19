# Plano 139: omp: as perguntas feitas em formulário voltam a chegar ao Zaplaud

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 139 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 8885d43..HEAD -- mod/habblaud-omp/habblaud.ts server/permissions/registry.ts`
> Se algum arquivo do escopo mudou desde `8885d43`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (extensão carregada dentro de toda sessão do omp; resposta devolvida ao agente precisa ter o formato exato)
- **Depende de**: nenhum
- **Categoria**: bug
- **Planejado em**: commit `8885d43`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O dono usa o Zaplaud para responder aos agentes. Quando um agente do omp faz uma pergunta, o Zaplaud mostra
"Fazendo uma pergunta a você" e mais nada: a pergunta fica esperando no terminal do omp, e o agente fica parado até
alguém ir lá. Caso real em 2026-11-19: sessão "XTC: Fix MK4 terminal IP mapping", omp 18.22.2.

## Estado atual

A extensão do Habblaud para o omp (`mod/habblaud-omp/habblaud.ts`) substitui a ferramenta `ask` (`ask()`,
~`:1084-1124`): registra a pergunta no servidor (`register(sid, ASK_TOOL, input, cwd, ASK_WAIT_MS)`), mostra o
diálogo nativo e o cartão ao mesmo tempo, e vale a primeira resposta. Mas `askQuestions()` (`:372-388`) só aceita o
formato antigo — `params.questions[]` com `id`, `question`, `options[{label, description?}]`, `multi` — e devolve
`undefined` para qualquer outra coisa; aí `ask()` chama só o nativo.

No omp 18.22.2 a ferramenta `ask` mudou: recebe **um formulário HTML** em `html` (e `i`, a intenção). Conferido no
transcript da sessão do caso: a chamada tem `html` e não tem `questions`. A descrição atual da ferramenta diz:
um `<form>` de HTML semântico simples; todo controle tem `name`; uma escolha = `input type="radio"`; várias =
`type="checkbox"` (mesmo `name`); listas longas = `<select>` (`multiple` opcional); cada pergunta agrupada num
`<fieldset>` com `<legend>` ou com um `<p>` antes do grupo; grupos de rádio e de checkbox ganham automaticamente
uma resposta livre "Outro"; `checked`/`selected`/`value` marcam o recomendado e valem se o usuário não responder a
tempo; texto livre com `input type="text|number|date|email|url"` e `<textarea>`; contexto em `<p>`, `<ul>`,
`<table>`, `<pre>`, imagens. A resposta volta ao agente como JSON `{"name": valor}`: rádio/select → o valor
escolhido; grupo de checkbox ou select múltiplo → lista; checkbox único ou rádio sem escolha → omitido;
`number` → número; campos de texto → texto; o "Outro" automático → o texto no lugar do valor do rádio, ou um item
a mais na lista do checkbox.

**Antes de escrever código, confirme tudo isso na implementação instalada do omp** (ache o pacote:
`readlink -f "$(command -v omp)"` e procure a ferramenta `ask` e o formato exato do **resultado** que ela devolve
ao modelo — texto e `details`), e num transcript real
(`~/.omp/agent/sessions/-Desenvolvimento/2026-11-19T15-10-34-863Z_01a1f45c-096f-7746-8ee1-803add143ea8.jsonl`,
chamada `toolu_01WmhqbbxrwyTDz1zP82kr8o`, e o `toolResult` de alguma chamada `ask` já respondida em outra sessão).
Não copie conteúdo de transcripts para o repositório.

Servidor: `server/permissions/registry.ts` aceita perguntas no formato do AskUserQuestion; só as quatro primeiras
posições e até seis opções por pergunta são exibíveis (`shared/activity.ts:13-34`, `registry.ts:261-290`); a
resposta do cartão volta como `answers` por texto da pergunta e rótulos (`:523-563`). O comentário de
`registry.ts:14-20` ("sem sugestões nem perguntas" para o omp) está desatualizado.

A extensão instalada é um atalho para `~/apps/habblaud/mod/habblaud-omp/habblaud.ts` (cópia de produção): depois
do merge, o revisor atualiza `~/apps/habblaud`; só sessões novas do omp carregam o código novo.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-139 -b advisor/139-omp-perguntas-em-formulario-chegam-ao-zaplaud producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes | `npm test` | 0 falhas |
| Traduções | `npm run i18n:check -- --strict` | sai com 0 |

## Escopo

**Pode modificar**:
- `mod/habblaud-omp/habblaud.ts` e os testes dele (procure `omp` em `mod/**/tests` e `server/permissions/omp.test.ts`)
- `server/permissions/registry.ts` (só o comentário desatualizado, e código apenas se a resposta precisar carregar algo que hoje não carrega)
- `CHANGELOG.md` (uma linha), `server/README.md`/`mod/README.md` se descreverem o `ask` do omp

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Clientes Zaplaud (Kotlin e Rust) e a página web: nenhuma mudança.
- Renderizar HTML no Zaplaud. Dependência nova para ler HTML (o formulário é HTML simples; um leitor pequeno e estrito basta).
- Mudar o diálogo nativo do omp.

## Passos

### Passo 1: formato real do `ask` novo

Pela implementação instalada e por transcripts: entrada (`html`, e o que mais houver), e **resultado** exato
devolvido ao modelo para: rádio escolhido; "Outro" com texto; checkbox múltiplo; `select`; formulário cancelado;
tempo esgotado com valores pré-marcados. Anote em NOTES (formas, sem conteúdo de sessões do dono).

**Verificação**: tabela entrada → resultado, com o arquivo:linha da implementação do omp.

### Passo 2: converter formulário simples em perguntas (testes primeiro)

Função pura `formToQuestions(html)`: lê o `<form>` e devolve, ou a lista de perguntas no formato do cartão mais
o mapa para refazer a resposta (`name` do controle, valores por rótulo, tipo), ou `undefined` ("não conversível").
Conversível = todo controle com `name` é rádio, checkbox ou `select` com opções; no máximo 4 perguntas e 6 opções
por pergunta (limites do cartão); texto da pergunta = `<legend>` do `<fieldset>` ou o `<p>` imediatamente antes
do grupo; rótulo da opção = texto do `<label>`; contexto extra (`<p>`, listas) entra na descrição/cabeçalho se
couber, senão o formulário é não conversível (não esconda do dono um contexto que o agente achou necessário).
Não conversível também: qualquer `input` de texto/número/data/e-mail/url, `<textarea>`, `required` em campo que
o cartão não represente, imagem, HTML malformado, controle sem `name`, nomes repetidos entre grupos.
O leitor é estrito: diante de qualquer coisa que não reconheça, devolve `undefined`. Sem `eval`, sem DOM de
navegador, sem executar nada do HTML.

Função pura `answersToFormResult(mapa, answers)`: transforma a resposta do cartão no **mesmo** resultado que o
`ask` nativo devolveria (passo 1), incluindo "Outro" quando o cartão mandar texto livre, e a omissão de grupos
sem escolha.

Testes: um por forma de controle, limites (5 perguntas, 7 opções → `undefined`), campo de texto → `undefined`,
HTML malformado → `undefined`, ida e volta (resposta do cartão → resultado idêntico ao nativo), valores com
caracteres especiais e entidades HTML, rótulos repetidos.

**Verificação**: os testes novos passam (`npx vitest run <arquivo>`); os existentes do omp continuam passando.

### Passo 3: ligar no `ask` da extensão

`ask()` passa a aceitar os dois formatos: o antigo (`questions`, como hoje) e o novo (`html` conversível). Para o
novo: registra as perguntas convertidas, corre o nativo e o cartão em paralelo como hoje, e quando o cartão vence
devolve ao agente o resultado montado por `answersToFormResult`; quando o terminal vence, retira o cartão como
hoje. Formulário não conversível: só o nativo, como hoje (sem cartão). Nenhuma exceção da conversão pode derrubar
o `ask`: qualquer erro → caminho nativo.

**Verificação**: teste do fluxo com o servidor falso que os testes da extensão já usam (formulário conversível
gera `POST /api/permissions` com as perguntas esperadas; resposta do cartão vira o resultado esperado; não
conversível não faz POST). `npm test` completo passa.

### Passo 4: prova com o omp de verdade

Sem tocar na instalação de produção (`~/apps/habblaud`, `~/.omp/agent/extensions/habblaud.ts`): suba o servidor
da sua worktree numa porta livre com diretório de dados temporário, e rode o omp com um diretório de agente
temporário (`OMP_AGENT_DIR`/equivalente — veja `npm run omp:install` e os testes para a forma suportada)
apontando a extensão da worktree; use um modelo barato já configurado no omp do dono **só se** isso for possível
sem copiar credenciais para o repositório nem alterar a configuração dele; faça o agente chamar `ask` com um
formulário de rádio e confira que o cartão aparece em `GET /api/snapshot` (campo `permission` do agente) e que
responder por `POST /api/permissions/:id/decision` destrava o agente com o resultado certo. Se não for possível
montar isso de forma isolada, **não improvise**: relate o que impede e deixe a prova para o revisor.

**Verificação**: evidência do cartão no snapshot e do resultado recebido pelo agente; ou a explicação do impedimento.

### Passo 5: documentação

Corrija o comentário de `registry.ts:14-20`; `CHANGELOG.md` ("Não lançado"): "omp: perguntas feitas em formulário
(escolha única, múltipla e lista) voltam a aparecer no Zaplaud e na página; formulários com texto livre continuam
só no terminal." Atualize os READMEs que descrevem o `ask` do omp.

**Verificação**: `npm run i18n:check -- --strict` e `npm test` saem com 0.

## Plano de testes

Conversão (ida e volta), limites, não conversíveis e fluxo da extensão.

## Critérios de pronto

Todos precisam valer:

- [ ] Formulário de rádio/checkbox/select vira cartão; resposta do cartão devolve ao agente o mesmo resultado do nativo
- [ ] Não conversível continua nativo, sem erro
- [ ] Formato antigo (`questions`) continua funcionando
- [ ] `npm test` verde; prova com omp real feita ou impedimento relatado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 139 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `8885d43`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O resultado do `ask` nativo não puder ser reproduzido pela extensão (formato interno não acessível).
- A API de extensão do omp 18.22.2 não permitir mais substituir o `ask` (confira que `ask()` da extensão está
  sendo chamado hoje: se nem isso ocorre, a causa é outra — relate).
- Precisar mudar os clientes Zaplaud.

## Notas de manutenção

- Formulários com texto livre continuam só no terminal: se forem comuns, o próximo passo é a folha do Zaplaud
  aceitar campo de texto (plano próprio, dois clientes).
- Depois do merge: atualizar `~/apps/habblaud` e abrir sessão nova do omp para valer.
