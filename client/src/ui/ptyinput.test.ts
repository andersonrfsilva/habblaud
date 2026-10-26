import { describe, expect, it } from 'vitest';
import { PtyInput } from './ptyinput';

const tick = () => new Promise((r) => setTimeout(r, 0));

function fakePost(opts: { rejectFirst?: boolean } = {}) {
  const calls: [string, string][] = [];
  const resolvers: (() => void)[] = [];
  let n = 0;
  const post = (id: string, data: string) => {
    calls.push([id, data]);
    if (opts.rejectFirst && n++ === 0) return Promise.reject(new Error('rede'));
    return new Promise<void>((res) => resolvers.push(res));
  };
  const drain = async () => {
    await tick();
    while (resolvers.length) {
      resolvers.shift()!();
      await tick();
    }
  };
  return { post, calls, resolvers, drain };
}

describe('PtyInput', () => {
  it('junta e mantém a ordem das teclas dentro de um pty', async () => {
    const f = fakePost();
    const a = new PtyInput('A', f.post);
    a.push('a');
    await tick();
    a.push('b');
    a.push('c');
    f.resolvers.shift()!();
    await tick();
    f.resolvers.shift()!();
    await tick();
    expect(f.calls).toEqual([
      ['A', 'a'],
      ['A', 'bc'],
    ]);
  });

  it('não vaza teclas do pty novo para o anterior com POST em andamento', async () => {
    const f = fakePost();
    const a = new PtyInput('A', f.post);
    a.push('a');
    await tick();
    a.push('b');
    const b = new PtyInput('B', f.post);
    b.push('c');
    await f.drain();
    const c = f.calls.filter(([, d]) => d.includes('c'));
    expect(c).toEqual([['B', 'c']]);
    expect(
      f.calls
        .filter(([id]) => id === 'A')
        .map(([, d]) => d)
        .join(''),
    ).toBe('ab');
  });

  it('falha de rede não trava a fila', async () => {
    const f = fakePost({ rejectFirst: true });
    const a = new PtyInput('A', f.post);
    a.push('a');
    await tick();
    a.push('b');
    await tick();
    expect(f.calls).toEqual([
      ['A', 'a'],
      ['A', 'b'],
    ]);
  });

  it('entrada vazia não chama post', async () => {
    const f = fakePost();
    new PtyInput('A', f.post).push('');
    await tick();
    expect(f.calls).toEqual([]);
  });
});
