// Histórico do terminal do Claude Code (o HistoryProvider 'claude', ver sources/source.ts): as sessões recentes
// de cada conta (abertas ou já encerradas), listadas a partir dos transcripts
// <config>/projects/<projeto>/<sessionId>.jsonl (no Docker, a pasta projects/ de cada conta montada só para leitura). De cada arquivo lê só o começo (projeto, primeira
// atividade e o primeiro prompt) e o fim (título e última atividade), nunca o arquivo inteiro, e guarda o
// resultado enquanto o mtime e o tamanho não mudam. Subagentes (<sessão>/subagents/) ficam de fora.
import { existsSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { open, readdir, stat } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { truncate } from '../../shared/activity';
import type { AgentInfo, RecentSession } from '../../shared/types';
import { errMsg, log } from '../log';
import type { HistoryProvider, HistoryResolveResult, HistoryWindow } from './source';
import { sessionDirOf } from './subagents';
import { createTerminalParser } from './terminal';
import { createTranscriptState, parseLine, titleOf } from './transcript';
import { readCustomTitleFile } from './watcher';
import { tr } from '../../shared/i18n';

/** Janela da listagem: sessões com o transcript modificado nos últimos 7 dias. */
export const HISTORY_DAYS = 7;
export const HISTORY_MAX_AGE_MS = HISTORY_DAYS * 24 * 3600_000;
/** Máximo de sessões listadas (as mais recentes). */
export const HISTORY_LIMIT = 150;
/**
 * Degraus de «Carregar mais» nos Recentes: cada um amplia a janela e o máximo de sessões. O primeiro é o padrão.
 * O último limita o custo: no pior caso, 600 transcripts lidos só no começo e no fim, com cache por mtime.
 */
export const HISTORY_STEPS: readonly { days: number; limit: number }[] = [
  { days: HISTORY_DAYS, limit: HISTORY_LIMIT },
  { days: 30, limit: 300 },
  { days: 90, limit: 450 },
  { days: 365, limit: 600 },
];
/** Janela do degrau `step` (1 = padrão); fora da faixa vale o degrau mais próximo. */
export function historyWindow(step: number): { step: number; days: number; limit: number; maxAgeMs: number } {
  const i = Math.min(HISTORY_STEPS.length, Math.max(1, Math.trunc(step) || 1)) - 1;
  const s = HISTORY_STEPS[i];
  return { step: i + 1, days: s.days, limit: s.limit, maxAgeMs: s.days * 24 * 3600_000 };
}
/** Cache de metadados: guarda tudo o que o maior degrau ainda pode listar, para os degraus não se apagarem entre si. */
export const HISTORY_CACHE_MAX_AGE_MS = HISTORY_STEPS[HISTORY_STEPS.length - 1].days * 24 * 3600_000;
/** Começo do arquivo lido: projeto (cwd), primeira atividade e o primeiro prompt. */
export const HEAD_BYTES = 64 * 1024;
/** Fim do arquivo lido: o Claude Code regrava título e último prompt a cada turno, então eles ficam perto do fim. */
export const TAIL_BYTES = 64 * 1024;
/** Sem título nos últimos 64 KB (ex.: um resultado enorme no fim do arquivo): procura num trecho maior, uma vez. */
export const TAIL_RETRY_BYTES = 512 * 1024;
/** Arquivos lidos ao mesmo tempo (a listagem é assíncrona: não trava o polling do escritório). */
const READ_CONCURRENCY = 8;
const TITLE_MAX = 90;

const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Linhas que dão título à sessão (o mesmo formato compacto que transcript.ts procura no prefixo). */
const TITLE_MARKERS = ['"type":"custom-title"', '"type":"agent-name"', '"type":"ai-title"', '"type":"last-prompt"'];

/** O id de uma sessão do Claude Code tem formato de UUID (é também o nome do transcript). */
export function isSessionId(v: string): boolean {
  return SESSION_ID.test(v);
}

export interface HistoryAccount {
  id: string;
  /** Config dir lido por este processo (no Docker, o caminho montado). */
  dir: string;
}

export interface HistoryOptions {
  accounts: () => readonly HistoryAccount[];
  /** Agente principal da sessão, se ela ainda estiver aberta no escritório. */
  openAgentOf: (account: string, sessionId: string) => string | undefined;
  now?: () => number;
  maxAgeMs?: number;
  limit?: number;
}

/** O que o começo e o fim do transcript dizem sobre a sessão. */
export interface SessionMeta {
  project?: string;
  title?: string;
  firstAt?: number;
  lastAt?: number;
}

interface Candidate {
  account: string;
  sessionId: string;
  projectDir: string;
  path: string;
  mtimeMs: number;
  size: number;
}

/** Resultado de resolve(): o caminho do transcript (com o parser do terminal do Claude Code) ou o erro HTTP. */
export type ResolveResult = HistoryResolveResult;

/** Agente principal (ainda no escritório e não encerrado) da sessão `sessionId` da conta. */
export function openMainAgent(agents: readonly AgentInfo[], account: string, sessionId: string): string | undefined {
  return agents.find((a) => a.kind === 'main' && a.account === account && a.sessionId === sessionId && a.status !== 'offline')?.id;
}

export class SessionHistory implements HistoryProvider {
  readonly provider = 'claude' as const;
  /** Metadados por caminho, válidos enquanto mtime e tamanho forem os mesmos. */
  private cache = new Map<string, { mtimeMs: number; size: number; meta: SessionMeta }>();
  private readonly now: () => number;
  private readonly maxAgeMs: number;
  private readonly limit: number;
  /** Arquivos lidos de verdade (sem cache) desde o início: só para os testes. */
  reads = 0;

  constructor(private readonly opts: HistoryOptions) {
    this.now = opts.now ?? Date.now;
    this.maxAgeMs = opts.maxAgeMs ?? HISTORY_MAX_AGE_MS;
    this.limit = opts.limit ?? HISTORY_LIMIT;
  }

  hasAccount(account: string): boolean {
    return this.opts.accounts().some((a) => a.id === account);
  }

  /** Sessões recentes de todas as contas, da atividade mais recente para a mais antiga. */
  async list(window?: HistoryWindow): Promise<RecentSession[]> {
    const now = this.now();
    const maxAgeMs = window?.maxAgeMs ?? this.maxAgeMs;
    const all: Candidate[] = [];
    for (const acc of this.opts.accounts()) all.push(...(await this.candidates(acc, now, maxAgeMs)));
    all.sort((a, b) => b.mtimeMs - a.mtimeMs);
    const picked = all.slice(0, window?.limit ?? this.limit);
    const metas = await mapLimit(picked, READ_CONCURRENCY, (c) => this.metaOf(c));
    // Esquece só o que nenhum degrau lista mais (o cache não cresce sem limite e os degraus não se apagam).
    const keepAge = Math.max(this.maxAgeMs, HISTORY_CACHE_MAX_AGE_MS);
    for (const [path, hit] of this.cache) if (now - hit.mtimeMs > keepAge) this.cache.delete(path);

    const out: RecentSession[] = [];
    picked.forEach((c, i) => {
      const meta = metas[i];
      const agentId = this.opts.openAgentOf(c.account, c.sessionId);
      // Sem nenhuma linha com horário (sessão aberta e fechada sem conversa): não há o que mostrar.
      if (!agentId && meta.firstAt === undefined && meta.lastAt === undefined) return;
      const s: RecentSession = {
        account: c.account,
        sessionId: c.sessionId,
        projectDir: c.projectDir,
        lastAt: meta.lastAt ?? c.mtimeMs,
        size: c.size,
        open: !!agentId,
      };
      if (meta.project) s.project = meta.project;
      if (meta.title) s.title = meta.title;
      if (meta.firstAt !== undefined) s.firstAt = meta.firstAt;
      if (agentId) s.agentId = agentId;
      out.push(s);
    });
    return out.sort((a, b) => b.lastAt - a.lastAt);
  }

  /**
   * Transcript da sessão `sessionId` da conta, validado: a conta precisa ser uma das conhecidas, o id precisa
   * ter formato de UUID e o arquivo (com links resolvidos) precisa ficar dentro da pasta projects/ da conta.
   */
  resolve(account: string, sessionId: string): ResolveResult {
    if (!isSessionId(sessionId)) return { status: 400, error: tr('id de sessão inválido') };
    const acc = this.opts.accounts().find((a) => a.id === account);
    if (!acc) return { status: 404, error: tr('conta desconhecida') };
    const projects = join(acc.dir, 'projects');
    const notFound: ResolveResult = { status: 404, error: tr('sessão não encontrada') };
    let root: string;
    let dirs: string[];
    try {
      root = realpathSync(projects);
      dirs = readdirSync(projects);
    } catch {
      return notFound;
    }
    const name = `${sessionId}.jsonl`;
    for (const d of dirs) {
      const candidate = join(projects, d, name);
      if (!existsSync(candidate)) continue;
      try {
        const real = realpathSync(candidate);
        // Um link dentro de projects/ apontando para fora não serve.
        if (!real.startsWith(root + sep) || !statSync(real).isFile()) continue;
        return { path: real, createParser: createTerminalParser };
      } catch {
        // sumiu entre a listagem e a leitura
      }
    }
    return notFound;
  }

  /** Transcripts de primeiro nível (um por sessão) modificados dentro da janela. */
  private async candidates(acc: HistoryAccount, now: number, maxAgeMs: number): Promise<Candidate[]> {
    const projects = join(acc.dir, 'projects');
    const out: Candidate[] = [];
    let dirs: string[];
    try {
      dirs = (await readdir(projects, { withFileTypes: true })).filter((d) => d.isDirectory()).map((d) => d.name);
    } catch {
      return out; // conta sem projects/ (ainda)
    }
    for (const projectDir of dirs) {
      let names: string[];
      try {
        names = await readdir(join(projects, projectDir));
      } catch {
        continue;
      }
      for (const name of names) {
        if (!name.endsWith('.jsonl')) continue;
        const sessionId = name.slice(0, -'.jsonl'.length);
        if (!isSessionId(sessionId)) continue;
        const path = join(projects, projectDir, name);
        try {
          const st = await stat(path);
          if (!st.isFile() || now - st.mtimeMs > maxAgeMs) continue;
          out.push({ account: acc.id, sessionId, projectDir, path, mtimeMs: st.mtimeMs, size: st.size });
        } catch {
          // apagado no meio da listagem
        }
      }
    }
    return out;
  }

  private async metaOf(c: Candidate): Promise<SessionMeta> {
    const hit = this.cache.get(c.path);
    if (hit && hit.mtimeMs === c.mtimeMs && hit.size === c.size) return hit.meta;
    let meta: SessionMeta;
    try {
      this.reads++;
      meta = await readSessionMeta(c.path, c.size);
    } catch (err) {
      log.warnOnce(`history:${errMsg(err)}`, tr('Histórico de sessões: transcript ilegível ({0}).', [errMsg(err)]));
      meta = {};
    }
    this.cache.set(c.path, { mtimeMs: c.mtimeMs, size: c.size, meta });
    return meta;
  }
}

/** Lê o começo e o fim do transcript (de tamanho `size`) e extrai projeto, título e horários. */
export async function readSessionMeta(path: string, size: number): Promise<SessionMeta> {
  const fh = await open(path, 'r');
  try {
    const read = async (start: number, length: number): Promise<string> => {
      const buf = Buffer.alloc(Math.max(0, length));
      const { bytesRead } = await fh.read(buf, 0, buf.length, start);
      return buf.toString('utf8', 0, bytesRead);
    };
    let head: string[];
    let tail: string[];
    if (size <= HEAD_BYTES + TAIL_BYTES) {
      head = tail = splitLines(await read(0, size), false, false);
    } else {
      // A última linha do começo e a primeira do fim saem cortadas: são descartadas.
      head = splitLines(await read(0, HEAD_BYTES), false, true);
      tail = splitLines(await read(size - TAIL_BYTES, TAIL_BYTES), true, false);
    }
    const state = createTranscriptState();
    const ctx = { idPrefix: '', now: 0, activities: false };
    for (const line of head) parseLine(state, line, ctx);
    if (tail !== head) for (const line of tail) parseLine(state, line, ctx);
    const meta: SessionMeta = {};
    if (state.firstAt !== undefined) meta.firstAt = state.firstAt;
    if (state.lastAt !== undefined) meta.lastAt = state.lastAt;
    const project = projectOf(head);
    if (project) meta.project = project;

    // Como no watcher: o /rename do transcript vale mais; o custom-title.json da sessão cobre o que o fim não trouxe.
    state.customTitle ??= readCustomTitleFile(sessionDirOf(path));
    // O fim não trouxe nenhuma linha de título (ex.: um resultado enorme por último): procura um pouco antes.
    if (size > HEAD_BYTES + TAIL_BYTES && !tail.some(isTitleLine) && !(state.customTitle ?? state.agentName ?? state.aiTitle)) {
      const len = Math.min(TAIL_RETRY_BYTES, size - HEAD_BYTES);
      for (const line of splitLines(await read(size - len, len), true, false)) if (isTitleLine(line)) parseLine(state, line, ctx);
    }
    const title = titleOf(state) ?? firstPrompt(head);
    if (title) meta.title = title;
    return meta;
  } finally {
    await fh.close();
  }
}

function isTitleLine(line: string): boolean {
  return TITLE_MARKERS.some((m) => line.includes(m));
}

function splitLines(text: string, dropFirst: boolean, dropLast: boolean): string[] {
  const lines = text.split('\n');
  if (dropFirst) lines.shift();
  if (dropLast) lines.pop();
  return lines.map((l) => l.replace(/\r$/, '')).filter((l) => l.trim());
}

/** O `cwd` da primeira linha que o traz (as linhas de conversa trazem o diretório do projeto). */
function projectOf(lines: readonly string[]): string | undefined {
  for (const line of lines) {
    if (!line.includes('"cwd"')) continue;
    try {
      const cwd = (JSON.parse(line) as { cwd?: unknown }).cwd;
      if (typeof cwd === 'string' && cwd.trim()) return cwd.trim();
    } catch {
      // linha inválida: tenta a próxima
    }
  }
  return undefined;
}

/** Sem nenhum título no transcript: o primeiro prompt (já limpo e com segredos mascarados pelo parser do terminal). */
function firstPrompt(lines: readonly string[]): string | undefined {
  const parser = createTerminalParser();
  for (const line of lines) {
    let entries;
    try {
      entries = parser.push(line);
    } catch {
      continue;
    }
    const user = entries.find((e) => e.kind === 'user');
    if (user?.kind === 'user' && user.text.trim()) return truncate(user.text, TITLE_MAX);
  }
  return undefined;
}

/** map() assíncrono com no máximo `limit` tarefas ao mesmo tempo (mantém a ordem). */
async function mapLimit<T, R>(items: readonly T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return out;
}
