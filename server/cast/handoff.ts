import { randomUUID } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { open } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { maskSecrets } from '../../shared/activity';
import type { TerminalEntry } from '../../shared/types';
import type { TerminalParser } from '../sources/terminal';

export const HANDOFF_MAX = 150_000;
export const HANDOFF_HEAD = 20_000;
export const HANDOFF_KEEP_MS = 7 * 24 * 3600_000;
const READ_MAX = 4 * 1024 * 1024;

/** Converte a conversa em texto, sem raciocínio nem mensagens de sistema. */
export function renderHandoff(input: { from: string; model?: string; cwd: string; at: number; entries: TerminalEntry[]; previous?: string }): string {
  const results = new Map(input.entries.filter((e): e is Extract<TerminalEntry, { kind: 'result' }> => e.kind === 'result').map(e => [e.toolUseId, e]));
  const parts = [input.previous ? `${input.previous}\n\n---\n` : '', '# Conversa anterior', `Ferramenta: ${input.from}${input.model ? ` (${input.model})` : ''}`, `Pasta: ${input.cwd}`, `Data: ${new Date(input.at).toISOString()}`, 'Gerado pelo Habblaud a partir da conversa; saídas de ferramentas foram cortadas.'];
  for (const entry of input.entries) {
    if (entry.kind === 'user' || entry.kind === 'assistant') parts.push(`\n## ${entry.kind === 'user' ? 'Pessoa' : 'Agente'}\n\n${entry.text}`);
    if (entry.kind === 'tool') {
      const result = results.get(entry.id);
      const output = result?.text.replace(/\s+/g, ' ').slice(0, 300);
      parts.push(`- ferramenta: ${entry.title.replace(/\s+/g, ' ')} → ${result?.error ? 'erro' : 'ok'}${output ? `: ${output}` : ''}`);
    }
  }
  const text = maskSecrets(parts.join('\n'));
  if (text.length <= HANDOFF_MAX) return text;
  let omitted = text.length - HANDOFF_MAX;
  let marker = `\n[… ${omitted} caracteres omitidos …]\n`;
  omitted = text.length - HANDOFF_MAX + marker.length;
  marker = `\n[… ${omitted} caracteres omitidos …]\n`;
  const tail = HANDOFF_MAX - HANDOFF_HEAD - marker.length;
  return text.slice(0, HANDOFF_HEAD) + marker + text.slice(-tail);
}

type RecordEntry = { last: string; pending: boolean };
export class HandoffStore {
  private readonly records = new Map<string, RecordEntry>();
  private readonly now: () => number;
  constructor(private readonly opts: { dir: string; now?: () => number }) {
    this.now = opts.now ?? Date.now;
    mkdirSync(opts.dir, { recursive: true, mode: 0o700 });
    chmodSync(opts.dir, 0o700);
    try {
      const index: unknown = JSON.parse(readFileSync(join(opts.dir, 'index.json'), 'utf8'));
      if (index && typeof index === 'object' && !Array.isArray(index)) {
        for (const [cast, entry] of Object.entries(index)) {
          if (entry && typeof entry === 'object' && typeof entry.last === 'string' && typeof entry.pending === 'boolean'
            && basename(entry.last) === entry.last && /^[a-z0-9-]+-\d+\.md$/.test(entry.last) && existsSync(join(opts.dir, entry.last))) {
            this.records.set(cast, { last: entry.last, pending: entry.pending });
          }
        }
      }
    } catch { /* Índice ausente ou ilegível: não há contexto a entregar. */ }
  }
  private atomic(path: string, text: string): void {
    const temporary = `${path}.${randomUUID()}.tmp`;
    try { writeFileSync(temporary, text, { mode: 0o600 }); renameSync(temporary, path); }
    finally { if (existsSync(temporary)) unlinkSync(temporary); }
  }
  private save(): void { this.atomic(join(this.opts.dir, 'index.json'), JSON.stringify(Object.fromEntries(this.records))); }
  put(cast: string, markdown: string): string {
    const safe = cast.toLowerCase().replace(/[^a-z0-9-]/g, '-') || 'cast';
    let epoch = this.now();
    while (existsSync(join(this.opts.dir, `${safe}-${epoch}.md`))) epoch++;
    const file = `${safe}-${epoch}.md`;
    this.atomic(join(this.opts.dir, file), markdown);
    this.records.set(cast, { last: file, pending: true });
    this.save();
    return join(this.opts.dir, file);
  }
  pending(cast: string): string | undefined { return this.records.get(cast)?.pending ? this.last(cast) : undefined; }
  last(cast: string): string | undefined { const entry = this.records.get(cast); return entry ? join(this.opts.dir, entry.last) : undefined; }
  take(cast: string): string | undefined {
    const path = this.pending(cast);
    if (path) { this.records.get(cast)!.pending = false; this.save(); }
    return path;
  }
  restore(cast: string, path: string): void {
    if (this.last(cast) !== path) return;
    this.records.get(cast)!.pending = true;
    this.save();
  }
  move(from: string, to: string): void {
    if (from === to) return;
    const entry = this.records.get(from);
    if (!entry) return;
    this.records.set(to, entry); this.records.delete(from); this.save();
  }
  forget(cast: string): void {
    if (this.pending(cast)) return;
    if (this.records.delete(cast)) this.save();
  }
  sweep(): void {
    for (const file of readdirSync(this.opts.dir)) {
      if (!/^[a-z0-9-]+-\d+\.md$/.test(file)) continue;
      if (this.now() - statSync(join(this.opts.dir, file)).mtimeMs <= HANDOFF_KEEP_MS) continue;
      unlinkSync(join(this.opts.dir, file));
      for (const [cast, entry] of this.records) if (entry.last === file) this.records.delete(cast);
    }
    this.save();
  }
}

/** Lit au plus les derniers 4 Mio, sans envoyer une ligne partielle au parseur. */
export async function readEntries(path: string, parser: TerminalParser): Promise<TerminalEntry[]> {
  let file;
  try { file = await open(path, 'r'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []; throw error; }
  try {
    const size = (await file.stat()).size;
    const start = Math.max(0, size - READ_MAX);
    const buffer = Buffer.alloc(Math.min(size, READ_MAX));
    const { bytesRead } = await file.read(buffer, 0, buffer.length, start);
    let text = buffer.subarray(0, bytesRead).toString('utf8');
    if (start) text = text.slice(text.indexOf('\n') < 0 ? text.length : text.indexOf('\n') + 1);
    return text.split('\n').flatMap(line => parser.push(line));
  } finally { await file.close(); }
}
