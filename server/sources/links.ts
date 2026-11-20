// Links Markdown do agente para arquivos do projeto com caminho relativo à pasta da sessão ([x](pasta/arte.png)):
// na saída do terminal o destino vira o caminho absoluto, que os clientes já sabem desenhar (imagem) ou baixar
// (/api/files/raw). Só quando o arquivo existe e /api/files o serviria (locate: raízes, ocultos, links que saem da raiz).
// Qualquer dúvida (código, consulta, fragmento, escape estranho) deixa o destino como veio. O transcript não muda.
import { statSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import type { FileRoot } from '../../shared/types';
import { locate, resolveRoots } from '../files/browse';

/** Quanto tempo vale a resposta do disco para um caminho (o stream repete o mesmo texto a cada atualização). */
const CACHE_TTL_MS = 5_000;
const CACHE_MAX = 1_000;

/** Link ou imagem inline: `[texto](destino "título")`; o destino é o grupo 3. Código já vem mascarado com "\n". */
const LINK =
  /(!?\[(?:[^[\]\\]|\\.|\[(?:[^[\]\\]|\\.)*\])*\]\()([ \t]*)(<(?:[^<>\n\\]|\\.)*>|(?:[^\s()<\\]|\\.|\((?:[^\s()\\]|\\.)*\))+)((?:[ \t]+(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\((?:[^()\\]|\\.)*\)))?[ \t]*\))/g;
const FENCE = /^ {0,3}(`{3,}|~{3,})/;
/** Caractere que os clientes leriam diferente do servidor (escape, entidade, <…>) ou que tornaria o caminho ambíguo. */
// eslint-disable-next-line no-control-regex
const UNSAFE = /[?#\\<>&\u0000-\u001f\u007f]/;

const IMG_START = /^[ \t]*<img(?=[ \t/>])/i;
const IMG_END = /[ \t]*\/?>[ \t\r]*$/y;
const IMG_ATTR = /[ \t]+([a-z_:][\w.:-]*)[ \t]*=[ \t]*(?:"([^"\r]*)"|'([^'\r]*)'|([^\s"'`=<>]+))/iy;
// Os dois clientes aceitam no máximo 10 MiB de imagem embutida (IMAGE_MAX_BYTES / MAX_IMAGE_BYTES).
const IMAGE_MAX_BYTES = 10 * 1024 * 1024;
const DATA_IMAGE = /^data:image\/(?:png|jpeg|gif|webp|svg\+xml);base64,/i;
const ENTITIES: Readonly<Record<string, string>> = {
  amp: '&', quot: '"', apos: "'", lt: '<', gt: '>',
};

function decodeEntities(value: string): string {
  return value.replace(/&(amp|quot|apos|lt|gt|#([0-9]+)|#[xX]([0-9a-fA-F]+));/g, (_, name: string, decimal?: string, hex?: string) => {
    if (decimal === undefined && hex === undefined) return ENTITIES[name];
    const cp = hex === undefined ? Number(decimal) : Number.parseInt(hex, 16);
    return cp > 0 && cp <= 0x10ffff && (cp < 0xd800 || cp > 0xdfff) ? String.fromCodePoint(cp) : '\ufffd';
  });
}

function validHttpImage(src: string): boolean {
  const authority = /^https?:\/\/([^/?#]*)/i.exec(src)?.[1];
  // Como no Kotlin: exija autoridade explícita e rejeite até credenciais vazias.
  if (!authority?.split(':', 1)[0].trim() || authority.includes('@') || /\s/.test(src)) return false;
  // Não permita que o leitor Markdown decodifique uma segunda entidade no destino.
  if (/&(?:#[xX]?[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);/.test(src)) return false;
  try {
    return new URL(src).hostname !== '';
  } catch {
    return false;
  }
}

function dataImage(src: string): string | undefined {
  const header = DATA_IMAGE.exec(src)?.[0];
  if (!header) return undefined;
  const length = src.length - header.length;
  if (!length || length % 4 !== 0 || length > Math.ceil(IMAGE_MAX_BYTES / 3) * 4) return undefined;
  const pad = src.endsWith('==') ? 2 : src.endsWith('=') ? 1 : 0;
  if (length / 4 * 3 - pad > IMAGE_MAX_BYTES) return undefined;
  const encoded = src.slice(header.length, src.length - pad);
  if (/[^A-Za-z0-9+/]/.test(encoded)) return undefined;
  // Base64 padrão: os bits sem dados do último sexteto devem ser zero.
  if (pad && !(pad === 2 ? /[AQgw]$/ : /[AEIMQUYcgkosw048]$/).test(encoded)) return undefined;
  return header.toLowerCase() + src.slice(header.length);
}

function markdownDestination(dest: string): string {
  return /[\s()]/.test(dest) ? `<${dest}>` : dest;
}

function htmlImage(
  line: string, start: number, rewrite: (dest: string) => string | undefined, absolute: (dest: string) => string | undefined,
): string {
  const seen = new Set<string>();
  let src: string | undefined;
  let alt: string | undefined;
  for (let pos = start; ;) {
    IMG_END.lastIndex = pos;
    if (IMG_END.test(line)) break;
    IMG_ATTR.lastIndex = pos;
    const attr = IMG_ATTR.exec(line);
    if (!attr) return line;
    const name = attr[1].toLowerCase();
    if (seen.has(name)) return line;
    seen.add(name);
    const value = attr[2] ?? attr[3] ?? attr[4];
    if (name === 'src') src = decodeEntities(value);
    if (name === 'alt') alt = decodeEntities(value);
    pos = IMG_ATTR.lastIndex;
  }
  // Não interprete escapes Markdown, delimitadores angulares ou quebras vindas do HTML.
  // eslint-disable-next-line no-control-regex
  if (!src || /[\\<>\u0000-\u001f\u007f\u2028\u2029]/.test(src)) return line;
  let dest: string | undefined;
  if (/^https?:\/\//i.test(src)) {
    if (validHttpImage(src)) dest = markdownDestination(src);
  } else if (/^data:/i.test(src)) {
    dest = dataImage(src);
  } else if (src.startsWith('/')) {
    if (!src.startsWith('//')) dest = absolute(src);
  } else {
    dest = rewrite(src);
  }
  if (!dest || /[\u2028\u2029]/.test(dest)) return line;
  const label = (alt ?? 'imagem').trim().replace(/\s+/g, ' ').match(/^.{0,200}/u)![0];
  // Inclui a pontuação de autolinks GFM, tachado, fórmulas, entidades e células de tabela.
  return `![${label.replace(/[\\[\]*_`<>!()~&:.@|$]/g, '\\$&')}](${dest})`;
}

/** Caminho do destino como o agente quis dizer, ou undefined se não é um caminho relativo sem dúvida. */
function relativeTarget(dest: string): string | undefined {
  const raw = (dest.startsWith('<') ? dest.slice(1, -1) : dest).replace(/\\([!-/:-@[-`{-~])/g, '$1');
  if (!raw || raw.includes('&') || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(raw) || /^[/#?~]/.test(raw)) return undefined;
  let path: string;
  try {
    path = decodeURIComponent(raw);
  } catch {
    return undefined;
  }
  if (UNSAFE.test(path) || /^[/~]/.test(path)) return undefined;
  return path;
}

/**
 * Resolve caminhos relativos de Markdown e converte tags img isoladas (fora de código).
 * Destinos locais precisam passar por `exists` e `allowed`; relativos também exigem `cwd` absoluto.
 */
export function resolveLocalLinks(markdown: string, cwd: string | undefined, exists: (p: string) => boolean, allowed: (p: string) => boolean): string {
  const hasImages = /<img(?=[ \t/>])/i.test(markdown);
  const hasLinks = cwd && isAbsolute(cwd) && markdown.includes('](');
  if (!hasImages && !hasLinks) return markdown;
  const absolute = (abs: string): string | undefined => {
    if (UNSAFE.test(abs) || !exists(abs) || !allowed(abs)) return undefined;
    // Os clientes usam o destino como veio (sem decodificar %): espaço e parênteses vão entre <…>.
    return markdownDestination(abs);
  };
  const rewrite = (dest: string): string | undefined => {
    if (!cwd || !isAbsolute(cwd)) return undefined;
    const rel = relativeTarget(dest);
    if (rel === undefined) return undefined;
    return absolute(resolve(cwd, rel));
  };
  const lines = markdown.split('\n');
  let fence: string | undefined;
  let prevBlank = true;
  let indented = false;
  let ticks = 0;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const blank = !line.trim();
    const open = fence ? undefined : FENCE.exec(line);
    if (fence) {
      const m = FENCE.exec(line);
      if (m && m[1][0] === fence[0] && m[1].length >= fence.length && !line.slice(m[0].length).trim()) fence = undefined;
    } else if (open && (open[1][0] === '~' || !line.slice(open[0].length).includes('`'))) {
      fence = open[1];
      indented = false;
      ticks = 0;
    } else if (!blank && /^( {4}|\t)/.test(line) && (prevBlank || indented)) {
      indented = true;
    } else {
      if (!blank) indented = false;
      if (blank) ticks = 0;
      const image = hasImages && IMG_START.exec(line);
      if (image && !ticks) {
        if (!/^( {4}| {0,3}\t)/.test(line)) lines[i] = htmlImage(line, image[0].length, rewrite, absolute);
      } else if ((hasLinks && line.includes('](')) || (hasImages && (ticks || line.includes('`')))) {
        const code = maskInlineCode(line, ticks);
        ticks = hasImages ? code.ticks : 0;
        if (!image && hasLinks && code.masked !== undefined && line.includes('](')) lines[i] = rewriteLine(line, code.masked, rewrite);
      }
    }
    prevBlank = blank;
  }
  return lines.join('\n');
}

/** Mascara trechos de código preservando os offsets; um trecho aberto pode continuar até o fim do parágrafo. */
function maskInlineCode(line: string, ticks: number): { masked?: string; ticks: number } {
  let masked = '';
  let start = 0;
  let codeStart = 0;
  for (let i = 0; i < line.length; ) {
    if (!ticks && line[i] === '\\') {
      i += 2;
      continue;
    }
    if (line[i] !== '`') {
      i++;
      continue;
    }
    let end = i + 1;
    while (line[end] === '`') end++;
    const run = end - i;
    if (ticks === run) {
      masked += '\n'.repeat(end - codeStart);
      start = end;
      ticks = 0;
    } else if (!ticks) {
      masked += line.slice(start, i);
      codeStart = i;
      ticks = run;
    }
    i = end;
  }
  return ticks ? { ticks } : { masked: masked + line.slice(start), ticks };
}

/** Reescreve só os destinos relativos da linha, com código inline já mascarado. */
function rewriteLine(line: string, masked: string, rewrite: (dest: string) => string | undefined): string {
  let out = '';
  let last = 0;
  for (const m of masked.matchAll(LINK)) {
    const start = m.index + m[1].length + m[2].length;
    const dest = line.slice(start, start + m[3].length);
    const next = rewrite(dest);
    if (next === undefined) continue;
    out += line.slice(last, start) + next;
    last = start + dest.length;
  }
  return last ? out + line.slice(last) : line;
}

type RootCandidates = () => readonly { path: string; label: string; roomId?: string }[];

function cached(fn: (p: string) => boolean, now: () => number): (p: string) => boolean {
  const cache = new Map<string, { ok: boolean; at: number }>();
  return (p) => {
    const t = now();
    const hit = cache.get(p);
    if (hit && t - hit.at < CACHE_TTL_MS) return hit.ok;
    const ok = fn(p);
    cache.delete(p);
    cache.set(p, { ok, at: t });
    if (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value as string);
    return ok;
  };
}

let check: { exists: (p: string) => boolean; allowed: (p: string) => boolean } | undefined;

/**
 * Configura a validação de arquivos com as raízes de /api/files (index.ts); `undefined` recusa arquivos locais.
 * Disco e raízes com cache curto: o parser roda a cada atualização do stream.
 */
export function configureLocalLinks(roots: RootCandidates | undefined, now: () => number = Date.now): void {
  if (!roots) {
    check = undefined;
    return;
  }
  let resolved: { roots: FileRoot[]; at: number } | undefined;
  const currentRoots = (): FileRoot[] => {
    const t = now();
    if (!resolved || t - resolved.at >= CACHE_TTL_MS) resolved = { roots: resolveRoots(roots()), at: t };
    return resolved.roots;
  };
  check = {
    exists: cached((p) => {
      try {
        return statSync(p).isFile();
      } catch {
        return false;
      }
    }, now),
    allowed: cached((p) => {
      const found = locate(p, currentRoots());
      return !('error' in found) && !found.isDir;
    }, now),
  };
}

const denyLocalFile = (): boolean => false;

/** Normaliza a saída assistant; arquivos locais só são resolvidos quando o navegador de arquivos está ligado. */
export function localLinks(markdown: string, cwd: string | undefined): string {
  return resolveLocalLinks(markdown, check ? cwd : undefined, check?.exists ?? denyLocalFile, check?.allowed ?? denyLocalFile);
}
