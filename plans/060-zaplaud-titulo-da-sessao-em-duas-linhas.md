# Plano 060: título da sessão na lista usa até duas linhas

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 060 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`.
> Planos anteriores mudam outras partes do arquivo; compare a linha do título abaixo pelo conteúdo.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

Títulos de sessão costumam ser o começo do primeiro prompt. Na captura do emulador em 2026-11-08, cinco das seis
linhas começavam com "Pode revisar a etapa 16 do projet…" e eram cortadas antes da parte que as diferencia. Duas
linhas de título, como em clientes de e-mail e chat atuais, mostram o suficiente para distinguir as conversas sem
abrir cada uma.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:313`:

  ```kotlin
  Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
  ```

- A linha da sessão é um `Row` com avatar de 48 dp, coluna central com `weight(1f)` e coluna de horário/contador à
  direita, alinhados ao centro (`:308`). A coluna da direita não depende da altura do título.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas novas |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

## Escopo

**Pode modificar**: `ReadingScreen.kt` (só o `Text` do título da linha da sessão) e `CHANGELOG.md`.

**Fora**: título da barra superior da conversa (`:222`, continua em uma linha), subtítulo da linha, folha de
informações, tamanho e peso da fonte.

## Git

- Branch: `advisor/060-zaplaud-titulo-duas-linhas`.
- Mensagem: `feat(android): título da sessão na lista em até duas linhas`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: duas linhas

Troque `maxLines = 1` por `maxLines = 2` no `Text` de `:313`. Mantenha `overflow = TextOverflow.Ellipsis`.

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: conferir visualmente

Rode o roteiro. Abra `sessoes-abertas-*` e `sessoes-recentes-*` nos dois temas. Depois, com
`adb shell settings put system font_scale 1.3`, capture a lista à mão (`adb exec-out screencap -p > /tmp/lista-1-3.png`)
e volte com `adb shell settings put system font_scale 1.0`.

**Verificar**: títulos longos ocupam duas linhas e terminam em reticências; o horário continua alinhado; com fonte
1,3 as linhas não se sobrepõem.

### Passo 3: changelog

Em `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
`- **Zaplaud:** títulos longos na lista de sessões usam até duas linhas.`

**Verificar**: `grep -n 'até duas linhas' CHANGELOG.md` retorna 1 linha.

## Plano de testes

Sem teste novo: mudança visual de uma propriedade. Rode a suíte instrumentada para garantir que nenhuma busca por
texto exato do título quebrou.

## Critérios de pronto

- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas novas.
- [ ] Capturas nos dois temas e com fonte 1,3.
- [ ] `git status --short` só com `ReadingScreen.kt`, `CHANGELOG.md` e `plans/README.md`.
- [ ] Linha 060 atualizada.

## Condições de PARADA

- Com fonte 1,3, menos de quatro sessões cabem na tela de 1080×2340 px. Relate com a captura; o dono decide entre
  duas linhas e densidade.

## Notas de manutenção

- Se a lista passar a mostrar a última mensagem como prévia, reavalie: título e prévia somados não devem passar de
  três linhas.
