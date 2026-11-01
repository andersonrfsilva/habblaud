// Caixa de mensagem: manda um texto ao agente principal pelo Habblaud (POST /api/messages) e o plugin
// habblaud-mensagens o entrega à sessão como se você o tivesse digitado (no Codex, `codex queue` a põe na fila da
// sessão, que a usa quando fica ociosa). Fica na gaveta do agente e no rodapé do
// terminal (só a sessão ao vivo). Enter manda, Shift+Enter quebra a linha; a linha de situação acompanha a entrega
// (GET /api/messages/:id) até ela se resolver. As teclas digitadas aqui não disparam os atalhos globais. O rascunho de
// cada agente fica guardado (só na memória) ao trocar de agente. O microfone (ui/dictation.ts, Whisper local no Mac)
// põe o texto ditado no cursor; aparece quando o ditado funciona daqui. A lógica pura está em ui/composer-model.ts.
import { MESSAGE_MAX } from '../../../shared/messages';
import type { AgentInfo, OutboxMessage, Provider } from '../../../shared/types';
import {
  canSend,
  ComposerAttachments,
  composerMode,
  composerTip,
  enterSends,
  isSettled,
  LOST_ERROR,
  messageText,
  OFFLINE_ERROR,
  POLL_LIMIT_MS,
  pastedAttachments,
  pollDelay,
  sendStatusText,
  timeoutError,
  type ComposerMode,
  type SendState,
  type ComposerAttachment,
} from './composer-model';
import type { UiContext } from './context';
import { DictationButton, dictationAvailable, insertDictation } from './dictation';
import { h, iconButton, setAttr, setHidden, setText, setTitle } from './dom';
import { access } from '../net/access';
import { tr } from '../../../shared/i18n';
import { formatBytes } from './spawn';

export type ComposerVariant = 'drawer' | 'terminal';

export interface ComposerOptions {
  /** Esc dentro da caixa: para onde vai o foco (padrão: só sai da caixa). */
  onEscape?: () => void;
}

export class MessageComposer {
  readonly el: HTMLElement;
  /** Agente da caixa (ou a chave da conversa, no terminal do histórico). */
  private agentId = '';
  private mode: ComposerMode = { kind: 'off', text: '' };
  /** Rascunho por agente: é ele que manda no conteúdo da caixa. */
  private drafts = new Map<string, string>();
  /** Último envio de cada agente (a linha de situação). */
  private states = new Map<string, SendState>();
  /** Ferramenta de cada agente que recebeu mensagem (o texto da entrega e do prazo muda no Codex). */
  private providers = new Map<string, Provider>();
  /** Ferramenta do agente da caixa. */
  private provider: Provider = 'claude';
  /** O que a caixa mostra agora ("<agente>|<pronta>"): ao mudar, o conteúdo vem do rascunho. */
  private shown = '';
  private attachments = new ComposerAttachments();
  private attachmentErrors = new Map<string, string>();
  private shownAttachments: readonly ComposerAttachment[] = [];
  private files: HTMLInputElement;
  private attachBtn: HTMLButtonElement;
  private attachmentList: HTMLElement;
  private attachmentStatus: HTMLElement;
  private pickerAgentId = '';
  private destroyed = false;
  private pollTimers = new Set<number>();

  private row: HTMLElement;
  private box: HTMLTextAreaElement;
  private sendBtn: HTMLButtonElement;
  private hint: HTMLElement;
  private tip: HTMLElement | null;
  private status: HTMLElement;
  private dictate: DictationButton;
  /** Linha do ditado (gravando, transcrevendo, idioma, erro). */
  private dictStatus: HTMLElement;
  private dictTimer: ReturnType<typeof setTimeout> | null = null;
  /** Rascunho que recebe o ditado em andamento (o agente pode mudar enquanto o Mac transcreve). */
  private dictKey = '';
  private canDictate = false;

  constructor(
    private ctx: UiContext,
    private variant: ComposerVariant,
    private opts: ComposerOptions = {},
  ) {
    const term = variant === 'terminal';
    this.box = h('textarea', {
      class: `ui-msg__input${term ? ' ui-term__input' : ''}`,
      attrs: { rows: 1, maxlength: MESSAGE_MAX, autocomplete: 'off', enterkeyhint: 'send' },
    });
    this.box.addEventListener('input', () => {
      this.drafts.set(this.agentId, this.box.value);
      this.resize();
      this.syncButton();
    });
    this.box.addEventListener('focus', () => this.resize());
    this.box.addEventListener('keydown', (e) => this.onKey(e));
    this.files = h('input', { type: 'file', hidden: true, attrs: { multiple: true } });
    this.files.addEventListener('change', () => {
      this.addFiles(Array.from(this.files.files ?? []), this.pickerAgentId);
      this.files.value = '';
    });
    this.attachBtn = iconButton(
      '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 11.5 12 20a6 6 0 0 1-8.5-8.5l10-10a4 4 0 0 1 5.7 5.7L9 17.3a2 2 0 0 1-2.8-2.8L15 5.7"/></svg>',
      tr('Anexar arquivos'),
      () => {
        this.pickerAgentId = this.agentId;
        this.files.click();
      },
      'ui-msg__attach',
    );
    this.sendBtn = h(
      'button',
      {
        class: term ? 'ui-msg__send' : 'ui-btn ui-msg__send',
        type: 'button',
        title: tr('Mandar (Enter)'),
        attrs: { 'aria-label': tr('Mandar a mensagem') },
        on: { click: () => void this.send() },
      },
      term ? '↵' : tr('Enviar'),
    );
    this.dictStatus = h('p', { class: 'ui-msg__status ui-msg__dictation', role: 'status', hidden: true, attrs: { 'aria-live': 'polite' } });
    this.dictate = new DictationButton(
      (t) => this.insertDictated(t.text),
      (text, error) => this.sayDictation(text, error),
      { compact: true, onStart: () => (this.dictKey = this.agentId) },
    );
    this.dictate.el.classList.add('ui-msg__mic');
    setHidden(this.dictate.el, true);
    this.row = h('div', { class: `ui-msg__row${term ? ' ui-term__prompt ui-term__prompt--mic' : ''}` });
    if (term) this.row.append(h('span', { class: 'ui-term__caret', text: '>', attrs: { 'aria-hidden': 'true' } }));
    this.row.append(this.box, this.attachBtn, this.dictate.el, this.sendBtn);
    // Clique na "linha do prompt" (fora do botão) põe o cursor na caixa, como num terminal.
    this.row.addEventListener('mousedown', (e) => {
      if (e.target === this.row && !this.box.disabled) {
        e.preventDefault();
        this.box.focus();
      }
    });
    this.hint = h('p', { class: 'ui-msg__hint', hidden: true });
    this.status = h('p', { class: 'ui-msg__status', role: 'status', hidden: true, attrs: { 'aria-live': 'polite' } });
    this.tip = term ? null : h('p', { class: 'ui-msg__tip', text: composerTip() });
    this.attachmentList = h('div', { class: 'ui-msg__attachments', hidden: true });
    this.attachmentStatus = h('p', { class: 'ui-msg__status is-error', role: 'status', hidden: true, attrs: { 'aria-live': 'polite' } });
    this.el = h('div', { class: `ui-msg ui-msg--${variant}` }, this.hint, this.row, this.files, this.attachmentList, this.attachmentStatus, this.dictStatus, this.status, this.tip);
    this.box.addEventListener('paste', (event) => {
      const data = event.clipboardData;
      if (this.mode.kind !== 'ready' || !data) return;
      const files = pastedAttachments(data);
      if (!files.length) return;
      event.preventDefault();
      this.addFiles(files);
    });
    this.el.addEventListener('dragover', (event) => {
      if (this.mode.kind === 'ready' && event.dataTransfer?.types.includes('Files')) event.preventDefault();
    });
    this.el.addEventListener('drop', (event) => {
      if (this.mode.kind !== 'ready' || !event.dataTransfer?.files.length) return;
      event.preventDefault();
      this.addFiles(Array.from(event.dataTransfer.files));
    });
  }

  /**
   * Atualiza a caixa para `agent` (undefined = nenhum: sessão do histórico ou agente que saiu); `key` identifica o
   * rascunho. Devolve o modo: a gaveta esconde a seção quando é 'off'.
   */
  render(agent: AgentInfo | undefined, key = agent?.id ?? ''): ComposerMode['kind'] {
    const store = this.ctx.store;
    const mode = composerMode(agent, {
      enabled: store.mock || !!store.snapshot?.meta.messages,
      local: store.mock || access().messages,
      replaying: store.replaying,
    });
    this.mode = mode;
    const ready = mode.kind === 'ready';
    const term = this.variant === 'terminal';
    this.provider = agent?.provider ?? 'claude';

    // Troca de agente (ou a caixa passou a valer/deixou de valer): o conteúdo vem do rascunho guardado.
    const shown = `${key}|${ready}`;
    if (shown !== this.shown) {
      // Gravando para outro agente: para sem transcrever (a fala era para aquele).
      this.dictate.cancel();
      this.shown = shown;
      this.agentId = key;
      this.box.value = ready ? (this.drafts.get(key) ?? '') : '';
      this.box.style.height = '';
      if (ready) requestAnimationFrame(() => this.resize());
    }
    this.box.disabled = !ready;
    this.attachBtn.disabled = !ready;
    setHidden(this.attachBtn, !ready);
    this.renderAttachments();
    const name = agent?.name ?? tr('o agente');
    const placeholder = ready ? tr('Mensagem para {0}…', [name]) : mode.text;
    setAttr(this.box, 'placeholder', placeholder);
    setAttr(this.box, 'aria-label', ready ? tr('Mensagem para {0}', [name]) : mode.text);
    setTitle(this.box, ready ? '' : mode.text);
    this.row.classList.toggle('is-disabled', !ready);
    setHidden(this.sendBtn, !ready);
    // Consulta em cache (30 s): o Mac pode instalar o Whisper ou parar o auxiliar com a página aberta.
    void dictationAvailable().then((ok) => {
      if (ok === this.canDictate) return;
      this.canDictate = ok;
      this.ctx.invalidate();
    });
    // Transcrevendo: o botão fica até o texto chegar (ele vai para o rascunho de quem ditou).
    setHidden(this.dictate.el, !this.canDictate || (!ready && !this.dictate.busy));
    // Na gaveta, a dica do plugin entra no lugar da caixa; no terminal ela já está no lugar do texto.
    setHidden(this.row, !term && !ready);
    if (this.tip) {
      // omp: o modo de envio (Configurações) decide como a mensagem entra; a dica genérica não vale.
      setHidden(this.tip, !ready || this.provider === 'omp');
      setText(this.tip, composerTip(this.provider));
    }
    setHidden(this.hint, term || mode.kind !== 'hint');
    setText(this.hint, mode.kind === 'hint' ? mode.text : '');
    this.syncButton();

    const state = this.states.get(key);
    const text = sendStatusText(state, Date.now(), this.providers.get(key) ?? this.provider);
    setText(this.status, text);
    setHidden(this.status, !text);
    this.status.classList.toggle('is-error', state?.phase === 'failed' || state?.phase === 'rejected');
    this.status.classList.toggle('is-ok', state?.phase === 'delivered');
    return mode.kind;
  }

  // ---------------------------------------------------------------- internos
  destroy(): void {
    this.destroyed = true;
    this.attachments.destroy();
    this.dictate.cancel();
    clearTimeout(this.dictTimer ?? undefined);
    for (const timer of this.pollTimers) clearTimeout(timer);
    this.pollTimers.clear();
  }

  private addFiles(files: File[], agentId = this.agentId): void {
    if (this.destroyed || !files.length) return;
    this.attachmentErrors.set(agentId, this.attachments.add(agentId, files).join(' · '));
    this.renderAttachments();
    this.syncButton();
  }

  private renderAttachments(): void {
    const ready = this.mode.kind === 'ready';
    const list = this.attachments.get(this.agentId);
    setHidden(this.attachmentList, !ready || !list.length);
    const error = this.attachmentErrors.get(this.agentId) ?? '';
    setText(this.attachmentStatus, error);
    setHidden(this.attachmentStatus, !ready || !error);
    if (list.length === this.shownAttachments.length && list.every((item, index) => item === this.shownAttachments[index])) return;
    this.shownAttachments = list;
    this.attachmentList.replaceChildren(...list.map((attachment) => {
      const file = attachment.file;
      const preview = attachment.url
        ? h('img', { attrs: { src: attachment.url, alt: '' } })
        : h('span', { class: 'ui-msg__extension', text: file.name.includes('.') ? file.name.split('.').pop()!.slice(0, 8) : '—' });
      return h('div', { class: 'ui-msg__attachment' },
        preview,
        h('span', { class: 'ui-msg__filename', text: file.name, title: file.name }),
        h('span', { class: 'ui-msg__size', text: formatBytes(file.size) }),
        h('button', {
          class: 'ui-msg__remove', type: 'button', title: tr('Remover anexo: {0}', [file.name]),
          attrs: { 'aria-label': tr('Remover anexo: {0}', [file.name]) },
          on: { click: () => {
            this.attachments.remove(this.agentId, attachment);
            this.attachmentErrors.delete(this.agentId);
            this.renderAttachments();
            this.syncButton();
          } },
        }, '×'));
    }));
  }

  private onKey(e: KeyboardEvent): void {
    // Os atalhos globais (F, T, P, /, setas...) não valem dentro da caixa. Com Ctrl/⌘ a tecla segue adiante
    // (Ctrl/⌘+F busca na conversa do terminal; os atalhos globais ignoram essas combinações).
    if (!e.ctrlKey && !e.metaKey) e.stopPropagation();
    if (e.key === 'Escape') {
      // Esc só sai da caixa (o próximo fecha o terminal ou a gaveta).
      e.preventDefault();
      e.stopPropagation();
      if (this.opts.onEscape) this.opts.onEscape();
      else this.box.blur();
      return;
    }
    if (enterSends(e)) {
      e.preventDefault();
      void this.send();
    }
  }

  /** Texto ditado: no cursor, se a caixa ainda é a de quem ditou; senão, no fim do rascunho daquele agente. */
  private insertDictated(text: string): void {
    const key = this.dictKey;
    if (key === this.agentId && !this.box.disabled) {
      const box = this.box;
      const { value, caret } = insertDictation(box.value, box.selectionStart ?? box.value.length, box.selectionEnd ?? box.value.length, text);
      box.value = value.slice(0, MESSAGE_MAX);
      this.drafts.set(key, box.value);
      this.resize();
      this.syncButton();
      box.focus({ preventScroll: true });
      box.setSelectionRange(caret, caret);
    } else {
      const cur = this.drafts.get(key) ?? '';
      this.drafts.set(key, insertDictation(cur, cur.length, cur.length, text).value.slice(0, MESSAGE_MAX));
    }
  }

  private sayDictation(text: string, error = false): void {
    if (this.dictTimer) clearTimeout(this.dictTimer);
    setText(this.dictStatus, text);
    setHidden(this.dictStatus, !text);
    this.dictStatus.classList.toggle('is-error', error);
    // Avisos de sucesso somem sozinhos; "Gravando…" e "Transcrevendo…" ficam até a próxima mudança.
    if (!error && !/…$/.test(text)) this.dictTimer = setTimeout(() => setHidden(this.dictStatus, true), 6_000);
  }

  private syncButton(): void {
    this.sendBtn.disabled = this.mode.kind !== 'ready' || !canSend(this.box.value, this.attachments.get(this.agentId).length) || this.states.get(this.agentId)?.phase === 'sending';
  }

  /** Cresce com o texto até o limite do CSS (~6 linhas; depois rola). Escondida, mede quando aparecer. */
  private resize(): void {
    const box = this.box;
    if (!box.isConnected || box.offsetParent === null) return;
    box.style.height = 'auto';
    box.style.height = `${box.scrollHeight + box.offsetHeight - box.clientHeight}px`;
  }

  private setState(agentId: string, state: SendState): void {
    this.states.set(agentId, state);
    if (agentId === this.agentId) this.syncButton();
    this.ctx.invalidate();
  }

  private async send(): Promise<void> {
    const agentId = this.agentId;
    const provider = this.provider;
    // Modo global (Configurações), lido no envio: trocar durante o upload não muda este envio.
    const deliverAs = provider === 'omp' ? this.ctx.prefs.ompDeliverAs : undefined;
    const raw = this.box.value;
    const batch = this.attachments.capture(agentId);
    if (this.destroyed || this.mode.kind !== 'ready' || !canSend(raw, batch.length) || this.states.get(agentId)?.phase === 'sending') return;
    this.providers.set(agentId, provider);
    this.setState(agentId, { phase: 'sending', deliverAs, at: Date.now() });
    let r: { message: OutboxMessage } | { error: string; attachmentsExpired?: boolean };
    try {
      const ids = await this.attachments.upload(batch, (file) => this.ctx.store.uploadMessageAttachment(file));
      if (this.destroyed) return;
      r = await this.ctx.store.sendMessage(agentId, messageText(raw), ids, deliverAs);
    } catch (error) {
      r = { error: error instanceof Error ? error.message : OFFLINE_ERROR };
    }
    if (this.destroyed) return;
    if ('error' in r) {
      // Recusada: o texto continua na caixa para tentar de novo.
      if (r.attachmentsExpired) this.attachments.invalidate(batch);
      this.setState(agentId, { phase: 'rejected', error: r.error, deliverAs, at: Date.now() });
      return;
    }
    this.attachments.accepted(agentId, batch);
    this.attachmentErrors.delete(agentId);
    this.renderAttachments();
    // Na fila: a caixa esvazia (a não ser que você tenha continuado a escrever enquanto o envio ia).
    if ((this.drafts.get(agentId) ?? '') === raw) {
      this.drafts.delete(agentId);
      if (this.agentId === agentId && this.box.value === raw) {
        this.box.value = '';
        this.resize();
      }
    }
    const m = r.message;
    this.setState(agentId, { phase: m.status, id: m.id, error: m.error, deliverAs: m.deliverAs ?? deliverAs, at: Date.now() });
    if (!isSettled(m.status)) this.poll(agentId, m.id, Date.now());
  }

  /** Consulta a situação até ela se resolver (ou o prazo acabar). Para quando outro envio toma a linha de situação. */
  private poll(agentId: string, id: string, since: number): void {
    const step = async () => {
      if (this.destroyed || this.states.get(agentId)?.id !== id) return;
      let m: OutboxMessage | null | undefined;
      try {
        m = await this.ctx.store.messageStatus(id);
      } catch {
        // Sem conexão agora: tenta de novo na próxima volta.
      }
      const cur = this.states.get(agentId);
      if (this.destroyed || cur?.id !== id) return;
      const now = Date.now();
      if (m === null) return this.setState(agentId, { ...cur, phase: 'failed', error: LOST_ERROR, at: now });
      if (m && (m.status !== cur.phase || m.error !== cur.error || (m.deliverAs !== undefined && m.deliverAs !== cur.deliverAs))) this.setState(agentId, { phase: m.status, id, error: m.error, deliverAs: m.deliverAs ?? cur.deliverAs, at: now });
      if (m && isSettled(m.status)) return;
      const elapsed = now - since;
      if (elapsed >= POLL_LIMIT_MS) return this.setState(agentId, { ...cur, phase: 'failed', error: timeoutError(this.providers.get(agentId)), at: now });
      this.schedulePoll(step, pollDelay(elapsed));
    };
    this.schedulePoll(step, pollDelay(0));
  }

  private schedulePoll(step: () => Promise<void>, delay: number): void {
    const timer = window.setTimeout(() => {
      this.pollTimers.delete(timer);
      if (!this.destroyed) void step();
    }, delay);
    this.pollTimers.add(timer);
  }
}
