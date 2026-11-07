# Plano 044: indicadores de "trabalhando" sem consumir CPU com o app parado ou a tela bloqueada

> **Instruções ao executor**: siga os passos em ordem. Rode cada verificação e confirme o resultado antes de avançar. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize a linha 044 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`. Se houver mudança, compare os trechos de "Estado atual" com o código vivo. Divergência é condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: LOW (comportamento visual dos indicadores muda pouco; nenhum dado é tocado)
- **Depende de**: nenhum plano. Base: branch `advisor/043-zaplaud-estabilidade-avatares` (`778466839`), ainda não mesclada em `producao`; parta dela ou de `producao` depois do merge da 043.
- **Categoria**: perf
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

Medido num Samsung M35 (Android 16), sem ninguém tocar no celular e com agentes trabalhando:

| Estado | Build | CPU (% de 1 núcleo) | Quadros/s |
|---|---|---|---|
| Lista parada | release | 24,7 | 9,2 |
| Conversa aberta | release | 76,7 | 60,3 |
| Tela bloqueada, activity `STOPPED` | release | 27,4 | — |
| Lista parada | debug | 43,0 | 10–14 |
| Conversa aberta | debug | 86,5 | 60 |

Perfil (`simpleperf`) com tela bloqueada: `Choreographer.doFrame` continua rodando, `StatusLine` recompõe (10,8% inclusivo), o texto é remedido (16,5%) e o Compose refaz a verificação semântica (58% com serviços de acessibilidade ativos). Com a conversa aberta, `InfiniteTransition` do `WorkingIndicator` responde por 13% da thread principal, RenderThread por 34,5% e o driver de GPU por 17,3%.

Consequência: bateria e aquecimento enquanto o app fica aberto ou bloqueado sobre ele. A correção reduz o ritmo das atualizações ao que é visível e para tudo fora de `STARTED`.

## Estado atual

Arquivos:

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt` — lista, conversa, `StatusLine`, `WorkingIndicator`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt` — polling de `/api/cast/jobs` a cada 5 s.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt` — polling de `/api/snapshot` (60 s) e `/api/cast/roles`.

`ReadingScreen.kt:524-549` (cronômetro reescreve estado a cada 120 ms, sem olhar o ciclo de vida):

```kotlin
private val SPINNER = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
private val CODEX_SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
private const val SPINNER_MS = 120L
...
private fun StatusLine(session: SessionItem, modifier: Modifier = Modifier) {
    ...
    var now by remember { mutableLongStateOf(uiNowMillis()) }
    LaunchedEffect(animate) { while (true) { kotlinx.coroutines.delay(if (animate) SPINNER_MS else 1000L); now = uiNowMillis() } }
    val glyph = if (animate) frames[((now / SPINNER_MS) % frames.size).toInt()] else if (codex) "•" else "✻"
    ...
        Text(glyph, Modifier.width(14.dp).clearAndSetSemantics { }, ...)
        Text("$text…", ...)
        session.statusSince?.let { Text("(${stopwatch(now - it)})", ...) }
```

Usos: `ReadingScreen.kt:316` (uma por sessão trabalhando na lista) e `:405` (conversa aberta).

`ReadingScreen.kt:561-577` (`WorkingIndicator`): `rememberInfiniteTransition` com o avatar pulando via `Modifier.graphicsLayer { translationY = -6.dp.toPx() * bounce }` e três pontos com alpha. A translação por `graphicsLayer` muda os limites do nó na janela a cada quadro; com acessibilidade ativa isso gera eventos de limites (`boundsUpdatesEventLoop`, 9,7% medido).

`CastActions.kt:63-87`: `LaunchedEffect(api, connected, refresh) { ... while (true) { ... api.request("/api/cast/jobs") ...; delay(5_000) } }`.

`SettingsScreen.kt:76-81` e `:88-100`: dois `while (true)` com `delay`, também sem ciclo de vida.

Convenções:

- Ciclo de vida já usado no código comum: `ReadingScreen.kt:75-90` (`LocalLifecycleOwner.current`, `LifecycleEventObserver`). Dependência disponível: `org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.4` (`android/shared/build.gradle.kts:47`), que oferece `repeatOnLifecycle` e `LifecycleStartEffect` para Android e iOS.
- `animationsEnabled()` (`UiPlatform.kt:24`, implementação Android em `UiPlatform.android.kt:25-35`) desliga animações quando a escala de animação do sistema é 0. Preserve esse respeito.
- Testes puros do cronômetro: `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/StatusLineTest.kt` (`stopwatch`, `statusLineText`). Mantenha-os.
- Comentários e strings em português, como no arquivo.

## Comandos

| Objetivo | Comando (na raiz do repositório) | Esperado |
|---|---|---|
| Testes host + lint + APK | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados (emulador AVD `habblaud034`, `npm ci` antes) | `python3 scripts/android-instrumented-ci.py` | `BUILD SUCCESSFUL`, zero falhas |
| iOS (só no Mac) | `bash scripts/ios-native-tests.sh` | relatório sem falhas |

Não rode a suíte a cada edição: rode uma vez no fim dos passos de código.

## Escopo

**Pode modificar**: `ReadingScreen.kt`, `CastActions.kt`, `SettingsScreen.kt`, `StatusLineTest.kt` e um teste novo em `android/app/src/androidTest/java/br/com/xlinx/habblaud/` ou `android/shared/src/androidDeviceTest/...`, seguindo os existentes.

**Fora do escopo**: `ReadingController` e streams (o controlador já para em `ON_STOP`, `ReadingScreen.kt:82-90`); `NotificationService`; árvore semântica das bolhas (plano 045); troca de biblioteca; telemetria permanente.

## Passos

### Passo 1: cronômetro no ritmo de segundos, alinhado e só em `STARTED`

Em `StatusLine`:

- Escreva `now` no máximo uma vez por segundo, alinhado à virada do segundo (`delay(1000 - uiNowMillis() % 1000)`). Assim todas as linhas da lista mudam no mesmo quadro.
- Rode o laço dentro de `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { ... }`, com `val lifecycleOwner = LocalLifecycleOwner.current`, dentro do `LaunchedEffect`. Fora de `STARTED` nada roda; ao voltar, `now` é atualizado na hora.
- Spinner: troque o glifo sem recompor nem remedir. Desenhe o caractere num nó de tamanho fixo (`Modifier.width(14.dp)` já existe) e leia o quadro só na fase de desenho, por exemplo com `rememberInfiniteTransition` + `drawWithContent`/`drawText` (via `rememberTextMeasurer`), ou girando um glifo fixo com `graphicsLayer { rotationZ = ... }` dentro do nó de tamanho fixo. O requisito: nenhuma escrita de estado lida em composição a cada quadro do spinner. Com `animationsEnabled() == false`, glifo parado como hoje.
- A semântica do glifo continua `clearAndSetSemantics { }`.

**Verificar**: `android/gradlew -p android :shared:testAndroidHostTest --tests '*StatusLineTest*'` → passa (os testes de `stopwatch` não mudam).

### Passo 2: `WorkingIndicator` só desenha e só anima em `STARTED`

- Troque a translação por `graphicsLayer` por translação no desenho (`Modifier.drawWithContent { translate(top = ...) { this@drawWithContent.drawContent() } }`) ou outra forma que não altere a posição de layout nem os limites do nó. Reserve a altura do pulo no tamanho fixo para não cortar o avatar.
- Crie a `InfiniteTransition` só quando o ciclo de vida estiver em `STARTED` e as animações ligadas (`val started by lifecycleOwner.lifecycle.currentStateAsState()` e `started.isAtLeast(STARTED)`). Fora disso, desenho estático.
- Mantenha `contentDescription` do indicador.

### Passo 3: polling de rede só com a tela visível

- `CastActions.kt:63-87`: envolva o `while (true)` em `repeatOnLifecycle(STARTED)`. Ao voltar a `STARTED`, a primeira consulta sai na hora, como hoje.
- `SettingsScreen.kt:76-81` e `:88-100`: o mesmo.
- Não mude intervalos, tratamento de 401/403 nem estados de erro.

**Verificar (código)**: `rg -n 'while \(true\)' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/{ReadingScreen,CastActions,SettingsScreen}.kt` → cada ocorrência está dentro de um bloco `repeatOnLifecycle(Lifecycle.State.STARTED)`.

### Passo 4: suíte

Rode os comandos da tabela. Esperado: tudo verde, incluindo `FixtureUiInstrumentedTest`.

### Passo 5: medir antes e depois no mesmo aparelho e build

Use emulador ou aparelho de teste. No M35 do dono, só com autorização dele: `adb install -r` com a mesma assinatura; nunca desinstale nem rode `pm clear`.

Procedimento (o mesmo antes e depois, mesmo tipo de build):

1. `adb shell svc power stayon ac` se estiver carregando (anote o valor de `settings get global stay_on_while_plugged_in` e restaure no fim).
2. Lista com pelo menos duas sessões trabalhando, sem tocar: `adb shell dumpsys gfxinfo br.com.xlinx.habblaud reset`; espere 20 s; `adb shell dumpsys gfxinfo br.com.xlinx.habblaud | grep 'Total frames rendered'` → quadros/20.
3. CPU: leia `utime+stime` (campos 14 e 15 de `/proc/<pid>/stat`, `pid=$(adb shell pidof br.com.xlinx.habblaud)`) no início e no fim de 30 s; CPU% = Δticks / 100 / 30 × 100.
4. Repita com a conversa de um agente trabalhando aberta.
5. Bloqueie (`adb shell input keyevent KEYCODE_SLEEP`), espere 20 s e meça 60 s de CPU. Confirme `state=STOPPED` em `adb shell dumpsys activity activities | grep state=`.

**Aceite**:

- Lista parada: ≤ 1,5 quadro/s e CPU ≤ 25% do valor medido antes no mesmo build.
- Tela bloqueada: CPU do processo ≤ 3% de um núcleo.
- Conversa parada: CPU menor que antes, com os números registrados. Sem alvo fixo, porque a animação do indicador continua por design.
- Spinner e cronômetro continuam visíveis e corretos; com animações do sistema desligadas, nada se move.

## Plano de testes

- Teste instrumentado (padrão: `FixtureUiInstrumentedTest.kt`, regra Compose existente) com uma sessão `working` em fixture: avance o relógio de teste (`mainClock.autoAdvance = false`, `mainClock.advanceTimeBy`) e conte recomposições da `StatusLine` com um contador de teste injetado. Contrato: no máximo 1 atualização de texto por segundo. Se exigir API de teste no código de produção além de um parâmetro interno, PARE e relate.
- Teste de ciclo de vida: com `TestLifecycleOwner` (`androidx.lifecycle:lifecycle-runtime-testing`, só se já estiver no classpath de teste; senão use `ActivityScenario.moveToState(Lifecycle.State.CREATED)`), confirme que o texto do cronômetro não muda enquanto `CREATED` e atualiza ao voltar a `RESUMED`.
- Não escreva teste que só confira constantes ou o código-fonte.

## Critérios de pronto

- [ ] Comandos da tabela verdes; iOS rodado no Mac ou registrado como pendente.
- [ ] Medições antes/depois do Passo 5 registradas neste plano, com build, aparelho e estado da acessibilidade.
- [ ] `git status` mostra só arquivos do escopo.
- [ ] Linha 044 de `plans/README.md` atualizada.

## Condições de PARADA

- `repeatOnLifecycle`/`currentStateAsState` indisponíveis no código comum com a dependência 2.9.4.
- O spinner só fica correto com escrita de estado por quadro: relate a alternativa medida em vez de aceitar.
- Medição depois pior que antes em qualquer estado.
- A correção exige mexer em `ReadingController` ou `NotificationService`.

## Notas de manutenção

- Todo laço novo de UI com `delay` deve rodar dentro de `repeatOnLifecycle(STARTED)`.
- O plano 045 (semântica) parte deste: cronômetro mais lento reduz eventos de acessibilidade, mas a árvore grande continua.
- Os números de antes foram medidos com serviços de acessibilidade ativos (APH e Reading mode), já removidos pelo dono. Remeça o "antes" no estado atual do aparelho.
