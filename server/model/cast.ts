import { copyFileSync, constants, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { nameKey, parseAppearanceParts, parseCharacterName, parseSeed } from '../../shared/appearance';
import type { CastMember } from '../../shared/types';
import { tr } from '../../shared/i18n';
import { errMsg, log } from '../log';

export type { CastMember } from '../../shared/types';
const MAX_AGE = 60 * 24 * 3_600_000;
const ID = /^[a-z0-9-]{1,40}$/;

export function parseCastMember(raw: unknown): CastMember | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const v = raw as Record<string, unknown>;
  const name = parseCharacterName(v.name);
  const seed = parseSeed(v.seed);
  const parts = v.parts === undefined ? undefined : parseAppearanceParts(v.parts);
  if (typeof v.id !== 'string' || !ID.test(v.id) || !name || seed === null || parts === null || (v.look !== 'f' && v.look !== 'm') || (v.kind !== 'main' && v.kind !== 'advisor' && v.kind !== 'sub')) return null;
  if (!Array.isArray(v.rooms) || v.rooms.length > 50 || v.rooms.some((r) => typeof r !== 'string' || !r || r.length > 1024)) return null;
  if (v.match !== undefined && (!Array.isArray(v.match) || v.match.length > 20 || v.match.some((m) => typeof m !== 'string' || !m.trim() || m.length > 60))) return null;
  if (v.home !== undefined && (v.kind !== 'main' || typeof v.home !== 'string' || !v.home || v.home.length > 1024 || !v.rooms.includes(v.home))) return null;
  return { id: v.id, name, seed, look: v.look, kind: v.kind, rooms: [...v.rooms], ...(parts && Object.keys(parts).length ? { parts } : {}), ...(v.match !== undefined ? { match: [...v.match as string[]] } : {}), ...(v.home !== undefined ? { home: v.home as string } : {}) };
}

export class CastStore {
  private members: CastMember[] = [];
  private bindings = new Map<string, { cast: string; at: number }>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private readonly now: () => number;
  private unreadable = false;

  constructor(private readonly file: string | null, opts: { now?: () => number } = {}) {
    this.now = opts.now ?? Date.now;
  }

  load(): void {
    if (!this.file) return;
    let raw: string;
    try {
      raw = readFileSync(this.file, 'utf8');
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') return this.migrate();
      this.unreadable = true;
      log.warnOnce('cast-read', tr('cast.json ilegível ({0}); começando do zero.', [errMsg(err)]));
      return;
    }
    try {
      const data = JSON.parse(raw);
      if (data.version !== 1 || !Array.isArray(data.members) || !data.bindings || typeof data.bindings !== 'object' || Array.isArray(data.bindings)) throw new Error('invalid cast file');
      for (const item of data.members) {
        const member = parseCastMember(item);
        if (member && this.members.length < 200 && !this.members.some((m) => m.id === member.id || nameKey(m.name) === nameKey(member.name))) this.members.push(member);
      }
      for (const [session, value] of Object.entries(data.bindings)) {
        const v = value as { cast?: unknown; at?: unknown } | null;
        if (session.length <= 200 && v && typeof v.cast === 'string' && this.members.some((m) => m.id === v.cast) && typeof v.at === 'number' && this.now() - v.at < MAX_AGE) this.bindings.set(session, { cast: v.cast, at: v.at });
      }
      this.trimBindings();
    } catch (err) {
      this.members = [];
      this.bindings.clear();
      this.unreadable = true;
      log.warnOnce('cast-read', tr('cast.json ilegível ({0}); começando do zero.', [errMsg(err)]));
    }
  }

  private migrate(): void {
    if (!this.file) return;
    const namesFile = join(dirname(this.file), 'names.json');
    let rawNames: string;
    try { rawNames = readFileSync(namesFile, 'utf8'); }
    catch (err) { if ((err as NodeJS.ErrnoException).code === 'ENOENT') return; throw err; }
    let data: { rooms?: unknown };
    try { data = JSON.parse(rawNames); }
    catch { return; }
    {
      if (!data || !data.rooms || typeof data.rooms !== 'object' || Array.isArray(data.rooms)) return;
      for (const [room, raw] of Object.entries(data.rooms)) {
        const old = raw as Record<string, unknown>;
        if (!old || typeof old !== 'object') throw new Error('invalid room');
        const id = this.nextId(typeof old.name === 'string' ? old.name : '');
        const member = parseCastMember({ ...old, id, kind: 'main', home: room, rooms: [room] });
        if (!member || this.members.length >= 200 || this.members.some((m) => nameKey(m.name) === nameKey(member.name))) throw new Error('invalid or duplicate room character');
        this.members.push(member);
        if (typeof old.owner === 'string' && old.owner && old.owner.length <= 200) this.bindings.set(old.owner, { cast: id, at: typeof old.at === 'number' ? old.at : this.now() });
      }
      if (!this.members.length) return;
      try { copyFileSync(namesFile, `${namesFile}.pre-031`, constants.COPYFILE_EXCL); } catch (err) { if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err; }
      const tmp = `${this.file}.${process.pid}.tmp`;
      writeFileSync(tmp, JSON.stringify({ version: 1, members: this.members, bindings: Object.fromEntries(this.bindings) }));
      renameSync(tmp, this.file);
    }
  }

  nextId(name: string): string {
    const base = name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'personagem';
    let id = base;
    for (let n = 2; this.members.some((m) => m.id === id); n++) { const suffix = `-${n}`; id = base.slice(0, 40 - suffix.length) + suffix; }
    return id;
  }

  list(): CastMember[] { return this.members.map((m) => structuredClone(m)); }
  get(id: string): CastMember | undefined { const m = this.members.find((item) => item.id === id); return m && structuredClone(m); }
  put(member: CastMember): boolean {
    const parsed = parseCastMember(member);
    if (!parsed || this.members.some((m) => m.id !== parsed.id && nameKey(m.name) === nameKey(parsed.name))) return false;
    const index = this.members.findIndex((m) => m.id === parsed.id);
    if (index < 0) { if (this.members.length >= 200) return false; this.members.push(parsed); } else this.members[index] = parsed;
    this.scheduleFlush();
    return true;
  }
  delete(id: string): boolean {
    const index = this.members.findIndex((m) => m.id === id);
    if (index < 0) return false;
    this.members.splice(index, 1);
    for (const [key, value] of this.bindings) if (value.cast === id) this.bindings.delete(key);
    this.scheduleFlush();
    return true;
  }
  binding(session: string): string | undefined {
    const binding = this.bindings.get(session);
    return binding && this.now() - binding.at < MAX_AGE ? binding.cast : undefined;
  }
  bind(session: string, cast: string): void {
    this.bindings.set(session, { cast, at: this.now() });
    this.trimBindings();
    this.scheduleFlush();
  }
  unbind(session: string): void { if (this.bindings.delete(session)) this.scheduleFlush(); }
  private trimBindings(): void {
    this.bindings = new Map([...this.bindings].filter(([, b]) => this.now() - b.at < MAX_AGE).sort((a, b) => b[1].at - a[1].at).slice(0, 4000));
  }
  private scheduleFlush(): void {
    if (!this.file || this.timer || this.unreadable) return;
    this.timer = setTimeout(() => { this.timer = null; this.flush(); }, 2_000);
    this.timer.unref?.();
  }
  flush(): void {
    clearTimeout(this.timer ?? undefined);
    this.timer = null;
    if (!this.file || this.unreadable) return;
    this.trimBindings();
    try {
      mkdirSync(dirname(this.file), { recursive: true });
      const tmp = `${this.file}.${process.pid}.tmp`;
      writeFileSync(tmp, JSON.stringify({ version: 1, members: this.members, bindings: Object.fromEntries(this.bindings) }));
      renameSync(tmp, this.file);
      log.clearOnce('cast-write');
    } catch (err) {
      log.warnOnce('cast-write', tr('Não foi possível gravar {0} ({1}); o elenco vale só nesta execução.', [this.file, errMsg(err)]));
    }
  }
}
