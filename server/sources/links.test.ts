import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { TerminalEntry } from '../../shared/types';
import { createCodexTerminalParser } from './codex/terminal';
import { configureLocalLinks, resolveLocalLinks } from './links';
import { createOmpTerminalParser } from './omp/terminal';
import { createTerminalParser, type TerminalParser } from './terminal';

const CWD = '/raiz/projeto';
const FILES = new Set([
  '/raiz/projeto/arte.png',
  '/raiz/projeto/pasta/sub/vistas.png',
  '/raiz/projeto/pasta/anim.gif',
  '/raiz/projeto/fonte.aseprite',
  '/raiz/outro/compartilhado.png',
  '/fora/segredo.png',
  '/raiz/projeto/Arte Final/ação.png',
  '/raiz/projeto/a(b).png',
  '/raiz/projeto/x&y.png',
]);
const exists = (p: string) => FILES.has(p);
const allowed = (p: string) => p.startsWith('/raiz/');
const run = (md: string, cwd = CWD) => resolveLocalLinks(md, cwd, exists, allowed);

describe('resolveLocalLinks', () => {
  it('relativo simples e com subpastas vira absoluto', () => {
    expect(run('[Arte](arte.png) e [Vistas](pasta/sub/vistas.png)')).toBe('[Arte](/raiz/projeto/arte.png) e [Vistas](/raiz/projeto/pasta/sub/vistas.png)');
  });

  it('./ e ../ que ficam dentro do projeto ou de uma raiz', () => {
    expect(run('[a](./arte.png) [b](pasta/sub/../anim.gif) [c](../outro/compartilhado.png)')).toBe(
      '[a](/raiz/projeto/arte.png) [b](/raiz/projeto/pasta/anim.gif) [c](/raiz/outro/compartilhado.png)',
    );
  });

  it('../ que sai das raízes, arquivo inexistente e pasta ficam como estão', () => {
    const md = '[a](../../fora/segredo.png) [b](nao-existe.png) [c](pasta)';
    expect(run(md)).toBe(md);
  });

  it('esquemas, âncoras, consultas e absolutos ficam intactos', () => {
    const md = '[a](http://x.com/a.png) [b](mailto:a@b.c) [c](#ancora) [d](?q=1) [e](/raiz/projeto/arte.png) [f](//host/x.png) [g](file:///x.png) [h](C:arte.png)';
    expect(run(md)).toBe(md);
  });

  it('destino com consulta ou fragmento fica como está (dúvida)', () => {
    const md = '[a](arte.png#x) [b](arte.png?v=2)';
    expect(run(md)).toBe(md);
  });

  it('link em bloco de código e em código inline fica intacto', () => {
    const md = ['```md', '[a](arte.png)', '```', 'Use `[b](arte.png)` assim; ``x`[c](arte.png)`x``', '~~~', '![d](arte.png)', '~~~', '', '    [e](arte.png)'].join('\n');
    expect(run(md)).toBe(md);
  });

  it('linha com crase sem par fica intacta (código que pode continuar na linha seguinte)', () => {
    const md = 'texto `aberto [a](arte.png)\ncontinua` fim';
    expect(run(md)).toBe(md);
  });

  it('link depois de um bloco de código fechado é reescrito', () => {
    expect(run('```\ncodigo\n```\n[a](arte.png)')).toBe('```\ncodigo\n```\n[a](/raiz/projeto/arte.png)');
  });

  it('imagem relativa vira absoluta e o título fica', () => {
    expect(run('![Vistas](pasta/sub/vistas.png "Quatro vistas")')).toBe('![Vistas](/raiz/projeto/pasta/sub/vistas.png "Quatro vistas")');
  });

  it('destino entre <…>, espaços codificados e acentos', () => {
    expect(run('[a](<Arte Final/ação.png>) [b](Arte%20Final/a%C3%A7%C3%A3o.png) [c](Arte%20Final/ação.png)')).toBe(
      '[a](</raiz/projeto/Arte Final/ação.png>) [b](</raiz/projeto/Arte Final/ação.png>) [c](</raiz/projeto/Arte Final/ação.png>)',
    );
  });

  it('parênteses no nome usam <…>; & e codificação inválida ficam como estão', () => {
    expect(run('[a](<a(b).png>)')).toBe('[a](</raiz/projeto/a(b).png>)');
    const md = '[b](x&y.png) [c](arte%ZZ.png)';
    expect(run(md)).toBe(md);
  });

  it('não-imagem também vira absoluto (para baixar)', () => {
    expect(run('[Aseprite](fonte.aseprite)')).toBe('[Aseprite](/raiz/projeto/fonte.aseprite)');
  });

  it('sem cwd, ou cwd relativo, nada muda', () => {
    expect(resolveLocalLinks('[a](arte.png)', undefined, exists, allowed)).toBe('[a](arte.png)');
    expect(run('[a](arte.png)', 'raiz/projeto')).toBe('[a](arte.png)');
  });

  it('sem `allowed` aceitar nada muda, mesmo se o arquivo existe', () => {
    expect(resolveLocalLinks('[a](arte.png)', CWD, exists, () => false)).toBe('[a](arte.png)');
  });
});

describe('links locais nas entradas assistant dos três runtimes', () => {
  let dir: string;
  let proj: string;

  beforeAll(() => {
    dir = realpathSync(mkdtempSync(join(tmpdir(), 'habblaud-links-')));
    proj = join(dir, 'projeto');
    mkdirSync(join(proj, 'sub'), { recursive: true });
    mkdirSync(join(proj, '.oculta'), { recursive: true });
    writeFileSync(join(proj, 'arte.png'), 'x');
    writeFileSync(join(proj, 'sub', 'fonte.aseprite'), 'x');
    writeFileSync(join(proj, '.oculta', 'x.png'), 'x');
    writeFileSync(join(dir, 'fora.png'), 'x');
  });
  afterAll(() => rmSync(dir, { recursive: true, force: true }));
  afterEach(() => configureLocalLinks(undefined));

  const MD = '![a](arte.png) [b](sub/fonte.aseprite) [c](.oculta/x.png) [d](../fora.png)';
  const expected = () => `![a](${proj}/arte.png) [b](${proj}/sub/fonte.aseprite) [c](.oculta/x.png) [d](../fora.png)`;

  function assistantText(parser: TerminalParser, lines: string[]): string | undefined {
    const out: TerminalEntry[] = lines.flatMap((l) => parser.push(l));
    const e = out.find((x) => x.kind === 'assistant');
    return e?.kind === 'assistant' ? e.text : undefined;
  }

  const claude = () =>
    assistantText(createTerminalParser(), [
      JSON.stringify({ type: 'assistant', uuid: 'a1', timestamp: '2026-11-20T10:00:00Z', cwd: proj, message: { id: 'm1', role: 'assistant', content: [{ type: 'text', text: MD }] } }),
    ]);
  const omp = () =>
    assistantText(createOmpTerminalParser(), [
      JSON.stringify({ type: 'session', id: 's', timestamp: '2026-11-20T10:00:00Z', cwd: proj }),
      JSON.stringify({ type: 'message', id: 'm1', timestamp: '2026-11-20T10:00:01Z', message: { role: 'assistant', content: [{ type: 'text', text: MD }] } }),
    ]);
  const codex = () =>
    assistantText(createCodexTerminalParser(), [
      JSON.stringify({ type: 'turn_context', timestamp: '2026-11-20T10:00:00Z', payload: { cwd: proj } }),
      JSON.stringify({ type: 'event_msg', timestamp: '2026-11-20T10:00:01Z', payload: { type: 'agent_message', message: MD } }),
    ]);

  it.each([
    ['claude', claude],
    ['omp', omp],
    ['codex', codex],
  ])('%s: com raízes configuradas, só os arquivos visíveis dentro delas viram absolutos', (_, read) => {
    configureLocalLinks(() => [{ path: proj, label: 'projeto' }]);
    expect(read()).toBe(expected());
  });

  it.each([
    ['claude', claude],
    ['omp', omp],
    ['codex', codex],
  ])('%s: sem raízes (arquivos desligados) o texto fica como veio', (_, read) => {
    expect(read()).toBe(MD);
  });
});
