import { mkdtempSync, readFileSync, rmSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import type { TerminalEntry } from '../../shared/types';
import { createTerminalParser } from '../sources/terminal';
import { createOmpTerminalParser } from '../sources/omp/terminal';
import { AntigravityMirror } from '../sources/antigravity/mirror';
import { HANDOFF_HEAD, HANDOFF_KEEP_MS, HANDOFF_MAX, HandoffStore, readEntries, renderHandoff } from './handoff';

const dirs: string[] = [];
const directory = () => { const dir = mkdtempSync(join(tmpdir(), 'handoff-')); dirs.push(dir); return dir; };
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }); });
const render = (entries: TerminalEntry[], previous?: string) => renderHandoff({ from: 'omp', cwd: '/project', model: 'provider/model', at: 0, entries, previous });

describe('handoff Markdown', () => {
  it('preserva falas, junta resultados numa linha e exclui raciocínio e sistema', () => {
    const text = render([
      { kind: 'user', id: 'u', at: 0, text: 'fala\ninteira' },
      { kind: 'thinking', id: 'h', at: 0, text: 'raciocínio secreto' },
      { kind: 'tool', id: 't', at: 0, tool: 'Bash', title: 'Bash(echo)' },
      { kind: 'result', id: 'r', at: 0, toolUseId: 't', text: 'saída\n' + 'x'.repeat(400), error: true },
      { kind: 'system', id: 's', at: 0, text: 'evento oculto' },
      { kind: 'assistant', id: 'a', at: 0, text: 'resposta\ninteira' },
    ], 'anterior');
    expect(text.startsWith('anterior\n\n---')).toBe(true);
    expect(text).toContain('## Pessoa\n\nfala\ninteira');
    expect(text).toContain('## Agente\n\nresposta\ninteira');
    expect(text).toContain('- ferramenta: Bash(echo) → erro: saída ');
    expect(text.split('\n').find(line => line.startsWith('- ferramenta:'))!.length).toBeLessThan(345);
    expect(text).not.toContain('raciocínio secreto');
    expect(text).not.toContain('evento oculto');
  });
  it('mascara segredos inclusive no contexto anterior', () => {
    expect(render([], 'Authorization: Bearer super-secret-token')).not.toContain('super-secret-token');
  });
  it('corta no limite, preservando começo e fim', () => {
    const text = render([{ kind: 'user', id: 'u', at: 0, text: 'A'.repeat(200_000) + 'FIM' }], 'COMEÇO');
    expect(text.length).toBe(HANDOFF_MAX);
    expect(text.startsWith('COMEÇO')).toBe(true);
    expect(text.slice(0, HANDOFF_HEAD)).not.toContain('omitidos');
    expect(text).toMatch(/\[… \d+ caracteres omitidos …\]/);
    expect(text.endsWith('FIM')).toBe(true);
  });
});

describe('HandoffStore', () => {
  it('mantém last depois de take, restaura, move e esquece só sem pendência', () => {
    const dir = directory();
    const store = new HandoffStore({ dir, now: () => 100 });
    const path = store.put('../Nina', 'conversa');
    expect(path).toBe(join(dir, '---nina-100.md'));
    expect(store.pending('../Nina')).toBe(path);
    store.forget('../Nina');
    expect(store.last('../Nina')).toBe(path);
    expect(store.take('../Nina')).toBe(path);
    expect(store.take('../Nina')).toBeUndefined();
    expect(store.last('../Nina')).toBe(path);
    store.restore('../Nina', path);
    store.move('../Nina', 'novo');
    expect(store.last('../Nina')).toBeUndefined();
    expect(store.pending('novo')).toBe(path);
    const restarted = new HandoffStore({ dir });
    expect(restarted.pending('novo')).toBe(path);
    expect(restarted.take('novo')).toBe(path);
    expect(new HandoffStore({ dir }).last('novo')).toBe(path);
    restarted.forget('novo');
    expect(new HandoffStore({ dir }).last('novo')).toBeUndefined();
    expect(statSync(dir).mode & 0o777).toBe(0o700);
    for (const file of [path, join(dir, 'index.json')]) expect(statSync(file).mode & 0o777).toBe(0o600);
  });
  it('índice inválido ou arquivo ausente não vira pendência', () => {
    const dir = directory();
    writeFileSync(join(dir, 'index.json'), '{broken');
    expect(new HandoffStore({ dir }).last('x')).toBeUndefined();
    writeFileSync(join(dir, 'index.json'), JSON.stringify({ x: { last: 'missing-1.md', pending: true }, y: { last: '../escape-1.md', pending: true } }));
    expect(new HandoffStore({ dir }).pending('x')).toBeUndefined();
    expect(new HandoffStore({ dir }).pending('y')).toBeUndefined();
  });
  it('evita colisões e limpa só arquivos vencidos', () => {
    const dir = directory();
    const now = Date.now();
    const store = new HandoffStore({ dir, now: () => now });
    const old = store.put('nina', 'old');
    const fresh = store.put('nina', 'fresh');
    const other = store.put('mateus', 'old');
    writeFileSync(join(dir, 'unrelated'), 'keep');
    for (const path of [old, other]) utimesSync(path, new Date(0), new Date(now - HANDOFF_KEEP_MS - 1));
    store.sweep();
    expect(() => readFileSync(old)).toThrow();
    expect(store.last('mateus')).toBeUndefined();
    expect(store.pending('nina')).toBe(fresh);
    expect(readFileSync(join(dir, 'unrelated'), 'utf8')).toBe('keep');
  });
});

describe('readEntries', () => {
  it('lê o fixture real do omp', async () => {
    const entries = await readEntries(join(import.meta.dirname, '../sources/omp/fixtures/main-clean-exit.jsonl'), createOmpTerminalParser());
    expect(render(entries)).toContain('## Pessoa');
    expect(render(entries)).toContain('## Agente');
  });
  it('lê o fixture do espelho Antigravity', async () => {
    const mirror = new AntigravityMirror({ dataDir: directory() });
    const sessionId = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
    await mirror.consume({ type: 'update', sessionId, update: { sessionUpdate: 'user_message_chunk', content: { text: 'Authorization: Bearer super-secret-token' } } });
    const text = render(await readEntries(mirror.pathOf(sessionId), createTerminalParser()));
    expect(text).toContain('## Pessoa');
    expect(text).not.toContain('super-secret-token');
  });
  it('ignora arquivo ausente e primeira linha parcial em arquivos grandes', async () => {
    const path = join(directory(), 'transcript');
    expect(await readEntries(path, createTerminalParser())).toEqual([]);
    writeFileSync(path, 'x'.repeat(4 * 1024 * 1024 + 100) + '\nlast\n');
    const lines: string[] = [];
    await readEntries(path, { push(line) { lines.push(line); return []; } });
    expect(lines).toEqual(['last', '']);
  });
});
