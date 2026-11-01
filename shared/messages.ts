// Mensagens pelo escritório (POST /api/messages): o que o servidor, o demo e a página têm em comum.
// Código puro: sem APIs de Node nem de DOM.
import { maskSecrets, truncate, type ActivityDescription } from './activity';
import { tr } from './i18n';

/** Tamanho máximo de uma mensagem (caracteres). */
export const MESSAGE_MAX = 20_000;
/** Limites dos anexos enviados pelo prompt (independentes do Novo agente). */
export const MESSAGE_ATTACHMENTS_MAX = 5;
export const MESSAGE_ATTACHMENT_MAX_BYTES = 15 * 1024 * 1024;
/** `Activity.tool` da atividade "Mensagem pelo Habblaud" (não é uma ferramenta do agente: é o seu texto entrando). */
export const MESSAGE_TOOL = 'HabblaudMessage';
/** Detalhe da atividade: o começo do texto, mascarado e cortado (o texto inteiro vai só para a sessão). */
const DETAIL_MAX = 300;

/** Níveis de raciocínio (thinking) do omp, do menor ao maior. */
export const OMP_THINKING_LEVELS = ['off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'] as const;

/** Modos de entrega de mensagens ao histórico principal do OMP. */
export const OMP_DELIVERY_MODES = ['aside', 'followUp', 'steer'] as const;
export type OmpDeliverAs = (typeof OMP_DELIVERY_MODES)[number];

/**
 * Atividade "Mensagem pelo Habblaud" de uma mensagem entregue à sessão (entrou nela ou na fila dela: não quer dizer
 * que o agente já leu).
 */
export function describeMessage(text: string): ActivityDescription & { tool: string } {
  const detail = truncate(maskSecrets(text.slice(0, DETAIL_MAX * 4)), DETAIL_MAX);
  return { kind: 'communicate', icon: '✉️', text: tr('Mensagem pelo Habblaud'), tool: MESSAGE_TOOL, ...(detail ? { detail } : {}) };
}
