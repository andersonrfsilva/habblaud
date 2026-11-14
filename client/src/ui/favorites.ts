// Sessões favoritas: modelo puro (retratos, junção com a lista do servidor, liga/desliga) e o armazenamento no
// navegador, compartilhado pelo popover do Histórico e pela gaveta (estrela do agente e da sala). Quem desenha assina
// as mudanças com favorites.subscribe; outra aba avisa pelo evento `storage`.
import type { AgentInfo, RecentSession, RoomInfo } from '../../../shared/types';
import { parseRecentSessions } from './recent-sessions';

export const FAVORITES_KEY = 'habblaud.history.favorites';
export const PINS_KEY = 'habblaud.history.pins';
export const MAX_FAVORITES = 100;

export function sessionKey(s: Pick<RecentSession, 'account' | 'sessionId' | 'provider'>): string {
  return JSON.stringify([s.provider ?? 'claude', s.account, s.sessionId]);
}

/** Retratos válidos do JSON salvo (reusa parseRecentSessions({ sessions: raw })), sem duplicados. */
export function parseFavorites(raw: unknown): RecentSession[] {
  const seen = new Set<string>();
  return parseRecentSessions({ sessions: raw }).filter((s) => {
    const k = sessionKey(s);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/** Retrato de uma favorita que o servidor já não lista: encerrada e sem agente. */
function snapshotOf(s: RecentSession): RecentSession {
  const { agentId: _agentId, ...rest } = s;
  return { ...rest, open: false };
}

function capFavorites(list: RecentSession[]): RecentSession[] {
  return list.length > MAX_FAVORITES ? [...list].sort((a, b) => b.lastAt - a.lastAt).slice(0, MAX_FAVORITES) : list;
}

/**
 * Junta favoritas com a lista do servidor. favorites: versão do servidor quando existe, senão o retrato com open=false
 * e sem agentId; lastAt decrescente. rest: sessões do servidor que não são favoritas. updated: retratos para salvar.
 */
export function splitFavorites(saved: readonly RecentSession[], sessions: readonly RecentSession[]): { favorites: RecentSession[]; rest: RecentSession[]; updated: RecentSession[] } {
  const server = new Map(sessions.map((s) => [sessionKey(s), s]));
  const favKeys = new Set(saved.map(sessionKey));
  const favorites = saved.map((f) => server.get(sessionKey(f)) ?? snapshotOf(f)).sort((a, b) => b.lastAt - a.lastAt);
  const rest = sessions.filter((s) => !favKeys.has(sessionKey(s)));
  const updated = capFavorites(saved.map((f) => { const live = server.get(sessionKey(f)); return live ? snapshotOf(live) : f; }));
  return { favorites, rest, updated };
}

/** Liga/desliga: devolve a nova lista salva (adiciona o retrato encerrado; corta em MAX_FAVORITES pelos lastAt mais antigos). */
export function toggleFavorite(saved: readonly RecentSession[], s: RecentSession): RecentSession[] {
  const k = sessionKey(s);
  if (saved.some((f) => sessionKey(f) === k)) return saved.filter((f) => sessionKey(f) !== k);
  return capFavorites([...saved, snapshotOf(s)]);
}

/** Fixadas primeiro; a atividade mantém a ordem dentro de cada partição. */
export function pinnedFirst(list: readonly RecentSession[], pinned: ReadonlySet<string>): RecentSession[] {
  return [...list].sort((a, b) => Number(pinned.has(sessionKey(b))) - Number(pinned.has(sessionKey(a))) || b.lastAt - a.lastAt);
}

/**
 * Retrato da sessão de um agente para a estrela da gaveta; null quando não há sessão própria no histórico (subagente)
 * ou o agente é fictício (demo). Conta e sessionId são os mesmos que /api/sessions/recent devolve: o servidor casa
 * o agente aberto com a sessão por (account, sessionId) (openMainAgent), nos três provedores.
 */
export function agentSession(agent: AgentInfo, room: Pick<RoomInfo, 'path'> | undefined, demo: boolean): RecentSession | null {
  if (demo || agent.kind !== 'main' || !agent.account || !agent.sessionId) return null;
  const s: RecentSession = {
    account: agent.account,
    sessionId: agent.sessionId,
    // Cwd do projeto codificado como o servidor faz (todo caractere não alfanumérico vira "-").
    projectDir: room?.path ? room.path.replace(/[^a-zA-Z0-9]/g, '-') : '',
    lastAt: agent.lastEventAt || agent.startedAt,
    firstAt: agent.startedAt,
    size: 0,
    open: false,
  };
  if (agent.provider === 'codex' || agent.provider === 'omp' || agent.provider === 'zeroclaw') s.provider = agent.provider;
  if (room?.path) s.project = room.path;
  if (agent.title) s.title = agent.title;
  return s;
}

export type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

export class FavoritesStore {
  private list: RecentSession[] | undefined;
  private listeners = new Set<() => void>();
  private watching = false;

  constructor(private storage: () => StorageLike | undefined, private key = FAVORITES_KEY) {}

  /** Retratos salvos (carrega do armazenamento na primeira leitura). */
  all(): readonly RecentSession[] {
    return (this.list ??= this.load());
  }

  has(key: string): boolean {
    return this.all().some((f) => sessionKey(f) === key);
  }

  /** Liga/desliga a sessão e avisa os assinantes. */
  toggle(s: RecentSession): boolean {
    const next = toggleFavorite(this.all(), s);
    if (!this.save(next)) return false;
    this.list = next;
    this.notify();
    return true;
  }

  /** Grava a lista atualizada pela junção com o servidor (retratos novos); não avisa: quem chama já está desenhando. */
  replace(list: RecentSession[]): boolean {
    if (!this.save(list)) return false;
    this.list = list;
    return true;
  }

  clear(): boolean {
    if (!this.replace([])) return false;
    this.notify();
    return true;
  }

  /** Assina as mudanças (toggle aqui ou em outra aba); devolve o cancelamento. */
  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    this.watch();
    return () => this.listeners.delete(fn);
  }

  private notify(): void {
    for (const fn of [...this.listeners]) fn();
  }

  private watch(): void {
    if (this.watching || typeof window === 'undefined') return;
    this.watching = true;
    window.addEventListener('storage', (e) => {
      if (e.key !== this.key && e.key !== null) return;
      this.list = this.load();
      this.notify();
    });
  }

  private load(): RecentSession[] {
    try {
      return parseFavorites(JSON.parse(this.storage()?.getItem(this.key) ?? '[]'));
    } catch {
      return [];
    }
  }

  private save(list: readonly RecentSession[]): boolean {
    try {
      const storage = this.storage();
      if (!storage) return false;
      storage.setItem(this.key, JSON.stringify(list));
      return true;
    } catch {
      return false;
    }
  }
}

/** As favoritas do navegador. */
export const favorites = new FavoritesStore(() => (typeof localStorage === 'undefined' ? undefined : localStorage));
export const pins = new FavoritesStore(() => (typeof localStorage === 'undefined' ? undefined : localStorage), PINS_KEY);
