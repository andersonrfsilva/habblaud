# Plano 131: Zaplaud Android: os avisos voltam sozinhos depois de reiniciar o aparelho

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 131 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- android/app/src/main/AndroidManifest.xml android/app/src/main/java/br/com/xlinx/habblaud/NotificationService.kt`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: MED (serviço em primeiro plano iniciado no boot; regras do Android 14+)
- **Depende de**: 096 (mesclado); decisão do dono em 2026-11-19 (opção A do spike 113)
- **Categoria**: feature
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Hoje, depois de reiniciar o telefone, os avisos do Zaplaud só voltam quando o dono abre o app: o serviço é
`START_STICKY` (o sistema tenta recriá-lo se matar o processo) mas nada o inicia no boot. Quem reinicia o aparelho
e não abre o app fica sem saber que um agente terminou ou pediu aprovação. O spike 113 (`plans/113-resultado.md`)
recomendou a opção A — receptor de boot, sem serviço externo — e o dono aprovou. Aviso nativo no iPad ficou fora:
exige Apple Developer Program pago.

## Estado atual

- `android/app/src/main/AndroidManifest.xml`: permissões `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (`:2-7`); serviço `.NotificationService` com
  `foregroundServiceType="specialUse"` (`:18-22`). Não há `RECEIVE_BOOT_COMPLETED` nem `<receiver>`.
- `NotificationService.kt`: `onStartCommand` devolve `START_STICKY` (`:52`); há um ponto único que decide iniciar
  (`ContextCompat.startForegroundService`, `:94`) depois de checar preferência de avisos, servidores cadastrados,
  permissão e canal (plano 096: "estado real"). Leia essa função inteira: o receptor deve chamar **a mesma**
  função, não repetir as condições.
- Regras do spike: usar `BOOT_COMPLETED` (depois do primeiro desbloqueio), **não** `LOCKED_BOOT_COMPLETED`; não
  mover login/cookie para armazenamento acessível antes do desbloqueio; `BOOT_COMPLETED` é exceção permitida para
  iniciar serviço em primeiro plano em segundo plano, mas no Android 15+ alguns tipos de FGS não podem ser
  iniciados do boot — confirme na documentação que `specialUse` pode
  (https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed) e cite em NOTES.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-131 -b advisor/131-zaplaud-android-avisos-voltam-depois-de-reiniciar-o-aparelho producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-131`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só Android: iPad não tem avisos em segundo plano (SideStore sem APNs) e o desktop Rust tem bandeja própria. Registre em `docs/zaplaud-paridade.md` na linha de avisos.
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
- `AndroidManifest.xml`, `NotificationService.kt`, um arquivo novo `BootReceiver.kt` no mesmo pacote
- teste em `android/app/src/androidTest/**` ou `src/test/**`
- `docs/zaplaud-paridade.md`, `docs/zaplaud-android.md` (se descrever avisos), `CHANGELOG.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- WorkManager, push, relé, isenção de bateria automática (o botão manual existente fica).
- Mudar as condições que decidem se os avisos estão ativos.

## Passos

### Passo 1: receptor

`RECEIVE_BOOT_COMPLETED` no manifesto; `<receiver android:name=".BootReceiver" android:exported="false">` com
filtro `android.intent.action.BOOT_COMPLETED` (o sistema entrega a receptor não exportado; confirme) e, para
atualização do próprio app, `android.intent.action.MY_PACKAGE_REPLACED` (depois de instalar uma versão nova o
serviço também morre). `BootReceiver.onReceive` chama a função existente que inicia o serviço se as condições
valerem, dentro de `try/catch` de `ForegroundServiceStartNotAllowedException`/`SecurityException`/`IllegalStateException`
(falha silenciosa com `Log.w`; o app volta ao normal quando for aberto). Como a função lê preferências de forma
assíncrona, use `goAsync()` com prazo curto, ou o que a função já oferecer, sem bloquear a thread principal.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :app:assembleDebug :app:lintDebug --max-workers=3`
→ `BUILD SUCCESSFUL`, lint sem erro novo.

### Passo 2: teste automatizado

Teste instrumentado: com avisos ligados e um servidor cadastrado, enviar o broadcast ao receptor
(instanciando `BootReceiver` e chamando `onReceive` com um `Intent(ACTION_BOOT_COMPLETED)`; broadcast protegido
não pode ser enviado por `sendBroadcast`) e verificar que o serviço fica em primeiro plano (a notificação fixa do
canal existe); com avisos desligados, ou sem servidor, o serviço **não** inicia. Use os utilitários que
`NotificationPrivacyInstrumentedTest`/os testes do plano 096 já têm para preparar esse estado.

**Verificação**: os casos novos passam no emulador 3 vezes seguidas.

### Passo 3: prova de reinício real no emulador

Com o app debug instalado, avisos ligados e servidor da fixture cadastrado: `adb reboot`, espere
`sys.boot_completed`, **não** abra o app, e verifique em até 60 s
`adb shell dumpsys activity services br.com.xlinx.habblaud | grep -i NotificationService` (serviço vivo, `isForeground=true`)
e `adb shell dumpsys notification --noredact | grep -i br.com.xlinx.habblaud`. Repita com avisos desligados: o
serviço não pode estar vivo. Guarde as saídas em `~/dados/habblaud/plano-131/`.

**Verificação**: ligado → serviço em primeiro plano sem abrir o app; desligado → nenhum serviço. Anote a versão
do Android do emulador. Sem `FATAL EXCEPTION` no `logcat` do boot.

### Passo 4: documentação

`CHANGELOG.md` ("Não lançado"): "Zaplaud (Android): os avisos voltam sozinhos depois de reiniciar o aparelho ou
atualizar o app, se estavam ligados." Paridade e guia Android: uma frase, com o limite (depende do primeiro
desbloqueio; o fabricante pode restringir apps em segundo plano; iPad não tem).

**Verificação**: `npm run i18n:check -- --strict` sai com 0 (rode `npm ci` antes).

## Plano de testes

Os casos instrumentados do passo 2.

## Critérios de pronto

Todos precisam valer:

- [ ] Receptor declarado e usando a mesma função de decisão do app
- [ ] Testes instrumentados novos passam 3 vezes
- [ ] Reinício real do emulador: serviço volta com avisos ligados e não volta com desligados
- [ ] Documentação atualizada
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 131 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A documentação do Android disser que `specialUse` não pode ser iniciado a partir de `BOOT_COMPLETED` na API alvo do app.
- Iniciar no boot exigir ler credenciais antes do primeiro desbloqueio.
- O serviço iniciar no boot e cair em seguida com exceção.

## Notas de manutenção

- Não testado em Samsung real: One UI pode colocar o app em "suspenso" e impedir o início; o dono confere no M35
  (reiniciar, desbloquear, esperar 1 min, ver a notificação fixa).
