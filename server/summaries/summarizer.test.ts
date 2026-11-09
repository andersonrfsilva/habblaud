import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as fsPromises from 'node:fs/promises';
import { tempDir } from '../test/fixtures';
import { httpSummaryRunner, rpcSummaryRunner, Summarizer, SummaryUnavailable } from './summarizer';

vi.mock('node:fs/promises', { spy: true });

/** Um "omp --mode rpc" de mentira: responde com o pid (para ver se o processo foi reaproveitado) ou sai no meio. */
function fakeOmp(mode: 'ok' | 'exit'): { bin: string; dir: string } {
  const dir = mkdtempSync(join(tmpdir(), 'hb-sum-'));
  const bin = join(dir, 'omp');
  writeFileSync(bin, `#!/usr/bin/env node
const out = (o) => process.stdout.write(JSON.stringify(o) + '\\n');
out({ type: 'ready' });
require('node:readline').createInterface({ input: process.stdin }).on('line', (line) => {
  const c = JSON.parse(line);
  if (c.type === 'prompt' && ${JSON.stringify(mode)} === 'exit') process.exit(3);
  out({ id: c.id, type: 'response', command: c.type, success: true, data: c.type === 'get_last_assistant_text' ? { text: ' pid ' + process.pid + ' ' } : undefined });
  if (c.type === 'prompt') out({ id: c.id, type: 'prompt_result', status: 'completed' });
});
`);
  chmodSync(bin, 0o755);
  return { bin, dir };
}

function deferred() {
  const calls: { text: string; resolve(v: string): void; reject(e: Error): void }[] = [];
  const run = (_bin: string, _dir: string, text: string) => {
    const { promise, resolve, reject } = Promise.withResolvers<string>();
    calls.push({ text, resolve, reject });
    return promise;
  };
  return { calls, run };
}

// Só microtarefas: a fila do Summarizer anda por promessas, sem relógio.
const tick = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
const tiny = async () => 'openai/gpt-mini:low';

describe('Summarizer', () => {
  it('limita os resumos simultâneos e passa a vaga para quem espera', async () => {
    const { calls, run } = deferred();
    const s = new Summarizer({ bin: () => 'omp', agentDir: () => '/a', run, tiny, concurrency: 1 });
    const a = s.summarize('a');
    const b = s.summarize('b');
    await tick();
    expect(calls.map((c) => c.text)).toEqual(['a']);
    calls[0].resolve('A');
    expect(await a).toBe('A');
    await tick();
    expect(calls.map((c) => c.text)).toEqual(['a', 'b']);
    calls[1].resolve('B');
    expect(await b).toBe('B');
  });

  it('reaproveita o resumo do mesmo texto, mas não guarda a falha', async () => {
    const { calls, run } = deferred();
    const s = new Summarizer({ bin: () => 'omp', agentDir: () => '/a', run, tiny });
    const first = s.summarize('x');
    await tick();
    calls[0].reject(new Error('caiu'));
    await expect(first).rejects.toThrow('caiu');
    const again = s.summarize('x');
    await tick();
    calls[1].resolve('ok');
    expect(await again).toBe('ok');
    expect(await s.summarize('x')).toBe('ok');
    expect(calls).toHaveLength(2);
  });

  it('sem omp ou sem conta, recusa sem rodar nada', async () => {
    const { calls, run } = deferred();
    const s = new Summarizer({ bin: () => undefined, agentDir: () => '/a', run, tiny });
    await expect(s.summarize('x')).rejects.toBeInstanceOf(SummaryUnavailable);
    expect(calls).toHaveLength(0);
  });

  it('sem modelo escolhido para o papel tiny, recusa sem rodar nada', async () => {
    const { calls, run } = deferred();
    const s = new Summarizer({ bin: () => 'omp', agentDir: () => '/a', run, tiny: async () => undefined });
    await expect(s.summarize('x')).rejects.toBeInstanceOf(SummaryUnavailable);
    expect(calls).toHaveLength(0);
  });
});

// O omp de mentira é um script com shebang: no Windows o spawn não o executa.
describe.runIf(process.platform !== 'win32')('rpcSummaryRunner', () => {
  it('devolve o texto da resposta e reaproveita o mesmo processo', async () => {
    const { bin, dir } = fakeOmp('ok');
    const run = rpcSummaryRunner(1);
    const first = await run(bin, dir, 'a');
    expect(first).toMatch(/^pid \d+$/);
    expect(await run(bin, dir, 'b')).toBe(first);
  });

  it('o omp que sai no meio vira erro, e o próximo resumo abre outro', async () => {
    const { bin, dir } = fakeOmp('exit');
    const run = rpcSummaryRunner(1);
    await expect(run(bin, dir, 'a')).rejects.toThrow('omp saiu');
    await expect(run(bin, dir, 'b')).rejects.toThrow('omp saiu');
  });
});

describe('HTTP summaries', () => {
  const cleanups: (() => void)[] = [];
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
    for (const cleanup of cleanups.splice(0)) cleanup();
  });
  function setup(auth: 'x-api-key' | 'bearer' = 'x-api-key') {
    const tmp = tempDir();
    cleanups.push(tmp.cleanup);
    const tokenFile = join(tmp.dir, 'token');
    writeFileSync(tokenFile, ' secret-token \n');
    const config = { url: 'https://inference.test/base', model: 'literal-model', tokenFile, auth };
    const forbidden = vi.fn((): never => { throw new Error('OMP must not be consulted'); });
    const s = new Summarizer({ http: config, bin: forbidden, agentDir: forbidden, tiny: forbidden });
    return { config, s, forbidden };
  }
  const response = (text = 'summary') => new Response(JSON.stringify({ content: [{ type: 'text', text }] }));

  it.each(['x-api-key', 'bearer'] as const)('sends exact Messages request with exclusive %s authentication and no OMP', async (auth) => {
    const { s, forbidden } = setup(auth);
    const fetchMock = vi.fn().mockResolvedValue(response());
    vi.stubGlobal('fetch', fetchMock);
    s.warm();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(s.httpConfigured).toBe(true);
    expect(await s.summarize('/agent text')).toBe('summary');
    expect(forbidden).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, request] = fetchMock.mock.calls[0];
    expect(url).toBe('https://inference.test/base/v1/messages');
    expect(request).toMatchObject({
      method: 'POST', redirect: 'error',
      headers: { 'Content-Type': 'application/json', 'anthropic-version': '2023-06-01' },
      signal: expect.any(AbortSignal),
    });
    expect(request.headers).toEqual({
      'Content-Type': 'application/json', 'anthropic-version': '2023-06-01',
      ...(auth === 'bearer' ? { Authorization: 'Bearer secret-token' } : { 'x-api-key': 'secret-token' }),
    });
    expect(JSON.parse(request.body)).toEqual({
      model: 'literal-model',
      system: 'O usuário envia a resposta que um agente de programação deu. Não responda a ela nem converse: resuma-a em no ' +
        'máximo 5 frases, no mesmo idioma dela. Mantenha exatos os nomes, números, arquivos, comandos e erros que importam. ' +
        'Responda só com o resumo, sem introdução nem markdown de títulos.',
      messages: [{ role: 'user', content: 'Resposta do agente:\n\n/agent text' }],
      max_tokens: 512, stream: false,
    });
  });
  it('collects text blocks in order and hides thinking', async () => {
    const { s } = setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ content: [
      { type: 'text', text: ' first ' }, { type: 'thinking', thinking: 'private' }, { type: 'text', text: 'second ' },
    ] }))));
    expect(await s.summarize('x')).toBe('first \nsecond');
  });
  it.each(['invalid JSON secret-token', '{}', '{"content":{}}', '{"content":[]}', '{"content":[{"type":"text","text":"   "}]}', '{"content":[{"type":"text","text":7}]}'])('rejects invalid responses safely: %s', async (body) => {
    const { s } = setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body)));
    await expect(s.summarize('private input')).rejects.toThrow(/^(falha no resumo HTTP|resposta HTTP inválida)$/);
  });
  it.each([401, 403, 429, 500, 503])('reports only status %s and retries a failed cache entry', async (status) => {
    const { s } = setup();
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response('secret-token private input', { status })).mockResolvedValueOnce(response());
    vi.stubGlobal('fetch', fetchMock);
    await expect(s.summarize('private input')).rejects.toThrow(`HTTP ${status}`);
    expect(await s.summarize('private input')).toBe('summary');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it.each(['network', 'redirect'])('sanitizes %s failures without fallback', async () => {
    const { s, forbidden } = setup();
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('secret-token private input upstream body'));
    vi.stubGlobal('fetch', fetchMock);
    await expect(s.summarize('private input')).rejects.toThrow(/^falha no resumo HTTP$/);
    expect(fetchMock.mock.calls[0][1].redirect).toBe('error');
    expect(forbidden).not.toHaveBeenCalled();
  });
  it('rejects missing, empty and unreadable token files with a fixed unavailable error', async () => {
    const { config } = setup();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const run = httpSummaryRunner(config);
    writeFileSync(config.tokenFile, ' \n');
    await expect(run('x')).rejects.toBeInstanceOf(SummaryUnavailable);
    await expect(httpSummaryRunner({ ...config, tokenFile: `${config.tokenFile}-missing` })('x')).rejects.toThrow(/^resumo indisponível: arquivo de token HTTP ilegível ou vazio$/);
    vi.spyOn(fsPromises, 'readFile').mockRejectedValueOnce(new Error('filesystem secret'));
    await expect(run('x')).rejects.toThrow(/^resumo indisponível: arquivo de token HTTP ilegível ou vazio$/);
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('rotates credentials for uncached calls and deduplicates successful calls', async () => {
    const { config, s } = setup();
    const fetchMock = vi.fn().mockImplementation(async () => response());
    vi.stubGlobal('fetch', fetchMock);
    const a = s.summarize('a');
    expect(s.summarize('a')).toBe(a);
    await a;
    writeFileSync(config.tokenFile, 'rotated');
    await s.summarize('a');
    await s.summarize('b');
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][1].headers['x-api-key']).toBe('rotated');
  });
  it('evicts the oldest cached entry', async () => {
    const { config, forbidden } = setup();
    const s = new Summarizer({ http: config, bin: forbidden, agentDir: forbidden, cacheMax: 1 });
    const fetchMock = vi.fn().mockImplementation(async () => response());
    vi.stubGlobal('fetch', fetchMock);
    await s.summarize('a');
    await s.summarize('b');
    await s.summarize('a');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });
  it('runs two HTTP jobs by default and admits waiting jobs FIFO', async () => {
    const { s } = setup();
    const calls: { text: string; resolve(value: Response): void }[] = [];
    vi.stubGlobal('fetch', vi.fn((_url, request) => {
      const { promise, resolve } = Promise.withResolvers<Response>();
      calls.push({ text: JSON.parse(request.body).messages[0].content, resolve });
      return promise;
    }));
    const a = s.summarize('a'), b = s.summarize('b'), c = s.summarize('c'), d = s.summarize('d');
    await vi.waitFor(() => expect(calls).toHaveLength(2));
    calls[0].resolve(response('A'));
    expect(await a).toBe('A');
    await vi.waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[2].text).toBe('Resposta do agente:\n\nc');
    calls[1].resolve(response('B'));
    await b;
    await vi.waitFor(() => expect(calls).toHaveLength(4));
    expect(calls[3].text).toBe('Resposta do agente:\n\nd');
    calls[2].resolve(response()); calls[3].resolve(response());
    await Promise.all([c, d]);
  });
  it.each([false, true])('aborts after 90 seconds, including pending body: %s', async (pendingBody) => {
    const { s } = setup();
    let signal: AbortSignal | undefined;
    const fetchMock = vi.fn((_url, request) => {
      signal = request.signal;
      const { promise, reject } = Promise.withResolvers<never>();
      signal!.addEventListener('abort', () => reject(new Error('secret')));
      return pendingBody ? Promise.resolve({ ok: true, json: () => promise }) : promise;
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.useFakeTimers();
    vi.spyOn(fsPromises, 'readFile').mockResolvedValue('token');
    const timed = s.summarize('timed');
    const timedAssertion = expect(timed).rejects.toThrow('timeout (90 s)');
    await tick();
    await vi.advanceTimersByTimeAsync(90_000);
    await timedAssertion;
    expect(signal!.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });
});
