import http from 'node:http';
import { appendFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { basename, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { OMP_SESSION_ID } from '../../shared/omp-events';
import { AccountsService } from '../accounts/service';
import { CastLauncher } from '../cast/launcher';
import { resolveRoots } from '../files/browse';
import { createAsideRoutes, handleAsideProgress } from '../asides/http';
import { AsideStore } from '../asides/store';
import { createApiHandler } from '../http/app';
import { createRequestGuard } from '../http/guard';
import { createRemoteGate, isRemoteRequest, loadSecret, parseAllow, RemoteSessions } from '../http/remote';
import { Hub } from '../http/sse';
import { TerminalStreams } from '../http/terminal';
import { setQuiet } from '../log';
import { createMessageRoutes } from '../messages/http';
import { MessageRegistry } from '../messages/registry';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { createPermissionRoutes } from '../permissions/http';
import { PermissionRegistry } from '../permissions/registry';
import { hashPassword, parsePasswordHash } from '../remote/password';
import { openMainAgent, SessionHistory } from '../sources/history';
import { OmpHistory } from '../sources/omp/history';
import { createOmpTerminalParser } from '../sources/omp/terminal';
import { HistorySet } from '../sources/source';
import { encodeCwd } from '../sources/watcher';
import { ATTACHMENT_TTL_MS, AttachmentStore, writeAttachments } from '../spawn/attachments';
import { ZeroClawCast } from '../cast/zeroclaw';
import { ZeroClawDelivery } from '../messages/zeroclaw';
import { ZeroClawConnector } from '../sources/zeroclaw/connector';
import { ZeroClawHistory } from '../sources/zeroclaw/history';
import { ZeroClawMirror } from '../sources/zeroclaw/mirror';
import { ZeroClawSource } from '../sources/zeroclaw/source';
import { startTestGateway } from '../sources/zeroclaw/test-gateway';
import { Summarizer } from '../summaries/summarizer';
import { setNeutralBrand } from '../../shared/i18n';

const CWD = '/fixture/projeto';
const ACCOUNT = '.claude';
const OMP_ACCOUNT = '.omp';
const AGENTS = [
  { id: 'fixture:claude', sessionId: '00000000-0000-4000-8000-000000000001', provider: 'claude' as const, account: ACCOUNT },
  { id: 'fixture:omp', sessionId: '00000000-0000-4000-8000-000000000002', provider: 'omp' as const, account: OMP_ACCOUNT },
  { id: 'fixture:analise', sessionId: '00000000-0000-4000-8000-000000000004', provider: 'claude' as const, account: ACCOUNT },
  { id: 'fixture:revisao', sessionId: '00000000-0000-4000-8000-000000000005', provider: 'claude' as const, account: ACCOUNT },
  { id: 'fixture:interface', sessionId: '00000000-0000-4000-8000-000000000006', provider: 'claude' as const, account: ACCOUNT },
  { id: 'fixture:testes', sessionId: '00000000-0000-4000-8000-000000000007', provider: 'claude' as const, account: ACCOUNT },
];
const CLOSED = '00000000-0000-4000-8000-000000000003';
const ZC_LIVE = '00000000-0000-4000-8000-000000000008';
const ZC_CLOSED = '00000000-0000-4000-8000-000000000009';
const ZC_MODELS = [
  ...['fixture/model-legivel', 'fixture/model-alternativo'].map(model => ({ alias: `m_${createHash('sha256').update(model).digest('hex').slice(0, 12)}`, model })),
];

export async function startAndroidFixture(root: string, args: string[]): Promise<void> {
  let port = 14748;
  let allow: string | undefined;
  let filesDir: string | undefined;
  let noSessionGuard = false;
  let ompCastEnabled = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--port' && args[i + 1]) port = Number(args[++i]);
    else if (args[i] === '--allow' && args[i + 1]) allow = args[++i];
    else if (args[i] === '--files' && args[i + 1]) filesDir = args[++i];
    else if (args[i] === '--no-session-guard') noSessionGuard = true;
    else throw new Error(`Unknown or incomplete option: ${args[i]}`);
  }
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid port');
  const filesRoots = filesDir === undefined ? undefined : resolveRoots([{ path: resolve(filesDir), label: basename(resolve(filesDir)) }]);
  if (filesRoots?.length === 0) throw new Error('Invalid files directory: use an existing workspace directory accepted by the file browser');
  setQuiet(true);
  const accountDir = join(root, ACCOUNT);
  const ompDir = join(root, OMP_ACCOUNT);
  const paths = new Map<string, string>();
  const pendingResults = new Map<string, string>();
  const timers = new Set<NodeJS.Timeout>();
  const seed = (sessionId: string, provider: 'claude' | 'omp' = 'claude', late = false, cwd = CWD) => {
    const projectDir = provider === 'omp' ? join(ompDir, 'sessions', encodeCwd(cwd)) : join(accountDir, 'projects', encodeCwd(cwd));
    mkdirSync(projectDir, { recursive: true });
    if (provider === 'omp') {
      const path = join(projectDir, `2026-01-01T00-00-00-000Z_${sessionId}.jsonl`);
      const lines = [
        { type: 'title', title: 'Conversa OMP fictícia' },
        { type: 'session', id: sessionId, cwd, timestamp: new Date().toISOString() },
        { type: 'message', id: randomUUID(), timestamp: new Date().toISOString(), message: { role: 'user', content: [{ type: 'text', text: 'Pedido OMP fictício' }] } },
        ...Array.from({ length: 60 }, (_, index) => ({
          type: 'message', id: randomUUID(), timestamp: new Date().toISOString(),
          message: { role: index % 2 ? 'assistant' : 'user', content: [{ type: 'text', text: `Etapa fictícia OMP ${index + 1}.` }] },
        })),
        { type: 'message', id: randomUUID(), timestamp: new Date().toISOString(), message: { role: 'assistant', content: [{ type: 'text', text: 'Resposta OMP fictícia' }] } },
      ];
      writeFileSync(path, lines.map(line => JSON.stringify(line)).join('\n') + '\n');
      return path;
    }
    const path = join(projectDir, `${sessionId}.jsonl`);
    const base = () => ({ uuid: randomUUID(), timestamp: new Date().toISOString(), sessionId, cwd, isSidechain: false });
    const lines = [
      ...(sessionId === CLOSED ? [{ ...base(), type: 'custom-title', customTitle: 'Conversa encerrada fictícia' }] : []),
      ...(Number(sessionId.slice(-12)) >= 20 ? [{ ...base(), type: 'custom-title', customTitle: `Planejamento de ${cwd.split('/').pop()}` }] : []),
      { ...base(), type: 'user', message: { role: 'user', content: sessionId === AGENTS[0].sessionId ? 'Pedido fictício' : `Planejamento de ${cwd.split('/').pop()}` } },
      ...Array.from({ length: 16 }, (_, index) => ([
        { ...base(), type: 'user', message: { role: 'user', content: index === 0 ? 'Pode revisar **todas** as etapas do projeto fictício?' : `Pode revisar a etapa ${index + 1} do projeto fictício?` } },
        { ...base(), type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'text', text: index === 0 ? `# Relatório fictício

Uma revisão **completa** e *cuidadosa*, com \`código inline\`.

- Organização conferida
  - Arquivos fictícios revisados
  - Nenhum dado real
- Próximos passos documentados

1. Conferir a entrada
2. Revisar a saída

> Este é um exemplo de citação.

[Documentação de exemplo](https://example.com)

| Item | Estado |
| --- | --- |
| Revisão | Concluída |

\`\`\`sh
printf 'um exemplo fictício de linha longa para testar a rolagem horizontal no bloco de código\\n'
\`\`\`` : `Revisei a etapa ${index + 1}.\n\n- Organização conferida\n- Próximos passos documentados\n\n\`\`\`sh\nprintf 'exemplo fictício\\n'\n\`\`\`` }] } },
      ])).flat(),
      { ...base(), type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'tool_use', id: 'tool-list', name: 'Bash', input: { command: 'ls -la' } }] } },
      { ...base(), type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-list', content: Array.from({ length: 20 }, (_, i) => `arquivo-ficticio-${i + 1}.txt`).join('\n') }] } },
      { ...base(), type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'tool_use', id: 'tool-read', name: 'Read', input: { file_path: '/fixture/projeto/exemplo.txt' } }] } },
      { ...base(), type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-read', content: 'Conteúdo demonstrativo sem dados reais.' }] } },
      { ...base(), type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'thinking', thinking: 'Primeira linha do pensamento.\nSegunda linha do pensamento.\nTerceira linha do pensamento.' }, { type: 'text', text: 'Resposta fictícia' }, { type: 'tool_use', id: 'tool-fixture', name: 'Bash', input: { command: 'echo fixture' } }] } },
      { ...base(), type: 'system', subtype: 'informational', content: 'Aviso fictício' },
    ];
    const result = JSON.stringify({ ...base(), type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-fixture', content: 'Resultado fictício' }] } }) + '\n';
    writeFileSync(path, lines.map(line => JSON.stringify(line)).join('\n') + '\n' + (late ? '' : result));
    if (late) pendingResults.set(path, result);
    return path;
  };
  const projects = ['projeto', 'caderno', 'loja', 'biblioteca', 'jardim', 'viagem'];
  for (const [index, agent] of AGENTS.entries()) paths.set(agent.id, seed(agent.sessionId, agent.provider, agent.id === 'fixture:claude', `/fixture/${projects[index]}`));
  seed(CLOSED);
  for (let index = 0; index < 6; index++) seed(`00000000-0000-4000-8000-${String(index + 20).padStart(12, '0')}`, 'claude', false, `/fixture/${projects[index]}`);
  const accounts = new AccountsService({ dirs: [accountDir], home: root, env: {}, onChange: () => {} });
  accounts.setProviderAccounts('omp', [{ dir: ompDir, detected: { id: OMP_ACCOUNT, configDir: ompDir, short: 'OMP', name: 'OMP fixture', color: '#4aa8e8' } }]);
  const late: { messages?: MessageRegistry; permissions?: PermissionRegistry; launcher?: CastLauncher } = {};
  const office = new Office({ names: new NameStore(null), version: 'android-fixture', startedAt: Date.now(), accounts: s => accounts.list(s), sources: () => [], accountName: () => undefined, terminal: true, messages: () => late.messages?.reachable() ?? new Set(), permissions: () => late.permissions?.snapshot() ?? new Map(), castLauncher: () => late.launcher });
  for (const [index, agent] of AGENTS.entries()) office.addMain({ ...agent, cwd: `/fixture/${projects[index]}`, role: 'Agente principal', startedAt: Date.now() - index * 300_000, status: index % 2 ? 'working' : 'idle' });
  // Contexto e modelo do omp (vêm da extensão em produção) para o anel do campo de mensagem (60% = alerta) e o símbolo
  // do modelo junto do avatar aparecerem nas capturas.
  const ompAgent = office.get('fixture:omp');
  if (ompAgent) office.applyTranscript(ompAgent.id, { title: ompAgent.title, tasks: ompAgent.tasks, stats: ompAgent.stats, model: 'claude-opus-5-5', context: { tokens: 120_000, window: 200_000 } });
  if (office.putCast({ id: 'ana', name: 'Ana Fictícia', kind: 'main', seed: 1001, look: 'f' }, 'fixture:claude') !== 'ok' ||
      office.putCast({ id: 'bia', name: 'Bia Fictícia', kind: 'main', seed: 1002, look: 'f', last: { '/fixture/projeto': CLOSED } }) !== 'ok' ||
      office.putCast({ id: 'caio', name: 'Caio Fictício', kind: 'main', seed: 1003, look: 'm' }) !== 'ok' ||
      office.putCast({ id: 'davi', name: 'Davi Fictício', kind: 'main', seed: 1004, look: 'm' }, 'fixture:omp') !== 'ok') throw new Error('Could not create fictional cast');
  const gateway = await startTestGateway();
  gateway.options.abortFrame = true;
  const mirror = new ZeroClawMirror({ dataDir: root });
  let zeroclaw: ZeroClawSource;
  const connector = new ZeroClawConnector({ url: gateway.url, dataDir: root, metadataOf: id => mirror.metadataOf(id), beforeOpen: input => zeroclaw.prepare(input) });
  zeroclaw = new ZeroClawSource({ accounts, office, connector, mirror });
  accounts.setProviderAccounts('zeroclaw', [{ dir: mirror.root, detected: { id: 'zeroclaw', configDir: mirror.root, short: 'Z', name: 'Laboratório', color: '#5cc97b' } }]);
  for (const [sessionId, name, cast] of [[ZC_LIVE, 'Zélia Fictícia', 'zelia'], [ZC_CLOSED, 'Conversa ZeroClaw encerrada', 'zora']]) {
    const now = Date.now();
    gateway.sessions.push({ session_id: sessionId, agent_alias: ZC_MODELS[0].alias, name, created_at: now, last_activity: now, channel_id: 'web' });
    gateway.messages.set(sessionId, [{ role: 'user', content: 'Pedido ZeroClaw fictício' }, { role: 'assistant', content: 'Resposta ZeroClaw fictícia' }]);
    await mirror.setMetadata({ sessionId, agent: ZC_MODELS[0].alias, name, cwd: CWD, cast, firstAt: now, lastAt: now });
    await mirror.bootstrap(sessionId, gateway.messages.get(sessionId)!);
  }
  gateway.options.autoStart = false;
  gateway.onConnection = connection => {
    const existing = gateway.sessions.some(session => session.session_id === connection.sessionId);
    if (!existing) gateway.sessions.push({ session_id: connection.sessionId, agent_alias: connection.url.searchParams.get('agent'), name: connection.url.searchParams.get('name'), created_at: Date.now(), last_activity: Date.now(), channel_id: 'web' });
    connection.send({ type: 'session_start', session_id: connection.sessionId, resumed: existing, message_count: gateway.messages.get(connection.sessionId)?.length ?? 0, name: connection.url.searchParams.get('name') });
  };
  connector.onEvent(event => {
    if (event.type === 'message-sent') {
      gateway.states.set(event.sessionId, 'running');
      const messages = gateway.messages.get(event.sessionId) ?? [];
      messages.push({ role: 'user', content: event.content });
      gateway.messages.set(event.sessionId, messages);
    } else if (event.type === 'frame' && ['done', 'aborted'].includes(event.frame.type)) gateway.states.set(event.sessionId, 'idle');
  });
  if (office.putCast({ id: 'zelia', name: 'Zélia Fictícia', kind: 'main', seed: 1005, look: 'f', model: ZC_MODELS[0].alias }) !== 'ok' ||
      office.putCast({ id: 'zora', name: 'Zora Fictícia', kind: 'main', seed: 1006, look: 'f', model: ZC_MODELS[0].alias, last: { [CWD]: ZC_CLOSED } }) !== 'ok') throw new Error('Could not create ZeroClaw cast');
  await zeroclaw.start();
  await connector.openSession({ sessionId: ZC_LIVE, agent: ZC_MODELS[0].alias, name: 'Zélia Fictícia', cwd: CWD, cast: 'zelia', officeAgentId: 'fixture:zeroclaw' });
  await zeroclaw.flush();
  await connector.sendMessage(ZC_LIVE, 'Turno ZeroClaw fictício em andamento');
  gateway.states.set(ZC_LIVE, 'running');
  // Deliberately stale context proves clients hide untrusted ZeroClaw measurements.
  office.applyTranscript('fixture:zeroclaw', { ...office.get('fixture:zeroclaw')!, context: { tokens: 120_000, window: 200_000 } });
  const launcher = new CastLauncher({ dataDir: root, member: id => office.cast.get(id), agents: () => office.list(), account: id => ompCastEnabled && id === OMP_ACCOUNT ? ompDir : !id || id === ACCOUNT ? accountDir : undefined, changed: () => office.markDirty() });
  late.launcher = launcher;
  let runtime: 'omp' | 'zeroclaw' = 'omp';
  let catalogMode = 'normal';
  const delivery = new ZeroClawDelivery({ connector, isClosing: id => late.launcher?.isClosing(id) ?? false, onCancelSession: (id, reason) => late.messages?.cancelZeroClawSession(id, reason), onReplacementFailed: id => office.closeMain(id) });
  const zcLauncher = new CastLauncher({ dataDir: root, member: id => office.cast.get(id), agents: () => office.list(), account: () => undefined, changed: () => office.markDirty(),
    zeroclaw: new ZeroClawCast({ catalog: { models: ZC_MODELS, defaultModel: ZC_MODELS[0].alias }, home: '/fixture/home', connector: () => connector, delivery: () => delivery, messages: () => late.messages, account: () => zeroclaw.accountId, ready: () => true, afterOpen: () => zeroclaw.flush() }) });
  launcher.poll({ open: ['ana', 'davi'] });
  const presence = setInterval(() => launcher.ack({ results: [] }), 10_000);
  timers.add(presence);
  const hub = new Hub(office, { canAnswer: req => isRemoteRequest(req) && parseAllow(allow).has('permissions') });
  const stagingDir = join(root, 'attachments', '.pending');
  const deliveredDir = join(root, 'attachments', 'delivered');
  let attachmentTimeOffset = 0;
  const attachments = new AttachmentStore({ dir: stagingDir, now: () => Date.now() + attachmentTimeOffset });
  const messages = new MessageRegistry({ office, zeroclaw: delivery, attachments, presenceMs: Number.MAX_SAFE_INTEGER, writeAttachments: async files => ({ paths: await writeAttachments(deliveredDir, randomUUID(), files) }) });
  // Bate-papo paralelo: sem extensão do omp, a fixture faz o papel dela (busca, resposta em duas partes e fim).
  const asides: AsideStore = new AsideStore({ dir: null, office, discard: id => messages.discard(id), queue: (agentId, req) => {
    const sent = messages.aside(agentId, req);
    const sessionId = office.get(agentId)?.sessionId;
    if ('message' in sent && sessionId) {
      const answer = `Resposta paralela fictícia: ${req.question}`;
      setTimeout(() => {
        asides.settled(sent.message.id, 'delivered');
        asides.progress({ sessionId, turnId: req.turnId, text: answer.slice(0, 20), state: 'running' });
        setTimeout(() => asides.progress({ sessionId, turnId: req.turnId, text: answer, state: 'complete' }), 1_500);
      }, 500);
    }
    return sent;
  } });
  const permissions = new PermissionRegistry({ office, viewers: () => 1, answerLater: () => true, orphanMs: 300_000 });
  const permissionResults: Record<string, unknown> = {};
  const waitForPermission = (id: string): void => {
    const wait = permissions.wait(id, 25_000);
    if (!wait) return;
    void wait.result.then(outcome => {
      if (outcome.status === 'pending') waitForPermission(id);
      else permissionResults[id] = outcome;
    });
  };
  late.messages = messages;
  late.permissions = permissions;
  messages.start();
  permissions.start();
  hub.start();
  for (const agent of AGENTS) messages.inbox({ session: agent.sessionId, account: agent.account });
  const terminals = new TerminalStreams({ office, transcriptPathOf: id => zeroclaw.transcriptPathOf(id) ?? paths.get(id), parserFor: id => office.get(id)?.provider === 'omp' ? createOmpTerminalParser() : undefined, initEntries: 50 });
  const openAgentOf = (account: string, session: string) => openMainAgent(office.list(), account, session);
  const history = new HistorySet([
    new SessionHistory({ accounts: () => accounts.entries().filter(account => account.provider === 'claude'), openAgentOf }),
    new OmpHistory({ accounts: () => ompCastEnabled ? accounts.entriesOf('omp') : accounts.entries().filter(account => account.provider === 'omp'), openAgentOf }),
    new ZeroClawHistory({ connector, mirror, account: () => zeroclaw.accountId, openAgentOf }),
  ]);
  const allowed = parseAllow(allow);
  const summaryToken = join(root, 'summary-fixture-token');
  writeFileSync(summaryToken, 'fixture-only');
  gateway.responses.set('POST /v1/messages', { body: { content: [{ type: 'text', text: 'Resumo HTTP fictício sem Tiny.' }] } });
  const summaries = new Summarizer({ bin: () => undefined, agentDir: () => undefined, http: { url: gateway.url, model: 'fixture-summary', tokenFile: summaryToken, auth: 'bearer' } });
  let httpSummariesEnabled = false;
  const api = createApiHandler({ office, hub, accounts, sources: () => zeroclaw.sources(), version: 'android-fixture', inDocker: false, terminal: true, terminals, sessions: history, messages: createMessageRoutes(messages), asides: { page: createAsideRoutes(asides), progress: (req, res) => handleAsideProgress(req, res, asides) }, permissions: createPermissionRoutes(permissions), get castLauncher() { return late.launcher; }, get summaries() { return runtime === 'zeroclaw' || httpSummariesEnabled ? summaries : undefined; }, spawnFolders: async () => projects.map(name => ({ name, path: `/fixture/${name}`, lastAt: Date.now(), sessions: 1 })), browse: filesRoots ? { roots: () => filesRoots } : undefined, remote: { is: isRemoteRequest, allows: feature => allowed.has(feature) } });
  const passwordHashText = await hashPassword('android-test');
  const passwordHash = parsePasswordHash(passwordHashText);
  if (!passwordHash) throw new Error('Could not create fixture password');
  const sessions = new RemoteSessions(loadSecret(join(root, 'remote-secret')), { user: 'android-test', passwordHashText }, Date.now, join(root, 'remote-revoked'));
  const gate = createRemoteGate({ config: { user: 'android-test', passwordHash }, sessions, secure: false });
  const guard = createRequestGuard({ allowedHosts: new Set() });
  const localGuard = createRequestGuard({ looseLoopbackOrigin: true });
  const posts: Record<string, number> = { '/api/messages': 0 };
  const messageRequests: Array<Record<string, unknown>> = [];
  const controlRequests: Array<Record<string, unknown>> = [];
  const castRequests: Array<Record<string, unknown>> = [];
  const gets: Record<string, number> = {};
  const stats = () => ({ sse: hub.size, terminal: terminals.size, posts, gets, messageRequests, controlRequests, castRequests, lastHeaders, permissionResults, localAcks, castJobs: late.launcher?.jobs(), runtime, zeroclaw: { account: zeroclaw.accountId, agentId: 'fixture:zeroclaw', sessionId: office.get('fixture:zeroclaw')?.sessionId, closedSessionId: ZC_CLOSED, models: ZC_MODELS, gatewayRequests: gateway.requests } });
  let lastHeaders: Record<string, string | null> = {};
  let drop = false;
  const localRequests = new WeakSet<http.IncomingMessage>();
  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    lastHeaders = { origin: req.headers.origin ?? null, referer: req.headers.referer ?? null, host: req.headers.host ?? null, 'content-type': req.headers['content-type'] ?? null };
    if (req.method === 'POST') posts[url.pathname] = (posts[url.pathname] ?? 0) + 1;
    if (req.method === 'GET') gets[url.pathname] = (gets[url.pathname] ?? 0) + 1;
    if (req.method === 'POST' && (url.pathname === '/api/messages' || url.pathname === '/api/messages/control' || /^\/api\/cast\/[^/]+\/start$/.test(url.pathname) || url.pathname === '/api/cast/new')) {
      let body = '';
      req.on('data', (chunk: Buffer) => { if (body.length <= 131_072) body += chunk.toString(); });
      res.once('finish', () => {
        try {
          const value = JSON.parse(body) as Record<string, unknown>;
          const record = { ...value, status: res.statusCode, ...(url.pathname === '/api/messages' ? { attachments: Array.isArray(value.attachments) ? value.attachments.length : 0 } : {}) };
          if (url.pathname === '/api/messages') messageRequests.push(record);
          else if (url.pathname === '/api/messages/control') controlRequests.push(record);
          else castRequests.push({ ...record, path: url.pathname });
        } catch {
          if (url.pathname === '/api/messages') messageRequests.push({ status: res.statusCode, attachments: 0 });
        }
      });
    }
    if (req.method === 'POST') {
      const end = res.end.bind(res);
      res.end = ((...args: Parameters<typeof res.end>) => {
        if (drop && res.statusCode >= 200 && res.statusCode < 300) {
          drop = false;
          res.destroy();
          return res;
        }
        return end(...args);
      }) as typeof res.end;
    }
    if (!localRequests.has(req) && (guard(req, res) || gate(req, res, url))) return;
    if (url.pathname === '/api/cast/models' && catalogMode !== 'normal') {
      res.writeHead(catalogMode === 'error' ? 503 : 200, { 'Content-Type': 'application/json' }).end(JSON.stringify(catalogMode === 'error' ? { error: 'Catálogo indisponível na fixture' } : { provider: runtime, models: [], defaultModel: '' }));
      return;
    }
    const match = /^\/api\/agents\/([^/]+)\/terminal$/.exec(url.pathname);
    if (req.method === 'GET' && match && allowed.has('terminal')) {
      const path = paths.get(decodeURIComponent(match[1]));
      const result = path && pendingResults.get(path);
      if (path && result) {
        pendingResults.delete(path);
        const timer = setTimeout(() => {
          timers.delete(timer);
          appendFileSync(path, result);
        }, 1_000);
        timers.add(timer);
      }
    }
    if (noSessionGuard && url.pathname === '/api/access') {
      const writeHead = res.writeHead.bind(res);
      res.writeHead = ((status: number, headers: http.OutgoingHttpHeaders) => {
        delete headers['Content-Length'];
        return writeHead(status, headers);
      }) as typeof res.writeHead;
      const end = res.end.bind(res);
      res.end = ((chunk?: unknown) => {
        if (typeof chunk === 'string' || Buffer.isBuffer(chunk)) {
          const body = JSON.parse(String(chunk)) as Record<string, unknown>;
          delete body.messageSessionGuard;
          return end(JSON.stringify(body));
        }
        return end();
      }) as typeof res.end;
    }
    if (!api(req, res, url)) res.writeHead(404).end();
  });
  // Evita que o adb reverse entregue ao cliente um socket ocioso já fechado pelo timeout padrão de 5 s.
  server.keepAliveTimeout = 3_600_000;
  const listening = Promise.withResolvers<void>();
  server.once('error', listening.reject);
  server.listen(port, '127.0.0.1', () => { server.off('error', listening.reject); listening.resolve(); });
  try {
    await listening.promise;
  } catch (error) {
    messages.stop();
    permissions.stop();
    terminals.stop();
    hub.stop();
    throw error;
  }
  const remoteAddress = server.address();
  if (!remoteAddress || typeof remoteAddress === 'string') throw new Error('Remote fixture listener has no port');
  const localAcks: Array<{ id: string; ok: boolean }> = [];
  const localServer = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    if (localGuard(req, res)) return;
    if (req.method === 'POST' && url.pathname === '/api/mod/inbox/ack') {
      let body = '';
      req.on('data', (chunk: Buffer) => {
        if (body.length <= 65_536) body += chunk.toString();
      });
      res.once('finish', () => {
        if (res.statusCode !== 200 || body.length > 65_536) return;
        try {
          const parsed = JSON.parse(body) as { results?: unknown };
          if (!Array.isArray(parsed.results)) return;
          for (const result of parsed.results) {
            if (result && typeof result.id === 'string' && typeof result.ok === 'boolean' &&
                messages.get(result.id)?.status === (result.ok ? 'delivered' : 'failed')) {
              localAcks.push({ id: result.id, ok: result.ok });
            }
          }
        } catch {
          // The real handler rejects malformed JSON.
        }
      });
    }
    if (url.pathname === '/api/fixture/stats' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(stats()));
      return;
    }
    if (url.pathname === '/api/fixture/command' && req.method === 'POST') {
      let body = '';
      req.on('data', (chunk: Buffer) => { if (body.length <= 65_536) body += chunk.toString(); });
      req.on('end', () => {
        void (async () => {
          try {
            const value = JSON.parse(body) as { command: string };
            const result = await fixtureCommand(value.command);
            res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
          } catch {
            res.writeHead(400).end(JSON.stringify({ error: 'Invalid fixture command' }));
          }
        })();
      });
      return;
    }
    // Browser proxy uses this local listener; share production handlers and request capture.
    localRequests.add(req);
    server.emit('request', req, res);
  });
  const localListening = Promise.withResolvers<void>();
  localServer.once('error', localListening.reject);
  localServer.listen(0, '127.0.0.1', () => { localServer.off('error', localListening.reject); localListening.resolve(); });
  try {
    await localListening.promise;
  } catch (error) {
    server.closeAllConnections();
    server.close();
    messages.stop();
    permissions.stop();
    terminals.stop();
    hub.stop();
    throw error;
  }
  const localAddress = localServer.address();
  if (!localAddress || typeof localAddress === 'string') throw new Error('Local fixture listener has no port');
  const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
  const fixtureCommand = async (line: string): Promise<unknown> => {
      const [command, agentId, kind] = line.trim().split(/\s+/);
      let result: unknown;
      if (command === 'stats') result = stats();
      else if (command === 'seed-usage') {
        const now = Date.now();
        accounts.setUsage(OMP_ACCOUNT, { source: 'omp', fetchedAt: now, fiveHour: { utilization: 60, resetsAt: now + 3_600_000 }, sevenDay: { utilization: 100, resetsAt: now + 86_400_000 } });
        office.markDirty();
        result = { ok: true, fetchedAt: now };
      }
      else if (command === 'seed-subagents') {
        const parent = office.get(agentId);
        if (!parent || parent.kind !== 'main') throw new Error('Expected seed-subagents <mainAgentId>');
        for (const role of ['task', '__advisor']) {
          const id = `${agentId}:${role}`;
          office.addSub({ id, parentId: agentId, sessionId: parent.sessionId, role, background: false, startedAt: Date.now() });
          const path = paths.get(agentId);
          if (path) paths.set(id, path);
        }
        result = { ok: true };
      }
      else if (command === 'seed-closed-omp') {
        if (!agentId || !OMP_SESSION_ID.test(agentId) || kind) throw new Error('Expected seed-closed-omp <sessionId>');
        ompCastEnabled = true;
        paths.set(agentId, seed(agentId, 'omp'));
        result = { ok: true, sessionId: agentId, account: OMP_ACCOUNT, project: CWD };
      }
      else if (command === 'roles-seed') {
        const roles: unknown = JSON.parse(line.trim().slice(command.length).trim());
        if (!roles || typeof roles !== 'object' || Array.isArray(roles)) throw new Error('Expected roles-seed <JSON object>');
        launcher.poll({ roles });
        result = { ok: true, ...launcher.roles() };
      }
      else if (command === 'runtime') {
        if (agentId !== 'omp' && agentId !== 'zeroclaw') throw new Error('Expected runtime omp|zeroclaw');
        runtime = agentId;
        late.launcher = runtime === 'zeroclaw' ? zcLauncher : launcher;
        catalogMode = 'normal';
        office.markDirty();
        result = { ok: true, runtime };
      } else if (command === 'catalog') {
        if (!['normal', 'empty', 'error'].includes(agentId)) throw new Error('Expected catalog normal|empty|error');
        catalogMode = agentId;
        result = { ok: true };
      } else if (command === 'brand') {
        if (agentId !== 'normal' && agentId !== 'neutral') throw new Error('Expected brand normal|neutral');
        setNeutralBrand(agentId === 'neutral');
        result = { ok: true };
      } else if (command === 'zc-context') {
        const agent = office.get('fixture:zeroclaw');
        if (!agent || !['none', '0', '120000'].includes(agentId)) throw new Error('Expected zc-context none|0|120000');
        office.applyTranscript(agent.id, { ...agent, context: agentId === 'none' ? undefined : { tokens: Number(agentId), window: 200_000 } });
        if (agentId === 'none') { delete agent.context; office.markDirty(); }
        result = { ok: true };
      } else if (command === 'zc-working' || command === 'zc-no-active' || command === 'zc-done') {
        const sessionId = office.get('fixture:zeroclaw')?.sessionId;
        if (!sessionId) throw new Error('No ZeroClaw session');
        if (command === 'zc-working') {
          await connector.sendMessage(sessionId, 'Turno ZeroClaw fictício');
          gateway.states.set(sessionId, 'running');
          gateway.aborts.delete(sessionId);
        } else if (command === 'zc-no-active') {
          gateway.states.set(sessionId, 'idle');
          gateway.aborts.set(sessionId, 'no_active_response');
          office.setStatus('fixture:zeroclaw', 'working');
        } else {
          gateway.states.set(sessionId, 'idle');
          gateway.connections.findLast(connection => connection.sessionId === sessionId && connection.open)?.send({ type: 'done', full_response: 'Resposta ZeroClaw fictícia' });
        }
        await zeroclaw.flush();
        result = { ok: true, sessionId };
      } else if (command === 'zc-reset') {
        for (const session of connector.openSessions()) await connector.closeSession(session.sessionId);
        await zeroclaw.flush();
        gateway.aborts.clear();
        gateway.states.clear();
        await connector.openSession({ sessionId: ZC_LIVE, agent: ZC_MODELS[0].alias, name: 'Zélia Fictícia', cwd: CWD, cast: 'zelia', officeAgentId: 'fixture:zeroclaw' });
        await zeroclaw.flush();
        await connector.sendMessage(ZC_LIVE, 'Turno ZeroClaw fictício em andamento');
        gateway.states.set(ZC_LIVE, 'running');
        office.applyTranscript('fixture:zeroclaw', { ...office.get('fixture:zeroclaw')!, context: { tokens: 120_000, window: 200_000 } });
        messageRequests.length = 0;
        controlRequests.length = 0;
        castRequests.length = 0;
        result = { ok: true, sessionId: ZC_LIVE };
      } else if (command === 'zc-close') {
        for (const session of connector.openSessions()) await connector.closeSession(session.sessionId);
        await zeroclaw.flush();
        result = { ok: true };
      }
      else if (command === 'cast-consume') {
        const jobs = launcher.poll();
        launcher.ack({ results: jobs.map(job => ({ id: job.id, ok: true })) });
        for (const job of jobs) {
          if (job.op === 'start') {
            const id = `fixture:cast:${job.cast}`;
            const sessionId = job.resume ?? randomUUID();
            // Como o auxiliar: sem `folder`, a sessão abre na pasta pessoal.
            const folder = job.folder ?? '/fixture/home';
            if (ompCastEnabled && job.agentDir === ompDir) {
              paths.set(id, (job.resume && paths.get(job.resume)) || seed(sessionId, 'omp', false, folder));
              office.addMain({ id, cast: job.cast, provider: 'omp', account: OMP_ACCOUNT, sessionId, cwd: folder, role: 'Agente principal', startedAt: Date.now(), status: 'idle' });
              messages.inbox({ session: sessionId, account: OMP_ACCOUNT });
              continue;
            }
            const existing = join(accountDir, 'projects', encodeCwd(folder), `${sessionId}.jsonl`);
            paths.set(id, job.resume && existsSync(existing) ? existing : seed(sessionId, 'claude', false, folder));
            office.addMain({ id, cast: job.cast, account: ACCOUNT, sessionId, cwd: folder, role: 'Agente principal', startedAt: Date.now(), status: 'idle' });
            messages.inbox({ session: sessionId, account: ACCOUNT });
          } else {
            const agent = office.list().find(item => item.cast === job.cast && item.status !== 'offline');
            if (agent) office.setStatus(agent.id, 'offline');
          }
        }
        launcher.tick();
        result = { ok: true, consumed: jobs, jobs: launcher.jobs() };
      }
      else if (command === 'drop-next-response') { drop = true; result = { ok: true }; }
      else if (command === 'expire-attachments') { attachmentTimeOffset += ATTACHMENT_TTL_MS + 1; result = { ok: true }; }
      else if (command === 'stop-streams') { terminals.stop(); hub.stop(); hub.start(); result = { ok: true }; }
      else if (command === 'bind-omp') {
        if (!agentId || !OMP_SESSION_ID.test(agentId) || kind) throw new Error('Expected bind-omp <sessionId>');
        const agent = office.get('fixture:omp');
        if (!agent) throw new Error('Unknown OMP agent');
        paths.set(agent.id, seed(agentId, 'omp'));
        office.switchSession(agent.id, agentId);
        messages.inbox({ session: agentId, account: agent.account });
        result = { ok: true, agentId: agent.id, sessionId: agentId };
      }
      else if (command === 'clear') {
        const agent = office.get(agentId);
        if (!agent) throw new Error('Unknown agent');
        const sessionId = randomUUID();
        paths.set(agentId, seed(sessionId, agent.provider === 'omp' ? 'omp' : 'claude', agent.provider !== 'omp'));
        office.switchSession(agentId, sessionId);
        messages.inbox({ session: sessionId, account: agent.account });
        result = { ok: true, sessionId };
      } else if (command === 'http-summaries') {
        if (!['on', 'off'].includes(agentId) || kind) throw new Error('Expected http-summaries <on|off>');
        httpSummariesEnabled = agentId === 'on';
        result = { ok: true };
      } else if (command === 'long-reply') {
        const agent = office.get(agentId);
        const path = paths.get(agentId);
        if (!agent || !path || kind) throw new Error('Expected long-reply <agentId>');
        const text = '# Resposta longa fictícia\n\n' + Array.from({ length: 40 }, (_, index) => `Parágrafo ${index + 1}: conteúdo fictício para verificar a expansão do resumo e preservar o cabeçalho tocado.`).join('\n\n');
        const message = { role: 'assistant', content: [{ type: 'text', text }] };
        appendFileSync(path, JSON.stringify(agent.provider === 'omp'
          ? { type: 'message', id: randomUUID(), timestamp: new Date().toISOString(), message }
          : { uuid: randomUUID(), timestamp: new Date().toISOString(), sessionId: agent.sessionId, cwd: CWD, isSidechain: false, type: 'assistant', message: { id: randomUUID(), ...message } }) + '\n');
        result = { ok: true, chars: text.length };
      } else if (command === 'reply') {
        const agent = office.get(agentId);
        if (!agent || agent.kind !== 'main' || kind) throw new Error('Expected reply <mainAgentId>');
        office.addActivity(agentId, { id: randomUUID(), kind: 'respond', icon: '', text: 'Resposta fictícia', at: Date.now() }, false);
        result = { ok: true };
      } else if (command === 'append') {
        const agent = office.get(agentId);
        const path = paths.get(agentId);
        if (!agent || !path || agent.provider === 'omp') throw new Error('Unknown Claude agent');
        appendFileSync(path, JSON.stringify({ uuid: randomUUID(), timestamp: new Date().toISOString(), sessionId: agent.sessionId, cwd: CWD, isSidechain: false, type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'text', text: 'Nova resposta fictícia.' }] } }) + '\n');
        result = { ok: true };
      } else if (command === 'deliver' || command === 'fail') {
        const agent = office.get(agentId);
        if (!agent) throw new Error('Unknown agent');
        const inbox = messages.inbox({ session: agent.sessionId, account: agent.account });
        messages.ack({ session: agent.sessionId, results: inbox.map(item => ({ id: item.id, ok: command === 'deliver', error: command === 'fail' ? 'Fixture rejection' : undefined })) });
        result = { ok: true, ids: inbox.map(item => item.id), controls: inbox.filter(item => item.control).map(item => ({ id: item.id, control: item.control })) };
      } else if (command === 'permission') {
        const agent = office.get(agentId);
        if (!agent) throw new Error('Unknown agent');
        if (!['bash', 'question-single', 'question-multi', 'question-omp'].includes(kind)) throw new Error('Unknown permission type');
        const question = kind !== 'bash';
        const questions = kind === 'question-omp' ? [
          { question: 'Escolha uma cor fictícia', options: [{ label: 'Azul' }, { label: 'Laranja' }] },
          { question: 'Escolha os itens fictícios', multiSelect: true, options: [{ label: 'Caderno' }, { label: 'Lápis' }, { label: 'Livro' }] },
          { question: 'Qual observação fictícia?', options: [{ label: 'Nenhuma' }, { label: 'Outra' }] },
        ] : [{ question: 'Escolha fictícia?', header: 'Fixture', multiSelect: kind === 'question-multi', options: [{ label: 'Primeira', description: 'Opção fictícia' }, { label: 'Segunda', description: 'Opção fictícia' }] }];
        const registered = permissions.register({ session_id: agent.sessionId, ...(agent.provider === 'omp' ? { provider: 'omp' } : {}), tool_name: question ? 'AskUserQuestion' : agent.provider === 'omp' ? 'bash' : 'Bash', tool_input: question ? { questions } : { command: 'echo fixture' }, ...(!question && agent.provider === 'claude' ? { permission_suggestions: [{ type: 'addRules', behavior: 'allow', destination: 'session', rules: [{ toolName: 'Bash', ruleContent: 'echo fixture' }] }] } : {}) });
        if ('skip' in registered) throw new Error(registered.skip);
        waitForPermission(registered.id);
        result = { ok: true, id: registered.id };
      } else throw new Error('Unknown command');
      return result;
  };
  let inputQueue = Promise.resolve();
  input.on('line', line => {
    inputQueue = inputQueue.then(async () => {
      try { process.stdout.write(`${JSON.stringify(await fixtureCommand(line))}\n`); }
      catch (error) { process.stdout.write(`${JSON.stringify({ ok: false, error: error instanceof Error ? error.message : 'Command failed' })}\n`); }
    });
  });
  let closing = false;
  const close = () => {
    if (closing) return;
    closing = true;
    input.close();
    for (const timer of timers) clearTimeout(timer);
    delivery.stop();
    zeroclaw.stop();
    messages.stop();
    permissions.stop();
    terminals.stop();
    hub.stop();
    localServer.closeAllConnections();
    localServer.close();
    server.closeAllConnections();
    server.close(() => {
      void (async () => {
        await zeroclaw.flush();
        await gateway.close();
        rmSync(root, { recursive: true, force: true });
      })();
    });
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
  input.once('close', close);
  process.stdout.write(`${JSON.stringify({ ready: true, url: `http://127.0.0.1:${remoteAddress.port}`, localUrl: `http://127.0.0.1:${localAddress.port}`, user: 'android-test', password: 'android-test', home: root, stagingDir, deliveredDir, agents: [...AGENTS.map(agent => agent.id), 'fixture:zeroclaw'] })}\n`);
}
