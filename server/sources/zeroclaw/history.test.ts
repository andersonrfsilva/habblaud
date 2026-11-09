import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { ZeroClawConnector } from './connector';
import { ZeroClawMirror } from './mirror';
import { ZeroClawHistory } from './history';
import { startTestGateway } from './test-gateway';

const SID = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
const NEXT = '7f2c9ee1-2bdd-49ea-b431-3d56fc046302';
const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => { for (const fn of cleanup.splice(0).reverse()) await fn(); });
async function setup(limit?: number) {
  const dataDir = mkdtempSync(join(tmpdir(), 'zc-history-'));
  cleanup.push(() => rmSync(dataDir, { recursive: true, force: true }));
  const gateway = await startTestGateway();
  cleanup.push(() => gateway.close());
  const mirror = new ZeroClawMirror({ dataDir });
  const connector = new ZeroClawConnector({ url: gateway.url, dataDir, metadataOf: (id) => mirror.metadataOf(id), timeoutMs: 300 });
  cleanup.push(() => connector.stop());
  const diagnostics: string[] = [];
  const history = new ZeroClawHistory({ connector, mirror, account: () => 'final-account~2', openAgentOf: () => undefined, diagnostic: (message) => diagnostics.push(message), limit });
  return { dataDir, gateway, mirror, connector, history, diagnostics };
}
const remote = (id = SID, age = 0) => ({ session_id: id, session_key: `gw_${id}`, agent_alias: 'assistant', created_at: new Date(Date.now() - age).toISOString(), last_activity: new Date(Date.now() - age).toISOString(), channel_id: null });

describe('histórico ZeroClaw', () => {
  it('materializa snapshot uma vez, sem WS, tools ou projeto inventado', async () => {
    const s = await setup();
    s.gateway.sessions.push(remote());
    s.gateway.messages.set(SID, [{ role: 'user', content: 'pergunta', timestamp: new Date().toISOString() }, { role: 'assistant', content: 'resposta', timestamp: new Date().toISOString() }]);
    const list = await s.history.list();
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ account: 'final-account~2', provider: 'zeroclaw', projectDir: 'sessions', open: false });
    expect(list[0].project).toBeUndefined();
    const path = s.mirror.pathOf(SID);
    const before = readFileSync(path, 'utf8');
    s.gateway.messages.set(SID, [{ role: 'assistant', content: 'não reimportar' }]);
    await s.history.list();
    expect(readFileSync(path, 'utf8')).toBe(before);
    expect(before).not.toContain('tool_use');
    expect(s.gateway.connections).toHaveLength(0);
    expect(s.connector.metadataOf(SID)).toBeUndefined();
    expect(s.connector.metadataOf('omp-session')).toBeUndefined();
  });

  it('filtra canal e idade, ordena e limita antes das importações', async () => {
    const s = await setup(1);
    s.gateway.sessions.push(remote(SID, 1000), remote(NEXT), remote('7f2c9ee1-2bdd-49ea-b431-3d56fc046303', 9 * 86400000), { ...remote('7f2c9ee1-2bdd-49ea-b431-3d56fc046304'), channel_id: 'telegram.bot' }, { ...remote(), session_id: '../escape' });
    const list = await s.history.list();
    expect(list.map((item) => item.sessionId)).toEqual([NEXT]);
  });

  it('preserva leitura e metadados locais com gateway offline', async () => {
    const s = await setup();
    await s.mirror.setMetadata({ sessionId: SID, agent: 'assistant', name: 'Local', cwd: '/projetos/local', lastAt: Date.now() });
    await s.mirror.bootstrap(SID, [{ role: 'assistant', content: 'salvo' }]);
    s.gateway.responses.set('/api/sessions', { status: 503, body: {} });
    const list = await s.history.list();
    expect(list[0]).toMatchObject({ project: '/projetos/local', open: false });
    expect(s.diagnostics).not.toHaveLength(0);
    expect(s.history.resolve('final-account~2', SID)).toHaveProperty('path');
    expect(s.connector.metadataOf(SID)).toMatchObject({ cwd: '/projetos/local' });
  });

  it('resolve síncrono rejeita conta, UUID, traversal e symlink de fuga sem HTTP', async () => {
    const s = await setup();
    expect(s.history.resolve('other', SID)).toMatchObject({ status: 404 });
    expect(s.history.resolve('final-account~2', '../escape')).toMatchObject({ status: 400 });
    expect(s.history.resolve('final-account~2', SID)).toMatchObject({ status: 404 });
    const outside = join(s.dataDir, 'outside.jsonl');
    writeFileSync(outside, '{}\n');
    mkdirSync(join(s.mirror.root, 'sessions'), { recursive: true });
    symlinkSync(outside, s.mirror.pathOf(SID));
    expect(s.history.resolve('final-account~2', SID)).toMatchObject({ status: 404 });
    expect(s.gateway.connections).toHaveLength(0);
  });
});
