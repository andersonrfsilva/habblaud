import { tr } from '../../shared/i18n';
import { UnknownModel, type AntigravityClient } from '../antigravity/acp';
import { agyModelValue } from '../../shared/agy-model';
import type { AntigravitySource } from '../sources/antigravity/source';
import type { PushDelivery } from './registry';
import type { HandoffStore } from '../cast/handoff';

export const ERR_ANTIGRAVITY_UNAVAILABLE = tr('a sessão Antigravity está indisponível ou em substituição');
export const ERR_ANTIGRAVITY_CONTROL = tr('Antigravity aceita somente modelo, STOP ou conversa nova, sem combinações');
export const ERR_ANTIGRAVITY_MODES = tr('Antigravity só recebe a mensagem quando o turno termina');
export const ERR_ANTIGRAVITY_DELIVERY = tr('Antigravity não confirmou a entrega da mensagem');
export const ERR_ANTIGRAVITY_QUEUED = tr('a mensagem Antigravity expirou esperando a sessão ficar disponível');

export class AntigravityDelivery implements PushDelivery {
  private readonly replacing = new Set<string>();
  private readonly instructions = new Map<string, { text: string; pending: boolean }>();
  constructor(private readonly opts: { client: AntigravityClient; source: Pick<AntigravitySource, 'replace'>; handoff?: HandoffStore; diagnostic?: (message: string) => void }) {
    opts.client.onEvent(event => { if (event.type === 'session-close') this.instructions.delete(event.sessionId); });
  }
  setInstructions(sessionId: string, instructions: string | undefined): void {
    if (instructions) this.instructions.set(sessionId, { text: instructions, pending: true });
    else this.instructions.delete(sessionId);
  }
  onEvent(listener: Parameters<PushDelivery['onEvent']>[0]): () => void {
    return this.opts.client.onEvent(event => {
      if (event.type === 'session-open' || event.type === 'session-close' || event.type === 'turn-state') listener(event);
    });
  }
  available(id: string): boolean { return !this.replacing.has(id) && !!this.opts.client.session(id); }
  busy(id: string): boolean { return this.opts.client.session(id)?.busy ?? true; }
  continuityLost(_id: string): boolean { return false; }
  async sendMessage(id: string, content: string): Promise<{ ok: true } | { ok: false; error: string }> {
    if (!this.available(id)) return { ok: false, error: ERR_ANTIGRAVITY_UNAVAILABLE };
    const instructions = this.instructions.get(id);
    const cast = this.opts.client.session(id)?.cast;
    const path = cast ? this.opts.handoff?.take(cast) : undefined;
    const request = instructions?.pending ? `Instruções do personagem:\n${instructions.text}\n\nPedido inicial:\n${content}` : content;
    const text = path ? `${tr('A conversa anterior, feita em outra ferramenta, está no arquivo anexo. Leia antes de responder.')}\n\n${request}` : request;
    try {
      if (path) await this.opts.client.prompt(id, text, [{ path, name: 'conversa-anterior.md' }]);
      else await this.opts.client.prompt(id, text);
      if (instructions) instructions.pending = false;
      return { ok: true };
    }
    catch {
      if (cast && path) this.opts.handoff?.restore(cast, path);
      return { ok: false, error: ERR_ANTIGRAVITY_DELIVERY };
    }
  }
  async setModel(id: string, model: string): Promise<void> {
    const value = agyModelValue(model);
    if (value === undefined) throw new UnknownModel(tr('escolha um modelo do Antigravity; não é possível voltar ao padrão numa conversa aberta'));
    try { await this.opts.client.setModel(id, value); }
    catch (error) {
      if (error instanceof UnknownModel) throw new UnknownModel(tr('modelo do Antigravity desconhecido: {0}', [value]));
      throw error;
    }
  }
  stopSession(id: string): Promise<void> { return this.opts.client.cancel(id); }
  async newSession(id: string): Promise<string> {
    const session = this.opts.client.session(id);
    if (!this.available(id) || !session) throw new Error(ERR_ANTIGRAVITY_UNAVAILABLE);
    const instructions = this.instructions.get(id)?.text;
    this.replacing.add(id);
    try {
      await this.opts.client.cancel(id);
      const next = await this.opts.source.replace(id, () => this.opts.client.open({ cwd: session.cwd, replaces: id, cast: session.cast, model: session.model }));
      this.setInstructions(next, instructions);
      try { await this.opts.client.close(id); }
      catch { this.opts.diagnostic?.(tr('Antigravity: não foi possível fechar a sessão anterior')); }
      return next;
    } finally { this.replacing.delete(id); }
  }
}
