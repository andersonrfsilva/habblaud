import { createHash, randomUUID } from 'node:crypto';
import { normalizeCwd } from '../model/rooms';
import type { ZeroClawConnector, OpenSession, ConnectorEvent } from '../sources/zeroclaw/connector';
import type { ZeroClawDelivery } from '../messages/zeroclaw';
import type { MessageRegistry } from '../messages/registry';

export interface ZeroClawCatalog {
  models: { alias: string; model: string }[];
  defaultModel: string;
}

/** Validate once at startup; aliases match the tenant's full UTF-8 model identifiers. */
export function parseZeroClawCatalog(raw: string | undefined, defaultAgent: string | undefined): ZeroClawCatalog {
  if (!raw?.trim()) throw new Error('HABBLAUD_ZEROCLAW_AGENTS obrigatória');
  if (!defaultAgent) throw new Error('HABBLAUD_ZEROCLAW_DEFAULT_AGENT obrigatória');
  const invalid = () => new Error('HABBLAUD_ZEROCLAW_AGENTS inválida');
  let entries: unknown;
  try { entries = JSON.parse(raw); } catch { throw invalid(); }
  if (!Array.isArray(entries) || !entries.length) throw invalid();
  const aliases = new Set<string>();
  const models = new Set<string>();
  const catalog: ZeroClawCatalog = { models: [], defaultModel: defaultAgent };
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry) ||
        typeof entry.alias !== 'string' || !/^m_[0-9a-f]{12}$/.test(entry.alias) ||
        typeof entry.model !== 'string' || !entry.model || /[\s\x00-\x1f\x7f-\x9f*?\[\]]/u.test(entry.model)) throw invalid();
    const alias = `m_${createHash('sha256').update(entry.model, 'utf8').digest('hex').slice(0, 12)}`;
    if (entry.alias !== alias || aliases.has(entry.alias) || models.has(entry.model)) throw invalid();
    aliases.add(entry.alias);
    models.add(entry.model);
    catalog.models.push({ alias: entry.alias, model: entry.model });
  }
  if (!aliases.has(defaultAgent)) throw new Error('HABBLAUD_ZEROCLAW_DEFAULT_AGENT inválida');
  return catalog;
}

/** Legacy model identifiers resolve only by exact equality, never by a model-family fallback. */
export function resolveZeroClawModel(catalog: ZeroClawCatalog, model?: string): string {
  if (model === undefined) return catalog.defaultModel;
  const entry = catalog.models.find((entry) => entry.alias === model || entry.model === model);
  if (!entry) throw new Error('Modelo ZeroClaw não configurado');
  return entry.alias;
}

export type ZeroClawCastJob = {
  id: string; op: 'start' | 'stop'; cast: string; session: OpenSession; text?: string;
};
export interface ZeroClawCastOptions {
  catalog: ZeroClawCatalog;
  connector(): Pick<ZeroClawConnector, 'openSession' | 'closeSession' | 'metadataOf' | 'session' | 'openSessions' | 'onEvent'> | undefined;
  delivery(): Pick<ZeroClawDelivery, 'cancelSession' | 'stopSession'> | undefined;
  messages(): Pick<MessageRegistry, 'send'> | undefined;
  account(): string | undefined;
  /** Pasta pessoal do servidor: toda sessão nova abre nela. */
  home: string;
  ready(): boolean;
  afterOpen?(): Promise<void>;
}

/** Concrete cast operations share the source connector and the existing message registry. */
export class ZeroClawCast {
  constructor(private readonly options: ZeroClawCastOptions) {}
  available(): boolean {
    return this.options.ready() && !!this.options.connector() && !!this.options.delivery() && !!this.options.messages();
  }
  models(): { provider: 'zeroclaw'; models: { alias: string; model: string }[]; defaultModel: string } {
    return { provider: 'zeroclaw', models: this.options.catalog.models.map((entry) => ({ ...entry })), defaultModel: this.options.catalog.defaultModel };
  }
  session(id: string) { return this.options.connector()?.session(id); }
  openSessions() { return this.options.connector()?.openSessions() ?? []; }
  observe(listener: (event: ConnectorEvent) => void): boolean {
    const connector = this.options.connector();
    if (!connector) return false;
    connector.onEvent(listener);
    return true;
  }
  prepare(input: { cast: string; name: string; room?: string; model?: string; resume?: string;
    instructions?: string; prompt?: string; account?: unknown }): { session: OpenSession; text?: string } {
    const connector = this.options.connector();
    if (!connector) throw new Error('ZeroClaw está indisponível');
    const room = input.room ?? this.options.home;
    if (/[\x00-\x1f]/.test(room)) throw new Error('Pasta ZeroClaw inválida');
    if (input.account !== undefined && (typeof input.account !== 'string' || input.account !== this.options.account())) {
      throw new Error('Conta ZeroClaw incompatível');
    }
    let alias = resolveZeroClawModel(this.options.catalog, input.model);
    if (input.resume !== undefined) {
      const original = connector.metadataOf(input.resume);
      if (!original || normalizeCwd(original.cwd) !== room
        || !this.options.catalog.models.some((entry) => entry.alias === original.agent)) throw new Error('Histórico ZeroClaw incompatível com a sala ou o catálogo');
      if (input.model !== undefined && alias !== original.agent) throw new Error('Modelo da conversa ZeroClaw diferente: abra uma conversa nova');
      if (connector.session(input.resume)) throw new Error('Conversa ZeroClaw já aberta');
      alias = original.agent;
    }
    if (input.instructions !== undefined && input.instructions.length > 8_000) throw new Error('Instruções ZeroClaw acima do limite');
    const text = input.resume === undefined && input.instructions
      ? `Instruções do personagem:\n${input.instructions}${input.prompt ? `\n\nPedido inicial:\n${input.prompt}` : ''}`
      : input.prompt;
    return { session: { sessionId: input.resume ?? randomUUID(), agent: alias, name: input.name, cwd: room, cast: input.cast },
      ...(text ? { text } : {}) };
  }
  async open(session: OpenSession): Promise<void> {
    const connector = this.options.connector();
    if (!connector) throw new Error('ZeroClaw está indisponível');
    await connector.openSession(session);
    await this.options.afterOpen?.();
  }
  deliver(job: ZeroClawCastJob, agentId: string): void {
    if (!job.text) return;
    const registry = this.options.messages();
    if (!registry) throw new Error('Mensagens ZeroClaw indisponíveis');
    const result = registry.send({ agentId, text: job.text, expectedSessionId: job.session.sessionId });
    if ('error' in result) throw new Error(('reason' in result && result.reason) || `ZeroClaw não recebeu o pedido inicial: ${result.error}`);
  }
  async dismiss(id: string): Promise<void> {
    const connector = this.options.connector();
    if (!connector) throw new Error('ZeroClaw está indisponível');
    const delivery = this.options.delivery();
    if (!delivery) throw new Error('Controles ZeroClaw indisponíveis');
    delivery.cancelSession(id, 'A sessão ZeroClaw está sendo dispensada');
    const session = connector.session(id);
    if (session?.busy || session?.continuityLost) await delivery.stopSession(id);
    await connector.closeSession(id);
  }
}
