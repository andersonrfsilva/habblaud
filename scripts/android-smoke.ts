import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { isAbsolute, join, relative, resolve } from 'node:path';

// --files <diretório> expõe somente essa raiz no navegador; requer --allow terminal para acesso remoto.
// Sem --files, o navegador permanece desabilitado. O diretório fornecido nunca é removido pela fixture.
const realHome = realpathSync(homedir());
const root = mkdtempSync(join(tmpdir(), 'habblaud-android-'));
const resolvedRoot = realpathSync(root);
const inside = (parent: string, child: string) => {
  const path = relative(parent, child);
  return path === '' || (!path.startsWith('..') && !isAbsolute(path));
};
if (inside(realHome, resolvedRoot)) {
  rmSync(root, { recursive: true, force: true });
  throw new Error('Temporary fixture directory must be outside the real HOME');
}
process.env.HOME = resolvedRoot;
process.env.HABBLAUD_ATTACHMENTS_DIR = resolve(resolvedRoot, 'attachments');

try {
  // Server modules must load only after HOME and attachment staging are isolated.
  const { startAndroidFixture } = await import('../server/test/android-fixture');
  await startAndroidFixture(resolvedRoot, process.argv.slice(2));
} catch (error) {
  rmSync(root, { recursive: true, force: true });
  throw error;
}
