# Plano 095: Zaplaud Android: o arquivo de sessão é gravado de forma atômica e só é apagado quando está de fato corrompido

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 095 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: MED (mexe no login guardado de todos os servidores; o formato cifrado não pode mudar)
- **Depende de**: 090 (gate)
- **Categoria**: bug
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O login de **todos** os servidores fica num único arquivo cifrado (`files/remote-session`). Ele é reescrito no
lugar a cada resposta que renova o cookie. Se o processo morrer no meio da escrita (o Android mata apps em
segundo plano o tempo todo), o arquivo fica truncado; na leitura seguinte, qualquer exceção, inclusive uma
falha passageira de leitura ou do cofre de chaves, **apaga o arquivo**. O usuário perde o login de todos os
servidores de uma vez, e a senha não é guardada (por decisão do projeto), então precisa digitar tudo de novo.

## Estado atual

`android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt:14-39`:

```kotlin
internal actual class SecureSessionStorage actual constructor() {
    private val file = storageContext.filesDir.resolve("remote-session")
    private fun key(): SecretKey { ... AndroidKeyStore "habblaud-remote-session", AES/GCM ... }

    actual fun read(): String? = try {
        if (!file.exists()) null else {
            val bytes = file.readBytes(); require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { file.delete(); null }
    actual fun write(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        file.writeBytes(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    actual fun clear() { check(!file.exists() || file.delete()) { "Could not clear session" } }
}
```

Quem chama: `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt:77-80` lê o mapa de todos os servidores
(`storage.read()?.let(::decodeCookies).orEmpty()`), troca a entrada do servidor atual e grava o mapa inteiro.
Se `read()` devolver `null` por engano, a gravação seguinte **apaga os outros servidores**.

O formato do arquivo é: 12 bytes de IV + texto cifrado AES-GCM. Ele não pode mudar (há instalações com login
guardado).

Teste existente do armazenamento: `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/`
(`SessionCookieStoreTest`, `PersistenceInstrumentedTest`), que roda em emulador.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-095 -b advisor/095-zaplaud-cookie-de-sessao-gravacao-atomica producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-095`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Este plano não muda comportamento compartilhado com o Rust.
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
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt`
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/SessionStorageAtomicInstrumentedTest.kt` (criar)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt` (a lógica comum de cookies).
- O armazenamento do iOS (Keychain) e do JVM.
- O nome do arquivo, o alias da chave e o formato cifrado.

## Passos

### Passo 1: gravação atômica com `android.util.AtomicFile`

Envolva o arquivo: `private val atomic = android.util.AtomicFile(file)`. `android.util.AtomicFile` é da
plataforma (API 17+), não exige dependência nova, grava num arquivo auxiliar e só troca pelo definitivo no
`finishWrite`; um arquivo antigo, escrito sem ele, é lido normalmente.

```kotlin
actual fun write(value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
    val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val out = atomic.startWrite()
    try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
}
actual fun clear() { atomic.delete(); check(!file.exists()) { "Could not clear session" } }
```

Em `read()`, leia com `atomic.readFully()` (ele recupera o arquivo de reserva se a última gravação não
terminou).

### Passo 2: apagar só quando o conteúdo é inválido

Em `read()`, separe as falhas:

- Conteúdo inválido (arquivo com 12 bytes ou menos, `javax.crypto.AEADBadTagException`,
  `javax.crypto.BadPaddingException`, `java.security.InvalidKeyException`/`UnrecoverableKeyException` quando a
  chave do cofre foi perdida): apague e devolva `null`, como hoje. Não há como recuperar.
- `java.io.IOException` (falha de leitura) e qualquer outra falha do cofre ou da cifra que não seja "conteúdo
  inválido" (`ProviderException`, `KeyStoreException` etc.): **não apague e não lance**. Tente ler uma segunda
  vez; se falhar de novo, devolva `null` mantendo o arquivo. `read()` nunca lançou e continua sem lançar: ela é
  chamada na criação de `HabblaudApi`, inclusive dentro do serviço de avisos, cujo escopo não tem tratador de
  exceção; lançar ali derruba o processo (corrigido na revisão da primeira execução; a versão anterior deste
  passo mandava relançar). Limite conhecido, a registrar em comentário `shortcut:` no código: uma gravação feita
  enquanto a leitura falha ainda substitui o arquivo e perde os outros servidores; continua melhor que o
  comportamento antigo, que apagava tudo na primeira falha.
- `java.io.FileNotFoundException`/arquivo inexistente: `null`.

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 3: teste em aparelho (compilar; executar só com autorização)

Crie `SessionStorageAtomicInstrumentedTest.kt` seguindo `SessionCookieStoreTest` do mesmo diretório (mesma
forma de obter o contexto e chamar `initializeStorage`):

- `roundTripAndOverwrite`: `write("a")`, `write("b")`, `read() == "b"`.
- `legacyFileStillReads`: grave um valor com a implementação nova, copie os bytes para o arquivo
  `remote-session` com `File.writeBytes` (simulando arquivo escrito pela versão antiga, sem auxiliar) e
  confirme que `read()` devolve o valor.
- `truncatedFileIsDiscarded`: escreva 5 bytes no arquivo → `read() == null` e o arquivo deixa de existir.
- `interruptedWriteKeepsPreviousValue`: `write("a")`; chame `startWrite()` num `AtomicFile` do mesmo caminho,
  escreva lixo e **não** chame `finishWrite` nem `failWrite` → um `SecureSessionStorage()` novo lê `"a"`.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest`
→ `BUILD SUCCESSFUL`. A execução no emulador depende de autorização do dono; registre no índice se rodou.

## Plano de testes

Os quatro casos do passo 3. Sem autorização para emulador, o aceite é compilar; nesse caso o índice deve dizer
"testes de aparelho escritos, não executados".

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'AtomicFile' android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/SessionCookieStore.kt` → presente; `grep -n 'file.writeBytes' ` no mesmo arquivo → ausente
- [ ] O `catch` de `read()` não apaga o arquivo em `IOException`
- [ ] `:shared:compileAndroidDeviceTest` e o comando de host completo terminam em `BUILD SUCCESSFUL`
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 095 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A tarefa `:shared:compileAndroidDeviceTest` não existir: liste com `:shared:tasks --all | grep -i devicetest` e
  use a de compilação; nunca rode `connected*` sem autorização.
- `SecureSessionStorage` tiver mudado de forma (por exemplo, já usar DataStore).

## Notas de manutenção

- Depois de instalar esta versão por cima de uma antiga, o login tem de continuar valendo: é o teste manual mais
  importante (o dono instala no M35 e confere que não pediu login).
- Se um dia o arquivo passar a guardar mais coisas, mantenha uma única gravação atômica por mudança.
