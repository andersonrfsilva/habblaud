# Plan 013 (spike): Medir se móveis e props do lado de fora desenhados em lote baixam a CPU, e qual desenho vale implementar

> **Executor instructions**: Este é um plano de **investigação**. O produto é um relatório
> (`plans/013-resultado.md`) com números e uma recomendação. Protótipos ficam num branch descartável e **não** entram
> na `main`. Follow this plan step by step. Run every verification command and confirm the expected result before
> moving to the next step. If anything in the "STOP conditions" section occurs, stop and report — do not improvise.
> When done, update the status row for this plan in `plans/README.md` — unless a reviewer dispatched you and told you
> they maintain the index.
>
> **Drift check (run first)**: `git diff --stat 31368cb..HEAD -- client/src/world/render/renderer.ts client/src/world/layout/exterior.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: LOW (só protótipo e relatório; nada vai para a `main`)
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `31368cb` (`fork/main`), 2026-10-25

## Why this matters

Com GPU de verdade (1440×900 em densidade 2, o caso de um Mac), cada quadro do escritório faz ≈ 1700 chamadas de
canvas. Dessas, ≈ 312 vêm de `drawFurniture` (móveis) e ≈ 206 de `drawProp` (árvores, cercas vivas, flores,
postes do lado de fora). Num experimento de 2026-10-25, tirar móveis e props do quadro baixou a CPU total de **85%
para 59% a 30 quadros/s (−30%)**; a CPU do processo da GPU caiu de 45,6% para 31,7%. É o maior pedaço
"de conteúdo" do quadro, mas o experimento só mostra o limite superior. Os móveis entram na ordenação por
profundidade com os personagens (quem está atrás da mesa fica escondido por ela). Por isso não dá para simplesmente
pré-desenhá-los numa imagem só.

Um segundo experimento colocou todos os sprites pequenos num atlas (uma textura grande, via script injetado, sem
mudar o código). Ele deu 70,7% e 68,5% contra 71,8% e 82,6% sem atlas, uma diferença menor que o ruído da medição.
Este spike mede com rigor as três saídas possíveis e recomenda uma, ou nenhuma.

## Current state

Arquivos:

- `client/src/world/render/renderer.ts` — classe `Renderer`. `frame()` monta a lista de entidades
  (móveis, personagens, props, carros), ordena por `y` e desenha na ordem.
- `client/src/world/layout/exterior.ts` — onde os props do lado de fora são criados (`props.push({ kind, x, y, seed,
  whenRoom? })`).
- `client/src/world/layout/types.ts:159` — tipos de prop: `'tree' | 'pine' | 'bush' | 'flowers' | 'lamp' | 'bench'
  | 'rock' | 'hedge' | 'parasol' | 'totem' | 'paving'`.

`client/src/world/render/renderer.ts:418-430` (móveis entram na lista de profundidade):

```ts
    for (const vis of this.areas.values()) {
      const p = vis.px;
      if (!visible(p.x - TILE, p.y - 3 * TILE, p.w + 2 * TILE, p.h + 4 * TILE)) continue;
      const anim = vis.room ? buildAnim(vis.room.phase, vis.room.progress(now)) : NO_ANIM;
      for (const f of vis.furniture) {
        const s = anim.mode === 'none' ? 1 : furnitureScale(anim, f.order);
        if (s <= 0.01) continue;
        if (!visible(f.ax - 3 * TILE, f.ay - 4 * TILE, 6 * TILE, 4 * TILE + 4)) continue;
        push(K.FurnBase, f.ay, f, vis, s);
        const fs = furnitureSprites(this.art, f, this.furnState(f, now));
        if (fs?.front) push(K.FurnFront, f.ay + 0.5, f, vis, s);
      }
    }
```

`client/src/world/render/renderer.ts:444-458` (props do lado de fora, alguns aparecem junto com a sala: `whenRoom`):

```ts
    for (const prop of this.exterior.props) {
      if (!visible(prop.x - 24, prop.y - 56, 48, 60)) continue;
      let alpha = 1;
      if (prop.whenRoom !== undefined) {
        // ex.: cerca viva diante do caminho do jardim — surge junto com a sala
        const room = this.roomInSlot(prop.whenRoom);
        if (!room) continue;
        ...
      }
      push(K.Prop, prop.y, prop, null, alpha);
    }
```

`client/src/world/render/renderer.ts:762-784` (estado do móvel varia com o tempo; o sprite muda):

```ts
  private furnState(f: FurnVis, now: number): number {
    if (f.kind === 'coffee_machine') return (this.sim.machineUntil.get(f.id) ?? 0) > now ? 1 : 0;
    if (f.kind === 'toilet_stall') return this.sim.stallBusy.has(f.id) ? 1 : 0;
    return 0;
  }

  private drawFurniture(f: FurnVis, area: AreaVis, scale: number, front: boolean, now: number): void {
    const { ctx } = this;
    const state = this.furnState(f, now);
    const fs = furnitureSprites(this.art, f, state);
    if (!fs) return;
    const s = front ? fs.front : seasonalFurniture(this.theme, f.kind, area.layout, f.seed) ?? fs.base;
    ...
    ctx.drawImage(s.canvas, dx, dy);
```

Depois do `drawImage`, `drawFurniture` desenha por cima a tela do monitor (`drawScreen`), a fumaça do café e a tela
do fliperama: partes animadas que continuam por quadro em qualquer variante.

A camada base (`buildBase`, linhas 182-258) já é um exemplo de pré-desenho: área externa, pátios e fachadas pintados
uma vez num canvas grande e desenhados com um único `drawImage` recortado à parte visível (linhas 360-366).

## Variantes a medir

- **A — atlas**: os sprites pequenos copiados numa textura grande e desenhados com o `drawImage` de 9 argumentos.
  Sem mudar o código: medido com o script injetado `/tmp/atlas-init.js` abaixo.
- **B — props em camadas**: os props do lado de fora que não se cruzam com nada que se mexe pintados uma vez em
  camadas, como a base. Personagens ficam dentro do prédio; carros andam na rua. Por exemplo: uma camada de props
  ao norte do prédio desenhada antes das áreas e outra ao sul desenhada depois das entidades. Os carros e os props
  com `whenRoom` (que aparecem com fade) continuam na lista de profundidade. Descubra no protótipo se a ordem fica
  certa: árvore do sul por cima da parede sul, carro passando na frente ou atrás dos postes.
- **C — móveis em camada por área, com redesenho local**: os móveis-base de cada área pintados uma vez num canvas por
  área, refeito quando o layout, o tema ou o `furnState` de algum móvel muda. No quadro, a camada vai logo depois do
  piso e das paredes. Para manter a profundidade, depois de cada personagem, redesenhe só os móveis com `ay` maior
  que o `y` dele cujo retângulo cruza o dele (os que deveriam ficar na frente). Partes animadas (telas, fumaça) e
  `FurnFront` continuam por quadro. Durante a montagem da sala (`anim.mode !== 'none'`), use o caminho atual.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort --host 127.0.0.1` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747`, com agentes no escritório. Se não estiver, STOP.

## Measurement scripts (temporários, fora do repositório)

1. `/tmp/habblaud-cpu.mjs`: copie da seção "Measurement script" de `plans/010-economia-de-energia-10-qps.md` e
   acrescente, logo antes de `const page = await ctx.newPage();`, a linha:

   ```js
   if (process.env.ATLAS) await ctx.addInitScript({ path: '/tmp/atlas-init.js' });
   ```

2. `/tmp/atlas-init.js` (variante A):

   ```js
   (() => {
     const P = CanvasRenderingContext2D.prototype;
     const orig = P.drawImage;
     let cur = null; let x = 0, y = 0, rowH = 0; const S = 2048;
     const map = new WeakMap();
     const place = (src) => {
       const w = src.width, h = src.height;
       if (!cur || x + w > S) { x = 0; y += rowH; rowH = 0; }
       if (!cur || y + h > S) { cur = document.createElement('canvas'); cur.width = S; cur.height = S; x = 0; y = 0; rowH = 0; }
       orig.call(cur.getContext('2d'), src, x, y);
       const e = { page: cur, sx: x, sy: y };
       x += w + 1; rowH = Math.max(rowH, h + 1);
       return e;
     };
     P.drawImage = function (src, ...a) {
       if (src instanceof HTMLCanvasElement && !src.isConnected && src.width > 0 && src.width <= 256 && src.height <= 256 && this.canvas.isConnected) {
         let e = map.get(src);
         if (!e) { e = place(src); map.set(src, e); }
         if (a.length === 2) return orig.call(this, e.page, e.sx, e.sy, src.width, src.height, a[0], a[1], src.width, src.height);
         if (a.length === 4) return orig.call(this, e.page, e.sx, e.sy, src.width, src.height, a[0], a[1], a[2], a[3]);
         if (a.length === 8) return orig.call(this, e.page, e.sx + a[0], e.sy + a[1], a[2], a[3], a[4], a[5], a[6], a[7]);
       }
       return orig.call(this, src, ...a);
     };
   })();
   ```

   Limitação conhecida: um sprite repintado depois de entrar no atlas aparece velho. Isso serve para medir custo, não
   para entregar.

**Ruído**: na mesma máquina e no mesmo minuto, a linha de base variou de 72% a 83%, conforme o que os agentes faziam.
Meça sempre **alternando** A/B (base, variante, base, variante…), 5 pares de 20 s, e compare as medianas. Uma
diferença menor que 8 pontos é ruído.

## Scope

**In scope**:

- Branch descartável `spike/013-lote` com protótipos em `client/src/world/render/renderer.ts` (e, se a variante B
  pedir, `client/src/world/layout/exterior.ts`).
- `plans/013-resultado.md` (criar): o relatório.
- `plans/README.md`: a linha do 013.

**Out of scope** (do NOT touch):

- Merge do protótipo em qualquer branch que não seja `spike/013-lote`.
- `client/src/art/**`: os sprites não mudam.
- Ritmo do laço (`pacing.ts`, `index.ts`): planos 010 e 011.
- Cache de telas animadas: plano 012.

## Git workflow

- `git fetch fork && git switch -c spike/013-lote fork/main`. Commits só nesse branch, assunto em português,
  minúsculo, prefixo `spike:`.
- O relatório `plans/013-resultado.md` e a linha do índice vão para o branch em que estiverem os `plans/` (o
  operador decide). Não misture com o código do protótipo.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Linha de base e variante A (sem código)

`npx vite build --config vite.config.ts`, suba o preview e rode 5 pares alternados de 20 s:
`node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 20` e `ATLAS=1 node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 20`.
Na última rodada de cada, use `SHOT=/tmp/hb-013-base.png` e `SHOT=/tmp/hb-013-A.png`.

**Verify**: 10 linhas `cpu=… quadros/s=30.0`; as duas capturas mostram o escritório igual (móveis, árvores e
personagens no lugar). Anote as medianas.

### Step 2: Limite superior (sem móveis, sem props)

No branch `spike/013-lote`, num commit `spike: limite superior sem móveis e props`, pule `K.FurnBase`, `K.FurnFront`
e `K.Prop` no `switch` de desenho de `frame()` (linhas 477-495). Meça 5 pares alternados contra a linha de base
(dois builds: guarde o `dist/client` da base numa pasta, por exemplo `cp -r dist/client /tmp/hb-013-base`, e sirva
cada um com `npx vite preview --outDir <pasta> --host 127.0.0.1`, em portas 4790 e 4791). Depois reverta esse commit.

**Verify**: a mediana cai pelo menos 15 pontos (o experimento de 2026-10-25 deu −26). Se cair menos de 8, os móveis
e props não são o gargalo nesta máquina ou neste escritório: pule para o passo 5 e recomende não implementar nada.

### Step 3: Variante B (props em camadas)

Protótipo como descrito em "Variantes a medir". Meça 5 pares alternados contra a base e salve
`SHOT=/tmp/hb-013-B.png`. Confira na captura: árvores e cercas no mesmo lugar e na mesma ordem em relação às paredes,
aos carros e aos postes.

**Verify**: `npm run typecheck` → exit 0; medianas anotadas; captura conferida.

### Step 4: Variante C (móveis em camada por área)

Protótipo como descrito em "Variantes a medir". Antes de medir, faça uma captura com personagens atrás de mesas:
`window.habblaud.world.debug.lookAtRoom('<nome de uma sala com agentes>', 3)` e depois `SHOT`. Compare com a mesma
cena na base: quem está atrás da mesa continua coberto pela mesa, e quem está na frente continua na frente. Meça 5
pares alternados contra a base, na visão geral (sem `lookAtRoom`).

**Verify**: `npm run typecheck` → exit 0; capturas base/C da sala com agentes iguais na ordem de profundidade;
medianas anotadas.

### Step 5: Relatório

Crie `plans/013-resultado.md` com:

- tabela: variante, mediana de CPU (5 rodadas), diferença contra a base, quadros/s;
- para B e C: o que quebrou ou quase quebrou na ordem de profundidade, e como o protótipo resolveu;
- linhas de código do protótipo (`git diff --stat fork/main spike/013-lote`);
- recomendação: qual variante implementar (ou nenhuma), com o ganho esperado a 30 quadros/s e a 10 quadros/s
  (planos 010/011). O ganho a 10 quadros/s é ~1/3 do ganho a 30, porque o custo é por quadro;
- se recomendar uma variante: um esboço de plano de implementação (arquivos, passos, riscos), para o advisor
  transformar no plano 014.

**Verify**: `test -s plans/013-resultado.md` → exit 0; a tabela tem as linhas base, A, limite superior, B e C.

## Test plan

- Sem testes novos: é um spike. Os protótipos precisam passar em `npm run typecheck` e `npm test` só para que a
  medição valha (código quebrado mede outra coisa).

## Done criteria

- [ ] `plans/013-resultado.md` existe com a tabela das 5 linhas e uma recomendação
- [ ] Toda medição foi feita com GPU (nenhuma saída `GPU indisponível`) e em pares alternados
- [ ] Nenhum commit do protótipo fora de `spike/013-lote` (`git branch --contains <commit>` só lista o spike)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- `/tmp/habblaud-cpu.mjs` sai com `GPU indisponível`.
- O escritório real não tem agentes (capturas sem personagens): a variante C não pode ser conferida.
- A variante C exige mexer em `client/src/art/**` ou na simulação (`client/src/world/sim/**`).
- O passo 2 mostra menos de 8 pontos de diferença: escreva o relatório (passo 5) recomendando não implementar e pare.

## Maintenance notes

- Se a variante C for adiante, todo móvel novo com estado que muda (como `coffee_machine` e `toilet_stall` em
  `furnState`) precisa invalidar a camada da área. Esquecer isso congela o sprite velho.
- Os números deste spike são de uma Intel UHD 630 no Linux. Num Mac (Metal, via ANGLE), a proporção entre o custo
  por chamada e o custo fixo por quadro pode ser outra. Se o operador tiver um Mac, repita o passo 1 lá.
