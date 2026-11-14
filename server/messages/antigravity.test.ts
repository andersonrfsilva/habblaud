import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Duplex } from 'node:stream';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsService } from '../accounts/service';
import { AntigravityClient } from '../antigravity/acp';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { AntigravitySource } from '../sources/antigravity/source';
import { AntigravityCatalog } from '../sources/antigravity/catalog';
import { AntigravityDelivery } from './antigravity';
import { InvalidRequest, MessageRegistry } from './registry';
import { HandoffStore } from '../cast/handoff';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

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
  const source = new AntigravitySource({ accounts, office, client, mirror: new AntigravityMirror({ dataDir: directory }), catalog: new AntigravityCatalog(directory) });
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

describe('instruções pendentes Antigravity', () => {
  it('sends instructions once, only after a person sends a message', async () => {
    const s = await setup();
    const prompt = vi.spyOn(s.client, 'prompt');
    s.delivery.setInstructions(s.sessionId, 'Seja breve.');
    expect(prompt).not.toHaveBeenCalled();
    expect(await s.delivery.sendMessage(s.sessionId, 'Olá')).toEqual({ ok: true });
    expect(prompt).toHaveBeenLastCalledWith(s.sessionId, 'Instruções do personagem:\nSeja breve.\n\nPedido inicial:\nOlá');
    await expect.poll(() => s.client.session(s.sessionId)?.busy).toBe(false);
    expect(await s.delivery.sendMessage(s.sessionId, 'Depois')).toEqual({ ok: true });
    expect(prompt).toHaveBeenLastCalledWith(s.sessionId, 'Depois');
  });
  it('retains instructions after rejected prompt and clears them on close', async () => {
    const s = await setup();
    const prompt = vi.spyOn(s.client, 'prompt').mockRejectedValueOnce(new Error('write failed'));
    s.delivery.setInstructions(s.sessionId, 'Instruções');
    expect((await s.delivery.sendMessage(s.sessionId, 'Primeira')).ok).toBe(false);
    expect((await s.delivery.sendMessage(s.sessionId, 'Segunda')).ok).toBe(true);
    expect(prompt).toHaveBeenLastCalledWith(s.sessionId, 'Instruções do personagem:\nInstruções\n\nPedido inicial:\nSegunda');
    await s.client.close(s.sessionId);
    await s.client.open({ cwd: '/tmp', resume: s.sessionId });
    await s.delivery.sendMessage(s.sessionId, 'Retomada');
    expect(prompt).toHaveBeenLastCalledWith(s.sessionId, 'Retomada');
  });
  it('new session preserves cast and model and rearms consumed instructions', async () => {
    const s = await setup();
    const opened = await s.client.open({ cwd: '/tmp', cast: 'novo-alice', model: 'fake-b' });
    s.delivery.setInstructions(opened.sessionId, 'Seja breve.');
    await s.delivery.sendMessage(opened.sessionId, 'Antes');
    await expect.poll(() => s.client.session(opened.sessionId)?.busy).toBe(false);
    const prompt = vi.spyOn(s.client, 'prompt');
    const next = await s.delivery.newSession(opened.sessionId);
    expect(s.client.session(next)).toMatchObject({ cast: 'novo-alice', model: 'fake-b' });
    expect(prompt).not.toHaveBeenCalled();
    await s.delivery.sendMessage(next, 'Depois');
    expect(prompt).toHaveBeenLastCalledWith(next, 'Instruções do personagem:\nSeja breve.\n\nPedido inicial:\nDepois');
  });
});

describe('anexo de contexto Antigravity', () => {
  it('mirrors only the person text when instructions and handoff accompany the prompt', async () => {
    const s = await setup();
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-clean-user-'));
    cleanups.push(() => rm(directory, { recursive: true, force: true }));
    const handoff = new HandoffStore({ dir: directory });
    const delivery = new AntigravityDelivery({ client: s.client, source: s.source, handoff });
    const { sessionId } = await s.client.open({ cwd: directory, cast: 'novo-clean' });
    delivery.setInstructions(sessionId, 'Seja breve.');
    handoff.put('novo-clean', 'ALFA');
    expect(await delivery.sendMessage(sessionId, 'Texto da pessoa')).toEqual({ ok: true });
    await expect.poll(() => s.client.session(sessionId)?.busy).toBe(false);
    await s.source.flush();
    const path = s.source.transcriptPathOf(`antigravity:${sessionId}`)!;
    const users = () => readFileSync(path, 'utf8').trim().split('\n').map(line => JSON.parse(line)).filter(record => record.message.role === 'user');
    expect(users().map(record => record.message.content)).toEqual(['Texto da pessoa']);
    await delivery.sendMessage(sessionId, 'Mensagem comum');
    await expect.poll(() => s.client.session(sessionId)?.busy).toBe(false);
    await s.source.flush();
    expect(users().map(record => record.message.content)).toEqual(['Texto da pessoa', 'Mensagem comum']);
  });
  it('envia resource_link antes do texto uma vez e restaura após falha', async () => {
    const s = await setup();
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-handoff-'));
    cleanups.push(() => rm(directory, { recursive: true, force: true }));
    const handoff = new HandoffStore({ dir: directory });
    const delivery = new AntigravityDelivery({ client: s.client, source: s.source, handoff });
    const { sessionId } = await s.client.open({ cwd: '/tmp', cast: 'nina' });
    const path = handoff.put('nina', 'ALFA');
    const blocks: Record<string, unknown>[] = [];
    s.client.onEvent(event => { if (event.type === 'update' && event.sessionId === sessionId && event.update.sessionUpdate === 'user_message_chunk') blocks.push(event.update.content as Record<string, unknown>); });
    const prompt = vi.spyOn(s.client, 'prompt').mockRejectedValueOnce(new Error('write failed'));
    expect(await delivery.sendMessage(sessionId, 'primeira')).toMatchObject({ ok: false });
    expect(handoff.pending('nina')).toBe(path);
    expect(await delivery.sendMessage(sessionId, 'segunda')).toEqual({ ok: true });
    await expect.poll(() => s.client.session(sessionId)?.busy).toBe(false);
    expect(blocks[0]).toEqual({ type: 'resource_link', uri: pathToFileURL(path).href, name: 'conversa-anterior.md', mimeType: 'text/markdown' });
    expect(blocks[1]).toMatchObject({ type: 'text', text: expect.stringContaining('Leia antes de responder.') });
    expect(readFileSync(path, 'utf8')).toBe('ALFA');
    expect(handoff.pending('nina')).toBeUndefined();
    blocks.length = 0;
    await delivery.sendMessage(sessionId, 'terceira');
    await expect.poll(() => s.client.session(sessionId)?.busy).toBe(false);
    expect(blocks).toEqual([{ type: 'text', text: 'terceira' }]);
    await expect(s.client.prompt(sessionId, 'bad', [{ path: 'relative.md', name: 'bad' }])).rejects.toThrow('absolute');
    expect(prompt).toHaveBeenCalled();
  });
});

describe('modelo numa conversa Antigravity aberta', () => {
  it('aplica modelo sem abrir sessão e espera o turno terminar', async () => {
    const s = await setup();
    const first = s.registry.control({ agentId: s.id, model: 'agy/fake-b' });
    if (!('message' in first)) throw new Error('controle recusado');
    await expect.poll(() => s.registry.get(first.message.id)?.status).toBe('delivered');
    await expect.poll(() => s.office.get(s.id)?.model).toBe('agy/fake-b');
    expect(s.office.get(s.id)?.sessionId).toBe(s.sessionId);
    await s.client.prompt(s.sessionId, 'sleep');
    const next = s.registry.control({ agentId: s.id, model: 'agy/fake-a' });
    if (!('message' in next)) throw new Error('controle recusado');
    expect(next.message.status).toBe('queued');
    expect(s.client.session(s.sessionId)?.model).toBe('fake-b');
    await s.client.cancel(s.sessionId);
    await expect.poll(() => s.registry.get(next.message.id)?.status).toBe('delivered');
    expect(s.client.session(s.sessionId)?.model).toBe('fake-a');
  });
  it('modelo desconhecido e padrão falham com motivo; thinking permanece recusado', async () => {
    const s = await setup();
    for (const model of ['agy/missing', 'agy/default']) {
      const result = s.registry.control({ agentId: s.id, model });
      if (!('message' in result)) throw new Error('controle recusado');
      await expect.poll(() => s.registry.get(result.message.id)?.status).toBe('failed');
      expect(s.registry.get(result.message.id)?.error).toMatch(/modelo/);
    }
    expect(s.registry.control({ agentId: s.id, thinking: 'high' })).toHaveProperty('error', 'unavailable');
  });
});
