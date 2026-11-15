# Plan 081: Zaplaud (Android e iOS, Kotlin): escolher um modelo `agy/…` passa o agente para o Antigravity

> **REMOVIDO em 2026-11-15 por ordem do dono. NÃO EXECUTE.** A integração com o Antigravity CLI (`agy`) foi
> apagada do código e da máquina por decisão do dono, para não expor a conta Google dele a risco.
> Este arquivo fica só como registro histórico. Nenhum agente deve reintroduzir, propor ou testar essa integração.

> **Executor instructions**: Siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado antes
> de avançar. Se ocorrer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha deste plano em `plans/README.md` (a menos que um revisor diga que ele cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat ba5a285..HEAD -- android/shared/src/commonMain`
> Se houver mudança, compare os trechos de "Current state" com o código; divergência é STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: LOW no código; a tela **não é testada por máquina** (ver abaixo)
- **Depends on**: plans/079-elenco-com-antigravity.md e plans/083-troca-de-ferramenta-com-contexto.md (mesclados)
- **Category**: direction
- **Planned at**: commit `ba5a285`, 2026-11-14 (reescrito no mesmo dia: saiu o "Novo no Antigravity"; o modelo decide)

## Why this matters

O dono usa o Habblaud pelo celular com o Zaplaud e pediu que usar o Antigravity CLI (`agy`) seja só **trocar o
modelo**. Com os planos 079 e 083 o servidor faz o trabalho: modelos `agy/…` rodam no Antigravity, a lista de
modelos de um agente aberto pelo elenco traz as duas ferramentas, e escolher um modelo da outra ferramenta fecha a
sessão, abre o mesmo personagem lá e anexa a conversa anterior à próxima mensagem. Falta o Zaplaud: hoje o menu de
modelo só existe para agentes do omp, agentes do Antigravity não têm STOP nem conversa nova, e a troca de ferramenta
aconteceria sem aviso. Este plano fecha isso no código Kotlin compartilhado, que serve Android e iOS.

## Regra do dono sobre testes (não negociável)

**Nenhum teste em emulador, simulador, aparelho, Xvfb ou máquina virtual.** O dono valida os clientes à mão. Este
plano termina em: compilar, testes de unidade no host (`testAndroidHostTest`, `testDebugUnitTest`), lint e APK de
debug. É proibido rodar `connectedDebugAndroidTest`, `:shared:jvmTest` (precisa de tela), `scripts/android-screens.sh`,
`scripts/android-instrumented-ci.py`, `adb`, ou instalar qualquer coisa num aparelho. O `AGENTS.md` do repositório
diz o mesmo: "Testes em emulador, simulador, Xvfb ou nos aparelhos do dono só com autorização expressa dele. Pedido
de mudança: implementar e compilar."

## Current state

### O contrato do servidor (planos 078, 079 e 083)

- Modelo cujo id começa com `agy/` roda no Antigravity (`agy/default` = padrão do `agy`). `google-antigravity/…` é
  modelo do omp e **não** é `agy/`.
- Agente do Antigravity no snapshot: `provider: "antigravity"`, `model: "agy/<valor>"`, `canMessage`, `cast` e
  `castOpened` quando aberto pelo elenco.
- Agente com `castOpened`: `models` traz os modelos das **duas** ferramentas (o servidor monta a lista).
- `POST /api/messages/control {agentId, model, expectedSessionId}`: mesma resposta de hoje. Modelo da mesma
  ferramenta: troca na conversa (vale para o Antigravity também). Modelo da outra ferramenta: **troca de
  ferramenta**; o agente atual sai e o mesmo personagem reaparece na outra, com outro id de sessão. Recusas voltam
  como erro com motivo em texto (agente trabalhando, destino indisponível, agente não aberto pelo elenco).
- Depois de uma troca, o agente novo tem `"handoff": true` até a primeira mensagem (a conversa anterior vai anexada
  a ela).
- Antigravity: mensagens sem `deliverAs` (o servidor recusa `steer` e `aside`); controles `stop: true` **ou**
  `command: "new"`; `thinking`, `shake` e conversa paralela não existem.
- `GET /api/cast/models` traz `"agyModels": ["agy/default", …]` quando o Antigravity está ligado.
- Retomar conversa: `POST /api/cast/<id>/start {"mode": "resume", "room", "session"}` como hoje; o servidor
  descobre sozinho se a conversa é do Antigravity.

### O Zaplaud hoje (`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`)

- `data/MessageController.kt:24-29`:
  ```kotlin
  internal fun supportsSessionControl(provider: String?) = provider == "omp" || provider == "zeroclaw"
  internal fun messageDelivery(provider: String?, preference: String): String? = when (provider) {
      "omp" -> preference
      "zeroclaw" -> if (preference == "steer") "steer" else "followUp"
      else -> null
  }
  ```
  e, nas linhas ~305-340: `fun setModel(agentId: String, model: String) = ompControl(agentId, "model") { put("model",
  model) }`. `ompControl` (linha ~313) começa com `if (key.provider != "omp" || !visible || !eligible(key, agentId))
  return`, manda o `POST /api/messages/control`, consulta `GET /api/messages/<id>` a cada 2 s por até 15 vezes
  enquanto `state.value.key == key`, e publica **só** `commandResult = CommandResult(kind, "pending" | "sent" |
  "failed" | "unknown")` (`data class CommandResult(val kind: String, val status: String)`, linha 32). O motivo do
  erro **não** é guardado. `controlError` é de outro caminho (STOP e conversa nova, `sessionControl`). O resultado
  aparece em `ReadingScreen.kt:670-674`, num `ChatBanner` com `command_pending`/`command_sent`/`command_failed`/
  `command_unknown`.
- `ReadingScreen.kt`:
  ```kotlin
  internal fun ompTools(provider: String?) = provider == "omp"                                    // ~104
  internal fun deliveryModes(provider: String?): List<String> = when (provider) { "omp" -> …; "zeroclaw" -> …; else -> emptyList() }
  val omp = selected?.key?.provider == "omp" && agentId != null && !selected.sub                    // ~262
  val sessionControls = supportsSessionControl(selected?.key?.provider) && agentId != null && selected?.sub == false
  …
  if (omp) {                                                                                        // ~312
      if (selected!!.models.isNotEmpty()) Group(Res.string.model_menu, (currentModel ?: selected.model)?.let(::modelLabel), "model", canControl)
      Group(Res.string.thinking_menu, …, "thinking", canControl)
      DropdownMenuItem(text = { Text(stringResource(Res.string.shake)) }, …)
      DropdownMenuItem(… Res.string.aside_menu …)
  }
  ```
  A página `"model"` do menu (linha ~286) lista `selected.models` agrupados por `providerLabel`.
  `ReadingScreen.kt:1095` mostra a ferramenta crua: `InfoLine(Res.string.info_provider, "${session.key.provider} ·
  ${session.key.account}")`.
- `data/Roles.kt:29-53`: `PROVIDER_LABELS` (`"google-antigravity" to "Google Antigravity"`, …) e `providerLabel(id)`
  (id desconhecido volta cru).
- `data/SessionRepository.kt:8-12`, dono de uma conversa encerrada: só para `key.provider == "omp" ||
  key.provider == "zeroclaw"`, comparando `provider`, `account` e `last` do personagem.
- `CastActions.kt:39-63`: `CastModelCatalog(runtime, models, defaultModel)`, `parseCastModelCatalog`, e
  ```kotlin
  internal fun canResumeCast(selected: SessionItem?, runtime: String?): Boolean =
      selected != null && selected.agentId == null && supportsSessionControl(selected.key.provider) &&
          selected.key.provider == runtime && selected.key.sessionId.matches(sessionIdPattern) && selected.project?.startsWith('/') == true
  ```
- `data/ApiModels.kt:37`: `data class SessionItem(…)`, montado em `data/SessionRepository.kt:94` a partir do
  snapshot (campos como `castOpened`, `models`, `model`).
- Textos: `android/shared/src/commonMain/composeResources/values/strings.xml` (o desktop Rust gera os dele a partir
  desse arquivo: nomes de chave novos precisam ser estáveis).
- Testes de unidade no host: `android/shared/src/commonTest/…/*Test.kt` e `android/shared/src/androidHostTest/…`.
  Instrumentados (não rodar): `androidDeviceTest`. O desktop Compose (`android/desktop`, `jvmMain`) está obsoleto.
- `docs/zaplaud-paridade.md:35` tem a linha "Antigravity pelo ACP | parcial | parcial | …".

## Commands you will need

Todos a partir de `android/`:

| Purpose | Command | Expected on success |
|---|---|---|
| Testes no host, lint e APK | `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `BUILD SUCCESSFUL` |
| Só os testes do módulo compartilhado | `./gradlew :shared:testAndroidHostTest` | `BUILD SUCCESSFUL` |

iOS só compila num Mac: não é verificado aqui.

## Scope

**Editar** (em `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`): `data/MessageController.kt`,
`data/SessionRepository.kt`, `data/ApiModels.kt` (só o campo `handoff`), `data/Roles.kt`, `ReadingScreen.kt`,
`CastActions.kt`; `composeResources/values/strings.xml` (e os `values-*` irmãos, se existirem); testes novos ou
acrescidos em `commonTest`/`androidHostTest`; `docs/zaplaud-paridade.md`; `CHANGELOG.md` se o repositório registra
mudanças do Zaplaud ali (confira as entradas anteriores).

**Out of scope** (NÃO toque):
- `server/**`, `client/**`, `shared/**` (TypeScript), `zaplaud-rs/**` (plano 082).
- `android/desktop/**`, `android/shared/src/jvmMain/**`, `android/shared/src/jvmTest/**` (obsoletos).
- `android/shared/src/androidDeviceTest/**`: nenhum teste instrumentado novo nem alterado.
- Criar/editar personagem no Zaplaud; `SettingsScreen.kt` (papéis do omp); um botão "Novo no Antigravity".
- Qualquer comando com emulador, aparelho, `adb`, Xvfb, simulador, ou assinatura/instalação de release.

## Git workflow

- Worktree: `git worktree add /tmp/habblaud-081 -b advisor/081-antigravity-zaplaud-kotlin producao`.
- Um commit por passo, estilo `feat(zaplaud): modelos agy trocam o agente para o Antigravity`.
- Não faça push nem abra PR.

## Steps

### Step 1: regras puras

- Novo, junto das regras existentes: `internal fun isAgyModel(model: String?) = model != null &&
  model.startsWith("agy/") && model.length > 4`; `internal fun crossesTool(provider: String?, model: String) =
  (provider == "omp" || provider == "antigravity") && isAgyModel(model) != (provider == "antigravity")`;
  `internal fun modelChoice(provider: String?) = provider == "omp" || provider == "antigravity"`.
- `supportsSessionControl`: aceita também `"antigravity"`. `messageDelivery("antigravity", …)` → `null` explícito.
  `deliveryModes("antigravity")` continua vazio; `ompTools` não muda.
- `PROVIDER_LABELS`: acrescente `"agy" to "Antigravity"` (é o prefixo que agrupa os modelos `agy/…` no menu).
  `agy/default` aparece com o texto de uma chave nova, `model_agy_default` = "Padrão do Antigravity".
- Testes no host (`AntigravityRulesTest.kt` em `commonTest`, ou acrescente ao arquivo que já testa essas funções):
  `isAgyModel` (`agy/x` sim; `google-antigravity/x`, `agy/`, `null` não); `crossesTool` nas quatro combinações e
  falso para `claude`/`codex`/`zeroclaw`; controle de sessão; entrega nula; modos vazios; `providerLabel("agy")`.

**Verify**: `./gradlew :shared:testAndroidHostTest` → `BUILD SUCCESSFUL`.

### Step 2: menu do agente

Em `ReadingScreen.kt`:

- O item **Modelo** (`Group(Res.string.model_menu, …)`) sai de dentro do `if (omp)` e passa a depender de
  `modelChoice(provider) && agentId != null && !selected.sub && selected.models.isNotEmpty()`. Raciocínio, shake e
  conversa paralela continuam dentro do `if (omp)`.
- Na página `"model"`, ao tocar num modelo com `crossesTool(provider, modelo)`: em vez de enviar na hora, abre um
  diálogo de confirmação (use o padrão do `confirmNew` que já existe para "conversa nova") com o texto da chave
  `switch_tool_confirm`: "Trocar para %1$s muda de ferramenta: a conversa atual fecha e abre outra. A conversa
  anterior vai anexada à sua próxima mensagem." e os botões de confirmar/cancelar já usados. Confirmado: o mesmo
  `controller.messages.setModel(agentId, modelo)`. Modelo da mesma ferramenta: envia direto, como hoje.
- Em `MessageController.kt`, `ompControl`: a trava `key.provider != "omp"` passa a ser "`omp` para qualquer `kind`;
  `antigravity` só para `kind == "model"`" (use `modelChoice`). O nome `ompControl` pode ficar.
- Motivo da recusa: `CommandResult` ganha `val reason: String? = null`. Quando o resultado é `failed`, `reason`
  recebe o texto do servidor: a mensagem da `ApiException` num erro 4xx, ou o campo `error` da mensagem consultada
  em `GET /api/messages/<id>`, se vier. Em `ReadingScreen.kt:670-674`, com `reason` presente o `ChatBanner` mostra
  `command_failed` seguido do motivo; sem ele, fica como hoje.
- Numa troca de ferramenta a sessão atual **some** enquanto o pedido é acompanhado (`state.value.key == key` deixa
  de valer): isso é sucesso, não erro. Não mostre "desconhecido" por causa disso; o laço de consulta já termina
  sozinho e o resultado não é publicado para outra sessão.
- Testes no host: `ompControl` (pela função pura que decide a trava) aceita `model` para `antigravity` e recusa
  `thinking`/`shake`; `CommandResult` com `reason`.
- Com `selected.handoff == true`, uma linha discreta acima da caixa de mensagem com a chave `handoff_pending`: "A
  conversa anterior vai anexada à sua próxima mensagem." Leia o campo em `SessionRepository.kt`/`ApiModels.kt`
  (`handoff: Boolean = false`, do `"handoff"` do snapshot).
- `ReadingScreen.kt:1095`: `antigravity` aparece como "Antigravity" (chave `provider_antigravity`), por uma função
  pura `providerDisplayName(provider)` que mantém o valor cru para os demais.
- Para o Antigravity aparecem STOP e conversa nova (o mesmo conjunto do ZeroClaw) e **nenhum** item só do omp.
- Testes no host das partes puras novas (`providerDisplayName`; leitura de `handoff` no parser do snapshot).

**Verify**: `./gradlew :shared:testAndroidHostTest` → `BUILD SUCCESSFUL`.

### Step 3: dono de conversa encerrada e retomar

- `castOwner` (`SessionRepository.kt`): aceita `key.provider == "antigravity"`; para ele não compara `account` (a
  conta do Antigravity é uma só) e exige o `last` com a sessão, como hoje.
- `CastModelCatalog` ganha `val agy: Boolean` (verdadeiro quando `agyModels` veio não vazio em `/api/cast/models`).
  `canResumeCast`: uma conversa do Antigravity é retomável quando `catalog.agy`, independente do runtime da
  instalação; para omp e ZeroClaw o resultado não muda (mude a assinatura para receber o catálogo). O corpo da
  retomada não muda.
- Testes no host: dono de conversa do Antigravity; retomável com `agy` e não retomável sem; casos antigos sem
  edição.

**Verify**: `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` →
`BUILD SUCCESSFUL`.

### Step 4: paridade e registro

`docs/zaplaud-paridade.md`: atualize a linha do Antigravity com o que o Kotlin passou a fazer, o que falta no Rust
(plano 082) e no iOS ("não compilado: só num Mac"). Diga, literalmente, que não houve teste em aparelho nem emulador
e que a validação é manual, do dono.

**Verify**: `git status --short` limpo depois do commit.

## Test plan

Só testes de unidade no host, das funções puras. Nada de interface. Roteiro para o dono validar à mão no aparelho
(escreva-o no corpo do último commit): num agente do omp aberto pelo elenco, menu → Modelo → um `agy/…` → aviso →
confirmar → o personagem reabre como Antigravity, com a linha do contexto pendente; mandar "do que falávamos?";
STOP e conversa nova no agente do Antigravity; trocar de modelo dentro do Antigravity (sem aviso, conversa mantida);
voltar para um modelo do omp; chamar um personagem cujo modelo é `agy/…`; retomar uma conversa encerrada do
Antigravity; um agente do omp aberto no terminal não oferece modelos `agy/…`.

## Done criteria

- [ ] `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` →
      `BUILD SUCCESSFUL`
- [ ] `git diff --stat producao -- android/desktop android/shared/src/jvmMain android/shared/src/androidDeviceTest server client zaplaud-rs` vazio
- [ ] Nenhum teste existente editado: `git diff producao -- 'android/**/*Test.kt' | grep -c '^-[^-]'` → `0`
- [ ] `docs/zaplaud-paridade.md` atualizado; `git status --short` limpo

## STOP conditions

- Um trecho de "Current state" não bate com o código.
- O build exigir emulador, aparelho, tela ou Mac para passar.
- Tirar o item Modelo do `if (omp)` fizer aparecer, para o Antigravity, raciocínio, shake ou conversa paralela.
- O Gradle não rodar neste ambiente (SDK do Android ausente): relate; não instale SDK nem imagens de sistema.

## Maintenance notes

- As chaves novas de `strings.xml` (`provider_antigravity`, `model_agy_default`, `switch_tool_confirm`,
  `handoff_pending`) são lidas pelo gerador do desktop Rust: o plano 082 as usa com esses nomes.
- A lista de modelos vem pronta do servidor; o cliente só decide **avisar** antes de cruzar de ferramenta.
- Quem revisar olha: que o aviso aparece só quando cruza de ferramenta; que cancelar não envia nada; que nenhum
  arquivo de teste instrumentado ou do desktop Compose foi tocado.
