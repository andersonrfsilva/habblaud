// Anexos do navegador: o depósito mantém apenas metadados; bytes ficam no volume persistente.
import { randomUUID } from 'node:crypto';
import { constants, mkdirSync, readdirSync, rmSync, lstatSync } from 'node:fs';
import { open, mkdir, rename, rm, type FileHandle } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { homedir } from 'node:os';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { tr } from '../../shared/i18n';
import type { AttachmentDescriptor } from '../../shared/types';

export const ATTACHMENT_MAX_BYTES = 15 * 1024 * 1024;
export const ATTACHMENTS_PER_SPAWN = 8;
export const ATTACHMENT_TTL_MS = 30 * 60_000;
export const STORE_MAX_BYTES = 120 * 1024 * 1024;
const NAME_MAX = 120;
export interface Attachment extends AttachmentDescriptor { path: string }
export type AttachmentConsumer = 'messages' | 'spawn';
export class AttachmentError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}
export function safeFileName(raw: string): string {
  const base = raw.split(/[\\/]/).pop() ?? '';
  let name = base.normalize('NFC').replace(/[\u0000-\u001f\u007f:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().replace(/^[.\s]+/, '');
  if (!name) name = 'arquivo';
  if (name.length > NAME_MAX) {
    const dot = name.lastIndexOf('.');
    const ext = dot > 0 && name.length - dot <= 12 ? name.slice(dot) : '';
    name = name.slice(0, NAME_MAX - ext.length) + ext;
  }
  return name;
}
export function attachmentsRoot(env: NodeJS.ProcessEnv = process.env): string {
  const root = env.HABBLAUD_ATTACHMENTS_DIR?.trim() || join(env.HOME || homedir(), '.habblaud', 'attachments');
  return isAbsolute(root) ? root : resolve(root);
}
interface Stored { file: Attachment; at: number; consumer: AttachmentConsumer; held: boolean }
export interface AttachmentStoreOptions { dir?: string; now?: () => number; maxBytes?: number }
export class AttachmentStore {
  private readonly items = new Map<string, Stored>();
  private bytes = 0;
  private readonly dir: string;
  private readonly now: () => number;
  private readonly maxBytes: number;
  constructor(opts: AttachmentStoreOptions = {}) {
    this.dir = resolve(opts.dir ?? join(attachmentsRoot(), '.pending'));
    this.now = opts.now ?? Date.now;
    this.maxBytes = opts.maxBytes ?? STORE_MAX_BYTES;
    mkdirSync(this.dir, { recursive: true, mode: 0o700 });
    if (lstatSync(this.dir).isSymbolicLink()) throw new Error('unsafe attachment staging');
    // Uma instância escritora por instalação. Só diretórios privados UUID são órfãos nossos.
    for (const name of readdirSync(this.dir)) if (/^[0-9a-f-]{36}$/.test(name)) rmSync(join(this.dir, name), { recursive: true, force: true });
  }
  get usedBytes(): number { return this.bytes; }
  async receive(req: IncomingMessage, consumer: AttachmentConsumer): Promise<{ id: string; name: string; size: number }> {
    const media = req.headers['content-type']?.split(';')[0].trim().toLowerCase();
    if (media !== 'application/octet-stream') throw new AttachmentError(415, tr('Envie o arquivo como binário (Content-Type: application/octet-stream).'));
    const encoded = req.headers['x-habblaud-file-name'];
    const mime = req.headers['x-habblaud-file-type'];
    let raw: string;
    try {
      if (typeof encoded !== 'string' || !encoded || encoded.length > 2048 || (mime !== undefined && (typeof mime !== 'string' || mime.length > 256))) throw new Error();
      raw = decodeURIComponent(encoded);
      if (!raw.trim()) throw new Error();
    } catch { throw new AttachmentError(400, tr('anexos inválidos')); }
    const name = safeFileName(raw);
    const type = typeof mime === 'string' && /^[\w.+-]+\/[\w.+-]+$/.test(mime) ? mime : 'application/octet-stream';
    const length = req.headers['content-length'];
    if (length !== undefined && (!/^\d+$/.test(length) || !Number.isSafeInteger(Number(length)))) throw new AttachmentError(400, tr('anexos inválidos'));
    const expected = length === undefined ? undefined : Number(length);
    if (expected !== undefined && expected > ATTACHMENT_MAX_BYTES) throw new AttachmentError(413, tr('o arquivo passa de {0} MB', [15]));
    if (expected === 0) throw new AttachmentError(400, tr('arquivo vazio'));
    this.prune();
    const reserved = expected ?? ATTACHMENT_MAX_BYTES;
    if (this.bytes + reserved > this.maxBytes) throw new AttachmentError(429, tr('anexos demais esperando: envie o pedido ou tente de novo daqui a pouco'));
    this.bytes += reserved;
    const id = randomUUID();
    const dir = join(this.dir, id);
    const path = join(dir, 'file');
    let handle: FileHandle | undefined;
    let size = 0;
    let failure: AttachmentError | undefined;
    let receiveError: Error | undefined;
    const failed = (error: Error) => { receiveError = error; };
    req.on('error', failed);
    try {
      await mkdir(dir, { mode: 0o700 });
      handle = await open(path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600);
      if (receiveError) throw receiveError;
      // Não usar pipeline(req,...): ela destrói o socket antes de o cliente receber 413.
      for await (const chunk of req.iterator({ destroyOnReturn: false })) {
        size += chunk.length;
        if (size > ATTACHMENT_MAX_BYTES) { failure = new AttachmentError(413, tr('o arquivo passa de {0} MB', [15])); break; }
        let offset = 0;
        while (offset < chunk.length) offset += (await handle.write(chunk, offset, chunk.length - offset)).bytesWritten;
      }
      await handle.close(); handle = undefined;
      if (failure) { req.resume(); throw failure; }
      if (!size) throw new AttachmentError(400, tr('arquivo vazio'));
      if (expected !== undefined && size !== expected) throw new AttachmentError(400, tr('anexos inválidos'));
      this.bytes += size - reserved;
      this.items.set(id, { file: { id, name, type, size, path }, consumer, held: false, at: this.now() });
      return { id, name, size };
    } catch (error) {
      try { await handle?.close(); await rm(dir, { recursive: true, force: true }); }
      finally { this.bytes -= reserved; }
      throw error;
    } finally { req.off('error', failed); }
  }
  take(ids: unknown, limit = ATTACHMENTS_PER_SPAWN, consumer: AttachmentConsumer = 'spawn'): Attachment[] | { error: string; attachmentsExpired?: true } {
    this.prune();
    if (ids === undefined) return [];
    if (!Array.isArray(ids) || ids.some(id => typeof id !== 'string') || new Set(ids).size !== ids.length) return { error: tr('anexos inválidos') };
    if (ids.length > limit) return { error: tr('no máximo {0} anexos por pedido', [limit]) };
    const entries = ids.map(id => this.items.get(id));
    if (entries.some(it => !it || it.held || it.consumer !== consumer)) return { error: tr('um anexo expirou: anexe de novo'), attachmentsExpired: true };
    return entries.map(it => { it!.held = true; return it!.file; });
  }
  release(files: readonly Attachment[]): void {
    for (const file of files) {
      const item = this.items.get(file.id);
      if (!item || item.file !== file) continue;
      this.items.delete(file.id);
      this.bytes -= file.size;
      rmSync(dirname(file.path), { recursive: true, force: true });
    }
  }
  private prune(): void {
    for (const item of this.items.values()) if (!item.held && this.now() - item.at > ATTACHMENT_TTL_MS) this.release([item.file]);
  }
}
/** Promove lote fechado no mesmo filesystem. Nenhum caminho é publicado antes do lote completo. */
export async function writeAttachments(root: string, _requestId: string, files: readonly Attachment[]): Promise<string[]> {
  if (!files.length) return [];
  root = resolve(root);
  await mkdir(root, { recursive: true, mode: 0o700 });
  const dir = join(root, randomUUID());
  await mkdir(dir, { mode: 0o700 });
  const used = new Set<string>();
  const paths: string[] = [];
  try {
    for (const file of files) {
      let name = safeFileName(file.name);
      if (used.has(name.toLowerCase())) {
        const dot = name.lastIndexOf('.');
        const [stem, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
        let n = 2;
        while (used.has(`${stem}-${n}${ext}`.toLowerCase())) n++;
        name = `${stem}-${n}${ext}`;
      }
      used.add(name.toLowerCase());
      const path = join(dir, name);
      await rename(file.path, path);
      paths.push(path);
    }
    return paths;
  } catch (error) { await rm(dir, { recursive: true, force: true }); throw error; }
}
/** Streams autorizados de um job; cancelamento só termina após fechar os descritores. */
export class AttachmentTransfers {
  private readonly active = new Set<{ abort: AbortController; done: Promise<void> }>();
  serve(file: Attachment, res: ServerResponse): Promise<void> {
    const abort = new AbortController();
    const { promise: done, resolve: closed } = Promise.withResolvers<void>();
    const transfer = { abort, done };
    this.active.add(transfer);
    return this.stream(file, res, abort.signal).finally(() => { this.active.delete(transfer); closed(); });
  }
  private async stream(file: Attachment, res: ServerResponse, signal: AbortSignal): Promise<void> {
    const handle = await open(file.path, constants.O_RDONLY | constants.O_NOFOLLOW);
    try {
      signal.throwIfAborted();
      if ((await handle.stat()).size !== file.size) throw new Error('attachment size changed');
      signal.throwIfAborted();
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': file.size });
      await pipeline(handle.createReadStream({ autoClose: false }), res, { signal });
    } finally { await handle.close(); }
  }
  async close(): Promise<void> {
    const active = [...this.active];
    for (const transfer of active) transfer.abort.abort();
    await Promise.allSettled(active.map(transfer => transfer.done));
  }
}
/** O pedido com a lista dos anexos no fim (o agente lê os arquivos pelos caminhos). */
export function promptWithAttachments(prompt: string, paths: readonly string[]): string {
  if (!paths.length) return prompt;
  const list = paths.map(p => `- ${p}`).join('\n');
  const head = paths.length === 1 ? tr('Anexo enviado pelo Habblaud (leia antes de começar):') : tr('Anexos enviados pelo Habblaud (leia antes de começar):');
  return `${prompt.trim() ? `${prompt.trimEnd()}\n\n` : ''}${head}\n${list}`;
}
