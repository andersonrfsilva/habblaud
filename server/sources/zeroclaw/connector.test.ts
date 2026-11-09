import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ZeroClawConnector, isSessionId, type ConnectorEvent, type ConnectorOptions, type OpenSession } from './connector';
import { startTestGateway, type TestGateway } from './test-gateway';

const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const input: OpenSession = { sessionId: A, agent: 'writer', name: 'Olá & projeto? #1', cwd: '/tmp/pasta com espaços & ?', cast: 'ana', officeAgentId: 'office-ana' };
const sleep = async () => { const wait = Promise.withResolvers<void>(); setImmediate(wait.resolve); await wait.promise; };
async function until(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Timed out waiting for fixture'); await sleep(); }
}
class Clock {
  now = 0;
  next = 0;
  tasks = new Map<number, { at: number; callback: () => void }>();
  timers = {
    setTimeout: ((callback: () => void, delay: number) => { const id = ++this.next; this.tasks.set(id, { at: this.now + delay, callback }); return id; }) as unknown as typeof setTimeout,
    clearTimeout: ((id: number) => { this.tasks.delete(id); }) as unknown as typeof clearTimeout,
    now: () => this.now,
  };
  advance(ms: number): void {
    this.now += ms;
    for (;;) {
      const ready = [...this.tasks].filter(([, task]) => task.at <= this.now).sort((a, b) => a[1].at - b[1].at)[0];
      if (!ready) return;
      this.tasks.delete(ready[0]); ready[1].callback();
    }
  }
  delays(): number[] { return [...this.tasks.values()].map((task) => task.at - this.now); }
}

describe('ZeroClawConnector', () => {
  let directory: string;
  let gateway: TestGateway;
  let connectors: ZeroClawConnector[];
  let events: ConnectorEvent[];
  function connector(options: Partial<ConnectorOptions> = {}): ZeroClawConnector {
    const value = new ZeroClawConnector({ url: gateway.url, dataDir: directory, jitter: () => 0, ...options });
    value.onEvent((event) => { events.push(event); }); connectors.push(value); return value;
  }
  beforeEach(async () => { directory = await mkdtemp(join(tmpdir(), 'zeroclaw-connector-')); gateway = await startTestGateway(); connectors = []; events = []; });
  afterEach(async () => { for (const value of connectors) value.stop(); await gateway.close(); await rm(directory, { recursive: true, force: true }); });

  it('validates UUID, explicit aliases, absolute cwd and optional metadata before persistence or network', async () => {
    const value = connector();
    for (const patch of [{ sessionId: '../escape' }, { agent: '' }, { agent: 'model/name' }, { cwd: 'relative' }, { cwd: '/tmp/\0' }, { name: '' }, { cast: 42 }]) {
      await expect(value.openSession({ ...input, ...patch } as OpenSession)).rejects.toThrow('Invalid');
    }
    expect(isSessionId(A)).toBe(true); expect(isSessionId('omp:abc')).toBe(false);
    expect(gateway.requests).toHaveLength(0); expect(value.openSessions()).toEqual([]);
    await expect(readFile(join(directory, 'zeroclaw/open-sessions.json'))).rejects.toMatchObject({ code: 'ENOENT' });
  });

  it('waits for session_start, encodes query values, and shares one socket for duplicate open and two sends', async () => {
    gateway.options.autoStart = false;
    const value = connector(); let resolved = false;
    const first = value.openSession(input).then(() => { resolved = true; });
    await until(() => gateway.connections.length === 1);
    expect(resolved).toBe(false); expect(value.session(A)?.connected).toBe(false);
    const duplicate = value.openSession(input);
    const connection = gateway.connections[0];
    expect(connection.url.searchParams.get('agent')).toBe(input.agent);
    expect(connection.url.searchParams.get('name')).toBe(input.name);
    expect(connection.url.searchParams.get('cwd')).toBe(input.cwd);
    connection.send({ type: 'session_start', session_id: A, resumed: true });
    await Promise.all([first, duplicate]);
    await value.sendMessage(A, 'primeiro'); await value.sendMessage(A, 'segundo');
    await until(() => connection.messages.length === 2);
    expect(connection.messages).toEqual([{ type: 'message', content: 'primeiro' }, { type: 'message', content: 'segundo' }]);
    expect(gateway.connections).toHaveLength(1);
    const sent = events.filter((event) => event.type === 'message-sent');
    expect(sent).toHaveLength(2); expect(new Set(sent.map((event) => event.id)).size).toBe(2);
    expect(value.session(A)?.busy).toBe(true);
    expect(events).toContainEqual(expect.objectContaining({ type: 'session-open', resumed: true }));
    await expect(value.openSession({ ...input, cwd: '/different' })).rejects.toThrow('Incompatible');
  });

  it('opens separate UUIDs, snapshots public descriptors, delegates metadata and normalizes gateway session lists', async () => {
    const value = connector({ metadataOf: (id) => id === B ? { ...input, sessionId: B } : undefined });
    await value.openSession(input); await value.openSession({ ...input, sessionId: B });
    expect(gateway.connections).toHaveLength(2);
    const descriptor = value.session(A)!; descriptor.busy = true;
    expect(value.session(A)?.busy).toBe(false);
    expect(value.metadataOf(A)).toEqual(input); expect(value.metadataOf(B)?.sessionId).toBe(B);
    gateway.sessions.push({ session_id: A, agent_alias: 'writer' });
    expect(await value.listSessions()).toEqual(gateway.sessions);
    gateway.options.sessionsShape = 'array'; expect(await value.listSessions()).toEqual(gateway.sessions);
    gateway.responses.set('/api/sessions', { body: { sessions: [null] } });
    await expect(value.listSessions()).rejects.toThrow('Invalid');
  });

  it('bootstraps before any websocket frames can arrive', async () => {
    let bootstrapped = false;
    const value = connector({ beforeOpen: async () => { expect(gateway.connections).toHaveLength(0); await sleep(); bootstrapped = true; } });
    gateway.onConnection = () => expect(bootstrapped).toBe(true);
    await value.openSession(input); expect(bootstrapped).toBe(true);
  });

  it('rejects wrong session_start without publishing presence', async () => {
    gateway.options.sessionId = B;
    const value = connector(); await expect(value.openSession(input)).rejects.toThrow('session_start');
    expect(events.some((event) => event.type === 'session-open')).toBe(false);
    expect(value.session(A)?.connected).toBe(false);
  });

  it('times out a silent handshake and rejects disconnected messages without hidden queues', async () => {
    gateway.options.autoStart = false;
    const clock = new Clock(); const value = connector({ timeoutMs: 50, timers: clock.timers });
    const opening = value.openSession(input);
    const rejected = expect(opening).rejects.toThrow('handshake timed out');
    await until(() => gateway.connections.length === 1);
    clock.advance(50); await rejected;
    await expect(value.sendMessage(A, 'not queued')).rejects.toThrow('disconnected');
    expect(gateway.connections[0].messages).toEqual([]);
  });

  it('keeps intentions on stop and restores only those, starting unknown until HTTP idle confirms', async () => {
    const first = connector(); await first.openSession(input); first.stop();
    gateway.sessions.push({ session_id: B, agent_alias: 'writer' });
    const second = connector(); await second.start();
    await until(() => second.session(A)?.busy === false);
    expect(second.openSessions().map((item) => item.sessionId)).toEqual([A]);
    expect(gateway.connections).toHaveLength(2); expect(gateway.connections[1].messages).toEqual([]);
    expect(events).toContainEqual(expect.objectContaining({ type: 'session-open', continuityLost: true }));
    expect(events).toContainEqual(expect.objectContaining({ type: 'turn-state', terminalConfirmed: true, source: 'state-idle' }));
    await second.sendMessage(A, 'after restart');
  });

  it('persists close before removing descriptor, preserves transcript independence, and emits replacement', async () => {
    const value = connector(); await value.openSession(input);
    await value.closeSession(A, { replacement: true });
    expect(value.session(A)).toBeUndefined();
    const saved = JSON.parse(await readFile(join(directory, 'zeroclaw/open-sessions.json'), 'utf8'));
    expect(saved).toEqual({ version: 1, sessions: [] });
    expect(events).toContainEqual(expect.objectContaining({ type: 'session-close', intentional: true, replacement: true }));
    expect(gateway.requests.some((request) => request.method === 'DELETE' || request.path.endsWith('/abort'))).toBe(false);
    const next = connector(); await next.start(); expect(next.openSessions()).toEqual([]);
  });

  it.each(['not json', '{"version":2,"sessions":[]}', '{"version":1,"sessions":[{}]}'])('does not overwrite corrupt persisted intentions: %s', async (raw) => {
    await mkdir(join(directory, 'zeroclaw'));
    const file = join(directory, 'zeroclaw/open-sessions.json'); await writeFile(file, raw);
    const value = connector(); await expect(value.start()).rejects.toThrow();
    await expect(value.openSession(input)).rejects.toThrow();
    expect(await readFile(file, 'utf8')).toBe(raw); expect(gateway.connections).toHaveLength(0);
  });

  it('failed atomic persistence never reports or opens an unpersisted session', async () => {
    await writeFile(join(directory, 'blocked'), 'not a directory');
    const value = connector({ dataDir: join(directory, 'blocked') });
    await expect(value.openSession(input)).rejects.toThrow();
    expect(value.openSessions()).toEqual([]); expect(gateway.requests).toHaveLength(0);
  });

  it('failed close persistence keeps the existing descriptor and socket', async () => {
    const value = connector(); await value.openSession(input);
    await rm(join(directory, 'zeroclaw/open-sessions.json')); await mkdir(join(directory, 'zeroclaw/open-sessions.json'));
    await expect(value.closeSession(A)).rejects.toThrow();
    expect(value.session(A)?.connected).toBe(true);
    await value.sendMessage(A, 'still open');
  });

  it('confirms no_active_response without WS and distinguishes accepted abort from completion', async () => {
    const value = connector(); await value.openSession(input); await value.sendMessage(A, 'work');
    gateway.aborts.set(A, 'aborted');
    expect(await value.abortSession(A)).toEqual({ status: 'aborted', terminalConfirmed: false });
    expect(value.session(A)?.busy).toBe(true);
    gateway.aborts.set(A, 'no_active_response');
    expect(await value.abortSession(A)).toEqual({ status: 'no_active_response', terminalConfirmed: true });
    expect(value.session(A)).toMatchObject({ busy: false, continuityLost: false });
    expect(events).toContainEqual(expect.objectContaining({ type: 'turn-state', source: 'abort-no-active', terminalConfirmed: true }));
    expect(events.some((event) => event.type === 'frame' && event.frame.type === 'done')).toBe(false);
  });

  it.each([{ status: 503, body: {} }, { status: 200, body: { status: 'mystery' } }, { status: 404, body: {} }])('never treats invalid abort as terminal: %j', async (response) => {
    const value = connector(); await value.openSession(input); await value.sendMessage(A, 'work');
    gateway.responses.set(`/api/sessions/${A}/abort`, response);
    await expect(value.abortSession(A)).rejects.toThrow(); expect(value.session(A)?.busy).toBe(true);
  });

  it('validates frames, leaves queue errors busy, and clears state only on current done or aborted', async () => {
    const value = connector(); await value.openSession(input); await value.sendMessage(A, 'work');
    const connection = gateway.connections[0];
    connection.raw('invalid json'); connection.send({ type: 'tool_call', id: 'a', name: 'shell', args: 'bad' });
    connection.send({ type: 'error', code: 'queue_full', message: 'queue full' });
    await until(() => events.some((event) => event.type === 'frame' && event.frame.type === 'error'));
    expect(value.session(A)?.busy).toBe(true);
    expect(events.some((event) => event.type === 'frame' && event.frame.type === 'tool_call')).toBe(false);
    connection.send({ type: 'done', full_response: '' }); await until(() => value.session(A)?.busy === false);
    await value.sendMessage(A, 'again'); connection.send({ type: 'aborted' }); await until(() => value.session(A)?.busy === false);
  });

  it('backs off 1001, reuses metadata, keeps running gap, polls idle, and never replays prompts', async () => {
    const clock = new Clock(); const value = connector({ timers: clock.timers });
    await value.openSession(input); await value.sendMessage(A, 'once');
    await until(() => gateway.connections[0].messages.length === 1);
    gateway.states.set(A, 'running'); gateway.connections[0].close(1001);
    await until(() => value.session(A)?.connected === false);
    expect(value.session(A)).toMatchObject({ busy: true, continuityLost: true });
    clock.advance(999); await sleep(); expect(gateway.connections).toHaveLength(1);
    clock.advance(1); await until(() => gateway.connections.length === 2 && value.session(A)?.connected === true);
    await until(() => events.some((event) => event.type === 'turn-state' && event.source === 'state-running'));
    expect(gateway.connections[1].url.searchParams.get('cwd')).toBe(input.cwd);
    expect(gateway.connections[1].messages).toEqual([]);
    gateway.states.set(A, 'idle'); clock.advance(10_000);
    await until(() => value.session(A)?.continuityLost === false);
    expect(value.session(A)?.busy).toBe(false);
  });

  it('restores a running intention, keeps unknown after 404 or malformed state, and clears error state', async () => {
    const first = connector(); await first.openSession(input); first.stop();
    gateway.states.set(A, 'running'); const value = connector(); await value.start();
    await until(() => events.some((event) => event.type === 'turn-state' && event.source === 'state-running'));
    expect(value.session(A)).toMatchObject({ busy: true, continuityLost: true });
    gateway.responses.set(`/api/sessions/${A}/state`, { status: 404, body: {} });
    await expect(value.refreshTurnState(A)).rejects.toThrow();
    gateway.responses.set(`/api/sessions/${A}/state`, { body: { state: 'unknown' } });
    await expect(value.refreshTurnState(A)).rejects.toThrow();
    expect(value.session(A)).toMatchObject({ busy: true, continuityLost: true });
    gateway.responses.delete(`/api/sessions/${A}/state`); gateway.states.set(A, 'error');
    expect(await value.refreshTurnState(A)).toEqual({ busy: false, continuityLost: false });
  });

  it('ignores delayed idle proof from an earlier turn', async () => {
    const value = connector(); await value.openSession(input); await value.sendMessage(A, 'old');
    const gate = Promise.withResolvers<void>();
    gateway.responses.set(`/api/sessions/${A}/state`, { body: { session_id: A, state: 'idle' }, wait: gate.promise });
    const refresh = value.refreshTurnState(A);
    await until(() => gateway.requests.some((request) => request.path.endsWith('/state')));
    await value.sendMessage(A, 'new'); gate.resolve(); await refresh;
    expect(value.session(A)?.busy).toBe(true);
  });

  it.each(['5', 'Thu, 01 Jan 1970 00:00:05 GMT'])('honors Retry-After minimum %s without premature upgrade', async (retryAfter) => {
    const clock = new Clock(); gateway.options.status = 503; gateway.options.retryAfter = retryAfter;
    const value = connector({ timers: clock.timers });
    await expect(value.openSession(input)).rejects.toThrow('503');
    expect(gateway.connections).toHaveLength(0); expect(clock.delays()).toContain(5000);
    gateway.options.status = 200; clock.advance(4999); await sleep(); expect(gateway.connections).toHaveLength(0);
    clock.advance(1); await until(() => value.session(A)?.connected === true);
  });

  it('uses bounded exponential jitter and resets only after session_start', async () => {
    const clock = new Clock(); gateway.options.status = 503;
    const value = connector({ timers: clock.timers, jitter: () => 1 });
    await expect(value.openSession(input)).rejects.toThrow('503'); expect(clock.delays()).toContain(1200);
    clock.advance(1200); await until(() => gateway.requests.length === 2); await until(() => clock.delays().includes(2400));
    clock.advance(2400); await until(() => gateway.requests.length === 3); await until(() => clock.delays().includes(4800));
    gateway.options.status = 200; clock.advance(4800); await until(() => value.session(A)?.connected === true);
    gateway.connections[0].close(); await until(() => value.session(A)?.connected === false);
    expect(clock.delays()).toContain(1200);
  });

  it.each([401, 403])('stops retries on HTTP %s without exposing response bodies', async (status) => {
    const clock = new Clock(); gateway.options.status = status;
    const value = connector({ timers: clock.timers }); await expect(value.openSession(input)).rejects.toThrow(String(status));
    clock.advance(100_000); await sleep(); expect(gateway.requests).toHaveLength(1); expect(clock.tasks.size).toBe(0);
  });

  it('stops retries on refused agent alias', async () => {
    const clock = new Clock(); gateway.options.autoStart = false;
    gateway.onConnection = (connection) => connection.send({ type: 'error', code: 'unknown_agent', message: 'unknown alias' });
    const value = connector({ timers: clock.timers }); await expect(value.openSession(input)).rejects.toThrow('alias');
    clock.advance(100_000); await sleep(); expect(gateway.connections).toHaveLength(1); expect(clock.tasks.size).toBe(0);
  });

  it.each(['close', 'stop'])('cancels retry and late callbacks on %s', async (operation) => {
    const clock = new Clock(); const value = connector({ timers: clock.timers }); await value.openSession(input);
    gateway.connections[0].drop(); await until(() => value.session(A)?.connected === false);
    if (operation === 'close') await value.closeSession(A); else value.stop();
    clock.advance(100_000); await sleep(); expect(gateway.connections).toHaveLength(1); expect(clock.tasks.size).toBe(0);
  });

  it.each(['close', 'stop'])('cancels pending HTTP/open handshake on %s', async (operation) => {
    const gate = Promise.withResolvers<void>();
    gateway.responses.set('/api/sessions', { body: { sessions: [] }, wait: gate.promise });
    const clock = new Clock(); const value = connector({ timers: clock.timers });
    const opening = value.openSession(input).then(() => 'opened', () => 'cancelled');
    await until(() => gateway.requests.length === 1);
    if (operation === 'close') await value.closeSession(A); else value.stop();
    expect(await opening).toBe('cancelled'); clock.advance(100_000);
    gate.resolve(); await sleep(); expect(gateway.connections).toHaveLength(0); expect(clock.tasks.size).toBe(0);
  });

  it('bounds HTTP timeout and disallows external/credentialed request targets', async () => {
    const clock = new Clock(); const value = connector({ timeoutMs: 30, timers: clock.timers });
    const gate = Promise.withResolvers<void>();
    gateway.responses.set('/api/sessions', { body: [], wait: gate.promise });
    const pending = expect(value.request('/api/sessions')).rejects.toThrow();
    await until(() => gateway.requests.length === 1);
    clock.advance(30); await pending; gate.resolve();
    await expect(value.request('http://example.com')).rejects.toThrow('Invalid');
    await expect(value.request('//example.com/test')).rejects.toThrow('Invalid');
    expect(gateway.requests).toHaveLength(1);
  });

  it('serializes concurrent durable updates without losing either intention', async () => {
    const value = connector();
    await Promise.all([value.openSession(input), value.openSession({ ...input, sessionId: B })]);
    const saved = JSON.parse(await readFile(join(directory, 'zeroclaw/open-sessions.json'), 'utf8'));
    expect(saved.sessions.map((item: OpenSession) => item.sessionId).sort()).toEqual([A, B]);
    expect(gateway.connections).toHaveLength(2);
  });

  it('awaits prompt persistence and propagates disk failure after socket acceptance without replay', async () => {
    const value = connector(); await value.openSession(input);
    const gate = Promise.withResolvers<void>();
    const unsubscribe = value.onEvent((event) => event.type === 'message-sent' ? gate.promise : undefined);
    let resolved = false;
    const sending = value.sendMessage(A, 'persist first').then(() => { resolved = true; });
    await until(() => gateway.connections[0].messages.length === 1);
    expect(resolved).toBe(false); expect(value.session(A)?.busy).toBe(true);
    gate.resolve(); await sending; unsubscribe();
    value.onEvent(async (event) => { if (event.type === 'message-sent') throw new Error('disk full'); });
    await expect(value.sendMessage(A, 'accepted but not durable')).rejects.toThrow('disk full');
    await until(() => gateway.connections[0].messages.length === 2);
    expect(events).toContainEqual(expect.objectContaining({ type: 'diagnostic', message: expect.stringContaining('do not resend') }));
    expect(value.session(A)?.busy).toBe(true);
  });

  it.each(['close', 'stop'])('cancels a silent websocket handshake on %s and ignores late frames', async (operation) => {
    gateway.options.autoStart = false;
    const clock = new Clock(); const value = connector({ timers: clock.timers });
    const opening = value.openSession(input).then(() => 'opened', () => 'cancelled');
    await until(() => gateway.connections.length === 1);
    if (operation === 'close') await value.closeSession(A); else value.stop();
    gateway.connections[0].send({ type: 'session_start', session_id: A, resumed: true });
    gateway.connections[0].send({ type: 'chunk', content: 'stale' });
    expect(await opening).toBe('cancelled'); clock.advance(100_000); await sleep();
    expect(events.some((event) => event.type === 'session-open' || event.type === 'frame')).toBe(false);
    expect(clock.tasks.size).toBe(0);
  });

  it('reconciles restored unknown state through no_active_response without a websocket terminal frame', async () => {
    const first = connector(); await first.openSession(input); first.stop();
    gateway.states.set(A, 'running');
    const value = connector(); await value.start();
    await until(() => value.session(A)?.continuityLost === true);
    gateway.aborts.set(A, 'no_active_response');
    expect(await value.abortSession(A)).toEqual({ status: 'no_active_response', terminalConfirmed: true });
    expect(value.session(A)).toMatchObject({ busy: false, continuityLost: false });
    expect(events.some((event) => event.type === 'frame' && ['done', 'aborted'].includes(event.frame.type))).toBe(false);
  });
});
