import { randomUUID } from 'node:crypto';
import { mkdir, readFile, rename, writeFile, unlink } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';

export type OpenSession = { sessionId: string; agent: string; name: string; cwd: string; cast?: string; officeAgentId?: string };
export type SessionDescriptor = OpenSession & { connected: boolean; busy: boolean; continuityLost: boolean };
export type ConnectorFrame = { type: string; [key: string]: unknown };
export type ConnectorEvent =
  | { type: 'session-open'; sessionId: string; descriptor: SessionDescriptor; resumed: boolean; continuityLost: boolean }
  | { type: 'session-close'; sessionId: string; intentional: boolean; replacement: boolean; code: number; reason: string }
  | { type: 'frame'; sessionId: string; frame: ConnectorFrame }
  | { type: 'message-sent'; sessionId: string; id: string; content: string }
  | { type: 'turn-state'; sessionId: string; busy: boolean; continuityLost: boolean; terminalConfirmed: boolean; source: string }
  | { type: 'diagnostic'; sessionId?: string; message: string };

type Timer = ReturnType<typeof setTimeout>;
export type ConnectorOptions = {
  url: string; dataDir: string;
  metadataOf?: (id: string) => OpenSession | undefined;
  beforeOpen?: (input: OpenSession) => Promise<void>;
  timeoutMs?: number;
  jitter?: () => number;
  timers?: { setTimeout: typeof setTimeout; clearTimeout: typeof clearTimeout; now?: () => number };
};
type Entry = {
  descriptor: SessionDescriptor; generation: number; turn: number; attempts: number; blocked: boolean;
  socket?: WebSocket; opening?: Promise<void>; retry?: Timer; poll?: Timer;
  controllers: Set<AbortController>; cancelOpen?: () => void;
};
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function isSessionId(value: unknown): value is string { return typeof value === 'string' && UUID.test(value); }
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function validate(value: unknown): asserts value is OpenSession {
  if (!object(value) || typeof value.sessionId !== 'string' || !UUID.test(value.sessionId)
    || typeof value.agent !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(value.agent)
    || typeof value.name !== 'string' || !value.name.trim() || /[\x00-\x1f]/.test(value.name)
    || typeof value.cwd !== 'string' || !isAbsolute(value.cwd) || /[\x00-\x1f]/.test(value.cwd)
    || ['cast', 'officeAgentId'].some((key) => value[key] !== undefined && (typeof value[key] !== 'string' || !value[key] || /[\x00-\x1f]/.test(value[key] as string)))) {
    throw new Error('Invalid ZeroClaw session metadata');
  }
}
function metadata(value: OpenSession): OpenSession {
  return { sessionId: value.sessionId, agent: value.agent, name: value.name, cwd: value.cwd,
    ...(value.cast !== undefined ? { cast: value.cast } : {}), ...(value.officeAgentId !== undefined ? { officeAgentId: value.officeAgentId } : {}) };
}
class HttpError extends Error {
  constructor(readonly status: number, readonly retryAfter: number) { super(`ZeroClaw HTTP ${status}`); }
}
/** O gateway respondeu 404: sem registro da sessão ou do turno (não prova que nada roda; persistência pode falhar). */
export function isNotFound(error: unknown): boolean { return error instanceof HttpError && error.status === 404; }

export class ZeroClawConnector {
  private readonly entries = new Map<string, Entry>();
  private readonly listeners = new Set<(event: ConnectorEvent) => void | Promise<void>>();
  private readonly controllers = new Set<AbortController>();
  private readonly file: string;
  private readonly base: URL;
  private readonly timers: NonNullable<ConnectorOptions['timers']>;
  private writes: Promise<unknown> = Promise.resolve();
  private boot?: Promise<void>;
  private loaded?: Promise<void>;
  private stopped = false;
  private epoch = 0;
  constructor(private readonly options: ConnectorOptions) {
    this.base = new URL(options.url);
    if (this.base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(this.base.hostname)
      || this.base.username || this.base.password || this.base.search || this.base.hash || this.base.pathname !== '/') throw new Error('Invalid ZeroClaw loopback URL');
    this.file = join(options.dataDir, 'zeroclaw', 'open-sessions.json');
    this.timers = options.timers ?? { setTimeout, clearTimeout };
  }
  private emit(event: ConnectorEvent): void {
    for (const listener of this.listeners) {
      try { void Promise.resolve(listener(event)).catch(() => undefined); } catch { /* A consumer cannot break transport lifecycle. */ }
    }
  }
  private async emitAwaited(event: ConnectorEvent): Promise<void> {
    try { await Promise.all([...this.listeners].map((listener) => listener(event))); }
    catch (error) { this.diagnostic('ZeroClaw accepted message could not be persisted; do not resend automatically', 'sessionId' in event ? event.sessionId : undefined); throw error; }
  }
  private diagnostic(message: string, sessionId?: string): void { this.emit({ type: 'diagnostic', sessionId, message }); }
  onEvent(listener: (event: ConnectorEvent) => void | Promise<void>): () => void { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  session(id: string): SessionDescriptor | undefined { const entry = this.entries.get(id); return entry ? { ...entry.descriptor } : undefined; }
  openSessions(): SessionDescriptor[] { return [...this.entries.values()].map((entry) => ({ ...entry.descriptor })); }
  metadataOf(id: string): OpenSession | undefined { const local = this.options.metadataOf?.(id); return local ?? (this.entries.has(id) ? metadata(this.entries.get(id)!.descriptor) : undefined); }
  private make(input: OpenSession, restored: boolean): Entry {
    return { descriptor: { ...metadata(input), connected: false, busy: restored, continuityLost: restored }, generation: 0, turn: 0, attempts: 0, blocked: false, controllers: new Set() };
  }
  start(): Promise<void> {
    if (this.boot) return this.boot;
    const epoch = this.epoch;
    this.loaded = (async () => {
      let raw: string;
      try { raw = await readFile(this.file, 'utf8'); } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw error; }
      const saved: unknown = JSON.parse(raw);
      if (!object(saved) || saved.version !== 1 || !Array.isArray(saved.sessions)) throw new Error('Invalid ZeroClaw intentions file');
      const restored = new Map<string, Entry>();
      for (const input of saved.sessions) { validate(input); if (restored.has(input.sessionId)) throw new Error('Duplicate ZeroClaw intention'); restored.set(input.sessionId, this.make(input, true)); }
      if (this.stopped || epoch !== this.epoch) return;
      for (const [id, entry] of restored) this.entries.set(id, entry);
    })();
    this.boot = this.loaded.then(async () => {
      await Promise.all([...this.entries.values()].map((entry) => this.connect(entry).catch(() => undefined)));
    });
    return this.boot;
  }
  private serialize<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.writes.then(operation);
    this.writes = next.catch(() => undefined);
    return next;
  }
  private async persist(sessions: OpenSession[]): Promise<void> {
    const directory = join(this.options.dataDir, 'zeroclaw');
    await mkdir(directory, { recursive: true });
    const temporary = `${this.file}.${randomUUID()}.tmp`;
    try { await writeFile(temporary, JSON.stringify({ version: 1, sessions }) + '\n', { mode: 0o600 }); await rename(temporary, this.file); }
    catch (error) { await unlink(temporary).catch(() => undefined); throw error; }
  }
  async openSession(input: OpenSession): Promise<void> {
    validate(input);
    await this.start();
    const epoch = this.epoch;
    const entry = await this.serialize(async () => {
      if (this.stopped) throw new Error('ZeroClaw connector stopped');
      const old = this.entries.get(input.sessionId);
      if (old) {
        if (JSON.stringify(metadata(old.descriptor)) !== JSON.stringify(metadata(input))) throw new Error('Incompatible ZeroClaw session metadata');
        return old;
      }
      const entry = this.make(input, false);
      await this.persist([...this.entries.values()].map((value) => metadata(value.descriptor)).concat(metadata(input)));
      if (this.stopped || epoch !== this.epoch) throw new Error('ZeroClaw connector stopped');
      this.entries.set(input.sessionId, entry);
      return entry;
    });
    await this.connect(entry);
  }
  async closeSession(id: string, options?: { replacement?: true }): Promise<void> {
    if (!this.boot) void this.start().catch(() => undefined);
    await this.loaded;
    await this.serialize(async () => {
      const entry = this.entries.get(id);
      if (!entry) return;
      await this.persist([...this.entries.values()].filter((value) => value !== entry).map((value) => metadata(value.descriptor)));
      this.entries.delete(id);
      this.cancel(entry);
      this.emit({ type: 'session-close', sessionId: id, intentional: true, replacement: !!options?.replacement, code: 1000, reason: 'Closed locally' });
    });
  }
  stop(): void {
    if (this.stopped) return;
    this.stopped = true; this.epoch++;
    for (const controller of this.controllers) controller.abort();
    for (const entry of this.entries.values()) {
      this.cancel(entry);
      this.emit({ type: 'session-close', sessionId: entry.descriptor.sessionId, intentional: true, replacement: false, code: 1000, reason: 'Connector stopped' });
    }
  }
  private cancel(entry: Entry): void {
    entry.generation++;
    if (entry.retry) this.timers.clearTimeout(entry.retry);
    if (entry.poll) this.timers.clearTimeout(entry.poll);
    entry.retry = entry.poll = undefined;
    for (const controller of entry.controllers) controller.abort();
    entry.cancelOpen?.(); entry.cancelOpen = undefined;
    const socket = entry.socket; entry.socket = undefined; entry.descriptor.connected = false;
    if (socket) { try { socket.close(); } catch { /* Already closed. */ } }
  }
  private current(entry: Entry, generation: number): boolean { return !this.stopped && this.entries.get(entry.descriptor.sessionId) === entry && entry.generation === generation; }
  async request(path: string, init?: RequestInit): Promise<unknown> {
    const sessionId = /^\/api\/sessions\/([^/]+)\//.exec(path)?.[1];
    return this.http(path, init, sessionId ? this.entries.get(sessionId) : undefined);
  }
  private async http(path: string, init?: RequestInit, entry?: Entry): Promise<unknown> {
    if (this.stopped) throw new Error('ZeroClaw connector stopped');
    const url = new URL(path, this.base);
    if (!path.startsWith('/') || url.origin !== this.base.origin || url.username || url.password) throw new Error('Invalid ZeroClaw request path');
    const controller = new AbortController();
    const external = init?.signal;
    const abort = () => controller.abort();
    external?.addEventListener('abort', abort, { once: true });
    if (external?.aborted) controller.abort();
    this.controllers.add(controller); entry?.controllers.add(controller);
    const timeout = this.timers.setTimeout(abort, this.options.timeoutMs ?? 5_000);
    try {
      const response = await fetch(url, { ...init, redirect: 'error', signal: controller.signal });
      if (!response.ok) {
        const retry = response.headers.get('retry-after');
        const now = this.timers.now?.() ?? Date.now();
        const delay = retry === null ? 0 : /^\d+(?:\.\d+)?$/.test(retry) ? Number(retry) * 1000 : Math.max(0, Date.parse(retry) - now);
        await response.body?.cancel();
        throw new HttpError(response.status, Number.isFinite(delay) ? delay : 0);
      }
      return await response.json();
    } finally {
      this.timers.clearTimeout(timeout); this.controllers.delete(controller); entry?.controllers.delete(controller);
      external?.removeEventListener('abort', abort);
    }
  }
  async listSessions(): Promise<Record<string, unknown>[]> { return this.sessionList(await this.request('/api/sessions')); }
  private sessionList(value: unknown): Record<string, unknown>[] {
    const list = Array.isArray(value) ? value : object(value) ? value.sessions : undefined;
    if (!Array.isArray(list) || !list.every(object)) throw new Error('Invalid ZeroClaw sessions response');
    return list;
  }
  private connect(entry: Entry): Promise<void> {
    if (entry.descriptor.connected) return Promise.resolve();
    if (entry.opening) return entry.opening;
    if (entry.retry) return Promise.reject(new Error('ZeroClaw reconnect backoff pending'));
    if (this.stopped || entry.blocked) return Promise.reject(new Error('ZeroClaw connection unavailable'));
    const generation = ++entry.generation;
    const operation = this.establish(entry, generation).catch((error: unknown) => {
      if (this.current(entry, generation)) {
        this.diagnostic(error instanceof HttpError ? error.message : 'ZeroClaw connection failed', entry.descriptor.sessionId);
        if (error instanceof HttpError && (error.status === 401 || error.status === 403)) entry.blocked = true;
        this.retry(entry, error instanceof HttpError ? error.retryAfter : 0);
      }
      throw error;
    });
    const opening = operation.finally(() => { if (entry.opening === opening) entry.opening = undefined; });
    entry.opening = opening;
    return opening;
  }
  private async establish(entry: Entry, generation: number): Promise<void> {
    this.sessionList(await this.http('/api/sessions', undefined, entry));
    if (!this.current(entry, generation)) throw new Error('ZeroClaw opening cancelled');
    await this.options.beforeOpen?.(metadata(entry.descriptor));
    if (!this.current(entry, generation)) throw new Error('ZeroClaw opening cancelled');
    const url = new URL('/ws/chat', this.base); url.protocol = 'ws:';
    for (const [key, value] of Object.entries({ agent: entry.descriptor.agent, session_id: entry.descriptor.sessionId, name: entry.descriptor.name, cwd: entry.descriptor.cwd })) url.searchParams.set(key, value);
    const { promise, resolve, reject } = Promise.withResolvers<void>();
      const socket = new WebSocket(url); entry.socket = socket;
      let accepted = false; let settled = false;
      const finish = (error?: Error) => {
        if (settled) return; settled = true;
        this.timers.clearTimeout(timeout); entry.cancelOpen = undefined;
        if (error) reject(error); else resolve();
      };
      const timeout = this.timers.setTimeout(() => { finish(new Error('ZeroClaw session handshake timed out')); this.disconnect(entry, generation, 1006); }, this.options.timeoutMs ?? 5_000);
      entry.cancelOpen = () => finish(new Error('ZeroClaw opening cancelled'));
      socket.addEventListener('message', (event) => {
        if (!this.current(entry, generation) || entry.socket !== socket) return;
        let frame: unknown;
        try { frame = JSON.parse(String(event.data)); } catch { this.diagnostic('Invalid ZeroClaw frame', entry.descriptor.sessionId); return; }
        if (!object(frame) || typeof frame.type !== 'string' || !this.validFrame(frame)) { this.diagnostic('Invalid ZeroClaw frame', entry.descriptor.sessionId); return; }
        if (frame.type === 'session_start') {
          if (accepted || frame.session_id !== entry.descriptor.sessionId) {
            finish(new Error('Unexpected ZeroClaw session_start')); this.disconnect(entry, generation, 1002); return;
          }
          accepted = true; entry.descriptor.connected = true; entry.attempts = 0;
          this.emit({ type: 'session-open', sessionId: entry.descriptor.sessionId, descriptor: { ...entry.descriptor }, resumed: frame.resumed === true, continuityLost: entry.descriptor.continuityLost });
          this.emit({ type: 'frame', sessionId: entry.descriptor.sessionId, frame: frame as ConnectorFrame });
          finish();
          if (entry.descriptor.continuityLost) void this.refreshTurnState(entry.descriptor.sessionId).catch(() => this.schedulePoll(entry));
          return;
        }
        if (frame.type === 'error' && ['unknown_agent', 'agent_not_found', 'invalid_agent', 'alias_not_found', 'unauthorized', 'forbidden'].includes(String(frame.code))) {
          entry.blocked = true; this.diagnostic('ZeroClaw agent alias or authentication refused', entry.descriptor.sessionId);
          finish(new Error('ZeroClaw agent alias or authentication refused')); this.disconnect(entry, generation, 1008); return;
        }
        if (!accepted) { this.diagnostic('ZeroClaw frame before session_start', entry.descriptor.sessionId); return; }
        if (frame.type === 'done' || frame.type === 'aborted') {
          entry.turn++; entry.descriptor.busy = false; entry.descriptor.continuityLost = false;
          if (entry.poll) this.timers.clearTimeout(entry.poll);
          entry.poll = undefined;
        }
        this.emit({ type: 'frame', sessionId: entry.descriptor.sessionId, frame: frame as ConnectorFrame });
      });
      socket.addEventListener('error', () => { if (!this.current(entry, generation)) return; finish(new Error('ZeroClaw websocket failed')); this.disconnect(entry, generation, 1006); });
      socket.addEventListener('close', (event) => {
        if (!this.current(entry, generation)) return;
        finish(new Error('ZeroClaw websocket closed')); this.disconnect(entry, generation, event.code);
      });
    await promise;
  }
  private validFrame(frame: Record<string, unknown>): boolean {
    switch (frame.type) {
      case 'session_start': return typeof frame.session_id === 'string' && typeof frame.resumed === 'boolean';
      case 'chunk': case 'thinking': return typeof frame.content === 'string';
      case 'tool_call': return typeof frame.id === 'string' && typeof frame.name === 'string' && object(frame.args);
      case 'tool_result': return typeof frame.id === 'string' && frame.output !== undefined;
      case 'done': return frame.full_response === undefined || typeof frame.full_response === 'string';
      case 'error': return typeof frame.message === 'string';
      default: return true;
    }
  }
  private disconnect(entry: Entry, generation: number, code: number): void {
    if (!this.current(entry, generation)) return;
    const lost = entry.descriptor.busy || entry.descriptor.continuityLost;
    this.cancel(entry);
    if (lost) { entry.descriptor.busy = true; entry.descriptor.continuityLost = true; this.diagnostic('ZeroClaw turn continuity lost; events cannot be replayed', entry.descriptor.sessionId); }
    this.emit({ type: 'session-close', sessionId: entry.descriptor.sessionId, intentional: false, replacement: false, code, reason: 'Gateway connection lost' });
    this.retry(entry);
  }
  private retry(entry: Entry, minimum = 0): void {
    if (this.stopped || entry.blocked || entry.retry || this.entries.get(entry.descriptor.sessionId) !== entry) return;
    const base = Math.min(30_000, 1000 * 2 ** Math.min(entry.attempts++, 5));
    const jitter = Math.max(0, Math.min(1, this.options.jitter?.() ?? Math.random()));
    entry.retry = this.timers.setTimeout(() => { entry.retry = undefined; if (!this.stopped && this.entries.get(entry.descriptor.sessionId) === entry) void this.connect(entry).catch(() => undefined); }, Math.max(minimum, Math.min(30_000, base + base * 0.2 * jitter)));
  }
  async sendMessage(id: string, content: string): Promise<void> {
    const entry = this.entries.get(id);
    if (typeof content !== 'string' || !content.trim()) throw new Error('Invalid ZeroClaw message');
    if (!entry?.descriptor.connected || entry.socket?.readyState !== WebSocket.OPEN || this.stopped) throw new Error('ZeroClaw session disconnected');
    entry.socket.send(JSON.stringify({ type: 'message', content }));
    entry.turn++; entry.descriptor.busy = true;
    await this.emitAwaited({ type: 'message-sent', sessionId: id, id: randomUUID(), content });
  }
  private turnState(entry: Entry, busy: boolean, terminalConfirmed: boolean, source: string): void {
    entry.descriptor.busy = busy;
    if (terminalConfirmed) { entry.descriptor.continuityLost = false; if (entry.poll) this.timers.clearTimeout(entry.poll); entry.poll = undefined; }
    this.emit({ type: 'turn-state', sessionId: entry.descriptor.sessionId, busy, continuityLost: entry.descriptor.continuityLost, terminalConfirmed, source });
  }
  async abortSession(id: string): Promise<{ status: 'aborted' | 'no_active_response'; terminalConfirmed: boolean }> {
    if (!UUID.test(id)) throw new Error('Invalid ZeroClaw session UUID');
    const entry = this.entries.get(id); const generation = entry?.generation; const turn = entry?.turn;
    const result = await this.http(`/api/sessions/${id}/abort`, { method: 'POST' }, entry);
    if (!object(result) || (result.status !== 'aborted' && result.status !== 'no_active_response')) throw new Error('Invalid ZeroClaw abort response');
    const current = !entry || (this.current(entry, generation!) && entry.turn === turn);
    const terminalConfirmed = result.status === 'no_active_response' && current && !this.stopped;
    if (terminalConfirmed && entry) { entry.turn++; this.turnState(entry, false, true, 'abort-no-active'); }
    return { status: result.status, terminalConfirmed };
  }
  async refreshTurnState(id: string): Promise<{ busy: boolean; continuityLost: boolean }> {
    const entry = this.entries.get(id);
    if (!entry) throw new Error('ZeroClaw session not open');
    const generation = entry.generation; const turn = entry.turn;
    try {
      const result = await this.http(`/api/sessions/${id}/state`, undefined, entry);
      if (!object(result) || (result.session_id !== undefined && result.session_id !== id) || !['running', 'idle', 'error'].includes(String(result.state))) throw new Error('Invalid ZeroClaw turn state');
      if (this.current(entry, generation) && entry.turn === turn) {
        if (result.state === 'running') { this.turnState(entry, true, false, 'state-running'); this.schedulePoll(entry); }
        else { entry.turn++; this.turnState(entry, false, true, `state-${result.state}`); }
      }
    } catch (error) { if (this.current(entry, generation) && entry.descriptor.continuityLost) this.schedulePoll(entry); throw error; }
    return { busy: entry.descriptor.busy, continuityLost: entry.descriptor.continuityLost };
  }
  /**
   * Sessão restaurada como "rodando" que nunca recebeu mensagem: o gateway não conhece turno dela (404) e o espelho não
   * tem pedido do usuário. Só quem chama prova o "nunca enviada"; aqui só se exige que nada tenha sido enviado nesta vida.
   */
  clearNeverSent(id: string): boolean {
    const entry = this.entries.get(id);
    if (!entry?.descriptor.connected || entry.turn !== 0 || !(entry.descriptor.busy || entry.descriptor.continuityLost)) return false;
    entry.turn++; this.turnState(entry, false, true, 'never-sent');
    return true;
  }
  private schedulePoll(entry: Entry): void {
    if (this.stopped || !entry.descriptor.connected || entry.poll || !entry.descriptor.busy) return;
    entry.poll = this.timers.setTimeout(() => { entry.poll = undefined; if (entry.descriptor.connected && !this.stopped) void this.refreshTurnState(entry.descriptor.sessionId).catch(() => undefined); }, 10_000);
  }
}
