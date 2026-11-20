# Plano 149: Zaplaud Android: GIF animado toca na conversa

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat eca711a..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt android/shared/src/androidMain android/shared/src/iosMain`
> Se algum mudou desde `eca711a`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P2 · **Esforço**: M · **Risco**: MED (memória e bateria; código compartilhado com o iPad, que não compila aqui)
- **Depende de**: nenhum · **Categoria**: feature
- **Planejado em**: commit `eca711a`, 2026-11-20 (pedido do dono; iPad fica de fora e bloqueado)

## Por que importa

Agentes entregam animações (ciclos de caminhada de sprites) como `.gif`. O Zaplaud mostra só o primeiro quadro;
o dono não vê a animação que pediu.

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownImages.kt:71-140`: `MarkdownImage` carrega os
  bytes (dados, arquivo do servidor ou remoto; cache em memória `imageCache`, teto `IMAGE_CACHE_MAX_BYTES`), mede com
  `imageDimensions` (`expect`, `:57`; Android em `androidMain/.../MarkdownImages.android.kt` via `BitmapFactory`),
  recusa acima de `IMAGE_MAX_PIXELS` (16 MP) e desenha `BitmapPainter(encoded.decodeToImageBitmap())` — **um quadro
  só**. Toque abre `MermaidFullscreen(painter, alt)`.
- iOS: `iosMain/.../MarkdownImages.ios.kt` tem o `actual` de `imageDimensions`. **O iPad não compila nesta máquina e o
  CI está desligado**: nenhuma assinatura `expect` nova pode ser criada (ela exigiria `actual` no iOS sem compilador).
- `minSdk = 26` (`android/app/build.gradle.kts:13`, `android/shared/build.gradle.kts:12`). `ImageDecoder` e
  `AnimatedImageDrawable` existem a partir da API 28.

## Decisões do dono (2026-11-20)

- Toca sozinho, em loop, quando aparece na conversa; para fora da tela.
- Com "remover animações" do sistema ligado (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`, ou a preferência de
  acessibilidade equivalente que o app já respeite, se houver), não toca sozinho: mostra o primeiro quadro e toca no
  toque.
- Limites: no máximo **300 quadros** e **50 MB** decodificados por GIF; acima, primeiro quadro como hoje.
- iPad fora: continua primeiro quadro.

## Escopo

**Dentro:** `MarkdownImages.kt` (só o ponto de escolha do pintor e a tela cheia), um arquivo novo em `androidMain`
com o pintor animado, testes de host e um teste instrumentado, `CHANGELOG.md`, `docs/zaplaud-paridade.md`.

**Fora:** `expect`/`actual` novos (iOS), dependência nova (Coil, Glide, Accompanist), avatares, imagens remotas além
do que já existe, WebP animado (pode vir de graça pelo mesmo decodificador; se vier, diga, sem testar a fundo).

## Passos

### Passo 1: decidir "é GIF animado?" sem mudar o contrato comum

Função pura em `commonMain` (sem `expect`): lê o cabeçalho GIF (`GIF87a`/`GIF89a`) e conta quadros percorrendo os
blocos (descritores de imagem `0x2C`, extensões `0x21`, terminador `0x3B`) sem decodificar pixels, com teto de leitura;
devolve número de quadros e dimensões lógicas. Testes de host com GIFs mínimos gerados no teste (1 quadro, 3 quadros,
truncado, não GIF).

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest --max-workers=3` passa com os testes novos.

### Passo 2: pintor animado só no Android

O ponto de decisão fica no código comum de forma que o iOS compile sem mudança: por exemplo, um
`CompositionLocal`/parâmetro opcional com fábrica de pintor animado cujo padrão é `null` (iOS e testes usam o caminho
atual), fornecido pelo app Android em `android/app` ou `androidMain` ao montar a interface. Se não houver como fazer
sem `expect` novo, PARE.

No Android, API ≥ 28: `ImageDecoder.createSource(ByteBuffer)` → `decodeDrawable` → `AnimatedImageDrawable`
(`repeatCount = REPEAT_INFINITE`), desenhado por um `Painter` que chama `invalidateSelf` via `Drawable.Callback` e
invalida a composição a cada quadro. Inicie no `onStart`/quando visível e pare quando sai da composição ou da tela
(`DisposableEffect`). API < 28, quadros > 300, memória estimada (largura × altura × 4 × quadros) > 50 MB, ou
"remover animações" ligado → caminho atual (primeiro quadro). Com "remover animações", um toque inicia a reprodução.
A tela cheia (`MermaidFullscreen`) recebe o mesmo pintor e anima.

**Verificação**: `:app:assembleDebug :app:lintDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin` → `BUILD SUCCESSFUL`;
`:shared:testAndroidHostTest` passa.

### Passo 3: prova no emulador

Fixture: uma mensagem com um GIF fictício de 4 quadros de cores diferentes (gerado no teste, via o mecanismo de
`--files` do plano 147). Teste instrumentado: abrir a conversa, capturar o nó da imagem em dois instantes separados
por mais que a duração de um quadro e conferir que os pixels mudaram; com `ANIMATOR_DURATION_SCALE=0`
(`adb shell settings put global animator_duration_scale 0`, restaurado no fim) os pixels não mudam até o toque. A
suíte instrumentada completa (`python3 scripts/android-instrumented-ci.py`) passa uma vez.

**Verificação**: teste novo 3/3; suíte completa sem falha.

### Passo 4: documentação

`CHANGELOG.md`: "Zaplaud (Android): GIF animado toca na conversa (Android 9 ou superior)." Paridade: linha "GIF
animado" — Android sim, desktop plano 150, iPad bloqueado (não compila sem Mac/CI).

**Verificação**: build e testes de host do passo 2 continuam passando.

## Critérios de pronto

- [ ] GIF animado toca na conversa e em tela cheia (API ≥ 28); para fora da tela
- [ ] Remover animações respeitado; limites de 300 quadros / 50 MB
- [ ] iOS sem `expect` novo e sem mudança de comportamento
- [ ] Build, lint, host, teste instrumentado novo e suíte completa ok

## Condições de PARADA

- Não houver como injetar o pintor animado sem `expect`/`actual` novo.
- A animação deixar a rolagem da conversa travando (relate os números de quadros por segundo do teste).

## Notas de manutenção

- iPad bloqueado: quando houver como compilar iOS, um plano próprio usa `CGImageSource` para os quadros.
