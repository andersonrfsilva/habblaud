# Plano 106: Zaplaud Android: o cache de conversas no aparelho tem teto de espaço

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 106 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.android.kt`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: MED (apagar cache não pode apagar rascunho, marcas nem posição de leitura de conversa recente)
- **Depende de**: 090 (gate)
- **Categoria**: perf
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Cada conversa aberta no app vira um arquivo no aparelho com as últimas 500 entradas (até cerca de 0,5 MiB cada,
medido na auditoria de 2026-11-07). Há limite por arquivo, mas nenhum limite para a **soma**: quem usa o app por
meses acumula um arquivo por conversa já aberta, para sempre. Os arquivos só somem quando o servidor é removido.
Em uso diário com dezenas de sessões por dia, isso chega a centenas de MiB num ano.

## Estado atual

`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt:52-58`: `encodeConversation` guarda `entries.takeLast(500)` (limite por conversa),
junto com os resumos e a posição de leitura (`reading`) daquela conversa.

`android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.android.kt`:

```kotlin
// :139-140  um arquivo por conversa: <conversations>/[geração/]<tag do servidor>/<hash>.json
private fun conversationFile(key: SessionKey, generation: String?): java.io.File =
    java.io.File(java.io.File(if (generation == null) conversations else java.io.File(conversations, generation), tag(key.origin)), hashed("", key).name + ".json")
// :152-170  gravação (sob coordinator.lock, em Dispatchers.IO)
internal actual suspend fun setConversation(key: SessionKey, entries: ..., write: ConversationWrite, summaries: ..., reading: ...) {
    try {
        beforeConversationWrite()
        coordinator.lock.withLock {
            ...
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = conversationFile(key, data[cacheGenerationKey])
                    file.parentFile!!.mkdirs()
                    val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(encodeConversation(entries, truncated, summaries, reading))
                    check(tmp.renameTo(file))
                    coordinator.written[key] = write.sequence
                }
            }
        }
// :243-246  única remoção: ao remover o servidor, apaga a pasta dele
```

Rascunhos, marcas (fixada/favorita), modo de envio e "lidos" ficam no DataStore (`context.localState`), **não**
nesses arquivos. O arquivo de conversa guarda só: entradas, resumos e posição de leitura daquela conversa.
Perder um arquivo significa: a conversa é baixada de novo do servidor ao abrir (até 500 entradas), os resumos são
pedidos de novo e a posição de leitura daquela conversa volta ao fim.

iOS (`android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.kt:128-137`): mesmo desenho,
um arquivo `conversation-<geração>-<tag>-<hash>.json` por conversa em `privateDirectory`, sem teto.

Testes do armazenamento Android: `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/PersistenceInstrumentedTest.kt`
e `ConversationEpochInstrumentedTest.kt`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-106 -b advisor/106-zaplaud-cache-de-conversas-com-teto producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-106`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. No Rust o cache fica no `zaplaud-store`; confira se há teto e registre a diferença em `docs/zaplaud-paridade.md` se houver. O iOS fica pendente (precisa de Mac): registre na mesma tabela.
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
- `android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.android.kt`
- `android/shared/src/androidDeviceTest/kotlin/br/com/xlinx/habblaud/ConversationCacheBudgetInstrumentedTest.kt` (criar)
- `docs/zaplaud-paridade.md` (pendência do iOS)

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- `iosMain`: sem Mac, não mexa; registre a pendência.
- O formato do arquivo e o limite de 500 entradas.
- **Mudar** como a posição de leitura e a expansão são gravadas (hoje regravam a conversa inteira): este plano só
  **mede** isso (passo 4) e registra a decisão; a mudança, se a medição pedir, é outro plano.
- O DataStore (rascunhos, marcas, lidos).

## Passos

### Passo 1: teto aplicado depois de cada gravação

Em `LocalStateStore.android.kt`, crie uma função privada chamada depois do `renameTo` bem-sucedido, ainda
dentro do `withContext(Dispatchers.IO)` e do `coordinator.lock`:

```kotlin
private const val CONVERSATION_CACHE_MAX_BYTES = 64L * 1024 * 1024

/** Apaga as conversas menos recentes até a soma caber no teto; nunca a que acabou de ser gravada. */
private fun trimConversationCache(root: java.io.File, keep: java.io.File) {
    val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.toList()
    var total = files.sumOf { it.length() }
    if (total <= CONVERSATION_CACHE_MAX_BYTES) return
    for (file in files.sortedBy { it.lastModified() }) {
        if (total <= CONVERSATION_CACHE_MAX_BYTES) break
        if (file == keep) continue
        val size = file.length()
        if (file.delete()) total -= size
    }
}
```

`root` é a pasta `conversations` (todas as gerações e servidores). Para não percorrer a pasta a cada gravação,
chame só quando o arquivo gravado for novo (não existia antes do `renameTo`) ou, mais simples, a cada N
gravações do processo (contador em memória, N = 20). Escolha a segunda forma e deixe o comentário
`// shortcut: conferência a cada 20 gravações; o teto pode ser ultrapassado por pouco entre conferências`.

Ao apagar um arquivo, remova a chave correspondente de `coordinator.written` se o mapa a tiver (a chave é a
`SessionKey`; como o nome do arquivo é um hash, limpe `coordinator.written` das chaves cujo
`conversationFile(key, generation)` não existe mais).

**Verificação**: comando de host completo → `BUILD SUCCESSFUL`.

### Passo 2: teste de aparelho (compilar; executar só com autorização)

`ConversationCacheBudgetInstrumentedTest.kt`, no padrão de `ConversationEpochInstrumentedTest.kt` (mesmo modo
de criar `LocalStateStore` e `SessionKey`): torne o teto e o intervalo ajustáveis para teste (propriedades
`internal var` no `LocalStateStore` Android, com os valores padrão acima). Com teto de 200 KB e intervalo 1,
grave 10 conversas de ~50 KB cada; afirme que a soma dos arquivos fica ≤ 200 KB, que a **última** gravada
ainda é lida por `conversation(key)` e que a primeira devolve `null`.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:compileAndroidDeviceTest`
→ `BUILD SUCCESSFUL`.

### Passo 3: registro

`docs/zaplaud-paridade.md`: acrescente uma linha na tabela "Cache de conversas com teto de espaço | Android:
sim (64 MiB); iPad: **pendente** | (resultado da conferência no Rust) | conversas antigas são baixadas de novo
ao abrir". `CHANGELOG.md` ("Não lançado"): uma linha.

### Passo 4: medir a regravação da conversa por rolagem e expansão, e decidir

Fato do código (`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt:216-258`):
`expand(...)` chama `persist()` na hora e `position(...)` chama `persist(500)`; os dois terminam em `save(key)`,
que copia as entradas e os resumos e manda `store.setConversation(...)` regravar o arquivo inteiro (até 500
entradas). A gravação roda em `Dispatchers.IO`, mas **sob `coordinator.lock`**
(`LocalStateStore.android.kt:155`), o mesmo cadeado que `conversation(key)` (`:141`) usa para ler; e `open(item)`
espera a gravação pendente (`ReadingController.kt:287`, `writeJob?.join()`). Portanto ler devagar ou expandir
várias linhas gera uma regravação por pausa, e reabrir uma conversa logo depois pode esperar por ela. O custo
real nunca foi medido.

Meça, sem mudar comportamento:

1. Num teste de aparelho descartável (não commitado), no padrão de `ConversationEpochInstrumentedTest.kt`: grave
   com `setConversation` uma conversa de 500 entradas somando ~500 KiB (o maior caso real medido em 2026-11-07
   foi 527 KiB) 20 vezes seguidas e anote a mediana e o pior tempo por gravação; depois, com uma gravação em
   andamento, meça quanto `conversation(key)` espera.
2. Conte as gravações numa leitura normal: com um contador temporário em `save(key)`, role uma conversa longa
   por 60 s parando a cada poucos segundos e expanda 5 itens; anote gravações por minuto.

Isto exige emulador ou aparelho: **só com autorização do dono**. Sem ela, não invente números: registre no
índice "regravação por rolagem/expansão: medição pendente" e encerre o passo.

Decisão, registrada na linha 106 do índice com os números:

- Gravação com mediana ≤ 30 ms **e** espera de `conversation(key)` ≤ 50 ms **e** ≤ 30 gravações por minuto:
  não fazer nada; mova o item para "Findings considered and rejected" com os números.
- Qualquer limite ultrapassado: **não corrija aqui**. Relate os números e peça um plano próprio (a correção
  provável é guardar posição e expansão num arquivo pequeno separado, com migração do formato atual, o que
  toca Android, iOS e o cache existente).

**Verificação**: a linha 106 do índice traz os três números e a decisão, ou a frase de medição pendente.

## Plano de testes

O teste do passo 2. Sem emulador autorizado, o aceite é compilar; registre no índice que não foi executado.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'CONVERSATION_CACHE_MAX_BYTES' android/shared/src/androidMain/kotlin/br/com/xlinx/habblaud/data/LocalStateStore.android.kt` → definição e uso
- [ ] A função de corte nunca apaga o arquivo recém-gravado (`keep`)
- [ ] Comando de host completo e `:shared:compileAndroidDeviceTest` terminam em `BUILD SUCCESSFUL`
- [ ] `docs/zaplaud-paridade.md` registra a pendência do iPad
- [ ] Passo 4: índice com os números medidos e a decisão, ou "medição pendente" por falta de autorização
- [ ] Nenhum contador ou teste descartável do passo 4 ficou no código (`git status` limpo fora do escopo)
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 106 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- A posição de leitura ou os resumos tiverem passado a ficar fora do arquivo de conversa (mudou o que se perde ao
  apagar): reavalie com o dono.
- Arquivos que não são cache de conversa viverem na mesma pasta `conversations`.

## Notas de manutenção

- 64 MiB equivale a algo como 120 a 600 conversas, conforme o tamanho. Se o dono quiser guardar mais, é a
  constante.
- O iPad continua sem teto até alguém com Mac repetir o plano em `iosMain`.
