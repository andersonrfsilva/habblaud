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
 * Troca o destino relativo de links e imagens inline (fora de código) pelo caminho absoluto `cwd/relativo`
 * normalizado, quando `exists` e `allowed` aceitam o resultado. Sem `cwd` absoluto, nada muda.
 */
export function resolveLocalLinks(markdown: string, cwd: string | undefined, exists: (p: string) => boolean, allowed: (p: string) => boolean): string {
  if (!cwd || !isAbsolute(cwd) || !markdown.includes('](')) return markdown;
  const rewrite = (dest: string): string | undefined => {
    const rel = relativeTarget(dest);
    if (rel === undefined) return undefined;
    const abs = resolve(cwd, rel);
    if (UNSAFE.test(abs) || !exists(abs) || !allowed(abs)) return undefined;
    // Os clientes usam o destino como veio (sem decodificar %): espaço e parênteses vão entre <…>.
    return /[\s()]/.test(abs) ? `<${abs}>` : abs;
  };
  const lines = markdown.split('\n');
  let fence: string | undefined;
  let prevBlank = true;
  let indented = false;
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
    } else if (!blank && /^( {4}|\t)/.test(line) && (prevBlank || indented)) {
      indented = true;
    } else {
      if (!blank) indented = false;
      if (line.includes('](')) lines[i] = rewriteLine(line, rewrite);
    }
    prevBlank = blank;
  }
  return lines.join('\n');
}

/** Reescreve os links de uma linha; trechos de código inline não contam. Crase sem par: a linha fica como está. */
function rewriteLine(line: string, rewrite: (dest: string) => string | undefined): string {
  let masked = '';
  for (let i = 0; i < line.length; ) {
    const c = line[i];
    if (c === '\\' && i + 1 < line.length) {
      masked += line.slice(i, i + 2);
      i += 2;
      continue;
    }
    if (c !== '`') {
      masked += c;
      i++;
      continue;
    }
    let n = 0;
    while (line[i + n] === '`') n++;
    let close = -1;
    for (let j = i + n; j < line.length; ) {
      if (line[j] !== '`') {
        j++;
        continue;
      }
      let k = 0;
      while (line[j + k] === '`') k++;
      if (k === n) {
        close = j;
        break;
      }
      j += k;
    }
    if (close < 0) return line;
    masked += '\n'.repeat(close + n - i);
    i = close + n;
  }
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
 * Liga a reescrita com as mesmas raízes de /api/files (index.ts); `undefined` desliga (arquivos desligados, testes).
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

/** Texto `assistant` com os links relativos resolvidos contra a pasta da sessão (se a reescrita estiver ligada). */
export function localLinks(markdown: string, cwd: string | undefined): string {
  return check ? resolveLocalLinks(markdown, cwd, check.exists, check.allowed) : markdown;
}
