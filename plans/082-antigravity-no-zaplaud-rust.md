# Plan 082: Zaplaud desktop (Rust + Slint): escolher um modelo `agy/…` passa o agente para o Antigravity

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat ba5a285..HEAD -- zaplaud-rs/crates`
> As chaves novas em `android/shared/src/commonMain/composeResources/values/strings.xml` (plano 081) são esperadas.
> Se `zaplaud-rs/crates` mudou, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW no código; a janela **não é testada por máquina** (ver abaixo)
- **Depends on**: plans/079-elenco-com-antigravity.md, plans/083-troca-de-ferramenta-com-contexto.md e
  plans/081-antigravity-no-zaplaud-kotlin.md (as chaves de texto e as regras de referência)
- **Category**: direction
- **Planned at**: commit `ba5a285`, 2026-11-14 (reescrito no mesmo dia: saiu o "Novo no Antigravity"; o modelo decide)

## Why this matters

O Zaplaud desktop (`zaplaud-rs/`) anda junto do Zaplaud Kotlin: a regra do repositório (`AGENTS.md`) é que mudança
de comportamento em um entra no outro na mesma entrega. O plano 081 faz o Kotlin oferecer os modelos `agy/…` no
menu de modelo, avisar antes de trocar de ferramenta, mostrar o contexto pendente e dar STOP e conversa nova aos
agentes do Antigravity CLI (`agy`). Este plano faz o mesmo no Rust, com as mesmas regras e os mesmos textos (o Rust
gera os dele a partir do `strings.xml` do Kotlin).

## Regra do dono sobre testes (não negociável)

**Nenhum teste em Xvfb, emulador, simulador, máquina virtual ou com a janela aberta.** O dono valida o cliente à
mão. Este plano termina em `cargo clippy`, `cargo build --release` e nos testes que **não precisam de tela**. É
proibido: `xvfb-run`, os roteiros `zaplaud-rs/scripts/*-smoke.py`, `cargo run`, e qualquer teste que abra janela. Se
um `cargo test` falhar por falta de display, isso **não** é para contornar com Xvfb: rode só os pacotes indicados
abaixo e relate o que ficou de fora.

## Current state

### O contrato do servidor (planos 078, 079 e 083)

- Modelo cujo id começa com `agy/` roda no Antigravity (`agy/default` = padrão do `agy`). `google-antigravity/…` é
  modelo do omp e **não** é `agy/`.
- Agente do Antigravity no snapshot: `provider: "antigravity"`, `model: "agy/<valor>"`, `canMessage`, `cast` e
  `castOpened` quando aberto pelo elenco.
- Agente com `castOpened`: `models` traz os modelos das **duas** ferramentas (o servidor monta a lista).
- `POST /api/messages/control {agentId, model, expectedSessionId}`: mesma resposta de hoje. Modelo da mesma
  ferramenta: troca na conversa (vale para o Antigravity). Modelo da outra ferramenta: **troca de ferramenta**; o
  agente atual sai e o mesmo personagem reaparece na outra, com outro id de sessão. Recusas voltam como erro com
  motivo em texto.
- Depois de uma troca, o agente novo tem `"handoff": true` até a primeira mensagem (a conversa anterior vai anexada
  a ela).
- Antigravity: mensagens sem `deliverAs`; controles `stop: true` **ou** `command: "new"`; sem `thinking`, shake,
  conversa paralela.
- `GET /api/cast/models` traz `"agyModels": ["agy/default", …]` quando o Antigravity está ligado. Retomar conversa
  não muda de corpo: o servidor descobre sozinho se é do Antigravity.

### O Rust hoje

- `zaplaud-rs/crates/zaplaud-core/src/rules/composer.rs:6-28`:
  ```rust
  pub fn supports_session_control(provider: &str) -> bool { matches!(provider, "omp" | "zeroclaw") }
  pub fn delivery_modes(provider: &str) -> &'static [&'static str] {
      match provider { "omp" => &["aside", "followUp", "steer"], "zeroclaw" => &["followUp", "steer"], _ => &[] }
  }
  pub fn deliver_modes_available(session: &SessionItem) -> bool {
      supports_session_control(&session.key.provider) && session.agent_id.is_some() && !session.sub
  }
  pub fn deliver_as<'a>(provider: &str, mode: &'a str) -> Option<&'a str> { … "omp" …, "zeroclaw" …, _ => None }
  ```
  **Atenção:** `deliver_modes_available` reaproveita `supports_session_control`. Liberar o Antigravity ali faria
  aparecer o seletor de modo de entrega para ele; precisa ser separado.
- `composer.rs:1468-1500`, o comando de sessão (modelo, raciocínio, shake):
  ```rust
  Event::Command { agent_id, kind, value, eligible } => {
      if let Some(key) = state.composer.key.clone()
          && key.provider == "omp" && state.visible && !state.composer.loading && !state.composer.sending && eligible
      {
          let mut body = json!({ "agentId": agent_id, "expectedSessionId": key.session_id });
          let field = match kind.as_str() { "shake" => "command", "model" => "model", "thinking" => "thinking", _ => return (state, effects) };
  ```
- `crates/zaplaud-ui/src/composer.rs:709-713`: `global.set_omp_tools(selected.as_ref().is_some_and(|s|
  s.key.provider == "omp" && s.agent_id.is_some() && !s.sub))` liga, de uma vez, modelo, raciocínio e shake na
  interface. `composer.rs:737` (shake) e `aside.rs:96` (conversa paralela) travam por `provider == "omp"`.
- `composer.rs:207-…`: `provider_label(id)` (rótulos de provedor de modelo; id desconhecido volta cru; já tem
  `google-antigravity`).
- `composer.rs:1652-1664`: `can_resume_cast(selected, runtime)` exige `runtime == Some(selected.key.provider)`.
- `crates/zaplaud-core/src/model.rs:155,208`: `pub cast_opened: bool` lido de `flag(o, "castOpened")`;
  `model.rs:420-430`: o dono de uma conversa encerrada só é procurado para `"omp" | "zeroclaw"`;
  `model.rs:1103-1126`: `CastCatalog::parse` e `catalog_runtime`.
- `crates/zaplaud-ui/src/cast.rs`: diálogo do elenco, com um modo de confirmação (`confirming`,
  `set_confirm_label`, `on_confirm`) que serve de modelo para o aviso deste plano.
- Textos: `crates/zaplaud-ui/build.rs` gera as constantes a partir de `android/shared/src/commonMain` (o
  `strings.xml` do Kotlin). O plano 081 cria `provider_antigravity`, `model_agy_default`, `switch_tool_confirm` e
  `handoff_pending`.
- Testes sem tela: `crates/zaplaud-core/tests/{rules_presentation,rules_reducers,transport,zeroclaw_capabilities}.rs`
  e os `#[cfg(test)]` de `zaplaud-core`. Os testes de `zaplaud-ui` podem precisar de display
  (`zaplaud-rs/README.md:24`).

## Commands you will need

Todos a partir de `zaplaud-rs/`:

| Purpose | Command | Expected on success |
|---|---|---|
| Lint | `cargo clippy --workspace --all-targets -j 4 -- -D warnings` | exit 0 |
| Testes do núcleo (sem tela) | `cargo test -p zaplaud-core -j 4` | todos passam |
| Testes do armazenamento | `cargo test -p zaplaud-store -j 4` | todos passam |
| Compilar o aplicativo | `cargo build --release -j 4` | exit 0 |

## Scope

**Editar**: `zaplaud-rs/crates/zaplaud-core/src/rules/composer.rs`, `…/src/model.rs`,
`zaplaud-rs/crates/zaplaud-ui/src/composer.rs` (e o `.slint` correspondente, só para separar "modelo" de "raciocínio
e shake", o aviso e a linha do contexto pendente), `zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs` (só o
rótulo da ferramenta, se for ali que o equivalente do `info_provider` do Kotlin aparece); testes novos em
`zaplaud-rs/crates/zaplaud-core/tests/antigravity_capabilities.rs` (modelo: `zeroclaw_capabilities.rs`);
`docs/zaplaud-paridade.md`; `zaplaud-rs/README.md` se ele lista capacidades por runtime.

**Out of scope** (NÃO toque):
- `server/**`, `client/**`, `android/**` (as chaves de texto vêm do 081; se faltarem, é STOP).
- `crates/zaplaud-ui/src/settings.rs`, `aside.rs`, `cast.rs` além de leitura, bandeja, avisos, tema.
- Um botão "Novo no Antigravity": não existe neste desenho.
- `zaplaud-rs/scripts/**` e qualquer teste que precise de display.
- Empacotar, instalar ou publicar o binário: é do dono.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-082 -b advisor/082-antigravity-zaplaud-rust producao` (com 079, 083 e
  081 já em `producao`).
- Um commit por passo, estilo `feat(zaplaud-rs): modelos agy trocam o agente para o Antigravity`.
- Não faça push nem abra PR.

## Steps

### Step 1: regras puras

Em `rules/composer.rs`:

- `pub fn is_agy_model(model: &str) -> bool` (`agy/` + pelo menos um caractere); `pub fn crosses_tool(provider:
  &str, model: &str) -> bool` (provedor `omp` ou `antigravity`, e `is_agy_model(model) != (provider ==
  "antigravity")`); `pub fn model_choice(provider: &str) -> bool` (`omp` ou `antigravity`).
- `supports_session_control`: aceita `"antigravity"`. `deliver_modes_available`: passa a ser
  `!delivery_modes(&session.key.provider).is_empty() && session.agent_id.is_some() && !session.sub` (igual para omp
  e ZeroClaw, falso para o Antigravity). `deliver_as("antigravity", …)` continua `None`.
- `provider_label`: acrescente `"agy"` → "Antigravity"; `agy/default` aparece com o texto de `model_agy_default`.
- `Event::Command`: a condição `key.provider == "omp"` vira "`omp` para qualquer `kind`; `antigravity` só para
  `kind == "model"`".
- Motivo da recusa: `CommandResult` (`model.rs:695`, hoje só `kind` e `status`) ganha `reason: Option<String>`,
  preenchido quando o resultado é `failed` com o texto do servidor (o `error` do corpo num erro 4xx, ou o campo
  `error` da mensagem consultada). A interface mostra o motivo junto do aviso de falha que já existe. Numa troca de
  ferramenta a sessão atual some durante o acompanhamento: isso não vira "desconhecido" (hoje, em
  `composer.rs:1536-1540`, `state.composer.key != command.key` dá `unknown`; para um comando `model` com
  `crosses_tool`, encerre o acompanhamento sem publicar resultado).
- `tests/antigravity_capabilities.rs`: `is_agy_model` (`google-antigravity/x` não); `crosses_tool` nas quatro
  combinações e falso para `claude`/`codex`/`zeroclaw`; controle de sessão; sem modos de entrega; comando `model`
  aceito e `thinking`/`shake` ignorados para o Antigravity; omp e ZeroClaw inalterados.

**Verify**: `cargo test -p zaplaud-core -j 4` → todos passam, `zeroclaw_capabilities` sem edição.

### Step 2: interface

- `zaplaud-ui/src/composer.rs`: `set_omp_tools` continua valendo para raciocínio e shake; acrescente uma
  propriedade para o **modelo** (`model_choice`), ligada para omp e Antigravity quando há `agent_id` e não é
  subagente. No `.slint`, o item de modelo passa a depender dela.
- Ao escolher um modelo com `crosses_tool`: antes de emitir `Event::Command`, um passo de confirmação com o texto
  `switch_tool_confirm` (com o rótulo do modelo). Use o mecanismo de confirmação que a interface já tem (o do
  diálogo do elenco é o modelo); cancelar não emite nada e o seletor volta ao valor atual.
- `SessionItem` (em `model.rs`) ganha `handoff: bool`, lido de `flag(o, "handoff")`. Com ele verdadeiro, uma linha
  discreta acima da caixa de mensagem com `handoff_pending`.
- Rótulo da ferramenta da sessão: `antigravity` aparece com `provider_antigravity` (onde o desktop mostra o
  equivalente do `info_provider`).

**Verify**: `cargo clippy --workspace --all-targets -j 4 -- -D warnings` exit 0; `cargo test -p zaplaud-core -j 4`
passa.

### Step 3: dono de conversa encerrada e retomar

- `model.rs:420-430`: aceita `"antigravity"`; para ele não compara `account` e exige o `last` com a sessão.
- `CastCatalog` ganha `pub agy: bool` (verdadeiro quando `agyModels` veio não vazio). `can_resume_cast`: conversa do
  Antigravity é retomável quando `catalog.agy`, independente do runtime da instalação; omp e ZeroClaw não mudam.
- Testes em `antigravity_capabilities.rs`: dono; retomável/não retomável; casos antigos sem edição.

**Verify**: `cargo test -p zaplaud-core -j 4 && cargo test -p zaplaud-store -j 4` passam.

### Step 4: compilar tudo e registrar

- `cargo clippy --workspace --all-targets -j 4 -- -D warnings` e `cargo build --release -j 4`.
- `docs/zaplaud-paridade.md`: a linha do Antigravity passa a dizer o que os dois clientes fazem; registre,
  literalmente, que os testes de `zaplaud-ui` e os roteiros com display **não foram rodados** e que a validação é
  manual, do dono.

**Verify**: os dois comandos com exit 0; `git status --short` limpo depois do commit.

## Test plan

Só testes sem tela, no `zaplaud-core`. Roteiro para o dono validar à mão (escreva-o no corpo do último commit): num
agente do omp aberto pelo elenco, escolher um modelo `agy/…` → aviso → confirmar → o personagem reabre como
Antigravity com a linha do contexto pendente; mandar "do que falávamos?"; sem seletor de modo de entrega; STOP e
conversa nova; trocar de modelo dentro do Antigravity sem aviso; voltar para um modelo do omp; retomar conversa
encerrada do Antigravity; agente do omp aberto no terminal não oferece `agy/…`.

## Done criteria

- [ ] `cargo clippy --workspace --all-targets -j 4 -- -D warnings` exit 0
- [ ] `cargo test -p zaplaud-core -j 4` e `cargo test -p zaplaud-store -j 4` passam
- [ ] `cargo build --release -j 4` exit 0
- [ ] `git diff --stat producao -- zaplaud-rs/crates/zaplaud-core/tests/zeroclaw_capabilities.rs zaplaud-rs/scripts android server client` vazio
- [ ] `docs/zaplaud-paridade.md` atualizado; `git status --short` limpo

## STOP conditions

- As chaves `provider_antigravity`, `model_agy_default`, `switch_tool_confirm`, `handoff_pending` não existem no
  `strings.xml` (o 081 não está em `producao`): pare; não crie textos só no Rust.
- Um trecho de "Current state" não bate com o código.
- `cargo test -p zaplaud-core` precisar de display ou de rede externa para passar.
- A interface não tiver um mecanismo de confirmação reaproveitável para o seletor de modelo e a saída for desenhar
  um diálogo novo: relate o que existe.
- Você se vir prestes a usar `xvfb-run`, abrir a janela ou rodar um roteiro de `zaplaud-rs/scripts/`.

## Maintenance notes

- As regras deste plano e as do 081 são as mesmas de propósito; se uma mudar, a outra muda junto
  (`docs/zaplaud-paridade.md`).
- Quem revisar olha: `deliver_modes_available` (não pode abrir modos para o Antigravity), que `thinking` e `shake`
  não saem para o Antigravity, e que cancelar o aviso não emite comando.
- Publicar o binário nas máquinas do dono é dele.
