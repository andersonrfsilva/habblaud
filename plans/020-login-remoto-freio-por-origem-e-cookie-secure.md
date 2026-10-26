# Plan 020: O freio do login remoto passa a ser por origem (atrás do Traefik, o IP real vem do `X-Forwarded-For` de um proxy confiável) e o cookie ganha `Secure` atrás de HTTPS

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- server/http/remote.ts server/http/remote.test.ts server/index.ts server/config.ts README.md README.pt-BR.md README.es.md README.fr.md CHANGELOG.md shared/locales/en.ts shared/locales/es.ts shared/locales/fr.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition. (`CHANGELOG.md`, os READMEs e os arquivos de tradução mudam com frequência:
> basta os trechos citados abaixo existirem.)

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (mexe na porta de login; o erro típico é confiar num `X-Forwarded-For` forjável ou travar o dono)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `af73a23` (branch `producao`), 2026-10-25

## Why this matters

Os Habblauds dos usuários do omp-tenant estão na internet pública (`https://<usuário>.homex.xlinx.com.br`, via Traefik
no host, que repassa para a porta remota do pod em `100.83.131.11:<porta>`), e o nome de usuário é fácil de adivinhar.
O freio do login (`LoginThrottle`) é **global**: depois de 5 falhas, cada tentativa espera 2^(n-5) s, até 300 s, e a
resposta 429 traz `Retry-After`. Qualquer pessoa que mande **uma senha errada por janela** mantém o dono trancado para
fora (os cookies de 30 dias que ele já tem continuam valendo, mas um aparelho novo ou um cookie vencido não entra). O
mesmo vale para o dono, na porta remota do IP Tailscale, contra qualquer nó da tailnet.

Este plano troca o freio global por um freio **por origem** (o endereço do cliente), com um teto global generoso por
cima para chutes distribuídos, e com um mapa de origens de tamanho limitado. Atrás de proxy, o endereço real vem do
`X-Forwarded-For`, **mas só quando quem abriu a conexão TCP está numa lista configurada de proxies confiáveis**
(`HABBLAUD_REMOTE_TRUST_PROXY`); sem isso o cabeçalho é forjável e viraria um jeito de escapar do freio. No mesmo
caminho, o cookie de sessão passa a levar `Secure` quando a requisição chegou por um proxy confiável com
`X-Forwarded-Proto: https`: hoje os tenants rodam com `HABBLAUD_REMOTE_INSECURE_HTTP=1` atrás do Traefik com HTTPS, e o
cookie de 30 dias sai sem `Secure`.

## Current state

Arquivos:

- `server/http/remote.ts` — porta remota: config (`parseRemoteConfig`), sessões, `LoginThrottle`, filtro de login
  (`createRemoteGate`), `cookieHeader`, listeners.
- `server/http/remote.test.ts` — testes da porta remota (Vitest). Tem um servidor de teste (`start()`), `request()` cru e
  o relógio injetado do `LoginThrottle`.
- `server/index.ts` — liga a porta remota (`createRemoteGate`, `startRemoteListener`).
- `server/config.ts` — chama `parseRemoteConfig(env, inDocker)` (linha 239); não parseia a variável nova.
- `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md` — seção do celular com login e tabela de variáveis.
- `CHANGELOG.md`; `shared/locales/{en,es,fr}.ts` — traduções (a chave é o texto em português exato passado a `tr()`).

Trechos (lidos em `af73a23`):

`server/http/remote.ts:44-54` e `:93-95` — a config remota:

```ts
export interface RemoteConfig {
  port: number;
  host: string;
  user: string;
  passwordHash: PasswordHash;
  /** Texto do hash como veio do ambiente (entra na chave das sessões). */
  passwordHashText: string;
  allow: ReadonlySet<RemoteFeature>;
  /** Ausente = HTTP puro (só com HABBLAUD_REMOTE_INSECURE_HTTP=1). */
  tls?: RemoteTls;
}
...
  return {
    config: { port, host, user, passwordHash, passwordHashText: hashText, allow: parseAllow(env.HABBLAUD_REMOTE_ALLOW), tls: hasTls ? tls : undefined },
  };
```

`server/http/remote.ts:224-264` — o freio global:

```ts
/**
 * Freio contra tentativa e erro: depois de 5 falhas seguidas, cada nova tentativa espera 2^(n-5) s (até 5 min).
 * Global, não por IP: no Docker todo cliente da rede chega com o endereço do gateway. Uma conferência de senha por vez
 * (begin/end), contada como falha desde o começo: tentativas em paralelo não passam pelo freio enquanto o scrypt roda.
 */
export class LoginThrottle {
  private failures = 0;
  private blockedUntil = 0;
  private busy = false;

  constructor(private readonly now: () => number = Date.now) {}

  /** Segundos até poder tentar de novo (0 = pode; 1 enquanto outra tentativa está em andamento). */
  retryAfter(): number {
    return Math.max(this.busy ? 1 : 0, Math.ceil((this.blockedUntil - this.now()) / 1000));
  }

  /** Começa uma tentativa (já contada como falha); false se o freio ou outra tentativa não deixam. */
  begin(): boolean {
    if (this.retryAfter() > 0) return false;
    this.busy = true;
    this.fail();
    return true;
  }

  /** Fim da tentativa começada em begin(): acertou zera o freio; errou fica a falha já contada. */
  end(ok: boolean): void {
    this.busy = false;
    if (ok) this.succeed();
  }

  fail(): void {
    this.failures++;
    if (this.failures >= 5) this.blockedUntil = this.now() + Math.min(300, 2 ** (this.failures - 5)) * 1000;
  }

  succeed(): void {
    this.failures = 0;
    this.blockedUntil = 0;
  }
}
```

`server/http/remote.ts:391-405` — o cookie e as dependências do filtro:

```ts
function cookieHeader(value: string, maxAgeS: number, secure: boolean): string {
  return `${COOKIE}=${value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAgeS}${secure ? '; Secure' : ''}`;
}

export interface RemoteGateDeps {
  config: Pick<RemoteConfig, 'user' | 'passwordHash'>;
  sessions: RemoteSessions;
  /** Cookie com Secure (a porta fala HTTPS). */
  secure: boolean;
  /** Arquivo da CA pública servido em /remote/ca.crt (ausente = sem download). */
  caFile?: string;
  throttle?: LoginThrottle;
  /** Conferência da senha (injetável nos testes; padrão: scrypt). */
  verify?: (password: string, hash: PasswordHash) => Promise<boolean>;
}
```

`server/http/remote.ts:411-465` — o login (todas as chamadas ao freio) e `:467-475` o logout:

```ts
export function createRemoteGate(deps: RemoteGateDeps): (req: IncomingMessage, res: ServerResponse, url: URL) => boolean {
  const throttle = deps.throttle ?? new LoginThrottle();
  const verify = deps.verify ?? verifyPassword;
  ...
    if (path === '/api/remote/login') {
      ...
      const tooMany = () => {
        const wait = Math.max(1, throttle.retryAfter());
        sendJson(res, 429, { error: tr('Muitas tentativas. Tente de novo em {0} s.', [wait]) }, { 'Retry-After': String(wait) });
      };
      if (throttle.retryAfter() > 0) {
        req.resume();
        tooMany();
        return true;
      }
      // A tentativa só começa (e ocupa o freio) com o corpo inteiro na mão: um corpo que nunca termina não trava o
      // login de mais ninguém.
      let begun = false;
      let ok = false;
      readBody(req)
        .then(async (raw) => {
          if (!throttle.begin()) return tooMany();
          begun = true;
          ...
          ok = true;
          const { token, maxAgeS } = deps.sessions.issue();
          sendJson(res, 200, { ok: true }, { 'Set-Cookie': cookieHeader(token, maxAgeS, deps.secure) });
        })
        ...
        .finally(() => {
          if (begun) throttle.end(ok);
        });
      return true;
    }

    if (path === '/api/remote/logout') {
      req.resume();
      if (method !== 'POST') sendJson(res, 405, { error: tr('método não permitido') }, { Allow: 'POST' });
      else {
        deps.sessions.revoke(readCookie(req.headers.cookie, COOKIE));
        sendJson(res, 200, { ok: true }, { 'Set-Cookie': cookieHeader('', 0, deps.secure) });
      }
      return true;
    }
```

`server/index.ts:469-476` — a única chamada de produção ao filtro (`secure: !!remote.tls`):

```ts
if (config.remote) {
  const remote = config.remote;
  const gate = createRemoteGate({
    config: remote,
    sessions: new RemoteSessions(loadSecret(join(config.dataDir, 'remote-secret')), remote, Date.now, join(config.dataDir, 'remote-revoked.json')),
    secure: !!remote.tls,
    caFile: remote.tls?.caFile,
  });
```

`server/http/remote.test.ts:134-167` — testes atuais do freio (usam `t.fail()`, `t.succeed()`, `t.retryAfter()` e `begin()/end()` **sem origem**; precisam ser reescritos):

```ts
describe('LoginThrottle', () => {
  it('libera 4 falhas, depois espera 1 s, 2 s... e zera ao acertar', () => {
    let now = 0;
    const t = new LoginThrottle(() => now);
    for (let i = 0; i < 4; i++) t.fail();
    expect(t.retryAfter()).toBe(0);
    t.fail();
    expect(t.retryAfter()).toBe(1);
    ...
```

`server/http/remote.test.ts:193`, `:249`, `:615-619` — o servidor de teste e o teste do `Secure`:

```ts
async function start(allow: RemoteFeature[] = ['permissions']): Promise<Harness> {
...
  const gate = createRemoteGate({ config: CFG, sessions: new RemoteSessions(SECRET, CFG), secure: false });
...
describe('cookie Secure e download da CA', () => {
  it('com HTTPS o cookie leva Secure; /remote/ca.crt é público e só existe com a CA', async () => {
```

`request(base, path, { method, headers, body })` (linha 287) devolve `{ status, headers, body }`; `login(base)` (linha 312) exige 200. Tudo roda
em `127.0.0.1`, então o endereço do par TCP nos testes é `127.0.0.1`.

### Como o pod enxerga o proxy (omp-tenant)

Lido em `~/Desenvolvimento/omp-tenant/bin/omp-tenant`:

- Rotas (`write_routes`, ~267-288): o Traefik roda no host (rootful) e aponta cada tenant para
  `http://{TAILSCALE_IP}:{porta}` com `TAILSCALE_IP = "100.83.131.11"` (linha 52). A rota HTTPS usa `entryPoints:
  ["websecure"]`; o Traefik acrescenta `X-Forwarded-For` e `X-Forwarded-Proto` por padrão.
- Pod (~100-108): `Network=pasta:-T,4000` e `PublishPort={TAILSCALE_IP}:{port}:4748`. O Habblaud do tenant escuta
  `0.0.0.0:4748` (linha 166: `HABBLAUD_REMOTE_HOST=0.0.0.0 HABBLAUD_REMOTE_PORT=4748 HABBLAUD_REMOTE_INSECURE_HTTP=1`).
- **O que o pod vê como `req.socket.remoteAddress` não está confirmado.** O Traefik conecta do host ao IP Tailscale do
  próprio host; com podman rootless a conexão pode chegar com o endereço do host (`100.83.131.11`), com o do gateway da
  rede do pod ou com outro. A lista `HABBLAUD_REMOTE_TRUST_PROXY` do tenant (plano 034, no repositório omp-tenant) tem de
  ter **exatamente** esse endereço. O Passo 8 abaixo explica como medir, sem tocar na produção.
- Risco a vigiar: se o `pasta` mostrar o **mesmo** endereço para o Traefik e para um nó qualquer da tailnet que fale
  direto com `100.83.131.11:<porta>`, então confiar nele deixa esse nó forjar o `X-Forwarded-For`. Nesse caso o
  endereço **não pode** ser confiado (ver STOP conditions).

### Convenções

- Prosa e comentários do código em português, explicando o porquê; mensagens ao usuário passam por `tr('...')` e cada
  texto novo exige entradas em `shared/locales/en.ts`, `es.ts` e `fr.ts` (a chave é o texto em português exato).
- Variáveis `HABBLAUD_REMOTE_*` são lidas em `parseRemoteConfig` (`server/http/remote.ts`), não em `server/config.ts`
  (que só repassa o resultado). `truthy()` (linha 62) e `parseAllow()` (linha 98) são o padrão de parse. A variável nova
  segue o nome `HABBLAUD_REMOTE_TRUST_PROXY` (lista separada por vírgulas, como `HABBLAUD_REMOTE_ALLOW`).
- Commits em português, minúsculos, sem prefixo (ver `git log --oneline -15`, ex.: `lógica pura das camadas de móveis
  e props, com testes`).

### Decisões já tomadas (não reabrir)

1. **Chave da origem**: endereço IP normalizado (sem `::ffff:` e sem zona); IPv6 vira o prefixo `/64`, porque quem tem
   uma sub-rede `/64` tem 2^64 endereços e o freio por endereço completo não valeria nada.
2. **Quem é o cliente atrás de proxy**: só se o par TCP está na lista confiável, percorre-se o `X-Forwarded-For` **da
   direita para a esquerda**, pulando os endereços confiáveis; o primeiro não confiável é o cliente. Entrada que não é IP
   → volta para o par TCP (nunca adivinha). Cabeçalho ausente ou cadeia toda confiável → par TCP. (Um valor forjado pelo
   cliente fica à esquerda do que o Traefik acrescentou, então nunca é lido.)
3. **Sem CIDR** na lista (só IPs exatos): é o suficiente para um Traefik no host e evita erro de máscara.
   `shortcut: só IP exato, trocar por CIDR se houver mais de um proxy numa faixa`.
4. **Teto global**: 60 tentativas erradas somadas de todas as origens por janela fixa de 5 min; passou disso, 429 para
   todos até a janela virar. Acerto devolve a contagem. Não deixa nenhuma origem bloquear outra: uma origem sozinha
   chega a ~10 tentativas por minuto (pelo freio dela), então é preciso várias origens ao mesmo tempo para encostar no
   teto. Esse é o custo aceito de segurar chute distribuído.
5. **Concorrência**: no máximo 4 conferências de senha (scrypt) ao mesmo tempo no total, e 1 por origem. Antes era 1 no
   total (`busy`); manter um teto evita que uma enxurrada de origens estoure a memória do scrypt.
6. **Mapa limitado**: 1024 origens; origem sem falha nova há 30 min é esquecida; ao encher, sai primeiro a origem mais
   antiga **que não está bloqueada nem conferindo**; se todas estão bloqueadas, sai a mais antiga.
7. **Cookie `Secure`**: `deps.secure` (a porta fala HTTPS) **ou** par TCP confiável com o último valor de
   `X-Forwarded-Proto` igual a `https`. Escolhido no lugar de uma variável fixa (`..._COOKIE_SECURE=1`) porque o mesmo
   pod também recebe HTTP direto (tailnet, `http://100.83.131.11:<porta>`); um `Secure` fixo faria o navegador descartar
   o cookie nesse acesso e o login pareceria funcionar sem abrir a sessão. Por requisição, o cookie acompanha o
   esquema que o navegador realmente usou.

## Commands you will need

| Purpose   | Command                                   | Expected on success                           |
|-----------|-------------------------------------------|-----------------------------------------------|
| Typecheck | `npm run typecheck`                       | exit 0                                        |
| Tests     | `npx vitest run server/http/remote.test.ts` | todos passam                                |
| All tests | `npm test`                                | baseline `af73a23`: 181 arquivos / 2180 testes passam, 2 pulados; ao final, os novos também |
| i18n      | `npm run i18n:check -- --strict`          | exit 0                                        |

Worktree (recomendado):

```bash
git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-020 -b advisor/020-login-remoto-por-origem producao
ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-020/node_modules
```

Nunca faça commit do link `node_modules`. Nunca toque em `~/apps/habblaud` (produção).

## Scope

**In scope** (os únicos arquivos que você deve modificar):
- `server/http/remote.ts`
- `server/http/remote.test.ts`
- `server/index.ts`
- `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md`
- `CHANGELOG.md`
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`
- `plans/README.md` — só a linha de status deste plano (a menos que um revisor diga que mantém o índice)

**Out of scope** (NÃO toque, mesmo parecendo relacionado):
- `~/Desenvolvimento/omp-tenant/` — pôr `HABBLAUD_REMOTE_TRUST_PROXY` nos pods é o plano 034 (outro repositório).
- `docker-compose.yml` e `scripts/docker-up.ts` — no Docker todo cliente chega com o endereço do gateway; passar a
  variável nova ao contêiner fica para quem precisar (ver Maintenance notes).
- `server/http/guard.ts` (Host/Origin) e `server/remote/password.ts` — não mudam.
- A tela de login (HTML em `loginPage`) e as mensagens de 429/401 — o texto continua o mesmo.
- Qualquer CIDR, lista de bloqueio permanente, CAPTCHA ou notificação ao dono.

## Git workflow

- Branch: `advisor/020-login-remoto-por-origem` a partir de `producao`.
- Um commit por passo lógico (passos 1–2 juntos servem), mensagens em português, minúsculas, no estilo
  `freio do login remoto por origem, com teto global e mapa limitado`.
- Não faça push nem abra PR.

## Steps

### Step 1: Funções puras de origem e de proxy confiável em `server/http/remote.ts`

Logo **antes** do comentário de `LoginThrottle` (linha 224), adicione (use `net`, já importado na linha 25):

```ts
/** IP sem zona e sem o prefixo ::ffff: (soquete dual-stack); undefined se não for um IP. */
export function normalizeIp(raw: string | undefined): string | undefined {
  if (!raw) return undefined;
  let a = raw.trim().toLowerCase();
  const zone = a.indexOf('%');
  if (zone >= 0) a = a.slice(0, zone);
  if (a.startsWith('::ffff:') && net.isIPv4(a.slice(7))) a = a.slice(7);
  return net.isIP(a) ? a : undefined;
}

/** Chave do freio: o IPv4 inteiro; do IPv6, só o /64 (quem tem uma sub-rede tem 2^64 endereços). */
function sourceKey(ip: string): string {
  if (!net.isIPv6(ip)) return ip;
  // shortcut: IPv6 com IPv4 embutido depois do "::" (exótico) pode errar o /64, só pior para o atacante, não para o dono.
  const [head, tail = ''] = ip.split('::');
  const h = head ? head.split(':') : [];
  const t = tail ? tail.split(':') : [];
  const full = ip.includes('::') ? [...h, ...Array<string>(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t] : h;
  return `${full.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, '')).join(':')}::/64`;
}

/**
 * Lista de proxies confiáveis (HABBLAUD_REMOTE_TRUST_PROXY): IPs exatos separados por vírgula. `invalid` traz o que não
 * é IP (o servidor avisa no log e ignora).
 */
export function parseTrustProxy(raw: string | undefined): { ips: Set<string>; invalid: string[] } {
  const ips = new Set<string>();
  const invalid: string[] = [];
  for (const part of (raw ?? '').split(',')) {
    const p = part.trim();
    if (!p) continue;
    const ip = normalizeIp(p);
    if (ip) ips.add(ip);
    else invalid.push(p);
  }
  return { ips, invalid };
}

/**
 * Quem está do outro lado: o par TCP, ou, se ele é um proxy confiável, o primeiro endereço NÃO confiável do
 * X-Forwarded-For lendo da direita (o que o proxy acrescentou vale; o que o cliente forjou fica à esquerda). Sem proxy
 * confiável o cabeçalho é ignorado: qualquer cliente o escreve como quiser.
 */
export function clientSource(peer: string | undefined, xff: string | string[] | undefined, trusted: ReadonlySet<string>): string {
  const p = normalizeIp(peer);
  if (!p) return 'desconhecida';
  if (!trusted.has(p)) return sourceKey(p);
  const header = Array.isArray(xff) ? xff.join(',') : xff;
  if (!header) return sourceKey(p);
  const hops = header.split(',');
  for (let i = hops.length - 1; i >= 0; i--) {
    const ip = normalizeIp(hops[i]);
    if (!ip) return sourceKey(p); // lixo no cabeçalho: não adivinha, cai no endereço do proxy
    if (!trusted.has(ip)) return sourceKey(ip);
  }
  return sourceKey(p);
}

/** A requisição veio por um proxy confiável que falou HTTPS com o navegador (o último X-Forwarded-Proto é o do proxy mais próximo). */
function viaHttps(req: IncomingMessage, trusted: ReadonlySet<string>): boolean {
  const p = normalizeIp(req.socket.remoteAddress);
  if (!p || !trusted.has(p)) return false;
  const raw = req.headers['x-forwarded-proto'];
  const last = (Array.isArray(raw) ? raw.join(',') : raw ?? '').split(',').pop()?.trim().toLowerCase();
  return last === 'https';
}
```

Em `RemoteConfig` (linhas 44-54) acrescente, depois de `allow`:

```ts
  /** Proxies (IPs exatos) em que o X-Forwarded-For e o X-Forwarded-Proto valem; vazio = nenhum (HABBLAUD_REMOTE_TRUST_PROXY). */
  trustProxy: ReadonlySet<string>;
  /** Entradas de HABBLAUD_REMOTE_TRUST_PROXY que não são IP (o servidor avisa no log). */
  trustProxyInvalid: readonly string[];
```

Em `parseRemoteConfig`, antes do `return` final (linha 93), faça `const trust = parseTrustProxy(env.HABBLAUD_REMOTE_TRUST_PROXY);` e acrescente
`trustProxy: trust.ips, trustProxyInvalid: trust.invalid` ao objeto `config`.

Atualize o cabeçalho de comentário do arquivo (linhas 8-17): acrescente a linha
`//   HABBLAUD_REMOTE_TRUST_PROXY  IPs de proxies (ex.: o Traefik) em que o X-Forwarded-For/-Proto valem; sem ela ignora os dois`
e troque, na linha 19, "(+ Secure com HTTPS)" por "(+ Secure com HTTPS, direto ou por proxy confiável)".

**Verify**: `npm run typecheck` → exit 0 (neste passo `LoginThrottle` e os testes ainda não mudaram; o campo novo de `RemoteConfig` só é lido por `config.ts`/`index.ts`, que repassam o objeto inteiro).

### Step 2: Reescrever `LoginThrottle` por origem, com teto global e mapa limitado

Substitua a classe `LoginThrottle` e seu comentário (linhas 224-264) por:

```ts
const FREE_FAILURES = 5;
const MAX_WAIT_S = 300;
/** Origem sem falha nova há tanto tempo é esquecida. */
const SOURCE_STALE_MS = 30 * 60_000;
const MAX_SOURCES = 1024;
/** Teto global: tentativas erradas de todas as origens somadas, por janela fixa. */
const GLOBAL_WINDOW_MS = 5 * 60_000;
const GLOBAL_MAX_FAILURES = 60;
/** Conferências de senha (scrypt) ao mesmo tempo, no total. */
const MAX_PARALLEL = 4;

interface SourceState {
  failures: number;
  blockedUntil: number;
  lastAt: number;
  busy: boolean;
}

/**
 * Freio contra tentativa e erro, por origem (ver clientSource): depois de 5 falhas seguidas daquela origem, cada nova
 * tentativa dela espera 2^(n-5) s (até 5 min). Falha de uma origem nunca atrasa a senha certa de outra. Por cima, um
 * teto global (60 erros por 5 min, somados) segura chute distribuído, e no máximo 4 conferências rodam juntas (o scrypt
 * é caro em memória). Uma conferência por origem (begin/end), contada como falha desde o começo: tentativas em paralelo
 * não passam pelo freio enquanto o scrypt roda. O mapa de origens é limitado (MAX_SOURCES).
 */
export class LoginThrottle {
  private readonly sources = new Map<string, SourceState>(); // ordem de inserção = da menos para a mais recente
  private active = 0;
  private windowEnd = 0;
  private windowCount = 0;

  constructor(
    private readonly now: () => number = Date.now,
    private readonly maxSources: number = MAX_SOURCES,
  ) {}

  /** Quantas origens estão guardadas (limitado por maxSources). */
  get size(): number {
    return this.sources.size;
  }

  /** Segundos até `source` poder tentar de novo (0 = pode; 1 se ela já está conferindo ou as 4 vagas estão ocupadas). */
  retryAfter(source: string): number {
    const t = this.now();
    const s = this.live(source, t);
    const globalWait = this.windowCount >= GLOBAL_MAX_FAILURES && t < this.windowEnd ? this.windowEnd - t : 0;
    const wait = Math.max(s?.blockedUntil ? s.blockedUntil - t : 0, globalWait);
    return Math.max(s?.busy || this.active >= MAX_PARALLEL ? 1 : 0, Math.ceil(wait / 1000));
  }

  /** Começa uma tentativa de `source` (já contada como falha); false se o freio dela, o teto global ou as vagas não deixam. */
  begin(source: string): boolean {
    if (this.retryAfter(source) > 0) return false;
    const t = this.now();
    const s = this.live(source, t) ?? this.add(source, t);
    this.sources.delete(source); // reinsere: passa a ser a mais recente
    this.sources.set(source, s);
    s.busy = true;
    s.lastAt = t;
    s.failures++;
    if (s.failures >= FREE_FAILURES) s.blockedUntil = t + Math.min(MAX_WAIT_S, 2 ** (s.failures - FREE_FAILURES)) * 1000;
    this.active++;
    if (t >= this.windowEnd) {
      this.windowEnd = t + GLOBAL_WINDOW_MS;
      this.windowCount = 0;
    }
    this.windowCount++;
    return true;
  }

  /** Fim da tentativa começada em begin(): acertou, zera a origem e devolve a falha ao teto global; errou, a falha fica. */
  end(source: string, ok: boolean): void {
    this.active = Math.max(0, this.active - 1);
    const s = this.sources.get(source);
    if (s) s.busy = false;
    if (!ok) return;
    this.sources.delete(source);
    this.windowCount = Math.max(0, this.windowCount - 1);
  }

  /** O estado da origem, ou undefined (e esquecida) se está parada há mais de SOURCE_STALE_MS. */
  private live(source: string, t: number): SourceState | undefined {
    const s = this.sources.get(source);
    if (s && !s.busy && t - s.lastAt > SOURCE_STALE_MS) {
      this.sources.delete(source);
      return undefined;
    }
    return s;
  }

  private add(source: string, t: number): SourceState {
    if (this.sources.size >= this.maxSources) {
      // Sai a mais antiga que não está conferindo nem bloqueada (o bloqueio é o que vale guardar); senão, a mais antiga.
      let victim: string | undefined;
      for (const [k, v] of this.sources) {
        if (v.busy) continue;
        victim ??= k;
        if (v.blockedUntil <= t) {
          victim = k;
          break;
        }
      }
      this.sources.delete(victim ?? this.sources.keys().next().value!);
    }
    return { failures: 0, blockedUntil: 0, lastAt: t, busy: false };
  }
}
```

Em `RemoteGateDeps` (linha 395), acrescente depois de `throttle?`:

```ts
  /** Proxies confiáveis (IPs exatos): só deles se lê X-Forwarded-For (origem do freio) e X-Forwarded-Proto (cookie Secure). */
  trustProxy?: ReadonlySet<string>;
```

Troque a documentação de `secure` por `/** Cookie sempre com Secure (a porta fala HTTPS). Atrás de proxy confiável com X-Forwarded-Proto: https também vale. */`.

Em `createRemoteGate` (linha 411), logo depois de `const verify = ...`, acrescente `const trusted = deps.trustProxy ?? new Set<string>();`.

Dentro do bloco `/api/remote/login` (linha 420) faça estas trocas — **o resto do bloco não muda**:

1. Antes de `const tooMany`, acrescente `const source = clientSource(req.socket.remoteAddress, req.headers['x-forwarded-for'], trusted);` e `const secure = deps.secure || viaHttps(req, trusted);`.
2. `throttle.retryAfter()` → `throttle.retryAfter(source)` (nas 2 ocorrências: dentro de `tooMany` e no `if`).
3. `throttle.begin()` → `throttle.begin(source)`.
4. `throttle.end(ok)` → `throttle.end(source, ok)`.
5. `cookieHeader(token, maxAgeS, deps.secure)` → `cookieHeader(token, maxAgeS, secure)`.

No bloco `/api/remote/logout` (linha 467): `cookieHeader('', 0, deps.secure)` → `cookieHeader('', 0, deps.secure || viaHttps(req, trusted))`.

**Verify**: `npm run typecheck` → o único erro esperado vem de `server/http/remote.test.ts` (API antiga de `LoginThrottle`: `fail`, `succeed`, `retryAfter()` sem argumento); `server/http/remote.ts` sem erros. (Isso é corrigido no Passo 4.) Confira com `npm run typecheck 2>&1 | grep -c "http/remote\.ts"` → `0` (o `grep -c` sai com código 1 quando a contagem é 0; é esperado).

### Step 3: Ligar no `server/index.ts` e avisar no log

Em `server/index.ts:471-476`, acrescente `trustProxy: remote.trustProxy,` ao objeto de `createRemoteGate`.

Dentro do `remoteListener.server.once('listening', ...)` (linha 494), depois do `log.info(... 'Acesso remoto: ligado ...')`, acrescente:

```ts
    if (remote.trustProxy.size) log.info(tr('   Acesso remoto: confia em {0} como proxy (X-Forwarded-For e X-Forwarded-Proto).', [[...remote.trustProxy].join(', ')]));
    for (const bad of remote.trustProxyInvalid) log.warn(tr('   Acesso remoto: HABBLAUD_REMOTE_TRUST_PROXY ignorou "{0}" (não é um endereço IP).', [bad]));
```

Em `shared/locales/en.ts`, `es.ts` e `fr.ts`, perto da entrada `"   Acesso remoto: certificado recarregado."` (en/es linha 2537, fr linha 2553), acrescente as duas chaves exatas acima com tradução, por exemplo (en):

```ts
  "   Acesso remoto: confia em {0} como proxy (X-Forwarded-For e X-Forwarded-Proto).": "   Remote access: trusting {0} as proxy (X-Forwarded-For and X-Forwarded-Proto).",
  "   Acesso remoto: HABBLAUD_REMOTE_TRUST_PROXY ignorou \"{0}\" (não é um endereço IP).": "   Remote access: HABBLAUD_REMOTE_TRUST_PROXY ignored \"{0}\" (not an IP address).",
```

Faça es e fr no mesmo estilo das linhas vizinhas de cada arquivo.

**Verify**: `npm run i18n:check -- --strict` → exit 0; `npm run typecheck 2>&1 | grep -c "server/index\.ts\|server/config\.ts"` → `0` (idem: código de saída 1 com contagem 0 é esperado).

### Step 4: Testes (reescrever os do freio e adicionar os novos)

Em `server/http/remote.test.ts`:

1. Importe `clientSource`, `normalizeIp` (se usar), `parseTrustProxy` de `./remote`.
2. Reescreva o `describe('LoginThrottle')` (linhas 134-167) com os casos da seção "Test plan". Use sempre `begin(src)`/`end(src, ok)`; para "falhar" uma origem, chame `begin` e `end(src, false)` (`begin` já conta a falha).
3. Acrescente um `describe('clientSource e parseTrustProxy')` e os testes de integração.
4. Em `start()` (linha 193), acrescente o segundo parâmetro `opts: { trustProxy?: string[]; throttle?: LoginThrottle } = {}` e passe `trustProxy: new Set(opts.trustProxy ?? [])` e `throttle: opts.throttle` ao `createRemoteGate` da linha 249 (o resto do `start()` não muda; as chamadas existentes `start()` / `start([...])` continuam valendo).

**Verify**: `npx vitest run server/http/remote.test.ts` → todos passam, incluindo os novos.

### Step 5: README (4 idiomas)

Em cada README, na seção do celular com login:

- Troque a frase do freio, uma por idioma, mantendo o resto da linha:
  - `README.md:310`: "After 5 wrong passwords in a row, each new attempt waits from 1 s up to 5 min." → "After 5 wrong passwords in a row from the same address, each new attempt from that address waits from 1 s up to 5 min; an address's wrong passwords never delay another address, and about 60 wrong passwords in 5 min from all addresses together pause every login until the window ends."
  - `README.pt-BR.md:308`, `README.es.md:317`, `README.fr.md:322-323` (a frase termina na linha 323 "de 1 s à 5 min."): mesma mudança no idioma de cada um.
- Logo depois do parágrafo "Without HTTPS the remote port doesn't start; ..." (`README.md:315-317`; pt-BR:313; es:324-326; fr:329-331), acrescente um parágrafo curto: atrás de um proxy reverso (ex.: Traefik terminando o HTTPS), `HABBLAUD_REMOTE_TRUST_PROXY=<ip>[,<ip>...]` (IPs exatos do proxy **como o Habblaud os vê**; sem máscara) faz o freio do login usar o endereço real do cliente (`X-Forwarded-For`) e o cookie ganhar `Secure` quando o proxy fala HTTPS (`X-Forwarded-Proto: https`). Sem a variável, os dois cabeçalhos são ignorados, porque qualquer cliente os escreve; e todos os clientes atrás do proxy dividem o mesmo freio. Só vale no modo Node (no Docker, o `docker-compose.yml` não repassa a variável).
- Na tabela de variáveis, logo depois da linha `HABBLAUD_REMOTE_ALLOW` (`README.md:802`, pt-BR:800, es:836, fr:841), nova linha: `| `HABBLAUD_REMOTE_TRUST_PROXY` | — | IPs (separados por vírgula) do proxy reverso na frente da porta remota, para o freio do login usar o `X-Forwarded-For` e o cookie levar `Secure` com `X-Forwarded-Proto: https`. Sem ela os dois cabeçalhos são ignorados. Só no modo Node. |` (traduzida em cada idioma; mantenha o formato das linhas vizinhas).

**Verify**: `grep -c "HABBLAUD_REMOTE_TRUST_PROXY" README.md README.pt-BR.md README.es.md README.fr.md` → pelo menos `2` em cada.

### Step 6: CHANGELOG

Em `CHANGELOG.md`, sob `## [Não lançado]`, na seção `### Corrigido` (já existe: linha 84; se a primeira `### Corrigido` de `[Não lançado]` for outra, use a primeira dentro de `[Não lançado]`), acrescente:

```md
- **Login remoto: uma tentativa errada de qualquer pessoa não tranca mais o dono:** o freio de senha era um só para
  todos, então quem mandasse uma senha errada por janela mantinha o dono sem poder entrar (e a porta do celular ou de
  um tenant fica exposta). Agora o freio é por origem (o endereço do cliente; IPv6 por /64), com um teto global de
  60 erros por 5 min para chute distribuído e no máximo 4 conferências de senha ao mesmo tempo. Atrás de um proxy
  reverso, `HABBLAUD_REMOTE_TRUST_PROXY=<ip do proxy>` faz o Habblaud ler o endereço real no `X-Forwarded-For` (só vindo
  desse proxy) e marca o cookie de sessão como `Secure` quando o proxy fala HTTPS (`X-Forwarded-Proto: https`). Sem a
  variável, nada muda além do freio por origem.
```

**Verify**: `grep -n "HABBLAUD_REMOTE_TRUST_PROXY" CHANGELOG.md` → 1 linha dentro de `[Não lançado]`.

### Step 7: Verificação completa

**Verify**: `npm run typecheck` → exit 0; `npm test` → todos passam (181 arquivos, 2180 testes no baseline + os novos, 2 pulados); `npm run i18n:check -- --strict` → exit 0; `git status --short` lista só arquivos do escopo.

### Step 8 (informativo, não bloqueia o código): medir o endereço do proxy visto no pod

Objetivo: dizer ao plano 034 qual valor pôr em `HABBLAUD_REMOTE_TRUST_PROXY` nos pods. **Não** acrescente log ao código e **não** use a produção. Em uma máquina com o podman rootless do usuário dos pods, rode um contêiner descartável (porta livre; troque `<sha>` por uma tag existente em `podman images | grep habblaud`):

```bash
podman run --rm -p 100.83.131.11:49999:4748 --network pasta --entrypoint node localhost/habblaud:tenant-<sha> \
  -e "require('http').createServer((q,s)=>{console.log('peer=',q.socket.remoteAddress,'xff=',q.headers['x-forwarded-for']);s.end('ok')}).listen(4748,'0.0.0.0')"
```

Em outro terminal, **no host** (é o que o Traefik faz): `curl -s http://100.83.131.11:49999/` e, **de outro nó da tailnet**, `curl -s http://100.83.131.11:49999/`. Registre os dois `peer=`. Se faltar sudo/acesso ao usuário dos pods, não improvise: relate no resultado final que a medição ficou pendente (o código deste plano não depende dela).

- Os dois diferentes (host vê o próprio endereço, nó vê o IP dele): o `peer` do host é o valor para o plano 034.
- Os dois iguais (o `pasta` mascara a origem): relate; o plano 034 **não deve** ligar a variável nesse caso (um nó da tailnet forjaria o `X-Forwarded-For`) e é preciso outra solução (ex.: o Traefik num endereço próprio).

**Verify**: o resultado dos dois `peer=` está no seu relatório final (ou "pendente: sem acesso", dito explicitamente).

## Test plan

Arquivo: `server/http/remote.test.ts`. Modele os testes unitários em `describe('LoginThrottle')` atual (relógio `let now = 0; new LoginThrottle(() => now)`) e os de integração em `'senha errada: 401 sem cookie; certa: cookie de sessão e acesso à API'` (linha 479) e `'cookie Secure e download da CA'` (linha 615), usando `start()`, `request()` e `login()`.

**`describe('LoginThrottle')`** (reescrito; helper local `fail(t, src)` = `expect(t.begin(src)).toBe(true); t.end(src, false);`):

1. Por origem, a escada de antes: 4 falhas de `'a'` livres, a 5ª `begin` passa e `retryAfter('a')` vira 1; depois de 1 s a seguinte passa e `retryAfter('a')` vira 2; acertar (`end('a', true)`) zera.
2. **Isolamento**: cinco falhas de `'a'` deixam `retryAfter('a') > 0`, mas `retryAfter('b')` é 0 e `begin('b')` é `true`; um acerto de `'b'` não limpa o bloqueio de `'a'`.
3. Uma tentativa por vez por origem: `begin('a')` duas vezes seguidas → a 2ª `false` (até `end`), e `begin('b')` no meio passa.
4. Origem esquecida: depois de 31 min sem falha nova, `'a'` bloqueada volta a `retryAfter('a') === 0` (e `size` diminui).
5. **Mapa limitado**: `new LoginThrottle(() => now, 3)`; origens `'x1'..'x10'` com uma falha cada → `size` nunca passa de 3. Origem `'a'` bloqueada (5 falhas) **sobrevive** a um fluxo de origens novas com 1 falha (`retryAfter('a') > 0` ao final).
6. **Teto global**: 60 origens diferentes, cada uma com 1 falha, dentro de 5 min → uma origem 61 nova e limpa recebe `begin` `false` e `retryAfter > 0` (≤ 300); depois de 300 s (avançar o relógio) ela passa. Variante: a 60ª tentativa que **acerta** (`end(src, true)`) devolve a contagem e a origem seguinte passa.
7. Vagas: 4 `begin` de origens diferentes sem `end` → o 5º `begin` de outra origem é `false`, `retryAfter` = 1; depois de um `end`, passa.

**`describe('clientSource e parseTrustProxy')`**:

8. Par não confiável: o `X-Forwarded-For` é ignorado (`clientSource('203.0.113.5', '1.1.1.1', new Set())` → `'203.0.113.5'`).
9. Par confiável: usa o primeiro não confiável da direita; valor forjado à esquerda é ignorado (`'6.6.6.6, 198.51.100.7'` com `10.0.0.1` confiável e par `10.0.0.1` → `'198.51.100.7'`); proxy intermediário confiável é pulado (`'198.51.100.7, 10.0.0.2'` com `10.0.0.1,10.0.0.2` confiáveis → `'198.51.100.7'`).
10. Cabeçalho ausente, entrada que não é IP (`'unknown'`, `'1.2.3.4, lixo'`) ou cadeia toda confiável → cai no par TCP.
11. Normalização: `'::ffff:203.0.113.5'` vira `'203.0.113.5'`; dois endereços IPv6 do mesmo /64 dão a mesma chave e de /64 diferentes, chaves diferentes; `'2001:db8::1'` e `'2001:db8:0:0:ffff::9'` mesma chave.
12. `parseTrustProxy('10.0.0.1, ::ffff:10.0.0.2, lixo, ')` → `ips` com `10.0.0.1` e `10.0.0.2`, `invalid` = `['lixo']`; `undefined` → vazio. E `parseRemoteConfig` com `HABBLAUD_REMOTE_TRUST_PROXY` preenche `config.trustProxy` (e fica vazio sem a variável).

**Integração (HTTP, `start([], { trustProxy, throttle })` com `LoginThrottle` de relógio injetado)**:

13. **O bug do achado**: com `trustProxy: ['127.0.0.1']`, 5 logins errados com `X-Forwarded-For: 203.0.113.9` (todos 401), o 6º dela → 429 com `Retry-After`; **o login correto** com `X-Forwarded-For: 198.51.100.7` → 200 e `Set-Cookie`.
14. **Sem confiança, cabeçalho forjável não escapa do freio**: `start()` sem `trustProxy`; 5 erros com `X-Forwarded-For` diferentes a cada vez → o 6º, com outro `X-Forwarded-For`, ainda é 429 (todos dividem o par TCP).
15. **Secure**: com `trustProxy: ['127.0.0.1']` e `X-Forwarded-Proto: https`, o `Set-Cookie` do login termina em `; Secure`, e o do logout (`Max-Age=0`) também; sem o cabeçalho, ou com `X-Forwarded-Proto: http`, não tem `Secure`; **sem** `trustProxy` e com `X-Forwarded-Proto: https`, não tem `Secure` (cabeçalho de cliente qualquer não vale). (O teste atual de `secure: true` na linha 615 segue passando sem mudança.)

Verificação: `npx vitest run server/http/remote.test.ts` → todos passam, incluindo os ~15 novos.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0; os testes novos de `remote.test.ts` existem e passam
- [ ] `npm run i18n:check -- --strict` exits 0
- [ ] `grep -n "throttle.retryAfter()\|throttle.begin()\|throttle.end(ok)\|succeed()" server/http/remote.ts` → nenhuma ocorrência
- [ ] `grep -n "deps.secure" server/http/remote.ts` → exatamente 2 linhas: `const secure = deps.secure || viaHttps(req, trusted);` (login) e a do logout (`cookieHeader('', 0, deps.secure || viaHttps(req, trusted))`); nenhum `cookieHeader(..., deps.secure)` solto
- [ ] `grep -c "HABBLAUD_REMOTE_TRUST_PROXY" README.md README.pt-BR.md README.es.md README.fr.md CHANGELOG.md` → ≥ 1 em todos
- [ ] Nenhum arquivo fora do escopo foi modificado (`git status --short`); o link `node_modules` não está commitado
- [ ] `plans/README.md` com a linha do plano 020 atualizada (se o revisor não a mantém)
- [ ] O relatório final traz o resultado do Passo 8 (os dois `peer=`) ou "pendente: sem acesso"

## STOP conditions

Stop and report back (do not improvise) if:

- Os trechos de "Current state" não batem com o código (o arquivo mudou desde `af73a23`).
- Alguma verificação falha duas vezes depois de uma correção razoável.
- Algum teste fora de `remote.test.ts` quebra (algum outro código chama `LoginThrottle`/`createRemoteGate`: hoje só `server/index.ts` e `remote.test.ts`; se achar outro, pare).
- A correção parece exigir mexer em `omp-tenant`, `docker-compose.yml` ou `guard.ts`.
- No Passo 8, o `pasta` mostra o **mesmo** `peer` para o host e para um nó da tailnet: relate e diga que o plano 034 não pode ligar `HABBLAUD_REMOTE_TRUST_PROXY` com esse endereço. (Isso não impede entregar este plano: sem a variável o freio por origem funciona, só que todos atrás do proxy dividem um balde.)
- Surgir a necessidade de aceitar `X-Forwarded-For` de um par que não está na lista configurada.

## Maintenance notes

- **Plano 034 (repositório omp-tenant)**: no `bin/omp-tenant`, na linha de `Environment=` do pod (~linha 166), acrescentar `HABBLAUD_REMOTE_TRUST_PROXY=<endereço medido no Passo 8>`; o `release` do Habblaud precisa conter este plano. Antes disso o freio é por origem, mas todos atrás do Traefik dividem um balde (o endereço do Traefik): ainda não resolve o trancamento por um atacante na internet. Também no 034: a nota do `write_routes` (`omp-tenants-hsts`: "O cookie do Habblaud não leva Secure") passa a ser falsa e deve ser atualizada.
- O dono na porta Tailscale (`tailscale serve`) chega com o par TCP do próprio `tailscaled` (loopback): lá todos os nós dividem um balde. Se isso incomodar, é o mesmo mecanismo: pôr `127.0.0.1` em `HABBLAUD_REMOTE_TRUST_PROXY` e confiar no `X-Forwarded-For` que o `tailscale serve` envia (só depois de confirmar que ele envia).
- Docker: o `docker-compose.yml` não repassa `HABBLAUD_REMOTE_TRUST_PROXY` (e o gateway da rede do Docker mascara os clientes). Quem precisar, acrescenta ao compose junto com a lista em `scripts/docker-up.ts`.
- `MAX_SOURCES` evictando origem **não bloqueada** primeiro significa que um atacante com milhares de origens pode apagar o contador (4 falhas) de uma origem: custo alto e limitado pelo teto global; um bloqueio ativo só sai se todas as 1024 estiverem bloqueadas.
- O teto global (60 erros / 5 min) é o ponto de troca: um atacante com várias origens ao mesmo tempo ainda consegue atrasar o dono por até 5 min. É o preço de segurar chute distribuído; para apertar ou afrouxar, mude `GLOBAL_MAX_FAILURES`/`GLOBAL_WINDOW_MS`.
- Revisor: confira que (1) `clientSource` lê o `X-Forwarded-For` **só** de par confiável e **da direita**; (2) o `Secure` exige par confiável; (3) nenhuma outra rota usa o endereço do `X-Forwarded-For` (a trava local do terminal em `server/http/app.ts`/`pty.ts` usa `req.socket.remoteAddress` e deve continuar assim).
- Diferido: CIDR na lista de proxies, alerta ao dono de tentativas bloqueadas, e `Retry-After` por origem na tela de login.
