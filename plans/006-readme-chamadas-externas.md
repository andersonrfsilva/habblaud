# Plan 006: A introdução dos READMEs para de dizer "nenhuma chamada externa"

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat 4931abc..HEAD -- README.md README.pt-BR.md README.es.md README.fr.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (só documentação)
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `4931abc`, 2026-10-24

## Why this matters

A introdução dos quatro READMEs afirma que o Habblaud "não faz chamadas externas". Não é verdade: ele consulta a API do
GitHub a cada 6 h atrás de versão nova (`server/updates/checker.ts`, desligável com `HABBLAUD_UPDATE_CHECK=0`), o
Habblaud World (ligado por padrão) fala com o servidor do mundo depois do login, e com o omp roda `omp usage --json`,
que consulta o provedor. A seção "Privacidade e segurança" de cada README já descreve isso corretamente. O problema é
só a frase da introdução, que contradiz a própria seção. Promessa de privacidade errada é o tipo de documentação que
custa confiança.

## Current state

As frases (texto atual exato):

- `README.md:37-39`:
  > Everything runs on your machine, reading the files Claude Code itself already writes (plus only the `alias` lines in
  > your shell, to give each account its letter). Nothing leaves the computer: Habblaud doesn't read credentials or make
  > external calls.
- `README.pt-BR.md:37-38`:
  > Tudo roda na sua máquina, lendo os arquivos que o próprio Claude Code já grava (e só as linhas `alias` do seu shell,
  > para dar a letra de cada conta). Nada sai do computador: o Habblaud não lê credenciais nem faz chamadas externas.
- `README.es.md:37-39`:
  > Todo se ejecuta en tu máquina, leyendo los archivos que el propio Claude Code ya guarda (y solo las líneas `alias` de
  > tu shell, para asignarle una letra a cada cuenta). Nada sale de tu computadora: Habblaud no lee credenciales ni hace
  > llamadas externas.
- `README.fr.md:37-39`:
  > Tout tourne sur votre machine, en lisant les fichiers que Claude Code écrit déjà lui-même (et seulement les lignes
  > `alias` de votre shell, pour attribuer une lettre à chaque compte). Rien ne quitte l'ordinateur : Habblaud ne lit
  > aucun identifiant et ne fait aucun appel externe.

Seções de privacidade (destino do link), títulos exatos:

| Arquivo | Linha | Título | Âncora GitHub |
|---|---|---|---|
| `README.md` | 991 | `## Privacy and security` | `#privacy-and-security` |
| `README.pt-BR.md` | 988 | `## Privacidade e segurança` | `#privacidade-e-segurança` |
| `README.es.md` | 1026 | `## Privacidad y seguridad` | `#privacidad-y-seguridad` |
| `README.fr.md` | 1035 | `## Confidentialité et sécurité` | `#confidentialité-et-sécurité` |

Trecho da seção em inglês que a frase nova resume (`README.md:1005-1012`): "There's no telemetry. The only external
call is the new-version check ... `HABBLAUD_UPDATE_CHECK=0` turns off the request. Habblaud World (on by default) only
sends something to the world server after you create an account and sign in ... `HABBLAUD_WORLD=0` turns it all off."

Os READMEs quebram linha em ~120 colunas; mantenha.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Conferir | `grep -n "chamadas externas\|external calls\|llamadas externas\|appel externe" README*.md` | só linhas fora da introdução (ou nenhuma) |

## Scope

**In scope**: `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md` — só a frase da introdução.

**Out of scope**: as seções de privacidade (já corretas); `docs/world/privacidade.md`; qualquer código.

## Git workflow

- Branch: `docs/readme-chamadas-externas` a partir de `main`.
- Um commit: `docs: a introdução não promete "nenhuma chamada externa" (versão nova, World e omp usage consultam fora)`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Reescrever a frase nos 4 idiomas

Troque só a segunda frase (a partir de "Nothing leaves" / "Nada sai" / "Nada sale" / "Rien ne quitte") por:

- en: `Your sessions stay on the computer: Habblaud doesn't read credentials and has no telemetry; the few requests it makes (new-version check, Habblaud World after you sign in) are listed in [Privacy and security](#privacy-and-security).`
- pt-BR: `Suas sessões ficam no computador: o Habblaud não lê credenciais nem tem telemetria; as poucas consultas que faz (versão nova, Habblaud World depois do login) estão em [Privacidade e segurança](#privacidade-e-segurança).`
- es: `Tus sesiones se quedan en tu computadora: Habblaud no lee credenciales ni tiene telemetría; las pocas consultas que hace (versión nueva, Habblaud World después de iniciar sesión) están en [Privacidad y seguridad](#privacidad-y-seguridad).`
- fr: `Vos sessions restent sur l'ordinateur : Habblaud ne lit aucun identifiant et n'a pas de télémétrie ; les rares requêtes qu'il fait (nouvelle version, Habblaud World après connexion) sont décrites dans [Confidentialité et sécurité](#confidentialité-et-sécurité).`

Reflua as linhas do parágrafo para ~120 colunas.

**Verify**: `grep -n "external calls\|chamadas externas\|llamadas externas\|appel externe" README*.md` → nenhuma linha
na faixa 35-42 de cada arquivo.

### Step 2: Conferir os links

**Verify**: para cada arquivo, o título da tabela acima existe:
`grep -n "^## Privacy and security" README.md; grep -n "^## Privacidade e segurança" README.pt-BR.md; grep -n "^## Privacidad y seguridad" README.es.md; grep -n "^## Confidentialité et sécurité" README.fr.md` → 1 linha cada.

## Test plan

- Documentação: sem testes. A prova são os greps acima.

## Done criteria

- [ ] Os greps dos Steps 1 e 2 dão o resultado esperado
- [ ] `git diff --stat` mostra só os 4 READMEs (mais `plans/README.md`)
- [ ] `plans/README.md` status row updated

## STOP conditions

- A frase atual não bate com as citações acima.
- Um dos títulos de seção não existe mais (o link ficaria quebrado).

## Maintenance notes

- Toda nova chamada para fora do computador precisa entrar na seção de privacidade dos 4 READMEs; a introdução só
  aponta para lá, então não precisa mudar de novo.
