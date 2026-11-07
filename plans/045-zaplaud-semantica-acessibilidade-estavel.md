# Plano 045: árvore de acessibilidade menor e estável, para o Compose não refazer o trabalho a cada quadro

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e relate. Ao terminar, atualize a linha 045 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat 778466839..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`. Se mudou além do plano 044, compare os trechos abaixo com o código vivo.

## Status

- **Prioridade**: P3. Era P1 com os serviços APH e Reading mode ativos no M35; o dono os removeu. Continua relevante para quem usa TalkBack, gerenciadores de senha ou outros serviços de acessibilidade.
- **Esforço**: M
- **Risco**: MED (pode piorar a leitura por TalkBack se mesclar demais)
- **Depende de**: `plans/044-zaplaud-indicadores-sem-consumo-ocioso.md` (cronômetro em segundos e pausado fora de `STARTED`)
- **Categoria**: perf / acessibilidade
- **Planejado em**: commit `778466839`, 2026-11-07

## Por que importa

Com qualquer serviço de acessibilidade ativo, o Compose mantém a árvore semântica e, a cada mudança, a percorre e compara. Medido no M35 com dois serviços ativos (`simpleperf`, % inclusivo da thread principal):

- Rolagem (debug): `getCurrentSemanticsNodes` 14,2%; `checkForSemanticsChanges` 9,8%; eventos de rolagem 5,6%.
- Lista parada (debug): `checkForSemanticsChanges` 25,6%; envio de eventos estruturais 20,6%.
- Conversa parada (release): ~30%, incluindo `boundsUpdatesEventLoop` 9,7%, causado pelo avatar que pula (tratado no 044).
- Desligar os serviços no mesmo build: janky 7,05% → 5,49%; p95 30 → 24 ms.

Duas causas sob controle do app: textos que mudam com frequência (cronômetro) e muitos nós semânticos por bolha (Markdown e `SelectionContainer`).

## Estado atual

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt:547`: o cronômetro `Text("(${stopwatch(now - it)})", ...)` não tem semântica própria, logo cada atualização é mudança de texto acessível. Depois do 044 é 1 por segundo; antes era ~8.
- `ReadingScreen.kt:309`: a linha da lista usa `clickable`, que já mescla descendentes. Não precisa de mudança.
- `ReadingScreen.kt:618-700` (`EntryView`): cada bolha é um `Surface` sem clique, com `SelectionContainer { Markdown(...) }` (`:677-691`) e o horário (`:697`). O Markdown (`com.mikepenz:multiplatform-markdown-renderer-m3` 0.38.0) cria vários `Text` por parágrafo, lista, tabela e código, cada um um nó. Links são abertos ao toque (README: "links abrem ao toque").
- Cabeçalhos de grupos de ferramentas e pensamentos (`:630`) são `clickable` e já mesclam.

## Comandos

| Objetivo | Comando | Esperado |
|---|---|---|
| Testes host + lint + APK | `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `python3 scripts/android-instrumented-ci.py` (emulador `habblaud034`, `npm ci` antes) | zero falhas |
| Eventos de acessibilidade | `adb -s <emulador> shell uiautomator events` por 20 s, contando as linhas `EventType:` | contagem registrada |
| Nós semânticos visíveis | `adb -s <emulador> shell uiautomator dump /sdcard/u.xml && adb shell cat /sdcard/u.xml \| grep -o '<node' \| wc -l` | contagem registrada |

`uiautomator events` liga a acessibilidade (UiAutomation) enquanto roda. É a condição que se quer medir.

## Escopo

**Pode modificar**: `ReadingScreen.kt` (semântica de `StatusLine` e `EntryView`) e testes instrumentados no padrão de `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`.

**Fora**: biblioteca de Markdown (sem troca nem fork); textos visíveis; linhas da lista; plano 044.

## Passos

### Passo 1: medir a linha de base no emulador

Use a fixture (`npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages`; instruções em `README.pt-BR.md`, seção "Testes instrumentados com fixture"). Abra uma conversa longa com Markdown (listas, código, links) e uma sessão trabalhando. Registre:

- (a) eventos de acessibilidade em 20 s parado na lista;
- (b) o mesmo, parado na conversa;
- (c) nós em `uiautomator dump` na conversa;
- (d) `dumpsys gfxinfo` com 10 flings na conversa.

### Passo 2: cronômetro com semântica estável

No `Text` do cronômetro da `StatusLine`, exponha à acessibilidade só a granularidade de minutos: `Modifier.clearAndSetSemantics { contentDescription = <"N min"> }`, usando uma string de recurso nova em `composeResources/values/strings.xml` (padrão `Res.string.*`). O valor muda no máximo uma vez por minuto. O texto visível continua em segundos.

**Verificar**: (a) cai para perto de zero eventos por segundo atribuíveis ao cronômetro. TalkBack no emulador lê "Executando… N min".

### Passo 3: experimento de bolha mesclada, só se mantiver links e seleção

Teste `Modifier.semantics(mergeDescendants = true)` no `Surface` de bolhas `user` e `assistant` (`EntryView`, `:619-623`). Valide com TalkBack ligado no emulador:

1. A bolha é lida inteira, na ordem.
2. Um link dentro da bolha continua acionável (menu de links do TalkBack ou ação equivalente).
3. A seleção de texto continua funcionando com toque.

Se 2 ou 3 falhar, desfaça e registre o resultado. Não mescle bolhas com links nesse caso. Uma alternativa permitida é mesclar só bolhas sem link (detecte `](` ou `http` no texto), registrando a escolha.

**Verificar**: (c) cai e (d) não piora.

### Passo 4: suíte e registro

Rode os comandos da tabela. Registre (a)–(d) antes/depois neste plano.

## Plano de testes

- Teste instrumentado: com `composeTestRule.onAllNodes(...)`, a bolha `assistant` da fixture aparece como um único nó mesclado (só se o Passo 3 for adotado), e o cronômetro tem `contentDescription` em minutos.
- Não teste o texto exato do recurso nem a presença de modificadores no código.

## Critérios de pronto

- [ ] Medições (a)–(d) antes/depois registradas.
- [ ] TalkBack validado nos três pontos do Passo 3, ou experimento descartado e registrado.
- [ ] Suíte verde; `git status` só com arquivos do escopo; linha 045 atualizada.

## Condições de PARADA

- Qualquer piora de leitura com TalkBack que não se resolva desfazendo o Passo 3.
- A redução exige alterar a biblioteca de Markdown.

## Notas de manutenção

- Texto que muda periodicamente deve expor semântica estável ou de baixa granularidade.
- Ao atualizar o renderer de Markdown (0.38.0 → 0.46.0 disponível), remeça (c) e (d).
