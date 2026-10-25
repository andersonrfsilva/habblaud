// Faixa do omp no rodapé do terminal: a barra do contexto (tokens em uso / janela do modelo, como no rodapé do omp) e
// os botões STOP, /new e /shake (elide e thinking). Vão pela fila de controle (POST /api/messages/control {command}
// ou {stop: true}); a extensão do omp digita os comandos na tela dele (só com o agente livre e o editor vazio),
// interrompe o turno no STOP (só com o agente trabalhando) e confirma ou recusa com o motivo.
import type { OmpCommand } from '../../../shared/omp-events';
import type { AgentInfo } from '../../../shared/types';
import { OFFLINE_ERROR } from './composer-model';
import type { UiContext } from './context';
import { h, setAttr, setHidden, setStyleVar, setText, setTitle, setVariant } from './dom';
import { formatTokens, usageLevel } from './format';
import { tr } from '../../../shared/i18n';

/** Rótulo e dica de cada botão, na ordem da faixa. */
const BUTTONS: ReadonlyArray<[OmpCommand, string]> = [
  ['new', tr('Começa uma conversa nova no omp (a atual fica no histórico)')],
  ['shake', tr('Roda /shake elide e /shake thinking: troca resultados de ferramentas e blocos grandes antigos por referências recuperáveis (artifact://) e tira do contexto o raciocínio das respostas antigas')],
];
/** Rótulo do botão que interrompe o turno (não é um comando digitado no omp). */
const STOP = 'STOP';

/** Barra do contexto: percentual (uma casa), nível de cor e o texto "123 k / 1 M · 12%". undefined = sem leitura. */
export function contextView(c: AgentInfo['context']): { pct: number; level: ReturnType<typeof usageLevel>; text: string } | undefined {
  if (!c || c.window <= 0) return undefined;
  const pct = Math.min(100, Math.round((c.tokens / c.window) * 1000) / 10);
  return { pct, level: usageLevel(pct), text: `${formatTokens(c.tokens)} / ${formatTokens(c.window)} · ${pct}%` };
}

export class OmpBar {
  readonly el: HTMLElement;
  private meter: HTMLElement;
  private fill: HTMLElement;
  private label: HTMLElement;
  private buttons: HTMLElement;
  private statusEl: HTMLElement;
  private stopBtn: HTMLButtonElement;
  private agentId: string | null = null;
  private busy = false;
  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor(private ctx: UiContext) {
    this.fill = h('span', { class: 'ui-term__ctx-fill' });
    this.label = h('span', { class: 'ui-term__ctx-text' });
    this.meter = h('div', { class: 'ui-term__ctx', role: 'meter', attrs: { 'aria-valuemin': '0', 'aria-valuemax': '100' } }, h('span', { class: 'ui-term__ctx-bar' }, this.fill), this.label);
    this.stopBtn = h('button', {
      class: 'ui-btn ui-btn--sm ui-btn--danger ui-term__cmd',
      type: 'button',
      text: STOP,
      title: tr('Interrompe o que o agente está fazendo agora (como o Esc no omp)'),
      on: { click: () => void this.run('stop') },
    });
    this.buttons = h(
      'div',
      { class: 'ui-term__cmds', role: 'group', attrs: { 'aria-label': tr('Comandos do omp') } },
      this.stopBtn,
      ...BUTTONS.map(([cmd, hint]) => h('button', { class: 'ui-btn ui-btn--sm ui-term__cmd', type: 'button', text: `/${cmd}`, title: hint, on: { click: () => void this.run(cmd) } })),
    );
    this.statusEl = h('span', { class: 'ui-term__cmd-status', role: 'status', hidden: true });
    this.el = h('div', { class: 'ui-term__omp', hidden: true }, this.meter, this.statusEl, this.buttons);
  }

  /** `ready`: a caixa de mensagem do agente está pronta (a extensão do omp está conectada). */
  render(agent: AgentInfo | undefined, ready: boolean): void {
    const omp = !!agent && agent.provider === 'omp' && agent.kind === 'main';
    if (agent?.id !== this.agentId) {
      this.agentId = agent?.id ?? null;
      this.say('');
    }
    const view = omp ? contextView(agent.context) : undefined;
    const commands = omp && ready && !this.ctx.store.mock;
    setHidden(this.el, !view && !commands);
    setHidden(this.meter, !view);
    if (view) {
      setStyleVar(this.fill, '--pct', String(view.pct));
      setVariant(this.meter, 'is-', view.level);
      setText(this.label, view.text);
      setAttr(this.meter, 'aria-valuenow', String(view.pct));
      setTitle(this.meter, tr('Contexto da sessão: {0} (atualiza a cada 10 s e no fim de cada turno)', [view.text]));
    }
    setHidden(this.buttons, !commands);
    for (const b of this.buttons.querySelectorAll('button')) b.disabled = this.busy;
    // Só há o que parar num turno (ou esperando você no meio dele).
    this.stopBtn.disabled = this.busy || (agent?.status !== 'working' && agent?.status !== 'waiting');
  }

  private say(text: string, error = false): void {
    clearTimeout(this.timer);
    setText(this.statusEl, text);
    setHidden(this.statusEl, !text);
    this.statusEl.classList.toggle('is-error', error);
    if (text && !error) this.timer = setTimeout(() => setHidden(this.statusEl, true), 5_000);
  }

  /** Manda o comando (ou o STOP) e acompanha até a extensão confirmar (ou recusar, com o motivo). */
  private async run(command: OmpCommand | 'stop'): Promise<void> {
    const agentId = this.agentId;
    if (!agentId || this.busy) return;
    if (command === 'new' && !window.confirm(tr('Começar uma conversa nova no omp? A atual continua no histórico (/resume).'))) return;
    const label = command === 'stop' ? STOP : `/${command}`;
    this.busy = true;
    this.ctx.invalidate();
    this.say(tr('Enviando {0}…', [label]));
    try {
      const r = await this.ctx.store.controlAgent(agentId, command === 'stop' ? { stop: true } : { command });
      if ('error' in r) return this.say(r.error, true);
      for (let i = 0; i < 20; i++) {
        // O cliente compila para antes do ES2024 (sem Promise.withResolvers).
        await new Promise((ok) => setTimeout(ok, 500));
        const m = await this.ctx.store.messageStatus(r.message.id);
        if (!m || m.status === 'delivered') return this.say(tr('{0} enviado ao omp.', [label]));
        if (m.status === 'failed') return this.say(m.error ?? tr('o omp não rodou o comando'), true);
      }
      this.say(tr('O omp ainda não confirmou o comando.'), true);
    } catch {
      this.say(OFFLINE_ERROR, true);
    } finally {
      this.busy = false;
      this.ctx.invalidate();
    }
  }
}
