import { describe, expect, it } from 'vitest';
import type { AgentInfo, CastMember } from '../../shared/types';
import { CastLauncher } from './launcher';

const member: CastMember = { id: 'ana', name: 'Ana', kind: 'main', look: 'f', seed: 1, rooms: ['/a'], idleMinutes: 5 };
const agent = { id: 'agent', cast: 'ana', kind: 'main', status: 'idle', statusSince: 0 } as AgentInfo;

function setup() {
  let now = 0;
  let current = { ...member };
  const agents: AgentInfo[] = [];
  const launcher = new CastLauncher({ dataDir: '/tmp', member: () => current, agents: () => agents, account: () => '/tmp', changed: () => {}, now: () => now });
  return { launcher, agents, time: (value: number) => { now = value; }, member: (value: CastMember) => { current = value; } };
}

describe('CastLauncher', () => {
  it('rejects option-like prompts, models and invalid resume identifiers', () => {
    const { launcher, member: setMember } = setup();
    launcher.poll();
    for (const prompt of ['-e x', ' --x']) expect(launcher.start('ana', { room: '/a', mode: 'new', prompt })).toMatchObject({ status: 422 });
    setMember({ ...member, model: '--no-session' });
    expect(launcher.start('ana', { room: '/a', mode: 'new' })).toMatchObject({ status: 422 });
    setMember({ ...member, last: { '/a': '../../bad' } });
    expect(launcher.start('ana', { room: '/a', mode: 'resume' })).toMatchObject({ status: 422 });
  });
  it('requires host, room, previous session and a free member', () => {
    const { launcher, agents } = setup();
    expect(launcher.start('ana', { room: '/a', mode: 'new' })).toMatchObject({ status: 503 });
    launcher.poll();
    expect(launcher.start('ana', { room: '/a', mode: 'resume' })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/b', mode: 'new' })).toMatchObject({ status: 422 });
    agents.push(agent);
    expect(launcher.start('ana', { room: '/a', mode: 'new' })).toMatchObject({ status: 409 });
    agents.pop();
    expect(launcher.start('ana', { room: '/a/', mode: 'new' })).toHaveProperty('job');
    expect(launcher.start('ana', { room: '/a', mode: 'new' })).toMatchObject({ status: 409 });
  });
  it('member without rooms accepts any absolute folder, even one not open in the office', () => {
    const { launcher, member: setMember } = setup();
    setMember({ ...member, rooms: [] });
    launcher.poll();
    expect(launcher.start('ana', { room: 'relativa', mode: 'new' })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/nunca/aberta//', mode: 'new' })).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', folder: '/nunca/aberta' }));
  });
  it('"Novo": abre fora do elenco com id novo-…, conclui quando o agente aparece e pode ser dispensado', () => {
    const { launcher, agents } = setup();
    expect(launcher.startNew({ room: '/x' })).toMatchObject({ status: 503 });
    launcher.poll();
    for (const bad of [{ room: 'relativa' }, {}, { room: '/x', prompt: '--x' }]) expect(launcher.startNew(bad)).toMatchObject({ status: 422 });
    const submitted = launcher.startNew({ room: '/x/' });
    if (!('job' in submitted)) throw new Error('start failed');
    const [job] = launcher.poll();
    expect(job).toMatchObject({ op: 'start', folder: '/x', agentDir: '/tmp' });
    expect(job.cast).toMatch(/^novo-[0-9a-f]{8}$/);
    expect(job).not.toHaveProperty('instructionsFile');
    launcher.ack({ results: [{ id: job.id, ok: true }] });
    agents.push({ ...agent, cast: job.cast, status: 'working' });
    launcher.tick();
    expect(launcher.jobs().find((j) => j.id === job.id)?.status).toBe('done');
    expect(launcher.dismiss(agents[0])).toHaveProperty('job');
  });
  it('papéis: valida no servidor, vai para a conta padrão, termina no ack; o que o auxiliar lê aparece em roles()', () => {
    const { launcher } = setup();
    expect(launcher.setRoles({ roles: { task: 'a/b' } })).toMatchObject({ status: 503 });
    launcher.poll({ roles: { task: 'openai/gpt:high', default: 'x/y', plan: 'sem-barra' } });
    expect(launcher.roles()).toEqual({ roles: { task: 'openai/gpt:high' }, pending: false });
    for (const bad of [{}, { roles: {} }, { roles: { default: 'a/b' } }, { roles: { task: 'gpt' } }, { roles: { task: '-x/y' } }, { roles: { task: 'a/b c' } }, { roles: { task: 3 } }])
      expect(launcher.setRoles(bad)).toMatchObject({ status: 422 });
    const submitted = launcher.setRoles({ roles: { task: 'openai/gpt-6:low', advisor: null } });
    if (!('job' in submitted)) throw new Error('roles failed');
    expect(launcher.roles().pending).toBe(true);
    const [job] = launcher.poll();
    expect(job).toEqual({ id: submitted.job.id, op: 'roles', cast: '', agentDir: '/tmp', roles: { task: 'openai/gpt-6:low', advisor: null } });
    launcher.ack({ results: [{ id: job.id, ok: true }] });
    expect(launcher.jobs().find((j) => j.id === job.id)?.status).toBe('done');
    // Logo após o ack (antes de o auxiliar reler), roles() já mostra o que foi gravado.
    expect(launcher.roles()).toEqual({ roles: { task: 'openai/gpt-6:low' }, pending: false });
  });
  it('fails a start without a session and queues a stop', () => {
    const { launcher, time } = setup();
    launcher.poll();
    const submitted = launcher.start('ana', { room: '/a', mode: 'new' });
    if (!('job' in submitted)) throw new Error('start failed');
    launcher.poll(); launcher.ack({ results: [{ id: submitted.job.id, ok: true }] });
    time(60_000); launcher.tick();
    expect(launcher.jobs().find((j) => j.id === submitted.job.id)?.status).toBe('failed');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'stop', cast: 'ana' }));
  });
  it('dismisses only opened idle sessions at the limit', () => {
    const { launcher, agents, time, member: setMember } = setup();
    expect(launcher.dismiss(agent)).toMatchObject({ status: 404 });
    launcher.poll();
    const submitted = launcher.start('ana', { room: '/a', mode: 'new' });
    if (!('job' in submitted)) throw new Error('start failed');
    launcher.poll(); launcher.ack({ results: [{ id: submitted.job.id, ok: true }] });
    agents.push({ ...agent, status: 'waiting' });
    time(300_000); launcher.tick();
    expect(launcher.poll()).toEqual([]);
    agents[0].status = 'shell'; launcher.tick();
    expect(launcher.poll()).toEqual([]);
    agents[0].status = 'working'; launcher.tick();
    expect(launcher.poll()).toEqual([]);
    agents[0].status = 'idle';
    setMember({ ...member, idleMinutes: 0 }); launcher.tick();
    expect(launcher.poll()).toEqual([]);
    setMember(member); time(299_999); launcher.tick();
    expect(launcher.poll()).toEqual([]);
    time(300_000); launcher.tick();
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'stop', cast: 'ana' }));
  });
  it('depois de reiniciar, a lista de sessões abertas do auxiliar volta a permitir Dispensar; sessão fechada por fora some', () => {
    const { launcher, agents } = setup();
    agents.push({ ...agent, status: 'working' });
    // Servidor recém-reiniciado: não lembra quem abriu.
    expect(launcher.isOpened('ana')).toBe(false);
    launcher.poll({ open: ['ana', '../x', 42] });
    expect(launcher.isOpened('ana')).toBe(true);
    expect(launcher.isOpened('../x')).toBe(false);
    expect(launcher.dismiss(agent)).toHaveProperty('job');
    // Rodada sem `open` (auxiliar antigo) não mexe; lista sem ela (tmux fechado por fora) esquece.
    const fresh = setup();
    fresh.launcher.poll({ open: ['ana'] });
    fresh.launcher.poll({});
    expect(fresh.launcher.isOpened('ana')).toBe(true);
    fresh.launcher.poll({ open: [] });
    expect(fresh.launcher.isOpened('ana')).toBe(false);
  });
  it('retoma uma conversa escolhida no histórico (session), mesmo sem last do personagem; só no modo resume', () => {
    const { launcher } = setup();
    launcher.poll();
    const sid = '01a17ce2-c30b-7021-a3bf-8d10a45dc21b';
    expect(launcher.start('ana', { room: '/a', mode: 'new', session: sid })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/a', mode: 'resume', session: '../x' })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/b', mode: 'resume', session: sid })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/a', mode: 'resume', session: sid, account: '.omp' })).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', cast: 'ana', folder: '/a', resume: sid }));
  });
  it('limite de abertos (salas do tenant): chamada em andamento e sessão aberta contam; quem está sendo dispensado não', () => {
    const agents: AgentInfo[] = [];
    const launcher = new CastLauncher({ dataDir: '/tmp', member: () => member, agents: () => agents, account: () => '/tmp', changed: () => {}, now: () => 0, max: 1 });
    launcher.poll();
    const first = launcher.start('ana', { room: '/a', mode: 'new' });
    expect(first).toHaveProperty('job');
    expect(launcher.start('bia', { room: '/a', mode: 'new' })).toMatchObject({ status: 409, error: expect.stringContaining('só 1 personagem') });
    launcher.poll();
    launcher.ack({ results: [{ id: (first as { job: { id: string } }).job.id, ok: true }] });
    agents.push({ ...agent, status: 'working' });
    launcher.tick();
    expect(launcher.start('bia', { room: '/a', mode: 'new' })).toMatchObject({ status: 409 });
    expect(launcher.dismiss(agents[0])).toHaveProperty('job');
    expect(launcher.start('bia', { room: '/a', mode: 'new' })).toHaveProperty('job');
    // Depois de reiniciar, as sessões hb-* que o auxiliar informa contam.
    const fresh = new CastLauncher({ dataDir: '/tmp', member: () => member, agents: () => [], account: () => '/tmp', changed: () => {}, now: () => 0, max: 2 });
    fresh.poll({ open: ['ana', 'bia'] });
    expect(fresh.start('caio', { room: '/a', mode: 'new' })).toMatchObject({ status: 409 });
  });
});
