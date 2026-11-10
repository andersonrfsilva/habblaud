# Plan 073: Ler a cota do tenant por HTTP sem depender do processo OMP

> **Executor instructions**: Siga este plano passo a passo. Execute cada comando
> de verificação e confirme o resultado esperado antes de seguir. Se ocorrer
> qualquer item de "STOP conditions", pare e reporte; não improvise.
> Ao terminar, atualize a própria linha deste plano em `plans/README.md`.
>
> **Drift check (run first)**: em `/home/anderson/Desenvolvimento/habblaud`, execute
> `git diff --stat 694938f..HEAD -- server/config.ts server/index.ts server/accounts/service.ts server/accounts/accounts.test.ts server/accounts/quota.ts server/accounts/quota.test.ts server/accounts/fixtures/quota.json server/sources/omp/config.test.ts server/README.md CHANGELOG.md plans/README.md`.
> Ordem de merge fixa no Habblaud: **068 → 073 → 069 → 070 → 071 → 072**.
> São ESPERADAS as mudanças do Habblaud **068 — Resumo sem processo OMP**
> em `server/config.ts`, `server/index.ts`, `server/README.md`, `CHANGELOG.md`
> e a atualização correspondente do índice. Preserve essas mudanças ao integrar.
> Compare os trechos de "Current state" com o código vivo; só divergências
> funcionais não provenientes do 068 são STOP. Examine também alterações locais com
> `git diff -- server/config.ts server/index.ts server/accounts/service.ts server/accounts/accounts.test.ts server/accounts/quota.ts server/accounts/quota.test.ts server/accounts/fixtures/quota.json server/sources/omp/config.test.ts server/README.md CHANGELOG.md plans/README.md`.
> Não sobrescreva alterações do usuário, nem arquivos não rastreados preexistentes.

## Status

- **Priority**: P1.
- **Effort**: M.
- **Risk**: MED: o servidor passa a ler uma credencial de tenant e consultar uma URL autenticada.
- **Depends on**: nenhuma dependência funcional; por ordem de integração, execute após Habblaud **068 — Resumo sem processo OMP** e antes de **069 — Fonte ZeroClaw**. Não exige alteração do proxy.
- **Category**: migration.
- **Planned at**: Habblaud commit `694938f`, branch `producao`, 2026-11-09. Contrato externo conferido no omp-tenant commit `4c978c2`.

## Why this matters

Hoje o cartão de cota depende da fonte OMP, de uma sessão principal aberta e de um executável que imita `omp usage`. O tenant ZeroClaw não terá esse processo nem precisará manter `models.yml` apenas para consultar percentuais. A consulta HTTP deve funcionar no Docker, com `HABBLAUD_OMP=0` e sem sessão trabalhando. Sem configuração nova, instalações atuais continuam usando exatamente o caminho de `omp usage` existente.

## Current state

Todos os caminhos relativos abaixo pertencem a `/home/anderson/Desenvolvimento/habblaud`. Os dois scripts externos são somente referências de contrato; não serão editados.

### O proxy já responde no formato necessário

`/home/anderson/Desenvolvimento/omp-tenant/bin/omp-tenant-cota:84-92`:

```python
def relatorio_cota(estado: list[dict], provedores: list[str], now_ms: int) -> dict:
    """JSON do Habblaud do usuário: só fração usada e renovação; nenhum valor em US$ sai daqui (o usuário vê só %)."""
    limites = [{"label": e["rotulo"], "scope": {"shared": True},
                "window": {"durationMs": e["dur"] * 1000, **({"resetsAt": int(e["renova"] * 1000)} if e["renova"] else {})},
                "amount": {"usedFraction": min(1.0, e["gasto"] / e["limite"])},
                "status": "exhausted" if e["esgotada"] else "ok"} for e in estado]
    # "quota": o Habblaud mostra o cartão enxuto de cota (só as janelas) em vez do de uso do provedor.
    return {"generatedAt": now_ms, "quota": True,
            "reports": [{"provider": p, "fetchedAt": now_ms, "limits": limites} for p in provedores]}
```

- `omp-tenant-cota:124-132` autentica `Authorization: Bearer` pelo hash do token em `clients.json`.
- `omp-tenant-cota:149-157` atende `GET /v1/cota`. Extrai provedores dos modelos liberados e repete **os mesmos limites do tenant** em cada report.
- `durationMs`: 18.000.000 para 5 h; 604.800.000 para 7 d. `fetchedAt`, `generatedAt` e `resetsAt` são epoch em milissegundos.
- Não há saldo em dólares nesse contrato. Não consulte o banco de consumo, o painel ou `/v1/models` para montar o cartão.

### O adaptador atual só faz um fetch, mas depende do agent dir

`/home/anderson/Desenvolvimento/omp-tenant/bin/omp-usage-cota:8-14`:

```js
try {
  key = /apiKey: (\S+)/.exec(readFileSync(`${process.env.PI_CODING_AGENT_DIR}/models.yml`, 'utf8'))?.[1];
} catch { fail('models.yml ilegível'); }
if (!key) fail('sem apiKey no models.yml');
fetch('http://127.0.0.1:4000/v1/cota', { headers: { Authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(10_000) })
  .then(async (r) => (r.ok ? process.stdout.write(await r.text()) : fail(`HTTP ${r.status}`)))
  .catch((e) => fail(e.message));
```

O novo caminho usa diretamente o arquivo de token montado pelo operador. Não leia nem crie um `models.yml` do OMP.

### A coleta atual está acoplada à fonte OMP

`server/config.ts:269-271`:

```ts
    omp,
    ompDirs: omp ? discoverOmpDirs(env, home) : [],
    ompUsage: omp && !inDocker && (!env.HABBLAUD_OMP_USAGE?.trim() || isTruthy(env.HABBLAUD_OMP_USAGE)),
```

`server/index.ts:186-189`:

```ts
const omp = config.ompDirs.length
  ? new OmpSource({ accounts, office, dirs: config.ompDirs, env: process.env, home: config.home, usage: config.ompUsage, roomByProject: () => ompSettings.roomByProject })
  : undefined;
if (omp) agents.add(omp);
```

`server/sources/omp/source.ts:318-326`:

```ts
      const provider = ompProviderOf(main?.state.model);
      if (!provider) continue;
      const due =
        acc.usageAt === 0 || provider !== acc.usageProvider || now - acc.usageAt >= (acc.usageFailed ? USAGE_RETRY_MS : USAGE_EVERY_MS);
      if (!due) continue;
      acc.usageBusy = true;
      acc.usageAt = now;
      acc.usageProvider = provider;
      (this.opts.runUsage ?? runOmpUsage)(bin, acc.dir, env)
```

A fonte só chama esse caminho com `opts.usage`, conforme `source.ts:300`. Não mude o coletor da fonte: desative sua opção quando a cota HTTP estiver configurada.

### O parser já entende a marca de cota e a exaustão

`server/sources/omp/usage.ts:54-56`:

```ts
  const fetchedAt = Math.min(now, toEpochMs(report.fetchedAt) ?? now);
  const usage: AccountUsage = { source: 'omp', ompProvider: provider, fetchedAt };
  if (parsed.quota) usage.quota = true;
```

`usage.ts:60-70` aceita somente limites compartilhados sem `tier`, reconhece 5 h e 7 d, converte fração em percentual e limita a 0–100. `usage.ts:72-76` só transforma exaustão em `noQuota` quando **não** é cota de instalação. Reutilize `parseOmpUsage`; ele é puro e não inicia OMP. Não transporte `ompProviders`, `metadata`, notas ou dados de credenciais para o novo resultado.

### Contas e frescor já têm um ponto comum

`server/accounts/service.ts:156-160`:

```ts
  setUsage(accountId: string, usage: AccountUsage): boolean {
    const changed = this.usage.set(accountId, usage);
    const viewChanged = this.usageViewChanged();
    if (changed || viewChanged) this.opts.onChange();
    return changed;
```

- `service.ts:171-177`, `222-224` e `227-243`: assinatura de mudanças, `usageView()` e serialização de contas consultam atualmente o `UsageStore` por id de conta.
- `server/accounts/usage.ts:79-85` omite janelas cujo reinício já passou. `usage.ts:117-121` marca dados como antigos após 30 min (`STALE_AFTER_MS`). Não zere percentuais quando o proxy falhar.
- `server/index.ts:126-132` constrói `AccountsService`; `386-388` inicia fontes/contas/hub; `651-656` encerra fontes e contas. O coletor de cota entra nesse ciclo, não dentro de `OmpSource`.
- `shared/types.ts:394,409-413`: `AccountUsage.source` ainda tem o literal histórico `'omp'`; `quota: true` já diferencia cota de instalação. Preserve esse fio por compatibilidade neste plano: `'omp'` no JSON **não** significa executar um binário.
- `client/src/ui/usage.ts:253-255` já prioriza a legenda de cota sem consultar o provider:

```ts
export function usageSubtitle(a: Pick<AccountInfo, 'email' | 'plan' | 'configDir' | 'provider' | 'usage'>): string {
  if (a.usage?.quota) return tr('cota de uso');
```

### Convenções e limites de integração

- Testes são Vitest em Node (`vitest.config.ts:3-6`). `server/sources/omp/usage.test.ts:1-7` lê fixture JSON com `readFileSync(join(__dirname, 'fixtures', ...), 'utf8')`.
- `server/accounts/accounts.test.ts:216-281` exercita contas externas, relógio injetado, callbacks de mudança, remoção de conta e envelhecimento. Use esse padrão.
- `server/sources/omp/config.test.ts:58-65` cobre os desligamentos por `HABBLAUD_OMP_USAGE`, `HABBLAUD_OMP` e Docker; mantenha essas expectativas quando a nova URL estiver ausente.
- O plano Habblaud **069 — Fonte ZeroClaw** registra uma conta `zeroclaw` em `AccountsService` mesmo sem sessões, com raiz `<dataDir>/zeroclaw`. Este plano não precisa esperar o 069: teste com conta externa Codex já suportada. Depois do 069, a mesma sobreposição vale para sua conta, sem importá-lo.
- O deploy real dos tenants está pausado. Não rode `omp-tenant sync`, não inicie/reinicie pods e não altere `/home/anderson/apps/habblaud`.

## Commands you will need

Execute em `/home/anderson/Desenvolvimento/habblaud`. Comandos conferidos em `package.json:22-27` e na configuração Vitest; não executados durante a redação.

| Purpose | Command | Expected on success |
| --- | --- | --- |
| Conferir dependências locais | `test -x node_modules/.bin/vitest && test -x node_modules/.bin/tsc` | exit 0; se faltar, STOP, sem instalar/atualizar dependências por conta própria |
| Testes do novo coletor e configuração | `npm test -- server/accounts/quota.test.ts server/sources/omp/config.test.ts` | exit 0, todos os testes passam |
| Testes da sobreposição | `npm test -- server/accounts/accounts.test.ts` | exit 0, todos passam |
| Regressão dos leitores/cartão existentes | `npm test -- server/sources/omp/usage.test.ts server/sources/omp/source.test.ts client/src/ui/usage-source.test.ts` | exit 0, todos passam |
| Tipos | `npm run typecheck` | exit 0, sem erros nos dois projetos TypeScript |
| Suíte completa | `npm test` | exit 0, nenhuma falha |
| Revisão do escopo | `git diff --check && git status --short` | sem erros de whitespace; alterações próprias somente no escopo |

Não há script `lint` no `package.json` lido. Não invente esse gate. Não execute build, publicação ou serviços para provar a consulta.

## Scope

**In scope** (únicos arquivos que o executor pode modificar):

- `server/config.ts`.
- `server/index.ts`.
- `server/accounts/service.ts`.
- `server/accounts/accounts.test.ts`.
- `server/accounts/quota.ts` (criar).
- `server/accounts/quota.test.ts` (criar; inclui validação da configuração).
- `server/accounts/fixtures/quota.json` (criar; dados inteiramente sintéticos).
- `server/sources/omp/config.test.ts`.
- `server/README.md`.
- `CHANGELOG.md`.
- `plans/README.md` (somente a própria linha de status do plano 073).

**Out of scope**:

- Proxy, wrapper, quadlets, arquivos de token e `tenant.json` no omp-tenant. A configuração/montagem fica no omp-tenant **040 — Runtime ZeroClaw por tenant**. Não remova `omp-usage-cota`: instalações OMP e rollback ainda o usam.
- Fonte/gateway/espelho: Habblaud **069 — Fonte ZeroClaw**. Entrega e controles: Habblaud **070 — ZeroClaw: mensagens e controles**. Elenco: Habblaud **071 — Elenco com ZeroClaw**.
- Rótulos, controles e barra de contexto web/Android: Habblaud **072 — ZeroClaw no cliente web e Android**. Não adicione source/provider novo nem altere telas neste plano.
- Resumidor: Habblaud **068 — Resumo sem processo OMP**. Imagem: omp-tenant **042 — Imagem por script nos tenants**.
- Implantação/piloto: omp-tenant **043 — Piloto e rollout ZeroClaw**; só esse plano pode operar pods, com autorização explícita do operador.
- Alterações no cálculo monetário, enforcement e cobrança: omp-tenant **039 — Cota no fio Anthropic Messages**. `GET /v1/cota` já existe, portanto 073 não depende do 039.
- Fontes Claude/Codex, parser OMP e tipos públicos: preservados. Não introduza conta OMP fictícia, endpoint para configurar URL pelo navegador, persistência de token, dependência nova ou opção de polling.
- Todos os demais planos e arquivos não rastreados do usuário. No índice, altere somente a linha do 073.

## Git workflow

- Trabalhe em `/home/anderson/Desenvolvimento/habblaud`, a partir de `producao`; nunca no checkout de produção `~/apps/habblaud`.
- Respeite a ordem Habblaud **068 → 073 → 069 → 070 → 071 → 072**. No omp-tenant, a ordem do lote é **039 → 040 → 041 → 042 → 043**; este plano não altera esse repositório.
- Antes de criar branch, registre `git status --short` para reconhecer mudanças preexistentes. Não as inclua no commit.
- Branch: `advisor/073-cartao-de-uso-pela-cota`.
- Commits convencionais, pt-BR, como `694938f feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo`.
- Sugestão para a unidade final: `feat(cota): consultar cota do tenant por HTTP sem omp`.
- Não faça push, PR, release ou deploy sem instrução do operador.

## Steps

### Step 1: Definir a configuração e o coletor HTTP testável

Em `server/config.ts`, acrescente `quota?: { url: string; tokenFile: string }` a `ServerConfig`. Variáveis de ambiente:

- `HABBLAUD_QUOTA_URL`: URL **completa** do endpoint, por exemplo `http://127.0.0.1:4000/v1/cota`; não acrescente `/v1/cota` novamente.
- `HABBLAUD_QUOTA_TOKEN_FILE`: caminho absoluto de arquivo contendo somente o bearer do tenant. O operador monta esse arquivo somente leitura no contêiner Habblaud.
- Ambas ausentes/vazias: `quota` ausente; nenhuma consulta, leitura de token ou timer novo; `ompUsage` mantém a expressão atual.
- URL presente: exija arquivo configurado, URL absoluta HTTP(S), sem usuário/senha embutidos nem fragmento. Configuração parcial ou inválida produz erro explícito e sanitizado na inicialização, sem voltar silenciosamente a `omp usage`. Arquivo configurado sozinho também é erro.
- Com configuração válida, `ompUsage` é `false`, mesmo fora do Docker. A cota HTTP não depende de `omp`, `ompDirs`, `HABBLAUD_OMP_USAGE` nem `inDocker`.

Crie `server/accounts/quota.ts` com parser/adaptador puro e um coletor pequeno, com `start()` e `stop()`. Injete `fetch`, relógio e callback de resultado nos testes; não crie camada genérica de providers. Contrato:

1. Um `GET` imediato em `start()`, nova consulta após 5 min de sucesso ou 1 min de falha; uma consulta em voo. Timer sem manter processo vivo (`unref`), sem retries dentro da mesma rodada.
2. Leia o arquivo de token a cada consulta com `trim()`. Arquivo vazio/ilegível é falha, sem fetch. Assim uma rotação por substituição do arquivo vale na próxima rodada. Não leia `.env`, config do OMP ou credenciais do gateway ZeroClaw.
3. `Authorization: Bearer <conteúdo>`, timeout total de 10 s incluindo corpo, `redirect: 'error'`. Interrompa a consulta em `stop()`; não publique resultado nem reagende depois do encerramento. Não exponha erros brutos que possam conter headers/URL.
4. Só aceite resposta HTTP 2xx com JSON de objeto, `quota === true`, reports não vazios e um report com provider string não vazia, `fetchedAt` numérico finito e limites válidos de 5 h e 7 d. `usedFraction` deve ser número finito; respeite `scope.shared === true`, sem `tier`. `resetsAt` é opcional; quando presente, deve ser timestamp numérico finito. Resposta sem as janelas obrigatórias é falha, não 0%.
5. Escolha o primeiro report válido: todos representam a mesma cota. Não dependa de modelo ativo, alias ZeroClaw ou provedor de inferência. Use `parseOmpUsage(raw, provider, now)` depois da validação; retenha somente `source: 'omp'`, `quota: true`, `fetchedAt`, `fiveHour`, `sevenDay`. O literal histórico mantém o contrato de cliente; o novo coletor não importa nem invoca `OmpSource` ou `bin.ts`.
6. Exaustão com `status: 'exhausted'` e fração 1 continua 100%, com renovação; nunca `noQuota`. Não copie notas, planos, metadata, lista de provedores ou valores monetários para a saída.
7. Falhas HTTP 401/403/429/5xx, erro de rede, timeout e JSON inválido preservam a última coleta válida com seu `fetchedAt` original. Antes da primeira coleta válida, não produza números. Use aviso limitado (`log.warnOnce`, chave estável) com categoria/status, nunca token, corpo da resposta ou exceção bruta.

Crie a fixture `server/accounts/fixtures/quota.json` conforme `relatorio_cota`: `quota: true`, dois reports (`anthropic`, `openai-codex`) com limites idênticos, frações 0.25 e 0.5, datas sintéticas epoch ms e labels sintéticos. Nenhum token no JSON. Comece os testes de `quota.test.ts` para configuração, parser e transporte.

**Verify**: `npm test -- server/accounts/quota.test.ts server/sources/omp/config.test.ts` → todos passam; URL ausente preserva as expectativas antigas e URL presente desliga só a coleta OMP. `npm run typecheck` → exit 0.

### Step 2: Sobrepor a cota do tenant sem inventar uma conta de runtime

Em `server/accounts/service.ts`:

1. Acrescente opção `tenantQuota?: boolean`, padrão falso, e método `setTenantQuota(usage: AccountUsage): boolean` para publicar a coleta validada. Não use `setProviderAccounts('omp', ...)`.
2. Guarde a cota em um `UsageStore` separado, com uma única chave interna. Reutilize `view()` para envelhecimento e rollover; não duplique essa lógica nem a misture com o mapa de contas reais.
3. Com `tenantQuota` falso, preserve todos os caminhos atuais. Com verdadeiro, `usageView(id)` devolve a visão da cota para contas existentes; antes do primeiro sucesso, devolve `{status: 'disabled'}`. Id inexistente continua sem uso. A cota não pode ressuscitar contas removidas.
4. Faça `list()` e `usageViewChanged()` usarem a mesma visão efetiva. Atualização de cota notifica `onChange()` inclusive quando ainda não há conta, e a conta registrada depois recebe a coleta já guardada. Atualização de cache/uso do runtime não substitui a cota HTTP.
5. Remover uma conta não apaga a cota global. Mantenha nomes, providers, ids, caminhos e contagem de sessões reais. Não crie conta sintética para exibir o cartão quando não existe runtime registrado.
6. Ajuste os comentários locais que hoje dizem que não há leitura de credenciais/rede: o coletor é separado, mas a origem HTTP agora existe. Não altere a documentação ou semântica dos arquivos de uso legados.

Em `server/accounts/accounts.test.ts`, use conta externa Codex para provar independência do OMP sem esperar o 069. Inclua duas contas, zero sessões, registro tardio, remoção, mudança notificada, precedência sobre uso local e passagem do tempo. As contas atuais continuam com seus próprios cartões; o mesmo percentual representa a mesma cota do tenant, não cotas separadas por provedor.

**Verify**: `npm test -- server/accounts/accounts.test.ts server/accounts/quota.test.ts` → todos passam, incluindo janelas expiradas omitidas e status antigo após 30 min sem atualização. `npm run typecheck` → exit 0.

### Step 3: Conectar ao ciclo do servidor e provar ausência de processo OMP

Em `server/index.ts`:

- Passe `tenantQuota: !!config.quota` na criação de `AccountsService`.
- Quando `config.quota` existir, construa o coletor independente com callback para `accounts.setTenantQuota()`. Inicie junto de `accounts.start()` e encerre na lista de `shutdown`, antes de parar contas/hub.
- Não condicione o coletor a `omp`, `agents`, binários, sessão aberta ou prontidão de fonte ZeroClaw. Se nenhuma conta existir ainda, a coleta fica disponível para registro posterior.
- A opção `usage` recebida por `OmpSource` continua `config.ompUsage`, agora falsa no modo quota. Não instancie qualquer adaptador `omp-usage-cota` no caminho novo.
- Log de ativação deve dizer apenas que a consulta de cota está ligada. Não imprima URL, conteúdo do arquivo ou cabeçalhos. Não altere o log legado quando não configurado.

Complete `quota.test.ts`: configuração `HABBLAUD_OMP=0`, Docker ligado, sem diretório OMP, binário inexistente; crie coletor e `AccountsService` com a mesma ligação usada pelo bootstrap, registre conta Codex com zero sessões e confira `accounts.list(new Map())`. O resultado deve conter `usage.quota === true`, 25% e 50%, sem qualquer execução de processo. Faça spy em `node:child_process.execFile`/`spawn` para falhar se forem chamados nessa prova. Teste também que `config.ompUsage === false` com URL válida e fonte OMP habilitada. Não importe `server/index.ts` no teste, pois ele abre servidores.

**Verify**: `npm test -- server/accounts/quota.test.ts server/accounts/accounts.test.ts server/sources/omp/config.test.ts server/sources/omp/usage.test.ts server/sources/omp/source.test.ts client/src/ui/usage-source.test.ts` → todos passam; spies não registram processo no modo HTTP. `npm run typecheck` → exit 0.

### Step 4: Documentar o contrato operacional e fechar os gates

Em `server/README.md`, adicione as duas variáveis à tabela e explique o modo cota junto do uso OMP existente:

- URL completa; exemplo local sem credencial; arquivo de token montado RO e legível pelo usuário do servidor.
- Consulta independente de OMP/Docker/sessões; precedência tenant-wide; intervalos, timeout, stale e rollover.
- URL ausente preserva comportamento legado. Configuração parcial é erro; token ilegível ou endpoint indisponível não autoriza fallback para um processo OMP.
- URL e caminho vêm somente do ambiente do servidor, não do navegador. Use loopback do pod ou HTTPS de destino confiável. Não use token de pareamento ZeroClaw, nem credencial administrativa do gateway.
- O operador do omp-tenant 040 configura mounts/env; a execução deste plano não opera tenants.

Em `CHANGELOG.md`, acrescente entrada em `[Não lançado]`, seção `Adicionado`: cota da instalação por HTTP, sem processo OMP, e compatibilidade quando não configurada. Não anuncie rollout já feito.

**Verify**: `npm run typecheck && npm test` → exit 0. `git diff --check && git status --short` → somente alterações próprias no escopo; mudanças preexistentes ficam intactas.

## Test plan

Use `server/sources/omp/usage.test.ts` como padrão de fixture e `server/accounts/accounts.test.ts` como padrão de contas/tempo. Em `quota.test.ts`, use relógio/timers falsos e fetch injetado ou mock local; nenhuma chamada ao proxy real ou token real. Limpe timers, spies e arquivos temporários em `afterEach`.

Casos obrigatórios, nomeados explicitamente nos testes:

- Duas envs ausentes/vazias: `quota` ausente, nenhum coletor, configuração OMP anterior preservada.
- URL/arquivo presentes: cota habilitada no Docker e com OMP desligado; OMP ligado não executa `usage` em paralelo.
- Uma env sozinha, protocolo inválido, URL relativa, URL com credenciais/fragmento e arquivo relativo: erro sanitizado.
- Fixture realista com dois provedores: 25%/50%, mesmos dados sem modelo ativo; selecionar primeiro report válido, não somar reports.
- Exaustão 100%, sem `noQuota`; frações fora de 0–1 limitadas pelo parser; `resetsAt` ausente aceito.
- JSON truncado, `quota` falsa/ausente, reports vazios, report nulo, janela sem shared/com tier, fração não numérica, datas inválidas e janela obrigatória ausente: coleta recusada.
- Headers corretos com token **sintético** de arquivo temporário; token com quebra final é trimado; arquivo vazio/ausente não gera fetch; rotação vale na próxima coleta.
- 401, 403, 429, 500, rede, timeout inclusive corpo pendurado e redirect: falha controlada sem publicar números falsos e sem vazar segredo nos logs.
- Início imediato; sucesso aguarda 5 min; falha aguarda 1 min; sem requisições sobrepostas; stop aborta e não publica/reagenda resultado tardio.
- Conta externa com zero sessões recebe quota; conta criada após coleta recebe o último resultado; conta removida não reaparece.
- Modo quota antes do primeiro sucesso não mostra cache local como se fosse cota; falha posterior mantém `fetchedAt`; depois de 30 min fica stale; janelas renovadas somem sem virarem 0%.
- Sem envs novas, `setUsage`, remoção de contas, OMP parser/source e cartão atual mantêm os testes existentes.

Verificação: comandos focados das etapas e `npm test` → todos passam. Não fixe contagem total da suíte; outros planos podem acrescentar casos.

## Done criteria

Todos os itens devem valer:

- [ ] `npm run typecheck` sai 0.
- [ ] `npm test` sai 0, incluindo fixture e casos obrigatórios acima.
- [ ] `npm test -- server/accounts/quota.test.ts server/accounts/accounts.test.ts server/sources/omp/config.test.ts` sai 0 e prova a consulta com OMP desligado, Docker e zero sessões.
- [ ] Teste de bootstrap isolado prova zero chamadas a `execFile`/`spawn` no modo HTTP e `config.ompUsage === false`.
- [ ] Testes sem envs novas preservam caminho OMP, sem fetch/timer/leitura nova de token.
- [ ] Testes de 401/429/timeout preservam o último `fetchedAt`, stale e rollover; nenhum token aparece em snapshots/logs/fixtures.
- [ ] `server/README.md` documenta as duas envs, precedência, proteção da credencial e falhas; `CHANGELOG.md` registra sem alegar deploy.
- [ ] `git diff --check` sai 0; `git status --short` confirma que alterações próprias estão somente no Scope.
- [ ] Própria linha do plano 073 atualizada em `plans/README.md`; nenhuma outra linha de status alterada.
- [ ] Nenhum sync/restart/deploy ou alteração no checkout de produção foi feito.

## STOP conditions

Pare e reporte, sem improvisar, se:

- Os trechos vivos divergem por mudanças não provenientes do Habblaud **068 — Resumo sem processo OMP**. Mudanças do 068 nos arquivos compartilhados e no índice são esperadas; preserve-as, sem tratá-las como STOP.
- Qualquer gate falha duas vezes após uma tentativa razoável de correção.
- A solução exige arquivo fora do escopo, pacote novo ou mudança de protocolo público.
- O proxy deixa de devolver `quota: true`, duas janelas compartilhadas e reports idênticos por provedor. Nesse caso, não some reports nem estime cota por modelo.
- O token disponível não é o bearer **do tenant** aceito pelo proxy. Não use token administrativo nem token de pareamento ZeroClaw como substituto.
- O ambiente exige seguir redirect autenticado, imprimir credencial ou disponibilizar token ao navegador para funcionar.
- A fonte ZeroClaw integrada pelo 069 não registra conta enquanto ociosa. Reporte a quebra desse contrato ao responsável pelo 069; não crie conta OMP fictícia nem passe a depender de sessão ativa.
- Algum teste só passa acessando proxy real, lendo token de produção ou iniciando pod. Use fixtures e mocks; se não bastarem, reporte o bloqueio.
- Os novos caminhos de teste/fixture já existem com conteúdo não rastreado do usuário: não os sobrescreva.

## Maintenance notes

- A cota é do tenant, não do modelo ou da ferramenta. Trocar alias/provedor não deve zerar, somar ou consultar novamente por conta.
- O literal `source: 'omp'` permanece apenas por compatibilidade do formato de uso. Se o protocolo ganhar uma origem de cota própria, migre todos os leitores em um plano separado.
- O token é lido por consulta para permitir rotação. Revise qualquer cache futuro de credenciais contra essa propriedade.
- A revisão deve conferir logs sanitizados, rejeição de redirects, cancelamento em shutdown e preservação de timestamps durante falhas.
- O fluxo OMP legado e o wrapper externo ficam intactos para instalações não configuradas e rollback. Isso não é um segundo runtime no caminho novo.
- A montagem real e o rollout são responsabilidade dos planos omp-tenant 040 e 043. Nenhuma etapa deste plano afirma que tenants foram migrados ou religados.
