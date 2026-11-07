# Plano 046: instalar o Zaplaud em release, assinado com a chave atual, em vez do build debug

> **Instruções ao executor**: siga os passos em ordem. Cada instalação no celular pessoal exige autorização explícita do dono naquela hora. Nunca desinstale, nunca rode `pm clear`. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 046 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/app/build.gradle.kts README.pt-BR.md`.

## Status

- **Prioridade**: P1
- **Esforço**: S (assinatura e instalação); M se o Passo 5 (R8) for adotado
- **Risco**: MED. Perder o arquivo da chave impede atualizar o app sem desinstalar e perder dados; R8 pode quebrar código refletivo.
- **Depende de**: nenhum
- **Categoria**: perf / dx
- **Planejado em**: commit `778466839`, 2026-11-07
- **Decisão do dono (2026-11-07)**: manter a chave atual como identidade do app pessoal, para atualizar por cima sem perder login nem rascunhos.

## Por que importa

O APK no M35 é `debuggable` (`aapt dump badging`: `application-debuggable`) e roda sem compilação antecipada (`dumpsys package dexopt`: `status=run-from-apk`). Mesmo roteiro de rolagem no M35, mesmo código:

| Build | Janky | p95 | p99 | Partida a frio |
|---|---|---|---|---|
| Debug recém-iniciado (JIT frio) | 16,57% | 65 ms | 150 ms | 911–1.055 ms |
| Debug aquecido | 7,05% | 30 ms | 61 ms | — |
| Release (`verify`) | 4,85% | 22 ms | 44 ms | 238–280 ms |
| Release (`speed-profile`) | 4,81% | 21 ms | 38 ms | 230–385 ms |

No debug, 41,9% das amostras da thread principal ficam em `libart.so` (interpretador). Cada vez que o Android encerra o processo, o debug volta ao estado de JIT frio.

## Estado atual

- `android/app/build.gradle.kts:21-36`: o `signingConfig` de release vem das variáveis/propriedades `HABBLAUD_KEYSTORE`, `HABBLAUD_KEYSTORE_PASSWORD`, `HABBLAUD_KEY_ALIAS` e `HABBLAUD_KEY_PASSWORD`. `:39-48` falham o `packageRelease` sem elas. Não há `isMinifyEnabled` nem arquivo de regras ProGuard.
- O certificado do app instalado é o da chave debug desta máquina: `~/.android/debug.keystore`, alias `androiddebugkey`, SHA-256 `82:de:00:34:cc:ee:ab:99:9e:30:06:00:bf:48:41:fd:b6:9e:67:d8:cb:4f:8a:a1:f9:12:f3:75:e0:e3:a7:eb`. A senha padrão desse arquivo é pública (`android`); por isso o passo 1 cria uma cópia com senha própria.
- Não existe `~/.gradle/gradle.properties` nesta máquina.
- README de build release: `README.pt-BR.md:271-281`.
- O código não usa `@Serializable` nem reflexão própria (`rg '@Serializable|Class.forName'` sem ocorrências em `commonMain`/`androidMain`). JSON é lido como `JsonElement`. OkHttp e Ktor trazem regras de consumidor.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Certificado de um APK | `/home/anderson/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs <apk> \| grep SHA-256` | `82de0034…a7eb` |
| Build release | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:assembleRelease` | APK em `android/app/build/outputs/apk/release/app-release.apk` |
| Suíte | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |

## Escopo

**Pode modificar**: `android/app/build.gradle.kts` (só o bloco `release` no passo 5); `android/app/proguard-rules.pro` (criar, só no passo 5); `README.pt-BR.md` e variantes de idioma (instruções de release); arquivos fora do repositório indicados nos passos.

**Fora**: código Kotlin; `applicationId`; `versionCode` (aumentar não é necessário para `install -r`); qualquer chave versionada no repositório.

## Passos

### Passo 1: chave de release com a mesma identidade e senha própria

1. `install -m 700 -d ~/.config/habblaud && cp ~/.android/debug.keystore ~/.config/habblaud/zaplaud-release.jks && chmod 600 ~/.config/habblaud/zaplaud-release.jks`.
2. Troque as senhas da cópia: `keytool -storepasswd -keystore ~/.config/habblaud/zaplaud-release.jks` e `keytool -keypasswd -alias androiddebugkey -keystore ~/.config/habblaud/zaplaud-release.jks`. A senha nova vem do dono (gerenciador de senhas dele). Não grave a senha em arquivo do repositório nem em log.
3. Backup: o dono escolhe o destino (cofre ou mídia criptografada). Registre neste plano só o tipo de destino, nunca a senha.

**Verificar**: `keytool -list -v -keystore ~/.config/habblaud/zaplaud-release.jks -alias androiddebugkey | grep SHA256` → mesmo SHA-256 acima.

### Passo 2: propriedades fora do repositório

Crie `~/.gradle/gradle.properties` (permissão 600) com `HABBLAUD_KEYSTORE=/home/anderson/.config/habblaud/zaplaud-release.jks`, `HABBLAUD_KEY_ALIAS=androiddebugkey` e as duas senhas. Se o dono preferir não gravar senha em disco, use variáveis de ambiente na hora do build.

**Verificar**: `android/gradlew -p android :app:assembleRelease` gera o APK, e `apksigner` mostra o SHA-256 esperado.

### Passo 3: instalar no M35 por cima (com autorização)

1. Confirme com `apksigner` que o certificado do APK novo é o mesmo do instalado (`adb shell pm path br.com.xlinx.habblaud`, depois `adb pull` do `base.apk` e `apksigner`). Se diferente, PARE.
2. `adb -s <serial> install -r android/app/build/outputs/apk/release/app-release.apk`.
3. Opcional, para o estado de uso contínuo: `adb -s <serial> shell cmd package compile -m speed-profile -f br.com.xlinx.habblaud`.

**Verificar**: o app abre já logado (lista visível, sem tela de login). `dumpsys package br.com.xlinx.habblaud | grep -c DEBUGGABLE` → `0`. `logcat -b crash` sem novos registros após 5 minutos de uso.

### Passo 4: medir

No M35, carregando, sem uso do dono, e com o mesmo estado de acessibilidade do "antes" (os serviços APH e Reading mode foram removidos depois da medição original; remeça o debug se precisar comparar):

- `adb shell dumpsys gfxinfo br.com.xlinx.habblaud reset`, depois um roteiro de rolagem igual ao do debug (abrir 3 conversas pela busca, 6 flings para cima e 6 para baixo em cada), depois `dumpsys gfxinfo`.
- Partida a frio: 3 × (`am force-stop br.com.xlinx.habblaud`; `am start -W -n br.com.xlinx.habblaud/.MainActivity`, campo `TotalTime`).

**Aceite**: janky ≤ 6%; p99 ≤ 50 ms; partida ≤ 400 ms (mediana).

### Passo 5 (opcional, só com medição): R8

Ative `isMinifyEnabled = true` e `isShrinkResources = true` no `release`, com `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`. Regras extras só quando um teste ou o smoke falhar, cada uma com um comentário de uma linha dizendo o motivo.

Smoke obrigatório no emulador com a fixture: login, lista, abrir conversa, enviar mensagem na fixture, avatar, notificação. Compare tamanho do APK e as métricas do passo 4.

**Adote só se** o smoke passar e as métricas não piorarem. Senão, desfaça e registre.

### Passo 6: documentação

Atualize `README.pt-BR.md:271-281` (e as variantes `README.md`, `README.es.md`, `README.fr.md` se tiverem o trecho):

- O app pessoal usa release assinado com a chave guardada fora do repositório.
- Trocar de chave exige desinstalar e perde login e rascunhos.
- Debug não deve ser instalado por cima para uso diário.

Não inclua caminhos com senha.

## Critérios de pronto

- [ ] SHA-256 da chave nova igual ao do app instalado.
- [ ] APK release instalado por cima com login preservado e `DEBUGGABLE` ausente.
- [ ] Métricas do passo 4 registradas e dentro do aceite.
- [ ] R8 adotado com smoke, ou registrado como não adotado.
- [ ] Nenhuma chave ou senha no `git status`; linha 046 atualizada.

## Condições de PARADA

- O certificado do APK release difere do instalado.
- O dono não autoriza a instalação no M35.
- O app abre deslogado ou sem dados depois do `install -r`.

## Notas de manutenção

- A chave em `~/.config/habblaud/zaplaud-release.jks` é a identidade do app. Sem ela, atualizar exige desinstalar.
- Builds debug continuam assinados com `~/.android/debug.keystore`, com o mesmo certificado. Instalar um debug por cima continua possível, mas volta a ser lento.
