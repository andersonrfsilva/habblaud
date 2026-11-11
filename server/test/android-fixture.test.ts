import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { TerminalEntry, TerminalInit } from '../../shared/types';

async function bounded<T>(promise: Promise<T>): Promise<T> {
  const timeout = Promise.withResolvers<never>();
  // A fixture roda em outro processo; o prazo limita falhas de I/O, sem simular o relógio.
  const timer = setTimeout(() => timeout.reject(new Error('Fixture não respondeu dentro do prazo.')), 20_000);
  try { return await Promise.race([promise, timeout.promise]); }
  finally { clearTimeout(timer); }
}

type Lab = {
  base: string;
  command(line: string): Promise<unknown>;
};

async function laboratory(run: (lab: Lab) => Promise<void>): Promise<void> {
  const child = spawn(process.execPath, ['--import', 'tsx', 'scripts/android-smoke.ts', '--port', '0', '--allow', 'terminal,permissions,messages'], {
    cwd: fileURLToPath(new URL('../../', import.meta.url)), stdio: ['pipe', 'pipe', 'pipe'], detached: true,
  });
  const lines = createInterface({ input: child.stdout });
  const ready = Promise.withResolvers<{ localUrl: string }>();
  const replies: Array<{ resolve(value: unknown): void; reject(error: Error): void }> = [];
  const exit = Promise.withResolvers<void>();
  child.stderr.resume();
  child.once('error', error => ready.reject(error));
  child.once('close', () => {
    exit.resolve();
    const error = new Error('Fixture encerrada antes de concluir o teste.');
    ready.reject(error);
    for (const reply of replies.splice(0)) reply.reject(error);
  });
  lines.on('line', line => {
    try {
      const value = JSON.parse(line);
      if (value.ready === true) ready.resolve({ localUrl: value.localUrl });
      else replies.shift()?.resolve(value);
    } catch { /* A fixture pode emitir diagnósticos antes de informar prontidão. */ }
  });
  try {
    const { localUrl } = await bounded(ready.promise);
    const url = new URL(localUrl);
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.username || url.password) throw new Error('Fixture fora do loopback.');
    await run({
      base: localUrl,
      command: async line => {
        const reply = Promise.withResolvers<unknown>();
        replies.push(reply);
        child.stdin.write(`${line}\n`);
        const value = await bounded(reply.promise);
        expect(value).not.toMatchObject({ ok: false });
        return value;
      },
    });
  } finally {
    child.stdin.end();
    // O processo recebe EOF; o prazo só impede que uma falha de encerramento prenda o teste.
    const timer = setTimeout(() => {
      if (child.pid) {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { /* O processo já encerrou. */ }
      }
    }, 5_000);
    try { await exit.promise; }
    finally { clearTimeout(timer); lines.close(); }
  }
}

async function terminal(base: string, agentId: string) {
  const response = await fetch(`${base}/api/agents/${encodeURIComponent(agentId)}/terminal`, { signal: AbortSignal.timeout(20_000) });
  expect(response.status).toBe(200);
  expect(response.headers.get('content-type')).toMatch(/^text\/event-stream/);
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  return {
    async next(event: string): Promise<unknown> {
      for (;;) {
        const end = buffer.indexOf('\n\n');
        if (end !== -1) {
          const block = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          if (/^event: (.*)$/m.exec(block)?.[1] === event) {
            const data = /^data: (.*)$/m.exec(block)?.[1];
            if (data !== undefined) return JSON.parse(data);
          }
          continue;
        }
        const chunk = await reader.read();
        if (chunk.done) throw new Error('Terminal encerrado antes do evento esperado.');
        buffer += decoder.decode(chunk.value, { stream: true });
      }
    },
    close: () => reader.cancel(),
  };
}

const summarize = (base: string) => fetch(`${base}/api/summaries`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ text: 'Resposta fictícia para resumir.' }), signal: AbortSignal.timeout(20_000),
});

const health = async (base: string): Promise<{ summaryHttp: boolean }> => {
  const response = await fetch(`${base}/api/health`, { signal: AbortSignal.timeout(20_000) });
  expect(response.status).toBe(200);
  return response.json();
};

describe('fixture Android: resumos e respostas longas', () => {
  it('OMP mantém resumos indisponíveis por padrão; http-summaries on habilita HTTP e off restaura o padrão', async () => {
    await laboratory(async ({ base, command }) => {
      await command('runtime omp');
      expect((await health(base)).summaryHttp).toBe(false);
      expect((await summarize(base)).status).toBe(404);

      await command('http-summaries on');
      expect((await health(base)).summaryHttp).toBe(true);
      const response = await summarize(base);
      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({ summary: 'Resumo HTTP fictício sem Tiny.' });
      const stats = await (await fetch(`${base}/api/fixture/stats`, { signal: AbortSignal.timeout(20_000) })).json();
      expect(stats.zeroclaw.gatewayRequests).toEqual(expect.arrayContaining([
        expect.objectContaining({ method: 'POST', path: '/v1/messages' }),
      ]));

      await command('http-summaries off');
      expect((await health(base)).summaryHttp).toBe(false);
      expect((await summarize(base)).status).toBe(404);
    });
  }, 60_000);

  it.each([
    ['omp', 'Resposta OMP fictícia'],
    ['claude', 'Resposta fictícia'],
  ])('long-reply acrescenta resposta assistant com mais de 500 caracteres para %s sem alterar o seed padrão', async (provider, seedReply) => {
    await laboratory(async ({ base, command }) => {
      const stream = await terminal(base, `fixture:${provider}`);
      try {
        const initial = await stream.next('init') as TerminalInit;
        const assistants = initial.entries.filter(entry => entry.kind === 'assistant');
        expect(assistants.at(-1)).toMatchObject({ text: seedReply });
        expect(assistants.some(entry => entry.kind === 'assistant' && entry.text.startsWith('# Resposta longa fictícia'))).toBe(false);

        const result = await command(`long-reply fixture:${provider}`);
        const appended = await stream.next('append') as TerminalEntry[];
        expect(appended).toHaveLength(1);
        expect(appended[0]).toMatchObject({ kind: 'assistant' });
        const entry = appended[0];
        if (entry.kind !== 'assistant') throw new Error('Resposta longa não foi publicada como assistant.');
        expect(entry.text.length).toBeGreaterThan(500);
        expect(entry.text).toMatch(/^# Resposta longa fictícia\n\nParágrafo 1:/);
        expect(entry.text).toContain('Parágrafo 40:');
        expect(result).toEqual({ ok: true, chars: entry.text.length });
      } finally {
        await stream.close();
      }
    });
  }, 60_000);
});
