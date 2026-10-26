# Plan 028: Botão no canto inferior direito pausa e retoma as animações do escritório

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4cdae9e..HEAD -- client/src/world/index.ts client/src/world/api.ts client/src/ui/index.ts client/src/ui/overlays.ts client/src/ui/styles.css client/src/ui/timelapse-icons.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `4cdae9e`, 2026-10-26

## Why this matters

O dono quer um botão no canto inferior direito da tela que congela e retoma as animações do escritório (personagens
andando, telas, luzes, efeitos). Pausado, o escritório vira uma imagem parada: a câmera (arrastar, zoom), o clique
para selecionar e os painéis continuam funcionando. Ao retomar, a simulação pula para o presente (como quando a aba
volta do segundo plano), sem replay acelerado.

Decisões já tomadas (não reabra):
- O estado **não** é salvo: recarregar a página volta a tocar. Sem preferência nova, sem atalho de teclado.
- Durante o timelapse (classe `is-replay` no root) e na cidade do World (classe `is-city` no root) o botão some: o
  timelapse tem play/pausa próprio e a cidade é outra cena.
- Pausado, os sons do ambiente param.

## Current state

- `client/src/world/index.ts` — `createWorld` monta o laço do escritório.
  - Variáveis do laço declaradas perto da linha 82 (`timer`, `hiddenAt`, `suspended`, `suspendedAt`, `playback`,
    `worldNow`, `lastTs`...). Confira com `sed -n 70,100p client/src/world/index.ts`.
  - `loop` (linhas 328-399). Os pontos que importam:
    ```ts
    const cap = frameCapFor({
      uiCap: frameCap,
      active: cameraMoved || camera.animating || !!playback || performance.now() - lastInputAt < ACTIVE_GRACE_MS,
      focused: document.hasFocus(),
      powerSave: !!options.powerSave,
      motion: renderer.motion,
    });
    ...
    const k = playback ? Math.max(0, playback.scale()) : 1;
    const dt = realDt * k;
    if (playback) worldNow += dt * 1000;
    const now = playback ? worldNow : real;
    ...
      if (soundCbs.size && !playback) cues.update(now, (c) => soundCbs.forEach((cb) => cb(c)));
    ```
    `sim.update(dt, now)`, `renderer.frame(now, dt, ...)` e `overlay.draw(now, ...)` recebem esse `dt`/`now`. A câmera
    usa `real`/`realDt` e deve continuar assim.
  - `setSuspended` (linhas 535-554) é o modelo de "voltar ao presente":
    ```ts
    lastTs = 0;
    if (suspendedAt && Date.now() - suspendedAt > 5000) sim.fastForward(playback ? worldNow : Date.now());
    ```
  - `wake()` (linhas 321-326) acorda o laço se ele estiver dormindo num timer.
  - O objeto devolvido (`WorldApi`) tem `setSuspended` e `setFrameCap` nas linhas 535-560: o método novo vai ao lado.
- `client/src/world/api.ts:104-113` — os métodos opcionais `setSuspended?` e `setFrameCap?` com doc em português. O
  método novo segue esse formato.
- `client/src/world/pacing.ts:22` — `frameCapFor({ uiCap, active, focused, powerSave, motion })`: com `active: false`
  e `motion: false`, o laço cai para o ritmo de "parado" (poucos quadros por segundo). Não mude este arquivo.
- `client/src/ui/overlays.ts` — componentes simples de camada (`EmptyState`, `ConnectionBanner`) que implementam
  `UiComponent` (`readonly el`, `render()`), recebem `ctx: UiContext` e usam `h`, `setHidden` de `./dom` e `tr` de
  `../../../shared/i18n`. O botão novo segue esse padrão e entra neste arquivo.
- `client/src/ui/dom.ts` — `iconButton(svg, label, onClick, cls?)` (cria o botão com `aria-label`/`title`),
  `setTitle`, `setAttr`. Confira as assinaturas com `grep -nE "export function (iconButton|setTitle|setAttr)" client/src/ui/dom.ts`.
- `client/src/ui/timelapse-icons.ts:3` — `TIMELAPSE_ICONS.play` e `TIMELAPSE_ICONS.pause` (SVG). Reuse; não desenhe
  ícones novos.
- `client/src/ui/index.ts`:
  - linha 304: `root.append(topbar.el, sidebar.el, scrim, feed.el, ..., splash.el);`
  - linha 313: `const components: UiComponent[] = [topbar, sidebar, ..., worldChip];`
  - `ctx.world` é o `WorldApi`.
- `client/src/ui/styles.css`:
  - `.ui-toasts` (linhas ~2666-2680) usa `right: max(var(--gap), var(--free-right));` — `--free-right` é a largura
    coberta pela gaveta à direita (vem de `ui/viewport.ts`), então o botão fica à esquerda da gaveta quando ela abre.
  - Rodapé com área segura do celular: `bottom: calc(var(--gap) + env(safe-area-inset-bottom, 0px));` (linha ~9743).
  - Classes de botão redondo de ícone: `ui-icon-btn` (veja um uso com `grep -n "ui-icon-btn" client/src/ui/topbar.ts | head -3`).
- i18n: todo texto passa por `tr('texto pt-BR')` e precisa de entrada em `shared/locales/en.ts`, `es.ts` e `fr.ts`.
- Comentários em português. Commits `area: descrição` em minúsculas.

## Commands you will need

| Purpose   | Command                     | Expected on success |
|-----------|-----------------------------|---------------------|
| Install   | `npm ci`                    | exit 0              |
| Typecheck | `npm run typecheck`         | exit 0              |
| Tests     | `npm test`                  | all pass            |
| i18n      | `npm run i18n:check`        | 100% en/es/fr       |
| Build     | `npm run build:client`      | exit 0              |

## Scope

**In scope**:
- `client/src/world/index.ts`, `client/src/world/api.ts`
- `client/src/ui/overlays.ts`, `client/src/ui/index.ts`, `client/src/ui/styles.css`
- `shared/locales/en.ts`, `es.ts`, `fr.ts`
- `CHANGELOG.md` (uma linha em "Não lançado › Adicionado")

**Out of scope**: `world/pacing.ts`, `world/sim/*`, `world/render/*`, timelapse, cidade do World, preferências
(`ui/prefs.ts`, `ui/settings.ts`), atalhos de teclado.

## Git workflow

- Branch: `advisor/028-pausar-animacoes`
- Um commit: `escritório: botão no canto inferior direito pausa e retoma as animações`
- Não faça push nem PR.

## Steps

### Step 1: `setPaused` no `WorldApi`

Em `client/src/world/api.ts`, depois de `setFrameCap?`, acrescente:
```ts
/**
 * (Opcional, aditivo) Animações pausadas: a simulação e o desenho param no instante da pausa (câmera, zoom e clique
 * continuam); ao retomar, a simulação pula para o presente, como com a aba em segundo plano.
 */
setPaused?(paused: boolean): void;
```

### Step 2: pausa no laço

Em `client/src/world/index.ts`:
1. Variável `let pausedAt = 0;` junto das outras do laço (0 = tocando; senão `Date.now()` do instante da pausa).
2. No `loop`:
   - em `frameCapFor`, `motion: !pausedAt && renderer.motion`;
   - `const k = pausedAt ? 0 : playback ? Math.max(0, playback.scale()) : 1;`
   - `const now = playback ? worldNow : pausedAt || real;`
   - sons: `if (soundCbs.size && !playback && !pausedAt) ...`
   O timelapse não convive com a pausa (o botão some no replay), mas se convivesse, `k = 0` congela também.
3. Método no objeto devolvido, ao lado de `setFrameCap`:
   ```ts
   setPaused: (on) => {
     if (on === !!pausedAt) return;
     if (on) {
       pausedAt = Date.now();
       return;
     }
     lastTs = 0;
     sim.fastForward(playback ? worldNow : Date.now());
     pausedAt = 0;
     wake();
   },
   ```

**Verify**: `npm run typecheck` → exit 0.

### Step 3: o botão

Em `client/src/ui/overlays.ts`, uma classe nova:
```ts
/** Canto inferior direito: pausa e retoma as animações do escritório (some no timelapse e na cidade, pelo CSS). */
export class AnimationToggle implements UiComponent {
  readonly el: HTMLButtonElement;
  private paused = false;

  constructor(private readonly ctx: UiContext) {
    this.el = iconButton(TIMELAPSE_ICONS.pause, tr('Pausar animações'), () => this.toggle(), 'ui-anim-toggle');
  }

  private toggle(): void {
    this.paused = !this.paused;
    this.ctx.world.setPaused?.(this.paused);
    this.el.innerHTML = this.paused ? TIMELAPSE_ICONS.play : TIMELAPSE_ICONS.pause;
    const label = this.paused ? tr('Retomar animações') : tr('Pausar animações');
    setTitle(this.el, label);
    this.el.setAttribute('aria-label', label);
    this.el.setAttribute('aria-pressed', String(this.paused));
  }

  render(): void {}
}
```
Ajuste os imports (`iconButton`, `setTitle` de `./dom`; `TIMELAPSE_ICONS` de `./timelapse-icons`). Se `iconButton`
já devolve um botão com classe base e só aceita uma classe extra, passe `'ui-anim-toggle'`; se `setTitle` já atualiza
o `aria-label`, não repita. Confira lendo `dom.ts`.

Em `client/src/ui/index.ts`: crie `const animToggle = new AnimationToggle(ctx);` junto dos outros overlays (perto de
`const banner = new ConnectionBanner(ctx);`), acrescente `animToggle.el` ao `root.append(...)` da linha 304 e
`animToggle` à lista `components` da linha 313.

**Verify**: `npm run typecheck` → exit 0.

### Step 4: CSS

Em `client/src/ui/styles.css`, perto de `.ui-toasts`:
```css
/* Pausa das animações do escritório: canto inferior direito, à esquerda da gaveta quando ela abre. */
.ui-anim-toggle {
  position: fixed;
  z-index: 15;
  right: max(var(--gap), var(--free-right));
  bottom: calc(var(--gap) + env(safe-area-inset-bottom, 0px));
}
.ui-root.is-replay .ui-anim-toggle,
.ui-root.is-city .ui-anim-toggle {
  display: none;
}
```
Se o botão ficar sem fundo (ícone solto sobre o mapa), dê a ele o mesmo fundo do cabeçalho do feed (`.ui-feed`):
procure a regra de fundo/borda dele e reuse as mesmas variáveis. Não invente cores.

**Verify**: `npm run build:client` → exit 0.

### Step 5: traduções e changelog

- `shared/locales/{en,es,fr}.ts`: `'Pausar animações'` e `'Retomar animações'`.
- `CHANGELOG.md`, "Não lançado › Adicionado": `**Pausar as animações do escritório:** um botão no canto inferior
  direito congela o escritório (a câmera e os cliques continuam); ao retomar, ele volta ao presente.`

**Verify**: `npm run i18n:check` → 100%; `npm test` → all pass.

## Test plan

A lógica nova é pequena e vive no laço (rAF), que não tem teste unitário. Não crie testes. A prova é o smoke do
revisor no navegador (`?mock=1`).

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0
- [ ] `npm run i18n:check` 100%
- [ ] `npm run build:client` exits 0
- [ ] `git diff --name-only 4cdae9e` lista só arquivos do Scope

## STOP conditions

- `sim.fastForward` não existe ou tem outra assinatura que `fastForward(now: number)`.
- `renderer.frame`/`overlay.draw` não aceitam `dt = 0` sem quebrar (erro no console ao pausar).
- O botão precisa de algo além de CSS para sumir no timelapse ou na cidade.

## Maintenance notes

- Se um dia a pausa precisar sobreviver ao recarregar, vira preferência em `ui/prefs.ts` e o `AnimationToggle` lê de lá.
- Novas animações que usem `Date.now()` direto, em vez do `now` do laço, não param com a pausa.
