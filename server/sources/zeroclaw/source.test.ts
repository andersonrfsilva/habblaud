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
  return { dataDir, gateway, accounts, office, mirror, connector, source, history };
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

  it.each([
    { label: 'perdeu o fim do bloco', chunk: 'resposta ', expected: 1 },
    { label: 'não perdeu texto', chunk: 'resposta completa', expected: 0 },
  ])('recupera HTTP depois da reconexão: $label', async ({ chunk, expected }) => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.connector.sendMessage(SID, 'pergunta');
    let receivedChunk = false;
    s.connector.onEvent((event) => { if (event.type === 'frame' && event.frame.type === 'chunk') receivedChunk = true; });
    s.gateway.connections[0].send({ type: 'chunk', content: chunk });
    await settled(s.source, () => receivedChunk);
    if (!expected) {
      // A ferramenta fecha o bloco, mas o turno continua ativo quando o socket cai.
      s.gateway.connections[0].send({ type: 'tool_call', id: 'pending', name: 'shell', args: {} });
      await settled(s.source, () => readFileSync(s.mirror.pathOf(SID), 'utf8').includes(chunk));
    }
    s.gateway.messages.set(SID, [
      { role: 'assistant', content: 'resposta antiga', created_at: '2020-01-01T00:00:00Z' },
      { role: 'assistant', content: 'resposta completa', created_at: new Date().toISOString() },
    ]);
    s.gateway.connections[0].drop();
    await vi.waitFor(() => expect(s.gateway.connections).toHaveLength(2), { timeout: 4000 });
    await settled(s.source, () => !s.connector.session(SID)?.busy && !s.mirror.needsRecovery(SID));
    const recovered = (await s.mirror.load(SID)).map((line) => JSON.parse(line)).filter((record) => record.uuid.startsWith('recovered-'));
    expect(recovered).toHaveLength(expected);
    if (expected) expect(recovered[0].message.content).toEqual([{ type: 'text', text: 'resposta completa' }]);
    const before = await s.mirror.load(SID);
    await s.connector.refreshTurnState(SID);
    await s.source.flush();
    expect(await s.mirror.load(SID)).toEqual(before);
  });

  it('aguarda done quando o turno reconectado ainda está ativo', async () => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.connector.sendMessage(SID, 'pergunta');
    s.gateway.states.set(SID, 'running');
    let receivedChunk = false;
    s.connector.onEvent((event) => { if (event.type === 'frame' && event.frame.type === 'chunk') receivedChunk = true; });
    s.gateway.connections[0].send({ type: 'chunk', content: 'parte ' });
    await settled(s.source, () => receivedChunk);
    s.gateway.connections[0].drop();
    await vi.waitFor(() => expect(s.gateway.connections).toHaveLength(2), { timeout: 4000 });
    await settled(s.source, () => s.connector.session(SID)?.connected === true && s.mirror.needsRecovery(SID));
    await s.connector.refreshTurnState(SID);
    await s.source.flush();
    expect(s.gateway.requests.filter((request) => request.path.endsWith('/messages'))).toHaveLength(1);
    expect((await s.mirror.load(SID)).some((line) => line.includes('recovered-'))).toBe(false);
    s.gateway.messages.set(SID, [{ role: 'assistant', content: 'parte perdida e final', created_at: new Date().toISOString() }]);
    s.gateway.states.set(SID, 'idle');
    s.gateway.connections[1].send({ type: 'done', full_response: 'parte perdida e final' });
    await settled(s.source, () => !s.mirror.needsRecovery(SID));
    const recovered = (await s.mirror.load(SID)).map((line) => JSON.parse(line)).filter((record) => record.uuid.startsWith('recovered-'));
    expect(recovered).toHaveLength(1);
    expect(recovered[0].message.content[0].text).toBe('parte perdida e final');
  });

  it('recupera duas respostas depois de reiniciar sem duplicar na segunda reinicialização', async () => {
    const s = await setup();
    await s.connector.openSession(open);
    await s.connector.sendMessage(SID, 'pergunta');
    s.gateway.connections[0].send({ type: 'chunk', content: 'bloco não persistido' });
    await s.source.flush();
    s.source.stop();
    s.gateway.messages.set(SID, [
      { role: 'assistant', content: 'primeira', created_at: new Date().toISOString() },
      { role: 'assistant', content: 'segunda', created_at: new Date(Date.now() + 1000).toISOString() },
    ]);
    for (let restart = 0; restart < 2; restart++) {
      const mirror = new ZeroClawMirror({ dataDir: s.dataDir });
      let source: ZeroClawSource;
      const connector = new ZeroClawConnector({ url: s.gateway.url, dataDir: s.dataDir, metadataOf: (id) => mirror.metadataOf(id), beforeOpen: (input) => source.prepare(input) });
      source = new ZeroClawSource({ accounts: s.accounts, office: s.office, connector, mirror });
      cleanup.push(() => source.stop());
      await source.start();
      await settled(source, () => !connector.session(SID)?.busy && !mirror.needsRecovery(SID));
      const recovered = (await mirror.load(SID)).map((line) => JSON.parse(line)).filter((record) => record.uuid.startsWith('recovered-'));
      expect(recovered.map((record) => record.message.content[0].text)).toEqual(['primeira', 'segunda']);
      source.stop();
    }
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
