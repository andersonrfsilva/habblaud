# Plano 120: Remover o desktop Compose obsoleto: módulo `:desktop`, alvo JVM do `shared`, testes JVM e o que só existe por causa deles

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 120 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat ae86474..HEAD -- android/desktop android/shared/src/jvmMain android/shared/src/jvmTest android/shared/build.gradle.kts android/settings.gradle.kts .github/workflows`
> Se algum arquivo do escopo mudou desde `ae86474`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: MED (remove testes JVM que hoje cobrem comportamento comum; toca o build do app Android e do iOS)
- **Depende de**: nenhum (ordem do dono em 2026-11-18)
- **Categoria**: tech-debt
- **Planejado em**: commit `ae86474`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O desktop Compose está obsoleto desde 2026-11-10: o desktop oficial do Zaplaud é o cliente Rust (`zaplaud-rs/`).
O código antigo continua no repositório e cobra custo: toda mudança de assinatura `expect` no código comum exige
manter um `actual` JVM que ninguém usa; o CI compila e testa o alvo JVM com Xvfb em todo push; o build carrega
dependências só dele. Em 2026-11-18 o dono mandou remover. Custo aceito: os testes de `jvmTest` deixam de existir,
inclusive os que exercitavam comportamento comum (duas colunas, Mermaid, rolagem do bate-papo paralelo).

## Estado atual

O que existe (confira com os comandos antes de apagar):

- Módulo `android/desktop/` (6 arquivos rastreados: `build.gradle.kts`, `src/main/.../desktop/Main.kt`, ícones,
  `zaplaud.desktop`). Incluído em `android/settings.gradle.kts:4`: `include(":app", ":shared", ":desktop")`.
- `android/shared/src/jvmMain/` e `android/shared/src/jvmTest/` (20 arquivos rastreados no total). Em
  `android/shared/build.gradle.kts`: `:20` `jvm { ... }`; `:59-65` `jvmMain.dependencies { compose.desktop.common, okhttp, ktor-client-okhttp, java-keyring, coroutines-swing }`;
  `:66-71` `jvmTest.dependencies { ... compose.desktop.currentOs, uiTestJUnit4 }`; `:87-92` `tasks.named<Test>("jvmTest") { ... }`.
- Catálogo `android/gradle/libs.versions.toml`: `java-keyring`, `composenativetray` e o plugin `kotlin-jvm` só
  servem ao desktop (confirme com `grep -rn 'java.keyring\|composenativetray\|kotlin.jvm' android --include=*.kts`).
- CI Android, `.github/workflows/android.yml`, job `verificar`: passos `setup-node`, `npm ci` e
  `xvfb-run -a ./gradlew :shared:jvmTest --max-workers=4` (os dois primeiros existem só porque testes JVM sobem a
  fixture Node).
- CI do Rust, `.github/workflows/zaplaud-rs.yml:66-80`: três passos de "interoperabilidade com a JVM"
  (`bash zaplaud-rs/packaging/jvm-interop.sh`, que copia `zaplaud-rs/packaging/JvmRustInteropTest.kt` para
  `android/shared/src/jvmTest/` e roda `:shared:jvmTest`; e `cargo test -p zaplaud-store --test jvm_interop -- --ignored`,
  em `zaplaud-rs/crates/zaplaud-store/tests/jvm_interop.rs`). Provavam que o Rust lê o estado gravado pelo desktop
  JVM; sem o desktop JVM, perdem o sentido.
- Outros: `scripts/desktop-install-linux.sh` (instalador do desktop Compose); `docs/zaplaud-desktop.md` (guia do
  desktop Compose); menções em `AGENTS.md` ("O desktop Compose (`android/desktop`, `android/shared/src/jvmMain`)
  está obsoleto: não recebe mudanças nem paridade."), `README.md`, `README.pt-BR.md` (seção "Desktop (Linux e
  macOS)"), `docs/zaplaud-paridade.md` (parágrafo sobre o desktop Compose), `zaplaud-rs/README.md`,
  `zaplaud-rs/packaging/README.md`, `zaplaud-rs/crates/zaplaud-store/README.md`.
- Ferramentas de comparação com o Compose em `zaplaud-rs/scripts/` (`certify-compose.py`, `compare-compose.py`,
  `compare-compose.md`): rodam o desktop Compose para medir paridade visual.

`expect` do código comum que só o JVM implementava de verdade (`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt`):
`PlatformSearchShortcut` e `platformSendsOnEnter` são vazios/falsos no Android e no iOS. **Ficam como estão**
neste plano (tirar exigiria mexer em `ReadingScreen.kt`).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-120 -b advisor/120-remover-o-desktop-compose-obsoleto producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Build e testes Android | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin --max-workers=3` | `BUILD SUCCESSFUL` |
| YAML | `bun -e "for (const f of process.argv.slice(2)) Bun.YAML.parse(await Bun.file(f).text()); console.log('ok')" .github/workflows/android.yml .github/workflows/zaplaud-rs.yml` | `ok` |
| Rust store | `cd zaplaud-rs && ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-store -j 4` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | sai com 0 |

## Escopo

**Pode modificar**:
- apagar: `android/desktop/`, `android/shared/src/jvmMain/`, `android/shared/src/jvmTest/`, `scripts/desktop-install-linux.sh`,
  `zaplaud-rs/packaging/jvm-interop.sh`, `zaplaud-rs/packaging/JvmRustInteropTest.kt`,
  `zaplaud-rs/crates/zaplaud-store/tests/jvm_interop.rs`, `zaplaud-rs/scripts/{certify-compose.py,compare-compose.py,compare-compose.md}`
- editar: `android/settings.gradle.kts`, `android/shared/build.gradle.kts`, `android/gradle/libs.versions.toml`,
  `.github/workflows/android.yml`, `.github/workflows/zaplaud-rs.yml`, `AGENTS.md`, `README.md`, `README.pt-BR.md`,
  `docs/zaplaud-paridade.md`, `docs/zaplaud-desktop.md`, `zaplaud-rs/README.md`, `zaplaud-rs/packaging/README.md`,
  `zaplaud-rs/crates/zaplaud-store/README.md`, `CHANGELOG.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código comum (`commonMain`), Android (`androidMain`, `android/app`) e iOS (`iosMain`, `ios/`): nenhuma mudança de
  comportamento. Os `expect` vazios ficam.
- O cliente Rust (código): só saem o teste e os scripts de interoperabilidade/comparação com a JVM.
- `plans/` (histórico): os planos 063–067 continuam como registro.
- `README.es.md` e `README.fr.md`: tradução é outro plano (088).

## Passos

### Passo 1: inventário antes de apagar

Liste o que vai sair e quem ainda referencia: `git ls-files android/desktop android/shared/src/jvmMain android/shared/src/jvmTest`
e `grep -rn 'desktop\|jvmMain\|jvmTest\|jvm-interop\|jvm_interop\|compare-compose\|certify-compose' --include=*.kts --include=*.toml --include=*.yml --include=*.sh --include=*.py --include=*.rs --include=*.md . | grep -v '^./plans/\|node_modules\|/target/\|/build/\|CHANGELOG'`.

**Verificação**: toda referência achada está na lista de "Estado atual" ou é a palavra "desktop" em outro sentido
(o desktop Rust, "desktop" do servidor/Codex). Referência a código que **usa** `jvmMain` fora dessa lista é
condição de PARADA.

### Passo 2: tirar do build Gradle

Apague `android/desktop/`, `android/shared/src/jvmMain/` e `android/shared/src/jvmTest/` com `git rm -r`. Em
`android/settings.gradle.kts`: `include(":app", ":shared")`. Em `android/shared/build.gradle.kts`: remova a linha
`jvm { ... }`, os blocos `jvmMain.dependencies` e `jvmTest.dependencies` e o bloco `tasks.named<Test>("jvmTest")`.
Em `libs.versions.toml`: remova `java-keyring`, `composenativetray` e o plugin `kotlin-jvm` se nada mais os usar
(confirme com `grep -rn` em `android/`).

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin --max-workers=3`
→ `BUILD SUCCESSFUL`, com o mesmo número de testes de host de antes (anote antes e depois).
`android/gradlew -p android projects` não lista `:desktop`.

### Passo 3: CI

`android.yml`, job `verificar`: remova o passo `xvfb-run -a ./gradlew :shared:jvmTest ...` e, se nenhum outro passo
do job usar Node, os passos `setup-node` e `npm ci` desse job (o job `instrumentado` tem os dele e continua).
`zaplaud-rs.yml`: remova os três passos de interoperabilidade com a JVM e qualquer preparação que só eles usavam
(Java, Android SDK), sem tocar nos demais passos.

**Verificação**: `bun -e "for (const f of process.argv.slice(2)) Bun.YAML.parse(await Bun.file(f).text()); console.log('ok')" .github/workflows/android.yml .github/workflows/zaplaud-rs.yml` → `ok`;
`grep -n 'jvmTest\|jvm-interop\|jvm_interop' .github/workflows/*.yml` → nada.

### Passo 4: Rust: teste e scripts que dependiam da JVM

`git rm` de `zaplaud-rs/packaging/jvm-interop.sh`, `zaplaud-rs/packaging/JvmRustInteropTest.kt`,
`zaplaud-rs/crates/zaplaud-store/tests/jvm_interop.rs` e dos três arquivos `compare-compose`/`certify-compose` em
`zaplaud-rs/scripts/`. `git rm scripts/desktop-install-linux.sh`.

**Verificação**: em `zaplaud-rs/`, com `CARGO_TARGET_DIR=/tmp/zaplaud-rs-target-120`:
`cargo fmt --check` e `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-store -j 4` saem com 0.

### Passo 5: documentação

- `AGENTS.md`: troque a frase do desktop Compose obsoleto por "O desktop Compose foi removido em 2026-11-18; o
  desktop é o cliente Rust."
- `README.pt-BR.md` e `README.md`, seção do desktop: uma frase dizendo que o desktop é o cliente Rust, com o link
  para `zaplaud-rs/README.md`; tire qualquer instrução do Compose que tenha sobrado.
- `docs/zaplaud-paridade.md`: o parágrafo sobre o desktop Compose passa a dizer que foi removido.
- `docs/zaplaud-desktop.md`: reduza a um aviso curto ("Este guia descrevia o desktop Compose, removido em
  2026-11-18. O desktop atual está em `zaplaud-rs/README.md`. O histórico está nos planos 063–067.").
- READMEs do `zaplaud-rs/`: tire as seções de interoperabilidade com a JVM e de comparação com o Compose, mantendo
  os números já medidos como histórico se estiverem em texto.
- `CHANGELOG.md` ("Não lançado", em "Removido"): "Desktop Compose (`android/desktop` e o alvo JVM do código
  compartilhado) removido; o desktop do Zaplaud é o cliente Rust."

**Verificação**: `npm run i18n:check -- --strict` sai com 0 (rode `npm ci` antes);
`grep -rn 'desktop:run\|:desktop:\|jvmMain' README.md README.pt-BR.md AGENTS.md docs zaplaud-rs/README.md zaplaud-rs/packaging/README.md` → nada.

## Plano de testes

Nenhum teste novo. A prova é o build Android completo, a compilação dos testes de aparelho e instrumentados, e o `cargo test` do `zaplaud-store` sem o teste de interoperabilidade.

## Critérios de pronto

Todos precisam valer:

- [ ] `git ls-files android/desktop android/shared/src/jvmMain android/shared/src/jvmTest` → vazio
- [ ] Comando Gradle do passo 2 termina em `BUILD SUCCESSFUL` com o mesmo número de testes de host de antes
- [ ] Os dois workflows são YAML válido e não citam `jvmTest` nem interoperabilidade com a JVM
- [ ] `cargo fmt --check` e os testes do `zaplaud-store` passam
- [ ] Documentação atualizada; `npm run i18n:check -- --strict` sai com 0
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 120 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `ae86474`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Algum arquivo de `commonMain`, `androidMain`, `iosMain` ou `android/app` deixar de compilar sem o alvo JVM.
- O número de testes de host do Android mudar depois da remoção (algum teste comum estava sendo contado pelo JVM).
- Uma referência a `jvmMain`/`:desktop` aparecer em código vivo fora da lista do "Estado atual".
- O iOS depender de algo declarado só no alvo JVM (confira `iosMain.dependencies` e os `expect/actual`): relate, porque
  o iOS não compila nesta máquina.

## Notas de manutenção

- Testes que existiam só no JVM e cobriam comportamento comum (duas colunas em tela larga, leitor Mermaid, rolagem
  do bate-papo paralelo, navegador de arquivos) deixam de existir. Se fizerem falta, o lugar é `androidHostTest`
  ou os instrumentados.
- Os `expect` vazios `PlatformSearchShortcut` e `platformSendsOnEnter` podem sair numa limpeza posterior.
- iOS não compilado nesta máquina: quem tiver Mac deve rodar `scripts/ios-build.sh simulator` depois deste plano.
