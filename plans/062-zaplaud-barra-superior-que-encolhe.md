# Plano 062: barra superior grande na lista de sessões, que encolhe ao rolar

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 062 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`.
> Os planos 055–061 mudam este arquivo; isso é esperado. Confirme que a conexão `reveal` do plano 059 existe e compare
> o trecho da barra superior abaixo pelo conteúdo.

## Status

- **Prioridade**: P3 (opcional; acabamento)
- **Esforço**: M
- **Risco**: MED (duas conexões de rolagem aninhadas na mesma lista; comportamento no iPad não verificado)
- **Depende de**: `059` (busca revelada por puxada longa)
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

A lista de sessões usa uma barra superior pequena e fixa. Apps Android atuais mostram, na tela principal, um título
grande que encolhe ao rolar, liberando espaço para a lista. No Zaplaud, isso destaca o nome do app e o ponto de
conexão na abertura e dá mais linhas visíveis durante a rolagem. A conversa aberta continua com a barra pequena atual,
que mostra avatar, título e ações.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:214-255` (em `dab677d`): um único
  `Scaffold` com `TopAppBar` para lista e conversa. O título muda conforme `selected`; na lista (`selected == null`)
  mostra `app_name`, o ponto de conexão e o menu "⋮" (ícone `MoreVert` depois do 055).

  ```kotlin
  Scaffold(floatingActionButton = { ... }, topBar = {
      TopAppBar(
          title = { ... if (selected != null) CharacterAvatar(...) ... Text(selected?.title ?: selected?.characterName ?: stringResource(Res.string.app_name), ...) ... if (selected == null) { /* ponto de conexão */ } ... },
          navigationIcon = { if (selected != null) { /* voltar */ } },
          actions = { if (selected != null) castActions(); if (selected == null) Box { /* menu */ } }
      )
  }) { padding -> ...
  ```

- Plano 059 adicionou ao `LazyColumn` da lista `Modifier.nestedScroll(reveal)`, uma `NestedScrollConnection` que soma
  a puxada para baixo além do topo e revela a busca a partir de 120 dp.
- Ordem do Compose para rolagem aninhada: em `onPostScroll`, a conexão mais próxima da lista (a `reveal`) recebe
  primeiro; a do `Scaffold` recebe depois. As duas veem a mesma sobra se a `reveal` devolver `Offset.Zero`, como faz.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas (a falha conhecida `imeDraftSurvivesRecreationRotationAndLargeFont` pode ser registrada e ignorada) |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

## Escopo

**Pode modificar**: `ReadingScreen.kt` (barra superior, `Scaffold`, condição da conexão `reveal`),
`FixtureUiInstrumentedTest.kt` (só se os testes da busca precisarem de uma puxada a mais) e `CHANGELOG.md`.

**Fora**: barra da conversa aberta, `SettingsScreen`, conteúdo do menu, busca (exceto a condição do Passo 3),
chips, segmentado.

## Git

- Branch: `advisor/062-zaplaud-barra-que-encolhe`.
- Mensagem: `feat(android): título grande na lista de sessões, encolhe ao rolar`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: barra grande só na lista

1. Antes do `Scaffold`: `val listBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`.
2. `Scaffold(modifier = if (selected == null) Modifier.nestedScroll(listBar.nestedScrollConnection) else Modifier, ...)`.
3. Em `topBar`: se `selected == null`, use `LargeTopAppBar(title = { <Row com app_name e ponto de conexão, o mesmo de hoje> }, actions = { <menu de hoje> }, scrollBehavior = listBar)`; senão, o `TopAppBar` atual sem os ramos de `selected == null`.

Extraia o título e o menu da lista para funções `private` no mesmo arquivo só se isso evitar duplicar código entre os
ramos; não duplique o ponto de conexão nem o menu.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: conferir a rolagem

No emulador, com a fixture, abra a lista em "Recentes" (lista longa), role para baixo e para cima.

**Verificar** (capturas manuais `adb exec-out screencap -p > /tmp/barra-<estado>.png`): título grande no topo;
barra pequena após rolar; ao voltar ao topo, a barra expande; abrir uma conversa mostra a barra pequena com avatar.

### Passo 3: puxada da busca só com a barra expandida

Na conexão `reveal` do plano 059, acrescente a condição `listBar.state.collapsedFraction == 0f` ao `if` que acumula a
puxada. Assim, quando a barra está encolhida, a primeira puxada para baixo só a expande; a busca exige puxar de novo
com a barra aberta. Passe `listBar` para o `remember` da conexão (`remember(revealPx, listBar)`).

**Verificar**: instrumentados → `searchRevealsOnlyAfterLongPullAndStaysPinned` e `searchWithoutMatchesOffersClear`
passam. Se falharem porque a barra começa encolhida no teste, acrescente uma puxada longa extra antes da que revela e
registre no commit.

### Passo 4: suíte, capturas e changelog

- Rode instrumentados e roteiro.
- `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
  `- **Zaplaud:** a lista de sessões tem título grande, que encolhe ao rolar.`

**Verificar**: zero falhas novas; roteiro com exit 0; `grep -n 'título grande' CHANGELOG.md` retorna 1 linha.

## Plano de testes

- Sem teste novo para a barra: o comportamento vem do Material 3.
- Os testes da busca (plano 059) cobrem a interação entre as duas conexões de rolagem.

## Critérios de pronto

- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas novas; testes da busca passando.
- [ ] Capturas manuais do Passo 2 e roteiro com exit 0.
- [ ] `git status --short` só com arquivos do escopo e `plans/README.md`.
- [ ] Linha 062 atualizada.

## Condições de PARADA

- A busca passa a abrir sozinha ao expandir a barra, ou deixa de abrir com a barra expandida.
- A barra grande cobre os chips de servidor ou o segmentado em fonte 1,3.
- `LargeTopAppBar` ou `exitUntilCollapsedScrollBehavior` não existem nesta versão do Material 3.
- A rolagem da lista engasga visivelmente no emulador (compare com a captura `sessoes-recentes` antes do plano).

## Notas de manutenção

- Validar no iPad no próximo gate iOS: a rolagem com efeito elástico do iOS pode entregar a sobra de forma diferente.
- Se o dono achar o título grande excessivo, desfaça só este plano: os demais não dependem dele.
