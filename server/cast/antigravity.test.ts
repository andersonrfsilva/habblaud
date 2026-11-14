import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { AccountsService } from '../accounts/service';
import { AntigravityClient } from '../antigravity/acp';
import { AntigravityDelivery } from '../messages/antigravity';
import { MessageRegistry } from '../messages/registry';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AntigravityCatalog } from '../sources/antigravity/catalog';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { AntigravitySource } from '../sources/antigravity/source';
import { AntigravityCast, type AntigravityCastJob } from './antigravity';
const cleanups: (() => Promise<void>)[] = [];
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup(); });
function setup(env: NodeJS.ProcessEnv = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'agy-cast-'));
  const trace = join(directory, 'trace.ndjson');
  const children: ChildProcess[] = [];
  const client = new AntigravityClient({ bin: 'unused', version: 'test', skipPermissions: false, connect: () => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../antigravity/fake-agy.mjs', import.meta.url))],
      { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...env, FAKE_AGY_TRACE_FILE: trace } });
    children.push(child); return Duplex.from({ readable: child.stdout, writable: child.stdin });
  } });
  const accounts = new AccountsService({ dirs: [], home: directory, env: {}, onChange() {} });
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: Date.now(), accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'Antigravity' });
  const catalog = new AntigravityCatalog(directory);
  const source = new AntigravitySource({ accounts, office, client, catalog, mirror: new AntigravityMirror({ dataDir: directory }) }); source.start();
  const delivery = new AntigravityDelivery({ client, source });
  const registry = new MessageRegistry({ office, antigravity: delivery });
  const cast = new AntigravityCast({ client: () => client, delivery: () => delivery, messages: () => registry, catalog, home: directory, dataDir: directory });
  cleanups.push(async () => {
    registry.stop(); source.stop(); client.stop();
    await Promise.all(children.filter(child => child.exitCode === null && child.signalCode === null).map(child => {
      const exited = Promise.withResolvers<void>();
      child.once('exit', () => exited.resolve());
      return exited.promise;
    }));
    await source.flush(); rmSync(directory, { recursive: true, force: true });
  });
  const job = (input: Parameters<AntigravityCast['prepare']>[0]): AntigravityCastJob => {
    const { instructions, text, ...session } = cast.prepare(input);
    return { id: 'job', op: 'start', cast: input.cast, session, instructions, text };
  };
  const requests = () => readFileSync(trace, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  return { cast, client, source, delivery, office, job, requests, directory };
}
describe('Antigravity cast', () => {
  it('opens a model without an automatic turn, then delivers instructions with the initial request', async () => {
    const s = setup();
    const job = s.job({ cast: 'novo-alice', model: 'agy/fake-b', instructions: 'Seja breve' });
    const sessionId = await s.cast.open(job);
    expect(s.client.session(sessionId)).toMatchObject({ cast: 'novo-alice', model: 'fake-b' });
    s.cast.deliver(job, `antigravity:${sessionId}`, sessionId);
    expect(s.requests().some(message => message.method === 'session/prompt')).toBe(false);
    expect(s.cast.owns(sessionId)).toBe(true);
    s.cast.deliver({ ...job, text: 'Olá' }, `antigravity:${sessionId}`, sessionId);
    await expect.poll(() => s.requests().filter(message => message.method === 'session/prompt').length).toBe(1);
    expect(s.requests().find(message => message.method === 'session/prompt').params.prompt[0].text).toBe('Instruções do personagem:\nSeja breve\n\nPedido inicial:\nOlá');
    await expect.poll(() => s.client.session(sessionId)?.busy).toBe(false);
    await s.source.flush();
    expect(s.cast.owns(sessionId)).toBe(true);
    expect(s.cast.owns('../escape')).toBe(false);
  });
  it('resumes with model reapplied and never rearms instructions', async () => {
    const s = setup();
    const id = await s.cast.open(s.job({ cast: 'novo-alice', model: 'agy/fake-b' }));
    await s.cast.dismiss(id);
    const job = s.job({ cast: 'novo-alice', model: 'agy/fake-b', resume: id, instructions: 'Não repetir', prompt: 'Retome' });
    expect(job.instructions).toBeUndefined();
    const resumed = await s.cast.open(job);
    expect(resumed).toBe(id);
    expect(s.client.session(id)?.model).toBe('fake-b');
    s.cast.deliver(job, `antigravity:${id}`, id);
    await expect.poll(() => s.requests().some(message => message.method === 'session/prompt')).toBe(true);
    expect(s.requests().find(message => message.method === 'session/prompt').params.prompt[0].text).toBe('Retome');
  });
  it('reports unknown model and missing login without opening a session', async () => {
    const s = setup();
    await expect(s.cast.open(s.job({ cast: 'novo-alice', model: 'agy/missing' }))).rejects.toThrow('modelo do Antigravity desconhecido: missing');
    expect(s.client.sessions()).toEqual([]);
    const unauthenticated = setup({ FAKE_AGY_AUTH: '0' });
    await expect(unauthenticated.cast.open(unauthenticated.job({ cast: 'novo-alice' }))).rejects.toThrow('entre na conta do Antigravity no computador: rode agy e faça o login');
  });
  it('cancels an active turn and closes on dismiss', async () => {
    const s = setup();
    const id = await s.cast.open(s.job({ cast: 'novo-alice' }));
    await s.client.prompt(id, 'sleep');
    await s.cast.dismiss(id);
    expect(s.client.session(id)).toBeUndefined();
    const methods = s.requests().map(message => message.method);
    expect(methods.indexOf('session/cancel')).toBeLessThan(methods.indexOf('session/close'));
  });
  it('validates folders and instruction limits without requiring a learned model', () => {
    const s = setup();
    expect(s.cast.available()).toBe(true);
    expect(s.cast.agyModels()).toEqual(['agy/default']);
    for (const room of ['relative', '/tmp\u0000bad', '/tmp\u007fbad']) expect(() => s.cast.prepare({ cast: 'alice', room })).toThrow();
    expect(() => s.cast.prepare({ cast: 'alice', instructions: 'x'.repeat(8001) })).toThrow();
    expect(s.cast.prepare({ cast: 'alice', model: 'agy/unknown' }).model).toBe('unknown');
  });
});
