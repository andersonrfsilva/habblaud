# Plan 023: Ver e baixar os arquivos da pasta de trabalho pelo escritório (botão "Arquivos" na gaveta)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Where to work**: only in the development clone `~/Desenvolvimento/habblaud` (or a `git worktree` of it). NEVER
> edit, build, run `npm`/`git` commands in, or install anything from `~/apps/habblaud`: that is the production
> Habblaud (`omp-tenant release` reads commits from it). Never run `omp-tenant` or `npm run omp:install`. If you find
> yourself in `~/apps/habblaud`, STOP.
>
> **Drift check (run first)**: `git diff --stat 799cefd..HEAD -- server/http/app.ts server/index.ts server/deliveries/files.ts shared/types.ts client/src/ui/drawer.ts client/src/ui/index.ts client/src/ui/icons.ts client/src/ui/styles.css client/src/net/access.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: L
- **Risk**: MED (rota nova que lê o disco e responde pela porta remota com login; um erro de contenção de caminho
  vaza arquivos fora da pasta de trabalho)
- **Depends on**: none
- **Category**: direction
- **Planned at**: commit `e689895`, 2026-10-25 (excertos reconferidos em `799cefd`: só `server/index.ts` mudou, fora dos trechos citados)

## Why this matters

O Habblaud roda em modo multiusuário (`omp-tenant`): cada usuário tem um pod com o omp e o Habblaud, e acessa o
escritório pelo navegador (porta remota com login, por HTTPS). Os agentes criam e alteram arquivos na pasta de
trabalho do usuário (`~/projetos` e as pastas dos projetos), mas o usuário não tem como ver nem baixar esses arquivos:
ele não tem shell no pod, e as Entregas só mostram anexos que o agente citou numa resposta. Este plano cria um
**navegador de arquivos com a cara do Habblaud**: um botão "Arquivos" (ícone de arquivo de aço em pixel art) na
gaveta da sala e na gaveta do agente abre uma janela com as pastas e os arquivos daquela sala; dá para entrar nas
pastas, ver imagem, PDF, vídeo, texto e página, e baixar qualquer arquivo. Só leitura: nada é apagado, movido nem
enviado.

Decisões já tomadas pelo dono (não reabra):

1. **Raízes navegáveis**: a pasta de cada sala que existe no escritório (`RoomInfo.path`) **e** a pasta
   `~/projetos` do servidor, quando existir. Nada fora delas.
2. **Entrada**: só botão na gaveta (sala e agente). Clicar no móvel `filing_cabinet` do escritório **não** faz parte
   deste plano.
3. **Tipos**: qualquer arquivo pode ser baixado (sem limite de tamanho, em stream, `Content-Disposition:
   attachment`). Só os tipos que as Entregas já mostram abrem no visualizador (a mesma tabela `TYPES` e a mesma
   sandbox para HTML/SVG). Arquivos e pastas com cara de segredo, e tudo que começa com `.`, nunca aparecem nem saem.

## Current state

### Onde os arquivos estão

- **Pods dos usuários** (`omp-tenant`, fora deste repositório, não edite): a HOME do usuário é
  `/habblaud/<nome>/home`, montada com o mesmo caminho no contêiner do omp (leitura e escrita) e no do Habblaud
  (**só leitura**). As sessões do omp começam em `~/projetos` (script `omp-tenant/image/salas:5`:
  `cd "$HOME/projetos" || exit 1`). O Habblaud do pod roda em modo Node (`HABBLAUD_IN_DOCKER=0`), com
  `HABBLAUD_REMOTE_ALLOW=terminal,permissions,messages`, então a trava `terminal` já vale na porta remota dos
  usuários. Dentro da HOME existe `~/.omp/agent/models.yml`, com o token do gateway do usuário: **nunca pode sair**
  (o filtro de nomes com `.` cobre isso).
- **Habblaud do dono** (produção, `habblaud.service`, podman): `HABBLAUD_IN_DOCKER=0`, `HOME=/home/anderson`, só
  `~/Desenvolvimento` montado (só leitura). As salas ficam em `~/Desenvolvimento/*`; `~/projetos` não existe lá e
  simplesmente não vira raiz.
- Uma sala pode ter a HOME inteira como pasta (agente aberto em `~`). Por isso o filtro de `.` é obrigatório: ele
  esconde `.omp`, `.claude`, `.codex`, `.habblaud`, `.config`, `.ssh` etc. sem precisar de lista.

### Servidor

- `server/http/app.ts` — todas as rotas `/api/*`. Exemplar do recurso mais parecido, as Entregas
  (`app.ts:612-631`):

```ts
    if (path === '/api/deliveries' || path.startsWith('/api/deliveries/')) {
      // Entregas mostram a resposta inteira e arquivos do agente: a mesma trava do terminal.
      const deliveries = deps.deliveries;
      if (!deliveries) sendJson(res, 403, { error: tr('entregas desligadas (precisam do terminal: acesso local)') });
      else if (!isRead) methodNotAllowed(res, 'GET');
      else if (!trusted(req, 'terminal')) sendJson(res, 403, { error: denied(req, tr('as entregas só abrem pelo próprio computador (http://localhost)')) });
      else if (path === '/api/deliveries') deliveries.list().then((list) => sendJson(res, 200, { deliveries: list }), (err) => fail(res, err));
```

- A trava (`app.ts:295-298`): `trusted(req, 'terminal')` = Host loopback na porta local; na porta remota, só se
  `HABBLAUD_REMOTE_ALLOW` tiver `terminal`. `denied(req, msg)` dá a mensagem certa para cada porta.
- `sendFile` (`app.ts:194-206`) monta os cabeçalhos dos anexos e manda um `Buffer` inteiro:

```ts
function sendFile(res: ServerResponse, data: Buffer, type: string, name: string): void {
  const active = /^text\/html|^image\/svg/.test(type);
  res.writeHead(200, {
    'Content-Type': type,
    'Content-Length': data.length,
    'Cache-Control': 'private, max-age=300',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'SAMEORIGIN',
    'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(name)}`,
    ...(active ? { 'Content-Security-Policy': "sandbox; default-src 'none'; img-src data: blob: 'self'; style-src 'unsafe-inline'; media-src 'self'" } : {}),
  });
  res.end(data);
}
```

- `GET /api/access` (`app.ts:422-438`) monta o `AccessInfo` que a página usa para mostrar ou esconder botões:

```ts
        const access: AccessInfo = {
          remote,
          local: !remote && isLoopbackHost(req.headers.host),
          permissions: !!deps.permissions && trusted(req, 'permissions'),
          messages: !!deps.messages && trusted(req, 'messages'),
          terminal: !!terminals && trusted(req, 'terminal'),
          spawn: !!deps.spawn && trusted(req, 'spawn'),
          world: !!deps.world && !!deps.terminal && !remote && isLoopbackHost(req.headers.host),
        };
```

- Rotas em módulo próprio: o padrão é `server/http/timeline.ts` (handler que devolve `false` para o resto) e, para
  stream de arquivo, a função `sendDay` dele (`timeline.ts:67-90`):

```ts
  const src = createReadStream(file, { start: 0, end: size - 1 });
  const done = (err: NodeJS.ErrnoException | null) => {
    if (err && !res.writableEnded) res.destroy();
  };
  if (gzip) pipeline(src, createGzip(), res, done);
  else pipeline(src, res, done);
```

- `server/deliveries/files.ts` — regras de arquivo das Entregas, reaproveitadas aqui:
  - `TYPES` (`files.ts:14-35`) e `fileType(path)` (`files.ts:48-50`): tipo de visualização (`image`, `pdf`, `video`,
    `page`, `text`, `file`) e `Content-Type`, por extensão.
  - `SECRET_DIRS` e `SECRET_NAMES` (`files.ts:38-39`), hoje privados e usados só em `pathProblem` (`files.ts:53-58`):

```ts
const SECRET_DIRS = /(^|[\\/])(\.ssh|\.gnupg|\.aws|\.azure|\.kube|\.docker|\.password-store|\.config[\\/]gh|\.config[\\/]gcloud|Keychains|\.habblaud[\\/]remote-tls)([\\/]|$)/i;
const SECRET_NAMES = /(^|[\\/])(\.env[^\\/]*|id_[a-z0-9]+|[^\\/]*\.(pem|key|p12|pfx|keystore|jks|kdbx)|[^\\/]*(secret|credential|password|senha|token)[^\\/]*)$/i;
...
export function pathProblem(path: string): string | undefined {
  if (!path || path.includes('\0') || !isAbsolute(path) || /(^|[\\/])\.\.([\\/]|$)/.test(path)) return tr('caminho inválido');
  if (!fileType(path)) return tr('tipo de arquivo que não se mostra aqui');
  if (SECRET_DIRS.test(path) || SECRET_NAMES.test(path)) return tr('arquivo protegido');
  return undefined;
}
```

- `server/index.ts:366-413` — `createApiHandler({...})` recebe as dependências. As salas reais vêm de
  `hub.current().rooms`, sem as do demo (`index.ts:404`): `snap.rooms.filter((r) => !isDemoId(r.id))`
  (`isDemoId` de `shared/timeline`). A HOME do servidor é `config.home`; o modo Docker é `config.inDocker`; a trava do
  terminal é `config.terminal`.
- `shared/types.ts:199-218` — `RoomInfo { id, name, path, slot, seed, createdAt, effect? }`. `AccessInfo`
  (`types.ts:435-448`) tem campos opcionais para servidores antigos (ex.: `world?: boolean`).

### Página

- `client/src/ui/drawer.ts` — a gaveta. `AgentView` (construtor em `drawer.ts:280-445`) tem a linha de status com os
  botões Seguir e Centralizar (`drawer.ts:308-319`):

```ts
    const centerBtn = h('button', { class: 'ui-btn', type: 'button', title: tr('Levar a câmera até o agente'), on: { click: () => ctx.focusSelection() } });
    centerBtn.innerHTML = ICONS.center;
    centerBtn.append(h('span', { text: tr('Centralizar') }));
    const statusRow = h(
      'div',
      { class: 'ui-status' },
      h('span', { class: 'ui-status__label' }, this.dot, this.statusText, this.statusSince),
      h('span', { class: 'ui-status__actions' }, this.followBtn, centerBtn),
    );
```

  `RoomView` (`drawer.ts:886-1041`) tem `this.newBtn` ("Nova sessão") e `centerBtn` em
  `h('span', { class: 'ui-status__actions' }, this.newBtn, centerBtn)` (`drawer.ts:972`) e esconde o "Nova sessão"
  em `render()` com `setHidden(this.newBtn, !this.pty.enabled)` (`drawer.ts:1021`). A sala atual fica em
  `this.last` (`RoomInfo`). O agente atual do `AgentView` tem `roomId`; a sala vem de `ctx.store.room(roomId)`.
- `client/src/ui/deliveries.ts` — exemplar de janela (`<dialog>`) e de visualizador. O visualizador escolhe o
  elemento pelo tipo (`deliveries.ts:415-432`): `img` para imagem, `video` com `controls`, `iframe` para `pdf` e
  `page`, e texto buscado com `fetch` e mostrado em `pre`. Classes CSS reaproveitáveis: `ui-deliv-viewer`,
  `ui-deliv-viewer__bar`, `ui-deliv-viewer__body`, `ui-deliv-viewer__frame`, `ui-deliv-viewer__video`,
  `ui-deliv-viewer__err` (`styles.css:9151` em diante).
- `client/src/ui/index.ts:191-305` — monta os componentes: `new Deliveries(ctx, terminal)` (`index.ts:220`), o
  `drawer = new Drawer(ctx, terminal, pty)` (`index.ts:241`), `root.append(..., deliveries.el, ...)` (`index.ts:296`)
  e a lista `components` (`index.ts:305`).
- `client/src/net/access.ts` — `access()` devolve o `AccessInfo` do servidor; `fallbackAccess` (`access.ts:26-29`)
  e `isAccessInfo` (`access.ts:35-38`) só exigem os campos antigos.
- `client/src/ui/icons.ts` — `pixelIcon(rows)` desenha ícones em pixel (`#` = cor do texto). Exemplo:
  `delivery: pixelIcon(['...####...', '..#....#..', ...])` (`icons.ts:304`).
- `client/src/ui/spawn.ts:53` `shortPath(path)` e `spawn.ts:68` `formatBytes(n)`; `client/src/ui/format.ts:21`
  `relativeTime(at, now)`. Use esses, não crie outros.
- `client/src/ui/dom.ts` — `h()`, `iconButton()`, `setHidden()`, `setText()`.

### Convenções

- Todo texto visível passa por `tr('texto em PT')` (`shared/i18n.ts`), com tradução em `shared/locales/en.ts`,
  `es.ts` e `fr.ts`; o CI roda `npm run i18n:check -- --strict` (falha com chave faltando ou sobrando).
- Toda mudança visível entra em "## [Não lançado]" › "### Adicionado" no `CHANGELOG.md` (em PT, no estilo das
  entradas que já estão lá).
- Comentários e textos em português; servidor sem dependências de runtime (só `node:*`).
- Testes Vitest ao lado do arquivo (`*.test.ts`). Teste de rota HTTP: `server/http/http.test.ts` sobe um servidor
  real com `start(extra)` (`http.test.ts:25-70`) e usa `fetch` ou `raw()` (para trocar o `Host`). Testes de regra de
  arquivo: `server/deliveries/files.test.ts`. Teste de modelo puro da página: `client/src/ui/aside-model.test.ts`.
- Paleta e fontes da interface: variáveis em `client/src/ui/styles.css:6-40` (`--ui-bg-solid`, `--ui-line-strong`,
  `--ui-text`, `--ui-muted`, `--ui-accent`, `--font-px` para rótulos de painel, `--font-ui` para o corpo,
  `--font-mono` para tamanhos e caminhos).

## Commands you will need

| Purpose   | Command | Expected on success |
|-----------|---------|---------------------|
| Typecheck | `npm run typecheck` | exit 0, no errors |
| Tests (alvo) | `npx vitest run server/files server/http/http.test.ts client/src/ui/files-model.test.ts` | all pass |
| Tests (tudo) | `npm test` | all pass (baseline em `799cefd`: verde) |
| Traduções | `npm run i18n:check -- --strict` | exit 0 |
| Build | `npm run build` | exit 0 |

## Scope

**In scope** (the only files you should modify or create):
- `server/files/browse.ts` (create) — raízes, contenção de caminho, listagem.
- `server/files/browse.test.ts` (create)
- `server/http/files.ts` (create) — rotas `/api/files*`.
- `server/http/app.ts` — campo novo em `ApiDeps`, despacho das rotas, `files` no `AccessInfo`, cabeçalhos de
  arquivo extraídos de `sendFile`.
- `server/http/http.test.ts` — testes das rotas.
- `server/index.ts` — liga as dependências.
- `server/deliveries/files.ts` — só exportar um teste de segredo (`isSecretPath`) e usá-lo em `pathProblem`.
- `shared/types.ts` — tipos `FileRoot`, `FileEntry`, `FileListing`; `files?: boolean` em `AccessInfo`.
- `client/src/ui/files.ts` (create) — a janela "Arquivos".
- `client/src/ui/files-model.test.ts` e `client/src/ui/files-model.ts` (create) — partes puras (migalhas, URLs).
- `client/src/ui/drawer.ts`, `client/src/ui/index.ts`, `client/src/ui/context.ts`, `client/src/ui/icons.ts`,
  `client/src/ui/styles.css`
- `shared/locales/en.ts`, `es.ts`, `fr.ts`
- `CHANGELOG.md`, `server/README.md` (tabela de rotas, perto da linha 107), e a tabela de rotas dos quatro READMEs
  (`README.md:996`, `README.pt-BR.md:993`, `README.es.md:1029`, `README.fr.md:1039`, logo abaixo da linha
  `/api/deliveries…`).
- `plans/README.md` (sua linha).

**Out of scope** (do NOT touch, even though they look related):
- `server/deliveries/fetcher.ts`, `server/deliveries/deliveries.ts` e as rotas `/api/deliveries*`: as Entregas
  continuam como estão.
- `server/http/remote.ts`: **não** crie um `RemoteFeature` novo. A trava é a `terminal`, que os pods já liberam;
  um recurso novo obrigaria mudar o `omp-tenant`.
- Qualquer escrita no disco (upload, apagar, renomear, criar pasta) e baixar pasta inteira em zip: fora deste plano.
- Clique no móvel `filing_cabinet` do escritório (`client/src/world/**`, `client/src/art/**`): fora.
- O repositório `~/Desenvolvimento/omp-tenant` e `~/apps/habblaud`.

## Git workflow

- Branch: `advisor/023-arquivos`
- Um commit por passo ou unidade lógica; mensagens curtas em português, no estilo do `git log` (ex.:
  `testes: ack repetido conta 4 tentativas fixas (pega ACK_RETRY_MS vazio)`, `plans: ...`). Ex.:
  `arquivos: rotas /api/files com raízes das salas e ~/projetos`.
- Não faça push nem merge.

## Contrato da API (implemente exatamente isto)

Todas só `GET` (outro método: 405 com `Allow: GET`). Recurso desligado (`deps.browse` ausente): 403
`{error: tr('arquivos desligados (precisam do terminal: acesso local, fora do Docker)')}`. Sem a trava:
403 `{error: denied(req, tr('os arquivos só abrem pelo próprio computador (http://localhost)'))}`.

| Rota | Resposta |
|------|----------|
| `GET /api/files` | `200 {roots: FileRoot[]}` |
| `GET /api/files/list?path=<absoluto>` | `200 FileListing`; pasta fora das raízes, oculta ou inexistente: 404 `{error}`; não é pasta: 400 |
| `GET /api/files/raw?path=<absoluto>` | arquivo para ver: só se `fileType(path)` existir; senão 415 `{error}`. Cabeçalhos como `sendFile` (inline, CSP sandbox para HTML/SVG), `Content-Length`, corpo em stream |
| `GET /api/files/raw?path=<absoluto>&download=1` | qualquer arquivo: `Content-Type: application/octet-stream`, `Content-Disposition: attachment; filename*=UTF-8''<nome>`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: same-origin`, `Content-Length`, stream |

Tipos novos em `shared/types.ts`:

```ts
/** Pasta de onde o navegador de arquivos começa (GET /api/files): a de uma sala ou ~/projetos. */
export interface FileRoot {
  /** Caminho real (sem links), absoluto. */
  path: string;
  /** Nome da sala, ou "projetos". */
  label: string;
  /** Sala dona da raiz (ausente para ~/projetos). */
  roomId?: string;
}

export interface FileEntry {
  name: string;
  type: 'dir' | 'file';
  /** Bytes (0 para pasta). */
  size: number;
  /** ms desde 1970. */
  mtime: number;
  /** Tipo de visualização (o mesmo das entregas); ausente = só baixar. Nunca em pasta. */
  kind?: DeliveryAttachmentKind;
}

/** GET /api/files/list. */
export interface FileListing {
  /** Caminho real da pasta listada. */
  path: string;
  /** Raiz que contém a pasta (FileRoot.path). */
  root: string;
  /** Pastas primeiro, depois arquivos; cada grupo em ordem alfabética (localeCompare, sensitivity 'base'). */
  entries: FileEntry[];
  /** A pasta tinha mais que FILE_LIST_MAX itens visíveis; só os primeiros vieram. */
  truncated: boolean;
}
```

E em `AccessInfo`, depois de `world?`:

```ts
  /** Pode ver e baixar arquivos das pastas das salas e de ~/projetos (/api/files*). Ausente em servidores antigos. */
  files?: boolean;
```

## Steps

### Step 1: Exportar o teste de segredo das Entregas

Em `server/deliveries/files.ts`, adicione e exporte, logo depois de `SECRET_NAMES`:

```ts
/** O caminho tem pasta ou nome com cara de segredo (.ssh, .env, chaves, credenciais...). */
export function isSecretPath(path: string): boolean {
  return SECRET_DIRS.test(path) || SECRET_NAMES.test(path);
}
```

E troque, em `pathProblem`, `if (SECRET_DIRS.test(path) || SECRET_NAMES.test(path))` por `if (isSecretPath(path))`.
Nada mais muda nesse arquivo.

**Verify**: `npx vitest run server/deliveries/files.test.ts` → all pass; `npm run typecheck` → exit 0.

### Step 2: Tipos compartilhados

Adicione `FileRoot`, `FileEntry`, `FileListing` e `AccessInfo.files?` em `shared/types.ts` exatamente como no
contrato acima (perto de `AccessInfo`; `DeliveryAttachmentKind` já existe no arquivo).

**Verify**: `npm run typecheck` → exit 0.

### Step 3: Núcleo do servidor (`server/files/browse.ts`)

Crie o módulo, Node puro (`node:fs`, `node:path`), com cabeçalho de comentário em PT explicando: só leitura; raízes =
pastas das salas + `~/projetos`; nada com `.` no nome nem com cara de segredo; links que saem da raiz não contam.
Exporte:

```ts
export const FILE_LIST_MAX = 2000;

/** Nome que nunca aparece nem sai: começa com "." (configurações, .git, .omp com o token do gateway) ou tem cara de segredo. */
export function hiddenName(name: string): boolean;

/**
 * Raízes válidas: caminho real de cada candidato que existe e é pasta, sem repetir, e nunca "/" nem uma pasta com
 * menos de 2 níveis ("/home", "/tmp", "/habblaud"): sala aberta na raiz do disco não abre o disco inteiro.
 * Um candidato com segmento oculto (hiddenName em qualquer parte do caminho) também fica de fora.
 */
export function resolveRoots(candidates: readonly { path: string; label: string; roomId?: string }[], fs?: BrowseFs): FileRoot[];

/**
 * Caminho real dentro de alguma raiz, ou o motivo. Recusa: vazio, com \0, relativo, com segmento "..";
 * caminho real fora de todas as raízes; qualquer segmento DEPOIS da raiz (no caminho pedido e no real) com
 * hiddenName. Devolve a raiz que contém (a mais longa, se houver aninhadas).
 */
export function locate(path: string, roots: readonly FileRoot[], fs?: BrowseFs):
  { path: string; root: string; isDir: boolean; size: number } | { status: 400 | 404; error: string };

/** Lista a pasta (já localizada): sem ocultos; links seguidos só se o alvo real ficar dentro de `root`. */
export function listDir(dir: string, root: string, fs?: BrowseFs): { entries: FileEntry[]; truncated: boolean };
```

Regras que precisam estar no código:

- `BrowseFs` = `{ realpath(p): string; stat(p): { isFile(): boolean; isDirectory(): boolean; size: number; mtimeMs: number }; readdir(p): string[] }`
  com padrão `realpathSync`, `statSync`, `(p) => readdirSync(p)`. É só para os testes poderem simular; os testes
  podem também usar pastas reais em `tempDir()` (`server/test/fixtures`), que é o preferido.
- "Dentro da raiz": `real === root || real.startsWith(root + sep)` (`sep` de `node:path`). **Nunca** só
  `startsWith(root)` (pegaria `/a/proj-b` para a raiz `/a/proj`).
- `locate` erra com 404 e `tr('pasta ou arquivo fora da pasta de trabalho')` para fora/oculto/inexistente (não
  diga qual dos três: não revele a existência de arquivos ocultos), e com 400 `tr('caminho inválido')` para os
  formatos ruins.
- `listDir`: para cada nome de `readdir`, pule `hiddenName(name)`; `realpath` + `stat` do item dentro de
  `try/catch` (link quebrado ou sem permissão: pule); pule se o real não estiver dentro de `root`; pule o que não
  for arquivo nem pasta. `kind` = `fileType(name)?.kind` só para arquivos (`fileType` de
  `../deliveries/files`). Ordene (pastas antes; `a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })`)
  e corte em `FILE_LIST_MAX` com `truncated: true`. Para cortar sem `stat` de 100 mil itens: filtre os nomes
  ocultos, ordene os nomes, e faça `stat` só até juntar `FILE_LIST_MAX` válidos (a ordem pastas-antes vale dentro
  do que voltou; documente isso no comentário como `shortcut: pasta com mais de FILE_LIST_MAX itens mostra os
  primeiros por nome, não todas as subpastas; trocar por paginação se alguém reclamar`).
- `hiddenName(name)`: `name.startsWith('.') || isSecretPath(name)` (`isSecretPath` do passo 1). Para o teste de
  segmentos em `locate`, aplique `hiddenName` a cada segmento depois da raiz.

**Verify**: `npm run typecheck` → exit 0.

### Step 4: Testes do núcleo (`server/files/browse.test.ts`)

Use pastas reais em `tempDir()` de `server/test/fixtures` (devolve `{dir, cleanup}`; veja o uso em
`server/http/http.test.ts:25-27`), com `cleanup()` no `afterEach`.
Casos obrigatórios (um `it` cada):

1. `resolveRoots` descarta `/`, uma pasta de 1 nível, um caminho inexistente, um arquivo, e repetições (dois
   candidatos que dão no mesmo caminho real viram um só).
2. `locate` aceita a própria raiz e um arquivo dentro dela (devolve caminho real e `isDir`).
3. `locate` recusa `..`, caminho relativo e `\0` com 400.
4. `locate` dá 404 para uma pasta irmã com prefixo igual (raiz `<tmp>/proj`, pedido `<tmp>/proj-b/a.txt`).
5. `locate` dá 404 para link simbólico dentro da raiz que aponta para fora (`symlinkSync`; pule o teste no Windows
   com `it.skipIf(process.platform === 'win32')`).
6. `locate` dá 404 para `<raiz>/.env`, `<raiz>/.git/config`, `<raiz>/sub/id_rsa`, `<raiz>/token.txt`.
7. `listDir` esconde ocultos e segredos, esconde link para fora, mostra link para dentro, põe pastas antes,
   preenche `kind` (`a.png` → `image`, `a.ts` → ausente) e `size`.
8. `listDir` com `FILE_LIST_MAX + 5` arquivos devolve `FILE_LIST_MAX` e `truncated: true` (crie os arquivos vazios
   num laço; se ficar lento demais, > 5 s, use um `BrowseFs` falso só neste caso).

**Verify**: `npx vitest run server/files` → 8 tests pass.

### Step 5: Rotas (`server/http/files.ts`) e ligação em `app.ts`

1. Em `app.ts`, extraia de `sendFile` uma função exportada que só monta os cabeçalhos, e faça `sendFile` usá-la
   (comportamento das Entregas idêntico):

```ts
/** Cabeçalhos de um arquivo para ver na página (anexos das entregas, /api/files/raw). HTML e SVG em sandbox sem scripts. */
export function viewHeaders(type: string, name: string, length: number): Record<string, string | number> { ... }
```

2. Crie `server/http/files.ts` no padrão de `server/http/timeline.ts`:

```ts
export interface BrowseDeps {
  /** Candidatos a raiz agora (salas reais + ~/projetos); browse.ts filtra. */
  roots: () => readonly { path: string; label: string; roomId?: string }[];
}
/** Trata /api/files* (a trava e o método já foram conferidos em app.ts). */
export function handleFilesRoute(req: IncomingMessage, res: ServerResponse, url: URL, deps: BrowseDeps): void;
```

   - Calcule `resolveRoots(deps.roots())` a cada requisição (as salas mudam).
   - `/api/files` → `sendJson(res, 200, { roots })`.
   - `/api/files/list` → `locate`; se não for pasta, 400 `tr('não é uma pasta')`; senão
     `sendJson(res, 200, { path, root, ...listDir(path, root) })`.
   - `/api/files/raw` → `locate`; se for pasta, 400 `tr('não é um arquivo')`. Nome = `basename(path)`. Sem
     `download=1`: `fileType(path)` ausente → 415 `tr('tipo de arquivo que não se mostra aqui: baixe')`; senão
     `res.writeHead(200, viewHeaders(type, name, size))`. Com `download=1`: os cabeçalhos de download do contrato.
     Corpo: `pipeline(createReadStream(path), res, done)` com o mesmo `done` de `timeline.ts:85-87`. Em `HEAD`,
     só os cabeçalhos.
   - Outro caminho em `/api/files/*` → 404 `tr('rota desconhecida')`.
   - `url.searchParams.get('path')` já vem decodificado; não use `decodeURIComponent` de novo.
3. Em `app.ts`:
   - `ApiDeps` ganha `browse?: BrowseDeps` com comentário no estilo dos outros (`/** Arquivos das pastas de
     trabalho (/api/files*, http/files.ts): só leitura; mesma trava do terminal. Ausente = desligado (sem terminal ou
     no Docker). */`).
   - Antes do bloco das Entregas, um bloco igual ao das Entregas (`app.ts:612-618`) para
     `path === '/api/files' || path.startsWith('/api/files/')`: desligado → 403; `!isRead` →
     `methodNotAllowed(res, 'GET')`; `!trusted(req, 'terminal')` → 403 com `denied`; senão
     `try { handleFilesRoute(...) } catch (err) { fail(res, err) }`; `return true`.
   - No `AccessInfo`: `files: !!deps.browse && trusted(req, 'terminal')`.
4. Em `server/index.ts`, no `createApiHandler({...})`:

```ts
  // Arquivos das pastas de trabalho: as salas reais e ~/projetos. No Docker os caminhos das salas são do host e não
  // existem aqui; desligado.
  browse:
    config.terminal && !config.inDocker
      ? {
          roots: () => [
            ...hub.current().rooms.filter((r) => !isDemoId(r.id)).map((r) => ({ path: r.path, label: r.name, roomId: r.id })),
            { path: join(config.home, 'projetos'), label: 'projetos' },
          ],
        }
      : undefined,
```

   (`join` e `isDemoId` já são importados em `index.ts`; confira e importe se não forem.)

**Verify**: `npm run typecheck` → exit 0; `npx vitest run server/http/http.test.ts server/deliveries` → all pass
(as Entregas não mudaram).

### Step 6: Testes das rotas (`server/http/http.test.ts`)

Num `describe('arquivos (/api/files)')` novo, use `start({ browse: { roots: () => [{ path: <tmp>/proj, label: 'proj' }] } })`
com uma pasta real criada em `tempDir()` (`proj/a.txt`, `proj/b.bin`, `proj/pagina.html`, `proj/.env`,
`proj/sub/c.png`). Casos:

1. `GET /api/files` → 200 com uma raiz `{label: 'proj'}`.
2. `GET /api/files/list?path=<proj>` → 200; nomes `['sub', 'a.txt', 'b.bin', 'pagina.html']` (sem `.env`);
   `a.txt` com `kind: 'text'`, `b.bin` sem `kind`.
3. `GET /api/files/raw?path=<proj>/a.txt` → 200, `content-type` `text/plain; charset=utf-8`, corpo igual ao
   arquivo, `content-disposition` começando com `inline`.
4. `GET /api/files/raw?path=<proj>/pagina.html` → `content-security-policy` contém `sandbox`.
5. `GET /api/files/raw?path=<proj>/b.bin` → 415; com `&download=1` → 200, `application/octet-stream`,
   `content-disposition` começando com `attachment`, corpo igual.
6. `GET /api/files/raw?path=<proj>/.env&download=1` → 404. `.../list?path=<tmp>` (pai da raiz) → 404.
7. `POST /api/files/list` → 405.
8. Host não local: `raw(base, '/api/files', { headers: { Host: 'habblaud.lan' } })` → 403 (veja como os testes
   existentes usam `raw` com `habblaud.lan`).
9. Sem `browse`: `start()` → `GET /api/files` 403; `GET /api/access` com `files: false`.

**Verify**: `npx vitest run server/http/http.test.ts` → all pass, incluindo os 9 novos.

### Step 7: Ícones e modelo puro da página

1. Em `client/src/ui/icons.ts`, adicione a `ICONS` (perto de `delivery`):

```ts
  cabinet: pixelIcon(['.########.', '.#......#.', '.#..##..#.', '.#......#.', '.########.', '.#......#.', '.#..##..#.', '.#......#.', '.########.']),
  folder: pixelIcon(['####......', '#..#######', '#........#', '#........#', '#........#', '##########']),
  file: pixelIcon(['#####...', '#...##..', '#....##.', '#......#', '#......#', '#......#', '########']),
```

2. Crie `client/src/ui/files-model.ts` (puro, sem DOM):

```ts
export const FILES_URL = '/api/files';
/** URL do arquivo para ver (inline) ou baixar. */
export function rawUrl(path: string, download: boolean): string; // `${FILES_URL}/raw?path=${encodeURIComponent(path)}` + `&download=1`
export function listUrl(path: string): string;
/** Migalhas da raiz até a pasta: [{label: rótulo da raiz, path: raiz}, {label: 'sub', path: raiz/sub}, ...]. */
export function crumbs(root: FileRoot, dir: string): { label: string; path: string }[];
/** Raiz da sala (pelo roomId) ou, sem ela, a primeira; undefined sem raízes. */
export function rootFor(roots: readonly FileRoot[], roomId: string | undefined): FileRoot | undefined;
/** Validação leve das respostas (no estilo de parseDeliveries em deliveries.ts). */
export function parseRoots(v: unknown): FileRoot[];
export function parseListing(v: unknown): FileListing | null;
```

   `crumbs` precisa funcionar com `/` e com `\` (use o separador que aparecer na raiz) e devolver só a raiz quando
   `dir === root.path`.
3. Crie `client/src/ui/files-model.test.ts` (padrão de `aside-model.test.ts`): `rawUrl` codifica espaço, `#` e `?`
   e põe `download=1` só quando pedido; `crumbs` para raiz, 1 nível e 3 níveis; `rootFor` com sala existente, sala
   ausente e lista vazia; `parseListing` recusa entrada sem `name` ou com `type` estranho.

**Verify**: `npx vitest run client/src/ui/files-model.test.ts` → all pass.

### Step 8: A janela "Arquivos" (`client/src/ui/files.ts`)

Componente `FileBrowser implements UiComponent` (veja `UiComponent` em `client/src/ui/context.ts`), no estilo de
`Deliveries`:

- `el`: um `<dialog class="ui-files">` com:
  - barra do topo: ícone `ICONS.cabinet`, título `tr('Arquivos')` em `--font-px`, um `<select>` das raízes
    (rótulo = `label`; a de `~/projetos` mostra `tr('projetos')`), e `iconButton(ICONS.close, tr('Fechar'), ...)`.
  - migalhas (`crumbs`), cada uma um botão que lista aquela pasta.
  - lista `<ul class="ui-files__list">`: cada item com ícone `folder`/`file`, nome, tamanho (`formatBytes`, vazio
    em pasta) e data (`relativeTime(mtime, ctx.now())`). Pasta: clicar lista a pasta. Arquivo: botão
    `tr('Ver')` só quando `kind` existe, e link `<a class="ui-btn" download href={rawUrl(path, true)}>` com o texto
    `tr('Baixar')`. Com `truncated`, uma linha no fim: `tr('Mostrando os primeiros {0} itens desta pasta.', [n])`.
  - estado vazio `tr('Pasta vazia.')`, carregando `tr('Carregando…')`, erro `tr('Não deu para abrir: {0}', [msg])`.
- `open(roomId?: string)`: busca `GET /api/files` (`fetch(..., { credentials: 'same-origin', cache: 'no-store' })`,
  como `Deliveries.refresh`), escolhe `rootFor(roots, roomId)`, lista a raiz e chama `showModal()`. Sem raízes:
  abre com `tr('Nenhuma pasta de trabalho por aqui ainda.')`.
- Visualizador: um segundo `<dialog>` com as classes `ui-deliv-viewer*` (copie a escolha de elemento por `kind` de
  `deliveries.ts:415-432`; texto via `fetch` + `pre`), barra com o nome, link `tr('Baixar')` (download) e fechar.
  Não importe nada privado de `deliveries.ts`.
- `render()`: não faz nada além de manter o estado; quem decide mostrar o botão é a gaveta (passo 9).
- Guarde uma contagem de pedidos (`seq`) e ignore resposta de uma listagem velha (clique rápido em duas pastas).

CSS em `client/src/ui/styles.css`, num bloco novo `/* Arquivos (ui/files.ts) */` perto do bloco das Entregas:
`.ui-files` copia o visual de `.ui-deliv-modal` (`styles.css:9158-9165`: `--ui-bg-solid`, borda
`--ui-line-strong`, raio 16px, sombra) com `width: min(720px, calc(100vw - 32px))`, e o mesmo `::backdrop` e
animação de entrada (acrescente `.ui-files` aos seletores existentes `.ui-deliv-modal::backdrop` e
`.ui-deliv-modal[open]`, não duplique a regra). Itens com linha `--ui-line`, hover `--ui-bg-hover`, tamanho e data
em `--font-mono` e `--ui-muted`, ícones em `--ui-accent`. Lista rolável (`overflow: auto; max-height:
calc(100dvh - 200px)`). Em tela estreita (o mesmo breakpoint que as Entregas usam no CSS), a janela ocupa a largura
toda.

**Verify**: `npm run typecheck` → exit 0.

### Step 9: Botões na gaveta e montagem

1. `client/src/ui/context.ts`: adicione a `UiContext` um opcional `openFiles?(roomId: string): void` com
   comentário em PT.
2. `client/src/ui/index.ts`: crie `const files = new FileBrowser(ctx)` perto de `new Deliveries(...)`, ligue
   `ctx.openFiles = (roomId) => files.open(roomId)` do mesmo jeito que os outros opcionais do contexto são ligados
   (procure como `renameRoom` é atribuído e siga), ponha `files.el` no `root.append(...)` e `files` em
   `components`.
3. `client/src/ui/drawer.ts`:
   - `RoomView`: botão `filesBtn` = `h('button', { class: 'ui-btn', type: 'button', title: tr('Ver e baixar os arquivos da pasta desta sala'), on: { click: () => this.last && this.ctx.openFiles?.(this.last.id) } })`,
     com `innerHTML = ICONS.cabinet` e `append(h('span', { text: tr('Arquivos') }))` (mesmo padrão do
     `centerBtn`). Entra em `ui-status__actions` antes do `newBtn`. Em `render()`:
     `setHidden(this.filesBtn, !filesAvailable(this.ctx) || !live)`.
   - `AgentView`: o mesmo botão em `ui-status__actions`, depois do `centerBtn`, abrindo
     `this.ctx.openFiles?.(this.last.roomId)`; título `tr('Ver e baixar os arquivos da pasta da sala deste agente')`.
     Esconda com a mesma regra no `render()` do `AgentView`.
   - Função local `filesAvailable(ctx)`: `access().files === true && !ctx.store.mock && !ctx.store.replaying &&
     !!ctx.openFiles` (`access` de `../net/access`; `mock` e `replaying` existem no store, veja `deliveries.ts:206`).

**Verify**: `npm run typecheck` → exit 0; `npm test` → all pass.

### Step 10: Traduções, docs e changelog

1. Rode `npm run i18n:check -- --strict`; para cada texto novo que ele listar, adicione a tradução em
   `shared/locales/en.ts`, `es.ts` e `fr.ts` (a chave é o texto em PT exato). Repita até passar.
2. `CHANGELOG.md`, em "## [Não lançado]" › "### Adicionado", uma entrada no estilo das vizinhas:
   `**Arquivos da pasta de trabalho:** na gaveta da sala e na do agente, o botão Arquivos abre as pastas e os arquivos da sala (e de ~/projetos, quando existir). Dá para ver imagem, PDF, vídeo, texto e página e baixar qualquer arquivo; nada com "." no nome nem com cara de segredo aparece. Só leitura, com a mesma trava do terminal (pelo celular, com terminal em HABBLAUD_REMOTE_ALLOW). Desligado no Docker.`
3. `server/README.md`, na tabela de rotas, uma linha depois da de `/api/deliveries`:
   `` | `GET /api/files` / `GET /api/files/list?path=` / `GET /api/files/raw?path=[&download=1]` | raízes (pastas das salas e `~/projetos`), conteúdo de uma pasta e um arquivo, em stream (`files/browse.ts`, `http/files.ts`); só leitura, mesma trava do terminal; desligado no Docker | ``
4. Nos quatro READMEs, logo depois da linha `/api/deliveries…`, uma linha no mesmo formato e idioma do arquivo
   (ex. em inglês: `` | `/api/files…` | Working-folder files: browse the folders of each room and `~/projects`, view and download files (read-only). Local access only (from the phone, with `terminal` in `HABBLAUD_REMOTE_ALLOW`); off in Docker. | ``;
   o nome da pasta é `~/projetos` em todos, é um caminho).

**Verify**: `npm run i18n:check -- --strict` → exit 0; `npm run build` → exit 0.

### Step 11: Prova de fumaça

Com uma HOME descartável (não a sua):

```sh
T=$(mktemp -d) && mkdir -p "$T/projetos/demo/sub" && echo oi > "$T/projetos/demo/a.txt" && echo segredo > "$T/projetos/demo/.env" && printf '\x00\x01' > "$T/projetos/demo/b.bin"
HOME="$T" HABBLAUD_PORT=4799 HABBLAUD_WORLD=0 HABBLAUD_UPDATE_CHECK=0 npx tsx server/index.ts &
sleep 4
curl -s localhost:4799/api/access            # contém "files":true
curl -s localhost:4799/api/files             # raiz com label "projetos" e path "$T/projetos"
curl -s "localhost:4799/api/files/list?path=$T/projetos/demo"   # sub, a.txt, b.bin; sem .env
curl -s -D- -o /dev/null "localhost:4799/api/files/raw?path=$T/projetos/demo/b.bin&download=1" | grep -i content-disposition   # attachment
curl -s -o /dev/null -w '%{http_code}\n' "localhost:4799/api/files/raw?path=$T/projetos/demo/.env&download=1"   # 404
curl -s -o /dev/null -w '%{http_code}\n' "localhost:4799/api/files/list?path=$T"   # 404
kill %1; rm -rf "$T"
```

Se o servidor não subir com essas variáveis (porta, World), leia a saída e ajuste só as variáveis de ambiente do
comando; não mude código por causa da fumaça. Não ligue o modo demo: as salas do demo não viram raiz. A interface
com sala real precisa de um agente aberto e fica para a revisão manual do operador.

**Verify**: as seis saídas batem com os comentários.

## Test plan

- `server/files/browse.test.ts` (novo, 8 casos do passo 4): contenção por raiz, prefixo irmão, link para fora,
  ocultos/segredos, ordenação, corte.
- `server/http/http.test.ts` (9 casos do passo 6): rotas, cabeçalhos de ver e de baixar, 415, 404 de oculto e de
  fora, 405, 403 por Host, recurso desligado e `AccessInfo.files`.
- `client/src/ui/files-model.test.ts` (novo, passo 7): URLs, migalhas, escolha de raiz, validação.
- `npm test` inteiro verde, incluindo `server/deliveries/files.test.ts` sem mudança.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; `server/files/browse.test.ts`, `client/src/ui/files-model.test.ts` e os testes novos de
      `/api/files` em `server/http/http.test.ts` existem e passam
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `npm run build` exits 0
- [ ] `grep -n "'spawn'];" server/http/remote.ts` ainda mostra `const FEATURES: readonly RemoteFeature[] = ['permissions', 'messages', 'terminal', 'spawn'];` (nenhum recurso remoto novo)
- [ ] `grep -rn "writeFile\|unlink\|rename\|mkdir" server/files/ server/http/files.ts` → nada (só leitura)
- [ ] Fumaça do passo 11 com as seis saídas esperadas
- [ ] `git status --short` só mostra arquivos da lista "In scope"
- [ ] `git -C ~/apps/habblaud status --short` vazio (produção intocada)
- [ ] `plans/README.md` status row updated

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código (drift desde `799cefd`).
- Parecer necessário um `RemoteFeature` novo, mexer em `server/http/remote.ts` ou no `omp-tenant` para a porta
  remota liberar a rota.
- O caso 5 do passo 4 (link para fora) ou o 6 do passo 6 (`.env`) passar a responder 200 em qualquer momento e
  você não entender por quê.
- `hub.current().rooms` não existir ou não trouxer `path` com o caminho completo da pasta.
- A Content-Security-Policy da página (`PAGE_CSP` em `server/http/static.ts`) bloquear o `iframe` do PDF ou o
  download pelo link (as Entregas já usam o mesmo caminho; se elas funcionam e isto não, pare).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.

## Maintenance notes

- A segurança está em `locate` e `listDir`: qualquer rota nova que leia arquivo do usuário deve passar por
  `locate`. Na revisão, confira a comparação `real === root || real.startsWith(root + sep)` e que o teste de oculto
  vale nos segmentos do caminho pedido **e** do real.
- As raízes vêm das salas vivas: quando todos os agentes de uma sala saem, a sala some e a pasta deixa de abrir.
  É o esperado; `~/projetos` continua.
- O filtro de `.` esconde também `.gitignore`, `.github/` etc. Se alguém pedir esses arquivos, troque o filtro por
  uma lista explícita de pastas de configuração (`.omp`, `.claude`, `.codex`, `.habblaud`, `.config`, `.local`,
  `.git` e as de `SECRET_DIRS`) **com teste** para cada uma.
- Docker: desligado porque os caminhos das salas são do host. Ligar exigiria mapear caminhos como o
  `FileFetcher.mapHostPath` (`server/index.ts:237-243`).
- Publicação (do operador, fora do plano): levar o merge a `~/apps/habblaud`, rebuild do `habblaud.service`, e
  `omp-tenant release` + `sync` para os usuários. Os pods já têm `terminal` em `HABBLAUD_REMOTE_ALLOW`; nada muda
  no `omp-tenant`.
- Adiado de propósito: upload/apagar, zip de pasta, busca por nome, clique no móvel `filing_cabinet`.
