# Plan 026: Timelapse e janela do Habblaud World baixados só quando abertos

> **Executor instructions**: siga o plano passo a passo. Rode cada verificação e confira o resultado esperado antes
> de seguir. Se acontecer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha 026 em `plans/README.md` (a menos que quem te despachou diga que cuida do índice).
>
> **Drift check (rode primeiro)**:
> `git diff --stat 450e635..HEAD -- client/src/ui/index.ts client/src/ui/topbar.ts client/src/ui/timelapse.ts client/src/ui/world/world-dialog.ts client/src/ui/world/settings-world.ts client/src/ui/settings.ts client/src/ui/daystats-launcher.ts`
> Se algum arquivo mudou, compare os trechos de "Current state" com o código; se não baterem, é STOP.

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: MED (mexe na montagem da interface; erro aparece como botão que não abre)
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `450e635`, 2026-10-26
- **Origem**: issue upstream marmottajr/habblaud#73, seção 4 ("O bundle principal tem 634 kB")

## Why this matters

O pedaço principal do client (`dist/client/bundle/app-*.js`) tem hoje 741 kB (244 kB com gzip), e cresceu desde a
issue #73 (634/214 kB). Todo visitante baixa e parseia isso antes de ver o escritório, inclusive no celular. Duas
partes grandes só servem quando a pessoa abre uma janela específica: o **player do timelapse**
(`client/src/ui/timelapse.ts`, ~12 kB minificado) e o **conteúdo da janela do Habblaud World**
(`WorldSettingsGroup` em `client/src/ui/world/settings-world.ts`, ~35 kB minificado; quem não configurou o servidor
do mundo nunca abre). Medido com esbuild `--minify` sobre `client/src/app.ts`: app ≈ 363 kB minificado, então as duas
partes somam ~13% dele. O projeto já faz isso com o "Meu dia", o terminal interativo, a cidade e os idiomas
(`import()` sob demanda); este plano segue o mesmo padrão.

## Current state

Já carregados sob demanda (não mexer): `client/src/app.ts:64` (cidade), `client/src/ui/daystats-launcher.ts:42`
(Meu dia), `client/src/ui/pty.ts:231` (terminal interativo), `client/src/main.ts:7-9` (idiomas).

**Exemplar a copiar** — `client/src/ui/daystats-launcher.ts` (inteiro, 55 linhas). O núcleo:

```ts
export class DayLauncher implements UiComponent {
  readonly button: HTMLButtonElement;
  private panel: DayPanel | null = null;
  private loading: Promise<DayPanel> | null = null;
  ...
  toggle(): void {
    if (this.panel) this.panel.toggle();
    else
      void this.load()
        .then((p) => p.open())
        .catch(() => this.ctx.announce(tr('Não foi possível abrir o painel Meu dia.')));
  }

  render(): void {
    this.panel?.render();
  }

  private load(): Promise<DayPanel> {
    this.loading ??= import('./daystats').then((m) => {
      const panel = new m.DayPanel(this.ctx, this.button);
      this.mount(panel.el);
      this.panel = panel;
      return panel;
    });
    // Falhou (rede caiu no meio do download): a próxima tentativa baixa de novo.
    this.loading.catch(() => (this.loading = null));
    return this.loading;
  }
}
```

Note: `import type` do módulo pesado (não puxa código), `loading ??=`, reset no erro, `ctx.announce` com texto
traduzido via `tr(...)`.

### Timelapse

- `client/src/ui/timelapse.ts` — exporta `SPEEDS`, `Speed`, `TIMELAPSE_ICONS` (linha 28), `dayLabel`, `hourTicks`
  (puras, testadas em `client/src/ui/timelapse.test.ts`) e a classe `TimelapsePlayer` (linha 73) com `el`, `badge`,
  `vignette`, `isOpen`, `replayTime`, `toggle()`, `close()`, `render()`.
- `client/src/ui/topbar.ts:9` — `import { TIMELAPSE_ICONS } from './timelapse';` (só os ícones). Isso sozinho
  prende o módulo inteiro no bundle principal.
- `client/src/ui/index.ts`:

```ts
36:import { TimelapsePlayer } from './timelapse';
86:  let timelapse: TimelapsePlayer;
106:    now: () => timelapse?.replayTime ?? Date.now() + skew,
168:    toggleTimelapse() {
169:      // O terminal mostra a conversa de agora: não combina com o dia reproduzido.
170:      if (!timelapse.isOpen && terminal.isOpen) terminal.close();
171:      timelapse.toggle();
172:    },
173:    isTimelapseOpen: () => timelapse.isOpen,
295:  timelapse = new TimelapsePlayer(ctx);
299:  root.append(timelapse.vignette, topbar.el, sidebar.el, scrim, feed.el, drawer.el, terminal.el, pty.el, timelapse.el, timelapse.badge, toasts.el, ...);
308:  const components: UiComponent[] = [..., splash, timelapse, sound, day, ...];
```

A ordem dos elementos em `root.append` importa para o empilhamento: `vignette` vem **antes** de `topbar.el`;
`el` e `badge` vêm depois de `pty.el` e antes de `toasts.el`.

### Janela do Habblaud World

- `client/src/ui/world/world-dialog.ts` — `WorldDialog` (126 linhas) cria `this.group = new WorldSettingsGroup(ctx, src)`
  no construtor (linha 42) e põe `this.group.el` no corpo e `this.group.chip` no cabeçalho. Expõe para as
  Configurações `themeNote` (getter, linha 70) e `onThemePicked(theme)` (linha 74), via a interface
  `WorldThemeLink`. `render()` (linha 119):

```ts
  render(): void {
    // Sem servidor do mundo configurado (ou pela porta do celular), nem o botão aparece.
    const hidden = worldSectionMode(this.src.meta(), worldGate(this.ctx, this.src)) === 'hidden';
    setHidden(this.button, hidden);
    if (hidden && this.el.open) this.close();
    this.group.render(this.el.open);
  }
```

- `client/src/ui/world/settings-world.ts` (1402 linhas) — `worldGate()` (linha 248, 4 linhas, usado também por
  `scene-switch.ts`, `world-chip.ts`, `world-popups.ts` e `ui/index.ts:45`) e a classe `WorldSettingsGroup`
  (linha 261). Com a janela fechada, `WorldSettingsGroup.render(false)` (linha 322) não faz rede: só calcula
  `mode`, mostra/esconde `themeNote` (`setHidden(this.themeNote, !this.themeLinked)`, linha 328) e pinta o chip.
  `themeNote` é um `<span>` criado no construtor (linha 310); `onThemePicked` manda o tema ao prédio.
- `client/src/ui/settings.ts:12` — `import type { WorldThemeLink } from './world/world-dialog';` (só tipo).
- `client/src/ui/index.ts:250-260` cria o `WorldDialog` e passa para `SettingsPopover`, `WorldPopups`, `WorldChip`.

Por causa do `worldGate` em `settings-world.ts`, **qualquer** import dele (chip, popups) puxa o arquivo inteiro.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Tests | `npm test` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0; lista os chunks |
| Tamanho do app | `ls -l dist/client/bundle/app-*.js && gzip -c dist/client/bundle/app-*.js \| wc -c` | bytes do app e com gzip |

Rode o build e anote o tamanho do `app-*.js` **antes** do passo 1 (linha de base).

## Scope

**In scope**:
- `client/src/ui/timelapse.ts`, `client/src/ui/timelapse-icons.ts` (criar), `client/src/ui/timelapse-launcher.ts` (criar)
- `client/src/ui/topbar.ts` (só o import dos ícones)
- `client/src/ui/index.ts` (montagem do timelapse e do World)
- `client/src/ui/world/world-dialog.ts`, `client/src/ui/world/settings-world.ts`, `client/src/ui/world/world-gate.ts` (criar)
- os importadores de `worldGate`: `client/src/ui/world/scene-switch.ts`, `world-chip.ts`, `world-popups.ts`
- `shared/locales/*.ts` só se precisar de texto novo de erro (ver passo 2)
- `CHANGELOG.md`

**Out of scope**:
- `vite.config.ts` / `manualChunks`: a issue já mostrou que isso sozinho não reduz o parse inicial.
- Editor de personagem (`character-editor.ts`, ~6 kB): pequeno demais para valer a mudança.
- Qualquer mudança de comportamento do timelapse ou da janela do World (só *quando* o código é baixado).
- `client/src/world/**`, `client/src/art/**`.

## Git workflow

- Branch: `advisor/026-bundle-sob-demanda`, a partir de `producao`.
- Commits curtos em português, no estilo do repo (ex.: `timelapse: player baixado só na primeira abertura`).
- Não faça push nem PR.

## Steps

### Step 1: ícones do timelapse num módulo próprio

Mova o objeto `TIMELAPSE_ICONS` (e só ele, com o que ele precisar, ex.: `pixelIcon`) de `timelapse.ts` para o novo
`client/src/ui/timelapse-icons.ts`. Em `timelapse.ts`, importe de lá (e re-exporte só se algum teste usar
`TIMELAPSE_ICONS` vindo de `./timelapse`; confira com `grep -rn TIMELAPSE_ICONS client/src`). Em `topbar.ts:9`,
importe de `./timelapse-icons`.

**Verify**: `npm run typecheck` → exit 0; `grep -n "from './timelapse'" client/src/ui/topbar.ts` → nada.

### Step 2: `TimelapseLauncher` no padrão do `DayLauncher`

Crie `client/src/ui/timelapse-launcher.ts` com uma classe `TimelapseLauncher implements UiComponent`:
- `import type { TimelapsePlayer } from './timelapse';`
- construtor `(ctx: UiContext, mount: (p: TimelapsePlayer) => void)`;
- `get isOpen(): boolean` → `this.player?.isOpen ?? false`;
- `get replayTime(): number | null` → `this.player?.replayTime ?? null`;
- `toggle()` → se carregado, `player.toggle()`; senão `load().then((p) => p.toggle())` com `.catch` que chama
  `ctx.announce(tr('Não foi possível abrir o timelapse.'))`;
- `close()` → `this.player?.close()`;
- `render()` → `this.player?.render()`;
- `load()` igual ao do exemplar (`loading ??= import('./timelapse').then(...)`, reset no erro), chamando `mount(player)`.

A string `'Não foi possível abrir o timelapse.'` é nova: adicione-a em `shared/locales/en.ts`, `es.ts` e `fr.ts` no
mesmo formato das vizinhas (procure `'Não foi possível abrir o painel Meu dia.'` em cada arquivo e ponha a nova ao
lado).

Em `client/src/ui/index.ts`:
- troque o import da linha 36 por `import { TimelapseLauncher } from './timelapse-launcher';` e o tipo da linha 86;
- `now:` (linha 106) continua `timelapse?.replayTime ?? Date.now() + skew` — `replayTime` do launcher devolve `null`
  sem player, então o `??` cai no relógio; confira que o tipo continua batendo;
- linha 295: `timelapse = new TimelapseLauncher(ctx, (p) => { ... })`, onde o `mount` insere os três elementos
  **nas mesmas posições** da linha 299: `root.insertBefore(p.vignette, topbar.el)` e `p.el`, `p.badge` antes de
  `toasts.el` (`root.insertBefore(p.el, toasts.el); root.insertBefore(p.badge, toasts.el);`);
- tire `timelapse.vignette`, `timelapse.el`, `timelapse.badge` da linha 299. `timelapse` continua na lista de
  componentes da linha 308 (o `render()` dele é seguro sem player).

**Verify**: `npm run typecheck` → exit 0; `npm test` → todos passam; `npm run i18n:check -- --strict` → exit 0;
`npm run build` → aparece um chunk novo `timelapse-*.js` e `grep -l "TimelapsePlayer\|hourTicks" dist/client/bundle/app-*.js` não acha nada
(se o nome for minificado, compare o tamanho do `app-*.js` com a linha de base: deve cair ~10 kB ou mais).

### Step 3: `worldGate` num módulo próprio

Mova `worldGate` (e o tipo `WorldGate`, se for declarado em `settings-world.ts`; senão ele já vem de outro módulo,
confira o import) para `client/src/ui/world/world-gate.ts`. Atualize os importadores (`scene-switch.ts`,
`world-chip.ts`, `world-popups.ts`, `world-dialog.ts`, `ui/index.ts:45`) para `./world-gate` / `./world/world-gate`.
Em `settings-world.ts`, importe de `./world-gate`.

**Verify**: `npm run typecheck` → exit 0; `grep -rn "worldGate.*settings-world" client/src` → nada.

### Step 4: `WorldSettingsGroup` baixado na primeira abertura

Em `world-dialog.ts`:
- `import type { WorldSettingsGroup } from './settings-world';` (só tipo) e `group: WorldSettingsGroup | null = null`.
- O `themeNote` precisa existir desde o início (as Configurações o colocam em Aparência na montagem). Crie no
  `WorldDialog` um `<span>` **contêiner** `themeSlot` (classe vazia, `hidden` inicialmente) e devolva ele no getter
  `themeNote`. Quando o grupo carregar, faça `themeSlot.replaceWith(group.themeNote)`? **Não**: o elemento pode já
  estar no DOM das Configurações. Use `themeSlot.append(group.themeNote)` e deixe o `themeSlot` visível
  (`setHidden(themeSlot, false)`); o grupo continua controlando o `hidden` do próprio `themeNote`.
- Antes do grupo carregar, a dica do tema não aparece. Para manter o comportamento atual (a dica aparece em
  Aparência mesmo com a janela fechada), o `render()` carrega o grupo **na primeira vez que o modo deixar de ser
  `'hidden'`** (quem tem o mundo configurado), não na primeira abertura. Quem não configurou o mundo nunca baixa.
  Use a mesma expressão do `render()` atual: `worldSectionMode(this.src.meta(), worldGate(this.ctx, this.src)) !== 'hidden'`.
- `onThemePicked(theme)`: `return this.load().then((g) => g.onThemePicked(theme));`.
- `show(screen, opener)`: se o grupo não carregou, `void this.load().then((g) => { g.show(screen); this.ctx.invalidate(); })`
  e siga abrindo o `<dialog>` normalmente (o corpo fica vazio até chegar, o que é aceitável: o chunk é pequeno).
- `render()`: como hoje, mas `this.group?.render(this.el.open)` e o gatilho de `load()` descrito acima.
- `load()`: no padrão do exemplar (`loading ??= import('./settings-world').then((m) => { const g = new m.WorldSettingsGroup(this.ctx, this.src); this.body.append(g.el); heading.append(g.chip); this.themeSlot.append(g.themeNote); setHidden(this.themeSlot, false); this.group = g; this.ctx.invalidate(); return g; })`,
  reset no erro). Guarde a `div` do cabeçalho (`ui-world-dialog__heading`) num campo para poder pôr o chip.

**Verify**: `npm run typecheck` → exit 0; `npm test` → todos passam; `npm run build` → chunk novo
`settings-world-*.js` (ou nome parecido) e o `app-*.js` cai mais ~30 kB em relação ao passo 2.

### Step 5: verificação no navegador

Suba o dev server (`npm run dev`, veja a porta no log) com `?mock=1` e confira, no DevTools › Network (filtro JS):
1. Ao carregar a página, **não** baixa `timelapse-*` nem `settings-world-*` (no mock, o mundo pode estar
   configurado como fake; se `settings-world-*` baixar logo no início no mock, confira que é por causa do passo 4 —
   modo diferente de `'hidden'` — e anote).
2. Tecla **L** (ou botão do timelapse): baixa `timelapse-*`, o player abre, a vinheta aparece atrás da barra do topo,
   o selo aparece. **L** de novo fecha.
3. Botão do Habblaud World (se visível no mock): abre a janela com o conteúdo.

**Verify**: os três itens acima observados; nenhum erro no Console.

### Step 6: CHANGELOG

Em `CHANGELOG.md`, sob `## [Não lançado]` › `### Alterado` (crie a seção se não existir, no formato das outras),
uma linha: o timelapse e a janela do Habblaud World passam a ser baixados só quando usados; anote o tamanho do
`app-*.js` antes → depois (bytes e gzip, medidos no passo 2/4).

**Verify**: `git diff --stat` mostra só arquivos do Scope.

## Test plan

- Sem teste novo de unidade: a mudança é de montagem (quando o código chega), sem lógica nova testável fora do
  navegador. Os testes existentes (`client/src/ui/timelapse.test.ts`, `timelapse-speed.test.ts`) precisam continuar
  passando sem mudar o que verificam — se algum quebrar só pelo caminho do import dos ícones, ajuste o import.
- A prova é o passo 5 (Network + comportamento) e os tamanhos do build.

## Done criteria

- [ ] `npm run typecheck` exit 0
- [ ] `npm test` exit 0
- [ ] `npm run i18n:check -- --strict` exit 0
- [ ] `npm run build` gera chunks separados para o player do timelapse e para `settings-world`
- [ ] `app-*.js` menor que a linha de base em pelo menos 30 kB (sem gzip); números no CHANGELOG
- [ ] Passo 5 observado: timelapse e World abrem; nada baixado antes de precisar (com a ressalva do mock)
- [ ] `git status` só com arquivos do Scope; linha 026 do `plans/README.md` atualizada

## STOP conditions

- O `app-*.js` cai menos de 15 kB depois do passo 2 **e** do passo 4 juntos: algum outro import estático ainda
  prende os módulos. Rode `grep -rn "from '.*\(timelapse\|settings-world\)'" client/src` e relate o que achou, sem
  refatorar mais nada.
- `WorldSettingsGroup` precisar rodar com a janela fechada para algo além de `themeNote`/chip (ex.: fazer rede, ouvir
  eventos de que outro componente dependa): relate antes de seguir com o passo 4. Hoje `src.onChange` no construtor
  (linha 311) só marca `stale` e chama `ctx.invalidate()`.
- O timelapse quebrar com o `replayTime` nulo antes de carregar (ex.: algo além do `now:` lê o player na montagem).
- Qualquer verificação falhar duas vezes depois de uma tentativa razoável.

## Maintenance notes

- Quem adicionar um import **de valor** de `./timelapse` ou `./world/settings-world` em código carregado no início
  desfaz o ganho em silêncio. Para tipos, use `import type`. Vale checar o tamanho do `app-*.js` no review.
- O `themeNote` passa a morar num contêiner do `WorldDialog`; se as Configurações mudarem como mostram a dica do
  tema, a fiação do passo 4 é o lugar a rever.
- Adiado: editor de personagem (~6 kB) e outras janelas pequenas; não compensam a complexidade.
