import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { CastStore, parseCastMember } from './cast';

const dirs: string[] = [];
function folder(): string { const dir = mkdtempSync(join(tmpdir(), 'habblaud-cast-')); dirs.push(dir); return dir; }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }); });
const ana = { id: 'ana', name: 'Ana', look: 'f' as const, seed: 1, kind: 'main' as const, rooms: ['/a'], home: '/a' };

describe('CastStore', () => {
  it('migrates rooms and owners after backing up names', () => {
    const dir = folder();
    const names = JSON.stringify({ version: 1, names: {}, rooms: { '/a': { name: 'Ana', look: 'f', seed: 1, owner: 's', at: 100 } } });
    writeFileSync(join(dir, 'names.json'), names);
    const store = new CastStore(join(dir, 'cast.json'), { now: () => 100 });
    store.load();
    expect(store.list()).toEqual([ana]);
    expect(store.binding('s')).toBe('ana');
    expect(readFileSync(join(dir, 'names.json.pre-031'), 'utf8')).toBe(names);
    const again = new CastStore(join(dir, 'cast.json'), { now: () => 100 });
    again.load();
    expect(again.binding('s')).toBe('ana');
  });
  it('migrates valid rooms and skips invalid and duplicate names', () => {
    const dir = folder();
    const names = JSON.stringify({ version: 1, names: {}, rooms: {
      '/a': { name: 'Ana', look: 'f', seed: 1, at: 100 },
      '/bad': { name: 'Bia', look: 'f', seed: -1, at: 100 },
      '/duplicate': { name: 'ana', look: 'f', seed: 2, at: 100 },
    } });
    writeFileSync(join(dir, 'names.json'), names);
    const store = new CastStore(join(dir, 'cast.json'), { now: () => 100 });
    expect(() => store.load()).not.toThrow();
    expect(store.list()).toEqual([ana]);
    expect(readFileSync(join(dir, 'names.json.pre-031'), 'utf8')).toBe(names);
  });
  it('preserves an unreadable file', () => {
    const file = join(folder(), 'cast.json');
    writeFileSync(file, '{');
    const store = new CastStore(file);
    store.load();
    store.put(ana);
    store.flush();
    expect(readFileSync(file, 'utf8')).toBe('{');
  });
  it('rejects duplicate names and invalid home and limits', () => {
    const store = new CastStore(null);
    expect(store.put(ana)).toBe(true);
    expect(store.put({ ...ana, id: 'other', name: 'ana' })).toBe(false);
    expect(parseCastMember({ ...ana, home: '/b' })).toBeNull();
    expect(parseCastMember({ ...ana, rooms: Array(51).fill('/a') })).toBeNull();
    expect(parseCastMember({ ...ana, match: Array(21).fill('task') })).toBeNull();
    for (let i = 1; i < 200; i++) expect(store.put({ ...ana, id: `n-${i}`, name: `N${i}` })).toBe(true);
    expect(store.put({ ...ana, id: 'overflow', name: 'Overflow' })).toBe(false);
  });
  it('expires bindings after 60 days and retains the newest 4000', () => {
    let now = 1;
    const store = new CastStore(null, { now: () => now });
    store.put(ana);
    store.bind('old', 'ana');
    now += 60 * 24 * 3_600_000;
    expect(store.binding('old')).toBeUndefined();
    for (let i = 0; i < 4001; i++) { now++; store.bind(`s${i}`, 'ana'); }
    expect(store.binding('s0')).toBeUndefined();
    expect(store.binding('s4000')).toBe('ana');
  });
});
