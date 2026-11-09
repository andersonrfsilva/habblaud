import { mkdtemp, readFile, rm, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { ConnectorEvent, OpenSession } from './connector';
import { ZeroClawMirror } from './mirror';
import { createTranscriptState, parseLine } from '../transcript';
import { createTerminalParser } from '../terminal';

const sessionId = '10000000-0000-4000-8000-000000000001';
const input: OpenSession = { sessionId, agent: 'dev', name: 'Projeto', cwd: '/projetos/demo', cast: 'dev', officeAgentId: 'office-dev' };
const frame = (value: { type: string; [key: string]: unknown }): ConnectorEvent => ({ type: 'frame', sessionId, frame: value });
const opened = (continuityLost = false): ConnectorEvent => ({ type: 'session-open', sessionId, descriptor: { ...input, connected: true, busy: continuityLost, continuityLost }, resumed: true, continuityLost });
const sent = (id: string, content = 'Olá "mundo"\n日本語'): ConnectorEvent => ({ type: 'message-sent', sessionId, id, content });

let dataDir: string;
let mirror: ZeroClawMirror;
let diagnostics: string[];
beforeEach(async () => {
  dataDir = await mkdtemp(join(tmpdir(), 'habblaud-zc-mirror-'));
  diagnostics = [];
  mirror = new ZeroClawMirror({ dataDir, diagnostic: (message) => diagnostics.push(message) });
});
afterEach(async () => { await rm(dataDir, { recursive: true, force: true }); });

async function parsed() {
  const lines = await mirror.load(sessionId);
  const state = createTranscriptState();
  const activities = lines.flatMap((line) => parseLine(state, line, { idPrefix: 'zc:test', now: Date.now() }).activities);
  const terminal = createTerminalParser();
  return { lines, records: lines.map((line) => JSON.parse(line)), state, activities, entries: lines.flatMap((line) => terminal.push(line)) };
}

describe('ZeroClawMirror — contrato de frames e parsers reais', () => {
  it('session_start cria título uma vez, inclusive após reinício, sem prompt', async () => {
    await mirror.consume(opened());
    await mirror.consume(frame({ type: 'session_start', name: input.name }));
    mirror = new ZeroClawMirror({ dataDir });
    await mirror.consume(opened());
    const { records, state, entries } = await parsed();
    expect(records).toHaveLength(1);
    expect(records[0]).toMatchObject({ type: 'custom-title', customTitle: input.name, provider: 'zeroclaw', sessionId, cwd: input.cwd, isSidechain: false });
    expect(state.customTitle).toBe(input.name);
    expect(entries.filter((entry) => entry.kind === 'user')).toHaveLength(0);
    expect(mirror.metadataOf(sessionId)).toEqual(input);
  });

  it('deduplica somente ID da operação local; textos iguais e deltas iguais sobrevivem', async () => {
    await mirror.consume(sent('operation-1'));
    await mirror.consume(sent('operation-1'));
    await mirror.consume(sent('operation-2'));
    await mirror.consume(frame({ type: 'chunk', content: 'igual\n"☃"' }));
    await mirror.consume(frame({ type: 'chunk', content: 'igual\n"☃"' }));
    await mirror.consume(frame({ type: 'done', full_response: 'igual\n"☃"igual\n"☃"', model: 'modelo-real', input_tokens: 123, output_tokens: 45, cost: 12 }));
    const { records, entries, state } = await parsed();
    expect(records.filter((record) => record.type === 'user')).toHaveLength(2);
    expect(entries.filter((entry) => entry.kind === 'assistant')).toHaveLength(2);
    expect(new Set(records.map((record) => record.uuid)).size).toBe(records.length);
    const assistants = records.filter((record) => record.type === 'assistant');
    expect(new Set(assistants.map((record) => record.message.id)).size).toBe(3);
    expect(assistants[2].message).toEqual({ id: expect.any(String), role: 'assistant', content: [], stop_reason: 'end_turn', model: 'modelo-real' });
    expect(records.at(-1)).toMatchObject({ type: 'system', subtype: 'turn_duration', durationMs: expect.any(Number) });
    expect(state.ended).toBe(true);
    expect(state.model).toBe('modelo-real');
    expect(records.every((record) => record.message?.usage === undefined && record.type !== 'cost-state')).toBe(true);
    expect(records.every((record) => record.gitBranch === undefined && record.context === undefined)).toBe(true);
    mirror = new ZeroClawMirror({ dataDir });
    await mirror.consume(sent('operation-1'));
    expect(await mirror.load(sessionId)).toHaveLength(records.length);
  });

  it('thinking vazio não inventa pensamento; conteúdo é preservado', async () => {
    await mirror.consume(frame({ type: 'thinking', content: '' }));
    await mirror.consume(frame({ type: 'thinking', content: 'Pensar\n"日本語"' }));
    const { entries, records } = await parsed();
    expect(records).toHaveLength(1);
    expect(records[0].message).toMatchObject({ stop_reason: null, content: [{ type: 'thinking', thinking: 'Pensar\n"日本語"' }] });
    expect(entries).toEqual([expect.objectContaining({ kind: 'thinking', text: 'Pensar\n"日本語"' })]);
  });

  it('ferramentas mantêm IDs e nomes genéricos; resultado JSON não vira erro', async () => {
    const id = '../id/"工具"';
    await mirror.consume(frame({ type: 'tool_call', id, name: 'zeroclaw_nova', args: { query: '"☃"\n' } }));
    await mirror.consume(frame({ type: 'tool_result', id, output: { text: 'error aparece no texto', count: 2 } }));
    const { records, state, entries } = await parsed();
    expect(records[0].message.content[0]).toEqual({ type: 'tool_use', id, name: 'zeroclaw_nova', input: { query: '"☃"\n' } });
    expect(records[1].message.content[0]).toEqual({ type: 'tool_result', tool_use_id: id, content: '{"text":"error aparece no texto","count":2}' });
    expect(state.pendingTools.size).toBe(0);
    expect(entries).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'tool', tool: 'zeroclaw_nova' }), expect.objectContaining({ kind: 'result' })]));
    expect(records[1].message.content[0].is_error).toBeUndefined();
  });

  it('done sem chunks inclui resposta; marcador de chunks não vaza entre turnos', async () => {
    await mirror.consume(frame({ type: 'chunk', content: 'primeiro' }));
    await mirror.consume(frame({ type: 'done', full_response: 'primeiro' }));
    await mirror.consume(sent('turn-2', 'segunda pergunta'));
    await mirror.consume(frame({ type: 'done', full_response: 'segundo' }));
    await mirror.consume(frame({ type: 'done', full_response: '' }));
    const { records, entries } = await parsed();
    expect(entries.filter((entry) => entry.kind === 'assistant').map((entry) => 'text' in entry ? entry.text : '')).toEqual(['primeiro', 'segundo']);
    expect(records.filter((record) => record.type === 'assistant').at(-1).message.content).toEqual([]);
    expect(records.filter((record) => record.subtype === 'turn_duration')).toHaveLength(3);
  });

  it('aborted limpa ferramentas nos dois parsers sem inventar done', async () => {
    await mirror.consume(frame({ type: 'tool_call', id: 'pending', name: 'shell', args: {} }));
    await mirror.consume(frame({ type: 'aborted' }));
    const { records, state, entries } = await parsed();
    expect(state.pendingTools.size).toBe(0);
    expect(state.ended).toBe(true);
    expect(records.at(-1).message.content).toBe('[Request interrupted by user]');
    expect(records.some((record) => record.subtype === 'turn_duration')).toBe(false);
    expect(entries).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'system', level: 'warn' })]));
  });

  it('erro visível é sanitizado; aprovação não concede permissão nem termina turno', async () => {
    await mirror.consume(frame({ type: 'tool_call', id: 'pending', name: 'shell', args: {} }));
    await mirror.consume(frame({ type: 'error', code: 'QUEUE', message: 'falhou\nAuthorization: Bearer secret-value-that-must-not-leak' }));
    await mirror.consume(frame({ type: 'approval_request', token: 'hidden', command: 'hidden' }));
    const { records, state, entries } = await parsed();
    expect(records[1]).toMatchObject({ type: 'system', subtype: 'informational', level: 'error' });
    expect(records[1].content).not.toContain('secret-value-that-must-not-leak');
    expect(records[2]).toMatchObject({ level: 'warning' });
    expect(records[2].content).toContain('nenhuma permissão');
    expect(state.pendingTools.size).toBe(1);
    expect(entries).toEqual(expect.arrayContaining([expect.objectContaining({ kind: 'system', level: 'error' }), expect.objectContaining({ kind: 'system', level: 'warn' })]));
  });

  it('lacuna e confirmação HTTP aparecem somente em suas transições', async () => {
    await mirror.consume(opened(true));
    await mirror.consume(opened(true));
    const terminal: ConnectorEvent = { type: 'turn-state', sessionId, busy: false, continuityLost: false, terminalConfirmed: true, source: 'http-state' };
    await mirror.consume(terminal);
    await mirror.consume(terminal);
    const { records, entries } = await parsed();
    const infos = records.filter((record) => record.subtype === 'informational');
    expect(infos).toHaveLength(2);
    expect(infos[0]).toMatchObject({ level: 'warning' });
    expect(infos[1]).toMatchObject({ level: 'info' });
    expect(records.some((record) => record.subtype === 'turn_duration' || record.type === 'user')).toBe(false);
    expect(entries.filter((entry) => entry.kind === 'system')).toHaveLength(2);
  });

  it('queda durante turno sinaliza lacuna sem reimportar resposta', async () => {
    await mirror.consume(sent('disconnect'));
    await mirror.consume({ type: 'session-close', sessionId, intentional: false, replacement: false, code: 1001, reason: 'reload' });
    const { records } = await parsed();
    expect(records.at(-1)).toMatchObject({ subtype: 'informational', level: 'warning' });
    expect(records.filter((record) => record.type === 'user')).toHaveLength(1);
  });

  it('frames desconhecidos e args inválidos não escrevem payload ou credenciais', async () => {
    await mirror.consume(frame({ type: 'future_event', token: 'sensitive', content: 'sensitive' }));
    await mirror.consume(frame({ type: 'Bearer secret', token: 'sensitive' }));
    await mirror.consume(frame({ type: 'tool_call', id: 'x', name: 'shell', args: 'sensitive' }));
    expect(await mirror.load(sessionId)).toEqual([]);
    expect(diagnostics).toHaveLength(3);
    expect(diagnostics.join(' ')).toContain('future_event');
    expect(diagnostics.join(' ')).not.toMatch(/sensitive|Bearer secret/);
  });
});

describe('ZeroClawMirror — persistência', () => {
  it('índice versionado persiste metadados parciais sem fabricar projeto', async () => {
    await Promise.all([mirror.setMetadata({ sessionId, agent: 'dev' }), mirror.setMetadata({ sessionId, name: 'Nome' })]);
    expect(mirror.metadataOf(sessionId)).toBeUndefined();
    mirror = new ZeroClawMirror({ dataDir });
    expect(mirror.entries()).toEqual([{ sessionId, agent: 'dev', name: 'Nome' }]);
    await mirror.setMetadata({ sessionId, cwd: '/projeto' });
    expect(mirror.metadataOf(sessionId)).toEqual({ sessionId, agent: 'dev', name: 'Nome', cwd: '/projeto' });
    expect(JSON.parse(await readFile(join(mirror.root, 'sessions.json'), 'utf8'))).toMatchObject({ version: 1, sessions: [{ sessionId }] });
    expect(() => mirror.pathOf('../../escape')).toThrow();
  });

  it('índice inválido falha sem sobrescrever arquivo', async () => {
    await mkdir(mirror.root, { recursive: true });
    const path = join(mirror.root, 'sessions.json');
    await writeFile(path, '{"version":99,"sessions":[]}');
    expect(() => new ZeroClawMirror({ dataDir, diagnostic: (message) => diagnostics.push(message) })).toThrow();
    expect(await readFile(path, 'utf8')).toBe('{"version":99,"sessions":[]}');
    expect(diagnostics).toHaveLength(1);
  });

  it('bootstrap HTTP roda uma vez e não emite feed; IDs e timestamps são determinísticos', async () => {
    const history = [{ role: 'user', content: 'pergunta', created_at: '2026-11-09T10:00:00Z' }, { role: 'assistant', content: 'resposta', timestamp: '2026-11-09T10:01:00Z' }];
    const live: string[] = [];
    mirror.onLine((_id, line) => live.push(line));
    await Promise.all([mirror.bootstrap(sessionId, history), mirror.bootstrap(sessionId, history)]);
    await mirror.bootstrap(sessionId, [...history, { role: 'assistant', content: 'não importar' }]);
    const { records, entries } = await parsed();
    expect(live).toEqual([]);
    expect(records).toHaveLength(2);
    expect(records[0].timestamp).toBe('2026-11-09T10:00:00.000Z');
    expect(records[0].cwd).toBeUndefined();
    expect(entries.map((entry) => entry.kind)).toEqual(['user', 'assistant']);
    const other = await mkdtemp(join(tmpdir(), 'habblaud-zc-bootstrap-'));
    try {
      const second = new ZeroClawMirror({ dataDir: other });
      await second.bootstrap(sessionId, history);
      expect((await second.load(sessionId)).map((line) => JSON.parse(line).uuid)).toEqual(records.map((record) => record.uuid));
    } finally { await rm(other, { recursive: true, force: true }); }
  });

  it('append preserva ordem e só publica depois de persistir', async () => {
    const observed: Array<Promise<boolean>> = [];
    const unsubscribe = mirror.onLine((_id, line) => {
      observed.push(readFile(mirror.pathOf(sessionId), 'utf8').then((disk) => disk.includes(line + '\n')));
    });
    await Promise.all(Array.from({ length: 12 }, (_, index) => mirror.consume(sent(`id-${index}`, `prompt-${index}`))));
    expect(await Promise.all(observed)).toEqual(Array(12).fill(true));
    const { records } = await parsed();
    expect(records.map((record) => record.message.content)).toEqual(Array.from({ length: 12 }, (_, index) => `prompt-${index}`));
    unsubscribe();
  });

  it('erro de disco rejeita operação, publica diagnóstico e não publica linha', async () => {
    await mkdir(mirror.pathOf(sessionId), { recursive: true });
    const live: string[] = [];
    mirror.onLine((_id, line) => live.push(line));
    await expect(mirror.consume(sent('disk-failure'))).rejects.toThrow();
    expect(live).toEqual([]);
    expect(diagnostics.join(' ')).toContain('Falha');
    expect(diagnostics.join(' ')).not.toContain(dataDir);
  });

  it('repara somente fragmento inválido final e preserva linhas completas', async () => {
    await mirror.consume(sent('before-crash', 'preservado'));
    const original = await readFile(mirror.pathOf(sessionId), 'utf8');
    await writeFile(mirror.pathOf(sessionId), original + '{"type":"assistant","content":"☃');
    mirror = new ZeroClawMirror({ dataDir, diagnostic: (message) => diagnostics.push(message) });
    await mirror.consume(sent('after-crash', 'novo'));
    const disk = await readFile(mirror.pathOf(sessionId), 'utf8');
    expect(disk.startsWith(original)).toBe(true);
    expect((await parsed()).records.map((record) => record.message.content)).toEqual(['preservado', 'novo']);
    expect(diagnostics.join(' ')).toContain('Fragmento final incompleto');
  });

  it('registro inválido no meio falha sem truncar conversa', async () => {
    await mkdir(join(mirror.root, 'sessions'), { recursive: true });
    const broken = '{"type":"user"}\nnot-json\n{"type":"assistant"}\n';
    await writeFile(mirror.pathOf(sessionId), broken);
    await expect(mirror.consume(sent('should-fail'))).rejects.toThrow('meio do espelho');
    expect(await readFile(mirror.pathOf(sessionId), 'utf8')).toBe(broken);
  });
  it('falha no índice não altera metadados em memória', async () => {
    await mkdir(join(mirror.root, 'sessions.json'), { recursive: true });
    await expect(mirror.setMetadata(input)).rejects.toThrow();
    expect(mirror.entries()).toEqual([]);
    expect(mirror.metadataOf(sessionId)).toBeUndefined();
    expect(diagnostics.join(' ')).toContain('Falha ao persistir índice');
  });

  it('bootstrap vazio também impede importações posteriores sobre streaming', async () => {
    await mirror.bootstrap(sessionId, []);
    await mirror.consume(frame({ type: 'done', full_response: 'ao vivo' }));
    await mirror.bootstrap(sessionId, [{ role: 'user', content: 'não duplicar' }]);
    const { entries } = await parsed();
    expect(entries.filter((entry) => entry.kind === 'user')).toHaveLength(0);
    expect(entries.filter((entry) => entry.kind === 'assistant')).toEqual([expect.objectContaining({ text: 'ao vivo' })]);
  });

  it('snapshot assistant concluído não suprime full_response do próximo turno', async () => {
    await mirror.bootstrap(sessionId, [{ role: 'assistant', content: 'histórico', timestamp: '2026-11-09T10:00:00Z' }]);
    await mirror.consume(sent('new-turn'));
    await mirror.consume(frame({ type: 'done', full_response: 'nova resposta' }));
    const { entries } = await parsed();
    expect(entries.filter((entry) => entry.kind === 'assistant').map((entry) => 'text' in entry ? entry.text : '')).toEqual(['histórico', 'nova resposta']);
  });

  it('linha JSON completa sem newline não é descartada antes do append', async () => {
    await mkdir(join(mirror.root, 'sessions'), { recursive: true });
    const complete = JSON.stringify({ type: 'user', uuid: 'complete', timestamp: '2026-11-09T10:00:00Z', sessionId, message: { role: 'user', content: 'completo' } });
    await writeFile(mirror.pathOf(sessionId), complete);
    await mirror.consume(sent('after-complete', 'novo'));
    expect((await readFile(mirror.pathOf(sessionId), 'utf8')).startsWith(complete + '\n')).toBe(true);
    expect((await parsed()).records).toHaveLength(2);
  });
});
