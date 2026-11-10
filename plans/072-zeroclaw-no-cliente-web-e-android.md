# Plan 072: Exibir e controlar sessões ZeroClaw no navegador e no Android

> **Executor instructions**: Execute este plano passo a passo. Rode cada comando de verificação e confirme o resultado esperado antes de avançar. Se ocorrer uma condição em “STOP conditions”, pare e relate; não improvise. Ao terminar, atualize a linha deste plano em `plans/README.md`, salvo se o responsável pelo índice disser que fará isso.
>
> Trabalhe em `/home/anderson/Desenvolvimento/habblaud`, branch-base `producao`. Nunca use `/home/anderson/apps/habblaud`: esse checkout é produção. Preserve alterações anteriores à sua execução. Os tenants estão pausados: não rode `omp-tenant sync`, não inicie pods, não reinicie serviços e não faça deploy.
>
> **Drift check (run first)**: `git diff --stat 694938f..HEAD -- client/src/ui/provider.ts client/src/ui/provider.test.ts client/src/ui/widgets.ts client/src/ui/rows.ts client/src/ui/model.ts client/src/ui/composer-model.ts client/src/ui/composer-model.test.ts client/src/ui/composer.ts client/src/ui/settings.ts client/src/ui/omp-bar.ts client/src/ui/omp-bar.test.ts client/src/ui/terminal.ts client/src/ui/drawer.ts client/src/ui/history.ts client/src/ui/history.test.ts client/src/ui/favorites.ts client/src/ui/favorites.test.ts client/src/ui/recent-sessions.ts client/src/ui/cast-screen.ts client/src/ui/styles.css shared/i18n.ts shared/i18n.test.ts shared/locales android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt android/shared/src/commonMain/composeResources/values/strings.xml android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/ZeroClawCapabilitiesTest.kt android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt server/test/android-fixture.ts CHANGELOG.md plans/README.md`
>
> Se algum caminho mudou, compare os trechos de “Current state” com o código vivo. Ordem fixa de merge: Habblaud **068 → 073 → 069 → 070 → 071 → 072**; omp-tenant **039 → 040 → 041 → 042 → 043**. São **ESPERADAS** as mudanças dos planos anteriores nessa ordem nos arquivos compartilhados `server/config.ts`, `server/index.ts`, `server/README.md`, `server/http/app.ts`, `CHANGELOG.md`, `shared/types.ts` e `README.md`, quando esses planos os modificarem. Isso também vale para seus ajustes necessários em caminhos deste Scope, como `client/src/ui/provider.ts`. Confirme autoria e contratos pelos diffs/testes; somente divergência não explicada por esses planos é STOP. A lista não amplia o Scope. Não trate arquivos não rastreados como descartáveis.

## Status

- **Priority**: P1.
- **Effort**: L.
- **Risk**: MED: um gate amplo demais pode liberar controles incompatíveis ou retomar conversa no runtime errado.
- **Depends on**: Habblaud plano 070 — **ZeroClaw: mensagens e controles** (`070-zeroclaw-mensagens-e-controles.md`); Habblaud plano 071 — **Elenco com ZeroClaw** (`071-elenco-com-zeroclaw.md`). Ambos exigem Habblaud plano 069 — **Fonte ZeroClaw** (`069-fonte-zeroclaw.md`). Para a capacidade `summaryHttp` usada nas configurações Android, exige também Habblaud plano 068 — **Resumo sem processo OMP** (`068-resumo-sem-processo-omp.md`).
- **Category**: migration.
- **Planned at**: Habblaud commit `694938f`, branch `producao`, 2026-11-09. Integração externa planejada contra omp-tenant commit `4c978c2`.

## Why this matters

O navegador e o Android conhecem controles e retomada do OMP, mas o ZeroClaw não implementa todos eles. Mostrar o seletor de raciocínio, o advisor ou o `/shake` produziria ações recusadas pelo servidor. Este plano mostra somente as ações disponíveis, mantém histórico e identidade do personagem e não inventa um percentual de contexto. O servidor continua responsável por autenticação, validação e entrega; nenhum cliente recebe o token do gateway.

## Current state

### Navegador e identidade

- `shared/types.ts:74-80` define o provider opcional; ausência significa Claude Code. O plano 069 acrescenta `zeroclaw`; não recrie esse contrato aqui.

```ts
// shared/types.ts:80
export type Provider = 'claude' | 'codex' | 'omp';
```

- `client/src/ui/provider.ts:12,63-65` contém o mapa de nomes e o gate do selo. O 069 acrescenta apenas a entrada de compilação `zeroclaw: unbrand('ZeroClaw')`; o resto da apresentação pertence a este plano.

```ts
// client/src/ui/provider.ts:12
export const PROVIDER_NAME: Record<Provider, string> = { claude: unbrand('Claude Code'), codex: unbrand('Codex'), omp: unbrand('omp') };
// client/src/ui/provider.ts:63-65
export function showsProviderTag(provider: Provider, accountName = ''): boolean {
  return provider !== 'claude' && !isNeutralBrand() && !accountName.toLowerCase().includes(PROVIDER_NAME[provider].toLowerCase());
}
```

- `widgets.ts:56-61` usa `PROVIDER_NAME`, mas sua dica só distingue OMP de Codex. `rows.ts:103` monta o texto acessível com os mesmos dois casos. `model.ts:326` também limita a busca de contas a esses nomes.
- `shared/i18n.ts:104-105` documenta o modo sem marca: `HABBLAUD_SEM_MARCA=1`; o navegador consulta `/api/health` antes de carregar o app. A expressão atual não conhece ZeroClaw:

```ts
// shared/i18n.ts:119
const BRAND = /(?<![\w./-])(?:Claude Code|Claude|Codex|oh-my-pi|OpenAI|Anthropic|omp)(?![\w/-]|[:.]\w)/g;
```

- `shared/i18n.ts:126-129` também remove nomes entre parênteses; inclua o nome novo nos dois caminhos, não só em `BRAND`. `shared/locales/en.ts:1-4` estabelece chave igual ao texto PT passado a `tr()`. Há catálogos `en.ts`, `es.ts` e `fr.ts`; PT é o texto-fonte.

### Mensagens e controles web

```ts
// client/src/ui/composer.ts:352
const deliverAs = provider === 'omp' ? this.ctx.prefs.ompDeliverAs : undefined;
// client/src/ui/omp-bar.ts:120
const omp = !!agent && agent.provider === 'omp' && agent.kind === 'main';
// client/src/ui/omp-bar.ts:125-126
const view = omp ? contextView(agent.context) : undefined;
const commands = omp && ready && !this.ctx.store.mock;
// client/src/ui/drawer.ts:898
const editable = provider === 'omp' && a.kind === 'main' && this.composerKind === 'ready' && !this.ctx.store.mock && !!this.ctx.agent(a.id);
```

- `omp-bar.ts:17-20,99-112` cria `/new`, `/shake`, advisor e `/resume`; `run()` nas linhas 180-182 envia `{stop:true}` ou `{command}` por `store.controlAgent`. O polling atual nas linhas 193-239 fala em extensão OMP.
- `composer-model.ts:39-43,100-109,138-139` escolhe dica de instalação, destino e confirmação por provider. ZeroClaw não pode cair nas mensagens de instalação do plugin Claude.
- `settings.ts:174-184,260` oferece os três modos `aside`, `followUp`, `steer` numa preferência global `ompDeliverAs`. Preserve a chave persistida; filtre a apresentação e normalize o valor apenas no envio ao ZeroClaw.
- `terminal.ts:898,908` e `aside.ts:158` limitam bate-papo paralelo a OMP. Mantenha esses gates estritos. `drawer.ts:898` já restringe seletores OMP; não troque todos os testes `omp` por um conjunto de providers.
- `cast-screen.ts:26-36,230-253` coleta modelos das sessões, permite texto livre sem lista e oferece contas OMP. Para ZeroClaw, use o catálogo de aliases do 071, inclusive com nenhum personagem aberto; não reutilize esse fallback OMP.

### Histórico e favoritos

```ts
// client/src/ui/history.ts:83-89
export function canResume(s: RecentSession, currentSessionId?: string): boolean {
  return s.provider === 'omp' && !s.open && s.sessionId !== currentSessionId;
}

/** Agentes que recebem o /resume: principais do omp, com a extensão conectada e livres (o omp só roda comandos assim). */
export function resumeTargets(agents: readonly AgentInfo[]): AgentInfo[] {
  return agents.filter((a) => a.provider === 'omp' && a.kind === 'main' && a.canMessage === true && (a.status === 'idle' || a.status === 'shell'));
}
```

- `history.ts:343-361` mistura destinos vivos OMP com personagens na reserva; a reserva já usa `store.startCast(..., 'resume', '', {session, account})`. ZeroClaw usa esse caminho do 071, nunca `terminal.resumeIn()`/controle `{resume}` do OMP.
- `favorites.ts:72` só grava provider Codex/OMP; `recent-sessions.ts:30` só restaura esses valores. Corrija ambos para não transformar um favorito ZeroClaw em Claude ao recarregar.

### Android e código compartilhado

Os arquivos de UI abaixo estão em `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/`.

```kotlin
// ReadingScreen.kt:204-205
val omp = selected?.key?.provider == "omp" && agentId != null && !selected.sub
val canControl = omp && selected!!.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
// data/MessageController.kt:213
if (key.provider == "omp") put("deliverAs", deliverAs)
// data/MessageController.kt:259
if (key.provider != "omp" || !visible || current.loading || key in controlling || !eligible(key, agentId) || current.control?.status in setOf("queued", "sent")) return
// CastActions.kt:134-135
val resume = selected != null && selected.agentId == null && selected.key.provider == "omp" &&
    selected.key.sessionId.matches(sessionIdPattern) && selected.project?.startsWith('/') == true
```

- `ReadingScreen.kt:212,220-232,235-241` contém modos, modelo, thinking e shake; `448` restringe aside a OMP; `508` restringe o gesto shake do anel. `520-535,580-589` restringe estado e botão STOP a OMP.
- `ReadingScreen.kt:578,945-950` mostra anel e barra quando tokens/janela existem, inclusive tokens zero. Não apresente esse zero como medição confiável ZeroClaw.
- `MessageController.kt:274-280` já envia `{stop:true, expectedSessionId}` e acompanha operação. `292-325` mantém `shake`, `setModel`, `setThinking` estritamente OMP. Não libere esse grupo inteiro para acrescentar `/new`.
- `data/SessionRepository.kt:7-10` vincula dono da sessão encerrada somente para OMP. `87,93` preserva provider recebido e aceita contexto com tokens `>= 0`.
- `SettingsScreen.kt:97-113` consulta `/api/cast/roles` em laço. `152-156` oferece aside globalmente. `160-185` exige papel `tiny` para resumo e desenha editor de papéis OMP. Para ZeroClaw, o editor não deve aparecer nem continuar consultando essa rota.
- `data/HabblaudApi.kt:72-92` permite GET `/api/*`, autentica por cookie e não repete POST ambíguo. Use a API existente, não um cliente HTTP direto para ZeroClaw.
- Padrões de teste: `client/src/ui/omp-bar.test.ts:4-24` testa decisões puras com Vitest; `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt:22-48,52-81` usa store em memória, MockWebServer e captura corpo exato. Preserve os testes de resposta perdida nas linhas 85-99.
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt:56-85` usa Compose e fixture loopback na porta 14748. Falta de fixture falha, não pula teste. `server/test/android-fixture.ts:35-40,128-135,181-188` monta contas, agentes, contexto OMP, streams e histórico; amplie somente os dados/caminhos fictícios necessários.

### iOS: nenhuma implementação específica

`android/shared/build.gradle.kts:20-24` gera o framework `ZaplaudShared` para iOS. `android/shared/src/iosMain/kotlin/br/com/xlinx/habblaud/MainViewController.kt:17,25-29` hospeda o mesmo `ZaplaudApp()`. A busca de gates OMP no código nativo `ios/` não encontrou implementação equivalente. Portanto **não modificar `ios/`, `iosMain` nem configuração Xcode**. Mudanças de `commonMain` chegam também ao app iOS; “sem mudança iOS” significa sem adaptação nativa separada, não isolamento de comportamento. Compilação/teste iOS em macOS não foi exercitado nesta elaboração.

### Contratos já decididos para as dependências

1. O 069 entrega provider `zeroclaw`, presença, histórico e espelho JSONL no diretório de dados Habblaud. Omite `AgentInfo.context` para ZeroClaw nesta migração. O gateway não expõe histórico de ferramentas no HTTP; o cliente lê o espelho pelo servidor, não o SQLite nem o gateway diretamente.
2. O 070 mantém `POST /api/messages` e `POST /api/messages/control`. Controles ZeroClaw permitidos: `{agentId, stop:true, expectedSessionId}` e `{agentId, command:'new', expectedSessionId}`. STOP usa abort HTTP no servidor; `/new` gera UUID novo, mantém personagem e confirma somente após `session_start`. Não existe frame WS `stop`. Abort de sessão já parada responde HTTP 200 `{"status":"no_active_response"}` sem frame `aborted`; o cliente acompanha o resultado do controle Habblaud, nunca espera esse frame diretamente. O servidor 070 consulta estado real via `GET /api/sessions/{id}/state` e `GET /api/sessions/running`; esses endpoints e credenciais permanecem exclusivamente no servidor.
3. Texto ocioso inicia turno. Ocupado: `steer` escreve no mesmo WS; `followUp` espera o fim e inicia o próximo turno. `aside`, thinking, troca de modelo e outros comandos são recusados. `delivered` confirma escrita local no WS, não aplicação pelo modelo; não repetir envio após desconexão ambígua.
4. O 071 abre/retoma pelo CastLauncher sem bridge/tmux. `GET /api/cast/models` retorna `{provider:'zeroclaw', models:[{alias,model}], defaultModel:alias}`. Cada opção **mostra `item.model` ao usuário e usa `item.alias` como valor**. Nunca mostre o identificador `m_<hash>` como nome do modelo. A tabela vem de `HABBLAUD_ZEROCLAW_AGENTS`, configurado pelo omp-tenant 040; não calcule hash nem monte lista no cliente. Modelo persistido do personagem é alias do agente, não um seletor de modelo da sessão viva. O servidor valida aliases e traduz valor legado exato; cliente não inventa correspondência por sufixo.
5. O 068 acrescenta `summaryHttp:boolean` a `GET /api/health`: true quando o resumidor HTTP está configurado e mensagens são autorizadas. Significa resumo independente do Tiny. Android usa `summaryHttp || hasTiny` para disponibilidade, mantendo o caminho OMP antigo. Campo ausente significa false.
6. Codex transportado pelo fio Anthropic foi observado com `input_tokens:0`/`last_input_tokens:0`. Isso não significa contexto vazio. Nesta entrega, oculte todos os indicadores de contexto ZeroClaw; não derive tokens de caracteres, custo ou acumulado.
7. Sessões OMP antigas ficam legíveis, mas nunca continuam no ZeroClaw. Nome e aparência podem permanecer; conversa nova tem UUID novo. Retomada ZeroClaw sempre conserva provider, conta, sessão, sala e associação de personagem validados pelo servidor.

## Commands you will need

Comandos web confirmados em `package.json:22-28`. Comando Android local atual confirmado em `.github/workflows/android.yml:68`; os planos 034/036 documentam instrumentação. Não rode instalação, Gradle nem build durante a elaboração deste plano; a execução dos gates abaixo é tarefa do executor.

| Finalidade | Comando, da raiz do Habblaud | Resultado esperado |
|---|---|---|
| Tipos | `npm run typecheck` | exit 0, sem erro |
| Regressões web | `npx vitest run client/src/ui shared/i18n.test.ts --maxWorkers=2` | todos passam, incluindo matriz ZeroClaw |
| Traduções | `npm run i18n:check -- --strict` | exit 0, sem chaves faltantes/excedentes |
| Suíte Node | `npm test -- --maxWorkers=2` | exit 0 |
| Build web/servidor | `npm run build` | exit 0; somente no checkout de desenvolvimento |
| Unidade Android atual | `android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest` | exit 0; XML com casos novos executados |
| Lint/APK debug | `android/gradlew -p android :app:lintDebug :app:assembleDebug` | exit 0; não requer chave release |
| Fixture de laboratório | `npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages` | JSON de prontidão em loopback; manter stdin aberto |
| Instrumentação, a partir de `android/` | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.fixture=true -Pandroid.testInstrumentationRunnerArguments.fixtureLocalPort="$PORTA_LOCAL"` | exit 0; XML com testes > 0, zero erros/falhas e cenários ZeroClaw |

`PORTA_LOCAL` é somente a porta numérica de `localUrl` emitida pela fixture; nunca escolha antecipadamente. Antes da instrumentação, execute `adb reverse tcp:14748 tcp:14748` e `adb reverse tcp:$PORTA_LOCAL tcp:$PORTA_LOCAL`. Ao terminar, remova somente esses dois encaminhamentos com `adb reverse --remove`; não use `--remove-all`. Encerre a fixture fechando stdin e aguarde o processo. Não publique JSON de prontidão, cookies, logs ou respostas integrais. Veja planos 034/036 para requisitos de emulador, autenticação fictícia e limpeza. Os comandos antigos `:app:testDebugUnitTest --tests '*MessageSendTest*'` do 034 não cobrem mais os testes movidos para `:shared:testAndroidHostTest`.

## Scope

**In scope** — únicos caminhos modificáveis; diretórios indicados apenas onde há vários catálogos de idioma:

- `client/src/ui/provider.ts`, `client/src/ui/provider.test.ts`, `client/src/ui/widgets.ts`, `client/src/ui/rows.ts`, `client/src/ui/model.ts`.
- `client/src/ui/composer-model.ts`, `client/src/ui/composer-model.test.ts`, `client/src/ui/composer.ts`, `client/src/ui/settings.ts`.
- `client/src/ui/omp-bar.ts`, `client/src/ui/omp-bar.test.ts`, `client/src/ui/terminal.ts`, `client/src/ui/drawer.ts`.
- `client/src/ui/history.ts`, `client/src/ui/history.test.ts`, `client/src/ui/favorites.ts`, `client/src/ui/favorites.test.ts`, `client/src/ui/recent-sessions.ts`, `client/src/ui/cast-screen.ts`, `client/src/ui/styles.css`.
- `shared/i18n.ts`, `shared/i18n.test.ts`, `shared/locales/` — somente `en.ts`, `es.ts`, `fr.ts`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ReadingScreen.kt`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/CastActions.kt`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/MessageController.kt`.
- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/SessionRepository.kt`.
- `android/shared/src/commonMain/composeResources/values/strings.xml`.
- `android/shared/src/commonTest/kotlin/br/com/xlinx/habblaud/ZeroClawCapabilitiesTest.kt` (criar).
- `android/shared/src/androidHostTest/kotlin/br/com/xlinx/habblaud/MessageSendTest.kt`.
- `android/app/src/androidTest/java/br/com/xlinx/habblaud/FixtureUiInstrumentedTest.kt`.
- `server/test/android-fixture.ts` — somente fixture e simulação local dos contratos já implementados.
- `CHANGELOG.md`, `plans/README.md` — registro da mudança e status deste plano; preserve entradas alheias.

**Out of scope**:

- Implementar provider/WS, parser JSONL, presença, reconexão e histórico no servidor: Habblaud 069 — **Fonte ZeroClaw**.
- Implementar filas, abort, `/new`, permissões e validação no servidor: Habblaud 070 — **ZeroClaw: mensagens e controles**.
- Alterar CastLauncher, contratos de aliases ou lançar processos: Habblaud 071 — **Elenco com ZeroClaw**.
- Implementar resumidor HTTP: Habblaud 068 — **Resumo sem processo OMP**. Só seu consumo nas configurações pertence aqui.
- Cartão de uso, `/v1/cota`, `omp usage`: Habblaud 073 — **Cartão de uso pela cota**.
- Proxy Messages: omp-tenant 039 — **Cota pelo fio Anthropic Messages**. Runtime/configuração: omp-tenant 040 — **Runtime ZeroClaw por tenant**. Deploy e prova com tenants: omp-tenant 043 — **Piloto e rollout ZeroClaw**.
- `ios/`, `android/shared/src/iosMain/`, toolchains, CI, assinatura release, novas dependências, novos ícones/logotipos, redesign de layout, migração de sessões antigas, acesso direto do navegador/Android ao gateway.

## Git workflow

- Crie `advisor/072-zeroclaw-cliente-web-android` a partir de `producao` já contendo as dependências mescladas.
- Antes de editar, registre `git status --short` e preserve os arquivos do usuário, inclusive planos não rastreados.
- Commits convencionais em pt-BR. Exemplo observado: `694938f feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo`.
- Sugestão de unidades: `feat(ui): adapta controles ao ZeroClaw`; `feat(android): reconhece sessões ZeroClaw`; `test(zeroclaw): cobre controles e retomada nos clientes`.
- Não faça push, merge, publicação, reinício ou instalação em dispositivo pessoal sem instrução do operador.

## Steps

### Step 1: Confirmar contratos e definir gates por recurso

1. Faça o drift check. Confirme os contratos 068–071 com seus testes e respostas de laboratório. A inclusão de `zeroclaw` no tipo/mapa pelo 069 é esperada; não a reverta.
2. Use decisões puras pequenas nos arquivos existentes `provider.ts` e `ReadingScreen.kt`, exportadas/internal somente quando precisarem de testes. Não crie camada de runtime, framework de capabilities ou flags enviadas por sessão.
3. Separe as decisões: provider conhecido; recebe mensagens; permite STOP/new; permite modos steer/followUp; permite ferramentas exclusivas OMP; permite contexto confiável. Preserve gates existentes de principal, sessão viva, mock, replay, acesso e conexão.
4. Use `GET /api/cast/models` para saber o runtime do elenco mesmo sem sessões. Não infira runtime por nome da conta, string de modelo, ausência de OMP ou ausência de personagens. Preserve estado desconhecido: não mostre controles exclusivos até carregar a resposta. Para páginas com origens múltiplas Android, armazene catálogo/capacidades por origem e limpe ao trocar servidor ou perder autenticação.

**Verify**: `npx vitest run client/src/ui/provider.test.ts client/src/ui/composer-model.test.ts --maxWorkers=2` → testes da matriz `claude/codex/omp/zeroclaw/ausente` passam; acesso negado, subagente, offline, mock/replay não ganham permissões.

### Step 2: Mostrar nome, selo e identidade sem vazar marca

1. Complete `provider.ts`, `widgets.ts`, `rows.ts` e a busca em `model.ts`: nome normal `ZeroClaw`, selo sem logo, dica de leitor de tela sem referência a Codex/OMP. Selo não duplica nome de conta que já contenha ZeroClaw. Modo sem marca mostra “Agente” e não desenha selo.
2. Complete `BRAND` e remoção entre parênteses em `shared/i18n.ts`, mantendo exclusões de caminhos, comandos e IDs. Teste `ZeroClaw`, `(ZeroClaw)`, `.zeroclaw`, `/opt/zeroclaw` e ID de modelo contendo nome semelhante. Não reescreva IDs técnicos, caminhos ou metadados persistidos.
3. Só acrescente estilo `data-provider='zeroclaw'` se o chip atual cair num estilo incorreto. Reutilize geometria, cores da conta e tokens CSS; nenhum logo externo nem nova paleta.
4. Em Android, não invente marca onde a UI já usa personagem. Nas informações técnicas existentes, mantenha provider exato para diagnóstico, sem tratá-lo como modelo ou selo OMP. Textos visíveis novos ficam em recursos Compose, não em XML gerado.

**Verify**: `npx vitest run client/src/ui/provider.test.ts shared/i18n.test.ts --maxWorkers=2` → ZeroClaw normal/sem marca e exclusões técnicas passam. `npm run i18n:check -- --strict` → exit 0 após atualizar `shared/locales/{en,es,fr}.ts`.

### Step 3: Adaptar mensagens e barra web sem liberar controles OMP

1. Em `composer-model.ts`, acrescente mensagens ZeroClaw para indisponibilidade, fila, entrega e timeout. Diga que entrega não confirma leitura/conclusão. Nenhum texto manda instalar plugin OMP/Claude ou iniciar bridge.
2. Em `settings.ts`, mostre somente `followUp` e `steer` quando o catálogo identificar ZeroClaw. Use título/dica neutros sobre o runtime atual. Em instalação OMP preserve `aside` e texto pertinente. Não renomeie `ompDeliverAs` nem migre preferências do usuário.
3. Em `composer.ts`, envie `deliverAs` também para ZeroClaw. Se a preferência persistida for `aside` ou desconhecida, use `followUp` somente nesse envio. Não sobrescreva o valor global, pois outro agente OMP pode usá-lo. Ocupado ou ocioso, permita escolher os dois modos; o servidor aplica a semântica do 070.
4. Em `OmpBar`, trate visibilidade de cada botão separadamente. ZeroClaw mostra STOP e `/new`; oculta `/shake`, advisor e `/resume` da barra OMP. Preserve todos no OMP. STOP fica disponível quando o turno está trabalhando ou aguardando decisão; não habilite em sessão encerrada. `/new` mantém confirmação, usa `{command:'new'}` e acompanha sucesso/erro pelo servidor. A barra não envia WS nem abort diretamente.
5. Confirme navegação para a nova sessão após `/new`: a confirmação do 070 ocorre depois do novo `session_start`. Reutilize acompanhamento por snapshot/sessionId da barra/terminal, sem depender de a sessão antiga ainda existir. Ao receber `session_changed`, não reenvie contra a sessão nova.
6. `terminal.ts` e `drawer.ts`: mantenha aside, modelo e thinking exclusivos do OMP. Não mostre advisor ou fallback de erro “o omp não rodou” para ZeroClaw. Não crie editor de papéis na web; se uma dependência introduzir um, aplique gate OMP pelo runtime do catálogo.
7. Oculte medidor de contexto ZeroClaw mesmo que uma fixture/estado antigo envie `{tokens:0, window:...}` ou tokens positivos sem confiabilidade. O OMP continua usando `contextView`. Não oculte toda a barra junto com o medidor: STOP e `/new` precisam continuar visíveis.

**Verify**: `npx vitest run client/src/ui/omp-bar.test.ts client/src/ui/composer-model.test.ts client/src/ui/provider.test.ts --maxWorkers=2` → testes demonstram ações permitidas/proibidas, normalização de aside e contexto oculto. `npm run typecheck` → exit 0.

### Step 4: Preservar histórico e usar aliases do elenco

1. Em `favorites.ts`/`recent-sessions.ts`, preserve `provider:'zeroclaw'` na serialização e leitura. Não altere a interpretação histórica de provider ausente. Teste ida e volta de conta/sessionId iguais em providers diferentes.
2. Em `history.ts`, separe retomada ZeroClaw da OMP. Histórico ZeroClaw mostra leitura do espelho e retomada somente em personagem/“Novo” elegível segundo o 071, conta e pasta compatíveis, sem sessão já aberta. Não ofereça agente OMP vivo como destino ZeroClaw nem ZeroClaw como destino de sessão OMP. Não generalize `resumeTargets` para incluir ZeroClaw.
3. Use `startCast(..., 'resume', ..., {session,account})` com o contrato do 071. Não converta essa ação em `{resume}` de `/api/messages/control`. Sessão aberta continua só abre seu terminal; não lança segunda sessão. Ausência de espelho/associação não deve aparecer como conversa vazia pronta para continuar: mostre erro do servidor.
4. Em `cast-screen.ts`, carregue o catálogo `/api/cast/models`. Para ZeroClaw, faça lista fechada de itens `{alias,model}`: texto visível = `item.model`, valor enviado/persistido = `item.alias`. A opção padrão usa `defaultModel` e seu nome de modelo correspondente; não exponha `m_<hash>` como rótulo. Isso funciona mesmo com zero sessões abertas. Sem texto livre, sem `agent.models` OMP e sem campo de conta OMP. Alias legado não reconhecido não vira escolha válida silenciosa: solicite escolha válida ou exiba o erro do servidor. Preserve nome, aparência, sala e instruções conforme o 071. Teste explicitamente que selecionar um nome legível envia seu alias exato.

**Verify**: `npx vitest run client/src/ui/history.test.ts client/src/ui/favorites.test.ts client/src/ui/provider.test.ts --maxWorkers=2` → round-trip ZeroClaw, não retomada cruzada, conversa aberta sem segundo lançamento e catálogo sem sessão passam. Inclua casos puros de seleção do catálogo em `provider.test.ts` se não houver teste estrutural próprio do editor.

### Step 5: Adaptar Android nos gates certos

1. Em `ReadingScreen.kt`, separe OMP-only de mensagens/STOP/new. Menu de envio ZeroClaw mostra apenas steer/followUp. Não mostre modelo OMP, thinking, shake, advisor ou aside. Feche submenu antigo se trocar de provider enquanto aberto. Gesto longo no contexto nunca envia shake ao ZeroClaw.
2. Em `MessageController.kt`, envie `deliverAs` para ZeroClaw com a mesma normalização por envio. Acrescente `/new` explicitamente pelo endpoint de controle do 070, com confirmação na UI e `expectedSessionId`. Preserve guardas de sessão, visibilidade, acesso, operações pendentes e política de falha ambígua. Não torne `ompControl` inteiro disponível para ZeroClaw.
3. Generalize somente STOP e apresentação de seu resultado/erro para ZeroClaw. Conteúdo/control status pertence à chave da sessão; ao `/new`, mantenha rascunho antigo separado e abra a sessão nova indicada pelo snapshot. Nada é reenviado ao reconectar.
4. Oculte o anel no campo de mensagem e a barra na ficha de informações para ZeroClaw, inclusive tokens zero com janela positiva. Preserve os dois no OMP. Modelo pode aparecer como informação, mas não como seletor vivo.
5. Em `CastActions.kt` e `data/SessionRepository.kt`, permita retomada de histórico ZeroClaw pelo 071. Preserve UUID, provider, conta, pasta e dono. Não aceite histórico OMP por runtime ZeroClaw. Leitura de sessão encerrada continua pelo histórico 069; retorno/reconexão não muda de sessão sozinho.
6. Em `SettingsScreen.kt`, consulte `/api/cast/models` por origem antes de mostrar editor/consultar papéis. No runtime ZeroClaw, não renderize seção de papéis e não faça polling/POST de `/api/cast/roles`. Preserve esses caminhos para OMP. Configurações globais de envio ZeroClaw não mostram aside; valores antigos são normalizados no envio, não apagados.
7. Leia `GET /api/health` via `api.request`. Faça `hasSummary = summaryHttp || hasTiny`, campo ausente=false. Remova a dependência visual de tiny somente no caso HTTP disponível; preserve preferência desligada do usuário e o caminho OMP. 401 cancela/limpa estado da origem; resposta ausente não habilita recurso por suposição.
8. Reutilize recursos `strings.xml`, sem editar `Res` gerado. Teste as decisões puras em `ZeroClawCapabilitiesTest.kt`; use os mesmos gates da produção, não uma cópia da lógica.

**Verify**: `android/gradlew -p android :shared:testAndroidHostTest :app:testDebugUnitTest` → casos ZeroClaw executados, XML sem falhas. `android/gradlew -p android :app:lintDebug :app:assembleDebug` → exit 0. Não alegue compilação iOS neste gate.

### Step 6: Provar os dois clientes em laboratório e registrar a mudança

1. Amplie `server/test/android-fixture.ts` com sessão/conta ZeroClaw fictícias, histórico e respostas/control statuses de teste. Reutilize contratos reais 069–071 com dependência local simulada; nunca adicione endpoint fictício ao servidor de produção. Preserve agentes e cenários Claude/OMP já existentes.
2. Em `FixtureUiInstrumentedTest.kt`, acrescente cenários completos: abrir ZeroClaw, escolher steer/followUp, enviar, STOP, `/new`, abrir histórico e retomar pelo elenco. Verifique pedidos/corpos e troca de sessão. Comprove ausência dos controles OMP e do contexto nas duas posições, inclusive após trocar de OMP para ZeroClaw. Verifique configurações sem papéis e resumo HTTP disponível sem tiny.
3. Rode a instrumentação com a fixture e os dois encaminhamentos da tabela. Confirme testes novos presentes no XML, `tests > 0`, `failures == 0`, `errors == 0`. Preserve a suíte completa, não filtre falhas preexistentes para obter verde.
4. Acrescente em `client/src/ui/omp-bar.test.ts` um grupo de integração **`ZeroClaw navegador real`**, executado quando `HABBLAUD_WEB_UI_TEST=1`. Use `playwright-core` já instalado, sem dependência nova. Nesse modo, ausência de `CHROMIUM_BIN` executável deve falhar, não pular testes. O teste inicia `npx tsx scripts/android-smoke.ts --port 0 --allow terminal,permissions,messages` como filho, com stdin aberto; lê a linha JSON de prontidão com timeout e retém somente URLs loopback em memória. Inicie Vite programaticamente com `createServer` e `vite.config.ts`, host `127.0.0.1`, porta `0` e proxy `/api` para `localUrl` da fixture, com `changeOrigin:true`. Esse padrão usa a raiz/aliases reais do cliente (`vite.config.ts:8-12`) e o proxy existente (`23-25`), sem editar sua configuração. A fixture ampliada neste passo fornece o backend ZeroClaw fictício. Abra Chromium headless pelo `executablePath:process.env.CHROMIUM_BIN`, sem desabilitar sandbox. Use testes com nomes identificáveis para: envio steer/followUp com `expectedSessionId`, STOP/new pelo controle, abort já parado concluído sem esperar frame, retomada pelo cast, selo normal/sem marca, modelo com rótulo legível e valor alias, comandos OMP-only ausentes da árvore acessível e contexto oculto. Intercepte/capture pedidos para assertions de corpo e espere a nova sessão por condição, não por pausa fixa. Repita os gates OMP no mesmo grupo. Finalize navegador, Vite e fixture em `finally`, inclusive falhas. Nunca imprima JSON de prontidão ou credenciais.
5. Com um Chromium de laboratório já instalado, defina `CHROMIUM_BIN` com seu caminho absoluto e execute `test -x "$CHROMIUM_BIN" && HABBLAUD_WEB_UI_TEST=1 npx vitest run client/src/ui/omp-bar.test.ts -t 'ZeroClaw navegador real' --maxWorkers=1 --reporter=verbose`. Resultado esperado: exit 0 e casos novos do grupo executados, nenhum pulado; preserve evidência do resumo de testes, sem payloads privados. Inspeção visual é complementar, nunca substitui esse gate.
6. Atualize “Não lançado” do `CHANGELOG.md` e a própria linha em `plans/README.md` ao concluir, salvo se o responsável pelo índice assumir explicitamente essa atualização. Documente que contexto ZeroClaw não é exibido por falta de leitura confiável, não por contexto zero. Não crie documentação de arquitetura nem atualize README de deploy.

**Verify**: `npm run typecheck && npm run i18n:check -- --strict && npm test -- --maxWorkers=2 && npm run build` → exit 0. Instrumentação pelo comando da tabela → exit 0 com cenários novos identificáveis. `git diff --name-only -- ios android/shared/src/iosMain` → vazio. `git status --short` → somente escopo deste plano além das mudanças anteriores preservadas.

## Test plan

- `provider.test.ts`: provider ausente continua Claude; nome/tag ZeroClaw; tag duplicada ocultada; identidade sem marca; gates por recurso para os quatro providers; catálogo disponível sem agentes; sem fallback OMP quando catálogo falha.
- `shared/i18n.test.ts`: ZeroClaw normal, neutralizado e entre parênteses; caminhos/comandos/IDs não são reescritos; idiomas existentes mantêm comportamento.
- `omp-bar.test.ts`: decisões efetivamente consumidas pelo render; ZeroClaw STOP/new visíveis e shake/advisor/resume ausentes; principal versus subagente; indisponível/mock; medidor oculto com `undefined`, zero e valor positivo não confiável; OMP preservado. Siga o teste puro atual em vez de instalar biblioteca DOM.
- `composer-model.test.ts`: mensagem ociosa; steer/followUp ocupados; aside persistido convertido por envio; OMP preserva aside; entregue não é concluído; desconexão/timeout não exige plugin nem causa replay.
- `history.test.ts` e `favorites.test.ts`: identidade completa no round-trip, abertura de histórico, retomada ZeroClaw pelo cast, recusa de cruzamento OMP/ZeroClaw, sessão aberta não ganha segunda chamada, favoritos fora do snapshot conservam provider.
- `ZeroClawCapabilitiesTest.kt`: matriz equivalente, contexto oculto nas duas posições, modos com preferência antiga, configurações sem papéis, resumo HTTP sem tiny, campo HTTP ausente com tiny legado.
- `MessageSendTest.kt`: corpo exato `steer`/`followUp`, aside normalizado, STOP e new com `expectedSessionId`; erro 409 preserva rascunho; perda de resposta não repete POST; comando OMP-only nunca sai para ZeroClaw; alternar A/B durante pedido não aplica status na sessão errada.
- `FixtureUiInstrumentedTest.kt`: presença/ausência real dos controles Compose e fluxo de retomada. Use padrão existente de login fictício, ciclo Activity e espera limitada; não use sleeps longos como condição de sucesso.
- Execute todos os comandos da tabela. Se faltar emulador/SDK, registre bloqueio; não declare entrega integral validada nem use teste unitário como substituto da instrumentação.

## Done criteria

Todos devem valer:

- [ ] `npm run typecheck`, `npm run i18n:check -- --strict`, `npm test -- --maxWorkers=2` e `npm run build` saem 0.
- [ ] `:shared:testAndroidHostTest`, `:app:testDebugUnitTest`, `:app:lintDebug` e `:app:assembleDebug` saem 0.
- [ ] Gate `HABBLAUD_WEB_UI_TEST=1` do Step 6 sai 0 e executa os casos `ZeroClaw navegador real`, sem skips; modelo legível envia alias, STOP já parado não espera frame e os gates acessíveis são comprovados.
- [ ] Instrumentação com fixture sai 0; XML contém testes novos e nenhuma falha/erro, com total maior que zero.
- [ ] Testes comprovam somente steer/followUp no ZeroClaw; aside antigo normaliza no envio sem apagar preferência OMP.
- [ ] Testes comprovam STOP/new pelo contrato 070, troca de UUID sem perda do personagem e sem replay de POST ambíguo.
- [ ] Testes comprovam ausência de shake/advisor/roles/aside/thinking/seletor OMP e de contexto não confiável para ZeroClaw.
- [ ] Histórico/favoritos preservam provider; retomada usa 069/071 e nunca continua sessão OMP no ZeroClaw.
- [ ] Lista de aliases funciona com nenhum personagem aberto; texto livre e contas OMP não aparecem no editor ZeroClaw.
- [ ] Resumo HTTP pode ser ligado sem tiny; OMP mantém disponibilidade anterior.
- [ ] `git diff --name-only -- ios android/shared/src/iosMain` não mostra arquivos.
- [ ] `CHANGELOG.md` atualizado; somente arquivos do Scope modificados, além de mudanças preexistentes preservadas.
- [ ] Status da própria linha em `plans/README.md` atualizado ao terminar, salvo delegação explícita ao responsável pelo índice; não duplicar índice.
- [ ] Nenhum token, chave, `.env`, gateway exposto, reinício de tenant ou alteração no checkout de produção.

## STOP conditions

Pare e relate, sem improvisar, se:

- Código divergir dos trechos sem explicação pelos planos anteriores na ordem fixa de merge; alterações esperadas 068, 073, 069, 070 e 071 ainda precisam ter autoria e contrato confirmados.
- 070 não aceitar stop/new com proteção de sessão, ou declarar sucesso de `/new` antes de `session_start` da nova sessão.
- 071 não entregar `/api/cast/models` com provider e aliases sem sessão aberta, ou exigir credencial ZeroClaw no cliente.
- 068 não entregar `summaryHttp` com a semântica descrita; não derive disponibilidade de resumo da existência de OMP nem habilite RPC sem tiny.
- Histórico não preservar provider/conta ou exigir usar controle OMP `{resume}` para retomar ZeroClaw.
- A implementação exigir alterar servidor de produção, tipo/protocolo além das dependências, código iOS nativo, toolchain ou arquivo fora do Scope.
- For exigido isolamento comportamental iOS: `commonMain` também é consumido pelo iOS; não copie a UI para fingir isolamento.
- Um gate falhar duas vezes após correção razoável, emulador/SDK não estiver disponível, ou instrumentação executar zero testes.
- A prova depender de despausar tenants, serviços de produção ou credenciais reais. A prova com tenants pertence exclusivamente ao omp-tenant 043, com autorização explícita.

## Maintenance notes

- Gates são por recurso, não por “provider diferente de Claude”. Revisão deve procurar ampliações indiscriminadas de `provider === 'omp'`.
- Contexto ZeroClaw só poderá voltar com contrato explícito de confiabilidade e testes para Codex pelo fio Anthropic. `tokens:0` sozinho não é esse contrato.
- Alias escolhe agente na abertura; não promete troca de modelo ao vivo. Adicionar outro runtime não deve reutilizar controles OMP por semelhança de UI.
- Rollback deste plano reverte somente cliente e textos; não apaga JSONL, favoritos, perfis ou histórico. O rollback do runtime pertence ao omp-tenant 043. Durante rollback de UI, preserve dados ZeroClaw no disco mesmo se o cliente antigo não os mostrar.
- Nenhuma mudança nativa iOS está prevista. O comportamento de UI comum será compartilhado; validação Apple exige macOS e deve ser declarada separadamente, nunca inferida do APK Android.
