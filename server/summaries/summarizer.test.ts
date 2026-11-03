import { describe, expect, it } from 'vitest';
import { Summarizer, SummaryUnavailable } from './summarizer';

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

describe('Summarizer', () => {
  it('limita os resumos simultâneos e passa a vaga para quem espera', async () => {
    const { calls, run } = deferred();
    const s = new Summarizer({ bin: () => 'omp', agentDir: () => '/a', run, concurrency: 1 });
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
    const s = new Summarizer({ bin: () => 'omp', agentDir: () => '/a', run });
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
    const s = new Summarizer({ bin: () => undefined, agentDir: () => '/a', run });
    await expect(s.summarize('x')).rejects.toBeInstanceOf(SummaryUnavailable);
    expect(calls).toHaveLength(0);
  });
});
