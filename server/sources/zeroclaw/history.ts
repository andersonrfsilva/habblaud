import { realpathSync, statSync } from 'node:fs';
import { sep } from 'node:path';
import type { RecentSession } from '../../../shared/types';
import { HISTORY_LIMIT, HISTORY_MAX_AGE_MS } from '../history';
import type { HistoryProvider, HistoryResolveResult } from '../source';
import { createTerminalParser } from '../terminal';
import { encodeCwd } from '../watcher';
import { ZeroClawConnector } from './connector';
import { ZeroClawMirror, type SessionMetadata } from './mirror';

export const isZeroClawSessionId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

export interface ZeroClawHistoryOptions {
  connector: ZeroClawConnector;
  mirror: ZeroClawMirror;
  account: () => string;
  openAgentOf: (account: string, sessionId: string) => string | undefined;
  diagnostic?: (message: string) => void;
  now?: () => number;
  limit?: number;
  maxAgeMs?: number;
}

export class ZeroClawHistory implements HistoryProvider {
  readonly provider = 'zeroclaw' as const;
  constructor(private readonly opts: ZeroClawHistoryOptions) {}

  hasAccount(account: string): boolean { return account === this.opts.account(); }

  async list(): Promise<RecentSession[]> {
    const { connector, mirror } = this.opts;
    let candidates: SessionMetadata[];
    try {
      const remote = await connector.listSessions();
      candidates = remote.filter((s) => isZeroClawSessionId(s.session_id) && typeof s.agent_alias === 'string' && !!s.agent_alias && (s.channel_id == null || s.channel_id === '' || s.channel_id === 'web' || s.channel_id === 'websocket' || s.channel_id === 'cli'))
        .map((s): SessionMetadata => ({ ...mirror.entries().find((m) => m.sessionId === s.session_id), sessionId: s.session_id as string, agent: s.agent_alias as string,
          ...(typeof s.name === 'string' ? { name: s.name } : {}), firstAt: dateOf(s.created_at), lastAt: dateOf(s.last_activity) }));
    } catch {
      this.opts.diagnostic?.('ZeroClaw: histórico local desatualizado; gateway indisponível');
      candidates = mirror.entries();
    }
    const now = (this.opts.now ?? Date.now)();
    const picked = candidates.filter((s) => s.lastAt !== undefined && now - s.lastAt <= (this.opts.maxAgeMs ?? HISTORY_MAX_AGE_MS))
      .sort((a, b) => b.lastAt! - a.lastAt!).slice(0, this.opts.limit ?? HISTORY_LIMIT);
    // Two workers bound snapshot imports, including when a remote session contains a large transcript.
    let cursor = 0;
    const results: RecentSession[] = [];
    await Promise.all(Array.from({ length: Math.min(2, picked.length) }, async () => {
      while (cursor < picked.length) {
        const metadata = picked[cursor++];
        try {
          await mirror.setMetadata(metadata);
          if ('status' in this.resolve(this.opts.account(), metadata.sessionId)) {
            const response = await connector.request(`/api/sessions/${metadata.sessionId}/messages`);
            const messages = Array.isArray(response) ? response : response && typeof response === 'object' && 'messages' in response ? response.messages : undefined;
            if (!Array.isArray(messages)) throw new Error('histórico inválido');
            await mirror.bootstrap(metadata.sessionId, messages);
          }
          const resolved = this.resolve(this.opts.account(), metadata.sessionId);
          if ('status' in resolved) continue;
          const agentId = this.opts.openAgentOf(this.opts.account(), metadata.sessionId);
          results.push({ account: this.opts.account(), provider: 'zeroclaw', sessionId: metadata.sessionId,
            projectDir: metadata.cwd ? encodeCwd(metadata.cwd) : 'sessions', ...(metadata.cwd ? { project: metadata.cwd } : {}),
            ...(metadata.name ? { title: metadata.name } : {}), firstAt: metadata.firstAt, lastAt: metadata.lastAt!,
            size: statSync(resolved.path).size, open: !!agentId, ...(agentId ? { agentId } : {}) });
        } catch {
          this.opts.diagnostic?.('ZeroClaw: não foi possível materializar uma sessão do histórico');
        }
      }
    }));
    return results.sort((a, b) => b.lastAt - a.lastAt);
  }

  resolve(account: string, sessionId: string): HistoryResolveResult {
    if (!isZeroClawSessionId(sessionId)) return { status: 400, error: 'id de sessão inválido' };
    if (!this.hasAccount(account)) return { status: 404, error: 'conta desconhecida' };
    try {
      const root = realpathSync(this.opts.mirror.root + '/sessions');
      const path = realpathSync(this.opts.mirror.pathOf(sessionId));
      if (path.startsWith(root + sep) && statSync(path).isFile()) return { path, createParser: createTerminalParser };
    } catch { /* A missing mirror is not fetched by synchronous resolve. */ }
    return { status: 404, error: 'sessão não encontrada' };
  }
}

function dateOf(value: unknown): number | undefined {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined;
  const timestamp = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : undefined;
}
