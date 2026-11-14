import { tr } from '../../shared/i18n';
import type { AntigravityClient } from '../antigravity/acp';
import type { AntigravitySource } from '../sources/antigravity/source';
import type { PushDelivery } from './registry';

export const ERR_ANTIGRAVITY_UNAVAILABLE = tr('a sessão Antigravity está indisponível ou em substituição');
export const ERR_ANTIGRAVITY_CONTROL = tr('Antigravity aceita somente STOP ou conversa nova, sem combinações');
export const ERR_ANTIGRAVITY_MODES = tr('Antigravity só recebe a mensagem quando o turno termina');
export const ERR_ANTIGRAVITY_DELIVERY = tr('Antigravity não confirmou a entrega da mensagem');
export const ERR_ANTIGRAVITY_QUEUED = tr('a mensagem Antigravity expirou esperando a sessão ficar disponível');

export class AntigravityDelivery implements PushDelivery {
  private readonly replacing = new Set<string>();
  constructor(private readonly opts: { client: AntigravityClient; source: Pick<AntigravitySource, 'replace'>; diagnostic?: (message: string) => void }) {}
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
    try { await this.opts.client.prompt(id, content); return { ok: true }; }
    catch { return { ok: false, error: ERR_ANTIGRAVITY_DELIVERY }; }
  }
  stopSession(id: string): Promise<void> { return this.opts.client.cancel(id); }
  async newSession(id: string): Promise<string> {
    const session = this.opts.client.session(id);
    if (!this.available(id) || !session) throw new Error(ERR_ANTIGRAVITY_UNAVAILABLE);
    this.replacing.add(id);
    try {
      await this.opts.client.cancel(id);
      const next = await this.opts.source.replace(id, () => this.opts.client.open({ cwd: session.cwd }));
      try { await this.opts.client.close(id); }
      catch { this.opts.diagnostic?.(tr('Antigravity: não foi possível fechar a sessão anterior')); }
      return next;
    } finally { this.replacing.delete(id); }
  }
}
