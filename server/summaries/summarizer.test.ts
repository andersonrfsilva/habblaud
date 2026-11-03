import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { rpcSummaryRunner, Summarizer, SummaryUnavailable } from './summarizer';

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

describe('rpcSummaryRunner', () => {
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
