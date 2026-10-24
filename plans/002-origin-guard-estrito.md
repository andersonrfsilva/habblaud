# Plan 002: O guard de borda só aceita Origin da mesma origem (ou de HABBLAUD_ALLOWED_HOSTS)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4931abc..HEAD -- server/http/guard.ts server/http/http.test.ts server/index.ts CHANGELOG.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED (pode recusar um cliente legítimo que mande Origin de outra porta local)
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `4931abc`, 2026-10-24

## Why this matters

O Habblaud deixa o navegador mandar prompts aos agentes (`/api/messages`), trocar modelo do omp, criar agentes
(`/api/spawn`) e aprovar ferramentas (`/api/permissions/:id/decision`). A barreira contra CSRF é o guard de borda
(`server/http/guard.ts`), mas ele aceita **qualquer Origin de loopback, em qualquer porta**. Então uma página servida
por outro serviço em `http://localhost:<outra porta>` (no homex a produção usa `Network=host`, e outros apps rodam no
mesmo host) pode mandar POST JSON a `127.0.0.1:4747` e o guard deixa passar. O próprio repositório já reconhece esse
risco no terminal: `ptyRequestProblem` (`server/http/pty.ts`) exige Origin da mesma origem e recusa
`Sec-Fetch-Site: same-site` ("outra porta do localhost"). As outras rotas que mudam estado não têm essa proteção.
Depois deste plano, em produção, só passa Origin igual ao Host da requisição ou com nome em `HABBLAUD_ALLOWED_HOSTS`.
O modo dev (`--dev`) mantém a folga de loopback para o Vite.

## Current state

- `server/http/guard.ts` — guard de Host/Origin/Content-Type aplicado a toda requisição (porta local e porta remota).
- `server/index.ts:433` — cria o guard: `const guard = createRequestGuard({ allowedHosts: config.allowedHosts });`
  (`config.dev` existe e vale `true` só com `--dev`; ver `server/index.ts:417`, `if (config.dev) {`).
- `server/http/http.test.ts` — testes do guard (unidade e integração).

`server/http/guard.ts:71-88` hoje:

```ts
/**
 * Origin aceito para requisições que mudam estado: ausente (curl, scripts), da mesma origem que o
 * Host da requisição, local (localhost/loopback) ou um nome de HABBLAUD_ALLOWED_HOSTS.
 */
export function originAllowed(origin: string | undefined, host: string | undefined, allowed: ReadonlySet<string>): boolean {
  if (origin === undefined) return true;
  if (origin === 'null') return false;
  let url: URL;
  try {
    url = new URL(origin);
  } catch {
    return false;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
  if (host !== undefined && url.host.toLowerCase() === host.trim().toLowerCase()) return true;
  const name = hostnameOf(url.host);
  return !!name && (isLoopbackName(name) || allowed.has(name));
}
```

`server/http/guard.ts:105-133` (opções e uso):

```ts
export interface GuardOptions {
  /** Nomes extras aceitos no Host/Origin (HABBLAUD_ALLOWED_HOSTS). */
  allowedHosts?: ReadonlySet<string>;
}
...
export function createRequestGuard(opts: GuardOptions = {}): (req: IncomingMessage, res: ServerResponse) => boolean {
  const allowed = opts.allowedHosts ?? new Set<string>();
  return (req, res) => {
    ...
    const origin = typeof req.headers.origin === 'string' ? req.headers.origin : undefined;
    if (!originAllowed(origin, host, allowed)) {
      reject(res, 403, tr('Origem não permitida.'));
      return true;
    }
```

O comentário do topo do arquivo (`guard.ts:7-9`) diz: "quando há `Origin`, ele precisa ser da mesma origem ou local".

Exemplo de checagem estrita já existente, para referência (`server/http/pty.ts`, função `ptyRequestProblem`):

```ts
  const origin = req.headers.origin;
  if (typeof origin === 'string') {
    let same = false;
    try {
      same = new URL(origin).host.toLowerCase() === String(host).trim().toLowerCase();
    } catch {
      same = false;
    }
    if (!same) return tr('origem não permitida para o terminal interativo');
  }
```

Teste unitário atual (`server/http/http.test.ts:430-439`):

```ts
  it('Origin: ausente, mesma origem ou local', () => {
    expect(originAllowed(undefined, 'localhost:4747', allowed)).toBe(true);
    expect(originAllowed('http://localhost:4747', 'localhost:4747', allowed)).toBe(true);
    expect(originAllowed('http://localhost:5173', '127.0.0.1:4747', allowed)).toBe(true); // Vite dev com proxy
    expect(originAllowed('http://192.168.0.10:4747', '192.168.0.10:4747', allowed)).toBe(true);
    expect(originAllowed('http://attacker.example', 'localhost:4747', allowed)).toBe(false);
    ...
```

Quem chama sem Origin (continua aceito, não muda): a extensão do omp, os hooks do Claude Code, o auxiliar do Codex,
curl. Todos são processos Node/CLI, que não mandam `Origin`.

Convenções: comentários e mensagens em português; texto visível via `tr('...')` (aqui nenhum texto novo é necessário);
toda mudança visível ao usuário entra em "Não lançado" no `CHANGELOG.md`.

## Commands you will need

| Purpose   | Command                                   | Expected on success |
|-----------|-------------------------------------------|---------------------|
| Install   | `npm ci`                                  | exit 0              |
| Typecheck | `npm run typecheck`                       | exit 0, no errors   |
| Tests     | `npx vitest run server/http/http.test.ts` | all pass            |
| All tests | `npm test`                                | 173+ files pass     |
| Build     | `npm run build`                           | exit 0              |

## Scope

**In scope** (the only files you should modify):
- `server/http/guard.ts`
- `server/http/http.test.ts`
- `server/index.ts` (só a linha que cria o guard)
- `CHANGELOG.md` (uma linha em "Não lançado")

**Out of scope** (do NOT touch):
- `server/http/pty.ts` — já é estrito; não unificar agora.
- `hostAllowed` e `isLoopbackHost` — a regra de Host (DNS rebinding) está correta e não muda.
- `shared/world/`, `server/world/` — regras do Habblaud World (ver AGENTS.md); não tocar.
- Os outros arquivos de teste que chamam `createRequestGuard({ allowedHosts: ... })` — a assinatura nova é compatível
  (a opção nova é opcional).

## Git workflow

- Branch: `fix/origin-guard-estrito` a partir de `main`.
- Um commit; estilo do repo (prefixo de área em português), por exemplo:
  `http: o guard só aceita Origin da mesma origem (outra porta do localhost não passa)`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Opção `looseLoopbackOrigin` no guard e regra estrita por padrão

Em `server/http/guard.ts`:

1. Mude a assinatura para `originAllowed(origin, host, allowed, looseLoopback = false)`.
2. Na última linha, só aceite loopback quando `looseLoopback` for `true`:
   `return !!name && ((looseLoopback && isLoopbackName(name)) || allowed.has(name));`
3. Em `GuardOptions`, adicione:
   ```ts
   /** Aceita Origin de qualquer porta do loopback (só no modo dev, para o Vite em outra porta). */
   looseLoopbackOrigin?: boolean;
   ```
4. Em `createRequestGuard`, passe `opts.looseLoopbackOrigin ?? false` como 4º argumento de `originAllowed`.
5. Atualize os comentários: o do topo (linhas 7-9) e o JSDoc de `originAllowed`, dizendo que a origem precisa ser a
   mesma do Host ou um nome de HABBLAUD_ALLOWED_HOSTS, e que outra porta do localhost só passa no modo dev (como o
   terminal, em http/pty.ts).

**Verify**: `npm run typecheck` → exit 0.

### Step 2: Ligar a folga só no modo dev

Em `server/index.ts:433`, troque por:

```ts
const guard = createRequestGuard({ allowedHosts: config.allowedHosts, looseLoopbackOrigin: config.dev });
```

**Verify**: `grep -n "looseLoopbackOrigin" server/index.ts server/http/guard.ts` → 1 linha em index.ts e ao menos 3 em guard.ts.

### Step 3: Testes

Em `server/http/http.test.ts`, no teste `'Origin: ausente, mesma origem ou local'` (linha ~430):

- Renomeie para `'Origin: ausente, mesma origem ou HABBLAUD_ALLOWED_HOSTS; outra porta do localhost só no dev'`.
- Troque a linha do Vite (`localhost:5173` com Host `127.0.0.1:4747`) por duas:
  ```ts
  expect(originAllowed('http://localhost:5173', '127.0.0.1:4747', allowed)).toBe(false); // outra porta local
  expect(originAllowed('http://localhost:5173', '127.0.0.1:4747', allowed, true)).toBe(true); // modo dev (Vite)
  ```
- Adicione:
  ```ts
  expect(originAllowed('http://127.0.0.1:8080', '127.0.0.1:4747', allowed)).toBe(false);
  expect(originAllowed('http://habblaud.lan:9999', 'localhost:4747', allowed)).toBe(true); // nome liberado: porta ignorada
  ```
  (confirme que `allowed` nesse `describe` contém `habblaud.lan`; se não contiver, use o nome que estiver nele.)

No teste de integração `'CSRF: POST exige JSON e origem local'` (linha ~209), antes de `same`, adicione um caso
que prova o bloqueio de outra porta do localhost na rota real:

```ts
const otherPort = await raw(env.base, '/api/demo', { method: 'POST', headers: { ...JSON_HEADERS, Host: `localhost:${port}`, Origin: 'http://localhost:1' }, body: demoOn });
expect(otherPort.status).toBe(403);
expect((await snapshotOf(env.base)).meta.demo).toBe(false);
```

(`port` já é declarado nesse teste; mova a declaração para antes se preciso.)

**Verify**: `npx vitest run server/http/http.test.ts` → all pass.

### Step 4: Suíte completa e CHANGELOG

Rode a suíte inteira: outros testes usam Origin (`server/permissions/http.test.ts:56` manda `Origin: srv.base`, que é
a mesma origem e deve continuar passando).

Adicione em `CHANGELOG.md`, seção "Não lançado", uma linha no estilo das vizinhas, por exemplo:
"Segurança: pedidos que mudam o escritório (mensagens, novo agente, permissões) só são aceitos da própria página do
Habblaud; uma página aberta em outra porta do computador não passa mais."

**Verify**: `npm test` → todos passam; `npm run build` → exit 0.

## Test plan

- Unidade (`server/http/http.test.ts`): outra porta do loopback recusada por padrão; aceita com `looseLoopback`;
  nome de HABBLAUD_ALLOWED_HOSTS aceito em qualquer porta; casos antigos (ausente, mesma origem, attacker, null,
  file://) inalterados.
- Integração (mesmo arquivo): POST `/api/demo` com Origin `http://localhost:1` → 403 e o demo continua desligado.
- Padrão: os próprios testes vizinhos do guard no mesmo arquivo.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, incluindo os casos novos acima
- [ ] `npm run build` exits 0
- [ ] `grep -n "return !!name && (isLoopbackName(name) || allowed.has(name));" server/http/guard.ts` → nenhuma linha (a regra antiga de Origin sumiu; a de `hostAllowed` fica)
- [ ] `git status` mostra só os 4 arquivos do escopo (mais `plans/README.md`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- O código de `originAllowed` ou `createRequestGuard` não bate com os trechos acima.
- Algum teste fora de `server/http/http.test.ts` falha por causa de Origin de outra porta local: reporte qual rota e
  qual cliente; não afrouxe a regra para ele passar.
- Você descobrir um cliente de produção (página, script do repo) que mande Origin de outra porta local de propósito
  (procure com `grep -rn "Origin" scripts mod client/src --include=*.ts --include=*.mjs`).

## Maintenance notes

- Quem servir a página por proxy/túnel com outro nome precisa pôr o nome em `HABBLAUD_ALLOWED_HOSTS` (já era assim
  para o Host).
- Revisar: a porta remota (celular) passa pelo mesmo guard; a página do celular é servida pela própria porta remota,
  então Origin = Host e continua passando.
- Adiado: `Sec-Fetch-Site` no guard global (como o terminal faz). A regra de Origin estrita já cobre o caso; adicionar
  Fetch Metadata é outra mudança.
