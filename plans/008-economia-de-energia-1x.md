# Plan 008: Opção "Economia de energia" desenha o escritório em 1× (pixel art ampliada pela tela)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 7af1def..HEAD -- client/src/world/camera.ts client/src/world/camera.test.ts client/src/world/api.ts client/src/world/index.ts client/src/ui/prefs.ts client/src/ui/prefs.test.ts client/src/ui/settings.ts shared/locales/en.ts shared/locales/es.ts shared/locales/fr.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. Exceções: `CHANGELOG.md` e `shared/locales/*.ts` mudam com frequência;
> para eles basta o arquivo ainda ter a estrutura descrita. Se o plano 007 já entrou, `client/src/world/index.ts`
> terá mudado no `loop` e perto de `attachInput`: isso é esperado; confira só os trechos citados aqui.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: MED (visual)
- **Depends on**: none (independente do plano 007; os dois somam)
- **Category**: perf
- **Planned at**: commit `7af1def`, 2026-10-25

## Why this matters

O custo de cada quadro do escritório é dominado pela quantidade de pixels pintados, não pelo JavaScript (≈1,9 ms de
JS por quadro, o resto é pintar). Hoje o canvas usa a densidade real da tela (`devicePixelRatio`, até 3): num monitor
Retina 1440×900 o canvas tem 2880×1800 pixels; num celular 393×851 com densidade 3, 1179×2553. Desenhar o mesmo
escritório em 1× e deixar o navegador ampliar com `image-rendering: pixelated` mediu:

| Tela | Canvas hoje → 1× | ms por quadro | CPU |
| --- | --- | --- | --- |
| Computador 1440×900, densidade 2 | 2880×1800 → 1440×900 | 34 → 13 | 100% a só 30 qps → 75% a 57 qps |
| Celular 393×851, densidade 3 | 1179×2553 → 393×851 | 15,6 → 7,2 | 94% → 45% |

A pixel art fica igual (cada pixel do mundo vira um bloco inteiro de pixels da tela). O que piora: nomes, balões e
pílulas das salas (desenhados com `fillText` pelo overlay no mesmo canvas) ficam um pouco menos nítidos em telas de
densidade 2 ou 3. Por isso é uma opção desligada por padrão, em Configurações › Escritório, decisão do mantenedor.

## Current state

Arquivos:

- `client/src/world/index.ts` — `createWorld`; `resize()` e o `loop` calculam a densidade e dimensionam o canvas.
- `client/src/world/camera.ts` — `Camera.setView(w, h, dpr)`; os níveis de zoom nítidos dependem de `camera.dpr`
  (`levels()`, linhas 63-74: inteiros em pixels de dispositivo). Também tem funções puras exportadas (ex.:
  `overviewFrame`, linhas 21-32) testadas sem DOM.
- `client/src/world/api.ts` — `WorldOptions` (linhas 7-22) e `DEFAULT_WORLD_OPTIONS` (linhas 156-163).
- `client/src/ui/prefs.ts` — `UiPrefs`, `DEFAULT_PREFS`, `sanitizePrefs`, `worldOptionsFrom`.
- `client/src/ui/settings.ts` — popover de Configurações; grupo "Escritório" com interruptores (`sw(...)`).
- `client/src/world/render/overlay.ts:274-281` — o overlay escreve textos com `ctx.setTransform(dpr, 0, 0, dpr, 0, 0)`
  usando `camera.dpr`: ao mudar `camera.dpr` ele acompanha sozinho. Não muda neste plano.

`client/src/world/index.ts:89-101`:

```ts
  const resize = () => {
    const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
    const w = canvas.clientWidth || window.innerWidth;
    const h = canvas.clientHeight || window.innerHeight;
    const pw = Math.floor(w * dpr);
    const ph = Math.floor(h * dpr);
    if (canvas.width !== pw || canvas.height !== ph) {
      canvas.width = pw;
      canvas.height = ph;
    }
    camera.setView(w, h, dpr);
    updateBounds();
  };
```

`client/src/world/index.ts:288-290` (dentro do `loop`):

```ts
    try {
      const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));
      if (canvas.clientWidth !== camera.viewW || canvas.clientHeight !== camera.viewH || dpr !== camera.dpr) resize();
```

`client/src/world/index.ts:419-422`:

```ts
    setOptions: (o) => {
      options = { ...options, ...o };
      if (o.followSelected !== undefined) camera.follow = o.followSelected && selection?.type === 'agent' ? selection.id : null;
    },
```

`client/src/world/api.ts:18-21` (padrão para campo opcional novo):

```ts
  /** (Opcional, aditivo) Ciclo dia/noite: automático, sempre dia ou sempre noite. Vale sobre `dayNight`. */
  daylight?: DaylightMode;
  /** Tema visual: automático (segue o calendário; ver seasonalThemeFor), nenhum, Natal ou Halloween. */
  theme?: OfficeTheme;
```

`client/src/ui/prefs.ts` — campos (linhas 9-28), padrões (30-43), validação (52-70) e o que vai para o mundo (92-94):

```ts
  worldVisitNotify: boolean;
}
...
  worldVisitNotify: true,
};
...
    worldVisitNotify: bool(o.worldVisitNotify, d.worldVisitNotify),
  };
}
...
export function worldOptionsFrom(p: UiPrefs): Partial<WorldOptions> {
  return { showNames: p.showNames, bubbles: p.bubbles, liveliness: p.liveliness, dayNight: p.daylight !== 'day', daylight: p.daylight, theme: p.theme };
}
```

`client/src/ui/prefs.test.ts:56-59` — este teste usa `toEqual` e vai quebrar ao acrescentar um campo; atualize-o:

```ts
  it('extrai só as opções do mundo', () => {
    expect(worldOptionsFrom({ ...DEFAULT_PREFS, daylight: 'day' })).toEqual({ showNames: true, bubbles: 'important', liveliness: 'normal', dayNight: false, daylight: 'day', theme: 'auto' });
    expect(worldOptionsFrom({ ...DEFAULT_PREFS, daylight: 'night' })).toMatchObject({ dayNight: true, daylight: 'night' });
  });
```

`client/src/ui/settings.ts:202-216` — grupo "Escritório"; `sw(chave, rótulo, dica, ação)` cria um interruptor; com
`flip('chave')` ele alterna a preferência booleana. O `render()` (linhas 285-292) já marca `aria-checked` de todo
interruptor registrado em `this.switches`, então não precisa de código extra lá:

```ts
      h(
        'div',
        { class: 'ui-set-group' },
        h('h3', { text: tr('Escritório') }),
        sw('showNames', tr('Mostrar nomes'), tr('Etiqueta com o nome acima de cada personagem.'), flip('showNames')),
        this.bubbles.row,
        this.liveliness.row,
        this.daylight.row,
        sw(
          'ompRoom',
          ...
        ),
      ),
```

Traduções: todo texto visível passa por `tr('texto em PT')`; as traduções ficam em `shared/locales/en.ts`, `es.ts` e
`fr.ts`, objetos com a frase em português como chave. Exemplo (`shared/locales/en.ts:661`):
`"Mostrar nomes": "Show names",`. O CI roda `npm run i18n:check -- --strict`, que falha se faltar tradução.

Convenção de changelog: toda mudança visível entra em `CHANGELOG.md`, seção `## [Não lançado]`, subseção
`### Adicionado` (já existe).

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes (filtro) | `npx vitest run client/src/world/camera.test.ts client/src/ui/prefs.test.ts` | todos passam |
| Testes | `npm test` | exit 0 |
| i18n | `npm run i18n:check -- --strict` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747` para a medição
(`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`). Se não estiver, STOP.

## Measurement script (temporário, fora do repositório)

Crie `/tmp/habblaud-meas.mjs`. Ele abre a página no Chromium sem placa de vídeo, fecha o aviso da primeira visita
(Esc), mede CPU da página e quadros desenhados por segundo, e salva uma captura. `POWERSAVE=1` liga a preferência
nova pelo `localStorage` antes de carregar; `SHOT=<arquivo.png>` define onde salvar a captura.

```js
// Uso (na raiz do repositório): node /tmp/habblaud-meas.mjs <url> <largura> <altura> <dpr> [segundos]
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const [, , url = 'http://127.0.0.1:4790/', w = '1440', h = '900', dpr = '1', secs = '5'] = process.argv;
const browser = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium', args: ['--disable-gpu', '--disable-accelerated-2d-canvas'] });
const ctx = await browser.newContext({ viewport: { width: +w, height: +h }, deviceScaleFactor: +dpr });
const page = await ctx.newPage();
await page.addInitScript((powerSave) => {
  if (powerSave) localStorage.setItem('habblaud:prefs', JSON.stringify({ powerSave: true }));
  const st = (window.__m = { frames: 0 });
  const P = CanvasRenderingContext2D.prototype;
  const o = P.fillRect;
  P.fillRect = function (...a) {
    if (this.canvas.isConnected && a[0] === 0 && a[1] === 0 && a[2] === this.canvas.width && a[3] === this.canvas.height) st.frames++;
    return o.apply(this, a);
  };
}, !!process.env.POWERSAVE);
await page.goto(url, { waitUntil: 'load' });
await page.waitForTimeout(4000);
for (let i = 0; i < 3; i++) { await page.keyboard.press('Escape'); await page.waitForTimeout(300); }
await page.waitForTimeout(1500);
const cdp = await ctx.newCDPSession(page);
await cdp.send('Performance.enable');
const metrics = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map((m) => [m.name, m.value]));
const f0 = await page.evaluate(() => window.__m.frames);
const m0 = await metrics();
await page.waitForTimeout(+secs * 1000);
const m1 = await metrics();
const frames = (await page.evaluate(() => window.__m.frames)) - f0;
const wall = m1.Timestamp - m0.Timestamp;
const busy = m1.TaskDuration - m0.TaskDuration;
const info = await page.evaluate(() => { const c = document.querySelector('canvas'); return `${c.width}x${c.height} ${getComputedStyle(c).imageRendering}`; });
console.log(`canvas=${info} cpu=${((100 * busy) / wall).toFixed(0)}% quadros/s=${(frames / wall).toFixed(1)} ms/quadro=${((1000 * busy) / Math.max(1, frames)).toFixed(1)}`);
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await browser.close();
```

Os números variam com a carga da máquina: rode cada medição 2 vezes e anote as duas.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/camera.ts` (só acrescentar a função pura `renderDpr`)
- `client/src/world/camera.test.ts` (create)
- `client/src/world/api.ts`
- `client/src/world/index.ts`
- `client/src/ui/prefs.ts`
- `client/src/ui/prefs.test.ts`
- `client/src/ui/settings.ts`
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- `client/src/world/render/overlay.ts` — separar os textos num segundo canvas em densidade real foi considerado e
  adiado (mais complexidade; avalie só se a opção for ligada por padrão no futuro).
- `client/src/world/city/**` e `client/src/world/visit/**` — a cidade e a visita têm canvas e laço próprios.
- Os níveis de zoom em `Camera.levels()` — com `camera.dpr = 1` eles já viram inteiros em px CSS, que é o certo para
  a ampliação inteira do navegador.
- Ligar a opção por padrão, ou ligá-la sozinha (bateria, celular): decisão do mantenedor; não faça.

## Git workflow

- Branch: `advisor/008-economia-de-energia`
- Commits no estilo do repositório: assunto em português, minúsculo. Exemplo do `git log`:
  `escritório a 30 qps com o terminal aberto fora do celular (setFrameCap)`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

Antes de mudar código: `npx vite build --config vite.config.ts`, suba o preview (deixe rodando) e rode, 2× cada:

- `SHOT=/tmp/hb-008-antes-d2.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 2`
- `SHOT=/tmp/hb-008-antes-m3.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 393 851 3`

**Verify**: `canvas=2880x1800 auto` no primeiro e `canvas=1179x2553 auto` no segundo. Anote `ms/quadro` e `cpu`.

### Step 2: Função pura `renderDpr` e teste

Em `client/src/world/camera.ts`, logo depois de `overviewFrame` (linha 32), acrescente:

```ts
/**
 * Densidade em que o escritório é desenhado: a da tela (de 1 a 3) ou, na economia de energia, sempre 1 (o navegador
 * amplia o canvas com image-rendering: pixelated; a pixel art fica igual e os textos, menos nítidos).
 */
export function renderDpr(deviceDpr: number | undefined, powerSave: boolean): number {
  if (powerSave) return 1;
  return Math.max(1, Math.min(3, deviceDpr || 1));
}
```

Crie `client/src/world/camera.test.ts` no padrão de `client/src/world/render/overlay.test.ts` (importa de
`vitest` e da função pura), cobrindo: `renderDpr(2, false)` → `2`; `renderDpr(4, false)` → `3`;
`renderDpr(undefined, false)` → `1`; `renderDpr(0.5, false)` → `1`; `renderDpr(3, true)` → `1`.

**Verify**: `npx vitest run client/src/world/camera.test.ts` → 1 arquivo, todos passando.

### Step 3: Opção do mundo

Em `client/src/world/api.ts`, dentro de `WorldOptions`, depois de `theme?: OfficeTheme;`:

```ts
  /**
   * (Opcional, aditivo) Economia de energia: desenha em densidade 1 e o navegador amplia (pixelated). Bem menos CPU em
   * telas de densidade 2–3; textos menos nítidos.
   */
  powerSave?: boolean;
```

Não mexa em `DEFAULT_WORLD_OPTIONS` (ausente = desligado).

Em `client/src/world/index.ts`:

1. Importe `renderDpr` de `./camera` (a linha 8 já importa `Camera, overviewFrame` de lá).
2. Em `resize()` troque a primeira linha por `const dpr = renderDpr(window.devicePixelRatio, !!options.powerSave);`
   e, antes de `camera.setView(...)`, acrescente:
   `canvas.style.imageRendering = options.powerSave ? 'pixelated' : '';`
3. No `loop`, troque `const dpr = Math.max(1, Math.min(3, window.devicePixelRatio || 1));` pela mesma chamada a
   `renderDpr(...)`. A comparação seguinte (`dpr !== camera.dpr`) já chama `resize()` quando a opção muda.
4. Em `setOptions`, guarde o valor antigo e, se `powerSave` mudou depois da primeira enquadrada, redimensione e
   reencaixe o zoom num nível nítido da nova densidade:

   ```ts
    setOptions: (o) => {
      const wasPowerSave = !!options.powerSave;
      options = { ...options, ...o };
      if (o.followSelected !== undefined) camera.follow = o.followSelected && selection?.type === 'agent' ? selection.id : null;
      // nova densidade: o zoom atual pode não ser mais um nível nítido
      if (!!options.powerSave !== wasPowerSave && framed) {
        resize();
        if (userMoved) camera.zoomAt(camera.snapZoom(camera.zoom), camera.viewW / 2, camera.viewH / 2, Date.now(), true);
        else overview(true);
      }
    },
   ```

**Verify**: `npm run typecheck` → exit 0.

### Step 4: Preferência e interruptor

Em `client/src/ui/prefs.ts`:

- `UiPrefs`: acrescente, depois de `theme`, `/** Economia de energia: escritório desenhado em densidade 1 (desligada por padrão). */ powerSave: boolean;`
- `DEFAULT_PREFS`: `powerSave: false,` na posição correspondente.
- `sanitizePrefs`: `powerSave: bool(o.powerSave, d.powerSave),`.
- `worldOptionsFrom`: acrescente `powerSave: p.powerSave` ao objeto retornado.

Em `client/src/ui/prefs.test.ts`:

- Atualize o `toEqual` da linha 57 para incluir `powerSave: false`.
- Acrescente um teste: `sanitizePrefs({ powerSave: true }).powerSave` → `true`; `sanitizePrefs({ powerSave: 'sim' }).powerSave` → `false`; `worldOptionsFrom({ ...DEFAULT_PREFS, powerSave: true }).powerSave` → `true`.

Em `client/src/ui/settings.ts`, no grupo "Escritório", depois de `this.daylight.row,`:

```ts
        sw(
          'powerSave',
          tr('Economia de energia'),
          tr('Desenha o escritório em resolução menor e deixa a tela ampliar: bem menos CPU em telas nítidas (celular, Retina), com nomes e balões um pouco menos nítidos.'),
          flip('powerSave'),
        ),
```

Traduções (acrescente as duas chaves nos três arquivos, perto de `"Mostrar nomes"`):

| PT (chave) | en | es | fr |
| --- | --- | --- | --- |
| Economia de energia | Power saving | Ahorro de energía | Économie d'énergie |
| Desenha o escritório em resolução menor e deixa a tela ampliar: bem menos CPU em telas nítidas (celular, Retina), com nomes e balões um pouco menos nítidos. | Draws the office at a lower resolution and lets the screen scale it up: much less CPU on sharp screens (phones, Retina), with slightly softer names and bubbles. | Dibuja la oficina a menor resolución y deja que la pantalla la amplíe: mucha menos CPU en pantallas nítidas (móvil, Retina), con nombres y globos un poco menos nítidos. | Dessine le bureau en plus basse résolution et laisse l'écran l'agrandir : beaucoup moins de CPU sur les écrans nets (téléphone, Retina), avec des noms et des bulles un peu moins nets. |

**Verify**: `npx vitest run client/src/ui/prefs.test.ts client/src/world/camera.test.ts` → todos passam;
`npm run typecheck` → exit 0; `npm run i18n:check -- --strict` → exit 0.

### Step 5: Medir, comparar a imagem e registrar

Rebuild (`npx vite build --config vite.config.ts`), reinicie o preview e rode, 2× cada:

- `POWERSAVE=1 SHOT=/tmp/hb-008-depois-d2.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 2`
  → `canvas=1440x900 pixelated`; `ms/quadro` ≤ 50% da linha de base do passo 1.
- `POWERSAVE=1 SHOT=/tmp/hb-008-depois-m3.png node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 393 851 3`
  → `canvas=393x851 pixelated`; `ms/quadro` ≤ 60% da linha de base.
- Sem `POWERSAVE` (opção desligada): `node /tmp/habblaud-meas.mjs http://127.0.0.1:4790/ 1440 900 2` →
  `canvas=2880x1800 auto` (nada mudou para quem não liga).

Abra as capturas antes/depois (`/tmp/hb-008-*.png`) e confira: a pixel art tem o mesmo enquadramento e blocos de
pixel regulares; os textos (nomes das salas) aparecem, só menos nítidos.

`CHANGELOG.md`, `## [Não lançado]`, `### Adicionado`:
`- **Economia de energia** (Configurações › Escritório, desligada por padrão): desenha o escritório em resolução menor
  e deixa a tela ampliar. Em telas nítidas o desenho custa menos da metade da CPU (computador Retina: 34 → 13 ms por
  quadro; celular: 16 → 7 ms); nomes e balões ficam um pouco menos nítidos.`

**Verify**: os três resultados acima batem; `npm test` → exit 0; `git status` só mostra arquivos do escopo.

## Test plan

- Novo: `client/src/world/camera.test.ts` — `renderDpr` (limites 1 e 3, valor ausente, economia ligada).
- Atualizado: `client/src/ui/prefs.test.ts` — `toEqual` de `worldOptionsFrom` com `powerSave: false`; teste novo de
  validação e repasse de `powerSave`.
- A troca de densidade em si (DOM/canvas) é provada pela medição e pelas capturas do passo 5.
- Verificação: `npm test` → exit 0.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; `client/src/world/camera.test.ts` existe e passa
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] Com `POWERSAVE=1`: `canvas=1440x900 pixelated` (1440×900@2) e `canvas=393x851 pixelated` (393×851@3), com
      `ms/quadro` ≤ 50% e ≤ 60% da linha de base, respectivamente
- [ ] Sem a opção: `canvas=2880x1800 auto` (1440×900@2)
- [ ] `grep -n "window.devicePixelRatio" client/src/world/index.ts` → só chamadas dentro de `renderDpr(...)`
- [ ] `CHANGELOG.md` tem o item "Economia de energia" em `## [Não lançado]`
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código.
- Com a opção ligada, o clique num agente seleciona o agente errado ou nenhum (o `pick` usa `camera.screenToWorld`,
  que depende de `camera.dpr`; deveria funcionar sem mudanças — se não funcionar, a premissa está errada).
- A captura com a opção ligada mostra a pixel art borrada (sinal de que `image-rendering` não foi aplicado) ou com
  enquadramento diferente da captura de antes.
- O `ms/quadro` com a opção ligada não cai pelo menos 40% na tela 1440×900@2.
- A mudança parece exigir mexer no overlay, na cidade ou na visita.

## Maintenance notes

- Em telas de densidade fracionária (1,25 ou 1,5, comuns no Windows) a ampliação `pixelated` não é inteira: alguns
  pixels do mundo ficam 1 px maiores que outros. É o preço aceito numa opção que o usuário liga; não tente corrigir.
- Se um dia a opção for ligada por padrão, a melhoria natural é desenhar o overlay (textos) num segundo canvas na
  densidade real, por cima do canvas do mundo em 1× (`client/src/world/render/overlay.ts:274`).
- A cidade (`client/src/world/city/index.ts:218,639`) calcula a densidade do mesmo jeito; levar a opção para lá é
  outro plano (não medido).
- Revisor: confira que nada além de `renderDpr` decide a densidade do escritório e que `setOptions` só redimensiona
  quando `powerSave` muda (não a cada chamada; a interface chama `setOptions` a cada mudança de preferência).
