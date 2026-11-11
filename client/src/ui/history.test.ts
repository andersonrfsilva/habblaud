import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentInfo, CastMember, RecentSession } from '../../../shared/types';
import { canResume, canResumeViaCast, castResumeTargets, dayLabel, filterSessions, formatSize, groupSessionsByDay, resumeHistoryCast, resumeTargets } from './history';
import { parseRecentSessions } from './recent-sessions';

const NOW = new Date(2026, 9, 8, 15, 0, 0).getTime();
const at = (day: number, hour: number, min = 0) => new Date(2026, 9, day, hour, min).getTime();

function session(p: Partial<RecentSession> & Pick<RecentSession, 'sessionId' | 'lastAt'>): RecentSession {
  return { account: '.claude', projectDir: '-p', size: 1_000, open: false, ...p };
}

afterEach(() => vi.unstubAllGlobals());

describe('sessões do Codex no histórico', () => {
  it('parseRecentSessions guarda o provider só quando é o Codex; a busca acha "codex"', () => {
    const list = parseRecentSessions({
      sessions: [
        { account: '.codex', provider: 'codex', sessionId: 'x', projectDir: '/p', lastAt: 2, size: 1, open: false },
        { account: '.claude', provider: 'claude', sessionId: 'c', projectDir: '-p', lastAt: 1, size: 1, open: false },
        { account: '.claude', provider: 'outro', sessionId: 'd', projectDir: '-p', lastAt: 0, size: 1, open: false },
      ],
    });
    expect(list.map((s) => [s.sessionId, s.provider])).toEqual([
      ['x', 'codex'],
      ['c', undefined],
      ['d', undefined],
    ]);
    expect(filterSessions(list, 'codex').map((s) => s.sessionId)).toEqual(['x']);
  });
});

describe('retomar sessão do omp', () => {
  it('canResume: só sessão do omp encerrada e que não é a atual do agente', () => {
    const omp = session({ sessionId: 's1', lastAt: 1, provider: 'omp' });
    expect(canResume(omp)).toBe(true);
    expect(canResume(omp, 's1')).toBe(false);
    expect(canResume({ ...omp, open: true })).toBe(false);
    expect(canResume({ ...omp, provider: 'codex' })).toBe(false);
    expect(canResume(session({ sessionId: 's2', lastAt: 1 }))).toBe(false);
  });

  it('resumeTargets: principais do omp com a extensão conectada e livres', () => {
    const a = (id: string, p: Partial<AgentInfo>) => ({ id, provider: 'omp', kind: 'main', canMessage: true, status: 'idle', ...p }) as AgentInfo;
    const list = [a('livre', {}), a('shell', { status: 'shell' }), a('ocupado', { status: 'working' }), a('sem-ext', { canMessage: undefined }), a('sub', { kind: 'sub' }), a('claude', { provider: 'claude' })];
    expect(resumeTargets(list).map((x) => x.id)).toEqual(['livre', 'shell']);
  });
});

describe('retomada ZeroClaw pelo elenco', () => {
  const catalog = { provider: 'zeroclaw' as const, models: [{ alias: 'm_123', model: 'openai/gpt-5' }], defaultModel: 'm_123' };
  const zero = session({ provider: 'zeroclaw', account: 'tenant', project: '/project', sessionId: '12345678-abcd', lastAt: 1 });

  it('preserva provider, conta e sessão na leitura do histórico e na busca', () => {
    const [parsed] = parseRecentSessions({ sessions: [zero] });
    expect(parsed).toEqual(zero);
    expect(filterSessions([parsed], 'ZeroClaw')).toEqual([zero]);
    expect(canResume(zero)).toBe(false);
    expect(resumeTargets([{ provider: 'zeroclaw', kind: 'main', canMessage: true, status: 'idle' } as AgentInfo])).toEqual([]);
  });

  it('exige runtime, conta, pasta e sessão compatíveis e encerrados', () => {
    expect(canResumeViaCast(zero, catalog, 'zeroclaw')).toBe(true);
    expect(canResumeViaCast(zero, undefined, 'zeroclaw')).toBe(false);
    expect(canResumeViaCast(zero, { ...catalog, provider: 'omp' }, 'zeroclaw')).toBe(false);
    expect(canResumeViaCast({ ...zero, provider: 'omp' }, catalog, 'omp')).toBe(false);
    expect(canResumeViaCast(zero, catalog, 'omp')).toBe(false);
    expect(canResumeViaCast(zero, catalog, undefined)).toBe(false);
    expect(canResumeViaCast({ ...zero, project: undefined }, catalog, 'zeroclaw')).toBe(false);
    expect(canResumeViaCast({ ...zero, project: 'relative' }, catalog, 'zeroclaw')).toBe(false);
    expect(canResumeViaCast({ ...zero, sessionId: 'invalid' }, catalog, 'zeroclaw')).toBe(false);
    expect(canResumeViaCast({ ...zero, open: true }, catalog, 'zeroclaw')).toBe(false);
  });

  it('oferece só personagens principais livres, com conta compatível', () => {
    const member = (id: string, extra: Partial<CastMember> = {}): CastMember => ({ id, name: id, kind: 'main', seed: 1, look: 'f', ...extra });
    const members = [
      member('free'), member('same-account', { account: 'tenant' }), member('other-account', { account: 'other' }),
      member('advisor', { kind: 'advisor' }), member('busy'), member('offline'),
    ];
    const agents = [{ cast: 'busy', status: 'idle' }, { cast: 'offline', status: 'offline' }] as AgentInfo[];
    expect(castResumeTargets(zero, members, agents).map((m) => m.id)).toEqual(['free', 'same-account', 'offline']);
  });

  it('retoma pelo startCast com UUID e conta exatos, nunca pelo controle OMP', async () => {
    const startCast = vi.fn().mockResolvedValue(undefined);
    const terminal = { openSession: vi.fn() };
    expect(await resumeHistoryCast(zero, 'character', { startCast }, terminal)).toBeUndefined();
    expect(startCast).toHaveBeenCalledExactlyOnceWith('character', 'resume', '', { room: '/project', session: zero.sessionId, account: zero.account });
    expect(terminal.openSession).not.toHaveBeenCalled();
  });

  it('sessão aberta abre o terminal e não cria segunda chamada nem POST', async () => {
    const startCast = vi.fn();
    const openSession = vi.fn();
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const opened = { ...zero, open: true, agentId: 'live' };
    await resumeHistoryCast(opened, 'character', { startCast }, { openSession });
    await resumeHistoryCast(opened, undefined, { startCast }, { openSession });
    expect(openSession).toHaveBeenCalledTimes(2);
    expect(openSession).toHaveBeenCalledWith(opened);
    expect(startCast).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('Novo usa cast/new e apresenta erro de histórico/associação do servidor sem fallback vazio', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, json: async () => ({ error: 'Histórico ZeroClaw incompatível com a sala ou o catálogo' }) });
    vi.stubGlobal('fetch', fetch);
    const error = await resumeHistoryCast(zero, undefined, { startCast: vi.fn() }, { openSession: vi.fn() });
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/cast/new', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ room: '/project', session: zero.sessionId, account: 'tenant' }),
    });
    expect(error).toBe('Histórico ZeroClaw incompatível com a sala ou o catálogo');
  });
});

describe('groupSessionsByDay', () => {
  it('Hoje, Ontem e datas, do dia mais recente para o mais antigo (e da mais recente para a mais antiga dentro do dia)', () => {
    const list = [
      session({ sessionId: 'a', lastAt: at(8, 9) }),
      session({ sessionId: 'b', lastAt: at(6, 23, 59) }),
      session({ sessionId: 'c', lastAt: at(8, 14) }),
      session({ sessionId: 'd', lastAt: at(7, 0, 5) }),
      session({ sessionId: 'e', lastAt: at(2, 10) }),
    ];
    const days = groupSessionsByDay(list, NOW);
    expect(days.map((d) => [d.label, d.sessions.map((s) => s.sessionId)])).toEqual([
      ['Hoje', ['c', 'a']],
      ['Ontem', ['d']],
      [dayLabel(at(6, 12), NOW), ['b']],
      [dayLabel(at(2, 12), NOW), ['e']],
    ]);
    expect(days[2].label).toMatch(/^Ter.*06.*out/i);
    expect(days[3].label).toMatch(/^Sex.*02.*out/i);
    expect(groupSessionsByDay([], NOW)).toEqual([]);
  });
});

describe('filterSessions', () => {
  const list = [
    session({ sessionId: 'a', lastAt: 3, title: 'Configuração do carrinho', project: '/Users/ana/projetos/loja' }),
    session({ sessionId: 'b', lastAt: 2, title: 'Rota de login', project: '/Users/ana/projetos/api', account: '.claude-conta2' }),
    session({ sessionId: 'c', lastAt: 1, projectDir: '-Users-ana-projetos-site' }),
  ];
  const label = (id: string) => (id === '.claude-conta2' ? 'Trabalho T' : 'Pessoal P');

  it('por título ou projeto, sem diferenciar maiúsculas nem acentos; todos os termos precisam casar', () => {
    expect(filterSessions(list, 'configuracao').map((s) => s.sessionId)).toEqual(['a']);
    expect(filterSessions(list, 'LOJA').map((s) => s.sessionId)).toEqual(['a']);
    expect(filterSessions(list, 'site').map((s) => s.sessionId)).toEqual(['c']);
    expect(filterSessions(list, 'rota api').map((s) => s.sessionId)).toEqual(['b']);
    expect(filterSessions(list, 'rota loja')).toEqual([]);
  });

  it('pela conta (nome ou letra); sem termo, tudo', () => {
    expect(filterSessions(list, 'trabalho', label).map((s) => s.sessionId)).toEqual(['b']);
    expect(filterSessions(list, 'pessoal', label).map((s) => s.sessionId)).toEqual(['a', 'c']);
    expect(filterSessions(list, '  ', label)).toHaveLength(3);
  });
});

describe('parseRecentSessions', () => {
  it('aceita as válidas, ignora o resto e ordena da mais recente para a mais antiga', () => {
    const raw = {
      sessions: [
        { account: '.claude', sessionId: 's1', projectDir: '-p', project: '/p', title: 'Um', firstAt: 1, lastAt: 10, size: 99, open: false, agentId: 'x' },
        { account: '.claude', sessionId: 's2', lastAt: 20, open: true, agentId: '.claude:7' },
        { account: '.claude', lastAt: 30 },
        { sessionId: 's4', lastAt: 30 },
        { account: '.claude', sessionId: 's5', lastAt: 'ontem' },
        null,
        'x',
      ],
    };
    const out = parseRecentSessions(raw);
    expect(out.map((s) => s.sessionId)).toEqual(['s2', 's1']);
    expect(out[0]).toEqual({ account: '.claude', sessionId: 's2', projectDir: '', lastAt: 20, size: 0, open: true, agentId: '.claude:7' });
    // agentId só vale para sessão aberta.
    expect(out[1]).toEqual({ account: '.claude', sessionId: 's1', projectDir: '-p', project: '/p', title: 'Um', firstAt: 1, lastAt: 10, size: 99, open: false });
    expect(parseRecentSessions(null)).toEqual([]);
    expect(parseRecentSessions({ sessions: 'x' })).toEqual([]);
  });
});

describe('utilitários', () => {
  it('formatSize', () => {
    expect(formatSize(10)).toBe('1 KB');
    expect(formatSize(820 * 1024)).toBe('820 KB');
    expect(formatSize(3.4 * 1024 * 1024)).toBe('3,4 MB');
  });
});
