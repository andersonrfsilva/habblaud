// Escolha de pasta do elenco no mesmo visual do "Novo agente" (spawn.ts): cartões com nome, caminho curto e quando foi
// usada, e "+ Outra pasta" para digitar um caminho que não está aberto no escritório. Uma pasta (Chamar) ou várias
// (salas permitidas do personagem).
import { tr } from '../../../shared/i18n';
import { h, setHidden } from './dom';
import { ago, shortPath, type FolderOption } from './spawn';

/** Último segmento do caminho, para o nome do cartão de uma pasta digitada. */
export function folderName(path: string): string {
  return path.split(/[\\/]+/).filter(Boolean).pop() ?? path;
}

/** Junta as pastas conhecidas com as que só o personagem conhece, sem repetir, as `first` no topo. */
export function folderOptions(known: readonly FolderOption[], extra: readonly string[], first: readonly string[] = []): FolderOption[] {
  const out = new Map<string, FolderOption>();
  for (const f of known) out.set(f.path, f);
  for (const path of extra) if (!out.has(path)) out.set(path, { path, name: folderName(path), lastAt: 0 });
  const rank = (p: string) => (first.includes(p) ? first.indexOf(p) : first.length);
  return [...out.values()].sort((a, b) => rank(a.path) - rank(b.path));
}

export class FolderPicker {
  readonly el: HTMLElement;
  private readonly list: HTMLElement;
  private readonly otherBtn: HTMLButtonElement;
  private readonly input: HTMLInputElement;
  private readonly addBtn: HTMLButtonElement;
  private readonly otherRow: HTMLElement;
  private readonly browser: HTMLElement;
  private options: FolderOption[] = [];
  private readonly chosen = new Set<string>();
  private typing = false;

  constructor(private readonly opts: { multi: boolean; free: boolean; label: string; badge?: (path: string) => string | undefined; onChange?: () => void; lockedHint?: string }) {
    this.list = h('div', { class: 'ui-spawn__folders', role: opts.multi ? 'group' : 'radiogroup', attrs: { 'aria-label': opts.label } });
    this.otherBtn = h('button', { class: 'ui-spawn__other', type: 'button', text: tr('+ Outra pasta'), on: { click: () => this.showOther(!this.typing) } });
    this.input = h('input', { class: 'ui-rename__input ui-spawn__path', type: 'text', attrs: { placeholder: tr('/caminho/da/pasta'), spellcheck: 'false', autocomplete: 'off' } });
    this.addBtn = h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: tr('Adicionar'), on: { click: () => this.addTyped() } });
    this.input.addEventListener('input', () => this.opts.onChange?.());
    this.input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && this.opts.multi) { e.preventDefault(); this.addTyped(); } });
    this.otherRow = h('div', { class: 'ui-folder-picker__other' }, this.input, opts.multi ? this.addBtn : null);
    const hint = h('p', { class: 'ui-rename__hint', text: opts.lockedHint ?? '' });
    this.browser = h('div', { class: 'ui-folder-picker__browser' });
    const browse = h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: tr('Procurar pasta…'), on: { click: () => void this.browse() } });
    const tools = opts.free ? h('div', { class: 'ui-folder-picker__tools' }, browse, this.otherBtn) : null;
    this.el = h('div', { class: 'ui-folder-picker' }, this.list, tools, opts.free ? this.otherRow : hint, this.browser);
    setHidden(this.otherRow, true);
  }

  set(options: FolderOption[], selected: readonly string[]): void {
    this.options = options;
    this.chosen.clear();
    for (const p of selected) this.chosen.add(p);
    if (!this.opts.multi && !this.chosen.size && options[0]) this.chosen.add(options[0].path);
    this.render();
    if (!options.length && this.opts.free && !this.opts.multi) this.showOther(true);
  }

  /** Pasta escolhida (uma só): a digitada, quando "+ Outra pasta" está aberto. */
  value(): string {
    return this.typing && !this.opts.multi ? this.input.value.trim() : ([...this.chosen][0] ?? '');
  }

  values(): string[] {
    return this.options.map((o) => o.path).filter((p) => this.chosen.has(p));
  }

  private render(): void {
    const now = Date.now();
    this.list.replaceChildren(...this.options.map((f) => {
      const on = this.chosen.has(f.path) && (this.opts.multi || !this.typing);
      const badge = this.opts.badge?.(f.path);
      const when = badge ?? ago(f.lastAt, now);
      return h('button', {
        class: `ui-spawn__folder${on ? ' is-on' : ''}`,
        type: 'button',
        role: this.opts.multi ? 'checkbox' : 'radio',
        attrs: { 'aria-checked': String(on), title: f.path },
        on: { click: () => this.pick(f.path) },
      },
      h('span', { class: 'ui-spawn__folder-name', text: f.name }),
      h('span', { class: 'ui-spawn__folder-path', text: shortPath(f.path) }),
      when ? h('span', { class: `ui-spawn__folder-when${badge ? ' ui-folder-picker__badge' : ''}`, text: when }) : null);
    }));
  }

  private pick(path: string): void {
    if (this.opts.multi) {
      if (this.chosen.has(path)) this.chosen.delete(path);
      else this.chosen.add(path);
    } else {
      this.chosen.clear();
      this.chosen.add(path);
      this.showOther(false);
    }
    this.render();
    this.opts.onChange?.();
  }

  private showOther(on: boolean): void {
    this.typing = on;
    setHidden(this.otherRow, !on);
    this.otherBtn.classList.toggle('is-on', on);
    if (on) this.input.focus();
    this.render();
    this.opts.onChange?.();
  }

  private addTyped(): void {
    const path = this.input.value.trim().replace(/[\\/]+$/, '');
    if (!/^(\/|[A-Za-z]:[\\/])/.test(path)) { this.input.setCustomValidity(tr('Use o caminho completo, começando por /')); this.input.reportValidity(); return; }
    this.input.setCustomValidity('');
    if (!this.options.some((o) => o.path === path)) this.options = [{ path, name: folderName(path), lastAt: 0 }, ...this.options];
    this.chosen.add(path);
    this.input.value = '';
    this.render();
    this.opts.onChange?.();
  }

  private async browse(path?: string): Promise<void> {
    this.browser.replaceChildren(h('p', { text: tr('Carregando…') }));
    try {
      const response = await fetch(`/api/cast/dirs${path ? `?path=${encodeURIComponent(path)}` : ''}`);
      if (!response.ok) throw new Error();
      const data = await response.json() as { path: string; parent?: string; dirs: { name: string; path: string }[] };
      const bar = h('div', { class: 'ui-folder-picker__bar' },
        h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: '↑', attrs: { title: tr('Voltar'), 'aria-label': tr('Voltar'), disabled: !data.parent }, on: { click: () => void this.browse(data.parent) } }),
        h('span', { class: 'ui-folder-picker__path', text: data.path ? shortPath(data.path) : tr('Pastas'), attrs: { title: data.path } }));
      const list = h('ul', { class: 'ui-files__list' }, ...data.dirs.map((dir) => h('li', {},
        h('button', { class: 'ui-folder-picker__dir', type: 'button', on: { click: () => void this.browse(dir.path) } }, h('span', { text: '📁', attrs: { 'aria-hidden': 'true' } }), h('span', { text: dir.name })))));
      if (!data.dirs.length) list.append(h('li', { class: 'ui-rename__hint', text: tr('Nenhuma subpasta.') }));
      const use = data.path ? h('button', { class: 'ui-btn ui-btn--sm ui-btn--primary', type: 'button', text: tr('Usar esta pasta'), on: { click: () => {
        if (!this.options.some((o) => o.path === data.path)) this.options = [{ path: data.path, name: folderName(data.path), lastAt: 0 }, ...this.options];
        if (!this.opts.multi) this.chosen.clear();
        this.chosen.add(data.path);
        this.typing = false;
        setHidden(this.otherRow, true);
        this.browser.replaceChildren();
        this.render();
        this.opts.onChange?.();
      } } }) : null;
      const close = h('button', { class: 'ui-btn ui-btn--sm', type: 'button', text: tr('Cancelar'), on: { click: () => this.browser.replaceChildren() } });
      this.browser.replaceChildren(bar, list, h('div', { class: 'ui-folder-picker__foot' }, close, use));
    } catch {
      this.browser.replaceChildren(h('p', { role: 'alert', text: tr('Não foi possível abrir a pasta.') }),
        h('button', { class: 'ui-btn', type: 'button', text: tr('Voltar'), on: { click: () => void this.browse() } }));
    }
  }
}
