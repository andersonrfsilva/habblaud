import { tr } from '../../../shared/i18n';
import { h } from './dom';

// Keep this aligned with ZeroClaw's personality::MAX_FILE_CHARS.
export const PERSONA_MAX_CHARS = 20_000;

export function validPersona(value: string): boolean {
  return Array.from(value).length <= PERSONA_MAX_CHARS;
}

export function telegramPeers(value: string): string[] | undefined {
  const peers = value.split(',').map((peer) => peer.trim());
  return peers.length >= 1 && peers.length <= 8 && peers.every((peer) => /^[0-9]{1,20}$/.test(peer))
    && new Set(peers).size === peers.length ? peers : undefined;
}

export function validTelegramToken(value: string): boolean {
  return /^[0-9]{6,12}:[A-Za-z0-9_-]{30,64}$/.test(value);
}

async function request(path: string, method = 'GET', body?: unknown): Promise<Record<string, unknown>> {
  const response = await fetch(path, { method,
    ...(method === 'GET' ? {} : { headers: { 'Content-Type': 'application/json' } }),
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const result = await response.json().catch(() => ({})) as Record<string, unknown>;
  if (!response.ok) throw new Error(typeof result.error === 'string' ? result.error : tr('Não foi possível salvar as configurações do agente.'));
  return result;
}

/** Settings always target the saved binding, never the pending selector value. */
export class NamedCastSettings {
  readonly el = h('div');
  private generation = 0;
  private busy = false;

  constructor(private readonly id: string | undefined, private readonly alias: string | undefined,
    private readonly lockBinding: (busy: boolean) => void) {}

  setBinding(selected: string): void {
    const generation = ++this.generation;
    this.el.replaceChildren();
    this.el.hidden = !selected;
    if (!selected) return;
    if (!this.id || selected !== this.alias) {
      this.el.append(h('p', { class: 'ui-rename__hint', text: tr('Salve o personagem para editar a persona e o Telegram deste agente.') }));
      return;
    }
    const field = (label: string, control: HTMLElement) => h('label', { class: 'ui-cast__field' },
      h('span', { class: 'ui-set__label', text: label }), control);
    const group = (title: string, ...children: HTMLElement[]) => h('section', { class: 'ui-set-group' }, h('h3', { text: title }), ...children);
    const path = `/api/cast/${encodeURIComponent(this.id)}`;
    const persona = h('textarea', { class: 'ui-rename__input ui-spawn__prompt' });
    const personaStatus = h('p', { role: 'status', class: 'ui-rename__hint', text: tr('Carregando…') });
    const personaError = h('p', { role: 'alert', class: 'ui-char__error' });
    const telegramStatus = h('p', { role: 'status', class: 'ui-rename__hint', text: tr('Carregando…') });
    const telegramError = h('p', { role: 'alert', class: 'ui-char__error' });
    const token = h('input', { type: 'password', class: 'ui-rename__input', attrs: { autocomplete: 'off', spellcheck: 'false', autocapitalize: 'none' } });
    const peers = h('input', { type: 'text', class: 'ui-rename__input', attrs: { autocomplete: 'off' } });
    let personaReady = false;
    let telegramReady = false;
    let linked = false;
    const current = () => this.generation === generation && this.el.isConnected;
    const sync = () => {
      persona.disabled = !personaReady || this.busy;
      savePersona.disabled = !personaReady || this.busy || !validPersona(persona.value);
      token.disabled = peers.disabled = link.disabled = !telegramReady || this.busy;
      unlink.disabled = !telegramReady || !linked || this.busy;
      retryPersona.hidden = personaReady || !personaError.textContent;
      retryTelegram.hidden = !telegramError.textContent;
      retryPersona.disabled = retryTelegram.disabled = this.busy;
    };
    const run = async (error: HTMLElement, action: () => Promise<void>) => {
      if (this.busy || !current()) return;
      error.textContent = '';
      this.busy = true;
      this.lockBinding(true);
      sync();
      try { await action(); }
      catch (failure) { if (current()) error.textContent = failure instanceof Error ? failure.message : tr('Não foi possível salvar as configurações do agente.'); }
      finally { this.busy = false; this.lockBinding(false); if (current()) sync(); }
    };
    const savePersona = h('button', { class: 'ui-btn', type: 'button', text: tr('Salvar persona'), on: { click: () => {
      if (!validPersona(persona.value)) {
        personaError.textContent = tr('A persona deve ter no máximo {0} caracteres.', [String(PERSONA_MAX_CHARS)]); return;
      }
      void run(personaError, async () => {
        await request(`${path}/persona`, 'PUT', { text: persona.value });
        if (current()) personaStatus.textContent = tr('Persona salva.');
      });
    } } });
    persona.addEventListener('input', () => {
      const problem = !validPersona(persona.value)
        ? tr('A persona deve ter no máximo {0} caracteres.', [String(PERSONA_MAX_CHARS)]) : '';
      persona.setCustomValidity(problem);
      persona.setAttribute('aria-invalid', String(!!problem));
      personaError.textContent = problem;
      personaStatus.textContent = '';
      sync();
    });
    const showTelegram = (result: Record<string, unknown>) => {
      if (typeof result.linked !== 'boolean' || typeof result.active !== 'boolean' || !Array.isArray(result.peers)
        || !result.peers.every((peer) => typeof peer === 'string')) throw new Error(tr('Resposta inválida do agente.'));
      linked = result.linked;
      peers.value = result.peers.join(', ');
      telegramStatus.textContent = !linked ? tr('Telegram não vinculado.') : result.active ? tr('Telegram vinculado e ativo.') : tr('Telegram vinculado; aguardando reinício.');
    };
    const link = h('button', { class: 'ui-btn', type: 'button', text: tr('Vincular Telegram'), on: { click: () => {
      const allowed = telegramPeers(peers.value);
      if (!validTelegramToken(token.value)) { telegramError.textContent = tr('Token do Telegram inválido.'); return; }
      if (!allowed) { telegramError.textContent = tr('Informe de 1 a 8 IDs numéricos únicos, com até 20 dígitos, separados por vírgula.'); return; }
      const secret = token.value;
      token.value = '';
      void run(telegramError, async () => {
        await request(`${path}/telegram`, 'PUT', { token: secret, peers: allowed });
        const result = await request(`${path}/telegram`);
        if (current()) showTelegram(result);
      });
    } } });
    const unlink = h('button', { class: 'ui-btn', type: 'button', text: tr('Desvincular Telegram'), on: { click: () => {
      if (!confirm(tr('Desvincular o Telegram deste agente?'))) return;
      token.value = '';
      void run(telegramError, async () => {
        await request(`${path}/telegram`, 'DELETE');
        const result = await request(`${path}/telegram`);
        if (current()) showTelegram(result);
      });
    } } });
    const retry = (error: HTMLElement, load: () => Promise<void>) => h('button', { class: 'ui-btn', type: 'button', text: tr('Tentar novamente'), on: { click: () => void run(error, load) } });
    const loadPersona = async () => {
      const result = await request(`${path}/persona`);
      if (!current()) return;
      if (typeof result.text !== 'string') throw new Error(tr('Resposta inválida do agente.'));
      persona.value = result.text;
      personaReady = true;
      personaStatus.textContent = '';
      personaError.textContent = '';
      sync();
    };
    const loadTelegram = async () => {
      const result = await request(`${path}/telegram`);
      if (!current()) return;
      showTelegram(result);
      telegramReady = true;
      telegramError.textContent = '';
      sync();
    };
    const retryPersona = retry(personaError, loadPersona);
    const retryTelegram = retry(telegramError, loadTelegram);
    this.el.append(group(tr('Persona'), field(tr('Persona (máximo de {0} caracteres)', [String(PERSONA_MAX_CHARS)]), persona), personaStatus, personaError,
      h('div', { class: 'ui-cast__actions' }, retryPersona, savePersona)),
    group(tr('Telegram'), telegramStatus, field(tr('Token do bot do Telegram'), token), field(tr('IDs permitidos (separados por vírgula)'), peers),
      h('p', { class: 'ui-rename__hint', text: tr('A aplicação pode levar cerca de um minuto e reinicia brevemente os personagens abertos do tenant.') }), telegramError,
      h('div', { class: 'ui-cast__actions' }, retryTelegram, link, unlink)));
    sync();
    // The caller attaches this element synchronously before these requests settle.
    void loadPersona().catch((failure: unknown) => { if (current()) { personaStatus.textContent = ''; personaError.textContent = failure instanceof Error ? failure.message : tr('Resposta inválida do agente.'); sync(); } });
    void loadTelegram().catch((failure: unknown) => { if (current()) { telegramStatus.textContent = ''; telegramError.textContent = failure instanceof Error ? failure.message : tr('Resposta inválida do agente.'); sync(); } });
  }
}
