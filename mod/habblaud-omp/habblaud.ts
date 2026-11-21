// Extensão do Habblaud para o omp (oh-my-pi): carregada pelo omp 18+ de <pasta do omp>/extensions/habblaud.ts (o
// instalador, scripts/omp-install.ts, grava lá um arquivo de uma linha que reexporta este). Roda dentro do Bun do omp,
// fora do projeto: usa node:*, fetch e módulos compartilhados do Habblaud, sem dependências externas.
//
// O que faz (design 2, 4 e 5 da mudança suporte-ao-omp):
//
// 1. Eventos: session_start, agent_start, agent_end, tool_execution_start/_end, tool_approval_requested/_resolved e
//    session_shutdown viram POST http://127.0.0.1:<porta>/api/omp/events (um OmpEvent, ver shared/omp-events.ts), mais
//    um batimento a cada 10 s: é ele que diz ao servidor que a sessão está viva (sem batimento por 30 s ela é dada
//    como encerrada). Porta: HABBLAUD_PORT no ambiente do omp › ~/.habblaud/omp-extension.json {"port"} › 4747, lida a
//    cada conexão. Nenhuma falha de rede chega ao omp: fetch com prazo de 2 s, erro engolido, e os eventos saem em
//    fila, um de cada vez (a ordem importa), sem acumular quando o Habblaud trava.
// 2. Mensagens: laço inbox → entrega → ack com as rotas existentes (POST /api/mod/inbox e /api/mod/inbox/ack,
//    `session` = id da sessão do omp). A pergunta também marca a presença da caixa de mensagem no escritório. Cada
//    mensagem entra por pi.sendUserMessage: com o agente ocupado, usa o modo pedido (ou 'followUp' se omitido);
//    livre, SEM deliverAs, que no omp 18.12 inicia um turno novo (com
//    deliverAs 'followUp' e o agente livre o omp só enfileira e a mensagem esperaria o próximo pedido). Perguntas a cada 2 s à toa, 5 s com o agente ocupado (a presença vale 10 s no
//    servidor), 30 s com o Habblaud fora do ar, sem as mensagens ligadas (403) ou respondendo qualquer coisa que
//    não seja 2xx.
// 3. Permissões, só com HABBLAUD_OMP_PERMISSOES=1 no ambiente do omp (modo de decisão): no `tool_call` de ferramenta
//    que não é só de leitura, abre ao mesmo tempo um pedido no Habblaud (POST /api/permissions, provider "omp") e,
//    com UI, um diálogo no terminal (ctx.ui.confirm com signal). A primeira resposta decide e fecha a outra: o
//    diálogo é cancelado, ou o pedido é retirado do escritório (POST /api/permissions/:id/decision
//    {behavior: "terminal"}). Recusa = {block, reason} (o motivo vai ao modelo). O prazo: com UI, a espera do
//    Habblaud (5 min: o omp não conta o prazo dos handlers com um diálogo da extensão aberto); sem UI,
//    HABBLAUD_OMP_TIMEOUT_MS (o `extensionHandlers.toolCallTimeoutMs` do omp, 30 000 por padrão) menos 3 s. Sem
//    resposta: "sem resposta a tempo". Habblaud que não aceita o registro em 2 s: só o diálogo (sem UI: recusa "Habblaud
//    fora do ar"). Esse modo é para o omp em --approval-mode yolo; em outro modo, um aviso único por sessão.
// 4. Perguntas (ferramenta `ask`): no session_start, troca o `ask` do omp por um de mesmo nome, descrição e
//    parâmetros (pi.registerTool com os do nativo, de pi.getAllTools) que abre a pergunta nos dois lugares: o diálogo
//    do omp no terminal (o nativo, por ctx.invokeTool) e o cartão "Pergunta para você" no escritório (POST
//    /api/permissions, tool_name "AskUserQuestion", as perguntas no formato do Claude Code). A primeira resposta
//    vale: resposta no escritório fecha o diálogo (signal) e volta ao modelo no mesmo texto do `ask` nativo;
//    resposta no terminal retira o cartão ({behavior: "terminal"}). "Não responder" no escritório vira erro da
//    ferramenta com o motivo, e o agente segue. Habblaud fora do ar, ninguém olhando ou pergunta que o cartão não
//    mostra inteira (mais de 4): só o diálogo, como sem a extensão.
// 5. Modelos liberados: com HABBLAUD_OMP_MODELS ("provedor/id" ou "provedor/*", separados por vírgula) no ambiente
//    do omp, a gaveta só lista e só troca para esses (o omp lista o catálogo inteiro do provedor mesmo quando um
//    gateway só atende parte dele). Sem a variável, tudo que o omp lista.
// 6. Bate-papo paralelo: um item do inbox com `aside` inicia ctx.runEphemeralTurn (a maquinaria do /btw do omp: modelo e
//    conversa de agora, fora do histórico, com o turno principal rodando). O ack sai na hora; o texto acumulado da
//    resposta vai por POST /api/omp/aside (a cada 0,5 s com texto novo, ao menos a cada 1 s), e a resposta do servidor
//    diz se a pergunta deve ser abortada (o usuário apertou Parar). Uma por vez; trocar de sessão aborta.
// 7. Elenco: HABBLAUD_CAST escolhe o membro pedido pela sessão, informado no início e no batimento.
//
// Subagentes (ctx.agent.kind === 'sub'): o omp refaz as extensões para cada um; eles não mandam eventos nem
// perguntam o inbox (o escritório já vê os subagentes pelo arquivo da sessão), mas as ferramentas deles passam pelo
// modo de decisão, no nome da sessão principal.
//
// Textos que vão ao modelo ou ao terminal do omp são em português fixo: a extensão roda fora do Habblaud, sem o tr().
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { OMP_COMMANDS, OMP_HEARTBEAT_MS, OMP_SESSION_ID, type OmpCommand, type OmpEvent, type OmpEventKind } from '../../shared/omp-events';
import { ASIDE_ANSWER_MAX, ASIDE_HEARTBEAT_MS, ASIDE_PROGRESS_MS, clipAnswer, type AsideProgress, type AsideRequest } from '../../shared/asides';
import { ANSWER_OTHER_MAX, ASK_LABEL_MAX, ASK_MAX_OPTIONS, ASK_MAX_QUESTIONS, ASK_QUESTION_MAX } from '../../shared/ask-limits';
import { maskSecrets } from '../../shared/activity';
import type { OmpDeliverAs } from '../../shared/messages';

export const DEFAULT_PORT = 4747;
/** Quanto esperar o Habblaud responder (qualquer rota). */
export const FETCH_TIMEOUT_MS = 2_000;
export const INBOX_IDLE_MS = 2_000;
export const INBOX_BUSY_MS = 5_000;
export const INBOX_OFFLINE_MS = 30_000;
/** Esperas antes de repetir o ack da caixa de entrada que não chegou: o servidor falha a mensagem sem ack em 30 s. */
export const ACK_RETRY_MS: readonly number[] = [300, 1_000, 3_000];
/** Comando de vários passos (/shake): espera o omp tirar o passo anterior do editor até este tempo... */
export const COMMAND_ACCEPT_MS = 3_000;
/** ...e então esta folga, para o passo anterior terminar antes do próximo (o /shake não avisa quando acaba). */
export const COMMAND_GAP_MS = 1_000;
/** Tamanho máximo do motivo de uma falha de entrega. */
export const MAX_REASON = 500;
/** Espera do Habblaud pelo pedido de permissão com o diálogo aberto (o padrão do servidor: DEFAULT_TIMEOUT_MS). */
export const DECISION_UI_MS = 300_000;
/** `extensionHandlers.toolCallTimeoutMs` padrão do omp. */
export const DEFAULT_TOOL_CALL_TIMEOUT_MS = 30_000;
/** Folga de uma recusa por prazo sobre o `toolCallTimeoutMs` (o omp bloqueia o handler nesse prazo). */
export const NO_UI_MARGIN_MS = 3_000;
export const MIN_DECISION_MS = 1_000;
/** Duração de cada long-poll de GET /api/permissions/:id/wait (s): o servidor corta em 25 s. */
const POLL_SECONDS = 25;
/** Fila de eventos: passando disso o Habblaud está travado e os eventos novos de ferramenta são largados. */
const MAX_QUEUE = 32;
const MAX_DIALOG_DETAIL = 800;
/** Ferramenta do cartão de pergunta no escritório (o nome do Claude Code). */
export const ASK_TOOL = 'AskUserQuestion';
/** Espera do Habblaud por uma pergunta: o `ask` do omp não tem prazo (ask.timeout 0), então o máximo do servidor. */
export const ASK_WAIT_MS = 24 * 60 * 60_000;

/**
 * Ferramentas só de leitura do omp: nunca entram no modo de decisão. O omp não expõe o nível da ferramenta ao
 * `tool_call`, então a lista é fixa (a base é READ_ONLY_TOOL_NAMES do omp, task/read-only-policy.ts); o que não está
 * aqui conta como `write`/`exec`, o mesmo padrão do omp para ferramenta sem nível declarado: pede mais, não menos.
 */
export const READ_ONLY_TOOLS: ReadonlySet<string> = new Set([
  'read',
  'wait',
  'grep',
  'glob',
  'find',
  'ls',
  'web_search',
  'fetch',
  'lsp',
  'jfind',
  'ast_grep',
  'yield',
  'ask',
  'todo',
  'think',
  'recall',
  'reflect',
  'retain',
  'memory_edit',
  'checkpoint',
  'rewind',
  'vibe_list',
]);

// ---------------------------------------------------------------------------------------------
// O que a extensão enxerga do omp (só o que usa; o pacote do omp não é dependência)
// ---------------------------------------------------------------------------------------------

type Rec = Record<string, unknown>;

export interface OmpContext {
  hasUI: boolean;
  cwd?: string;
  agent?: { kind?: string };
  /** "tui" com a tela do omp no terminal (os comandos do Habblaud só valem nela). */
  mode?: string;
  /** O agente está livre (não está num turno). */
  isIdle?(): boolean;
  /** Interrompe o turno em andamento (o Esc da tela do omp). */
  abort?(): void;
  /** Tokens em uso e janela do modelo (o mesmo número do rodapé do omp). */
  getContextUsage?(): { tokens?: number; contextWindow?: number } | undefined;
  ui: {
    confirm(title: string, message: string, options?: { signal?: AbortSignal }): Promise<boolean>;
    notify(message: string, type?: 'info' | 'warning' | 'error'): void;
    setEditorText?(text: string): void;
    getEditorText?(): string;
  };
  models?: { list(): { provider: string; id: string }[]; resolve?(spec: string): unknown; current?(): { provider: string; id: string; api?: string } | undefined };
  /**
   * Pergunta lateral (o /btw do omp): usa o modelo e a conversa de agora, não entra no histórico e roda com o turno
   * principal ativo. O limite de tokens de saída e o desligamento das ferramentas ficam de fora de propósito: vários
   * transportes recusam o primeiro antes de começar e o segundo quebra o reaproveitamento do cache.
   */
  runEphemeralTurn?(opts: {
    promptText: string;
    history?: unknown[];
    conversationKey?: string;
    dedupeReply?: boolean;
    onTextDelta?: (delta: string) => void;
    signal?: AbortSignal;
  }): Promise<{ replyText: string }>;
  sessionManager: {
    getSessionId(): string;
    getSessionFile?(): string | undefined;
    getCwd?(): string;
  };
}

/** Contexto do `execute` de uma ferramenta registrada: o da sessão mais o nativo de mesmo nome. */
export interface ToolContext extends OmpContext {
  invokeTool?(params: unknown, options?: { signal?: AbortSignal }): Promise<unknown>;
}

export interface ToolInfo {
  name: string;
  description?: string;
  /** Schema do omp (opaco: repassado como veio). */
  parameters?: unknown;
}

export interface ToolDefinition {
  name: string;
  label: string;
  description: string;
  parameters: unknown;
  approval: 'read';
  strict: true;
  lenientArgValidation: true;
  concurrency: 'exclusive';
  execute(toolCallId: string, params: unknown, signal: AbortSignal | undefined, onUpdate: unknown, ctx: ToolContext): Promise<unknown>;
}

export interface OmpApi {
  on(event: string, handler: (event: Rec, ctx: OmpContext) => unknown): void;
  sendUserMessage(text: string, options?: { deliverAs?: OmpDeliverAs }): void;
  /** Troca o modelo da sessão; o omp devolve false quando não há credencial para ele. */
  setModel?(model: unknown): Promise<boolean> | boolean;
  setThinkingLevel?(level: string): void;
  getAllTools?(): ToolInfo[];
  registerTool?(tool: ToolDefinition): void;
}

export interface ToolCallResult {
  block: true;
  reason: string;
}

export interface Deps {
  env: NodeJS.ProcessEnv;
  home: string;
  fetch: typeof fetch;
  now: () => number;
  /** Compartilhado entre as instâncias da extensão do mesmo processo (o omp refaz a extensão para cada subagente). */
  shared: { mainSessionId?: string };
  /** Tecla Enter na tela do omp (comandos do Habblaud: /new, /shake). Padrão: um "\r" no stdin do processo. */
  pressEnter?: () => void;
}

const SHARED: Deps['shared'] = {};

// ---------------------------------------------------------------------------------------------
// Funções puras (testadas em server/test/omp-extension.test.ts)
// ---------------------------------------------------------------------------------------------

function validPort(n: unknown): number | undefined {
  return typeof n === 'number' && Number.isInteger(n) && n > 0 && n < 65_536 ? n : undefined;
}

/** Caminho do arquivo de configuração da extensão (o instalador grava a porta nele). */
export function configPath(home: string): string {
  return join(home, '.habblaud', 'omp-extension.json');
}

/** Porta do Habblaud: HABBLAUD_PORT › omp-extension.json › padrão. Lida a cada chamada. */
export function portOf(env: NodeJS.ProcessEnv, home: string): number {
  const fromEnv = validPort(Number.parseInt(env.HABBLAUD_PORT ?? '', 10));
  if (fromEnv) return fromEnv;
  try {
    const p = configPath(home);
    if (existsSync(p)) {
      const j: unknown = JSON.parse(readFileSync(p, 'utf8'));
      const port = j && typeof j === 'object' ? validPort((j as Rec).port) : undefined;
      if (port) return port;
    }
  } catch {
    // arquivo ilegível: porta padrão
  }
  return DEFAULT_PORT;
}

/** Modelos que a gaveta pode mostrar e usar: os do omp filtrados por HABBLAUD_OMP_MODELS (ausente = todos). */
export function allowedModels(models: string[], env: NodeJS.ProcessEnv): string[] {
  const grants = (env.HABBLAUD_OMP_MODELS ?? '').split(',').map((g) => g.trim()).filter(Boolean);
  if (!grants.length) return models;
  return models.filter((m) => grants.some((g) => g === m || (g.endsWith('/*') && m.startsWith(g.slice(0, -1)))));
}

export interface InboxMessage {
  id: string;
  text: string;
  deliverAs?: unknown;
  /** Troca de modelo e/ou raciocínio, um comando do omp, STOP ou /resume (POST /api/messages/control): sem texto. */
  control?: { model?: string; thinking?: string; command?: string; stop?: boolean; resume?: string };
  /** Pergunta do bate-papo paralelo (a janela lateral do terminal do Habblaud): sem texto; a extensão a inicia e confirma na hora. */
  aside?: AsideRequest;
}

export interface AckResult {
  id: string;
  ok: boolean;
  error?: string;
}

/**
 * Lê a resposta de POST /api/mod/inbox; fora do formato = undefined (vale "fora do ar"). Item sem id fica de fora (não
 * há como confirmá-lo); com id e sem texto, entra com texto vazio, para ser confirmado como falha.
 */
export function parseInbox(text: string): InboxMessage[] | undefined {
  let j: unknown;
  try {
    j = JSON.parse(text);
  } catch {
    return undefined;
  }
  if (!j || typeof j !== 'object' || Array.isArray(j)) return undefined;
  const list = (j as Rec).messages;
  if (!Array.isArray(list)) return undefined;
  const out: InboxMessage[] = [];
  for (const item of list) {
    if (!item || typeof item !== 'object') continue;
    const m = item as Rec;
    if (typeof m.id !== 'string' || !m.id) continue;
    const msg: InboxMessage = { id: m.id, text: typeof m.text === 'string' ? m.text : '' };
    if ('deliverAs' in m) msg.deliverAs = m.deliverAs;
    const a = m.aside && typeof m.aside === 'object' && !Array.isArray(m.aside) ? (m.aside as Rec) : undefined;
    if (a && typeof a.topicId === 'string' && typeof a.turnId === 'string' && typeof a.question === 'string' && typeof a.index === 'number' && Array.isArray(a.history)) {
      const history = a.history.flatMap((h: unknown) => {
        const r = h && typeof h === 'object' ? (h as Rec) : undefined;
        if (!r || typeof r.question !== 'string' || typeof r.answer !== 'string') return [];
        return [{ question: r.question, answer: r.answer, createdAt: typeof r.createdAt === 'number' ? r.createdAt : 0, updatedAt: typeof r.updatedAt === 'number' ? r.updatedAt : 0 }];
      });
      msg.aside = { topicId: a.topicId, turnId: a.turnId, index: a.index, question: a.question, history };
    }
    const c = m.control && typeof m.control === 'object' ? (m.control as Rec) : undefined;
    if (c) {
      msg.control = {};
      if (typeof c.model === 'string' && c.model) msg.control.model = c.model;
      if (typeof c.thinking === 'string' && c.thinking) msg.control.thinking = c.thinking;
      if (typeof c.command === 'string' && c.command) msg.control.command = c.command;
      if (c.stop === true) msg.control.stop = true;
      if (typeof c.resume === 'string' && c.resume) msg.control.resume = c.resume;
    }
    out.push(msg);
  }
  return out;
}

/** O motivo de uma falha numa linha só, com tamanho limitado. */
export function reasonText(raw: unknown, fallback: string): string {
  const msg = raw instanceof Error ? raw.message : typeof raw === 'string' ? raw : '';
  const line = msg.replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (!line) return fallback;
  return line.length > MAX_REASON ? `${line.slice(0, MAX_REASON - 1)}…` : line;
}

/** A pergunta paralela na moldura do /btw do omp (sem o "brevemente": o escritório mostra respostas longas). */
export function sideQuestion(question: string): string {
  return `<btw>\nEphemeral side question for current interactive session.\nAnswer directly in text, using the conversation context already provided.\nNEVER use tools.\nNEVER ask follow-up questions.\nQuestion:\n${question}\n</btw>`;
}

/** O prazo do modo de decisão: com UI, a espera do Habblaud; sem UI, o prazo do omp para o handler menos a folga. */
export function decisionTimeoutMs(hasUI: boolean, env: NodeJS.ProcessEnv): number {
  if (hasUI) return DECISION_UI_MS;
  const configured = Number.parseInt(env.HABBLAUD_OMP_TIMEOUT_MS ?? '', 10);
  const total = Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_TOOL_CALL_TIMEOUT_MS;
  return Math.max(MIN_DECISION_MS, total - NO_UI_MARGIN_MS);
}

/** A ferramenta passa pelo modo de decisão (tudo que não é só leitura). */
export function needsDecision(toolName: string): boolean {
  return !READ_ONLY_TOOLS.has(toolName);
}

/** Texto do diálogo do terminal: o que a ferramenta vai fazer, em poucas linhas. */
export function dialogDetail(toolName: string, input: unknown): string {
  const r = input && typeof input === 'object' ? (input as Rec) : undefined;
  const pick = (k: string): string | undefined => (typeof r?.[k] === 'string' && (r[k] as string).trim() ? (r[k] as string) : undefined);
  let text = pick('command') ?? pick('path') ?? pick('file_path') ?? pick('url') ?? pick('code');
  if (text === undefined) {
    try {
      text = JSON.stringify(input) ?? toolName;
    } catch {
      text = toolName;
    }
  }
  return text.length > MAX_DIALOG_DETAIL ? `${text.slice(0, MAX_DIALOG_DETAIL - 1)}…` : text;
}

interface AskQuestion {
  id: string;
  question: string;
  options: Array<{ label: string; description?: string }>;
  header?: string;
  multi: boolean;
}

/** Resposta do escritório a uma pergunta: a posição dela, as posições das opções e o texto livre ("Outro"). */
export interface AskAnswer {
  question: number;
  options?: number[];
  other?: string;
}

export interface AskToolResult {
  content: Array<{ type: 'text'; text: string }>;
  details: unknown;
}

/** As perguntas da chamada do `ask` (os campos que o cartão usa); undefined = fora do formato. */
function askQuestions(params: unknown): AskQuestion[] | undefined {
  const raw = params && typeof params === 'object' ? (params as Rec).questions : undefined;
  if (!Array.isArray(raw) || !raw.length) return undefined;
  const out: AskQuestion[] = [];
  for (const item of raw) {
    const q = item && typeof item === 'object' ? (item as Rec) : undefined;
    if (!q || typeof q.id !== 'string' || typeof q.question !== 'string' || !Array.isArray(q.options)) return undefined;
    const options: AskQuestion['options'] = [];
    for (const o of q.options) {
      const r = o && typeof o === 'object' ? (o as Rec) : undefined;
      if (!r || typeof r.label !== 'string') return undefined;
      options.push({ label: r.label, ...(typeof r.description === 'string' && r.description ? { description: r.description } : {}) });
    }
    out.push({ id: q.id, question: q.question, options, ...(typeof q.header === 'string' && q.header ? { header: q.header } : {}), multi: q.multi === true });
  }
  return out;
}

type AskCard = { questions: Array<{ question: string; header?: string; options: AskQuestion['options']; multiSelect: boolean; noOther?: true }> };

/** A chamada do `ask` no formato do AskUserQuestion do Claude Code (o que o cartão de pergunta do escritório lê). */
export function askToHabblaud(params: unknown): AskCard | undefined {
  const qs = askQuestions(params);
  return qs && { questions: qs.map((q) => ({ question: q.question, ...(q.header ? { header: q.header } : {}), options: q.options, multiSelect: q.multi })) };
}

type FormField = {
  name: string;
  question: string;
  type: 'radio' | 'checkbox' | 'select' | 'text' | 'textarea';
  multi: boolean;
  noOther?: true;
  required: boolean;
  options: Array<{ label: string; value: string }>;
};
type FormNode = { tag: string; attrs: Record<string, string>; children: Array<FormNode | string> };

const FORM_ATTRS: Record<string, readonly string[]> = {
  form: [], fieldset: [], legend: [], p: [], ul: [], ol: [], li: [], label: [],
  br: [], span: [], strong: [], b: [], em: [], i: [], code: [],
  input: ['type', 'name', 'value', 'checked', 'required', 'placeholder', 'maxlength'],
  textarea: ['name', 'required', 'placeholder', 'maxlength', 'rows'],
  select: ['name', 'required', 'multiple'],
  option: ['value', 'selected'],
};
const FORM_HTML_MAX = 32_768;
const FREE_FORM_ATTRS = { name: true, type: true, value: true, id: true, for: true, multiple: true, disabled: true };
const VOID_TAGS = { area: true, base: true, br: true, col: true, embed: true, hr: true, img: true, input: true, link: true, meta: true, param: true, source: true, track: true, wbr: true };
const INVISIBLE_TAGS = { button: true, head: true, iframe: true, link: true, meta: true, noscript: true, script: true, style: true, template: true, title: true };
const CONTROL_TAGS = { input: true, select: true, textarea: true };
const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0' };
const oneline = (text: string) => text.replace(/\s+/g, ' ').trim();

function decodeFormText(text: string, onUnknown?: () => void): string {
  return text.replace(/&(#(?:x[0-9a-f]+|\d+)|[a-z][a-z0-9]+);/gi, (raw, entity: string) => {
    if (entity[0] === '#') {
      const hex = entity[1]?.toLowerCase() === 'x';
      const point = Number.parseInt(entity.slice(hex ? 2 : 1), hex ? 16 : 10);
      if (point > 0 && point <= 0x10ffff && !(point >= 0xd800 && point <= 0xdfff)) return String.fromCodePoint(point);
    } else if (Object.hasOwn(ENTITIES, entity.toLowerCase())) {
      return ENTITIES[entity.toLowerCase()];
    }
    onUnknown?.();
    return raw;
  });
}

/** Leitor inerte e limitado. Em texto livre, ignora atributos sem uso em vez de interpretá-los. */
function readAskForm(html: string, freeText = false): FormNode | undefined {
  if (html.length > FORM_HTML_MAX || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(html)) return undefined;
  const root: FormNode = { tag: '', attrs: {}, children: [] };
  const stack = [root];
  let unknownEntity = false;
  const unknown = freeText ? undefined : () => { unknownEntity = true; };
  const open = /<([a-z][a-z0-9-]*)/iy;
  const close = /<\/([a-z][a-z0-9-]*)[ \t\r\n\f]*>/iy;
  const end = /[ \t\r\n\f]*(\/?)>/y;
  const attr = /[ \t\r\n\f]+([a-z][a-z0-9-]*)(?:[ \t\r\n\f]*=[ \t\r\n\f]*(?:"([^"<]*)"|'([^'<]*)'|([^ \t\r\n\f"'`=<>]+)))?/iy;
  let at = 0;
  while (at < html.length) {
    const parent = stack[stack.length - 1];
    if (html[at] !== '<') {
      const next = html.indexOf('<', at);
      const text = decodeFormText(html.slice(at, next < 0 ? html.length : next), unknown);
      parent.children.push(text);
      at = next < 0 ? html.length : next;
      continue;
    }
    if (freeText && html.startsWith('<!--', at)) {
      const end = html.indexOf('-->', at + 4);
      at = end < 0 ? html.length : end + 3;
      continue;
    }
    close.lastIndex = at;
    const closing = close.exec(html);
    if (closing) {
      if (parent.tag !== closing[1].toLowerCase()) return undefined;
      stack.pop();
      at = close.lastIndex;
      continue;
    }
    open.lastIndex = at;
    const opening = open.exec(html);
    if (!opening) return undefined;
    const tag = opening[1].toLowerCase();
    if (!freeText && !Object.hasOwn(FORM_ATTRS, tag)) return undefined;
    const attrs: FormNode['attrs'] = Object.create(null);
    at = open.lastIndex;
    end.lastIndex = at;
    let tail = end.exec(html);
    while (!tail) {
      attr.lastIndex = at;
      const pair = attr.exec(html);
      if (!pair) return undefined;
      const [, raw, double, single, bare] = pair;
      const name = raw.toLowerCase();
      const value = double ?? single ?? bare;
      if (!freeText) {
        if (bare?.endsWith('/') && html[attr.lastIndex] === '>') return undefined;
        if (!FORM_ATTRS[tag].includes(name) || Object.hasOwn(attrs, name)) return undefined;
        if (value === undefined && !['required', 'checked', 'multiple', 'selected'].includes(name)) return undefined;
      }
      if ((!freeText || Object.hasOwn(FREE_FORM_ATTRS, name)) && !Object.hasOwn(attrs, name)) attrs[name] = decodeFormText(value ?? '', unknown);
      at = end.lastIndex = attr.lastIndex;
      tail = end.exec(html);
    }
    at = end.lastIndex;
    const node = { tag, attrs, children: [] };
    parent.children.push(node);
    if (tag !== 'input' && tag !== 'br' && !(freeText && Object.hasOwn(VOID_TAGS, tag))) {
      if (stack.length >= 16 || (tail[1] && !freeText)) return undefined;
      if (!tail[1]) stack.push(node);
    }
  }
  const nodes = root.children.filter((node) => typeof node !== 'string' || node.trim());
  const form = nodes[0];
  if (freeText) return stack.length === 1 ? (nodes.find((node): node is FormNode => typeof node !== 'string' && node.tag === 'form') ?? root) : undefined;
  return stack.length === 1 && !unknownEntity && nodes.length === 1 && typeof form === 'object' && form.tag === 'form' ? form : undefined;
}

/** Texto inline sem interação. Uma marca de quebra é espaço, como no resumo do omp. */
function formText(nodes: FormNode['children']): string | undefined {
  let text = '';
  for (const node of nodes) {
    if (typeof node === 'string') text += node;
    else if (node.tag === 'br') text += ' ';
    else if (['span', 'strong', 'b', 'em', 'i', 'code'].includes(node.tag)) {
      const inner = formText(node.children);
      if (inner === undefined) return undefined;
      text += inner;
    } else return undefined;
  }
  return text;
}

/** Só projeta sem perdas: contexto visível e respostas aceitas pelo formato nativo. */
export function formToQuestions(html: string): { input: AskCard; map: FormField[] } | undefined {
  const form = readAskForm(html);
  if (!form) return undefined;
  const map: FormField[] = [];
  const questions: AskCard['questions'] = [];
  const context: string[] = [];
  const names = new Set<string>();

  function section(nodes: FormNode['children'], legend = ''): boolean {
    let lastGroup: FormField | undefined;
    let groupHasHeading = false;
    let caption = '';
    for (const node of nodes) {
      if (typeof node === 'string') {
        if (node.trim()) return false;
        continue;
      }
      if (node.tag === 'br') continue;
      if (node.tag === 'fieldset') {
        if (legend) return false;
        const [title, ...body] = node.children.filter((child) => typeof child !== 'string' || child.trim());
        const heading = typeof title === 'object' && title.tag === 'legend' ? formText(title.children) : undefined;
        const before = map.length;
        if (!heading || !oneline(heading) || !section(body, oneline(heading))) return false;
        if (map.length === before) return false;
        caption = '';
        lastGroup = undefined;
        continue;
      }
      if (node.tag === 'p' || node.tag === 'ul' || node.tag === 'ol') {
        if (node.tag === 'p') {
          const text = formText(node.children);
          if (text === undefined) return false;
          caption = oneline(text);
        } else {
          const items = node.children.filter((child) => typeof child !== 'string' || child.trim());
          const parts: string[] = [];
          for (const item of items) {
            if (typeof item === 'string' || item.tag !== 'li') return false;
            const text = formText(item.children);
            if (!text || !oneline(text)) return false;
            parts.push(`${node.tag === 'ul' ? '•' : `${parts.length + 1}.`} ${oneline(text)}`);
          }
          if (!parts.length) return false;
          context.push(parts.join(' '));
          caption = '';
        }
        if (caption) context.push(caption);
        lastGroup = undefined;
        continue;
      }

      let control = node;
      let label = '';
      if (node.tag === 'label') {
        const position = node.children.findIndex((child) => typeof child !== 'string' && ['input', 'textarea', 'select'].includes(child.tag));
        if (position < 0) return false;
        control = node.children[position] as FormNode;
        const text = formText(node.children.filter((_, i) => i !== position));
        if (text === undefined) return false;
        label = oneline(text);
      }
      if (!['input', 'textarea', 'select'].includes(control.tag)) return false;
      const { name, value, placeholder, maxlength } = control.attrs;
      if (!name?.trim() || /[\x00-\x20\x7f]/.test(name) || Object.hasOwn(Object.prototype, name)) return false;
      const rawType = control.tag === 'input' ? (control.attrs.type ?? 'text').toLowerCase() : control.tag;
      const type = (['radio', 'checkbox', 'select', 'text', 'textarea'] as const).find((known) => known === rawType);
      if (!type) return false;
      if ((type === 'textarea' || type === 'select') && control.tag !== type) return false;
      const group = type === 'radio' || type === 'checkbox';
      const select = type === 'select';
      const multi = type === 'checkbox' || (select && Object.hasOwn(control.attrs, 'multiple'));
      const required = Object.hasOwn(control.attrs, 'required');
      if (group) {
        if (!label || label.length > ASK_LABEL_MAX || placeholder !== undefined || maxlength !== undefined) return false;
      } else if (!select && (Object.hasOwn(control.attrs, 'checked') || (maxlength !== undefined && !(/^\d+$/.test(maxlength) && Number.isSafeInteger(Number(maxlength)) && Number(maxlength) >= ANSWER_OTHER_MAX)))) return false;

      const option = { label, value: value ?? 'on' };
      if (group && lastGroup?.name === name && lastGroup.type === type) {
        if (!groupHasHeading || lastGroup.options.length >= ASK_MAX_OPTIONS || lastGroup.options.some((o) => o.label === label)) return false;
        lastGroup.options.push(option);
        lastGroup.required ||= required;
        questions[questions.length - 1].options.push({ label });
        continue;
      }
      if (names.has(name) || map.length >= ASK_MAX_QUESTIONS) return false;
      const options = group ? [option] : [];
      if (select) {
        for (const child of control.children) {
          if (typeof child === 'string' && !child.trim()) continue;
          if (typeof child === 'string' || child.tag !== 'option' || child.children.some((part) => typeof part !== 'string')) return false;
          const text = oneline(child.children.join(''));
          const value = child.attrs.value ?? text;
          const label = text || value;
          if (!label.trim() || label.length > ASK_LABEL_MAX || options.length >= ASK_MAX_OPTIONS || options.some((o) => o.label === label)) return false;
          options.push({ label, value });
        }
        if (!options.length) return false;
      }
      const question = group ? caption || legend || label
        : select ? label || caption || legend || (options.length === 1 ? options[0].label : name)
        : label || oneline(name.replace(/[_-]+/g, ' '));
      if (!question) return false;
      groupHasHeading = !!(caption || legend);
      if (caption && question === caption) context.pop();
      if (legend && question !== legend && !context.includes(legend)) context.unshift(legend);
      if (!group && !select) {
        if (control.children.some((child) => typeof child !== 'string')) return false;
        const preset = type === 'textarea' ? control.children.join('').replace(/^\r?\n/, '') : value;
        if ([placeholder, preset].some((text) => text && (!oneline(text) || /[\r\n]/.test(text)))) return false;
        if (placeholder) context.push(oneline(placeholder));
        if (preset) context.push(oneline(preset));
      }
      names.add(name);
      const displayQuestion = !group && !select && !label && caption && context.at(-1) === caption ? context.pop()! : question;
      const cardQuestion = context.length ? `${context.join(' ')} — ${displayQuestion}` : displayQuestion;
      if (cardQuestion.length > ASK_QUESTION_MAX) return false;
      context.length = 0;
      caption = '';
      const field = { name, question, type, multi, required, options };
      map.push(field);
      questions.push({ question: cardQuestion, multiSelect: multi, options: options.map(({ label }) => ({ label })) });
      lastGroup = group ? field : undefined;
    }
    return true;
  }

  if (!section(form.children) || !map.length) return undefined;
  for (const [index, field] of map.entries()) {
    if (field.type === 'select' || field.options.length === 1 || field.options.some(({ label }) => /^\s*other\b/i.test(label))) {
      field.noOther = questions[index].noOther = true;
    }
  }
  if (context.length) {
    const last = questions[questions.length - 1];
    last.question += ` — ${context.join(' ')}`;
    if (last.question.length > ASK_QUESTION_MAX) return undefined;
  }
  return { input: { questions }, map };
}

type FreeTextField = Pick<FormField, 'name' | 'question' | 'options' | 'multi'>;
type FreeTextForm = { input: AskCard; fields?: FreeTextField[] };

/** Texto sem marcas e sem atributos. Nunca publica um formulário com senha ou arquivo, mesmo se a árvore falhar. */
function freeFormText(html: string): string | undefined {
  const tags = /<!--[\s\S]*?(?:-->|$)|<!\[CDATA\[[\s\S]*?(?:\]\]>|$)|<[!?][^>]*(?:>|$)|<(?:[^>"']|"[^"]*(?:"|$)|'[^']*(?:'|$))*(?:>|$)/g;
  const attribute = /\s*([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/y;
  let at = 0;
  let text = '';
  const hidden: string[] = [];
  for (let token = tags.exec(html); token; token = tags.exec(html)) {
    if (!hidden.length && at < FORM_HTML_MAX) text += html.slice(at, Math.min(token.index, FORM_HTML_MAX)) + ' ';
    const element = /^<(\/?)\s*([a-z][a-z0-9-]*)(?=[\s/>]|$)/i.exec(token[0]);
    const tag = element?.[2].toLowerCase() ?? '';
    if (element?.[1]) {
      const open = hidden.lastIndexOf(tag);
      if (open >= 0) hidden.length = open;
    } else if (element && (tag === 'input' || tag === 'script' || tag === 'style')) {
      const source = token[0];
      let cursor = element[0].length;
      let selfClosing = false;
      while (cursor < source.length) {
        while (/\s/.test(source[cursor] ?? '')) cursor++;
        if (source[cursor] === '/') { selfClosing = true; break; }
        if (source[cursor] === '>' || cursor === source.length) break;
        attribute.lastIndex = cursor;
        const attr = attribute.exec(source);
        if (!attr) break;
        if (tag === 'input' && attr[1].toLowerCase() === 'type') {
          const value = decodeFormText(attr[2] ?? attr[3] ?? attr[4] ?? '').trim();
          if (/^(?:password|file)\/?$/i.test(value) || /^["']/.test(value)) return undefined;
        }
        cursor = attribute.lastIndex;
      }
      if (tag !== 'input' && !selfClosing) {
        const end = new RegExp(`</${tag}\\s*>`, 'gi');
        end.lastIndex = tags.lastIndex;
        tags.lastIndex = end.exec(html) ? end.lastIndex : html.length;
      }
    } else if (Object.hasOwn(INVISIBLE_TAGS, tag) && !Object.hasOwn(VOID_TAGS, tag)) {
      hidden.push(tag);
    }
    at = tags.lastIndex;
  }
  if (!hidden.length && at < FORM_HTML_MAX) text += html.slice(at, FORM_HTML_MAX);
  return maskSecrets(oneline(decodeFormText(text).replace(/[\x00-\x1f\x7f]/g, ' ')));
}

function formLabel(nodes: FormNode['children']): string {
  let label = '';
  for (const node of nodes) {
    if (typeof node === 'string') label += node;
    else if (node.tag === 'br') label += ' ';
    else if (!Object.hasOwn(CONTROL_TAGS, node.tag) && !Object.hasOwn(INVISIBLE_TAGS, node.tag)) label += formLabel(node.children);
  }
  return label;
}

function selectOptions(nodes: FormNode['children'], options: FreeTextField['options']): void {
  for (const node of nodes) {
    if (typeof node === 'string' || Object.hasOwn(INVISIBLE_TAGS, node.tag)) continue;
    if (node.tag !== 'option') {
      selectOptions(node.children, options);
    } else if (!Object.hasOwn(node.attrs, 'disabled')) {
      const text = oneline(formLabel(node.children));
      const value = node.attrs.value ?? text;
      if (text || value.trim()) options.push({ label: text || value, value });
    }
  }
}

/** Cartão de texto para campos que não podem ser validados pelo Habblaud. Não interpreta `pattern` nem roda HTML. */
function formToFreeText(html: string): FreeTextForm | undefined {
  const visible = freeFormText(html);
  if (visible === undefined) return undefined;
  const form = readAskForm(html, true);
  const named = new Map<string, FreeTextField & { type: string; id?: string }>();
  const labels = new Map<string, string>();
  function collect(nodes: FormNode['children'], caption = '', wrapped = ''): boolean {
    for (const node of nodes) {
      if (typeof node === 'string' || Object.hasOwn(INVISIBLE_TAGS, node.tag)) continue;
      const { name, value, id } = node.attrs;
      if (!Object.hasOwn(CONTROL_TAGS, node.tag)) {
        if (node.tag === 'label') {
          const label = oneline(formLabel(node.children));
          if (node.attrs.for && label) labels.set(node.attrs.for, label);
          if (!collect(node.children, caption, label || wrapped)) return false;
        } else {
          if (/^(?:legend|p|h[1-6])$/.test(node.tag)) caption = oneline(formLabel(node.children)) || caption;
          if (!collect(node.children, caption, wrapped)) return false;
        }
        continue;
      }
      const type = node.tag === 'input' ? (node.attrs.type ?? 'text').toLowerCase() : node.tag;
      if (!name?.trim() || /[\x00-\x20\x7f]/.test(name) || Object.hasOwn(Object.prototype, name) || /^(?:hidden|button|submit|reset|image|file|password)$/.test(type)) continue;
      const group = type === 'radio' || type === 'checkbox';
      const option = { label: wrapped || value || 'on', value: value ?? 'on' };
      const previous = named.get(name);
      if (previous) {
        if (!group || previous.type !== type) return false;
        previous.options.push(option);
        continue;
      }
      if (named.size >= ASK_MAX_QUESTIONS) return false;
      const question = (group ? caption || wrapped : wrapped || caption) || oneline(name.replace(/[_-]+/g, ' ')) || name;
      const options = group ? [option] : [];
      if (type === 'select') selectOptions(node.children, options);
      named.set(name, { id, name, type, question, options, multi: type === 'checkbox' || (type === 'select' && Object.hasOwn(node.attrs, 'multiple')) });
      caption = '';
    }
    return true;
  }
  if (form && collect(form.children) && named.size) {
    const fields: FreeTextField[] = [];
    const questions: AskCard['questions'] = [];
    for (const field of named.values()) {
      const question = (field.id && labels.get(field.id)) || field.question;
      const options = field.options.length <= ASK_MAX_OPTIONS ? field.options : [];
      const multiSelect = field.multi && !!options.length;
      fields.push({ name: field.name, question, options, multi: multiSelect });
      questions.push({ question: maskSecrets(question).slice(0, ASK_QUESTION_MAX), multiSelect, options: options.map(({ label }) => ({ label: maskSecrets(label).slice(0, ASK_LABEL_MAX) })) });
    }
    return { input: { questions }, fields };
  }
  const suffix = '… veja o formulário completo no terminal';
  const text = visible.slice(0, ASK_QUESTION_MAX - suffix.length - 1).trimEnd();
  const question = !visible ? suffix : visible.length > ASK_QUESTION_MAX || html.length > FORM_HTML_MAX ? `${text} ${suffix}` : visible;
  return { input: { questions: [{ question, options: [], multiSelect: false }] } };
}

function answersToFreeTextResult(form: FreeTextForm, answers: readonly AskAnswer[]): AskToolResult | undefined {
  if (answers.length !== form.input.questions.length) return undefined;
  const byIndex = new Map(answers.map((answer) => [answer.question, answer]));
  const data: Record<string, string> = Object.create(null);
  const details: Array<{ name: string; question: string; answer: string }> = [];
  for (const [index] of form.input.questions.entries()) {
    const submitted = byIndex.get(index);
    if (!submitted) return undefined;
    const field = form.fields?.[index];
    const other = submitted.other?.trim();
    if (other && (other.length > ANSWER_OTHER_MAX || /[\x00-\x08\x0b-\x1f\x7f]/.test(other))) return undefined;
    const picked = submitted.options ?? [];
    if (new Set(picked).size !== picked.length || picked.some((i) => !field?.options[i])) return undefined;
    const count = picked.length + Number(!!other);
    if (!count || (!field?.multi && count !== 1)) return undefined;
    if (!field) return { content: [{ type: 'text', text: `User answered in free text (Habblaud): ${other}` }], details: { answers: [] } };
    const options = field.options.filter((_, i) => picked.includes(i));
    const values = options.map(({ value }) => value);
    const labels = options.map(({ label }) => label);
    if (other) { values.push(other); labels.push(other); }
    data[field.name] = values.join(', ');
    details.push({ name: field.name, question: field.question, answer: labels.join(', ') });
  }
  return { content: [{ type: 'text', text: `User answers:\n${JSON.stringify(data, null, 2)}\n(Respondido pelo Habblaud em texto livre; o formulário não validou os valores.)` }], details: { answers: details } };
}

/** Mesmo JSON e resumo de `ask` HTML do omp, sem inventar notas nem marcar prazo esgotado. */
export function answersToFormResult(map: readonly FormField[], answers: readonly AskAnswer[]): AskToolResult | undefined {
  const byIndex = new Map(answers.map((a) => [a.question, a]));
  if (byIndex.size !== answers.length || answers.some((a) => !map[a.question])) return undefined;
  const data: Record<string, string | string[]> = Object.create(null);
  const details: Array<{ name: string; question: string; answer: string }> = [];
  for (const [index, field] of map.entries()) {
    const submitted = byIndex.get(index);
    const other = submitted?.other?.trim();
    if (field.noOther && submitted?.other !== undefined) return undefined;
    if (submitted?.other !== undefined && (!other || other.length > ANSWER_OTHER_MAX)) return undefined;
    if (other && (field.type === 'textarea' ? /[\x00-\x08\x0b-\x1f\x7f]/ : /[\x00-\x1f\x7f]/).test(other)) return undefined;
    const picked = submitted?.options ?? [];
    if (new Set(picked).size !== picked.length || picked.some((i) => !field.options[i])) return undefined;
    const values = field.options.flatMap(({ value }, i) => picked.includes(i) ? [value] : []);
    if (other) values.push(other);
    const choice = field.options.length > 0;
    // O omp só serializa um vetor quando o controle múltiplo tem mais de uma opção.
    const array = field.multi && field.options.length > 1;
    if ((!array && values.length > 1) || ((field.required || field.noOther) && !values.length)) return undefined;
    if (!choice && !other) return undefined;
    if (array) data[field.name] = values;
    else if (values.length) data[field.name] = values[0];
    const answer = choice ? values.map((v) => field.options.find((o) => o.value === v)?.label ?? `Other: ${v}`).join(', ') : other!;
    details.push({ name: field.name, question: field.question, answer });
  }
  return { content: [{ type: 'text', text: `User answers:\n${JSON.stringify(data, null, 2)}` }], details: { answers: details } };
}

/** Texto com mais de uma linha recuado embaixo do rótulo, como no `ask` do omp. */
const indented = (label: string, text: string): string => (text.includes('\n') ? `${label}:\n${text.split('\n').map((l) => `  ${l}`).join('\n')}` : `${label}: ${text}`);

/**
 * Resposta do escritório no mesmo resultado do `ask` do omp (texto e `details`), para o modelo não ver diferença
 * entre responder lá ou aqui. undefined = a chamada não está no formato esperado.
 */
export function askResult(params: unknown, answers: readonly AskAnswer[]): AskToolResult | undefined {
  const qs = askQuestions(params);
  if (!qs) return undefined;
  const results = qs.map((q, i) => {
    const a = answers.find((x) => x.question === i);
    const selectedOptions = (a?.options ?? []).flatMap((k) => (q.options[k] ? [q.options[k].label] : []));
    return { id: q.id, question: q.question, options: q.options.map((o) => o.label), multi: q.multi, selectedOptions, ...(a?.other ? { customInput: a.other } : {}) };
  });
  if (results.length === 1) {
    const { id: _id, ...r } = results[0];
    const lines: string[] = [];
    if (r.selectedOptions.length) lines.push(`User selected: ${r.multi ? r.selectedOptions.join(', ') : r.selectedOptions[0]}`);
    if (r.customInput !== undefined) lines.push(indented('User provided custom input', r.customInput));
    const text = lines.length ? lines.join('\n') : r.multi ? 'User did not select any options' : 'User cancelled the selection';
    return { content: [{ type: 'text', text }], details: r };
  }
  const line = (r: (typeof results)[number]): string => {
    const custom = r.customInput === undefined ? undefined : `"${r.customInput}"`;
    if (!r.selectedOptions.length) return `${r.id}: ${custom ?? (r.multi ? '[]' : '(cancelled)')}`;
    const picked = r.multi ? `[${r.selectedOptions.join(', ')}]` : r.selectedOptions[0];
    return `${r.id}: ${custom === undefined ? picked : `${picked} + ${custom}`}`;
  };
  return { content: [{ type: 'text', text: ['User answers:', ...results.map(line)].join('\n') }], details: { results } };
}

/** As respostas de uma decisão `answer` vinda do Habblaud (já conferidas lá); undefined = fora do formato. */
function parseAnswers(raw: unknown): AskAnswer[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const out: AskAnswer[] = [];
  for (const item of raw) {
    const a = item && typeof item === 'object' ? (item as Rec) : undefined;
    if (!a || typeof a.question !== 'number') return undefined;
    const options = Array.isArray(a.options) ? a.options.filter((k): k is number => typeof k === 'number') : undefined;
    out.push({ question: a.question, ...(options?.length ? { options } : {}), ...(typeof a.other === 'string' && a.other ? { other: a.other } : {}) });
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// A extensão
// ---------------------------------------------------------------------------------------------

type Outcome =
  | { kind: 'habblaud'; allowed: boolean; message?: string }
  /** Respostas a uma pergunta (`ask`). */
  | { kind: 'answer'; answers: AskAnswer[] }
  | { kind: 'terminal'; allowed: boolean }
  | { kind: 'timeout' }
  /** O Habblaud largou o pedido (página fechada, pedido desconhecido). */
  | { kind: 'gone' };

interface Reply {
  status: number;
  text: string;
}

/** Promessa que nunca se cumpre: tira um lado de uma corrida (Promise.race). */
const never = <T>(): Promise<T> => Promise.withResolvers<T>().promise;

/** Espera `ms`, ou até o sinal: devolve false se foi cancelada. */
function sleep(ms: number, signal?: AbortSignal): Promise<boolean> {
  const { promise, resolve } = Promise.withResolvers<boolean>();
  if (signal?.aborted) {
    resolve(false);
    return promise;
  }
  const done = (v: boolean) => {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
    resolve(v);
  };
  const onAbort = () => done(false);
  const timer = setTimeout(() => done(true), ms);
  signal?.addEventListener('abort', onAbort, { once: true });
  return promise;
}

/** Enter na tela do omp: o TUI lê as teclas do stdin do processo, então um "\r" ali vale como apertar Enter. */
function pressEnter(): void {
  process.stdin.emit('data', Buffer.from('\r'));
}

function defaultDeps(): Deps {
  return { env: process.env, home: homedir(), fetch: globalThis.fetch.bind(globalThis), now: Date.now, shared: SHARED, pressEnter };
}

/** Monta a extensão sobre a API `pi` do omp. Exportada para os testes (o omp usa o `export default`). */
export function create(pi: OmpApi, deps: Deps): void {
  const { env, home } = deps;
  const decisionMode = env.HABBLAUD_OMP_PERMISSOES === '1';

  let sessionId: string | undefined;
  let busy = false;
  /** O contexto da sessão principal (para perguntar se o agente está livre na hora de entregar). */
  let mainCtx: OmpContext | undefined;
  /** A pergunta paralela em andamento (uma por vez): a sessão trocar ou fechar a aborta. */
  let aside: { turnId: string; abort: AbortController } | undefined;
  let closed = false;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  let inboxTimer: ReturnType<typeof setTimeout> | undefined;
  let inboxStarted = false;
  const warned = new Set<string>();

  // ---- rede ----------------------------------------------------------------------------------

  async function request(method: 'GET' | 'POST', path: string, body?: unknown, signal?: AbortSignal, timeoutMs = FETCH_TIMEOUT_MS): Promise<Reply | undefined> {
    try {
      const timeout = AbortSignal.timeout(timeoutMs);
      const res = await deps.fetch(`http://127.0.0.1:${portOf(env, home)}${path}`, {
        method,
        headers: { accept: 'application/json', ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      });
      return { status: res.status, text: await res.text() };
    } catch {
      return undefined;
    }
  }

  const ok = (r: Reply | undefined): r is Reply => !!r && r.status >= 200 && r.status < 300;

  // ---- eventos (em fila: um de cada vez, na ordem) --------------------------------------------

  let queue: Promise<unknown> = Promise.resolve();
  let queued = 0;

  function emit(event: OmpEventKind, id: string, extra: Partial<OmpEvent> = {}, critical = false): Promise<unknown> {
    if (queued >= MAX_QUEUE && !critical) return Promise.resolve();
    const body: OmpEvent = { event, sessionId: id, at: deps.now(), ...extra };
    queued++;
    const sent = queue.then(() => request('POST', '/api/omp/events', body)).finally(() => queued--);
    queue = sent;
    return sent;
  }

  /** Modelos da sessão atual: lidos no session_start e repetidos no batimento (o Habblaud pode ter reiniciado). */
  let models: string[] | undefined;

  /** Arquivo e projeto da sessão: vão no session_start e em todo batimento (um Habblaud reiniciado não conhece a
   * sessão, e sem arquivo gravado, antes da 1ª mensagem, só o cwd do evento a coloca no escritório). */
  function location(ctx: OmpContext | undefined): Partial<OmpEvent> {
    const out: Partial<OmpEvent> = {};
    try {
      const file = ctx?.sessionManager.getSessionFile?.();
      if (file) out.sessionFile = file;
      const cwd = ctx?.sessionManager.getCwd?.() ?? ctx?.cwd;
      if (cwd) out.cwd = cwd;
    } catch {
      // contexto já descartado: só o id
    }
    return out;
  }

  function sessionFields(ctx: OmpContext): Partial<OmpEvent> {
    const out = location(ctx);
    try {
      const cur = ctx.models?.current?.();
      const curRef = cur ? `${cur.provider}/${cur.id}` : '';
      const all = ctx.models?.list().map((m) => `${m.provider}/${m.id}`).filter((m) => m === curRef || !(/:(?:batch|free|extended)$/.test(m) || m.startsWith('openrouter/~')));
      models = all && allowedModels(all, env);
    } catch {
      models = undefined; // sem lista de modelos: o seletor da gaveta usa só o atual
    }
    if (models?.length) out.models = models;
    if (env.HABBLAUD_CAST && /^[a-z0-9-]{1,40}$/.test(env.HABBLAUD_CAST)) out.cast = env.HABBLAUD_CAST;
    return out;
  }

  /** O id da sessão do contexto; sessão nova (início ou troca dentro do processo) = fecha a anterior e abre a nova. */
  function track(ctx: OmpContext): string | undefined {
    let id: string | undefined;
    try {
      id = ctx.sessionManager.getSessionId();
    } catch {
      return sessionId;
    }
    if (!id) return sessionId;
    if (id === sessionId) mainCtx = ctx;
    if (id !== sessionId) {
      if (sessionId) void emit('session_shutdown', sessionId);
      sessionId = id;
      mainCtx = ctx;
      deps.shared.mainSessionId = id;
      busy = false;
      aside?.abort.abort();
      void emit('session_start', id, sessionFields(ctx));
      startLoops();
    }
    return id;
  }

  function startLoops(): void {
    if (closed) return;
    if (!heartbeat) {
      heartbeat = setInterval(() => {
        if (sessionId) void emit('heartbeat', sessionId, { ...location(mainCtx), ...(models?.length ? { models } : {}), ...(env.HABBLAUD_CAST && /^[a-z0-9-]{1,40}$/.test(env.HABBLAUD_CAST) ? { cast: env.HABBLAUD_CAST } : {}), ...contextNow() });
      }, OMP_HEARTBEAT_MS);
      heartbeat.unref?.();
    }
    if (!inboxStarted) {
      inboxStarted = true;
      scheduleInbox(0);
    }
  }

  const isSub = (ctx: OmpContext): boolean => ctx.agent?.kind === 'sub';

  /** Registra um handler de observação: nunca lança, e subagentes ficam de fora. */
  function observe(name: string, run: (event: Rec, ctx: OmpContext, id: string) => void): void {
    pi.on(name, (event, ctx) => {
      try {
        if (isSub(ctx)) return;
        const id = track(ctx);
        if (id) run(event, ctx, id);
      } catch {
        // nenhum erro chega ao omp
      }
    });
  }

  const str = (v: unknown): string | undefined => (typeof v === 'string' && v ? v : undefined);

  // `pi.on` aceita qualquer nome sem erro (nome errado nunca dispara): cada nome abaixo foi conferido em
  // extensibility/extensions/types.ts do omp e provado contra um omp real (tarefa 5.1).
  observe('session_start', () => {
    // track() já anunciou a sessão.
    shadowAsk();
  });
  // /new, /resume e /fork trocam de sessão no mesmo processo sem session_start: o ctx do session_switch já é o da
  // sessão nova, e track() fecha a anterior e anuncia a nova (sem isso, a nova só aparecia no próximo prompt).
  observe('session_switch', () => undefined);
  observe('agent_start', (_e, _c, id) => {
    busy = true;
    void emit('agent_start', id);
  });
  observe('agent_end', (_e, _c, id) => {
    busy = false;
    void emit('agent_end', id, contextNow());
  });
  observe('tool_execution_start', (e, _c, id) => {
    void emit('tool_start', id, { toolName: str(e.toolName), toolCallId: str(e.toolCallId) });
  });
  observe('tool_execution_end', (e, _c, id) => {
    void emit('tool_end', id, { toolName: str(e.toolName), toolCallId: str(e.toolCallId) });
  });
  observe('tool_approval_requested', (e, ctx, id) => {
    void emit('approval_requested', id, { toolName: str(e.toolName), toolCallId: str(e.toolCallId) });
    // Modo de decisão com o omp perguntando também: duas perguntas por ferramenta (aviso único por sessão).
    if (decisionMode && e.approvalMode !== 'yolo' && !warned.has(id)) {
      warned.add(id);
      try {
        ctx.ui.notify('Habblaud: o modo de decisão (HABBLAUD_OMP_PERMISSOES=1) funciona com o omp em --approval-mode yolo; neste modo o omp também vai perguntar, e você verá duas perguntas por ferramenta.', 'warning');
      } catch {
        // sem UI para o aviso
      }
    }
  });
  observe('tool_approval_resolved', (e, _c, id) => {
    void emit('approval_resolved', id, { toolName: str(e.toolName), toolCallId: str(e.toolCallId), ...(typeof e.approved === 'boolean' ? { approved: e.approved } : {}) });
  });

  pi.on('session_shutdown', async (_event, ctx) => {
    try {
      if (isSub(ctx)) return;
      const id = sessionId ?? track(ctx);
      closed = true;
      aside?.abort.abort();
      clearInterval(heartbeat);
      clearTimeout(inboxTimer);
      heartbeat = inboxTimer = undefined;
      if (id) {
        sessionId = undefined;
        await emit('session_shutdown', id, {}, true);
      }
    } catch {
      // nenhum erro chega ao omp
    }
  });

  // ---- mensagens -----------------------------------------------------------------------------

  function scheduleInbox(ms: number): void {
    if (closed) return;
    inboxTimer = setTimeout(() => void inboxRound(), ms);
    inboxTimer.unref?.();
  }

  /** O agente está livre agora? Pergunta ao omp; sem resposta (contexto descartado), vale o que os eventos disseram. */
  function idleNow(): boolean {
    try {
      const idle = mainCtx?.isIdle?.();
      if (typeof idle === 'boolean') return idle;
    } catch {
      // contexto descartado
    }
    return !busy;
  }

  /** A conversa trocou entre a busca e a entrega: cada mensagem vale só para a sessão em que foi mandada. */
  const SESSION_CHANGED = 'a conversa mudou (/new ou /resume) antes da entrega';

  /**
   * Entrega cada mensagem, na ordem. Nunca lança. A primeira, com o agente livre, inicia um turno; o que vier depois
   * (da mesma rodada ou com o agente ocupado) usa o modo pedido. Item de uma sessão que
   * já não é a atual vira falha, sem entrega.
   */
  async function deliver(session: string, messages: readonly InboxMessage[]): Promise<AckResult[]> {
    let idle = idleNow();
    const results: AckResult[] = [];
    for (const m of messages) results.push(sessionId === session ? await deliverOne(m) : { id: m.id, ok: false, error: SESSION_CHANGED });
    return results;

    async function deliverOne(m: InboxMessage): Promise<AckResult> {
      if (m.aside) return startAside(m.id, m.aside);
      if (m.control) return applyControl(m.id, m.control);
      if (!m.text.trim()) return { id: m.id, ok: false, error: 'mensagem vazia' };
      const mode = m.deliverAs === undefined ? 'followUp' : m.deliverAs;
      if (mode !== 'aside' && mode !== 'followUp' && mode !== 'steer') return { id: m.id, ok: false, error: 'modo de entrega inválido' };
      try {
        if (idle) {
          pi.sendUserMessage(m.text);
          idle = false;
        } else pi.sendUserMessage(m.text, { deliverAs: mode });
        if (mode === 'steer') interruptTextHold(session);
        return { id: m.id, ok: true };
      } catch (err) {
        return { id: m.id, ok: false, error: reasonText(err, 'falha ao entregar a mensagem') };
      }
    }
  }

  // ---- pergunta paralela (bate-papo da janela lateral) ---------------------------------------

  /**
   * Inicia a pergunta paralela e confirma NA HORA: esperar a resposta aqui travaria a caixa de entrada pelo tempo todo
   * (a rodada espera cada entrega). A resposta sai por POST /api/omp/aside, em runAside.
   */
  function startAside(id: string, req: AsideRequest): AckResult {
    const ctx = mainCtx;
    if (!ctx?.runEphemeralTurn) return { id, ok: false, error: 'este omp não faz perguntas paralelas pela extensão (atualize o omp)' };
    if (aside) return { id, ok: false, error: 'já há uma pergunta paralela em andamento' };
    const controller = new AbortController();
    aside = { turnId: req.turnId, abort: controller };
    void runAside(ctx, req, controller);
    return { id, ok: true };
  }

  /**
   * Roda a pergunta com ctx.runEphemeralTurn (a maquinaria do /btw do omp: o modelo e a conversa de agora, fora do
   * histórico, com o turno principal rodando) e manda o texto acumulado ao Habblaud. Nunca lança: uma exceção solta numa
   * extensão derruba a sessão inteira do omp.
   */
  async function runAside(ctx: OmpContext, req: AsideRequest, controller: AbortController): Promise<void> {
    let pump: ReturnType<typeof setInterval> | undefined;
    try {
      const session = sessionId;
      if (!session || !ctx.runEphemeralTurn) return;
      let text = '';
      let sentLen = -1;
      let lastSent = 0;
      const post = async (state: AsideProgress['state'], error?: string): Promise<void> => {
        const r = await request('POST', '/api/omp/aside', { sessionId: session, turnId: req.turnId, text: clipAnswer(text), state, ...(error ? { error } : {}) });
        if (!ok(r)) return;
        try {
          if ((JSON.parse(r.text) as Rec | null)?.cancel === true) controller.abort();
        } catch {
          // resposta que não é JSON: sem cancelamento
        }
      };
      pump = setInterval(() => {
        try {
          if (text.length !== sentLen || deps.now() - lastSent >= ASIDE_HEARTBEAT_MS) {
            sentLen = text.length;
            lastSent = deps.now();
            void post('running').catch(() => {});
          }
        } catch {
          // nunca chega ao omp
        }
      }, ASIDE_PROGRESS_MS);
      pump.unref?.();

      // Perguntas anteriores da conversa, no formato do /btw do omp; sem o modelo (ou sem `api`), vão como texto na pergunta.
      const model = ctx.models?.current?.();
      const past = req.history;
      let history: unknown[] | undefined;
      let promptText = req.question;
      if (past.length && model?.api) {
        const zero = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };
        history = past.flatMap((h) => [
          { role: 'user', content: [{ type: 'text', text: h.question }], attribution: 'agent', timestamp: h.createdAt },
          ...(h.answer ? [{ role: 'assistant', content: [{ type: 'text', text: h.answer }], api: model.api, provider: model.provider, model: model.id, usage: zero, stopReason: 'stop', timestamp: h.updatedAt }] : []),
        ]);
      } else if (past.length) {
        promptText = `${past.map((h) => `Pergunta anterior: ${h.question}${h.answer ? `\nResposta: ${h.answer}` : ''}`).join('\n\n')}\n\nPergunta: ${req.question}`;
      }

      try {
        // Mesma moldura do /btw do omp: o turno efêmero leva as ferramentas da sessão e, sem ela, o modelo às vezes só
        // chama uma ferramenta; o omp descarta a chamada e devolve replyText vazio (resposta em branco no escritório).
        // Vazia mesmo assim: tenta mais uma vez antes de desistir com um motivo.
        for (let attempt = 1; ; attempt++) {
          text = '';
          const { replyText } = await ctx.runEphemeralTurn({
            promptText: sideQuestion(promptText),
            history,
            conversationKey: `hb:${req.topicId}:${req.index}`,
            dedupeReply: false,
            onTextDelta: (d) => {
              if (text.length < ASIDE_ANSWER_MAX) text += d;
            },
            signal: controller.signal,
          });
          text = replyText || text;
          if (text.trim()) break;
          if (attempt >= 2) {
            await post('failed', 'o modelo respondeu sem texto (tentou usar uma ferramenta); pergunte de novo');
            return;
          }
        }
        await post('complete');
      } catch (err) {
        if (controller.signal.aborted) await post('cancelled');
        else await post('failed', reasonText(err, 'falha na pergunta paralela'));
      }
    } catch {
      // nenhum erro chega ao omp
    } finally {
      if (aside?.abort === controller) aside = undefined;
      clearInterval(pump);
    }
  }

  /** Contexto da sessão principal para o evento (`{}` sem leitura: omp antigo ou contexto descartado). */
  function contextNow(): Pick<OmpEvent, 'context'> {
    try {
      const u = mainCtx?.getContextUsage?.();
      const tokens = u?.tokens;
      const window = u?.contextWindow;
      if (typeof tokens === 'number' && typeof window === 'number' && tokens >= 0 && window > 0) return { context: { tokens: Math.round(tokens), window: Math.round(window) } };
    } catch {
      // contexto descartado
    }
    return {};
  }

  /**
   * Roda comandos do omp (/new, /shake, /resume) como se fossem digitados: põe cada texto no editor e aperta Enter, em
   * ordem. O omp não dá às extensões outro jeito de rodar comandos internos. Só com o agente livre, a tela do omp
   * aberta e o editor vazio (para não apagar um rascunho nem responder um diálogo aberto). Entre um passo e outro
   * espera o omp aceitar o anterior (editor vazio) e mais COMMAND_GAP_MS, para os dois não rodarem juntos.
   */
  async function typeCommands(id: string, steps: readonly string[]): Promise<AckResult> {
    const ctx = mainCtx;
    const ui = ctx?.ui;
    const enter = deps.pressEnter;
    if (!ctx || ctx.mode !== 'tui' || !ui?.setEditorText || !ui.getEditorText || !enter) return { id, ok: false, error: 'só funciona com a tela do omp aberta no terminal' };
    if (!idleNow()) return { id, ok: false, error: 'o agente está trabalhando: espere o turno acabar' };
    if (ui.getEditorText().trim()) return { id, ok: false, error: 'há texto no editor do omp: envie ou apague antes' };
    const session = sessionId;
    for (const [i, text] of steps.entries()) {
      if (i > 0) {
        for (let waited = 0; ui.getEditorText().trim() && waited < COMMAND_ACCEPT_MS; waited += 50) await sleep(50);
        if (ui.getEditorText().trim()) return { id, ok: false, error: `o omp não aceitou ${steps[i - 1]}` };
        await sleep(COMMAND_GAP_MS);
        if (sessionId !== session) return { id, ok: false, error: `a conversa mudou depois de ${steps[i - 1]}` };
      }
      ui.setEditorText(text);
      enter();
    }
    return { id, ok: true };
  }

  /** Interrompe o turno em andamento, como o Esc na tela do omp. Com o agente livre não há o que parar. */
  function stopTurn(id: string): AckResult {
    if (!mainCtx?.abort) return { id, ok: false, error: 'este omp não interrompe o turno pela extensão' };
    if (idleNow()) return { id, ok: false, error: 'o agente já está parado' };
    mainCtx.abort();
    return { id, ok: true };
  }

  /** Troca o modelo e/ou o raciocínio, roda um comando, retoma outra sessão ou interrompe o turno. O arquivo da sessão ganha model_change/thinking_level_change, que o Habblaud já lê. */
  async function applyControl(id: string, control: NonNullable<InboxMessage['control']>): Promise<AckResult> {
    try {
      if (control.stop) return stopTurn(id);
      if (control.resume) return OMP_SESSION_ID.test(control.resume) ? typeCommands(id, [`/resume ${control.resume}`]) : { id, ok: false, error: 'id de sessão inválido' };
      if (control.command) return Object.hasOwn(OMP_COMMANDS, control.command) ? typeCommands(id, OMP_COMMANDS[control.command as OmpCommand]) : { id, ok: false, error: 'comando desconhecido' };
      if (control.model) {
        const target = mainCtx?.models?.resolve?.(control.model);
        if (!target) return { id, ok: false, error: 'modelo desconhecido' };
        const ref = typeof target === 'object' && 'provider' in target && 'id' in target ? `${String(target.provider)}/${String(target.id)}` : '';
        if (!allowedModels([ref], env).length) return { id, ok: false, error: 'modelo não liberado' };
        if (!pi.setModel) return { id, ok: false, error: 'este omp não troca de modelo pela extensão' };
        if ((await pi.setModel(target)) === false) return { id, ok: false, error: 'o omp recusou o modelo' };
      }
      if (control.thinking) {
        if (!pi.setThinkingLevel) return { id, ok: false, error: 'este omp não troca o raciocínio pela extensão' };
        pi.setThinkingLevel(control.thinking);
      }
      return { id, ok: true };
    } catch (err) {
      return { id, ok: false, error: reasonText(err, 'falha ao aplicar a troca') };
    }
  }

  /**
   * Confirma o lote. Repete só quando o pedido não chegou ou o servidor falhou (rede, prazo, 5xx): um 4xx é recusa
   * definitiva e repetir não muda nada. O servidor ignora ack repetido, então repetir é seguro; sem a repetição, um
   * ack perdido faz uma mensagem entregue aparecer como "não confirmada" e o usuário a reenvia.
   */
  async function sendAck(session: string, results: readonly AckResult[]): Promise<void> {
    for (let attempt = 0; ; attempt++) {
      const r = await request('POST', '/api/mod/inbox/ack', { session, results });
      if (r && r.status < 500) return;
      const wait = ACK_RETRY_MS[attempt];
      if (wait === undefined || closed) return;
      await sleep(wait);
    }
  }

  /** Uma rodada: pergunta, entrega, confirma. Uma por vez (o próximo agendamento só sai no fim). */
  async function inboxRound(): Promise<void> {
    let next = busy ? INBOX_BUSY_MS : INBOX_IDLE_MS;
    try {
      const session = sessionId;
      if (!session) {
        next = INBOX_IDLE_MS;
      } else {
        const res = await request('POST', '/api/mod/inbox', { session });
        const messages = ok(res) ? parseInbox(res.text) : undefined;
        if (!messages) next = INBOX_OFFLINE_MS;
        else if (messages.length) {
          const results = await deliver(session, messages);
          await sendAck(session, results);
        }
      }
    } catch {
      next = INBOX_OFFLINE_MS;
    }
    scheduleInbox(next);
  }

  // ---- permissões (modo de decisão) ------------------------------------------------------------

  async function register(sid: string, toolName: string, input: unknown, cwd: string, timeoutMs: number): Promise<{ id?: string; skip?: string } | undefined> {
    const res = await request('POST', '/api/permissions', { provider: 'omp', session_id: sid, tool_name: toolName, tool_input: input ?? {}, cwd, timeout_ms: timeoutMs });
    if (!ok(res)) return undefined;
    try {
      const j = JSON.parse(res.text) as Rec;
      if (res.status === 201 && typeof j.id === 'string') return { id: j.id };
      if (typeof j.skip === 'string') return { skip: j.skip };
    } catch {
      // resposta fora do formato = fora do ar
    }
    return undefined;
  }

  /** Long-poll até o Habblaud decidir, largar o pedido ou o sinal cancelar. */
  async function poll(id: string, signal: AbortSignal): Promise<Outcome | undefined> {
    while (!signal.aborted) {
      const res = await request('GET', `/api/permissions/${encodeURIComponent(id)}/wait?timeout=${POLL_SECONDS}`, undefined, signal, (POLL_SECONDS + 5) * 1_000);
      if (signal.aborted) return undefined;
      if (!res) {
        await sleep(1_000, signal);
        continue;
      }
      if (res.status === 404) return { kind: 'gone' };
      if (!ok(res)) {
        await sleep(1_000, signal);
        continue;
      }
      let j: Rec;
      try {
        j = JSON.parse(res.text) as Rec;
      } catch {
        await sleep(1_000, signal);
        continue;
      }
      if (j.status === 'decided') {
        if (j.behavior === 'allow') return { kind: 'habblaud', allowed: true };
        if (j.behavior === 'deny') return { kind: 'habblaud', allowed: false, message: str(j.message) };
        const answers = j.behavior === 'answer' ? parseAnswers(j.answers) : undefined;
        if (answers) return { kind: 'answer', answers };
        return { kind: 'gone' };
      }
      if (j.status === 'released') return j.reason === 'expired' ? { kind: 'timeout' } : { kind: 'gone' };
    }
    return undefined;
  }

  /** Retira o pedido do escritório (decidido no terminal): o servidor o solta como `released/terminal`. */
  function withdraw(id: string): Promise<unknown> {
    return request('POST', `/api/permissions/${encodeURIComponent(id)}/decision`, { behavior: 'terminal' });
  }

  const block = (reason: string): ToolCallResult => ({ block: true, reason });

  async function decide(toolName: string, input: unknown, ctx: OmpContext): Promise<ToolCallResult | undefined> {
    let sid: string | undefined;
    try {
      sid = isSub(ctx) ? deps.shared.mainSessionId : track(ctx);
    } catch {
      sid = undefined;
    }
    const hasUI = ctx.hasUI === true;
    const timeoutMs = decisionTimeoutMs(hasUI, env);
    let cwd = '';
    try {
      cwd = ctx.cwd ?? ctx.sessionManager.getCwd?.() ?? '';
    } catch {
      // contexto descartado
    }

    const reg = sid ? await register(sid, toolName, input, cwd, timeoutMs) : undefined;
    const requestId = reg?.id;
    if (!requestId && !hasUI) {
      return block(reg?.skip ? `Habblaud não pôde decidir esta ferramenta (${reg.skip})` : 'Habblaud fora do ar');
    }

    const abort = new AbortController();
    const entries: Array<Promise<Outcome>> = [];

    // O prazo: com o diálogo aberto o omp não conta o tempo do handler, então a espera vai até timeoutMs.
    entries.push(sleep(timeoutMs, abort.signal).then((elapsed): Outcome | Promise<Outcome> => (elapsed ? { kind: 'timeout' } : never<Outcome>())));
    if (hasUI) {
      const detail = dialogDetail(toolName, input);
      entries.push(
        Promise.resolve()
          .then(() => ctx.ui.confirm(`Habblaud: permitir ${toolName}?`, detail, { signal: abort.signal }))
          .then((allowed): Outcome | Promise<Outcome> => (abort.signal.aborted ? never<Outcome>() : { kind: 'terminal', allowed: allowed === true }))
          .catch(() => never<Outcome>()),
      );
    }
    if (requestId) {
      entries.push(
        poll(requestId, abort.signal).then((o): Outcome | Promise<Outcome> => {
          if (!o) return never<Outcome>();
          // Com o diálogo aberto, um pedido largado pelo Habblaud deixa a decisão com o terminal.
          return o.kind === 'gone' && hasUI ? never<Outcome>() : o;
        }),
      );
    }

    const outcome = await Promise.race(entries);
    abort.abort();
    if (outcome.kind === 'terminal' && requestId) await withdraw(requestId);

    switch (outcome.kind) {
      case 'habblaud':
        return outcome.allowed ? undefined : block(outcome.message ?? 'recusado no Habblaud');
      case 'terminal':
        return outcome.allowed ? undefined : block('recusado no terminal');
      case 'timeout':
        return block('sem resposta a tempo');
      case 'gone':
      case 'answer':
        return block('pedido encerrado no Habblaud');
    }
  }

  if (decisionMode) {
    pi.on('tool_call', async (event, ctx) => {
      try {
        const toolName = str(event.toolName);
        if (!toolName || !needsDecision(toolName)) return undefined;
        return await decide(toolName, event.input, ctx);
      } catch (err) {
        // Falha inesperada do modo de decisão: recusa (o modo é opt-in: na dúvida, não executa).
        return block(`erro no Habblaud: ${reasonText(err, 'falha inesperada')}`);
      }
    });
  }

  // ---- perguntas (ask) pelo escritório --------------------------------------------------------

  // O omp não oferece unsubscribe em pi.on; só as esperas ativas têm uma inscrição neste despachante.
  const textHolds = new Map<string, { input: EventTarget; count: number }>();
  function interruptTextHold(id: string): void {
    try {
      textHolds.get(id)?.input.dispatchEvent(new Event('input'));
    } catch {
      // A observação não muda o envio nem o reconhecimento da mensagem.
    }
  }
  pi.on('input', (event, ctx) => {
    try {
      if (!textHolds.size || (event.source !== 'interactive' && event.source !== 'rpc')) return;
      interruptTextHold(ctx.sessionManager.getSessionId());
    } catch {
      // Uma falha de observação não pode impedir a entrega da mensagem.
    }
  });

  /**
   * A pergunta nos dois lugares ao mesmo tempo: o diálogo nativo no terminal e o cartão no escritório. A primeira
   * resposta vale e fecha a outra. Sem cartão (subagente, Habblaud fora do ar, ninguém olhando ou controle
   * sensível), só o nativo.
   */
  async function ask(params: unknown, signal: AbortSignal | undefined, ctx: ToolContext): Promise<unknown> {
    const native = ctx.invokeTool;
    if (!native) throw new Error('Habblaud: o ask do omp não está disponível');
    let input: AskCard | undefined;
    let formMap: FormField[] | undefined;
    let freeForm: FreeTextForm | undefined;
    let sid: string | undefined;
    let cwd = '';
    try {
      if (!isSub(ctx)) {
        const html = typeof params === 'string' ? params : params && typeof params === 'object' ? (params as Rec).html : undefined;
        if (typeof html === 'string') {
          const form = formToQuestions(html);
          if (!form) freeForm = formToFreeText(html);
          input = form?.input ?? freeForm?.input;
          formMap = form?.map;
        } else {
          input = askToHabblaud(params);
        }
      }
      sid = input ? track(ctx) : undefined;
      cwd = ctx.cwd ?? ctx.sessionManager.getCwd?.() ?? '';
    } catch {
      sid = undefined;
    }
    if (!sid || !input) return native(params, signal ? { signal } : undefined);
    const id = (await register(sid, ASK_TOOL, input, cwd, ASK_WAIT_MS))?.id;
    if (!id) return native(params, signal ? { signal } : undefined);

    const stop = new AbortController();
    const onAbort = () => stop.abort();
    signal?.addEventListener('abort', onAbort, { once: true });
    const singleFreeText = !!freeForm && !freeForm.fields;
    if (singleFreeText && signal?.aborted) onAbort();
    try {
      const local = native(params, { signal: stop.signal }).then(
        (value) => ({ kind: 'local' as const, value }),
        (error: unknown) => ({ kind: 'local-error' as const, error }),
      );
      // Do escritório só valem a resposta e o "Não responder"; o resto (pedido largado, prazo) deixa o terminal.
      const polled = poll(id, stop.signal);
      function remoteResult(outcome: Outcome | undefined) {
        if (outcome?.kind === 'habblaud' && !outcome.allowed) return outcome;
        if (outcome?.kind === 'answer') {
          if (!formMap && !freeForm) return outcome;
          try {
            const result = freeForm ? answersToFreeTextResult(freeForm, outcome.answers)
              : formMap?.length === outcome.answers.length ? answersToFormResult(formMap, outcome.answers) : undefined;
            if (result) return { kind: 'form-result' as const, result };
          } catch {
            // Uma resposta que não dá para converter não fecha o diálogo nativo.
          }
        }
        return undefined;
      }
      const remote = polled.then((outcome) => remoteResult(outcome) ?? never<never>());
      let first = await Promise.race([local, remote]);
      if (singleFreeText && first.kind === 'local' && first.value && typeof first.value === 'object' && 'details' in first.value) {
        const { details } = first.value;
        if (details && typeof details === 'object' && 'answers' in details && Array.isArray(details.answers) && !details.answers.length
          && !('timedOut' in details && details.timedOut === true) && !('isError' in first.value && first.value.isError === true)) {
          // O nativo retorna um diagnóstico imediato quando não há campos; não há diálogo para responder lá.
          let hold = textHolds.get(sid);
          if (!hold) {
            hold = { input: new EventTarget(), count: 0 };
            textHolds.set(sid, hold);
          }
          hold.count++;
          const onMessage = () => stop.abort('user');
          hold.input.addEventListener('input', onMessage);
          let outcome;
          try {
            outcome = remoteResult(await polled);
          } finally {
            hold.input.removeEventListener('input', onMessage);
            if (!--hold.count) textHolds.delete(sid);
          }
          if (stop.signal.aborted) {
            await withdraw(id);
            throw new Error(stop.signal.reason === 'user' ? 'Ask interrupted by message' : 'Ask input was cancelled');
          }
          if (outcome?.kind !== 'form-result') return first.value;
          first = outcome;
        }
      }
      stop.abort();
      if (first.kind === 'local' || first.kind === 'local-error') {
        await withdraw(id);
        if (first.kind === 'local-error') throw first.error;
        return first.value;
      }
      await local;
      if (first.kind === 'form-result') return first.result;
      if (first.kind === 'habblaud') throw new Error(first.message ? `User declined to answer: ${first.message}` : 'User declined to answer');
      const result = askResult(params, first.answers);
      if (!result) throw new Error('Habblaud: resposta fora do formato do ask');
      return result;
    } finally {
      signal?.removeEventListener('abort', onAbort);
    }
  }

  let askShadowed = false;

  /** Troca o `ask` nativo pelo de cima, com o mesmo nome, descrição e parâmetros (o nativo segue no invokeTool). */
  function shadowAsk(): void {
    if (askShadowed || !pi.getAllTools || !pi.registerTool) return;
    const native = pi.getAllTools().find((t) => t.name === 'ask');
    if (!native?.parameters) return;
    askShadowed = true;
    pi.registerTool({
      name: 'ask',
      label: 'Ask',
      description: native.description ?? '',
      parameters: native.parameters,
      approval: 'read',
      strict: true,
      lenientArgValidation: true,
      concurrency: 'exclusive',
      execute: (_id, params, signal, _onUpdate, ctx) => ask(params, signal, ctx),
    });
  }
}

export default function habblaud(pi: OmpApi): void {
  create(pi, defaultDeps());
}
