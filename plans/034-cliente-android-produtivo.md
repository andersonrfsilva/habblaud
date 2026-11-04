# Plano 034: Criar um cliente Android nativo para trabalhar nas sessões do Habblaud

> Executor: leia este plano inteiro. Implemente no clone de desenvolvimento ou em uma worktree isolada. Não faça deploy, push, alterações em tenants ou reinício de sessões reais. A entrega é um aplicativo funcional, não um esqueleto. A fonte única do andamento é a linha 034 de `plans/README.md`.
>
> Drift check: `git diff --stat 84afde2..HEAD -- android scripts/android-smoke.ts server/test/android-fixture.ts .github/workflows/android.yml .gitignore README.pt-BR.md CHANGELOG.md plans/README.md shared/types.ts shared/messages.ts server/http server/messages server/permissions server/spawn/attachments.ts server/sources server/index.ts server/config.ts server/deliveries/fetcher.ts server/tsconfig.json server/README.md mod/habblaud-omp/habblaud.ts client/src/ui/composer.ts .github/workflows/ci.yml`. Compare também as alterações sem commit com os contratos abaixo. Se houver divergência de protocolo, resolva o contrato antes de implementar.

## Prioridade e intenção

- Prioridade P2; esforço L; risco médio; categoria direção.
- Planejado em `84afde2`, em 2026-11-02. Este número é do repositório Habblaud, não do `omp-tenant`.
- Dependências: nenhuma implementação de outro plano pendente. Há um limite conhecido do runtime no 033, descrito abaixo.
- Solicitação: cliente Android com foco em produtividade, sem escritório, personagens, animações decorativas ou WebView. A economia no navegador não resolveu a experiência relatada pelo usuário.
- Não prometer ganho percentual antes de medir no aparelho. O objetivo é remover o custo da interface do mundo e melhorar leitura, escrita e retomada de sessões.

## Decisões de produto

1. Aplicativo nativo Kotlin + Jetpack Compose, instalado por APK. O mesmo servidor e as mesmas sessões continuam sendo a fonte de verdade.
2. Tela inicial: sessões abertas; alternância para recentes, busca local por título/projeto/agente e indicador de conexão. Sessões com pedidos de aprovação ficam destacadas, sem reordenar a lista enquanto o usuário toca nela.
3. Tocar numa sessão abre a conversa e o compositor. Mostrar projeto, provedor, estado e atividade atual. Não expor a organização física do escritório na navegação.
4. Conversa: texto selecionável, respostas completas dentro da janela fornecida pelo servidor, código preservado, ferramentas recolhíveis e resultados associados. Sem terminal ANSI nem execução de HTML.
5. Envio: texto e até cinco anexos, estado de aceitação e entrega, rascunho por sessão e nenhuma duplicação automática de pedidos.
6. Interromper: somente OMP, pela operação já existente. Aprovações e perguntas aparecem somente quando a conexão e o pedido permitem a resposta.
7. Retomada do aplicativo: voltar à última conversa e recuperar texto ainda não enviado. Isso NÃO significa iniciar um processo encerrado nem executar `/resume` automaticamente.
8. Uma instalação por vez, configurada por URL e login remoto existente. Decisão do usuário (2026-11-02): **inicialmente HTTP dentro do Tailscale**, usando o endpoint atual `http://100.83.131.11:4747`. HTTPS continua aceito, para migrar depois (internet por proxy) sem mudar o app. A troca de servidor encerra fluxos e isola todo estado pela origem; não criar catálogo de perfis.
9. Android mínimo API 26. Interface utilizável em retrato, paisagem, tablet, fonte ampliada e com teclado aberto. Português como idioma inicial; textos em recursos Android, sem copiar catálogos web inteiros.
10. Primeiro alvo de distribuição: APK assinado para instalação direta. Loja, atualização automática, push e notificações com o app fechado não fazem parte desta entrega.

## Fora de escopo

- Escritório, World, personagens, elenco, criação/dispensa de agentes, temas, jogos, estatísticas e timelapse.
- Terminal interativo PTY, shell remoto, captura de teclas e edição de arquivos do host.
- Criar sessões, retomar processos fechados, trocar modelo/raciocínio, comandos de barra e chat paralelo `/api/asides`.
- Paginação nova de transcript, histórico completo, busca no servidor e sincronização offline de conversas.
- Fila offline de comandos, reenvio automático de POST, idempotência nova no backend, serviço Android permanente ou WorkManager para manter SSE.
- Firebase, anúncios, telemetria, conta adicional e servidor intermediário.
- Importação de CA privada, desativação de TLS, HTTP fora da regra Tailscale abaixo e login via navegador embutido. Testes de TLS usam a CA de teste do MockWebServer só nos testes, nunca no APK. Não fazer bypass.
- Configurar Tailscale Serve, proxy, DNS, certificado ou variáveis do standalone. Isso é pré-requisito de produção do operador (seção própria abaixo), com autorização separada.
- Alterar semântica da extensão OMP, corrigir o runtime do 033 ou prometer interrupção uniforme entre provedores.

Esses limites são deliberados: este cliente serve para operar sessões existentes. Não esconder controles necessários atrás de um falso suporte nem anunciar cobertura dessas exclusões.

## Estado atual confirmado

### Arquitetura e verificação

Servidor Node/TypeScript ESM sem dependências de runtime; cliente web DOM/Canvas; protocolo comum em `shared/`. `package.json:16-50` define `npm run typecheck`, `npm test`, `npm run i18n:check -- --strict` e `npm run build`. Não há comando de lint JavaScript. `.github/workflows/ci.yml:16-35` executa os quatro gates em Ubuntu e Windows com Node 22.12.

Não existe projeto Android no inventário consultado. No ambiente de planejamento há Java/Javac 21.0.13; `gradle`, `adb`, `sdkmanager` e `emulator` não aparecem no PATH. `ANDROID_HOME`/`ANDROID_SDK_ROOT` não estão configurados. Isso não prova ausência de SDK em outro diretório. Não houve instalação nem build Android nesta investigação.

O HEAD tem mudanças locais anteriores em preferências Android, overlays, changelog e documentação, além do plano 033 e índice. Preservá-las; o SHA não inclui essas mudanças. Não fazer stash/reset/commit indiscriminado.

### Contratos reutilizáveis

| Uso | Contrato existente | Consequência para o app |
|---|---|---|
| Login | `POST /api/remote/login`, JSON `{user,password}`, `200 {ok:true}` e cookie `habblaud_remote` | Não criar bearer token nem guardar senha |
| Logout | `POST /api/remote/logout`, JSON `{}`, `200 {ok:true}` e cookie vencido | Tentar revogar; limpar estado local mesmo sem rede, avisando se revogação não foi confirmada |
| Recursos | `GET /api/access`, `AccessInfo` | Habilitar cada função pela trava da conexão (matriz abaixo), não por versão presumida |
| Estado ao vivo | `GET /api/stream`: `snapshot`, `feed`, `notice`; início com snapshot | Usar snapshot para sessões abertas e permissões; feed/notice não são a conversa |
| Recentes | `GET /api/sessions/recent`: `{sessions,days,limit}` | Últimos 7 dias, limite agregado 150; sem paginação |
| Conversa aberta | `GET /api/agents/:id/terminal` | SSE estruturado, não PTY |
| Conversa histórica | `GET /api/sessions/:account/:sessionId/terminal` | Mesmo protocolo, sessão fechada fica somente leitura |
| Envio | `POST /api/messages`: `{agentId,text,attachments?,deliverAs?}` → `201 OutboxMessage` | Capturar destinatário antes dos uploads; não reenviar automaticamente |
| Situação | `GET /api/messages/:id` | `queued`, `sent`, `delivered`, `failed`; 404 depois da retenção/reinício não prova que não executou |
| Interromper OMP | `POST /api/messages/control`: `{agentId,stop:true}` → `201 OutboxMessage` | Mesma fila e confirmação; não matar processo nem usar `/api/agents/:id/stop` |
| Aprovação | `GET /api/permissions/:id`; `POST /api/permissions/:id/decision` | Revalidar pedido e mostrar argumentos antes de decidir |
| Anexos | `POST /api/messages/attachments`, bytes binários → `201 {id,name,size}` | Sem multipart, base64 ou caminho do dispositivo no JSON |

**Matriz de travas na porta remota** (`server/http/app.ts:309-312,419-424,459-473,491,509,517,814`; `server/index.ts:186`; `HABBLAUD_REMOTE_ALLOW`, padrão só `permissions` em `server/http/remote.ts:11-12,114-122`):

| Função do app | Exige, além do login | Sem a trava |
|---|---|---|
| Sessões abertas (`/api/stream`, `/api/snapshot`) | nada | — |
| Recentes, conversa viva e histórica | `terminal` | 403: lista só com abertos, conversa indisponível com aviso |
| Enviar, anexar, interromper OMP | `messages` (e `messageSessionGuard`) | 403: compositor em leitura com motivo |
| Responder aprovações/perguntas | `permissions` | pedido visível sem botões, com motivo |

Cada trava é independente: `messages` não implica `terminal`. Funcionalidade completa exige `terminal,permissions,messages`. O standalone atual já usa esse valor (`HABBLAUD_REMOTE_ALLOW=terminal,permissions,messages`, lido do contêiner em 2026-11-02). O app nunca pede nem altera essas flags.

Referências abertas e conferidas durante o planejamento:

- `server/http/remote.ts:60-63,147-177,540-541,562-675`: sessão fixa de 30 dias; cookie HttpOnly/SameSite=Strict e Secure com HTTPS; mudança de senha invalida tokens. Não há refresh. O login usa freio e pode retornar 429 com `Retry-After`.
- `server/http/guard.ts:77-89,131-143`: Origin ausente é aceito; JSON nos POST de API, exceto uploads binários. OkHttp não precisa inventar Origin, Host, cabeçalho de proxy ou liberar CORS.
- `server/http/app.ts:459-473`: autorização por recurso para a conexão. Pela porta remota, PTY/World e rotas de hook/auxiliar não ficam liberados por login.
- `server/http/sessions.ts:49-81`: histórico resolve conta e sessão no servidor; não recebe caminho de transcript do cliente.
- `server/sources/source.ts:127-148`: resolução encaminhada ao provedor da conta. Incluir provedor na chave local, mas não acrescentar segmento de provedor inexistente à URL.
- `shared/types.ts:185-205,627-687`: `agentId` identifica agente vivo; sessão usa conta/provedor/sessionId. Provedor ausente significa Claude.
- `server/http/terminal.ts:24-39,112-150`: janela inicial de até 500 entradas dos aproximadamente 4 MiB finais, `truncated`, máximo de oito streams de conversa. Substituição/truncamento do arquivo produz novo `init`.
- `server/http/sse.ts:79-97,166-168`: stream de estado tem snapshot inicial e pings. Não há replay por ID SSE.
- `shared/messages.ts:6-21`: texto máximo 20.000 caracteres UTF-16, cinco anexos, 15 MiB por arquivo, modos OMP `aside | followUp | steer`.
- `server/messages/registry.ts:183-228,321-395`: validação, fila por sessão, restrição do controle a OMP e `followUp` quando modo omitido.
- `server/messages/http.ts:26-42,85-96`: erros HTTP e status de entrega; resultado final fica retido por dez minutos, ou até reinício.
- `server/spawn/attachments.ts:12-15,55-117`: TTL de 30 minutos no staging e orçamento compartilhado de 120 MiB. IDs são consumíveis e podem desaparecer com reinício.
- `server/permissions/http.ts:59-76` e `shared/types.ts:770-827`: perguntas por índice, expiração, conflito de decisão e restrições OMP/Codex.
- `client/src/ui/composer.ts:348-386`: exemplo de captura imutável de agente/modo/lote; só limpa o rascunho se o usuário não escreveu outro texto durante o envio. Rascunhos web atuais ficam em memória; o Android terá persistência própria, sem alterar a web.

Trechos mínimos para o drift check:

```ts
// shared/types.ts:644-660
export interface TerminalInit {
  agentId: string;
  entries: TerminalEntry[];
  truncated: boolean;
}
export type TerminalMessage = { type: 'init'; data: TerminalInit } | { type: 'append'; data: TerminalEntry[] };
```

```ts
// server/messages/registry.ts:327,337 — não copiar a implementação para o app
if (deliverAs !== undefined && agent.provider !== 'omp') throw new InvalidRequest('deliverAs só é aceito para OMP');
if (agent.provider === 'omp') entry.msg.deliverAs = deliverAs ?? 'followUp';
```

`TerminalEntry` possui seis variantes: `user`, `assistant`, `thinking`, `tool`, `result`, `system`. `tool.id` casa com `result.toolUseId`; resultado pode chegar depois. `init` SUBSTITUI a conversa, inclusive ao reconectar. Não concatenar um segundo init, não assumir streaming de tokens e não usar `Last-Event-ID` como recuperação.

### Única extensão necessária no backend: destinatário esperado

**Evidência:** `server/messages/registry.ts:321-336,382-392` resolve o agente e captura `agent.sessionId` somente quando o POST chega. `parseSend`/`parseControl` não recebem a sessão que o usuário estava vendo. A checagem no app não elimina a troca por `/clear` entre a leitura do snapshot e a chegada do pedido.

Adicionar `expectedSessionId?: string` aos corpos existentes de mensagem e controle. Quando fornecido, validar string não vazia com o limite `ID_MAX` existente e comparar com `agent.sessionId` dentro de `MessageRegistry.send/control`, sem await entre comparação e criação da Entry. Divergência retorna um resultado novo `{ error: 'session-changed' }`, que `server/messages/http.ts` responde como `409 {error: ERR_SESSION_CHANGED, code: 'session_changed'}`, antes de consumir anexos, criar mensagem ou aplicar controle. Os demais 409 (`unavailable`) não ganham `code`. O app decide pelo `code`, nunca pelo texto traduzido. Omitido mantém o contrato dos consumidores web existentes; não criar alias nem rota nova. Erros de validação usam literal sem `tr()`, como `'deliverAs inválido'` em `registry.ts:187`: nenhuma chave nova de tradução.

Em `parseControl`, separar esse metadado de `OmpControl`: ele não deve chegar à extensão como comando. Os controles `stop`/`resume` continuam sozinhos quanto às ações, mas podem carregar `expectedSessionId`. Preservar os checks atuais de sessão depois do enqueue.

Adicionar `messageSessionGuard?: boolean` em `AccessInfo`, anunciado como true por `/api/access` somente quando mensagens estão habilitadas e autorizadas nesta conexão. O Android exige true para mensagem/controle e sempre envia `expectedSessionId`; servidor antigo sem esse campo fica em leitura para essas operações, com aviso de atualização. Não tentar enviar para descobrir se o backend ignora campos desconhecidos.

Esta adaptação deve entrar antes do APK gravar em produção. Não altera login, modos de entrega, filas nem extensão. Publicação do backend continua dependendo de autorização separada do operador.

### Limite conhecido dos modos OMP

O plano 033 registra teste com OMP 18.12.6 no qual `steer` entrou antes da resposta, mas não pulou a segunda ferramenta pendente do lote (`plans/033-modos-de-envio-omp.md:160-168`). Também registra que sessões já abertas podem manter extensão antiga (`:193`).

No app, oferecer escolha por envio: **Acrescentar agora** (`aside`, padrão), **Enfileirar** (`followUp`) e **Mudar direção** (`steer`), somente OMP. Mostrar aviso de que a entrega depende da extensão carregada e que mudar direção não garante interromper/pular ferramentas. Não depender da conclusão do 033 nem prometer sua semântica não comprovada. `aside` aqui é mensagem principal, não conversa paralela.

### Evidência de execução desta investigação

Consultas somente leitura na produção standalone, via loopback (`/api/access` retornou `remote:false`): `/api/access`, `/api/snapshot` e `/api/sessions/recent` retornaram 200; recentes informou `days=7`, `limit=150`, 62 sessões e provedor OMP. Leitura inicial de `/api/stream` confirmou `snapshot`/`feed`; conversa de um agente OMP confirmou `init`. Ambos responderam `text/event-stream`, `retry: 2000` e nenhum `id:` SSE no bloco observado. Nenhum prompt ou aprovação foi enviado.

Porta remota atual (`http://100.83.131.11:4747`, Tailscale), sondada sem credenciais e como cliente nativo (sem `Origin`): `/api/access`, `/api/sessions/recent`, `/api/stream` e `POST /api/messages` → 401; `POST /api/remote/logout` JSON sem Origin e sem cookie → `200 {"ok":true}`; o mesmo com `Origin: https://evil.example` → 403. Conclusão: o guard aceita cliente sem Origin e recusa Origin estranho. O app **não envia `Origin`** nem `Referer`; não relaxar CSRF no servidor.

Isso comprova formatos e o gate, não login remoto com cookie, SSE autenticado, uploads remotos, os três provedores nem desempenho no aparelho. Esses itens ficam para a fixture (etapa 2+) e para a prova real pelo Tailscale (etapa 7).

### Endpoint de produção: HTTP dentro do Tailscale (decisão inicial)

Estado verificado em 2026-11-02: o standalone expõe a porta remota em HTTP no IP Tailscale (`HABBLAUD_REMOTE_HOST=100.83.131.11`, `HABBLAUD_REMOTE_PORT=4747`, `HABBLAUD_REMOTE_INSECURE_HTTP=1`, `HABBLAUD_REMOTE_ALLOW=terminal,permissions,messages`). Esse é o alvo inicial do app; nenhuma mudança de infraestrutura é necessária. Host é IP literal, então o guard aceita sem `HABBLAUD_ALLOWED_HOSTS`.

**Por que é aceitável e qual o risco.** O tráfego entre aparelhos da tailnet é cifrado pelo WireGuard. Senha e cookie, porém, vão sem TLS na camada HTTP, e o cookie sai sem `Secure`. O risco concreto: `100.64.0.0/10` também é faixa de CGNAT de operadoras. Com o Tailscale desligado no celular, o mesmo IP pode ser roteado pela operadora para outra máquina, que receberia senha e cookie. Por isso a regra abaixo exige VPN ativa, não só o IP.

**Regra de HTTP no app (release e debug):** HTTP só é aceito se as três condições valerem:

1. Host é **exatamente** o IPv4 literal que o usuário digitou e confirmou, e ele está em `100.64.0.0/10`. Qualquer outro host HTTP é recusado, mesmo dentro da faixa. Nomes como `*.ts.net` e IPv6 ficam fora nesta versão.
2. Consentimento explícito, uma vez por origem, guardado junto com ela: tela que diz "esta conexão não usa HTTPS; a segurança depende do Tailscale estar ligado neste celular; o app só confere que há alguma VPN ligada, não qual é; com o Tailscale desligado, senha e sessão podem ir para outra máquina". Sem aceite, nada é enviado. Trocar de origem pede de novo. A interface nunca exibe "Tailscale conectado"; sem VPN, diz "conecte o Tailscale".
3. Toda conexão HTTP fora do loopback sai por um `OkHttpClient` próprio com `socketFactory(vpnNetwork.socketFactory)`, onde `vpnNetwork` é o `Network` atual com `TRANSPORT_VPN` (obtido por `ConnectivityManager.registerNetworkCallback`). Assim o socket fica preso à VPN: se ela cair entre a checagem e a conexão, o socket falha em vez de sair pela rede móvel. Ao perder esse `Network` (`onLost` ou capacidades sem VPN), cancelar todas as chamadas desse cliente (`dispatcher.cancelAll()`, `connectionPool.evictAll()`) e não reconectar até voltar. Sem `Network` VPN: não enviar nada, nem login nem cookie, e mostrar "conecte o Tailscale".

A condição 3 impede o envio pela rede móvel quando a VPN cai, mas **não prova** que a VPN ativa é o Tailscale nem que o IP chega ao seu computador: qualquer VPN satisfaz a checagem, e `100.64.0.0/10` também é faixa de CGNAT de operadoras. Não mostrar na interface nada que afirme o contrário. A garantia de destino vem do consentimento e de o usuário manter o Tailscale ligado. Redirect de HTTPS para HTTP continua recusado. Migrar para HTTPS (abaixo) remove esse risco.

**Depois (fora deste plano, opcional):** HTTPS sem mudar o app. O app só precisa da URL nova; o trabalho é do operador, com autorização separada. Caminhos:

**B. Internet pública via proxy reverso (alvo; ex.: o Traefik que já atende os tenants, `plans/020-login-remoto-freio-por-origem-e-cookie-secure.md:27-39,237-246`).** Exige:

1. Rota HTTPS com certificado público para um nome dedicado, apontando **somente** para o listener remoto (`100.83.131.11:4747`). Nunca para `127.0.0.1:4747`: a porta local não tem login.
2. Nome público em `HABBLAUD_ALLOWED_HOSTS`; sem isso o guard responde 403 de Host.
3. `HABBLAUD_REMOTE_TRUST_PROXY` com o IP exato do proxy **como o Habblaud o vê**, medido como no plano 020. Se o proxy e um nó qualquer da tailnet aparecerem com o mesmo endereço, não ligar a variável: STOP e revisar.
4. `HABBLAUD_REMOTE_INSECURE_HTTP=1` pode continuar só para o salto proxy→Habblaud.

**A. Tailscale Serve (opcional, teste antecipado; não atende acesso sem Tailscale).** O host já tem HTTPS do Tailscale para `homex.tail2a824e.ts.net` (`CertDomains`). Em 2026-11-02, `tailscale serve status` mostrou só encaminhamentos TCP (8080, 8087, 8092–8095) e nenhum HTTPS em 443; `ss` não mostrou nada escutando em 443. Passos do operador: reconferir `tailscale serve status` e a porta 443; `tailscale serve --bg --https=443 http://100.83.131.11:4747`; acrescentar `HABBLAUD_ALLOWED_HOSTS=homex.tail2a824e.ts.net` em `~/.habblaud/remote.env` (o quadlet lê esse arquivo) e reiniciar só `habblaud.service`. Rollback: `tailscale serve --https=443 off`, remover a linha e reiniciar o serviço. O celular precisa do app Tailscale conectado. O freio de login vê todos vindos do mesmo endereço (sem `TRUST_PROXY`), aceitável porque só a tailnet alcança.

O app é o mesmo nos dois caminhos: só muda a URL digitada.

Aceite do pré-requisito (pelo operador, sem senha errada contra produção, registrado em `plans/034-resultado.md`): certificado válido sem `-k`; `curl -s -o /dev/null -w '%{http_code}' https://<nome>/api/access` → 401; `POST /api/remote/logout` JSON sem Origin → 200; `https://<nome>` não alcança a porta local (login exigido). No caminho B, após um login autorizado no navegador, `Set-Cookie` com `Secure`. Freio por origem, `X-Forwarded-For` forjado e `Secure` atrás de proxy já têm testes em `server/http/remote.test.ts:247-256,792-815`; o plano não cria teste novo de freio. A parte específica do caminho B é medir o IP do proxy (passo 3); nunca provocar 429 no endpoint real.

## Desenho técnico mínimo

### Projeto e dependências

Criar `android/` na raiz, um único módulo `:app`, namespace/applicationId `br.com.xlinx.habblaud`, Activity única, Material 3 e injeção manual por construtor. Não criar módulos de domínio, Hilt, KMP, Retrofit, banco de transcripts ou gerador TypeScript/Kotlin.

Baseline deliberadamente conservadora: AGP 9.0.1, Gradle Wrapper 9.1.0 com checksum, JDK 17, compileSdk/targetSdk 36, minSdk 26. AGP 9 já integra Kotlin; não aplicar `org.jetbrains.kotlin.android` junto. Usar plugin Compose compatível com a versão Kotlin efetivamente resolvida. A tabela oficial de compatibilidade está nas referências finais. Se uma dependência exigir outra baseline, parar e revisar a escolha, em vez de misturar plugins ou instalar versões preview.

Fixar versões exatas em `android/gradle/libs.versions.toml`: Compose BOM estável compatível com SDK 36, Activity Compose, Lifecycle ViewModel/runtime Compose, Kotlin coroutines, kotlinx.serialization, Preferences DataStore, OkHttp e okhttp-sse. MockWebServer apenas em testes. Resolver os números estáveis no passo 1 e registrá-los no catálogo, sem `+`, intervalos, snapshots ou dependências recém-lançadas sem necessidade. Não há versões Android herdadas neste repo.

Usar texto nativo selecionável inicialmente: preservar Markdown como texto, quebras, listas e cercas de código, sem parser artesanal, HTML, imagens remotas ou WebView. Código/resultados em fonte monoespaçada e painel expansível; copiar texto deve preservar os caracteres. Renderização rica de Markdown não é requisito desta entrega.

### Arquivos novos previstos

Todos os caminhos Kotlin abaixo são relativos a `android/app/src/main/java/br/com/xlinx/habblaud/`:

- `MainActivity.kt`, `HabblaudApp.kt`: composição e navegação simples Conexão → Sessões → Conversa.
- `data/ApiModels.kt`: DTOs somente dos campos consumidos e chave de sessão.
- `data/HabblaudApi.kt`: OkHttp, JSON, erros e operações HTTP existentes.
- `data/SessionCookieStore.kt`: CookieJar por origem e persistência cifrada.
- `data/LocalStateStore.kt`: DataStore de URL, última sessão, rascunhos e operações pendentes.
- `data/SessionRepository.kt`: snapshot, recentes, acesso e resolução do agente vivo.
- `data/ConversationRepository.kt`: SSE, janela, reconexão e cancelamento.
- `data/MessageRepository.kt`: upload por stream, envio, controle e consulta de status.
- `ui/ConnectionScreen.kt`, `ui/SessionsScreen.kt`, `ui/ConversationScreen.kt`, `ui/PermissionSheet.kt`, `ui/HabblaudViewModel.kt`.

Não separar mais classes por padrão arquitetural. Extrair componentes menores só quando o tamanho/uso real justificar, dentro destas pastas. Testes correspondentes em `android/app/src/test/` e `android/app/src/androidTest/`.

### Identidade, estados e persistência

- `SessionKey = (origem normalizada pela política de URL, provider, account, sessionId)`. Não concatenar separadores ambíguos; serializar campos. Origem inclui esquema e porta: `http://100.83.131.11:4747` e uma futura URL HTTPS são servidores diferentes, com login próprio. Aceitar URL sem userinfo, query ou fragmento; inicialmente caminho `/` apenas. Rejeitar subpath de proxy com mensagem explícita, sem remover silenciosamente o caminho.
- Reunir agentes principais do snapshot e recentes pela chave; principais ativos que não aparecerem na janela histórica continuam listados. Subagentes ficam como atividade/detalhe, não como destinatário editável.
- O snapshot é autoritativo sobre agente vivo. Antes de enviar, verificar se o `agentId` ainda aponta para o mesmo `account/provider/sessionId`. `/clear`, retomada ou troca de agente nunca transfere rascunho ou anexos para outra sessão implicitamente. Se mudar durante upload, abortar o POST e preservar o rascunho para revisão. Mesmo após a checagem local, enviar `expectedSessionId`: somente a comparação síncrona no servidor fecha a corrida anterior ao enqueue.
- `/clear` com a conversa aberta: o stream do mesmo `agentId` emite novo `init` da sessão nova (`server/http/terminal.ts:136-141`), e `TerminalInit` não traz `sessionId`. Quando o snapshot mostrar outro `sessionId` para o `agentId` aberto, ou chegar `init` fora de reconexão, fechar a vista da chave antiga com aviso "a sessão mudou". O rascunho fica na chave antiga. Abrir a nova sessão exige toque do usuário. Recebido `code: 'session_changed'`, aplicar o mesmo fluxo.
- Sessão fechada: leitura pelo endpoint histórico, compositor indisponível. Não enviar à sessão ativa de outro agente como substituição.
- Estados separados: conexão, leitura e operação de envio. Texto visível anteriormente pode permanecer com selo de desatualizado ao perder rede; não parecer resposta ao vivo.
- DataStore guarda rascunho e metadados de URIs persistíveis, nunca transcript completo. Escrever fora da thread principal, de forma serializada. Uma gravação antiga não pode sobrescrever a mais recente. Salvar seleção/última sessão, preservando texto durante rotação e recriação.
- Antes de despachar POST de mensagem/controle, persistir uma operação local em andamento. Após 201 persistir ID/status antes de limpar o rascunho correspondente. Depois de morte do processo, operação sem ID é **resultado desconhecido**, não reenviar.
- Ao limpar rascunho após 201, comparar a revisão capturada: texto/anexos novos escritos durante upload permanecem.

### Rede, autenticação e segurança

- HTTPS com validação de cadeia e hostname padrão. Sem trust-all, sem host verifier permissivo, sem CA embarcada do usuário e sem certificado pinado de curta duração. `network_security_config` permite texto puro na base, porque o IP Tailscale escolhido pelo usuário não é fixo no build; a política de URL, o consentimento e o cliente preso ao `Network` VPN são a barreira e precisam de teste.
- Não enviar `Origin`, `Referer` nem cabeçalhos de proxy (`X-Forwarded-*`). POST de API usa `Content-Type: application/json`, exceto upload binário. Host é o do URL configurado.
- Não seguir redirects para outra origem ou downgrade; rejeitar redirect de API/login com explicação. Não transferir cookies entre hosts, portas ou instalações. Cookies HTTP são normalmente por host, portanto a restrição adicional por origem precisa estar no CookieJar do app.
- Reutilizar o cookie `habblaud_remote`, respeitando expiração e flags; guardar somente esse cookie para a origem selecionada. Senha fica apenas na tela até terminar a tentativa, sem persistência.
- Cifrar cookie em arquivo privado com AES-GCM e chave Android Keystore. IV novo por gravação; não implementar algoritmo próprio. Chave inválida/arquivo corrompido pede login, sem crash. Desabilitar backup/transferência do estado sensível e excluir cookie/rascunhos das regras aplicáveis de extração de dados.
- Sem logging de corpos, Cookie/Set-Cookie, senha, prompts, argumentos ou URIs de anexos. Diagnósticos locais mostram rota-modelo, status e classe do erro, não dados de sessão.
- 401 encerra SSE, descarta cookie e pede login; preservar rascunhos da origem para depois do login, sem reenviar. 403 informa recurso bloqueado; 429 de login respeita `Retry-After` sem loop.
- No logout online, pedir revogação; sempre apagar cookie e liberar memória sensível. Rascunhos da conta/origem são apagados somente no logout explícito ou troca de servidor, com confirmação se houver texto não enviado. Expiração de login não é logout explícito.
- Debug também permite HTTP em `localhost`/`127.0.0.1`, para a fixture via `adb reverse` (flag `ALLOW_LOOPBACK_HTTP`). Nunca aceitar HTTP de LAN comum. Não alterar guards/CORS/config de produção para facilitar testes.

### SSE e consumo de recursos

- No máximo um `/api/stream` com app visível e um stream de conversa da sessão selecionada. Não abrir stream por linha da lista; não baixar feed/notice para uma segunda lista.
- Lifecycle em STARTED inicia observação; STOPPED cancela streams, consultas periódicas e uploads ainda não despachados. Usar coleta lifecycle-aware e cancelamento dos Calls reais; `viewModelScope` sozinho não suspende rede no background.
- POST já despachado pode ter sido aceito mesmo quando cancelado. Registrar resultado desconhecido se a resposta não chegou. Voltar ao foreground só retoma GET/status; nunca POST automaticamente.
- SSE em dispatcher de I/O, com leitura compatível com ping de 15 s e detecção de conexão sem bytes por 45 s. Timeout infinito sem detecção de rede morta não é aceitável.
- Usar okhttp-sse para framing, inclusive múltiplas linhas `data:`, CRLF e comentários. `init` substitui a lista; `append` acrescenta por ID sem duplicar. Desconhecer variante/campo não derruba app: campos extras são ignorados; tipo novo aparece como entrada não suportada, sem registrar seu conteúdo.
- Reconectar GET com atraso inicial de 2 s, aumentando até 30 s, cancelável e respeitando 429/`Retry-After`; 401/403/404 não entram em loop. Reconexão entrega outro init, não replay.
- Proteger troca rápida A → B: cancelamento mais geração/chave da assinatura impede eventos atrasados de A entrarem em B.
- Lista em `LazyColumn` com chaves estáveis, atualização só por dados recebidos, sem relógio de recomposição a cada quadro. Atualizar contador de tempo no máximo uma vez por minuto enquanto visível, ou mostrar horário absoluto.
- Limitar janela local a 1.000 entradas ou 8 MiB de texto UTF-8, descartando as mais antigas com aviso de janela reduzida. Entrada/evento individual acima do limite gera erro explícito e mantém app responsivo; não truncar silenciosamente aprovação ou comando. Não copiar transcript inteiro a cada append.
- `truncated=true` mostra que existe conversa anterior não carregada. Não criar botão de paginação sem endpoint. Reabertura pode recuperar apenas a janela atual, não toda a conversa perdida.
- Não deslocar rolagem se o usuário estiver lendo acima. Mostrar indicador de novas entradas; seguir automaticamente somente se já estava no fim.

### Envio, anexos e controles

- Habilitar envio apenas para principal vivo com `access.messages && access.messageSessionGuard === true && canMessage`. Mensagens e controles sempre incluem `expectedSessionId` da chave capturada. Texto máximo 20.000 unidades UTF-16, sem trim destrutivo; vazio só vale com anexo.
- Capturar chave da sessão, agente, texto, revisão, modo e lote antes do primeiro await. Um envio por vez por sessão no app, sem impedir escrever o próximo rascunho.
- Enviar `deliverAs` explicitamente no OMP; omitir nos demais provedores. Escolha inicial aside, alterável no compositor e mantida no rascunho. Não assumir que preferência web é sincronizada.
- Upload sequencial por `ContentResolver.openInputStream`, `application/octet-stream`, `X-Habblaud-File-Name` com percent-encoding compatível com `encodeURIComponent` e `X-Habblaud-File-Type` MIME. Não usar `URLEncoder` sem corrigir a semântica de espaço/`+`.
- Usar Storage Access Framework (`OpenMultipleDocuments`), com leitura persistível quando concedida. Nenhuma permissão ampla de armazenamento. Imagem também pode ser selecionada pelo mesmo seletor; câmera e share target ficam fora do escopo.
- Não carregar arquivo inteiro em ByteArray/base64. Tamanho conhecido vai em Content-Length; desconhecido usa stream com contagem e limite de 15 MiB. Arquivo vazio, excesso, URI revogada e nome vazio viram erro visível antes do envio da mensagem sempre que detectáveis. MIME desconhecido não é erro: o servidor o troca por `application/octet-stream` (`attachments.ts:67`).
- Corpo de erro pode não ser JSON: guard 403/415 responde `text/plain` (`guard.ts:96-105`). O DTO de erro aceita os dois. 429 de `MAX_STREAMS` do terminal não traz `Retry-After`: usar o backoff de reconexão.
- Cinco arquivos no máximo; cancelar seleção não altera lote. Remover anexo libera acesso persistido quando não for mais usado. URI sem permissão persistível exige nova seleção após morte do processo, sem substituir por caminho bruto.
- Guardar ID de upload só na tentativa atual; ele não é cache permanente nem anexo reutilizável. `attachmentsExpired=true` permite nova preparação explícita a partir das URIs; não reenviar automaticamente a mensagem.
- Após 201, acompanhar `/api/messages/:id` enquanto visível; intervalo de 2 s, parar em estado final. Persistir ID para retomar consulta no próximo foreground. 404 após reinício/retenção vira situação desconhecida, não falha comprovada de execução.
- Se socket cair/timeout/5xx sem confirmação de aceitação, preservar conteúdo com aviso **envio não confirmado; confira a conversa antes de reenviar**. OkHttp deve ter retries de transporte desativados para as operações mutáveis. Novo envio exige ação e confirmação do usuário; pode duplicar o primeiro.
- `delivered` significa aceito pela sessão/runtime, não lido nem concluído. Não inserir uma resposta de sucesso fictícia no transcript. Mostrar estado de envio separado das entradas recebidas.
- Interromper mostra pedido em andamento até ack; não garantir efeito imediato nem cancelamento de shells externos. OMP sem `abort` retorna erro da extensão, que deve permanecer visível.
- Aprovação abre folha com projeto/agente/ferramenta, input e prazo. Buscar detalhe ao abrir e não decidir pedido vencido. Oferecer allow/deny/terminal; em Claude, perguntas e sugestões quando presentes. OMP/Codex não recebem `interrupt` ou `suggestion`.
- `AskUserQuestion` preserva índices, single/multi-select e opção livre; nunca enviar texto de opção como identificador. 404/409 após resposta por outro cliente encerra a folha e atualiza estado, sem reaplicar a decisão.

## Escopo de arquivos do executor

**Novos:** `android/**`; `scripts/android-smoke.ts`; `server/test/android-fixture.ts`; workflow `.github/workflows/android.yml`; `plans/034-resultado.md` somente depois das verificações.

**Alterações permitidas:** `.gitignore` para artefatos Android e segredos locais (`android/**/build/`, `android/.gradle/`, `android/local.properties`, `*.jks`, `*.keystore`, `keystore.properties`); `README.pt-BR.md` e `server/README.md` para instruções Android e extensão do contrato; `CHANGELOG.md`; linha 034 e notas correspondentes de `plans/README.md`; `server/tsconfig.json` somente para incluir `../scripts/android-smoke.ts` no typecheck. No backend, somente `shared/types.ts` (`AccessInfo`), `server/http/app.ts` (`/api/access`), `server/messages/registry.ts` (destinatário esperado), `server/messages/http.ts` (409 com `code` e comentário do contrato) e os testes correspondentes em `server/messages/registry.test.ts`, `server/messages/http.test.ts`, `server/http/http.test.ts`, `server/http/remote.test.ts`. Fixture pode importar helpers existentes de `server/test/`, sem alterar sua semântica.

**Não alterar:** `mod/`, `client/`, autenticação, guards, entrega/fila além da checagem de destinatário, fontes de sessões, catálogos de tradução, outros protocolos e configurações de deploy; planos existentes além do índice. Se for necessária outra mudança de produção, parar e pedir revisão de escopo.

`server/test/android-fixture.ts` pertence ao laboratório, não pode ser importado pelo servidor de produção. A mudança de backend é somente a proteção de sessão descrita acima, não uma API móvel paralela. Fixture e script não usam `tr()`: `npm run i18n:check -- --strict` varre `server` e `scripts` (`scripts/i18n-check.ts:12`).

## Comandos e gates

Os comandos npm abaixo existem e foram conferidos no `package.json`; não foram todos executados nesta investigação. Comandos Android e do laboratório são o contrato que o executor deve criar, não resultados já observados.

| Finalidade | Comando a partir da raiz | Resultado exigido |
|---|---|---|
| Tipos do repo | `npm run typecheck` | exit 0 |
| Proteção de sessão | `npm test -- server/messages/registry.test.ts server/messages/http.test.ts server/http/http.test.ts server/http/remote.test.ts` | exit 0; comparação de destinatário antes do enqueue comprovada |
| Testes do repo | `npm test` | relatório completo; investigar regressões, sem esconder falhas |
| Traduções web | `npm run i18n:check -- --strict` | exit 0 |
| Build web/servidor | `npm run build` | exit 0; app Android não altera distribuição existente |
| Baseline Android | `android/gradlew -p android --version` | Gradle 9.1.0; JVM ≥ 17 (o ambiente tem 21) |
| Unidade Android | `android/gradlew -p android :app:testDebugUnitTest` | exit 0, casos de contrato/estado passam |
| Lint e APK | `android/gradlew -p android :app:lintDebug :app:assembleDebug` | exit 0; APK em `android/app/build/outputs/apk/debug/app-debug.apk` |
| Fixture isolada | `npx tsx scripts/android-smoke.ts --port 14748 --allow terminal,permissions,messages` | processo anuncia pronto, sem ler sessões/config/credenciais reais; `--allow` aceita o formato de `HABBLAUD_REMOTE_ALLOW` |
| Encaminhar ao aparelho | `adb reverse tcp:14748 tcp:14748` | exit 0; fixture acessível como `http://127.0.0.1:14748` no debug |
| Instalar debug | `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` | `Success` |
| Instrumentação | `android/gradlew -p android :app:connectedDebugAndroidTest` | exit 0 em emulador/dispositivo escolhido |
| APK de entrega | `android/gradlew -p android :app:assembleRelease` | APK assinado com chave do operador; sem chave, falha com mensagem explícita |
| Política HTTP do release | `android/gradlew -p android :app:testReleaseUnitTest --tests '*UrlPolicyTest*' --tests '*VpnGuardTest*'` | exit 0: HTTP aceito só para o IP confirmado em `100.64.0.0/10`, com consentimento e VPN; recusado para outro IP da faixa, sem consentimento, sem VPN, em `localhost` (flag false no release), em IPv6 e em redirect HTTPS→HTTP |

A suíte completa teve timeouts registrados anteriormente em `server/world/uplink.test.ts` no plano 033. Não tratá-los como causados pelo Android nem declarar suíte verde sem resultado. Se aparecerem novamente, registrar a falha e investigar antes do aceite; não repetir o mesmo check apenas para tentar obter verde.

## Execução em etapas

### 0. Proteger o destinatário no backend existente

Pré-condição: o plano, a linha 034 e o 033 existem só na cópia local, sem commit. Não commitar nem fazer cherry-pick deles. Recebê-los como handoff: copiar `plans/034-cliente-android-produtivo.md` e só a linha 034 do índice para a worktree, sem commitar. Ao devolver, aplicar somente o diff de implementação no clone; não sobrescrever o `plans/README.md` nem o `CHANGELOG.md` locais, que têm alterações alheias.

Criar a worktree/branch indicada na etapa 1 antes de editar. Implementar somente `expectedSessionId`, o resultado `session-changed` e `messageSessionGuard` conforme a seção de contrato, preservando consumidores que omitem o campo. Reutilizar `ERR_SESSION_CHANGED` para não criar mensagem traduzível nova.

Nos testes existentes, cobrir: valor ausente, correto, divergente, vazio, objeto, null e tamanho excessivo; agente muda de sessão depois da leitura do cliente e antes do POST; 409 com `code: 'session_changed'` e outros 409 sem `code`; nenhuma Entry criada e nenhum ID de anexo consumido no 409; controle stop não entregue à nova sessão; expectativa não encaminhada no objeto `control`. Em `server/http/remote.test.ts` (harness com `createRemoteGate`, `:535-552`): `/api/access` remoto com e sem `messages` em allow anuncia `messageSessionGuard` só no primeiro. Verificar também que a mudança posterior ao enqueue continua falhando pelos checks já existentes.

**Gate:** `npm test -- server/messages/registry.test.ts server/messages/http.test.ts server/http/http.test.ts server/http/remote.test.ts`, `npm run typecheck` e `npm run i18n:check -- --strict` saem 0. A fixture das próximas etapas deve exercitar esse guard por HTTP real; não basta teste de DTO Android.

### 1. Preparar ambiente e projeto Android reproduzível

1. Criar branch `advisor/034-cliente-android-produtivo` em worktree isolada; preservar alterações locais existentes. Mensagens de commit seguem `feat(android): ...` / `fix(android): ...`, como os commits `feat(settings)` e `fix(composer)` observados. Não fazer push.
2. Localizar SDK/JDK disponíveis antes de instalar. Se faltarem, obter autorização para instalação do SDK (`cmdline-tools`, `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`) e aceitação de licenças; não instalar em produção nem usar root sem necessidade. Gradle roda no JDK instalado (≥ 17); o código compila para bytecode 17 (`jvmTarget = 17`, `compileOptions` 17), sem exigir toolchain 17 baixada.
3. Bootstrap do Wrapper sem Gradle no PATH: baixar `https://services.gradle.org/distributions/gradle-9.1.0-bin.zip` e o `.sha256` publicado ao lado, conferir com `sha256sum`, extrair num diretório temporário e rodar `<extraído>/bin/gradle -p android wrapper --gradle-version 9.1.0 --gradle-distribution-sha256-sum <sha>`. Versionar `gradlew`, `gradlew.bat` e `gradle/wrapper/*`; apagar a cópia temporária. Catálogo com versões exatas de Kotlin/Compose/AndroidX/OkHttp. Não atualizar toolchain do servidor.
4. Criar Activity e navegação; manifest apenas com permissões necessárias de rede (`INTERNET`, `ACCESS_NETWORK_STATE`). Backup bloqueado para estado sensível. `buildConfigField("boolean", "ALLOW_LOOPBACK_HTTP", ...)`: true só em debug, false em release. A política de URL (função pura) aceita HTTPS sempre, HTTP só para o IPv4 confirmado em `100.64.0.0/10` com consentimento registrado e, só com a flag, `http://127.0.0.1`/`localhost`. HTTP fora do loopback usa o cliente preso ao `Network` VPN da regra acima. `SessionKey` usa a origem normalizada que a política devolver.
5. Assinatura: `signingConfigs.release` lê `HABBLAUD_KEYSTORE`, `HABBLAUD_KEYSTORE_PASSWORD`, `HABBLAUD_KEY_ALIAS` e `HABBLAUD_KEY_PASSWORD` de variáveis de ambiente ou de `~/.gradle/gradle.properties`, nunca de arquivo versionado. Sem eles, `assembleRelease` falha com mensagem que cita as variáveis.

**Gate:** comandos de baseline Android, lint e APK saem 0. Instalar e abrir Activity no dispositivo antes de seguir; tela de conexão precisa aparecer, não basta compilar.

### 2. Autenticar contra o servidor existente

Implementar `HabblaudApi`, `SessionCookieStore`, `LocalStateStore` e tela de conexão. Aplicar validação da URL, TLS, cookies por origem, 401/403/429, logout e erros de armazenamento conforme o contrato acima.

Construir fixture isolada em `server/test/android-fixture.ts`, exposta por `scripts/android-smoke.ts`. Antes de qualquer `import` do servidor (import dinâmico depois), o script cria `mkdtemp`, define `HOME` e `HABBLAUD_ATTACHMENTS_DIR` dentro dele e aborta se algum caminho usado resolver dentro do HOME real (`AttachmentStore` sem `dir` apaga subpastas em `$HOME/.habblaud/attachments/.pending`, `attachments.ts:33-53`). Passar `dir` explícito ao `AttachmentStore` e arquivo de segredo temporário ao `RemoteSessions`. Usar `createRequestGuard`, `createRemoteGate`, `createApiHandler` com `remote: { is: isRemoteRequest, allows }` (`app.ts:116-118`), `Office`, `Hub` e registries reais; porta de loopback 14748 e credenciais fictícias fixas `android-test` / `android-test`. Todas as requisições do app passam pelo gate remoto, com `--allow` lido por `parseAllow`. Reutilizar estrutura de `server/messages/http.test.ts:38-80`, sem importar um arquivo `.test.ts` que registre testes. Encerrar timers/sockets e apagar temporários em SIGINT/SIGTERM.

Interface de controle da fixture (stdin, uma linha por comando, resposta em uma linha JSON no stdout):

| Entrada | Efeito | Saída |
|---|---|---|
| flag `--allow <lista>` | travas remotas | — |
| flag `--no-session-guard` | `/api/access` omite `messageSessionGuard` (simula servidor antigo; só na fixture) | — |
| `stats` | — | `{"sse":n,"terminal":n,"posts":{"/api/messages":n,...},"lastHeaders":{"origin":null,...}}` |
| `clear <agentId>` | troca o `sessionId` do agente (novo transcript) | `{"ok":true,"sessionId":"..."}` |
| `drop-next-response` | aceita o próximo POST mutável e destrói o socket antes da resposta | `{"ok":true}` |
| `permission <agentId> <tipo>` | registra pedido real (`bash`, `question-single`, `question-multi`) | `{"ok":true,"id":"..."}` |
| `deliver <agentId>` / `fail <agentId>` | consumidor de inbox confirma ou recusa | `{"ok":true,"ids":[...]}` |
| `stop-streams` | encerra os SSE abertos (reconexão) | `{"ok":true}` |

A fixture deve servir o gate remoto e bloquear rotas locais da mesma forma que produção. Não criar backend falso exclusivo para a UI. A senha fictícia pode ser exibida no laboratório; credenciais reais nunca entram em logs.

**Gate:** `android/gradlew -p android :app:testDebugUnitTest --tests '*RemoteSessionTest*' --tests '*UrlPolicyTest*' --tests '*VpnGuardTest*'` sai 0 (`UrlPolicyTest` cobre a flag true e false; `VpnGuardTest` usa provedor de rede injetado e prova: sem `Network` VPN nenhuma requisição sai, nem login; `onLost` durante uma chamada a cancela; a VPN cair entre a validação e a abertura do socket faz a chamada falhar, sem sair por outra rede). No aplicativo instalado, login na fixture e GET `/api/access` funcionam; chamada sem login recebe 401; `stats` mostra `lastHeaders.origin` null. Provar TLS válido e rejeição de certificado/hostname inválido em testes de cliente, não desativando verificações. Com `--no-session-guard`, leitura funciona e `stats` mostra zero POST de mensagem/controle. Repetir com `--allow permissions` e `--allow none`: cada função da matriz de travas mostra o bloqueio correto, sem loop de 403.

### 3. Listar sessões e ler conversa estruturada

Implementar DTOs e repositories de sessão/conversa; ViewModel expõe StateFlow e cancela rede conforme lifecycle. Unir abertos/recentes, resolver projeto pelo snapshot quando possível e preservar identidade de sessão. A tela de sessões tem busca local e separação aberto/recente sem inventar metadados ausentes.

Semear fixture com contas/provedores fictícios, dois principais abertos e um transcript fechado. A pasta da conta fica no temporário, mas o transcript fechado precisa de `sessionId` UUID, título ou primeiro prompt e `cwd` fora de `/tmp` e de `tmpdir()` (ex.: `/fixture/projeto`): senão `isTestSession` o esconde de recentes (`server/http/sessions.ts:88-95`). Os arquivos devem ser parseados por `TerminalStreams` e parsers reais; incluir as seis variantes, ferramenta com resultado tardio e substituição de transcript. Sem rotas públicas de teste: controle só pelo stdin acima.

**Gate:** `android/gradlew -p android :app:testDebugUnitTest --tests '*SessionIdentityTest*' --tests '*ConversationStateTest*'` sai 0. Na Activity real, alternar entre as duas sessões, abrir a fechada, `stop-streams` e voltar do background; `clear <agentId>` fecha a vista antiga com aviso. `stats` cinco segundos após background mostra `sse` 0 e `terminal` 0; com a conversa aberta, no máximo 1 e 1. Colar as saídas no resultado.

### 4. Enviar instruções e preservar rascunhos

Implementar `MessageRepository`, compositor e persistência por chave/revisão. Incluir seletor OMP dos três modos com aviso honesto, estado de entrega e resultado desconhecido. A fixture usa o `MessageRegistry` real e um consumidor simulado de inbox para exercitar queued/sent/delivered/failed; isso prova transporte, não comportamento de runtime OMP.

**Gate:** `android/gradlew -p android :app:testDebugUnitTest --tests '*MessageSendTest*'` sai 0. No app instalado, escrever em A, alternar a B, recriar Activity e matar/reabrir processo: texto recuperado pertence somente a A. Com `drop-next-response`, enviar uma vez, reconectar e reabrir: `stats.posts["/api/messages"]` aumenta exatamente 1 e o app mostra "envio não confirmado". Com `clear <agentId>` entre a leitura e o envio: resposta 409 `session_changed`, nenhuma entrega na sessão nova (`deliver` devolve `ids` vazio) e rascunho preservado.

### 5. Anexar arquivos sem inflar memória

Integrar SAF, URIs persistíveis e RequestBody por stream. Mostrar seleção, remoção, progresso e erros. Não criar preview de imagem grande nem copiar arquivo para RAM. Se a origem da URI não garantir leitura após reinício, sinalizar necessidade de selecionar de novo.

**Gate:** `android/gradlew -p android :app:testDebugUnitTest --tests '*AttachmentUploadTest*'` sai 0. Cobrir arquivo de 1 byte, 15 MiB, maior que 15 MiB, tamanho desconhecido, vazio, nome com espaço/`+`/acentos, URI revogada e seis selecionados. Simular staging vencido e troca de A/B durante upload. No aplicativo, enviar arquivo/imagem reais para fixture e verificar bytes recebidos no diretório temporário, sem tocar projetos do usuário.

### 6. Aprovar e interromper sem prometer o que o provedor não faz

Implementar folha de aprovação e botão de interrupção OMP. A fixture registra pedido pelo `PermissionRegistry` real e consome controle via inbox real. Cobrir approve/deny/terminal, perguntas Claude single/multi/free-text e rejeição de sugestões/interrupt em OMP/Codex.

**Gate:** `android/gradlew -p android :app:testDebugUnitTest --tests '*PermissionDecisionTest*'` sai 0. Na Activity, responder pedidos criados por `permission` e observar seu encerramento na fixture.

Laboratório OMP real (interrupção): binário `/opt/omp-tenant/omp` (existe neste host; `omp` não está no PATH). Seguir o modelo do 033 (`plans/033-modos-de-envio-omp.md:162-170`): HOME e diretório de agente temporários, `--no-session`, provedor HTTP local determinístico que segura a resposta, extensão real do repo e a fixture como servidor Habblaud. Sem credenciais de provedor real. Com uma ferramenta lenta em andamento, enviar stop pelo app. Evidência exigida: ack `ok:true` do controle na inbox, evento de turno abortado no log da extensão e um envio seguinte entregue. Sem o binário ou se a extensão não carregar: registrar BLOCKED com a saída; não usar personagens existentes.

### 7. Medir no aparelho e fechar a distribuição

1. Adicionar `.github/workflows/android.yml` independente da matriz Node: Ubuntu, JDK 17 e SDK 36 fixados; executar testes locais, lint e assembleDebug. Fixar actions por SHA conforme o CI atual, sem mudar o workflow Node. Instrumentação roda em dispositivo/emulador local na entrega; não adicionar infraestrutura de emulador ao CI sem necessidade.
2. Atualizar README em português com build, login, uso pelo Tailscale (o app exige o Tailscale conectado para HTTP), limites de provedor/histórico e instalação. Atualizar changelog somente com comportamento comprovado.
3. Criar `plans/034-resultado.md` com comandos, versões, dispositivo/API/RAM/rede, checksums dos APKs, resultados e limites. Não incluir tokens, prompts reais ou chaves.
4. Medir app release e navegador atual no mesmo aparelho, mesma instalação, mesmas sessões e rede. Registrar abertura da lista, abertura da conversa, digitação/rolagem, memória e comportamento ao ficar 60 s em background. Fazer três rodadas por variante e relatar medianas, sem confundir emulador com aparelho real.
5. Alvos de aceitação do app: sem ANR/crash; digitação sem operações de rede/arquivo na thread principal; p95 de quadros abaixo de 32 ms numa rolagem reproduzível com 500 entradas; zero conexões de leitura/poll do app cinco segundos após background; retorno recupera sessão/rascunho. Medir com ferramentas Android (`dumpsys gfxinfo`/profiler/trace), anexar saída e explicar o método. Não usar métricas debug como prova de release.
6. Gerar APK release assinado com chave persistente do operador, fora do Git. Não inventar chave descartável de entrega nem registrar senhas em argumentos/logs. Se não houver chave disponível, concluir código/verificação possível e registrar distribuição bloqueada, sem anunciar entregue.
7. Verificar certificado de assinatura e checksum (`apksigner verify --verbose --print-certs` e `sha256sum` no APK). Instalar a release no aparelho com Tailscale conectado, fazer login em `http://100.83.131.11:4747` e observar lista/conversa. Desligar o Tailscale e confirmar que o app bloqueia sem enviar requisição. Não enviar prompts/aprovações a sessões reais sem autorização específica; ações mutáveis já foram provadas no laboratório.
8. Não publicar no servidor nem fazer sync de tenants automaticamente. A proteção de destinatário no backend precisa ser revisada e publicada pelo operador, mediante autorização separada, antes do aceite de envio real. Sem ela, o APK permanece somente leitura para mensagens/controle nesse servidor. O restante usa as rotas já existentes; configurações de autorização remota também ficam com o operador.

**Gate:** todos os comandos da tabela executados onde aplicáveis; fixture e processos temporários encerrados; APK release assinado instalado e cenário real observado. Atualizar linha 034 para DONE somente quando todos os critérios abaixo passarem.

## Testes permanentes exigidos

Colocar sob `android/app/src/test/java/br/com/xlinx/habblaud/`, usando JUnit e MockWebServer; relógio/dispatchers controlados, sem rede externa:

- `SessionIdentityTest`: conta/provedor/origem distintos não colidem; troca de sessionId no mesmo agente não transfere destinatário.
- `ConversationStateTest`: init substitui, resultado tardio associa ferramenta, append duplicado não duplica entrada, evento A atrasado não chega a B, janela/truncamento preservam aviso.
- `RemoteSessionTest`: cookie por origem/expiração, 401 cancela streams, redirect/downgrade recusados, 429 respeitado e TLS inválido recusado. Crypto/Keystore real fica na instrumentação, não mockar segurança e chamar isso de prova. O cliente HTTP preso à VPN é usado só para a origem HTTP confirmada; uma origem HTTPS usa o cliente padrão, e qualquer outra URL HTTP é recusada antes de abrir socket.
- `MessageSendTest`: 201 é aceitação, não conclusão; erro ambíguo não repete POST; reinício não reenvia; status perdido não marca execução inexistente; rascunho novo não some.
- `AttachmentUploadTest`: conteúdo binário exato, limite/tamanho desconhecido, percent-encoding do nome, seleção excessiva e anexos vencidos sem reenvio automático.
- `PermissionDecisionTest`: índices single/multi/free-text, pedido vencido, conflito e controles incompatíveis com provedor.

Instrumentação em `android/app/src/androidTest/java/br/com/xlinx/habblaud/` cobre Keystore/persistência, rotação/processo recriado quando possível, isolamento de rascunhos, entrada com IME, fonte ampliada e navegação de aprovação. `VpnGuardInstrumentedTest` prova o vínculo real do socket: no emulador, sem VPN, a chamada HTTP ao IP confirmado falha sem conectar (servidor de teste não recebe nada); a fábrica de socket usada é a do `Network` obtido, não a padrão. Unidade com estado de rede simulado não substitui esse teste. Se o emulador não permitir VPN de teste, registrar o limite e fazer a prova de queda (Tailscale desligado antes e durante uma chamada lenta) no aparelho na etapa 7. Não fixar textos literais, ordem incidental de campos JSON ou implementação interna. Screenshots e smoke real complementam testes; não são substituídos por mocks.

## Critérios de pronto

- [ ] APK Android nativo realiza login remoto e usa o backend existente, sem WebView/PTY nem backend móvel paralelo.
- [ ] Lista abertos/recentes, lê conversa viva e histórica, exibe limite de janela e estados sem enganar.
- [ ] Envia instrução/anexos, acompanha aceitação e preserva rascunhos após rotação/reabertura.
- [ ] Resposta perdida e morte de processo não provocam reenvio automático.
- [ ] Trocar sessão durante upload/reconexão não entrega conteúdo ao destinatário errado.
- [ ] Backend recusa `expectedSessionId` divergente antes de consumir anexos/enfileirar mensagem ou controle; app antigo continua compatível e servidor antigo não recebe operações inseguras do Android.
- [ ] Aprovações e interrupção OMP funcionam no laboratório com limites por provedor expostos.
- [ ] TLS/cookies/backup/logs respeitam as restrições; HTTP só para o IP Tailscale confirmado, com consentimento e VPN ativa (`UrlPolicyTest`, `VpnGuardTest` e prova no aparelho com Tailscale desligado registrados).
- [ ] Testes Android, lint, build, instrumentação e gates Node têm resultado registrado; nenhuma falha escondida.
- [ ] Medição em aparelho real atingiu os alvos definidos; comparação com navegador documentada sem prometer ganho não observado.
- [ ] APK release assinado, checksum e certificado registrados; instalação e leitura reais pelo Tailscale verificadas.
- [ ] README/changelog e resultado atualizados, sem segredos; somente arquivos em escopo alterados.
- [ ] Fixture temporária encerrada, produção e tenants intactos; linha 034 atualizada no índice.

## STOP conditions específicas

- O telefone alvo tem API menor que 26: revisar compatibilidade antes de mudar minSdk silenciosamente.
- Único acesso disponível exige CA privada, HTTP fora do Tailscale, nome `*.ts.net` em HTTP, proxy com subpath ou login diferente do gate remoto: revisar onboarding; não contornar TLS/guard.
- Rota pública apontaria para a porta local, ou o IP do proxy (caminho B) não se distingue de nós da tailnet: não prosseguir com a prova real; revisar com o operador.
- `HABBLAUD_REMOTE_ALLOW` do alvo não libera `terminal`, `messages` ou `permissions`: app mostra o bloqueio da função; não aumentar permissões automaticamente.
- Dados reais não cabem nos contratos de janela/tipos ou exigem endpoint novo para requisito obrigatório: relatar amostra sanitizada e revisar escopo.
- OMP real não suporta interrupção/entrega requerida com extensão carregada: preservar erro, não substituir por matar processo ou teclado PTY.
- SDK/licenças, dispositivo de prova ou chave de assinatura não estão disponíveis: registrar pré-requisito ausente; não chamar build debug ou emulação de entrega final.
- Necessidade de editar extensão, cliente web, arquivos alheios ou backend além da proteção de destinatário expressamente prevista: interromper essa etapa e pedir revisão do plano.

## Manutenção e escolhas rejeitadas

- Reaproveitar o aplicativo web em WebView foi rejeitado: mantém a superfície que motivou esta solicitação.
- Reescrever o backend, criar API `/mobile` ou bearer token foi rejeitado: login, conversa e controles já existem.
- Compatibilidade integral de terminal foi rejeitada: conversa estruturada não é shell; programas interativos continuam fora deste app.
- Sincronização offline, notificações com app fechado e histórico completo exigem contratos extras; não foram disfarçados como funcionalidades prontas.
- Mudanças em `shared/types.ts`, modos de envio e autenticação precisam revisar DTOs/testes Android. Não exigir correspondência de todos os campos do escritório: o app consome apenas um subconjunto.
- Mudança de origem/cookie, identidade de sessão, lifecycle e morte de processo são os pontos de maior risco na revisão. Não otimizar desempenho removendo essas verificações.

## Referências de plataforma consultadas

- [AGP 9.0: compatibilidade, Kotlin integrado, JDK e Gradle](https://developer.android.com/build/releases/agp-9-0-0-release-notes).
- [Lifecycle e coroutines no Compose](https://developer.android.com/topic/libraries/architecture/coroutines): coleta de estado lifecycle-aware não substitui cancelamento de rede do repository.
- [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config): confiança de CA e restrições de tráfego.
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore): proteção de chaves e operações fora da thread principal.
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files): acesso por URI e concessões, sem permissão ampla.
- [Testes Android pela linha de comando](https://developer.android.com/studio/test/command-line).

## Limites desta investigação

Investigação focada no plano solicitado, não auditoria geral das nove categorias do repo. Foram conferidos contratos de sessões, SSE, mensagens/controle, permissões, anexos, autenticação, CI e documentação relacionada. Não houve implementação, instalação de SDK, benchmark Android, build de APK, teste real de CA/cookie Android, auditoria integral de parsers/World/arte nem ações mutáveis na produção.
