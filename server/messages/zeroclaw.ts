import { randomUUID } from 'node:crypto';
import { tr } from '../../shared/i18n';
import { ZeroClawConnector, type ConnectorEvent } from '../sources/zeroclaw/connector';

export const ERR_ZEROCLAW_UNAVAILABLE = tr('a sessão ZeroClaw está indisponível ou em substituição');
export const ERR_ZEROCLAW_CONTINUITY = tr('a continuidade do turno ZeroClaw foi perdida: interrompa ou abra uma conversa nova');
export const ERR_ZEROCLAW_CONTROL = tr('ZeroClaw aceita somente STOP ou conversa nova, sem combinações');
export const ERR_ZEROCLAW_ASIDE = tr('ZeroClaw não oferece conversa paralela');
export const ERR_ZEROCLAW_DELIVERY = tr('ZeroClaw não confirmou a entrada no buffer: não reenvie automaticamente');
export const ERR_ZEROCLAW_TIMEOUT = tr('ZeroClaw não confirmou o fim do turno dentro do prazo');
export const ERR_ZEROCLAW_QUEUED = tr('a mensagem ZeroClaw expirou esperando a sessão ficar disponível');
export const ERR_ZEROCLAW_CHANGED = tr('a conversa do agente mudou (/clear ou /resume) antes da entrega');

export interface ZeroClawDeliveryOptions {
  connector: ZeroClawConnector;
  isClosing?: (sessionId: string) => boolean;
  onCancelSession?: (sessionId: string, reason: string) => void;
  onReplacementFailed?: (officeAgentId: string) => void;
  timeoutMs?: number;
  timers?: { setTimeout: typeof setTimeout; clearTimeout: typeof clearTimeout };
}

/** Uses the source's socket. Acceptance means local buffer only, never an application ack. */
export class ZeroClawDelivery {
  private readonly listeners = new Set<(event: ConnectorEvent) => void>();
  private readonly stopping = new Map<string, Promise<void>>();
  private readonly replacing = new Set<string>();
  private readonly cancellations = new Set<() => void>();
  private readonly unsubscribe: () => void;
  private readonly timers: NonNullable<ZeroClawDeliveryOptions['timers']>;
  private stopped = false;

  constructor(private readonly options: ZeroClawDeliveryOptions) {
    this.timers = options.timers ?? { setTimeout, clearTimeout };
    this.unsubscribe = options.connector.onEvent((event) => this.emit(event));
  }

  private emit(event: ConnectorEvent): void { for (const listener of this.listeners) listener(event); }
  onEvent(listener: (event: ConnectorEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
  blocked(id: string): boolean { return this.stopped || this.replacing.has(id) || this.stopping.has(id) || !!this.options.isClosing?.(id); }
  available(id: string, control = false): boolean {
    const session = this.options.connector.session(id);
    return !this.blocked(id) && !!session?.connected && (control || !session.continuityLost);
  }
  busy(id: string): boolean { return this.options.connector.session(id)?.busy ?? true; }
  continuityLost(id: string): boolean { return this.options.connector.session(id)?.continuityLost ?? false; }

  async sendMessage(id: string, content: string): Promise<{ ok: true } | { ok: false; error: string }> {
    if (!this.available(id)) return { ok: false, error: ERR_ZEROCLAW_UNAVAILABLE };
    try { await this.options.connector.sendMessage(id, content); return { ok: true }; }
    catch { return { ok: false, error: ERR_ZEROCLAW_DELIVERY }; }
  }

  cancelSession(id: string, reason: string): void { this.options.onCancelSession?.(id, reason); }

  /** Internal dismissal may call this even when isClosing rejects public admission. */
  stopSession(id: string): Promise<void> {
    const pending = this.stopping.get(id);
    if (pending) return pending;
    if (this.stopped) return Promise.reject(new Error(ERR_ZEROCLAW_UNAVAILABLE));
    const session = this.options.connector.session(id);
    if (!session?.connected) return Promise.reject(new Error(ERR_ZEROCLAW_UNAVAILABLE));
    if (!session.busy && !session.continuityLost) return Promise.resolve();
    // Defer I/O until the lock exists, including when a terminal event arrives synchronously.
    const operation = Promise.resolve().then(() => this.awaitTerminal(id));
    this.stopping.set(id, operation);
    return operation.finally(() => {
      this.stopping.delete(id);
      this.wake(id);
    });
  }

  private async awaitTerminal(id: string): Promise<void> {
    const terminal = Promise.withResolvers<void>();
    let settled = false;
    let terminalSeen = false;
    let abortAccepted = false;
    let poll: NodeJS.Timeout | undefined;
    const finish = (error?: Error) => {
      if (settled) return;
      settled = true;
      if (error) terminal.reject(error); else terminal.resolve();
    };
    const cancel = () => finish(new Error(ERR_ZEROCLAW_UNAVAILABLE));
    this.cancellations.add(cancel);
    const unsubscribe = this.options.connector.onEvent((event) => {
      if (!('sessionId' in event) || event.sessionId !== id) return;
      if ((event.type === 'frame' && ['done', 'aborted'].includes(event.frame.type))
        || (event.type === 'turn-state' && event.terminalConfirmed)) {
        terminalSeen = true;
        if (abortAccepted) finish();
      }
    });
    const timeout = this.timers.setTimeout(() => finish(new Error(ERR_ZEROCLAW_TIMEOUT)), this.options.timeoutMs ?? 30_000);
    const refresh = async () => {
      if (settled) return;
      try { await this.options.connector.refreshTurnState(id); } catch { /* Failure is not evidence of idle. */ }
      if (!settled) poll = this.timers.setTimeout(() => { void refresh(); }, 100);
    };
    void this.options.connector.abortSession(id).then((result) => {
      // Keep dispatch locked until the POST settles; a late abort must not hit the next followUp.
      abortAccepted = true;
      if (result.terminalConfirmed || terminalSeen) finish();
      else if (!settled) void refresh();
    }, () => finish(new Error(ERR_ZEROCLAW_UNAVAILABLE)));
    try { await terminal.promise; }
    finally {
      unsubscribe(); this.cancellations.delete(cancel); this.timers.clearTimeout(timeout);
      if (poll !== undefined) this.timers.clearTimeout(poll);
    }
  }

  async newSession(id: string, officeAgentId?: string): Promise<string> {
    if (!this.available(id, true)) throw new Error(ERR_ZEROCLAW_UNAVAILABLE);
    const descriptor = this.options.connector.session(id)!;
    const identity = descriptor.officeAgentId ?? officeAgentId;
    this.replacing.add(id);
    this.cancelSession(id, ERR_ZEROCLAW_CHANGED);
    let closed = false;
    let next: string | undefined;
    try {
      await this.stopSession(id);
      if (this.stopped) throw new Error(ERR_ZEROCLAW_UNAVAILABLE);
      await this.options.connector.closeSession(id, { replacement: true });
      closed = true;
      next = randomUUID();
      await this.options.connector.openSession({ sessionId: next, agent: descriptor.agent, name: descriptor.name,
        cwd: descriptor.cwd, cast: descriptor.cast, officeAgentId: identity });
      return next;
    } catch {
      if (closed) {
        if (next) await this.options.connector.closeSession(next, { replacement: true }).catch(() => undefined);
        if (identity) this.options.onReplacementFailed?.(identity);
      }
      throw new Error(ERR_ZEROCLAW_UNAVAILABLE);
    } finally { this.replacing.delete(id); this.wake(id); }
  }

  private wake(id: string): void {
    const session = this.options.connector.session(id);
    if (session && !this.stopped) this.emit({ type: 'turn-state', sessionId: id, busy: session.busy,
      continuityLost: session.continuityLost, terminalConfirmed: !session.busy && !session.continuityLost, source: 'delivery-unlocked' });
  }

  stop(): void {
    this.stopped = true;
    this.unsubscribe();
    for (const cancel of this.cancellations) cancel();
    this.listeners.clear();
  }
}
