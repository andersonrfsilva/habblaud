import { existsSync } from 'node:fs';
import { isAbsolute, join } from 'node:path';
import { agyModelValue, isAgyModel } from '../../shared/agy-model';
import { tr } from '../../shared/i18n';
import { AuthRequired, UnknownModel, type AntigravityClient } from '../antigravity/acp';
import type { AntigravityDelivery } from '../messages/antigravity';
import type { MessageRegistry } from '../messages/registry';
import type { AntigravityCatalog } from '../sources/antigravity/catalog';

export type AntigravityCastJob = { id: string; op: 'start' | 'stop'; cast: string;
  session: { sessionId?: string; cwd: string; model?: string; resume?: string }; instructions?: string; text?: string };
export class AntigravityCast {
  constructor(private readonly opts: { client: () => AntigravityClient | undefined;
    delivery: () => Pick<AntigravityDelivery, 'stopSession' | 'setInstructions'> | undefined;
    messages: () => Pick<MessageRegistry, 'send'> | undefined; catalog: AntigravityCatalog; home: string; dataDir: string }) {}
  available(): boolean { return !!this.opts.client(); }
  agyModels(): string[] { return this.opts.catalog.agyModels(); }
  owns(sessionId: string): boolean {
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(sessionId)
      && existsSync(join(this.opts.dataDir, 'antigravity', 'sessions', `${sessionId}.jsonl`));
  }
  session(id: string) { return this.opts.client()?.session(id); }
  openSessions() { return this.opts.client()?.sessions() ?? []; }
  observe(listener: (event: { type: 'session-open'; sessionId: string; cast?: string; replaces?: string }
    | { type: 'session-close'; sessionId: string }) => void): boolean {
    const client = this.opts.client();
    if (!client) return false;
    client.onEvent(event => { if (event.type === 'session-open' || event.type === 'session-close') listener(event); });
    return true;
  }
  prepare(input: { cast: string; room?: string; model?: string; resume?: string; instructions?: string; prompt?: string }):
    AntigravityCastJob['session'] & { instructions?: string; text?: string } {
    const cwd = input.room ?? this.opts.home;
    if (!isAbsolute(cwd) || /[\x00-\x1f\x7f-\x9f]/.test(cwd)) throw new Error(tr('informe a pasta do projeto (caminho absoluto)'));
    if (!input.resume && input.instructions !== undefined && input.instructions.length > 8_000) throw new Error(tr('pedido inválido'));
    return { cwd, resume: input.resume, model: isAgyModel(input.model) ? agyModelValue(input.model!) : undefined,
      ...(!input.resume && input.instructions ? { instructions: input.instructions } : {}), ...(input.prompt ? { text: input.prompt } : {}) };
  }
  async open(job: AntigravityCastJob): Promise<string> {
    const client = this.opts.client();
    const delivery = this.opts.delivery();
    if (!client || !delivery) throw new Error(tr('Antigravity desligado neste Habblaud'));
    try {
      const { sessionId } = await client.open({ cwd: job.session.cwd, resume: job.session.resume, cast: job.cast, model: job.session.model });
      delivery.setInstructions(sessionId, job.instructions);
      return sessionId;
    } catch (error) {
      if (error instanceof UnknownModel) throw new Error(tr('modelo do Antigravity desconhecido: {0}', [job.session.model ?? 'default']));
      if (error instanceof AuthRequired) throw new Error(tr('entre na conta do Antigravity no computador: rode agy e faça o login'));
      throw error;
    }
  }
  deliver(job: AntigravityCastJob, agentId: string, sessionId: string): void {
    if (!job.text) return;
    const registry = this.opts.messages();
    if (!registry) throw new Error(tr('Antigravity não confirmou a entrega da mensagem'));
    const result = registry.send({ agentId, text: job.text, expectedSessionId: sessionId });
    if ('error' in result) throw new Error(('reason' in result && result.reason) || tr('Antigravity não confirmou a entrega da mensagem'));
  }
  async dismiss(sessionId: string): Promise<void> {
    const client = this.opts.client();
    const delivery = this.opts.delivery();
    if (!client || !delivery) throw new Error(tr('Antigravity desligado neste Habblaud'));
    if (client.session(sessionId)?.busy) await delivery.stopSession(sessionId);
    await client.close(sessionId);
  }
}
