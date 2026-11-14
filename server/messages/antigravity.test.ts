import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { AccountsService } from '../accounts/service';
import { AntigravityClient } from '../antigravity/acp';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { AntigravitySource } from '../sources/antigravity/source';
import { AntigravityDelivery } from './antigravity';
import { InvalidRequest, MessageRegistry } from './registry';

const cleanups: (() => Promise<void>)[] = [];
afterEach(async () => { for (const cleanup of cleanups.splice(0).reverse()) await cleanup(); });
async function setup() {
  const directory = await mkdtemp(join(tmpdir(), 'antigravity-delivery-'));
  const accounts = new AccountsService({ dirs: [], home: directory, env: {}, onChange() {} });
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: Date.now(), accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'Antigravity' });
  let child: ChildProcess | undefined;
  const client = new AntigravityClient({ bin: 'unused', version: 'test', skipPermissions: false, connect: () => {
    const process = spawn(globalThis.process.execPath, [fileURLToPath(new URL('../antigravity/fake-agy.mjs', import.meta.url))], { stdio: ['pipe', 'pipe', 'pipe'] });
    child = process;
    return Duplex.from({ readable: process.stdout, writable: process.stdin });
  } });
  const source = new AntigravitySource({ accounts, office, client, mirror: new AntigravityMirror({ dataDir: directory }) });
  source.start();
  const delivery = new AntigravityDelivery({ client, source });
  const registry = new MessageRegistry({ office, antigravity: delivery });
  cleanups.push(async () => {
    registry.stop(); source.stop(); client.stop();
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = Promise.withResolvers<void>(); child.once('exit', () => exited.resolve());
      await exited.promise;
    }
    await source.flush(); await rm(directory, { recursive: true, force: true });
  });
  const { sessionId } = await client.open({ cwd: directory });
  return { client, source, office, delivery, registry, sessionId, id: `antigravity:${sessionId}` };
}
describe('entrega Antigravity', () => {
  it('envia mensagem parada e entrega a fila depois do turno', async () => {
    const s = await setup();
    const first = s.registry.send({ agentId: s.id, text: 'ok' });
    expect(first).toHaveProperty('message');
    await expect.poll(() => s.client.session(s.sessionId)?.busy).toBe(false);
    await s.client.prompt(s.sessionId, 'sleep');
    const queued = s.registry.send({ agentId: s.id, text: 'ok' });
    if (!('message' in queued)) throw new Error('Mensagem recusada');
    expect(queued.message.status).toBe('queued');
    await s.client.cancel(s.sessionId);
    await expect.poll(() => s.registry.get(queued.message.id)?.status).toBe('delivered');
  });
  it('STOP cancela e conversa nova preserva o agente', async () => {
    const s = await setup();
    await s.client.prompt(s.sessionId, 'sleep');
    const stopped = s.registry.control({ agentId: s.id, stop: true });
    expect(stopped).toHaveProperty('message');
    await expect.poll(() => s.client.session(s.sessionId)?.busy).toBe(false);
    const next = s.registry.control({ agentId: s.id, command: 'new' });
    expect(next).toHaveProperty('message');
    await expect.poll(() => s.office.get(s.id)?.sessionId).not.toBe(s.sessionId);
    expect(s.office.list()).toHaveLength(1);
    expect(s.office.get(s.id)?.status).not.toBe('offline');
  });
  it('recusa steer e aside; sessão fechada fica indisponível', async () => {
    const s = await setup();
    for (const deliverAs of ['steer', 'aside']) expect(() => s.registry.send({ agentId: s.id, text: 'ok', deliverAs })).toThrow(InvalidRequest);
    await s.client.close(s.sessionId);
    expect(s.delivery.available(s.sessionId)).toBe(false);
    expect(s.registry.canMessage(s.id)).toBe(false);
  });
});
