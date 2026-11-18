# Plano 101: Zaplaud: o roteiro de capturas retoma uma conversa elegível e termina nos dois temas (fecha 054 e destrava 055–061)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 101 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- scripts/android-screens.sh server/test/android-fixture.ts`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW (só script e fixture de teste)
- **Depende de**: autorização do dono para usar o emulador `habblaud034`
- **Categoria**: tests / dx
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Sete planos de interface (055 a 061) estão implementados e marcados IN PROGRESS no índice apenas porque o
critério "o roteiro de capturas termina com saída 0 nos dois temas" não fecha. O roteiro
(`scripts/android-screens.sh`) para em "Retomar conversa": ele abre uma conversa encerrada da fixture e espera o
botão de retomar, que o app só mostra para sessões que o elenco pode retomar. Segundo a auditoria, as conversas
encerradas que o roteiro escolhe são do provedor Claude, que não é elegível; a fixture já tem um comando para
semear uma sessão encerrada do omp. Consertar o roteiro fecha o 054 e permite marcar 055–061 como DONE.

## Estado atual

`scripts/android-screens.sh:444-466`:

```bash
tap 'Voltar às sessões'
tap Recentes
open_search
enter_text 'Buscar por título, projeto ou agente' encerrada
tap 'Conversa encerrada fictícia'
wait_text 'Retomar conversa'        # o roteiro para aqui (plano 054, BLOCKED)
shot conversa-retomar-icone
tap 'Retomar conversa'
wait_text Confirmar
shot elenco-retomar
tap Confirmar
...
enter_text 'Buscar por título, projeto ou agente' jardim
tap 'Planejamento de jardim'
tap 'Retomar conversa'
wait_text 'Escolha um contato'
```

O roteiro conversa com a fixture por um descritor (`printf 'cast-consume\n' >&3`, linhas 432, 442, 456): a
fixture lê comandos de texto na entrada padrão.

`server/test/android-fixture.ts`:

```ts
// :64
let ompCastEnabled = false;
// :81   seed(sessionId, provider = 'claude', ...)  → o padrão das sessões semeadas é Claude
// :197  o lançador só aceita a conta do omp quando ompCastEnabled
const launcher = new CastLauncher({ ..., account: id => ompCastEnabled && id === OMP_ACCOUNT ? ompDir : !id || id === ACCOUNT ? accountDir : undefined, ... });
// :443-446
else if (command === 'seed-closed-omp') {
  if (!agentId || !OMP_SESSION_ID.test(agentId) || kind) throw new Error('Expected seed-closed-omp <sessionId>');
  ompCastEnabled = true;
  paths.set(agentId, seed(agentId, 'omp'));
```

No app, o botão de retomar depende de `canResumeCast(...)` em
`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` (procure a função; ela decide por
provedor e disponibilidade do elenco). No fluxo de retomar, o diálogo "Escolha um contato" vem **antes** do
"Confirmar" (`CastActions.kt:272`: escolher um membro define `dialog = "resume"`).

Índice: linha 054 de `plans/README.md` descreve o bloqueio; 055–061 citam "gate do roteiro pendente do 054".

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-101 -b advisor/101-zaplaud-roteiro-de-capturas-ate-o-fim producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Sintaxe do roteiro | `bash -n scripts/android-screens.sh` | sai com 0 |
| Tipos (se a fixture mudar) | `npm run typecheck` | sai com 0 |
| Roteiro (com autorização) | `bash scripts/android-screens.sh` | sai com 0 |

## Escopo

**Pode modificar**:
- `scripts/android-screens.sh`
- `server/test/android-fixture.ts` (só se faltar título/projeto na sessão semeada para o roteiro achá-la pela busca)
- `plans/README.md` (linhas 054 a 061, ao final)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Qualquer arquivo Kotlin do app: se o botão não aparecer para uma sessão omp encerrada semeada, é defeito do
  app e condição de PARADA, não algo a contornar no roteiro.
- Os testes instrumentados (`FixtureUiInstrumentedTest`) e `scripts/android-smoke.ts`.

## Passos

### Passo 1: confirmar a causa lendo o código (sem emulador)

Leia `canResumeCast` em `CastActions.kt` e a função `seed` e os comandos da fixture
(`server/test/android-fixture.ts:81-110` e `:430-560`). Escreva no corpo do commit: (a) qual condição
`canResumeCast` exige; (b) de que provedor são "Conversa encerrada fictícia" e "Planejamento de jardim";
(c) o formato exato do comando `seed-closed-omp` (o identificador tem de casar com `OMP_SESSION_ID`, importado
de `shared/omp-events`).

**Verificação**: você consegue responder aos três itens citando arquivo e linha. Se (b) mostrar que as
sessões já são elegíveis, é condição de PARADA.

### Passo 2: roteiro semeia e retoma uma sessão do omp

Antes do bloco da linha 444, mande à fixture o comando de semear (mesmo mecanismo do `cast-consume`):
`printf 'seed-closed-omp %s\n' "$OMP_CLOSED_ID" >&3`, com `OMP_CLOSED_ID` definido no topo do roteiro com um
identificador válido. Troque a busca e o toque para o título que a sessão semeada recebe (veja em `seed` qual
é; se o título não for distinguível, acrescente um parâmetro opcional de título ao comando da fixture).

Corrija a ordem do primeiro fluxo: depois de `tap 'Retomar conversa'`, espere `'Escolha um contato'`, toque no
contato da fixture e só então espere `Confirmar`.

**Verificação**: `bash -n scripts/android-screens.sh` → sem erro de sintaxe; `npm run typecheck` → sai com 0
(se a fixture mudou).

### Passo 3: rodar nos dois temas (precisa de autorização)

Com autorização do dono e o AVD `habblaud034` disponível: `bash scripts/android-screens.sh`.

**Verificação**: saída 0; `ls /tmp/android-screens/*-claro.png | wc -l` igual a
`ls /tmp/android-screens/*-escuro.png | wc -l`, ambos maiores que 25 (confira os sufixos reais que o roteiro
usa em `$THEME`).

Sem autorização: pare aqui, deixe os passos 1 e 2 commitados e registre no índice "roteiro corrigido, não
executado".

### Passo 4: fechar o índice

Com o passo 3 verde: em `plans/README.md`, marque 054 como DONE (com o commit) e, em cada uma das linhas 055,
056, 057, 058, 059, 060 e 061, troque IN PROGRESS por DONE acrescentando "gate do roteiro fechado pelo 101".
Olhe as capturas de cada plano antes de marcar (ícones, linhas compactas, botão segmentado, cores de estado,
busca, título em duas linhas, campo de mensagem); se alguma estiver visivelmente errada, deixe aquele plano
IN PROGRESS com a observação.

## Plano de testes

O próprio roteiro é o teste (passo 3).

## Critérios de pronto

Todos precisam valer:

- [ ] `bash -n scripts/android-screens.sh` sai com 0
- [ ] `grep -n 'seed-closed-omp' scripts/android-screens.sh` → pelo menos 1 ocorrência
- [ ] Com autorização: roteiro sai com 0 e gera o mesmo número de capturas nos dois temas
- [ ] Índice: 054 atualizado; 055–061 atualizados **somente** se o passo 3 rodou
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 101 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A leitura do passo 1 mostrar que as sessões do roteiro já são elegíveis para retomar: a causa é outra; relate.
- O botão "Retomar conversa" não aparecer para a sessão omp semeada: é defeito do app; relate com a captura.
- O dono não autorizar o emulador: entregue os passos 1 e 2 e registre.

## Notas de manutenção

- Texto de interface que muda quebra o roteiro (`wait_text`); quem mudar `strings.xml` deve procurar o texto
  antigo em `scripts/android-screens.sh`.
- O README (`README.pt-BR.md:267-268`) manda rodar este roteiro; depois deste plano a instrução volta a valer.
