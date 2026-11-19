# Plano 141: Zaplaud Rust: a conversa aberta é gravada no cache com prazo fixo, mesmo com o agente escrevendo sem parar

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 141 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat bdd442d..HEAD -- zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs`
> Se algum arquivo do escopo mudou desde `bdd442d`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW-MED
- **Depende de**: 130 (mesma política no Kotlin)
- **Categoria**: bug
- **Planejado em**: commit `bdd442d`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O desktop agenda a gravação do cache da conversa para 1 ou 3 segundos depois de cada novidade e **reinicia** essa
contagem a cada novidade. Com um agente escrevendo sem parar, a contagem nunca termina e nada é gravado até ele
pausar. Se o processo morrer nesse intervalo (travar, ser morto, a máquina desligar), perde-se o trecho recebido
desde a última gravação (é baixado de novo) e a posição de leitura do período. O Kotlin foi corrigido no plano 130;
decisão do dono em 2026-11-19: igualar o Rust.

## Estado atual

`zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs` (base `producao`): `save_at: Option<Instant>` (`:521`) é
reatribuído para `agora + 1 s` em dois pontos (`:556`, `:565`) e para `agora + 3 s` em outros dois (`:611`, `:617`);
o `select!` grava quando `sleep_until(save_at)` vence (`:624`) e zera. Trocar de conversa, fechar e `Drop` da
sessão gravam na hora (veja `run.save()` nos caminhos de encerramento e `SessionRun::drop`). Confirme as linhas:
o plano 134 mexeu neste arquivo.

Política do Kotlin (plano 130, `ConversationCacheSchedule` em `ReadingController.kt`): a primeira mudança abre uma
janela fixa de 3 s; as seguintes não adiam; gravação imediata ao fechar, trocar e parar.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-141 -b advisor/141-zaplaud-rs-cache-de-conversa-com-prazo-fixo producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes | `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` | todos passam |

## Escopo

**Pode modificar**:
- `conversation_view.rs` (e um módulo pequeno ao lado, se extrair a política)
 - `docs/zaplaud-paridade.md` (a linha de cache), `CHANGELOG.md` (uma linha)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Formato do cache, limite de entradas, os caminhos de gravação imediata, rolagem e posição de leitura (planos 126-134). `plans/`.

## Passos

### Passo 1: política testável, teste primeiro

Extraia a decisão do prazo para uma função ou tipo pequeno e puro (ex.: dado o prazo pendente e o instante de um
novo evento com seu atraso desejado, devolve o prazo a manter): se já há prazo pendente, **mantém o mais cedo**;
se não há, abre `agora + atraso`. Testes de unidade com instantes simulados (`tokio::time::pause`/`advance` ou
aritmética de `Instant`): (a) eventos a cada 200 ms por 10 s → há gravação pelo menos a cada 3 s (hoje: nenhuma
até parar); (b) um evento isolado grava depois do seu atraso; (c) evento com atraso de 1 s chegando quando há
um pendente para daqui a 3 s adianta para 1 s; o contrário não adia.

**Verificação**: o teste (a) falha com a lógica atual (escreva-o contra a função extraída reproduzindo a
atribuição de hoje, veja falhar, depois troque) e passa depois.

### Passo 2: aplicar nos quatro pontos

Os quatro pontos que reatribuem `save_at` passam a usar a função. Os caminhos de gravação imediata não mudam.

**Verificação**: `cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings`;
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` (com `npm ci`
na raiz antes: os testes de contrato sobem o servidor Node).

### Passo 3: roteiros e documentação

Sob `xvfb-run`, o roteiro de conversa (`zaplaud-rs/scripts/conversation-smoke.py`) 3 vezes por tema — ele confere
o cache com 220 entradas e as reaberturas. Paridade e `CHANGELOG.md` ("Zaplaud desktop: a conversa aberta é
gravada no cache a cada poucos segundos mesmo durante uma resposta longa.").

**Verificação**: 6/6 no roteiro; `python3 -m py_compile zaplaud-rs/scripts/*.py`.

## Plano de testes

Os três casos de unidade da política.

## Critérios de pronto

Todos precisam valer:

- [ ] Fluxo contínuo grava pelo menos a cada 3 s (teste)
 - [ ] fmt, clippy, testes e roteiro de conversa ok
 - [ ] Paridade e CHANGELOG atualizados
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 141 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `bdd442d`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A mudança alterar o que o roteiro de conversa mede (cache, reabertura, memória) de forma não explicada.

## Notas de manutenção

- Depois do merge, reinstalar o desktop no Mac e no miranote.
