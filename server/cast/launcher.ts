import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { CAST_IDLE_MINUTES, NEW_CAST_PREFIX, OMP_EDITABLE_ROLES, type AgentInfo, type CastJob, type CastJobInfo, type CastMember, type OmpRoles } from '../../shared/types';
import { tr } from '../../shared/i18n';
import { isAgyModel } from '../../shared/agy-model';
import { AntigravityCast, type AntigravityCastJob } from './antigravity';
import { HOST_PRESENCE_MS, KEEP_MS, PROMPT_MAX, QUEUED_TIMEOUT_MS, SENT_TIMEOUT_MS } from '../spawn/spawn';
import { normalizeCwd } from '../model/rooms';
import { ZeroClawCast, type ZeroClawCatalog, type ZeroClawCastJob } from './zeroclaw';
import type { ConnectorEvent } from '../sources/zeroclaw/connector';
import { setTimeout as delay } from 'node:timers/promises';
import { HandoffStore, readEntries, renderHandoff } from './handoff';
import type { TerminalParser } from '../sources/terminal';

type Entry = ({ runtime: 'omp'; job: CastJob } | { runtime: 'zeroclaw'; job: ZeroClawCastJob } | { runtime: 'antigravity'; job: AntigravityCastJob }) & {
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
  /** Pasta das conversas novas do omp (HABBLAUD_CAST_FOLDER); ausente = a pasta pessoal do host. */
  folder?: string;
  zeroclaw?: ZeroClawCast;
  antigravity?: AntigravityCast;
  handoff?: HandoffStore;
  transcriptPathOf?(id: string): string | undefined;
  terminalParser?(id: string): TerminalParser;
  cwdOf?(agent: AgentInfo): string | undefined;
  switched?(agent: AgentInfo, from: string, to: string, pending: boolean): void;
}

export class CastLauncher {
  private readonly entries = new Map<string, Entry>();
  private readonly opened = new Map<string, number | string>();
  private readonly openedRuntime = new Map<string, 'antigravity'>();
  private observingAntigravity = false;
  private observingZeroClaw = false;
  private readonly cleaningZeroClaw = new Set<string>();
  private hostSeen = -Infinity;
  private readonly now: () => number;
  private readonly switching = new Set<string>();
  // shortcut: só em memória, some ao reiniciar o servidor; persistir quando isso atrapalhar.
  private ompModels: string[] = [];
  constructor(private readonly opts: Options) { this.now = opts.now ?? Date.now; }
  available(): boolean { return this.opts.zeroclaw ? this.opts.zeroclaw.available() : this.now() - this.hostSeen <= HOST_PRESENCE_MS; }
  models(): ((ZeroClawCatalog & { provider: 'zeroclaw' }) | { provider: 'omp'; models: []; named?: never }) & { agyModels?: string[] } {
    return { ...(this.opts.zeroclaw?.models() ?? { provider: 'omp' as const, models: [] as [] }),
      ...(this.opts.antigravity ? { agyModels: this.opts.antigravity.agyModels() } : {}) };
  }
  rememberOmpModels(list: string[]): void { this.ompModels = list.filter(model => !isAgyModel(model)); }
  otherModels(provider: AgentInfo['provider']): string[] | undefined {
    if (!this.opts.antigravity?.available()) return undefined;
    if (provider === 'omp') return this.opts.antigravity.agyModels();
    if (provider === 'antigravity') return this.ompModels;
    return undefined;
  }
  namedAliases(): string[] { return this.opts.zeroclaw?.models().named?.map((entry) => entry.alias) ?? []; }
  isOpened(cast: string): boolean { this.syncZeroClaw(); return this.opened.has(cast); }
  isClosing(sessionId: string): boolean {
    return this.cleaningZeroClaw.has(sessionId) || [...this.entries.values()].some((e) =>
      e.runtime !== 'omp' && e.job.session.sessionId === sessionId && e.job.op === 'stop'
      && ['queued', 'sent', 'waiting'].includes(e.info.status));
  }
  state(cast: string): 'calling' | 'dismissing' | undefined {
    const active = [...this.entries.values()].find((e) => e.job.cast === cast && (e.info.status === 'queued' || e.info.status === 'sent' || e.info.status === 'waiting'));
    return active ? active.job.op === 'start' ? 'calling' : 'dismissing' : undefined;
  }
  jobs(): CastJobInfo[] { return [...this.entries.values()].map((e) => ({ ...e.info })); }
  handoffPending(cast: string): boolean { return !!this.opts.handoff?.pending(cast); }
  start(id: string, raw: unknown): { job: CastJobInfo } | { status: number; error: string } {
    return this.startMember(id, raw);
  }
  private startMember(id: string, raw: unknown, internal?: { model: string; room: string }): { job: CastJobInfo } | { status: number; error: string } {
    const member = this.opts.member(id);
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    if (!member || member.kind !== 'main' || (member.agent !== undefined && (!this.opts.zeroclaw || !this.namedAliases().includes(member.agent))) || (body.mode !== 'new' && body.mode !== 'resume') || (body.mode === 'new' && body.session !== undefined) || (member.agent === undefined && !(internal ? ROLE_VALUE.test(internal.model) && !!resumeRoom(internal.room) : validModel(body, this.opts.zeroclaw, member.model))) || (body.prompt !== undefined && (typeof body.prompt !== 'string' || body.prompt.length > PROMPT_MAX || /^\s*-/.test(body.prompt))) || (member.agent === undefined && member.model !== undefined && member.model.startsWith('-'))) return { status: 422, error: tr('pedido inválido') };
    if (!internal && this.switching.has(id)) return { status: 409, error: tr('personagem ocupado') };
    if (this.state(id) || this.opts.agents().some((a) => a.cast === id && a.status !== 'offline' && a.status !== 'done')) return { status: 409, error: tr('personagem ocupado') };
    const limit = this.limitError();
    if (limit) return limit;
    if ((this.opts.zeroclaw || this.openedRuntime.has(id)) && this.isOpened(id)) return { status: 409, error: tr('personagem ocupado') };
    // Nova sessão: sempre na pasta padrão (HABBLAUD_CAST_FOLDER, ou a pessoal; o `room` de clientes antigos é ignorado).
    // Retomar: `session` escolhida no histórico (pode ser de outro agente) vem com a pasta dela; sem ela, a conversa
    // mais recente do personagem (a última chave de `last`).
    let room: string | undefined = internal?.room;
    let resume: string | undefined;
    if (body.mode === 'resume') {
      if (body.session !== undefined) {
        if (typeof body.session !== 'string') return { status: 422, error: tr('pedido inválido') };
        room = resumeRoom(body.room);
        if (!room) return { status: 422, error: tr('informe a pasta do projeto (caminho absoluto)') };
        resume = body.session;
      } else {
        [room, resume] = Object.entries(member.last ?? {}).at(-1) ?? [];
        if (!room) return { status: 422, error: tr('personagem sem conversa anterior') };
      }
      if (!/^[0-9a-f][0-9a-f-]{7,199}$/i.test(resume!)) return { status: 422, error: tr('pedido inválido') };
    }
    const model = member.agent === undefined ? internal?.model ?? (body.model as string | undefined) ?? member.model : undefined;
    if (!internal && body.mode === 'new') this.opts.handoff?.forget(id);
    if (this.useAntigravity(model, resume)) {
      if (member.agent !== undefined || body.account !== undefined) return { status: 422, error: tr('pedido inválido') };
      return this.startAntigravity({ cast: id, room, model: resume ? member.model : model, resume,
        instructions: member.instructions, prompt: body.prompt as string | undefined });
    }
    // ZeroClaw: `model` do pedido vale só nesta conversa nova; retomar segue o modelo em que a conversa começou.
    if (this.opts.zeroclaw) return this.startZeroClaw({ cast: id, name: member.name, room, agent: member.agent,
      model: member.agent || resume ? undefined : (body.model as string | undefined) ?? member.model,
      resume, instructions: member.agent === undefined && body.mode === 'new' ? member.instructions : undefined, prompt: body.prompt as string | undefined, account: body.account });
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
    const folder = room ?? this.opts.folder;
    const job: CastJob = { id: randomUUID(), op: 'start', cast: id, ...(folder ? { folder } : {}), agentDir, ...(model ? { model } : {}), ...(instructionsFile ? { instructionsFile } : {}), ...(resume ? { resume } : {}), ...(body.prompt ? { prompt: body.prompt as string } : {}) };
    return { job: this.enqueue(job) };
  }
  /**
   * "Novo": agente fora do elenco, na pasta padrão configurada ou pessoal. O escritório sorteia nome e visual (como numa sessão aberta à mão);
   * o id `novo-…` só serve para acompanhar a chamada, dispensar e encerrar por ociosidade como um personagem.
   * Com `session`, retoma essa conversa do histórico na pasta dela (`room`).
   */
  startNew(raw: unknown): { job: CastJobInfo } | { status: number; error: string } {
    return this.startNewInternal(raw);
  }
  private startNewInternal(raw: unknown, internal?: { model: string; room: string; cast: string }): { job: CastJobInfo } | { status: number; error: string } {
    const body = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {};
    if ((body.account !== undefined && typeof body.account !== 'string') || !(internal ? ROLE_VALUE.test(internal.model) && !!resumeRoom(internal.room) : validModel(body, this.opts.zeroclaw)) || (body.prompt !== undefined && (typeof body.prompt !== 'string' || body.prompt.length > PROMPT_MAX || /^\s*-/.test(body.prompt)))) return { status: 422, error: tr('pedido inválido') };
    if (body.session !== undefined && (typeof body.session !== 'string' || !/^[0-9a-f][0-9a-f-]{7,199}$/i.test(body.session))) return { status: 422, error: tr('pedido inválido') };
    const limit = this.limitError();
    if (limit) return limit;
    const room = internal?.room ?? (body.session === undefined ? undefined : resumeRoom(body.room));
    if (body.session !== undefined && !room) return { status: 422, error: tr('informe a pasta do projeto (caminho absoluto)') };
    const model = internal?.model ?? body.model as string | undefined;
    const cast = internal?.cast ?? `${NEW_CAST_PREFIX}${randomUUID().slice(0, 8)}`;
    if (!internal && body.session === undefined) this.opts.handoff?.forget(cast);
    if (this.useAntigravity(model, body.session as string | undefined)) {
      if (body.account !== undefined) return { status: 422, error: tr('pedido inválido') };
      return this.startAntigravity({ cast, room,
        model, resume: body.session as string | undefined, prompt: body.prompt as string | undefined });
    }
    if (this.opts.zeroclaw) return this.startZeroClaw({ cast,
      name: 'Novo', room, model, resume: body.session as string | undefined, prompt: body.prompt as string | undefined, account: body.account });
    if (!this.available()) return { status: 503, error: tr('auxiliar do host desligado (npm run codex:bridge)') };
    const agentDir = this.opts.account(body.account as string | undefined);
    if (!agentDir) return { status: 422, error: tr('conta não encontrada') };
    const folder = room ?? this.opts.folder;
    return { job: this.enqueue({ id: randomUUID(), op: 'start', cast, ...(folder ? { folder } : {}), agentDir, ...(internal ? { model: internal.model } : {}), ...(body.session ? { resume: body.session as string } : {}), ...(body.prompt ? { prompt: body.prompt as string } : {}) }) };
  }
  async switchTool(agent: AgentInfo, model: string): Promise<void> {
    const cast = agent.cast;
    if (!cast || !this.isOpened(cast) || agent.kind !== 'main' || (!cast.startsWith(NEW_CAST_PREFIX) && this.opts.member(cast)?.kind !== 'main') || !['omp', 'antigravity'].includes(agent.provider ?? '')) {
      throw new Error(tr('só agentes abertos pelo elenco trocam de ferramenta; dispense e chame de novo com o outro modelo'));
    }
    if (agent.status === 'working' || agent.status === 'waiting') throw new Error(tr('espere o turno terminar (ou use STOP) para trocar de ferramenta'));
    if (this.switching.has(cast) || this.state(cast)) throw new Error(tr('personagem ocupado'));
    const cwd = this.opts.cwdOf?.(agent);
    if (!cwd || !resumeRoom(cwd) || /[\x00-\x1f\x7f-\x9f]/.test(cwd)) throw new Error(tr('não foi possível determinar a pasta desta conversa'));
    if (!ROLE_VALUE.test(model) || isAgyModel(model) === (agent.provider === 'antigravity')) throw new Error(tr('pedido inválido'));
    if (isAgyModel(model) ? !this.opts.antigravity?.available() : !this.available() || !!this.opts.zeroclaw) {
      throw new Error(isAgyModel(model) ? tr('Antigravity desligado neste Habblaud') : tr('auxiliar do host desligado (npm run codex:bridge)'));
    }
    if (!this.opts.handoff || !this.opts.terminalParser || !this.opts.transcriptPathOf) throw new Error(tr('troca de ferramenta indisponível neste Habblaud'));
    this.switching.add(cast);
    try {
      const path = this.opts.transcriptPathOf(agent.id);
      const entries = path ? await readEntries(path, this.opts.terminalParser(agent.id)) : [];
      const last = this.opts.handoff.last(cast);
      const previous = last ? readFileSync(last, 'utf8') : undefined;
      const from = agent.provider === 'antigravity' ? 'Antigravity' : 'omp';
      const to = isAgyModel(model) ? 'Antigravity' : 'omp';
      if (entries.length || previous) this.opts.handoff.put(cast, renderHandoff({ from, model: agent.model, cwd, at: this.now(), entries, previous }));
      await this.waitJob(this.dismiss(agent));
      const input = { model, room: cwd };
      const opening = cast.startsWith(NEW_CAST_PREFIX) ? this.startNewInternal({}, { ...input, cast }) : this.startMember(cast, { mode: 'new' }, input);
      const job = await this.waitJob(opening);
      const next = this.opts.agents().find(a => a.cast === job.cast && a.kind === 'main' && a.status !== 'offline' && a.status !== 'done');
      if (next) this.opts.switched?.(next, from, to, !!this.opts.handoff.pending(job.cast));
    } finally { this.switching.delete(cast); }
  }
  private async waitJob(result: { job: CastJobInfo } | { status: number; error: string }): Promise<CastJobInfo> {
    if ('error' in result) throw new Error(result.error);
    for (;;) {
      this.tick();
      const job = this.entries.get(result.job.id)?.info;
      if (!job || job.status === 'failed') throw new Error(job?.error ?? tr('o auxiliar não confirmou a sessão'));
      if (job.status === 'done') return job;
      await delay(25);
    }
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
    if (this.openedRuntime.has(agent.cast)) {
      const sessionId = this.opened.get(agent.cast);
      const session = typeof sessionId === 'string' ? this.opts.antigravity?.session(sessionId) : undefined;
      if (!session || sessionId !== agent.sessionId || agent.provider !== 'antigravity') return { status: 404, error: tr('agente não encontrado') };
      if (this.isClosing(session.sessionId)) return { status: 409, error: tr('personagem ocupado') };
      return { job: this.enqueuePushed({ runtime: 'antigravity', job: { id: randomUUID(), op: 'stop', cast: agent.cast, session } }) };
    }
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
    const casts = new Set([...this.opened.keys()].filter((c) => this.openedRuntime.has(c) || this.opts.zeroclaw || this.state(c) !== 'dismissing'));
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
  private useAntigravity(model: string | undefined, resume?: string): boolean {
    return resume !== undefined ? this.opts.antigravity?.owns(resume) ?? false : isAgyModel(model);
  }
  private startAntigravity(input: Parameters<AntigravityCast['prepare']>[0]): { job: CastJobInfo } | { status: number; error: string } {
    this.syncZeroClaw();
    if (!this.opts.antigravity?.available()) return { status: 503, error: tr('Antigravity desligado neste Habblaud') };
    if (input.resume && (this.opts.antigravity.session(input.resume) || this.isClosing(input.resume) || [...this.entries.values()].some(e =>
      e.runtime === 'antigravity' && e.job.session.resume === input.resume && (e.opening || ['queued', 'sent', 'waiting'].includes(e.info.status))))) {
      return { status: 409, error: tr('personagem ocupado') };
    }
    try {
      const { instructions, text, ...session } = this.opts.antigravity.prepare(input);
      return { job: this.enqueuePushed({ runtime: 'antigravity', job: { id: randomUUID(), op: 'start', cast: input.cast, session, instructions, text } }) };
    } catch (error) { return { status: 422, error: error instanceof Error ? error.message : tr('pedido inválido') }; }
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
  private enqueueZeroClaw(job: ZeroClawCastJob): CastJobInfo { return this.enqueuePushed({ runtime: 'zeroclaw', job }); }
  private enqueuePushed(input: { runtime: 'zeroclaw'; job: ZeroClawCastJob } | { runtime: 'antigravity'; job: AntigravityCastJob }): CastJobInfo {
    const now = this.now();
    const info: CastJobInfo = { id: input.job.id, cast: input.job.cast, op: input.job.op, status: 'queued' };
    const entry: Entry = { ...input, info, at: now, started: now };
    this.entries.set(input.job.id, entry);
    this.opts.changed();
    // Reservation and dismissal lock exist before any asynchronous operation.
    void Promise.resolve().then(async () => {
      if (entry.info.status === 'failed') return;
      entry.info.status = 'sent'; entry.at = this.now(); this.opts.changed();
      if (entry.job.op === 'stop') {
        await this.dismissPushed(entry);
        entry.info.status = 'done'; entry.at = this.now(); this.opts.changed();
        return;
      }
      entry.opening = true;
      try {
        if (entry.runtime === 'zeroclaw') await this.opts.zeroclaw!.open(entry.job.session);
        else {
          const id = await this.opts.antigravity!.open(entry.job);
          entry.job.session.sessionId = id;
          this.opened.set(entry.job.cast, id);
          this.openedRuntime.set(entry.job.cast, 'antigravity');
          this.opts.changed();
        }
      } finally { entry.opening = false; }
      if (['failed'].includes(entry.info.status)) { await this.cleanupPushed(entry); return; }
      entry.info.status = 'waiting'; entry.at = this.now(); this.opts.changed();
      this.finishPushedStart(entry);
    }).catch((error: unknown) => this.failPushed(entry, error instanceof Error ? error.message
      : entry.runtime === 'zeroclaw' ? 'Falha ao operar a sessão ZeroClaw' : tr('Antigravity não confirmou a entrega da mensagem')));
    return { ...info };
  }
  private finishPushedStart(entry: Entry): void {
    if (entry.runtime === 'omp' || entry.job.op !== 'start' || entry.info.status !== 'waiting' || entry.delivering) return;
    if (this.now() - entry.started >= 60_000) {
      this.failPushed(entry, entry.runtime === 'zeroclaw' ? 'ZeroClaw não confirmou a sessão dentro do prazo' : tr('Antigravity não confirmou a sessão dentro do prazo'));
      return;
    }
    const agent = this.opts.agents().find((a) => a.provider === entry.runtime && a.kind === 'main'
      && a.sessionId === entry.job.session.sessionId && a.cast === entry.job.cast && a.status !== 'offline' && a.status !== 'done');
    if (!agent) return;
    entry.delivering = true;
    try {
      if (entry.runtime === 'zeroclaw') this.opts.zeroclaw!.deliver(entry.job, agent.id);
      else this.opts.antigravity!.deliver(entry.job, agent.id, entry.job.session.sessionId!);
      entry.info.status = 'done'; entry.at = this.now(); this.opts.changed();
    } catch (error) { this.failPushed(entry, error instanceof Error ? error.message
      : entry.runtime === 'zeroclaw' ? 'ZeroClaw não recebeu o pedido inicial' : tr('Antigravity não confirmou a entrega da mensagem')); }
  }
  private failPushed(entry: Entry, error: string): void {
    if (entry.runtime === 'omp') return;
    entry.info.status = 'failed'; entry.info.error = error.slice(0, 300); entry.at = this.now(); this.opts.changed();
    if (entry.job.op === 'start') void this.cleanupPushed(entry);
  }
  private async dismissPushed(entry: Exclude<Entry, { runtime: 'omp' }>): Promise<void> {
    const id = entry.job.session.sessionId;
    if (!id) return;
    if (entry.runtime === 'zeroclaw') await this.opts.zeroclaw!.dismiss(id);
    else await this.opts.antigravity!.dismiss(id);
  }
  private async cleanupPushed(entry: Entry): Promise<void> {
    if (entry.runtime === 'omp') return;
    const id = entry.job.session.sessionId;
    if (!id || this.cleaningZeroClaw.has(id)) return;
    this.cleaningZeroClaw.add(id);
    try { await this.dismissPushed(entry); }
    catch (error) {
      entry.info.error = `${entry.info.error ?? (entry.runtime === 'zeroclaw' ? 'Falha ZeroClaw' : tr('Antigravity não confirmou a entrega da mensagem'))}; ${error instanceof Error ? error.message : 'falha ao fechar a sessão'}`.slice(0, 300);
      this.opts.changed();
    } finally { this.cleaningZeroClaw.delete(id); }
  }
  private syncZeroClaw(): void {
    this.syncAntigravity();
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
      if (failed) { void this.cleanupPushed(failed); return; }
      this.opened.set(event.descriptor.cast, event.sessionId); this.opts.changed();
    }
    if (event.type === 'session-close' && event.intentional && !event.replacement) {
      for (const [cast, id] of this.opened) if (id === event.sessionId) this.forgetOpened(cast);
      this.opts.changed();
    }
  }
  private forgetOpened(cast: string): void { this.opened.delete(cast); this.openedRuntime.delete(cast); }
  private syncAntigravity(): void {
    const runtime = this.opts.antigravity;
    if (!runtime) return;
    if (!this.observingAntigravity) this.observingAntigravity = runtime.observe(event => {
      if (event.type === 'session-open' && event.replaces) {
        for (const [cast, id] of this.opened) if (this.openedRuntime.has(cast) && id === event.replaces) {
          this.opened.set(cast, event.sessionId); this.opts.changed();
        }
      }
      if (event.type === 'session-close') {
        for (const [cast, id] of this.opened) if (this.openedRuntime.has(cast) && id === event.sessionId) this.forgetOpened(cast);
        this.opts.changed();
      }
    });
    for (const session of runtime.openSessions()) {
      if (!session.cast || this.opened.has(session.cast) || [...this.entries.values()].some(e =>
        e.runtime === 'antigravity' && e.job.cast === session.cast && e.opening)) continue;
      this.opened.set(session.cast, session.sessionId);
      this.openedRuntime.set(session.cast, 'antigravity');
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
      for (const cast of [...this.opened.keys()]) if (!this.openedRuntime.has(cast) && !open.includes(cast) && !this.state(cast)) { this.forgetOpened(cast); changed = true; }
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
      if (e.runtime !== 'omp') {
        if (e.job.op === 'start' && ['queued', 'sent', 'waiting'].includes(e.info.status) && now - e.started >= 60_000) {
          this.failPushed(e, e.runtime === 'zeroclaw' ? 'ZeroClaw não confirmou a sessão dentro do prazo' : tr('Antigravity não confirmou a sessão dentro do prazo'));
        }
        this.finishPushedStart(e);
        if (!e.opening && ['done', 'failed'].includes(e.info.status) && now - e.at > KEEP_MS) this.entries.delete(id);
        continue;
      }
      if (e.job.op === 'start' && e.info.status === 'waiting' && agents.some((a) => a.cast === e.job.cast && a.status !== 'offline')) { e.info.status = 'done'; e.at = now; this.opts.changed(); }
      if (e.job.op === 'stop' && e.info.status === 'waiting' && !agents.some((a) => a.cast === e.job.cast && a.status !== 'offline')) { e.info.status = 'done'; e.at = now; this.forgetOpened(e.job.cast); this.opts.changed(); }
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
        const sessionId = this.opened.get(cast);
        const antigravity = this.openedRuntime.has(cast);
        if ((antigravity ? typeof sessionId === 'string' && !this.opts.antigravity?.session(sessionId) : !this.opts.zeroclaw) && !this.state(cast)) {
          this.forgetOpened(cast); this.opts.changed();
        }
        continue;
      }
      const idle = this.opts.member(cast)?.idleMinutes ?? CAST_IDLE_MINUTES;
      if (this.openedRuntime.has(cast)) {
        const sessionId = this.opened.get(cast);
        const session = typeof sessionId === 'string' ? this.opts.antigravity?.session(sessionId) : undefined;
        if (!session || session.busy) continue;
      } else if (this.opts.zeroclaw) {
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

/** Pasta da conversa retomada: absoluta e de tamanho sensato (o auxiliar confere se ela existe no host). */
function resumeRoom(raw: unknown): string | undefined {
  const room = typeof raw === 'string' ? normalizeCwd(raw) : '';
  return isAbsolute(room) && room.length <= 1024 ? room : undefined;
}

/** `model` só em conversa nova no ZeroClaw (o catálogo valida o valor); omp e retomadas não escolhem modelo no pedido. */
function validModel(body: Record<string, unknown>, zeroclaw: unknown, memberModel?: string): boolean {
  if (body.model === undefined) return true;
  if (typeof body.model !== 'string' || body.session !== undefined || body.mode === 'resume') return false;
  if (isAgyModel(body.model)) return /^[^\s\x00-\x1f\x7f]{1,200}$/.test(body.model);
  return !!zeroclaw || (isAgyModel(memberModel) && ROLE_VALUE.test(body.model));
}
