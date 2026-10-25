# Plan 010: "Economia de energia" deixa o escritório parado a 10 quadros/s (5 sem foco), com o laço dormindo num timer

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 31368cb..HEAD -- client/src/world/pacing.ts client/src/world/pacing.test.ts client/src/world/index.ts client/src/ui/settings.ts shared/locales/en.ts shared/locales/es.ts shared/locales/fr.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` e os arquivos de tradução mudam com frequência: basta os
> trechos citados abaixo existirem.)

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `31368cb` (`fork/main`), 2026-10-25

## Why this matters

Com a opção "Economia de energia" ligada, um MacBook M1 continua com 20–30% de CPU só com o Habblaud aberto. A
opção atual (planos 007 e 008) desenha o cenário em resolução menor, e isso só ajuda quando o canvas é desenhado
**sem GPU**. Medido com GPU de verdade (Chromium com aceleração, 1440×900 em densidade 2, o caso de um Mac), a
economia não muda nada:

| Build | Normal | Economia ligada |
|---|---|---|
| Produção atual (`localhost:4747`) | 85% | 79% |
| `fork/main` (`31368cb`) | 82% | 82% |

O custo acompanha o número de quadros por segundo quase em linha reta. Na mesma máquina, forçando o limite:

| Quadros/s | CPU |
|---|---|
| 30 | 82–85% |
| 15 | 50% |
| 10 | 37–38% |
| 10, com o laço dormindo num `setTimeout` em vez de pular quadros do `requestAnimationFrame` | 34% |
| 1 (só o `requestAnimationFrame` acordando 60 vezes/s) | 9,6% |

Mesmo sem desenhar, o `requestAnimationFrame` acorda 60 vezes por segundo, e isso sozinho custa ~8%. Este plano faz
a economia valer em qualquer tela. Com a opção ligada e o escritório parado, ele desenha a 10 quadros/s, ou a 5 sem
foco. Abaixo de 30 quadros/s, o laço dorme num timer. Esperado: ~82% → ~34% (−60%) com foco. A animação mais rápida
de quem está sentado (digitar) troca de pose a cada 120 ms, então 10 quadros/s quase não muda o que se vê; quem anda
fica um pouco aos saltos, o preço aceito da opção.

## Current state

Arquivos:

- `client/src/world/pacing.ts` — função pura que decide o limite de quadros/s do laço do escritório.
- `client/src/world/pacing.test.ts` — testes dela (Vitest).
- `client/src/world/index.ts` — `createWorld`: o laço (`loop`), a entrada (`markInput`) e a API (`setOptions`,
  `setSuspended`, `setFrameCap`, `destroy`).
- `client/src/ui/settings.ts` — texto da opção em Configurações › Escritório.
- `shared/locales/en.ts`, `es.ts`, `fr.ts` — traduções; a chave é o texto em português exato passado a `tr()`.
- `CHANGELOG.md` — a entrada "Economia de energia" já está em `## [Não lançado]` › `### Adicionado`.

`client/src/world/pacing.ts` (arquivo inteiro):

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

`client/src/world/index.ts:21` (import):

```ts
import { ACTIVE_GRACE_MS, frameCapFor } from './pacing';
```

`client/src/world/index.ts:81-90` (estado do laço):

```ts
  let raf = 0;
  let lastTs = 0;
  let hiddenAt = 0;
  /** Escritório fora da tela (cidade aberta): sem laço de desenho nem teclado da câmera. */
  let suspended = false;
  let suspendedAt = 0;
  /** Limite pedido pela interface (0 = nenhum); o laço ainda aplica o ritmo de escritório parado (pacing.ts). */
  let frameCap = 0;
  /** Última entrada do usuário no escritório (performance.now()): o laço segue a 60 qps por ACTIVE_GRACE_MS. */
  let lastInputAt = 0;
```

`client/src/world/index.ts:261-266` (`interact` dentro de `attachInput`):

```ts
    interact: () => {
      userMoved = true;
      lastInputAt = performance.now();
      camera.follow = null;
      camera.stop();
    },
```

`client/src/world/index.ts:273-276`:

```ts
  // Arrasto, pinça e hover não passam por interact() a cada movimento: o laço fica a 60 qps enquanto houver entrada.
  const markInput = () => (lastInputAt = performance.now());
  canvas.addEventListener('pointermove', markInput, { signal: abort.signal, passive: true });
  canvas.addEventListener('pointerdown', markInput, { signal: abort.signal, passive: true });
```

`client/src/world/index.ts:305-316` (início do laço):

```ts
  const loop = (ts: number) => {
    raf = requestAnimationFrame(loop);
    // 2 ms de folga: com 30 qps num monitor de 60 Hz, o quadro de 33,3 ms não pode cair para o seguinte.
    const cap = frameCapFor({
      uiCap: frameCap,
      active: cameraMoved || camera.animating || !!playback || performance.now() - lastInputAt < ACTIVE_GRACE_MS,
      focused: document.hasFocus(),
    });
    if (cap && lastTs && ts - lastTs < 1000 / cap - 2) return;
    const real = Date.now();
    const realDt = lastTs ? Math.min(0.1, Math.max(0, (ts - lastTs) / 1000)) : 1 / 60;
    lastTs = ts;
```

`client/src/world/index.ts:465-476` (`setOptions`), `508-526` (`setSuspended`, `setFrameCap`) e `527-528` (`destroy`):

```ts
    setOptions: (o) => {
      const wasPowerSave = !!options.powerSave;
      options = { ...options, ...o };
      ...
    },
```

```ts
    setSuspended: (on) => {
      if (on === suspended) return;
      suspended = on;
      if (on) {
        cancelAnimationFrame(raf);
        suspendedAt = Date.now();
        setHover(null);
        mouse = null;
        return;
      }
      lastTs = 0;
      if (suspendedAt && Date.now() - suspendedAt > 5000) sim.fastForward(playback ? worldNow : Date.now());
      suspendedAt = 0;
      hiddenAt = 0;
      raf = requestAnimationFrame(loop);
    },
    setFrameCap: (fps) => {
      frameCap = Math.max(0, fps);
    },
    destroy: () => {
      cancelAnimationFrame(raf);
```

Fatos que o plano precisa respeitar:

- A interface chama `world.setFrameCap(terminal.isOpen ? 30 : 0)` e `world.setSuspended(...)` a **cada**
  renderização da UI (`client/src/ui/index.ts:323-326`). Os dois são chamados repetidas vezes com o mesmo valor; o
  código novo não pode acordar o laço só porque foram chamados.
- `realDt` é limitado a 0,1 s. A 5 quadros/s o intervalo real é 0,2 s: com o limite atual, a simulação (personagens
  andando, `sim.update(dt, now)`) andaria na metade da velocidade. O plano sobe o limite para 0,25 s.
  `client/src/world/sim/sim.ts` usa `dt` de forma linear (`ch.animT += dt * 1000`, `ch.stepT += dt * 1000`,
  `ch.alpha += dt * 5` com `Math.min(1, ...)`); não há física que exploda com passo maior.
- `options.powerSave` é a preferência "Economia de energia" (`client/src/ui/prefs.ts`, padrão `false`).

Convenções: comentários em português explicando o porquê; constantes exportadas de `pacing.ts` com JSDoc de uma
linha (veja o arquivo acima); testes em `pacing.test.ts` no estilo `it('frase em português', ...)`; mudanças
visíveis no `CHANGELOG.md` em `## [Não lançado]`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Testes do ritmo | `npx vitest run client/src/world/pacing.test.ts` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort --host 127.0.0.1` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747`
(`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4747/` → `200`). Se não estiver, STOP.

## Measurement script (temporário, fora do repositório)

Crie `/tmp/habblaud-cpu.mjs`. Ele soma a CPU de todos os processos do Chromium (% de 1 núcleo) **com GPU de verdade**
e conta quadros/s. Se a GPU não estiver disponível, ele sai com código 2: não meça sem GPU, porque os números não
valem para um Mac.

```js
// Uso (na raiz do repositório): [SAVE=1] [FOCUS=0] node /tmp/habblaud-cpu.mjs <url> [segundos]
// CPU do Chromium inteiro (todos os processos, % de 1 núcleo) com GPU de verdade, 1440×900 em densidade 2.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import { execSync } from 'node:child_process';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const [, , url = 'http://127.0.0.1:4790/', secs = '15'] = process.argv;
const browser = await chromium.launch({
  executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium',
  args: ['--ozone-platform=headless', '--use-gl=angle', '--use-angle=gl-egl', '--ignore-gpu-blocklist', '--enable-gpu'],
});
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
await ctx.addInitScript((save) => {
  try {
    const o = JSON.parse(localStorage.getItem('habblaud:prefs') || '{}');
    localStorage.setItem('habblaud:prefs', JSON.stringify({ ...o, powerSave: save }));
  } catch {}
  const st = (window.__m = { frames: 0 });
  // quadro = callback de requestAnimationFrame que desenhou algo num canvas visível
  let drew = false;
  const P = CanvasRenderingContext2D.prototype;
  for (const k of ['fillRect', 'drawImage']) {
    const o = P[k];
    P[k] = function (...a) {
      if (this.canvas.isConnected) drew = true;
      return o.apply(this, a);
    };
  }
  const raf = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = (f) =>
    raf((t) => {
      drew = false;
      f(t);
      if (drew) st.frames++;
    });
}, process.env.SAVE === '1');
const page = await ctx.newPage();
await page.goto(url, { waitUntil: 'load' });
const gl = await page.evaluate(() => {
  const g = document.createElement('canvas').getContext('webgl');
  const d = g?.getExtension('WEBGL_debug_renderer_info');
  return d ? g.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'sem webgl';
});
if (/swiftshader|sem webgl/i.test(gl)) {
  console.error(`GPU indisponível (${gl}): a medição não vale.`);
  process.exit(2);
}
await page.waitForTimeout(5000);
for (let i = 0; i < 3; i++) {
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
}
if (process.env.FOCUS === '0') await page.evaluate(() => (document.hasFocus = () => false));
await page.mouse.move(5, 450);
await page.waitForTimeout(3000);
// todos os processos do Chromium = descendentes deste node
const all = execSync('ps -eo pid=,ppid=').toString().trim().split('\n').map((l) => l.trim().split(/\s+/));
const tree = new Set([String(process.pid)]);
for (let grew = true; grew; ) {
  grew = false;
  for (const [p, pp] of all) if (tree.has(pp) && !tree.has(p)) (tree.add(p), (grew = true));
}
tree.delete(String(process.pid));
const ticks = () => {
  let t = 0;
  for (const p of tree) {
    try {
      const s = fs.readFileSync(`/proc/${p}/stat`, 'utf8');
      const f = s.slice(s.lastIndexOf(')') + 2).split(' ');
      t += +f[11] + +f[12];
    } catch {}
  }
  return t;
};
const t0 = ticks();
const f0 = await page.evaluate(() => window.__m.frames);
const w0 = Date.now();
await page.waitForTimeout(+secs * 1000);
const f1 = await page.evaluate(() => window.__m.frames);
const wall = (Date.now() - w0) / 1000;
const cpu = ((ticks() - t0) / 100 / wall) * 100;
console.log(`cpu=${cpu.toFixed(1)}% quadros/s=${((f1 - f0) / wall).toFixed(1)} gpu=${gl.slice(0, 40)}`);
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await browser.close();
```

O script foi validado em 2026-10-25 nesta máquina (Intel UHD 630, Chromium do sistema): `fork/main` deu
`cpu=81.9% quadros/s=30.0` normal, `cpu=82.0% quadros/s=30.0` com `SAVE=1` e `cpu=48.6% quadros/s=15.0` com
`FOCUS=0`. Os números variam com a carga da máquina e com o que os agentes estão fazendo: rode cada caso 3 vezes e use
a mediana.

O advisor aplicou os passos 2 e 3 numa cópia descartável, para conferir o plano. Resultados: `SAVE=1` deu
`cpu=33.3% quadros/s=11.9`; `SAVE=1 FOCUS=0` deu `cpu=13.4% quadros/s=5.5`; sem `SAVE`, `cpu=79.3% quadros/s=30.0`.
O script do passo 6 deu `parado: 11` e `arrastando o mouse: 60`. O código compilou (`tsc` só reclamou de
`pacing.test.ts` sem `powerSave`, o que o passo 2 corrige).

## Scope

**In scope** (the only files you should modify):

- `client/src/world/pacing.ts`
- `client/src/world/pacing.test.ts`
- `client/src/world/index.ts`
- `client/src/ui/settings.ts` (só o texto de descrição da opção)
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts` (só a tradução desse texto)
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- `client/src/world/camera.ts` (`renderDpr`) e o cenário em resolução menor (`scene` em `index.ts`): ficam como
  estão. Ajudam em aparelhos que desenham o canvas sem GPU.
- `client/src/ui/index.ts` (`setFrameCap(terminal.isOpen ? 30 : 0)`): o limite 30 com terminal aberto continua.
  Com a economia ligada, o mínimo entre 30 e 10 já dá 10.
- O ritmo **sem** a economia (30/15 parado): é o plano 011.
- `client/src/world/city/**`: a cidade tem laço próprio, não medido.
- `client/src/world/render/**`: nada no desenho muda.

## Git workflow

- Base: `fork/main` (o `producao` local está atrás). `git fetch fork && git switch -c advisor/010-economia-10-qps fork/main`.
  Os arquivos de `plans/` não commitados vão junto na troca de branch.
- Commits no estilo do repositório: assunto em português, minúsculo. Exemplo do `git log`:
  `escritório parado a 30 qps (15 sem foco); 60 qps só com câmera em movimento ou entrada`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

Antes de mudar código: `npx vite build --config vite.config.ts`, suba o preview (deixe rodando) e rode 3× cada:

- `SAVE=1 node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 15`
- `SAVE=1 FOCUS=0 node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 15`
- `node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 15`

**Verify**: os três rodam sem `GPU indisponível`. `SAVE=1` mostra `quadros/s` entre 28 e 31 e `SAVE=1 FOCUS=0`
entre 14 e 16. Anote as medianas de `cpu`.

### Step 2: Ritmo da economia em `pacing.ts` (puro, com testes)

Em `client/src/world/pacing.ts`:

1. Atualize o comentário do topo para incluir a economia, por exemplo:
   `// Ritmo do laço do escritório: 60 qps só quando a câmera se mexe ou há entrada; parado, 30 (15 sem foco); com a`
   `// economia de energia, parado a 10 (5 sem foco).`
2. Novas constantes, depois de `UNFOCUSED_FPS`:

   ```ts
   /** Escritório parado com a economia de energia, janela com foco (digitar troca de pose a cada 120 ms). */
   export const STILL_FPS = 10;
   /** Escritório parado com a economia de energia, janela sem foco. */
   export const STILL_UNFOCUSED_FPS = 5;
   ```

3. `frameCapFor` ganha `powerSave: boolean` na entrada:

   ```ts
   export function frameCapFor(s: { uiCap: number; active: boolean; focused: boolean; powerSave: boolean }): number {
     if (s.active) return s.uiCap;
     const idle = s.powerSave ? (s.focused ? STILL_FPS : STILL_UNFOCUSED_FPS) : s.focused ? IDLE_FPS : UNFOCUSED_FPS;
     return s.uiCap > 0 ? Math.min(s.uiCap, idle) : idle;
   }
   ```

   Atualize o JSDoc: `powerSave` = preferência "Economia de energia".
4. Nova função:

   ```ts
   /**
    * Abaixo de 30 qps o laço dorme num setTimeout em vez de pular quadros do requestAnimationFrame: o rAF acorda a
    * página 60 vezes por segundo mesmo sem desenhar (medido: ~8% de CPU a 1 qps).
    */
   export function sleepsBetweenFrames(cap: number): boolean {
     return cap > 0 && cap < IDLE_FPS;
   }
   ```

Em `client/src/world/pacing.test.ts`: acrescente `powerSave: false` em todas as chamadas existentes (o resultado
esperado delas não muda) e os casos novos da seção "Test plan".

**Verify**: `npx vitest run client/src/world/pacing.test.ts` → todos passam. `npm run typecheck` falha **só** em
`client/src/world/index.ts` (falta `powerSave` na chamada); qualquer outro erro é STOP.

### Step 3: Laço do escritório usa o ritmo novo e dorme no timer

Em `client/src/world/index.ts`:

1. Import: `import { ACTIVE_GRACE_MS, frameCapFor, sleepsBetweenFrames } from './pacing';`
2. Estado, logo depois de `let raf = 0;`:

   ```ts
   /** Próximo quadro agendado num setTimeout (ritmo abaixo de 30 qps); 0 = nenhum. */
   let timer: ReturnType<typeof setTimeout> | 0 = 0;
   ```

3. Duas funções antes de `const loop`:

   ```ts
   /** Agenda o próximo quadro: abaixo de 30 qps dorme num timer e só então pede o rAF (alinhado à tela). */
   const schedule = (cap: number) => {
     if (sleepsBetweenFrames(cap)) {
       timer = setTimeout(() => {
         timer = 0;
         raf = requestAnimationFrame(loop);
       }, Math.max(0, 1000 / cap - 1000 / 60));
     } else raf = requestAnimationFrame(loop);
   };
   /** Entrada do usuário com o laço dormindo no timer: desenha já, sem esperar até 200 ms. */
   const wake = () => {
     if (!timer || suspended) return;
     clearTimeout(timer);
     timer = 0;
     raf = requestAnimationFrame(loop);
   };
   ```

   `loop` é uma `const` declarada depois; as funções só a usam quando são chamadas, depois que o módulo terminou de
   montar. O TypeScript aceita.
4. Início do `loop` (linhas 305-316). Mova `raf = requestAnimationFrame(loop);` para depois do cálculo de `cap`,
   trocando-o por `schedule(cap)`. Pule o quadro só quando o ritmo não dorme no timer (com timer, o intervalo já foi
   esperado). Suba o limite de `realDt` para 0,25 s:

   ```ts
   const loop = (ts: number) => {
     const cap = frameCapFor({
       uiCap: frameCap,
       active: cameraMoved || camera.animating || !!playback || performance.now() - lastInputAt < ACTIVE_GRACE_MS,
       focused: document.hasFocus(),
       powerSave: !!options.powerSave,
     });
     schedule(cap);
     // 2 ms de folga: com 30 qps num monitor de 60 Hz, o quadro de 33,3 ms não pode cair para o seguinte.
     if (cap && !sleepsBetweenFrames(cap) && lastTs && ts - lastTs < 1000 / cap - 2) return;
     const real = Date.now();
     // até 0,25 s: a 5 qps (economia sem foco) o quadro dura 0,2 s e a simulação não pode andar mais devagar
     const realDt = lastTs ? Math.min(0.25, Math.max(0, (ts - lastTs) / 1000)) : 1 / 60;
     lastTs = ts;
   ```

5. Acorde o laço na entrada:
   - `interact`: depois de `lastInputAt = performance.now();` acrescente `wake();`.
   - `markInput`: troque por
     `const markInput = () => { lastInputAt = performance.now(); wake(); };` (mantenha o comentário acima).
6. API:
   - `setOptions`: no fim do corpo, `wake();` (ligar ou desligar a economia vale na hora).
   - `setFrameCap`: acorde só quando o valor muda (a UI chama a cada renderização):

     ```ts
     setFrameCap: (fps) => {
       const next = Math.max(0, fps);
       if (next === frameCap) return;
       frameCap = next;
       wake();
     },
     ```

   - `setSuspended(true)`: depois de `cancelAnimationFrame(raf);` acrescente `clearTimeout(timer); timer = 0;`.
   - `setSuspended(false)`: antes de `raf = requestAnimationFrame(loop);` acrescente
     `clearTimeout(timer); timer = 0;` (nunca dois laços).
   - `destroy`: depois de `cancelAnimationFrame(raf);` acrescente `clearTimeout(timer);`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0;
`grep -c "wake();" client/src/world/index.ts` → `4` (interact, markInput, setOptions e setFrameCap; se der outro
número, confira a lista dos itens 5 e 6);
`grep -n "Math.min(0.1," client/src/world/index.ts` → nenhuma linha.

### Step 4: Texto da opção e traduções

Em `client/src/ui/settings.ts`, troque a descrição de `powerSave`:

- de: `'Desenha o cenário em resolução menor e amplia; nomes e balões continuam nítidos. Bem menos CPU em telas nítidas (celular, Retina).'`
- para: `'Escritório parado a 10 quadros por segundo (5 com a janela sem foco) e cenário em resolução menor. Bem menos CPU e bateria; quem anda fica um pouco aos saltos.'`

Em `shared/locales/en.ts`, `es.ts` e `fr.ts`, troque a **chave** antiga pela nova (mesma posição) e traduza:

- en: `"Office at 10 frames per second while still (5 when the window is not focused) and scenery at a lower resolution. Much less CPU and battery; people walking look a little jumpy."`
- es: `"Oficina a 10 cuadros por segundo en reposo (5 con la ventana sin foco) y escenario a menor resolución. Mucha menos CPU y batería; quien camina se ve un poco a saltos."`
- fr: `"Bureau à 10 images par seconde au repos (5 quand la fenêtre n'a pas le focus) et décor en plus basse résolution. Beaucoup moins de CPU et de batterie ; les personnages qui marchent sont un peu saccadés."`

**Verify**: `npm run i18n:check -- --strict` → exit 0;
`grep -rn "Desenha o cenário em resolução menor" client shared` → nenhuma linha.

### Step 5: Medir

Rebuild (`npx vite build --config vite.config.ts`), reinicie o preview e rode 3× cada um dos três comandos do
passo 1.

**Verify**:

- `SAVE=1`: mediana de `quadros/s` entre 9 e 13; mediana de `cpu` ≤ 55% da mediana do passo 1 (esperado: ~42%,
  de ~82% para ~34%).
- `SAVE=1 FOCUS=0`: mediana de `quadros/s` entre 4 e 6; `cpu` menor que a de `SAVE=1`.
- Sem `SAVE`: `quadros/s` entre 28 e 31 e `cpu` dentro de ±10% do passo 1 (nada mudou para quem não liga a opção).

Se `quadros/s` de `SAVE=1` ficar fora de 9–13, o timer está atrasando demais: STOP e reporte os números.

### Step 6: Entrada acorda o laço (verificação manual por script)

Com o preview do passo 5 rodando, rode este teste descartável (não vai para o repositório):

```js
// /tmp/habblaud-wake.mjs — node /tmp/habblaud-wake.mjs
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const b = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium' });
const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addInitScript(() => localStorage.setItem('habblaud:prefs', JSON.stringify({ powerSave: true })));
const p = await ctx.newPage();
await p.goto('http://127.0.0.1:4790/', { waitUntil: 'load' });
await p.waitForTimeout(5000);
for (let i = 0; i < 3; i++) { await p.keyboard.press('Escape'); await p.waitForTimeout(300); }
const count = () => p.evaluate(() => new Promise((ok) => {
  let n = 0; const t0 = performance.now();
  const P = CanvasRenderingContext2D.prototype; const o = P.fillRect;
  P.fillRect = function (...a) { if (a[0] === 0 && a[1] === 0 && a[2] === this.canvas.width) n++; return o.apply(this, a); };
  setTimeout(() => { P.fillRect = o; ok(Math.round((n * 1000) / (performance.now() - t0))); }, 1000);
}));
await p.waitForTimeout(2500);
console.log('parado:', await count());
const drag = (async () => { for (let i = 0; i < 40; i++) { await p.mouse.move(700 + i * 3, 450); await p.waitForTimeout(25); } })();
console.log('arrastando o mouse:', await count());
await drag;
await b.close();
```

**Verify**: `parado:` entre 8 e 13; `arrastando o mouse:` ≥ 25 (a entrada volta ao ritmo do monitor). Este script
roda sem GPU de propósito: só conta quadros, não mede CPU.

### Step 7: Changelog

Em `CHANGELOG.md`, `## [Não lançado]` › `### Adicionado`, reescreva a entrada `- **Economia de energia**` (começa
com `(Configurações › Escritório, desligada por padrão)`). A entrada nova diz que o escritório parado desenha a 10
quadros por segundo (5 sem foco), além do cenário em resolução menor. Os números entre parênteses passam a ser os
medidos no passo 5, por exemplo:
`(medido com GPU, 1440×900 em densidade 2: 82% → 34% de CPU)`. Apague os números antigos ("24 → 10 ms por quadro"
etc.): foram medidos sem GPU e não valem para um Mac.

**Verify**: `git status --short` lista só arquivos do "In scope" (mais `plans/`).

## Test plan

Em `client/src/world/pacing.test.ts` (padrão: os `it` já existentes), além de `powerSave: false` nos casos atuais:

- economia, parado, com foco → `10`;
- economia, parado, sem foco → `5`;
- economia, ativo, sem limite da UI → `0` (entrada continua no ritmo do monitor);
- economia, parado, com foco e limite 30 da UI (terminal aberto) → `10`;
- `sleepsBetweenFrames(10)` → `true`; `sleepsBetweenFrames(30)` → `false`; `sleepsBetweenFrames(0)` → `false`.

`npx vitest run client/src/world/pacing.test.ts` → todos passam (6 antigos + 7 novos).

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 7 casos novos em `pacing.test.ts`
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] Passo 5: `SAVE=1` a 9–13 quadros/s com `cpu` ≤ 55% da linha de base; sem `SAVE`, inalterado (±10%)
- [ ] Passo 6: `parado` 8–13, `arrastando o mouse` ≥ 25
- [ ] `CHANGELOG.md` sem os números antigos medidos sem GPU (`grep -n "24 → 10 ms" CHANGELOG.md` → nenhuma linha)
- [ ] Nenhum arquivo fora do "In scope" modificado (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código.
- `/tmp/habblaud-cpu.mjs` sai com `GPU indisponível`: a medição sem GPU não vale para este plano.
- No passo 5, `SAVE=1` não fica entre 9 e 13 quadros/s, ou a CPU não cai pelo menos 30%.
- Algum teste existente fora de `pacing.test.ts` quebra com o novo limite de `realDt` (0,25 s): a simulação depende
  do passo pequeno e o plano subestimou isso.
- Aparecem dois laços ao mesmo tempo (quadros/s dobrando depois de abrir e fechar a cidade ou o terminal).

## Maintenance notes

- Ações da API que mexem a câmera sem entrada do usuário (`focusAgent`, `focusRoom`, `overview`, `zoomBy`) não
  chamam `wake()`: com a economia ligada, a animação começa no próximo quadro do timer (até 100 ms, ou 200 ms sem
  foco). Se isso incomodar, chame `wake()` nelas.
- O plano 011 usa `STILL_FPS`/`STILL_UNFOCUSED_FPS` também fora da economia, quando nada se mexe na tela. Mantenha os
  nomes.
- Com o timelapse, `active` é verdadeiro e nada muda.
- Revisor: confira que todo caminho que cancela o rAF também cancela o `timer` (suspender, destruir) e que
  `setFrameCap` só acorda quando o valor muda.
