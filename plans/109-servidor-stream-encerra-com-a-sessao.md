# Plano 109: Servidor: streams remotos fecham quando a sessão é encerrada ou vence, e quem só pode escrever no bate-papo paralelo não recebe o histórico

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. Ao terminar, atualize
> a linha 109 em `plans/README.md` (a menos que um revisor tenha dito que ele cuida do índice).
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 7236378..HEAD -- server/http/remote.ts server/http/sse.ts server/http/app.ts server/asides/http.ts`
> Se algum arquivo do escopo mudou desde `7236378`, compare os trechos de "Estado atual" com o código vivo; se não
> baterem, é condição de PARADA.

## Status

- **Prioridade**: P3
- **Esforço**: M
- **Risco**: MED (fechar stream demais derruba clientes legítimos; os streams locais não podem ser afetados)
- **Depende de**: nenhum
- **Categoria**: security
- **Planejado em**: commit `7236378`, 2026-11-18 (auditoria deep do Zaplaud Kotlin)

## Por que importa

Dois pontos do servidor que o Zaplaud consome, achados por leitura (não explorados):

1. O login remoto é conferido **quando a requisição chega**. Um stream (`/api/stream` ou o terminal de uma
   conversa) aberto antes continua recebendo o escritório inteiro depois de o usuário tocar em "Sair" ou de o
   token vencer. O "Sair" existe justamente para cortar um aparelho perdido ou uma sessão copiada; hoje ele não
   corta o que já está aberto.
2. No bate-papo paralelo, ler exige a permissão `terminal` e perguntar exige `messages`. Mas a resposta do
   `POST /api/asides` devolve o **tópico inteiro**, com perguntas e respostas anteriores. Uma conexão que só tem
   `messages` e conhece o id de um tópico lê o histórico por esse caminho. Os ids são aleatórios e por sessão,
   então o alcance é pequeno; ainda assim a regra "ler é `terminal`" é contornada.

## Estado atual

`server/http/remote.ts`:

```ts
// :147-188  RemoteSessions
issue(): { token: string; maxAgeS: number } { const exp = this.now() + SESSION_MS; ... }
verify(token: string | undefined): boolean { ... !this.revoked.has(parsed.sig) }
revoke(token: string | undefined): void { ...; this.revoked.set(parsed.sig, parsed.exp); this.saveRevoked(); }
private parse(token): { exp: number; sig: string } | undefined
// :570   conferência por requisição
const authed = deps.sessions.verify(readCookie(req.headers.cookie, COOKIE));
// :621-627  logout
deps.sessions.revoke(readCookie(req.headers.cookie, COOKIE));
```

`server/http/sse.ts:83-98` (`SseHub.attach`): registra o cliente e remove em `close`/`error`; nada liga o cliente
à sessão. `server/http/app.ts:439-441` chama `hub.attach(req, res)`; `:523` chama `terminals.attach(req, res, id)`
para o stream de uma conversa.

`server/http/app.ts:656-662`:

```ts
if (path === '/api/asides' || path.startsWith('/api/asides/')) {
  // Perguntar manda texto ao modelo (a trava das mensagens); ler mostra a conversa (a trava do terminal).
  if (!deps.asides) sendJson(res, 403, { error: MESSAGES_OFF() });
  else if (!trusted(req, isRead ? 'terminal' : 'messages')) { ...403... }
  else deps.asides.page(req, res, path, url);
```

`server/asides/http.ts:38-40`: `const r = store.ask(await readJson(req)); if ('topic' in r) return sendJson(res, 201, { topic: r.topic });`
e `server/asides/store.ts:150`: `return { topic: structuredClone(topic) };` (o tópico com todos os turnos).

Clientes que leem essa resposta: a página (`client/src/net/store.ts`), o Zaplaud Kotlin
(`android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/data/AsideController.kt:92-97`, que põe o tópico
devolvido no estado) e o Zaplaud Rust.

Convenções do servidor: sem dependências de runtime; textos por `tr('...')`; testes em Vitest ao lado do código
(`npm test`); `npm run typecheck`.

## Convenções do repositório (valem para este plano)

- **Onde executar**: numa worktree temporária, nunca em `~/apps/habblaud` (produção):
  `git -C ~/Desenvolvimento/habblaud worktree add /tmp/habblaud-109 -b advisor/109-servidor-stream-encerra-com-a-sessao producao`. Remova ao terminar.
- **Commits**: um por passo lógico, no estilo do `git log` (`docs(planos): ...`, `fix(server): ...`). Sem push,
  merge ou PR sem ordem do operador.
- Texto visível do servidor passa por `tr('texto em PT')`, com traduções em `shared/locales/` (`npm run i18n:check`).
- **CHANGELOG**: mudança visível ganha uma linha em "Não lançado".
- Emulador, simulador e aparelhos do dono só com autorização expressa dele.

## Comandos

| Objetivo | Comando (na raiz da worktree) | Esperado |
|---|---|---|
| Tipos | `npm run typecheck` | sai com 0 |
| Testes do servidor | `npm test` | todos passam |
| Traduções | `npm run i18n:check -- --strict` | sai com 0 |

## Escopo

**Pode modificar**:
- `server/http/remote.ts`, `server/http/sse.ts`, `server/http/app.ts`
- o arquivo que implementa `terminals.attach` (ache com `grep -rn 'class TerminalStreams' server/`)
- `server/asides/http.ts`
- testes Vitest correspondentes (ao lado de cada arquivo; ache os existentes com `ls server/http/*.test.ts server/asides/*.test.ts`)
- `CHANGELOG.md`

**Fora do escopo** (não toque, mesmo parecendo relacionado):
- Os clientes (página, Kotlin, Rust): a resposta do `POST /api/asides` só muda para quem **não** tem `terminal`;
  os clientes atuais sempre têm as duas permissões quando mostram o bate-papo.
- `shared/world/` e `server/world/` (regras do Habblaud World; ver aviso no `AGENTS.md`).
- Streams locais (mesmo computador, sem login remoto): não podem ser fechados nem ganhar prazo.
- Abertura de arquivo por descritor em `server/files/` (achado separado, esforço L, não planejado).

## Passos

### Passo 1: a sessão avisa quando termina

Em `RemoteSessions`, acrescente um registro de interessados por assinatura do token:

```ts
/** Chama `end` quando este token for encerrado ("Sair") ou vencer; devolve como cancelar. Token inválido: chama já. */
watch(token: string | undefined, end: () => void): () => void
```

Implementação: `parse(token)`; se inválido ou já revogado, chame `end()` e devolva uma função vazia. Senão,
guarde `end` num `Map<string, Set<() => void>>` pela assinatura e arme um `setTimeout` para `parsed.exp - now`
(com `unref()`), que chama `end`. `revoke()` chama e remove todos os interessados daquela assinatura. A função
devolvida remove o interessado e cancela o temporizador.

Teste Vitest (use `vi.useFakeTimers()` e o relógio injetável `now` do construtor): `revoke` chama `end` uma
vez; o vencimento chama `end`; cancelar impede a chamada.

**Verificação**: `npx vitest run server/http/remote` → passa.

### Passo 2: streams remotos se ligam à sessão

No ponto em que a requisição remota autenticada é marcada como remota (em `createRemoteGate`, depois de
`authed`), guarde o token na requisição de um modo que `app.ts` alcance: veja como `isRemote(req)` descobre que
a requisição é remota e use o mesmo mecanismo (um `WeakMap<IncomingMessage, string>` exportado ao lado, por
exemplo `remoteTokenOf(req)`).

Em `app.ts`, nas duas rotas de stream (`/api/stream` e o terminal), depois do `attach`: se
`remoteTokenOf(req)` existir, `const stop = deps.remoteSessions.watch(token, () => res.destroy())` e
`res.on('close', stop)`. Requisição local não tem token e não é afetada. `deps.remoteSessions` é a instância
de `RemoteSessions` já criada na subida do servidor (`server/index.ts`); passe-a nas dependências do app como
opcional.

Teste Vitest: suba o app de teste como os testes existentes de `server/http` fazem, abra `/api/stream` com um
cookie válido, chame o logout com o mesmo cookie e afirme que a conexão do stream fecha; uma conexão local
aberta ao mesmo tempo continua aberta; uma nova conexão com o cookie antigo recebe 401.

**Verificação**: `npx vitest run server/http` → passa.

### Passo 3: bate-papo paralelo: quem só escreve recebe só o que escreveu

Em `app.ts`, ao despachar o `POST /api/asides`, informe às rotas se a conexão pode ler
(`trusted(req, 'terminal')`). Em `server/asides/http.ts`, quando **não** pode, responda `201` com o tópico
reduzido ao turno recém-criado: `{ topic: { ...r.topic, turns: r.topic.turns.slice(-1) } }`. Quando pode,
a resposta fica idêntica à de hoje.

Teste Vitest ao lado dos testes de `server/asides`: com permissão só de `messages`, perguntar num tópico que
já tem dois turnos devolve um tópico com um turno (o novo); com `terminal`, devolve os três.

**Verificação**: `npx vitest run server/asides` → passa.

### Passo 4: suíte inteira e registro

`npm run typecheck` → sai com 0. `npm test` → todos passam. `npm run i18n:check -- --strict` → sai com 0 (se
algum texto novo com `tr(...)` foi criado, acrescente as traduções em `shared/locales/`).

`CHANGELOG.md` ("Não lançado"): "Acesso remoto: 'Sair' e o vencimento da sessão fecham na hora os streams já
abertos".

## Plano de testes

Os três grupos de teste Vitest descritos nos passos 1 a 3, modelados nos testes existentes de cada pasta.

## Critérios de pronto

Todos precisam valer:

- [ ] `grep -n 'watch(' server/http/remote.ts` → método presente em `RemoteSessions`
- [ ] Teste: logout fecha o stream remoto aberto e não fecha o local
- [ ] Teste: `POST /api/asides` sem `terminal` devolve só o turno novo
- [ ] `npm run typecheck`, `npm test` e `npm run i18n:check -- --strict` saem com 0
- [ ] `CHANGELOG.md` atualizado
- [ ] `git status` não mostra arquivo fora do escopo modificado
- [ ] Linha 109 de `plans/README.md` atualizada

## Condições de PARADA

Pare e relate (não improvise) se:

- O código nos locais de "Estado atual" não bater com os trechos (o repositório mudou desde `7236378`).
- Uma verificação falhar duas vezes depois de uma tentativa razoável de correção.
- A correção exigir tocar arquivo fora do escopo.
- Não existir um modo de `app.ts` saber qual token autenticou a requisição sem refatorar o portão remoto além
  de um `WeakMap`: relate o desenho antes de mexer.
- O Zaplaud Kotlin ou a página dependerem do tópico completo na resposta do POST **sem** ter `terminal` (procure
  usos): se sim, o passo 3 quebraria o cliente; relate.
- Qualquer teste existente de `server/http` ou `server/asides` falhar.

## Notas de manutenção

- Depois de publicado, o Zaplaud vai ver o stream cair no "Sair" de outro aparelho e reconectar com 401, o que
  já leva à tela de login (comportamento existente).
- Este plano vale para o servidor do fork; publicar em produção é passo do operador (`omp-tenant release`),
  nunca do executor.
- O revisor deve conferir que nenhum caminho chama `res.destroy()` em stream local.
