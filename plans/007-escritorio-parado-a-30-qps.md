# Plan 007: Escritório parado desenha a 30 qps (15 sem foco); 60 qps só com a câmera em movimento ou com entrada

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 7af1def..HEAD -- client/src/world/index.ts client/src/world/pacing.ts client/src/world/pacing.test.ts client/src/ui/index.ts CHANGELOG.md docs/terminal-celular-desempenho.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: para ele, só confira que a seção
> `## [Não lançado]` ainda existe.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `7af1def`, 2026-10-25

## Why this matters

O escritório (canvas do mundo) é redesenhado inteiro a cada `requestAnimationFrame`, ou seja, 60 vezes por segundo
num monitor de 60 Hz, mesmo quando a câmera está parada e só há personagens digitando. Com o mundo pausado a página
gasta 0–3% de CPU; com ele rodando, 71% de um núcleo (computador 1440×900, densidade 1×) e 94% (celular 393×851,
densidade 3×), medidos no Chromium sem placa de vídeo. Limitar o laço a 30 quadros por segundo (qps) cortou a CPU pela
metade nas medições (71% → 36%; 94% → 51%). A animação em pixel art continua legível a 30 qps (andar = 3,2 tiles/s);
arrastar, dar zoom e a câmera animada ou seguindo alguém continuam a 60 qps, que é onde 30 qps apareceria como tranco.
Com a janela sem foco (o escritório num segundo monitor enquanto se trabalha em outra janela), o limite cai para
15 qps; o ganho extra é pequeno (medido: 36% → 31% no computador), mas é o caso de uso mais comum.

## Current state

Arquivos:

- `client/src/world/index.ts` — `createWorld`: laço `loop` (rAF), câmera, entrada e o objeto `WorldApi`.
- `client/src/world/camera.ts` — `Camera` (`x`, `y`, `zoom`, `follow`, getter `animating`). Não muda neste plano.
- `client/src/world/input.ts` — `attachInput(canvas, camera, handlers)`; chama `handlers.interact()` no início de um
  arrasto, na pinça, na roda do mouse e nas teclas de câmera. Não muda neste plano.
- `client/src/ui/index.ts` — `renderAll()` chama `world.setFrameCap?.(terminal.isOpen ? 30 : 0)` a cada renderização
  da interface (o terminal aberto em janela já limita o escritório a 30 qps). Não muda neste plano.

`client/src/world/index.ts:74-85` (estado do laço):

```ts
  let raf = 0;
  let lastTs = 0;
  let hiddenAt = 0;
  /** Escritório fora da tela (cidade aberta): sem laço de desenho nem teclado da câmera. */
  let suspended = false;
  let suspendedAt = 0;
  /** Limite de quadros por segundo (0 = o do monitor). */
  let frameCap = 0;
  let userMoved = false;
  let framed = false;
  let lastCols = -1;
  const abort = new AbortController();
```

`client/src/world/index.ts:225-247` (entrada):

```ts
  const detachInput = attachInput(canvas, camera, {
    pick,
    click: (hit, at) => (visitMode ? visitClick(hit, at) : setSelection(hit)),
    ...
    interact: () => {
      userMoved = true;
      camera.follow = null;
      camera.stop();
    },
    overview: () => overview(true),
    zoomStep,
    active: () => !suspended,
    keys: () => !visitMode,
  });
```

`client/src/world/index.ts:276-291` (início do laço):

```ts
  const loop = (ts: number) => {
    raf = requestAnimationFrame(loop);
    // 2 ms de folga: com 30 qps num monitor de 60 Hz, o quadro de 33,3 ms não pode cair para o seguinte.
    if (frameCap && lastTs && ts - lastTs < 1000 / frameCap - 2) return;
    const real = Date.now();
    const realDt = lastTs ? Math.min(0.1, Math.max(0, (ts - lastTs) / 1000)) : 1 / 60;
    lastTs = ts;
    // Timelapse: simulação e desenho no relógio do mundo (acelerado ou parado); a câmera segue no tempo real.
    const k = playback ? Math.max(0, playback.scale()) : 1;
    const dt = realDt * k;
    if (playback) worldNow += dt * 1000;
    const now = playback ? worldNow : real;
    try {
```

`client/src/world/index.ts:309-313` (câmera e desenho, dentro do `try`):

```ts
      const followCh = camera.follow ? sim.person(camera.follow) : undefined;
      if (camera.follow && !followCh) camera.follow = null;
      camera.update(real, followCh ? camera.centerFor(followCh.x, followCh.y - 12, camera.zoom) : null, realDt);
      renderer.frame(now, dt, options, { agent: selection?.type === 'agent' ? selection.id : null, room: selection?.type === 'room' ? selection.id : null, hover });
      overlay.draw(now, options, { agent: selection?.type === 'agent' ? selection.id : null, room: selection?.type === 'room' ? selection.id : null, hover });
```

`client/src/world/index.ts:471-473`:

```ts
    setFrameCap: (fps) => {
      frameCap = Math.max(0, fps);
    },
```

`client/src/world/index.ts:474-481` (`destroy`) remove os ouvintes `resize`, `contextmenu` e `visibilitychange`
um a um; ouvintes novos devem usar `{ signal: abort.signal }`, como o de `document.fonts` na linha 272:

```ts
  if (document.fonts) document.fonts.addEventListener?.('loadingdone', () => overlay.resetCaches(), { signal: abort.signal });
```

Convenções do repositório:

- Comentários e nomes de teste em português, frases curtas explicando o porquê (veja os comentários acima).
- Funções puras ficam em módulos sem DOM para serem testadas no Vitest com `environment: 'node'`
  (`vitest.config.ts`). Exemplo de teste a imitar: `client/src/world/render/overlay.test.ts` (importa funções puras e
  usa `describe`/`it`/`expect` do `vitest`).
- Toda mudança visível entra em `CHANGELOG.md`, seção `## [Não lançado]` (hoje com `### Adicionado` e
  `### Corrigido`; crie `### Alterado` entre as duas se precisar, no formato Keep a Changelog).
- Este plano não cria texto visível na interface, então não precisa de `tr()` nem de traduções.

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `npm run typecheck` | exit 0, sem erros |
| Testes (filtro) | `npx vitest run client/src/world/pacing.test.ts` | todos passam |
| Testes | `npm test` | exit 0 |
| i18n | `npm run i18n:check -- --strict` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort` | serve em `http://127.0.0.1:4790/`, repassando `/api` ao servidor em `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747` para a medição (`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`). Se não estiver, STOP.

## Measurement script (temporário, fora do repositório)

Crie `/tmp/habblaud-meas.mjs` com o conteúdo abaixo. Ele abre a página no Chromium sem placa de vídeo, fecha o aviso
da primeira visita (Esc) e mede a CPU da página e os quadros desenhados por segundo (conta o `fillRect` que limpa o
canvas inteiro no começo de cada quadro, `client/src/world/render/renderer.ts:331`). `UNFOCUSED=1` simula a janela sem
foco; `ACTIVE=1` move o mouse sobre o escritório durante a medição.

```js
// Uso (na raiz do repositório): node /tmp/habblaud-meas.mjs <url> <largura> <altura> <dpr> [segundos]
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const [, , url = 'http://127.0.0.1:4790/', w = '1440', h = '900', dpr = '1', secs = '5'] = process.argv;
const browser = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium', args: ['--disable-gpu', '--disable-accelerated-2d-canvas'] });
const ctx = await browser.newContext({ viewport: { width: +w, height: +h }, deviceScaleFactor: +dpr });
const page = await ctx.newPage();
await page.addInitScript((unfocused) => {
  if (unfocused) Document.prototype.hasFocus = () => false;
  const st = (window.__m = { frames: 0, calls: 0 });
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
}, !!process.env.UNFOCUSED);
await page.goto(url, { waitUntil: 'load' });
await page.waitForTimeout(4000);
for (let i = 0; i < 3; i++) { await page.keyboard.press('Escape'); await page.waitForTimeout(300); }
await page.waitForTimeout(1500);
const cdp = await ctx.newCDPSession(page);
await cdp.send('Performance.enable');
const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
const s0 = await page.evaluate(() => ({ ...window.__m }));
const m0 = await metrics();
const end = Date.now() + +secs * 1000;
if (process.env.ACTIVE) {
  let i = 0;
  while (Date.now() < end) { await page.mouse.move(+w / 2 + (i++ % 2) * 40, +h / 2); await page.waitForTimeout(100); }
} else await page.waitForTimeout(+secs * 1000);
const m1 = await metrics();
const s1 = await page.evaluate(() => ({ ...window.__m }));
const wall = m1.Timestamp - m0.Timestamp;
const frames = s1.frames - s0.frames;
const size = await page.evaluate(() => { const c = document.querySelector('canvas'); return `${c.width}x${c.height}`; });
console.log(`canvas=${size} cpu=${((100 * (m1.TaskDuration - m0.TaskDuration)) / wall).toFixed(0)}% quadros/s=${(frames / wall).toFixed(1)} chamadas/quadro=${((s1.calls - s0.calls) / Math.max(1, frames)).toFixed(0)}`);
await browser.close();
```

Os números variam com a carga da máquina: rode cada medição 2 vezes e anote as duas.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/pacing.ts` (create)
- `client/src/world/pacing.test.ts` (create)
- `client/src/world/index.ts`
- `CHANGELOG.md`
- `docs/terminal-celular-desempenho.md` (uma linha na tabela "O que mudou"; veja o passo 5)

**Out of scope** (do NOT touch, even though they look related):

- `client/src/ui/index.ts` — o `setFrameCap(terminal.isOpen ? 30 : 0)` continua igual; ele é o limite *da
  interface* e este plano combina com ele, sem substituí-lo.
- `client/src/world/input.ts` e `client/src/world/camera.ts` — o plano só lê o que eles já expõem.
- `client/src/world/city/**` e `client/src/world/visit/**` — a cidade tem laço próprio; fica para depois.
- Trocar o "pular quadros do rAF" por `setTimeout` + rAF: foi medido e não economiza nada a mais (36–39% nos dois
  jeitos a 30 qps). Mantenha o mecanismo atual.
- `client/src/world/render/**` e `client/src/art/**`.

## Git workflow

- Branch: `advisor/007-escritorio-30-qps`
- Commits no estilo do repositório: assunto em português, minúsculo, com prefixo de área quando houver. Exemplo do
  `git log`: `escritório a 30 qps com o terminal aberto fora do celular (setFrameCap)`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

Na branch nova, antes de mudar código: `npx vite build --config vite.config.ts`, suba o preview (comando da tabela;
deixe rodando em outro terminal) e rode:

- `node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1` (2×)
- `UNFOCUSED=1 node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1` (2×)

**Verify**: as duas medições mostram `quadros/s` perto de 60 (≥ 50) e `cpu` bem acima de 40%. Anote os números. Se
`quadros/s` já estiver perto de 30 sem nenhuma mudança, STOP (alguém já limitou o laço).

### Step 2: Criar a regra pura `frameCapFor`

Crie `client/src/world/pacing.ts`:

```ts
// Ritmo do laço do escritório: 60 qps só quando a câmera se mexe ou há entrada; parado, 30 (15 sem foco).
// Puro (sem DOM): testado em pacing.test.ts.

/** Escritório parado, janela com foco. */
export const IDLE_FPS = 30;
/** Escritório parado, janela sem foco (ex.: num segundo monitor). */
export const UNFOCUSED_FPS = 15;
/** Por quanto tempo depois de uma entrada (mouse, roda, toque, teclado) o laço segue no ritmo do monitor. */
export const ACTIVE_GRACE_MS = 1000;

/**
 * Limite de quadros por segundo (0 = o do monitor). `uiCap` é o limite pedido pela interface (`setFrameCap`, ex.: 30
 * com o terminal aberto); `active` = câmera em movimento, entrada recente ou timelapse.
 */
export function frameCapFor(s: { uiCap: number; active: boolean; focused: boolean }): number {
  if (s.active) return s.uiCap;
  const idle = s.focused ? IDLE_FPS : UNFOCUSED_FPS;
  return s.uiCap > 0 ? Math.min(s.uiCap, idle) : idle;
}
```

**Verify**: `npm run typecheck` → exit 0.

### Step 3: Testar a regra

Crie `client/src/world/pacing.test.ts` no padrão de `client/src/world/render/overlay.test.ts`, cobrindo:

1. ativo sem limite da interface → `0`; ativo com `uiCap: 30` → `30` (o limite da interface vale mesmo em movimento);
2. parado com foco → `30`; parado sem foco → `15`;
3. parado sem foco com `uiCap: 30` → `15` (vale o menor);
4. parado com foco e `uiCap: 10` → `10`.

**Verify**: `npx vitest run client/src/world/pacing.test.ts` → 4 testes (ou mais) passando.

### Step 4: Usar a regra no laço

Em `client/src/world/index.ts`:

1. Importe `ACTIVE_GRACE_MS` e `frameCapFor` de `./pacing`.
2. Junto do estado do laço (linhas 74-85), acrescente:

   ```ts
   /** Última entrada do usuário no escritório (performance.now()): o laço segue a 60 qps por ACTIVE_GRACE_MS. */
   let lastInputAt = 0;
   /** A câmera se mexeu no último quadro desenhado (animação, seguir, arrastar, pinça). */
   let cameraMoved = false;
   ```

3. Marque a entrada: dentro do handler `interact` (linhas 238-242) acrescente `lastInputAt = performance.now();`.
   Logo depois de `attachInput(...)`, registre também (o arrasto e a pinça só chamam `interact` no começo; o hover do
   mouse não chama nada):

   ```ts
   // Arrasto, pinça e hover não passam por interact() a cada movimento: o laço fica a 60 qps enquanto houver entrada.
   const markInput = () => (lastInputAt = performance.now());
   canvas.addEventListener('pointermove', markInput, { signal: abort.signal, passive: true });
   canvas.addEventListener('pointerdown', markInput, { signal: abort.signal, passive: true });
   ```

   Em `zoomStep` (linhas 178-181), em `focusAgent`, `focusRoom` e `overview` **não** é preciso marcar nada: eles
   iniciam `camera.animateTo`, e a câmera em movimento já conta (item 5).
4. Troque a linha do limite no começo do `loop` (linha 279) por:

   ```ts
    const cap = frameCapFor({
      uiCap: frameCap,
      active: cameraMoved || camera.animating || !!playback || performance.now() - lastInputAt < ACTIVE_GRACE_MS,
      focused: document.hasFocus(),
    });
    // 2 ms de folga: com 30 qps num monitor de 60 Hz, o quadro de 33,3 ms não pode cair para o seguinte.
    if (cap && lastTs && ts - lastTs < 1000 / cap - 2) return;
   ```

5. Calcule `cameraMoved` em volta do `camera.update(...)` (linha 311):

   ```ts
      const cx = camera.x;
      const cy = camera.y;
      const cz = camera.zoom;
      camera.update(real, followCh ? camera.centerFor(followCh.x, followCh.y - 12, camera.zoom) : null, realDt);
      // seguir alguém andando ou a câmera deslizando: 30 qps aí viraria tranco
      cameraMoved = Math.abs(camera.x - cx) + Math.abs(camera.y - cy) > 0.01 || Math.abs(camera.zoom - cz) > 1e-4;
   ```

   O arrasto (`camera.panBy` em `input.ts`) muda a câmera fora do laço; ele já está coberto por `lastInputAt`.
6. Atualize o comentário de `frameCap` (linha 80) para: `/** Limite pedido pela interface (0 = nenhum); o laço ainda
   aplica o ritmo de escritório parado (pacing.ts). */`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0.

### Step 5: Medir de novo, changelog e docs

Rebuild (`npx vite build --config vite.config.ts`), reinicie o preview e repita as medições do passo 1, mais uma com
entrada:

- `node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1` (2×) → `quadros/s` entre 27 e 33; `cpu` cerca de
  metade da linha de base.
- `UNFOCUSED=1 node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1` (2×) → `quadros/s` entre 13 e 17.
- `ACTIVE=1 node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 1` (2×) → `quadros/s` ≥ 50.

Depois:

- `CHANGELOG.md`, `## [Não lançado]`: em `### Alterado` (crie entre `### Adicionado` e `### Corrigido` se não
  existir) um item:
  `- **Escritório mais leve:** parado, o escritório é desenhado a 30 quadros por segundo (15 com a janela sem foco),
  cerca de metade da CPU; arrastar, dar zoom e a câmera seguindo alguém continuam no ritmo do monitor.`
- `docs/terminal-celular-desempenho.md`, tabela "O que mudou": uma linha nova com o hash do seu commit e
  `O escritório parado vai a 30 qps (15 sem foco) em qualquer tela; 60 qps só com a câmera em movimento, entrada
  recente ou timelapse (client/src/world/pacing.ts)`. Acrescente as medições do passo 1 e deste passo na seção
  "Medições" como uma tabela curta (antes/depois, com foco/sem foco/com entrada).

**Verify**: os três intervalos acima batem; `npm run i18n:check -- --strict` → exit 0; `git status` só mostra os
arquivos do escopo.

## Test plan

- Novo: `client/src/world/pacing.test.ts` com os 4 casos do passo 3 (modelo: `client/src/world/render/overlay.test.ts`).
- O laço em si (DOM, rAF) não tem teste unitário no repositório; a prova é a medição do passo 5.
- Verificação: `npm test` → exit 0, incluindo os testes novos.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; `client/src/world/pacing.test.ts` existe e passa
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] Medição parado: `quadros/s` entre 27 e 33; sem foco: entre 13 e 17; com entrada: ≥ 50 (2 rodadas cada)
- [ ] `grep -n "frameCapFor" client/src/world/index.ts` → 1 ocorrência no `loop`
- [ ] `CHANGELOG.md` tem o item "Escritório mais leve" em `## [Não lançado]`
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- O código nas linhas citadas em "Current state" não bate com os trechos.
- A medição de linha de base (passo 1) já mostra ~30 quadros/s.
- Com a mudança, `ACTIVE=1` fica abaixo de 50 quadros/s (a detecção de entrada não está funcionando).
- `document.hasFocus()` lança exceção ou não existe em algum ambiente de teste.
- A mudança parece exigir mexer em `client/src/ui/index.ts`, `input.ts`, `camera.ts` ou na cidade.

## Maintenance notes

- Quem adicionar uma animação de câmera nova não precisa fazer nada: qualquer movimento da câmera mantém 60 qps.
  Uma animação *fora* da câmera que precise de 60 qps (ex.: um efeito rápido em tela cheia) teria que entrar na
  condição `active` do `loop`.
- O limite da interface (`setFrameCap`) continua valendo em movimento: com o terminal aberto, arrastar o escritório
  fica a 30 qps (comportamento do commit `46126b7`, mantido de propósito).
- Medição do celular emulado (393×851, 3×) para referência: 94% sem limite → 51% a 30 qps.
- Revisor: confira que `pointermove`/`pointerdown` usam `{ signal: abort.signal }` (sem vazamento no `destroy`/
  `rebuild`) e que o cálculo de `cameraMoved` fica dentro do `try` do laço.
- Adiado: aplicar o mesmo ritmo ao laço da cidade (`client/src/world/city/index.ts:631-640`), que não foi medido.
