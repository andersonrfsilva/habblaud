import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsService } from '../accounts/service';
import { AsideStore } from '../asides/store';
import { setQuiet } from '../log';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AttachmentStore, promptWithAttachments } from '../spawn/attachments';
import { ZeroClawConnector } from '../sources/zeroclaw/connector';
import { ZeroClawMirror } from '../sources/zeroclaw/mirror';
import { ZeroClawSource } from '../sources/zeroclaw/source';
import { startTestGateway } from '../sources/zeroclaw/test-gateway';
import { uploadAttachment } from '../test/attachment-fixture';
import {
  ERR_SESSION_CHANGED, KEEP_MS, MAX_OPEN, MessageRegistry, QUEUED_TIMEOUT_MS, SENT_TIMEOUT_MS,
  type MessageRegistryOptions, type SendResult,
} from './registry';
import { ZeroClawDelivery } from './zeroclaw';

setQuiet(true);
const SID = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
const OTHER = '7f2c9ee1-2bdd-49ea-b431-3d56fc046302';
const HANDLE = 'stable-zeroclaw-handle';
const open = { sessionId: SID, agent: 'assistant', name: 'Teste', cwd: '/projetos/teste', cast: 'test-cast', officeAgentId: HANDLE };
const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => {
  for (const dispose of cleanup.splice(0).reverse()) await dispose();
  vi.restoreAllMocks();
});
const until = (assertion: () => void | Promise<void>) => vi.waitFor(assertion, { timeout: 4_000, interval: 5 });
function accepted(result: SendResult) {
  if (!('message' in result)) throw new Error(`Message refused: ${JSON.stringify(result)}`);
  expect(result.message.status).toBe('queued');
  return result.message.id;
}

async function setup(extra: Partial<MessageRegistryOptions> = {}, timeoutMs = 250) {
  const dataDir = mkdtempSync(join(tmpdir(), 'zc-delivery-'));
  cleanup.push(() => rmSync(dataDir, { recursive: true, force: true }));
  const gateway = await startTestGateway();
  cleanup.push(() => gateway.close());
  const accounts = new AccountsService({ dirs: [], home: dataDir, env: {}, onChange() {} });
  let registry: MessageRegistry | undefined;
  let now = 1_000_000;
  const clock = { now: () => now, advance: (ms: number) => { now += ms; } };
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: 0,
    accounts: (snapshot) => accounts.list(snapshot), sources: () => [], accountName: () => 'ZeroClaw',
    messages: () => registry?.reachable() ?? new Set(), now: clock.now });
  const mirror = new ZeroClawMirror({ dataDir });
  let source: ZeroClawSource;
  const connector = new ZeroClawConnector({ url: gateway.url, dataDir, timeoutMs: 300, jitter: () => 0,
    metadataOf: (id) => mirror.metadataOf(id), beforeOpen: (input) => source.prepare(input) });
  source = new ZeroClawSource({ accounts, office, connector, mirror });
  cleanup.push(async () => { source.stop(); await source.flush(); });
  const closing = new Set<string>();
  const replacementFailed = vi.fn((id: string) => office.closeMain(id));
  const delivery = new ZeroClawDelivery({ connector, timeoutMs, isClosing: (id) => closing.has(id),
    onCancelSession: (id, reason) => registry?.cancelZeroClawSession(id, reason), onReplacementFailed: replacementFailed });
  cleanup.push(() => delivery.stop());
  const onSettled = vi.fn();
  registry = new MessageRegistry({ office, zeroclaw: delivery, now: clock.now, onSettled, ...extra });
  const messages = registry;
  cleanup.push(() => messages.stop());
  await source.start();
  await connector.openSession(open);
  await source.flush();
  const socket = () => gateway.connections.filter((c) => c.sessionId === SID).at(-1)!;
  const frames = () => gateway.connections.flatMap((c) => c.messages);
  const send = (text: string, deliverAs?: 'steer' | 'followUp') => accepted(messages.send({ agentId: HANDLE, text, ...(deliverAs ? { deliverAs } : {}) }));
  const status = async (id: string, expected: string) => until(() => expect(messages.get(id)?.status).toBe(expected));
  const busy = async () => { await delivery.sendMessage(SID, 'initial turn'); await until(() => expect(socket().messages).toHaveLength(1)); };
  return { dataDir, gateway, office, source, connector, delivery, registry: messages, clock, closing,
    replacementFailed, onSettled, socket, frames, send, status, busy };
}

// Groups 1–7: transport acceptance, per-session FIFO, steering and mirror ownership.
describe('ZeroClaw transport and registry queue', () => {
  it('idle preserves exact text and UUID, uses one socket, and writes one user record', async () => {
    const s = await setup();
    const text = '  mantenha espaços\n\tno fim  ';
    const id = s.send(text);
    await s.status(id, 'delivered');
    await until(() => expect(s.socket().messages).toEqual([{ type: 'message', content: text }]));
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.socket().url.searchParams.get('session_id')).toBe(SID);
    expect(s.connector.session(SID)?.busy).toBe(true);
    await s.source.flush();
    const records = readFileSync(s.source.transcriptPathOf(HANDLE)!, 'utf8').trim().split('\n').map((line) => JSON.parse(line));
    expect(records.filter((record) => record.message?.role === 'user' && record.message?.content === text)).toHaveLength(1);
    expect(s.frames().some((frame) => frame.type === 'stop')).toBe(false);
    expect(s.registry.get(id)).not.toHaveProperty('text');
  });

  it('busy steer overtakes waiting followUps on the same socket, preserving steer order', async () => {
    const s = await setup();
    await s.busy();
    const first = s.send('follow one');
    const second = s.send('follow two', 'followUp');
    const steer1 = s.send('steer one', 'steer');
    const steer2 = s.send('steer two', 'steer');
    await s.status(steer1, 'delivered');
    await s.status(steer2, 'delivered');
    await until(() => expect(s.socket().messages.map((frame) => frame.content)).toEqual(['initial turn', 'steer one', 'steer two']));
    expect(s.registry.get(first)?.status).toBe('queued');
    expect(s.registry.get(second)?.status).toBe('queued');
    expect(s.gateway.connections).toHaveLength(1);
    s.socket().send({ type: 'done' });
    await s.status(first, 'delivered');
    expect(s.registry.get(second)?.status).toBe('queued');
    await until(() => expect(s.socket().messages).toHaveLength(4));
    s.registry.tick();
    expect(s.socket().messages).toHaveLength(4);
    s.socket().send({ type: 'done' });
    await s.status(second, 'delivered');
    await until(() => expect(s.socket().messages.map((frame) => frame.content)).toEqual(['initial turn', 'steer one', 'steer two', 'follow one', 'follow two']));
  });

  it('first idle send makes busy synchronous; another agent advances independently', async () => {
    const s = await setup();
    await s.connector.openSession({ ...open, sessionId: OTHER, officeAgentId: 'other-handle' });
    await s.source.flush();
    const first = s.send('first');
    const second = s.send('must wait');
    const other = accepted(s.registry.send({ agentId: 'other-handle', text: 'parallel' }));
    await s.status(first, 'delivered');
    await s.status(other, 'delivered');
    expect(s.registry.get(second)?.status).toBe('queued');
    await until(() => expect(s.socket().messages).toEqual([{ type: 'message', content: 'first' }]));
    expect(s.gateway.connections.find((c) => c.sessionId === OTHER)?.messages).toEqual([{ type: 'message', content: 'parallel' }]);
  });

  it('queued admission snapshot precedes sent and delivered; acceptance needs no terminal frame', async () => {
    const s = await setup();
    const acceptedBuffer = Promise.withResolvers<void>();
    const transport = vi.spyOn(s.connector, 'sendMessage').mockReturnValueOnce(acceptedBuffer.promise);
    const result = s.registry.send({ agentId: HANDLE, text: 'buffer gate' });
    const id = accepted(result);
    await s.status(id, 'sent');
    expect(s.onSettled).not.toHaveBeenCalled();
    acceptedBuffer.resolve();
    await s.status(id, 'delivered');
    expect(transport).toHaveBeenCalledExactlyOnceWith(SID, 'buffer gate');
    expect(s.onSettled).toHaveBeenCalledExactlyOnceWith(id, 'delivered', undefined);
    expect(s.office.get(HANDLE)?.recent.some((activity) => activity.kind === 'communicate' && activity.text === 'Mensagem pelo Habblaud')).toBe(true);
  });

  it.each(['sync', 'async'] as const)('%s transport failure settles once without success activity', async (mode) => {
    const s = await setup();
    const before = s.office.get(HANDLE)?.recent.length;
    const transport = vi.spyOn(s.connector, 'sendMessage');
    if (mode === 'sync') transport.mockImplementationOnce(() => { throw new Error('socket refused'); });
    else transport.mockRejectedValueOnce(new Error('socket refused'));
    const id = s.send('secret payload');
    await s.status(id, 'failed');
    expect(s.onSettled).toHaveBeenCalledTimes(1);
    expect(s.onSettled.mock.calls[0].slice(0, 2)).toEqual([id, 'failed']);
    expect(s.registry.get(id)?.error).not.toContain('secret payload');
    expect(s.office.get(HANDLE)?.recent.length).toBe(before);
    expect(s.frames()).toEqual([]);
  });

  it('uncorrelated steering error reaches source without rewriting delivered messages', async () => {
    const s = await setup();
    const id = s.send('accepted steering', 'steer');
    await s.status(id, 'delivered');
    s.socket().send({ type: 'error', code: 'STEERING_QUEUE_FULL', message: 'steering queue full' });
    await until(async () => {
      await s.source.flush();
      expect(readFileSync(s.source.transcriptPathOf(HANDLE)!, 'utf8')).toContain('steering queue full');
    });
    expect(s.registry.get(id)?.status).toBe('delivered');
    expect(s.onSettled).toHaveBeenCalledTimes(1);
    expect(s.connector.session(SID)?.busy).toBe(true);
  });

  it('disconnected or unknown transport rejects without opening another socket', async () => {
    const s = await setup();
    await s.connector.closeSession(SID);
    expect(await s.delivery.sendMessage(SID, 'never')).toMatchObject({ ok: false });
    expect(await s.delivery.sendMessage(OTHER, 'never')).toMatchObject({ ok: false });
    expect(s.delivery.available(SID)).toBe(false);
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.frames()).toEqual([]);
  });
});

// Groups 8 and 11: terminal proof must belong to the captured UUID.
describe('ZeroClaw continuity and STOP', () => {
  it.each(['stop', 'new'] as const)('restored intention recovers through %s with HTTP no_active_response and no terminal frame', async (control) => {
    const s = await setup();
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.registry.stop();
    s.delivery.stop();
    s.source.stop();
    const restored = new ZeroClawConnector({ url: s.gateway.url, dataDir: s.dataDir, timeoutMs: 300 });
    cleanup.push(() => restored.stop());
    await restored.start();
    expect(restored.session(SID)).toMatchObject({ connected: true, busy: true, continuityLost: true });
    const delivery = new ZeroClawDelivery({ connector: restored, timeoutMs: 250 });
    cleanup.push(() => delivery.stop());
    expect(delivery.available(SID)).toBe(false);
    expect(delivery.available(SID, true)).toBe(true);
    s.gateway.aborts.set(SID, 'no_active_response');
    if (control === 'stop') {
      await delivery.stopSession(SID);
      expect(restored.session(SID)).toMatchObject({ busy: false, continuityLost: false });
      expect(delivery.available(SID)).toBe(true);
    } else {
      const next = await delivery.newSession(SID);
      expect(next).not.toBe(SID);
      expect(restored.session(next)).toMatchObject({ connected: true, busy: false, continuityLost: false, officeAgentId: HANDLE });
      expect(restored.session(SID)).toBeUndefined();
    }
    expect(s.gateway.requests.filter((request) => request.method === 'POST')).toEqual([
      expect.objectContaining({ path: `/api/sessions/${SID}/abort` }),
    ]);
    expect(s.frames()).toEqual([{ type: 'message', content: 'initial turn' }]);
  });

  it('busy false alone cannot prove continuity or admit another turn', async () => {
    const s = await setup();
    const descriptor = s.connector.session(SID)!;
    vi.spyOn(s.connector, 'session').mockImplementation((id) => id === SID
      ? { ...descriptor, busy: false, continuityLost: true } : undefined);
    expect(s.delivery.available(SID)).toBe(false);
    expect(s.registry.canMessage(HANDLE)).toBe(false);
    expect(s.registry.send({ agentId: HANDLE, text: 'not proven idle' })).toMatchObject({ error: 'unavailable' });
    expect(s.frames()).toEqual([]);
  });

  it.each(['terminal', 'timeout', 'shutdown'] as const)('removes terminal subscriptions after %s and does not retain an active control', async (ending) => {
    const s = await setup({}, 60);
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.aborts.set(SID, 'aborted');
    const subscribe = s.connector.onEvent.bind(s.connector);
    const unsubscribe = vi.fn();
    vi.spyOn(s.connector, 'onEvent').mockImplementation((listener) => {
      const dispose = subscribe(listener);
      return () => { unsubscribe(); dispose(); };
    });
    const stopped = s.delivery.stopSession(SID).then(() => 'delivered', () => 'failed');
    await until(() => expect(s.gateway.requests.some((request) => request.method === 'POST')).toBe(true));
    if (ending === 'terminal') s.socket().send({ type: 'done' });
    if (ending === 'shutdown') s.delivery.stop();
    expect(await stopped).toBe(ending === 'terminal' ? 'delivered' : 'failed');
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    if (ending !== 'shutdown') expect(s.delivery.blocked(SID)).toBe(false);
  });

  it('disconnect never replays accepted text; reconnect fails old followUps and blocks new text until proof', async () => {
    const s = await setup();
    s.gateway.states.set(SID, 'running');
    const delivered = s.send('never replay');
    await s.status(delivered, 'delivered');
    const waiting = s.send('old followUp');
    await until(() => expect(s.socket().messages).toHaveLength(1));
    s.socket().drop();
    await until(() => expect(s.delivery.available(SID)).toBe(false));
    await until(() => expect(s.gateway.connections).toHaveLength(2));
    await until(() => expect(s.connector.session(SID)?.connected).toBe(true));
    await s.source.flush();
    await s.status(waiting, 'failed');
    expect(s.registry.get(delivered)?.status).toBe('delivered');
    expect(s.frames().map((frame) => frame.content)).toEqual(['never replay']);
    expect(s.registry.canMessage(HANDLE)).toBe(false);
    expect(s.registry.send({ agentId: HANDLE, text: 'blocked' })).toMatchObject({ error: 'unavailable' });
    s.gateway.aborts.set(SID, 'no_active_response');
    const stop = accepted(s.registry.control({ agentId: HANDLE, stop: true }));
    await s.status(stop, 'delivered');
    expect(s.connector.session(SID)).toMatchObject({ busy: false, continuityLost: false });
    expect(s.registry.canMessage(HANDLE)).toBe(true);
    expect(s.frames()).toHaveLength(1);
  });

  it.each(['aborted', 'done'] as const)('STOP waits for %s, then releases exactly one followUp', async (terminal) => {
    const s = await setup();
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.aborts.set(SID, 'aborted');
    const one = s.send('after stop');
    const two = s.send('after next terminal');
    const stop = accepted(s.registry.control({ agentId: HANDLE, stop: true }));
    await until(() => expect(s.gateway.requests.filter((request) => request.method === 'POST')).toEqual([
      expect.objectContaining({ path: `/api/sessions/${SID}/abort` }),
    ]));
    expect(s.registry.get(stop)?.status).toBe('sent');
    expect(s.socket().messages).toHaveLength(1);
    s.socket().send({ type: terminal });
    await s.status(stop, 'delivered');
    await s.status(one, 'delivered');
    expect(s.registry.get(two)?.status).toBe('queued');
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.frames().some((frame) => frame.type === 'stop')).toBe(false);
  });

  it('early done keeps dispatch locked until delayed abort HTTP response settles', async () => {
    const s = await setup();
    await s.busy();
    const response = Promise.withResolvers<void>();
    s.gateway.responses.set(`POST /api/sessions/${SID}/abort`, { body: { status: 'aborted' }, wait: response.promise });
    const waiting = s.send('after abort response only');
    const stop = accepted(s.registry.control({ agentId: HANDLE, stop: true }));
    await until(() => expect(s.gateway.requests.some((request) => request.method === 'POST')).toBe(true));
    s.socket().send({ type: 'done' });
    await until(() => expect(s.connector.session(SID)?.busy).toBe(false));
    s.registry.tick();
    expect(s.registry.get(stop)?.status).toBe('sent');
    expect(s.registry.get(waiting)?.status).toBe('queued');
    expect(s.frames()).toHaveLength(1);
    response.resolve();
    await s.status(stop, 'delivered');
    await s.status(waiting, 'delivered');
    await until(() => expect(s.frames().map((frame) => frame.content)).toEqual(['initial turn', 'after abort response only']));
  });

  it('subscribes before abort so an immediate terminal frame cannot be missed', async () => {
    const s = await setup();
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.options.abortFrame = true;
    await expect(s.delivery.stopSession(SID)).resolves.toBeUndefined();
    expect(s.connector.session(SID)?.busy).toBe(false);
    expect(s.gateway.requests.filter((request) => request.method === 'POST')).toHaveLength(1);
    expect(s.frames().every((frame) => frame.type === 'message')).toBe(true);
  });

  it('no_active_response proves terminal without frame and does not open an idle turn', async () => {
    const s = await setup();
    await s.busy();
    s.gateway.aborts.set(SID, 'no_active_response');
    await expect(s.delivery.stopSession(SID)).resolves.toBeUndefined();
    expect(s.connector.session(SID)?.busy).toBe(false);
    const before = s.gateway.requests.length;
    await expect(s.delivery.stopSession(SID)).resolves.toBeUndefined();
    expect(s.gateway.requests).toHaveLength(before);
    expect(s.frames()).toEqual([{ type: 'message', content: 'initial turn' }]);
  });

  it.each(['idle', 'error'] as const)('HTTP %s proves termination after accepted abort without terminal frame', async (state) => {
    const s = await setup();
    await s.busy();
    s.gateway.aborts.set(SID, 'aborted');
    s.gateway.states.set(SID, state);
    await expect(s.delivery.stopSession(SID)).resolves.toBeUndefined();
    expect(s.connector.session(SID)).toMatchObject({ busy: false, continuityLost: false });
    expect(s.gateway.requests).toContainEqual(expect.objectContaining({ method: 'GET', path: `/api/sessions/${SID}/state` }));
  });

  it.each(['running', '404', 'invalid', 'wrong-uuid'] as const)('%s state never fabricates terminal success', async (response) => {
    const s = await setup({}, 60);
    await s.busy();
    s.gateway.aborts.set(SID, 'aborted');
    s.gateway.states.set(SID, 'running');
    if (response !== 'running') s.gateway.responses.set(`/api/sessions/${SID}/state`, response === '404'
      ? { status: 404, body: {} }
      : { body: response === 'wrong-uuid' ? { session_id: OTHER, state: 'idle' } : { state: 'unknown' } });
    await expect(s.delivery.stopSession(SID)).rejects.toThrow();
    expect(s.connector.session(SID)?.busy).toBe(true);
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.frames()).toHaveLength(1);
  });

  it.each(['post-refused', 'timeout', 'disconnect'] as const)('%s cannot falsely settle STOP as delivered', async (failure) => {
    const s = await setup({}, 60);
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.aborts.set(SID, 'aborted');
    if (failure === 'post-refused') s.gateway.responses.set(`POST /api/sessions/${SID}/abort`, { status: 503, body: {} });
    if (failure === 'timeout') s.gateway.responses.set(`POST /api/sessions/${SID}/abort`, { body: { status: 'aborted' }, delayMs: 150 });
    const stop = accepted(s.registry.control({ agentId: HANDLE, stop: true }));
    if (failure === 'disconnect') {
      await until(() => expect(s.gateway.requests.some((request) => request.method === 'POST')).toBe(true));
      s.socket().drop();
    }
    await s.status(stop, 'failed');
    expect(s.frames().some((frame) => frame.type === 'stop')).toBe(false);
  });

  it('another UUID terminal cannot resolve STOP; concurrent mutation cannot issue two aborts', async () => {
    const s = await setup();
    await s.connector.openSession({ ...open, sessionId: OTHER, officeAgentId: 'other-handle' });
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.aborts.set(SID, 'aborted');
    const stop = s.delivery.stopSession(SID);
    const concurrent = s.delivery.stopSession(SID).then(() => 'resolved', () => 'rejected');
    await until(() => expect(s.gateway.requests.some((request) => request.method === 'POST')).toBe(true));
    s.gateway.connections.find((connection) => connection.sessionId === OTHER)!.send({ type: 'done' });
    await s.source.flush();
    expect(s.delivery.blocked(SID)).toBe(true);
    s.socket().send({ type: 'aborted' });
    await stop;
    await concurrent;
    expect(s.gateway.requests.filter((request) => request.method === 'POST')).toHaveLength(1);
  });

  it('cancel before dismissal prevents followUp release; closing rejects public I/O but allows internal stop', async () => {
    const s = await setup();
    await s.busy();
    const pending = s.send('must not run while dismissing');
    s.delivery.cancelSession(SID, 'dismissed');
    s.closing.add(SID);
    await s.status(pending, 'failed');
    const before = s.gateway.requests.length;
    expect(s.registry.send({ agentId: HANDLE, text: 'new text' })).toMatchObject({ error: 'unavailable' });
    expect(s.registry.control({ agentId: HANDLE, stop: true })).toMatchObject({ error: 'unavailable' });
    expect(s.registry.control({ agentId: HANDLE, command: 'new' })).toMatchObject({ error: 'unavailable' });
    expect(s.gateway.requests).toHaveLength(before);
    s.gateway.aborts.set(SID, 'no_active_response');
    await expect(s.delivery.stopSession(SID)).resolves.toBeUndefined();
    expect(s.frames().map((frame) => frame.content)).toEqual(['initial turn']);
  });
});

// Groups 9–10: attachment ownership and captured-session validation.
describe('ZeroClaw attachments and session guards', () => {
  it('writes attachments before dispatch and sends only absolute paths in prompt', async () => {
    const s = await setup();
    const store = new AttachmentStore({ dir: join(s.dataDir, 'uploads') });
    const writing = Promise.withResolvers<{ paths: string[] }>();
    const registry = new MessageRegistry({ office: s.office, zeroclaw: s.delivery, attachments: store, writeAttachments: () => writing.promise });
    cleanup.push(() => registry.stop());
    const upload = await uploadAttachment(store, 'private file contents');
    const id = accepted(registry.send({ agentId: HANDLE, text: 'read file', attachments: [upload.id] }));
    expect(registry.get(id)?.status).toBe('queued');
    expect(s.frames()).toEqual([]);
    const paths = ['/habblaud/test/.habblaud/attachments/uuid/nota.txt'];
    writing.resolve({ paths });
    await until(() => expect(registry.get(id)?.status).toBe('delivered'));
    await until(() => expect(s.socket().messages).toEqual([{ type: 'message', content: promptWithAttachments('read file', paths) }]));
    expect(JSON.stringify(s.frames())).not.toContain('private file contents');
    expect(JSON.stringify(s.frames())).not.toContain(Buffer.from('private file contents').toString('base64'));
  });

  it.each(['changed', 'gone', 'timeout', 'disk'] as const)('%s during attachment write releases reservation once and never sends late', async (cause) => {
    const s = await setup();
    const store = new AttachmentStore({ dir: join(s.dataDir, 'uploads'), maxBytes: 8 });
    const writing = Promise.withResolvers<{ paths: string[] } | { error: string; status: number }>();
    const registry = new MessageRegistry({ office: s.office, zeroclaw: s.delivery, attachments: store,
      writeAttachments: () => writing.promise, now: s.clock.now });
    cleanup.push(() => registry.stop());
    const id = accepted(registry.send({ agentId: HANDLE, text: '', attachments: [(await uploadAttachment(store)).id] }));
    await expect(uploadAttachment(store)).rejects.toHaveProperty('status', 429);
    if (cause === 'changed') s.office.switchSession(HANDLE, OTHER);
    if (cause === 'gone') s.office.closeMain(HANDLE);
    if (cause === 'timeout') { s.clock.advance(QUEUED_TIMEOUT_MS); registry.tick(); }
    writing.resolve(cause === 'disk' ? { error: 'disk full', status: 500 } : { paths: ['/habblaud/test/nota.txt'] });
    await until(() => expect(registry.get(id)?.status).toBe('failed'));
    registry.tick();
    const fresh = await uploadAttachment(store);
    expect(fresh).toHaveProperty('id');
    // A second release would undercount this upload and incorrectly admit another one.
    await expect(uploadAttachment(store)).rejects.toHaveProperty('status', 429);
    s.socket().send({ type: 'done' });
    await s.source.flush();
    expect(s.frames()).toEqual([]);
    expect(registry.get(id)?.status).toBe('failed');
  });

  it('stale expectedSessionId rejects text and controls before attachments or I/O', async () => {
    const s = await setup();
    const store = new AttachmentStore({ dir: join(s.dataDir, 'uploads') });
    const writeAttachments = vi.fn(async () => ({ paths: ['/habblaud/test/nota.txt'] }));
    const registry = new MessageRegistry({ office: s.office, zeroclaw: s.delivery, attachments: store, writeAttachments });
    cleanup.push(() => registry.stop());
    const upload = await uploadAttachment(store);
    const before = s.gateway.requests.length;
    const changed = { error: 'unavailable', code: 'session_changed', reason: ERR_SESSION_CHANGED };
    expect(registry.send({ agentId: HANDLE, text: '', attachments: [upload.id], expectedSessionId: OTHER })).toEqual(changed);
    expect(registry.control({ agentId: HANDLE, stop: true, expectedSessionId: OTHER })).toEqual(changed);
    expect(registry.control({ agentId: HANDLE, command: 'new', expectedSessionId: OTHER })).toEqual(changed);
    expect(writeAttachments).not.toHaveBeenCalled();
    expect(store.take([upload.id], 5, 'messages')).toHaveLength(1);
    expect(s.gateway.requests).toHaveLength(before);
    expect(s.frames()).toEqual([]);
  });

  it('UUID switch after admission fails queued text instead of dispatching into the new context', async () => {
    const s = await setup();
    await s.busy();
    const id = s.send('captured old context');
    s.office.switchSession(HANDLE, OTHER);
    s.registry.tick();
    expect(s.registry.get(id)).toMatchObject({ status: 'failed', error: ERR_SESSION_CHANGED });
    s.socket().send({ type: 'done' });
    await s.source.flush();
    expect(s.frames()).toHaveLength(1);
  });
});

// Groups 12–13: replacement changes context, never character identity or old history.
describe('ZeroClaw new conversation', () => {
  it('waits for session_start, preserves identity and alias, resets tasks/stats, and retains old transcript', async () => {
    const s = await setup();
    const oldMessage = s.send('old history');
    await s.status(oldMessage, 'delivered');
    s.socket().send({ type: 'done', full_response: 'old answer', input_tokens: 100, output_tokens: 20 });
    await until(() => expect(s.connector.session(SID)?.busy).toBe(false));
    await s.source.flush();
    const agent = s.office.get(HANDLE)!;
    agent.tasks = [{ id: 'old-task', title: 'Old work', status: 'pending' }];
    agent.stats = { toolCalls: 3, tokensIn: 100, tokensOut: 20, subagents: 1 };
    const before = structuredClone(s.office.get(HANDLE)!);
    const oldPath = s.source.transcriptPathOf(HANDLE)!;
    const oldTranscript = readFileSync(oldPath, 'utf8');
    s.gateway.options.autoStart = false;
    const id = accepted(s.registry.control({ agentId: HANDLE, command: 'new' }));
    await until(() => expect(s.gateway.connections).toHaveLength(2));
    const replacement = s.gateway.connections[1];
    expect(replacement.sessionId).not.toBe(SID);
    expect(s.registry.get(id)?.status).toBe('sent');
    expect(replacement.messages).toEqual([]);
    replacement.send({ type: 'session_start', session_id: replacement.sessionId, resumed: false, message_count: 0 });
    await s.status(id, 'delivered');
    await s.source.flush();
    expect(s.office.list()).toHaveLength(1);
    expect(s.office.get(HANDLE)).toMatchObject({ id: HANDLE, sessionId: replacement.sessionId,
      name: before.name, look: before.look, roomId: before.roomId, tasks: [] });
    expect(s.office.get(HANDLE)?.cast).toBeUndefined();
    expect(s.connector.session(replacement.sessionId)).toMatchObject({ agent: open.agent, name: open.name, cwd: open.cwd,
      cast: open.cast, officeAgentId: HANDLE, connected: true });
    expect(s.office.get(HANDLE)?.stats).toEqual({ toolCalls: 0, tokensIn: 0, tokensOut: 0, subagents: 0 });
    expect(readFileSync(oldPath, 'utf8')).toBe(oldTranscript);
    expect(s.gateway.requests.some((request) => request.method === 'DELETE')).toBe(false);
    expect(s.frames().some((frame) => frame.content === '/new' || frame.type === 'stop')).toBe(false);
  });

  it('busy /new cancels old followUps before fast aborted and confirms only the new UUID', async () => {
    const s = await setup();
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.gateway.options.abortFrame = true;
    const waiting = s.send('do not migrate');
    const id = accepted(s.registry.control({ agentId: HANDLE, command: 'new' }));
    await s.status(id, 'delivered');
    expect(s.registry.get(waiting)).toMatchObject({ status: 'failed', error: ERR_SESSION_CHANGED });
    expect(s.gateway.connections).toHaveLength(2);
    expect(s.frames().map((frame) => frame.content)).toEqual(['initial turn']);
    expect(s.office.get(HANDLE)?.sessionId).not.toBe(SID);
    expect(s.onSettled.mock.calls.filter(([settledId]) => settledId === id)).toHaveLength(1);
  });

  it('continuity-lost /new uses no_active_response without waiting for nonexistent aborted frame', async () => {
    const s = await setup();
    await s.busy();
    s.gateway.states.set(SID, 'running');
    s.socket().drop();
    await until(() => expect(s.gateway.connections).toHaveLength(2));
    await until(() => expect(s.connector.session(SID)?.connected).toBe(true));
    await s.source.flush();
    expect(s.registry.canMessage(HANDLE)).toBe(false);
    s.gateway.aborts.set(SID, 'no_active_response');
    const id = accepted(s.registry.control({ agentId: HANDLE, command: 'new' }));
    await s.status(id, 'delivered');
    expect(s.office.get(HANDLE)?.sessionId).not.toBe(SID);
    expect(s.frames()).toHaveLength(1);
  });

  it('failed abort never closes old session or opens a replacement', async () => {
    const s = await setup();
    await s.busy();
    s.gateway.responses.set(`POST /api/sessions/${SID}/abort`, { status: 500, body: {} });
    const close = vi.spyOn(s.connector, 'closeSession');
    const id = accepted(s.registry.control({ agentId: HANDLE, command: 'new' }));
    await s.status(id, 'failed');
    expect(close).not.toHaveBeenCalled();
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.office.get(HANDLE)?.sessionId).toBe(SID);
    expect(s.replacementFailed).not.toHaveBeenCalled();
  });

  it('failed new handshake closes character through callback instead of leaving a connected ghost', async () => {
    const s = await setup();
    s.gateway.onConnection = (connection) => {
      if (connection.sessionId !== SID) connection.send({ type: 'error', code: 'unknown_agent', message: 'alias unavailable' });
    };
    s.gateway.options.autoStart = false;
    const id = accepted(s.registry.control({ agentId: HANDLE, command: 'new' }));
    await s.status(id, 'failed');
    await s.source.flush();
    expect(s.replacementFailed).toHaveBeenCalledExactlyOnceWith(HANDLE);
    expect(s.office.get(HANDLE)?.status).toBe('offline');
    expect(s.connector.session(SID)).toBeUndefined();
    expect(s.frames()).toEqual([]);
  });

  it('concurrent /new never opens two replacements; a different session remains usable', async () => {
    const s = await setup();
    await s.connector.openSession({ ...open, sessionId: OTHER, officeAgentId: 'other-handle' });
    await s.source.flush();
    s.gateway.options.autoStart = false;
    const first = s.delivery.newSession(SID);
    const second = s.delivery.newSession(SID).then((id) => ({ id }), () => ({ rejected: true }));
    await until(() => expect(s.gateway.connections).toHaveLength(3));
    expect(await s.delivery.sendMessage(OTHER, 'parallel while replacing')).toEqual({ ok: true });
    const replacement = s.gateway.connections[2];
    replacement.send({ type: 'session_start', session_id: replacement.sessionId, resumed: false });
    expect(await first).toBe(replacement.sessionId);
    const otherResult = await second;
    if ('id' in otherResult) expect(otherResult.id).toBe(replacement.sessionId);
    expect(s.gateway.connections).toHaveLength(3);
    expect(s.gateway.connections.filter((connection) => connection.sessionId !== SID && connection.sessionId !== OTHER)).toHaveLength(1);
  });
});

// Groups 14–16. HTTP guards/status and messages-disabled composition are tested in http.test.ts (group 17).
describe('ZeroClaw rejection, lifetime and provider isolation', () => {
  it('text and attachment limits reject before upload consumption or transport', async () => {
    const s = await setup();
    const store = new AttachmentStore({ dir: join(s.dataDir, 'uploads') });
    const writeAttachments = vi.fn(async () => ({ paths: ['/habblaud/test/nota.txt'] }));
    const registry = new MessageRegistry({ office: s.office, zeroclaw: s.delivery, attachments: store, writeAttachments });
    cleanup.push(() => registry.stop());
    expect(() => registry.send({ agentId: HANDLE, text: '  \n ' })).toThrow();
    expect(() => registry.send({ agentId: HANDLE, text: 'x'.repeat(20_001) })).toThrow();
    const ids: string[] = [];
    for (let index = 0; index < 6; index++) ids.push((await uploadAttachment(store)).id);
    expect(() => registry.send({ agentId: HANDLE, text: '', attachments: ids })).toThrow();
    expect(store.take(ids, 6, 'messages')).toHaveLength(6);
    expect(writeAttachments).not.toHaveBeenCalled();
    expect(s.frames()).toEqual([]);
    const id = accepted(registry.send({ agentId: HANDLE, text: 'x'.repeat(20_000) }));
    await until(() => expect(registry.get(id)?.status).toBe('delivered'));
  });

  it('unsupported controls and both aside paths do no I/O, create no entry or activity, and do not consume attachments', async () => {
    const s = await setup();
    const before = structuredClone(s.office.get(HANDLE)?.recent);
    const requests = s.gateway.requests.length;
    for (const change of [{ model: 'anthropic/test' }, { thinking: 'high' }, { command: 'shake' },
      { command: 'advisorOn' }, { command: 'advisorOff' }, { resume: OTHER }]) {
      expect(s.registry.control({ agentId: HANDLE, ...change })).toMatchObject({ error: 'unavailable' });
    }
    for (const change of [{ stop: true, command: 'new' }, { stop: true, model: 'test' }, { command: 'new', thinking: 'high' }]) {
      expect(s.registry.control({ agentId: HANDLE, ...change })).toMatchObject({ error: 'unavailable' });
    }
    const store = new AttachmentStore({ dir: join(s.dataDir, 'uploads') });
    const writeAttachments = vi.fn(async () => ({ paths: ['/habblaud/test/nota.txt'] }));
    const registry = new MessageRegistry({ office: s.office, zeroclaw: s.delivery, attachments: store, writeAttachments });
    cleanup.push(() => registry.stop());
    const upload = await uploadAttachment(store);
    expect(() => registry.send({ agentId: HANDLE, text: 'aside', deliverAs: 'aside', attachments: [upload.id] })).toThrow();
    expect(store.take([upload.id], 5, 'messages')).toHaveLength(1);
    expect(writeAttachments).not.toHaveBeenCalled();
    const request = { topicId: 'topic', turnId: 'turn', index: 0, question: 'parallel?', history: [] };
    expect(registry.aside(HANDLE, request)).toMatchObject({ error: 'unavailable' });
    const asides = new AsideStore({ dir: null, office: s.office, queue: (id, value) => registry.aside(id, value), discard: (id) => registry.discard(id) });
    expect(asides.ask({ agentId: HANDLE, question: 'parallel?' })).not.toHaveProperty('topic');
    expect(s.gateway.requests).toHaveLength(requests);
    expect(s.gateway.connections).toHaveLength(1);
    expect(s.frames()).toEqual([]);
    expect(s.office.get(HANDLE)?.recent).toEqual(before);
    expect(s.onSettled).not.toHaveBeenCalled();
  });

  it('MAX_OPEN, queued expiry and retention stay registry-owned; late terminal cannot deliver expired text', async () => {
    const s = await setup();
    await s.busy();
    const ids = Array.from({ length: MAX_OPEN }, (_, index) => s.send(`waiting ${index}`));
    expect(s.registry.send({ agentId: HANDLE, text: 'overflow' })).toEqual({ error: 'too-many' });
    s.clock.advance(QUEUED_TIMEOUT_MS - 1);
    s.registry.tick();
    expect(ids.every((id) => s.registry.get(id)?.status === 'queued')).toBe(true);
    s.clock.advance(1);
    s.registry.tick();
    for (const id of ids) {
      expect(s.registry.get(id)?.status).toBe('failed');
      expect(s.registry.get(id)?.error).not.toMatch(/plugin|Codex/);
    }
    s.socket().send({ type: 'done' });
    await until(() => expect(s.connector.session(SID)?.busy).toBe(false));
    expect(s.frames()).toHaveLength(1);
    s.clock.advance(KEEP_MS);
    s.registry.tick();
    expect(ids.every((id) => s.registry.get(id) === undefined)).toBe(true);
  });

  it('sent timeout cannot be repaired by late transport or plugin/bridge ack and never retries', async () => {
    const s = await setup();
    const buffer = Promise.withResolvers<void>();
    const transport = vi.spyOn(s.connector, 'sendMessage').mockReturnValueOnce(buffer.promise);
    const id = s.send('uncertain acceptance');
    await s.status(id, 'sent');
    s.clock.advance(SENT_TIMEOUT_MS - 1);
    s.registry.tick();
    expect(s.registry.get(id)?.status).toBe('sent');
    s.clock.advance(1);
    s.registry.tick();
    expect(s.registry.get(id)?.status).toBe('failed');
    expect(s.registry.get(id)?.error).not.toMatch(/plugin|Codex/);
    buffer.resolve();
    await Promise.resolve();
    s.registry.ack({ session: SID, results: [{ id, ok: true }] });
    s.registry.codexAck({ results: [{ id, ok: true }] });
    s.registry.tick();
    expect(s.registry.get(id)?.status).toBe('failed');
    expect(transport).toHaveBeenCalledTimes(1);
    expect(s.onSettled).toHaveBeenCalledTimes(1);
  });

  it('presence follows socket lifecycle, marks snapshots dirty, and never requires OMP polling', async () => {
    const s = await setup();
    expect(s.registry.canMessage(HANDLE)).toBe(true);
    expect(s.registry.reachable()).toContain(HANDLE);
    s.office.commit();
    s.socket().drop();
    await until(() => expect(s.registry.canMessage(HANDLE)).toBe(false));
    expect(s.office.commit().changed).toBe(true);
    expect(s.registry.send({ agentId: HANDLE, text: 'offline' })).toMatchObject({ error: 'unavailable', reason: expect.not.stringMatching(/plugin|omp:install/) });
    await until(() => expect(s.gateway.connections).toHaveLength(2));
    await until(() => expect(s.registry.canMessage(HANDLE)).toBe(true));
    await s.source.flush();
    expect(s.office.commit().changed).toBe(true);
  });

  it('without delivery or after office departure ZeroClaw stays unavailable with provider-specific reason', async () => {
    const s = await setup();
    const noDelivery = new MessageRegistry({ office: s.office });
    cleanup.push(() => noDelivery.stop());
    expect(noDelivery.canMessage(HANDLE)).toBe(false);
    expect(noDelivery.send({ agentId: HANDLE, text: 'no adapter' })).toMatchObject({ error: 'unavailable', reason: expect.not.stringMatching(/plugin|Codex|omp:install/) });
    await s.busy();
    const waiting = s.send('gone');
    s.office.closeMain(HANDLE);
    s.registry.tick();
    expect(s.registry.get(waiting)?.status).toBe('failed');
    expect(s.registry.canMessage(HANDLE)).toBe(false);
  });

  it('plugin inbox/ack and Codex poll/ack cannot see or settle ZeroClaw, even with a colliding UUID', async () => {
    const s = await setup({ codex: { homeOf: () => '/test/.codex' } });
    s.office.addMain({ id: 'omp-collision', provider: 'omp', account: '.omp', sessionId: SID, cwd: '/omp', role: 'main', startedAt: 0, status: 'idle' });
    s.office.addMain({ id: 'codex-collision', provider: 'codex', account: '.codex', sessionId: SID, cwd: '/codex', role: 'main', startedAt: 0, status: 'idle' });
    await s.busy();
    const queued = s.send('zeroclaw only');
    expect(s.registry.inbox({ session: SID })).toEqual([]);
    expect(s.registry.codexPoll({})).toEqual([]);
    s.registry.ack({ session: SID, results: [{ id: queued, ok: true }] });
    s.registry.codexAck({ results: [{ id: queued, ok: true }] });
    expect(s.registry.get(queued)?.status).toBe('queued');
    s.socket().send({ type: 'done' });
    await s.status(queued, 'delivered');
    s.registry.ack({ session: SID, results: [{ id: queued, ok: false, error: 'wrong provider' }] });
    s.registry.codexAck({ results: [{ id: queued, ok: false, error: 'wrong provider' }] });
    expect(s.registry.get(queued)?.status).toBe('delivered');
    await until(() => expect(s.frames().filter((frame) => frame.content === 'zeroclaw only')).toHaveLength(1));
    const buffer = Promise.withResolvers<void>();
    vi.spyOn(s.connector, 'sendMessage').mockReturnValueOnce(buffer.promise);
    const sending = s.send('awaiting ZeroClaw buffer', 'steer');
    await s.status(sending, 'sent');
    expect(s.registry.inbox({ session: SID })).toEqual([]);
    expect(s.registry.codexPoll({})).toEqual([]);
    s.registry.ack({ session: SID, results: [{ id: sending, ok: true }] });
    s.registry.codexAck({ results: [{ id: sending, ok: true }] });
    expect(s.registry.get(sending)?.status).toBe('sent');
    buffer.resolve();
    await s.status(sending, 'delivered');
  });
});
