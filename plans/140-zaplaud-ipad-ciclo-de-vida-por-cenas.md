# Plano 140: Zaplaud iPad: o app adota o ciclo de vida por cenas (UIScene), validado pelo CI

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 140 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat bdd442d..HEAD -- ios/Zaplaud/AppDelegate.swift ios/Zaplaud/Info.plist ios/Zaplaud.xcodeproj ios/ZaplaudUITests`
> Se algum arquivo do escopo mudou desde `bdd442d`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: MED (configuração errada = tela preta ao abrir; sem Mac nem iPad, só o simulador do CI)
- **Depende de**: 103 (as duas primeiras partes, mescladas)
- **Categoria**: tech-debt
- **Planejado em**: commit `bdd442d`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O Zaplaud do iPad cria a janela no `AppDelegate`, do jeito antigo. A Apple avisou (nota técnica TN3187) que apps
compilados com o SDK seguinte ao do iOS 26 precisam do ciclo de vida por cenas para abrir. Hoje nada quebra; quando
o Xcode do runner (ou do Mac do dono) mudar de SDK, o app compilado deixa de abrir. Era o passo 4 do plano 103, que
ficou de fora. Decisão do dono em 2026-11-19: fazer agora.

## Estado atual

Leia o **passo 4 do plano 103** (`plans/103-zaplaud-ipad-seletor-segundo-plano-e-uiscene.md`, seção
"Passo 4: ciclo de vida por cenas", com o esqueleto do `SceneDelegate` e as chaves do `Info.plist`) e as
condições de parada dele sobre UIScene. Confira contra a árvore de hoje: `ios/Zaplaud/` tem só `AppDelegate.swift`
e `Info.plist`; o projeto é `ios/Zaplaud.xcodeproj` (veja se os arquivos-fonte são listados um a um no
`project.pbxproj` ou se o alvo usa grupo sincronizado com a pasta: disso depende ter de editar o `.pbxproj`).
O plano 103 já mudou `MainViewController.kt` (a composição fica viva em segundo plano) e o smoke
(`ios/ZaplaudUITests/ZaplaudUITests.swift`, `scripts/ios-smoke.sh`) já cobre: login, sair e voltar do app com
rascunho preservado, seletor de arquivos, e conexões 1 → 0 → 1.

**Não há Mac.** O único compilador é o workflow `iPadOS` (`.github/workflows/ios.yml`, runner macOS, Xcode 26.4):
testes nativos, build de simulador, smoke com fixture e IPA sem assinatura.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-140 -b advisor/140-zaplaud-ipad-ciclo-de-vida-por-cenas producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| CI | workflow `iPadOS` no branch | verde |

## Escopo

**Pode modificar**:
- `ios/Zaplaud/**`, `ios/Zaplaud.xcodeproj/**`, `ios/ZaplaudUITests/**`, `scripts/ios-*.sh` (só se o smoke precisar)
 - `CHANGELOG.md` (uma linha), `README.pt-BR.md` (se a seção do iPad citar o `AppDelegate`)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Várias janelas/multitarefa de cenas (uma cena só). Código Kotlin, salvo se a ponte com o `MainViewController` exigir (aí relate antes). Assinatura, SideStore. `plans/`.

## Passos

### Passo 1: cena única

`SceneDelegate` que cria a `UIWindow` da cena com o mesmo controlador raiz que o `AppDelegate` cria hoje;
`AppDelegate` deixa de criar janela e passa a devolver a configuração da cena; `Info.plist` com
`UIApplicationSceneManifest` (`UIApplicationSupportsMultipleScenes` = `false`, configuração padrão apontando
`$(PRODUCT_MODULE_NAME).SceneDelegate`). Inclua o arquivo novo no alvo (editando o `.pbxproj` com cuidado se os
arquivos forem listados um a um: copie o padrão das entradas de `AppDelegate.swift`, com identificadores únicos).
Tudo o que o `AppDelegate` fazia ao abrir, entrar e sair de segundo plano continua acontecendo uma vez só.

**Verificação**: revisão por leitura (não há compilador local): toda referência do `.pbxproj` tem as quatro
entradas que `AppDelegate.swift` tem; `plutil`/`python3 -c "import plistlib,sys; plistlib.load(open('ios/Zaplaud/Info.plist','rb'))"` lê o `Info.plist`.

### Passo 2: o smoke prova que abriu por cena

No teste de interface, acrescente uma verificação que só passa se o app abriu com janela visível e, se for
possível observar pelo XCTest ou por um identificador de acessibilidade/argumento de lançamento de teste, que a
janela pertence a uma cena conectada (por exemplo, o app expõe em modo de teste um rótulo de diagnóstico; ou o
teste confere que `UIApplication.shared.connectedScenes` não está vazio através de um ponto de teste). Se não
houver forma limpa de observar a cena, baste o smoke existente passar (login, sair/voltar, seletor) e diga isso.

**Verificação**: workflow `iPadOS` verde no seu branch (dê o id do run), com testes nativos, build de simulador,
smoke e IPA.

### Passo 3: documentação

`CHANGELOG.md` ("Não lançado"): "Zaplaud (iPad): o app passa a usar o ciclo de vida por cenas, exigido pelos
próximos SDKs da Apple." Ajuste o guia se citar o `AppDelegate`.

**Verificação**: `npm ci && npm run i18n:check -- --strict` sai com 0.

## Plano de testes

O smoke do simulador no CI.

## Critérios de pronto

Todos precisam valer:

- [ ] App abre por cena única no simulador do CI; smoke completo verde
 - [ ] IPA sem assinatura gerado
 - [ ] Nenhuma mudança de comportamento ao sair e voltar do app (verificações do 103 continuam passando)
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 140 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `bdd442d`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O app abrir com tela preta ou o smoke falhar no login depois de duas rodadas de CI com a mesma causa.
- Precisar de mudança no projeto Xcode que não dê para fazer com segurança editando o `.pbxproj` à mão.
- Precisar mexer no Kotlin (`MainViewController`): relate antes.

## Notas de manutenção

- Só um iPad físico prova: abrir o IPA reassinado pelo SideStore, girar o aparelho, Split View/Slide Over (uma cena só deve continuar funcionando).
