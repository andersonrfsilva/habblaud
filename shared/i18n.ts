// Idiomas da interface. O português (pt-BR) continua sendo a língua de origem: cada texto é escrito em PT
// e passa por tr(), que devolve a tradução do idioma ativo ou, na falta dela, o próprio texto em PT.
// Assim nada muda para quem usa em português, e uma tradução incompleta nunca quebra a interface.
//
// Escolha do idioma (uma vez, ao carregar este módulo):
// - navegador: preferência salva (Configurações > Idioma), senão os idiomas do navegador;
// - servidor: HABBLAUD_LANG, senão o idioma do sistema (LC_ALL / LC_MESSAGES / LANG / Intl), menos o inglês (ver
//   serverLocale).
// Os textos de atividade são montados pelo servidor, então seguem o idioma do servidor.
import { DICTIONARIES } from './locales/all';

export type Locale = 'pt-BR' | 'en' | 'es' | 'fr';

/** Na ordem do seletor; o nome de cada idioma vai na própria língua, para quem não entende o idioma atual. */
export const LOCALES: { id: Locale; name: string }[] = [
  { id: 'pt-BR', name: 'Português' },
  { id: 'en', name: 'English' },
  { id: 'es', name: 'Español' },
  { id: 'fr', name: 'Français' },
];

/** Chave da preferência salva no navegador. */
export const LOCALE_STORAGE_KEY = 'habblaud:locale';

/** Dicionários disponíveis (o PT não tem: é a língua de origem). No navegador começa vazio: ver registerDictionary. */
const DICTS: Partial<Record<Locale, Readonly<Record<string, string>>>> = { ...DICTIONARIES };

/** O navegador carrega só o dicionário do idioma ativo e o registra aqui antes de montar a interface. */
export function registerDictionary(l: Locale, dict: Readonly<Record<string, string>>): void {
  DICTS[l] = dict;
}

/** "fr-FR", "fr_CA.UTF-8", "pt", "pt_BR"... -> idioma suportado; undefined se não for nenhum deles. */
export function normalizeLocale(v: string | null | undefined): Locale | undefined {
  const s = (v ?? '').trim().toLowerCase().replace('_', '-');
  if (!s) return undefined;
  if (s === 'fr' || s.startsWith('fr-')) return 'fr';
  if (s === 'pt' || s.startsWith('pt-')) return 'pt-BR';
  if (s === 'en' || s.startsWith('en-')) return 'en';
  if (s === 'es' || s.startsWith('es-')) return 'es';
  return undefined;
}

/**
 * Idioma do servidor (atividades, avisos, logs). HABBLAUD_LANG vale para qualquer idioma. Do sistema (LC_ALL,
 * LC_MESSAGES, LANG e, por último, o Intl) vale francês, espanhol ou português, mas não o inglês: muitas máquinas e
 * containers vêm em inglês por padrão (en_US, ou o en-US do Node sem LANG), mesmo para quem usa outra língua, e o
 * escritório mudaria de idioma sozinho. Para o servidor em inglês: HABBLAUD_LANG=en.
 */
export function serverLocale(env: Record<string, string | undefined>, intl?: string): Locale {
  const own = normalizeLocale(env.HABBLAUD_LANG);
  if (own) return own;
  for (const v of [env.LC_ALL, env.LC_MESSAGES, env.LANG, intl]) {
    const n = normalizeLocale(v);
    if (n && n !== 'en') return n;
  }
  return 'pt-BR';
}

function detect(): Locale {
  const g = globalThis as {
    window?: unknown;
    localStorage?: { getItem(k: string): string | null };
    navigator?: { languages?: readonly string[]; language?: string };
    process?: { env?: Record<string, string | undefined> };
  };
  if (g.window !== undefined) {
    try {
      const saved = normalizeLocale(g.localStorage?.getItem(LOCALE_STORAGE_KEY));
      if (saved) return saved;
    } catch {
      // localStorage bloqueado (modo privado, iframe): segue para os idiomas do navegador.
    }
    for (const l of g.navigator?.languages ?? [g.navigator?.language ?? '']) {
      const n = normalizeLocale(l);
      if (n) return n;
    }
    return 'pt-BR';
  }
  const env = g.process?.env ?? {};
  // Os testes comparam textos em PT: ficam em PT mesmo numa máquina em outro idioma.
  if (env.VITEST) return normalizeLocale(env.HABBLAUD_LANG_TEST) ?? 'pt-BR';
  let intl: string | undefined;
  try {
    intl = Intl.DateTimeFormat().resolvedOptions().locale;
  } catch {
    intl = undefined;
  }
  return serverLocale(env, intl);
}

let current: Locale = detect();

export function getLocale(): Locale {
  return current;
}

/** Troca o idioma ativo (testes; no navegador, a troca pela interface recarrega a página). */
export function setLocale(l: Locale): void {
  current = l;
}

/**
 * Modo sem marca (Habblaud dos usuários do omp-tenant): os textos não citam a ferramenta do backend. No servidor vem
 * de HABBLAUD_SEM_MARCA=1; no navegador, o main.ts pergunta ao servidor (/api/health) antes de carregar o app.
 */
let neutral = (globalThis as { window?: unknown }).window === undefined && (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env?.HABBLAUD_SEM_MARCA === '1';

export function setNeutralBrand(on: boolean): void {
  neutral = on;
}

export function isNeutralBrand(): boolean {
  return neutral;
}

// Nomes de ferramenta soltos no texto; não pega pedaços de caminho, comando ou id (".omp", "omp:install", "omp.json",
// "claude-opus").
const BRAND = /(?<![\w./-])(?:Claude Code|Claude|Codex|oh-my-pi|OpenAI|Anthropic|omp)(?![\w/-]|[:.]\w)/g;

/** Troca o nome da ferramenta por "agente" ("Agente principal (omp)" -> "Agente principal"). Só no modo sem marca. */
export function unbrand(s: string): string {
  if (!neutral) return s;
  const en = current === 'en' || current === 'fr';
  const word = en ? 'agent' : 'agente';
  return s
    .replace(/\s*\((?:Claude Code|Codex|oh-my-pi|OpenAI|Anthropic|omp|npm run [\w:]+)\)/g, '')
    .replace(/omp usage/g, en ? 'usage meter' : 'medidor de uso')
    .replace(BRAND, (_m, at: number, all: string) => (at === 0 || /[.!?:(]\s*$/.test(all.slice(0, at)) ? word[0].toUpperCase() + word.slice(1) : word))
    // "Agente do agente" -> "Agente"; "agentes do agente" -> "agentes"; "agent agents" -> "agents".
    .replace(/\b(agentes?|agents?) (?:(?:do|de|du|of the|of|ao) )?agen(?:te|t)(s?)\b/gi, (_m, a: string, pl: string) => (pl && !a.endsWith('s') ? a + 's' : a));
}

/**
 * Traduz um texto escrito em PT. Lacunas numeradas ({0}, {1}...) recebem `args` na ordem; a tradução pode
 * reordená-las. Sem tradução para o idioma ativo, devolve o texto original.
 */
export function tr(src: string, args?: readonly unknown[]): string {
  const dict = DICTS[current];
  const s = unbrand((dict && dict[src]) || src);
  if (!args || args.length === 0) return s;
  return s.replace(/\{(\d+)\}/g, (m, i: string) => (Number(i) < args.length ? String(args[Number(i)]) : m));
}

/** Idioma no formato do Intl (datas, números). */
export function intlLocale(): string {
  return current === 'fr' ? 'fr-FR' : current === 'en' ? 'en-US' : current === 'es' ? 'es' : 'pt-BR';
}
