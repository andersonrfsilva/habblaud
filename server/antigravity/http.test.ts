import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { AccountsService } from '../accounts/service';
import { createApiHandler } from '../http/app';
import { createRequestGuard } from '../http/guard';
import { Hub } from '../http/sse';
import { AntigravityDelivery } from '../messages/antigravity';
import { MessageRegistry } from '../messages/registry';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { AntigravitySource } from '../sources/antigravity/source';
import { AntigravityClient } from './acp';

const cleanup: (() => Promise<void>)[] = [];
afterEach(async () => { for (const finish of cleanup.splice(0).reverse()) await finish(); });
async function setup(options: { enabled?: boolean; remote?: boolean; auth?: boolean; terminal?: boolean } = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'antigravity-http-'));
  let child: ChildProcess | undefined;
  const client = new AntigravityClient({ bin: 'unused', skipPermissions: false, version: 'test', connect: () => {
    const process = spawn(globalThis.process.execPath, [fileURLToPath(new URL('./fake-agy.mjs', import.meta.url))], { env: { ...globalThis.process.env, FAKE_AGY_AUTH: options.auth === false ? '0' : '1' }, stdio: ['pipe', 'pipe', 'pipe'] });
    child = process; return Duplex.from({ readable: process.stdout, writable: process.stdin });
  } });
  const accounts = new AccountsService({ dirs: [], home: directory, env: {}, onChange() {} });
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: 0, accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'Antigravity' });
  const source = new AntigravitySource({ client, office, accounts, mirror: new AntigravityMirror({ dataDir: directory }) });
  source.start();
  const delivery = new AntigravityDelivery({ client, source });
  const messages = new MessageRegistry({ office, antigravity: delivery });
  const hub = new Hub(office);
  const api = createApiHandler({ office, hub, accounts, sources: () => [], version: 'test', inDocker: false, terminal: options.terminal !== false,
    remote: options.remote ? { is: () => true, allows: () => false } : undefined,
    antigravity: options.enabled === false ? undefined : { client, office, messages, skipPermissions: false } });
  const guard = createRequestGuard({ allowedHosts: new Set() });
  const server = createServer((req, res) => { if (!guard(req, res)) api(req, res, new URL(req.url ?? '/', 'http://localhost')); });
  const listening = Promise.withResolvers<void>(); server.listen(0, '127.0.0.1', () => listening.resolve()); await listening.promise;
  cleanup.push(async () => {
    server.closeAllConnections();
    const closed = Promise.withResolvers<void>(); server.close(() => closed.resolve()); await closed.promise;
    messages.stop(); source.stop(); hub.stop(); client.stop();
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = Promise.withResolvers<void>(); child.once('exit', () => exited.resolve()); await exited.promise;
    }
    await source.flush(); await rm(directory, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const post = (path: string, body: unknown) => fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return { base, post, office, client, directory };
}
describe('rotas Antigravity', () => {
  it('não publica rotas desligadas', async () => {
    const s = await setup({ enabled: false });
    expect((await fetch(s.base + '/api/antigravity')).status).toBe(404);
  });
  it('recusa acesso remoto sem spawn', async () => {
    const s = await setup({ remote: true });
    expect((await fetch(s.base + '/api/antigravity')).status).toBe(403);
    expect((await s.post('/api/antigravity/sessions', { folder: '/tmp' })).status).toBe(403);
  });
  it('abre, entrega prompt, mostra Dispensar e fecha sem launcher', async () => {
    const s = await setup();
    expect(await (await fetch(s.base + '/api/antigravity')).json()).toEqual({ available: true, skipPermissions: false });
    const response = await s.post('/api/antigravity/sessions', { folder: s.directory, prompt: 'ok' });
    expect((await (await fetch(s.base + '/api/access')).json() as { spawn: boolean }).spawn).toBe(true);
    expect(response.status).toBe(201);
    const body = await response.json() as { sessionId: string; agentId: string };
    expect(body.agentId).toBe(`antigravity:${body.sessionId}`);
    expect(s.office.get(body.agentId)?.provider).toBe('antigravity');
    expect(s.office.commit().snapshot.agents.find(agent => agent.id === body.agentId)?.castOpened).toBe(true);
    await expect.poll(() => s.client.session(body.sessionId)?.busy).toBe(false);
    const dismissed = await s.post(`/api/agents/${encodeURIComponent(body.agentId)}/dismiss`, {});
    expect(dismissed.status).toBe(202);
    expect(s.office.get(body.agentId)?.status).toBe('offline');
  });
  it('falta de login devolve 409 sem tentar outro método', async () => {
    const s = await setup({ auth: false });
    const response = await s.post('/api/antigravity/sessions', { folder: '/tmp' });
    expect(response.status).toBe(409);
    expect(await response.json()).toEqual({ error: 'entre na conta do Antigravity no computador: rode agy e faça o login' });
  });
  it('valida pasta absoluta, UUID e tamanho do pedido', async () => {
    const s = await setup();
    for (const body of [{ folder: 'relative' }, { folder: '/tmp', session: 'invalid' }, { folder: '/tmp', prompt: 'x'.repeat(8001) }]) {
      expect((await s.post('/api/antigravity/sessions', body)).status).toBe(422);
    }
  });
});
