import { randomUUID } from 'node:crypto';
import { appendFileSync, mkdirSync } from 'node:fs';
import { appendFile, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { maskSecrets } from '../../../shared/activity';
import type { AcpEvent } from '../../antigravity/acp';

export const ANTIGRAVITY_TOOLS: Readonly<Record<string, { name: string; fields: Readonly<Record<string, string>> }>> = {
  run_command: { name: 'Bash', fields: { command: 'CommandLine' } },
  read_file: { name: 'Read', fields: { file_path: 'Path' } },
  view_file: { name: 'Read', fields: { file_path: 'AbsolutePath' } },
  write_to_file: { name: 'Write', fields: { file_path: 'TargetFile' } },
  replace_file_content: { name: 'Edit', fields: { file_path: 'TargetFile' } },
  multi_replace_file_content: { name: 'Edit', fields: { file_path: 'TargetFile' } },
  grep_search: { name: 'Grep', fields: { pattern: 'Query', path: 'SearchPath' } },
  list_dir: { name: 'LS', fields: { path: 'DirectoryPath' } },
};
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const text = (value: unknown) => typeof value === 'string' ? value : '';
export function antigravityTool(call: Record<string, unknown>): { name: string; input: Record<string, string | number | boolean> } {
  const name = text(call.name);
  const tool = ANTIGRAVITY_TOOLS[name];
  const raw = object(call.rawInput) ? call.rawInput : {};
  if (!tool) return { name, input: { description: maskSecrets(text(call.title)).slice(0, 500),
    ...Object.fromEntries(Object.entries(raw)
      .filter(([key, value]) => key !== 'description' && (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'))
      .map(([key, value]) => [key, typeof value === 'string' ? maskSecrets(value).slice(0, 500) : value])) } };
  return { name: tool.name, input: Object.fromEntries(Object.entries(tool.fields).map(([key, source]) => [key, maskSecrets(text(raw[source]))])) };
}

type Pending = { kind: 'text' | 'thinking'; text: string };
export class AntigravityMirror {
  readonly root: string;
  private readonly pending = new Map<string, Pending>();
  private readonly expectedUsers = new Map<string, { sent: string; shown: string }[]>();
  private readonly directories = new Map<string, string>();
  constructor(options: { dataDir: string }) { this.root = join(options.dataDir, 'antigravity'); }
  pathOf(sessionId: string): string {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)) throw new Error('Invalid ACP session UUID');
    return join(this.root, 'sessions', `${sessionId}.jsonl`);
  }
  open(sessionId: string, cwd: string): void {
    const path = this.pathOf(sessionId);
    mkdirSync(join(this.root, 'sessions'), { recursive: true, mode: 0o700 });
    appendFileSync(path, '', { mode: 0o600 });
    this.directories.set(sessionId, cwd);
  }
  expectUser(sessionId: string, sent: string, shown: string): void {
    const expected = this.expectedUsers.get(sessionId) ?? [];
    expected.push({ sent, shown });
    this.expectedUsers.set(sessionId, expected);
  }
  async consume(event: AcpEvent): Promise<void> {
    if (event.type === 'diagnostic') return;
    const { sessionId } = event;
    if (event.type === 'session-open') { if (!this.directories.has(sessionId)) this.open(sessionId, event.cwd); return; }
    if (event.type === 'session-close') this.expectedUsers.delete(sessionId);
    if (event.type === 'turn-state' && !event.busy || event.type === 'session-close') { await this.flush(sessionId); return; }
    if (event.type !== 'update') return;
    const update = event.update;
    const kind = update.sessionUpdate;
    if (kind === 'agent_message_chunk' || kind === 'agent_thought_chunk') {
      if (!object(update.content) || typeof update.content.text !== 'string') return;
      const block = kind === 'agent_message_chunk' ? 'text' : 'thinking';
      if (this.pending.get(sessionId)?.kind !== block) await this.flush(sessionId);
      const pending = this.pending.get(sessionId) ?? { kind: block, text: '' };
      pending.text += update.content.text;
      this.pending.set(sessionId, pending);
      return;
    }
    await this.flush(sessionId);
    if (kind === 'user_message_chunk' && object(update.content) && typeof update.content.text === 'string' && update.content.text) {
      const content = update.content.text;
      const expected = this.expectedUsers.get(sessionId);
      const index = expected?.findIndex(item => item.sent === content) ?? -1;
      const shown = index >= 0 ? expected!.splice(index, 1)[0].shown : content;
      if (!expected?.length) this.expectedUsers.delete(sessionId);
      await this.append(sessionId, { type: 'user', message: { role: 'user', content: maskSecrets(shown) } });
    } else if (kind === 'tool_call') {
      const tool = antigravityTool(update);
      await this.append(sessionId, { type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [{ type: 'tool_use', id: update.toolCallId, name: tool.name, input: tool.input }], stop_reason: 'tool_use' } });
    } else if (kind === 'tool_call_update' && (update.status === 'completed' || update.status === 'failed')) {
      const output = object(update.rawOutput) ? text(update.rawOutput.output) : '';
      await this.append(sessionId, { type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: update.toolCallId, content: maskSecrets(output).slice(0, 20_000), ...(update.status === 'failed' ? { is_error: true } : {}) }] } });
    }
  }
  private async flush(sessionId: string): Promise<void> {
    const pending = this.pending.get(sessionId);
    if (!pending) return;
    this.pending.delete(sessionId);
    const content = pending.kind === 'text' ? { type: 'text', text: maskSecrets(pending.text) } : { type: 'thinking', thinking: maskSecrets(pending.text) };
    await this.append(sessionId, { type: 'assistant', message: { id: randomUUID(), role: 'assistant', content: [content], stop_reason: null } });
  }
  private async append(sessionId: string, fields: Record<string, unknown>): Promise<void> {
    const path = this.pathOf(sessionId);
    await mkdir(this.root, { recursive: true, mode: 0o700 });
    await mkdir(join(this.root, 'sessions'), { recursive: true, mode: 0o700 });
    const record = { uuid: randomUUID(), timestamp: new Date().toISOString(), sessionId, isSidechain: false, provider: 'antigravity', cwd: this.directories.get(sessionId), ...fields };
    await appendFile(path, JSON.stringify(record) + '\n', { mode: 0o600 });
  }
}
