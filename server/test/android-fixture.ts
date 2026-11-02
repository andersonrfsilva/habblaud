import http from 'node:http';
import { appendFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { OMP_SESSION_ID } from '../../shared/omp-events';
import { AccountsService } from '../accounts/service';
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

export async function startAndroidFixture(root: string, args: string[]): Promise<void> {
  let port = 14748;
  let allow: string | undefined;
  let noSessionGuard = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--port' && args[i + 1]) port = Number(args[++i]);
    else if (args[i] === '--allow' && args[i + 1]) allow = args[++i];
    else if (args[i] === '--no-session-guard') noSessionGuard = true;
    else throw new Error(`Unknown or incomplete option: ${args[i]}`);
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid port');
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
  const late: { messages?: MessageRegistry; permissions?: PermissionRegistry } = {};
  const office = new Office({ names: new NameStore(null), version: 'android-fixture', startedAt: Date.now(), accounts: s => accounts.list(s), sources: () => [], accountName: () => undefined, terminal: true, messages: () => late.messages?.reachable() ?? new Set(), permissions: () => late.permissions?.snapshot() ?? new Map() });
  for (const [index, agent] of AGENTS.entries()) office.addMain({ ...agent, cwd: `/fixture/${projects[index]}`, role: 'Agente principal', startedAt: Date.now() - index * 300_000, status: index % 2 ? 'working' : 'idle' });
  const hub = new Hub(office, { canAnswer: req => isRemoteRequest(req) && parseAllow(allow).has('permissions') });
  const stagingDir = join(root, 'attachments', '.pending');
  const deliveredDir = join(root, 'attachments', 'delivered');
  let attachmentTimeOffset = 0;
  const attachments = new AttachmentStore({ dir: stagingDir, now: () => Date.now() + attachmentTimeOffset });
  const messages = new MessageRegistry({ office, attachments, presenceMs: Number.MAX_SAFE_INTEGER, writeAttachments: async files => ({ paths: await writeAttachments(deliveredDir, randomUUID(), files) }) });
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
  const terminals = new TerminalStreams({ office, transcriptPathOf: id => paths.get(id), parserFor: id => office.get(id)?.provider === 'omp' ? createOmpTerminalParser() : undefined, initEntries: 50 });
  const openAgentOf = (account: string, session: string) => openMainAgent(office.list(), account, session);
  const history = new HistorySet([
    new SessionHistory({ accounts: () => accounts.entries().filter(account => account.provider === 'claude'), openAgentOf }),
    new OmpHistory({ accounts: () => accounts.entries().filter(account => account.provider === 'omp'), openAgentOf }),
  ]);
  const allowed = parseAllow(allow);
  const api = createApiHandler({ office, hub, accounts, sources: () => [], version: 'android-fixture', inDocker: false, terminal: true, terminals, sessions: history, messages: createMessageRoutes(messages), permissions: createPermissionRoutes(permissions), remote: { is: isRemoteRequest, allows: feature => allowed.has(feature) } });
  const passwordHashText = await hashPassword('android-test');
  const passwordHash = parsePasswordHash(passwordHashText);
  if (!passwordHash) throw new Error('Could not create fixture password');
  const sessions = new RemoteSessions(loadSecret(join(root, 'remote-secret')), { user: 'android-test', passwordHashText }, Date.now, join(root, 'remote-revoked'));
  const gate = createRemoteGate({ config: { user: 'android-test', passwordHash }, sessions, secure: false });
  const guard = createRequestGuard({ allowedHosts: new Set() });
  const posts: Record<string, number> = { '/api/messages': 0 };
  const messageRequests: Array<{ status: number; text?: string; attachments: number; expectedSessionId?: string }> = [];
  let lastHeaders: Record<string, string | null> = {};
  let drop = false;
  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    lastHeaders = { origin: req.headers.origin ?? null, referer: req.headers.referer ?? null, host: req.headers.host ?? null, 'content-type': req.headers['content-type'] ?? null };
    if (req.method === 'POST') posts[url.pathname] = (posts[url.pathname] ?? 0) + 1;
    if (req.method === 'POST' && url.pathname === '/api/messages') {
      let body = '';
      req.on('data', (chunk: Buffer) => { if (body.length <= 131_072) body += chunk.toString(); });
      res.once('finish', () => {
        try {
          const value = JSON.parse(body) as { text?: string; attachments?: unknown; expectedSessionId?: string };
          messageRequests.push({ status: res.statusCode, text: value.text, attachments: Array.isArray(value.attachments) ? value.attachments.length : 0, expectedSessionId: value.expectedSessionId });
        } catch {
          messageRequests.push({ status: res.statusCode, attachments: 0 });
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
    if (guard(req, res) || gate(req, res, url)) return;
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
  const localAcks: Array<{ id: string; ok: boolean }> = [];
  const localServer = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1');
    if (guard(req, res)) return;
    if (!['/api/mod/inbox', '/api/mod/inbox/ack', '/api/omp/events'].includes(url.pathname) &&
        url.pathname !== '/api/permissions' && !url.pathname.startsWith('/api/permissions/')) {
      res.writeHead(404).end();
      return;
    }
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
    if (!api(req, res, url)) res.writeHead(404).end();
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
  input.on('line', line => {
    try {
      const [command, agentId, kind] = line.trim().split(/\s+/);
      let result: unknown;
      if (command === 'stats') result = { sse: hub.size, terminal: terminals.size, posts, messageRequests, lastHeaders, permissionResults, localAcks };
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
        if (!['bash', 'question-single', 'question-multi'].includes(kind)) throw new Error('Unknown permission type');
        const question = kind !== 'bash';
        const registered = permissions.register({ session_id: agent.sessionId, ...(agent.provider === 'omp' ? { provider: 'omp' } : {}), tool_name: question ? 'AskUserQuestion' : agent.provider === 'omp' ? 'bash' : 'Bash', tool_input: question ? { questions: [{ question: 'Escolha fictícia?', header: 'Fixture', multiSelect: kind === 'question-multi', options: [{ label: 'Primeira', description: 'Opção fictícia' }, { label: 'Segunda', description: 'Opção fictícia' }] }] } : { command: 'echo fixture' }, ...(!question && agent.provider === 'claude' ? { permission_suggestions: [{ type: 'addRules', behavior: 'allow', destination: 'session', rules: [{ toolName: 'Bash', ruleContent: 'echo fixture' }] }] } : {}) });
        if ('skip' in registered) throw new Error(registered.skip);
        waitForPermission(registered.id);
        result = { ok: true, id: registered.id };
      } else throw new Error('Unknown command');
      process.stdout.write(`${JSON.stringify(result)}\n`);
    } catch (error) { process.stdout.write(`${JSON.stringify({ ok: false, error: error instanceof Error ? error.message : 'Command failed' })}\n`); }
  });
  let closing = false;
  const close = () => {
    if (closing) return;
    closing = true;
    input.close();
    for (const timer of timers) clearTimeout(timer);
    messages.stop();
    permissions.stop();
    terminals.stop();
    hub.stop();
    localServer.closeAllConnections();
    localServer.close();
    server.closeAllConnections();
    server.close(() => { rmSync(root, { recursive: true, force: true }); });
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
  input.once('close', close);
  process.stdout.write(`${JSON.stringify({ ready: true, url: `http://127.0.0.1:${port}`, localUrl: `http://127.0.0.1:${localAddress.port}`, user: 'android-test', password: 'android-test', home: root, stagingDir, deliveredDir, agents: AGENTS.map(agent => agent.id) })}\n`);
}
