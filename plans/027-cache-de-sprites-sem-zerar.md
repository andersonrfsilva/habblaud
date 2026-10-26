# Plan 027: Cache de sprites descarta só os mais antigos ao encher, em vez de zerar

> **Executor instructions**: siga o plano passo a passo. Rode cada verificação e confira o resultado esperado antes
> de seguir. Se acontecer qualquer item de "STOP conditions", pare e relate; não improvise. Ao terminar, atualize a
> linha 027 em `plans/README.md` (a menos que quem te despachou diga que cuida do índice).
>
> **Drift check (rode primeiro)**: `git diff --stat 450e635..HEAD -- client/src/art/index.ts client/src/art/core/`
> Se algo mudou, compare com os trechos abaixo; se não baterem, é STOP.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `450e635`, 2026-10-26
- **Origem**: issue upstream marmottajr/habblaud#73, seção 3 ("O cache de sprites zera por completo ao encher")

## Why this matters

Os sprites do escritório (personagens, móveis, pisos, paredes, avatares) são gerados uma vez e guardados em caches
com limite. Quando um cache enche, ele hoje **apaga tudo** (`map.clear()`), e no quadro seguinte todos os sprites
visíveis são regenerados de uma vez: um engasgo visível. Ninguém viu o cache de personagens (30 000) encher em uso
normal, mas sessões longas com muitos agentes e trocas de aparência podem chegar lá. A correção é pequena: ao
encher, descartar só a quarta parte mais antiga (o `Map` guarda a ordem de inserção).

## Current state

`client/src/art/index.ts:56-77`:

```ts
/** Cache com limite simples (descarta tudo ao estourar — evita crescer sem fim em sessões longas). */
class Cache<T> {
  private map = new Map<string, T>();
  constructor(private readonly limit: number) {}
  get(key: string, make: () => T): T {
    let v = this.map.get(key);
    if (v === undefined) {
      if (this.map.size >= this.limit) this.map.clear();
      v = make();
      this.map.set(key, v);
    }
    return v;
  }
}

const charCache = new Cache<Sprite>(30000);
const furnCache = new Cache<FurnitureSprites>(4000);
const tileCache = new Cache<HTMLCanvasElement>(2000);
const floorCache = new Cache<HTMLCanvasElement>(1500);
const iconCache = new Cache<Sprite>(64);
const avatarCache = new Cache<HTMLCanvasElement>(2000);
const rugCache = new Cache<HTMLCanvasElement>(512);
```

`client/src/art/index.ts` usa DOM (`toCanvas`), por isso os testes do módulo de arte (`client/src/art/art.test.ts`)
não o importam: testam só módulos puros (ex.: `./core/pixbuf`, `./character/render`). A classe precisa ir para um
módulo puro para ser testada. Pasta `client/src/art/core/` tem hoje `color.ts`, `pixbuf.ts`, `sprite.ts`.

**Decisão (não reabrir):** sem reordenar no acerto (LRU de verdade exigiria `delete` + `set` a cada `get`, e o
`get` roda milhares de vezes por quadro). Descartar por ordem de inserção basta: o que for descartado e ainda estiver
em uso é regenerado sob demanda, um por vez.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Teste novo | `npx vitest run client/src/art/core/cache.test.ts` | todos passam |
| Tests | `npm test` | todos passam |

## Scope

**In scope**:
- `client/src/art/core/cache.ts` (criar)
- `client/src/art/core/cache.test.ts` (criar)
- `client/src/art/index.ts` (só remover a classe local e importar a nova)
- `CHANGELOG.md`

**Out of scope**:
- Os limites de cada cache (linhas 71-77): não mude os números.
- Qualquer outro cache do client (ex.: `client/src/ui/avatar.ts`, que não tem limite; fora deste plano).

## Git workflow

- Branch: `advisor/027-cache-de-sprites`, a partir de `producao`.
- Um commit em português, no estilo do repo, ex.: `arte: cache de sprites descarta só os mais antigos ao encher`.
- Não faça push nem PR.

## Steps

### Step 1: classe pura em `client/src/art/core/cache.ts`

```ts
// Cache com limite para os sprites gerados: ao encher, descarta a quarta parte mais antiga (ordem de inserção do
// Map), em vez de zerar tudo e regenerar a tela inteira de uma vez.
export class Cache<T> {
  private readonly map = new Map<string, T>();
  constructor(private readonly limit: number) {}

  get size(): number {
    return this.map.size;
  }

  get(key: string, make: () => T): T {
    let v = this.map.get(key);
    if (v === undefined) {
      if (this.map.size >= this.limit) this.evict();
      v = make();
      this.map.set(key, v);
    }
    return v;
  }

  private evict(): void {
    let n = Math.max(1, Math.ceil(this.limit / 4));
    for (const k of this.map.keys()) {
      if (n-- <= 0) break;
      this.map.delete(k);
    }
  }
}
```

(Apagar do `Map` durante a iteração de `keys()` é seguro em JS.)

**Verify**: `npm run typecheck` → exit 0.

### Step 2: usar em `client/src/art/index.ts`

Apague a classe local e o comentário dela (linhas 56-69) e importe `import { Cache } from './core/cache';` junto dos
outros imports de `./core/`. As linhas 71-77 não mudam.

**Verify**: `npm run typecheck` → exit 0; `grep -n "map.clear()" client/src/art/index.ts` → nada.

### Step 3: testes

`client/src/art/core/cache.test.ts`, no estilo de `client/src/art/art.test.ts` (vitest, `describe`/`it`/`expect`,
textos em português). Casos:
1. Acerto não chama `make` de novo (use um contador).
2. Com `limit = 8`, depois de inserir `a`…`h` (8 chaves) e pedir `i`: `size` é 7 (8 − 2 descartadas + 1 nova); `a` e
   `b` regeneram (contador sobe), `c` e `h` não.
3. `limit = 1`: cada chave nova substitui a anterior; `size` nunca passa de 1.

**Verify**: `npx vitest run client/src/art/core/cache.test.ts` → 3 testes passam; `npm test` → todos passam.

### Step 4: CHANGELOG

`CHANGELOG.md`, `## [Não lançado]` › `### Corrigido` (crie no formato das outras se não existir): uma linha dizendo
que o cache de sprites, ao encher, descarta só os mais antigos em vez de zerar e regenerar a tela toda.

**Verify**: `git diff --stat` mostra só os arquivos do Scope.

## Test plan

Os 3 casos do passo 3. O resto do comportamento (sprites corretos) já é coberto por `client/src/art/art.test.ts`,
que não muda.

## Done criteria

- [ ] `npm run typecheck` exit 0
- [ ] `npm test` exit 0, com os 3 testes novos de `client/src/art/core/cache.test.ts`
- [ ] `grep -n "map.clear()" client/src/art/index.ts` sem resultado
- [ ] Limites das linhas 71-77 iguais aos de antes (`git diff client/src/art/index.ts` não toca nelas)
- [ ] `git status` só com arquivos do Scope; linha 027 do `plans/README.md` atualizada

## STOP conditions

- A classe `Cache` em `client/src/art/index.ts` não bater com o trecho acima (código mudou).
- Algum outro arquivo importar ou estender a `Cache` local (`grep -rn "Cache<" client/src`): relate antes de mover.
- Uma verificação falhar duas vezes.

## Maintenance notes

- Se um dia algum cache mostrar troca constante (encher e descartar o tempo todo), o problema é o limite ou a chave
  (ex.: chave com valor que muda a cada quadro), não a política de descarte. Medir com `size` antes de aumentar.
- Adiado: LRU com reordenação no acerto (custo por `get` no laço de desenho não compensa).
