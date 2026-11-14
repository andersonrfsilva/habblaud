import { describe, expect, it } from 'vitest';
import type { AgentInfo, CastMember } from '../../shared/types';
import { CastLauncher } from './launcher';

const member: CastMember = { id: 'ana', name: 'Ana', kind: 'main', look: 'f', seed: 1, idleMinutes: 5 };
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
    for (const prompt of ['-e x', ' --x']) expect(launcher.start('ana', { mode: 'new', prompt })).toMatchObject({ status: 422 });
    setMember({ ...member, model: '--no-session' });
    expect(launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 422 });
    setMember({ ...member, last: { '/a': '../../bad' } });
    expect(launcher.start('ana', { mode: 'resume' })).toMatchObject({ status: 422 });
  });
  it('rejects named bindings outside ZeroClaw and keeps the old catalog response', () => {
    const { launcher, member: setMember } = setup();
    launcher.poll();
    expect(launcher.models()).toEqual({ provider: 'omp', models: [] });
    expect(launcher.namedAliases()).toEqual([]);
    setMember({ ...member, agent: 'financeiro' });
    expect(launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 422 });
    expect(launcher.poll()).toEqual([]);
  });
  it('requires host, previous session and a free member', () => {
    const { launcher, agents } = setup();
    expect(launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 503 });
    launcher.poll();
    expect(launcher.start('ana', { mode: 'resume' })).toMatchObject({ status: 422 });
    agents.push(agent);
    expect(launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 409 });
    agents.pop();
    expect(launcher.start('ana', { mode: 'new' })).toHaveProperty('job');
    expect(launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 409 });
  });
  it('nova sessão vai sem pasta (o auxiliar abre na pasta pessoal), mesmo se um cliente antigo mandar room', () => {
    const { launcher } = setup();
    launcher.poll();
    expect(launcher.start('ana', { room: '/qualquer', mode: 'new' })).toHaveProperty('job');
    const [job] = launcher.poll();
    expect(job).toMatchObject({ op: 'start', cast: 'ana' });
    expect(job).not.toHaveProperty('folder');
  });
  it('com pasta padrão configurada, personagem e "Novo" do omp abrem nela (o room do cliente continua ignorado)', () => {
    const launcher = new CastLauncher({ dataDir: '/tmp', member: () => ({ ...member, last: { '/velha': 'aaaaaaaa-1' } }), agents: () => [], account: () => '/tmp', changed: () => {}, now: () => 0, folder: '/dev' });
    launcher.poll();
    expect(launcher.start('ana', { mode: 'new', room: '/qualquer' })).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', cast: 'ana', folder: '/dev' }));
    expect(launcher.startNew({})).toHaveProperty('job');
    expect(launcher.poll().filter((job) => job.cast !== 'ana')).toContainEqual(expect.objectContaining({ op: 'start', folder: '/dev' }));
  });
  it.each(['latest', 'history', 'novo'])('retomar %s mantém a pasta da conversa com pasta padrão configurada', (mode) => {
    const launcher = new CastLauncher({ dataDir: '/tmp', member: () => ({ ...member, last: { '/conversa': 'aaaaaaaa-1' } }),
      agents: () => [], account: () => '/tmp', changed: () => {}, folder: '/padrao' });
    launcher.poll();
    const input = { room: '/conversa', session: 'aaaaaaaa-1' };
    const result = mode === 'novo' ? launcher.startNew(input)
      : launcher.start('ana', { mode: 'resume', ...(mode === 'history' ? input : {}) });
    expect(result).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', folder: '/conversa', resume: 'aaaaaaaa-1' }));
  });
  it('Continuar sem session retoma a conversa mais recente do personagem, na pasta dela', () => {
    const { launcher, member: setMember } = setup();
    launcher.poll();
    setMember({ ...member, last: { '/velha': 'aaaaaaaa-1', '/nova': 'bbbbbbbb-2' } });
    expect(launcher.start('ana', { mode: 'resume' })).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', folder: '/nova', resume: 'bbbbbbbb-2' }));
  });
  it('"Novo": abre fora do elenco com id novo-…, conclui quando o agente aparece e pode ser dispensado', () => {
    const { launcher, agents } = setup();
    expect(launcher.startNew({})).toMatchObject({ status: 503 });
    launcher.poll();
    expect(launcher.startNew({ prompt: '--x' })).toMatchObject({ status: 422 });
    // omp: o modelo vem do personagem ou do padrão; o pedido não escolhe.
    expect(launcher.startNew({ model: 'anthropic/opus' })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { mode: 'new', model: 'anthropic/opus' })).toMatchObject({ status: 422 });
    const submitted = launcher.startNew({ room: '/ignorada' });
    if (!('job' in submitted)) throw new Error('start failed');
    const [job] = launcher.poll();
    expect(job).toMatchObject({ op: 'start', agentDir: '/tmp' });
    expect(job).not.toHaveProperty('folder');
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
    launcher.poll({ roles: { task: 'openai/gpt:high', default: 'x/y', smol: 'a/b', plan: 'sem-barra' } });
    expect(launcher.roles()).toEqual({ roles: { default: 'x/y', task: 'openai/gpt:high' }, pending: false });
    for (const bad of [{}, { roles: {} }, { roles: { smol: 'a/b' } }, { roles: { task: 'gpt' } }, { roles: { task: '-x/y' } }, { roles: { task: 'a/b c' } }, { roles: { task: 3 } }])
      expect(launcher.setRoles(bad)).toMatchObject({ status: 422 });
    const submitted = launcher.setRoles({ roles: { default: 'anthropic/opus:high', task: 'openai/gpt-6:low', advisor: null } });
    if (!('job' in submitted)) throw new Error('roles failed');
    expect(launcher.roles().pending).toBe(true);
    const [job] = launcher.poll();
    expect(job).toEqual({ id: submitted.job.id, op: 'roles', cast: '', agentDir: '/tmp', roles: { default: 'anthropic/opus:high', task: 'openai/gpt-6:low', advisor: null } });
    launcher.ack({ results: [{ id: job.id, ok: true }] });
    expect(launcher.jobs().find((j) => j.id === job.id)?.status).toBe('done');
    // Logo após o ack (antes de o auxiliar reler), roles() já mostra o que foi gravado.
    expect(launcher.roles()).toEqual({ roles: { default: 'anthropic/opus:high', task: 'openai/gpt-6:low' }, pending: false });
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
    expect(launcher.start('ana', { room: 'relativa', mode: 'resume', session: sid })).toMatchObject({ status: 422 });
    expect(launcher.start('ana', { room: '/a/', mode: 'resume', session: sid, account: '.omp' })).toHaveProperty('job');
    expect(launcher.poll()).toContainEqual(expect.objectContaining({ op: 'start', cast: 'ana', folder: '/a', resume: sid }));
  });
  it('"Novo" retoma uma conversa do histórico (session) sem depender de personagem livre na pasta', () => {
    const { launcher } = setup();
    launcher.poll();
    const sid = '01a17ce2-c30b-7021-a3bf-8d10a45dc21b';
    expect(launcher.startNew({ room: '/b', session: '../x' })).toMatchObject({ status: 422 });
    expect(launcher.startNew({ room: '/b', session: 7 })).toMatchObject({ status: 422 });
    expect(launcher.startNew({ session: sid, account: '.omp' })).toMatchObject({ status: 422 });
    expect(launcher.startNew({ room: '/b', session: sid, account: '.omp' })).toHaveProperty('job');
    const [job] = launcher.poll();
    expect(job).toMatchObject({ op: 'start', folder: '/b', resume: sid });
    expect(job.cast).toMatch(/^novo-[0-9a-f]{8}$/);
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

import { spawn, type ChildProcess } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, vi } from 'vitest';
import { AccountsService } from '../accounts/service';
import { AntigravityClient } from '../antigravity/acp';
import { AntigravityDelivery } from '../messages/antigravity';
import { MessageRegistry } from '../messages/registry';
import { CastStore } from '../model/cast';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AntigravityCatalog } from '../sources/antigravity/catalog';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { AntigravitySource } from '../sources/antigravity/source';
import { AntigravityCast } from './antigravity';
import type { CastJobInfo } from '../../shared/types';
import { HandoffStore } from './handoff';
import { createOmpTerminalParser } from '../sources/omp/terminal';
import { createTerminalParser } from '../sources/terminal';
import { appendFileSync, existsSync } from 'node:fs';
import type { CastJob } from '../../shared/types';

const agyCleanups: (() => Promise<void>)[] = [];
afterEach(async () => { for (const cleanup of agyCleanups.splice(0).reverse()) await cleanup(); });
function agySetup(max?: number, enabled = true, folder?: string) {
  const directory = mkdtempSync(join(tmpdir(), 'agy-launcher-'));
  const trace = join(directory, 'trace.ndjson');
  const children: ChildProcess[] = [];
  const client = new AntigravityClient({ bin: 'unused', version: 'test', skipPermissions: false, connect: () => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../antigravity/fake-agy.mjs', import.meta.url))],
      { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, FAKE_AGY_TRACE_FILE: trace } });
    children.push(child); return Duplex.from({ readable: child.stdout, writable: child.stdin });
  } });
  const accounts = new AccountsService({ dirs: [], home: directory, env: {}, onChange() {} });
  const cast = new CastStore(null);
  cast.put({ ...member, model: 'agy/fake-b', instructions: 'Seja breve' });
  cast.put({ ...member, id: 'bia', name: 'Bia', model: 'omp/model' });
  const controls = { now: 0 };
  let launcher: CastLauncher;
  const office = new Office({ names: new NameStore(null), cast, version: 'test', startedAt: 0,
    accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'Antigravity',
    now: () => controls.now, castLauncher: () => launcher });
  const catalog = new AntigravityCatalog(directory);
  const source = new AntigravitySource({ accounts, office, client, catalog, mirror: new AntigravityMirror({ dataDir: directory }) });
  source.start();
  const delivery = new AntigravityDelivery({ client, source });
  const registry = new MessageRegistry({ office, antigravity: delivery });
  const runtime = new AntigravityCast({ client: () => client, delivery: () => delivery, messages: () => registry,
    catalog, home: folder ?? directory, dataDir: directory });
  const idle = vi.fn();
  launcher = new CastLauncher({ dataDir: directory, member: id => cast.get(id), agents: () => office.list(),
    account: () => directory, changed: () => office.markDirty(), now: () => controls.now, max, idle, folder,
    antigravity: enabled ? runtime : undefined });
  agyCleanups.push(async () => {
    registry.stop(); source.stop(); client.stop();
    await Promise.all(children.filter(child => child.exitCode === null && child.signalCode === null).map(child => {
      const exited = Promise.withResolvers<void>(); child.once('exit', () => exited.resolve()); return exited.promise;
    }));
    await source.flush(); rmSync(directory, { recursive: true, force: true });
  });
  const requests = () => readFileSync(trace, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  async function done(result: { job: CastJobInfo } | { status: number; error: string }) {
    if (!('job' in result)) throw new Error(`Rejected: ${result.status}: ${result.error}`);
    await expect.poll(() => { launcher.tick(); return launcher.jobs().find(job => job.id === result.job.id)?.status; }).toBe('done');
    return result.job;
  }
  function mirror(sessionId: string) {
    const folder = join(directory, 'antigravity', 'sessions');
    mkdirSync(folder, { recursive: true }); writeFileSync(join(folder, `${sessionId}.jsonl`), '');
  }
  return { launcher, client, office, cast, source, delivery, registry, runtime, controls, requests, done, mirror, idle, children };
}

describe('elenco Antigravity junto do omp', () => {
  it.each(['member', 'novo'])('opens %s in the configured default folder without room', async (kind) => {
    const s = agySetup(undefined, true, '/configured');
    await s.done(kind === 'member' ? s.launcher.start('ana', { mode: 'new' }) : s.launcher.startNew({ model: 'agy/default' }));
    expect(s.requests().find(message => message.method === 'session/new').params.cwd).toBe('/configured');
    expect(s.office.roomPath(s.office.list()[0].roomId)).toBe('/configured');
  });
  it('opens without the omp helper, exposes cast metadata and records last session', async () => {
    const s = agySetup();
    const submitted = s.launcher.start('ana', { mode: 'new' });
    expect(submitted).toMatchObject({ job: { status: 'queued' } });
    await s.done(submitted);
    const current = s.office.list()[0];
    expect(current).toMatchObject({ provider: 'antigravity', cast: 'ana', model: 'agy/fake-b', models: ['agy/fake-a', 'agy/fake-b'] });
    expect(s.office.commit().snapshot.agents.find(a => a.cast === 'ana')?.castOpened).toBe(true);
    expect(Object.values(s.cast.get('ana')!.last!)).toEqual([current.sessionId]);
    expect(s.requests().some(message => message.method === 'session/prompt')).toBe(false);
  });
  it('delivers instructions with an explicit initial request only', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new', prompt: 'Olá' }));
    await expect.poll(() => s.requests().some(message => message.method === 'session/prompt')).toBe(true);
    expect(s.requests().find(message => message.method === 'session/prompt').params.prompt[0].text)
      .toBe('Instruções do personagem:\nSeja breve\n\nPedido inicial:\nOlá');
  });
  it('uses body model above character model in either runtime direction', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('bia', { mode: 'new', model: 'agy/fake-b' }));
    expect(s.office.list()[0]).toMatchObject({ cast: 'bia', provider: 'antigravity', model: 'agy/fake-b' });
    s.launcher.poll();
    expect(s.launcher.start('ana', { mode: 'new', model: 'omp/override' })).toHaveProperty('job');
    expect(s.launcher.poll()).toContainEqual(expect.objectContaining({ cast: 'ana', model: 'omp/override' }));
  });
  it('opens Novo with the CLI default and learned catalog begins with agy/default', async () => {
    const s = agySetup();
    expect(s.launcher.models()).toEqual({ provider: 'omp', models: [], agyModels: ['agy/default'] });
    const job = await s.done(s.launcher.startNew({ model: 'agy/default' }));
    expect(job.cast).toMatch(/^novo-/);
    expect(s.office.list()[0]).toMatchObject({ cast: job.cast, model: 'agy/fake-a' });
    expect(s.launcher.models().agyModels).toEqual(['agy/default', 'agy/fake-a', 'agy/fake-b']);
  });
  it('omp polls keep Antigravity open but still forget missing omp sessions', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.launcher.poll({ open: ['bia'] });
    expect(s.launcher.isOpened('bia')).toBe(true);
    s.launcher.poll({ open: [] });
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.launcher.isOpened('bia')).toBe(false);
  });
  it('dismisses through a queued job and closes the session', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    const current = s.office.list()[0];
    await s.done(s.launcher.dismiss(current));
    expect(s.client.session(current.sessionId)).toBeUndefined();
    expect(s.launcher.isOpened('ana')).toBe(false);
  });
  it('idleness dismisses once but never during an active turn', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    const current = s.office.list()[0];
    await s.client.prompt(current.sessionId, 'sleep');
    s.controls.now = 300_000;
    s.launcher.tick();
    expect(s.idle).not.toHaveBeenCalled();
    await s.client.cancel(current.sessionId);
    s.controls.now = 600_000;
    s.launcher.tick();
    expect(s.idle).toHaveBeenCalledTimes(1);
    await expect.poll(() => s.client.session(current.sessionId)).toBeUndefined();
    expect(s.launcher.isOpened('ana')).toBe(false);
  });
  it('new conversation keeps cast opened and dismissible after old session closes', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    const current = s.office.list()[0];
    const previousSessionId = current.sessionId;
    const next = await s.delivery.newSession(previousSessionId);
    expect(next).not.toBe(previousSessionId);
    expect(s.office.get(current.id)).toMatchObject({ cast: 'ana', sessionId: next });
    expect(s.launcher.isOpened('ana')).toBe(true);
    expect(s.office.commit().snapshot.agents.find(a => a.cast === 'ana')?.castOpened).toBe(true);
    await s.done(s.launcher.dismiss(s.office.get(current.id)));
    expect(s.client.session(next)).toBeUndefined();
  });
  it('shares the installation limit with omp and pending openings', async () => {
    const s = agySetup(1);
    s.launcher.poll({ open: ['bia'] });
    expect(s.launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 409 });
    s.launcher.poll({ open: [] });
    expect(s.launcher.start('ana', { mode: 'new' })).toHaveProperty('job');
    expect(s.launcher.startNew({ model: 'agy/default' })).toMatchObject({ status: 409 });
    await expect.poll(() => s.launcher.jobs()[0]?.status).toBe('done');
  });
  it.each(['agy/fake-b', 'omp/model'])('resumes mirror-owned conversation despite character model %s', async model => {
    const s = agySetup();
    const sessionId = '10000000-0000-4000-8000-000000000001';
    s.mirror(sessionId);
    s.cast.put({ ...member, model, instructions: 'Não repetir' });
    await s.done(s.launcher.start('ana', { mode: 'resume', session: sessionId, room: '/tmp', prompt: 'Retome' }));
    expect(s.office.list()[0]).toMatchObject({ provider: 'antigravity', sessionId, model: model === 'agy/fake-b' ? model : 'agy/fake-a' });
    await expect.poll(() => s.requests().some(message => message.method === 'session/prompt')).toBe(true);
    expect(s.requests().find(message => message.method === 'session/prompt').params.prompt[0].text).toBe('Retome');
  });
  it('resumes Novo by mirror ownership and unmirrored history through omp', async () => {
    const s = agySetup();
    const sessionId = '10000000-0000-4000-8000-000000000001';
    s.mirror(sessionId);
    await s.done(s.launcher.startNew({ session: sessionId, room: '/tmp' }));
    expect(s.office.list()[0]).toMatchObject({ provider: 'antigravity', cast: expect.stringMatching(/^novo-/), sessionId });
    s.launcher.poll();
    expect(s.launcher.start('bia', { mode: 'resume', session: '10000000-0000-4000-8000-000000000002', room: '/tmp' })).toHaveProperty('job');
    expect(s.launcher.poll()).toContainEqual(expect.objectContaining({ cast: 'bia', resume: '10000000-0000-4000-8000-000000000002' }));
  });
  it('fails unknown model without leaving an opened character', async () => {
    const s = agySetup();
    const submitted = s.launcher.start('ana', { mode: 'new', model: 'agy/missing' });
    expect(submitted).toHaveProperty('job');
    await expect.poll(() => s.launcher.jobs()[0]?.status).toBe('failed');
    expect(s.launcher.jobs()[0]?.error).toBe('modelo do Antigravity desconhecido: missing');
    expect(s.launcher.isOpened('ana')).toBe(false);
  });
  it('reports disabled runtime and rejects advisor/sub calls', () => {
    const s = agySetup(undefined, false);
    expect(s.launcher.start('ana', { mode: 'new' })).toMatchObject({ status: 503, error: 'Antigravity desligado neste Habblaud' });
    expect(s.launcher.startNew({ model: 'agy/default' })).toMatchObject({ status: 503 });
    expect(s.launcher.models()).not.toHaveProperty('agyModels');
    for (const kind of ['advisor', 'sub'] as const) {
      s.cast.put({ ...member, kind, idleMinutes: undefined });
      expect(s.launcher.start('ana', { mode: 'new', model: 'agy/fake-b' })).toMatchObject({ status: 422 });
    }
  });
  it('forgets cast sessions when the fake ACP process exits', async () => {
    const s = agySetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.children[0].kill('SIGKILL');
    await expect.poll(() => s.client.sessions()).toEqual([]);
    expect(s.launcher.isOpened('ana')).toBe(false);
  });
});

function switchingSetup(folder?: string) {
  const directory = mkdtempSync(join(tmpdir(), 'tool-switch-'));
  const trace = join(directory, 'trace.ndjson');
  const children: ChildProcess[] = [];
  const client = new AntigravityClient({ bin: 'unused', version: 'test', skipPermissions: false, connect: () => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../antigravity/fake-agy.mjs', import.meta.url))],
      { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, FAKE_AGY_TRACE_FILE: trace } });
    children.push(child);
    return Duplex.from({ readable: child.stdout, writable: child.stdin });
  } });
  const accounts = new AccountsService({ dirs: [], home: directory, env: {}, onChange() {} });
  const cast = new CastStore(null);
  cast.put({ ...member, model: 'omp/saved' });
  let launcher: CastLauncher;
  let handoff = new HandoffStore({ dir: join(directory, 'handoff') });
  const office = new Office({ names: new NameStore(null), cast, version: 'test', startedAt: Date.now(),
    accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'test', castLauncher: () => launcher });
  const catalog = new AntigravityCatalog(directory);
  const source = new AntigravitySource({ accounts, office, client, catalog, mirror: new AntigravityMirror({ dataDir: directory }) });
  source.start();
  let delivery = new AntigravityDelivery({ client, source, handoff });
  let registry = new MessageRegistry({ office, antigravity: delivery, handoff, switchTool: (agent, model) => launcher.switchTool(agent, model) });
  const runtime = new AntigravityCast({ client: () => client, delivery: () => delivery, messages: () => registry, catalog, home: folder ?? directory, dataDir: directory });
  const paths = new Map<string, string>();
  const jobs: CastJob[] = [];
  const switched = vi.fn(() => office.markDirty());
  const makeLauncher = () => new CastLauncher({ dataDir: directory, member: id => cast.get(id), agents: () => office.list(), account: () => directory,
    changed: () => { office.markDirty(); queueMicrotask(() => pump()); }, handoff, antigravity: runtime, switched, folder,
    transcriptPathOf: id => paths.get(id) ?? source.transcriptPathOf(id),
    cwdOf: a => office.roomPath(a.roomId),
    terminalParser: id => office.get(id)?.provider === 'omp' ? createOmpTerminalParser() : createTerminalParser() });
  launcher = makeLauncher();
  let sequence = 0;
  const present = () => office.list().filter(a => a.kind === 'main' && a.status !== 'offline' && a.status !== 'done');
  const current = () => present()[0]!;
  const pump = () => {
    for (const job of launcher.poll({ open: present().filter(a => a.provider === 'omp').map(a => a.cast) })) {
      jobs.push(job);
      if (job.op === 'stop') {
        for (const a of present().filter(a => a.cast === job.cast)) office.closeMain(a.id);
      }
      if (job.op === 'start') {
        const sessionId = `omp-session-${++sequence}`;
        const id = `omp:${sessionId}`;
        office.addMain({ id, provider: 'omp', account: 'omp', sessionId, cast: job.cast, cwd: job.folder ?? '/project/original',
          startedAt: Date.now(), status: 'idle', role: 'main' });
        const path = join(directory, `${sessionId}.jsonl`);
        writeFileSync(path, '');
        paths.set(id, path);
        if (job.model) office.get(id)!.model = job.model;
      }
      launcher.ack({ results: [{ id: job.id, ok: true }] });
    }
    launcher.tick();
  };
  pump();
  const requests = () => existsSync(trace) ? readFileSync(trace, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)) : [];
  const transcript = (text: string, response = 'resposta omp') => {
    const a = current();
    if (a.provider !== 'omp') throw new Error('expected omp');
    appendFileSync(paths.get(a.id)!, [
      { type: 'message', id: `u-${++sequence}`, message: { role: 'user', content: [{ type: 'text', text }] } },
      { type: 'message', id: `a-${++sequence}`, message: { role: 'assistant', content: [{ type: 'text', text: response }] } },
    ].map(value => JSON.stringify(value)).join('\n') + '\n');
  };
  async function done(result: { job: CastJobInfo } | { status: number; error: string }) {
    if (!('job' in result)) throw new Error(result.error);
    await expect.poll(() => launcher.jobs().find(j => j.id === result.job.id)?.status).toBe('done');
    return result.job;
  }
  async function switchTo(model: string, success = true) {
    await source.flush();
    const result = registry.control({ agentId: current().id, model });
    if (!('message' in result)) throw new Error('control unavailable');
    expect(result.message).toMatchObject({ id: expect.any(String), agentId: expect.any(String), status: 'sent', createdAt: expect.any(Number), updatedAt: expect.any(Number) });
    await expect.poll(() => registry.get(result.message.id)?.status).toBe(success ? 'delivered' : 'failed');
    return registry.get(result.message.id)!;
  }
  async function human(text: string) {
    const a = current();
    if (a.provider === 'omp') registry.inbox({ session: a.sessionId });
    const result = registry.send({ agentId: a.id, text });
    if (!('message' in result)) throw new Error('send unavailable');
    if (a.provider === 'omp') {
      const message = registry.inbox({ session: a.sessionId }).find(m => m.id === result.message.id)!;
      transcript(text);
      registry.ack({ session: a.sessionId, results: [{ id: message.id, ok: true }] });
      return message.text;
    }
    await expect.poll(() => registry.get(result.message.id)?.status).toBe('delivered');
    await expect.poll(() => client.session(a.sessionId)?.busy).toBe(false);
    await source.flush();
    return text;
  }
  agyCleanups.push(async () => {
    registry.stop(); source.stop(); client.stop();
    await Promise.all(children.filter(child => child.exitCode === null && child.signalCode === null).map(child => {
      const exit = Promise.withResolvers<void>(); child.once('exit', () => exit.resolve()); return exit.promise;
    }));
    await source.flush(); rmSync(directory, { recursive: true, force: true });
  });
  return { office, cast, client, source, current, transcript, done, switchTo, human, requests, jobs, switched,
    get launcher() { return launcher; }, get registry() { return registry; }, get handoff() { return handoff; },
    restart() {
      registry.stop();
      handoff = new HandoffStore({ dir: join(directory, 'handoff') });
      delivery = new AntigravityDelivery({ client, source, handoff });
      registry = new MessageRegistry({ office, antigravity: delivery, handoff, switchTool: (a, m) => launcher.switchTool(a, m) });
      launcher = makeLauncher();
      pump();
    },
  };
}

describe('troca de ferramenta com contexto', () => {
  it.each(['member', 'novo'])('switchTool preserves %s conversation folder instead of configured default', async (kind) => {
    const s = switchingSetup('/configured');
    const input = { room: '/conversation', session: 'aaaaaaaa-1' };
    await s.done(kind === 'member' ? s.launcher.start('ana', { mode: 'resume', ...input }) : s.launcher.startNew(input));
    await s.switchTo('agy/fake-b');
    expect(s.requests().find(message => message.method === 'session/new').params.cwd).toBe('/conversation');
    await s.switchTo('omp/returned');
    expect(s.jobs.filter(job => job.op === 'start').at(-1)?.folder).toBe('/conversation');
  });
  it('1, 2, 3: mantém pasta, não manda prompt na troca e entrega contexto uma vez nas duas direções', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.transcript('ALFA');
    await s.switchTo('agy/fake-b');
    expect(s.current()).toMatchObject({ provider: 'antigravity', model: 'agy/fake-b' });
    expect(s.office.roomPath(s.current().roomId)).toBe('/project/original');
    expect(s.office.commit().snapshot.agents.find(a => a.id === s.current().id)?.handoff).toBe(true);
    expect(s.requests().filter(r => r.method === 'session/prompt')).toEqual([]);
    await s.human('BETA');
    const prompts = () => s.requests().filter(r => r.method === 'session/prompt');
    expect(prompts()[0].params.prompt[0]).toMatchObject({ type: 'resource_link', name: 'conversa-anterior.md' });
    const path = fileURLToPath(prompts()[0].params.prompt[0].uri);
    expect(readFileSync(path, 'utf8')).toContain('ALFA');
    s.office.markDirty();
    expect(s.office.commit().snapshot.agents.find(a => a.id === s.current().id)?.handoff).toBeUndefined();
    await s.human('GAMA');
    expect(prompts()[1].params.prompt).toEqual([{ type: 'text', text: 'GAMA' }]);
    await s.switchTo('omp/requested');
    expect(s.jobs.filter(j => j.op === 'start').at(-1)).toMatchObject({ model: 'omp/requested', folder: '/project/original' });
    expect(s.current()).toMatchObject({ provider: 'omp', model: 'omp/requested' });
    expect(s.office.roomPath(s.current().roomId)).toBe('/project/original');
    const text = await s.human('DELTA');
    expect(text).toContain(s.handoff.last('ana'));
    expect(s.cast.get('ana')?.model).toBe('omp/saved');
  });
  it('4: ida e volta antes da mensagem mantém ambas as conversas', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.transcript('ALFA');
    await s.switchTo('agy/fake-b');
    await s.switchTo('omp/returned');
    const text = readFileSync(s.handoff.pending('ana')!, 'utf8');
    expect(text).toContain('ALFA');
    expect(text).toContain('Ferramenta: Antigravity');
    expect(s.requests().filter(r => r.method === 'session/prompt')).toEqual([]);
  });
  it.each(['omp', 'antigravity'])('4b: %s → mensagem entregue → volta conserva ALFA, mensagem e resposta em ordem', async start => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new', ...(start === 'antigravity' ? { model: 'agy/fake-b' } : {}) }));
    if (start === 'omp') s.transcript('ALFA'); else await s.human('ALFA');
    await s.switchTo(start === 'omp' ? 'agy/fake-b' : 'omp/next');
    await s.human('BETA');
    expect(s.handoff.pending('ana')).toBeUndefined();
    await s.switchTo(start === 'omp' ? 'omp/returned' : 'agy/fake-a');
    const text = readFileSync(s.handoff.pending('ana')!, 'utf8');
    expect(text.indexOf('ALFA')).toBeLessThan(text.indexOf('BETA'));
    expect(text.indexOf('BETA')).toBeLessThan(text.lastIndexOf(start === 'omp' ? 'ok' : 'resposta omp'));
  });
  it('4c: conversa nova pedida pela pessoa esquece contexto entregue', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.transcript('ALFA');
    await s.switchTo('agy/fake-b');
    await s.human('BETA');
    const old = s.current().sessionId;
    const result = s.registry.control({ agentId: s.current().id, command: 'new' });
    if (!('message' in result)) throw new Error('new unavailable');
    await expect.poll(() => s.registry.get(result.message.id)?.status).toBe('delivered');
    expect(s.current().sessionId).not.toBe(old);
    expect(s.handoff.last('ana')).toBeUndefined();
    await s.human('NOVA');
    await s.switchTo('omp/returned');
    const text = readFileSync(s.handoff.pending('ana')!, 'utf8');
    expect(text).toContain('NOVA');
    expect(text).not.toContain('ALFA');
    expect(text).not.toContain('BETA');
  });
  it('4d: reiniciar HandoffStore não perde o anexo da primeira mensagem', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.transcript('ALFA');
    await s.switchTo('agy/fake-b');
    const path = s.handoff.pending('ana');
    s.restart();
    expect(s.handoff.pending('ana')).toBe(path);
    await s.human('BETA');
    const prompt = s.requests().find(r => r.method === 'session/prompt');
    expect(fileURLToPath(prompt.params.prompt[0].uri)).toBe(path);
    expect(s.handoff.pending('ana')).toBeUndefined();
  });
  it('5: recusa ocupado, permissão, terminal, subagente, pasta e modelo inválidos sem fechar', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    for (const status of ['working', 'waiting'] as const) await expect(s.launcher.switchTool({ ...s.current(), status }, 'agy/fake-b')).rejects.toThrow('espere o turno');
    await expect(s.launcher.switchTool({ ...s.current(), cast: undefined }, 'agy/fake-b')).rejects.toThrow('elenco');
    await expect(s.launcher.switchTool({ ...s.current(), kind: 'sub' }, 'agy/fake-b')).rejects.toThrow('elenco');
    for (const cwd of [undefined, 'relative', '/tmp/\ninvalid']) {
      vi.spyOn(s.office, 'roomPath').mockReturnValueOnce(cwd);
      await expect(s.launcher.switchTool(s.current(), 'agy/fake-b')).rejects.toThrow('determinar a pasta');
    }
    await expect(s.launcher.switchTool(s.current(), 'agy/bad model')).rejects.toThrow('inválido');
    expect(s.jobs.filter(j => j.op === 'stop')).toEqual([]);
    expect(s.current().provider).toBe('omp');
  });
  it('6: falha ao abrir deixa fechado e contexto pendente para a próxima chamada', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.transcript('ALFA');
    expect((await s.switchTo('agy/missing', false)).error).toContain('desconhecido');
    expect(s.current()).toBeUndefined();
    expect(readFileSync(s.handoff.pending('ana')!, 'utf8')).toContain('ALFA');
    await s.done(s.launcher.start('ana', { mode: 'new', model: 'agy/fake-b' }));
    await s.human('BETA');
    expect(s.requests().find(r => r.method === 'session/prompt').params.prompt[0].type).toBe('resource_link');
  });
  it('7: Novo muda id e leva contexto em ambas as direções, inclusive modelo omp explícito', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.startNew({}));
    const first = s.current().cast!;
    s.transcript('ALFA');
    await s.switchTo('agy/fake-b');
    const second = s.current().cast!;
    expect(second).not.toBe(first);
    expect(s.handoff.last(first)).toBeUndefined();
    expect(s.handoff.pending(second)).toBeDefined();
    await s.switchTo('omp/explicit');
    expect(s.current().cast).not.toBe(second);
    expect(s.jobs.filter(j => j.op === 'start').at(-1)?.model).toBe('omp/explicit');
    expect(readFileSync(s.handoff.pending(s.current().cast!)!, 'utf8')).toContain('ALFA');
  });
  it('8: conversa vazia troca sem arquivo nem handoff', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    await s.switchTo('agy/fake-b');
    expect(s.handoff.last('ana')).toBeUndefined();
    expect(s.office.commit().snapshot.agents.find(a => a.id === s.current().id)?.handoff).toBeUndefined();
    expect(s.switched).toHaveBeenCalledWith(expect.any(Object), 'omp', 'Antigravity', false);
  });
});

describe('modelos das duas ferramentas no snapshot', () => {
  it('omp aberto ganha catálogo agy, Antigravity aberto ganha última lista omp sem duplicatas', async () => {
    const s = switchingSetup();
    await s.done(s.launcher.start('ana', { mode: 'new' }));
    s.current().models = ['omp/one', 'omp/two'];
    s.office.markDirty();
    expect(s.office.commit().snapshot.agents.find(a => a.id === s.current().id)?.models).toEqual(['omp/one', 'omp/two', 'agy/default']);
    await s.switchTo('agy/fake-b');
    const snapshot = s.office.commit().snapshot.agents.find(a => a.id === s.current().id)!;
    expect(snapshot.models).toEqual(['agy/fake-a', 'agy/fake-b', 'omp/one', 'omp/two']);
    expect(s.current().models).toEqual(['agy/fake-a', 'agy/fake-b']);
  });
  it('agente sem castOpened mantém lista; Antigravity desligado não enriquece', () => {
    const enabled = agySetup();
    enabled.office.addMain({ id: 'terminal', provider: 'omp', account: 'omp', sessionId: 'terminal', cwd: '/project', role: 'main', startedAt: 0, status: 'idle' });
    enabled.office.get('terminal')!.models = ['omp/only'];
    expect(enabled.office.commit().snapshot.agents.find(a => a.id === 'terminal')).toMatchObject({ models: ['omp/only'] });
    expect(enabled.office.commit().snapshot.agents.find(a => a.id === 'terminal')?.castOpened).toBeUndefined();
    const disabled = agySetup(undefined, false);
    disabled.office.addMain({ id: 'omp', provider: 'omp', account: 'omp', sessionId: 'omp', cwd: '/project', cast: 'ana', role: 'main', startedAt: 0, status: 'idle' });
    disabled.office.get('omp')!.models = ['omp/only'];
    disabled.launcher.poll({ open: ['ana'] });
    expect(disabled.office.commit().snapshot.agents.find(a => a.id === 'omp')).toMatchObject({ castOpened: true, models: ['omp/only'] });
  });
});
