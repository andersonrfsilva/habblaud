import { randomUUID, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { appendFile, mkdir, readFile, rename, stat, truncate, unlink, writeFile } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { maskSecrets } from '../../../shared/activity';
import type { ConnectorEvent, OpenSession } from './connector';

export interface SessionMetadata {
  sessionId: string;
  agent?: string;
  name?: string;
  cwd?: string;
  cast?: string;
  officeAgentId?: string;
  lastAt?: number;
  firstAt?: number;
}

type RecordLine = Record<string, unknown>;
type Turn = { chunks: boolean; startedAt?: number; unknown: boolean; title?: string; seen: Set<string> };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const object = (value: unknown): value is RecordLine => !!value && typeof value === 'object' && !Array.isArray(value);
const missing = (error: unknown): boolean => object(error) && error.code === 'ENOENT';
const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0;

export class ZeroClawMirror {
  readonly root: string;
  private readonly metadata = new Map<string, SessionMetadata>();
  private readonly turns = new Map<string, Turn>();
  private readonly queues = new Map<string, Promise<unknown>>();
  private readonly listeners = new Set<(sessionId: string, line: string) => void>();
  private indexQueue: Promise<unknown> = Promise.resolve();
  private readonly diagnostic: (message: string) => void;

  constructor(options: { dataDir: string; diagnostic?: (message: string) => void }) {
    this.root = join(options.dataDir, 'zeroclaw');
    this.diagnostic = options.diagnostic ?? (() => {});
    let raw: string;
    try {
      raw = readFileSync(join(this.root, 'sessions.json'), 'utf8');
    } catch (error) {
      if (missing(error)) return;
      this.report('Falha ao ler índice de sessões', error);
      throw error;
    }
    try {
      const index: unknown = JSON.parse(raw);
      if (!object(index) || index.version !== 1 || !Array.isArray(index.sessions)) throw new Error('Índice ZeroClaw inválido');
      for (const entry of index.sessions) {
        this.validateMetadata(entry);
        if (this.metadata.has(entry.sessionId)) throw new Error('Sessão duplicada no índice ZeroClaw');
        this.metadata.set(entry.sessionId, { ...entry });
      }
    } catch (error) {
      this.report('Índice de sessões ZeroClaw inválido', error);
      throw error;
    }
  }

  pathOf(sessionId: string): string {
    this.validateId(sessionId);
    return join(this.root, 'sessions', `${sessionId}.jsonl`);
  }

  metadataOf(sessionId: string): OpenSession | undefined {
    const entry = this.metadata.get(sessionId);
    if (!entry || !text(entry.agent) || !text(entry.name) || !text(entry.cwd) || !isAbsolute(entry.cwd)) return undefined;
    return { sessionId, agent: entry.agent, name: entry.name, cwd: entry.cwd, ...(entry.cast !== undefined ? { cast: entry.cast } : {}), ...(entry.officeAgentId !== undefined ? { officeAgentId: entry.officeAgentId } : {}) };
  }

  entries(): SessionMetadata[] {
    return [...this.metadata.values()].map((entry) => ({ ...entry }));
  }

  setMetadata(partial: SessionMetadata): Promise<void> {
    this.validateMetadata(partial);
    const operation = this.indexQueue.then(async () => {
      const next = { ...this.metadata.get(partial.sessionId), ...partial };
      const entries = new Map(this.metadata);
      entries.set(next.sessionId, next);
      await mkdir(this.root, { recursive: true });
      const temporary = join(this.root, `sessions.${randomUUID()}.tmp`);
      try {
        await writeFile(temporary, JSON.stringify({ version: 1, sessions: [...entries.values()] }) + '\n', { mode: 0o600 });
        await rename(temporary, join(this.root, 'sessions.json'));
      } catch (error) {
        await unlink(temporary).catch(() => {});
        throw error;
      }
      this.metadata.set(next.sessionId, next);
    });
    this.indexQueue = operation.catch(() => {});
    return operation.catch((error: unknown) => {
      this.report('Falha ao persistir índice de sessões', error);
      throw error;
    });
  }

  onLine(listener: (sessionId: string, line: string) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  load(sessionId: string): Promise<string[]> {
    return this.serial(sessionId, () => this.read(sessionId, true));
  }

  bootstrap(sessionId: string, messages: unknown[]): Promise<void> {
    return this.serial(sessionId, async () => {
      await this.read(sessionId);
      try {
        await stat(this.pathOf(sessionId));
        return;
      } catch (error) {
        if (!missing(error)) throw error;
      }
      await mkdir(join(this.root, 'sessions'), { recursive: true });
      const records: RecordLine[] = [];
      messages.forEach((entry, position) => {
        if (!object(entry) || (entry.role !== 'user' && entry.role !== 'assistant') || typeof entry.content !== 'string') {
          this.report('Mensagem inválida no histórico HTTP ZeroClaw');
          return;
        }
        const timestamp = entry.timestamp ?? entry.created_at;
        const value = typeof timestamp === 'number' ? timestamp * 1000 : typeof timestamp === 'string' ? Date.parse(timestamp) : NaN;
        const at = Number.isFinite(value) && Math.abs(value) <= 8.64e15 ? value : 0;
        const id = `bootstrap-${createHash('sha256').update(`${sessionId}:${position}`).digest('hex')}`;
        records.push(this.record(sessionId, entry.role === 'user' ? { type: 'user', message: { role: 'user', content: entry.content } } : { type: 'assistant', message: { id, role: 'assistant', content: entry.content ? [{ type: 'text', text: entry.content }] : [], stop_reason: 'end_turn' } }, at, id));
      });
      // Bootstrap completes before a socket opens; historical lines never generate live feed.
      await writeFile(this.pathOf(sessionId), records.map((record) => JSON.stringify(record) + '\n').join(''), { flag: 'wx', mode: 0o600 });
      this.turns.delete(sessionId);
      await this.read(sessionId);
      if (records.length) await this.touch(sessionId, records);
    });
  }

  consume(event: ConnectorEvent): Promise<void> {
    if (event.type === 'diagnostic') {
      this.report('Diagnóstico do conector ZeroClaw: ' + maskSecrets(event.message).replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 500));
      return Promise.resolve();
    }
    return this.serial(event.sessionId, async () => {
      await this.read(event.sessionId);
      const turn = this.turns.get(event.sessionId)!;
      if (event.type === 'session-open') {
        const { sessionId, agent, name, cwd, cast, officeAgentId } = event.descriptor;
        await this.setMetadata({ sessionId, agent, name, cwd, ...(cast !== undefined ? { cast } : {}), ...(officeAgentId !== undefined ? { officeAgentId } : {}) });
        await this.title(sessionId, name, turn);
        if (event.continuityLost) await this.gap(sessionId, turn);
        return;
      }
      if (event.type === 'session-close') {
        if (!event.intentional && turn.startedAt !== undefined) await this.gap(event.sessionId, turn);
        return;
      }
      if (event.type === 'message-sent') {
        if (turn.seen.has(event.id)) return;
        await this.append(event.sessionId, [this.record(event.sessionId, { type: 'user', message: { role: 'user', content: event.content } }, Date.now(), event.id)]);
        turn.startedAt ??= Date.now();
        return;
      }
      if (event.type === 'turn-state') {
        if (event.continuityLost) await this.gap(event.sessionId, turn);
        if (event.terminalConfirmed && !event.busy && turn.unknown) {
          await this.info(event.sessionId, 'info', 'Ausência de turno ativo confirmada pelo gateway.');
          turn.unknown = false;
          turn.chunks = false;
          turn.startedAt = undefined;
        }
        return;
      }
      const frame = event.frame;
      const assistant = (content: unknown[], extra: RecordLine = {}) => this.record(event.sessionId, { type: 'assistant', message: { id: randomUUID(), role: 'assistant', content, ...extra } });
      switch (frame.type) {
        case 'session_start':
          if (text(frame.name)) await this.title(event.sessionId, frame.name, turn);
          return;
        case 'chunk':
        case 'thinking':
          if (typeof frame.content !== 'string') return this.protocol(frame.type);
          if (!frame.content) return;
          await this.append(event.sessionId, [assistant([frame.type === 'chunk' ? { type: 'text', text: frame.content } : { type: 'thinking', thinking: frame.content }], { stop_reason: null })]);
          if (frame.type === 'chunk') turn.chunks = true;
          turn.startedAt ??= Date.now();
          return;
        case 'tool_call':
          if (!text(frame.id) || !text(frame.name) || !object(frame.args)) return this.protocol('tool_call');
          await this.append(event.sessionId, [assistant([{ type: 'tool_use', id: frame.id, name: frame.name, input: frame.args }], { stop_reason: 'tool_use' })]);
          turn.startedAt ??= Date.now();
          return;
        case 'tool_result': {
          if (!text(frame.id) || frame.output === undefined) return this.protocol('tool_result');
          const content = typeof frame.output === 'string' ? frame.output : JSON.stringify(frame.output);
          await this.append(event.sessionId, [this.record(event.sessionId, { type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: frame.id, content }] } })]);
          return;
        }
        case 'done': {
          const content = !turn.chunks && text(frame.full_response) ? [{ type: 'text', text: frame.full_response }] : [];
          const durationMs = Math.max(0, Date.now() - (turn.startedAt ?? Date.now()));
          await this.append(event.sessionId, [assistant(content, { stop_reason: 'end_turn', ...(text(frame.model) ? { model: frame.model } : {}) }), this.record(event.sessionId, { type: 'system', subtype: 'turn_duration', durationMs })]);
          turn.chunks = false;
          turn.startedAt = undefined;
          turn.unknown = false;
          return;
        }
        case 'aborted':
          await this.append(event.sessionId, [this.record(event.sessionId, { type: 'user', message: { role: 'user', content: '[Request interrupted by user]' } })]);
          turn.chunks = false;
          turn.startedAt = undefined;
          turn.unknown = false;
          return;
        case 'error':
          await this.info(event.sessionId, 'error', maskSecrets([typeof frame.code === 'string' ? frame.code : '', typeof frame.message === 'string' ? frame.message : 'Erro ZeroClaw'].filter(Boolean).join(': ')).replace(/[\x00-\x1f\x7f]/g, ' ').slice(0, 2000));
          return;
        case 'approval_request':
          await this.info(event.sessionId, 'warning', 'Aprovação de ferramenta pendente no ZeroClaw; nenhuma permissão foi concedida pelo Habblaud.');
          return;
        default:
          this.report(`Frame ZeroClaw desconhecido: ${/^[a-z_]{1,64}$/.test(frame.type) ? frame.type : '(tipo inválido)'}`);
      }
    });
  }

  private serial<T>(sessionId: string, operation: () => Promise<T>): Promise<T> {
    this.validateId(sessionId);
    const next = (this.queues.get(sessionId) ?? Promise.resolve()).then(operation);
    this.queues.set(sessionId, next.catch(() => {}));
    return next.catch((error: unknown) => {
      this.report('Falha na operação de espelho ZeroClaw', error);
      throw error;
    });
  }

  private async read(sessionId: string, force = false): Promise<string[]> {
    if (!force && this.turns.has(sessionId)) return [];
    let bytes: Buffer;
    try {
      bytes = await readFile(this.pathOf(sessionId));
    } catch (error) {
      if (!missing(error)) throw error;
      this.turns.set(sessionId, this.turns.get(sessionId) ?? { chunks: false, unknown: false, seen: new Set() });
      return [];
    }
    const lines: string[] = [];
    const records: RecordLine[] = [];
    let offset = 0;
    while (offset < bytes.length) {
      const newline = bytes.indexOf(10, offset);
      const end = newline < 0 ? bytes.length : newline;
      const line = bytes.subarray(offset, end).toString('utf8');
      try {
        const record: unknown = JSON.parse(line);
        if (!object(record)) throw new Error('Registro JSONL inválido');
        records.push(record);
        lines.push(line);
      } catch (error) {
        if (newline >= 0) throw new Error('Registro inválido no meio do espelho ZeroClaw', { cause: error });
        await truncate(this.pathOf(sessionId), offset);
        this.report('Fragmento final incompleto removido do espelho ZeroClaw');
        break;
      }
      if (newline < 0) {
        await appendFile(this.pathOf(sessionId), '\n');
        break;
      }
      offset = newline + 1;
    }
    if (!this.turns.has(sessionId)) {
      const turn: Turn = { chunks: false, unknown: false, seen: new Set() };
      for (const record of records) {
        if (text(record.uuid)) turn.seen.add(record.uuid);
        if (record.type === 'custom-title' && text(record.customTitle)) turn.title = record.customTitle;
        if (record.type === 'assistant' && object(record.message)) {
          const message = record.message;
          if (Array.isArray(message.content) && message.content.some((block: unknown) => object(block) && block.type === 'text')) turn.chunks = true;
          if (message.stop_reason === 'end_turn') turn.chunks = false;
        }
        if (record.type === 'system' && record.subtype === 'turn_duration' || record.type === 'user' && object(record.message) && record.message.content === '[Request interrupted by user]') turn.chunks = false;
      }
      this.turns.set(sessionId, turn);
    }
    return lines;
  }

  private record(sessionId: string, fields: RecordLine, at = Date.now(), uuid: string = randomUUID()): RecordLine {
    const cwd = this.metadata.get(sessionId)?.cwd;
    return { uuid, timestamp: new Date(at).toISOString(), sessionId, isSidechain: false, provider: 'zeroclaw', ...(cwd ? { cwd } : {}), ...fields };
  }

  private async append(sessionId: string, records: RecordLine[]): Promise<void> {
    await mkdir(join(this.root, 'sessions'), { recursive: true });
    const lines = records.map((record) => JSON.stringify(record));
    await appendFile(this.pathOf(sessionId), lines.join('\n') + '\n', { mode: 0o600 });
    const turn = this.turns.get(sessionId)!;
    for (const record of records) if (text(record.uuid)) turn.seen.add(record.uuid);
    await this.touch(sessionId, records);
    for (const line of lines) for (const listener of this.listeners) {
      try { listener(sessionId, line); } catch { this.report('Falha no consumidor de linha ZeroClaw'); }
    }
  }

  private async touch(sessionId: string, records: RecordLine[]): Promise<void> {
    const previous = this.metadata.get(sessionId);
    let firstAt = previous?.firstAt ?? Infinity;
    let lastAt = previous?.lastAt ?? -Infinity;
    for (const record of records) {
      const at = Date.parse(String(record.timestamp));
      firstAt = Math.min(firstAt, at);
      lastAt = Math.max(lastAt, at);
    }
    await this.setMetadata({ sessionId, firstAt, lastAt });
  }

  private async title(sessionId: string, name: string, turn: Turn): Promise<void> {
    if (turn.title === name) return;
    await this.setMetadata({ sessionId, name });
    await this.append(sessionId, [this.record(sessionId, { type: 'custom-title', customTitle: name })]);
    turn.title = name;
  }

  private async gap(sessionId: string, turn: Turn): Promise<void> {
    if (turn.unknown) return;
    await this.info(sessionId, 'warning', 'Lacuna no acompanhamento ZeroClaw: eventos e ferramentas podem estar ausentes; nenhum prompt foi reenviado.');
    turn.unknown = true;
  }

  private info(sessionId: string, level: string, content: string): Promise<void> {
    return this.append(sessionId, [this.record(sessionId, { type: 'system', subtype: 'informational', level, content })]);
  }

  private protocol(type: string): void {
    this.report(`Frame ZeroClaw inválido: ${type}`);
  }

  private report(message: string, error?: unknown): void {
    const code = object(error) && typeof error.code === 'string' && /^[A-Z0-9_]+$/.test(error.code) ? ` (${error.code})` : '';
    this.diagnostic(message + code);
  }

  private validateId(sessionId: string): void {
    if (!UUID.test(sessionId)) throw new Error('UUID de sessão ZeroClaw inválido');
  }

  private validateMetadata(value: unknown): asserts value is SessionMetadata {
    if (!object(value) || typeof value.sessionId !== 'string') throw new Error('Metadados ZeroClaw inválidos');
    this.validateId(value.sessionId);
    for (const key of ['agent', 'name', 'cwd', 'cast', 'officeAgentId']) if (value[key] !== undefined && typeof value[key] !== 'string') throw new Error('Metadados ZeroClaw inválidos');
    if (typeof value.cwd === 'string' && !isAbsolute(value.cwd)) throw new Error('Diretório ZeroClaw deve ser absoluto');
    for (const key of ['lastAt', 'firstAt']) if (value[key] !== undefined && (typeof value[key] !== 'number' || !Number.isFinite(value[key]))) throw new Error('Data de sessão ZeroClaw inválida');
  }
}
