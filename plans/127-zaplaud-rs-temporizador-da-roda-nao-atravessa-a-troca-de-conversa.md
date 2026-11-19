# Plano 127: Zaplaud Rust: o temporizador de assentamento da roda é cancelado ao trocar de conversa

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 127 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat c017628..HEAD -- zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs zaplaud-rs/scripts/conversation-smoke.py`
> Se algum arquivo do escopo mudou desde `c017628`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: LOW-MED
- **Depende de**: 126 (mesclado)
- **Categoria**: bug
- **Planejado em**: commit `c017628`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O plano 126 criou o temporizador `scroll-settle` (220 ms depois do último evento de roda). Em janela larga a
`ConversationView` continua viva ao trocar de conversa. Se o usuário gira a roda no fim da conversa A e clica na
conversa B dentro de 220 ms, o temporizador dispara já com o estado de B: com as linhas ainda vazias a lista está
"no fim" (`content-y = end-y = 0`), então ele grava `following = true` e chama `position(true)`. A posição de
leitura salva de B (o ponto do histórico onde o usuário parou) é trocada por "no fim". Achado da revisão em
2026-11-19, depois do merge; confirmado por leitura, ainda não reproduzido.

## Estado atual

- `zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint`: `scroll-settle := Timer { interval: 220ms; triggered => { self.stop(); messages.scroll-from-end = false; if following || messages.content-y <= messages.end-y + 2px { following = true; messages.content-y = messages.end-y; position(true); } } }` (~`:284-297`);
  iniciado/reiniciado no `scrolled` da lista (~`:374-379`); parado só em `jump-to-end()` (~`:246-249`).
- `zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs:168-182`: no `reset` (troca de conversa) o Rust repõe
  `restore-pending`, `anchor-*`, `following = reading.following` e só incrementa `end-request` (que chama
  `jump-to-end()` e para o temporizador) quando a conversa nova está em modo "seguir o fim". Para conversa com
  posição de leitura no histórico, nada cancela o temporizador pendente.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-127 -b advisor/127-zaplaud-rs-temporizador-da-roda-nao-atravessa-a-troca-de-conversa producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Roteiros | matriz do passo do CI com `xvfb-run` | sai com 0 |

## Escopo

**Pode modificar**:
- os três arquivos acima
 - `CHANGELOG.md` só se o defeito tiver chegado a uma versão instalada (não chegou: sem linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- A lógica de seguir o fim do plano 126 fora deste cancelamento. `android/`. `plans/`.

## Passos

### Passo 1: reproduzir

Build debug (`CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-127`), `npm ci`. Sob `xvfb-run`, com a fixture: abra uma
conversa B longa, role para o histórico (Home ou roda) e espere a posição ser salva; abra a conversa A (longa,
no fim); gire a roda para baixo no fim de A e, em menos de 220 ms, clique em B. Espere 1 s.

**Verificação**: no binário atual B aparece no fim (ou com `following` verdadeiro) em vez de no ponto salvo, em
pelo menos parte das tentativas; registre quantas de 10. Se não reproduzir em 10 tentativas com o intervalo
controlado (injete o clique logo após o evento de roda, sem `sleep`), relate os tempos medidos e siga para o
passo 2 mesmo assim: a correção é justificada por leitura.

### Passo 2: cancelar em toda troca

Em todo `reset` de conversa, antes de restaurar o estado: pare `scroll-settle` e zere `scroll-from-end`,
independentemente de `reading.following`. Forma sugerida, seguindo o padrão de `end-request`: um contador
`reset-request` em `ConversationViewState`, incrementado no bloco `if reset` do Rust, com
`changed reset-request => { scroll-settle.stop(); messages.scroll-from-end = false; }` no `.slint`. Como os
`changed` do Slint são entregues depois, proteja também o disparo: `triggered` não faz nada se
`restore-pending` for verdadeiro ou `anchor-ticks > 0` (restauração em curso).

**Verificação**: a reprodução do passo 1 dá 0 de 10 com a posição de B perdida; B abre no ponto salvo.

### Passo 3: roteiro e regressão

Acrescente ao `conversation-smoke.py` o caso "roda no fim de A, troca imediata para B com posição no histórico:
B abre no ponto salvo". Rode: conversa 5× por tema com DejaVu (`FONTCONFIG_FILE` temporário, como no plano 126)
e 5× com a fonte padrão; matriz do CI (2 temas × 3 roteiros) 1× em cada fonte; `cargo fmt --check`; clippy do
workspace com `-D warnings`; testes de core/store.

**Verificação**: tudo sai com 0; os casos de roda do plano 126 continuam passando.

## Plano de testes

O caso novo no roteiro de conversa.

## Critérios de pronto

Todos precisam valer:

- [ ] Troca dentro de 220 ms não altera a posição de leitura da conversa aberta
 - [ ] Roteiro cobre o caso; matriz, fmt, clippy e testes ok
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 127 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `c017628`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A correção exigir mudar a lógica de seguir o fim além do cancelamento.
 - O caso não puder ser automatizado de forma determinística: entregue a correção com prova avulsa e explique.

## Notas de manutenção

- Prova no GitHub depois do merge. Reinstalar o desktop no Mac e no miranote.
