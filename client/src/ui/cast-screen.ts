// Gestão do elenco: membros existem independentemente das sessões presentes.
import type { AgentInfo, CastJobInfo, CastMember } from '../../../shared/types';
import { tr } from '../../../shared/i18n';
import { appearanceFromSeed } from '../art';
import { AppearanceFields } from './appearance-fields';
import { changedParts, validateCast } from './character-model';
import type { UiContext } from './context';
import { h, iconButton } from './dom';
import { ICONS } from './icons';
import { createAvatar } from './avatar';
import { FolderPicker, folderOptions } from './folder-picker';
import { shortPath, spawnFolders, type FolderOption } from './spawn';
import { modelGroups } from './format';

const HOST_OFF = (): string => tr('auxiliar do host desligado (npm run codex:bridge)');
const active = (jobs: readonly CastJobInfo[], cast: string): CastJobInfo | undefined =>
  jobs.find((job) => job.cast === cast && (job.status === 'queued' || job.status === 'sent' || job.status === 'waiting'));

export class CastScreen {
  readonly el: HTMLDialogElement;
  private readonly content: HTMLElement;
  private members: CastMember[] = [];
  /** Pastas que o escritório conhece (histórico de sessões + salas), da mais recente para a mais antiga. */
  private folders: FolderOption[] = [];
  private readonly recentTypes = new Set<string>();
  /** Modelos do omp vistos nas sessões (a extensão manda a lista no início e no batimento); ficam para a ficha. */
  private readonly knownModels = new Set<string>();

  constructor(private readonly ctx: UiContext) {
    this.content = h('div', { class: 'ui-dialog__body ui-cast__content' });
    const close = iconButton(ICONS.close, tr('Fechar (Esc)'), () => this.el.close(), 'ui-icon-btn--sm');
    this.el = h('dialog', { class: 'ui-dialog ui-cast', attrs: { 'aria-labelledby': 'ui-cast-title' } }, h('div', { class: 'ui-dialog__head' }, h('h2', { text: tr('Elenco'), attrs: { id: 'ui-cast-title' } }), close), this.content);
    ctx.root.addEventListener('habblaud:cast-create', (event) => void this.open((event as CustomEvent<AgentInfo>).detail));
    ctx.store.on('snapshot', (snapshot) => {
      for (const agent of snapshot.agents) {
        for (const m of agent.models ?? []) this.knownModels.add(m);
        if (!agent.agentType) continue;
        this.recentTypes.delete(agent.agentType);
        this.recentTypes.add(agent.agentType);
        if (this.recentTypes.size > 20) this.recentTypes.delete(this.recentTypes.values().next().value!);
      }
    });
  }

  private async loadFolders(): Promise<void> {
    this.folders = folderOptions([], spawnFolders(this.ctx.store.snapshot));
    try {
      const response = await fetch('/api/cast/folders');
      if (response.ok) this.folders = folderOptions(((await response.json()) as { folders?: FolderOption[] }).folders ?? [], this.folders.map((f) => f.path));
    } catch { /* As salas abertas continuam disponíveis. */ }
  }

  async open(agent?: AgentInfo): Promise<void> {
    if (!this.el.open) this.el.showModal();
    this.content.replaceChildren(h('p', { text: tr('Carregando…') }));
    const [result] = await Promise.all([this.ctx.store.castList(), this.loadFolders()]);
    if ('error' in result) { this.content.replaceChildren(h('p', { role: 'alert', text: result.error })); return; }
    this.members = result.members;
    if (agent) this.edit(undefined, agent);
    else this.showList();
  }

  private showList(): void {
    const add = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: tr('Criar personagem'), on: { click: () => this.edit() } });
    const list = h('div', { class: 'ui-cast__list' });
    const calls: Array<{ member: CastMember; button: HTMLButtonElement | null; status: HTMLElement }> = [];
    const reason = h('p', { class: 'ui-rename__hint', role: 'status' });
    for (const member of this.members) {
      const label = member.kind === 'main' ? tr('Principal') : member.kind === 'advisor' ? tr('Advisor') : tr('Subagente');
      const where = member.rooms.length ? member.rooms.map((p) => shortPath(p)).join(', ') : tr('qualquer sala');
      const edit = h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: tr('Editar'), on: { click: () => this.edit(member) } });
      const call = member.kind === 'main' ? h('button', { class: 'ui-btn ui-btn--sm ui-btn--primary', type: 'button', text: tr('Chamar'), on: { click: () => void this.call(member.id) } }) : null;
      const status = h('small', { text: tr('Na reserva') });
      if (call) call.disabled = true;
      calls.push({ member, button: call, status });
      const del = h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: tr('Apagar'), on: { click: () => void this.remove(member) } });
      list.append(h('div', { class: 'ui-cast__row' }, createAvatar({ ...member, kind: member.kind === 'sub' ? 'sub' : 'main' }, 'sm'),
        h('div', { class: 'ui-cast__who' }, h('strong', { text: member.name }), h('small', { text: `${label} · ${where}` }), status),
        h('div', { class: 'ui-cast__row-actions' }, call, edit, del)));
    }
    this.content.replaceChildren(add, reason, list);
    const watch = async () => {
      if (!this.el.open || !this.content.contains(list)) return;
      const jobs = await this.ctx.store.castJobs();
      if (!this.el.open || !this.content.contains(list)) return;
      reason.textContent = jobs.available ? '' : HOST_OFF();
      for (const { member, button, status } of calls) {
        const busy = !!active(jobs.jobs, member.id);
        const agent = this.ctx.store.snapshot?.agents.find((a) => a.cast === member.id && a.status !== 'offline');
        if (button) {
          button.disabled = !jobs.available || busy || !!agent;
          button.textContent = busy ? tr('chamando…') : tr('Chamar');
        }
        status.textContent = busy ? tr('chamando…') : agent ? tr('Trabalhando em {0}', [shortPath(agent.roomId)]) : tr('Na reserva');
      }
      setTimeout(() => void watch(), 2000);
    };
    void watch();
  }

  async call(id: string): Promise<void> {
    if (!this.el.open) this.el.showModal();
    this.content.replaceChildren(h('p', { text: tr('Carregando…') }));
    const [list, jobs] = await Promise.all([this.ctx.store.castList(), this.ctx.store.castJobs(), this.loadFolders()]);
    if ('error' in list) { this.content.replaceChildren(h('p', { role: 'alert', text: list.error })); return; }
    const member = list.members.find((m) => m.id === id && m.kind === 'main');
    if (!member) return;
    const restricted = member.rooms.length > 0;
    const previous = Object.keys(member.last ?? {});
    const first = [...(member.home ? [member.home] : []), ...previous];
    // Com salas, só elas (mesmo fechadas); sem salas, as conhecidas, as que já tiveram sessão dele e qualquer outra digitada.
    const options = restricted
      ? folderOptions(this.folders.filter((f) => member.rooms.includes(f.path)), member.rooms, first)
      : folderOptions(this.folders, previous, first);
    let mode: 'new' | 'resume' = 'new';
    let sent = false;
    const picker = new FolderPicker({
      multi: false,
      free: !restricted,
      label: tr('Sala'),
      badge: (path) => (path === member.home ? tr('casa') : member.last?.[path] ? tr('tem sessão') : undefined),
      onChange: () => sync(),
      lockedHint: tr('Só as salas permitidas deste personagem. Para outra pasta, edite o personagem.'),
    });
    const segResume = h('button', { class: 'ui-seg__opt', type: 'button', role: 'radio', text: tr('Continuar'), on: { click: () => { mode = 'resume'; sync(); } } });
    const segNew = h('button', { class: 'ui-seg__opt', type: 'button', role: 'radio', text: tr('Nova'), on: { click: () => { mode = 'new'; sync(); } } });
    const prompt = h('textarea', { class: 'ui-rename__input ui-spawn__prompt', attrs: { id: 'ui-cast-prompt', maxlength: '8000', placeholder: tr('O que ele deve fazer ao chegar (opcional)') } });
    const status = h('p', { class: 'ui-rename__hint', role: 'status' });
    const start = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: tr('Chamar'), on: { click: () => void (async () => {
      start.disabled = true;
      const failure = await this.ctx.store.startCast(id, picker.value(), mode, prompt.value);
      if (failure) { status.textContent = failure; start.disabled = false; return; }
      sent = true;
      start.textContent = tr('chamando…');
      status.textContent = tr('Abrindo o omp no host…');
    })() } });
    const back = h('button', { class: 'ui-btn', type: 'button', text: tr('Voltar'), on: { click: () => void this.open() } });
    let available = jobs.available;
    let calling = !!active(jobs.jobs, id);
    const sync = () => {
      const canResume = !!member.last?.[picker.value()];
      if (!canResume) mode = 'new';
      segResume.disabled = !canResume;
      segResume.title = canResume ? '' : tr('Sem sessão anterior nesta sala');
      segResume.setAttribute('aria-checked', String(mode === 'resume'));
      segNew.setAttribute('aria-checked', String(mode === 'new'));
      start.disabled = !available || calling || !picker.value();
    };
    picker.set(options, first.length ? [first[0]] : []);
    if (member.last?.[picker.value()]) mode = 'resume';
    sync();
    if (!available) status.textContent = HOST_OFF();
    else if (!calling) status.textContent = jobs.jobs.filter((job) => job.cast === id && job.status === 'failed').at(-1)?.error ?? '';
    this.content.replaceChildren(h('div', { class: 'ui-cast__call' },
      h('div', { class: 'ui-cast__identity' }, createAvatar({ ...member, kind: 'main' }, 'sm'), h('h3', { class: 'ui-cast__title', text: tr('Chamar {0}', [member.name]) })),
      h('p', { class: 'ui-set__label', text: tr('Sala') }), picker.el,
      h('p', { class: 'ui-set__label', text: tr('Sessão') }),
      h('div', { class: 'ui-seg', role: 'radiogroup', attrs: { 'aria-label': tr('Sessão') } }, segResume, segNew),
      h('label', { class: 'ui-set__label', attrs: { for: 'ui-cast-prompt' }, text: tr('Pedido') }), prompt,
      status,
      h('div', { class: 'ui-cast__actions' }, back, start)));
    const watch = async () => {
      if (!this.el.open || !this.content.contains(start)) return;
      const latest = await this.ctx.store.castJobs();
      if (!this.el.open || !this.content.contains(start)) return;
      available = latest.available;
      calling = !!active(latest.jobs, id);
      const failed = latest.jobs.filter((job) => job.cast === id && job.status === 'failed').at(-1);
      // Chegou: o personagem já está andando para a sala, a janela sai da frente.
      if (sent && !calling && latest.jobs.some((job) => job.cast === id && job.op === 'start' && job.status === 'done')) { this.el.close(); return; }
      start.textContent = calling ? tr('chamando…') : tr('Chamar');
      status.textContent = !available ? HOST_OFF() : calling ? tr('Abrindo o omp no host…') : failed?.error ?? '';
      sync();
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
    const field = (label: string, control: HTMLElement) => h('label', { class: 'ui-cast__field' }, h('span', { class: 'ui-set__label', text: label }), control);
    const name = h('input', { class: 'ui-rename__input', type: 'text', attrs: { maxlength: '24', required: '' } });
    name.value = member?.name ?? agent?.name ?? '';
    const kind = h('select', { class: 'ui-rename__input' });
    for (const [value, label] of [['main', tr('Principal')], ['advisor', tr('Advisor')], ['sub', tr('Subagente')]]) kind.append(h('option', { text: label, attrs: { value } }));
    kind.value = member?.kind ?? 'main';
    const match = h('input', { class: 'ui-rename__input', type: 'text', attrs: { list: 'ui-cast-types' } });
    match.value = member?.match?.join(', ') ?? '';
    const suggestions = h('datalist', { attrs: { id: 'ui-cast-types' } });
    for (const type of this.recentTypes) suggestions.append(h('option', { attrs: { value: type } }));
    const initialRooms = member?.rooms ?? (agent ? [agent.roomId] : []);
    const home = h('select', { class: 'ui-rename__input' });
    const syncHome = () => {
      const keep = home.value;
      const chosen = rooms.values();
      home.replaceChildren(h('option', { text: tr('Nenhuma'), attrs: { value: '' } }), ...chosen.map((p) => h('option', { text: shortPath(p), attrs: { value: p } })));
      home.value = chosen.includes(keep) ? keep : '';
    };
    const rooms = new FolderPicker({ multi: true, free: true, label: tr('Salas permitidas'), onChange: () => syncHome() });
    rooms.set(folderOptions(this.folders, initialRooms, initialRooms), initialRooms);
    syncHome();
    home.value = member?.home ?? '';
    // Com a lista de modelos do omp: seletor agrupado como o da gaveta; sem ela (nenhuma sessão com a extensão), texto.
    const current = member?.model ?? '';
    const known = [...this.knownModels];
    let model: HTMLInputElement | HTMLSelectElement;
    if (known.length) {
      model = h('select', { class: 'ui-rename__input' });
      model.append(h('option', { text: tr('Padrão do omp'), attrs: { value: '' } }));
      if (current && !known.includes(current)) model.append(h('option', { text: current, attrs: { value: current } }));
      for (const g of modelGroups(known, current)) {
        const group = h('optgroup', { attrs: { label: g.label } });
        group.append(...g.options.map((o) => h('option', { text: o.label, attrs: { value: o.value } })));
        model.append(group);
      }
    } else {
      model = h('input', { class: 'ui-rename__input', type: 'text', attrs: { maxlength: '200', placeholder: tr('provedor/modelo (vazio = padrão do omp)') } });
    }
    model.value = current;
    const instructions = h('textarea', { class: 'ui-rename__input ui-spawn__prompt', attrs: { maxlength: '8000' } });
    instructions.value = member?.instructions ?? '';
    const idle = h('input', { class: 'ui-rename__input', type: 'number', attrs: { min: '0', max: '1440', step: '1' } });
    idle.value = String(member?.idleMinutes ?? 30);
    const account = h('select', { class: 'ui-rename__input' });
    account.append(h('option', { text: tr('Padrão'), attrs: { value: '' } }));
    for (const item of this.ctx.store.snapshot?.accounts ?? []) if (item.provider === 'omp') account.append(h('option', { text: item.name, attrs: { value: item.id } }));
    account.value = member?.account ?? '';
    const error = h('p', { role: 'alert', class: 'ui-char__error' });
    const roll = h('button', { class: 'ui-btn', type: 'button', text: tr('Sortear'), on: { click: () => {
      seed = crypto.getRandomValues(new Uint32Array(1))[0];
      base = appearanceFromSeed(seed, { look }); fields.set({ ...base });
    } } });
    const lookSelect = h('select', { class: 'ui-rename__input' });
    lookSelect.append(h('option', { text: tr('Feminino'), attrs: { value: 'f' } }), h('option', { text: tr('Masculino'), attrs: { value: 'm' } }));
    lookSelect.value = look;
    lookSelect.addEventListener('change', () => { look = lookSelect.value === 'm' ? 'm' : 'f'; base = appearanceFromSeed(seed, { look }); fields.set({ ...base }); });
    const save = h('button', { class: 'ui-btn ui-btn--primary', type: 'button', text: tr('Salvar'), on: { click: () => void (async () => {
      const allowed = rooms.values();
      const types = match.value.split(',').map((v) => v.trim()).filter(Boolean);
      const homeValue = kind.value === 'main' ? home.value : '';
      const problem = validateCast(name.value, allowed, homeValue, types);
      if (problem || !fields.value) { error.textContent = problem ?? tr('Aparência inválida.'); return; }
      const body: Omit<CastMember, 'id'> = { name: name.value.trim(), look, seed, kind: kind.value as CastMember['kind'], rooms: allowed, parts: changedParts(base, fields.value), ...(kind.value === 'sub' ? { match: types } : {}), ...(homeValue ? { home: homeValue } : {}), ...(kind.value === 'main' ? { ...(model.value ? { model: model.value } : {}), ...(instructions.value ? { instructions: instructions.value } : {}), idleMinutes: Number(idle.value), ...(account.value ? { account: account.value } : {}) } : {}) };
      save.disabled = true;
      const failure = await this.ctx.store.saveCast(member ? { ...body, id: member.id } : body, member ? undefined : agent?.id);
      save.disabled = false;
      if (failure) { error.textContent = failure; return; }
      await this.open();
    })() } });
    const cancel = h('button', { class: 'ui-btn', type: 'button', text: tr('Cancelar'), on: { click: () => this.showList() } });
    const group = (title: string, ...children: HTMLElement[]) => h('section', { class: 'ui-set-group' }, h('h3', { text: title }), ...children);
    const execution = group(tr('Execução'), field(tr('Modelo'), model), field(tr('Instruções'), instructions),
      field(tr('Ociosidade em minutos (0 = nunca)'), idle), field(tr('Conta'), account));
    const sub = group(tr('Subagente'), field(tr('Tipos de subagente (separados por vírgula)'), match), suggestions);
    const mainHome = field(tr('Sala principal'), home);
    const syncKind = () => {
      execution.hidden = kind.value !== 'main';
      mainHome.hidden = kind.value !== 'main';
      sub.hidden = kind.value !== 'sub';
    };
    kind.addEventListener('change', syncKind);
    syncKind();
    this.content.replaceChildren(h('div', { class: 'ui-cast__form' },
      group(tr('Identidade'), field(tr('Nome'), name), field(tr('Tipo'), kind),
        field(tr('Aparência'), lookSelect), fields.preview, roll, fields.groups),
      group(tr('Salas'), h('div', { class: 'ui-cast__field' }, h('p', { class: 'ui-set__label', text: tr('Salas permitidas') }), rooms.el,
        h('p', { class: 'ui-rename__hint', text: tr('Nenhuma marcada = trabalha em qualquer sala.') })), mainHome),
      execution, sub, error, h('div', { class: 'ui-cast__actions' }, cancel, save)));
  }
}
