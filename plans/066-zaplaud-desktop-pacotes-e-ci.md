# Plano 066: pacotes do Zaplaud desktop (Linux e macOS), ajuste da JVM e verificação no CI

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação antes do próximo. Em condição de
> PARADA, pare e relate; não improvise. Ao terminar, atualize a linha 066 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 694938f..HEAD -- android/desktop .github/workflows/android.yml`.
> `android/desktop` deve existir (criado pelo 063). Se não existir, PARE: o 063 não foi integrado.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: LOW
- **Depende de**: `063`; recomendável depois de `064` e `065`, para o pacote já ter as duas colunas e os avisos
- **Categoria**: dx / direction
- **Planejado em**: commit `694938f`, 2026-11-09

## Por que importa

Depois do 063, o desktop só roda com `./gradlew :desktop:run`. O dono precisa instalar o Zaplaud como app nas
máquinas Linux e no Mac, com ícone no menu, e quer o app leve. A medição do protótipo
(`/tmp/zaplaud-desktop-spike/REPORT.md`, 2026-11-09) mostrou que flags da JVM mudam o consumo: JVM padrão 240 MiB
RSS parado e 994 ms para abrir; com `-Xmx128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`, 209 MiB e 876 ms. O pacote
com runtime embutido tem 140 MiB.

## Estado atual

- `android/desktop/build.gradle.kts` (do 063) tem `compose.desktop { application { mainClass = "br.com.xlinx.habblaud.desktop.MainKt"; nativeDistributions { packageName = "Zaplaud"; packageVersion = "1.0.0" } } }`.
- Ícone: vetor Android em `android/shared/src/commonMain/composeResources/drawable/ic_zaplaud.xml` (108×108; os
  `pathData` são sintaxe de caminho SVG). Ferramentas presentes na máquina de desenvolvimento: `rsvg-convert` e `magick`.
- CI Android: `.github/workflows/android.yml`, job `verificar` (ubuntu-latest, Temurin **17**), passo
  `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` em `android/`.
  Outro job do mesmo arquivo já roda `npm ci` (linha ~90).
- As máquinas Linux do dono rodam Arch (pacote `.deb`/`.rpm` não instala direto). O Mac (`macbook-air-de-mira`) fica
  offline com frequência (planos 041/053); `.dmg` só pode ser gerado num Mac (o `jpackage` não gera pacote de outro SO).

## Decisões já tomadas

- Formatos: `TargetFormat.Deb`, `TargetFormat.Rpm`, `TargetFormat.Dmg`. Para Arch, o artefato é o diretório de
  `createReleaseDistributable` compactado em `.tar.gz` + um `.desktop` instalado por script do usuário.
- Flags da JVM no pacote: só `-XX:+UseSerialGC` e `-Xmx256m` (folga sobre os 128 MiB do protótipo, que não tinha
  markdown nem rede). Sem `-XX:TieredStopAtLevel=1`: piora o JIT em conversa longa. O plano 067 mede o app real e
  pode ajustar.
- Assinatura/notarização do macOS: fora (uso pessoal; abrir com botão direito → Abrir na primeira vez).

## Comandos que você vai usar

Rode de `android/`. Worktree `/tmp/habblaud-066`, branch `advisor/066-zaplaud-desktop-pacotes`.

| Propósito | Comando | Esperado |
|---|---|---|
| Distribuição (Linux) | `./gradlew :desktop:createReleaseDistributable` | `android/desktop/build/compose/binaries/main-release/app/Zaplaud/` com `bin/Zaplaud` |
| `.deb` / `.rpm` | `./gradlew :desktop:packageReleaseDeb :desktop:packageReleaseRpm` | arquivos em `android/desktop/build/compose/binaries/main-release/{deb,rpm}/` (o `.rpm` exige `rpmbuild`; se faltar, registre e siga) |
| `.dmg` (só no Mac) | `./gradlew :desktop:packageReleaseDmg` | arquivo em `.../main-release/dmg/` |
| Módulos do runtime | `./gradlew :desktop:suggestRuntimeModules` | lista de módulos `java.*`/`jdk.*` |
| JVM (regressão) | `./gradlew :shared:jvmTest` | exit 0 |

## Escopo

**Dentro do escopo**:
- `android/desktop/build.gradle.kts`
- `android/desktop/icons/zaplaud.svg`, `android/desktop/icons/zaplaud.png`, `android/desktop/icons/zaplaud.icns` (criar; `.icns` só se houver Mac)
- `android/desktop/zaplaud.desktop` e `scripts/desktop-install-linux.sh` (criar)
- `.github/workflows/android.yml` (um passo novo no job `verificar`)
- `README.md`, `README.pt-BR.md`: só a seção do Zaplaud, se existir; acrescente "Desktop (Linux e macOS)" com os
  comandos. Os READMEs em es/fr seguem o mesmo trecho traduzido apenas se a seção do Zaplaud existir neles.
- `CHANGELOG.md`

**Fora do escopo**:
- Código Kotlin em `shared` ou `desktop/src` (se o pacote quebrar por reflexão, ver PARADA).
- Assinatura Apple, notarização, AUR, Flatpak, AppImage, atualização automática.

## Git

Commits como `build(desktop): pacotes deb, rpm e dmg com ícone` e `ci(desktop): testes JVM e distribuição no CI`.

## Passos

### Passo 1: ícone

Crie `android/desktop/icons/zaplaud.svg` transcrevendo o vetor (`viewBox="0 0 108 108"`; cada `<path android:fillColor="X" android:pathData="Y"/>`
vira `<path fill="X" d="Y"/>`). Gere o PNG: `rsvg-convert -w 512 -h 512 android/desktop/icons/zaplaud.svg -o android/desktop/icons/zaplaud.png`.
No Mac: `iconutil` a partir de um `.iconset` com os tamanhos 16–1024 (ou `magick zaplaud.png zaplaud.icns`).

**Verificar**: `file android/desktop/icons/zaplaud.png` → `PNG image data, 512 x 512`.

### Passo 2: `nativeDistributions` e JVM

```kotlin
nativeDistributions {
    targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
    packageName = "Zaplaud"
    packageVersion = "1.0.0"
    description = "Cliente do Habblaud"
    vendor = "xlinx"
    modules(/* saída de :desktop:suggestRuntimeModules */)
    linux { iconFile.set(project.file("icons/zaplaud.png")); shortcut = true; menuGroup = "Network" }
    macOS { bundleID = "br.com.xlinx.zaplaud"; iconFile.set(project.file("icons/zaplaud.icns")) }  // só se o .icns existir
}
jvmArgs += listOf("-XX:+UseSerialGC", "-Xmx256m")
buildTypes.release.proguard { /* padrão ligado */ }
```

**Verificar**: `./gradlew :desktop:createReleaseDistributable` → exit 0; rode
`android/desktop/build/compose/binaries/main-release/app/Zaplaud/bin/Zaplaud` contra o servidor de teste
(`node --import tsx scripts/android-smoke.ts --port 14748 --allow terminal,messages,permissions`), faça login
(`android-test`/`android-test`), abra uma conversa e envie uma mensagem. Sem tela, use `xvfb-run -a`.

### Passo 3: instalação no Arch

`scripts/desktop-install-linux.sh`: copia o diretório da distribuição para `~/.local/opt/zaplaud` (substituindo a
versão anterior), instala `android/desktop/zaplaud.desktop` em `~/.local/share/applications/` com
`Exec=$HOME/.local/opt/zaplaud/bin/Zaplaud` e `Icon=$HOME/.local/opt/zaplaud/lib/Zaplaud.png` (confira o caminho real
do ícone na distribuição), e roda `update-desktop-database ~/.local/share/applications` se existir. `set -euo pipefail`.
Não usa `sudo`.

**Verificar**: `bash scripts/desktop-install-linux.sh && desktop-file-validate ~/.local/share/applications/zaplaud.desktop`
→ sem erros; `~/.local/opt/zaplaud/bin/Zaplaud` existe.

### Passo 4: pacotes

`./gradlew :desktop:packageReleaseDeb :desktop:packageReleaseRpm`. No Mac, se acessível: `packageReleaseDmg`.

**Verificar**: os arquivos existem; `dpkg-deb -I <deb>` (se houver `dpkg-deb`) mostra `Package: zaplaud`.

### Passo 5: CI

No job `verificar` de `.github/workflows/android.yml`, depois do passo Gradle atual, acrescente:
```yaml
      - uses: actions/setup-node@<mesma referência fixada usada no outro job do arquivo>
        with: { node-version: '24', cache: npm }
      - run: npm ci
      - run: xvfb-run -a ./gradlew :shared:jvmTest :desktop:createReleaseDistributable
        working-directory: android
```
Use as mesmas versões fixadas por hash que o arquivo já usa para `setup-node`. Acrescente
`'scripts/desktop-install-linux.sh'` aos `paths` do `push`/`pull_request` só se o script for usado no CI (não é: não
acrescente).

**Verificar**: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml'))"` → sem erro. Não
dispare o workflow sem ordem do operador.

## Plano de testes

Nenhum teste novo: a prova é o passo 2 (app empacotado conversa com o servidor de teste) e o CI rodando `jvmTest` +
distribuição.

## Critérios de pronto

- [ ] `createReleaseDistributable` e `packageReleaseDeb` → exit 0; app empacotado faz login e envia mensagem na fixture.
- [ ] `du -sh android/desktop/build/compose/binaries/main-release/app/Zaplaud` registrado na linha 066 (comparar com 140 MiB do protótipo).
- [ ] `desktop-file-validate` sem erros.
- [ ] YAML válido; `git status` só com arquivos do escopo.
- [ ] Entrada em "Não lançado" no `CHANGELOG.md`: pacotes `.deb`, `.rpm`, `.dmg` e instalação no Arch.

## Condições de PARADA

- O release com ProGuard quebra em tempo de execução (erro de classe ausente em `kotlinx.serialization`, Ktor, JNA
  ou `java-keyring`): relate o erro e o tamanho com `proguard { isEnabled.set(false) }`. Não escreva regras ProGuard
  por tentativa e erro além de uma rodada.
- `suggestRuntimeModules` omite algo e o app empacotado falha (por exemplo, `jdk.crypto.ec` para TLS): acrescente o
  módulo citado no erro uma vez; se falhar de novo, PARE.
- `-Xmx256m` causa `OutOfMemoryError` ao abrir a conversa mais longa da fixture: relate; não suba o limite sem medir (plano 067).

## Notas de manutenção

- `packageVersion` precisa subir a cada entrega ao Mac (o `.dmg` recusa versão igual em atualização) [INFERÊNCIA].
- Sem assinatura, o macOS bloqueia a primeira abertura; o dono abre com botão direito → Abrir.
- O CI só prova Linux. O `.dmg` depende de um Mac acessível, como os gates de iOS dos planos 041/053.
