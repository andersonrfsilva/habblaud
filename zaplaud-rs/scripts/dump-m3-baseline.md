# Manutenção do baseline Material 3

`crates/zaplaud-ui/theme/m3-baseline.json` foi extraído em 09/11/2026 da biblioteca real `org.jetbrains.compose.material3:material3-desktop:1.9.0`, resolvida pelo Compose Multiplatform 1.12.2 do projeto. Os 48 papéis são ARGB; o gerador converte para RGBA Slint e aplica por cima as cores lidas do Kotlin a cada build.

O procedimento de extração dependia do desktop Compose, removido em 2026-11-18, e não pode mais ser executado nesta árvore. Mantenha `crates/zaplaud-ui/theme/m3-baseline.json` à mão a partir das paletas de `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt`.
