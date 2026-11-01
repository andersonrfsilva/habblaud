// Caixa de mensagem (ui/composer.ts), parte pura e testada em ui/composer-model.test.ts: quando a caixa aparece (e
// por que não), o que a tecla Enter faz, a linha de situação de cada envio e o ritmo da consulta da entrega.
// No Codex a mensagem entra na fila da sessão (`codex queue`) e vira prompt quando ela fica ociosa: "Entregue" diz
// isso; sem quem entregue (no Docker, o auxiliar do host), a dica é a do `npm run codex:bridge`. No omp a extensão do
// Habblaud busca as mensagens e as entrega conforme o modo escolhido; a dica de instalação é a do `npm run omp:install`.
import { MESSAGE_MAX, MESSAGE_ATTACHMENTS_MAX, MESSAGE_ATTACHMENT_MAX_BYTES, type OmpDeliverAs } from '../../../shared/messages';
import type { AgentInfo, OutboxStatus, Provider } from '../../../shared/types';
import { isNeutralBrand, tr } from '../../../shared/i18n';

/** Dica para quem tem o recurso ligado, mas a sessão do agente não está com o plugin conectado. */
export const PLUGIN_HINT = tr('Para mandar mensagens daqui: npm run mod:install (plugin habblaud-mensagens)');
/** A mesma dica para um agente do Codex sem entregador (no modo Node o próprio servidor entrega). */
export const CODEX_BRIDGE_HINT = tr('Para mandar mensagens ao Codex: com o Habblaud no Docker, deixe npm run codex:bridge rodando; no modo Node funciona sozinho');
/** A mesma dica para um agente do omp cuja sessão não está com a extensão do Habblaud (sem marca: sem o comando). */
export const OMP_EXTENSION_HINT = isNeutralBrand()
  ? tr('Esta sessão ainda não recebe mensagens daqui: reabra a sessão')
  : tr('Para mandar mensagens ao omp: npm run omp:install (extensão do Habblaud no omp) e reabra a sessão');

/**
 * - ready: dá para mandar (principal com o plugin conectado, recurso ligado, página local);
 * - hint: recurso ligado e principal presente, mas a sessão não está com o plugin (`text` diz como instalar);
 * - off: não dá (`text` diz por quê). A gaveta esconde a caixa; o terminal mostra o motivo no lugar dela.
 */
export type ComposerMode = { kind: 'ready' } | { kind: 'hint'; text: string } | { kind: 'off'; text: string };

export interface ComposerEnv {
  /** Recurso ligado no servidor (OfficeSnapshot.meta.messages; no ?mock=1, sempre). */
  enabled: boolean;
  /** Página aberta pelo próprio computador (o servidor só aceita mensagens assim). */
  local: boolean;
  /** Timelapse: o escritório mostrado é o do momento reproduzido. */
  replaying: boolean;
}

export function composerMode(agent: AgentInfo | undefined, env: ComposerEnv): ComposerMode {
  if (env.replaying) return { kind: 'off', text: tr('Sem mensagens no timelapse: o escritório mostrado é o de outro momento') };
  if (agent?.kind === 'sub') return { kind: 'off', text: tr('Subagentes não recebem mensagens: escreva para o agente principal') };
  if (!agent || agent.status === 'offline' || agent.status === 'done') return { kind: 'off', text: tr('Sessão encerrada') };
  const codex = agent.provider === 'codex';
  const omp = agent.provider === 'omp';
  if (!env.enabled) return { kind: 'off', text: codex ? tr('Para responder, use o Codex') : omp ? tr('Para responder, use o omp') : tr('Para responder, use o terminal do Claude Code') };
  if (!env.local) return { kind: 'off', text: tr('Para mandar mensagens por aqui, abra o Habblaud por http://localhost (ou 127.0.0.1)') };
  if (!agent.canMessage) return { kind: 'hint', text: codex ? CODEX_BRIDGE_HINT : omp ? OMP_EXTENSION_HINT : PLUGIN_HINT };
  return { kind: 'ready' };
}

/** Dica embaixo da caixa (gaveta): como a mensagem entra na sessão. */
export function composerTip(provider: Provider = 'claude'): string {
  return provider === 'codex'
    ? tr('Entra na fila da sessão e vira o próximo prompt quando o Codex terminar o que está fazendo. Enter manda; Shift+Enter quebra a linha.')
    : tr('Entra na sessão como se você tivesse digitado. Enter manda; Shift+Enter quebra a linha.');
}

/** O que sai da caixa: o texto como foi digitado, sem os espaços e linhas em branco do fim. */
export function messageText(raw: string): string {
  return raw.replace(/\s+$/, '');
}

export function canSend(raw: string, attachments = 0): boolean {
  const text = messageText(raw);
  return (text.trim().length > 0 || attachments > 0) && text.length <= MESSAGE_MAX;
}

/** Enter manda; Shift+Enter (e Alt+Enter) quebram a linha; Enter compondo um acento (IME) não conta. */
export function enterSends(e: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'altKey' | 'isComposing'>): boolean {
  return e.key === 'Enter' && !e.shiftKey && !e.altKey && !e.isComposing;
}

/**
 * Situação do último envio de um agente: `sending` (o POST ainda não voltou), `rejected` (o servidor recusou: o texto
 * fica na caixa) ou a situação da mensagem no servidor (OutboxStatus).
 */
export type SendPhase = 'sending' | 'rejected' | OutboxStatus;

export interface SendState {
  phase: SendPhase;
  /** Id da mensagem no servidor (depois do 201). */
  id?: string;
  error?: string;
  deliverAs?: OmpDeliverAs;
  /** Última mudança (epoch ms, relógio da página). */
  at: number;
}

export function isSettled(phase: SendPhase): boolean {
  return phase === 'delivered' || phase === 'failed' || phase === 'rejected';
}

/** "Entregue" some sozinho depois deste tempo; erros ficam até o próximo envio. */
export const DELIVERED_SHOW_MS = 12_000;

/** Texto da linha de situação ('' = nada a mostrar). */
export function sendStatusText(s: SendState | undefined, now: number, provider: Provider = 'claude'): string {
  if (!s) return '';
  const codex = provider === 'codex';
  switch (s.phase) {
    case 'sending':
      return tr('Enviando…');
    case 'queued':
      return codex ? tr('Na fila: entregando ao Codex…') : provider === 'omp' ? tr('Na fila: esperando a extensão do omp buscar a mensagem…') : tr('Na fila: esperando a sessão buscar a mensagem…');
    case 'sent':
      return tr('Entregando à sessão…');
    case 'delivered':
      // Entrega ao runtime não confirma leitura pelo modelo nem conclusão do trabalho.
      if (now - s.at >= DELIVERED_SHOW_MS) return '';
      if (provider === 'omp') {
        if (s.deliverAs === 'aside') return tr('Aceita pelo runtime ✓: se ocupado, acrescenta no próximo limite de etapa; não confirma leitura nem conclusão.');
        if (s.deliverAs === 'steer') return tr('Aceita pelo runtime ✓: se ocupado, muda direção no próximo ponto possível; não confirma leitura nem conclusão.');
        return tr('Aceita pelo runtime ✓: se ocupado, continua após o turno atual; não confirma leitura nem conclusão.');
      }
      return codex
        ? tr('Entregue ✓ na fila da sessão: entra quando o Codex terminar o que está fazendo (até ~10 s)')
        : tr('Entregue ✓ (se o agente estiver ocupado, entra quando ele terminar)');
    case 'rejected':
      return tr('Não foi possível mandar: {0}', [s.error ?? tr('erro desconhecido')]);
    case 'failed':
      return tr('Não foi entregue: {0}', [s.error ?? tr('erro desconhecido')]);
  }
}

/** Consulta da entrega: o servidor leva até ~90 s para desistir (60 s na fila + 30 s sem confirmação). */
export const POLL_LIMIT_MS = 120_000;

/** Espera até a próxima consulta: rápida no começo (a sessão busca a cada 2 s), mais espaçada depois. */
export function pollDelay(elapsed: number): number {
  if (elapsed < 10_000) return 700;
  if (elapsed < 40_000) return 1_500;
  return 3_000;
}

export const LOST_ERROR = tr('o Habblaud não conhece mais esta mensagem (ele reiniciou?)');
export const TIMEOUT_ERROR = tr('sem notícia da entrega: confira no terminal do Claude Code');
export const CODEX_TIMEOUT_ERROR = tr('sem notícia da entrega: confira no Codex');
export const OMP_TIMEOUT_ERROR = tr('sem notícia da entrega: confira no omp');
export const OFFLINE_ERROR = tr('não foi possível falar com o Habblaud');

/** Prazo da consulta acabou sem notícia: onde conferir. */
export function timeoutError(provider: Provider = 'claude'): string {
  return provider === 'codex' ? CODEX_TIMEOUT_ERROR : provider === 'omp' ? OMP_TIMEOUT_ERROR : TIMEOUT_ERROR;
}

/** Texto do clipboard tem precedência, mesmo quando o navegador também oferece uma imagem. */
export function pastedAttachments(data: Pick<DataTransfer, 'getData' | 'files'>): File[] {
  if (data.getData('text/plain') || data.getData('text/html')) return [];
  return Array.from(data.files);
}

export interface ComposerAttachment {
  file: File;
  url?: string;
  id?: string;
}

/** Rascunhos guardam os File mesmo após uma recusa; IDs pertencem à mesma seleção, não ao agente em exibição. */
export class ComposerAttachments {
  private drafts = new Map<string, ComposerAttachment[]>();
  private destroyed = false;

  constructor(private urls = {
    create: (file: File) => URL.createObjectURL(file),
    revoke: (url: string) => URL.revokeObjectURL(url),
  }) {}

  get(agentId: string): readonly ComposerAttachment[] {
    return this.drafts.get(agentId) ?? [];
  }

  add(agentId: string, files: readonly File[]): string[] {
    if (this.destroyed) return [];
    const list = [...this.get(agentId)];
    const errors: string[] = [];
    for (const file of files) {
      if (file.size > MESSAGE_ATTACHMENT_MAX_BYTES) {
        errors.push(tr('Arquivo acima de 15 MiB: {0}', [file.name]));
      } else if (list.length >= MESSAGE_ATTACHMENTS_MAX) {
        errors.push(tr('Limite de 5 anexos: {0}', [file.name]));
      } else {
        list.push({ file, ...(file.type.startsWith('image/') ? { url: this.urls.create(file) } : {}) });
      }
    }
    this.drafts.set(agentId, list);
    return errors;
  }

  remove(agentId: string, attachment: ComposerAttachment): void {
    if (!this.get(agentId).includes(attachment)) return;
    if (attachment.url) this.urls.revoke(attachment.url);
    const list = this.get(agentId).filter((item) => item !== attachment);
    if (list.length) this.drafts.set(agentId, list);
    else this.drafts.delete(agentId);
  }

  capture(agentId: string): ComposerAttachment[] {
    return [...this.get(agentId)];
  }

  async upload(batch: readonly ComposerAttachment[], upload: (file: File) => Promise<{ id: string } | { error: string }>): Promise<string[]> {
    const ids: string[] = [];
    for (const attachment of batch) {
      if (this.destroyed) throw new Error(OFFLINE_ERROR);
      if (!attachment.id) {
        const result = await upload(attachment.file);
        if ('error' in result) throw new Error(result.error);
        attachment.id = result.id;
      }
      ids.push(attachment.id);
    }
    return ids;
  }

  invalidate(batch: readonly ComposerAttachment[]): void {
    for (const attachment of batch) delete attachment.id;
  }

  accepted(agentId: string, batch: readonly ComposerAttachment[]): void {
    for (const attachment of batch) this.remove(agentId, attachment);
  }

  destroy(): void {
    this.destroyed = true;
    for (const [agentId, list] of this.drafts) this.accepted(agentId, list);
  }
}
