// Regras puras da câmera do mundo.
import { describe, expect, it } from 'vitest';
import { renderDpr } from './camera';

describe('renderDpr', () => {
  it('usa a densidade da tela, de 1 a 3', () => {
    expect(renderDpr(2, false)).toBe(2);
    expect(renderDpr(4, false)).toBe(3);
    expect(renderDpr(undefined, false)).toBe(1);
    expect(renderDpr(0.5, false)).toBe(1);
  });
  it('economia de energia desenha sempre em 1', () => {
    expect(renderDpr(3, true)).toBe(1);
  });
});
