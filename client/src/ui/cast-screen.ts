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
    const calls: Array<{ member: CastMember; button: HTMLButtonElement }> = [];
    const reason = h('p', { role: 'status' });
    for (const member of this.members) {
      const label = member.kind === 'main' ? tr('Principal') : member.kind === 'advisor' ? tr('Advisor') : tr('Subagente');
      const edit = h('button', { class: 'ui-btn', type: 'button', text: tr('Editar'), on: { click: () => this.edit(member) } });
      const call = member.kind === 'main' ? h('button', { class: 'ui-btn', type: 'button', text: tr('Chamar'), on: { click: () => void this.call(member.id) } }) : null;
      if (call) { call.disabled = true; calls.push({ member, button: call }); }
      const del = h('button', { class: 'ui-btn', type: 'button', text: tr('Apagar'), on: { click: () => void this.remove(member) } });
      list.append(h('div', { class: 'ui-cast__row' }, h('span', { text: `${member.name} — ${label}` }), ...(call ? [call] : []), edit, del));
    }
    this.content.replaceChildren(add, reason, list);
    const watch = async () => {
      if (!this.el.open || !this.content.contains(list)) return;
      const jobs = await this.ctx.store.castJobs();
      if (!this.el.open || !this.content.contains(list)) return;
      reason.textContent = jobs.available ? '' : tr('auxiliar do host desligado (npm run codex:bridge)');
      for (const { member, button } of calls) {
        const busy = jobs.jobs.some((job) => job.cast === member.id && (job.status === 'queued' || job.status === 'sent' || job.status === 'waiting'));
        button.disabled = !jobs.available || busy || !!this.ctx.store.snapshot?.agents.some((a) => a.cast === member.id && a.status !== 'offline');
        button.textContent = busy ? tr('chamando…') : tr('Chamar');
      }
      setTimeout(() => void watch(), 2000);
    };
    void watch();
  }

  async call(id: string): Promise<void> {
    if (!this.el.open) this.el.showModal();
    const [list, jobs] = await Promise.all([this.ctx.store.castList(), this.ctx.store.castJobs()]);
    if ('error' in list) { this.content.replaceChildren(h('p', { role: 'alert', text: list.error })); return; }
    const member = list.members.find((m) => m.id === id && m.kind === 'main');
    if (!member) return;
    const folders = member.rooms.length ? member.rooms : spawnFolders(this.ctx.store.snapshot);
    if (!member.rooms.length) {
      try {
        const response = await fetch('/api/spawn/folders');
        if (response.ok) for (const folder of ((await response.json()) as { folders?: FolderOption[] }).folders ?? []) if (!folders.includes(folder.path)) folders.push(folder.path);
      } catch { /* As salas abertas continuam disponíveis. */ }
    }
    folders.sort((a, b) => Number(b === member.home) - Number(a === member.home));
    const room = h('select');
    for (const path of folders) room.append(h('option', { text: path, attrs: { value: path } }));
    const mode = h('select');
    const resume = h('option', { text: tr('Continuar'), attrs: { value: 'resume' } });
    mode.append(resume, h('option', { text: tr('Nova'), attrs: { value: 'new' } }));
    const refresh = () => { resume.disabled = !member.last?.[room.value]; if (resume.disabled) mode.value = 'new'; };
    room.addEventListener('change', refresh); refresh();
    const prompt = h('textarea', { attrs: { maxlength: '8000' } });
    const error = h('p', { role: 'alert' });
    const calling = jobs.jobs.find((job) => job.cast === id && (job.status === 'queued' || job.status === 'sent' || job.status === 'waiting'));
    const start = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: calling ? tr('chamando…') : tr('Chamar'), on: { click: () => void (async () => {
      start.disabled = true;
      const failure = await this.ctx.store.startCast(id, room.value, mode.value === 'resume' ? 'resume' : 'new', prompt.value);
      if (failure) { error.textContent = failure; start.disabled = false; return; }
      start.textContent = tr('chamando…');
    })() } });
    start.disabled = !jobs.available || !!calling || !folders.length;
    if (!jobs.available) error.textContent = tr('auxiliar do host desligado (npm run codex:bridge)');
    else if (!calling) error.textContent = jobs.jobs.filter((job) => job.cast === id && job.status === 'failed').at(-1)?.error ?? '';
    const back = h('button', { class: 'ui-btn', type: 'button', text: tr('Voltar'), on: { click: () => void this.open() } });
    this.content.replaceChildren(h('h3', { text: member.name }), h('label', {}, h('span', { text: tr('Sala') }), room), h('label', {}, h('span', { text: tr('Sessão') }), mode), h('label', {}, h('span', { text: tr('Pedido opcional') }), prompt), error, start, back);
    const watch = async () => {
      if (!this.el.open || !this.content.contains(start)) return;
      const latest = await this.ctx.store.castJobs();
      if (!this.el.open || !this.content.contains(start)) return;
      const active = latest.jobs.find((job) => job.cast === id && (job.status === 'queued' || job.status === 'sent' || job.status === 'waiting'));
      const failed = latest.jobs.filter((job) => job.cast === id && job.status === 'failed').at(-1);
      start.disabled = !latest.available || !!active || !folders.length;
      start.textContent = active ? tr('chamando…') : tr('Chamar');
      error.textContent = !latest.available ? tr('auxiliar do host desligado (npm run codex:bridge)') : failed?.error ?? '';
      setTimeout(() => void watch(), 2000);
    };
    setTimeout(() => void watch(), 2000);
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
    const model = h('input', { type: 'text', attrs: { list: 'ui-cast-models', maxlength: '200' } });
    model.value = member?.model ?? '';
    const models = h('datalist', { attrs: { id: 'ui-cast-models' } });
    for (const value of new Set((this.ctx.store.snapshot?.agents ?? []).flatMap((a) => a.models ?? []))) models.append(h('option', { attrs: { value } }));
    const instructions = h('textarea', { attrs: { maxlength: '8000' } });
    instructions.value = member?.instructions ?? '';
    const idle = h('input', { type: 'number', attrs: { min: '0', max: '1440', step: '1' } });
    idle.value = String(member?.idleMinutes ?? 30);
    const account = h('select');
    account.append(h('option', { text: tr('Padrão'), attrs: { value: '' } }));
    for (const item of this.ctx.store.snapshot?.accounts ?? []) if (item.provider === 'omp') account.append(h('option', { text: item.name, attrs: { value: item.id } }));
    account.value = member?.account ?? '';
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
      const body: Omit<CastMember, 'id'> = { name: name.value.trim(), look, seed, kind: kind.value as CastMember['kind'], rooms: allowed, parts: changedParts(base, fields.value), ...(kind.value === 'sub' ? { match: types } : {}), ...(homeValue ? { home: homeValue } : {}), ...(kind.value === 'main' ? { ...(model.value ? { model: model.value } : {}), ...(instructions.value ? { instructions: instructions.value } : {}), idleMinutes: Number(idle.value), ...(account.value ? { account: account.value } : {}) } : {}) };
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
      h('label', {}, h('span', { text: tr('Modelo') }), model), models,
      h('label', {}, h('span', { text: tr('Instruções') }), instructions),
      h('label', {}, h('span', { text: tr('Ociosidade em minutos (0 = nunca)') }), idle),
      h('label', {}, h('span', { text: tr('Conta') }), account),
      h('label', {}, h('span', { text: tr('Aparência') }), lookSelect), fields.preview, roll, fields.groups, error,
      h('div', { class: 'ui-char__actions' }, save, cancel)));
  }
}
