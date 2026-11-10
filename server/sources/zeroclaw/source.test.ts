import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AccountsService } from '../../accounts/service';
import { NameStore } from '../../model/names';
import { Office } from '../../model/office';
import { setQuiet } from '../../log';
import { createTerminalParser } from '../terminal';
import { ZeroClawConnector } from './connector';
import { ZeroClawMirror } from './mirror';
import { ZeroClawSource } from './source';
import { ZeroClawHistory } from './history';
import { startTestGateway } from './test-gateway';

setQuiet(true);
const SID = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
const NEXT = '7f2c9ee1-2bdd-49ea-b431-3d56fc046302';
const cleanup: Array<() => void | Promise<void>> = [];
afterEach(async () => { for (const fn of cleanup.splice(0).reverse()) await fn(); });

async function setup() {
  const dataDir = mkdtempSync(join(tmpdir(), 'zc-source-'));
  cleanup.push(() => rmSync(dataDir, { recursive: true, force: true }));
  const gateway = await startTestGateway();
  cleanup.push(() => gateway.close());
  const accounts = new AccountsService({ dirs: [], home: dataDir, env: {}, onChange() {} });
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: Date.now(), accounts: (s) => accounts.list(s), sources: () => [], accountName: () => 'ZeroClaw' });
  const mirror = new ZeroClawMirror({ dataDir });
  let source: ZeroClawSource;
  const connector = new ZeroClawConnector({ url: gateway.url, dataDir, metadataOf: (id) => mirror.metadataOf(id), beforeOpen: (input) => source.prepare(input), timeoutMs: 300 });
  source = new ZeroClawSource({ accounts, office, connector, mirror });
  cleanup.push(() => source.stop());
  const history = new ZeroClawHistory({ connector, mirror, account: () => source.accountId,
    openAgentOf: (_account, id) => office.list().find((a) => a.sessionId === id && a.status !== 'offline')?.id });
  await source.start();
  return { gateway, accounts, office, mirror, connector, source, history };
}
const open = { sessionId: SID, agent: 'assistant', name: 'Teste', cwd: '/projetos/teste' };

async function settled(source: ZeroClawSource, condition: () => boolean) {
  await vi.waitFor(async () => { await source.flush(); expect(condition()).toBe(true); });
}

describe('fonte ZeroClaw', () => {
  it('registra conta sem sessões; inventário remoto não cria presença', async () => {
    const s = await setup();
    s.gateway.sessions.push({ session_id: SID, agent_alias: 'assistant', created_at: new Date().toISOString(), last_activity: new Date().toISOString(), channel_id: null });
    await s.source.poll();
    expect(s.accounts.entriesOf('zeroclaw')[0].id).toBe(s.source.accountId);
    expect(s.office.list()).toHaveLength(0);
    expect(s.source.sources()[0].sessions).toBe(0);
  });

  it('transporta prompt, ferramenta e deltas iguais; preserva leitura após fechamento', async () => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.source.flush();
    const id = `zeroclaw:${s.source.accountId}:${SID}`;
    expect(s.office.get(id)).toMatchObject({ provider: 'zeroclaw', sessionId: SID, roomId: open.cwd });
    expect(s.office.get(id)?.context).toBeUndefined();
    await s.connector.sendMessage(SID, 'Faça o teste');
    await settled(s.source, () => s.gateway.connections[0].messages.length === 1);
    expect(s.gateway.connections[0].messages[0]).toEqual({ type: 'message', content: 'Faça o teste' });
    const socket = s.gateway.connections[0];
    socket.send({ type: 'tool_call', id: 'call-test', name: 'custom_tool', args: { path: 'a' } });
    socket.send({ type: 'tool_result', id: 'call-test', name: 'custom_tool', output: 'ok' });
    socket.send({ type: 'chunk', content: 'igual ' });
    socket.send({ type: 'chunk', content: 'igual ' });
    socket.send({ type: 'done', full_response: 'igual igual ', model: 'test-model', input_tokens: 100 });
    await settled(s.source, () => s.office.get(id)?.status === 'idle');
    const path = s.source.transcriptPathOf(id)!;
    const records = readFileSync(path, 'utf8').trim().split('\n').map((line) => JSON.parse(line));
    expect(records.filter((r) => r.message?.content === 'Faça o teste')).toHaveLength(1);
    expect(records.filter((r) => r.message?.content?.[0]?.text === 'igual igual ')).toHaveLength(1);
    expect(records.some((r) => r.message?.usage || r.type === 'cost-state')).toBe(false);
    const parser = createTerminalParser();
    const parsed = readFileSync(path, 'utf8').trim().split('\n').flatMap((line) => parser.push(line));
    expect(parsed.filter((entry) => entry.kind === 'assistant').map((entry) => entry.text)).toEqual(['igual igual']);
    expect(s.office.get(id)?.model).toBe('test-model');
    await s.connector.closeSession(SID);
    await s.source.flush();
    expect(s.office.get(id)?.status).toBe('offline');
    expect(s.history.resolve(s.source.accountId, SID)).toHaveProperty('path', path);
  });

  it('aprovação espera; erro não termina turno; HTTP parado não fabrica sucesso', async () => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.source.flush();
    const id = `zeroclaw:${s.source.accountId}:${SID}`;
    await s.connector.sendMessage(SID, 'teste');
    s.gateway.connections[0].send({ type: 'approval_request', id: 'approval', tool: 'shell' });
    await settled(s.source, () => s.office.get(id)?.status === 'waiting');
    s.gateway.connections[0].send({ type: 'error', code: 'queue_full', message: 'fila cheia' });
    await s.source.flush();
    expect(s.connector.session(SID)?.busy).toBe(true);
    await s.connector.abortSession(SID);
    await s.source.flush();
    expect(s.office.get(id)?.status).toBe('idle');
    expect(s.office.get(id)?.recent.some((a) => a.kind === 'done')).toBe(false);
  });

  it('replacement conserva handle e troca UUID antes de aplicar transcript novo', async () => {
    const s = await setup();
    await s.connector.openSession({ ...open, officeAgentId: 'stable-handle' });
    await s.source.flush();
    await s.connector.closeSession(SID, { replacement: true });
    await s.source.flush();
    expect(s.office.get('stable-handle')?.status).not.toBe('offline');
    await s.connector.openSession({ ...open, sessionId: NEXT, officeAgentId: 'stable-handle' });
    await s.source.flush();
    expect(s.office.get('stable-handle')?.sessionId).toBe(NEXT);
    expect(s.office.list()).toHaveLength(1);
    // O terminal segue a conversa nova, não o espelho da sessão anterior (bug do /new no piloto 043).
    expect(s.source.transcriptPathOf('stable-handle')).toMatch(new RegExp(`${NEXT}\\.jsonl$`));
  });

  it('queda HTTP remove presença mas preserva histórico e intenção', async () => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.source.flush();
    s.gateway.responses.set('/api/sessions', { status: 503, body: {} });
    await s.source.poll();
    expect(s.source.sources()[0]).toMatchObject({ ok: false, sessions: 0 });
    expect(s.connector.session(SID)).toBeDefined();
    expect(s.history.resolve(s.source.accountId, SID)).toHaveProperty('path');
    s.gateway.responses.delete('/api/sessions');
    await s.source.poll();
    expect(s.source.sources()[0].sessions).toBe(1);
  });

  it('equilibra boot mesmo quando gateway falha e convive com outro provider', async () => {
    const s = await setup();
    s.source.stop();
    const connector = new ZeroClawConnector({ url: s.gateway.url, dataDir: join(s.mirror.root, '..'), timeoutMs: 100 });
    s.gateway.options.status = 503;
    const begin = vi.spyOn(s.office, 'beginBoot');
    const end = vi.spyOn(s.office, 'endBoot');
    const source = new ZeroClawSource({ accounts: s.accounts, office: s.office, connector, mirror: s.mirror });
    cleanup.push(() => source.stop());
    await source.start();
    expect(begin).toHaveBeenCalledTimes(1);
    expect(end).toHaveBeenCalledTimes(1);
    s.office.addMain({ id: 'other', provider: 'omp', account: 'other', sessionId: 'other', cwd: '/other', role: 'Agente principal', startedAt: Date.now(), status: 'idle' });
    expect(s.office.get('other')?.provider).toBe('omp');
    expect(source.sources()[0].ok).toBe(false);
  });
});
