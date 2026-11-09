# Plano 058: cores de conexão, contexto e cota vêm do tema

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 058 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`.
> Planos 055–057 mudam estes arquivos em outras partes; compare os trechos abaixo pelo conteúdo. Divergência é
> condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

O tema do Zaplaud é verde (`primary` `0xFF176B58` no claro, `0xFF9CDCC6` no escuro, `ZaplaudApp.kt:39-59`), mas o
ponto de conexão usa outro verde fixo (`0xFF2E7D32`) e outro vermelho fixo (`0xFFC62828`). Na captura escura de
2026-11-08, o ponto verde-escuro destoa do verde-menta do tema. O laranja de alerta (`0xFFEF6C00`) é o mesmo nos dois
temas e aparece em quatro lugares. Usar as cores do tema e um único laranja ajustado por tema deixa os estados
coerentes com o resto do app nos modos claro e escuro.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:225` (ponto ao lado do título):

  ```kotlin
  val color = when (state.connection) { "connected" -> Color(0xFF2E7D32); "connecting" -> Color(0xFFEF6C00); else -> Color(0xFFC62828) }
  ```

- `ReadingScreen.kt:274` (ponto em cada chip de servidor):

  ```kotlin
  val color = when (connection) { "connected" -> Color(0xFF2E7D32); "connecting" -> Color(0xFFEF6C00); else -> Color(0xFFC62828) }
  ```

- `ReadingScreen.kt:492` (`ContextBar`):

  ```kotlin
  val color = when { pct >= 80 -> MaterialTheme.colorScheme.error; pct >= 50 -> Color(0xFFEF6C00); else -> MaterialTheme.colorScheme.primary }
  ```

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt:299` e `:327`: o mesmo
  `Color(0xFFEF6C00)` para cota desatualizada e cota acima de 70%.
- Como o código já detecta o tema escuro sem depender do sistema (o usuário escolhe claro/escuro/sistema em
  Configurações), `ReadingScreen.kt:256`:
  `if (MaterialTheme.colorScheme.background.luminance() < 0.5f) ... else ...`. Use o mesmo critério.
  `isSystemInDarkTheme()` está errado aqui: ignora a escolha do usuário.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Cores fixas restantes | `grep -n -E 'Color\(0xFF(2E7D32\|EF6C00\|C62828)\)' -r android/shared/src/commonMain/kotlin` | nenhuma linha |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |

No terminal, digite o grep sem as barras invertidas da tabela:
`grep -rn -E 'Color\(0xFF(2E7D32|EF6C00|C62828)\)' android/shared/src/commonMain/kotlin`.

## Escopo

**Pode modificar**: `ReadingScreen.kt`, `SettingsScreen.kt`, um arquivo novo
`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/StatusColors.kt` e `CHANGELOG.md`.

**Fora**: `ZaplaudApp.kt` (esquema de cores), `CastActions.kt` (laranja animado de aprovação pendente, com outra
função), `CharacterAvatar.kt` (fundos dos avatares), limites de 50/80/70/90%.

## Git

- Branch: `advisor/058-zaplaud-cores-de-status`.
- Mensagem: `feat(android): cores de conexão, contexto e cota seguem o tema`.
- Não faça push. Não instale no M35 sem autorização.

## Passos

### Passo 1: criar as duas funções de cor

Crie `StatusColors.kt` no pacote `br.com.xlinx.habblaud`:

```kotlin
package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Laranja de alerta legível nos dois temas (contraste ≥ 4,5:1 sobre o fundo de cada um). */
@Composable
internal fun warningColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFB870) else Color(0xFF8A5100)

/** Cor do ponto de conexão: tema para conectado e desconectado, alerta enquanto conecta. */
@Composable
internal fun connectionColor(connection: String?): Color = when (connection) {
    "connected" -> MaterialTheme.colorScheme.primary
    "connecting" -> warningColor()
    else -> MaterialTheme.colorScheme.error
}
```

**Verificar**: Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 2: usar as funções

- `ReadingScreen.kt:225` e `:274`: `val color = connectionColor(state.connection)` e `val color = connectionColor(connection)`.
- `ReadingScreen.kt:492` e `SettingsScreen.kt:327`: troque `Color(0xFFEF6C00)` por `warningColor()`.
- `SettingsScreen.kt:299`: `color = warningColor()`.

Remova imports de `Color` que ficarem sem uso.

**Verificar**: o grep "Cores fixas restantes" não retorna linhas; Host + lint + APK com `BUILD SUCCESSFUL`.

### Passo 3: conferir visualmente

Rode o roteiro. Abra `sessoes-abertas-*` nos dois temas.

**Verificar**: exit 0; o ponto ao lado de "Zaplaud" tem a mesma cor dos destaques verdes do tema.

### Passo 4: changelog

Em `CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie se não existir, logo após `### Adicionado`):
`- **Zaplaud:** pontos de conexão e alertas de contexto e cota usam as cores do tema claro ou escuro.`

**Verificar**: `grep -n 'pontos de conexão' CHANGELOG.md` retorna 1 linha.

## Plano de testes

Sem teste automatizado: comparar cores em teste prenderia valores, não comportamento. A verificação é o grep e as
capturas.

## Critérios de pronto

- [ ] Grep de cores fixas sem resultados.
- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Capturas nos dois temas.
- [ ] `git status --short` só com os arquivos do escopo e `plans/README.md`.
- [ ] Linha 058 atualizada.

## Condições de PARADA

- O laranja proposto fica ilegível em alguma captura (texto "desatualizado" em Configurações › Cotas). Relate com a
  captura; não escolha outra cor sozinho.
- Surgir outro uso de `0xFFEF6C00` fora do escopo.

## Notas de manutenção

- Novo estado com cor própria: acrescente em `StatusColors.kt`, não em `Color(0x...)` solto.
- O plano 061 transforma a `ContextBar` em anel; ele mantém `warningColor()`.
