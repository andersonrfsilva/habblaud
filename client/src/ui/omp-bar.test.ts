import { describe, expect, it } from 'vitest';
import { contextView } from './omp-bar';

describe('barra do contexto do omp no terminal', () => {
  it('percentual com uma casa, nível de cor pelos limites do uso (50% e 80%) e o texto tokens / janela', () => {
    expect(contextView({ tokens: 16_881, window: 1_000_000 })).toMatchObject({ pct: 1.7, level: 'ok' });
    expect(contextView({ tokens: 120_000, window: 200_000 })?.level).toBe('warn');
    expect(contextView({ tokens: 190_000, window: 200_000 })?.level).toBe('crit');
    expect(contextView({ tokens: 250_000, window: 200_000 })?.pct).toBe(100);
    expect(contextView({ tokens: 16_881, window: 1_000_000 })?.text).toMatch(/16,9 k \/ 1 M · 1\.7%|16\.9 k \/ 1 M · 1\.7%/);
  });

  it('sem leitura ou janela zero: sem barra', () => {
    expect(contextView(undefined)).toBeUndefined();
    expect(contextView({ tokens: 1, window: 0 })).toBeUndefined();
  });
});
