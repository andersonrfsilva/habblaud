import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn, type ChildProcess } from 'node:child_process';
import { connect } from 'node:net';
import type { Duplex, Readable, Writable } from 'node:stream';

/** A linha de comando inteira do agy. Nada além disto pode chegar ao binário (regra 3). */
export function agyArgs(opts: { skipPermissions: boolean }): string[] {
  return opts.skipPermissions ? ['acp', '--dangerously-skip-permissions'] : ['acp'];
}

export interface SessionInfo { model?: string; models: string[]; modelOptions: { value: string; name: string }[]; tier?: string }
export type AcpEvent =
  | { type: 'session-open'; sessionId: string; cwd: string; resumed: boolean; info: SessionInfo; replaces?: string; cast?: string }
  | { type: 'session-close'; sessionId: string; reason: 'closed' | 'process-exit' }
  | { type: 'update'; sessionId: string; update: Record<string, unknown> }
  | { type: 'turn-state'; sessionId: string; busy: boolean; stopReason?: string }
  | { type: 'permission'; sessionId: string; requestId: number | string; toolCall: Record<string, unknown>; options: { optionId: string; kind: string; name: string }[] }
  | { type: 'permission-gone'; sessionId: string; requestId: number | string }
  | { type: 'diagnostic'; message: string };
export class AuthRequired extends Error {}
export class UnknownModel extends Error {}

type Session = { sessionId: string; cwd: string; busy: boolean; cast?: string; model?: string; turn?: Promise<unknown> };
type Permission = Extract<AcpEvent, { type: 'permission' }>;
type Pending = { resolve: (value: unknown) => void; reject: (error: Error) => void; timer?: NodeJS.Timeout };
type Options = { bin: string; socket?: string; skipPermissions: boolean; version: string; additionalDirectories?: string[]; env?: NodeJS.ProcessEnv; connect?: () => Duplex };
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const rpcId = (v: unknown): v is string | number => typeof v === 'string' || typeof v === 'number';
const MAX_LINE = 8 * 1024 * 1024;

export class AntigravityClient {
  private readonly listeners = new Set<(event: AcpEvent) => void>();
  private readonly opened = new Map<string, Session>();
  private readonly permissions = new Map<number | string, Permission>();
  private readonly pending = new Map<number, Pending>();
  private sequence = 0;
  private connection?: { input: Writable; output: Readable; child?: ChildProcess };
  private ready?: Promise<void>;
  private disconnect?: () => void;

  constructor(private readonly opts: Options) {
    if (opts.additionalDirectories?.some(path => !isAbsolute(path))) throw new Error('ACP additional directories must be absolute');
  }
  onEvent(listener: (event: AcpEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  private emit(event: AcpEvent): void { for (const listener of this.listeners) listener(event); }
  session(id: string): Omit<Session, 'turn'> | undefined {
    const session = this.opened.get(id);
    return session && { sessionId: id, cwd: session.cwd, busy: session.busy, cast: session.cast, model: session.model };
  }
  sessions(): Omit<Session, 'turn'>[] { return [...this.opened.keys()].map(id => this.session(id)!); }

  private ensure(): Promise<void> {
    if (this.ready) return this.ready;
    let input: Writable;
    let output: Readable;
    let child: ChildProcess | undefined;
    if (this.opts.connect || this.opts.socket) {
      const stream = this.opts.connect ? this.opts.connect() : connect(this.opts.socket!);
      input = stream; output = stream;
    } else {
      const process = spawn(this.opts.bin, agyArgs({ skipPermissions: this.opts.skipPermissions }), { stdio: ['pipe', 'pipe', 'ignore'], env: this.opts.env });
      input = process.stdin; output = process.stdout;
      child = process;
    }
    const connection = { input, output, child };
    this.connection = connection;
    const fail = (error = new Error('ACP connection closed')) => {
      if (this.connection !== connection) return;
      this.connection = undefined; this.ready = undefined;
      this.disconnect = undefined;
      for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
      this.pending.clear();
      for (const session of this.opened.values()) {
        this.clearPermissions(session.sessionId, false);
        if (session.busy) this.emit({ type: 'turn-state', sessionId: session.sessionId, busy: false });
        this.emit({ type: 'session-close', sessionId: session.sessionId, reason: 'process-exit' });
      }
      this.opened.clear();
      input.end();
    };
    this.disconnect = fail;
    input.on('error', fail); output.on('error', fail); output.on('end', () => fail());
    child?.on('error', fail); child?.on('exit', () => fail());
    let buffered = Buffer.alloc(0);
    let discarding = false;
    output.on('data', (chunk: Buffer) => {
      if (this.connection !== connection) return;
      let start = 0;
      for (let index = 0; index < chunk.length; index++) {
        if (chunk[index] !== 10) continue;
        const fragment = chunk.subarray(start, index);
        if (!discarding && buffered.length + fragment.length <= MAX_LINE) {
          this.receive(Buffer.concat([buffered, fragment]).toString('utf8'));
        } else if (!discarding) this.emit({ type: 'diagnostic', message: 'ACP line exceeds 8 MiB' });
        buffered = Buffer.alloc(0); discarding = false; start = index + 1;
      }
      if (discarding) return;
      const rest = chunk.subarray(start);
      if (buffered.length + rest.length > MAX_LINE) {
        buffered = Buffer.alloc(0); discarding = true;
        this.emit({ type: 'diagnostic', message: 'ACP line exceeds 8 MiB' });
      } else if (rest.length) buffered = Buffer.concat([buffered, rest]);
    });
    this.ready = this.call('initialize', {
      protocolVersion: 1,
      clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false },
      clientInfo: { name: 'habblaud', version: this.opts.version },
    }).then(() => {}, error => { fail(error); throw error; });
    return this.ready;
  }

  private write(message: unknown): Promise<void> {
    const connection = this.connection;
    if (!connection) return Promise.reject(new Error('ACP connection closed'));
    const result = Promise.withResolvers<void>();
    connection.input.write(JSON.stringify(message) + '\n', error => error ? result.reject(error) : result.resolve());
    return result.promise;
  }
  private call(method: string, params: unknown, timeout = true): Promise<unknown> {
    const id = ++this.sequence;
    const result = Promise.withResolvers<unknown>();
    const pending: Pending = { resolve: result.resolve, reject: result.reject };
    if (timeout) pending.timer = setTimeout(() => { this.pending.delete(id); result.reject(new Error('ACP request timed out')); }, 30_000);
    this.pending.set(id, pending);
    void this.write({ jsonrpc: '2.0', id, method, params }).catch(error => {
      if (!this.pending.delete(id)) return;
      clearTimeout(pending.timer); result.reject(error);
    });
    return result.promise;
  }
  private receive(line: string): void {
    let parsed: unknown;
    try { parsed = JSON.parse(line); if (!object(parsed)) throw new Error(); }
    catch { this.emit({ type: 'diagnostic', message: 'Invalid ACP JSON' }); return; }
    const message = parsed as Record<string, unknown>;
    if (typeof message.method === 'string') {
      const params = message.params;
      if (rpcId(message.id)) {
        if (message.method === 'session/request_permission' && object(params) && typeof params.sessionId === 'string' && this.opened.has(params.sessionId) && object(params.toolCall) && Array.isArray(params.options)) {
          const options: Permission['options'] = [];
          for (const option of params.options) if (object(option) && typeof option.optionId === 'string' && typeof option.kind === 'string' && typeof option.name === 'string') {
            options.push({ optionId: option.optionId, kind: option.kind, name: option.name });
          }
          const event: Permission = { type: 'permission', sessionId: params.sessionId, requestId: message.id, toolCall: params.toolCall, options };
          this.permissions.set(message.id, event); this.emit(event);
        } else void this.write({ jsonrpc: '2.0', id: message.id, error: { code: -32601, message: 'Method not found' } }).catch(() => {});
      } else if (message.method === 'session/update' && object(params) && typeof params.sessionId === 'string' && this.opened.has(params.sessionId) && object(params.update)) {
        this.emit({ type: 'update', sessionId: params.sessionId, update: params.update });
      }
      return;
    }
    if (typeof message.id !== 'number') return;
    const pending = this.pending.get(message.id);
    if (!pending) return;
    this.pending.delete(message.id); clearTimeout(pending.timer);
    if (object(message.error)) {
      const text = typeof message.error.message === 'string' ? message.error.message : 'ACP error';
      pending.reject(message.error.code === -32000 ? new AuthRequired(text) : message.error.code === -32602 ? new UnknownModel(text) : new Error(text));
    }
    else pending.resolve(message.result);
  }
  async open(input: { cwd: string; resume?: string; replaces?: string; cast?: string; model?: string }): Promise<{ sessionId: string }> {
    await this.ensure();
    const result = await this.call(input.resume ? 'session/resume' : 'session/new', { cwd: input.cwd, mcpServers: [], additionalDirectories: this.opts.additionalDirectories ?? [], ...(input.resume ? { sessionId: input.resume } : {}) });
    if (!object(result)) throw new Error('Invalid ACP session');
    const sessionId = result.sessionId;
    if (typeof sessionId !== 'string') throw new Error('Invalid ACP session');
    let configured: unknown = result;
    if (input.model !== undefined) {
      try { configured = await this.call('session/set_config_option', { sessionId, configId: 'model', value: input.model }); }
      catch (error) {
        try { await this.call('session/close', { sessionId }); }
        finally { if (!this.opened.size) this.stop(); }
        throw error;
      }
    }
    const configOptions = object(configured) && Array.isArray(configured.configOptions) ? configured.configOptions : [];
    const config = configOptions.find(option => object(option) && option.id === 'model');
    const modelOptions = object(config) && Array.isArray(config.options) ? config.options.flatMap(option =>
      object(option) && typeof option.value === 'string' && typeof option.name === 'string'
        ? [{ value: option.value, name: option.name }] : []) : [];
    const models = object(result.models) ? result.models : {};
    const model = object(config) && typeof config.currentValue === 'string' ? config.currentValue
      : typeof models.currentModelId === 'string' ? models.currentModelId : undefined;
    const available = Array.isArray(models.availableModels) ? models.availableModels : [];
    this.opened.set(sessionId, { sessionId, cwd: input.cwd, busy: false, cast: input.cast, model });
    const meta = object(result._meta) && object(result._meta.antigravity) ? result._meta.antigravity : {};
    const tier = object(meta.tier) && typeof meta.tier.name === 'string' ? meta.tier.name : undefined;
    this.emit({ type: 'session-open', sessionId, cwd: input.cwd, resumed: !!input.resume, ...(input.replaces !== undefined ? { replaces: input.replaces } : {}),
      ...(input.cast !== undefined ? { cast: input.cast } : {}),
      info: { model, models: available.flatMap(entry => object(entry) && typeof entry.modelId === 'string' ? [entry.modelId] : []), modelOptions, tier } });
    return { sessionId };
  }
  async setModel(sessionId: string, value: string): Promise<void> {
    const session = this.opened.get(sessionId);
    if (!session) throw new Error('ACP session unavailable');
    await this.call('session/set_config_option', { sessionId, configId: 'model', value });
    session.model = value;
  }
  async prompt(sessionId: string, text: string, links?: { path: string; name: string }[]): Promise<void> {
    if (links?.some(link => !isAbsolute(link.path))) throw new Error('ACP resource path must be absolute');
    const session = this.opened.get(sessionId);
    if (!session || session.busy) throw new Error('ACP session unavailable');
    session.busy = true; this.emit({ type: 'turn-state', sessionId, busy: true });
    session.turn = this.call('session/prompt', { sessionId, prompt: [
      ...(links ?? []).map(link => ({ type: 'resource_link', uri: pathToFileURL(link.path).href, name: link.name, mimeType: 'text/markdown' })),
      { type: 'text', text },
    ] }, false).then(result => {
      this.finishTurn(session, object(result) && typeof result.stopReason === 'string' ? result.stopReason : undefined);
    }, () => this.finishTurn(session));
    // Writes complete in order; this marker resolves only after the prompt write has completed.
    const written = Promise.withResolvers<void>();
    this.connection!.input.write('', error => error ? written.reject(error) : written.resolve());
    await written.promise;
  }
  private finishTurn(session: Session, stopReason?: string): void {
    if (!this.opened.has(session.sessionId) || !session.busy) return;
    this.clearPermissions(session.sessionId, true);
    session.busy = false;
    this.emit({ type: 'turn-state', sessionId: session.sessionId, busy: false, stopReason });
  }
  private clearPermissions(sessionId: string, respond: boolean): void {
    for (const [id, permission] of this.permissions) if (permission.sessionId === sessionId) {
      this.permissions.delete(id);
      if (respond) void this.write({ jsonrpc: '2.0', id, result: { outcome: { outcome: 'cancelled' } } }).catch(() => {});
      this.emit({ type: 'permission-gone', sessionId, requestId: id });
    }
  }
  answerPermission(sessionId: string, requestId: number | string, kind: 'allow_once' | 'reject_once'): boolean {
    const permission = this.permissions.get(requestId);
    const option = permission?.sessionId === sessionId ? permission.options.find(option => option.kind === kind) : undefined;
    if (!option) return false;
    this.permissions.delete(requestId);
    void this.write({ jsonrpc: '2.0', id: requestId, result: { outcome: { outcome: 'selected', optionId: option.optionId } } }).catch(() => {});
    this.emit({ type: 'permission-gone', sessionId, requestId });
    return true;
  }
  async cancel(sessionId: string): Promise<void> {
    const session = this.opened.get(sessionId);
    if (!session?.busy) return;
    await this.write({ jsonrpc: '2.0', method: 'session/cancel', params: { sessionId } });
    this.clearPermissions(sessionId, true);
    await session.turn;
  }
  async close(sessionId: string): Promise<void> {
    if (!this.opened.has(sessionId)) return;
    await this.cancel(sessionId);
    await this.call('session/close', { sessionId });
    this.opened.delete(sessionId);
    this.emit({ type: 'session-close', sessionId, reason: 'closed' });
    if (!this.opened.size) this.stop();
  }
  async list(): Promise<{ sessionId: string; cwd: string; title?: string; updatedAt?: string }[]> {
    await this.ensure();
    const result = await this.call('session/list', {});
    if (!object(result) || !Array.isArray(result.sessions)) throw new Error('Invalid ACP session list');
    return result.sessions.flatMap(entry => object(entry) && typeof entry.sessionId === 'string' && typeof entry.cwd === 'string'
      ? [{ sessionId: entry.sessionId, cwd: entry.cwd, ...(typeof entry.title === 'string' ? { title: entry.title } : {}), ...(typeof entry.updatedAt === 'string' ? { updatedAt: entry.updatedAt } : {}) }] : []);
  }
  stop(): void {
    this.disconnect?.();
  }
}
