# Plan 068: Resumir respostas por HTTP sem manter processos omp

> **Executor instructions**: Siga este plano passo a passo. Execute cada comando de verificação e confirme o resultado esperado antes de avançar. Se ocorrer alguma condição de STOP, pare e relate; não improvise. Ao concluir, atualize somente a linha deste plano em `plans/README.md`.
>
> **Drift check (run first)**: em `/home/anderson/Desenvolvimento/habblaud`, execute `git diff --stat 694938f..HEAD -- server/config.ts server/index.ts server/summaries/summarizer.ts server/summaries/summarizer.test.ts server/summaries/config.test.ts server/http/app.ts server/http/http.test.ts server/README.md CHANGELOG.md plans/README.md`. Ordem fixa de merge Habblaud: **068 → 073 → 069 → 070 → 071 → 072**. Este plano é o primeiro: não há mudanças anteriores esperadas deste conjunto nos arquivos de código compartilhados (`server/config.ts`, `server/index.ts`, `server/README.md`, `server/http/app.ts`, `CHANGELOG.md`, `shared/types.ts` e `README.md`). A inclusão prévia das linhas do conjunto em `plans/README.md` pelo planejador é ESPERADA; preserve as demais linhas. Se algum arquivo mudou, compare os trechos de “Current state” com o código atual. Somente divergências não explicadas pelas mudanças esperadas são STOP. Confira também alterações locais com `git diff --` seguido dos caminhos de Scope; não sobrescreva trabalho alheio.

## Status

- **Priority**: P1.
- **Effort**: M.
- **Risk**: MED: texto de conversas passa a sair diretamente para um endpoint de inferência configurado pelo operador.
- **Depends on**: nenhuma para implementar e testar. Uso nos tenants depende de **omp-tenant 039 — Cota pelo fio Anthropic Messages** (`/home/anderson/Desenvolvimento/omp-tenant/plans/039-cota-fio-anthropic-messages.md`). Uso direto no host depende de um token de cliente do gateway, fornecido pelo operador.
- **Category**: perf.
- **Planned at**: Habblaud commit `694938f`, branch `producao`, 2026-11-09. Conjunto de migração: omp-tenant commit `4c978c2`.

## Why this matters

O resumidor mantém dois `omp --mode rpc` abertos para evitar a demora de inicialização. A medição de produção fornecida para este planejamento encontrou aproximadamente 470 MiB nos dois processos ociosos; não é uma medição reproduzida por este plano. Uma chamada HTTP curta remove essa necessidade no host e permite resumos em tenants que não terão binário nem conta OMP. Instalações sem a nova configuração continuam usando o runner RPC atual, deliberadamente: são instalações não migradas, não um fallback para falhas HTTP.

## Current state

Arquivos e trechos confirmados por leitura do código:

- `server/summaries/summarizer.ts:13-18`: limite, prazo e instrução já definidos. Reutilize-os; não crie outro prompt.

  ```ts
  export const SUMMARY_INPUT_MAX = 32 * 1024;
  const TIMEOUT_MS = 90_000;
  const PROMPT =
    'O usuário envia a resposta que um agente de programação deu. Não responda a ela nem converse: resuma-a em no ' +
    'máximo 5 frases, no mesmo idioma dela. Mantenha exatos os nomes, números, arquivos, comandos e erros que importam. ' +
    'Responda só com o resumo, sem introdução nem markdown de títulos.';
  ```

- `server/summaries/summarizer.ts:35-37`: o processo exige OMP e `@tiny`.

  ```ts
  constructor(readonly bin: string, readonly agentDir: string, readonly config: number, onExit?: () => void) {
    const args = ['--mode', 'rpc', '--no-session', '--no-tools', '--no-lsp', '--no-extensions', '--no-skills', '--no-rules', '--model', '@tiny', '--system-prompt', PROMPT];
    this.child = spawn(bin, args, { env: { ...process.env, PI_CODING_AGENT_DIR: agentDir }, stdio: ['pipe', 'pipe', 'ignore'], windowsHide: true });
  ```

- `server/summaries/summarizer.ts:123-165`: `rpcSummaryRunner(size = 2)` mantém o pool aquecido, mata chamadas após 90 segundos e repõe trabalhadores. `tinyRoleReader`, linhas 98-115, executa `omp config get modelRoles --json`.
- `server/summaries/summarizer.ts:185-194,205-210`: trocar somente a função de rede não basta. Construtor, aquecimento e pedido ainda passam pelas pré-condições OMP.

  ```ts
  constructor(private readonly opts: Options) {
    this.run = opts.run ?? rpcSummaryRunner(opts.concurrency ?? 2);
    this.tiny = opts.tiny ?? tinyRoleReader();
  }
  ```

  ```ts
  const bin = this.opts.bin();
  const agentDir = this.opts.agentDir();
  if (!bin || !agentDir) return Promise.reject(new SummaryUnavailable(tr('resumo indisponível: omp ou conta do omp não encontrados')));
  const job = this.tiny(bin, agentDir).then((tiny) => {
    if (!tiny) throw new SummaryUnavailable(tr('resumo desligado: escolha um modelo para o papel Tiny nas Configurações'));
    return this.slot(() => this.run(bin, agentDir, text));
  });
  ```

- `server/summaries/summarizer.ts:197-231`: cache SHA-256 de `Promise<string>`, deduplicação, remoção de falhas, máximo padrão 500, fila FIFO e concorrência padrão 2. Devem ser compartilhados pelos dois runners.
- `server/index.ts:167-172`: composição única do resumidor, condicionada a mensagens habilitadas.

  ```ts
  const summaries = config.messages ? new Summarizer({
    bin: () => ompBinFrom(process.env, config.home),
    agentDir: () => accounts.entriesOf('omp')[0]?.detected.configDir,
  }) : undefined;
  // As contas do omp aparecem depois que as fontes sobem: abre o omp dos resumos alguns segundos depois.
  if (summaries) setTimeout(() => summaries.warm(), 10_000).unref();
  ```

- `server/config.ts:1,14,240-249`: `ServerConfig` centraliza configuração; `loadConfig(env, argv)` recebe ambiente injetável e normaliza strings com `.trim()`. A documentação canônica é `server/README.md`. Não há convenção `HABBLAUD_*TOKEN_FILE` existente neste arquivo; introduza somente a necessária para este recurso.
- `server/http/app.ts:660-671`: `POST /api/summaries` exige a permissão `messages`, recusa texto vazio ou maior que `SUMMARY_INPUT_MAX` com 422, retorna `{summary}`, usa 503 para `SummaryUnavailable` e 502 para outras falhas. Preserve esse contrato, incluindo o comportamento HTTP externo para falhas do upstream.
- `server/http/app.ts:450-475`: `/api/health` já anuncia capacidades por conexão, sem segredos.

  ```ts
  messages: !!deps.messages && (!remote || trusted(req, 'messages')),
  codexEvents: !!deps.codexLive,
  ompEvents: !!deps.ompLive,
  ```

- `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/SettingsScreen.kt:160-164`: o cliente atual bloqueia resumos sem Tiny.

  ```kotlin
  val hasTiny = roles?.get("tiny") != null
  summarize?.let { on -> SwitchRow(stringResource(Res.string.settings_summarize), on && hasTiny, enabled = hasTiny) { scope.launch { store.setSummarize(!on) } } }
  Hint(if (hasTiny) Res.string.settings_summarize_hint else Res.string.settings_summarize_needs_tiny)
  ```

  Este plano fornece a capacidade `summaryHttp` no health; **Habblaud 072 — ZeroClaw no cliente web e Android** consome esse campo. Não altere Android aqui. Até o 072, o backend HTTP pode funcionar enquanto o interruptor do aplicativo permanece bloqueado.
- Padrões de teste: `server/summaries/summarizer.test.ts:25-82` injeta runner/promessas para concorrência, cache e indisponibilidade; linhas 85-100 cobrem RPC com um executável falso. `server/sources/omp/config.test.ts:23-65` usa `loadConfig` com HOME temporário. `server/http/http.test.ts:27-73,182-189` oferece `start(extra)` e teste de health. Use esses padrões.
- `package.json:26-28` define `typecheck`, `test` e `i18n:check`; `vitest.config.ts:5-6` inclui `server/**/*.test.ts` em ambiente Node. `CHANGELOG.md:11-13` contém `[Não lançado]` e `Adicionado`.

### Contrato de destino decidido

1. Novas variáveis: `HABBLAUD_SUMMARY_URL`, `HABBLAUD_SUMMARY_MODEL`, `HABBLAUD_SUMMARY_TOKEN_FILE` e `HABBLAUD_SUMMARY_AUTH` (`x-api-key` padrão ou `bearer`). URL é a base, sem `/v1/messages`; modelo é o ID de inferência do gateway, não alias de agente ZeroClaw nem `@tiny`.
2. Todas ausentes/vazias = RPC atual. Qualquer uma preenchida = intenção de usar HTTP: URL, modelo e caminho de token tornam-se obrigatórios. Configuração parcial/inválida deve falhar com erro seguro no carregamento, sem selecionar RPC silenciosamente.
3. URL aceita `http:` ou `https:`, sem usuário, senha, query ou fragmento; normalize barras finais. HTTP sem TLS serve aos serviços locais; para servidor externo, documente HTTPS obrigatório para proteger token e conversa. Caminho-base pode existir, mas não aceite uma URL já terminada em `/v1/messages`, evitando duplicação acidental. Não deduza autenticação pela porta.
4. Guarde somente caminho do token na configuração. Leia e aplique `.trim()` no arquivo em cada chamada HTTP efetiva, permitindo rotação; arquivo ausente/ilegível/vazio produz `SummaryUnavailable` com mensagem técnica fixa, sem valor do token, conteúdo do arquivo ou erro bruto do filesystem. Não invente credencial, não leia `.env` e não use token de pareamento ZeroClaw.
5. Request: `POST ${url}/v1/messages`, `Content-Type: application/json`, `anthropic-version: 2023-06-01`, um único header de autenticação conforme a configuração e corpo abaixo. Não use SDK nem nova dependência.

   ```ts
   {
     model: config.model,
     system: PROMPT,
     messages: [{ role: 'user', content: `Resposta do agente:\n\n${text}` }],
     max_tokens: 512,
     stream: false,
   }
   ```

6. `x-api-key` usa o token do tenant no proxy `127.0.0.1:4000`; o plano omp-tenant 039 traduz para Bearer. Gateway direto no host `127.0.0.1:4001` exige `Authorization: Bearer` com token de cliente autorizado ao modelo. Não é o gateway de sessões ZeroClaw `42617`.
7. Use `fetch` nativo com aborto real após `TIMEOUT_MS`, inclusive durante leitura do corpo, e `redirect: 'error'` para não encaminhar credencial. Libere temporizador em `finally`. Não faça retries nem fallback RPC em erro HTTP. 401, 403, 429, 5xx e rede rejeitam o job, que sai do cache. Reporte somente status/código seguro, nunca corpo upstream ou texto enviado.
8. Resposta não-stream: valide `content` como array de blocos. Colete somente blocos `{type:'text', text:string}`, na ordem, una com quebra de linha e aplique `.trim()`. Ignore blocos não textuais; JSON inválido, estrutura inválida ou texto final vazio são erros. Não assuma `choices`, não use SSE e não exponha `thinking`.
9. `Summarizer` recebe configuração HTTP opcional e seleciona caminho antes de consultar `bin`, `agentDir`, `tinyRoleReader` ou criar/aquecer pool. Reutilize o mesmo cache e `slot()`. `warm()` no HTTP é no-op, sem rede nem processos. Preserve `RunSummary` e injeções dos testes RPC; não preencha binário/conta falsos para satisfazer o contrato antigo.
10. Exponha no resumidor uma indicação somente leitura `httpConfigured: boolean`. `/api/health` ganha `summaryHttp: !!deps.summaries?.httpConfigured && trusted(req, 'messages')`. Significa “resumo HTTP configurado e autorizado nesta conexão”, não disponibilidade instantânea do upstream. Falso no RPC, sem resumidor ou sem permissão remota. Não anuncie URL, modelo, caminho nem token. O plano 072 habilitará resumos com `health.summaryHttp || hasTiny`; campo ausente em servidor antigo equivale a falso.

## Commands you will need

Execute na cópia de desenvolvimento `/home/anderson/Desenvolvimento/habblaud`, nunca em `/home/anderson/apps/habblaud`.

| Finalidade | Comando | Resultado esperado |
|---|---|---|
| Tipos | `npm run typecheck` | exit 0, sem erros |
| Resumidor e configuração | `npm test -- server/summaries --maxWorkers=2` | todos os testes passam |
| Contrato HTTP | `npm test -- server/http/http.test.ts --maxWorkers=2` | todos passam, incluindo health e POST de resumo |
| Regressão HTTP existente | `npm test -- server/http/remote.test.ts server/http/terminal.test.ts --maxWorkers=2` | todos passam |
| Suíte final | `npm test -- --maxWorkers=2` | todos passam |
| Revisão de arquivos | `git status --short` | nenhuma alteração nova fora de Scope; arquivos prévios do usuário preservados |

Comandos conferidos por leitura dos scripts e da configuração de testes. Não foram executados durante a autoria. Não instale dependências nem rode build para este plano; dependências ausentes são STOP.

## Scope

**In scope** (únicos arquivos a modificar):

- `server/config.ts` — tipo opcional e parser da configuração HTTP.
- `server/index.ts` — repasse da configuração ao resumidor.
- `server/summaries/summarizer.ts` — runner HTTP, seleção e capacidade sem OMP.
- `server/summaries/summarizer.test.ts` — regressões dos dois caminhos.
- `server/summaries/config.test.ts` — criar testes da configuração.
- `server/http/app.ts` — somente `summaryHttp` no health e correção do comentário do resumo para citar ambos os modos.
- `server/http/http.test.ts` — capacidade e contrato POST sem OMP.
- `server/README.md` — variáveis, autenticação e operação documentada sem executar publicação.
- `CHANGELOG.md` — entrada curta em `[Não lançado]`.
- `plans/README.md` — somente status da linha 068 ao concluir.

**Out of scope**:

- Alterar clientes web/Android ou telas de modelos/Tiny: **Habblaud 072 — ZeroClaw no cliente web e Android**.
- Fonte/histórico: **Habblaud 069 — Fonte ZeroClaw**. Mensagens/STOP: **Habblaud 070 — ZeroClaw: mensagens e controles**. Elenco: **Habblaud 071 — Elenco com ZeroClaw**. Cartão de uso: **Habblaud 073 — Cartão de uso pela cota**.
- Proxy e medição de custo: **omp-tenant 039 — Cota pelo fio Anthropic Messages**. Variáveis/montagens de tokens por tenant: **omp-tenant 040 — Runtime ZeroClaw por tenant**. Ativação: **omp-tenant 043 — Piloto e rollout ZeroClaw**.
- Publicar, alterar systemd/Quadlet, criar tokens/grants, ler credenciais, rodar `omp-tenant sync`, iniciar/reiniciar pods. Tenants estão pausados; somente o plano 043 pode ativar piloto com autorização explícita do operador.
- Mudança do contrato de erro do POST, quotas, streaming, ferramentas, seleção de modelo pelo usuário, novo cache ou fila. Não remover suporte RPC de instalações sem HTTP.

## Git workflow

- Trabalhe a partir de `producao` na cópia de desenvolvimento, branch `advisor/068-resumo-sem-processo-omp`.
- Respeite a ordem fixa de merge: Habblaud **068 → 073 → 069 → 070 → 071 → 072**; omp-tenant **039 → 040 → 041 → 042 → 043**. Esta ordem de integração não autoriza ativação de serviços.
- Não use comandos destrutivos; alterações locais e planos não rastreados de outros trabalhos pertencem ao usuário.
- Commit por unidade lógica. Convenção observada: `694938f feat(android): puxada longa para cima no fim da conversa abre o bate-papo paralelo`. Mensagem sugerida: `feat(resumos): resumir por HTTP sem abrir processos omp`.
- Não faça push, PR, merge ou deploy sem instrução do operador.

## Steps

### Step 1: Centralizar e testar a configuração HTTP

Em `server/config.ts`, adicione `SummaryHttpConfig` com `url`, `model`, `tokenFile`, `auth: 'x-api-key' | 'bearer'` e `ServerConfig.summaryHttp?`. Extraia parser `parseSummaryHttpConfig(env)` e chame-o em `loadConfig`. Implemente exatamente as regras 1–3 do contrato; erros devem identificar a variável inválida sem reproduzir seu conteúdo. Não leia token durante parsing e não exporte essas opções em APIs públicas.

Crie `server/summaries/config.test.ts`, usando `tempDir()` e ambiente injetado, como o teste OMP citado. Cubra todas ausentes/vazias, configuração completa, autenticação padrão/Bearer, URL com barra final, configuração parcial, esquema inválido, credenciais/query/fragmento na URL, endpoint duplicado e auth inválida. Inclua `loadConfig` com `HABBLAUD_OMP=0` para provar independência.

**Verify**: `npm test -- server/summaries/config.test.ts --maxWorkers=2` → todos passam. `npm run typecheck` → exit 0.

### Step 2: Acrescentar runner HTTP sem duplicar cache e fila

Em `server/summaries/summarizer.ts`, implemente `httpSummaryRunner(config)` seguindo as regras 4–8. Injete `fetch` nos testes com `vi.stubGlobal`, restaurando-o após cada teste; não crie transporte genérico. Use arquivo de token temporário, nunca credencial real. Adicione opção HTTP ao `Summarizer`, selecione o caminho HTTP antes das pré-condições OMP e mantenha cache/fila em um único trecho. Atualize comentários que afirmam exclusividade do RPC. Preserve comentários que continuam corretos.

Adicione testes nomeados em `summarizer.test.ts` para request exato, dois esquemas de autenticação, parse de múltiplos blocos, arquivo de token inválido/rotacionado, falhas de upstream, timeout, rejeição de redirect, ausência completa de chamadas aos callbacks OMP em `warm()`/`summarize()`, cache e concorrência no caminho HTTP. Mantenha os testes RPC existentes sem enfraquecê-los.

**Verify**: `npm test -- server/summaries --maxWorkers=2` → todos passam, incluindo RPC e HTTP. `npm run typecheck` → exit 0.

### Step 3: Ligar configuração e anunciar capacidade ao aplicativo

Em `server/index.ts`, passe `config.summaryHttp` ao construtor existente. Mantenha `config.messages` como condição de criação e o aquecimento existente, que será no-op no HTTP. Não altere a inicialização das fontes ou do elenco.

Em `server/http/app.ts`, adicione o booleano `summaryHttp` ao health usando a regra 10. Não mude campos existentes, a autorização nem o contrato de `POST /api/summaries`. Atualize somente o comentário OMP-only junto ao POST.

Em `server/http/http.test.ts`, use o helper `start(extra)` para montar um `Summarizer` HTTP real com token temporário e `fetch` controlado. Teste health verdadeiro/falso, RPC falso, conexão remota sem `messages` falso e remota autorizada verdadeiro. Teste POST sem conta/binário OMP: 200 `{summary}`; entrada vazia ou longa: 422 sem chamada upstream; sem autorização: 403 sem chamada; upstream 429: 502 e novo pedido tenta novamente; token ausente: 503. A cota 429 permanece preservada no proxy pelo plano 039, mas a rota Habblaud mantém seu contrato 502 atual.

**Verify**: `npm test -- server/http/http.test.ts server/http/remote.test.ts server/http/terminal.test.ts --maxWorkers=2` → todos passam. `npm run typecheck` → exit 0.

### Step 4: Documentar configuração e fechar regressões

Em `server/README.md`, documente variáveis e POST `/api/summaries`, além de `summaryHttp` no health. Inclua dois exemplos sem segredo:

- Tenant futuro: URL `http://127.0.0.1:4000`, auth `x-api-key`, modelo liberado e caminho do token montado pelo plano omp-tenant 040. Uso somente após 039 e ativação autorizada no 043.
- Host: URL `http://127.0.0.1:4001`, auth `bearer`, ID de modelo permitido pelo gateway e caminho absoluto para arquivo 0600 legível pelo processo Habblaud. O operador precisa criar/selecionar um token de cliente do gateway com grant para esse modelo e montar o arquivo somente leitura se Habblaud estiver em contêiner. Caminho no ambiente é o visto pelo processo; `127.0.0.1` requer rede compartilhada com o host. Criar credencial, alterar serviço e reiniciar para aplicar são tarefas do operador, não deste executor.

Explique remoção de todas as quatro variáveis para retornar ao RPC numa instalação que ainda possui OMP/Tiny. Erro HTTP nunca aciona RPC. Não prometa economia de 470 MiB sem medir após publicação: esse número é a referência fornecida, não gate automatizado. Cite a dependência do 072 para liberar o interruptor Android sem Tiny.

Acrescente entrada em `CHANGELOG.md` sem afirmar que produção já foi migrada. Não altere versões ou scripts de release.

**Verify**: `rg -n 'HABBLAUD_SUMMARY_(URL|MODEL|TOKEN_FILE|AUTH)|summaryHttp' server/README.md` → todas as quatro variáveis e a capacidade documentadas. `npm run typecheck` e `npm test -- --maxWorkers=2` → exit 0. `git diff --check` → exit 0. `git status --short` → somente arquivos de Scope alterados por este trabalho, além das alterações prévias preservadas.

## Test plan

- Estrutura: mantenha `deferred()` e asserções de cache/concorrência de `server/summaries/summarizer.test.ts`; configuration segue `server/sources/omp/config.test.ts`; HTTP segue `start(extra)` de `server/http/http.test.ts`.
- HTTP correto: URL normalizada, POST, versão Anthropic, `system` idêntico, prefixo existente no user, `stream:false`, `max_tokens:512`, modelo literal e somente um header auth. Nenhuma chamada a `/v1/chat/completions`.
- Parse: um bloco, vários blocos com não textuais intercalados, whitespace, JSON inválido, `content` inválido e ausência de texto útil.
- Segurança: redirects rejeitados; erros não contêm token, texto do usuário nem corpo upstream. Arquivo vazio/ausente/ilegível gera indisponibilidade, nunca RPC. Simule erro de leitura por mock, sem depender de permissões do usuário que executa testes.
- Recursos: timeout de 90 s aborta sinal usado por `fetch`; finalize corpo pendente no teste para provar que o prazo inclui leitura. Restaure timers, globals e temporários. Não rode chamadas reais ao gateway.
- Cache/limites: mesmo texto simultâneo gera um request; falha sai do cache; sucesso reaproveitado; eviction com `cacheMax:1`; máximo duas chamadas simultâneas por padrão; terceira aguarda FIFO. Preserve validação HTTP de 32 Ki caracteres.
- Sem OMP: callbacks `bin`, `agentDir` e `tiny` devem lançar se consultados no teste HTTP; `warm()` e pedido ainda funcionam. RPC continua recusando ausência de OMP/conta/Tiny e reaproveitando processo no teste existente.
- Capacidade: health anuncia `summaryHttp` sem expor config e respeita permissão remota. Campo não significa teste de conectividade nem disponibilidade de Tiny.
- Verificação final: `npm run typecheck` e `npm test -- --maxWorkers=2` → todos passam, incluindo novos casos descritos acima.

## Done criteria

- [ ] `npm run typecheck` termina com exit 0.
- [ ] `npm test -- server/summaries --maxWorkers=2` passa com testes HTTP e regressões RPC mantidas.
- [ ] `npm test -- server/http/http.test.ts server/http/remote.test.ts server/http/terminal.test.ts --maxWorkers=2` passa.
- [ ] `npm test -- --maxWorkers=2` termina com exit 0.
- [ ] Testes provam HTTP sem consultar binário, conta ou Tiny, incluindo `warm()` sem spawn/rede.
- [ ] Testes provam deduplicação, fila, eviction, remoção de falha e aborto efetivo em 90 s no caminho HTTP.
- [ ] Testes de request provam fio Messages não-stream e autenticação exclusiva x-api-key/Bearer.
- [ ] Testes de health provam `summaryHttp` verdadeiro somente no HTTP autorizado; POST mantém status e formato existentes.
- [ ] `rg -n 'HABBLAUD_SUMMARY_(URL|MODEL|TOKEN_FILE|AUTH)|summaryHttp' server/README.md` encontra todas as variáveis/capacidade; changelog registra o recurso em `[Não lançado]`.
- [ ] `git diff --check` termina com exit 0; `git status --short` não mostra alterações deste trabalho fora de Scope.
- [ ] `plans/README.md` registra este plano como DONE; nenhuma outra linha foi alterada pelo executor.
- [ ] Nenhum token real, restart, deploy, `omp-tenant sync` ou ativação de tenant foi necessário.

## STOP conditions

Pare e reporte, sem improvisar, se:

- Os trechos de Current state não correspondem ao código e a divergência afeta composição, fila/cache, autorização ou contrato HTTP, sem ser explicada pelas mudanças esperadas declaradas no drift check.
- Um gate falha duas vezes após tentativa razoável de correção, ou as dependências locais necessárias não estão instaladas.
- Resolver a tarefa exigir editar arquivo fora de Scope, inventar credencial, alterar gateway/proxy ou tocar produção.
- O endpoint disponível exigir Chat Completions em vez de Messages. Não troque o fio: depende do plano omp-tenant 039.
- O token de cliente do gateway do host não existe ou não tem grant. Entregue implementação/testes locais; reporte a ativação real bloqueada ao operador, sem criar token nem copiar credenciais de outro usuário.
- Um teste HTTP aciona `omp config get`, cria processo RPC ou depende de uma conta OMP. Corrija a seleção do caminho, não simule um binário/conta válidos.
- O contrato `summaryHttp:boolean` do health conflitar com implementação já entregue pelo plano 072. Alinhe contrato com o revisor antes de mudar nome/semântica.
- Alguém solicitar reiniciar tenant durante a pausa. Somente omp-tenant 043, com autorização explícita, pode executar piloto.

## Maintenance notes

- O RPC continua sendo comportamento suportado quando HTTP não está configurado; não remover pool/Tiny nesta migração.
- Revisão deve olhar especialmente seleção antes das pré-condições OMP, ausência de fallback em falhas, aborto durante leitura e preservação de cache/fila.
- Modelo/configuração são fixos por processo. Mudança de env exige reinício autorizado; rotação do arquivo de token vale na próxima chamada não atendida pelo cache.
- O proxy mede custo e controla modelos; Habblaud só envia o request. Não duplique catálogo, precificação ou lógica de quota no resumidor.
- `summaryHttp` é capacidade de configuração/autorização, não health check do upstream. O aplicativo deve tratar falha de resumo normalmente e preservar texto original.
- Produção do host pode adotar o recurso antes dos tenants, com token de cliente do gateway fornecido pelo operador. A economia de memória precisa ser medida depois, fora deste plano de implementação.
