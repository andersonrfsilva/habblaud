// Empacota o servidor (TypeScript) em um único arquivo ESM em dist/server e grava dist/build.json (commit e "cópia
// modificada", ver scripts/build-info.mjs).
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
import { writeBuildInfo } from './build-info.mjs';

const { devDependencies = {} } = JSON.parse(readFileSync('package.json', 'utf8'));

await build({
  entryPoints: ['server/index.ts'],
  outfile: 'dist/server/index.js',
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'esm',
  // Dependências de runtime (ex.: beautiful-mermaid) entram no bundle: a imagem de produção copia só o dist/.
  // As de desenvolvimento (vite no modo dev) ficam de fora.
  external: Object.keys(devDependencies),
  sourcemap: true,
  logLevel: 'info',
});

const info = writeBuildInfo('dist/build.json');
console.log(`  dist/build.json  ${info.commit === 'unknown' ? 'unknown' : info.commit.slice(0, 12)}${info.dirty ? ' (dirty)' : ''}`);
