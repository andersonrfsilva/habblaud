# Plano 055: Zaplaud usa ícones Material em vez de caracteres de texto

> **Instruções ao executor**: siga os passos em ordem e confirme cada verificação. Em condição de PARADA, pare e
> relate; não improvise. Ao terminar, atualize a linha 055 em `plans/README.md`.
>
> **Verificação de desvio (primeiro)**: `git diff --stat dab677d..HEAD -- android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`.
> Mudanças vindas só do plano 054 não afetam estes arquivos. Qualquer outra mudança: compare os trechos abaixo pelo
> conteúdo (os números de linha podem ter mudado); divergência de conteúdo é condição de PARADA.

## Status

- **Prioridade**: P2
- **Esforço**: S
- **Risco**: LOW
- **Depende de**: `plans/054-zaplaud-roteiro-de-capturas-funcionando.md` (só para a verificação visual)
- **Categoria**: direction (modernização visual)
- **Planejado em**: commit `dab677d`, 2026-11-08

## Por que importa

O Zaplaud desenha botões com caracteres de texto (`‹`, `⋮`, `📎`, `➤`, `⚙`, `↓`, `×`, `ⓘ`). Na captura do emulador em
2026-11-08, o clipe aparece como emoji azul fora da paleta verde, a seta de voltar fica fina e desalinhada e cada
caractere tem tamanho e peso diferentes conforme a fonte do sistema. O projeto já depende do conjunto completo de
ícones Material e já os usa em `CastActions.kt`. Trocar os caracteres por ícones é a mudança mais barata que deixa o
app com aparência atual e consistente entre Android e iPad.

## Estado atual

- `android/shared/build.gradle.kts:41` já tem `implementation(compose.materialIconsExtended)`. Nenhuma dependência
  nova é necessária.
- Exemplo do padrão já usado no projeto, `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`:

  ```kotlin
  import androidx.compose.material.icons.filled.MoreVert
  import androidx.compose.material.icons.Icons
  ...
  IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = menuLabel) }
  ```

- Ocorrências a trocar (conteúdo atual, `ReadingScreen.kt` salvo indicação):

  | Local | Código atual | Função |
  |---|---|---|
  | `ReadingScreen.kt:173` | `leadingIcon = { Text("‹", style = MaterialTheme.typography.titleMedium) }` | "Voltar" dentro do menu em páginas |
  | `ReadingScreen.kt:176` | `trailingIcon = { Text("›", style = MaterialTheme.typography.titleMedium) }` | entrar num grupo do menu |
  | `ReadingScreen.kt:240` | `IconButton(onClick = controller::close, modifier = Modifier.semantics { contentDescription = backLabel }) { Text("‹", ...) }` | voltar às sessões |
  | `ReadingScreen.kt:247` | `IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) { Text("⋮", ...) }` | menu da lista |
  | `ReadingScreen.kt:399` | `BadgedBox(...) { Text("↓", style = MaterialTheme.typography.titleLarge) }` | ir para o fim |
  | `ReadingScreen.kt:435` | `trailingIcon = { Text("×", Modifier.semantics { contentDescription = removeLabel }) }` | remover anexo |
  | `ReadingScreen.kt:461` | `IconButton(..., modifier = Modifier.size(32.dp).semantics { contentDescription = attachLabel }) { Text("📎", ...) }` | anexar |
  | `ReadingScreen.kt:466-474` | `FilledIconButton(...) { val color = LocalContentColor.current; Canvas(Modifier.size(14.dp)) { drawRect(color) } }` | parar agente |
  | `ReadingScreen.kt:477` | `FilledIconButton(..., modifier = Modifier.testTag("message-send").semantics { contentDescription = sendLabel }) { Text("➤") }` | enviar |
  | `ReadingScreen.kt:588` | `Text("ⓘ", color = MaterialTheme.colorScheme.onSecondaryContainer)` | ícone do `ChatBanner` |
  | `ReadingScreen.kt:624` | `if (entry.kind == "tool") Text("⚙", color = MaterialTheme.colorScheme.primary)` | grupo de ferramentas |
  | `SettingsScreen.kt:128` | `IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = label }) { Text("‹", ...) }` | voltar das configurações |

- Teste que depende de um dos caracteres, `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt:247`:

  ```kotlin
  compose.onNodeWithText("ⓘ").assertDoesNotExist()
  ```

  Ele verifica que nenhum `ChatBanner` aparece. Depois da troca, essa asserção passaria sempre, sem testar nada.

## Comandos

| Objetivo | Comando (na raiz `habblaud/`) | Esperado |
|---|---|---|
| Host + lint + APK | `ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Instrumentados | `npm ci` (uma vez); com o emulador `habblaud034` aberto: `python3 scripts/android-instrumented-ci.py` | zero falhas |
| Capturas | `bash scripts/android-screens.sh` | exit 0 |
| Caracteres restantes | o comando do bloco abaixo | nenhuma linha |

```bash
grep -rn -E 'Text\("(‹|›|⋮|📎|➤|⚙|↓|×|ⓘ)"' android/shared/src/commonMain/kotlin
```

Abrir o emulador: `$HOME/Android/Sdk/emulator/emulator -avd habblaud034 -no-window -no-audio -no-snapshot-save -no-boot-anim &`.
O teste `imeDraftSurvivesRecreationRotationAndLargeFont` já travava antes deste plano (ver linha 042 do índice); se só
ele falhar, registre e siga.

## Escopo

**Pode modificar**: `ReadingScreen.kt`, `SettingsScreen.kt` (só a linha do voltar) e
`FixtureUiInstrumentedTest.kt` (só a asserção do `ⓘ`).

**Fora**: `CastActions.kt` (já usa ícones), `PermissionSheet` e demais telas, textos (`strings.xml`), cores,
tamanhos de botões, layout. Não mude `contentDescription` nem `testTag` existentes: testes e o roteiro de capturas
usam esses valores.

## Git

- Branch: `advisor/055-zaplaud-icones`.
- Mensagem: `feat(android): ícones Material no lugar de caracteres de texto`.
- Não faça push. Não instale no M35 sem autorização do dono.

## Passos

### Passo 1: trocar os caracteres por ícones

Em cada local da tabela, troque o `Text(...)`/`Canvas` por `Icon(...)`. Mantenha o `IconButton`, o `Modifier` e a
semântica existentes. Use `contentDescription = null` no `Icon` quando o botão já declara a descrição pela
semântica (evita leitura dupla no TalkBack). Mapeamento:

| Local | Ícone | Observação |
|---|---|---|
| `:173` | `Icons.AutoMirrored.Filled.ArrowBack` | `Modifier.size(20.dp)` |
| `:176` | `Icons.AutoMirrored.Filled.KeyboardArrowRight` | — |
| `:240` | `Icons.AutoMirrored.Filled.ArrowBack` | — |
| `:247` | `Icons.Default.MoreVert` | — |
| `:399` | `Icons.Default.KeyboardArrowDown` | dentro do `BadgedBox` |
| `:435` | `Icons.Default.Close` | `Modifier.size(18.dp)`, `contentDescription = removeLabel` (o roteiro toca "Remover" por essa descrição) |
| `:461` | `Icons.Default.AttachFile` | manter `Modifier.size(32.dp)` do botão |
| `:466-474` | `Icons.Default.Stop` | remover o `Canvas` e o `LocalContentColor` que só ele usava |
| `:477` | `Icons.AutoMirrored.Filled.Send` | — |
| `:588` | `Icons.Outlined.Info` | `Modifier.size(18.dp)`; a cor vem do `Surface` |
| `:624` | `Icons.Default.Build` | `Modifier.size(18.dp)`, `tint = MaterialTheme.colorScheme.primary` |
| `SettingsScreen.kt:128` | `Icons.AutoMirrored.Filled.ArrowBack` | — |

Imports: `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.automirrored.filled.*` nos nomes
usados, `androidx.compose.material.icons.filled.*` nos nomes usados, `androidx.compose.material.icons.outlined.Info`.
Importe nomes explícitos, como em `CastActions.kt`. Remova imports que ficarem sem uso (`Canvas` só se não houver
outro uso no arquivo; `SubagentBranch` e `ContextBar` ainda usam `Canvas`/`Box`).

**Verificar**: o grep "Caracteres restantes" da tabela não retorna linhas; o comando Host + lint + APK termina com
`BUILD SUCCESSFUL`.

### Passo 2: manter o teste do banner significativo

Em `ChatBanner` (`ReadingScreen.kt:585-592`), acrescente `Modifier.testTag("chat-banner")` ao `Row` interno (não ao
`Surface`, que já recebe o `modifier` do chamador com `testTag("message-status-...")`). Em
`FixtureUiInstrumentedTest.kt:247`, troque a asserção por
`compose.onAllNodesWithTag("chat-banner", useUnmergedTree = true).assertCountEquals(0)`.

**Verificar**: `grep -n '"ⓘ"' android/app/src/androidTest -r` não retorna linhas; instrumentados com zero falhas.

### Passo 3: conferir visualmente

Rode o roteiro de capturas. Abra `sessoes-abertas-*`, `conversa-fim-*`, `anexos-selecionados-*` e `omp-stop-*` nos
dois temas.

**Verificar**: exit 0; nas capturas, nenhum caractere de texto no lugar dos botões; o clipe e o enviar usam a cor do
tema.

### Passo 4: changelog

Em `CHANGELOG.md`, seção `## [Não lançado]`, subseção `### Alterado` (crie logo após `### Adicionado` se não
existir), acrescente: `- **Zaplaud:** botões e indicadores usam ícones Material no lugar de caracteres de texto.`

**Verificar**: `grep -n 'ícones Material' CHANGELOG.md` retorna 1 linha.

## Plano de testes

- Atualizar a asserção do banner (Passo 2). Não criar teste que verifique o tipo do ícone: é detalhe de
  implementação.
- Rodar a suíte instrumentada completa; as buscas por `contentDescription` (voltar às sessões, anexar, remover,
  enviar, ir para o fim) cobrem os botões trocados.

## Critérios de pronto

- [ ] Grep de caracteres restantes sem resultados.
- [ ] Host + lint + APK com `BUILD SUCCESSFUL`.
- [ ] Instrumentados sem falhas (exceto a falha conhecida e registrada, se ocorrer).
- [ ] Roteiro de capturas com exit 0.
- [ ] `git status --short` só com os arquivos do escopo, `CHANGELOG.md` e `plans/README.md`.
- [ ] Linha 055 de `plans/README.md` atualizada.

## Condições de PARADA

- Algum `Icons.*` da tabela não resolve no build (o conjunto estendido não está no classpath do `commonMain`). Não
  adicione dependência; relate.
- Algum teste instrumentado que buscava descrição ou tag passa a falhar e a correção exigiria mudar a descrição.
- O Passo 1 exige mudar layout ou tamanho de botão para caber.

## Notas de manutenção

- Novo botão na interface: use `Icon(Icons...)`, nunca caractere de texto.
- O plano 056 redesenha os grupos de ferramentas e pensamentos e reutiliza o ícone `Build`; o 061 move o anexo para
  dentro do campo.
