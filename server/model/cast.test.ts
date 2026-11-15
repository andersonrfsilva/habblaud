import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { CastStore, parseCastMember } from './cast';

const dirs: string[] = [];
function folder(): string { const dir = mkdtempSync(join(tmpdir(), 'habblaud-cast-')); dirs.push(dir); return dir; }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }); });
const ana = { id: 'ana', name: 'Ana', look: 'f' as const, seed: 1, kind: 'main' as const };

describe('CastStore', () => {
  it('persists main profiles and rejects invalid fields', () => {
    const file = join(folder(), 'cast.json');
    const member = { ...ana, model: 'provider/model', instructions: 'Teste', idleMinutes: 5, account: 'omp', last: { '/a': 'session' } };
    const store = new CastStore(file);
    expect(store.put(member)).toBe(true);
    store.flush();
    const loaded = new CastStore(file);
    loaded.load();
    expect(loaded.get('ana')).toEqual(member);
    for (const fields of [
      { model: 'bad model' }, { model: '--no-session' }, { model: 'x'.repeat(201) }, { instructions: 'x'.repeat(8001) },
      { idleMinutes: 4 }, { idleMinutes: 1441 }, { idleMinutes: 5.5 }, { account: '' },
      { last: { '/a': '' } }, { kind: 'sub', model: 'model' },
    ]) expect(parseCastMember({ ...ana, ...fields })).toBeNull();
    expect(parseCastMember({ ...ana, idleMinutes: 0 })).not.toBeNull();
  });
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
  it('rejects duplicate names and limits; drops legacy rooms and home', () => {
    const store = new CastStore(null);
    expect(store.put(ana)).toBe(true);
    expect(store.put({ ...ana, id: 'other', name: 'ana' })).toBe(false);
    expect(parseCastMember({ ...ana, rooms: ['/b'], home: '/b' })).toEqual(ana);
    expect(parseCastMember({ ...ana, match: Array(21).fill('task') })).toBeNull();
    for (let i = 1; i < 200; i++) expect(store.put({ ...ana, id: `n-${i}`, name: `N${i}` })).toBe(true);
    expect(store.put({ ...ana, id: 'overflow', name: 'Overflow' })).toBe(false);
  });
  it('accepts only configured main agents and ignores their model and instructions', () => {
    const bound = { ...ana, agent: 'financeiro' };
    expect(parseCastMember(bound)).toBeNull();
    expect(parseCastMember(bound, ['financeiro'])).toEqual(bound);
    expect(parseCastMember({ ...bound, model: '--ignored', instructions: 123 }, ['financeiro'])).toEqual(bound);
    for (const agent of ['', 'a', 'Financeiro', 'a_b', 'a-b', 'a'.repeat(25)]) {
      expect(parseCastMember({ ...ana, agent }, ['financeiro', agent])).toBeNull();
    }
    expect(parseCastMember({ ...bound, agent: 'unknown' }, ['financeiro'])).toBeNull();
    for (const kind of ['advisor', 'sub']) expect(parseCastMember({ ...bound, kind }, ['financeiro'])).toBeNull();
  });
  it('enforces unique named binding through updates, deletion and persistence', () => {
    const file = join(folder(), 'cast.json');
    const options = { namedAliases: ['financeiro', 'suporte'] };
    const store = new CastStore(file, options);
    const bound = { ...ana, agent: 'financeiro' };
    const bia = { ...ana, id: 'bia', name: 'Bia', agent: 'financeiro' };
    expect(store.put(bound)).toBe(true);
    expect(store.agentConflict('financeiro', 'ana')).toBe(false);
    expect(store.agentConflict('financeiro', 'bia')).toBe(true);
    expect(store.put({ ...bound, instructions: 'ignored' })).toBe(true);
    expect(store.put(bia)).toBe(false);
    expect(store.put({ ...bia, agent: 'suporte' })).toBe(true);
    expect(store.put({ ...bound, agent: 'suporte' })).toBe(false);
    store.flush();
    const loaded = new CastStore(file, options);
    loaded.load();
    expect(loaded.get('ana')).toEqual(bound);
    expect(loaded.delete('ana')).toBe(true);
    expect(loaded.put(bia)).toBe(true);
    loaded.flush();
  });
  it('drops unknown and duplicate bindings from disk without affecting unbound members', () => {
    const file = join(folder(), 'cast.json');
    writeFileSync(file, JSON.stringify({ version: 1, bindings: {}, members: [
      { ...ana, agent: 'financeiro' },
      { ...ana, id: 'bia', name: 'Bia', agent: 'financeiro' },
      { ...ana, id: 'caio', name: 'Caio', agent: 'unknown' },
      { ...ana, id: 'dani', name: 'Dani' },
    ] }));
    const store = new CastStore(file, { namedAliases: ['financeiro'] });
    store.load();
    expect(store.list().map((member) => member.id)).toEqual(['ana', 'dani']);
    const legacy = new CastStore(file);
    legacy.load();
    expect(legacy.list().map((member) => member.id)).toEqual(['dani']);
  });
  it('keeps the first named binding when migrating legacy rooms', () => {
    const dir = folder();
    writeFileSync(join(dir, 'names.json'), JSON.stringify({ rooms: {
      '/a': { ...ana, agent: 'financeiro' },
      '/b': { ...ana, name: 'Bia', agent: 'financeiro' },
    } }));
    const store = new CastStore(join(dir, 'cast.json'), { namedAliases: ['financeiro'] });
    store.load();
    expect(store.list()).toEqual([{ ...ana, agent: 'financeiro' }]);
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
