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
