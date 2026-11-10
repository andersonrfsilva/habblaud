import { existsSync } from 'node:fs';
import type { AgentStatus, SourceInfo } from '../../../shared/types';
import type { AccountsService } from '../../accounts/service';
import type { Office } from '../../model/office';
import { log } from '../../log';
import type { AgentSource } from '../source';
import { createTranscriptState, parseLine, type ParsedActivity, type TranscriptState } from '../transcript';
import { ZeroClawConnector, type ConnectorEvent, type OpenSession, type SessionDescriptor } from './connector';
import { ZeroClawMirror } from './mirror';

interface Tracker { id: string; state: TranscriptState; present: boolean; backlog: ParsedActivity[] }
export interface ZeroClawSourceOptions {
  accounts: AccountsService;
  office: Office;
  connector: ZeroClawConnector;
  mirror: ZeroClawMirror;
  pollMs?: number;
}

export class ZeroClawSource implements AgentSource {
  readonly provider = 'zeroclaw' as const;
  readonly accountId: string;
  private trackers = new Map<string, Tracker>();
  private timer?: ReturnType<typeof setTimeout>;
  private stopped = true;
  private reachable = false;
  private error?: string;
  private pending = Promise.resolve();
  private unsubscribers: Array<() => void> = [];

  constructor(private readonly opts: ZeroClawSourceOptions) {
    this.accountId = opts.accounts.setProviderAccounts('zeroclaw', [{ dir: opts.mirror.root,
      detected: { id: 'zeroclaw', configDir: opts.mirror.root, short: 'Z', name: 'ZeroClaw', color: '#5cc97b' } }])[0].id;
  }

  diagnostic(message: string): void { this.error = message; log.warn(message); }

  async start(): Promise<void> {
    if (!this.stopped) return;
    this.stopped = false;
    this.opts.office.beginBoot();
    this.unsubscribers.push(this.opts.mirror.onLine((id, line) => this.applyLine(id, line, true)));
    this.unsubscribers.push(this.opts.connector.onEvent((event) => {
      const operation = this.pending.then(() => this.event(event));
      this.pending = operation.catch(() => this.diagnostic('ZeroClaw: falha ao persistir ou aplicar evento da sessão'));
      return operation;
    }));
    try {
      await this.opts.connector.start();
      await this.pending;
      await this.poll();
    } catch {
      this.diagnostic('ZeroClaw: fonte indisponível ao iniciar');
    } finally {
      this.opts.office.endBoot();
      this.schedule();
    }
  }

  /** Bootstrap finishes before the connector opens a socket, never concurrently with streaming imports. */
  async prepare(input: OpenSession): Promise<void> {
    const { mirror, connector } = this.opts;
    await mirror.setMetadata(input);
    if (!existsSync(mirror.pathOf(input.sessionId))) {
      const response = await connector.request(`/api/sessions/${input.sessionId}/messages`);
      const messages = Array.isArray(response) ? response : response && typeof response === 'object' && 'messages' in response ? response.messages : undefined;
      if (!Array.isArray(messages)) throw new Error('ZeroClaw: histórico de bootstrap inválido');
      await mirror.bootstrap(input.sessionId, messages);
    }
    const id = input.officeAgentId ?? `zeroclaw:${this.accountId}:${input.sessionId}`;
    const tracker: Tracker = { id, state: createTranscriptState(), present: false, backlog: [] };
    this.trackers.set(input.sessionId, tracker);
    for (const line of await mirror.load(input.sessionId)) this.applyLine(input.sessionId, line, false);
  }

  stop(): void {
    this.stopped = true;
    clearTimeout(this.timer);
    this.opts.connector.stop();
    for (const unsubscribe of this.unsubscribers.splice(0)) unsubscribe();
    for (const t of this.trackers.values()) if (t.present) { this.opts.office.closeMain(t.id); t.present = false; }
  }

  sources(): SourceInfo[] {
    return [{ label: this.accountId, provider: 'zeroclaw', path: this.opts.mirror.root,
      sessions: [...this.trackers.values()].filter((t) => t.present).length, ok: this.reachable && !this.error,
      ...(this.error ? { error: this.error } : {}) }];
  }

  transcriptPathOf(agentId: string): string | undefined {
    // Depois de /new o personagem mantém o id e ganha outro tracker: vale o da sessão atual, não o primeiro do Map.
    const current = this.opts.office.get(agentId)?.sessionId;
    if (current && this.trackers.get(current)?.id === agentId && existsSync(this.opts.mirror.pathOf(current))) return this.opts.mirror.pathOf(current);
    let found: string | undefined;
    for (const [id, tracker] of this.trackers) if (tracker.id === agentId && existsSync(this.opts.mirror.pathOf(id))) found = this.opts.mirror.pathOf(id);
    return found;
  }

  async flush(): Promise<void> { await this.pending; }

  async poll(): Promise<void> {
    if (this.stopped) return;
    try {
      await this.opts.connector.listSessions();
      if (this.stopped) return;
      this.reachable = true;
      this.error = undefined;
      for (const session of this.opts.connector.openSessions()) {
        if (session.connected) this.enter(session);
        if (session.connected && (session.busy || session.continuityLost)) {
          try { await this.opts.connector.refreshTurnState(session.sessionId); } catch { this.diagnostic('ZeroClaw: estado do turno desconhecido'); }
        }
      }
      await this.pending;
    } catch {
      this.reachable = false;
      this.diagnostic('ZeroClaw: gateway indisponível');
      for (const t of this.trackers.values()) if (t.present) { this.opts.office.closeMain(t.id); t.present = false; }
    }
  }

  private schedule(): void {
    if (this.stopped) return;
    this.timer = setTimeout(() => { void this.poll().finally(() => this.schedule()); }, this.opts.pollMs ?? 10_000);
    this.timer.unref();
  }

  private enter(session: SessionDescriptor): void {
    const t = this.trackers.get(session.sessionId);
    if (!t || !session.cwd || !session.connected) return;
    if (t.present) return;
    const previous = this.opts.office.get(t.id);
    if (previous && previous.sessionId !== session.sessionId) this.opts.office.switchSession(t.id, session.sessionId);
    this.opts.office.addMain({ id: t.id, provider: 'zeroclaw', account: this.accountId, sessionId: session.sessionId,
      cwd: session.cwd, role: 'Agente principal', cast: session.cast, startedAt: t.state.firstAt ?? Date.now(), status: session.busy ? 'working' : 'idle' });
    t.present = this.opts.office.has(t.id);
    if (t.present) for (const a of t.backlog.splice(0)) this.opts.office.addActivity(t.id, a.activity, a.current, { feed: false });
    this.summary(t);
  }

  private async event(event: ConnectorEvent): Promise<void> {
    if (this.stopped) return;
    if (event.type === 'diagnostic') { this.diagnostic(event.message); return; }
    if (event.type === 'session-open') {
      this.reachable = true;
      this.error = undefined;
      this.enter(event.descriptor);
    }
    await this.opts.mirror.consume(event);
    const t = this.trackers.get(event.sessionId);
    if (!t) return;
    if (event.type === 'session-close') {
      t.present = false;
      if (!event.replacement) this.opts.office.closeMain(t.id);
      if (!event.intentional) { this.reachable = false; this.error = 'ZeroClaw: sessão desconectada'; }
      return;
    }
    if (!t.present) return;
    let status: AgentStatus | undefined;
    let silent = false;
    if (event.type === 'message-sent') status = 'working';
    if (event.type === 'frame') {
      if (['chunk', 'thinking', 'tool_call', 'tool_result'].includes(event.frame.type)) status = 'working';
      if (event.frame.type === 'approval_request') status = 'waiting';
      if (event.frame.type === 'done' || event.frame.type === 'aborted') status = 'idle';
    }
    if (event.type === 'turn-state' && event.terminalConfirmed) {
      t.state.pendingTools.clear();
      t.state.ended = true;
      status = 'idle';
      silent = true;
    }
    if (status) {
      // HTTP proves only absence of work; Office must not synthesize a successful delivery for that transition.
      if (silent) this.opts.office.beginBoot();
      try { this.opts.office.setStatus(t.id, status); } finally { if (silent) this.opts.office.endBoot(); }
    }
  }

  private applyLine(sessionId: string, line: string, live: boolean): void {
    const t = this.trackers.get(sessionId);
    if (!t) return;
    const parsed = parseLine(t.state, line, { idPrefix: t.id, now: Date.now() });
    if (t.present) for (const a of parsed.activities) this.opts.office.addActivity(t.id, a.activity, a.current, { feed: live });
    else {
      t.backlog.push(...parsed.activities);
      if (t.backlog.length > 200) t.backlog.splice(0, t.backlog.length - 200);
    }
    this.summary(t);
  }

  private summary(t: Tracker): void {
    if (!t.present) return;
    const s = t.state;
    this.opts.office.applyTranscript(t.id, { tasks: s.tasks, stats: { ...s.stats }, title: s.customTitle ?? s.aiTitle,
      model: s.model, firstAt: s.firstAt, lastAt: s.lastAt });
  }
}
