import { describe, expect, it } from 'vitest';
import type { AgentInfo, RecentSession } from '../../../shared/types';
import { agentSession, FAVORITES_KEY, PINS_KEY, FavoritesStore, MAX_FAVORITES, parseFavorites, pinnedFirst, sessionKey, splitFavorites, toggleFavorite, type StorageLike } from './favorites';

function session(p: Partial<RecentSession> & Pick<RecentSession, 'sessionId' | 'lastAt'>): RecentSession {
  return { account: '.claude', projectDir: '-p', size: 1_000, open: false, ...p };
}

describe('favoritas', () => {
  it('parseFavorites ignora lixo e duplicados', () => {
    const a = session({ sessionId: 'a', lastAt: 1 });
    expect(parseFavorites(null)).toEqual([]);
    expect(parseFavorites('x')).toEqual([]);
    expect(parseFavorites([null, 'x', { account: '.claude', lastAt: 1 }, a, a]).map(sessionKey)).toEqual([sessionKey(a)]);
  });

  it('identidade separa provedor e delimitadores da conta e da sessão', () => {
    const base = session({ account: 'one', sessionId: 'two:three', lastAt: 1 });
    expect(sessionKey(base)).not.toBe(sessionKey({ ...base, account: 'one:two', sessionId: 'three' }));
    expect(sessionKey({ ...base, provider: 'omp' })).not.toBe(sessionKey({ ...base, account: 'omp:one' }));
    expect(sessionKey({ ...base, provider: 'claude' })).toBe(sessionKey(base));
  });

  it('splitFavorites: versão do servidor vence, retrato antigo fica encerrado, favoritas saem de rest', () => {
    const savedA = session({ sessionId: 'a', lastAt: 1, title: 'velho' });
    const savedOld = session({ sessionId: 'old', lastAt: 5, title: 'antiga' });
    const serverA = session({ sessionId: 'a', lastAt: 9, title: 'novo', open: true, agentId: 'ag' });
    const other = session({ sessionId: 'b', lastAt: 3 });
    const { favorites, rest, updated } = splitFavorites([savedA, savedOld], [other, serverA]);
    expect(favorites.map((s) => [s.sessionId, s.title, s.open, s.agentId])).toEqual([
      ['a', 'novo', true, 'ag'],
      ['old', 'antiga', false, undefined],
    ]);
    expect(rest.map((s) => s.sessionId)).toEqual(['b']);
    expect(updated.map((s) => [s.sessionId, s.title, s.open, s.agentId, s.lastAt])).toEqual([
      ['a', 'novo', false, undefined, 9],
      ['old', 'antiga', false, undefined, 5],
    ]);
  });

  it('preserva identidades ZeroClaw e OMP com a mesma conta e sessão no armazenamento e na junção', () => {
    const zero = session({ provider: 'zeroclaw', account: 'tenant', sessionId: '12345678-abcd', project: '/project', lastAt: 1 });
    const omp = { ...zero, provider: 'omp' as const, title: 'Outra conversa' };
    const data = new Map<string, string>();
    const storage = { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => void data.set(key, value) };
    const store = new FavoritesStore(() => storage);
    store.toggle(zero);
    store.toggle(omp);
    const loaded = new FavoritesStore(() => storage).all();
    expect(loaded).toHaveLength(2);
    expect(loaded).toEqual(expect.arrayContaining([zero, omp]));
    expect(sessionKey(zero)).not.toBe(sessionKey(omp));
    const joined = splitFavorites(loaded, [{ ...omp, open: true, agentId: 'omp-agent' }]);
    expect(joined.favorites.find((s) => s.provider === 'zeroclaw')).toEqual(zero);
    expect(joined.favorites.find((s) => s.provider === 'omp')?.agentId).toBe('omp-agent');
    expect(joined.updated.find((s) => s.provider === 'zeroclaw')).toEqual(zero);
    expect(toggleFavorite(loaded, zero)).toEqual([omp]);
  });

  it('toggleFavorite adiciona, remove e respeita MAX_FAVORITES', () => {
    const s = session({ sessionId: 'a', lastAt: 1, open: true, agentId: 'ag' });
    const added = toggleFavorite([], s);
    expect(added).toHaveLength(1);
    expect(added[0]).toMatchObject({ sessionId: 'a', open: false });
    expect(added[0]?.agentId).toBeUndefined();
    expect(toggleFavorite(added, s)).toEqual([]);

    const many = Array.from({ length: MAX_FAVORITES }, (_, i) => session({ sessionId: `s${i}`, lastAt: 100 + i }));
    const capped = toggleFavorite(many, session({ sessionId: 'novo', lastAt: 1000 }));
    expect(capped).toHaveLength(MAX_FAVORITES);
    expect(capped.some((x) => x.sessionId === 'novo')).toBe(true);
    expect(capped.some((x) => x.sessionId === 's0')).toBe(false);
  });
});

describe('agentSession (estrela da gaveta)', () => {
  const agent = (p: Partial<AgentInfo> = {}) =>
    ({ id: '.claude:1', kind: 'main', account: '.claude', sessionId: 's1', title: 'Meu título', startedAt: 100, lastEventAt: 500, ...p }) as AgentInfo;
  const room = { path: '/home/u/meu projeto' };

  it('monta o retrato como o histórico do servidor', () => {
    expect(agentSession(agent(), room, false)).toEqual({
      account: '.claude',
      sessionId: 's1',
      projectDir: '-home-u-meu-projeto',
      project: '/home/u/meu projeto',
      title: 'Meu título',
      firstAt: 100,
      lastAt: 500,
      size: 0,
      open: false,
    });
    expect(sessionKey(agentSession(agent(), room, false)!)).toBe(JSON.stringify(['claude', '.claude', 's1']));
  });

  it('provider codex/omp vai no retrato; claude fica sem; sem sala nem título, só o essencial', () => {
    expect(agentSession(agent({ provider: 'codex', account: '.codex' }), room, false)?.provider).toBe('codex');
    expect(agentSession(agent({ provider: 'omp' }), room, false)?.provider).toBe('omp');
    expect(agentSession(agent({ provider: 'zeroclaw' }), room, false)?.provider).toBe('zeroclaw');
    expect(agentSession(agent({ provider: 'claude' }), room, false)?.provider).toBeUndefined();
    const bare = agentSession(agent({ title: undefined, lastEventAt: 0 }), undefined, false)!;
    expect(bare).toMatchObject({ projectDir: '', lastAt: 100 });
    expect(bare.project).toBeUndefined();
    expect(bare.title).toBeUndefined();
  });

  it('sem estrela para subagente e para o demo', () => {
    expect(agentSession(agent({ kind: 'sub' }), room, false)).toBeNull();
    expect(agentSession(agent(), room, true)).toBeNull();
  });
});

describe('FavoritesStore', () => {
  function memory(initial?: string): StorageLike & { data: Map<string, string> } {
    const data = new Map<string, string>(initial === undefined ? [] : [[FAVORITES_KEY, initial]]);
    return { data, getItem: (k) => data.get(k) ?? null, setItem: (k, v) => void data.set(k, v) };
  }

  it('carrega o salvo, liga/desliga, grava e avisa os assinantes', () => {
    const mem = memory(JSON.stringify([session({ sessionId: 'a', lastAt: 1 })]));
    const store = new FavoritesStore(() => mem);
    let calls = 0;
    const off = store.subscribe(() => calls++);
    expect(store.has(sessionKey(session({ sessionId: 'a', lastAt: 1 })))).toBe(true);

    store.toggle(session({ sessionId: 'b', lastAt: 2 }));
    expect(calls).toBe(1);
    expect(store.has(sessionKey(session({ sessionId: 'b', lastAt: 2 })))).toBe(true);
    expect(parseFavorites(JSON.parse(mem.data.get(FAVORITES_KEY)!)).map((s) => s.sessionId)).toEqual(['b', 'a']);

    store.toggle(session({ sessionId: 'a', lastAt: 1 }));
    expect(calls).toBe(2);
    expect(store.has(sessionKey(session({ sessionId: 'a', lastAt: 1 })))).toBe(false);

    off();
    store.toggle(session({ sessionId: 'c', lastAt: 3 }));
    expect(calls).toBe(2);
  });

  it('replace grava sem avisar; JSON ruim é ignorado e falta de armazenamento não altera marcações', () => {
    const mem = memory();
    const store = new FavoritesStore(() => mem);
    let calls = 0;
    store.subscribe(() => calls++);
    store.replace([session({ sessionId: 'x', lastAt: 1 })]);
    expect(calls).toBe(0);
    expect(mem.data.get(FAVORITES_KEY)).toContain('"x"');

    const broken = new FavoritesStore(() => memory('{não é json'));
    expect(broken.all()).toEqual([]);
    const none = new FavoritesStore(() => undefined);
    expect(none.toggle(session({ sessionId: 'y', lastAt: 1 }))).toBe(false);
    expect(none.has(sessionKey(session({ sessionId: 'y', lastAt: 1 })))).toBe(false);
  });

  it('fixadas e favoritas são independentes, recarregam e preservam retratos existentes', () => {
    const saved = session({ sessionId: 'same', account: 'one', lastAt: 1, title: 'Retrato salvo' });
    const mem = memory(JSON.stringify([saved]));
    const favorites = new FavoritesStore(() => mem);
    const pins = new FavoritesStore(() => mem, PINS_KEY);
    const otherAccount = { ...saved, account: 'two' };
    const otherProvider = { ...saved, provider: 'omp' as const };
    expect(pins.toggle(saved)).toBe(true);
    expect(pins.toggle(otherAccount)).toBe(true);
    expect(pins.toggle(otherProvider)).toBe(true);
    expect(favorites.all()).toEqual([saved]);
    expect(JSON.parse(mem.data.get(FAVORITES_KEY)!)).toEqual([saved]);
    const reloaded = new FavoritesStore(() => mem, PINS_KEY);
    expect(reloaded.all().map(sessionKey).sort()).toEqual([sessionKey(saved), sessionKey(otherAccount), sessionKey(otherProvider)].sort());
    expect(reloaded.toggle(saved)).toBe(true);
    expect(favorites.has(sessionKey(saved))).toBe(true);
    expect(reloaded.has(sessionKey(otherAccount))).toBe(true);
    expect(reloaded.has(sessionKey(otherProvider))).toBe(true);
    expect(reloaded.clear()).toBe(true);
    expect(new FavoritesStore(() => mem, PINS_KEY).all()).toEqual([]);
    expect(new FavoritesStore(() => mem).all()).toEqual([saved]);
  });

  it('limpa apenas o armazenamento da origem atual', () => {
    const saved = session({ sessionId: 'same', lastAt: 1 });
    const originA = memory(JSON.stringify([saved]));
    const originB = memory(JSON.stringify([saved]));
    const pinsA = new FavoritesStore(() => originA, PINS_KEY);
    const pinsB = new FavoritesStore(() => originB, PINS_KEY);
    pinsA.toggle(saved);
    pinsB.toggle(saved);
    new FavoritesStore(() => originA).clear();
    pinsA.clear();
    expect(new FavoritesStore(() => originA).all()).toEqual([]);
    expect(new FavoritesStore(() => originA, PINS_KEY).all()).toEqual([]);
    expect(new FavoritesStore(() => originB).all()).toEqual([saved]);
    expect(new FavoritesStore(() => originB, PINS_KEY).all()).toEqual([saved]);
  });

  it('falha ao gravar não muda a lista nem notifica assinantes', () => {
    const saved = session({ sessionId: 'saved', lastAt: 1 });
    const store = new FavoritesStore(() => ({
      getItem: () => JSON.stringify([saved]),
      setItem: () => { throw new Error('quota'); },
    }));
    let calls = 0;
    store.subscribe(() => calls++);
    expect(store.toggle(saved)).toBe(false);
    expect(store.replace([])).toBe(false);
    expect(store.all()).toEqual([saved]);
    expect(calls).toBe(0);
  });

  it('ordena fixadas antes das demais, por atividade em cada grupo, sem duplicar ou alterar a lista', () => {
    const list = [
      session({ sessionId: 'old', lastAt: 1 }),
      session({ sessionId: 'new', lastAt: 4 }),
      session({ sessionId: 'pinned-old', lastAt: 2 }),
      session({ sessionId: 'pinned-new', lastAt: 3 }),
    ];
    const pinned = new Set(list.slice(2).map(sessionKey));
    expect(pinnedFirst(list, pinned).map((s) => s.sessionId)).toEqual(['pinned-new', 'pinned-old', 'new', 'old']);
    expect(pinnedFirst(list.filter((s) => s.lastAt > 2), pinned).map((s) => s.sessionId)).toEqual(['pinned-new', 'new']);
    expect(list[0].sessionId).toBe('old');
  });
});
