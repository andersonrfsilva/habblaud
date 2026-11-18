# Plano 103: Zaplaud iPad: escolher arquivos demais não derruba o app, trocar de app não apaga a tela e o projeto adota UIScene

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 103 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt ios/Zaplaud/AppDelegate.swift ios/Zaplaud/Info.plist ios/Zaplaud.xcodeproj/project.pbxproj`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: L
- **Risco**: MED (ciclo de vida da janela e do seletor mudam; só verificável num Mac)
- **Depende de**: Mac acessível (`macbook-air-de-mira`) e autorização do dono para simulador; mesma dependência dos planos 041 e 053
- **Categoria**: bug / migration
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Três problemas do alvo iPad, achados por leitura (o iOS não compila nesta máquina; nada foi executado):

1. **Fechamento do app**: escolher seis ou mais arquivos no seletor lança uma exceção Kotlin dentro do retorno
   do UIKit (o limite é cinco), fora de qualquer `try`. Exceção Kotlin não tratada atravessando um retorno
   nativo encerra o app.
2. **Tela apagada ao trocar de app**: ao ir para segundo plano, toda a interface é removida da composição
   (`if (foreground) ZaplaudApp()`). Na volta, o app recomeça: o formulário de login em preenchimento, a busca e
   a aba somem. Quem alterna para o gerenciador de senhas para copiar a senha perde o endereço digitado.
3. **UIScene**: o app cria a janela no `AppDelegate`. A Apple avisou (nota técnica TN3187) que apps compilados
   com o SDK seguinte ao do iOS 26 precisam do ciclo de vida por cenas para abrir. Não afeta o IPA atual; trava a
   próxima atualização de Xcode.

## Estado atual

`android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt`:

```kotlin
// :15-33
fun MainViewController(): UIViewController {
    lateinit var controller: UIViewController
    controller = ComposeUIViewController {
    var foreground by remember { mutableStateOf(UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val stopped = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { foreground = false }
        val started = center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { foreground = true }
        onDispose { center.removeObserver(stopped); center.removeObserver(started) }
    }
    CompositionLocalProvider(...) {
        if (foreground) ZaplaudApp()
    }
    }
    return controller
}
// :56-65  retorno do seletor
object : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val tokens = retainPickedDocuments(didPickDocumentsAtURLs.filterIsInstance<NSURL>())   // fora do try
        pendingTokens.addAll(tokens)
        try { callback(tokens) }
        catch (error: Throwable) { discardPickedDocuments(tokens); pendingTokens.removeAll(tokens.toSet()); throw error }
    }
// :70-71
val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = false)
picker.allowsMultipleSelection = true
```

`android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt:30-32`:

```kotlin
fun retainPickedDocuments(urls: List<NSURL>): List<String> {
    val selected = urls.distinctBy { it.absoluteString }
    checkAttachmentCount(0, selected.size)      // require(...): lança IllegalArgumentException acima de 5
```

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt:7-8`: `const val MAX_ATTACHMENTS = 5` e `checkAttachmentCount`.

No Android o excesso é tratado pelo controlador: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt:131-150` (`select` →
`selectPrepared`), que compara com `MAX_ATTACHMENTS`, libera o excedente e mostra o erro de anexo na tela.

A parada da rede em segundo plano **não** depende de remover a composição: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:137-145`
observa o ciclo de vida (`LocalLifecycleOwner`) e chama `controller.stop()` em `ON_STOP`; `SettingsScreen.kt`
usa `repeatOnLifecycle(STARTED)`.

`ios/Zaplaud/AppDelegate.swift:4-15`: `@main final class AppDelegate` cria `UIWindow(frame: UIScreen.main.bounds)`
com `MainViewControllerKt.MainViewController()` como raiz. `ios/Zaplaud/Info.plist` não tem
`UIApplicationSceneManifest`.

`ios/ZaplaudUITests/ZaplaudUITests.swift:48-54`: o smoke põe o app em segundo plano por 30 s e confere que o
rascunho sobrevive e que nada é reenviado.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-103 -b advisor/103-zaplaud-ipad-seletor-segundo-plano-e-uiscene producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-103`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Só iPad; nada a portar.
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
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt`
- `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt`
- `ios/Zaplaud/AppDelegate.swift`, `ios/Zaplaud/SceneDelegate.swift` (criar), `ios/Zaplaud/Info.plist`,
  `ios/Zaplaud.xcodeproj/project.pbxproj` (só para incluir o arquivo novo no alvo)
- `android/shared/src/iosTest/kotlin/br/com/xlinx/habblaud/data/NativeStorageTest.kt` (caso novo do seletor)
- `ios/ZaplaudUITests/ZaplaudUITests.swift` (caso novo de segundo plano no login)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Código comum (`commonMain`) e Android.
- Suporte a várias janelas no iPad: uma cena só.
- Leitura coordenada de arquivos de provedores externos (iCloud) e exclusão de backup: achados separados, sem
  como verificar aqui; ficam listados em "Notas de manutenção".
- Avisos no iPad (plano 113).

## Passos

### Passo 1: seletor recusa o excesso sem lançar

Em `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt`, faça `retainPickedDocuments` reter no máximo `MAX_ATTACHMENTS + 1` documentos
distintos em vez de lançar: troque `checkAttachmentCount(0, selected.size)` por
`val selected = urls.distinctBy { it.absoluteString }.take(MAX_ATTACHMENTS + 1)`. Com seis itens retidos, o
controlador comum (`MessageController.selectPrepared`) detecta o excesso, libera os documentos e mostra o
erro de anexo, como no Android. Reter um a mais é de propósito: é o que faz o erro aparecer em vez de o app
aceitar cinco em silêncio.

Em `MainViewController.kt:59`, mova a chamada para dentro de um `try` que, em qualquer `Throwable`, chama
`callback(emptyList())` em vez de relançar. Nenhuma exceção deve sair do método do delegate.

**Verificação** (no Mac): `bash scripts/ios-native-tests.sh` → passa, com o caso novo
`retainingMoreThanLimitDoesNotThrow` em `NativeStorageTest.kt` (6 URLs temporárias → devolve 6 tokens, sem
exceção; depois `discardPickedDocuments` libera todos).

### Passo 2: a interface fica viva em segundo plano

Em `MainViewController.kt`, remova o estado `foreground`, os dois observadores e o `if`; chame `ZaplaudApp()`
direto dentro do `CompositionLocalProvider`.

**Verificação** (no Mac): `bash scripts/ios-smoke.sh` → passa, incluindo o trecho existente de 30 s em segundo
plano. Acrescente ao `ZaplaudUITests.swift` um caso: digitar o endereço do servidor na tela de login, mandar
o app para segundo plano (`XCUIDevice.shared.press(.home)`), reativar (`app.activate()`) e afirmar que o
campo ainda tem o endereço.

### Passo 3: conferir que a rede para em segundo plano

O `if (foreground)` pode ter sido posto para garantir que os streams fechem. Confirme que o ciclo de vida do
Compose no iOS faz isso: com o app do simulador conectado à fixture (`scripts/ios-smoke.sh` mostra como ela
sobe), mande o app para segundo plano e veja no log da fixture que a conexão de `/api/stream` fecha em poucos
segundos.

**Verificação**: a conexão fecha. Se **não** fechar, é condição de PARADA (ver abaixo).

### Passo 4: ciclo de vida por cenas

Crie `ios/Zaplaud/SceneDelegate.swift`:

```swift
import UIKit
import ZaplaudShared

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = MainViewControllerKt.MainViewController()
        window.makeKeyAndVisible()
        self.window = window
    }
}
```

Em `AppDelegate.swift`, remova a criação da janela (o método `didFinishLaunchingWithOptions` passa a só
devolver `true`) e a propriedade `window`. Em `Info.plist`, acrescente `UIApplicationSceneManifest` com
`UIApplicationSupportsMultipleScenes = false` e uma configuração `UIWindowSceneSessionRoleApplication` cujo
`UISceneDelegateClassName` é `$(PRODUCT_MODULE_NAME).SceneDelegate`. Inclua `SceneDelegate.swift` no alvo
`Zaplaud` do `project.pbxproj`, copiando as entradas que `AppDelegate.swift` já tem (referência de arquivo,
arquivo de build e fase de fontes).

**Verificação** (no Mac): `bash scripts/ios-build.sh simulator` → compila; `bash scripts/ios-smoke.sh` →
passa (abertura, login, envio, segundo plano, seletor de documentos).

## Plano de testes

- `NativeStorageTest.kt`: `retainingMoreThanLimitDoesNotThrow` (passo 1).
- `ZaplaudUITests.swift`: caso do login que sobrevive ao segundo plano (passo 2).
- Os smokes existentes (`scripts/ios-native-tests.sh`, `scripts/ios-smoke.sh`) continuam passando.
- Android não pode regredir: comando de host completo → `BUILD SUCCESSFUL`.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'foreground' android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt` → nenhuma ocorrência
- [ ] `grep -n 'checkAttachmentCount' android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/Attachments.kt` → nenhuma ocorrência em `retainPickedDocuments`
- [ ] `grep -n 'UIApplicationSceneManifest' ios/Zaplaud/Info.plist` → presente
- [ ] No Mac: `scripts/ios-build.sh simulator`, `scripts/ios-native-tests.sh` e `scripts/ios-smoke.sh` passam
- [ ] Comando de host completo (Android) termina em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 103 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Não há Mac acessível: **não execute este plano**. Editar `iosMain`, Swift e o projeto do Xcode sem compilar
  entrega código quebrado. Marque BLOCKED no índice com o motivo.
- No passo 3 a conexão não fechar em segundo plano: devolva o `if (foreground)` e relate; a correção passa a ser
  preservar o estado (por exemplo com `rememberSaveable` nos campos do login), o que é outro desenho.
- O smoke falhar no seletor de documentos depois do passo 4 (a apresentação usa `presenter.view.window`).

## Notas de manutenção

- Achados de iPad que ficaram fora (sem verificação possível): leitura de arquivos de provedores externos sem
  `NSFileCoordinator` (`iosMain/.../data/Attachments.kt`); downloads concluídos só alcançáveis enquanto a folha
  está aberta (`FileDownloads.ios.kt`); primeiro login em rede local sem tratar o pedido de permissão de rede
  local; conversas e anexos em `Application Support` entrando no backup do aparelho; ícone do app ausente.
  Cada um precisa de um iPad físico para ser confirmado.
- Ao subir a versão do Xcode no CI (`.github/workflows/ios.yml`, hoje 26.4), este plano precisa estar mesclado.
