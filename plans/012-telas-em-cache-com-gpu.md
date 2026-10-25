# Plan 012: Telas, vista das janelas e relógios repintados só quando mudam (cache por item), medido com GPU

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 31368cb..HEAD -- client/src/world/render/renderer.ts client/src/art/dynamic.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (Se o plano 011 já entrou, `renderer.ts` terá o campo `motion` e 4 linhas
> `this.motion = true`: isso é esperado; confira só os trechos citados aqui.)

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW
- **Depends on**: none (mede-se a 30 quadros/s; ver "Git workflow")
- **Category**: perf
- **Planned at**: commit `31368cb` (`fork/main`), 2026-10-25
- **Substitui**: `plans/009-telas-animadas-em-cache.md` (executado sem GPU, merge não recomendado; o branch foi apagado)

## Why this matters

A cada quadro o escritório repinta, pixel a pixel com `fillRect`, as telas de monitor e TV, a vista de cada janela e
os ponteiros de cada relógio (`client/src/art/dynamic.ts`). Medido em 2026-10-25 no escritório real (1440×900 em
densidade 2), essas chamadas são ≈ 720 das ≈ 1700 de cada quadro:

| Origem | Chamadas por quadro |
|---|---|
| `drawScreen` | 391 |
| `drawWindowView` | 217 |
| `drawClock` | 89 |
| `drawBoard` | 26 |

O plano 009 tentou cachear isso, mas mediu sem GPU, onde o custo está em pintar pixels, e não viu ganho. Com GPU de
verdade (o caso de um Mac), tirar esses desenhos do quadro baixa a CPU total de **85% para 72,5% a 30 quadros/s
(−15%)**. Esse é o limite superior. Com o cache, sobra um `drawImage` por item e uma repintura quando o conteúdo muda.

O conteúdo deles muda devagar. A tela mais rápida troca a cada ~110 ms (a maioria a cada 250 ms–1 s). A vista da
janela tem nuvens que andam menos de 1 px por segundo e estrelas que piscam a cada 600 ms. O relógio muda 1 vez por
segundo. O plano pinta cada item num canvas pequeno e só repinta quando a "chave" do item muda.

Atenção ao ritmo: a 10 quadros/s (planos 010 e 011), o intervalo entre quadros (100 ms) já passa do passo das telas,
e elas repintam quase todo quadro. O ganho ali vem só de janelas e relógios. Por isso a medição deste plano é a
30 quadros/s, onde o ganho é maior; o relatório final deve dizer isso.

## Current state

Arquivos:

- `client/src/world/render/renderer.ts` — classe `Renderer`; chama os desenhos animados em `drawFurniture`,
  `drawWallItem` e `drawWindow`.
- `client/src/art/dynamic.ts` — os desenhos animados. **Não muda neste plano.** Todos desenham em coordenadas
  absolutas do contexto recebido e recortam ao retângulo com um recorte próprio (`clipTo`). Não leem o estado do
  contexto (só escrevem `fillStyle`).

`client/src/world/render/renderer.ts:616-624` (relógio de parede):

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

`client/src/world/render/renderer.ts:634-646` (TV do lounge):

```ts
      case 'tv': {
        const s = wallSprites(this.art, 'tv', w.variant, 0, w.seed);
        if (!s) return;
        const o = wallItemOrigin(w, s.base);
        ctx.drawImage(s.base.canvas, o.x, o.y + rise);
        const r = s.base.rects?.tv;
        // roda na TV do lounge: o programa escolhido (futebol, novela, desenho) ou o videogame
        const live = this.sim.social.tvScreen();
        const mode = live ? live.mode : TV_MODES[Math.floor(now / 9000) % TV_MODES.length];
        const seed = live ? live.seed : w.seed;
        if (r) this.safe(() => this.art.drawScreen(ctx, { x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }, mode, now, seed));
        return;
      }
```

`client/src/world/render/renderer.ts:665-679` (`drawWindow`, usado nas janelas das salas e das fachadas vazias):

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

`client/src/world/render/renderer.ts:784-797` (`drawFurniture`: monitor da mesa e fliperama):

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

`client/src/world/render/renderer.ts:1227-1233` (`safe`, para arte incompleta não derrubar o quadro):

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

- `drawScreen(ctx, r, mode, t, seed)` arredonda `r` (`Math.round`) e só depende de `mode`, `seed`, `r` e de `t`.
  O tempo entra quantizado em passos de 110 ms ou mais, exceto alguns modos `game`/`show` com `Math.sin(t / ...)`
  contínuo de 1–2 px de amplitude.
- `drawWindowView(ctx, r, hour, t, seed)` depende de `hour` (cor do céu contínua ao longo do dia), de `t` (nuvens
  a menos de 1 px/s, estrelas piscando a cada 600 ms) e de `seed`.
- `drawClock(ctx, r, date)` **não** arredonda `r` (usa `r.x + r.w / 2` com `Math.floor`) e depende de horas,
  minutos e segundos de `date`.
- As cores incluem `rgba(...)` (ponteiro dos segundos). Pintar num canvas transparente e depois desenhar a imagem por
  cima dá o mesmo resultado quando `globalAlpha` é 1, porque a composição `source-over` é associativa. Com
  `globalAlpha` < 1 (só durante o fade de montagem/desmontagem de sala), o resultado vira "opacidade do grupo" em vez
  de cada pixel com alpha. A diferença é imperceptível e aceita.

Convenções: comentários em português explicando o porquê; nada de alocação por quadro desnecessária (a chave é
uma string curta por item, aceita); mudanças no `CHANGELOG.md` em `## [Não lançado]`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort --host 127.0.0.1` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747`
(`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`). Se não estiver, STOP.

## Measurement script (temporário, fora do repositório)

Use `/tmp/habblaud-cpu.mjs`, idêntico ao da seção "Measurement script" de
`plans/010-economia-de-energia-10-qps.md`. Copie de lá sem mudar nada. Ele mede a CPU de todos os processos do
Chromium com GPU de verdade e sai com código 2 se a GPU não estiver disponível; nesse caso, STOP. Com
`SHOT=<png>`, salva uma captura no fim.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/render/renderer.ts`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- `client/src/art/dynamic.ts` e `client/src/art/preview.ts`: os desenhos continuam iguais e o cache fica todo no
  renderer.
- `drawBoard` (quadro kanban, ≈ 26 chamadas por quadro), `drawSteam`, `drawReflection` (espelho),
  `drawSeasonalWindow` e as decorações de tema: ganho pequeno, ficam como estão.
- `client/src/world/index.ts` e `client/src/world/pacing.ts` (ritmo do laço): planos 010 e 011.

## Git workflow

- **Atualizado em 2026-10-25, depois que 010/011 entraram em `fork/main` (`dfbdde3`).** Com eles, o escritório
  parado desenha a 10 quadros/s e a medição a 30 deixa de ser possível em `fork/main`. O trabalho tem duas fases:
  1. **Medição** (passos 1–4): branch `advisor/012-medicao` a partir de `31368cb`, o commit anterior a 010/011, que
     desenha a 30 quadros/s. Implemente e meça ali. Esse branch é descartável e não vai para merge.
  2. **Entrega**: branch `advisor/012-telas-em-cache` a partir de `fork/main`. Leve para lá o commit de
     `renderer.ts` com `git cherry-pick`. O único conflito possível são as linhas `this.motion = true` do plano 011,
     que devem ficar. Depois rode `npm run typecheck` e `npm test` e faça o commit do passo 5 (changelog). Por fim,
     meça 3× em `fork/main` e 3× no branch de entrega, como informação, sem critério de aprovação.
- Commits no estilo do repositório: assunto em português, minúsculo. Exemplo do `git log`:
  `escritório parado a 30 qps (15 sem foco); 60 qps só com câmera em movimento ou entrada`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

`npx vite build --config vite.config.ts`, suba o preview (deixe rodando) e rode 3×:
`SHOT=/tmp/hb-012-antes.png node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 20`.

**Verify**: `quadros/s` entre 28 e 31 nas 3 rodadas (se não, o branch não partiu de `31368cb`: STOP). Anote a
mediana de `cpu` (esperado ~80–85%).

### Step 2: Helper de cache no `Renderer`

Em `client/src/world/render/renderer.ts`:

1. Constantes no topo do módulo, junto das outras:

   ```ts
   /** Telas animadas repintam no máximo a cada 66 ms (~15 vezes/s); o passo mais rápido delas é ~110 ms. */
   const SCREEN_MS = 66;
   /** Vista da janela: nuvens andam menos de 1 px/s e estrelas piscam a cada 600 ms; 4 repinturas/s bastam. */
   const WINDOW_MS = 250;
   ```

2. Campo e método privados na classe, perto de `safe`:

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

**Verify**: `npm run typecheck` → exit 0. Se reclamar de membro não usado, siga para o passo 3 e verifique lá.

### Step 3: Passar os cinco desenhos pelo cache

Troque as chamadas e mantenha o resto de cada método igual. Em todas, a chave termina com a posição (`x`, `y` do
retângulo), porque ela muda durante a montagem da sala (`rise`) e o relógio depende da fração da posição. Em cada
caso, monte o retângulo uma vez numa `const` e use-o na chave e no `paint`.

1. Monitor da mesa (`drawFurniture`, linha 789):

   ```ts
        const sr = { x: dx + r.x, y: dy + r.y, w: r.w, h: r.h };
        this.drawDynamic(f, sr, `${mode}|${f.seed}|${Math.floor(now / SCREEN_MS)}|${sr.x}|${sr.y}`, (c) => this.art.drawScreen(c, sr, mode, now, f.seed));
   ```

2. Fliperama (`drawFurniture`, linha 796), dentro do `if (seed !== null)`: igual ao item 1, com `'game'` no lugar de
   `mode`, `seed` no lugar de `f.seed` e owner `f`.
3. TV (`drawWallItem`, linha 644), dentro do `if (r)`: owner `w`; retângulo
   `{ x: o.x + r.x, y: o.y + rise + r.y, w: r.w, h: r.h }`; chave
   `` `${mode}|${seed}|${Math.floor(now / SCREEN_MS)}|${rect.x}|${rect.y}` ``.
4. Relógio (`drawWallItem`, linha 622), dentro do `if (r)`: owner `w`; chave pelos segundos de `this.date`:
   `` `${this.date.getHours()}:${this.date.getMinutes()}:${this.date.getSeconds()}|${rect.x}|${rect.y}` ``;
   `paint` = `(c) => this.art.drawClock(c, rect, this.date)`.
5. Vista da janela (`drawWindow`, linha 676): owner `w`; chave
   `` `${w.seed}|${Math.floor(hour * 60)}|${Math.floor(now / WINDOW_MS)}|${rect.x}|${rect.y}` ``;
   `paint` = `(c) => this.art.drawWindowView(c, rect, hour, now, w.seed)`. A linha `ctx.globalAlpha *= alpha;`
   continua antes (vale para o `drawImage` do cache); `drawSeasonalWindow` e o `drawImage` da moldura continuam
   depois, como hoje.

`drawDynamic` já chama `this.safe`. Remova o `this.safe(() => ...)` externo dessas cinco chamadas para não aninhar.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0;
`grep -c "this.drawDynamic(" client/src/world/render/renderer.ts` → `5`;
`grep -n "this.art.drawScreen(ctx\|this.art.drawClock(ctx\|this.art.drawWindowView(ctx" client/src/world/render/renderer.ts` → nenhuma linha.

### Step 4: Medir e comparar a imagem

Rebuild, reinicie o preview e rode 3×:
`SHOT=/tmp/hb-012-depois.png node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 20`.

**Verify**:

- `quadros/s` entre 28 e 31;
- mediana de `cpu` ≤ 93% da mediana do passo 1 (esperado ~88–90%, de ~85% para ~75%);
- abra `/tmp/hb-012-antes.png` e `/tmp/hb-012-depois.png`: telas dos monitores, TV, janelas e relógios aparecem
  (nenhum vazio ou preto onde antes havia imagem, nenhum deslocado 1 px). O conteúdo das telas pode diferir porque o
  tempo passou entre as capturas: compare forma e posição, não o texto rolando.

### Step 5: Changelog

`CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie entre `### Adicionado` e `### Corrigido` se não existir):
`- **Escritório:** telas, janelas e relógios são repintados só quando mudam, em vez de a cada quadro: <cpu antes> →
<cpu depois> de CPU a 30 quadros por segundo (medido com GPU), sem mudança visível.` Use as medianas dos passos 1
e 4.

**Verify**: `git status --short` só mostra `client/src/world/render/renderer.ts` e `CHANGELOG.md` (mais `plans/`).

## Test plan

- Sem teste unitário novo: o cache depende de canvas (DOM), o Vitest roda em `environment: 'node'` e a chave é uma
  concatenação simples. A prova é a medição e a comparação de capturas do passo 4.
- `npm test` → exit 0 (nada existente deve quebrar).

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0
- [ ] `grep -c "this.drawDynamic(" client/src/world/render/renderer.ts` → `5`
- [ ] Mediana de `cpu` ≤ 93% da linha de base, a 28–31 quadros/s, com GPU (3 rodadas cada)
- [ ] Capturas antes/depois sem telas, janelas ou relógios faltando ou deslocados
- [ ] `CHANGELOG.md` atualizado em `## [Não lançado]`
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código.
- `/tmp/habblaud-cpu.mjs` sai com `GPU indisponível`.
- Algum desenho de `client/src/art/dynamic.ts` lê estado do contexto (ex.: `ctx.globalAlpha`, `ctx.getTransform()`)
  ou desenha fora do retângulo recebido: o cache não reproduziria o resultado.
- A captura "depois" mostra uma tela, janela ou relógio em branco, preto ou deslocado.
- A mediana de `cpu` não cai pelo menos 7%: o ganho não se paga. Reverta, marque o plano REJECTED no índice com os
  números e reporte.
- A mudança parece exigir mexer em `client/src/art/**`.

## Maintenance notes

- Um desenho animado novo chamado a cada quadro deve passar por `drawDynamic` com uma chave que inclua **tudo** de
  que ele depende. Esquecer uma dependência na chave congela a imagem (ex.: a vista da janela depende de `hour`).
- `SCREEN_MS` (66 ms) limita a fluidez dos modos com movimento contínuo (`game`, `show`). Se alguém notar a bola do
  futebol "pulando", baixe para 33.
- Com os planos 010/011 (10 quadros/s sem movimento), as telas repintam quase todo quadro, e o ganho ali vem de
  janelas e relógios. O ganho medido aqui vale para os momentos a 30 quadros/s.
- No timelapse (relógio do mundo acelerado), a chave muda a cada quadro e o cache repinta sempre: sem ganho, sem
  perda.
- O `WeakMap` usa os objetos `FurnVis`/`WallVis` como dono. Quando uma sala é reconstruída, eles mudam e o cache
  antigo é coletado sozinho.
- Revisor: confira as 5 chaves (modo, seed, tempo quantizado, posição; hora no caso da janela; segundos no relógio).
