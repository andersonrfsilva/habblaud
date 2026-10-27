import { describe, expect, it, vi } from 'vitest';
import { ago, formatBytes, shortPath, SpawnPanel, spawnFolders } from './spawn';
import { uploadAttachment } from '../net/store';
import { insertDictation, pickMimeType } from './dictation';

describe('spawnFolders', () => {
  it('pastas das salas, a mais recente primeiro, sem as do demo nem caminhos relativos', () => {
    const rooms = [
      { id: 'r1', path: '/Users/a/antigo' },
      { id: 'r2', path: '/Users/a/novo' },
      { id: 'demo:x', path: '/demo/x' },
      { id: 'r3', path: 'relativo' },
      { id: 'r4', path: 'C:\\code\\win' },
    ];
    const agents = [
      { roomId: 'r1', lastEventAt: 10 },
      { roomId: 'r2', lastEventAt: 50 },
      { roomId: 'r4', lastEventAt: 20 },
    ];
    expect(spawnFolders({ rooms, agents } as never)).toEqual(['/Users/a/novo', 'C:\\code\\win', '/Users/a/antigo']);
    expect(spawnFolders(null)).toEqual([]);
  });
});

describe('Novo agente: lista de projetos e anexos', () => {
  it('caminho curto com ~ e tempo relativo', () => {
    expect(shortPath('/Users/ana/Coding/proj')).toBe('~/Coding/proj');
    expect(shortPath('/home/ana/proj')).toBe('~/proj');
    expect(shortPath('/opt/x')).toBe('/opt/x');
    expect(shortPath('C:\\Users\\ana\\proj')).toBe('~\\proj');
    const now = 10_000_000;
    expect([ago(0, now), ago(now - 10_000, now), ago(now - 5 * 60_000, now), ago(now - 3 * 3_600_000, now), ago(now - 72 * 3_600_000, now)]).toEqual(['', 'agora', '5 min', '3 h', '3 d']);
    expect([formatBytes(900), formatBytes(2_048), formatBytes(3_500_000)]).toEqual(['900 B', '2 KB', '3,3 MB']);
  });
});

describe('ditado no campo', () => {
  it('insere no cursor com espaço certo dos dois lados', () => {
    expect(insertDictation('', 0, 0, ' Crie um agente ')).toEqual({ value: 'Crie um agente', caret: 14 });
    expect(insertDictation('Revise', 6, 6, 'o relatório')).toEqual({ value: 'Revise o relatório', caret: 18 });
    expect(insertDictation('ab', 1, 1, 'X')).toEqual({ value: 'a X b', caret: 3 });
    expect(insertDictation('troque ISTO aqui', 7, 11, 'isso')).toEqual({ value: 'troque isso aqui', caret: 11 });
    expect(insertDictation('igual', 5, 5, '   ')).toEqual({ value: 'igual', caret: 5 });
  });
  it('escolhe o formato que o navegador grava (mp4 no iPhone, webm no Chrome)', () => {
    expect(pickMimeType((t) => t === 'audio/mp4')).toBe('audio/mp4');
    expect(pickMimeType((t) => t.startsWith('audio/webm'))).toBe('audio/webm;codecs=opus');
    expect(pickMimeType(() => false)).toBeUndefined();
    expect(pickMimeType(() => { throw new Error('x'); })).toBeUndefined();
  });
});

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  // The browser's ES2023 target does not include Promise.withResolvers.
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

async function withUploadTransport(handler: (request: Request) => Promise<Response>, run: () => Promise<void>): Promise<void> {
  vi.stubGlobal('fetch', (path: string, init?: RequestInit) => handler(new Request(new URL(path, 'http://localhost'), init)));
  try {
    await run();
  } finally {
    vi.unstubAllGlobals();
  }
}

describe('upload binário compartilhado', () => {
  it('envia bytes e nome Unicode pelos dois endpoints; mantém erro HTTP observável', async () => {
    const bytes = new Uint8Array([0, 1, 255, 42]);
    const seen: string[] = [];
    await withUploadTransport(async (request) => {
      expect(new Uint8Array(await request.arrayBuffer())).toEqual(bytes);
      expect(request.method).toBe('POST');
      expect(request.headers.get('Content-Type')).toBe('application/octet-stream');
      expect(request.headers.get('X-Habblaud-File-Name')).toBe(encodeURIComponent('ação 🐈.png'));
      expect(request.headers.get('X-Habblaud-File-Type')).toBe('image/png');
      seen.push(new URL(request.url).pathname);
      const refused = seen.length === 3;
      return Response.json(refused ? { error: 'too large' } : { id: `id-${seen.length}`, name: 'ação 🐈.png', size: bytes.length }, { status: refused ? 413 : 201 });
    }, async () => {
      const file = new File([bytes], 'ação 🐈.png', { type: 'image/png' });
      expect(await uploadAttachment('/api/messages/attachments', file)).toEqual({ id: 'id-1' });
      expect(await uploadAttachment('/api/spawn/attachments', file)).toEqual({ id: 'id-2' });
      expect(await uploadAttachment('/api/spawn/attachments', file)).toEqual({ error: 'too large' });
    });
    expect(seen).toEqual(['/api/messages/attachments', '/api/spawn/attachments', '/api/spawn/attachments']);
  });
});

interface TestPendingFile {
  file: File;
  id?: string;
  el: { remove(): void };
}

interface TestSpawnPanel {
  submit(): Promise<void>;
  files: TestPendingFile[];
  busy: boolean;
  selected: string;
  prompt: { value: string };
}

/** Exercise the real submit method without a DOM; Request bodies still contain the selected File bytes. */
function submitPanel(files: TestPendingFile[]): TestSpawnPanel {
  return Object.assign(Object.create(SpawnPanel.prototype), {
    files, busy: false, selected: '/original', typing: false,
    prompt: { value: 'pedido original' },
    dictate: { busy: false }, submitBtn: { disabled: false },
    status: { textContent: '', classList: { toggle() {} } },
    ctx: { announce() {} },
  });
}

describe('captura e reuso do lote do Novo agente', () => {
  it('mantém pasta/prompt/lote capturados durante await e conserva arquivos adicionados depois', async () => {
    const started = deferred();
    const resume = deferred();
    const first = { file: new File(['first'], 'original.txt'), el: { remove: vi.fn() } };
    const later = { file: new File(['later'], 'depois.txt'), el: { remove: vi.fn() } };
    const panel = submitPanel([first]);
    let uploads = 0;
    let submittedRequest: unknown;
    await withUploadTransport(async (request) => {
      if (new URL(request.url).pathname === '/api/spawn/attachments') {
        uploads++;
        expect(await request.text()).toBe('first');
        started.resolve();
        await resume.promise;
        return Response.json({ id: 'first-id' }, { status: 201 });
      }
      submittedRequest = await request.json();
      return Response.json({ id: 'spawn', status: 'opened', how: 'cli' }, { status: 201 });
    }, async () => {
      const submitted = panel.submit();
      await started.promise;
      panel.files.push(later);
      panel.prompt.value = 'rascunho posterior';
      panel.selected = '/depois';
      resume.resolve();
      await submitted;
    });
    expect(submittedRequest).toEqual({ folder: '/original', prompt: 'pedido original', attachments: ['first-id'] });
    expect(uploads).toBe(1);
    expect(panel.files).toEqual([later]);
    expect(panel.prompt.value).toBe('rascunho posterior');
    expect(first.el.remove).toHaveBeenCalledOnce();
    expect(later.el.remove).not.toHaveBeenCalled();
  });

  it('reusa IDs na recusa anterior à aceitação e reenvia após expiração', async () => {
    const file: TestPendingFile = { file: new File(['x'], 'x.txt'), el: { remove() {} } };
    const panel = submitPanel([file]);
    let uploads = 0;
    let submissions = 0;
    await withUploadTransport(async (request) => {
      if (new URL(request.url).pathname === '/api/spawn/attachments') {
        expect(await request.text()).toBe('x');
        uploads++;
        return Response.json({ id: `upload-${uploads}` }, { status: 201 });
      }
      submissions++;
      if (submissions === 1) return Response.json({ error: 'refused' }, { status: 403 });
      if (submissions === 2) return Response.json({ error: 'expired', attachmentsExpired: true }, { status: 400 });
      return Response.json({ id: 'spawn', status: 'opened', how: 'cli' }, { status: 201 });
    }, async () => {
      await panel.submit();
      expect(file.id).toBe('upload-1');
      await panel.submit();
      expect(uploads).toBe(1);
      expect(file.id).toBeUndefined();
      await panel.submit();
    });
    expect(uploads).toBe(2);
    expect(panel.files).toEqual([]);
  });
});
