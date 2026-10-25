# Plan 009: Telas, vista das janelas e relógios são repintados só quando mudam (cache por item, até 15 vezes/s)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 7af1def..HEAD -- client/src/world/render/renderer.ts client/src/art/dynamic.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: basta a seção `## [Não lançado]`
> existir.)

## Status

- **Priority**: P3
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none (soma com os planos 007 e 008, sem conflito de arquivo)
- **Category**: perf
- **Planned at**: commit `7af1def`, 2026-10-25

## Why this matters

A cada quadro o escritório repinta, pixel a pixel com `fillRect`, todas as telas de monitor e TV, a vista de cada
janela e os ponteiros de cada relógio (`client/src/art/dynamic.ts`, "Desenhos animados chamados a cada quadro").
Medido no escritório real (1440×900, visão geral): ≈ 1150 chamadas de canvas por quadro, das quais ≈ 280 vêm de
`drawScreen`, ≈ 145 de `drawWindowView` e ≈ 85 de `drawClock`. O conteúdo deles muda devagar: o passo mais rápido é
de ~110 ms (a maioria entre 250 ms e 1 s; o relógio, 1 vez por segundo). Pintar cada item num canvas pequeno e só
repintar quando o "carimbo" de tempo dele muda (no máximo 15 vezes por segundo; relógio, 1 vez por segundo) troca
centenas de `fillRect` por quadro por um `drawImage` por item. O ganho é só na parte de JavaScript do quadro
(≈ 1,9 ms de ≈ 12 ms; o resto é pintar pixels, tratado nos planos 007 e 008), então a expectativa é modesta: menos
chamadas de canvas e menos trabalho da GPU por comando, sem mudança visível.

## Current state

Arquivos:

- `client/src/world/render/renderer.ts` — classe `Renderer`; chama os desenhos animados em `drawFurniture`,
  `drawWallItem` e `drawWindow`.
- `client/src/art/dynamic.ts` — os desenhos animados. **Não muda neste plano.** Todos desenham com coordenadas
  absolutas do contexto recebido e recortam ao retângulo com um recorte próprio (`clipTo`); não leem o estado do
  contexto (só escrevem `fillStyle`).

`client/src/world/render/renderer.ts:777-790` (`drawFurniture`: monitor da mesa e fliperama):

```ts
    ctx.drawImage(s.canvas, dx, dy);
    if (!front) {
      if (f.kind === 'desk' && s.rects?.screen) {
        const r = s.rects.screen;
        const mode = this.deskScreen(f, area);
        this.safe(() => this.art.drawScreen(ctx, { x: dx + r.x, y: dy + r.y, w: r.w, h: r.h }, mode, now, f.seed));
      } else if (f.kind === 'coffee_machine' && state === 1) {
        this.drawSteam(f.ax, dy + 2, now, f.seed);
      } else if (f.kind === 'arcade' && s.rects?.screen) {
        // duelo no fliperama: as duas máquinas mostram a partida
        const seed = this.sim.social.arcadeSeed();
        const r = s.rects.screen;
        if (seed !== null) this.safe(() => this.art.drawScreen(ctx, { x: dx + r.x, y: dy + r.y, w: r.w, h: r.h }, 'game', now, seed));
      }
    }
```

`client/src/world/render/renderer.ts:609-617` (relógio de parede):

```ts
      case 'clock': {
        const s = wallSprites(this.art, 'clock', w.variant, 0, w.seed);
        if (!s) return;
        const o = wallItemOrigin(w, s.base);
        ctx.drawImage(s.base.canvas, o.x, o.y + rise);
        const r = s.base.rects?.face;
        if (r) this.safe(() => this.art.drawClock(ctx, { x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }, this.date));
        return;
      }
```

`client/src/world/render/renderer.ts:627-639` (TV do lounge):

```ts
      case 'tv': {
        ...
        const live = this.sim.social.tvScreen();
        const mode = live ? live.mode : TV_MODES[Math.floor(now / 9000) % TV_MODES.length];
        const seed = live ? live.seed : w.seed;
        if (r) this.safe(() => this.art.drawScreen(ctx, { x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }, mode, now, seed));
        return;
      }
```

`client/src/world/render/renderer.ts:658-672` (`drawWindow`, usado nas janelas das salas e das fachadas vazias):

```ts
  private drawWindow(w: WallVis, hour: number, now: number, alpha: number, rise = 0): void {
    const { ctx } = this;
    const s = wallSprites(this.art, 'window', w.variant, 0, w.seed);
    if (!s) return;
    const o = wallItemOrigin(w, s.base);
    let r = s.base.rects?.glass;
    if (!r) {
      const b = opaqueBounds(s.base.canvas);
      r = { x: b.x + 2, y: b.y + 2, w: b.w - 4, h: b.h - 4 };
    }
    ctx.globalAlpha *= alpha;
    this.safe(() => this.art.drawWindowView(ctx, { x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }, hour, now, w.seed));
    drawSeasonalWindow(ctx, this.theme, { x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }, now, w.seed);
    ctx.drawImage(s.base.canvas, o.x, o.y + rise);
  }
```

`client/src/world/render/renderer.ts:1220-1226` (`safe`, para arte incompleta não derrubar o quadro):

```ts
  private safe(fn: () => void): void {
    try {
      fn();
    } catch {
      // arte incompleta (stub) não pode derrubar o mundo
    }
  }
```

Fatos sobre `client/src/art/dynamic.ts` que o cache precisa respeitar:

- `drawScreen(ctx, r, mode, t, seed)` arredonda `r` (`Math.round`) e só depende de `mode`, `seed`, `r` e de `t`
  quantizado (passos de 110 ms ou mais; alguns modos `game`/`show` usam `Math.sin(t / ...)` contínuo, com amplitude
  de 1–2 px).
- `drawWindowView(ctx, r, hour, t, seed)` depende de `hour` (cor do céu contínua ao longo do dia), de `t` (nuvens
  andando devagar, estrelas piscando a cada 600 ms) e de `seed`.
- `drawClock(ctx, r, date)` **não** arredonda `r` (usa `r.x + r.w / 2` com `Math.floor`) e depende de horas,
  minutos e segundos de `date`.
- As cores incluem `rgba(...)` (ponteiro dos segundos). Pintar num canvas transparente e depois desenhar a imagem por
  cima dá o mesmo resultado (composição `source-over` é associativa) quando `globalAlpha` é 1. Com `globalAlpha` < 1
  (só durante o fade de montagem/desmontagem de sala) o resultado vira "opacidade do grupo" em vez de cada pixel
  com alpha; a diferença é imperceptível e aceita.

Convenções: comentários em português explicando o porquê; nada de alocação por quadro desnecessária; mudanças
visíveis no `CHANGELOG.md` (`## [Não lançado]`). Esta mudança não é visível; entra como nota de desempenho em
`### Alterado` (crie entre `### Adicionado` e `### Corrigido` se não existir).

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747`
(`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`). Se não estiver, STOP.

## Measurement script (temporário, fora do repositório)

Crie `/tmp/habblaud-meas.mjs`. Ele mede quadros/s, chamadas de canvas por quadro, tempo de JavaScript por quadro
(soma dos callbacks de `requestAnimationFrame`) e salva uma captura com a hora fixada ao meio-dia
(`habblaud.world.debug.setHour(12)`), para comparar antes/depois.

```js
// Uso (na raiz do repositório): SHOT=<png> node /tmp/habblaud-meas.mjs <url> <largura> <altura> <dpr> [segundos]
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const [, , url = 'http://127.0.0.1:4790/', w = '1440', h = '900', dpr = '1', secs = '5'] = process.argv;
const browser = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium', args: ['--disable-gpu', '--disable-accelerated-2d-canvas'] });
const ctx = await browser.newContext({ viewport: { width: +w, height: +h }, deviceScaleFactor: +dpr });
const page = await ctx.newPage();
await page.addInitScript(() => {
  const st = (window.__m = { frames: 0, calls: 0, js: 0 });
  const raf = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = (f) => raf((t) => { const a = performance.now(); try { f(t); } finally { st.js += performance.now() - a; } });
  const P = CanvasRenderingContext2D.prototype;
  for (const k of ['fillRect', 'drawImage', 'fillText', 'fill', 'stroke']) {
    const o = P[k];
    P[k] = function (...a) {
      if (this.canvas.isConnected) {
        st.calls++;
        if (k === 'fillRect' && a[0] === 0 && a[1] === 0 && a[2] === this.canvas.width && a[3] === this.canvas.height) st.frames++;
      }
      return o.apply(this, a);
    };
  }
});
await page.goto(url, { waitUntil: 'load' });
await page.waitForTimeout(4000);
for (let i = 0; i < 3; i++) { await page.keyboard.press('Escape'); await page.waitForTimeout(300); }
await page.evaluate(() => window.habblaud.world.debug?.setHour(12));
await page.waitForTimeout(1500);
const s0 = await page.evaluate(() => ({ ...window.__m }));
const t0 = Date.now();
await page.waitForTimeout(+secs * 1000);
const s1 = await page.evaluate(() => ({ ...window.__m }));
const wall = (Date.now() - t0) / 1000;
const frames = Math.max(1, s1.frames - s0.frames);
console.log(`quadros/s=${(frames / wall).toFixed(1)} chamadas/quadro=${((s1.calls - s0.calls) / frames).toFixed(0)} js_ms/quadro=${((s1.js - s0.js) / frames).toFixed(2)}`);
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await browser.close();
```

Os números variam com a carga da máquina e com o que os agentes estão fazendo: rode 3 vezes e use a mediana.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/render/renderer.ts`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- `client/src/art/dynamic.ts` e `client/src/art/preview.ts` — os desenhos continuam iguais; o cache fica todo no
  renderer. A página de pré-visualização da arte chama os desenhos direto e não precisa de cache.
- `drawBoard` (quadro kanban, ≈ 20 chamadas por quadro), `drawSteam`, `drawReflection` (espelho),
  `drawSeasonalWindow` e as decorações de tema: pouco ganho; ficam como estão.
- `client/src/world/index.ts` (laço) e a densidade do canvas — planos 007 e 008.

## Git workflow

- Branch: `advisor/009-telas-em-cache`
- Commits no estilo do repositório: assunto em português, minúsculo. Exemplo do `git log`:
  `escritório a 30 qps com o terminal aberto fora do celular (setFrameCap)`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

Antes de mudar código: `npx vite build --config vite.config.ts`, suba o preview (deixe rodando) e rode 3×:
`SHOT=/tmp/hb-009-antes.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1`.

**Verify**: `chamadas/quadro` acima de 700 (o escritório tem várias salas com mesas). Se ficar abaixo de 400, o
escritório está quase vazio e a comparação não vale: STOP e peça ao operador para medir com salas abertas.

### Step 2: Helper de cache no `Renderer`

Em `client/src/world/render/renderer.ts`:

1. Constante no topo do módulo, junto das outras:

   ```ts
   /** Telas e janelas animadas são repintadas no máximo a cada 66 ms (~15 vezes/s); o passo mais rápido delas é ~110 ms. */
   const DYN_MS = 66;
   ```

2. Campo e método privados na classe (perto de `safe`):

   ```ts
   /** Desenhos animados (telas, vista da janela, relógio): um canvas pequeno por item, repintado só quando a chave muda. */
   private dyn = new WeakMap<object, { canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D; key: string }>();

   /**
    * Desenha `paint` através do cache do item `owner`. `key` resume tudo de que o desenho depende (modo, seed, tempo
    * quantizado, posição); igual ao último => só um drawImage. `paint` recebe um contexto deslocado para que as
    * coordenadas absolutas de `r` caiam dentro do canvas pequeno, pixel a pixel como no canvas principal.
    */
   private drawDynamic(owner: object, r: { x: number; y: number; w: number; h: number }, key: string, paint: (c: CanvasRenderingContext2D) => void): void {
     const x0 = Math.floor(r.x);
     const y0 = Math.floor(r.y);
     const w = Math.ceil(r.x + r.w) - x0;
     const h = Math.ceil(r.y + r.h) - y0;
     if (w <= 0 || h <= 0) return;
     let e = this.dyn.get(owner);
     if (!e) {
       const canvas = document.createElement('canvas');
       const ctx = canvas.getContext('2d');
       if (!ctx) return;
       e = { canvas, ctx, key: '' };
       this.dyn.set(owner, e);
     }
     if (e.canvas.width !== w || e.canvas.height !== h) {
       e.canvas.width = w;
       e.canvas.height = h;
       e.key = '';
     }
     if (e.key !== key) {
       const c = e.ctx;
       c.setTransform(1, 0, 0, 1, 0, 0);
       c.clearRect(0, 0, w, h);
       c.setTransform(1, 0, 0, 1, -x0, -y0);
       this.safe(() => paint(c));
       e.key = key;
     }
     this.ctx.drawImage(e.canvas, x0, y0);
   }
   ```

**Verify**: `npm run typecheck` → exit 0 (o método ainda não é usado; se o typecheck reclamar de membro não usado,
siga para o passo 3 e verifique lá).

### Step 3: Passar os quatro desenhos pelo cache

Troque as chamadas, mantendo o resto de cada método igual. Em todas, a chave termina com a posição (`r.x`, `r.y`),
porque ela muda durante a montagem da sala (`rise`) e o relógio depende da fração da posição.

1. Monitor da mesa (`drawFurniture`, linha 782):

   ```ts
        const sr = { x: dx + r.x, y: dy + r.y, w: r.w, h: r.h };
        const stamp = Math.floor(now / DYN_MS);
        this.drawDynamic(f, sr, `${mode}|${f.seed}|${stamp}|${sr.x}|${sr.y}`, (c) => this.art.drawScreen(c, sr, mode, now, f.seed));
   ```

2. Fliperama (`drawFurniture`, linha 789), dentro do `if (seed !== null)`: igual ao item 1 com `'game'` e `seed`,
   owner `f`.
3. TV (`drawWallItem`, linha 637), dentro do `if (r)`: owner `w`; chave
   `` `${mode}|${seed}|${Math.floor(now / DYN_MS)}|${rect.x}|${rect.y}` `` com `rect` = o retângulo hoje passado a
   `drawScreen`.
4. Relógio (`drawWallItem`, linha 615), dentro do `if (r)`: owner `w`; chave pelos segundos de `this.date`:
   `` `${this.date.getHours()}:${this.date.getMinutes()}:${this.date.getSeconds()}|${rect.x}|${rect.y}` ``;
   `paint` = `(c) => this.art.drawClock(c, rect, this.date)`.
5. Vista da janela (`drawWindow`, linha 669): owner `w`; chave
   `` `${w.seed}|${Math.floor(hour * 60)}|${Math.floor(now / DYN_MS)}|${rect.x}|${rect.y}` ``;
   `paint` = `(c) => this.art.drawWindowView(c, rect, hour, now, w.seed)`. A linha `ctx.globalAlpha *= alpha;`
   continua antes (vale para o `drawImage` do cache) e `drawSeasonalWindow` e o `drawImage` da moldura continuam
   depois, como hoje.

`drawDynamic` já chama `this.safe`; remova o `this.safe(() => ...)` externo dessas cinco chamadas para não aninhar.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0;
`grep -c "this.drawDynamic(" client/src/world/render/renderer.ts` → `5`;
`grep -n "this.art.drawScreen(ctx\|this.art.drawClock(ctx\|this.art.drawWindowView(ctx" client/src/world/render/renderer.ts` → nenhuma linha.

### Step 4: Medir e comparar a imagem

Rebuild, reinicie o preview e rode 3×:
`SHOT=/tmp/hb-009-depois.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1`.

**Verify**:

- mediana de `chamadas/quadro` ≤ 85% da mediana do passo 1;
- mediana de `js_ms/quadro` não maior que a do passo 1 (+10% de tolerância pela variação);
- abra `/tmp/hb-009-antes.png` e `/tmp/hb-009-depois.png`: telas dos monitores, TV, janelas e relógios aparecem
  (nenhum vazio/preto onde antes havia imagem, nenhum deslocado 1 px). O conteúdo das telas pode diferir porque o
  tempo passou entre as capturas; compare forma e posição, não o texto rolando.

### Step 5: Changelog

`CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie entre `### Adicionado` e `### Corrigido` se não existir):
`- **Escritório:** telas, janelas e relógios são repintados só quando mudam (até 15 vezes por segundo), em vez de a
  cada quadro: menos trabalho por quadro, sem mudança visível.`

**Verify**: `git status` só mostra `client/src/world/render/renderer.ts` e `CHANGELOG.md`.

## Test plan

- Sem teste unitário novo: o cache depende de canvas (DOM) e o Vitest roda em `environment: 'node'`; a chave é uma
  concatenação simples. A prova é a medição e a comparação de capturas do passo 4.
- `npm test` → exit 0 (nada existente deve quebrar).

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0
- [ ] `grep -c "this.drawDynamic(" client/src/world/render/renderer.ts` → `5`
- [ ] Mediana de `chamadas/quadro` ≤ 85% da linha de base (1440×900@1, 3 rodadas cada)
- [ ] Capturas antes/depois sem telas, janelas ou relógios faltando ou deslocados
- [ ] `CHANGELOG.md` atualizado em `## [Não lançado]`
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código.
- Algum desenho de `client/src/art/dynamic.ts` lê estado do contexto (ex.: `ctx.globalAlpha`, `ctx.getTransform()`)
  ou desenha fora do retângulo recebido: o cache não reproduziria o resultado.
- A captura "depois" mostra uma tela/janela/relógio em branco, preto ou deslocado.
- `chamadas/quadro` não cai pelo menos 10%: o ganho não se paga; reverta e reporte os números.
- A mudança parece exigir mexer em `client/src/art/**`.

## Maintenance notes

- Um desenho animado novo chamado a cada quadro deve passar por `drawDynamic` com uma chave que inclua **tudo** de
  que ele depende. Esquecer uma dependência na chave = imagem congelada (ex.: a vista da janela depende de `hour`).
- `DYN_MS` (66 ms) limita a fluidez dos modos com movimento contínuo (`game`, `show`); se alguém notar a bola do
  futebol "pulando", baixe para 33.
- No timelapse (relógio do mundo acelerado), o carimbo muda a cada quadro e o cache repinta sempre: sem ganho, sem
  perda.
- O `WeakMap` usa os objetos `FurnVis`/`WallVis` como dono; quando uma sala é reconstruída eles mudam e o cache antigo
  é coletado sozinho.
- Revisor: confira as 5 chaves (modo, seed, tempo quantizado, posição; hora no caso da janela; segundos no relógio).
