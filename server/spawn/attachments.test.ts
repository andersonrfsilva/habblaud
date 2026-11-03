import { chmodSync, readdirSync, readFileSync, statSync, existsSync, writeFileSync, rmSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { PassThrough, Readable } from 'node:stream';
import type { IncomingMessage } from 'node:http';
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { HAS_POSIX_MODES, tempDir } from '../test/fixtures';
import { attachmentRequest, uploadAttachment } from '../test/attachment-fixture';
import { ATTACHMENT_MAX_BYTES, ATTACHMENT_TTL_MS, AttachmentStore, promptWithAttachments, safeFileName, writeAttachments } from './attachments';

function take(store: AttachmentStore, ids: string[], consumer: 'messages' | 'spawn' = 'messages') {
  const result = store.take(ids, 8, consumer);
  if (!Array.isArray(result)) throw new Error(result.error);
  return result;
}
describe('anexos em disco', () => {
  it('saneia Unicode, separadores e nomes sem extensão', () => {
    expect(safeFileName('../ac\u0327a\u0303o.txt')).toBe('ação.txt');
    expect(safeFileName('C:\\x\\.env')).toBe('env');
    expect(safeFileName('a\u0000b:c?.png')).toBe('a_b_c_.png');
    expect(safeFileName(`${'x'.repeat(300)}.jpeg`)).toHaveLength(120);
  });
  it('1 byte, 15 MiB exatos e chunked; quota volta após descarte', async () => {
    const store = new AttachmentStore({ dir: tempDir().dir });
    for (const size of [1, ATTACHMENT_MAX_BYTES]) {
      const req = attachmentRequest(Buffer.alloc(size, 7));
      delete req.headers['content-length'];
      const result = await store.receive(req, 'messages');
      const files = take(store, [result.id]);
      expect(result.size).toBe(size);
      expect(createHash('sha256').update(readFileSync(files[0].path)).digest('hex')).toBe(createHash('sha256').update(Buffer.alloc(size, 7)).digest('hex'));
      expect(store.usedBytes).toBe(size);
      store.release(files); store.release(files);
      expect(store.usedBytes).toBe(0);
    }
  });
  it('vazio, oversized e metadados inválidos não deixam parciais', async () => {
    const dir = tempDir().dir;
    const store = new AttachmentStore({ dir });
    for (const request of [attachmentRequest(''), attachmentRequest('x', '', {}), attachmentRequest('x', 'a', { 'x-habblaud-file-name': '%ZZ' }), attachmentRequest('x', 'a', { 'content-length': 'invalid' }), attachmentRequest('x', 'a', { 'content-type': 'application/json' })]) {
      await expect(store.receive(request, 'messages')).rejects.toHaveProperty('status');
    }
    const req = attachmentRequest(Buffer.alloc(ATTACHMENT_MAX_BYTES + 1));
    delete req.headers['content-length'];
    await expect(store.receive(req, 'messages')).rejects.toHaveProperty('status', 413);
    await expect(store.receive(attachmentRequest('x', 'a', { 'content-length': '2' }), 'messages')).rejects.toHaveProperty('status', 400);
    expect(store.usedBytes).toBe(0);
    expect(readdirSync(dir)).toEqual([]);
  });
  it('aborto e falha do disco fecham e devolvem reservas', async () => {
    const dir = tempDir().dir;
    const store = new AttachmentStore({ dir });
    const req = Readable.from((async function* () { yield Buffer.from('a'); throw new Error('interrompido'); })()) as IncomingMessage;
    req.headers = attachmentRequest('ab').headers;
    await expect(store.receive(req, 'messages')).rejects.toThrow('interrompido');
    expect(store.usedBytes).toBe(0);
    expect(readdirSync(dir)).toEqual([]);
    // Diretório de staging removido simula falha do filesystem antes da abertura.
    rmSync(dir, { recursive: true });
    writeFileSync(dir, 'not a directory');
    await expect(store.receive(attachmentRequest('x'), 'messages')).rejects.toThrow();
    expect(store.usedBytes).toBe(0);
  });
  it('IDs duplicados, estrangeiros, consumidores errados e limite não consomem lote válido', async () => {
    const store = new AttachmentStore({ dir: tempDir().dir });
    const a = await uploadAttachment(store);
    expect(store.take([a.id, a.id], 5, 'messages')).toHaveProperty('error');
    expect(store.take([a.id, 'missing'], 5, 'messages')).toHaveProperty('attachmentsExpired', true);
    expect(store.take([a.id], 8, 'spawn')).toHaveProperty('error');
    expect(store.take([a.id], 0, 'messages')).toHaveProperty('error');
    const files = take(store, [a.id]);
    expect(store.take([a.id], 5, 'messages')).toHaveProperty('error');
    store.release(files);
  });
  it('reservas simultâneas e held compartilham orçamento messages/spawn', async () => {
    const store = new AttachmentStore({ dir: tempDir().dir, maxBytes: 8 });
    const a = await uploadAttachment(store, '12345');
    const b = await uploadAttachment(store, '123', 'b', 'spawn');
    const held = take(store, [a.id]);
    await expect(uploadAttachment(store, 'x')).rejects.toHaveProperty('status', 429);
    store.release(held);
    expect((await uploadAttachment(store, '12345')).size).toBe(5);
    store.release(take(store, [b.id], 'spawn'));
  });
  it('oito reservas em voo de 15 MiB são comuns; abortos devolvem capacidade', async () => {
    const store = new AttachmentStore({ dir: tempDir().dir });
    const requests = Array.from({ length: 8 }, () => {
      const req = new PassThrough() as unknown as IncomingMessage;
      req.headers = attachmentRequest('x', 'x', { 'content-length': String(ATTACHMENT_MAX_BYTES) }).headers;
      return req;
    });
    const receiving = requests.map((req, index) => store.receive(req, index < 5 ? 'messages' : 'spawn'));
    const settled = Promise.allSettled(receiving);
    expect(store.usedBytes).toBe(120 * 1024 * 1024);
    await expect(uploadAttachment(store, 'x')).rejects.toHaveProperty('status', 429);
    for (const req of requests) req.destroy(new Error('cancelled'));
    expect((await settled).every(result => result.status === 'rejected')).toBe(true);
    expect(store.usedBytes).toBe(0);
    expect((await uploadAttachment(store, 'x')).size).toBe(1);
  });
  it('TTL só remove pendentes; restart preserva finais e remove staging órfão', async () => {
    const root = tempDir().dir;
    const dir = join(root, '.pending');
    let now = 0;
    const store = new AttachmentStore({ dir, now: () => now });
    const a = await uploadAttachment(store, 'held');
    const held = take(store, [a.id]);
    const b = await uploadAttachment(store, 'pending');
    now += ATTACHMENT_TTL_MS + 1;
    expect(store.take([b.id], 5, 'messages')).toHaveProperty('error');
    expect(existsSync(held[0].path)).toBe(true);
    const [final] = await writeAttachments(root, 'job', held);
    store.release(held);
    await uploadAttachment(store, 'orphan');
    new AttachmentStore({ dir });
    expect(readdirSync(dir)).toEqual([]);
    expect(readFileSync(final, 'utf8')).toBe('held');
  });
  it('rename com nomes únicos, modos privados e raiz 0750 intacta', async () => {
    const root = tempDir().dir;
    chmodSync(root, 0o750);
    const store = new AttachmentStore({ dir: join(root, '.pending') });
    const a = await uploadAttachment(store, 'one', 'ação.txt');
    const b = await uploadAttachment(store, 'two', 'ação.txt');
    const files = take(store, [a.id, b.id]);
    const paths = await writeAttachments(root, 'job', files);
    expect(paths.map(path => basename(path))).toEqual(['ação.txt', 'ação-2.txt']);
    expect(readFileSync(paths[1], 'utf8')).toBe('two');
    if (HAS_POSIX_MODES) {
      expect(statSync(root).mode & 0o777).toBe(0o750);
      expect(statSync(dirname(paths[0])).mode & 0o777).toBe(0o700);
      expect(statSync(paths[0]).mode & 0o777).toBe(0o600);
    }
    store.release(files);
    expect(existsSync(paths[0])).toBe(true);
  });
  it('falha parcial remove só tentativa sem publicar ou apagar finais antigos', async () => {
    const root = tempDir().dir;
    const store = new AttachmentStore({ dir: join(root, '.pending') });
    const a = await uploadAttachment(store, 'old');
    const [old] = await writeAttachments(root, 'old', take(store, [a.id]));
    const b = await uploadAttachment(store, 'new');
    const files = take(store, [b.id]);
    await expect(writeAttachments(root, 'bad', [...files, { ...files[0], path: '/not-existing' }])).rejects.toThrow();
    expect(readdirSync(root).filter(name => name !== '.pending')).toEqual([basename(dirname(old))]);
    expect(readFileSync(old, 'utf8')).toBe('old');
    store.release(files);
    expect(store.usedBytes).toBe(3); // lote antigo ainda held; TTL nunca libera held.
  });
  it('o pedido ganha caminhos sem reler arquivos', () => {
    expect(promptWithAttachments('', ['/a', '/b'])).toBe('Anexos enviados pelo Habblaud (leia antes de começar):\n- /a\n- /b');
    expect(promptWithAttachments('texto', [])).toBe('texto');
  });
});
