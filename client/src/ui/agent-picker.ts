// Lista de agentes aberta pelos contadores da barra do topo (agentes, trabalhando, precisam de você): clique na linha
// seleciona o agente (e troca o terminal aberto para ele); o botão de terminal abre ou fecha o terminal dele.
import type { AgentInfo } from '../../../shared/types';
import type { UiContext } from './context';
import { h, iconButton, KeyedList, setAttr, setHidden, setText, setTitle } from './dom';
import { ICONS } from './icons';
import { isPresent, waitingAgents } from './model';
import { createAgentRow, updateAgentRow } from './rows';
import { tr } from '../../../shared/i18n';

export type PickerKind = 'agents' | 'working' | 'waiting';

/** Agentes de cada contador: principais antes dos subagentes; "precisam de você" do que espera há mais tempo. */
export function pickerAgents(kind: PickerKind, agents: readonly AgentInfo[]): AgentInfo[] {
  if (kind === 'waiting') return waitingAgents(agents);
  const shown = agents.filter((a) => isPresent(a) && (kind === 'agents' || a.status === 'working'));
  return shown.sort((a, b) => Number(a.kind === 'sub') - Number(b.kind === 'sub') || a.name.localeCompare(b.name));
}

const TITLES: Record<PickerKind, () => string> = {
  agents: () => tr('Agentes no escritório'),
  working: () => tr('Trabalhando agora'),
  waiting: () => tr('Precisam de você'),
};

export class AgentPicker {
  readonly el: HTMLElement;
  private readonly title: HTMLElement;
  private readonly empty: HTMLElement;
  private readonly list: KeyedList<AgentInfo>;
  private kind: PickerKind = 'agents';
  private anchor: HTMLElement | null = null;
  private closedAt = 0;

  constructor(private readonly ctx: UiContext) {
    this.title = h('h2');
    this.empty = h('p', { class: 'ui-picker__empty', text: tr('Ninguém agora.') });
    const listEl = h('ul', { class: 'ui-picker__list' });
    this.list = new KeyedList<AgentInfo>(listEl, {
      animate: false,
      key: (a) => a.id,
      create: (a) => this.createItem(a),
      update: (li, a) => this.updateItem(li, a),
    });
    const close = iconButton(ICONS.close, tr('Fechar'), () => this.hide(), 'ui-icon-btn--sm');
    this.el = h('div', { class: 'ui-popover ui-picker', role: 'dialog', tabIndex: -1, attrs: { popover: 'auto' } },
      h('div', { class: 'ui-popover__head' }, this.title, close), this.empty, listEl);
    this.el.addEventListener('toggle', () => {
      if (this.isOpen) return;
      this.closedAt = performance.now();
      if (this.anchor) setAttr(this.anchor, 'aria-expanded', 'false');
      this.anchor?.classList.remove('is-on');
    });
  }

  get isOpen(): boolean {
    try {
      return this.el.matches(':popover-open');
    } catch {
      return this.el.classList.contains('is-open');
    }
  }

  toggle(kind: PickerKind, anchor: HTMLElement): void {
    if (this.isOpen && this.kind === kind) return this.hide();
    // O clique que fechou a lista (fora dela, no próprio contador) não a reabre.
    if (!this.isOpen && this.kind === kind && performance.now() - this.closedAt < 250) return;
    this.anchor?.classList.remove('is-on');
    this.kind = kind;
    this.anchor = anchor;
    const r = anchor.getBoundingClientRect();
    this.el.style.top = `${Math.round(r.bottom + 8)}px`;
    this.el.style.left = `${Math.max(8, Math.min(Math.round(r.left), innerWidth - 368))}px`;
    if (!this.isOpen) {
      if (typeof this.el.showPopover === 'function') this.el.showPopover();
      else this.el.classList.add('is-open');
    }
    setAttr(anchor, 'aria-expanded', 'true');
    anchor.classList.add('is-on');
    this.render();
  }

  hide(): void {
    if (typeof this.el.hidePopover === 'function' && this.isOpen) this.el.hidePopover();
    this.el.classList.remove('is-open');
  }

  render(): void {
    if (!this.isOpen) return;
    const agents = pickerAgents(this.kind, this.ctx.store.snapshot?.agents ?? []);
    setText(this.title, `${TITLES[this.kind]()} · ${agents.length}`);
    setAttr(this.el, 'aria-label', TITLES[this.kind]());
    setHidden(this.empty, agents.length > 0);
    this.list.sync(agents);
  }

  private createItem(a: AgentInfo): HTMLElement {
    const row = createAgentRow(a, (id) => this.pick(id), a.kind === 'sub' ? 'sm' : 'md');
    const term = h('button', { class: 'ui-node__term', type: 'button' });
    term.innerHTML = ICONS.terminal;
    term.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = row.dataset.id;
      if (!id) return;
      this.ctx.select({ type: 'agent', id }, { focus: true });
      this.ctx.terminals?.toggle(id, term);
      this.hide();
    });
    return h('li', { class: 'ui-picker__item' }, h('div', { class: 'ui-node__head' }, row, term));
  }

  private updateItem(li: HTMLElement, a: AgentInfo): void {
    const head = li.firstElementChild as HTMLElement;
    const [row, term] = head.children as unknown as [HTMLElement, HTMLButtonElement];
    const sel = this.ctx.selection();
    updateAgentRow(row, a, this.ctx.account(a.account), sel?.type === 'agent' && sel.id === a.id, this.ctx.now(), this.ctx.store.snapshot?.agents);
    const router = this.ctx.terminals;
    const can = !!router?.available && a.status !== 'offline';
    setHidden(term, !can);
    if (can && router) {
      const open = router.openAgentId === a.id;
      term.classList.toggle('is-on', open);
      term.classList.toggle('is-live', router.interactive(a.id));
      const label = open ? tr('Fechar o terminal de {0}', [a.name]) : tr('Abrir o terminal de {0}', [a.name]);
      setTitle(term, label);
      setAttr(term, 'aria-label', label);
    }
  }

  /** Mesmo clique da lista lateral: seleciona e, com um terminal aberto, troca o terminal para este agente. */
  private pick(id: string): void {
    this.ctx.select({ type: 'agent', id }, { focus: true });
    const t = this.ctx.terminals;
    if (t && t.openAgentId !== null && t.openAgentId !== id && this.ctx.agent(id)) t.open(id);
    this.hide();
  }
}
