import { describe, expect, it } from 'vitest';
import type { UiContext } from './context';
import { officeIsEmpty } from './overlays';

describe('officeIsEmpty', () => {
  it('shows the empty state only when there are no agents, rooms or reserve members', () => {
    const snapshot = { agents: [], rooms: [], reserve: [] as unknown[] };
    const ctx = { store: { connection: 'open', replaying: false, snapshot } } as unknown as UiContext;
    expect(officeIsEmpty(ctx)).toBe(true);
    snapshot.reserve.push({ cast: 'ana' });
    expect(officeIsEmpty(ctx)).toBe(false);
    snapshot.reserve.length = 0;
    expect(officeIsEmpty(ctx)).toBe(true);
  });
});
