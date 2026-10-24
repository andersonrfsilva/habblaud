# Plan 005: Traduções completas e `i18n:check --strict` no CI

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4931abc..HEAD -- scripts/i18n-check.ts shared/locales .github/workflows/ci.yml CONTRIBUTING.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (pode deixar o CI vermelho quando alguém esquecer uma tradução — é a intenção)
- **Depends on**: none (se 004 for executado depois, ele já precisa traduzir as próprias chaves; com este plano o CI cobra)
- **Category**: dx
- **Planned at**: commit `4931abc`, 2026-10-24

## Why this matters

Todo texto visível passa por `tr('texto em PT')`, com traduções em `shared/locales/{en,es,fr}.ts`. O script
`npm run i18n:check` lista o que falta e o que sobrou, mas **sempre sai com código 0** e o CI não o roda. Hoje, na
`main` do fork, faltam traduções e sobra uma chave morta:

```
en/es/fr: 3101/3102 textos traduzidos
  sem tradução: "Terminal {0}: o Claude Code é o processo {1} (aberto por um intermediário)."  (server/pty/manager.ts:474)
  sobrando:     "informe o modelo ou o raciocínio"
```

Este plano zera essa diferença e adiciona um modo `--strict` (sai com 1 se houver falta ou sobra) que o CI roda. O
comportamento padrão do script continua o mesmo, preservando a decisão registrada no cabeçalho dele ("Não falha o
build: um texto sem tradução simplesmente aparece em PT"): o build e o `npm run i18n:check` local não mudam; só o CI
passa a cobrar.

## Current state

- `scripts/i18n-check.ts` — 59 linhas. Cabeçalho (linhas 1-3):
  ```ts
  // Confere as traduções: lista os textos passados a tr() sem tradução e as traduções que sobraram
  // (o texto em PT mudou ou saiu do código). Uso: npm run i18n:check [-- en|es|fr]
  // Não falha o build: um texto sem tradução simplesmente aparece em PT.
  ```
  Bloco principal (linhas 47-58):
  ```ts
  if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('i18n-check.ts')) {
    const only = process.argv[2];
    const keys = collectKeys();
    for (const [lang, dict] of Object.entries(DICTS)) {
      if (only && only !== lang) continue;
      const missing = [...keys.keys()].filter((k) => !(k in dict));
      const stale = Object.keys(dict).filter((k) => !keys.has(k));
      console.log(`${lang}: ${keys.size - missing.length}/${keys.size} textos traduzidos`);
      for (const k of missing) console.log(`  sem tradução: ${JSON.stringify(k)}  (${keys.get(k)![0]})`);
      for (const k of stale) console.log(`  sobrando:     ${JSON.stringify(k)}`);
    }
  }
  ```
- `package.json` script: `"i18n:check": "tsx scripts/i18n-check.ts"`.
- `shared/locales/en.ts` — `export const EN: Readonly<Record<string, string>> = { "chave em PT": "valor", ... }`;
  comentários `// caminho/do/arquivo.ts` agrupam as chaves. Chave morta em `en.ts:1823`, `es.ts:1823`, `fr.ts:1719`:
  `"informe o modelo ou o raciocínio": ...` (confirmado: nenhum `tr()` no código usa esse texto).
- Chaves vizinhas da que falta (para posicionar e copiar o estilo), `en.ts:1897-1898`:
  ```ts
  "Terminal {0} aberto em {1} (conta {2}, retomando {3}).": "Terminal {0} opened in {1} (account {2}, resuming {3}).",
  "Terminal {0} aberto em {1} (conta {2}).": "Terminal {0} opened in {1} (account {2}).",
  ```
  Em `es.ts` ficam nas linhas 1897-1898; em `fr.ts`, 2334-2335.
- `.github/workflows/ci.yml` — job `verificar` (ubuntu + windows) com os passos:
  ```yaml
      - run: npm ci
      - run: npm run typecheck
      - run: npm test
      - run: npm run build
  ```
- `CONTRIBUTING.md`, seção "Textos e traduções", diz: "`npm run i18n:check` lista os textos sem tradução e as
  traduções que sobraram."

## Commands you will need

| Purpose   | Command                          | Expected on success |
|-----------|----------------------------------|---------------------|
| Install   | `npm ci`                         | exit 0              |
| i18n      | `npm run i18n:check`             | exit 0, "3102/3102" (ou o total atual) nos 3 idiomas, nada listado |
| i18n strict | `npm run i18n:check -- --strict` | exit 0 sem falta/sobra; exit 1 com |
| Typecheck | `npm run typecheck`              | exit 0              |
| Tests     | `npm test`                       | all pass            |

## Scope

**In scope**:
- `scripts/i18n-check.ts`
- `shared/locales/en.ts`, `shared/locales/es.ts`, `shared/locales/fr.ts`
- `.github/workflows/ci.yml` (um passo novo)
- `CONTRIBUTING.md` (uma frase)

**Out of scope**:
- `server/pty/manager.ts` — o texto em PT fica como está; só se traduz.
- `package.json` — não criar script novo; o CI passa `-- --strict`.
- READMEs.

## Git workflow

- Branch: `chore/i18n-strict-ci` a partir de `main`.
- Commits: `i18n: traduz o aviso do terminal aberto por intermediário e remove chave sem uso` e
  `ci: i18n:check --strict`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Zerar a diferença

1. Remova a linha `"informe o modelo ou o raciocínio": ...` dos três arquivos de locale.
2. Adicione, logo após as chaves `"Terminal {0} aberto em {1} (conta {2})."` de cada arquivo:
   - en: `"Terminal {0}: o Claude Code é o processo {1} (aberto por um intermediário).": "Terminal {0}: Claude Code is process {1} (opened through an intermediary).",`
   - es: `"Terminal {0}: o Claude Code é o processo {1} (aberto por um intermediário).": "Terminal {0}: Claude Code es el proceso {1} (abierto por un intermediario).",`
   - fr: `"Terminal {0}: o Claude Code é o processo {1} (aberto por um intermediário).": "Terminal {0} : Claude Code est le processus {1} (ouvert par un intermédiaire).",`
   A chave tem de ser **idêntica** ao texto em `server/pty/manager.ts:474` (copie de lá).

**Verify**: `npm run i18n:check` → nos 3 idiomas, `N/N textos traduzidos` (mesmo número dos dois lados) e nenhuma linha
"sem tradução" ou "sobrando".

### Step 2: Modo `--strict`

Em `scripts/i18n-check.ts`:

1. Separe a flag do filtro de idioma:
   ```ts
   const args = process.argv.slice(2);
   const strict = args.includes('--strict');
   const only = args.find((a) => !a.startsWith('--'));
   ```
2. Acumule se houve problema (`let problems = 0; problems += missing.length + stale.length;`) dentro do laço.
3. Depois do laço: `if (strict && problems) process.exitCode = 1;`
4. Atualize o cabeçalho: uso `npm run i18n:check [-- en|es|fr] [--strict]`; mantenha a frase "Não falha o build" e
   acrescente "`--strict` (CI) sai com 1 se faltar ou sobrar tradução".

**Verify**:
- `npm run i18n:check -- --strict; echo EXIT=$?` → `EXIT=0`.
- Prova negativa (desfaça depois): acrescente temporariamente `"chave-de-teste": "x",` em `shared/locales/en.ts`,
  rode `npm run i18n:check -- --strict; echo EXIT=$?` → lista `sobrando: "chave-de-teste"` e `EXIT=1`; remova a linha
  e rode de novo → `EXIT=0`.
- `npm run i18n:check -- en; echo EXIT=$?` → só o bloco `en:`, `EXIT=0` (filtro por idioma continua funcionando).

### Step 3: CI e CONTRIBUTING

1. Em `.github/workflows/ci.yml`, depois de `- run: npm run typecheck`, adicione `- run: npm run i18n:check -- --strict`.
2. Em `CONTRIBUTING.md`, na frase sobre `i18n:check`, acrescente: "O CI roda `npm run i18n:check -- --strict`, que
  falha se faltar ou sobrar tradução."

**Verify**: `grep -n "i18n:check -- --strict" .github/workflows/ci.yml CONTRIBUTING.md` → 1 linha em cada.

### Step 4: Fechamento

**Verify**: `npm run typecheck` → exit 0; `npm test` → all pass (há testes de i18n em `shared/i18n.test.ts`; não
devem quebrar).

## Test plan

- Sem teste unitário novo: o script é ferramenta de linha de comando e a prova é a execução do Step 2 (positiva,
  negativa e filtro). O CI passa a exercitar o modo strict a cada push.

## Done criteria

- [ ] `npm run i18n:check -- --strict` exits 0 e não lista nada
- [ ] A prova negativa do Step 2 deu `EXIT=1` (registre a saída no relatório)
- [ ] `npm run typecheck` e `npm test` exit 0
- [ ] `grep -rn "informe o modelo ou o raciocínio" shared/locales` → nenhuma linha
- [ ] `git status` só com os arquivos do escopo (mais `plans/README.md`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- `i18n:check` mostrar outras faltas ou sobras além das duas acima (o código mudou): traduza só se forem poucas (até
  5) e liste no relatório; acima disso, STOP.
- A chave morta aparecer em algum `tr()` (`grep -rn "informe o modelo ou o raciocínio" client server shared scripts mod`
  fora de `shared/locales`): não remova; reporte.
- O passo novo do CI precisar de algo diferente no Windows (o job roda em `windows-latest` também): reporte.

## Maintenance notes

- Quem adicionar `tr('...')` novo precisa traduzir nos três arquivos no mesmo PR, senão o CI fica vermelho.
- Se o upstream (marmottajr/habblaud) não quiser o modo strict, o passo do CI pode ficar só no fork.
