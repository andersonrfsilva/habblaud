# Regenerar o baseline Material 3

`crates/zaplaud-ui/theme/m3-baseline.json` foi extraído em 09/11/2026 da biblioteca real `org.jetbrains.compose.material3:material3-desktop:1.9.0`, resolvida pelo Compose Multiplatform 1.12.2 do projeto. Os 48 papéis são ARGB; o gerador converte para RGBA Slint e aplica por cima as cores lidas do Kotlin a cada build.

Crie temporariamente `android/shared/src/jvmTest/kotlin/br/com/xlinx/habblaud/M3BaselineDumpTest.kt`, com um teste `kotlin.test.Test`. Instancie `lightColorScheme()` e `darkColorScheme()` sem argumentos. Para cada instância, selecione via reflexão os métodos sem argumentos cujo nome começa por `get` e termina por `-0d7_KjU`. O nome do papel é o trecho entre esses marcadores, com inicial minúscula. Converta o retorno assim:

```kotlin
val argb = Color((method.invoke(scheme) as Long).toULong()).toArgb().toUInt()
val value = "#" + argb.toString(16).padStart(8, '0')
```

Grave JSON com `material3` (coordenada da versão efetivamente resolvida), `light` e `dark`. Use `kotlinx.serialization.json` para escapar e indentar. Configure temporariamente `android/local.properties` com o SDK local e execute:

```sh
cd android
./gradlew :shared:jvmTest --tests br.com.xlinx.habblaud.M3BaselineDumpTest -Dorg.gradle.workers.max=4
```

Copie o JSON para o caminho acima. Remova o teste e `local.properties` temporários. Não os commite. Atualize a versão declarada somente depois de conferir a biblioteca resolvida. Rode `cargo test -p zaplaud-ui --test resource_generation -j 4` na pasta `zaplaud-rs`.
