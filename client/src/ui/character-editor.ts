// A gaveta veste membros do elenco em sessões principais presentes.
import type { AgentInfo } from '../../../shared/types';
import { tr } from '../../../shared/i18n';
import { access } from '../net/access';
import { canEditCharacter, castUnavailable } from './character-model';
import type { UiContext } from './context';
import { h, iconButton, setAttr, setHidden, setText } from './dom';
import { ICONS } from './icons';

export class CharacterEditor {
  readonly button: HTMLButtonElement;
  readonly el: HTMLElement;
  private agentId = '';
  private editing = false;
  private generation = 0;
  private readonly list: HTMLElement;
  private readonly error: HTMLElement;
  private readonly reset: HTMLButtonElement;

  constructor(private ctx: UiContext) {
    this.button = iconButton(ICONS.pencil, tr('Vestir personagem'), () => this.editing ? this.close() : void this.start(), 'ui-icon-btn--sm ui-char-edit');
    this.button.hidden = true;
    setAttr(this.button, 'aria-expanded', 'false');
    this.list = h('div', { class: 'ui-cast__list' });
    this.error = h('p', { class: 'ui-char__error', role: 'alert', hidden: true });
    this.reset = h('button', { class: 'ui-link-btn', type: 'button', text: tr('Voltar ao sorteio'), on: { click: () => void this.undress() } });
    const create = h('button', { class: 'ui-btn', type: 'button', text: tr('Criar a partir deste agente'), on: { click: () => {
      const agent = this.ctx.agent(this.agentId);
      if (agent) this.ctx.root.dispatchEvent(new CustomEvent('habblaud:cast-create', { detail: agent }));
    } } });
    this.el = h('section', { class: 'ui-char', hidden: true, attrs: { 'aria-label': tr('Vestir personagem') } }, this.list, this.error, create, this.reset);
  }

  open(id: string): void { if (id !== this.agentId) this.close(); this.agentId = id; }
  render(a: AgentInfo, live: boolean): void {
    const store = this.ctx.store;
    const allowed = canEditCharacter(a, { live, terminal: !!store.snapshot?.meta.terminal, local: access().local, replaying: store.replaying, mock: store.mock });
    setHidden(this.button, !allowed);
    if (this.editing && !allowed) this.close();
    setHidden(this.reset, !a.cast);
  }
  close(): void {
    this.generation++;
    this.editing = false;
    setHidden(this.el, true);
    setAttr(this.button, 'aria-expanded', 'false');
  }
  private async start(): Promise<void> {
    const agent = this.ctx.agent(this.agentId);
    if (!agent) return;
    this.editing = true;
    const generation = ++this.generation;
    setHidden(this.el, false);
    setAttr(this.button, 'aria-expanded', 'true');
    setHidden(this.error, true);
    this.list.replaceChildren();
    const result = await this.ctx.store.castList();
    if (!this.editing || this.agentId !== agent.id || this.generation !== generation) return;
    if ('error' in result) return this.showError(result.error);
    for (const member of result.members.filter((m) => m.kind === 'main')) {
      const reason = castUnavailable(member, agent, this.ctx.store.snapshot?.agents ?? []);
      const button = h('button', { class: 'ui-btn', type: 'button', text: reason ? `${member.name} — ${reason}` : member.name, on: { click: () => void this.dress(member.id) } });
      button.disabled = !!reason;
      this.list.append(button);
    }
    setHidden(this.reset, !agent.cast);
  }
  private async dress(cast: string): Promise<void> {
    const generation = this.generation;
    const error = await this.ctx.store.dressCast(this.agentId, cast);
    if (this.generation !== generation) return;
    if (error) this.showError(error); else this.close();
  }
  private async undress(): Promise<void> {
    const generation = this.generation;
    const error = await this.ctx.store.undressCast(this.agentId);
    if (this.generation !== generation) return;
    if (error) this.showError(error); else this.close();
  }
  private showError(message: string): void { setText(this.error, message); setHidden(this.error, false); }
}
