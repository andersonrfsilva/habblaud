// Auxiliar das mensagens ao Codex no Docker (scripts/codex-bridge.ts) contra as rotas de verdade (guard, app e
// /api/codex/bridge/*): a trava das mensagens (recurso ligado + Host local), a rodada completa página → auxiliar →
// codex queue (binário falso) → confirmação, e a conferência de cada mensagem antes de rodar o comando.
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import type { AddressInfo } from 'node:net';
import { basename, dirname, isAbsolute, join, sep } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { castRound, checkMessage, cleanupAttachmentStaging, dictationRound, downloadAttachments, filesRound, httpAttachmentDownload, httpPost, ompRoles, parseArgs, round, spawnRound, type OmpRolesIO, type Post } from '../../scripts/codex-bridge';
import { spawnUrl } from '../spawn/spawn';
import { AccountsService } from '../accounts/service';
import { createApiHandler } from '../http/app';
import { createRequestGuard } from '../http/guard';
import { Hub } from '../http/sse';
import { setQuiet } from '../log';
import { createCodexQueueRunner } from '../messages/codex';
import { createMessageRoutes } from '../messages/http';
import { MessageRegistry } from '../messages/registry';
import { NameStore } from '../model/names';
import { Office } from '../model/office';
import { FAKE_CODEX_RUNS, fakeCodexCalls, writeFakeCodex } from './fake-codex';
import { HAS_POSIX_MODES, tempDir } from './fixtures';
import { request } from './permission-server';
import { FileFetcher } from '../deliveries/fetcher';
import { ATTACHMENT_MAX_BYTES } from '../spawn/attachments';

// Um `omp` de mentira em script com shebang e sinais POSIX: no Windows não há como executar o script nem um SIGTERM
// que o processo receba (o kill encerra na hora).
const UNIX = process.platform !== 'win32';

setQuiet(true);

const THREAD = '0199b0c0-1234-7abc-8def-0123456789ab';
const MAIN = `.codex:${THREAD}`;

let tmp: ReturnType<typeof tempDir>;
let codexHome: string;
let close: (() => Promise<void>) | undefined;
beforeEach(() => {
  tmp = tempDir();
  codexHome = join(tmp.dir, '.codex');
  mkdirSync(join(codexHome, 'sessions', '2026'), { recursive: true });
});
afterEach(async () => {
  vi.unstubAllEnvs();
  await close?.();
  close = undefined;
  tmp.cleanup();
});

describe('rodada do elenco', () => {
  it('executa tmux sem shell, com porta e argumentos literais, e recusa pedidos inválidos', async () => {
    for (const key of ['PUPPETEER_EXECUTABLE_PATH', 'MOLI_BIN', 'MOLI_CHROME_HEADED']) vi.stubEnv(key, undefined);
    const castDir = join(tmp.dir, 'cast');
    mkdirSync(castDir);
    const instructionsFile = join(castDir, 'ana.md');
    writeFileSync(instructionsFile, 'instruções');
    const base = { id: 'one', op: 'start', cast: 'ana', folder: tmp.dir, agentDir: codexHome, model: 'provider/model', instructionsFile, resume: '01a18526-af3e-72e1-8a15-121c40de7583', prompt: 'olá $(literal)' };
    const jobs = [base, { ...base, id: 'bad-id', cast: 'bad/id' }, { ...base, id: 'bad-folder', folder: join(tmp.dir, 'missing') }, { ...base, id: 'bad-file', instructionsFile: join(tmp.dir, 'outside') }];
    writeFileSync(join(tmp.dir, 'outside'), 'x');
    const ack: unknown[] = [];
    const post: Post = async (path, body) => {
      if (path.endsWith('/ack')) { ack.push(body); return { status: 200, json: {} }; }
      return { status: 200, json: { jobs } };
    };
    const calls: string[][] = [];
    const run = async (_file: string, args: string[]) => {
      calls.push(args);
      if (args[0] === 'has-session') throw Object.assign(new Error('not found'), { code: 1 });
    };
    const result = await castRound(post, run, () => {}, { port: 12345, dataDir: tmp.dir, omp: '/bin/omp', tmux: '/bin/tmux' });
    expect(result.state).toBe('ok');
    expect(calls).toContainEqual(['new-session', '-d', '-s', 'hb-ana', '-c', tmp.dir, '-e', 'HABBLAUD_CAST=ana', '-e', `PI_CODING_AGENT_DIR=${codexHome}`, '-e', 'HABBLAUD_PORT=12345', '--', '/bin/omp', '--model', 'provider/model', '--append-system-prompt', instructionsFile, '--resume', '01a18526-af3e-72e1-8a15-121c40de7583', 'olá $(literal)']);
    expect(ack).toEqual([{ results: [expect.objectContaining({ id: 'one', ok: true }), expect.objectContaining({ id: 'bad-id', ok: false }), expect.objectContaining({ id: 'bad-folder', ok: false }), expect.objectContaining({ id: 'bad-file', ok: false })] }]);
  });
  it.each([undefined, 'relative/moli-chrome', '/abs/moli-chrome'])('passa apenas caminhos absolutos do navegador (%s)', async (value) => {
    vi.stubEnv('PUPPETEER_EXECUTABLE_PATH', value);
    vi.stubEnv('MOLI_BIN', '/abs/moli');
    vi.stubEnv('MOLI_CHROME_HEADED', 'relative/chromium');
    const post: Post = async (path) => ({ status: 200, json: path.endsWith('/poll') ? { jobs: [{ id: 'a', op: 'start', cast: 'ana', folder: tmp.dir, agentDir: codexHome }] } : {} });
    const calls: string[][] = [];
    await castRound(post, async (_file, args) => {
      calls.push(args);
      if (args[0] === 'has-session') throw Object.assign(new Error('not found'), { code: 1 });
    }, () => {}, { port: 12345, dataDir: tmp.dir, omp: '/bin/omp', tmux: '/bin/tmux' });
    const args = calls.find((call) => call[0] === 'new-session')!;
    const separator = args.indexOf('--');
    expect(args.slice(0, separator)).toContain('MOLI_BIN=/abs/moli');
    expect(args).not.toContain('MOLI_CHROME_HEADED=relative/chromium');
    if (value?.startsWith('/')) expect(args.slice(0, separator)).toContain(`PUPPETEER_EXECUTABLE_PATH=${value}`);
    else expect(args.some((arg) => arg.startsWith('PUPPETEER_EXECUTABLE_PATH='))).toBe(false);
  });
  it('recusa opções em prompt, modelo ou sessão antes de executar qualquer comando', async () => {
    const base = { op: 'start', cast: 'ana', folder: tmp.dir, agentDir: codexHome };
    const jobs = [
      { ...base, id: 'a', prompt: '-e x' }, { ...base, id: 'b', prompt: ' --x' },
      { ...base, id: 'c', model: '--no-session' }, { ...base, id: 'd', resume: '../session' },
    ];
    const post: Post = async (path) => ({ status: 200, json: path.endsWith('/poll') ? { jobs } : {} });
    const run = vi.fn(async () => {});
    const result = await castRound(post, run, () => {}, { port: 12345, dataDir: tmp.dir, omp: '/bin/omp', tmux: '/bin/tmux' });
    expect(result).toMatchObject({ state: 'ok', results: jobs.map(({ id }) => ({ id, ok: false })) });
    expect(run).not.toHaveBeenCalled();
  });
  it('recusa start duplicado e aceita stop de sessão inexistente', async () => {
    const post: Post = async (path) => ({ status: 200, json: path.endsWith('/poll') ? { jobs: [{ id: 'a', op: 'start', cast: 'ana', folder: tmp.dir, agentDir: codexHome }, { id: 'b', op: 'stop', cast: 'bia' }] } : {} });
    const run = async (_file: string, args: string[]) => { if (args[2] === '=hb-bia') throw Object.assign(new Error('not found'), { code: 1 }); };
    const result = await castRound(post, run, () => {}, { port: 12345, dataDir: tmp.dir, omp: '/bin/omp', tmux: '/bin/tmux' });
    expect(result).toMatchObject({ state: 'ok', results: [{ id: 'a', ok: false }, { id: 'b', ok: true }] });
  });
  it('manda junto do pedido as sessões hb-* abertas (o servidor reconstrói o Dispensar depois de reiniciar)', async () => {
    const bodies: unknown[] = [];
    const post: Post = async (path, body) => { bodies.push({ path, body }); return { status: 200, json: path.endsWith('/poll') ? { jobs: [] } : {} }; };
    await castRound(post, async () => {}, () => {}, { port: 12345, dataDir: tmp.dir, tmux: '/bin/tmux', list: async () => ['melissa', 'giovana'] });
    expect(bodies[0]).toEqual({ path: '/api/host/cast/poll', body: { open: ['melissa', 'giovana'] } });
  });
  it('papéis: grava só papéis editáveis válidos, relê a conta do servidor e manda no próximo poll', async () => {
    const writes: unknown[] = [];
    let current: Record<string, string> | undefined;
    const roles: OmpRolesIO = { report: () => current, refresh: async (dir) => { current = { task: `lido de ${dir}` }; }, write: async (dir, r) => { writes.push({ dir, r }); } };
    const jobs = [
      { id: 'ok', op: 'roles', cast: '', agentDir: codexHome, roles: { default: 'anthropic/claude-opus-5-5:high', task: 'openai-codex/gpt-6-luna:high', plan: null } },
      { id: 'role', op: 'roles', cast: '', agentDir: codexHome, roles: { smol: 'a/b' } },
      { id: 'value', op: 'roles', cast: '', agentDir: codexHome, roles: { task: '--x' } },
      { id: 'dir', op: 'roles', cast: '', agentDir: join(tmp.dir, 'missing'), roles: { task: 'a/b' } },
    ];
    const bodies: unknown[] = [];
    const post: Post = async (path, body) => { bodies.push(body); return { status: 200, json: path.endsWith('/poll') ? { jobs, rolesDir: codexHome } : {} }; };
    const result = await castRound(post, async () => {}, () => {}, { port: 12345, dataDir: tmp.dir, tmux: '/bin/tmux', roles });
    expect(result).toMatchObject({ results: [{ id: 'ok', ok: true }, { id: 'role', ok: false }, { id: 'value', ok: false }, { id: 'dir', ok: false }] });
    expect(writes).toEqual([{ dir: codexHome, r: { default: 'anthropic/claude-opus-5-5:high', task: 'openai-codex/gpt-6-luna:high', plan: null } }]);
    expect(bodies[0]).toEqual({});
    await castRound(async (path, body) => { bodies.push(body); return { status: 200, json: path.endsWith('/poll') ? { jobs: [] } : {} }; }, async () => {}, () => {}, { port: 12345, dataDir: tmp.dir, roles });
    expect(bodies.at(-1)).toEqual({ roles: { task: `lido de ${codexHome}` } });
  });
  it.runIf(UNIX)('ompRoles: junta ao modelRoles atual, apaga com null e grava o mapa inteiro pela conta pedida', async () => {
    const log = join(tmp.dir, 'omp.log');
    const omp = join(tmp.dir, 'omp');
    writeFileSync(omp, `#!/bin/sh\necho "$PI_CODING_AGENT_DIR $*" >> '${log}'\n[ "$2" = get ] && echo '{"key":"modelRoles","value":{"default":"a/b","plan":"c/d","task":"e/f"}}'\nexit 0\n`, { mode: 0o755 });
    await ompRoles(omp).write(codexHome, { task: 'x/y:high', plan: null, advisor: 'z/w' });
    const lines = readFileSync(log, 'utf8').trim().split('\n');
    expect(lines[0]).toBe(`${codexHome} config get modelRoles --json`);
    expect(lines[1]).toBe(`${codexHome} config set modelRoles {"default":"a/b","task":"x/y:high","advisor":"z/w"}`);
  });
});

/** Office + Hub + rotas de verdade, com um agente do Codex e o registro das mensagens SEM o modo Node (como no Docker). */
async function serve(enabled = true, files?: FileFetcher): Promise<{ base: string; port: number; office: Office; registry?: MessageRegistry }> {
  const late: { registry?: MessageRegistry } = {};
  const office = new Office({
    names: new NameStore(null),
    version: 't',
    startedAt: Date.now(),
    accounts: () => [],
    sources: () => [],
    accountName: () => undefined,
    terminal: true,
    messages: enabled ? () => late.registry?.reachable() ?? new Set() : undefined,
  });
  const hub = new Hub(office, { throttleMs: 10 });
  const registry = enabled ? new MessageRegistry({ office, codex: { homeOf: () => codexHome }, tickMs: 20 }) : undefined;
  late.registry = registry;
  registry?.start();
  const accounts = new AccountsService({ dirs: [], home: tmp.dir, env: {}, onChange: () => {} });
  const api = createApiHandler({ office, hub, accounts, sources: () => [], version: 't', inDocker: true, terminal: true, files, messages: registry ? createMessageRoutes(registry) : undefined });
  const guard = createRequestGuard({ allowedHosts: new Set(['habblaud.lan']) });
  const server = http.createServer((req, res) => {
    if (guard(req, res)) return;
    if (!api(req, res, new URL(req.url ?? '/', 'http://x'))) res.writeHead(404).end();
  });
  await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok));
  const port = (server.address() as AddressInfo).port;
  office.addMain({ id: MAIN, provider: 'codex', account: '.codex', sessionId: THREAD, cwd: '/p/loja', role: 'Agente principal', startedAt: Date.now(), status: 'idle' });
  close = () =>
    new Promise((ok) => {
      registry?.stop();
      hub.stop();
      server.closeAllConnections();
      server.close(() => ok());
    });
  return { base: `http://127.0.0.1:${port}`, port, office, registry };
}

describe('rotas do auxiliar do Codex', () => {
  it('seguem a trava das mensagens: desligadas = 403; Host que não é local = 403; GET = 405; sem JSON = 415', async () => {
    let s = await serve(false);
    expect((await request(s.base, '/api/codex/bridge/poll', { method: 'POST', body: {} })).status).toBe(403);
    await close!();
    s = await serve();
    expect((await request(s.base, '/api/codex/bridge/poll', { method: 'POST', headers: { Host: 'habblaud.lan' }, body: {} })).status).toBe(403);
    expect((await request(s.base, '/api/codex/bridge/ack')).status).toBe(405);
    expect((await request(s.base, '/api/codex/bridge/poll', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: '{}' })).status).toBe(415);
    expect((await request(s.base, '/api/codex/bridge/ack', { method: 'POST', body: { results: 'x' } })).status).toBe(400);
    expect(await request(s.base, '/api/codex/bridge/poll', { method: 'POST', body: {} })).toMatchObject({ status: 200, json: { messages: [] } });
  });

  it.runIf(FAKE_CODEX_RUNS)('fluxo: o auxiliar conecta (canMessage) → página manda → rodada roda o codex queue → confirma → delivered', async () => {
    const s = await serve();
    const bin = writeFakeCodex(tmp.dir);
    const log = join(tmp.dir, 'chamadas.log');
    const run = createCodexQueueRunner(bin, { env: { PATH: process.env.PATH, FAKE_CODEX_LOG: log } });
    const post = httpPost(s.port);
    // Antes do auxiliar: o Codex não recebe.
    expect((await request(s.base, '/api/messages', { method: 'POST', body: { agentId: MAIN, text: 'oi' } })).status).toBe(409);
    expect(await round(post, run)).toEqual({ state: 'ok', results: [] });
    expect(s.office.commit().snapshot.agents.find((a) => a.id === MAIN)!.canMessage).toBe(true);
    const r = await request(s.base, '/api/messages', { method: 'POST', body: { agentId: MAIN, text: '--message=x e o resto' } });
    expect(r.status).toBe(201);
    const id = (r.json as { id: string }).id;
    const lines: string[] = [];
    expect(await round(post, run, (l) => lines.push(l))).toEqual({ state: 'ok', results: [{ id, ok: true }] });
    expect(fakeCodexCalls(log)).toEqual([{ args: ['queue', `--thread=${THREAD}`, '--message=--message=x e o resto'], codexHome }]);
    expect((await request(s.base, `/api/messages/${id}`)).json).toMatchObject({ status: 'delivered' });
    // O texto nunca vai para a tela do auxiliar.
    expect(lines.join('\n')).not.toContain('o resto');
  });

  it('Habblaud fora do ar ou recusando: a rodada só informa (nada roda)', async () => {
    const run = async () => ({ ok: true as const });
    expect(await round(httpPost(1), run)).toEqual({ state: 'down' });
    const s = await serve(false);
    expect(await round(httpPost(s.port), run)).toMatchObject({ state: 'refused', status: 403 });
  });

  it('confirma cada resultado antes de rodar a próxima mensagem do lote', async () => {
    const s = await serve();
    const thread = '0199b0c0-0000-7abc-8def-0123456789ab';
    const other = `.codex:${thread}`;
    s.office.addMain({ id: other, provider: 'codex', account: '.codex', sessionId: thread, cwd: '/p/api', role: 'Agente principal', startedAt: Date.now(), status: 'idle' });
    const post = httpPost(s.port);
    await round(post, async () => ({ ok: true }));
    const a = await request(s.base, '/api/messages', { method: 'POST', body: { agentId: MAIN, text: 'um' } });
    const b = await request(s.base, '/api/messages', { method: 'POST', body: { agentId: other, text: 'dois' } });
    const idA = (a.json as { id: string }).id;
    const idB = (b.json as { id: string }).id;
    const before: string[] = [];
    const result = await round(post, async (job) => {
      if (job.thread === thread) {
        const message = await request(s.base, `/api/messages/${idA}`);
        before.push((message.json as { status: string }).status);
        return { ok: false, error: 'Error: recusado' };
      }
      return { ok: true };
    });
    expect(before).toEqual(['delivered']);
    expect(result).toEqual({ state: 'ok', results: [{ id: idA, ok: true }, { id: idB, ok: false, error: 'Error: recusado' }] });
    expect((await request(s.base, `/api/messages/${idB}`)).json).toMatchObject({ status: 'failed', error: 'Error: recusado' });
  });

  it('checkMessage: thread, texto e uma pasta do Codex que existe (nunca uma do Claude Code)', () => {
    const ok = { id: 'm1', account: '.codex', codexHome, thread: THREAD, text: 'oi' };
    expect(checkMessage(ok)).toEqual(ok);
    expect(checkMessage({ ...ok, thread: 'nome' })).toEqual({ id: 'm1', error: 'id de thread do Codex inválido' });
    expect(checkMessage({ ...ok, text: ' ' })).toEqual({ id: 'm1', error: 'mensagem vazia' });
    expect(checkMessage({ ...ok, codexHome: join(tmp.dir, 'nao-existe') })).toMatchObject({ id: 'm1', error: expect.stringMatching(/não existe/) });
    expect(checkMessage({ ...ok, codexHome: 'relativo' })).toMatchObject({ error: expect.stringMatching(/não existe/) });
    const claude = join(tmp.dir, '.claude');
    mkdirSync(join(claude, 'projects'), { recursive: true });
    expect(checkMessage({ ...ok, codexHome: claude })).toMatchObject({ error: expect.stringMatching(/não existe/) });
    expect(checkMessage({ text: 'x' })).toEqual({ error: 'mensagem sem id' });
  });

  it('parseArgs', () => {
    expect(parseArgs([], {})).toEqual({ port: 4747, once: false, onlyCast: false });
    expect(parseArgs(['--port', '4851', '--once', '--only-cast'], {})).toEqual({ port: 4851, once: true, onlyCast: true });
    expect(parseArgs([], { HABBLAUD_PORT: '4848' })).toEqual({ port: 4848, once: false, onlyCast: false });
    expect(parseArgs(['-h'], {})).toBe('help');
    expect(() => parseArgs(['--port', 'x'], {})).toThrow(/--port/);
    expect(() => parseArgs(['--xyz'], {})).toThrow(/desconhecida/);
  });
});

describe('auxiliar somente para elenco', () => {
  it('mostra a opção na ajuda', () => {
    const result = spawnSync(process.execPath, ['--import', 'tsx', 'scripts/codex-bridge.ts', '--help'], { encoding: 'utf8' });
    expect(result.status).toBe(0);
    expect(result.stdout).toContain('--only-cast');
  });

  it.each([200, 403])('consulta apenas o elenco e retorna o resultado HTTP %i', async (status) => {
    const paths: string[] = [];
    const { port } = await streamServer((req, res) => {
      paths.push(req.url!);
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(status === 200 ? { jobs: [] } : { error: 'recusado' }));
    });
    const child = spawn(process.execPath, ['--import', 'tsx', 'scripts/codex-bridge.ts', '--only-cast', '--once', '--port', String(port)], { env: { ...process.env, HOME: tmp.dir }, stdio: 'ignore' });
    const [code] = await once(child, 'exit');
    expect(code).toBe(status === 200 ? 0 : 1);
    expect(paths).toEqual(['/api/host/cast/poll']);
  });

  it.runIf(UNIX)('encerra ao receber SIGTERM durante o laço de elenco', async () => {
    const polled = Promise.withResolvers<void>();
    const paths: string[] = [];
    const { port } = await streamServer((req, res) => {
      paths.push(req.url!);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ jobs: [] }), polled.resolve);
    });
    const child = spawn(process.execPath, ['--import', 'tsx', 'scripts/codex-bridge.ts', '--only-cast', '--port', String(port)], { env: { ...process.env, HOME: tmp.dir }, stdio: 'ignore' });
    const exited = once(child, 'exit');
    try {
      await polled.promise;
      child.kill('SIGTERM');
      expect(await exited).toEqual([0, null]);
      expect(paths).toEqual(['/api/host/cast/poll']);
    } finally {
      child.kill('SIGKILL');
    }
  });
});

describe('spawnRound com anexos e dictationRound', () => {
  it('baixa bytes por HTTP antes de abrir; descritor inválido recusa sem gravar', async () => {
    const root = join(tmp.dir, 'spawn-host');
    vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', root);
    const { port } = await streamServer((_req, res) => {
      res.writeHead(200, { 'Content-Length': 3 });
      res.write(Buffer.from([0, 255]));
      res.end(Buffer.from([1]));
    });
    const post: Post = async (path) => path === '/api/host/spawn/poll'
      ? { status: 200, json: { spawns: [
        { id: 'a', folder: '/ok', prompt: 'veja', files: [{ id: 'f', name: '../ação.png', type: 'image/png', size: 3 }] },
        { id: 'b', folder: '/ok', prompt: '', files: [{ name: 'x', data: 'eA==' }] },
      ] } }
      : { status: 200, json: {} };
    const opened: string[] = [];
    const r = await spawnRound(post, async (url) => (opened.push(url), { ok: true }), undefined, () => undefined, httpAttachmentDownload(port));
    expect(r).toMatchObject({ state: 'ok', results: [{ id: 'a', ok: true }, { id: 'b', ok: false }] });
    const prompt = new URL(opened[0]).searchParams.get('q')!;
    const path = prompt.split('\n- ')[1];
    expect(path.startsWith(root + sep)).toBe(true);
    expect(basename(path)).toBe('ação.png');
    expect(readFileSync(path)).toEqual(Buffer.from([0, 255, 1]));
  });

  it('dictationRound transcreve e devolve texto e idioma; erro vira resultado', async () => {
    const calls: Array<[string, unknown]> = [];
    const post: Post = async (path, body) => {
      calls.push([path, body]);
      return path === '/api/host/dictation/poll'
        ? { status: 200, json: { dictations: [{ id: 'd1', type: 'audio/mp4', data: Buffer.from('ok').toString('base64') }, { id: 'd2', type: 'audio/mp4', data: Buffer.from('ruim').toString('base64') }] } }
        : { status: 200, json: { ok: true } };
    };
    const r = await dictationRound(post, async (buf) => {
      if (buf.toString() === 'ruim') throw new Error('o Whisper falhou (x)');
      return { text: 'crie um agente', lang: 'pt' };
    });
    expect(r).toEqual({ state: 'ok', results: [{ id: 'd1', ok: true, text: 'crie um agente', lang: 'pt' }, { id: 'd2', ok: false, error: 'o Whisper falhou (x)' }] });
    expect(calls[1][0]).toBe('/api/host/dictation/ack');
    expect(await dictationRound(async () => null, async () => ({ text: '', lang: '' }))).toEqual({ state: 'down' });
  });
});

describe('filesRound (arquivos das entregas pelo auxiliar)', () => {
  it('lê só o que passa na checagem e devolve base64; recusa com motivo', async () => {
    let acked: unknown;
    const post: Post = async (path, body) => {
      if (path === '/api/host/files/poll') return { status: 200, json: { files: [{ id: 'f1', path: '/ok/a.png' }, { id: 'f2', path: '/Users/a/.ssh/id.png' }] } };
      acked = body;
      return { status: 200, json: { ok: true } };
    };
    const r = await filesRound(post, () => {}, () => Buffer.from('png'), (p) => (p.includes('.ssh') ? { error: 'arquivo protegido' } : { path: p, size: 3 }));
    expect(r).toEqual({ state: 'ok', results: [{ id: 'f1', ok: true }, { id: 'f2', ok: false, error: 'arquivo protegido' }] });
    expect(acked).toEqual({ results: [{ id: 'f1', ok: true, data: Buffer.from('png').toString('base64') }, { id: 'f2', ok: false, error: 'arquivo protegido' }] });
  });

  it('fora das pastas que pode ler (inside): recusa sem ler; com espera, pede a rodada com ?wait=1', async () => {
    const paths: string[] = [];
    let reads = 0;
    const post: Post = async (path) => {
      paths.push(path);
      if (path.startsWith('/api/host/files/poll')) return { status: 200, json: { files: [{ id: 'f1', path: '/Users/a/Documents/extrato.pdf' }] } };
      return { status: 200, json: { ok: true } };
    };
    const r = await filesRound(post, () => {}, () => (reads++, Buffer.from('x')), (p) => ({ path: p, size: 1 }), { inside: (p) => p.startsWith('/proj/'), wait: true });
    expect(r).toEqual({ state: 'ok', results: [{ id: 'f1', ok: false, error: 'fora das pastas de projeto conhecidas' }] });
    expect(reads).toBe(0);
    expect(paths[0]).toBe('/api/host/files/poll?wait=1');
  });

  it('grava pela fila e HTTP reais em raiz do host, ignorando caminhos enviados', async () => {
    const root = join(tmp.dir, 'host-attachments');
    mkdirSync(root, { mode: 0o750 });
    vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', root);
    const fetcher = new FileFetcher();
    const { port } = await serve(true, fetcher);
    fetcher.poll();
    const bytes = Buffer.from([0, 1, 2, 255]);
    const source = join(tmp.dir, 'source');
    writeFileSync(source, bytes);
    const pending = fetcher.writeAttachments([{ id: 'file-a', name: '../imagem.png', type: 'image/png', size: bytes.length, path: source }]);
    const realPost = httpPost(port);
    const post: Post = async (path, body) => {
      const result = await realPost(path, body);
      if (path.startsWith('/api/host/files/poll') && result?.status === 200) {
        const json = result.json as { files: object[] };
        json.files = json.files.map((op) => ({ ...op, root: '/arbitrary', path: '/arbitrary/x' }));
      }
      return result;
    };
    expect(await filesRound(post, undefined, undefined, undefined, { download: httpAttachmentDownload(port) })).toMatchObject({ state: 'ok', results: [{ ok: true }] });
    const result = await pending;
    if (!('paths' in result)) throw new Error(result.error);
    expect(result.paths).toHaveLength(1);
    expect(isAbsolute(result.paths[0])).toBe(true);
    expect(result.paths[0].startsWith(root + sep)).toBe(true);
    expect(basename(result.paths[0])).toBe('imagem.png');
    expect(readFileSync(result.paths[0])).toEqual(bytes);
    if (HAS_POSIX_MODES) {
      expect(statSync(root).mode & 0o777).toBe(0o750);
      expect(statSync(result.paths[0]).mode & 0o777).toBe(0o600);
      expect(statSync(dirname(result.paths[0])).mode & 0o777).toBe(0o700);
    }
    expect(readdirSync(join(root, '.pending'))).toEqual([]);
  });

  it('valida todo o lote antes de gravar: quantidade, IDs, vazio e tamanho', async () => {
    const root = join(tmp.dir, 'invalid-attachments');
    vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', root);
    const valid = { id: 'f', name: '../../safe.txt', type: 'text/plain', size: 1 };
    const invalid = [
      undefined, [], Array(6).fill(valid), [valid, valid],
      [{ ...valid, id: '' }], [{ ...valid, size: 0 }],
      [{ ...valid, size: ATTACHMENT_MAX_BYTES + 1 }],
      [{ name: 'legacy', data: 'eA==' }],
    ];
    let ack: unknown;
    const post: Post = async (path, body) => {
      if (path.startsWith('/api/host/files/poll')) return { status: 200, json: { files: invalid.map((files, i) => ({ id: `bad-${i}`, op: 'writeAttachments', files, root: '/arbitrary' })) } };
      ack = body;
      return { status: 200, json: {} };
    };
    expect(await filesRound(post)).toMatchObject({ state: 'ok', results: invalid.map((_, i) => ({ id: `bad-${i}`, ok: false, error: expect.any(String) })) });
    expect(ack).toMatchObject({ results: invalid.map((_, i) => ({ id: `bad-${i}`, ok: false })) });
    expect(existsSync(root)).toBe(false);
  });

  it('falha de disco devolve erro sem caminhos e não tenta tratar operação desconhecida como leitura', async () => {
    const blocked = join(tmp.dir, 'not-a-directory');
    writeFileSync(blocked, 'blocked');
    vi.stubEnv('HABBLAUD_ATTACHMENTS_DIR', blocked);
    const post: Post = async (path) => path.startsWith('/api/host/files/poll')
      ? { status: 200, json: { files: [
        { id: 'disk', op: 'writeAttachments', files: [{ id: 'f', name: 'a.txt', type: 'text/plain', size: 1 }] },
        { id: 'unknown', op: 'other', path: '/not-read' },
      ] } }
      : { status: 200, json: {} };
    const result = await filesRound(post, () => {}, () => { throw new Error('não deve ler'); });
    expect(result).toMatchObject({ state: 'ok', results: [{ id: 'disk', ok: false, error: expect.any(String) }, { id: 'unknown', ok: false }] });
  });
});

describe('spawnRound (agentes novos pelo auxiliar)', () => {
  it('com o claude: roda em segundo plano; pasta não confiável cai no app e diz por quê', async () => {
    const calls: Array<[string, unknown]> = [];
    const post: Post = async (path, body) => {
      calls.push([path, body]);
      return path === '/api/host/spawn/poll'
        ? { status: 200, json: { spawns: [{ id: 'a', folder: '/conhecida', prompt: 'oi' }, { id: 'b', folder: '/nova', prompt: 'oi' }] } }
        : { status: 200, json: { ok: true } };
    };
    const opened: string[] = [];
    const ran: string[] = [];
    const lines: string[] = [];
    const r = await spawnRound(
      post,
      async (url) => (opened.push(url), { ok: true }),
      (l) => lines.push(l),
      () => undefined,
      undefined,
      async (req) => (ran.push(req.folder), req.folder === '/conhecida' ? { ok: true, id: 'x1' } : { ok: false, error: 'Workspace not trusted. Run `claude` in /nova once' }),
    );
    expect(ran).toEqual(['/conhecida', '/nova']);
    expect(opened).toEqual([spawnUrl({ folder: '/nova', prompt: 'oi' })]);
    expect(r).toMatchObject({ state: 'ok', results: [{ id: 'a', ok: true, how: 'cli' }, { id: 'b', ok: true, how: 'app', note: expect.stringMatching(/confiável/) }] });
    expect(calls[1]).toEqual(['/api/host/spawn/ack', { results: (r as { results: unknown }).results }]);
    expect(lines[0]).toMatch(/segundo plano em \/conhecida/);
  });

  it('monta o link ele mesmo, recusa pasta que não existe e confirma tudo numa chamada', async () => {
    const calls: Array<[string, unknown]> = [];
    const post: Post = async (path, body) => {
      calls.push([path, body]);
      if (path === '/api/host/spawn/poll') {
        return { status: 200, json: { spawns: [{ id: 'a', folder: '/ok', prompt: 'p', url: 'https://evil.example' }, { id: 'b', folder: '/sumiu', prompt: '' }, { folder: '/sem-id' }] } };
      }
      return { status: 200, json: { ok: true } };
    };
    const opened: string[] = [];
    const r = await spawnRound(post, async (url) => (opened.push(url), { ok: true }), () => {}, (f) => (f === '/ok' ? undefined : 'pasta não encontrada neste computador'));
    expect(opened).toEqual([spawnUrl({ folder: '/ok', prompt: 'p' })]);
    expect(r).toEqual({ state: 'ok', results: [{ id: 'a', ok: true, how: 'app' }, { id: 'b', ok: false, error: 'pasta não encontrada neste computador' }] });
    expect(calls[1]).toEqual(['/api/host/spawn/ack', { results: r.state === 'ok' ? r.results : [] }]);
  });

  it('Habblaud fora do ar ou recusando', async () => {
    expect(await spawnRound(async () => null, async () => ({ ok: true }))).toEqual({ state: 'down' });
    expect(await spawnRound(async () => ({ status: 403, json: { error: 'x' } }), async () => ({ ok: true }))).toEqual({ state: 'refused', status: 403 });
  });
});

/** A real fragmented HTTP body exercises fetch, backpressure, byte checks and cancellation. */
async function streamServer(handler: http.RequestListener): Promise<{ port: number }> {
  const server = http.createServer(handler);
  const listening = Promise.withResolvers<void>();
  server.listen(0, '127.0.0.1', listening.resolve);
  await listening.promise;
  close = () => {
    const stopped = Promise.withResolvers<void>();
    server.closeAllConnections();
    server.close(() => stopped.resolve());
    return stopped.promise;
  };
  return { port: (server.address() as AddressInfo).port };
}

describe('downloads binários do auxiliar', () => {
  it('no restart remove só staging próprio, sem tocar finais, pendentes do servidor ou destinos de links', async () => {
    const root = join(tmp.dir, 'restart-host');
    const pending = join(root, '.pending');
    mkdirSync(pending, { recursive: true });
    const final = join(root, '6ca619c1-d91f-473e-ad6d-d6de881b64cc');
    const serverPending = join(pending, '91f60586-2aaa-434d-889a-b36f2759aa49');
    const external = join(tmp.dir, 'external');
    for (const directory of [final, serverPending, external]) {
      mkdirSync(directory);
      writeFileSync(join(directory, 'keep.txt'), 'preserve');
    }
    const orphan = mkdtempSync(join(pending, 'bridge-'));
    writeFileSync(join(orphan, 'partial'), 'partial bytes');
    symlinkSync(external, join(orphan, 'external-link'));
    const linkedStage = join(pending, 'bridge-Ab1234');
    symlinkSync(external, linkedStage);
    const other = join(pending, 'bridge-not-owned');
    mkdirSync(other);
    await cleanupAttachmentStaging(root);
    expect(existsSync(orphan)).toBe(false);
    expect(lstatSync(linkedStage).isSymbolicLink()).toBe(true);
    expect(existsSync(other)).toBe(true);
    for (const directory of [final, serverPending, external]) {
      expect(readFileSync(join(directory, 'keep.txt'), 'utf8')).toBe('preserve');
    }
    await cleanupAttachmentStaging(root);
    expect(readdirSync(pending).sort()).toEqual(['91f60586-2aaa-434d-889a-b36f2759aa49', 'bridge-Ab1234', 'bridge-not-owned'].sort());
  });

  it('não segue .pending symlink no cleanup nem cria staging através dele', async () => {
    const root = join(tmp.dir, 'linked-root');
    const external = join(tmp.dir, 'linked-external');
    mkdirSync(root);
    mkdirSync(external);
    const untouched = mkdtempSync(join(external, 'bridge-'));
    writeFileSync(join(untouched, 'keep'), 'external');
    symlinkSync(external, join(root, '.pending'));
    await expect(cleanupAttachmentStaging(root)).rejects.toThrow();
    await expect(downloadAttachments('job', [{ id: 'f', name: 'x', type: 'text/plain', size: 1 }],
      async () => { throw new Error('must not download'); }, root)).rejects.toThrow();
    expect(readFileSync(join(untouched, 'keep'), 'utf8')).toBe('external');
    expect(readdirSync(external)).toHaveLength(1);
  });

  it('preserva bytes no limite e nomes repetidos sem publicar antes do lote completo', async () => {
    const root = join(tmp.dir, 'stream-host');
    const visited: string[] = [];
    const { port } = await streamServer((req, res) => {
      visited.push(req.url!);
      expect(existsSync(join(root, '.pending'))).toBe(true);
      expect(readdirSync(root).filter((name) => name !== '.pending')).toEqual([]);
      const size = req.url!.endsWith('/small') ? 1 : ATTACHMENT_MAX_BYTES;
      res.writeHead(200, { 'Content-Length': size });
      let remaining = size;
      const send = () => {
        while (remaining) {
          const length = Math.min(64 * 1024, remaining);
          remaining -= length;
          if (!res.write(Buffer.alloc(length, 173))) return void res.once('drain', send);
        }
        res.end();
      };
      send();
    });
    const paths = await downloadAttachments('job/id', [
      { id: 'small', name: 'ação.txt', type: 'text/plain', size: 1 },
      { id: 'large', name: 'ação.txt', type: 'text/plain', size: ATTACHMENT_MAX_BYTES },
    ], httpAttachmentDownload(port), root);
    expect(visited).toEqual(['/api/codex/bridge/files/job%2Fid/attachments/small', '/api/codex/bridge/files/job%2Fid/attachments/large']);
    expect(paths.map((path) => basename(path))).toEqual(['ação.txt', 'ação-2.txt']);
    expect(readFileSync(paths[0])).toEqual(Buffer.from([173]));
    expect(readFileSync(paths[1]).equals(Buffer.alloc(ATTACHMENT_MAX_BYTES, 173))).toBe(true);
  });

  it.each(['short', 'long', 'missing-length', 'timeout'])('remove lote parcial e fecha streams: %s', async (kind) => {
    const root = join(tmp.dir, kind);
    let visits = 0;
    const { port } = await streamServer((_req, res) => {
      visits++;
      if (visits === 1) return void res.writeHead(200, { 'Content-Length': 1 }).end('a');
      if (kind === 'missing-length') return void res.writeHead(200).end('abc');
      res.writeHead(200, { 'Content-Length': kind === 'long' ? 3 : 2 });
      res.write('a');
      if (kind === 'short') return void res.destroy();
      if (kind === 'long') res.end('bc');
    });
    await expect(downloadAttachments('job', [
      { id: 'one', name: 'a.txt', type: 'text/plain', size: 1 },
      { id: 'two', name: 'b.txt', type: 'text/plain', size: 2 },
    ], httpAttachmentDownload(port), root, kind === 'timeout' ? 50 : 5_000)).rejects.toThrow();
    expect(readdirSync(root)).toEqual(['.pending']);
    expect(readdirSync(join(root, '.pending'))).toEqual([]);
  });
});
