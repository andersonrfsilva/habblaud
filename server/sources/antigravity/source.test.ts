import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { AccountsService } from '../../accounts/service';
import type { AcpEvent } from '../../antigravity/acp';
import { setQuiet } from '../../log';
import { NameStore } from '../../model/names';
import { Office } from '../../model/office';
import { createTerminalParser } from '../terminal';
import { AntigravityMirror } from './mirror';
import { AntigravitySource } from './source';

setQuiet(true);
const SID = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
const cleanups: (() => void)[] = [];
afterEach(() => { for (const cleanup of cleanups.splice(0).reverse()) cleanup(); });
function setup() {
  const dataDir = mkdtempSync(join(tmpdir(), 'antigravity-source-'));
  cleanups.push(() => rmSync(dataDir, { recursive: true, force: true }));
  const accounts = new AccountsService({ dirs: [], home: dataDir, env: {}, onChange() {} });
  const office = new Office({ names: new NameStore(null), version: 'test', startedAt: Date.now(), accounts: sources => accounts.list(sources), sources: () => [], accountName: () => 'Antigravity' });
  const listeners = new Set<(event: AcpEvent) => void>();
  const client = { onEvent(listener: (event: AcpEvent) => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }, session: () => ({ sessionId: SID, cwd: '/tmp', busy: true }) };
  const source = new AntigravitySource({ accounts, office, client, mirror: new AntigravityMirror({ dataDir }) });
  cleanups.push(() => source.stop());
  source.start();
  const emit = (event: AcpEvent) => { for (const listener of listeners) listener(event); };
  emit({ type: 'session-open', sessionId: SID, cwd: '/tmp', resumed: false, info: { model: 'fake', models: ['fake'] } });
  return { accounts, office, source, emit, id: `antigravity:${SID}` };
}
describe('fonte Antigravity', () => {
  it('registra conta, entra e acompanha trabalho, permissão e fechamento', async () => {
    const s = setup();
    expect(s.accounts.entriesOf('antigravity')[0].detected.short).toBe('G');
    expect(s.office.get(s.id)).toMatchObject({ provider: 'antigravity', status: 'idle', sessionId: SID });
    s.emit({ type: 'turn-state', sessionId: SID, busy: true });
    expect(s.office.get(s.id)?.status).toBe('working');
    s.emit({ type: 'permission', sessionId: SID, requestId: 1, toolCall: { title: 'echo ok' }, options: [] });
    expect(s.office.get(s.id)).toMatchObject({ status: 'waiting', waitingFor: 'echo ok' });
    s.emit({ type: 'permission-gone', sessionId: SID, requestId: 1 });
    expect(s.office.get(s.id)?.status).toBe('working');
    s.emit({ type: 'turn-state', sessionId: SID, busy: false });
    expect(s.office.get(s.id)?.status).toBe('idle');
    s.emit({ type: 'session-close', sessionId: SID, reason: 'closed' });
    expect(s.office.get(s.id)?.status).toBe('offline');
    expect(s.source.sources()[0].sessions).toBe(0);
    await s.source.flush();
  });
  it('aplica atividade Bash, título, modelo e contexto', async () => {
    const s = setup();
    s.emit({ type: 'update', sessionId: SID, update: { sessionUpdate: 'tool_call', toolCallId: 'tool', name: 'run_command', rawInput: { CommandLine: 'echo ok' } } });
    s.emit({ type: 'update', sessionId: SID, update: { sessionUpdate: 'session_info_update', title: 'Teste' } });
    s.emit({ type: 'update', sessionId: SID, update: { sessionUpdate: 'usage_update', used: 50, size: 100 } });
    expect(s.office.get(s.id)?.recent.some(activity => activity.tool === 'Bash')).toBe(true);
    expect(s.office.get(s.id)).toMatchObject({ model: 'fake', context: { tokens: 50, window: 100 } });
    await s.source.flush();
  });
  it('o leitor de terminal existente lê texto e ferramentas do espelho', async () => {
    const s = setup();
    const update = (value: Record<string, unknown>) => s.emit({ type: 'update', sessionId: SID, update: value });
    update({ sessionUpdate: 'user_message_chunk', content: { type: 'text', text: 'Teste' } });
    update({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'Olá ' } });
    update({ sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: 'mundo' } });
    update({ sessionUpdate: 'tool_call', toolCallId: 'tool', name: 'run_command', rawInput: { CommandLine: 'echo ok' } });
    update({ sessionUpdate: 'tool_call_update', toolCallId: 'tool', status: 'completed', rawOutput: { output: 'ok' } });
    s.emit({ type: 'turn-state', sessionId: SID, busy: false });
    await s.source.flush();
    const parser = createTerminalParser();
    const lines = readFileSync(s.source.transcriptPathOf(s.id)!, 'utf8').trim().split('\n');
    const entries = lines.flatMap(line => parser.push(line));
    expect(entries.some(entry => entry.kind === 'assistant' && entry.text === 'Olá mundo')).toBe(true);
    expect(lines.some(line => line.includes('tool_result'))).toBe(true);
  });
  it('adota a sessão nova sem trocar o agente e ignora o fechamento antigo', async () => {
    const s = setup();
    const next = '7f2c9ee1-2bdd-49ea-b431-3d56fc046302';
    expect(await s.source.replace(SID, async () => {
      s.emit({ type: 'session-open', sessionId: next, cwd: '/tmp', resumed: false, info: { models: [] } });
      return { sessionId: next };
    })).toBe(next);
    s.emit({ type: 'session-close', sessionId: SID, reason: 'closed' });
    expect(s.office.list()).toHaveLength(1);
    expect(s.office.get(s.id)).toMatchObject({ sessionId: next, status: 'idle' });
    expect(s.source.sources()[0].sessions).toBe(1);
    await s.source.flush();
  });
  it('preserva a sessão antiga se abrir falha e rejeita adoção concorrente', async () => {
    const s = setup();
    const pending = Promise.withResolvers<{ sessionId: string }>();
    const replacement = s.source.replace(SID, () => pending.promise);
    await expect(s.source.replace(SID, async () => ({ sessionId: SID }))).rejects.toThrow();
    pending.reject(new Error('falha'));
    await expect(replacement).rejects.toThrow('falha');
    expect(s.office.get(s.id)).toMatchObject({ sessionId: SID, status: 'idle' });
    await expect(s.source.replace(SID, async () => { throw new Error('outra falha'); })).rejects.toThrow('outra falha');
    expect(s.office.get(s.id)?.sessionId).toBe(SID);
    await s.source.flush();
  });
});
