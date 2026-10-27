import { readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FileFetcher, HOST_PRESENCE_MS, WAIT_MS } from './fetcher';
import { ATTACHMENT_MAX_BYTES } from '../spawn/attachments';
import { tempDir } from '../test/fixtures';

const ok = (p: string) => ({ path: p, size: 3 });
const files = [{ name: '../olá.txt', type: 'text/plain', data: Buffer.from('bytes intactos') }];
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllEnvs();
});

describe('FileFetcher', () => {
  it('Node: lê direto do disco, com a checagem', async () => {
    const f = new FileFetcher({ direct: true, check: ok, read: () => Buffer.from('abc') });
    expect(await f.get('/a/x.png')).toEqual({ data: Buffer.from('abc') });
    const denied = new FileFetcher({ direct: true, check: () => ({ error: 'arquivo protegido' }) });
    expect(await denied.get('/a/.ssh/x.png')).toEqual({ error: 'arquivo protegido', status: 403 });
  });

  it('Docker: pasta montada lê aqui; o resto vai pelo auxiliar (poll → ack) e fica no cache', async () => {
    let now = 1_000;
    const reads: string[] = [];
    const f = new FileFetcher({
      now: () => now,
      check: ok,
      read: (p) => (reads.push(p), Buffer.from('m')),
      mapHostPath: (p) => (p.startsWith('/Users/a/.claude/') ? `/claude${p.slice('/Users/a/.claude'.length)}` : undefined),
    });
    expect(await f.get('/Users/a/.claude/projects/x/t.jpg')).toEqual({ data: Buffer.from('m') });
    expect(reads).toEqual(['/claude/projects/x/t.jpg']);
    expect(await f.get('/tmp/x.png')).toMatchObject({ status: 503 });
    f.poll();
    const pending = f.get('/tmp/x.png');
    const [req] = f.poll();
    expect(req.path).toBe('/tmp/x.png');
    f.ack({ results: [{ id: req.id, ok: true, data: Buffer.from('png').toString('base64') }] });
    expect(await pending).toEqual({ data: Buffer.from('png') });
    now += HOST_PRESENCE_MS + 1;
    expect(await f.get('/tmp/x.png')).toEqual({ data: Buffer.from('png') });
  });

  it('auxiliar recusa: o erro dele chega ao navegador', async () => {
    const f = new FileFetcher({ check: ok });
    f.poll();
    const pending = f.get('/tmp/y.png');
    const [req] = f.poll();
    f.ack({ results: [{ id: req.id, ok: false, error: 'arquivo protegido' }] });
    expect(await pending).toEqual({ error: 'arquivo protegido', status: 404 });
  });

  it('rodada com espera (?wait=1): o auxiliar fica parado até a página pedir um arquivo, e conta como presente', async () => {
    const f = new FileFetcher({ mapHostPath: () => undefined });
    expect(f.hostAvailable()).toBe(false);
    const round = f.pollWait(60_000);
    expect(f.hostAvailable()).toBe(true);
    const got = f.get('/proj/a.png');
    const list = await round;
    expect(list).toEqual([{ id: expect.any(String), path: '/proj/a.png' }]);
    f.ack({ results: [{ id: list[0].id, ok: true, data: Buffer.from('png').toString('base64') }] });
    await expect(got).resolves.toEqual({ data: Buffer.from('png') });
  });

  it('grava diretamente em lotes distintos, com bytes e permissões privados; erro de disco não entrega caminhos', async () => {
    const tmp = tempDir();
    try {
      vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', join(tmp.dir, 'attachments'));
      const f = new FileFetcher({ direct: true });
      const a = await f.writeAttachments(files);
      const b = await f.writeAttachments(files);
      expect(a).toHaveProperty('paths');
      expect(b).toHaveProperty('paths');
      if (!('paths' in a) || !('paths' in b)) throw new Error('gravação falhou');
      expect(isAbsolute(a.paths[0])).toBe(true);
      expect(a.paths[0]).not.toBe(b.paths[0]);
      expect(readFileSync(a.paths[0])).toEqual(files[0].data);
      expect(statSync(a.paths[0]).mode & 0o777).toBe(0o600);
      expect(statSync(dirname(a.paths[0])).mode & 0o777).toBe(0o700);
      const blocked = join(tmp.dir, 'blocked');
      writeFileSync(blocked, 'não é pasta');
      vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', blocked);
      expect(await f.writeAttachments(files)).toMatchObject({ status: 500, error: expect.any(String) });
    } finally {
      tmp.cleanup();
    }
  });

  it('host escreve pela fila compartilhada, ignora ack falso e preserva leituras antigas', async () => {
    const f = new FileFetcher();
    const round = f.pollWait(60_000);
    const pending = f.writeAttachments(files);
    const [request] = await round;
    expect(request).toMatchObject({ op: 'writeAttachments', files: [{ name: '../olá.txt', data: files[0].data.toString('base64') }] });
    expect(f.poll()).toEqual([]);
    let settled = false;
    void pending.then(() => { settled = true; });
    for (const paths of [[], ['relative.txt'], ['/ok/a', '/ok/b']]) {
      f.ack({ results: [{ id: request.id, ok: true, paths }] });
    }
    f.ack({ results: [{ id: request.id, ok: true, data: 'eA==' }] });
    await Promise.resolve();
    expect(settled).toBe(false);
    f.ack({ results: [{ id: request.id, ok: true, paths: ['/host/attachments/a.txt'] }] });
    expect(await pending).toEqual({ paths: ['/host/attachments/a.txt'] });
    const read = f.get('/tmp/a.png');
    const [legacy] = f.poll();
    expect(legacy).toEqual({ id: expect.any(String), path: '/tmp/a.png' });
    f.ack({ results: [{ id: legacy.id, ok: true, data: 'eA==' }] });
    expect(await read).toEqual({ data: Buffer.from('x') });
  });

  it('recusa ausência, limites e fila cheia; timeout e falha liberam a fila', async () => {
    vi.useFakeTimers();
    const f = new FileFetcher();
    expect(await f.writeAttachments(files)).toMatchObject({ status: 503 });
    expect(await f.writeAttachments(Array(6).fill(files[0]))).toMatchObject({ status: 400 });
    expect(await f.writeAttachments([{ ...files[0], data: Buffer.alloc(ATTACHMENT_MAX_BYTES + 1) }])).toMatchObject({ status: 400 });
    f.poll();
    const pending = Array.from({ length: 16 }, () => f.writeAttachments(files));
    expect(await f.writeAttachments(files)).toMatchObject({ status: 429 });
    const requests = f.poll();
    f.ack({ results: [{ id: requests[0].id, ok: false, error: 'disco cheio' }] });
    expect(await pending[0]).toEqual({ error: 'disco cheio', status: 500 });
    await vi.advanceTimersByTimeAsync(WAIT_MS);
    expect(await Promise.all(pending.slice(1))).toEqual(Array(15).fill({ error: 'o computador não respondeu a tempo', status: 504 }));
    f.poll();
    const retry = f.writeAttachments(files);
    const [request] = f.poll();
    f.ack({ results: [{ id: request.id, ok: true, paths: ['/host/retry.txt'] }] });
    expect(await retry).toEqual({ paths: ['/host/retry.txt'] });
  });

  it('limita bytes pendentes e libera buffers quando o host não confirma', async () => {
    vi.useFakeTimers();
    const f = new FileFetcher();
    f.poll();
    const big = [{ ...files[0], data: Buffer.alloc(ATTACHMENT_MAX_BYTES) }];
    const pending = Array.from({ length: 8 }, () => f.writeAttachments(big));
    expect(await f.writeAttachments(big)).toMatchObject({ status: 429 });
    await vi.advanceTimersByTimeAsync(WAIT_MS);
    expect(await Promise.all(pending)).toEqual(Array(8).fill({ error: 'o computador não respondeu a tempo', status: 504 }));
    f.poll();
    const retry = f.writeAttachments(files);
    const [request] = f.poll();
    f.ack({ results: [{ id: request.id, ok: true, paths: ['/host/retry.txt'] }] });
    expect(await retry).toEqual({ paths: ['/host/retry.txt'] });
  });
});
