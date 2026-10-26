# Plan 022: Alinhar a documentação da API, as variáveis de ambiente e o AGENTS.md/CLAUDE.md com o código

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat af73a23..HEAD -- README.md README.pt-BR.md README.es.md README.fr.md server/README.md AGENTS.md CLAUDE.md server/http/app.ts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW (só documentação; nenhum código muda)
- **Depends on**: none (o plano 006 mexe nas frases de "nenhuma chamada externa" dos READMEs; este plano não toca nelas)
- **Category**: docs
- **Planned at**: commit `af73a23` (`producao`), 2026-10-25

## Why this matters

As tabelas de rotas da API estão defasadas em dois sentidos. Os quatro READMEs (`README.md`, `README.pt-BR.md`,
`README.es.md`, `README.fr.md`) listam 20 rotas e **não têm** `/api/updates`, `/api/world/*`, `/api/access`, as rotas do
omp (`/api/omp/*`, `/api/asides`), `/api/spawn`, `/api/dictation`, `/api/deliveries`, `/api/pty` nem `/api/host/*`. O
`server/README.md` (a referência "completa") tem `/api/updates` e `/api/world/*`, mas **não tem** `POST /api/rooms/rename`
(que existe no código e nos quatro READMEs) nem as rotas acima. O `AGENTS.md` (e o `CLAUDE.md`, cópia idêntica) diz que o
Habblaud mostra "os agentes do Claude Code e do Codex", mas o omp é suportado e vem ligado por padrão. Duas variáveis de
ambiente lidas pelo código (`HABBLAUD_OMP_MODELS`, `HABBLAUD_ATTACHMENTS_DIR`) não aparecem em nenhum documento. Quem
integra com a API, ou um agente de IA que lê o `AGENTS.md`, parte de uma imagem errada do produto.

**Correção do achado original**: o achado dizia que `POST /api/rooms/rename` "não está na lista atual de rotas". Isso
está **errado**: a rota existe em `server/http/app.ts:722`. O que falta é ela estar no `server/README.md`. Este plano
corrige isso no sentido certo (acrescenta ao `server/README.md`, mantém nos READMEs).

## Current state

### A lista autoritativa de rotas (lida de `server/http/app.ts` e dos handlers que ele chama, em `af73a23`)

`createApiHandler` (`server/http/app.ts:286`) roteia por `path ===` / `startsWith`. Despachos (linha do `if`):

| Linha | Caminho | Handler / observação |
| --- | --- | --- |
| 370 | `/api/world`, `/api/world/*` | `deps.world` (`server/world/http.ts`, 21 rotas); só porta local + Host loopback |
| 382 | `/api/stream` | SSE |
| 387 | `/api/snapshot` | |
| 392 | `/api/health` | campos: `ok, version, demo, docker, remote, terminal, permissions, pty, messages, codexEvents, ompEvents, semMarca, updates, sources, accounts` (linhas 395-420) |
| 422 | `/api/access` | `AccessInfo`: `remote, local, permissions, messages, terminal, spawn, world` |
| 439 | `/api/mod/summary` | |
| 467 | `/api/sessions/*` | `recent` e `:conta/:sessionId/terminal` |
| 475 | `/api/permissions`, `/api/permissions/*` | |
| 486 | `/api/pty`, `/api/pty/*`, `POST /api/agents/:id/stop`, `.../takeover` | `server/http/pty.ts` |
| 514 | `/api/codex/events` | |
| 524 | `/api/omp/events` | `server/omp/http.ts` |
| 532 | `/api/omp/aside` | `server/asides/http.ts` |
| 541 | `/api/asides`, `/api/asides/*` | `server/asides/http.ts` |
| 549 | `/api/spawn`, `/api/spawn/*` | GET `{available, mode}`, POST cria (201), `/folders`, `/attachments`, GET `/:id` |
| 588 | `/api/dictation`, `/api/dictation/*` | GET `{available}`, POST, GET `/:id` |
| 612 | `/api/deliveries`, `/api/deliveries/*` | lista e `/file/:id` |
| 632, 652, 672 | `/api/host/files/*`, `/api/host/dictation/*`, `/api/host/spawn/*` | `poll`/`ack` do auxiliar do host |
| 690 (via `isMessagesPath`, linha 154) | `/api/messages`, `/api/messages/control`, `/api/messages/:id`, `/api/mod/inbox`, `/api/mod/inbox/ack`, `/api/codex/bridge/poll`, `/api/codex/bridge/ack` | `server/messages/http.ts:71-76` |
| 705 | `/api/agents/:id` (GET), `/terminal`, `PUT|DELETE .../character` | |
| 722 | `/api/rooms/rename` | POST `{id, name}` |
| 739 | `/api/stats`, `/api/stats/days` | |
| 743 | `/api/demo` | |
| 750 | `/api/omp/settings` | POST `{roomByProject: boolean}` → `{ok, roomByProject}`; 404 se `setOmpRoomByProject` ausente |
| 757, 762 | `/api/updates`, `/api/updates/check` | |
| (timeline) | `/api/timeline/days`, `/api/timeline/:dia` | `server/http/timeline.ts` |

Trechos de `server/http/app.ts` que o executor deve conferir antes de começar:

```ts
// server/http/app.ts:722-726
    if (path === '/api/rooms/rename') {
      // Como o personagem: a mesma trava do terminal (bind local + Host local).
      if (method !== 'POST') methodNotAllowed(res, 'POST');
      else if (!deps.terminal) sendJson(res, 403, { error: tr('renomear salas desligado: só funciona com o Habblaud acessível apenas pelo próprio computador') });
```
```ts
// server/http/app.ts:757-759
    if (path === '/api/updates') {
      if (!isRead) methodNotAllowed(res, 'GET');
      else sendJson(res, 200, { version: deps.version, ...(deps.updates?.status() ?? { state: 'off', available: false }) });
```
```ts
// server/http/app.ts:140-150  (rotas que a porta remota recusa com 403)
function isLocalOnlyPath(path: string): boolean {
  return (
    path === '/api/permissions' ||
    /^\/api\/permissions\/[^/]+\/wait$/.test(path) ||
    path === '/api/mod/inbox' ||
    path === '/api/mod/inbox/ack' ||
    path.startsWith('/api/codex/') ||
    path.startsWith('/api/omp/') ||
    path.startsWith('/api/host/')
  );
}
```

### O que cada documento tem hoje (medido com `grep -c -F` em `af73a23`)

| Rota | README.md | README.pt-BR.md | README.es.md | README.fr.md | server/README.md |
| --- | --- | --- | --- | --- | --- |
| `/api/access` | 0 | 0 | 0 | 0 | 1 (só em prosa, linha 444) |
| `/api/updates` | 0 | 0 | 0 | 0 | 2 (tabela, linhas 84-85) |
| `/api/world` | 0 | 0 | 0 | 0 | 15 |
| `/api/omp/events`, `/api/omp/aside`, `/api/omp/settings`, `/api/asides` | 0 | 0 | 0 | 0 | 0 |
| `/api/spawn`, `/api/dictation`, `/api/deliveries`, `/api/pty`, `/api/host/` | 0 | 0 | 0 | 0 | 0 |
| `/api/agents/:id/stop`, `takeover` | 0 | 0 | 0 | 0 | 0 |
| `/api/messages/control` | 0 | 0 | 0 | 0 | 0 |
| `/api/codex/events`, `/api/codex/bridge/*` | 1 | 1 | 1 | 1 | só em prosa (linhas 343-355), não na tabela |
| `rooms/rename` | 1 (linha 980) | 1 (977) | 1 (1013) | 1 (1023) | **0** |

Os quatro READMEs têm a tabela de rotas na mesma ordem e com as mesmas 20 linhas (a última é a de
`/api/codex/bridge/poll`: README.md:985, README.pt-BR.md:982, README.es.md:1018, README.fr.md:1028). Cada tabela é
seguida de uma linha "More server details in `server/README.md`" (README.md:987, pt-BR:984, es:1020, fr:1030).

Formato das linhas existentes (README.md:985): ``| `/api/codex/bridge/poll` and `/ack` | `npm run codex:bridge` fetches the messages for Codex and confirms delivery. Local access only. |``
Frase padrão por idioma para a trava: EN "Local access only." / PT "Só com acesso local." / ES "Solo con acceso local." /
FR "En accès local uniquement."

Tabela da API do `server/README.md` (cabeçalho `## API`, linha 65; tabela nas linhas 67-94): colunas `Rota | Descrição`, texto em português, as
rotas em ordem de assunto. `GET /api/health` está na linha 81:

```
| `GET /api/health` | `{ok, version, demo, docker, terminal, permissions, messages, updates:{state, latest, available}, sources, accounts:[{id, usageStatus}]}` |
| `POST /api/demo` | `{enabled: boolean}` liga/desliga agentes simulados (misturados aos reais) |
```

### Variáveis de ambiente (medido: `grep -rhoE "HABBLAUD_[A-Z0-9_]+"` em `server shared scripts client/src mod docker-compose.yml Dockerfile` contra os cinco documentos)

- **Documentadas e inexistentes no código**: nenhuma. Não há nada a remover.
- **No código e em nenhum documento**, e voltadas a quem usa:
  - `HABBLAUD_OMP_MODELS` (`mod/habblaud-omp/habblaud.ts:38-42` e `:240-242`): lida no ambiente **do omp** (como `HABBLAUD_OMP_PERMISSOES`), lista `provedor/id` ou `provedor/*` separados por vírgula; a gaveta do agente só lista e só troca para esses modelos; ausente = todos os que o omp lista.
  - `HABBLAUD_ATTACHMENTS_DIR` (`server/spawn/attachments.ts:109-111`): pasta onde os anexos do "Novo agente" são gravados; padrão `~/.habblaud/attachments`.
- **No código, sem documento, mas não voltadas a quem usa (NÃO documentar)**: `HABBLAUD_REMOTE_USER`, `_PASSWORD_HASH`, `_DOMAIN`, `_HTTP_PORT`, `_NAME`, `_BIND` (escritas pelo `npm run remote:setup`; o README já diz "Os outros `HABBLAUD_REMOTE_*` são escritos pelo `npm run remote:setup`"); `HABBLAUD_REMOTE_PASSWORD` (recusada: só dá aviso); `HABBLAUD_CHROMIUM` (só o script `scripts/world-shots.mjs`); `HABBLAUD_LANG_TEST` (só testes); `HABBLAUD_IN_DOCKER`, `HABBLAUD_BUILD_COMMIT`, `HABBLAUD_BUILD_DIRTY`, `HABBLAUD_HOOK_DEBUG` (internas).
- `HABBLAUD_CODEX_PERMISSION_TIMEOUT` aparece **só num comentário** (`shared/demo/permission.ts:131`); nenhum código a lê. Não documentar (ver "Maintenance notes").

Linhas das tabelas de variáveis onde as duas novas entram (cabeçalho `| Variable | Default | What for |`):

| Arquivo | Linha de `HABBLAUD_DATA_DIR` | Linha de `HABBLAUD_OMP_PERMISSOES` |
| --- | --- | --- |
| README.md | 794 | 811 |
| README.pt-BR.md | 792 | 809 |
| README.es.md | 828 | 845 |
| README.fr.md | 833 | 850 |

### AGENTS.md e CLAUDE.md

São **dois arquivos regulares, versionados e idênticos** (`cmp AGENTS.md CLAUDE.md` sem saída; não há symlink). O
`shared/world/README.md:11` aponta para o `CLAUDE.md` como texto completo do aviso. **As duas cópias precisam continuar
idênticas.** Linhas 3-4 de ambos:

```
O Habblaud é um escritório virtual em pixel art que mostra, em tempo real, o que os agentes do Claude Code e do Codex
estão fazendo: servidor Node sem dependências de runtime (`server/`), página no navegador (`client/`) e código comum
```

O omp é suportado e vem ligado (`server/config.ts:56-57`: `/** Observa as sessões do omp (sources/omp/); HABBLAUD_OMP=0 desliga. */ omp: boolean;`;
README.md tabela de variáveis: ``| `HABBLAUD_OMP` | on | `0` turns off omp (oh-my-pi) in the office. |``).

### Regra de paridade dos READMEs

`CONTRIBUTING.md` não tem regra sobre manter os READMEs idênticos (a seção "Textos e traduções", linhas 22-31, trata só
de `tr()` e `shared/locales/`). Na prática os quatro READMEs são traduções linha a linha, e o `git log` mostra os quatro
mudando juntos. **Siga o hábito: toda linha nova entra nos quatro, na mesma posição.** O `server/README.md` é só em
português e é um documento à parte.

### Convenções

- Prosa em português do Brasil; comentários e docs nos quatro idiomas conforme o arquivo. Conversão: `README.pt-BR.md` PT, `README.es.md` ES, `README.fr.md` FR, `README.md` EN.
- Textos em `.md` não passam por `tr()`: **não** mexa em `shared/locales/`, e o `npm run i18n:check` não é afetado.
- Mensagens de commit em português, minúsculas (`git log --oneline`: "modo sem marca: HABBLAUD_SEM_MARCA=1 troca omp, Claude Code e Codex por "agente" na interface").
- `CHANGELOG.md`: correção só de documentação, sem efeito visível no app, **não** entra em "Não lançado".

## Commands you will need

| Purpose | Command | Expected on success |
| --- | --- | --- |
| Worktree | `git -C /home/anderson/Desenvolvimento/habblaud worktree add /tmp/hb-022 -b advisor/022-docs-api-e-agents producao` | branch criado |
| node_modules | `ln -s /home/anderson/Desenvolvimento/habblaud/node_modules /tmp/hb-022/node_modules` | link criado (NUNCA commitar) |
| Typecheck (sanidade) | `npm run typecheck` | exit 0 |
| Testes (sanidade) | `npm test` | todos passam (baseline em `af73a23`: 181 arquivos / 2180 testes, 2 skipped) |
| i18n | `npm run i18n:check -- --strict` | exit 0 (nada muda) |

## Scope

**In scope** (os únicos arquivos a modificar):
- `README.md`, `README.pt-BR.md`, `README.es.md`, `README.fr.md` (tabela de rotas e tabela de variáveis)
- `server/README.md` (tabela `## API`)
- `AGENTS.md` e `CLAUDE.md` (a mesma edição, idêntica)

**Out of scope** (NÃO tocar):
- Qualquer código (`server/`, `client/`, `shared/`, `scripts/`, `mod/`), inclusive o comentário morto `HABBLAUD_CODEX_PERMISSION_TIMEOUT`.
- A frase "nenhuma chamada externa" / "Nothing leaves the computer… network calls" dos READMEs e a seção "The sources are local files…": é o plano 006. Não edite essas frases.
- O subtítulo (linha 8) dos quatro READMEs, `package.json` `description` e `client/index.html` (meta `description`, `og:description`): dizem "Claude Code (e Codex)" / "Claude Code", mas são texto de marca e de SEO; decisão do dono. Só relatar no final.
- `shared/locales/`, `CHANGELOG.md`, `plans/README.md` além da linha do status deste plano.
- Reescrever prosa existente, reordenar tabelas, traduzir de novo linhas que já existem.

## Git workflow

- Branch: `advisor/022-docs-api-e-agents`, de `producao`, no worktree `/tmp/hb-022` (comandos acima). Faça tudo dentro de `/tmp/hb-022`.
- Um commit por passo lógico (passos 1, 2, 3, 4), mensagens em português, minúsculas, por exemplo: `docs: rotas que faltam na tabela da API dos READMEs`.
- NÃO fazer push. NÃO tocar em `~/apps/habblaud` (produção).

## Steps

### Step 1: Acrescentar as rotas que faltam à tabela de rotas dos quatro READMEs

Em cada README, **logo depois** da linha que começa com ``| `/api/codex/bridge/poll` `` (última linha da tabela) e antes
da linha em branco que precede "More server details…", insira **11 linhas novas**, na ordem abaixo. Não altere nenhuma
linha existente. Posições da linha-âncora: README.md:985, README.pt-BR.md:982, README.es.md:1018, README.fr.md:1028.

**README.md (EN)**

```
| `GET /api/access` | What this connection can use (`remote`, `local`, `permissions`, `messages`, `terminal`, `spawn`, `world`); the page uses it to show or hide features. |
| `GET /api/updates` and `POST /api/updates/check` | New-version check: the version in use and the result; `check` ("Check now") asks GitHub (at most once every 30 s). Off with `HABBLAUD_UPDATE_CHECK=0`. |
| `/api/world/*` | Habblaud World (account, character and building); on by default, `HABBLAUD_WORLD=0` turns it off. Local access only, never through the phone port. |
| `POST /api/omp/events` | Events from the Habblaud extension in omp (session, tool, approval, heartbeat). Local access only. |
| `POST /api/omp/settings` | `{"roomByProject": true \| false}`: Settings › room follows the project, for omp agents. Local access only. |
| `/api/asides…` and `POST /api/omp/aside` | Side chat with the omp agent: the page asks and follows the answer; the omp extension sends the accumulated text and receives whether to cancel. The extension's route is local access only. |
| `/api/spawn…` | New agent: availability, suggested folders, attachments, start and follow-up of the request. Local access only (from the phone, with `spawn` in `HABBLAUD_REMOTE_ALLOW`). |
| `/api/dictation…` | Dictation (microphone) in New agent and in the message box: sends the audio and fetches the text. Local access only (from the phone, with `spawn` or `messages` in `HABBLAUD_REMOTE_ALLOW`). |
| `/api/deliveries…` | Deliveries: the end of each request an agent worked on, with its attachments. Local access only (from the phone, with `terminal` in `HABBLAUD_REMOTE_ALLOW`). |
| `/api/pty…`, `POST /api/agents/:id/stop` and `/takeover` | Interactive terminal and ending or taking over a session; only with `HABBLAUD_PTY=1`. Local access only, never through the phone port. |
| `/api/host/*` | Host helper (`npm run codex:bridge`): fetches and confirms the requests for New agent, dictation and delivery files. Local access only. |
```

**README.pt-BR.md (PT)**

```
| `GET /api/access` | O que esta conexão pode usar (`remote`, `local`, `permissions`, `messages`, `terminal`, `spawn`, `world`); a página usa para mostrar ou esconder recursos. |
| `GET /api/updates` e `POST /api/updates/check` | Verificação de versão nova: a versão em uso e o resultado; `check` ("Verificar agora") consulta o GitHub (no máximo uma vez a cada 30 s). Desligada com `HABBLAUD_UPDATE_CHECK=0`. |
| `/api/world/*` | Habblaud World (conta, personagem e prédio); ligado por padrão, `HABBLAUD_WORLD=0` desliga. Só com acesso local, nunca pela porta do celular. |
| `POST /api/omp/events` | Eventos da extensão do Habblaud no omp (sessão, ferramenta, aprovação, batimento). Só com acesso local. |
| `POST /api/omp/settings` | `{"roomByProject": true \| false}`: Configurações › sala segue o projeto, para os agentes do omp. Só com acesso local. |
| `/api/asides…` e `POST /api/omp/aside` | Bate-papo paralelo com o agente do omp: a página pergunta e acompanha a resposta; a extensão do omp manda o texto acumulado e recebe se deve cancelar. A rota da extensão é só com acesso local. |
| `/api/spawn…` | Novo agente: disponibilidade, pastas sugeridas, anexos, início e acompanhamento do pedido. Só com acesso local (pelo celular, com `spawn` em `HABBLAUD_REMOTE_ALLOW`). |
| `/api/dictation…` | Ditado (microfone) no Novo agente e na caixa de mensagem: envia o áudio e busca o texto. Só com acesso local (pelo celular, com `spawn` ou `messages` em `HABBLAUD_REMOTE_ALLOW`). |
| `/api/deliveries…` | Entregas: o fim de cada pedido em que um agente trabalhou, com os anexos. Só com acesso local (pelo celular, com `terminal` em `HABBLAUD_REMOTE_ALLOW`). |
| `/api/pty…`, `POST /api/agents/:id/stop` e `/takeover` | Terminal interativo e encerrar ou assumir uma sessão; só com `HABBLAUD_PTY=1`. Só com acesso local, nunca pela porta do celular. |
| `/api/host/*` | Auxiliar do host (`npm run codex:bridge`): busca e confirma os pedidos do Novo agente, do ditado e dos arquivos das entregas. Só com acesso local. |
```

**README.es.md (ES)**

```
| `GET /api/access` | Lo que puede usar esta conexión (`remote`, `local`, `permissions`, `messages`, `terminal`, `spawn`, `world`); la página lo usa para mostrar u ocultar funciones. |
| `GET /api/updates` y `POST /api/updates/check` | Comprobación de versión nueva: la versión en uso y el resultado; `check` ("Comprobar ahora") consulta GitHub (como máximo una vez cada 30 s). Se desactiva con `HABBLAUD_UPDATE_CHECK=0`. |
| `/api/world/*` | Habblaud World (cuenta, personaje y edificio); activado por defecto, `HABBLAUD_WORLD=0` lo desactiva. Solo con acceso local, nunca por el puerto del celular. |
| `POST /api/omp/events` | Eventos de la extensión de Habblaud en omp (sesión, herramienta, aprobación, latido). Solo con acceso local. |
| `POST /api/omp/settings` | `{"roomByProject": true \| false}`: Configuración › la sala sigue al proyecto, para los agentes de omp. Solo con acceso local. |
| `/api/asides…` y `POST /api/omp/aside` | Chat paralelo con el agente de omp: la página pregunta y sigue la respuesta; la extensión de omp envía el texto acumulado y recibe si debe cancelar. La ruta de la extensión es solo con acceso local. |
| `/api/spawn…` | Agente nuevo: disponibilidad, carpetas sugeridas, adjuntos, inicio y seguimiento de la solicitud. Solo con acceso local (desde el celular, con `spawn` en `HABBLAUD_REMOTE_ALLOW`). |
| `/api/dictation…` | Dictado (micrófono) en Agente nuevo y en la caja de mensajes: envía el audio y recoge el texto. Solo con acceso local (desde el celular, con `spawn` o `messages` en `HABBLAUD_REMOTE_ALLOW`). |
| `/api/deliveries…` | Entregas: el final de cada solicitud en la que trabajó un agente, con sus adjuntos. Solo con acceso local (desde el celular, con `terminal` en `HABBLAUD_REMOTE_ALLOW`). |
| `/api/pty…`, `POST /api/agents/:id/stop` y `/takeover` | Terminal interactivo y terminar o tomar una sesión; solo con `HABBLAUD_PTY=1`. Solo con acceso local, nunca por el puerto del celular. |
| `/api/host/*` | Auxiliar del host (`npm run codex:bridge`): recoge y confirma las solicitudes de Agente nuevo, del dictado y de los archivos de las entregas. Solo con acceso local. |
```

**README.fr.md (FR)**

```
| `GET /api/access` | Ce que cette connexion peut utiliser (`remote`, `local`, `permissions`, `messages`, `terminal`, `spawn`, `world`) ; la page s'en sert pour afficher ou masquer des fonctions. |
| `GET /api/updates` et `POST /api/updates/check` | Vérification de nouvelle version : la version utilisée et le résultat ; `check` (« Vérifier maintenant ») interroge GitHub (au plus une fois toutes les 30 s). Désactivée avec `HABBLAUD_UPDATE_CHECK=0`. |
| `/api/world/*` | Habblaud World (compte, personnage et immeuble) ; activé par défaut, `HABBLAUD_WORLD=0` le désactive. En accès local uniquement, jamais par le port du téléphone. |
| `POST /api/omp/events` | Événements de l'extension d'Habblaud dans omp (session, outil, validation, battement). En accès local uniquement. |
| `POST /api/omp/settings` | `{"roomByProject": true \| false}` : Réglages › la salle suit le projet, pour les agents omp. En accès local uniquement. |
| `/api/asides…` et `POST /api/omp/aside` | Discussion parallèle avec l'agent omp : la page pose la question et suit la réponse ; l'extension d'omp envoie le texte accumulé et reçoit s'il faut annuler. La route de l'extension est en accès local uniquement. |
| `/api/spawn…` | Nouvel agent : disponibilité, dossiers suggérés, pièces jointes, démarrage et suivi de la demande. En accès local uniquement (depuis le téléphone, avec `spawn` dans `HABBLAUD_REMOTE_ALLOW`). |
| `/api/dictation…` | Dictée (micro) dans Nouvel agent et dans la boîte de message : envoie l'audio et récupère le texte. En accès local uniquement (depuis le téléphone, avec `spawn` ou `messages` dans `HABBLAUD_REMOTE_ALLOW`). |
| `/api/deliveries…` | Livrables : la fin de chaque demande sur laquelle un agent a travaillé, avec ses pièces jointes. En accès local uniquement (depuis le téléphone, avec `terminal` dans `HABBLAUD_REMOTE_ALLOW`). |
| `/api/pty…`, `POST /api/agents/:id/stop` et `/takeover` | Terminal interactif, fin ou reprise d'une session ; seulement avec `HABBLAUD_PTY=1`. En accès local uniquement, jamais par le port du téléphone. |
| `/api/host/*` | Auxiliaire de l'hôte (`npm run codex:bridge`) : récupère et confirme les demandes de Nouvel agent, de la dictée et des fichiers des livrables. En accès local uniquement. |
```

(Cada linha é uma linha única de tabela; não quebre linhas. O `\|` dentro do JSON é o escape de pipe de tabela Markdown,
igual ao que já existe na linha de `POST /api/demo`.)

**Verify** (no worktree):
```
for f in README.md README.pt-BR.md README.es.md README.fr.md; do
  echo "$f: $(grep -c -E '^\| `(GET /api/access|GET /api/updates|/api/world/\*|POST /api/omp/events|POST /api/omp/settings|/api/asides|/api/spawn|/api/dictation|/api/deliveries|/api/pty|/api/host/\*)' $f)"
done
```
→ cada um dos quatro imprime `: 11`. E `git diff --stat` mostra só os quatro READMEs, ~11 linhas inseridas em cada, 0 removidas
(`git diff --numstat -- README.md README.pt-BR.md README.es.md README.fr.md` → `11	0	<arquivo>` para cada).

### Step 2: Acrescentar as duas variáveis de ambiente que faltam aos quatro READMEs

Em cada README, na tabela `| Variable | Default | What for |` (pt: `| Variável | Padrão | Para quê |`, es: `| Variable | Por defecto | Para qué |`, fr: `| Variable | Par défaut | À quoi ça sert |`):

(a) **logo depois** da linha ``| `HABBLAUD_DATA_DIR` ``, insira 1 linha (`HABBLAUD_ATTACHMENTS_DIR`);
(b) **logo depois** da linha ``| `HABBLAUD_OMP_PERMISSOES` ``, insira 1 linha (`HABBLAUD_OMP_MODELS`).

**README.md**
```
| `HABBLAUD_ATTACHMENTS_DIR` | `~/.habblaud/attachments` | Where the attachments of **New agent** are saved before the request is sent. |
| `HABBLAUD_OMP_MODELS` | all that omp lists | In **omp's** environment, not Habblaud's: comma-separated `provider/id` or `provider/*`; the agent drawer only lists and only switches to these models. |
```
**README.pt-BR.md**
```
| `HABBLAUD_ATTACHMENTS_DIR` | `~/.habblaud/attachments` | Onde ficam gravados os anexos do **Novo agente** antes de o pedido ser enviado. |
| `HABBLAUD_OMP_MODELS` | todos os que o omp lista | No ambiente **do omp**, não do Habblaud: `provedor/id` ou `provedor/*`, separados por vírgula; a gaveta do agente só lista e só troca para esses modelos. |
```
**README.es.md**
```
| `HABBLAUD_ATTACHMENTS_DIR` | `~/.habblaud/attachments` | Dónde se guardan los adjuntos de **Agente nuevo** antes de enviar la solicitud. |
| `HABBLAUD_OMP_MODELS` | todos los que lista omp | En el entorno **de omp**, no de Habblaud: `proveedor/id` o `proveedor/*`, separados por comas; el cajón del agente solo lista y solo cambia a esos modelos. |
```
**README.fr.md**
```
| `HABBLAUD_ATTACHMENTS_DIR` | `~/.habblaud/attachments` | Où sont enregistrées les pièces jointes de **Nouvel agent** avant l'envoi de la demande. |
| `HABBLAUD_OMP_MODELS` | tous ceux qu'omp liste | Dans l'environnement **d'omp**, pas celui de Habblaud : `fournisseur/id` ou `fournisseur/*`, séparés par des virgules ; le tiroir de l'agent ne liste et ne bascule que vers ces modèles. |
```

Antes de inserir, confirme no código as duas afirmações (leia `server/spawn/attachments.ts:109-111` e
`mod/habblaud-omp/habblaud.ts:38-42`); se o comportamento descrito diferir, STOP.

**Verify**:
```
for f in README.md README.pt-BR.md README.es.md README.fr.md; do
  echo "$f: $(grep -c -E '^\| `HABBLAUD_(ATTACHMENTS_DIR|OMP_MODELS)`' $f)"
done
```
→ `: 2` nos quatro. `git diff --numstat` acumulado: cada README `13	0`.

### Step 3: Completar a tabela `## API` do `server/README.md`

Em `server/README.md`:

(a) **Trocar** a linha 81 (`GET /api/health`) por (acrescenta `remote`, `pty`, `codexEvents`, `ompEvents`, `semMarca` e o `provider` das contas que não são do Claude Code, todos lidos de `server/http/app.ts:395-420`):

```
| `GET /api/health` | `{ok, version, demo, docker, remote, terminal, permissions, pty, messages, codexEvents, ompEvents, semMarca, updates:{state, latest, available}, sources, accounts:[{id, provider?, usageStatus}]}` (`terminal`, `permissions` e `messages` valem para a conexão que perguntou; `pty` nunca vale pela porta remota) |
```

(b) **Inserir logo depois** da linha 82 (`POST /api/demo`):

```
| `POST /api/rooms/rename` | `{id, name}`: renomeia a sala (nome vazio volta ao padrão, o nome da pasta); `200 {name}`, `400`, `404` (sala desconhecida), `403` (terminal desligado ou fora do acesso local); o nome fica em `rooms.json` na pasta de dados |
| `GET /api/access` | `AccessInfo`: `{remote, local, permissions, messages, terminal, spawn, world}`: o que esta conexão pode usar (a porta remota só vê o que `HABBLAUD_REMOTE_ALLOW` liberar) |
```

(c) **Inserir logo depois** da linha 94 (`POST /api/mod/inbox/ack`), nesta ordem:

```
| `POST /api/messages/control` | (página, só omp) `{agentId, model?, thinking?}`: troca o modelo e o raciocínio da sessão viva; mesmas respostas de `POST /api/messages` |
| `POST /api/codex/events` | (hook do Codex) `{account?, codexHome?, event}`: `200 {ok}`; só pelo computador (a porta remota recebe 403) |
| `POST /api/codex/bridge/poll` e `/ack` | (auxiliar `npm run codex:bridge`) busca as mensagens para o Codex e confirma cada entrega (`{results: [{id, ok, error?}]}`: `200 {ok: true}`); só pelo computador |
| `POST /api/omp/events` | (extensão do omp) `OmpEvent` (`shared/omp-events.ts`): `200 {ok}`; corpo fora do formato: `400`; só pelo computador |
| `POST /api/omp/settings` | `{roomByProject: boolean}`: Configurações › sala do omp segue o projeto; `200 {ok, roomByProject}`, `400`, `404` se desligado; só pelo computador |
| `GET /api/asides?agentId=` / `POST /api/asides` / `POST /api/asides/<topicId>/cancel` / `DELETE /api/asides/<topicId>?agentId=` | (página) bate-papo paralelo com o agente do omp; formatos e códigos em `asides/http.ts`; ler vale com a trava do terminal, perguntar com a das mensagens |
| `POST /api/omp/aside` | (extensão do omp) `AsideProgress`: o texto acumulado da resposta; `200 {cancel: boolean}` diz se a pergunta deve ser abortada; só pelo computador |
| `GET /api/spawn` / `POST /api/spawn` / `GET /api/spawn/:id` | Novo agente pelo escritório: `{available, mode}`; `201` com o pedido criado (`400`, `409`, `429` na recusa); situação do pedido. Com a trava `spawn` (`spawn/spawn.ts`) |
| `GET /api/spawn/folders` / `POST /api/spawn/attachments` | pastas sugeridas (projetos recentes) e anexos do pedido (`spawn/folders.ts`, `spawn/attachments.ts`); mesma trava |
| `GET /api/dictation` / `POST /api/dictation` / `GET /api/dictation/:id` | ditado: `{available}`; envia o áudio (JSON base64); busca o texto (`dictation/dictation.ts`); vale com a trava `spawn` ou `messages` |
| `GET /api/deliveries` / `GET /api/deliveries/file/:id` | entregas (`{deliveries}`) e o anexo de uma delas (`deliveries/deliveries.ts`); mesma trava do terminal |
| `GET /api/pty`, `POST /api/pty`, `GET /api/pty/:id/stream`, `POST /api/pty/:id/input` \| `resize` \| `close`, `POST /api/agents/:id/stop` \| `takeover` | terminal interativo e ações nos agentes (`http/pty.ts`); só com `HABBLAUD_PTY=1`, conexão pelo loopback e `Host` local; nunca pela porta remota |
| `POST /api/host/spawn/poll` \| `ack`, `/api/host/dictation/poll` \| `ack`, `/api/host/files/poll` \| `ack` | (auxiliar do host, `npm run codex:bridge`) busca e confirma os pedidos do Novo agente, do ditado e dos arquivos das entregas; só pelo computador |
```

Conferir antes de gravar cada afirmação nova contra o código (os números de linha de `server/http/app.ts` estão na
tabela "Current state"). Em particular: `POST /api/host/*` só aceita `POST` (linhas 632-636 mostram `method !== 'POST'`
para `files`; confira `dictation` na 652 e `spawn` na 672 antes de escrever `POST` em todas); se alguma aceitar outro
método, escreva só o que o código aceita.

**Verify**:
```
for k in 'POST /api/rooms/rename' 'GET /api/access' '/api/messages/control' '/api/codex/events' '/api/codex/bridge/poll' '/api/omp/events' '/api/omp/settings' '/api/asides' '/api/omp/aside' '/api/spawn' '/api/dictation' '/api/deliveries' '/api/pty' '/api/host/'; do
  printf '%-26s %s\n' "$k" "$(grep -c -E -- "^\| .*$k" server/README.md)"; done
```
→ todas as 14 linhas com contagem ≥ 1. E `grep -n 'codexEvents, ompEvents, semMarca' server/README.md` → 1 linha (a 81).

### Step 4: Corrigir a primeira frase do `AGENTS.md` e do `CLAUDE.md` (idênticos)

Nos **dois** arquivos, linhas 3-4, trocar:

```
O Habblaud é um escritório virtual em pixel art que mostra, em tempo real, o que os agentes do Claude Code e do Codex
estão fazendo: servidor Node sem dependências de runtime (`server/`), página no navegador (`client/`) e código comum
```

por:

```
O Habblaud é um escritório virtual em pixel art que mostra, em tempo real, o que os agentes do Claude Code, do Codex e
do omp estão fazendo: servidor Node sem dependências de runtime (`server/`), página no navegador (`client/`) e código comum
```

Só essa troca; o resto do arquivo (incluindo o aviso "Habblaud World: dados verdadeiros" e o "NOTICE") fica como está.
Para garantir cópias idênticas, edite o `AGENTS.md` e depois rode `cp AGENTS.md CLAUDE.md` (eram idênticos antes).

**Verify**: `cmp AGENTS.md CLAUDE.md && echo iguais` → `iguais`; `grep -c "do Codex e$" AGENTS.md` → `1`;
`grep -c "do omp estão fazendo" AGENTS.md CLAUDE.md` → `AGENTS.md:1` e `CLAUDE.md:1`; `git diff --numstat -- AGENTS.md CLAUDE.md` → `2	2` em cada.

### Step 5: Conferência final de sanidade

Rode os checks de "Done criteria" abaixo. Nenhum teste muda, mas `npm test` e `npm run typecheck` devem continuar verdes
(há testes de documentação, como `server/world/privacy-doc.test.ts`, e eles leem arquivos `.md`).

**Verify**: `npm run typecheck` → exit 0; `npm test` → todos passam (181 arquivos / 2180 testes, 2 skipped).

## Test plan

Sem testes novos: a mudança é só documentação e não há comportamento do app para provar. A verificação é a de
cobertura por `grep` dos passos 1-4. Sanidade: `npm test` (inclui os testes que leem `.md`, p.ex.
`server/world/privacy-doc.test.ts`) e `npm run typecheck` não podem quebrar.

## Done criteria

Todos devem valer:

- [ ] Cada um dos quatro READMEs tem as 11 linhas novas da tabela de rotas:
  `for f in README.md README.pt-BR.md README.es.md README.fr.md; do grep -c -E '^\| `(GET /api/access|GET /api/updates|/api/world/\*|POST /api/omp/events|POST /api/omp/settings|/api/asides|/api/spawn|/api/dictation|/api/deliveries|/api/pty|/api/host/\*)' $f; done` → `11` quatro vezes
- [ ] Cada README tem as duas variáveis: `grep -c -E '^\| `HABBLAUD_(ATTACHMENTS_DIR|OMP_MODELS)`' <arquivo>` → `2` nos quatro
- [ ] `server/README.md` cita `rooms/rename`, `/api/access`, `/api/messages/control`, `/api/codex/events`, `/api/omp/events`, `/api/omp/settings`, `/api/asides`, `/api/omp/aside`, `/api/spawn`, `/api/dictation`, `/api/deliveries`, `/api/pty`, `/api/host/` (contagem ≥ 1 cada, comando do passo 3)
- [ ] A linha `GET /api/health` do `server/README.md` lista `remote`, `pty`, `codexEvents`, `ompEvents`, `semMarca`
- [ ] `cmp AGENTS.md CLAUDE.md` sai sem diferença, e os dois contêm `do Codex e` seguido de `do omp estão fazendo` (passo 4)
- [ ] Nenhuma linha existente foi removida dos READMEs: `git diff --numstat -- README.md README.pt-BR.md README.es.md README.fr.md` → `13	0` em cada
- [ ] `git diff --numstat -- server/README.md` → `16	1` (15 linhas novas e a linha de health trocada)
- [ ] `git diff --name-only producao` lista **somente**: `AGENTS.md`, `CLAUDE.md`, `README.es.md`, `README.fr.md`, `README.md`, `README.pt-BR.md`, `server/README.md` (e `plans/README.md` se você atualizou o status)
- [ ] Nenhuma ocorrência nova de `HABBLAUD_CODEX_PERMISSION_TIMEOUT`, `HABBLAUD_REMOTE_USER`, `HABBLAUD_REMOTE_PASSWORD_HASH` nos documentos: `git diff producao | grep -c '^+.*HABBLAUD_\(CODEX_PERMISSION_TIMEOUT\|REMOTE_USER\|REMOTE_PASSWORD\)'` → `0`
- [ ] `npm run typecheck` exit 0; `npm test` verde; `npm run i18n:check -- --strict` exit 0
- [ ] `plans/README.md` com a linha do plano 022 atualizada (se não houver revisor mantendo o índice)

## STOP conditions

Stop and report back (do not improvise) if:

- O drift check mostra mudança nos arquivos do escopo e os trechos de "Current state" não batem (em especial se `server/http/app.ts` ganhou, perdeu ou mudou rotas: a lista autoritativa acima ficaria desatualizada).
- A linha-âncora (``| `/api/codex/bridge/poll` ``) não existir num dos READMEs, ou a tabela de rotas dos quatro não tiver as mesmas 20 linhas.
- Uma afirmação nova dos passos 1-3 (trava, método, resposta, padrão da variável) não bater com o código quando você conferir; escreva o que o código faz e relate a divergência, ou pare se não conseguir decidir.
- Algum README, no momento da execução, já tiver uma das rotas ou variáveis (o plano 006 ou outro commit pode ter mexido): não duplique, ajuste a lista e relate.
- `AGENTS.md` e `CLAUDE.md` já não forem idênticos ao começar (`cmp` com diferença) ou algum virou symlink: não sobrescreva um com o outro, relate.
- O passo exigir tocar num arquivo fora do escopo (código, `CHANGELOG.md`, `shared/locales/`, subtítulo dos READMEs).
- Uma verificação falhar duas vezes após correção razoável.

## Maintenance notes

- **Não há teste que prenda a documentação ao código.** A próxima rota nova em `server/http/app.ts` volta a defasar as tabelas. Quem adiciona rota deve atualizar `server/README.md` (referência completa) e, se for voltada a quem usa, os quatro READMEs. Um teste que compare as rotas do `app.ts` com a tabela foi considerado e deixado de fora: seria frágil (rotas por prefixo) e pediria decisão de formato do dono.
- **Mantenha `AGENTS.md` e `CLAUDE.md` idênticos.** São duas cópias, não um link; `shared/world/README.md:11` aponta para o `CLAUDE.md`. Tornar um deles symlink do outro seria uma decisão separada (Windows/Docker `COPY`).
- **Descobertas deixadas de fora de propósito** (relatar ao dono): (1) o subtítulo dos quatro READMEs (linha 8: "Claude Code (and Codex)"), `package.json` `description` e `client/index.html` (`meta description`, `og:description`: "agentes do Claude Code") também não citam o omp; é texto de marca/SEO, decisão do dono, e o de `client/index.html` entra no build. (2) `shared/demo/permission.ts:131` menciona `HABBLAUD_CODEX_PERMISSION_TIMEOUT` num comentário, mas nenhum código lê essa variável: o comentário está morto e deve ser corrigido num plano de código, não aqui. (3) `HABBLAUD_WORLD*` e `HABBLAUD_REMOTE_*` já estão documentados em seções próprias dos READMEs, por isso não entraram no passo 2.
- **O que um revisor deve olhar**: que cada linha nova nos quatro READMEs diz a mesma coisa (mudar um idioma sem os outros é a deriva que este plano corrige); que as travas descritas (só acesso local, `spawn`/`messages`/`terminal` no celular) batem com `trusted(req, ...)` em `server/http/app.ts`; que nenhuma frase do plano 006 foi alterada.
- **Adiado**: melhorar o texto das linhas já existentes da tabela (por exemplo, dizer que `/api/health` devolve também `remote` e `pty` nos READMEs) e a seção "The sources are local files…" (plano 006).
