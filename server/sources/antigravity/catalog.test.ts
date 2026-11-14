import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, it } from 'vitest';
import { AntigravityCatalog } from './catalog';
const folders: string[] = [];
afterEach(() => { for (const folder of folders.splice(0)) rmSync(folder, { recursive: true, force: true }); });
function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'agy-catalog-')); folders.push(directory);
  return { directory, catalog: new AntigravityCatalog(directory), file: join(directory, 'antigravity', 'models.json') };
}
it('learns, persists privately, reloads and avoids rewriting unchanged options', () => {
  const { directory, catalog, file } = fixture();
  expect(catalog.agyModels()).toEqual(['agy/default']);
  const options = [{ value: 'fake-a', name: 'Fake A' }, { value: 'fake-b', name: 'Fake B' }];
  catalog.learn(options);
  expect(JSON.parse(readFileSync(file, 'utf8'))).toEqual({ values: ['fake-a', 'fake-b'] });
  expect(statSync(file).mode & 0o777).toBe(0o600);
  expect(statSync(join(directory, 'antigravity')).mode & 0o777).toBe(0o700);
  const before = statSync(file);
  catalog.learn(options);
  expect(statSync(file).ino).toBe(before.ino);
  expect(new AntigravityCatalog(directory).agyModels()).toEqual(['agy/default', 'agy/fake-a', 'agy/fake-b']);
});
it('ignores corrupt and malformed catalog files', () => {
  const { directory, catalog, file } = fixture();
  catalog.learn([{ value: 'fake-a', name: 'Fake A' }]);
  for (const content of ['{', '{"values":[1]}', '{"values":["bad value"]}', '{}']) {
    writeFileSync(file, content);
    expect(new AntigravityCatalog(directory).agyModels()).toEqual(['agy/default']);
  }
});
