# Plano 134: Zaplaud Rust: a posição de leitura só é gravada na conversa em que foi capturada, e o roteiro do CI volta a passar

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 134 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat a4c5b2c..HEAD -- zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint zaplaud-rs/scripts/conversation-smoke.py`
> Se algum arquivo do escopo mudou desde `a4c5b2c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (roteamento de comandos entre a tela e o estado; roteiro gráfico com tempo)
- **Depende de**: 127 (mesclado); substitui a investigação 127b
- **Categoria**: bug
- **Planejado em**: commit `a4c5b2c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O workflow `Zaplaud Rust` está vermelho no GitHub desde o plano 127, e por dois motivos misturados. Um é ruído do
roteiro (compara recortes de tela 1 s depois da troca, antes de a tela assentar; e uma espera fixa na reabertura do
cache). O outro é defeito real do cliente: trocar de conversa logo depois de girar a roda ainda pode gravar, na
conversa de destino, uma posição de leitura capturada na conversa de origem. O dono perde o ponto onde parou de ler.
O plano 127 reduziu a frequência (7 em 10 → 2 em 14) cancelando um temporizador, mas não fechou o caminho: os
comandos de posição não dizem de que conversa vieram.

## Estado atual

**Leia primeiro** `~/dados/habblaud/plano-127b/HANDOFF.md` (passagem de serviço do executor anterior, com medições,
arquivos e comandos) e os auxiliares na mesma pasta (`run-127b`, `client-127b-slow`, `probe-127b.py`,
`fonts-127b.conf`). Resumo do que está **provado**:

- Sem girar a roda, o recorte da tela difere em 10 de 18 trocas após 1 s e em 0 de 18 após 2,6 s: comparar pixels
  1 s depois da troca mede o assentamento da tela, não a roda.
- Com roda e troca imediata, em 2 de 14 casos a posição **persistida** da conversa de destino mudou (âncora
  `local:window-truncated`, deslocamento 0 → outra entrada, deslocamentos −21,8 e −145 px), lida em
  `state/data/local_state.json` do diretório temporário do roteiro.
- Caminho no código (base `producao`): o `.slint` captura a posição por um temporizador de 16 ms
  (`ui/conversation_view.slint:283-287`, `changed capture-request` em `:117-126`) e chama
  `reading-position(entry, row, offset, following)` **sem identidade de conversa**. No Rust,
  `Command::Position(bool)` e `Command::Anchor(String, String, f32, bool)` (`src/conversation_view.rs:29-30,715-729`)
  entram num canal compartilhado; o roteador (`:778-797`) entrega ao estado da conversa **atual**, que aplica em
  `run.reading` e agenda gravar (`:543-555`), com a chave da sessão ativa (`:95-100`).

O que é **hipótese**: que só esses caminhos gravam posição atrasada (não foi instrumentado). A worktree
`/tmp/habblaud-127b` (branch `advisor/127b-zaplaud-rs-troca-rapida-no-ci`) tem uma tentativa **não commitada e não
validada** (guarda por geração só no `.slint`, e um roteiro muito alterado que falha antes, em
"Conversa Claude não abriu"). Use como referência de leitura; **não parta dela**.

Falhas atuais do CI no roteiro de conversa (`zaplaud-rs/scripts/conversation-smoke.py`):
`:292 A roda da conversa anterior moveu o histórico OMP` (run `44507322068`) e
`:249 Reabertura 20 saiu do fim` (run `44512351524`): a segunda é uma espera fixa depois de reabrir o cache.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-134 -b advisor/134-zaplaud-rs-posicao-de-leitura-presa-a-conversa-de-origem producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Roteiros | matriz do passo do CI com `xvfb-run` | sai com 0 |
| Lint | `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings` | sai com 0 |

## Escopo

**Pode modificar**:
- os três arquivos de "Arquivos afetados"
 - `CHANGELOG.md` (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Reescrever a rolagem ou os temporizadores dos planos 126/127 além do necessário para identificar a origem.
- Afrouxar verificações do roteiro; mudar limites de memória (PSS) do roteiro.
- `android/`. `plans/`.

## Passos

### Passo 1: base limpa e medição

Worktree nova a partir de `producao`. Build debug (`CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-134`), `npm ci`.
Copie o binário da base para um nome próprio (ex.: `zaplaud-base`). Com os auxiliares do handoff (fonte DejaVu via
`FONTCONFIG_FILE`, cliente preso a 2 CPUs com carga em uma), rode 20 trocas com roda e 20 sem, lendo a posição
persistida da conversa de destino antes da troca e 3 s depois.

**Verificação**: tabela "posição persistida mudou" (com roda / sem roda). Espera-se > 0 com roda e 0 sem. Se der
0 com roda em 20, aumente para 40; se ainda 0, relate e siga (a correção se justifica pela leitura do código).

### Passo 2: identificar a origem de cada comando de posição

No Rust, cada reset de conversa já incrementa um contador (`reset-request`, plano 127). Faça a posição carregar a
identidade de onde foi capturada, de ponta a ponta: o `.slint` lê a geração **no momento da captura** e a envia
junto em `reading-position(...)`; `Command::Anchor`/`Command::Position` passam a levar essa geração (ou a chave da
sessão); na entrega, o roteador/estado **descarta** o comando cuja geração não é a da conversa ativa. A decisão
de descartar fica no Rust (um ponto só), não espalhada em guardas no `.slint`. Mantenha os cancelamentos do 127.

**Verificação**: `cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings`;
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 6`. Se a decisão de descarte
puder ser uma função pura (geração do comando × geração ativa), dê a ela um teste de unidade.

### Passo 3: provar no mesmo ensaio

Repita o ensaio do passo 1 com o binário corrigido.

**Verificação**: 0 mudanças de posição persistida em 40 trocas com roda; e a rolagem normal continua gravando
posição: role para o histórico numa conversa, espere 2 s, a posição persistida muda para a nova âncora (se não
mudar, o descarte está largo demais).

### Passo 4: roteiro: verificar o que importa

Em `conversation-smoke.py`, partindo da versão da `producao`: (a) troque a comparação de recorte em `:292` por:
posição persistida da conversa de destino igual antes da troca e 3 s depois (lida do `local_state.json` do
diretório temporário), repetida em 5 trocas com roda dentro do mesmo roteiro, mais a conferência de que a
conversa de destino mostra o botão de voltar ao fim (está no histórico); (b) troque a espera fixa da reabertura
(`:240-249`) por espera de condição com prazo pela mesma verificação que já existe (a última mensagem copiada),
sem remover nenhuma reabertura nem o limite de PSS; (c) em qualquer falha desses trechos, salve a tela inteira em
`/tmp/zaplaud-rs-*` para o workflow enviar.

**Verificação**: com o binário **da base** (`zaplaud-base`), a verificação (a) falha em pelo menos 1 de 5
execuções do roteiro sob a condição lenta (prova de que acusa o defeito; se não falhar em 5, rode 10 e relate a
taxa — uma verificação que nunca falha na base não serve). Com o binário corrigido: roteiro de conversa 10× por
tema com DejaVu sob condição lenta e 5× por tema com a fonte padrão, tudo verde.

### Passo 5: matriz

Matriz do CI (2 temas × 3 roteiros) uma vez por fonte; `python3 -m py_compile zaplaud-rs/scripts/*.py`.
`CHANGELOG.md`: "Zaplaud desktop: trocar de conversa logo depois de rolar não altera mais o ponto de leitura da
outra conversa."

**Verificação**: tudo sai com 0; `git status` limpo; processos e target removidos.

## Plano de testes

O roteiro de conversa (verificação nova de posição persistida) e, se houver, o teste de unidade do descarte.

## Critérios de pronto

Todos precisam valer:

- [ ] Tabela antes/depois de mudanças de posição persistida (com e sem roda)
- [ ] Comandos de posição levam a origem e são descartados fora dela, num ponto só do Rust
- [ ] A verificação nova do roteiro falha na base e passa 30 vezes no corrigido (10+10 DejaVu lento, 5+5 fonte padrão)
- [ ] Matriz, fmt, clippy e testes ok; nenhum limite afrouxado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 134 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `a4c5b2c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Com a origem identificada e o descarte no lugar, a posição persistida ainda mudar: há outro escritor; relate qual
  (instrumente) em vez de empilhar guardas.
- O roteiro da `producao` intocada falhar em outro ponto sob a condição lenta (ex.: "Conversa Claude não abriu"):
  capture a tela, diga se é coordenada/tempo do roteiro e corrija só se for; se for o cliente, relate.

## Notas de manutenção

- Prova final: workflow `Zaplaud Rust` verde no GitHub. Depois, instalar o desktop no Mac e no miranote.
- A worktree `/tmp/habblaud-127b` pode ser removida pelo revisor depois deste plano.
