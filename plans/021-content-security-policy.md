# Plan 021: A página do Habblaud passa a ser servida com Content-Security-Policy (rede de proteção contra um futuro `innerHTML` errado)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- server/http/static.ts server/http/http.test.ts client/index.html vite.config.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md` muda com frequência: basta a seção `## [Não lançado]` existir.)

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: MED (uma política errada deixa a tela em branco; por isso há uma verificação em navegador real)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `af73a23` (branch `producao`), 2026-10-25

## Why this matters

A página do Habblaud (`index.html` e a rota de SPA que cai nela) sai do servidor **sem Content-Security-Policy**. Só
os anexos de entrega têm CSP (`server/http/app.ts:203`, com `sandbox`). Hoje o cliente é limpo: todo `innerHTML` recebe
texto constante, o markdown é montado com `createElement`/`textContent` e os links ficam restritos a http(s)/mailto.
Mas a mesma página mostra texto que o agente controla (títulos, saída de ferramentas, respostas do bate-papo paralelo,
mensagens) e **aprova permissões e envia mensagens**. Um único `innerHTML` errado no futuro viraria execução de script
com o login do escritório, e nada barraria. A CSP é a segunda barreira: com `script-src 'self'`, um `<img onerror=…>`
ou um `<script>` injetado simplesmente não roda. Este plano liga a política só no modo de produção e prova, em
navegador real, que nada do app quebra.

## Current state

Arquivos:

- `server/http/static.ts` — serve `dist/client` em produção (`createStaticHandler`); é aqui que ficam os cabeçalhos de
  segurança da página.
- `server/http/http.test.ts` — testes da API e dos estáticos (o teste `estáticos: SPA fallback…`, linhas 242-272).
- `client/index.html` — fonte da página; tem **um** `<style>` embutido e **um** `<script type="module" src="/src/main.ts">`.
- `vite.config.ts` — `assetsInlineLimit: 0`, `assetsDir: 'bundle'`.
- `CHANGELOG.md` — `## [Não lançado]`.

`server/http/static.ts:77-101` (função `send`):

```ts
  const send = (req: IncomingMessage, res: ServerResponse, file: string, st: Stats, cache: string) => {
    const etag = etagOf(st);
    const headers: Record<string, string | number> = {
      'Cache-Control': cache,
      ETag: etag,
      'Last-Modified': new Date(st.mtimeMs).toUTCString(),
      'X-Content-Type-Options': 'nosniff',
      // A página não deve ser embutida em sites de terceiros (clickjacking nas configurações).
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
    };
    if (isNotModified(req, etag, st.mtimeMs)) {
      res.writeHead(304, headers);
      return void res.end();
    }
    res.writeHead(200, {
      ...headers,
      'Content-Type': MIME[extname(file).toLowerCase()] ?? 'application/octet-stream',
      'Content-Length': st.size,
    });
```

`server/http/app.ts:203` (única CSP existente, dos anexos de entrega — **não mexer**):

```ts
    ...(active ? { 'Content-Security-Policy': "sandbox; default-src 'none'; img-src data: blob: 'self'; style-src 'unsafe-inline'; media-src 'self'" } : {}),
```

`server/http/http.test.ts:242-248` (teste de estáticos que será estendido):

```ts
  it('estáticos: SPA fallback, immutable só no /bundle (hash) e revalidação no resto', async () => {
    const page = await fetch(`${env.base}/sala/qualquer`);
    expect(page.status).toBe(200);
    expect(await page.text()).toContain('<title>Habblaud</title>');
    expect(page.headers.get('cache-control')).toBe(REVALIDATE);
    expect(page.headers.get('x-frame-options')).toBe('DENY');
    const bundle = await fetch(`${env.base}/bundle/main-abc12345.js`);
    expect(bundle.headers.get('cache-control')).toBe(IMMUTABLE);
```

Nesse teste o `dist` falso tem `index.html`, `bundle/main-abc12345.js`, `assets/brand/logo-mark.png` e `assets/manifest.json`.

### O que o cliente de produção realmente precisa (investigado em `af73a23`)

Build de teste (`npx vite build --config vite.config.ts --outDir /tmp/...`) e leitura do código do cliente:

| Recurso | Achado | Diretiva |
|---|---|---|
| Scripts | `index.html` compilado tem **um** `<script type="module" crossorigin src="/bundle/main-….js">` externo; nenhum script embutido; nenhum Worker, `eval`, `new Function`, WASM | `script-src 'self'` |
| Estilos | `<style>` embutido no `<head>` (linha 34 do `index.html` compilado) e CSS do Vite em `/bundle/*.css`. O xterm e o código do cliente criam elementos `<style>` e usam `element.style` | `style-src 'self' 'unsafe-inline'` |
| Fontes | `@fontsource/pixelify-sans` vira arquivos `.woff2` em `/bundle/` (`assetsInlineLimit: 0`) | `font-src 'self'` |
| Imagens | `/assets/...` (mesma origem); avatares via `canvas.toDataURL('image/png')` (`client/src/ui/avatar.ts:16`, `img.src = data.url` na linha 53); miniaturas de anexo com `URL.createObjectURL(file)` (`client/src/ui/spawn.ts:325`); um `url("data:image/svg+xml…")` em `client/src/ui/styles.css:7240` | `img-src 'self' data: blob:` |
| Rede | Só mesma origem: `fetch('/api/…')`, `EventSource` (`store.ts`, `world-stream.ts`, `terminal.ts`, `ptypane.ts`). O Habblaud World online **passa pelo servidor**: `client/src/net/world.ts:331` e `world-stream.ts:157` usam `base = '/api/world'`. O navegador **nunca** fala com host externo | `connect-src 'self'` (sem origens externas) |
| Áudio | `client/src/audio/*` usa Web Audio (sintetizado, sem arquivo e sem URL) | nenhuma entrada além do `default-src` |
| Ditado | `getUserMedia` + `MediaRecorder` + `POST /api/dictation` (`client/src/ui/dictation.ts`) | nenhuma entrada |
| Iframes | `client/src/ui/deliveries.ts:421` abre PDF e página de entrega num `<iframe src="/api/…">` **da mesma origem** | `frame-src` herda `default-src 'self'` |
| Links externos | `https://habblaud.com/world/termos` e GitHub são `<a href>` (navegação não é restringida por CSP; `form-action` só vale para `<form>`) | nenhuma |
| Download | `client/src/ui/world/settings-world.ts:1396` cria `URL.createObjectURL(new Blob(…))` para um `<a download>` (navegação, não restringida) | nenhuma |
| Modo dev | O `--dev` usa o Vite em middleware e **não** passa por `createStaticHandler` (`server/index.ts:429-443`); o Vite precisa de scripts embutidos e WebSocket de HMR | CSP **só** em `static.ts`, portanto só em produção; o dev fica intocado |

Política escolhida (uma string só, constante exportada de `static.ts`):

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; media-src 'self' blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
```

Observações de projeto:

- `'unsafe-inline'` fica **só em `style-src`** (o `<style>` do `index.html` e o `<style>` do xterm). `script-src` não o
  tem: é a diretiva que importa para XSS.
- `frame-ancestors 'none'` repete o `X-Frame-Options: DENY` (o X-Frame-Options continua, por compatibilidade).
- `object-src 'none'` não atrapalha o PDF: ele abre num `<iframe>`, não em `<object>`/`<embed>`.
- A CSP vai **só nas respostas HTML** da página (`index.html` e o fallback de SPA). Em JS/CSS/imagem ela não tem efeito.
- Sem `upgrade-insecure-requests`: o Habblaud é usado em `http://localhost` e `http://<nome>.local` (acesso remoto na LAN).

Convenções: comentários em português explicando o porquê; testes em `it('frase em português', …)`; mudança visível
no `CHANGELOG.md` em `## [Não lançado]`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Typecheck | `npm run typecheck` | exit 0 |
| Testes | `npm test` | exit 0 (baseline em `af73a23`: 181 arquivos / 2180 testes passam, 2 ignorados) |
| Só o teste de HTTP | `npx vitest run server/http/http.test.ts` | todos passam |
| Build completo | `npm run build` | exit 0 (gera `dist/client` e `dist/server`) |
| Subir o servidor do build | veja o Passo 4 | `curl -sI http://127.0.0.1:4791/` mostra `Content-Security-Policy` |
| Traduções | `npm run i18n:check -- --strict` | exit 0 (nenhum texto `tr()` muda; é só garantia) |

## Scope

**In scope** (the only files you should modify):
- `server/http/static.ts`
- `server/http/http.test.ts`
- `CHANGELOG.md`
- `plans/README.md` (só a linha deste plano)

**Out of scope** (do NOT touch, even though they look related):
- `server/http/app.ts` — a CSP de sandbox dos anexos de entrega já está correta e é outra política.
- `client/**` — nenhum arquivo do cliente deve mudar. Se o smoke do Passo 5 mostrar uma violação, é STOP (veja abaixo), não um motivo para mexer no cliente.
- `server/index.ts` / `vite.config.ts` — o modo dev não recebe CSP; não crie uma variante.
- Nonces/hashes de script e `Content-Security-Policy-Report-Only`: não há script embutido a proteger.
- Cabeçalhos extras (`Permissions-Policy`, COOP/COEP): outro assunto.
- `~/apps/habblaud` (produção): nunca tocar.

## Git workflow

- Worktree recomendado:
  `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-021 -b advisor/021-content-security-policy producao`
  e `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-021/node_modules` (**nunca** faça commit do symlink: confira `git status`). Trabalhe em `/tmp/hb-021`.
- Um commit por passo lógico; mensagem em português, assunto em minúsculas (veja `git log --oneline -15`), por exemplo
  `segurança: serve a página com content-security-policy`.
- Não faça push nem abra PR.

## Steps

### Step 1: Adicionar a CSP aos cabeçalhos da página

Em `server/http/static.ts`:

1. Logo depois de `export const REVALIDATE = 'no-cache';` (linha 40), acrescente:

```ts
/**
 * CSP da página. Texto do agente (títulos, saída de ferramentas, respostas) é mostrado numa tela que aprova permissões
 * e manda mensagens; se um dia um `innerHTML` deixar passar HTML, o `script-src 'self'` impede que ele execute.
 * `'unsafe-inline'` fica só nos estilos (o <style> do index.html e o do xterm). O Habblaud World passa pelo servidor
 * (/api/world), então o navegador só fala com a própria origem. Só vale em produção: o modo dev (Vite) não passa por aqui.
 */
export const PAGE_CSP =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; " +
  "connect-src 'self'; media-src 'self' blob:; worker-src 'self' blob:; object-src 'none'; base-uri 'none'; " +
  "frame-ancestors 'none'; form-action 'self'";
```

2. Em `send`, depois de montar o objeto `headers` e **antes** do `if (isNotModified(…))`, acrescente (vale para 200 e 304):

```ts
    // Só o HTML carrega a política; o navegador a aplica ao documento, não aos arquivos de /bundle.
    if (extname(file).toLowerCase() === '.html') headers['Content-Security-Policy'] = PAGE_CSP;
```

**Verify**: `npm run typecheck` → exit 0.

### Step 2: Estender o teste de estáticos

Em `server/http/http.test.ts`, no teste `estáticos: SPA fallback, immutable só no /bundle…` (linhas 242-272):

1. Acrescente `PAGE_CSP` ao import da linha 15: `import { createStaticHandler, IMMUTABLE, PAGE_CSP, REVALIDATE } from './static';`
2. Depois de `expect(page.headers.get('x-frame-options')).toBe('DENY');` acrescente:

```ts
    // A página (inclusive a rota de SPA) leva a CSP; o script externo e as imagens não precisam dela.
    expect(page.headers.get('content-security-policy')).toBe(PAGE_CSP);
    const csp = page.headers.get('content-security-policy')!;
    expect(csp).toMatch(/(^|; )script-src 'self'(;|$)/);
    expect(csp).not.toMatch(/script-src[^;]*unsafe-/);
    expect(csp).toContain("frame-ancestors 'none'");
```

3. Depois da linha `expect(bundle.headers.get('content-type')).toMatch(/javascript/);` acrescente:

```ts
    expect(bundle.headers.get('content-security-policy')).toBeNull();
```

4. No laço `for (const path of ['/assets/brand/logo-mark.png', '/assets/manifest.json'])`, não mude nada (esses não são HTML).
5. Antes do laço, acrescente a conferência do 304 da própria página (o navegador revalida o HTML a cada acesso, e a CSP não pode sumir nessa resposta):

```ts
    const pageEtag = page.headers.get('etag')!;
    const page304 = await fetch(`${env.base}/`, { headers: { 'If-None-Match': pageEtag } });
    expect(page304.status).toBe(304);
    expect(page304.headers.get('content-security-policy')).toBe(PAGE_CSP);
```

**Verify**: `npx vitest run server/http/http.test.ts` → todos passam (o teste falha se você comentar o `if` do Passo 1).

### Step 3: Entrada no CHANGELOG

Em `CHANGELOG.md`, sob `## [Não lançado]`, crie `### Alterado` (se ainda não existir; se existir, acrescente nela) e adicione:

```md
- **Página com Content-Security-Policy:** o servidor passa a mandar uma CSP junto com a página (só em produção, não no
  `npm run dev`). Scripts só rodam se vierem do próprio Habblaud, e a página não pode ser embutida em outro site. É uma
  barreira extra caso algum texto de agente chegue à tela como HTML. Nada muda no uso.
```

**Verify**: `grep -n "Content-Security-Policy" CHANGELOG.md` → uma linha na seção `[Não lançado]`.

### Step 4: Build e servidor de verdade, com dados temporários

A CSP é um cabeçalho do servidor: `vite preview` **não** a envia, então o smoke precisa do servidor do Habblaud.
No worktree (`/tmp/hb-021`):

```bash
npm run build
mkdir -p /tmp/hb021-data /tmp/hb021-home
HABBLAUD_PORT=4791 HABBLAUD_DATA_DIR=/tmp/hb021-data HOME=/tmp/hb021-home HABBLAUD_DEMO=1 \
  node dist/server/index.js > /tmp/hb021-server.log 2>&1 &
echo $! > /tmp/hb021-server.pid
```

(`HOME` temporário evita ler as sessões reais do usuário; `HABBLAUD_DEMO=1` liga os agentes simulados, que dão o que
abrir na tela. Se a porta 4791 estiver ocupada, use outra livre e ajuste os comandos.)

**Verify**:
`sleep 3; curl -sI http://127.0.0.1:4791/ | grep -i content-security-policy` → imprime a linha com `default-src 'self'; script-src 'self'; …`.
`curl -sI http://127.0.0.1:4791/bundle/$(ls dist/client/bundle | grep '^main-.*\.js$')` **não** mostra `Content-Security-Policy`.

### Step 5: Smoke em navegador real (zero violações)

Crie o script **temporário** `/tmp/hb021-smoke.mjs` (fora do repositório; não faça commit dele):

```js
// Uso (na raiz do worktree): node /tmp/hb021-smoke.mjs http://127.0.0.1:4791/
import { createRequire } from 'node:module';
const require = createRequire(process.cwd() + '/package.json');
const { chromium } = require('playwright-core');
const url = process.argv[2] ?? 'http://127.0.0.1:4791/';
const browser = await chromium.launch({ executablePath: process.env.HABBLAUD_CHROMIUM || '/usr/bin/chromium' });
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
const violations = [];
const errors = [];
// securitypolicyviolation dispara no documento; o init script o instala antes de qualquer script da página.
await page.addInitScript(() => {
  window.__csp = [];
  document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
});
page.on('console', (m) => {
  if (/Content Security Policy|Refused to/i.test(m.text())) violations.push(m.text());
});
page.on('pageerror', (e) => errors.push(String(e)));
const res = await page.goto(url, { waitUntil: 'load' });
if (!res.headers()['content-security-policy']) (console.error('FALHA: a página veio sem CSP'), process.exit(2));
await page.waitForTimeout(4000);
// Abre o que der: cada botão da interface, um por vez, fechando com Escape; depois clica no canvas (agentes/cidade).
const buttons = await page.locator('#ui button:visible').count();
for (let i = 0; i < Math.min(buttons, 40); i++) {
  const b = page.locator('#ui button:visible').nth(i);
  await b.click({ timeout: 1500, trial: false }).catch(() => {});
  await page.waitForTimeout(250);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(100);
}
for (const [x, y] of [[300, 300], [600, 400], [900, 500], [1100, 300], [720, 450]]) {
  await page.mouse.click(x, y);
  await page.waitForTimeout(400);
}
await page.waitForTimeout(2000);
const inPage = await page.evaluate(() => window.__csp);
console.log(JSON.stringify({ buttons, uiChildren: await page.locator('#ui *').count(), violations, inPage, errors }, null, 1));
await browser.close();
process.exit(violations.length + inPage.length ? 1 : 0);
```

Rode: `node /tmp/hb021-smoke.mjs http://127.0.0.1:4791/`. O script clica em cada botão visível da interface (painéis,
configurações, ajuda, histórico, entregas, cidade, o que estiver na barra) e em pontos do canvas (abre agentes e o
terminal dos agentes de demonstração). Não precisa de seletores exatos: o que importa é ele exercitar a interface
inteira sem violação.

**Verify**: o script sai com código 0 e imprime `"violations": []`, `"inPage": []`, `"errors": []`, e `uiChildren` maior que 20.
Se `uiChildren` for ~0 ou `errors` não estiver vazio, o app quebrou: para saber se a causa é a CSP, comente a linha do
`if (extname…'.html')` do Passo 1 no worktree, refaça `npm run build:server` e rode o smoke de novo (a página só volta
a funcionar sem CSP se a culpa for dela). Seja qual for o resultado, é STOP: reporte.

### Step 6: Limpeza e verificação final

```bash
kill $(cat /tmp/hb021-server.pid); rm -rf /tmp/hb021-data /tmp/hb021-home /tmp/hb021-server.log /tmp/hb021-server.pid /tmp/hb021-smoke.mjs
```

**Verify**:
- `npm run typecheck` → exit 0
- `npm test` → exit 0 (181 arquivos, 2180 testes + os asserts novos no teste existente; nenhum teste novo é um arquivo)
- `npm run i18n:check -- --strict` → exit 0
- `git status --short` → só `server/http/static.ts`, `server/http/http.test.ts`, `CHANGELOG.md` (e `plans/README.md`); `node_modules` não aparece.

## Test plan

- Arquivo: `server/http/http.test.ts`, estendendo o teste `estáticos: SPA fallback, immutable só no /bundle (hash) e
  revalidação no resto` (mesmo estilo; serve um `dist` falso com o `createStaticHandler` real). Cobre:
  - a rota de SPA (`/sala/qualquer`) e o `index.html` levam `Content-Security-Policy` igual a `PAGE_CSP`;
  - `script-src` é exatamente `'self'` (sem `unsafe-inline`/`unsafe-eval`) e há `frame-ancestors 'none'`: a regressão
    que importa é alguém "consertar" um erro de CSP afrouxando `script-src`;
  - o arquivo JS de `/bundle` **não** leva a CSP;
  - a revalidação da página (304 por `If-None-Match`) mantém a CSP: sem isso, a política sumiria na segunda visita.
- O que o teste unitário não prova (o app continuar funcionando com a política) é provado pelo smoke do Passo 5, que
  não entra no repositório porque depende de Chromium e do build completo.
- Verificação: `npx vitest run server/http/http.test.ts` → todos passam.

## Done criteria

ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `grep -n "PAGE_CSP" server/http/static.ts server/http/http.test.ts` mostra a constante, o uso em `send` e as asserções do teste
- [ ] `grep -n "unsafe-eval" server/http/static.ts` não retorna nada; `script-src` no arquivo não contém `unsafe-inline`
- [ ] O smoke do Passo 5 saiu com código 0, com `violations`, `inPage` e `errors` vazios
- [ ] `curl -sI` no servidor do build mostra a CSP na página e não a mostra em `/bundle/*.js`
- [ ] Nenhum arquivo fora do escopo foi modificado (`git status`); `client/**` e `server/http/app.ts` intocados
- [ ] `CHANGELOG.md` tem a entrada em `## [Não lançado]`
- [ ] `plans/README.md` com a linha do plano 021 atualizada

## STOP conditions

Stop and report back (do not improvise) if:

- O trecho de `static.ts:77-101` não bate com o excerto acima (o código mudou).
- O smoke mostrar **qualquer** violação de CSP ou a tela em branco. Reporte a diretiva e o `blockedURI`; **não**
  adicione `'unsafe-inline'`/`'unsafe-eval'` em `script-src` nem mexa em `client/**` por conta própria.
- `index.html` compilado (`dist/client/index.html`) tiver `<script>` sem `src` ou atributo `on…=` (a premissa "nenhum
  script embutido" seria falsa: confira com `grep -nP "<script(?![^>]*src)" dist/client/index.html` ou lendo o arquivo).
- `grep -rnE "new Worker|new WebSocket|serviceWorker" client/src --include=*.ts` (sem testes) retornar algo: a premissa
  "sem Worker nem WebSocket do navegador" mudou e `worker-src`/`connect-src` precisam ser revistos.
- `grep -rnE "fetch\(['\"\`]https?://|new EventSource\(['\"\`]https?://" client/src --include=*.ts` retornar algo: o
  navegador passou a falar com host externo e `connect-src 'self'` quebraria isso.
- Uma verificação falhar duas vezes após uma tentativa razoável de correção.
- A correção exigir mexer num arquivo fora do escopo.

## Maintenance notes

- **Novo recurso externo no cliente** (fonte de CDN, imagem de outro host, WebSocket para fora, Worker): a
  política precisa ganhar a origem na diretiva certa **na mesma mudança**, ou o recurso falha só em produção (o dev não tem CSP).
  O teste do Passo 2 não pega isso; rode o smoke do Passo 5.
- Hoje o Habblaud World passa pelo servidor (`/api/world`); se o cliente passar a falar direto com `habblaud.com`,
  `connect-src` precisa dessa origem exata (nunca `*`).
- O `'unsafe-inline'` de `style-src` existe por causa do `<style>` do `index.html` e do xterm. Um passo futuro pode
  trocá-lo por hash/nonce (exige o servidor reescrever o HTML por resposta ou fixar o hash no build); fica de fora
  por ser grande para o ganho (CSS injetado quase não rende exploração).
- O revisor deve conferir: `script-src` continua só `'self'`; a CSP só entra em HTML; o modo dev continua sem CSP.
- Adiado de propósito: `Content-Security-Policy-Report-Only` com endpoint de relatório, `Permissions-Policy` e
  COOP/COEP — não há onde coletar relatórios hoje e nenhum deles fecha uma brecha concreta deste achado.
