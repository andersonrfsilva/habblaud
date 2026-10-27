import { chmodSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HAS_POSIX_MODES, tempDir } from '../test/fixtures';
import { ATTACHMENT_MAX_BYTES, ATTACHMENTS_PER_SPAWN, AttachmentStore, parseAttachment, promptWithAttachments, safeFileName, writeAttachments } from './attachments';

const b64 = (s: string) => Buffer.from(s).toString('base64');

describe('anexos do Novo agente', () => {
  it('nome seguro: sem caminho, controles nem ponto no começo', () => {
    expect(safeFileName('../../etc/passwd')).toBe('passwd');
    expect(safeFileName('C:\\Users\\x\\foto 1.jpg')).toBe('foto 1.jpg');
    expect(safeFileName('.env')).toBe('env');
    expect(safeFileName('a\u0000b:c?.png')).toBe('a_b_c_.png');
    expect(safeFileName('')).toBe('arquivo');
    const long = safeFileName(`${'x'.repeat(300)}.jpeg`);
    expect(long.length).toBe(120);
    expect(long.endsWith('.jpeg')).toBe(true);
  });

  it('parseAttachment: base64 válido, não vazio e no limite', () => {
    expect(parseAttachment({ name: 'a.png', type: 'image/png', data: b64('oi') })).toMatchObject({ name: 'a.png', type: 'image/png' });
    expect(parseAttachment({ name: 'a', type: 'bad type', data: b64('oi') })).toMatchObject({ type: 'application/octet-stream' });
    expect(parseAttachment({ name: 'a', data: '' })).toHaveProperty('error');
    expect(parseAttachment({ name: 'a', data: 'não é base64!' })).toHaveProperty('error');
    expect(parseAttachment({ name: 'a', data: Buffer.alloc(ATTACHMENT_MAX_BYTES + 1).toString('base64') })).toHaveProperty('error');
    expect(parseAttachment({ name: 'a', data: 'a' })).toHaveProperty('error');
    expect(parseAttachment({ name: 'a', data: 'ab==' })).toHaveProperty('error');
    expect(parseAttachment({ name: 'a', data: Buffer.alloc(ATTACHMENT_MAX_BYTES).toString('base64') })).toHaveProperty('data');
  });

  it('o depósito entrega cada anexo uma vez, expira e limita a quantidade', () => {
    let now = 0;
    let n = 0;
    const store = new AttachmentStore(() => now, () => `id${++n}`);
    const a = store.put({ name: 'a.txt', type: 'text/plain', data: Buffer.from('a') });
    const b = store.put({ name: 'b.txt', type: 'text/plain', data: Buffer.from('b') });
    expect(a).toEqual({ id: 'id1' });
    expect(store.take(['id1', 'id2'])).toHaveLength(2);
    expect(store.take(['id1'])).toHaveProperty('error');
    expect(b).toEqual({ id: 'id2' });
    store.put({ name: 'c', type: 't/t', data: Buffer.from('c') });
    now += 31 * 60_000;
    expect(store.take(['id3'])).toHaveProperty('error');
    expect(store.take(undefined)).toEqual([]);
    expect(store.take('id1')).toHaveProperty('error');
    expect(store.take(Array.from({ length: ATTACHMENTS_PER_SPAWN + 1 }, (_, i) => `x${i}`))).toHaveProperty('error');
  });

  it('grava numa pasta nova (700, arquivos 600) com nomes únicos', () => {
    const root = tempDir().dir;
    const files = [
      { name: 'foto.jpg', type: 'image/jpeg', data: Buffer.from('1') },
      { name: 'foto.jpg', type: 'image/jpeg', data: Buffer.from('2') },
      { name: '../nota.txt', type: 'text/plain', data: Buffer.from('3') },
    ];
    const paths = writeAttachments(root, 'abcdef12-3456', files);
    expect(paths.map((p) => p.split(/[\\/]/).pop())).toEqual(['foto.jpg', 'foto-2.jpg', 'nota.txt']);
    const dir = dirname(paths[0]);
    expect(dir.slice(root.length + 1)).toMatch(/^[a-f0-9-]{36}$/);
    if (HAS_POSIX_MODES) {
      expect(statSync(dir).mode & 0o777).toBe(0o700);
      expect(statSync(paths[0]).mode & 0o777).toBe(0o600);
    }
    expect(readFileSync(paths[1], 'utf8')).toBe('2');
    expect(readdirSync(dir)).toHaveLength(3);
    expect(writeAttachments(root, 'x', [])).toEqual([]);
  });

  it('o pedido ganha a lista dos caminhos', () => {
    expect(promptWithAttachments('Corrija o layout', ['/h/.habblaud/attachments/x/print.png'])).toBe(
      'Corrija o layout\n\nAnexo enviado pelo Habblaud (leia antes de começar):\n- /h/.habblaud/attachments/x/print.png',
    );
    expect(promptWithAttachments('', ['/a', '/b'])).toBe('Anexos enviados pelo Habblaud (leia antes de começar):\n- /a\n- /b');
    expect(promptWithAttachments('só texto', [])).toBe('só texto');
  });

  it('teto de memória: conta também os anexos já tirados por pedidos na fila, até serem soltos', () => {
    let n = 0;
    const store = new AttachmentStore(Date.now, () => `a${++n}`, 10);
    const file = (size: number) => ({ name: 'x.png', type: 'image/png', data: Buffer.alloc(size, 1) });
    expect(store.put(file(6))).toEqual({ id: 'a1' });
    const taken = store.take(['a1']);
    expect(Array.isArray(taken) && taken.length).toBe(1);
    // Os 6 bytes saíram do depósito, mas seguem na memória (pedido na fila): 6 + 6 passa do teto.
    expect(store.put(file(6))).toHaveProperty('error');
    store.release(6);
    expect(store.put(file(6))).toEqual({ id: 'a2' });
  });

  it('mensagens aceitam cinco e Novo agente oito; recusas são atômicas', () => {
    let n = 0;
    const store = new AttachmentStore(Date.now, () => `id${++n}`);
    for (let i = 0; i < 8; i++) store.put({ name: 'x', type: 'text/plain', data: Buffer.from('x') });
    expect(store.take(['id1', 'id1'], 5)).toHaveProperty('error');
    expect(store.take(['id1', 'missing'], 5)).toMatchObject({ attachmentsExpired: true });
    expect(store.take(['id1', 'id2', 'id3', 'id4', 'id5', 'id6'], 5)).toHaveProperty('error');
    expect(store.take(['id1', 'id2', 'id3', 'id4', 'id5', 'id6', 'id7', 'id8'])).toHaveLength(8);
  });

  it('lotes no mesmo instante não compartilham pasta nem sobrescrevem bytes', () => {
    const root = tempDir().dir;
    const file = (text: string) => [{ name: 'ação.txt', type: 'text/plain', data: Buffer.from(text) }];
    const [a] = writeAttachments(root, 'mesmo-pedido', file('primeiro'));
    const [b] = writeAttachments(root, 'mesmo-pedido', file('segundo'));
    expect(dirname(a)).not.toBe(dirname(b));
    expect(readFileSync(a, 'utf8')).toBe('primeiro');
    expect(readFileSync(b, 'utf8')).toBe('segundo');
  });

  it.skipIf(!HAS_POSIX_MODES)('preserva permissões da raiz configurada preexistente', () => {
    const root = tempDir().dir;
    chmodSync(root, 0o750);
    const [path] = writeAttachments(root, 'permissões', [{ name: 'nota.txt', type: 'text/plain', data: Buffer.from('ok') }]);
    expect(statSync(root).mode & 0o777).toBe(0o750);
    expect(statSync(dirname(path)).mode & 0o777).toBe(0o700);
    expect(statSync(path).mode & 0o777).toBe(0o600);
    expect(readFileSync(path, 'utf8')).toBe('ok');
  });

  it('falha de disco remove só a pasta da própria tentativa', () => {
    const root = tempDir().dir;
    const file = { name: 'old', type: 'text/plain', data: Buffer.from('preservado') };
    const [old] = writeAttachments(root, 'old', [file]);
    expect(() => writeAttachments(root, 'bad', [file, { ...file, data: null as unknown as Buffer }])).toThrow();
    expect(readdirSync(root)).toEqual([dirname(old).split('/').pop()]);
    expect(readFileSync(old, 'utf8')).toBe('preservado');
  });
});
