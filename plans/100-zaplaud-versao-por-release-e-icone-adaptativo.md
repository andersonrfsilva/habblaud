# Plano 100: Zaplaud: cada APK distribuído tem número de versão próprio, visível em Configurações, e o ícone é adaptativo

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 100 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml android/app/src/main/res ios/Zaplaud.xcodeproj/project.pbxproj`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: nenhum
- **Categoria**: dx
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Todo APK já instalado é `versionCode 1`, `versionName "1.0"`; o iPad idem (`CURRENT_PROJECT_VERSION = 1`). Não
há como saber qual build está no aparelho, qual build um relato de erro descreve, nem como uma loja ou um canal
de atualização distinguiria um build do outro. O ícone é um vetor único com fundo desenhado: em lançadores com
máscara (redondo, "squircle") ele é cortado ou encolhido, e não há versão monocromática para ícones temáticos.

## Estado atual

`android/app/build.gradle.kts:15-16`: `versionCode = 1` e `versionName = "1.0"`.

`ios/Zaplaud.xcodeproj/project.pbxproj:35-36` (configurações Debug e Release do alvo): `MARKETING_VERSION = 1.0; CURRENT_PROJECT_VERSION = 1;`.

`android/app/src/main/AndroidManifest.xml:8`: `android:icon="@drawable/ic_zaplaud"`. `minSdk = 26`, então ícone
adaptativo é suportado em todos os aparelhos aceitos.

`android/app/src/main/res/drawable/ic_zaplaud.xml` (108 × 108):

```xml
<path android:fillColor="#087D66" android:pathData="M0,0h108v108h-108z" />                 <!-- fundo -->
<path android:fillColor="#F5FFF9" android:pathData="M32,34 Q32,28 38,28 L70,28 ... Z" />     <!-- balão -->
<path android:fillColor="#087D66" android:pathData="M45,41 L63,41 L45,55 ... Z" />           <!-- Z -->
<path android:fillColor="#087D66" android:pathData="M67,33 L68.5,37.5 ... Z" />              <!-- estrela -->
```

`ic_zaplaud` é referenciado no manifesto e no desktop obsoleto; `android/app/src/main/res` tem só `drawable/`,
`values/` e `xml/`.

A tela de Configurações é `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`
(seções com `Section(Res.string....)` e `Hint(...)`).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-100 -b advisor/100-zaplaud-versao-por-release-e-icone-adaptativo producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-100`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só Android/iOS; nada a portar.
- **CHANGELOG**: toda mudança visível ao usuário ganha uma linha em "Não lançado" no `CHANGELOG.md`.
- **Emulador, simulador e aparelhos do dono só com autorização expressa dele.** Sem ela, o aceite deste plano é
  compilar e passar os testes de host; registre no índice o que ficou sem verificação em aparelho.
- O desktop Compose (`android/desktop`, `android/shared/src/jvmMain`, `jvmTest`) está obsoleto: não recebe
  melhorias. Só toque nele se uma assinatura `expect` mudar e o `actual` do JVM deixar de compilar.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Testes de host, lint e APK debug | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` (base: 141 testes, 0 falhas; lint 0 erros) |

## Escopo

**Pode modificar**:
- `android/app/build.gradle.kts` (versão)
- `ios/Zaplaud.xcodeproj/project.pbxproj` (as duas ocorrências de versão)
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (criar), `res/drawable/ic_zaplaud_foreground.xml` e
  `res/drawable/ic_zaplaud_monochrome.xml` (criar), `res/values/colors.xml` (criar, uma cor)
- `android/app/src/main/AndroidManifest.xml` (atributo `android:icon`)
- `README.pt-BR.md`, seção "Compilar e instalar" (procedimento de versão)
- `SettingsScreen.kt`, `UiPlatform.kt` e os `actual` de plataforma **somente** para mostrar a versão (passo 3)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- A chave de assinatura e o `applicationId`: não mudam (trocar a chave apaga os dados instalados).
- Ícone do iPad (catálogo de recursos no Xcode): exige Mac; fica registrado como pendente.
- Automatizar o número da versão por contagem de commits ou CI.
- `drawable/ic_zaplaud.xml`: fica como está (o desktop obsoleto o usa).

## Passos

### Passo 1: número de versão e procedimento

Em `android/app/build.gradle.kts`: `versionCode = 2` e `versionName = "1.1"`. No `project.pbxproj`, nas duas
linhas (35 e 36): `MARKETING_VERSION = 1.1; CURRENT_PROJECT_VERSION = 2;`.

Em `README.pt-BR.md`, na seção "Compilar e instalar", acrescente um parágrafo: antes de distribuir um APK
release ou um IPA, some 1 ao `versionCode` (`android/app/build.gradle.kts`) e ao `CURRENT_PROJECT_VERSION`
(`ios/Zaplaud.xcodeproj/project.pbxproj`), ajuste `versionName`/`MARKETING_VERSION` e faça o commit
`chore(zaplaud): versão X.Y (N)`; o `versionCode` nunca diminui nem se repete.

**Verificação**: `grep -n 'versionCode\|versionName' android/app/build.gradle.kts` → `2` e `"1.1"`;
`grep -c 'CURRENT_PROJECT_VERSION = 2' ios/Zaplaud.xcodeproj/project.pbxproj` → `2`.

### Passo 2: ícone adaptativo

Crie `res/values/colors.xml` com `<color name="ic_zaplaud_background">#087D66</color>`.

Crie `res/drawable/ic_zaplaud_foreground.xml`: o mesmo vetor de `ic_zaplaud.xml` **sem** o primeiro `<path>`
(o fundo). O desenho já fica dentro da área segura central (ocupa de 32 a 76 e 28 a 78 num quadro de 108).

Crie `res/drawable/ic_zaplaud_monochrome.xml`: o mesmo do foreground, com `android:fillColor="#FFFFFF"` no
balão e **sem** os dois caminhos internos (Z e estrela), que no monocromático virariam um bloco único; em vez
disso, recorte-os do balão usando um único `<path>` com `android:fillType="evenOdd"` cujo `pathData` é a
concatenação dos três `pathData` (balão + Z + estrela).

Crie `res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_zaplaud_background" />
    <foreground android:drawable="@drawable/ic_zaplaud_foreground" />
    <monochrome android:drawable="@drawable/ic_zaplaud_monochrome" />
</adaptive-icon>
```

No manifesto: `android:icon="@mipmap/ic_launcher"`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL` (o `lintDebug` reclama de ícone inválido).
Depois `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -c 'ic_launcher'` → pelo menos `1`.

### Passo 3: versão visível em Configurações

Acrescente em `UiPlatform.kt` um `expect fun appVersion(): String` com `actual`:
- Android (`androidMain/.../UiPlatform.android.kt`): leia do `PackageManager`
  (`context.packageManager.getPackageInfo(context.packageName, 0)`, `versionName` e `longVersionCode`),
  usando o contexto que o arquivo já obtém para outras funções; formato `"1.1 (2)"`.
- iOS (`iosMain/.../MainViewController.kt`): `NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString")`
  e `"CFBundleVersion"`, mesmo formato.
- JVM obsoleto (`jvmMain/.../UiPlatform.jvm.kt`): `actual fun appVersion() = ""` (só para compilar).

No fim de `SettingsScreen`, depois da última seção, mostre uma linha discreta com a string nova
`settings_version` (`"Versão %1$s"`) quando `appVersion()` não for vazio, com o mesmo componente `Hint` usado
na tela.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`;
`grep -n 'settings_version' android/shared/src/commonMain/composeResources/values/strings.xml` → 1 linha.

## Plano de testes

Sem teste automatizado novo: são recursos e uma leitura de metadado. O aceite é o build e o lint. Reteste manual
para o dono (pendente até ele fazer): instalar o release por cima do atual no M35 e conferir que o login
continua, que Configurações mostra "Versão 1.1 (2)" e que o ícone ocupa a máscara do lançador.

## Critérios de pronto

Todos precisam valer:

- [ ] `versionCode = 2` e `versionName = "1.1"`; `CURRENT_PROJECT_VERSION = 2` nas duas configurações do iOS
- [ ] `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` existe com `background`, `foreground` e `monochrome`
- [ ] `grep -n 'android:icon' android/app/src/main/AndroidManifest.xml` → `@mipmap/ic_launcher`
- [ ] `grep -n 'expect fun appVersion' android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/UiPlatform.kt` → 1 linha
- [ ] Comando de host completo termina em `BUILD SUCCESSFUL` sem erro novo de lint
- [ ] `README.pt-BR.md` descreve o procedimento de versão; `CHANGELOG.md` cita a versão em Configurações e o ícone
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 100 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- O `actual` do iOS não puder ser conferido (sem Mac) e houver dúvida de sintaxe: entregue os passos 1 e 2 e o
  Android do passo 3, e pare antes de tocar em `iosMain`, relatando.
- O lint acusar o ícone monocromático como inválido depois de uma tentativa de correção.

## Notas de manutenção

- O ícone do iPad (AppIcon no catálogo de recursos) continua faltando: fazer no Mac, junto do plano 103.
- Este plano não cria canal de atualização; é pré-requisito do plano 110.
