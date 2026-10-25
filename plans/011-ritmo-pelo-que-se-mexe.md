# Plan 011: Escritório a 30 quadros/s só quando algo anda na tela; sem movimento, 10 (5 sem foco)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: o plano 010 precisa estar no branch (`grep -n "STILL_FPS" client/src/world/pacing.ts`
> mostra a constante; senão, STOP). Depois:
> `git diff --stat 31368cb..HEAD -- client/src/world/render/renderer.ts`
> deve mostrar **nenhuma** mudança em `renderer.ts` (o plano 010 não mexe nele). Se o plano 012 já entrou, aceite as
> mudanças dele (cache `drawDynamic`) e confira só os trechos citados aqui. `pacing.ts`, `pacing.test.ts`,
> `index.ts`, `settings.ts` e as traduções mudaram no plano 010: compare com os trechos de "Current state" abaixo,
> que já são o estado depois do 010. Divergência = STOP.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (mudança visível de ritmo para todo mundo, não só para quem liga a economia)
- **Depends on**: plans/010-economia-de-energia-10-qps.md
- **Category**: perf
- **Planned at**: commit `31368cb` (`fork/main`) + plano 010, 2026-10-25

## Why this matters

O escritório parado desenha a 30 quadros/s mesmo quando nada anda na tela. Medido com GPU de verdade (1440×900 em
densidade 2), isso custa 82–85% de CPU. A 10 quadros/s, custa 34–38%. Quase tudo o que se mexe num escritório
"parado" troca de quadro devagar:

- digitar: 120 ms por pose (`client/src/art/character/render.ts`, `POSE_DURATION`);
- sentado: 800 ms; dormindo: 1000 ms;
- telas, janelas e relógio: 110 ms ou mais.

Só o que **anda** precisa de 30 quadros/s para não dar tranco: personagens andando ou correndo, salas sendo
montadas ou desmontadas, partículas (confete, moedas) e a bolinha do pingue-pongue. Este plano faz o renderer
avisar se desenhou algo assim. Sem movimento, o laço cai para 10 quadros/s (5 sem foco), o ritmo que o plano 010
criou para a economia de energia. Com a economia ligada, fica sempre em 10/5, mesmo com movimento.

## Current state

Arquivos:

- `client/src/world/pacing.ts` — decide o limite de quadros/s (puro, testado em `pacing.test.ts`).
- `client/src/world/index.ts` — o laço (`loop`) chama `frameCapFor` e `renderer.frame(...)`.
- `client/src/world/render/renderer.ts` — classe `Renderer`, desenha o quadro (`frame`).
- `client/src/ui/settings.ts` e `shared/locales/{en,es,fr}.ts` — texto da opção "Economia de energia".
- `CHANGELOG.md`.

`client/src/world/pacing.ts` depois do plano 010 (trechos):

```ts
/** Escritório parado com a economia de energia, janela com foco (digitar troca de pose a cada 120 ms). */
export const STILL_FPS = 10;
/** Escritório parado com a economia de energia, janela sem foco. */
export const STILL_UNFOCUSED_FPS = 5;
...
export function frameCapFor(s: { uiCap: number; active: boolean; focused: boolean; powerSave: boolean }): number {
  if (s.active) return s.uiCap;
  const idle = s.powerSave ? (s.focused ? STILL_FPS : STILL_UNFOCUSED_FPS) : s.focused ? IDLE_FPS : UNFOCUSED_FPS;
  return s.uiCap > 0 ? Math.min(s.uiCap, idle) : idle;
}
```

`client/src/world/index.ts`, início do `loop` depois do plano 010:

```ts
  const loop = (ts: number) => {
    const cap = frameCapFor({
      uiCap: frameCap,
      active: cameraMoved || camera.animating || !!playback || performance.now() - lastInputAt < ACTIVE_GRACE_MS,
      focused: document.hasFocus(),
      powerSave: !!options.powerSave,
    });
    schedule(cap);
```

O mesmo `loop` chama, mais abaixo, `renderer.frame(now, dt, options, {...})` (hoje na linha 352 de `31368cb`).
Ou seja, `frameCapFor` do quadro N enxerga o `renderer.motion` do quadro N−1, o que basta.

`client/src/world/render/renderer.ts` em `31368cb`:

- `frame(...)` começa na linha 328:

  ```ts
  frame(now: number, dt: number, opts: WorldOptions, sel: { agent: string | null; room: string | null; hover: string | null }): void {
    const { ctx, camera } = this;
    this.date.setTime(this.clock());
    this.dt = dt;
    this.updateCars(now, dt);
  ```

- laço de pisos, linhas 385-390 (só áreas visíveis; `anim.mode` é `'none'` fora da montagem/desmontagem):

  ```ts
    for (const vis of this.areas.values()) {
      const p = vis.px;
      if (!visible(p.x, p.y - WALL_MARGIN, p.w, p.h + WALL_MARGIN)) continue;
      const anim = vis.room ? buildAnim(vis.room.phase, vis.room.progress(now)) : NO_ANIM;
      this.drawFloor(vis, anim);
    }
  ```

- partículas, linhas 500-502:

  ```ts
    this.roomFx.emit(this.particles, this.sim, now, dt, view);
    this.particles.update(dt);
    this.particles.draw(ctx, vx0, vy0, vx1, vy1, now);
  ```

  (`this.particles.count` é um getter com o número de partículas vivas, `client/src/world/render/particles.ts:56`.)

- `drawCharacter`, linhas 831-836. Só é chamado para personagens visíveis, porque o `frame` só empurra os
  visíveis para a lista de desenho (linhas 432-443):

  ```ts
  private drawCharacter(ch: Character, now: number, sel: { agent: string | null; hover: string | null }): void {
    const { ctx } = this;
    const head = this.headOf(ch);
    // faixa lateral de quem anda (dois no mesmo caminho não viram um boneco de duas cabeças)
    const x = Math.round(ch.x + ch.offX);
    const y = Math.round(ch.y + ch.offY);
  ```

- `drawPingPong`, linhas 1162-1174 (a bolinha atravessa a mesa a cada 1,1 s):

  ```ts
  private drawPingPong(now: number): void {
    const { ctx } = this;
    for (const g of this.sim.social.gatherings) {
      if (g.kind !== 'pingpong' || g.phase !== 'run') continue;
      ...
      if (!a || !b) continue;
  ```

- carros: `updateCars`/`drawCar` (linhas 1144-1160). Um carro passa a cada 4–13 s e leva ~25 s para cruzar a
  rua, então quase sempre há um na tela. **Carro não conta como movimento**: senão o escritório nunca cairia para 10.
  O carro a 10 quadros/s anda aos saltos de ~5 px, aceito.

Convenções: comentários em português explicando o porquê; campos públicos da classe com JSDoc de uma linha (veja
`cars: Car[] = [];` e vizinhos no topo de `Renderer`); nada de alocação por quadro; testes de `pacing.ts` no estilo
`it('frase em português', ...)`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 |
| Testes do ritmo | `npx vitest run client/src/world/pacing.test.ts` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build do cliente | `npx vite build --config vite.config.ts` | exit 0 |
| Servir o build | `npx vite preview --config vite.config.ts --port 4790 --strictPort --host 127.0.0.1` | serve em `http://127.0.0.1:4790/`, repassando `/api` a `127.0.0.1:4747` |

O servidor do Habblaud precisa estar rodando em `127.0.0.1:4747` (`curl -s -o /dev/null -w '%{http_code}'
http://127.0.0.1:4747/` → `200`), com pelo menos um agente no escritório. Se não estiver, STOP.

## Measurement scripts (temporários, fora do repositório)

1. `/tmp/habblaud-cpu.mjs`: o mesmo do plano 010 (seção "Measurement script" de
   `plans/010-economia-de-energia-10-qps.md`). Copie de lá sem mudar nada. Ele sai com código 2 se não houver GPU;
   nesse caso, STOP.
2. `/tmp/habblaud-motion.mjs`: confere que andar volta a 30 quadros/s. Roda sem GPU de propósito, porque só conta
   quadros.

```js
// node /tmp/habblaud-motion.mjs
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const b = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium' });
const ctx = await b.newContext({ viewport: { width: 1440, height: 900 } });
const p = await ctx.newPage();
await p.goto('http://127.0.0.1:4790/', { waitUntil: 'load' });
await p.waitForTimeout(5000);
for (let i = 0; i < 3; i++) { await p.keyboard.press('Escape'); await p.waitForTimeout(300); }
const count = (ms) => p.evaluate((ms) => new Promise((ok) => {
  let n = 0; const t0 = performance.now();
  const P = CanvasRenderingContext2D.prototype; const o = P.fillRect;
  P.fillRect = function (...a) { if (a[0] === 0 && a[1] === 0 && a[2] === this.canvas.width) n++; return o.apply(this, a); };
  setTimeout(() => { P.fillRect = o; ok(Math.round((n * 1000) / (performance.now() - t0))); }, ms);
}), ms);
// amostras de 1 s por 20 s: mostra quanto tempo o escritório passa em cada ritmo
const s = [];
for (let i = 0; i < 20; i++) s.push(await count(1000));
console.log('amostras (qps):', s.join(' '));
// um agente novo entra pelo elevador e anda até a mesa
await p.evaluate(() => window.habblaud.world.debug.spawnSession());
await p.waitForTimeout(1500);
console.log('agente andando:', await count(1000));
await b.close();
```

O advisor aplicou 010 e 011 numa cópia descartável, para conferir o plano. Sem economia, as `amostras` ficaram em
11–13 (ninguém andando naquele minuto) e `agente andando:` deu `29`. `habblaud-cpu.mjs` (30 s) deu
`cpu=34.7% quadros/s=11.9`, contra ~80% antes.

## Scope

**In scope** (the only files you should modify):

- `client/src/world/pacing.ts`
- `client/src/world/pacing.test.ts`
- `client/src/world/index.ts` (só a chamada de `frameCapFor`)
- `client/src/world/render/renderer.ts` (só o campo `motion` e as linhas que o ligam)
- `client/src/ui/settings.ts` (só a descrição de `powerSave`)
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts` (só a tradução dessa descrição)
- `CHANGELOG.md`

**Out of scope** (do NOT touch, even though they look related):

- O timer de `schedule`/`wake` do plano 010: continua igual.
- Carros, elevador, fumaça do café, relógios, telas: **não** contam como movimento (ver "Current state").
- `client/src/world/city/**`: laço próprio, não medido.
- `ACTIVE_GRACE_MS` e o ritmo com entrada do usuário: continuam no ritmo do monitor.

## Git workflow

- Branch: `advisor/011-ritmo-pelo-movimento`, criado a partir do branch do plano 010 (ou de `fork/main` depois que
  o 010 entrar).
- Commits no estilo do repositório: assunto em português, minúsculo. Exemplo do `git log`:
  `escritório parado a 30 qps (15 sem foco); 60 qps só com câmera em movimento ou entrada`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Medir a linha de base

`npx vite build --config vite.config.ts`, suba o preview e rode 3× cada:

- `node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 30`
- `node /tmp/habblaud-motion.mjs`

**Verify**: o primeiro mostra `quadros/s` entre 28 e 31 (sem economia, nada mudou desde o 010). As `amostras` do
segundo ficam todas em 28–31. Anote a mediana de `cpu`.

### Step 2: `frameCapFor` recebe `motion`

Em `client/src/world/pacing.ts`:

1. Comentário do topo:
   `// Ritmo do laço do escritório: 60 qps só quando a câmera se mexe ou há entrada; 30 (15 sem foco) quando algo anda`
   `// na tela; sem movimento ou com a economia de energia, 10 (5 sem foco).`
2. JSDoc das constantes:
   - `IDLE_FPS`: `/** Algo andando na tela (personagem, montagem de sala, partículas), janela com foco. */`
   - `UNFOCUSED_FPS`: `/** Algo andando na tela, janela sem foco (ex.: num segundo monitor). */`
   - `STILL_FPS`: `/** Nada andando na tela, ou economia de energia; janela com foco (digitar troca de pose a cada 120 ms). */`
   - `STILL_UNFOCUSED_FPS`: `/** Nada andando na tela, ou economia de energia; janela sem foco. */`
3. `frameCapFor`:

   ```ts
   export function frameCapFor(s: { uiCap: number; active: boolean; focused: boolean; powerSave: boolean; motion: boolean }): number {
     if (s.active) return s.uiCap;
     const still = s.powerSave || !s.motion;
     const idle = still ? (s.focused ? STILL_FPS : STILL_UNFOCUSED_FPS) : s.focused ? IDLE_FPS : UNFOCUSED_FPS;
     return s.uiCap > 0 ? Math.min(s.uiCap, idle) : idle;
   }
   ```

   JSDoc: `motion` = o último quadro desenhou algo andando (personagem andando/correndo, sala em montagem,
   partículas, pingue-pongue).

Em `client/src/world/pacing.test.ts`: acrescente `motion: true` em todas as chamadas existentes (os resultados
esperados delas não mudam) e os casos novos do "Test plan".

**Verify**: `npx vitest run client/src/world/pacing.test.ts` → todos passam. `npm run typecheck` falha **só** em
`client/src/world/index.ts` (falta `motion`).

### Step 3: O renderer avisa se desenhou movimento

Em `client/src/world/render/renderer.ts`:

1. Campo público junto de `cars: Car[] = [];`:

   ```ts
   /**
    * O último quadro desenhou algo andando (personagem andando/correndo, sala em montagem, partículas, pingue-pongue):
    * o laço fica a 30 qps; sem isso cai para 10 (pacing.ts). Carro não conta: quase sempre há um passando.
    */
   motion = false;
   ```

2. `frame`: primeira linha depois de `const { ctx, camera } = this;` → `this.motion = false;`
3. Laço de pisos (linhas 385-390): depois de `const anim = ...;` → `if (anim.mode !== 'none') this.motion = true;`
4. `drawCharacter`: depois de `const y = Math.round(ch.y + ch.offY);` →
   `if (ch.pose === 'walk' || ch.pose === 'run') this.motion = true;`
5. Partículas: depois de `this.particles.draw(ctx, vx0, vy0, vx1, vy1, now);` →
   `if (this.particles.count > 0) this.motion = true;`
6. `drawPingPong`: depois de `if (!a || !b) continue;` → `this.motion = true;`

**Verify**: `grep -c "this.motion = true" client/src/world/render/renderer.ts` → `4`;
`grep -c "this.motion = false" client/src/world/render/renderer.ts` → `1`.

### Step 4: O laço passa `motion`

Em `client/src/world/index.ts`, na chamada de `frameCapFor` no início do `loop`, acrescente
`motion: renderer.motion,` depois de `powerSave: !!options.powerSave,`.

**Verify**: `npm run typecheck` → exit 0; `npm test` → exit 0.

### Step 5: Texto da economia

Com o movimento, a economia passa a se diferenciar pelo "sempre 10, mesmo com gente andando". Em
`client/src/ui/settings.ts`, troque a descrição de `powerSave`:

- de: `'Escritório parado a 10 quadros por segundo (5 com a janela sem foco) e cenário em resolução menor. Bem menos CPU e bateria; quem anda fica um pouco aos saltos.'`
- para: `'Escritório sempre a 10 quadros por segundo (5 com a janela sem foco), mesmo com gente andando, e cenário em resolução menor. Menos CPU e bateria; quem anda fica um pouco aos saltos.'`

Troque a chave e a tradução em `shared/locales/en.ts`, `es.ts` e `fr.ts`:

- en: `"Office always at 10 frames per second (5 when the window is not focused), even with people walking, and scenery at a lower resolution. Less CPU and battery; people walking look a little jumpy."`
- es: `"Oficina siempre a 10 cuadros por segundo (5 con la ventana sin foco), incluso con gente caminando, y escenario a menor resolución. Menos CPU y batería; quien camina se ve un poco a saltos."`
- fr: `"Bureau toujours à 10 images par seconde (5 quand la fenêtre n'a pas le focus), même quand des personnages marchent, et décor en plus basse résolution. Moins de CPU et de batterie ; les personnages qui marchent sont un peu saccadés."`

**Verify**: `npm run i18n:check -- --strict` → exit 0.

### Step 6: Medir

Rebuild, reinicie o preview e rode 3× cada comando do passo 1.

**Verify**:

- `habblaud-motion.mjs`: as `amostras` mostram valores em 9–13 (sem movimento) e, quando alguém anda, em 25–31.
  Se nenhuma amostra ficar em 9–13 nas 3 rodadas, o escritório estava sempre em movimento. Não mexa no escritório
  real para forçar isso (nada de `debug.reset()`): reporte as amostras e siga.
- `agente andando:` ≥ 25.
- `habblaud-cpu.mjs` (30 s): mediana de `cpu` ≤ 80% da mediana do passo 1. O ganho depende de quanto tempo o
  escritório passa sem ninguém andando. Anote o valor no relatório final.
- `SAVE=1 node /tmp/habblaud-cpu.mjs http://127.0.0.1:4790/ 15`: `quadros/s` entre 9 e 13 (a economia continua
  igual ao plano 010).

### Step 7: Changelog

`CHANGELOG.md`, `## [Não lançado]`, `### Alterado` (crie entre `### Adicionado` e `### Corrigido` se não existir):
`- **Escritório mais leve:** sem ninguém andando na tela, o escritório desenha a 10 quadros por segundo (5 sem foco)
em vez de 30; volta a 30 assim que alguém anda, uma sala é montada ou cai confete. Medido com GPU: <cpu antes> →
<cpu depois> de CPU.` Use as medianas dos passos 1 e 6. Ajuste a entrada "Economia de energia" (`### Adicionado`)
para dizer "sempre a 10 quadros por segundo (5 sem foco), mesmo com gente andando".

**Verify**: `git status --short` lista só arquivos do "In scope" (mais `plans/`).

## Test plan

Em `client/src/world/pacing.test.ts`, além de `motion: true` nos casos existentes:

- sem economia, parado, sem movimento, com foco → `10`;
- sem economia, parado, sem movimento, sem foco → `5`;
- sem economia, parado, com movimento, com foco → `30` (o caso antigo);
- economia, parado, com movimento, com foco → `10` (a economia ignora o movimento);
- ativo (entrada), sem movimento, sem limite da UI → `0` (entrada continua no ritmo do monitor);
- sem movimento com limite 30 da UI (terminal aberto) → `10`.

O sinal `motion` do renderer não tem teste unitário: o renderer precisa de canvas e o Vitest roda em
`environment: 'node'`. A prova é o passo 6 (`agente andando ≥ 25`).

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 6 casos novos em `pacing.test.ts`
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] Passo 6: amostras em 9–13 sem movimento, `agente andando` ≥ 25, `SAVE=1` a 9–13 quadros/s
- [ ] Passo 6: mediana de `cpu` sem economia ≤ 80% da linha de base
- [ ] `CHANGELOG.md` atualizado
- [ ] Nenhum arquivo fora do "In scope" modificado (`git status`)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- O plano 010 não está no branch ou os trechos de "Current state" não batem.
- `agente andando` < 25: o sinal de movimento não está chegando ao laço.
- As amostras nunca saem de 28–31, mesmo com o escritório sem ninguém andando na captura (`SHOT=/tmp/x.png` no
  script de CPU mostra a cena). Algo marca `motion` sempre (provavelmente partículas que nunca acabam): reporte qual.
- Para funcionar, seria preciso contar carro, elevador ou telas como movimento: é decisão de produto, reporte.

## Maintenance notes

- Toda animação nova que **desloca** algo na tela (não só troca de pose) precisa ligar `this.motion = true` no
  renderer. Sem isso, ela roda a 10 quadros/s e dá tranco.
- O sinal vem do quadro anterior: quando alguém começa a andar, os primeiros ≤100 ms saem a 10 quadros/s.
- Se 10 quadros/s parado incomodar alguém (ex.: telas com futebol aos saltos), suba `STILL_FPS` para 15: ainda
  economiza ~40% (medido: 15 qps = 50% contra 82% a 30).
- Revisor: confira as 4 condições de movimento e que carro **não** está entre elas.
