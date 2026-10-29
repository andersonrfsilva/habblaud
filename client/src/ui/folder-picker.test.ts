import { describe, expect, it } from 'vitest';
import { folderOptions } from './folder-picker';

describe('folderOptions', () => {
  it('junta pastas conhecidas e as só do personagem, sem repetir, com as preferidas no topo', () => {
    const known = [
      { path: '/p/a', name: 'a', lastAt: 3 },
      { path: '/p/b', name: 'b', lastAt: 2 },
    ];
    const out = folderOptions(known, ['/p/b', '/fechada/c'], ['/fechada/c', '/p/b']);
    expect(out.map((f) => f.path)).toEqual(['/fechada/c', '/p/b', '/p/a']);
    // A pasta que o escritório não conhece ganha o nome pela última parte do caminho.
    expect(out[0]).toEqual({ path: '/fechada/c', name: 'c', lastAt: 0 });
  });
});
