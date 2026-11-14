import { existsSync } from 'node:fs';
import { dirname } from 'node:path';
import { describeTool, maskSecrets } from '../../../shared/activity';
import { toAgyModel } from '../../../shared/agy-model';
import { tr } from '../../../shared/i18n';
import type { SourceInfo } from '../../../shared/types';
import type { AccountsService } from '../../accounts/service';
import type { AcpEvent, AntigravityClient, SessionInfo } from '../../antigravity/acp';
import { log } from '../../log';
import type { Office } from '../../model/office';
import type { AgentSource } from '../source';
import { createTranscriptState } from '../transcript';
import { antigravityTool, AntigravityMirror } from './mirror';
import { AntigravityCatalog } from './catalog';

type Tracker = { id: string; info: SessionInfo; title?: string; context?: { tokens: number; window: number }; open: boolean; replaced?: boolean };
export class AntigravitySource implements AgentSource {
  readonly provider = 'antigravity' as const;
  accountId = 'antigravity';
  private readonly trackers = new Map<string, Tracker>();
  private unsubscribe?: () => void;
  private pending = Promise.resolve();
  private readonly replacing = new Set<string>();
  private readonly catalog: AntigravityCatalog;
  constructor(private readonly opts: { accounts: AccountsService; office: Office; client: Pick<AntigravityClient, 'onEvent' | 'session'>; mirror: AntigravityMirror; catalog?: AntigravityCatalog }) {
    this.catalog = opts.catalog ?? new AntigravityCatalog(dirname(opts.mirror.root));
  }
  start(): void {
    if (this.unsubscribe) return;
    this.accountId = this.opts.accounts.setProviderAccounts('antigravity', [{ dir: this.opts.mirror.root,
      detected: { id: 'antigravity', configDir: this.opts.mirror.root, short: 'G', name: 'Antigravity', color: '#6c8cff' } }])[0].id;
    this.unsubscribe = this.opts.client.onEvent(event => {
      // Presence must be synchronous so HTTP can enqueue the opening message immediately.
      this.apply(event);
      this.pending = this.pending.then(() => this.opts.mirror.consume(event)).catch(() => log.warn(tr('Antigravity: falha ao gravar o terminal')));
    });
  }
  stop(): void {
    this.unsubscribe?.(); this.unsubscribe = undefined;
    for (const tracker of this.trackers.values()) if (tracker.open) { tracker.open = false; this.opts.office.closeMain(tracker.id); }
  }
  async flush(): Promise<void> { await this.pending; }
  /** Troca a sessão de um agente: `open` abre a nova (com `replaces: oldSessionId`); o agente continua o mesmo. */
  async replace(oldSessionId: string, open: () => Promise<{ sessionId: string }>): Promise<string> {
    const previous = this.trackers.get(oldSessionId);
    if (this.replacing.has(oldSessionId) || !previous?.open || previous.replaced) throw new Error(tr('a sessão Antigravity está indisponível ou em substituição'));
    this.replacing.add(oldSessionId);
    try {
      return (await open()).sessionId;
    } finally { this.replacing.delete(oldSessionId); }
  }
  sources(): SourceInfo[] {
    return [{ label: 'antigravity', provider: 'antigravity', path: '', sessions: [...this.trackers.values()].filter(tracker => tracker.open).length, ok: true }];
  }
  transcriptPathOf(agentId: string): string | undefined {
    const current = this.opts.office.get(agentId)?.sessionId;
    if (!current || this.trackers.get(current)?.id !== agentId) return undefined;
    const path = this.opts.mirror.pathOf(current);
    return existsSync(path) ? path : undefined;
  }
  private apply(event: AcpEvent): void {
    if (event.type === 'diagnostic') return;
    const { office } = this.opts;
    if (event.type === 'session-open') {
      this.catalog.learn(event.info.modelOptions);
      const oldSession = event.replaces && this.replacing.has(event.replaces) ? this.trackers.get(event.replaces) : undefined;
      const previous = oldSession?.open && !oldSession.replaced && office.has(oldSession.id) ? oldSession : undefined;
      const id = previous?.id ?? `antigravity:${event.sessionId}`;
      this.trackers.set(event.sessionId, { id, info: event.info, open: true });
      if (previous) {
        office.switchSession(id, event.sessionId);
        previous.replaced = true;
        previous.open = false;
      }
      else office.addMain({ id, provider: 'antigravity', account: this.accountId, sessionId: event.sessionId, cwd: event.cwd, cast: event.cast,
        role: tr('Agente principal (Antigravity)'), startedAt: Date.now(), status: 'idle' });
      this.summary(event.sessionId);
      return;
    }
    const tracker = this.trackers.get(event.sessionId);
    if (!tracker || tracker.replaced) return;
    if (event.type === 'session-close') { tracker.open = false; office.closeMain(tracker.id); }
    if (event.type === 'turn-state') office.setStatus(tracker.id, event.busy ? 'working' : 'idle');
    if (event.type === 'permission') office.setStatus(tracker.id, 'waiting', maskSecrets(typeof event.toolCall.title === 'string' ? event.toolCall.title : ''));
    if (event.type === 'permission-gone' && this.opts.client.session(event.sessionId)?.busy) office.setStatus(tracker.id, 'working');
    if (event.type !== 'update') return;
    const update = event.update;
    if (update.sessionUpdate === 'config_option_update' && Array.isArray(update.configOptions)) {
      const model = update.configOptions.find(option => option && typeof option === 'object' && option.id === 'model');
      if (model && typeof model.currentValue === 'string') tracker.info.model = model.currentValue;
    }
    if (update.sessionUpdate === 'tool_call') {
      const tool = antigravityTool(update);
      office.addActivity(tracker.id, { ...describeTool(tool.name, tool.input), id: `${tracker.id}:${String(update.toolCallId)}`, at: Date.now(), tool: tool.name }, true);
    }
    if (update.sessionUpdate === 'session_info_update' && typeof update.title === 'string') tracker.title = maskSecrets(update.title);
    if (update.sessionUpdate === 'usage_update' && typeof update.used === 'number' && typeof update.size === 'number') tracker.context = { tokens: update.used, window: update.size };
    this.summary(event.sessionId);
  }
  private summary(sessionId: string): void {
    const tracker = this.trackers.get(sessionId)!;
    this.opts.office.applyTranscript(tracker.id, { title: tracker.title, model: tracker.info.model ? toAgyModel(tracker.info.model) : undefined,
      models: tracker.info.modelOptions.map(option => toAgyModel(option.value)),
      context: tracker.context, tasks: [], stats: createTranscriptState().stats });
  }
}
