# Plan 004: Revogação do acesso remoto falha fechada e o verificador de versão nunca derruba o servidor

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4931abc..HEAD -- server/http/remote.ts server/http/remote.test.ts server/updates/checker.ts server/updates/checker.test.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: security (A) + bug (B)
- **Planned at**: commit `4931abc`, 2026-10-24

## Why this matters

Duas falhas pequenas e independentes, cada uma com um caminho de erro silencioso:

**A. Revogação esquecida.** O botão "Sair" do acesso remoto (celular) revoga o token assinado (validade de 30 dias) e
grava a lista em `~/.habblaud/remote-revoked.json`. Se esse arquivo existir mas estiver ilegível ou corrompido no
próximo início, `loadRevoked` trata como "ninguém revogado": um token encerrado volta a valer até vencer. A correção
falha fechada: arquivo existente e ilegível → todos os tokens emitidos antes deste início ficam inválidos (o celular só
precisa entrar de novo) e um aviso vai para o log. Falha ao gravar a lista também passa a gerar aviso.

**B. Rejeição não tratada.** `UpdateChecker.run()` chama `this.opts.onChange?.()` sem proteção. O temporizador chama
`void this.check()`; se `onChange` lançar, a promessa rejeita sem ninguém tratar. O servidor não registra
`process.on('unhandledRejection')` (conferido: nenhum em `server/`), e o Node 22+ encerra o processo nesse caso.
Hoje o `onChange` de produção é `() => late.office?.markDirty()` (`server/index.ts`), que dificilmente lança; a
correção é uma proteção barata no ponto certo.

## Current state

### A — `server/http/remote.ts`

Imports (linhas 21-30): `node:crypto`, `node:fs` (`existsSync`, `readFileSync`, `renameSync`, `writeFileSync`, ...),
`../remote/password`, `./guard`, `../../shared/i18n` (`getLocale`, `tr`). **Não importa `log`**. Outros módulos do
servidor importam assim (ex.: `server/messages/registry.ts`): `import { errMsg, log } from '../log';`.

`SESSION_MS` (linha 57): `export const SESSION_MS = 30 * 24 * 60 * 60 * 1000;`. O token é `v1.<exp>.<assinatura>`,
emitido em `issue()` com `exp = now() + SESSION_MS`; logo, a data de emissão é `exp - SESSION_MS`.

`RemoteSessions` (linhas ~129-202), trechos:

```ts
  verify(token: string | undefined): boolean {
    const parsed = this.parse(token);
    return !!parsed && !this.revoked.has(parsed.sig);
  }
...
  private loadRevoked(): void {
    if (!this.revokedFile) return;
    try {
      const data: unknown = JSON.parse(readFileSync(this.revokedFile, 'utf8'));
      const now = this.now();
      for (const [sig, exp] of Object.entries(data as Record<string, unknown>)) {
        if (/^[A-Za-z0-9_-]{43}$/.test(sig) && typeof exp === 'number' && exp > now) this.revoked.set(sig, exp);
      }
    } catch {
      // sem arquivo (ou ilegível): ninguém encerrado ainda
    }
  }

  private saveRevoked(): void {
    if (!this.revokedFile) return;
    try {
      mkdirSync(dirname(this.revokedFile), { recursive: true });
      const tmp = `${this.revokedFile}.tmp`;
      writeFileSync(tmp, JSON.stringify(Object.fromEntries(this.revoked)), { mode: 0o600 });
      renameSync(tmp, this.revokedFile);
    } catch {
      // segue valendo em memória até o próximo reinício
    }
  }
```

Teste-modelo (`server/http/remote.test.ts:91-107`):

```ts
  it('revoke encerra só aquele token, e a lista sobrevive a um reinício', () => {
    const now = 1_800_000_000_000;
    const tmp = tempDir();
    const file = join(tmp.dir, 'data', 'remote-revoked.json');
    const sessions = new RemoteSessions(SECRET, CFG, () => now, file);
    const phone = sessions.issue().token;
    ...
    const restarted = new RemoteSessions(SECRET, CFG, () => now, file);
    expect(restarted.verify(phone)).toBe(false);
    expect(restarted.verify(other)).toBe(true);
    tmp.cleanup();
  });
```

### B — `server/updates/checker.ts`

```ts
// linhas 165-181
  private async run(): Promise<UpdateStatus> {
    const before = JSON.stringify(this.status());
    this.lastAttempt = this.now();
    try {
      await this.fetchLatest();
      ...
    } catch (err) {
      this.error = describeError(err);
      log.warnOnce('updates', tr('Verificação de versão nova falhou: {0}.', [this.error]));
    }
    this.schedule(this.error ? RETRY_MS : CHECK_EVERY_MS);
    this.announce();
    const after = this.status();
    if (JSON.stringify(after) !== before) this.opts.onChange?.();
    return after;
  }
// linha 239
    this.timer = setTimeout(() => void this.check(), Math.min(ms, 2 ** 31 - 1));
```

`checker.ts` já importa `log` e `tr`. Teste-modelo: `server/updates/checker.test.ts`, teste
`'release mais nova: available, link e data; avisa a mudança'` (linha ~86), que usa `make({ fetch, now, onChange })`
e `fakeFetch([...])`.

Convenções: texto de log via `tr('texto em PT', [args])` com lacunas numeradas, nunca template string dentro do
`tr()`. Todo texto novo passado a `tr()` precisa de tradução em `shared/locales/en.ts`, `es.ts` e `fr.ts`
(chave = o texto em PT; ver o cabeçalho de `shared/locales/en.ts`). `npm run i18n:check` lista as faltantes.

## Commands you will need

| Purpose   | Command                                                         | Expected on success |
|-----------|-----------------------------------------------------------------|---------------------|
| Install   | `npm ci`                                                        | exit 0              |
| Typecheck | `npm run typecheck`                                             | exit 0              |
| Tests     | `npx vitest run server/http/remote.test.ts server/updates/checker.test.ts` | all pass |
| All tests | `npm test`                                                      | all pass            |
| i18n      | `npm run i18n:check`                                            | os textos novos não aparecem como "sem tradução" |

## Scope

**In scope**:
- `server/http/remote.ts` (só `RemoteSessions`: `verify`, `loadRevoked`, `saveRevoked`, um campo novo, import de `log`)
- `server/http/remote.test.ts`
- `server/updates/checker.ts` (só a chamada de `onChange` em `run`)
- `server/updates/checker.test.ts`
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts` (traduções dos textos novos)

**Out of scope**:
- `loadSecret` (mesmo arquivo): o fallback dele já falha seguro (segredo novo = sessões antigas inválidas).
- Leitura do corpo em `readJson`/login (drena acima do limite): considerado e rejeitado, ver `plans/README.md`.
- `CHANGELOG.md`: só caminhos de erro, nada visível no uso normal.

## Git workflow

- Branch: `fix/revogacao-e-verificador` a partir de `main`.
- Dois commits, um por parte, estilo do repo, ex.:
  `acesso remoto: lista de tokens encerrados ilegível invalida as sessões anteriores (falha fechada)` e
  `atualizações: erro no aviso de mudança não derruba o servidor`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1 (A): Teste da revogação ilegível (deve FALHAR antes)

Em `server/http/remote.test.ts`, ao lado do teste-modelo, adicione:

```ts
  it('lista de tokens encerrados ilegível: as sessões anteriores deixam de valer; as novas valem', () => {
    const now = 1_800_000_000_000;
    const tmp = tempDir();
    const file = join(tmp.dir, 'data', 'remote-revoked.json');
    const before = new RemoteSessions(SECRET, CFG, () => now, file);
    const phone = before.issue().token;
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, '{ corrompido');
    const restarted = new RemoteSessions(SECRET, CFG, () => now + 1_000, file);
    expect(restarted.verify(phone)).toBe(false);
    expect(restarted.verify(restarted.issue().token)).toBe(true);
    tmp.cleanup();
  });
```

Ajuste os imports do arquivo de teste (`mkdirSync`, `writeFileSync` de `node:fs`; `dirname` de `node:path`) se ainda
não estiverem lá.

**Verify**: `npx vitest run server/http/remote.test.ts -t "ilegível"` → falha (`verify(phone)` é `true`).

### Step 2 (A): Falha fechada em `RemoteSessions`

1. `import { log } from '../log';` em `server/http/remote.ts`.
2. Campo novo em `RemoteSessions`:
   ```ts
   /** Tokens emitidos antes disto não valem (a lista de encerrados estava ilegível ao subir). */
   private notBefore = 0;
   ```
3. Em `verify`, rejeite os emitidos antes de `notBefore`:
   ```ts
   return !!parsed && parsed.exp - SESSION_MS >= this.notBefore && !this.revoked.has(parsed.sig);
   ```
4. Em `loadRevoked`, troque o `catch` vazio por: arquivo ausente segue sem nada; qualquer outro erro (leitura ou JSON)
   faz `this.notBefore = this.now()` e avisa:
   ```ts
   } catch (err) {
     if ((err as NodeJS.ErrnoException).code === 'ENOENT') return; // ninguém encerrado ainda
     this.notBefore = this.now();
     log.warn(tr('Acesso remoto: a lista de sessões encerradas está ilegível ({0}); quem estava conectado precisa entrar de novo.', [(err as Error).message]));
   }
   ```
   Se o JSON for válido mas não for objeto (ex.: `null`, `[]`), `Object.entries` lança ou não itera: trate `null`
   como ilegível (use `if (!data || typeof data !== 'object') throw new Error('formato inválido');` logo após o parse).
5. Em `saveRevoked`, no `catch`, avise (mantendo o comportamento de seguir em memória):
   ```ts
   } catch (err) {
     log.warn(tr('Acesso remoto: não deu para gravar a lista de sessões encerradas ({0}); vale até o próximo reinício.', [(err as Error).message]));
   }
   ```
6. Adicione as duas chaves novas nos três arquivos de `shared/locales/` (en, es, fr), perto de outras chaves
   "Acesso remoto: ...". Mantenha `{0}` na tradução.

**Verify**: `npx vitest run server/http/remote.test.ts` → all pass (o teste novo e o teste-modelo).

### Step 3 (B): Teste do `onChange` que lança (deve FALHAR antes)

Em `server/updates/checker.test.ts`, adicione um teste que monta o verificador como o teste-modelo, com
`onChange: () => { throw new Error('boom'); }` e um `fakeFetch` que devolve uma release nova (copie a resposta do
teste-modelo), e afirme:

```ts
await expect(c.check()).resolves.toMatchObject({ available: true });
```

(Use os nomes de campo de `UpdateStatus` que o teste-modelo já afirma; se `available` não existir, use o que ele usa.)

**Verify**: `npx vitest run server/updates/checker.test.ts -t "onChange"` → falha (a promessa rejeita com `boom`).

### Step 4 (B): Proteger a chamada

Em `run()`, troque a linha do `onChange` por:

```ts
    if (JSON.stringify(after) !== before) {
      try {
        this.opts.onChange?.();
      } catch (err) {
        log.warn(tr('Aviso de versão nova falhou: {0}.', [errMsg(err)]));
      }
    }
```

Importe `errMsg` de `'../log'` se o arquivo ainda não importar (confira a linha de import de `log`). Adicione a chave
nova em en/es/fr.

**Verify**: `npx vitest run server/updates/checker.test.ts` → all pass.

### Step 5: Fechamento

**Verify**: `npm run typecheck` → exit 0; `npm test` → all pass; `npm run i18n:check` → nenhuma das 3 chaves novas
aparece como "sem tradução".

## Test plan

- `remote.test.ts`: arquivo de revogação corrompido → token antigo recusado, token novo aceito.
- `checker.test.ts`: `onChange` que lança → `check()` resolve com o status.
- Testes existentes dos dois arquivos continuam passando sem mudança.

## Done criteria

- [ ] `npm run typecheck` exits 0
- [ ] `npm test` exits 0, com os 2 testes novos
- [ ] `grep -n "notBefore" server/http/remote.ts` → ao menos 3 linhas
- [ ] `npm run i18n:check` sem as 3 chaves novas em "sem tradução"
- [ ] `git status` só com os arquivos do escopo (mais `plans/README.md`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- `RemoteSessions` ou `run()` não batem com os trechos acima.
- Os testes novos passam antes da correção.
- Algum teste existente de `remote.test.ts` depende de arquivo de revogação ilegível valer como vazio: reporte qual.

## Maintenance notes

- Se o formato do token mudar (ex.: incluir a data de emissão), troque `parsed.exp - SESSION_MS` pela data real.
- Se `SESSION_MS` mudar, tokens antigos ainda calculam a emissão com o valor novo; a diferença só desloca o corte de
  `notBefore`, sem abrir brecha (o corte só existe quando a lista estava ilegível).
