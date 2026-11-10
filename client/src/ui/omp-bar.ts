// Faixa do omp no rodapé do terminal: a barra do contexto (tokens em uso / janela do modelo, como no rodapé do omp) e
// os botões STOP, /new, /shake (elide e thinking) e /resume (escolhe a conversa no histórico), mais o olho do advisor
// (liga/desliga o /advisor da sessão; o TerminalPanel o põe na barra de cima, ao lado da estrela). Vão pela fila de
// controle (POST /api/messages/control {command}, {stop: true} ou {resume}); a extensão do omp digita os comandos na
// tela dele (só com o agente livre e o editor vazio), interrompe o turno no STOP (só com o agente trabalhando) e
// confirma ou recusa com o motivo.
import type { OmpCommand } from '../../../shared/omp-events';
import type { AgentInfo, OmpControl } from '../../../shared/types';
import { OFFLINE_ERROR } from './composer-model';
import type { UiContext } from './context';
import { h, iconButton, setAttr, setHidden, setStyleVar, setText, setTitle, setVariant } from './dom';
import { formatTokens, usageLevel } from './format';
import { tr } from '../../../shared/i18n';
import { pixelIcon } from './icons';
import { runtimeAllowsContext, runtimeAllowsControl, runtimeAllowsOmpTools } from './provider';

export function barActions(agent: AgentInfo | undefined, ready: boolean, mock: boolean) {
  const main = !!agent && agent.kind === 'main';
  const live = main && agent.status !== 'offline' && agent.status !== 'done';
  const commands = live && ready && !mock && runtimeAllowsControl(agent.provider);
  const omp = commands && runtimeAllowsOmpTools(agent.provider);
  return {
    new: commands,
    stop: commands,
    canStop: commands && (agent.status === 'working' || agent.status === 'waiting'),
    shake: omp,
    advisor: omp,
    resume: omp,
    context: main && runtimeAllowsContext(agent.provider) ? contextView(agent.context) : undefined,
  };
}

export function newSessionAgent(agents: readonly AgentInfo[], previous: AgentInfo): AgentInfo | undefined {
  return agents.find((agent) => agent.provider === previous.provider && agent.kind === 'main' &&
    agent.account === previous.account && agent.roomId === previous.roomId &&
    agent.cast === previous.cast && (agent.id === previous.id || !!previous.cast) &&
    agent.sessionId !== previous.sessionId && agent.status !== 'offline' && agent.status !== 'done');
}

/** Rótulo e dica de cada botão, na ordem da faixa. */
const BUTTONS: ReadonlyArray<[OmpCommand, string]> = [
  ['new', tr('Começa uma conversa nova no omp (a atual fica no histórico)')],
  ['shake', tr('Roda /shake elide e /shake thinking: troca resultados de ferramentas e blocos grandes antigos por referências recuperáveis (artifact://) e tira do contexto o raciocínio das respostas antigas')],
];
/** Rótulo do botão que interrompe o turno (não é um comando digitado no omp). */
const STOP = 'STOP';
/** /resume: quantas vezes (a cada 0,5 s) procura a sessão retomada no escritório antes de dar como falha. */
const RESUME_POLLS = 30;
/** /resume com a mensagem já falhada: quantas vezes ainda espera a sessão aparecer (a falha pode ser só a saída da anterior). */
const RESUME_GRACE_POLLS = 6;

const EYE_OPEN = pixelIcon([
  '............',
  '...######...',
  '..#......#..',
  '.#...##...#.',
  '#...####...#',
  '#...####...#',
  '.#...##...#.',
  '..#......#..',
  '...######...',
  '............',
]);
/** Desligado (ou não sabido): o mesmo olho riscado na diagonal. */
const EYE_SHUT = pixelIcon([
  '#...........',
  '.#.######...',
  '..#......#..',
  '.#..#.#...#.',
  '#...##.#...#',
  '#...###....#',
  '.#...###..#.',
  '..#......#..',
  '...######.#.',
  '...........#',
]);

/** Advisor do omp: ligado (true), desligado (false) ou ainda não sabido (undefined; a extensão não lê o estado). */
export function advisorNext(on: boolean | undefined): 'advisorOn' | 'advisorOff' {
  return on ? 'advisorOff' : 'advisorOn';
}

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
  /** Aviso dos comandos (/new, /shake, /resume...): o TerminalPanel o põe na linha de estado da sessão. */
  readonly statusEl: HTMLElement;
  private stopBtn: HTMLButtonElement;
  private commandButtons = new Map<string, HTMLButtonElement>();
  /** Olho do advisor: fica na barra de cima do terminal, ao lado da estrela (o TerminalPanel o coloca lá). */
  readonly eyeBtn: HTMLButtonElement;
  /** Último estado do advisor confirmado pelo omp, por agente (a extensão não lê o estado real). */
  private advisor = new Map<string, boolean>();
  private eyeShown: boolean | undefined;
  private agentId: string | null = null;
  private busy = false;
  /** Abre o histórico para escolher a conversa a retomar neste agente (ligado pelo index.ts). */
  onPickResume?: (agentId: string) => void;
  /** Abre o terminal na sessão retomada (ligado pelo TerminalPanel). */
  onResumed?: (agentId: string) => void;
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
    this.eyeBtn = iconButton(EYE_SHUT, tr('Advisor do omp'), () => void this.toggleAdvisor(), 'ui-icon-btn--sm');
    setHidden(this.eyeBtn, true);
    this.buttons = h(
      'div',
      { class: 'ui-term__cmds', role: 'group', attrs: { 'aria-label': tr('Comandos do omp') } },
      this.stopBtn,
      ...BUTTONS.map(([cmd, hint]) => {
        const button = h('button', { class: 'ui-btn ui-btn--sm ui-term__cmd', type: 'button', text: `/${cmd}`, title: hint, on: { click: () => void this.run(cmd) } });
        this.commandButtons.set(cmd, button);
        return button;
      }),
      h('button', {
        class: 'ui-btn ui-btn--sm ui-term__cmd',
        type: 'button',
        text: '/resume',
        title: tr('Retoma outra conversa do omp neste agente: escolha no histórico (a atual continua lá)'),
        on: { click: () => this.agentId && this.onPickResume?.(this.agentId) },
      }),
    );
    this.commandButtons.set('resume', this.buttons.querySelector('button:last-child')!);
    this.statusEl = h('span', { class: 'ui-term__cmd-status', role: 'status', hidden: true });
    this.el = h('div', { class: 'ui-term__omp', hidden: true }, this.meter, this.buttons);
  }

  /** `ready`: a caixa de mensagem do agente está pronta (a extensão do omp está conectada). */
  render(agent: AgentInfo | undefined, ready: boolean): void {
    const actions = barActions(agent, ready, this.ctx.store.mock);
    if (agent?.id !== this.agentId) {
      this.agentId = agent?.id ?? null;
      this.say('');
    }
    const view = actions.context;
    const commands = actions.new;
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
    setHidden(this.commandButtons.get('shake')!, !actions.shake);
    setHidden(this.commandButtons.get('resume')!, !actions.resume);
    setTitle(this.commandButtons.get('new')!, agent?.provider === 'zeroclaw' ? tr('Começa uma conversa nova no ZeroClaw (a atual fica no histórico)') : BUTTONS[0][1]);
    setTitle(this.stopBtn, agent?.provider === 'zeroclaw' ? tr('Interrompe o turno atual no ZeroClaw') : tr('Interrompe o que o agente está fazendo agora (como o Esc no omp)'));
    setAttr(this.buttons, 'aria-label', agent?.provider === 'zeroclaw' ? tr('Comandos do ZeroClaw') : tr('Comandos do omp'));
    for (const b of this.buttons.querySelectorAll('button')) b.disabled = this.busy;
    setHidden(this.eyeBtn, !actions.advisor);
    this.eyeBtn.disabled = this.busy;
    const on = this.agentId ? this.advisor.get(this.agentId) : undefined;
    if (on !== this.eyeShown) {
      this.eyeShown = on;
      this.eyeBtn.innerHTML = on ? EYE_OPEN : EYE_SHUT;
    }
    setAttr(this.eyeBtn, 'aria-pressed', on === undefined ? null : String(on));
    setAttr(this.eyeBtn, 'aria-label', tr('Advisor do omp'));
    setTitle(
      this.eyeBtn,
      on === true
        ? tr('Advisor do omp ligado nesta sessão. Clique para desligar (/advisor off)')
        : on === false
          ? tr('Advisor do omp desligado nesta sessão. Clique para ligar (/advisor on)')
          : tr('Liga o advisor do omp nesta sessão (/advisor on). O Habblaud não vê se ele já está ligado; o olho mostra o último comando enviado daqui'),
    );
    // Só há o que parar num turno (ou esperando você no meio dele).
    this.stopBtn.disabled = this.busy || !actions.canStop;
  }

  private say(text: string, error = false): void {
    clearTimeout(this.timer);
    setText(this.statusEl, text);
    setHidden(this.statusEl, !text);
    this.statusEl.classList.toggle('is-error', error);
    if (text && !error) this.timer = setTimeout(() => setHidden(this.statusEl, true), 5_000);
  }

  /** Retoma a sessão `sessionId` no agente `agentId` (`/resume <id>` no omp), depois de confirmar. */
  resume(agentId: string, sessionId: string, title: string): void {
    if (!window.confirm(tr('Retomar “{0}” neste agente? A conversa atual continua no histórico.', [title]))) return;
    // O terminal acabou de abrir neste agente: o render seguinte não apaga o andamento.
    this.agentId = agentId;
    void this.send({ resume: sessionId }, '/resume');
  }

  /** Agente da sessão retomada já no escritório (a sessão vira outro agente: os ids são por sessão). */
  private resumedAgent(sessionId: string): AgentInfo | undefined {
    return this.ctx.store.snapshot?.agents.find((a) => a.provider === 'omp' && a.kind === 'main' && a.sessionId === sessionId && a.status !== 'offline');
  }

  private run(command: OmpCommand | 'stop'): void {
    const agent = this.agentId ? this.ctx.agent(this.agentId) : undefined;
    const actions = barActions(agent, !!agent?.canMessage, this.ctx.store.mock);
    if (command === 'stop' ? !actions.canStop : command === 'new' ? !actions.new : !actions.shake) return;
    if (command === 'new' && !window.confirm(agent?.provider === 'zeroclaw' ? tr('Começar uma conversa nova no ZeroClaw? A atual continua no histórico.') : tr('Começar uma conversa nova no omp? A atual continua no histórico (/resume).'))) return;
    void this.send(command === 'stop' ? { stop: true } : { command }, command === 'stop' ? STOP : `/${command}`);
  }

  private async toggleAdvisor(): Promise<void> {
    const id = this.agentId;
    if (!id) return;
    const next = advisorNext(this.advisor.get(id));
    if (await this.send({ command: next }, next === 'advisorOn' ? '/advisor on' : '/advisor off')) this.advisor.set(id, next === 'advisorOn');
    this.ctx.invalidate();
  }

  /** Manda o pedido e acompanha até a extensão confirmar (ou recusar, com o motivo). */
  private async send(change: OmpControl, label: string): Promise<boolean> {
    const agentId = this.agentId;
    if (!agentId || this.busy) return false;
    const previous = this.ctx.agent(agentId);
    if (!previous || !runtimeAllowsControl(previous.provider)) return false;
    if (previous.provider === 'zeroclaw' && !(change.stop === true || change.command === 'new')) return false;
    const guarded = { ...change, expectedSessionId: previous.sessionId };
    const zeroclaw = previous.provider === 'zeroclaw';
    const changingSession = zeroclaw && change.command === 'new';
    this.busy = true;
    this.ctx.invalidate();
    this.say(tr('Enviando {0}…', [label]));
    try {
      const r = await this.ctx.store.controlAgent(agentId, guarded);
      if ('error' in r) {
        this.say(r.error, true);
        return false;
      }
      // /resume: a extensão só confirma que digitou; deu certo quando a sessão pedida aparece no escritório (o omp mostra
      // o erro na tela dele e não avisa ninguém). A mensagem pode falhar só porque a conversa anterior saiu do
      // escritório antes da confirmação: o veredito espera a sessão nova por mais um pouco.
      const target = change.resume;
      let failedAt: number | undefined;
      for (let i = 0; i < (target ? RESUME_POLLS : 20); i++) {
        // O cliente compila para antes do ES2024 (sem Promise.withResolvers).
        await new Promise((ok) => setTimeout(ok, 500));
        if (changingSession) {
          const next = newSessionAgent(this.ctx.store.snapshot?.agents ?? [], previous);
          if (next) {
            this.agentId = next.id;
            this.onResumed?.(next.id);
            this.say(tr('Conversa nova aberta.'));
            return true;
          }
        }
        const resumed = target ? this.resumedAgent(target) : undefined;
        if (resumed) {
          this.agentId = resumed.id;
          this.onResumed?.(resumed.id);
          this.say(tr('Conversa retomada.'));
          return false;
        }
        const m = await this.ctx.store.messageStatus(r.message.id);
        if (target) {
          if (m?.status === 'failed') failedAt ??= i;
          if (failedAt !== undefined && i - failedAt >= RESUME_GRACE_POLLS) {
            this.say(m?.error ?? tr('o omp não rodou o comando'), true);
            return false;
          }
          continue;
        }
        if (((!zeroclaw && !m) || m?.status === 'delivered') && !changingSession) {
          this.say(zeroclaw ? tr('{0} enviado ao ZeroClaw.', [label]) : tr('{0} enviado ao omp.', [label]));
          return true;
        }
        if (m?.status === 'failed') {
          this.say(m.error ?? (zeroclaw ? tr('O ZeroClaw não confirmou o comando.') : tr('o omp não rodou o comando')), true);
          return false;
        }
      }
      this.say(zeroclaw ? tr('O ZeroClaw ainda não confirmou o comando. Confira a conversa antes de tentar novamente.') : target ? tr('O omp não retomou a conversa: veja o motivo na tela do omp.') : tr('O omp ainda não confirmou o comando.'), true);
      return false;
    } catch {
      this.say(OFFLINE_ERROR, true);
      return false;
    } finally {
      this.busy = false;
      this.ctx.invalidate();
    }
  }
}
