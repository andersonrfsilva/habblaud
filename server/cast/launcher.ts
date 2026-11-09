import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { CAST_IDLE_MINUTES, NEW_CAST_PREFIX, OMP_EDITABLE_ROLES, type AgentInfo, type CastJob, type CastJobInfo, type CastMember, type OmpRoles } from '../../shared/types';
import { tr } from '../../shared/i18n';
import { HOST_PRESENCE_MS, KEEP_MS, PROMPT_MAX, QUEUED_TIMEOUT_MS, SENT_TIMEOUT_MS } from '../spawn/spawn';
import { normalizeCwd } from '../model/rooms';
import { ZeroClawCast, type ZeroClawCastJob } from './zeroclaw';
import type { ConnectorEvent } from '../sources/zeroclaw/connector';

type Entry = ({ runtime: 'omp'; job: CastJob } | { runtime: 'zeroclaw'; job: ZeroClawCastJob }) & {
  info: CastJobInfo; at: number; started: number; delivering?: boolean; opening?: boolean;
};
interface Options {
  dataDir: string;
  member(id: string): CastMember | undefined;
  agents(): AgentInfo[];
  account(id?: string): string | undefined;
  changed(): void;
  idle?(agent: AgentInfo): void;
  now?: () => number;
  /** Máximo de personagens abertos ao mesmo tempo (as "salas" de um tenant); ausente = sem limite. */
  max?: number;
  zeroclaw?: ZeroClawCast;
}

export class CastLauncher {
  private readonly entries = new Map<string, Entry>();
  private readonly opened = new Map<string, number | string>();
  private observingZeroClaw = false;
  private readonly cleaningZeroClaw = new Set<string>();
  private hostSeen = -Infinity;
  private readonly now: () => number;
  constructor(private readonly opts: Options) { this.now = opts.now ?? Date.now; }
  available(): boolean { return this.opts.zeroclaw ? this.opts.zeroclaw.available() : this.now() - this.hostSeen <= HOST_PRESENCE_MS; }
  models(): { provider: 'zeroclaw'; models: { alias: string; model: string }[]; defaultModel: string } | { provider: 'omp'; models: [] } {
    return this.opts.zeroclaw?.models() ?? { provider: 'omp', models: [] };
  }
  isOpened(cast: string): boolean { this.syncZeroClaw(); return this.opened.has(cast); }
  isClosing(sessionId: string): boolean {
    return this.cleaningZeroClaw.has(sessionId) || [...this.entries.values()].some((e) =>
      e.runtime === 'zeroclaw' && e.job.session.sessionId === sessionId && e.job.op === 'stop'
      && ['queued', 'sent', 'waiting'].includes(e.info.status));
  }
  state(cast: string): 'calling' | 'dismissing' | undefined {
    const active = [...this.entries.values()].find((e) => e.job.cast === cast && (e.info.status === 'queued' || e.info.status === 'sent' || e.info.status === 'waiting'));
    return active ? active.job.op === 'start' ? 'calling' : 'dismissing' : undefined;
  }
  jobs(): CastJobInfo[] { return [...this.entries.values()].map((e) => ({ ...e.info })); }
  start(id: string, raw: unknown): { job: CastJobInfo } | { status: number; error: string } {
    const member = this.opts.member(id);
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    if (!member || member.kind !== 'main' || typeof body.room !== 'string' || (body.mode !== 'new' && body.mode !== 'resume') || (body.prompt !== undefined && (typeof body.prompt !== 'string' || body.prompt.length > PROMPT_MAX || /^\s*-/.test(body.prompt))) || (member.model !== undefined && member.model.startsWith('-'))) return { status: 422, error: tr('pedido inválido') };
    if (this.state(id) || this.opts.agents().some((a) => a.cast === id && a.status !== 'offline' && a.status !== 'done')) return { status: 409, error: tr('personagem ocupado') };
    const limit = this.limitError();
    if (limit) return limit;
    if (this.opts.zeroclaw && this.isOpened(id)) return { status: 409, error: tr('personagem ocupado') };
    // Sem salas no personagem vale qualquer pasta absoluta (o auxiliar confere se ela existe no host); com salas, só elas.
    const room = normalizeCwd(body.room);
    if (!isAbsolute(room) || room.length > 1024) return { status: 422, error: tr('informe a pasta do projeto (caminho absoluto)') };
    // `session`: retomar uma conversa escolhida no histórico (pode ser de outro agente); sem ele, a última do personagem na sala.
    const explicit = body.session !== undefined;
    if (explicit && (body.mode !== 'resume' || typeof body.session !== 'string')) return { status: 422, error: tr('pedido inválido') };
    if ((member.rooms.length && !member.rooms.includes(room)) || (body.mode === 'resume' && !explicit && !member.last?.[room])) return { status: 422, error: tr('personagem não trabalha nesta sala') };
    const resume = body.mode === 'resume' ? (explicit ? body.session as string : member.last![room]) : undefined;
    if (resume !== undefined && !/^[0-9a-f][0-9a-f-]{7,199}$/i.test(resume)) return { status: 422, error: tr('pedido inválido') };
    if (this.opts.zeroclaw) return this.startZeroClaw({ cast: id, name: member.name, room, model: member.model,
      resume, instructions: body.mode === 'new' ? member.instructions : undefined, prompt: body.prompt as string | undefined, account: body.account });
    if (!this.available()) return { status: 503, error: tr('auxiliar do host desligado (npm run codex:bridge)') };
    // A conversa mora na conta em que foi gravada: `account` do histórico vence a do personagem.
    if (body.account !== undefined && typeof body.account !== 'string') return { status: 422, error: tr('pedido inválido') };
    const agentDir = this.opts.account((body.account as string | undefined) ?? member.account);
    if (!agentDir) return { status: 422, error: tr('conta não encontrada') };
    let instructionsFile: string | undefined;
    if (member.instructions) {
      try {
        const dir = join(this.opts.dataDir, 'cast');
        mkdirSync(dir, { recursive: true, mode: 0o700 });
        instructionsFile = join(dir, `${id}.md`);
        writeFileSync(instructionsFile, member.instructions, { mode: 0o600 });
      } catch { return { status: 500, error: tr('não foi possível gravar as instruções') }; }
    }
    const job: CastJob = { id: randomUUID(), op: 'start', cast: id, folder: room, agentDir, ...(member.model ? { model: member.model } : {}), ...(instructionsFile ? { instructionsFile } : {}), ...(resume ? { resume } : {}), ...(body.prompt ? { prompt: body.prompt as string } : {}) };
    return { job: this.enqueue(job) };
  }
  /**
   * "Novo": agente fora do elenco, numa sala livre. O escritório sorteia nome e visual (como numa sessão aberta à mão);
   * o id `novo-…` só serve para acompanhar a chamada, dispensar e encerrar por ociosidade como um personagem.
   * Com `session`, retoma essa conversa do histórico (quando nenhum personagem livre trabalha na pasta).
   */
  startNew(raw: unknown): { job: CastJobInfo } | { status: number; error: string } {
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    if (typeof body.room !== 'string' || (body.account !== undefined && typeof body.account !== 'string') || (body.prompt !== undefined && (typeof body.prompt !== 'string' || body.prompt.length > PROMPT_MAX || /^\s*-/.test(body.prompt)))) return { status: 422, error: tr('pedido inválido') };
    if (body.session !== undefined && (typeof body.session !== 'string' || !/^[0-9a-f][0-9a-f-]{7,199}$/i.test(body.session))) return { status: 422, error: tr('pedido inválido') };
    const limit = this.limitError();
    if (limit) return limit;
    const room = normalizeCwd(body.room);
    if (!isAbsolute(room) || room.length > 1024) return { status: 422, error: tr('informe a pasta do projeto (caminho absoluto)') };
    if (this.opts.zeroclaw) return this.startZeroClaw({ cast: `${NEW_CAST_PREFIX}${randomUUID().slice(0, 8)}`,
      name: 'Novo', room, resume: body.session as string | undefined, prompt: body.prompt as string | undefined, account: body.account });
    if (!this.available()) return { status: 503, error: tr('auxiliar do host desligado (npm run codex:bridge)') };
    const agentDir = this.opts.account(body.account as string | undefined);
    if (!agentDir) return { status: 422, error: tr('conta não encontrada') };
    const cast = `${NEW_CAST_PREFIX}${randomUUID().slice(0, 8)}`;
    return { job: this.enqueue({ id: randomUUID(), op: 'start', cast, folder: room, agentDir, ...(body.session ? { resume: body.session as string } : {}), ...(body.prompt ? { prompt: body.prompt as string } : {}) }) };
  }
  private limitError(): { status: number; error: string } | undefined {
    if (this.opts.max === undefined || this.openCount() < this.opts.max) return undefined;
    return { status: 409, error: this.opts.max === 1 ? tr('só 1 personagem trabalha por vez: dispense o atual antes de chamar outro') : tr('limite de {0} personagens trabalhando ao mesmo tempo: dispense um antes de chamar outro', [this.opts.max]) };
  }
  dismiss(agent: AgentInfo | undefined): { job: CastJobInfo } | { status: number; error: string } {
    this.syncZeroClaw();
    if (!agent?.cast || !this.opened.has(agent.cast) || agent.kind !== 'main') return { status: 404, error: tr('agente não encontrado') };
    const active = this.state(agent.cast);
    if (active) return { status: 409, error: tr('personagem ocupado') };
    if (this.opts.zeroclaw) {
      const sessionId = this.opened.get(agent.cast);
      const session = typeof sessionId === 'string' ? this.opts.zeroclaw.session(sessionId) : undefined;
      if (!session || sessionId !== agent.sessionId || agent.provider !== 'zeroclaw') return { status: 404, error: tr('agente não encontrado') };
      if (this.isClosing(session.sessionId)) return { status: 409, error: tr('personagem ocupado') };
      return { job: this.enqueueZeroClaw({ id: randomUUID(), op: 'stop', cast: agent.cast, session }) };
    }
    return { job: this.enqueue({ id: randomUUID(), op: 'stop', cast: agent.cast }) };
  }
  /** Modelos dos papéis editáveis da conta padrão, como o auxiliar leu por último (`omp config get modelRoles`). */
  roles(): { roles?: Partial<Record<string, string>>; pending: boolean } {
    const pending = [...this.entries.values()].some((e) => e.job.op === 'roles' && (e.info.status === 'queued' || e.info.status === 'sent'));
    return { ...(this.lastRoles ? { roles: { ...this.lastRoles } } : {}), pending };
  }
  /** Pasta da conta padrão: o auxiliar lê dela os papéis que manda a cada rodada. */
  rolesDir(): string | undefined { return this.opts.zeroclaw ? undefined : this.opts.account(undefined); }
  /** Troca papéis (`{roles: {task: "prov/modelo:nível", plan: null}}`): o auxiliar grava com `omp config set modelRoles`. */
  setRoles(raw: unknown): { job: CastJobInfo } | { status: number; error: string } {
    if (this.opts.zeroclaw) return { status: 422, error: 'ZeroClaw não oferece papéis OMP' };
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    const input = body.roles && typeof body.roles === 'object' && !Array.isArray(body.roles) ? body.roles as Record<string, unknown> : undefined;
    const roles: OmpRoles = {};
    for (const [role, value] of Object.entries(input ?? {})) {
      if (!(OMP_EDITABLE_ROLES as readonly string[]).includes(role) || (value !== null && (typeof value !== 'string' || !ROLE_VALUE.test(value)))) return { status: 422, error: tr('pedido inválido') };
      roles[role as keyof OmpRoles] = value as string | null;
    }
    if (!Object.keys(roles).length) return { status: 422, error: tr('pedido inválido') };
    if (!this.available()) return { status: 503, error: tr('auxiliar do host desligado (npm run codex:bridge)') };
    const agentDir = this.rolesDir();
    if (!agentDir) return { status: 422, error: tr('conta não encontrada') };
    return { job: this.enqueue({ id: randomUUID(), op: 'roles', cast: '', agentDir, roles }) };
  }
  private lastRoles: Partial<Record<string, string>> | undefined;
  /** Abertos agora pelo Habblaud (sessão hb-* ou chamada em andamento); quem já está sendo dispensado não conta. */
  private openCount(): number {
    this.syncZeroClaw();
    const casts = new Set([...this.opened.keys()].filter((c) => this.opts.zeroclaw || this.state(c) !== 'dismissing'));
    for (const e of this.entries.values()) if (e.job.op === 'start' && (e.info.status === 'queued' || e.info.status === 'sent' || e.info.status === 'waiting')) casts.add(e.job.cast);
    return casts.size;
  }
  private enqueue(job: CastJob): CastJobInfo {
    const now = this.now();
    const info: CastJobInfo = { id: job.id, cast: job.cast, op: job.op, status: 'queued' };
    this.entries.set(job.id, { runtime: 'omp', job, info, at: now, started: now });
    this.opts.changed();
    return { ...info };
  }
  private startZeroClaw(input: Parameters<ZeroClawCast['prepare']>[0]): { job: CastJobInfo } | { status: number; error: string } {
    this.syncZeroClaw();
    if (!this.available()) return { status: 503, error: 'ZeroClaw está indisponível' };
    try {
      const request = this.opts.zeroclaw!.prepare(input);
      if (this.cleaningZeroClaw.has(request.session.sessionId) || [...this.entries.values()].some((entry) =>
        entry.runtime === 'zeroclaw' && entry.job.session.sessionId === request.session.sessionId
        && (entry.opening || ['queued', 'sent', 'waiting'].includes(entry.info.status)))) return { status: 409, error: tr('personagem ocupado') };
      return { job: this.enqueueZeroClaw({ ...request, id: randomUUID(), op: 'start', cast: input.cast }) };
    } catch (error) { return { status: 422, error: error instanceof Error ? error.message : 'Pedido ZeroClaw inválido' }; }
  }
  private enqueueZeroClaw(job: ZeroClawCastJob): CastJobInfo {
    const now = this.now();
    const info: CastJobInfo = { id: job.id, cast: job.cast, op: job.op, status: 'queued' };
    const entry: Entry = { runtime: 'zeroclaw', job, info, at: now, started: now };
    this.entries.set(job.id, entry);
    this.opts.changed();
    // Reservation and dismissal lock exist before any asynchronous operation.
    void Promise.resolve().then(async () => {
      if (entry.info.status === 'failed') return;
      entry.info.status = 'sent'; entry.at = this.now(); this.opts.changed();
      if (job.op === 'stop') {
        await this.opts.zeroclaw!.dismiss(job.session.sessionId);
        entry.info.status = 'done'; entry.at = this.now(); this.opts.changed();
        return;
      }
      entry.opening = true;
      try { await this.opts.zeroclaw!.open(job.session); }
      finally { entry.opening = false; }
      if (['failed'].includes(entry.info.status)) {
        await this.cleanupZeroClaw(entry);
        return;
      }
      entry.info.status = 'waiting'; entry.at = this.now(); this.opts.changed();
      this.finishZeroClawStart(entry);
    }).catch((error: unknown) => this.failZeroClaw(entry, error instanceof Error ? error.message : 'Falha ao operar a sessão ZeroClaw'));
    return { ...info };
  }
  private finishZeroClawStart(entry: Entry): void {
    if (entry.runtime !== 'zeroclaw' || entry.job.op !== 'start' || entry.info.status !== 'waiting' || entry.delivering) return;
    if (this.now() - entry.started >= 60_000) {
      this.failZeroClaw(entry, 'ZeroClaw não confirmou a sessão dentro do prazo');
      return;
    }
    const agent = this.opts.agents().find((a) => a.provider === 'zeroclaw' && a.kind === 'main'
      && a.sessionId === entry.job.session.sessionId && a.cast === entry.job.cast && a.status !== 'offline' && a.status !== 'done');
    if (!agent) return;
    entry.delivering = true;
    try {
      this.opts.zeroclaw!.deliver(entry.job, agent.id);
      entry.info.status = 'done'; entry.at = this.now(); this.opts.changed();
    } catch (error) { this.failZeroClaw(entry, error instanceof Error ? error.message : 'ZeroClaw não recebeu o pedido inicial'); }
  }
  private failZeroClaw(entry: Entry, error: string): void {
    if (entry.runtime !== 'zeroclaw') return;
    entry.info.status = 'failed'; entry.info.error = error.slice(0, 300); entry.at = this.now(); this.opts.changed();
    if (entry.job.op === 'start') void this.cleanupZeroClaw(entry);
  }
  private async cleanupZeroClaw(entry: Entry): Promise<void> {
    if (entry.runtime !== 'zeroclaw') return;
    const id = entry.job.session.sessionId;
    if (this.cleaningZeroClaw.has(id)) return;
    this.cleaningZeroClaw.add(id);
    try { await this.opts.zeroclaw!.dismiss(id); }
    catch (error) {
      entry.info.error = `${entry.info.error ?? 'Falha ZeroClaw'}; ${error instanceof Error ? error.message : 'falha ao fechar a sessão'}`.slice(0, 300);
      this.opts.changed();
    } finally { this.cleaningZeroClaw.delete(id); }
  }
  private syncZeroClaw(): void {
    const runtime = this.opts.zeroclaw;
    if (!runtime) return;
    if (!this.observingZeroClaw) this.observingZeroClaw = runtime.observe((event) => this.zeroClawEvent(event));
    for (const session of runtime.openSessions()) {
      if (!session.cast) continue;
      const failed = [...this.entries.values()].some((e) => e.runtime === 'zeroclaw'
        && e.job.op === 'start' && e.job.session.sessionId === session.sessionId && e.info.status === 'failed');
      // Keep failed attempts reserved until cleanup confirms close, but never replace a newer session.
      if ((!failed || !this.opened.has(session.cast)) && this.opened.get(session.cast) !== session.sessionId) {
        this.opened.set(session.cast, session.sessionId); this.opts.changed();
      }
    }
  }
  private zeroClawEvent(event: ConnectorEvent): void {
    if (event.type === 'session-open' && event.descriptor.cast) {
      const failed = [...this.entries.values()].find((e) => e.runtime === 'zeroclaw'
        && e.job.op === 'start' && e.job.session.sessionId === event.sessionId && e.info.status === 'failed');
      if (failed) { void this.cleanupZeroClaw(failed); return; }
      this.opened.set(event.descriptor.cast, event.sessionId); this.opts.changed();
    }
    if (event.type === 'session-close' && event.intentional && !event.replacement) {
      for (const [cast, id] of this.opened) if (id === event.sessionId) this.opened.delete(cast);
      this.opts.changed();
    }
  }
  /** `raw.open`: personagens com sessão `hb-<id>` aberta no host (o auxiliar manda a cada rodada). Reconstrói quem pode
   * ser dispensado depois de o servidor reiniciar, e esquece quem fechou a sessão por fora. */
  poll(raw?: unknown): CastJob[] {
    if (this.opts.zeroclaw) return [];
    this.hostSeen = this.now();
    const reported = raw && typeof raw === 'object' && 'roles' in raw && raw.roles && typeof raw.roles === 'object' ? raw.roles as Record<string, unknown> : undefined;
    if (reported) {
      const roles = Object.fromEntries(OMP_EDITABLE_ROLES.flatMap((r) => typeof reported[r] === 'string' && ROLE_VALUE.test(reported[r]) ? [[r, reported[r]]] : []));
      if (JSON.stringify(roles) !== JSON.stringify(this.lastRoles)) { this.lastRoles = roles; this.opts.changed(); }
    }
    const open = raw && typeof raw === 'object' && 'open' in raw && Array.isArray(raw.open) ? raw.open.filter((c): c is string => typeof c === 'string' && /^[a-z0-9-]{1,40}$/.test(c)) : undefined;
    if (open) {
      let changed = false;
      for (const cast of open) if (!this.opened.has(cast) && (this.opts.member(cast) || cast.startsWith(NEW_CAST_PREFIX))) { this.opened.set(cast, this.now()); changed = true; }
      for (const cast of [...this.opened.keys()]) if (!open.includes(cast) && !this.state(cast)) { this.opened.delete(cast); changed = true; }
      if (changed) this.opts.changed();
    }
    const jobs: CastJob[] = [];
    for (const e of this.entries.values()) if (e.runtime === 'omp' && e.info.status === 'queued') { e.info.status = 'sent'; e.at = this.now(); jobs.push(e.job); }
    if (jobs.length) this.opts.changed();
    return jobs;
  }
  ack(raw: unknown): void {
    if (this.opts.zeroclaw) return;
    this.hostSeen = this.now();
    const results = raw && typeof raw === 'object' && 'results' in raw && Array.isArray(raw.results) ? raw.results : [];
    for (const result of results) {
      const r = result && typeof result === 'object' ? result as Record<string, unknown> : {};
      const e = typeof r.id === 'string' ? this.entries.get(r.id) : undefined;
      if (!e || e.runtime !== 'omp' || e.info.status !== 'sent') continue;
      // Papéis gravados terminam no ack; chamar e dispensar esperam o agente aparecer ou sumir (tick).
      e.info.status = r.ok === true ? (e.job.op === 'roles' ? 'done' : 'waiting') : 'failed';
      if (r.ok !== true) e.info.error = typeof r.error === 'string' ? r.error.slice(0, 300) : tr('o auxiliar não abriu a sessão');
      e.at = this.now();
      if (r.ok === true && e.job.op === 'start') this.opened.set(e.job.cast, this.now());
      // O auxiliar só relê os papéis na rodada seguinte: sem isto, quem consulta logo após o ack vê os antigos.
      if (r.ok === true && e.job.op === 'roles') {
        const roles = { ...this.lastRoles };
        for (const [role, value] of Object.entries(e.job.roles)) { if (value === null) delete roles[role]; else roles[role] = value; }
        this.lastRoles = roles;
      }
      this.opts.changed();
    }
  }
  tick(): void {
    this.syncZeroClaw();
    const now = this.now();
    const agents = this.opts.agents();
    for (const [id, e] of this.entries) {
      if (e.runtime === 'zeroclaw') {
        if (e.job.op === 'start' && ['queued', 'sent', 'waiting'].includes(e.info.status) && now - e.started >= 60_000) {
          this.failZeroClaw(e, 'ZeroClaw não confirmou a sessão dentro do prazo');
        }
        this.finishZeroClawStart(e);
        if (!e.opening && ['done', 'failed'].includes(e.info.status) && now - e.at > KEEP_MS) this.entries.delete(id);
        continue;
      }
      if (e.job.op === 'start' && e.info.status === 'waiting' && agents.some((a) => a.cast === e.job.cast && a.status !== 'offline')) { e.info.status = 'done'; e.at = now; this.opts.changed(); }
      if (e.job.op === 'stop' && e.info.status === 'waiting' && !agents.some((a) => a.cast === e.job.cast && a.status !== 'offline')) { e.info.status = 'done'; e.at = now; this.opened.delete(e.job.cast); this.opts.changed(); }
      if ((e.info.status === 'queued' && now - e.at > QUEUED_TIMEOUT_MS) || (e.info.status === 'sent' && now - e.at > SENT_TIMEOUT_MS) || (e.job.op === 'start' && e.info.status === 'waiting' && now - e.started >= 60_000)) {
        const needsStop = e.job.op === 'start' && (e.info.status === 'waiting' || e.info.status === 'sent');
        e.info.status = 'failed'; e.info.error = tr('o auxiliar não confirmou a sessão'); e.at = now;
        if (needsStop) this.enqueue({ id: randomUUID(), op: 'stop', cast: e.job.cast });
        this.opts.changed();
      }
      if ((e.info.status === 'done' || e.info.status === 'failed') && now - e.at > KEEP_MS) this.entries.delete(id);
    }
    for (const cast of this.opened.keys()) {
      const agent = agents.find((a) => a.cast === cast && a.kind === 'main' && a.status !== 'offline');
      if (!agent) {
        if (!this.opts.zeroclaw && !this.state(cast)) { this.opened.delete(cast); this.opts.changed(); }
        continue;
      }
      const idle = this.opts.member(cast)?.idleMinutes ?? CAST_IDLE_MINUTES;
      if (this.opts.zeroclaw) {
        const sessionId = this.opened.get(cast);
        const session = typeof sessionId === 'string' ? this.opts.zeroclaw.session(sessionId) : undefined;
        if (!session?.connected || session.busy || session.continuityLost) continue;
      }
      if (idle && agent.status === 'idle' && now - agent.statusSince >= idle * 60_000 && !this.state(cast)) {
        if ('job' in this.dismiss(agent)) this.opts.idle?.(agent);
      }
    }
  }
}

/** `provedor/modelo`, com `:nível` de raciocínio opcional (o omp separa o último `:off|…|max`); nada de espaço ou controle. */
const ROLE_VALUE = /^[A-Za-z0-9][\w.-]*\/[^\s\x00-\x1f\x7f]{1,200}$/;
