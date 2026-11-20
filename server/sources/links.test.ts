import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { tokenizeInline } from '../../client/src/ui/markdown';
import type { TerminalEntry } from '../../shared/types';
import { createCodexTerminalParser } from './codex/terminal';
import { configureLocalLinks, localLinks, resolveLocalLinks } from './links';
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

describe('tag img isolada', () => {
  it.each([
    ['<img src="sub/a.png" alt="Ânfora">', '![Ânfora]'],
    ['  <IMG SRC="sub/a.png" alt="Ânfora" width="200" height="100" title="Ignorado" />  ', '![Ânfora]'],
    ["<img alt='Ânfora' src='sub/a.png'>", '![Ânfora]'],
    ['<img src=sub/a.png />', '![imagem]'],
  ])('converte %s', (md, label) => {
    const path = `${CWD}/sub/a.png`;
    expect(resolveLocalLinks(md, CWD, (p) => p === path, allowed)).toBe(`${label}(${path})`);
  });

  it('delimita espaços e parênteses no destino e aceita um absoluto permitido', () => {
    expect(run('<img src="a(b).png">')).toBe(`![imagem](<${CWD}/a(b).png>)`);
    expect(run(`<img src="${CWD}/Arte Final/ação.png">`)).toBe(`![imagem](<${CWD}/Arte Final/ação.png>)`);
  });

  it('normaliza e escapa o alt; o leitor web lê seu conteúdo como um único texto', () => {
    const md = '<img src="arte.png" alt=" ] &#91; ( ) * _ ` &lt;b&gt; ![x](y) &#10; &quot; &#39; \\ ~~ www.exemplo.com https://exemplo.com a@b.com &amp;#91; $x$ ">';
    const out = run(md);
    const suffix = `](${CWD}/arte.png)`;
    expect(out.startsWith('![') && out.endsWith(suffix)).toBe(true);
    expect(tokenizeInline(out.slice(2, -suffix.length))).toEqual([
      { type: 'text', text: '] [ ( ) * _ ` <b> ![x](y) " \' \\ ~~ www.exemplo.com https://exemplo.com a@b.com &#91; $x$' },
    ]);
    expect(out).toContain('\\$x\\$');
    expect(run(`<img src="arte.png" alt="${'🖼'.repeat(201)}">`)).toBe(`![${'🖼'.repeat(200)}](${CWD}/arte.png)`);
  });

  it.each([
    'javascript:alert(1)', 'java&#x73;cript:alert(1)', 'file:///tmp/a.png', '//host/a.png', '~arte.png',
    '../../fora/segredo.png', '/fora/segredo.png', 'ausente.png', '/raiz/projeto/ausente.png', '',
    '&lt;arte.png&gt;', '\\!arte.png', 'arte%ZZ.png', 'x&amp;y.png', 'arte.png&#10;', 'arte.png&#13;',
    'https://user:pass@host/x.png', 'https://@host/x.png', 'https:///x.png', 'https://:80/x.png',
    'https://host:99999/x.png', 'https://host\\x.png', 'https://host/x y.png', 'https://host/x&gt;.png',
    'https://host/x.png?a=1&amp;copy;', 'https://host/x.png?a=1&amp;#10;',
    'data:image/not-known;base64,AA==', 'data:image/png;base64,a===', 'data:image/png;base64,AB==',
    'data:image/png;base64,', 'data:image/png;base64,AA== lixo',
  ])('preserva src recusado: %s', (src) => {
    const md = `<img src="${src}" alt="legenda ![x](arte.png)">`;
    expect(run(md)).toBe(md);
  });

  it('aceita HTTP(S) sem credenciais e decodifica entidades uma vez', () => {
    expect(run('<img src="https://host/x.png" alt="alt">')).toBe('![alt](https://host/x.png)');
    expect(run('<img src="HTTP://host/x(1).png?x=1&amp;y=2" alt="alt">')).toBe('![alt](<HTTP://host/x(1).png?x=1&y=2>)');
  });

  it('parênteses na URL não fecham a imagem nem abrem outra', () => {
    const dest = 'https://host/a.png)![x](https://example.com/y.png)';
    expect(run(`<img src="${dest}">`)).toBe(`![imagem](<${dest}>)`);
  });

  it('aceita base64 de imagem até 10 MiB, mas não acima', () => {
    const dest = `data:image/png;base64,${'AAAA'.repeat(Math.floor((10 * 1024 * 1024) / 3))}`;
    const convert = (payload: string) => run(`<img src="${dest}${payload}">`);
    expect(convert('AA==') === `![imagem](${dest}AA==)`).toBe(true);
    expect(convert('AAA=') === `<img src="${dest}AAA=">`).toBe(true);
  });

  it.each([
    '<img src="arte.png"> <img src="arte.png">',
    'Use <img src="arte.png"> no texto',
    '<img src="arte.png"><!-- comentário -->',
    '<iframe src="https://host/x.png">',
    '<img src="arte.png" SRC="ausente.png">',
    '<img src="arte.png" title="x" TITLE="y">',
    '<img src=Arte Final/ação.png>',
    '<img src="arte.png>',
    '```html\n<img src="arte.png">\n```',
    '    <img src="arte.png">',
    '\n\t<img src="arte.png">',
    '`<img src="arte.png">`',
    'Código `aberto\n<img src="arte.png">\nfecha aqui`',
  ])('preserva HTML fora da gramática ou em código: %s', (md) => {
    expect(run(md)).toBe(md);
  });

  it('sem cwd, preserva src relativo e converte HTTPS', () => {
    const relative = '<img src="arte.png">';
    expect(resolveLocalLinks(relative, undefined, exists, allowed)).toBe(relative);
    expect(resolveLocalLinks('<img src="https://host/x.png">', undefined, exists, allowed)).toBe('![imagem](https://host/x.png)');
    configureLocalLinks(undefined);
    expect(localLinks(relative, CWD)).toBe(relative);
    expect(localLinks('<img src="https://host/x.png">', CWD)).toBe('![imagem](https://host/x.png)');
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
    writeFileSync(join(proj, 'sub', 'a.png'), 'x');
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

  it('converte só arquivos locais acessíveis ao montar a entrada assistant do omp', () => {
    configureLocalLinks(() => [{ path: proj, label: 'projeto' }]);
    const kept = '<img src=".oculta/x.png">\n<img src="../fora.png">';
    const text = assistantText(createOmpTerminalParser(), [
      JSON.stringify({ type: 'session', id: 's', timestamp: '2026-11-20T10:00:00Z', cwd: proj }),
      JSON.stringify({ type: 'message', id: 'm1', timestamp: '2026-11-20T10:00:01Z', message: { role: 'assistant', content: [{ type: 'text', text: `<img src="sub/a.png" alt="Ânfora">\n${kept}` }] } }),
    ]);
    expect(text).toBe(`![Ânfora](${proj}/sub/a.png)\n${kept}`);
  });

  it.each([
    ['claude', claude],
    ['omp', omp],
    ['codex', codex],
  ])('%s: sem raízes (arquivos desligados) o texto fica como veio', (_, read) => {
    expect(read()).toBe(MD);
  });
});
