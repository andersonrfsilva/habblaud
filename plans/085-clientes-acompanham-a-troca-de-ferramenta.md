# Plan 085: Página, Zaplaud Kotlin e Zaplaud Rust acompanham o contato depois da troca de ferramenta

> **Executor instructions**: Este arquivo tem três partes independentes (A: página, B: Kotlin, C: Rust). Execute
> **só a parte que o seu despacho indicar**. Siga os passos, rode cada verificação e confirme o resultado antes de
> avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise.
>
> **Drift check (rode primeiro, só os caminhos da sua parte)**:
> `git diff --stat 924199e..HEAD -- client/src/ui android/shared/src/commonMain zaplaud-rs/crates`
> Se os arquivos da sua parte mudaram, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: M (S por parte)
- **Risk**: LOW
- **Depends on**: contrato do plano 084 (não o código): o sucessor tem o mesmo `cast`
- **Category**: bug
- **Planned at**: commit `924199e`, 2026-11-14

## Why this matters

Escolher um modelo da outra ferramenta (omp ⇄ Antigravity) faz o servidor fechar a sessão e abrir o mesmo contato na
outra. No teste dirigido no Mac, depois de confirmar a troca a tela ficou parada na conversa antiga, marcada
"encerrado", e o contato reaberto apareceu na lista como se fosse outro: a pessoa tem de achá-lo e selecioná-lo à
mão. Os três clientes têm o mesmo defeito, porque só sabem seguir uma sessão nova **da mesma ferramenta** (caso
"Nova sessão"). Este plano faz cada cliente selecionar o sucessor sozinho.

## Regra do dono sobre testes

Nenhum teste em navegador automatizado, emulador, simulador, Xvfb, aparelho ou VM. Cada parte termina em compilação
e testes de unidade sem tela. A conferência na tela é feita depois, pelo revisor, no Mac do dono, com autorização
dele.

## O contrato (igual para as três partes)

- A troca é pedida por `POST /api/messages/control {agentId, model, expectedSessionId}` quando o modelo é da outra
  ferramenta (`agy/…` num agente do omp, ou modelo do omp num agente `antigravity`).
- Se der certo, o agente antigo sai (fica `offline`/encerrado) e aparece um agente principal aberto com **o mesmo
  `cast`** (campo `cast` do agente no snapshot), **outro `id`** e outro `provider`, na mesma origem. Vale para
  personagens do elenco e para "Novo" (`novo-…`).
- **Regra do sucessor**: depois de mandar um pedido de troca de ferramenta a partir de uma conversa selecionada,
  guarde `{origem, cast, chave ou id da conversa antiga, prazo = agora + 90 s}`. Enquanto o prazo vale: se aparecer
  um agente principal aberto (não subagente, não `offline`/`done`), da mesma origem, com o mesmo `cast` e identidade
  diferente da antiga, **selecione-o** e esqueça a pendência. Esqueça também se: o pedido falhar; a pessoa selecionar
  outra conversa por conta própria; o prazo vencer. Sem `cast` na conversa antiga, não há pendência.

## Scope

**Parte A (página)**: `client/src/ui/provider.ts` (+ `provider-antigravity.test.ts`), `client/src/ui/drawer.ts`,
`client/src/ui/index.ts` só se a seleção precisar de um ponto de entrada novo, `CHANGELOG.md`.

**Parte B (Kotlin)**: em `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`: `ReadingScreen.kt`,
`data/MessageController.kt` só se precisar expor o resultado do comando; testes novos em `commonTest`;
`docs/zaplaud-paridade.md`.

**Parte C (Rust)**: `zaplaud-rs/crates/zaplaud-core/src/rules/composer.rs`, `…/src/model.rs` (campo no
`PendingCommand`), `zaplaud-rs/crates/zaplaud-ui/src/composer.rs`; testes em
`zaplaud-rs/crates/zaplaud-core/tests/antigravity_capabilities.rs`; `docs/zaplaud-paridade.md`.

**Out of scope** (NÃO toque): `server/**`, `shared/**`; os arquivos das outras partes; qualquer teste que precise de
tela; `android/desktop`, `jvmMain`, `androidDeviceTest`; `zaplaud-rs/scripts`.

## Git workflow

- Uma worktree por parte, a partir de `producao`: `/tmp/habblaud-085a` (`advisor/085a-pagina-segue-troca`),
  `/tmp/habblaud-085b` (`advisor/085b-kotlin-segue-troca`), `/tmp/habblaud-085c` (`advisor/085c-rust-segue-troca`).
- Commits no estilo `fix(zaplaud): …` / `fix(antigravity): …`. Não faça push nem abra PR.

## Parte A: página

### Current state

- `client/src/ui/index.ts:74-78,107-125`: a seleção é `{ type: 'agent', id }`, trocada por `ctx.select(...)`.
- `client/src/ui/drawer.ts`: a troca de modelo chama `this.control({ model })` (com `confirm` quando
  `crossesTool(this.last?.provider, model)`, função de `client/src/ui/provider.ts`); quando o agente some do
  snapshot a gaveta guarda `last` e mostra o estado "gone" (linhas ~420-424 e ~568-572). Não há regra de sucessor.
- Comandos: `npm run typecheck`; `npx vitest run client/src/ui`; `npm run i18n:check -- --strict`; `npm run build`.

### Steps

1. `client/src/ui/provider.ts`: função pura
   `switchSuccessor(agents: AgentInfo[], from: { id: string; cast?: string }): AgentInfo | undefined` (mesmo `cast`,
   `id` diferente, `kind === 'main'`, `status` nem `offline` nem `done`; sem `cast` → `undefined`). Testes em
   `provider-antigravity.test.ts`: acha o sucessor; ignora o próprio agente, subagentes, encerrados e outro `cast`.
   **Verify**: `npx vitest run client/src/ui/provider` passa.
2. `client/src/ui/drawer.ts`: ao confirmar uma troca de ferramenta, guarde a pendência (`id`, `cast`, prazo de 90
   s). A cada snapshot com pendência válida, `switchSuccessor(...)`; achou → `ctx.select({ type: 'agent', id })` e
   limpa. Limpa também quando o `control` devolve erro, quando a seleção muda para outro agente por ação da pessoa,
   ou no prazo. **Verify**: `npm run typecheck` exit 0; `npx vitest run client/src/ui` passa.
3. `CHANGELOG.md` ("Corrigido"). **Verify**: `npm run i18n:check -- --strict && npm test && npm run build` exit 0.

### Done criteria

- [ ] typecheck, `npm test`, i18n estrito e build com exit 0; `git diff --stat producao -- server shared android zaplaud-rs` vazio; `git status --short` limpo

## Parte B: Kotlin

### Current state

- `ReadingScreen.kt:78-81`:
  ```kotlin
  internal fun newSessionTarget(sessions: List<SessionItem>, previous: SessionKey, agentId: String): SessionItem? =
      sessions.firstOrNull { it.agentId == agentId && it.key.origin == previous.origin &&
          it.key.provider == previous.provider && it.key.account == previous.account && it.key != previous }
  ```
  usado só pelo fluxo "Nova sessão": `newTarget` é definido antes de `newSession` (linhas ~227-231) e um
  `LaunchedEffect` (linhas ~203-223) abre o alvo quando ele aparece. Exige o mesmo `provider`, então não acha o
  sucessor de uma troca de ferramenta.
- A troca é confirmada no diálogo `switchModel` (`AlertDialog` com `switch_tool_confirm`), que chama
  `controller.messages.setModel(selected.agentId, model)`; o resultado vai para `composer.commandResult`
  (`CommandResult(kind, status, reason)`), e não é publicado quando a sessão muda.
- `SessionItem` tem `cast` (leia em `data/ApiModels.kt`).
- Comando (em `android/`): `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug --max-workers=2` → `BUILD SUCCESSFUL`. Se o Gradle não achar o SDK, crie `android/local.properties`
  com `sdk.dir=/home/anderson/Android/Sdk` (arquivo ignorado pelo git; não commite). Nunca rode `connected*`,
  `:shared:jvmTest`, `adb` nem scripts `android-*`.

### Steps

1. Função pura, ao lado de `newSessionTarget`:
   `internal fun switchToolTarget(sessions: List<SessionItem>, previous: SessionKey, cast: String?): SessionItem?`
   (mesma origem, `cast` igual e não nulo, `agentId != null`, não subagente, `key != previous`). Testes no host
   (`commonTest`, por exemplo `SwitchToolTargetTest.kt`): acha; ignora outra origem, outro `cast`, subagente, sessão
   sem agente, a própria sessão, `cast` nulo. **Verify**: `./gradlew :shared:testAndroidHostTest` → `BUILD SUCCESSFUL`.
2. `ReadingScreen.kt`: ao confirmar a troca no diálogo, guarde a pendência (chave antiga, `cast`, prazo de 90 s) num
   estado irmão do `newTarget`; um `LaunchedEffect` equivalente ao existente abre o alvo quando
   `switchToolTarget(...)` o acha, e limpa a pendência nesse caso, no prazo, se a pessoa abrir outra conversa, ou se
   `composer.commandResult` do pedido vier `failed`. Reaproveite o mecanismo que o `newTarget` usa para abrir a
   conversa; não crie outro caminho de navegação.
   **Verify**: o comando Gradle completo → `BUILD SUCCESSFUL`.
3. `docs/zaplaud-paridade.md`: registre que o cliente segue o contato na troca; sem teste em aparelho.

### Done criteria

- [ ] Gradle `BUILD SUCCESSFUL`; nenhum teste existente editado; `git diff --stat producao -- server client zaplaud-rs android/desktop android/shared/src/jvmMain android/shared/src/androidDeviceTest` vazio; `git status --short` limpo

## Parte C: Rust

### Current state

- `zaplaud-rs/crates/zaplaud-core/src/rules/composer.rs:31-45`, `new_session_target(...)`: exige mesma origem,
  **mesmo provider**, mesma conta, mesmo `agent_id` e outro `session_id`; usado em
  `zaplaud-rs/crates/zaplaud-ui/src/composer.rs:381-390` só para `new_target`, definido por "Nova sessão" (linhas
  ~424-427).
- `rules/composer.rs:1468-1500`: `Event::Command` cria `PendingCommand { token, crosses_tool, key, kind, id, polls }`;
  para comando `model` com `crosses_tool`, quando a sessão selecionada deixa de ser a do comando o acompanhamento
  termina sem publicar resultado. Não guarda `cast`.
- `SessionItem` (`model.rs`) tem `cast: Option<String>`, `agent_id`, `sub`.
- Comandos (em `zaplaud-rs/`): `cargo clippy --workspace --all-targets -j 4 -- -D warnings`; `cargo test -p
  zaplaud-core -j 4`; `cargo build --release -j 4`. Worktree nova precisa de `npm ci` na raiz (o teste de contrato
  sobe um servidor Node de teste). Nunca `xvfb-run`, `cargo run` nem os roteiros de `zaplaud-rs/scripts/`.

### Steps

1. `rules/composer.rs`: função pura
   `pub fn switch_tool_target<'a>(sessions: &'a [SessionItem], previous: &SessionKey, cast: Option<&str>) -> Option<&'a SessionItem>`
   (mesma origem, `cast` igual e presente, `agent_id` presente, não subagente, chave diferente). Testes em
   `tests/antigravity_capabilities.rs`: acha; ignora outra origem, outro `cast`, subagente, sem agente, a própria,
   `cast` ausente. **Verify**: `cargo test -p zaplaud-core -j 4` passa.
2. `zaplaud-ui/src/composer.rs`: ao emitir o comando `model` com `crosses_tool` (depois da confirmação), guarde uma
   pendência irmã de `new_target` (chave antiga, `cast` da sessão selecionada, prazo de 90 s); no mesmo ponto onde
   `new_target` é resolvido contra os snapshots, resolva esta com `switch_tool_target` e selecione o sucessor pelo
   mesmo comando de seleção que `new_target` usa. Limpe a pendência ao achar, no prazo, quando a pessoa seleciona
   outra conversa, ou quando o comando falha.
   **Verify**: `cargo clippy --workspace --all-targets -j 4 -- -D warnings` exit 0; `cargo test -p zaplaud-core -j 4`
   passa; `cargo build --release -j 4` exit 0.
3. `docs/zaplaud-paridade.md`: registre; sem teste com janela.

### Done criteria

- [ ] clippy, testes do núcleo e build release com exit 0; `git diff --stat producao -- server client android zaplaud-rs/scripts` vazio; testes existentes sem edição de afirmação; `git status --short` limpo

## STOP conditions

- Um trecho de "Current state" da sua parte não bate com o código.
- Selecionar o sucessor exigir um caminho de navegação novo em vez do que "Nova sessão" já usa (partes B e C), ou
  mudar `shared/**`/`server/**` (parte A).
- Você se vir prestes a abrir navegador, emulador, janela, Xvfb ou aparelho.

## Maintenance notes

- As três partes implementam a mesma regra; se uma mudar, as outras mudam junto (`docs/zaplaud-paridade.md`).
- O prazo de 90 s cobre a abertura mais lenta medida (omp pelo auxiliar: poucos segundos; limite do lançador: 60 s).
- Quem revisar olha: que a pendência some quando a pessoa escolhe outra conversa (a tela não pode "pular" sozinha
  depois), e que falha do pedido não deixa pendência.
