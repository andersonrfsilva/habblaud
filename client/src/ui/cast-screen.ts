// Gestão do elenco: membros existem independentemente das sessões presentes.
import type { AgentInfo, CastMember } from '../../../shared/types';
import { tr } from '../../../shared/i18n';
import { appearanceFromSeed } from '../art';
import { AppearanceFields } from './appearance-fields';
import { changedParts, validateCast } from './character-model';
import type { UiContext } from './context';
import { h } from './dom';
import { spawnFolders, type FolderOption } from './spawn';

export class CastScreen {
  readonly el: HTMLDialogElement;
  private readonly content: HTMLElement;
  private members: CastMember[] = [];
  private folders: string[] = [];
  private readonly recentTypes = new Set<string>();

  constructor(private readonly ctx: UiContext) {
    this.content = h('div', { class: 'ui-cast__content' });
    const close = h('button', { class: 'ui-btn', type: 'button', text: tr('Fechar'), on: { click: () => this.el.close() } });
    this.el = h('dialog', { class: 'ui-cast', attrs: { 'aria-label': tr('Elenco') } }, h('div', { class: 'ui-cast__heading' }, h('h2', { text: tr('Elenco') }), close), this.content);
    ctx.root.addEventListener('habblaud:cast-create', (event) => void this.open((event as CustomEvent<AgentInfo>).detail));
    ctx.store.on('snapshot', (snapshot) => {
      for (const agent of snapshot.agents) {
        if (!agent.agentType) continue;
        this.recentTypes.delete(agent.agentType);
        this.recentTypes.add(agent.agentType);
        if (this.recentTypes.size > 20) this.recentTypes.delete(this.recentTypes.values().next().value!);
      }
    });
  }

  async open(agent?: AgentInfo): Promise<void> {
    if (!this.el.open) this.el.showModal();
    this.content.replaceChildren(h('p', { text: tr('Carregando…') }));
    const result = await this.ctx.store.castList();
    if ('error' in result) { this.content.replaceChildren(h('p', { role: 'alert', text: result.error })); return; }
    this.members = result.members;
    this.folders = spawnFolders(this.ctx.store.snapshot);
    try {
      const response = await fetch('/api/spawn/folders');
      if (response.ok) {
        const data = await response.json() as { folders?: FolderOption[] };
        for (const folder of data.folders ?? []) if (!this.folders.includes(folder.path)) this.folders.push(folder.path);
      }
    } catch { /* As salas abertas continuam disponíveis. */ }
    if (agent) this.edit(undefined, agent);
    else this.showList();
  }

  private showList(): void {
    const add = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: tr('Criar personagem'), on: { click: () => this.edit() } });
    const list = h('div', { class: 'ui-cast__list' });
    for (const member of this.members) {
      const label = member.kind === 'main' ? tr('Principal') : member.kind === 'advisor' ? tr('Advisor') : tr('Subagente');
      const edit = h('button', { class: 'ui-btn', type: 'button', text: tr('Editar'), on: { click: () => this.edit(member) } });
      const del = h('button', { class: 'ui-btn', type: 'button', text: tr('Apagar'), on: { click: () => void this.remove(member) } });
      list.append(h('div', { class: 'ui-cast__row' }, h('span', { text: `${member.name} — ${label}` }), edit, del));
    }
    this.content.replaceChildren(add, list);
  }

  private async remove(member: CastMember): Promise<void> {
    if (!confirm(tr('Apagar {0} do elenco?', [member.name]))) return;
    const error = await this.ctx.store.deleteCast(member.id);
    if (error) { this.content.append(h('p', { role: 'alert', text: error })); return; }
    await this.open();
  }

  private edit(member?: CastMember, agent?: AgentInfo): void {
    let seed = member?.seed ?? agent?.seed ?? crypto.getRandomValues(new Uint32Array(1))[0];
    let look: 'f' | 'm' = member?.look ?? agent?.look ?? 'f';
    let base = appearanceFromSeed(seed, { look });
    const fields = new AppearanceFields();
    fields.set({ ...base, ...(member?.parts ?? agent?.parts) });
    const name = h('input', { type: 'text', attrs: { maxlength: '24', required: '' } });
    name.value = member?.name ?? agent?.name ?? '';
    const kind = h('select');
    for (const [value, label] of [['main', tr('Principal')], ['advisor', tr('Advisor')], ['sub', tr('Subagente')]]) kind.append(h('option', { text: label, attrs: { value } }));
    kind.value = member?.kind ?? 'main';
    const match = h('input', { type: 'text', attrs: { list: 'ui-cast-types' } });
    match.value = member?.match?.join(', ') ?? '';
    const suggestions = h('datalist', { attrs: { id: 'ui-cast-types' } });
    for (const type of this.recentTypes) suggestions.append(h('option', { attrs: { value: type } }));
    const selected = new Set(member?.rooms ?? (agent ? [agent.roomId] : []));
    const allFolders = [...new Set([...this.folders, ...selected])];
    const checks: Array<{ path: string; input: HTMLInputElement }> = [];
    const rooms = h('fieldset', {}, h('legend', { text: tr('Salas permitidas (nenhuma = todas)') }));
    for (const path of allFolders) {
      const input = h('input', { type: 'checkbox' }); input.checked = selected.has(path);
      checks.push({ path, input });
      rooms.append(h('label', { class: 'ui-cast__check' }, input, h('span', { text: path })));
    }
    const home = h('select');
    home.append(h('option', { text: tr('Nenhuma'), attrs: { value: '' } }));
    for (const path of allFolders) home.append(h('option', { text: path, attrs: { value: path } }));
    home.value = member?.home ?? '';
    const error = h('p', { role: 'alert', class: 'ui-char__error' });
    const roll = h('button', { class: 'ui-btn', type: 'button', text: tr('Sortear'), on: { click: () => {
      seed = crypto.getRandomValues(new Uint32Array(1))[0];
      base = appearanceFromSeed(seed, { look }); fields.set({ ...base });
    } } });
    const lookSelect = h('select');
    lookSelect.append(h('option', { text: tr('Feminino'), attrs: { value: 'f' } }), h('option', { text: tr('Masculino'), attrs: { value: 'm' } }));
    lookSelect.value = look;
    lookSelect.addEventListener('change', () => { look = lookSelect.value === 'm' ? 'm' : 'f'; base = appearanceFromSeed(seed, { look }); fields.set({ ...base }); });
    const save = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: tr('Salvar'), on: { click: () => void (async () => {
      const allowed = checks.filter((c) => c.input.checked).map((c) => c.path);
      const types = match.value.split(',').map((v) => v.trim()).filter(Boolean);
      const homeValue = kind.value === 'main' ? home.value : '';
      const problem = validateCast(name.value, allowed, homeValue, types);
      if (problem || !fields.value) { error.textContent = problem ?? tr('Aparência inválida.'); return; }
      const body: Omit<CastMember, 'id'> = { name: name.value.trim(), look, seed, kind: kind.value as CastMember['kind'], rooms: allowed, parts: changedParts(base, fields.value), ...(kind.value === 'sub' ? { match: types } : {}), ...(homeValue ? { home: homeValue } : {}) };
      save.disabled = true;
      const failure = await this.ctx.store.saveCast(member ? { ...body, id: member.id } : body);
      save.disabled = false;
      if (failure) { error.textContent = failure; return; }
      await this.open();
    })() } });
    const cancel = h('button', { class: 'ui-btn', type: 'button', text: tr('Cancelar'), on: { click: () => this.showList() } });
    this.content.replaceChildren(h('div', { class: 'ui-cast__form' },
      h('label', {}, h('span', { text: tr('Nome') }), name),
      h('label', {}, h('span', { text: tr('Tipo') }), kind),
      h('label', {}, h('span', { text: tr('Tipos de subagente (separados por vírgula)') }), match), suggestions,
      rooms, h('label', {}, h('span', { text: tr('Sala principal') }), home),
      h('label', {}, h('span', { text: tr('Aparência') }), lookSelect), fields.preview, roll, fields.groups, error,
      h('div', { class: 'ui-char__actions' }, save, cancel)));
  }
}
