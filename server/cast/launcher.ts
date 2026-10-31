import { randomUUID } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { CAST_IDLE_MINUTES, type AgentInfo, type CastJob, type CastJobInfo, type CastMember } from '../../shared/types';
import { tr } from '../../shared/i18n';
import { HOST_PRESENCE_MS, KEEP_MS, PROMPT_MAX, QUEUED_TIMEOUT_MS, SENT_TIMEOUT_MS } from '../spawn/spawn';
import { normalizeCwd } from '../model/rooms';

interface Entry { job: CastJob; info: CastJobInfo; at: number; started: number }
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
}

export class CastLauncher {
  private readonly entries = new Map<string, Entry>();
  private readonly opened = new Map<string, number>();
  private hostSeen = -Infinity;
  private readonly now: () => number;
  constructor(private readonly opts: Options) { this.now = opts.now ?? Date.now; }
  available(): boolean { return this.now() - this.hostSeen <= HOST_PRESENCE_MS; }
  isOpened(cast: string): boolean { return this.opened.has(cast); }
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
    if (this.opts.max !== undefined && this.openCount() >= this.opts.max) return { status: 409, error: tr('limite de {0} personagens trabalhando ao mesmo tempo: dispense um antes de chamar outro', [this.opts.max]) };
    // Sem salas no personagem vale qualquer pasta absoluta (o auxiliar confere se ela existe no host); com salas, só elas.
    const room = normalizeCwd(body.room);
    if (!isAbsolute(room) || room.length > 1024) return { status: 422, error: tr('informe a pasta do projeto (caminho absoluto)') };
    // `session`: retomar uma conversa escolhida no histórico (pode ser de outro agente); sem ele, a última do personagem na sala.
    const explicit = body.session !== undefined;
    if (explicit && (body.mode !== 'resume' || typeof body.session !== 'string')) return { status: 422, error: tr('pedido inválido') };
    if ((member.rooms.length && !member.rooms.includes(room)) || (body.mode === 'resume' && !explicit && !member.last?.[room])) return { status: 422, error: tr('personagem não trabalha nesta sala') };
    const resume = body.mode === 'resume' ? (explicit ? body.session as string : member.last![room]) : undefined;
    if (resume !== undefined && !/^[0-9a-f][0-9a-f-]{7,199}$/i.test(resume)) return { status: 422, error: tr('pedido inválido') };
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
  dismiss(agent: AgentInfo | undefined): { job: CastJobInfo } | { status: number; error: string } {
    if (!agent?.cast || !this.opened.has(agent.cast) || agent.kind !== 'main') return { status: 404, error: tr('agente não encontrado') };
    const active = this.state(agent.cast);
    if (active) return { status: 409, error: tr('personagem ocupado') };
    return { job: this.enqueue({ id: randomUUID(), op: 'stop', cast: agent.cast }) };
  }
  /** Abertos agora pelo Habblaud (sessão hb-* ou chamada em andamento); quem já está sendo dispensado não conta. */
  private openCount(): number {
    const casts = new Set([...this.opened.keys()].filter((c) => this.state(c) !== 'dismissing'));
    for (const e of this.entries.values()) if (e.job.op === 'start' && (e.info.status === 'queued' || e.info.status === 'sent' || e.info.status === 'waiting')) casts.add(e.job.cast);
    return casts.size;
  }
  private enqueue(job: CastJob): CastJobInfo {
    const now = this.now();
    const info: CastJobInfo = { id: job.id, cast: job.cast, op: job.op, status: 'queued' };
    this.entries.set(job.id, { job, info, at: now, started: now });
    this.opts.changed();
    return { ...info };
  }
  /** `raw.open`: personagens com sessão `hb-<id>` aberta no host (o auxiliar manda a cada rodada). Reconstrói quem pode
   * ser dispensado depois de o servidor reiniciar, e esquece quem fechou a sessão por fora. */
  poll(raw?: unknown): CastJob[] {
    this.hostSeen = this.now();
    const open = raw && typeof raw === 'object' && 'open' in raw && Array.isArray(raw.open) ? raw.open.filter((c): c is string => typeof c === 'string' && /^[a-z0-9-]{1,40}$/.test(c)) : undefined;
    if (open) {
      let changed = false;
      for (const cast of open) if (!this.opened.has(cast) && this.opts.member(cast)) { this.opened.set(cast, this.now()); changed = true; }
      for (const cast of [...this.opened.keys()]) if (!open.includes(cast) && !this.state(cast)) { this.opened.delete(cast); changed = true; }
      if (changed) this.opts.changed();
    }
    const jobs: CastJob[] = [];
    for (const e of this.entries.values()) if (e.info.status === 'queued') { e.info.status = 'sent'; e.at = this.now(); jobs.push(e.job); }
    if (jobs.length) this.opts.changed();
    return jobs;
  }
  ack(raw: unknown): void {
    this.hostSeen = this.now();
    const results = raw && typeof raw === 'object' && 'results' in raw && Array.isArray(raw.results) ? raw.results : [];
    for (const result of results) {
      const r = result && typeof result === 'object' ? result as Record<string, unknown> : {};
      const e = typeof r.id === 'string' ? this.entries.get(r.id) : undefined;
      if (!e || e.info.status !== 'sent') continue;
      e.info.status = r.ok === true ? 'waiting' : 'failed';
      if (r.ok !== true) e.info.error = typeof r.error === 'string' ? r.error.slice(0, 300) : tr('o auxiliar não abriu a sessão');
      e.at = this.now();
      if (r.ok === true && e.job.op === 'start') this.opened.set(e.job.cast, this.now());
      this.opts.changed();
    }
  }
  tick(): void {
    const now = this.now();
    const agents = this.opts.agents();
    for (const [id, e] of this.entries) {
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
        if (!this.state(cast)) { this.opened.delete(cast); this.opts.changed(); }
        continue;
      }
      const idle = this.opts.member(cast)?.idleMinutes ?? CAST_IDLE_MINUTES;
      if (idle && agent.status === 'idle' && now - agent.statusSince >= idle * 60_000 && !this.state(cast)) {
        this.dismiss(agent);
        this.opts.idle?.(agent);
      }
    }
  }
}
