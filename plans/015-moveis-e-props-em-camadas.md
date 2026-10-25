# Plan 015: Móveis do prédio numa camada única e props estáticos do lado de fora em duas camadas (B + C do spike 013)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 47aae29..HEAD -- client/src/world/render/renderer.ts client/src/world/render/scene.ts client/src/world/layout/types.ts client/src/world/pacing.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: L
- **Risk**: MED (muda a ordem de desenho de todos os móveis; erro aparece como móvel por cima ou por baixo de personagem)
- **Depends on**: plans/013-resultado.md (protótipo no branch local `spike/013-lote`, commit `08f9673`)
- **Category**: perf
- **Planned at**: commit `47aae29` (`producao`), 2026-10-25

## Why this matters

O operador usa um MacBook M1 e quer menos CPU com o escritório aberto. Os planos 010/011 já baixaram o ritmo
para 10 quadros/s quando nada anda na tela. O spike 013 (`plans/013-resultado.md`, leia o resumo e a seção "O que
quebrou ou quase quebrou") mediu com GPU, a 30 quadros/s, em 5 pares alternados:

| Variante | CPU (mediana) |
|---|---|
| Base | 73,6% |
| B + C (este plano) | 59,5% (−14,1 pontos; os 5 valores de B+C abaixo do melhor da base) |

Hoje cada quadro desenha cada móvel e cada árvore/cerca/flor com um `drawImage` próprio (≈ 312 + 206 chamadas).
Este plano pinta os móveis-base do prédio uma vez numa camada (C) e os props estáticos do lado de fora em duas
camadas (B), redesenhando só os móveis que precisam ficar **na frente** de um personagem. O ganho esperado a
10 quadros/s é de ≈ 4,5 pontos (estimado, um terço do de 30); a 30 quadros/s (gente andando, câmera mexendo) é
de ≈ 14.

O protótipo do spike funciona e é a referência deste plano. O trabalho aqui é: portar para o código atual, separar
a lógica pura num módulo testável, corrigir o retângulo de redesenho do personagem e provar por imagem que nada
mudou de lugar.

## Current state

### Arquivos

- `client/src/world/render/renderer.ts` (1300+ linhas): classe `Renderer`. `buildBase()` pinta a área externa
  uma vez; `frame()` monta a lista de entidades (`ents`), ordena por `y` e desenha.
- `client/src/world/render/scene.ts`: `AreaVis`, `FurnVis`, `furnitureSprites`.
- `client/src/world/render/props.ts`: `propSprite(p)` (sprite com âncora `ax`/`ay`).
- `client/src/world/layout/types.ts:159`: `ExteriorProp['kind']` = `'tree' | 'pine' | 'bush' | 'flowers' | 'lamp' |
  'bench' | 'rock' | 'hedge' | 'parasol' | 'totem' | 'paving'`; `whenRoom?` = prop que surge com a sala.
- Protótipo: `git diff 31368cb spike/013-lote -- client/src/world/render/renderer.ts` (326 linhas de diff). O branch
  `spike/013-lote` existe só no repositório local `/home/anderson/Desenvolvimento/habblaud`.

### Invalidação já existente (`renderer.ts:150-183` em `47aae29`)

```ts
    this.theme = theme;
    const assetsChanged = this.builtAssetsVersion !== this.assetsVersion || themeChanged;
    if (this.baseCols !== sim.building.cols || assetsChanged) this.buildBase();
    ...
        if (!cur || cur.layout !== a || assetsChanged) this.areas.set(a.id, buildAreaVis(this.art, a, undefined, this.assets, theme));
    ...
        this.areas.set(room.id, buildAreaVis(this.art, room.layout, room, this.assets, theme));
```

Ou seja: tema, arte e layout novos recriam `AreaVis` (objeto novo) e chamam `buildBase()`. A camada de móveis
compara a **identidade** das áreas estáveis; as camadas de props são refeitas em `buildBase()`. Não precisa de
outra invalidação além do estado dos móveis (`furnState`).

### Lista de entidades (`renderer.ts` em `47aae29`, dentro de `frame`)

```ts
const enum K {
  FurnBase,
  FurnFront,
  Char,
  Prop,
  Car,
}
```

```ts
      for (const f of vis.furniture) {
        const s = anim.mode === 'none' ? 1 : furnitureScale(anim, f.order);
        if (s <= 0.01) continue;
        if (!visible(f.ax - 3 * TILE, f.ay - 4 * TILE, 6 * TILE, 4 * TILE + 4)) continue;
        push(K.FurnBase, f.ay, f, vis, s);
        const fs = furnitureSprites(this.art, f, this.furnState(f, now));
        if (fs?.front) push(K.FurnFront, f.ay + 0.5, f, vis, s);
      }
```

```ts
    ents.sort((a, b) => a.y - b.y || a.k - b.k);
    ...
        case K.FurnBase:
          this.drawFurniture(e.ref as FurnVis, e.area!, e.scale, false, now);
          break;
        case K.FurnFront:
          this.drawFurniture(e.ref as FurnVis, e.area!, e.scale, true, now);
          break;
        case K.Char:
          this.drawCharacter(e.ref as Character, now, sel);
          break;
```

(Confira as linhas exatas com `grep -n "push(K.FurnBase\|case K.Char\|ents.sort" client/src/world/render/renderer.ts`.)

### `drawCharacter` desenha mais que o boneco (`renderer.ts:839-882` em `47aae29`)

```ts
    const x = Math.round(ch.x + ch.offX);
    const y = Math.round(ch.y + ch.offY);
    ...
    if (ch.mode === 'wait') this.drawAlertRing(x, y, now);
    if (sel.agent === ch.id) this.drawRing(x, y, '#7fe3ff', now, true);
    else if (sel.hover === ch.id) this.drawRing(x, y, '#ffffff', now, false);
    if (!ch.seated) {
      const sh = shadowSprite();
      ctx.drawImage(sh.canvas, x - sh.ax, y - sh.ay);
    }
    ...
    if (sprite) {
      const sx = x - sprite.ax;
      const sy = y - sprite.ay;
      ctx.drawImage(sprite.canvas, sx, sy);
      const b = opaqueBounds(sprite.canvas);
      head.bx = sx + b.x;
      head.by = sy + b.y;
      head.bw = b.w;
      head.bh = b.h;
```

Os anéis vão até `ellipse(x, y, 6 + ph * 12, 2.5 + ph * 5)` (alerta, raio até 18 × 7,5) e `8 * k × 3.5 * k`
(seleção). O protótipo usou `ch.x - 24, ch.y - 48, 48, 56`, **sem** `offX/offY`: errado para quem anda na faixa
lateral. Este plano usa a caixa do sprite (`head.bx/by/bw/bh`) unida a uma caixa nos pés `x ± 20, y ± 10`.

### Convenções

- Comentários em português explicando o porquê; JSDoc de uma linha nos campos (veja `cars: Car[] = [];`).
- Sem alocação por quadro no caminho quente: o protótipo reaproveita arrays (`this.cands`, `this.redrawn`,
  `this.stable`). Mantenha.
- Testes Vitest em `environment: 'node'` (sem canvas): só a lógica pura é testável. Exemplo de estilo,
  `client/src/world/render/particles.test.ts`:

  ```ts
  // Partículas: pool fixo (sem crescer), vida útil e física básica.
  import { describe, expect, it } from 'vitest';
  import { Particles } from './particles';

  describe('partículas', () => {
    it('confete some depois da vida útil; pipoca volta a cair', () => {
  ```

- `CHANGELOG.md`: entrada em `## [Não lançado]`, `### Alterado`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Teste novo | `npx vitest run client/src/world/render/furn-layer.test.ts` | todos passam |
| Build do cliente | `npx vite build --config vite.config.ts --outDir <dir> --emptyOutDir` | exit 0 |
| Servir um build | `npx vite preview --config vite.config.ts --outDir <dir> --port <p> --strictPort --host 127.0.0.1` | serve em `http://127.0.0.1:<p>/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar em `127.0.0.1:4747` (`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`), com agentes no escritório.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/render/renderer.ts`
- `client/src/world/render/furn-layer.ts` (criar)
- `client/src/world/render/furn-layer.test.ts` (criar)
- `CHANGELOG.md`

**Out of scope** (do NOT touch):

- `client/src/art/**`, `client/src/world/sim/**`, `client/src/world/layout/**`.
- `client/src/world/pacing.ts` e `client/src/world/index.ts`: só recebem a mudança **temporária** de medição do
  passo 6, que nunca vai para commit.
- O cache de telas (plano 012, rejeitado) e o atlas (variante A do spike, rejeitada).
- `client/src/world/city/**`.

## Git workflow

- `git switch -c advisor/015-moveis-em-camadas producao` (ou um worktree a partir de `producao`).
- Commits no estilo do repositório: assunto em português, minúsculo. Ex.: `móveis do prédio numa camada única`.
  Um commit para a lógica pura + testes, um para B, um para C, um para o changelog.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Lógica pura em `furn-layer.ts`, com testes

Crie `client/src/world/render/furn-layer.ts` (sem DOM, sem import de canvas):

```ts
// Camadas de desenho do escritório: o que pode ser pintado uma vez e o que precisa ser redesenhado por quadro.
// Puro (sem DOM): testado em furn-layer.test.ts.
import type { ExteriorProp } from '../layout/types';

/** Retângulo no mundo. */
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** Item da camada de móveis: profundidade (`ay`) e retângulo do sprite no mundo. */
export interface DepthRect extends Rect {
  ay: number;
}

/**
 * Props do lado de fora que nunca se cruzam com nada que se mexe: vão para as camadas pré-pintadas.
 * Fora: os que surgem com a sala (`whenRoom`), o poste (carro passa na frente), banco, totem e calçamento
 * (perto do caminho do visitante).
 */
export function isStaticProp(p: Pick<ExteriorProp, 'kind' | 'whenRoom'>): boolean {
  return p.whenRoom === undefined && p.kind !== 'lamp' && p.kind !== 'bench' && p.kind !== 'totem' && p.kind !== 'paving';
}

export function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

/**
 * Móveis da camada que precisam ser redesenhados depois de algo desenhado em `hit` com profundidade `depth`:
 * os de `ay` maior que cruzam `hit` e, em cascata, os de `ay` ainda maior que cruzam algum já escolhido (senão o
 * redesenho cobriria um móvel que está na frente dele). `items` vem ordenado por `ay`; `out` é reaproveitado.
 */
export function redrawSet<T extends DepthRect>(items: readonly T[], hit: Rect, depth: number, out: T[]): T[] {
  out.length = 0;
  for (const it of items) {
    if (it.ay <= depth) continue;
    let pick = overlaps(it, hit);
    for (let i = 0; i < out.length && !pick; i++) pick = overlaps(it, out[i]);
    if (pick) out.push(it);
  }
  return out;
}
```

Crie `client/src/world/render/furn-layer.test.ts` com os casos do "Test plan".

**Verify**: `npx vitest run client/src/world/render/furn-layer.test.ts` → todos passam; `npm run typecheck` → exit 0.

### Step 2: B — props estáticos em duas camadas

Porte do protótipo (`git diff 31368cb spike/013-lote -- client/src/world/render/renderer.ts`), usando
`isStaticProp` de `furn-layer.ts` em vez do método estático:

1. Campos `propsBack`/`propsFront` (`HTMLCanvasElement | null`) com o JSDoc do protótipo.
2. `buildPropLayers()` chamado no fim de `buildBase()`; `blitLayer(layer, vx0, vy0, vx1, vy1)`.
3. Em `frame()`: `this.blitLayer(this.propsBack, ...)` logo depois do `drawImage` da base;
   `if (isStaticProp(prop)) continue;` no começo do laço de props; `this.blitLayer(this.propsFront, ...)` logo
   depois do laço de desenho das entidades, antes de `this.drawPingPong(now)`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0. Commit.

### Step 3: C — camada única de móveis

Porte do protótipo, com estas correções:

1. `K.FurnAnim` no `enum K` (logo depois de `FurnBase`), interfaces `LayerItem` (com `ay` vindo de `f.ay`, para
   servir de `DepthRect`) e `FurnLayer`, campos `furnLayer`, `stable`, `redrawn`, `opaqueCopies`.
2. `furnLayerFor(now)` como no protótipo. Ao montar `items`, preencha `ay: f.ay`.
3. Troque `redrawInFront` + `redrawCascade` do protótipo por uma função só:

   ```ts
   /** Depois de desenhar algo em `hit` com profundidade `depth`: redesenha os móveis da camada que ficam na frente. */
   private redrawInFront(hit: Rect, depth: number, now: number): void {
     const l = this.furnLayer;
     if (!l || !overlaps(hit, { x: l.x, y: l.y, w: l.c.width, h: l.c.height })) return;
     for (const it of redrawSet(l.items, hit, depth, this.redrawn)) this.drawFurniture(it.f, it.vis, 1, false, now, false, true);
   }
   ```

   Para não alocar o `{ x, y, w, h }` por chamada, guarde em `FurnLayer` um campo `rect: Rect` preenchido ao criar
   a camada e use `overlaps(hit, l.rect)`.
4. Retângulo do personagem: em `drawCharacter`, depois de preencher `head.bx/by/bw/bh`, **não** mude nada; no
   `case K.Char` do `frame()`, monte o retângulo num campo reaproveitado (`this.hitRect`) como a união de
   `{ head.bx, head.by, head.bw, head.bh }` com `{ x: head.x - 20, y: head.feetY - 10, w: 40, h: 20 }` (anéis e
   sombra) e chame `this.redrawInFront(this.hitRect, e.y, now)`. `head` é `this.headOf(ch)`.
5. `case K.FurnFront`: como no protótipo (redesenha os da frente usando o retângulo do sprite `front`), passando um
   `Rect` reaproveitado.
6. `opaqueOnly` e os parâmetros `overlayOnly`/`hard` de `drawFurniture` como no protótipo.
7. O código do plano 011 (`this.motion = true` no laço de pisos, `drawCharacter`, partículas, pingue-pongue) fica
   como está.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0;
`grep -c "redrawCascade" client/src/world/render/renderer.ts` → `0`. Commit.

### Step 4: Patch de diagnóstico (temporário, nunca commitado)

Para comparar imagem com e sem as camadas no **mesmo** build, aplique (sem commit):

- em `furnLayerFor`, primeira linha: `if ((globalThis as { __noLayers?: boolean }).__noLayers) return (this.furnLayer = null);`
- em `frame()`, troque `if (isStaticProp(prop)) continue;` por
  `if (isStaticProp(prop) && !(globalThis as { __noLayers?: boolean }).__noLayers) continue;` e pule os dois
  `blitLayer` de props quando `__noLayers`.

Build em `/tmp/hb015-diag`, sirva na porta 4792.

Script descartável `/tmp/hb015-diff.mjs` (Playwright, sem GPU basta): abre a página, fecha diálogos com `Escape`,
e para cada sala de `window.habblaud.world.debug.state().rooms` (use `name`): `debug.lookAtRoom(name, 3)`, espera
1,5 s, captura com `__noLayers = false`, espera ~120 ms, captura com `__noLayers = true` e volta para `false`.
Faça toda a comparação **dentro do navegador** (não há `pngjs`, `sharp` nem `pixelmatch` em `node_modules`, e não
instale nada). Copie o canvas visível do mundo para um canvas novo
(`getContext('2d').drawImage(mundo, 0, 0)`) em cada captura, compare os dois `getImageData` pixel a pixel, pinte
os diferentes de vermelho num terceiro canvas e devolva `toDataURL('image/png')` e a contagem. No Node, grave o
PNG em `/tmp/hb015-diff-<sala>.png`. Repita com a visão geral (sem `lookAtRoom`).

**Verify**: para cada imagem de diff, as únicas diferenças aceitas são:

- personagens que andaram entre as duas capturas (pares de silhuetas deslocadas);
- telas de monitor/TV/fliperama e relógios (mudam com o tempo);
- até ~40 pixels por sala em móveis translúcidos na frente de personagens (vidro do bebedouro, sombra de cadeira;
  limitação conhecida do `opaqueOnly`).

Qualquer móvel inteiro, árvore, cerca ou poste diferente é falha: corrija e repita. Se não conseguir em 2
tentativas, STOP.

Confira também, a olho, 3 cenas em que a ordem importa (capturas com o build final, sem `__noLayers`):
personagem sentado atrás de uma mesa (a mesa cobre as pernas), personagem andando na frente de um móvel
(o personagem cobre o móvel) e o carro passando na rua (poste na frente/atrás como antes). Salve como
`/tmp/hb015-cena-{mesa,frente,carro}.png`.

Reverta o patch de diagnóstico (`git diff --stat` não pode mostrar `__noLayers`).

### Step 5: Montagem e desmontagem de sala

Com o build final servido, rode no console via Playwright:

- `debug.spawnSession({ project: 'hb015-sala-nova' })` (`project` = nome da sala; um nome novo monta uma sala
  nova, só no navegador de teste, sem tocar no servidor). Capture 3 vezes durante a montagem (0,5 s, 1,5 s, 3 s)
  e 1 vez depois. A sala deve montar como antes (móveis crescendo um a um) e, ao fim, os móveis ficarem no lugar.
- Pegue o `id` da sala em `debug.state().rooms` (o item com `name === 'hb015-sala-nova'`), rode
  `debug.closeRoom(id)` e capture 2 vezes durante a desmontagem.

**Verify**: nenhuma captura com móvel faltando, duplicado ou fora do lugar. Salve como `/tmp/hb015-montagem-*.png`.

### Step 6: Medir

Medição em pares alternados, a 30 quadros/s, com GPU. Para forçar 30 quadros/s com o escritório parado, aplique
nos **dois** builds (base e final), sem commit, `export const STILL_FPS = 30;` e
`export const STILL_UNFOCUSED_FPS = 30;` em `client/src/world/pacing.ts`.

- Base: `producao` + o patch de 30 qps → build em `/tmp/hb015-base`, porta 4790.
- Final: o branch + o patch de 30 qps → build em `/tmp/hb015-final`, porta 4791.
- Script de CPU: `/tmp/habblaud-cpu.mjs`, copiado da seção "Measurement script" de
  `plans/010-economia-de-energia-10-qps.md` sem mudanças.
- 5 pares alternados de 20 s (`node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 20`, depois 4791, …).
- Depois, sem o patch de 30 qps (ritmo real, 10 quadros/s), mais 5 pares, só como informação.

Reverta o patch de `pacing.ts`.

**Verify**: a 30 qps, a mediana do final fica pelo menos 8 pontos abaixo da mediana da base; todas as rodadas com
`quadros/s` entre 28 e 31; nenhuma `GPU indisponível`. Anote os 20 valores brutos de cada ritmo.

### Step 7: Changelog

`CHANGELOG.md`, `## [Não lançado]`, `### Alterado`:
`- **Escritório:** móveis do prédio e árvores, cercas e flores do lado de fora são pintados uma vez e reaproveitados
a cada quadro, em vez de desenhados um a um: <base> → <final> de CPU a 30 quadros por segundo (medido com GPU).`
Use as medianas do passo 6.

**Verify**: `git status --short` lista só arquivos do "In scope"; `git diff producao --stat` mostra só esses
4 arquivos.

## Test plan

`client/src/world/render/furn-layer.test.ts`, no estilo de `particles.test.ts`:

- `isStaticProp`: `tree`, `hedge`, `flowers` sem `whenRoom` → `true`; `lamp`, `bench`, `totem`, `paving` → `false`;
  `hedge` com `whenRoom: 0` → `false`.
- `redrawSet`, móvel atrás do personagem não volta: item com `ay` menor que `depth` cruzando `hit` → fora.
- `redrawSet`, móvel na frente volta: item com `ay` maior cruzando `hit` → dentro.
- `redrawSet`, móvel na frente mas longe: `ay` maior sem cruzar `hit` nem nenhum escolhido → fora.
- `redrawSet`, cascata: A (`ay` 10) cruza `hit`, B (`ay` 20) não cruza `hit` mas cruza A → os dois, na ordem A, B.
- `redrawSet`, cascata só para frente: C (`ay` 5, maior que `depth` 0) cruza B escolhido depois → C fora se não
  cruza `hit` (a lista vem por `ay`, então C é avaliado antes de B).
- `redrawSet` reaproveita `out`: chamar duas vezes com o mesmo array devolve só o resultado da segunda.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 7 casos de `furn-layer.test.ts`
- [ ] Passo 4: diffs só com as diferenças aceitas; 3 capturas de cena conferidas
- [ ] Passo 5: montagem e desmontagem sem móvel faltando ou fora do lugar
- [ ] Passo 6: mediana a 30 qps ≥ 8 pontos abaixo da base, com os valores brutos no relatório
- [ ] Nenhum `__noLayers` nem `STILL_FPS = 30` no diff final
- [ ] `CHANGELOG.md` atualizado
- [ ] Nenhum arquivo fora do "In scope" modificado
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código.
- `/tmp/habblaud-cpu.mjs` sai com `GPU indisponível`.
- O passo 4 mostra móvel, árvore, cerca ou poste fora de ordem e 2 tentativas de correção não resolvem.
- A montagem ou desmontagem de sala (passo 5) quebra e a correção exige mexer em `client/src/world/sim/**`.
- O passo 6 dá menos de 8 pontos a 30 qps: reverta tudo, marque o plano REJECTED com os números e reporte.
- A correção pede mexer em `client/src/art/**` ou em `client/src/world/layout/**`.

## Maintenance notes

- Todo móvel novo cujo sprite depende de estado precisa entrar na lista `dyn` da camada (hoje `coffee_machine` e
  `toilet_stall`, os mesmos de `furnState`). Esquecer congela o sprite velho.
- Todo móvel novo com parte animada (como a tela da mesa) precisa entrar na condição de `K.FurnAnim`.
- Todo tipo de prop novo do lado de fora que cruze carro ou visitante precisa sair de `isStaticProp`.
- Qualquer coisa nova desenhada entre os móveis (como `K.Char`) que possa ficar atrás de um móvel precisa chamar
  `redrawInFront` com o seu retângulo.
- Limitação aceita: pixels translúcidos de móvel na frente de personagem não são redesenhados (`opaqueOnly`).
- Revisor: confira o retângulo do personagem (sprite + pés, com `offX/offY`), a ausência de alocação por quadro
  em `redrawInFront`, e que o caminho antigo continua para áreas em montagem/desmontagem.
