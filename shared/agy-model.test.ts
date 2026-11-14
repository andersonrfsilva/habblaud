import { describe, expect, it } from 'vitest';
import { AGY_DEFAULT, agyModelValue, isAgyModel, toAgyModel } from './agy-model';

describe('agy model names', () => {
  it('recognizes only nonempty agy/ identifiers', () => {
    expect(isAgyModel('agy/x')).toBe(true);
    for (const model of ['google-antigravity/x', 'Agy/x', 'agy', 'agy/', undefined]) expect(isAgyModel(model)).toBe(false);
  });
  it('round trips values and leaves default to the CLI', () => {
    expect(agyModelValue(toAgyModel('gemini-3.8-flash'))).toBe('gemini-3.8-flash');
    expect(isAgyModel(AGY_DEFAULT)).toBe(true);
    expect(agyModelValue(AGY_DEFAULT)).toBeUndefined();
  });
});
