import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { antigravityTool, AntigravityMirror } from './mirror';

const SID = '7f2c9ee1-2bdd-49ea-b431-3d56fc046301';
describe('espelho Antigravity', () => {
  it('creates a private empty transcript on opening and never truncates on resume', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-mirror-'));
    try {
      const mirror = new AntigravityMirror({ dataDir: directory });
      const event = { type: 'session-open' as const, sessionId: SID, cwd: '/tmp', resumed: false, info: { models: [], modelOptions: [] } };
      await mirror.consume(event);
      const path = mirror.pathOf(SID);
      expect(await readFile(path, 'utf8')).toBe('');
      expect((await stat(path)).mode & 0o777).toBe(0o600);
      expect((await stat(dirname(path))).mode & 0o777).toBe(0o700);
      await mirror.consume({ type: 'update', sessionId: SID, update: { sessionUpdate: 'user_message_chunk', content: { text: 'hello' } } });
      const saved = await readFile(path, 'utf8');
      await mirror.consume({ ...event, resumed: true });
      expect(await readFile(path, 'utf8')).toBe(saved);
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  it('traduz a tabela de ferramentas e preserva ferramentas desconhecidas', () => {
    expect(antigravityTool({ name: 'run_command', rawInput: { CommandLine: 'echo ok' } })).toEqual({ name: 'Bash', input: { command: 'echo ok' } });
    expect(antigravityTool({ name: 'view_file', rawInput: { AbsolutePath: '/tmp/file' } })).toEqual({ name: 'Read', input: { file_path: '/tmp/file' } });
    expect(antigravityTool({ name: 'custom', title: 'Teste', rawInput: { target: '/tmp/file' } })).toEqual({ name: 'custom', input: { description: 'Teste', target: '/tmp/file' } });
  });
  it('translates read_file and masks and bounds primitive unknown-tool arguments', () => {
    expect(antigravityTool({ name: 'read_file', rawInput: { Path: '/tmp/context.md' } })).toEqual({ name: 'Read', input: { file_path: '/tmp/context.md' } });
    const tool = antigravityTool({ name: 'custom', title: 'Custom action', rawInput: { secret: 'Authorization: Bearer super-secret-token', long: 'x'.repeat(600), count: 2, enabled: true, nested: { ignored: true } } });
    expect(tool.input).toMatchObject({ description: 'Custom action', count: 2, enabled: true });
    expect(tool.input.long).toHaveLength(500);
    expect(JSON.stringify(tool)).not.toContain('super-secret-token');
    expect(tool.input).not.toHaveProperty('nested');
  });
  it('usa modos privados, mascara segredos e limita saída de ferramenta', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'antigravity-mirror-'));
    try {
      const mirror = new AntigravityMirror({ dataDir: directory });
      await mirror.consume({ type: 'update', sessionId: SID, update: { sessionUpdate: 'user_message_chunk', content: { text: 'Authorization: Bearer super-secret-token' } } });
      await mirror.consume({ type: 'update', sessionId: SID, update: { sessionUpdate: 'tool_call_update', toolCallId: 'tool', status: 'completed', rawOutput: { output: 'x'.repeat(21_000) } } });
      const path = mirror.pathOf(SID);
      expect((await stat(path)).mode & 0o777).toBe(0o600);
      expect((await stat(dirname(path))).mode & 0o777).toBe(0o700);
      const raw = await readFile(path, 'utf8');
      expect(raw).not.toContain('super-secret-token');
      expect(JSON.parse(raw.trim().split('\n')[1]).message.content[0].content).toHaveLength(20_000);
      expect(() => mirror.pathOf('../escape')).toThrow();
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
});
