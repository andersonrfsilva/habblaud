# Plano 119: Zaplaud Rust: lista de entregas da sessão com download, como no Kotlin (plano 117)

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 119 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat ae86474..HEAD -- zaplaud-rs/crates docs/zaplaud-paridade.md`
> Se algum arquivo do escopo mudou desde `ae86474`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: M
- **Risco**: MED (tela nova no desktop, que não pode ser aberta na execução)
- **Depende de**: 117 e 118 (mesclados)
- **Categoria**: direction → feature
- **Planejado em**: commit `ae86474`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O cliente Kotlin ganhou "Entregas da sessão" (plano 117): pelo menu da conversa, a lista dos trabalhos concluídos
daquela sessão, com o pedido, a resposta e os arquivos, e o download de cada arquivo. A regra do projeto é os
dois clientes Zaplaud andarem juntos; a pendência está registrada em `docs/zaplaud-paridade.md`. Este plano leva
o mesmo recurso ao desktop Rust, com as mesmas regras de leitura e os mesmos textos.

## Estado atual

**Referência de comportamento** (leia antes): os commits do plano 117, `git log --oneline b9742d5^..c16b755 -- android/`,
e os arquivos `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Files.kt` (funções `parseDeliveries`,
`deliveriesOf`, `readDeliveries`, `downloadDelivery`) e `DeliveriesSheet.kt`.

**Contrato do servidor** (não muda). `GET /api/deliveries` → `{ "deliveries": [...] }`, até 60 itens de todas as
sessões, mais recentes primeiro; cada item: `id`, `account`, `provider` (ausente = `claude`), `sessionId`,
`request`, `headline`, `summary?`, `text` (markdown, até 40.000), `finishedAt` (epoch ms), `attachments[]` com
`id`, `name`, `kind`, `caption?`. `GET /api/deliveries/file/<id do anexo>` → bytes do anexo (até 25 MiB). Login
obrigatório; sem a permissão `terminal`, 403; id de anexo desconhecido, 404.

**Regras que o Kotlin aplica e o Rust deve repetir:**
- item sem `id`, `account` ou `sessionId` é descartado; anexo sem `id` ou `name` é descartado; textos ausentes viram vazio;
- a lista mostrada é a das entregas cuja `provider`+`account`+`sessionId` batem com a sessão aberta, na ordem do servidor;
- o corpo de `/api/deliveries` é lido até 8 MiB; acima disso é erro;
- 401 → fluxo de login vencido; 403 → "Entregas bloqueadas pelo servidor."; outra falha → "Não foi possível carregar as entregas.";
- a resposta (`text`) é mostrada como **texto simples** selecionável, sem markdown e sem carregar imagens;
- "Baixar" usa a mesma rotina de download, progresso, cancelamento e limpeza da janela de arquivos.

**No Rust**: `zaplaud-rs/crates/zaplaud-core/src/api/files.rs` (rotas e download de arquivos, com leitura em blocos)
e `src/model.rs` (modelos); testes de transporte em `crates/zaplaud-core/tests/transport.rs` e de regras em
`tests/rules_reducers.rs`. Tela de arquivos: `crates/zaplaud-ui/src/files.rs` e `ui/files.slint` (use como molde
da janela nova). Menu da conversa: `crates/zaplaud-ui/src/conversation_view.rs` e `ui/conversation_view.slint`
(procure o item que abre arquivos). Os textos `deliveries_*` e o plural `deliveries_files` já existem em
`android/shared/src/commonMain/composeResources/values/strings.xml` e chegam ao Rust pelo gerador
(`crates/zaplaud-ui/resource_gen.rs`); veja como o plural `unread_replies` é usado no Rust para repetir o padrão.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-119 -b advisor/119-zaplaud-rust-entregas-da-sessao producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Formatação | `cd zaplaud-rs && cargo fmt --check` | sai com 0 |
| Lint | `cargo clippy --locked --workspace --all-targets -j 4 -- -D warnings` | sai com 0 |
| Testes sem tela | `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4` | todos passam |
| Build release | `cargo build --release --locked -p zaplaud-ui --bin zaplaud -j 4` | sai com 0 |

## Escopo

**Pode modificar**:
- `zaplaud-rs/crates/**` (código, `.slint` e testes)
- `docs/zaplaud-paridade.md` (linha "Entregas da sessão")
- `CHANGELOG.md` (uma linha em "Não lançado")

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `android/`, `server/`, `strings.xml` do Kotlin (os textos já existem).
- Prévia de arquivo, miniaturas, lista global de entregas, mudança no destino do aviso.
- Qualquer teste com tela (Xvfb, roteiros `scripts/*-smoke.py`).

## Passos

### Passo 1: base

`export CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-119` (diretório próprio: o cache de outro checkout guarda caminhos
absolutos no script de build e falha). `npm ci` na raiz da worktree. Em `zaplaud-rs/`: `cargo fmt --check`,
`cargo clippy --locked -p zaplaud-core -p zaplaud-store --all-targets -j 4 -- -D warnings`,
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4`.

**Verificação**: os três passam (base: 173 testes, 1 ignorado). Falha na base é condição de PARADA.

### Passo 2: modelo, leitura e download em `zaplaud-core`, com testes primeiro

Modelos `Delivery` e `DeliveryAttachment`; função pura de leitura do JSON e função pura de filtro pela chave da
sessão; chamada `deliveries()` com o teto de 8 MiB e a classificação de erros acima; `download_delivery(id, ...)`
reaproveitando a rotina de download em blocos de `api/files.rs`, com o id codificado no caminho.

Testes (escreva antes, veja falhar, depois implemente): leitura com itens válidos, item sem `id` descartado,
anexo sem `name` descartado, `provider` ausente vira `claude`, filtro por sessão mantendo a ordem; transporte
com o servidor falso que `tests/transport.rs` já usa: corpo acima de 8 MiB → erro, 403 → erro classificado como
bloqueio, download grava bytes idênticos e pede `/api/deliveries/file/<id codificado>`, 404 no download → erro.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -j 4` passa com os testes novos.

### Passo 3: janela de entregas e item no menu da conversa

Crie a janela no molde da de arquivos (`src/files.rs` + `ui/files.slint`): título `deliveries_title`, botão
`deliveries_refresh`, estados carregando/vazio/erro/bloqueado, lista com manchete (até 2 linhas), horário, resumo
(até 3 linhas) e contagem de arquivos (plural `deliveries_files`); expandir uma entrega mostra `deliveries_request`,
o pedido, a resposta como texto simples selecionável e os anexos com `deliveries_download`. O download usa o
mesmo componente de progresso/cancelamento da janela de arquivos e a mesma pasta de destino. Item
`deliveries_menu` no menu da conversa, logo depois do de arquivos, só para sessão principal aberta.

**Verificação**: `cargo check --locked -p zaplaud-ui -j 4` e
`cargo clippy --locked --workspace --all-targets -j 4 -- -D warnings` saem com 0.

### Passo 4: fechar

`cargo fmt --check`; clippy do workspace; testes de `zaplaud-core` e `zaplaud-store`;
`cargo build --release --locked -p zaplaud-ui --bin zaplaud -j 4`. Atualize `docs/zaplaud-paridade.md` (linha
"Entregas da sessão": Rust "sim") e acrescente uma linha ao `CHANGELOG.md`.

**Verificação**: os quatro comandos saem com 0; `grep -n 'Entregas da sessão' docs/zaplaud-paridade.md` não tem "pendente".

## Plano de testes

Os testes de `zaplaud-core` do passo 2. A janela não tem teste automatizado neste plano: precisa de conferência manual do dono.

## Critérios de pronto

Todos precisam valer:

- [ ] Testes novos de leitura, filtro, limite e download passam em `zaplaud-core`
- [ ] `cargo fmt --check`, clippy do workspace com `-D warnings`, testes de core/store e build release saem com 0
- [ ] Item de menu e janela existem e usam só textos `deliveries_*` gerados do `strings.xml`
- [ ] `docs/zaplaud-paridade.md` e `CHANGELOG.md` atualizados; nada alterado em `android/` ou `server/`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 119 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `ae86474`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A base não passar no passo 1.
- O gerador de textos não expuser o plural `deliveries_files` de forma utilizável no Slint.
- A janela de arquivos não puder ser reaproveitada sem mudar o comportamento dela.
- A parte de tela não compilar depois de duas tentativas: entregue os passos 1 e 2 e relate.

## Notas de manutenção

- A janela não foi aberta na execução: o dono precisa conferir no desktop (menu da conversa → Entregas).
- Se o servidor ganhar filtro por sessão ou resposta compacta, os dois clientes mudam juntos.
