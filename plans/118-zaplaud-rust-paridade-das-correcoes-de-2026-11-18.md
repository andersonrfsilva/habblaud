# Plano 118: Zaplaud Rust: aplicar no desktop as correções de comportamento feitas no Kotlin em 2026-11-18

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 118 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3927246..HEAD -- zaplaud-rs/crates docs/zaplaud-paridade.md`
> Se algum arquivo do escopo mudou desde `3927246`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: L
- **Risco**: MED (mexe em envio, bate-papo paralelo e confirmações do cliente desktop)
- **Depende de**: nenhum (ordem do dono em 2026-11-18)
- **Categoria**: bug / migration
- **Planejado em**: commit `3927246`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O `AGENTS.md` manda os dois clientes Zaplaud andarem juntos. Em 2026-11-18 o cliente Kotlin recebeu uma série de
correções de defeitos de comportamento e o Rust ficou com as pendências anotadas em `docs/zaplaud-paridade.md`.
Vários desses defeitos são de lógica comum (perder a pergunta do bate-papo paralelo, conflito de sessão fechando
a conversa errada, confirmação usando o alvo atual em vez do original), então é provável que existam também no
desktop. Este plano confere cada um no Rust e porta a correção onde o defeito existir.

## Estado atual

Cliente Rust: `zaplaud-rs/`, três crates. `zaplaud-core` (regras e controladores, sem tela):
`src/controller/{aside,message,permission,reading}.rs`, `src/rules/*.rs`, `src/api.rs`, `src/api/routes.rs`,
`src/model.rs`; testes em `crates/zaplaud-core/tests/` (`rules_reducers.rs`, `transport.rs`, `contract.rs`).
`zaplaud-store` (estado em disco). `zaplaud-ui` (Slint): `src/{app,aside,cast,permission,settings,list,images}.rs`,
`src/markdown/`, `ui/*.slint`. Textos vêm de `android/shared/src/commonMain/composeResources/values/strings.xml`
(gerados na compilação); textos só do desktop ficam em `crates/zaplaud-ui/ui/native-strings.xml`.

Cada item abaixo cita o commit Kotlin que é a **referência de comportamento** (leia o diff com
`git show <sha> -- android/`) e onde procurar no Rust.

| # | Comportamento | Referência Kotlin | Onde olhar no Rust |
|---|---|---|---|
| 1 | Bate-papo paralelo: o rascunho só é apagado quando o servidor aceita a pergunta | `fdcd55f` | `controller/aside.rs`, `zaplaud-ui/src/aside.rs`, `ui/aside.slint` |
| 2 | Bate-papo paralelo: depois de uma consulta que falha com resposta em andamento, tenta de novo (3 s) em vez de parar; erro some no sucesso seguinte | `29f779b` | `controller/aside.rs` |
| 3 | Conflito `session_changed` de um envio ou comando feito na conversa A não fecha a conversa B aberta depois | `5f4df95` | `controller/message.rs` (procure `session_changed`) |
| 4 | Consulta de status de uma mensagem antiga não apaga o estado "incerto" da seguinte | `3e84949` | `controller/message.rs`; a tabela de paridade diz que o Rust **já** confere o id: confirme e, se sim, só escreva o teste |
| 5 | Sessão que some da lista ao vivo e não está em Recentes dispara uma releitura de `/api/sessions/recent` | `68beb64` e `b9ec815` (função `recentIsStale` em `SessionRepository.kt`) | onde o Rust guarda `recent` e trata o snapshot (procure `sessions/recent`) |
| 6 | Confirmação de retomar/dispensar usa o alvo de quando o diálogo abriu; trocar de conversa ou servidor fecha o diálogo sem enviar | `a1f680c` | `zaplaud-ui/src/cast.rs`, `ui/cast.slint`; a paridade diz: "limpa o diálogo ao observar a troca, mas usa a seleção atual ao confirmar" |
| 7 | Folha de permissão não fecha (botão, Esc, clique fora) enquanto a decisão está sendo enviada | `df53c43` | `controller/permission.rs`, `zaplaud-ui/src/permission.rs`, `ui/permission.slint` |
| 8 | Abrir uma sessão pelo aviso tem prioridade sobre a restauração da última conversa de outro servidor | `0f05c6a` | `controller/reading.rs` e o tratamento da ação "Abrir" do aviso em `zaplaud-ui` |
| 9 | Imagens do markdown: sem seguir redirecionamento, sem `usuario@` na URL, teto de pixels antes de decodificar, cache limitado por bytes, falha visível | commits do plano 091 (`git log --oneline -8 -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt`) | `zaplaud-ui/src/images.rs`, `src/markdown/images.rs`, `zaplaud-core/src/api/files.rs` |
| 10 | Configurações mostra "Acesso neste servidor" (conversas, mensagens, aprovações, arquivos) | `5d842e8`..`851ea21` | `zaplaud-ui/src/settings.rs`, `ui/settings.slint`, `zaplaud-core/src/model.rs` (campos de acesso) |

Não se aplicam ao desktop e ficam fora: pausa do bate-papo ao sair do app e avisos do Android (ciclo de vida e
serviço do Android), arquivo de sessão atômico (o desktop usa o chaveiro), ícone e versão do APK, teto do cache de
conversas (decisão separada), leitor SVG do Android.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-118 -b advisor/118-zaplaud-rust-paridade-das-correcoes-de-2026-11-18 producao`. Remova ao terminar.
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
- `zaplaud-rs/crates/**` (código e testes)
- `zaplaud-rs/crates/zaplaud-ui/ui/native-strings.xml` só se um texto existir apenas no desktop
- `docs/zaplaud-paridade.md` (atualizar cada linha conferida)
- `CHANGELOG.md` (uma linha por correção visível, em "Não lançado")

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `android/` e `strings.xml` do Kotlin: os textos necessários já existem lá (inclusive `access_*` e `image_failed`).
- Servidor.
- Entregas no Rust (plano 119) e "Dispensar pela lista" no Rust (pendência antiga, não é correção de hoje).
- Qualquer teste que precise de tela (Xvfb, roteiros `scripts/*-smoke.py`): não autorizados; o aceite é `fmt`,
  `clippy`, testes sem tela e o build release.

## Passos

### Passo 1: preparar e medir a base

Use um diretório de build fora do repositório: `export CARGO_TARGET_DIR=/tmp/zaplaud-rs-target`. Na raiz da
worktree rode `npm ci` (os testes de contrato sobem o servidor de teste Node). Em `zaplaud-rs/`:
`cargo fmt --check`, `cargo clippy --locked -p zaplaud-core -p zaplaud-store --all-targets -j 4 -- -D warnings` e
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4`.

**Verificação**: os três passam antes de qualquer mudança. Anote o número de testes. Se algum falhar na base, é
condição de PARADA.

### Passo 2: um item por vez: conferir, testar, corrigir

Para cada linha da tabela de "Estado atual", nesta ordem (1 a 10):

a. Leia o diff Kotlin de referência e o código Rust correspondente. Decida: **já está certo**, **tem o mesmo
   defeito**, ou **não se aplica** (diga por quê).
b. Se tem o defeito e a lógica mora em `zaplaud-core`: escreva primeiro um teste que falha (em
   `crates/zaplaud-core/tests/`, no arquivo que já testa aquele controlador ou regra), depois corrija, e confirme
   que passa. Se a lógica mora só em `zaplaud-ui` (Slint), corrija com a menor mudança e, se der, extraia a
   decisão para uma função pura testável em `zaplaud-core`.
c. Se já está certo: escreva o teste que prova (quando couber em `zaplaud-core`) e siga.
d. Atualize a linha correspondente em `docs/zaplaud-paridade.md` (tire o "pendente" ou escreva o que ficou).
e. Um commit por item: `fix(zaplaud-rs): ...` ou `test(zaplaud-rs): ...`.

Item 10 (acesso por servidor) é recurso novo de tela: use os textos `access_*` que já existem no `strings.xml`
do Kotlin, e as mesmas regras de `serverAccess`/`messagingState` de `SettingsScreen.kt` (campo ausente = "não
informado"; mensagens sem a proteção = bloqueado com o aviso `message_guard_required`); ponha a leitura e a
regra em `zaplaud-core` com teste, e a seção na tela de configurações.

**Verificação** depois de cada item: os testes de `zaplaud-core`/`zaplaud-store` passam e
`cargo clippy --locked -p zaplaud-core -p zaplaud-store --all-targets -j 4 -- -D warnings` sai limpo.

### Passo 3: fechar

`cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 4 -- -D warnings`;
`ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -j 4`;
`cargo build --release --locked -p zaplaud-ui --bin zaplaud -j 4`.

**Verificação**: os quatro saem com 0. Confira `df -h /tmp` antes do build release (precisa de ~3 GB livres); se
faltar espaço, apague `/tmp/zaplaud-rs-target/debug` e relate.

## Plano de testes

Um teste por item corrigido ou confirmado, em `crates/zaplaud-core/tests/`, seguindo os testes vizinhos do mesmo arquivo. Itens que só existem na camada Slint ficam sem teste automatizado e precisam constar no relato como "sem teste; conferir na janela".

## Critérios de pronto

Todos precisam valer:

- [ ] Relato com uma linha por item (1 a 10): já certo / corrigido / não se aplica, com arquivo e linha do Rust
- [ ] `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, testes de `zaplaud-core` e `zaplaud-store` e o build release saem com 0
- [ ] `docs/zaplaud-paridade.md` sem "pendente de conferência no Rust" nas linhas dos itens tratados
- [ ] Nenhum arquivo em `android/` ou `server/` alterado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 118 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3927246`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A base não passar no passo 1.
- Um item exigir mudar o contrato com o servidor ou o formato do estado em disco.
- Um item de tela (6, 7, 10) não puder ser feito sem rodar a janela para conferir: faça a parte de `zaplaud-core`
  com teste, deixe a parte Slint como está e relate; não entregue mudança de tela que você não consegue compilar.
- Mais de dois itens seguidos saírem "tem o defeito, mas a correção é grande": pare e relate o que já está feito;
  o resto vira plano próprio.

## Notas de manutenção

- Depois deste plano, o desktop precisa ser reinstalado no Mac e no miranote para as correções valerem.
- A janela não é aberta neste plano: os itens de tela precisam de uma conferência manual do dono.
- Regra para o futuro: correção de lógica comum entra nos dois clientes na mesma entrega.
