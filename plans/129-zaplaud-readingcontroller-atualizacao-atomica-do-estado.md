# Plano 129: Zaplaud Kotlin: o estado do `ReadingController` é atualizado de forma atômica

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 129 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 3152f8c..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/ReadingController.kt`
> Se algum arquivo do escopo mudou desde `3152f8c`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW-MED (arquivo central da leitura de conversas)
- **Depende de**: 122 (mesclado)
- **Categoria**: tech-debt
- **Planejado em**: commit `3152f8c`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

O plano 122 mostrou que `ReadingController` perde atualizações quando duas rotinas escrevem o estado em threads
diferentes: `refreshUnread` lê `mutable.value`, monta uma cópia e grava, apagando o `readingLoaded = true` que a
rotina de abertura acabara de publicar; o toque no resumo passava a ser ignorado. Em produção isso não ocorre
hoje porque o escopo da tela roda tudo na thread principal, mas nada no código garante: basta alguém lançar uma
dessas rotinas em outro despachante, ou o Compose mudar o despachante do `rememberCoroutineScope`, para o defeito
aparecer em uso real sem nenhum teste acusar.

## Estado atual

`mutable` é um `MutableStateFlow<ReadingState>` (ou equivalente; confirme a declaração). Escritas no formato
`mutable.value = mutable.value.copy(...)` ou `val state = mutable.value; ...; mutable.value = state.copy(...)` nas
linhas (levantamento do executor do 122, base `producao`): 101, 108, 124, 128, 147, 151, 163, 171, 185, 193, 202,
204, 217, 236, 277, 297, 313, 319, 342, 365, 366; `:280` e `:326` copiam por funções auxiliares. Caso que falhou:
`refreshUnread` (`:209-218`) contra a abertura (`:313`).

`kotlinx.coroutines.flow.update { }` faz a leitura-cópia-gravação com comparação e troca: a função pode rodar mais
de uma vez, então **não pode ter efeito colateral** (lançar corrotina, gravar em disco, mudar `var` da classe).
Várias dessas escritas vêm misturadas com efeitos (`reading = ...`, `seen = next; saveSeen(...)`, `persist()`).

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção) nem direto no clone:
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-129 -b advisor/129-zaplaud-readingcontroller-atualizacao-atomica-do-estado producao`. Ao terminar,
  deixe os commits no branch e remova a worktree (`git worktree remove /tmp/habblaud-129`). Saídas de build ficam
  na worktree em `/tmp`, nunca em `~/Desenvolvimento`.
- **Commits**: um por passo lógico, no estilo do `git log` (`fix(zaplaud): bate-papo paralelo acompanha o fim da
  resposta`, `feat(zaplaud): ...`, `docs(planos): ...`). Não faça push, merge nem PR sem ordem do operador.
- **Textos visíveis** do Zaplaud ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
  (o cliente Rust gera os dele desse arquivo). Nada de texto fixo no código Kotlin.
- **Paridade Kotlin/Rust** (`AGENTS.md`): mudança de comportamento num cliente entra no outro na mesma entrega, ou
  vira pendência registrada em `docs/zaplaud-paridade.md`. Rust tem redutor próprio de estado, sem este padrão; nada a portar. Diga isso em `docs/zaplaud-paridade.md` só se houver linha sobre o assunto (não há: sem mudança).
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
- `ReadingController.kt`
 - `android/shared/src/commonTest/**` ou `androidHostTest/**` para o teste novo

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Mudar comportamento, ordem de eventos ou despachantes. Outros controladores. Testes instrumentados (já corrigidos no 122).

## Passos

### Passo 1: teste que falha hoje

Escreva um teste de host que exercite a corrida real: construa o controlador com as dependências falsas que os
testes vizinhos de `ReadingController`/`ConversationRepository` já usam (procure em `commonTest`/`androidHostTest`),
com um escopo em `Dispatchers.Default`, e dispare em paralelo muitas vezes as duas operações que colidiram
(abertura de conversa publicando `readingLoaded = true` e `refreshUnread` via chegada de snapshot). Afirme que,
ao final, `readingLoaded` é verdadeiro. Se o controlador não puder ser construído em teste de host (dependência
de contexto Android), extraia para uma função pura apenas a parte testável ou teste a primitiva (duas rotinas
fazendo cópia sobre o mesmo `MutableStateFlow`) e explique a limitação em NOTES.

**Verificação**: o teste falha na base em pelo menos algumas de 200 repetições (mostre a contagem).

### Passo 2: trocar as escritas

Para cada escrita listada: separe o cálculo puro do efeito colateral. O novo estado passa a ser publicado com
`mutable.update { it.copy(...) }`, usando **`it`** (não uma leitura anterior) para todos os campos derivados do
estado atual; os efeitos (`persist()`, `saveSeen`, atribuição a `var` da classe, lançamento de corrotina) ficam
fora do bloco `update`, antes ou depois, na mesma ordem relativa de hoje. Onde o valor novo depende de uma
leitura feita antes de uma suspensão (`val state = mutable.value` seguido de `suspend` e depois `state.copy`),
recalcule a partir de `it` dentro do `update`. Em `refreshUnread`, `unread` passa a ser calculado sobre
`it.sessions`/`it.selected`.

**Verificação**: `grep -n 'mutable.value = ' ReadingController.kt` não devolve nenhuma escrita por cópia de
leitura anterior (as que restarem devem ser atribuições de valor completo que não dependem do estado atual;
liste-as com justificativa); o teste do passo 1 passa 200 de 200.

### Passo 3: regressão

`ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:assembleDebug :shared:compileAndroidDeviceTest :app:compileDebugAndroidTestKotlin --max-workers=3`.

**Verificação**: `BUILD SUCCESSFUL`; host = 173 + testes novos, 0 falhas.

## Plano de testes

O teste de corrida do passo 1.

## Critérios de pronto

Todos precisam valer:

- [ ] Teste falha na base e passa 200/200 depois
 - [ ] Nenhuma escrita por cópia de leitura anterior restante sem justificativa
 - [ ] Build e testes de host ok
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 129 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `3152f8c`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Uma escrita não puder virar `update` sem mudar a ordem de um efeito observável.
 - O teste de host não conseguir reproduzir a corrida de forma alguma.

## Notas de manutenção

- O revisor roda a suíte instrumentada no emulador depois do merge (o iOS compila no CI).
