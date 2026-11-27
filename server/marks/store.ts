// Sessões fixadas e favoritas do Zaplaud: ficam no servidor para valerem em todos os aparelhos.
// Um arquivo só, <dataDir>/session-marks.json (0600). Sessão sem nenhuma marca não é guardada.
import { renameSync, readFileSync, writeFileSync } from 'node:fs';
import { tr } from '../../shared/i18n';
import { errMsg, log } from '../log';

export interface SessionMark {
  provider: string;
  account: string;
  sessionId: string;
  pinned: boolean;
  favorite: boolean;
}

const ID_MAX = 300;
/** Teto de sessões marcadas; a marca mais antiga sai quando passa. */
export const MARKS_MAX = 2000;

function idStr(v: unknown): v is string {
  return typeof v === 'string' && v.length > 0 && v.length <= ID_MAX;
}

/** Só os campos mandados mudam: dois aparelhos marcando a mesma sessão ao mesmo tempo não apagam a marca um do outro. */
export type MarkPatch = Pick<SessionMark, 'provider' | 'account' | 'sessionId'> & Partial<Pick<SessionMark, 'pinned' | 'favorite'>>;

/** Valida o corpo de POST /api/marks (`pinned` e/ou `favorite`); undefined = inválido. */
export function parseMark(v: unknown): MarkPatch | undefined {
  if (!v || typeof v !== 'object') return undefined;
  const o = v as Record<string, unknown>;
  if (!idStr(o.provider) || !idStr(o.account) || !idStr(o.sessionId)) return undefined;
  if (o.pinned === undefined && o.favorite === undefined) return undefined;
  if ((o.pinned !== undefined && typeof o.pinned !== 'boolean') || (o.favorite !== undefined && typeof o.favorite !== 'boolean')) return undefined;
  return { provider: o.provider, account: o.account, sessionId: o.sessionId, pinned: o.pinned, favorite: o.favorite };
}

const keyOf = (m: Pick<SessionMark, 'provider' | 'account' | 'sessionId'>): string => JSON.stringify([m.provider, m.account, m.sessionId]);

export class SessionMarksStore {
  /** Ordem de inserção = da marca mais antiga para a mais nova. */
  private readonly marks = new Map<string, SessionMark>();

  /** `file` null = só em memória (testes ou diretório de dados indisponível). */
  constructor(private readonly file: string | null) {}

  load(): void {
    if (!this.file) return;
    try {
      const j = JSON.parse(readFileSync(this.file, 'utf8')) as { marks?: unknown };
      for (const item of Array.isArray(j.marks) ? j.marks : []) {
        const mark = parseMark(item);
        if (mark && (mark.pinned || mark.favorite)) this.marks.set(keyOf(mark), { ...mark, pinned: mark.pinned === true, favorite: mark.favorite === true });
      }
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code !== 'ENOENT') log.warn(tr('session-marks.json ilegível ({0}); começando do zero.', [errMsg(err)]));
    }
  }

  list(): SessionMark[] {
    return [...this.marks.values()];
  }

  /** Muda os campos mandados e mantém o outro (as duas marcas falsas = apaga). Devolve se algo mudou; erro de disco propaga e nada muda. */
  set(patch: MarkPatch): boolean {
    const key = keyOf(patch);
    const old = this.marks.get(key);
    const mark: SessionMark = {
      provider: patch.provider, account: patch.account, sessionId: patch.sessionId,
      pinned: patch.pinned ?? old?.pinned ?? false, favorite: patch.favorite ?? old?.favorite ?? false,
    };
    if ((old?.pinned ?? false) === mark.pinned && (old?.favorite ?? false) === mark.favorite) return false;
    const next = new Map(this.marks);
    // Só a sessão nova vai para o fim da fila do teto; mudar uma marca não a rejuvenesce nem troca a ordem da lista.
    if (mark.pinned || mark.favorite) next.set(key, mark);
    else next.delete(key);
    while (next.size > MARKS_MAX) next.delete(next.keys().next().value as string);
    if (this.file) {
      const tmp = `${this.file}.tmp`;
      writeFileSync(tmp, JSON.stringify({ marks: [...next.values()] }), { mode: 0o600 });
      renameSync(tmp, this.file);
    }
    this.marks.clear();
    for (const [k, v] of next) this.marks.set(k, v);
    return true;
  }
}
