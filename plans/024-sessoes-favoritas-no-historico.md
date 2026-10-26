# Plan 024: Estrela para favoritar sessões no Histórico, fixadas no topo

## Status

- Escrito em 2026-10-26 contra o commit `176f867` (variante `plan`). Antes de começar: `git diff 176f867 -- client/src/ui/history.ts client/src/ui/history.test.ts` deve sair vazio; se não, releia os trechos abaixo e confira se ainda batem (se não baterem, STOP).

## Why this matters

O Histórico de sessões (popover do botão da barra superior, `client/src/ui/history.ts`) lista só as sessões dos
últimos 7 dias, por dia. Sessões importantes somem da vista e da lista depois de 7 dias. O dono quer uma estrela
em cada linha: a sessão favoritada fica numa seção "Favoritas" no topo do popover e continua lá depois dos 7 dias.

## Decisões (já tomadas, não reabrir)

1. **Só cliente.** Favoritas ficam no `localStorage` do navegador, chave `habblaud.history.favorites`, como as
   outras preferências locais (`client/src/ui/agentorder.ts` usa `habblaud.tabs.order`). Nada muda no servidor.
2. **Guarda o retrato da sessão** (o objeto `RecentSession` inteiro), não só o id. Assim uma favorita com mais de 7
   dias, que o servidor já não lista, continua aparecendo com título, projeto e conta.
   - Abrir uma sessão antiga funciona: `GET /api/sessions/:conta/:id/terminal` usa `HistorySet.resolve`, que não
     olha idade (ex.: `server/sources/history.ts:142` só procura o arquivo). A janela de 7 dias só existe no `list()`.
3. Quando o servidor devolve a sessão, a versão do servidor vence (título, `lastAt`, `open`, `agentId` atuais) e o
   retrato salvo é atualizado. Quando não devolve, usa o retrato com `open: false` e sem `agentId`.
4. Sessões favoritas aparecem **só** na seção "Favoritas" (não repetem no grupo do dia). Ordem: `lastAt` decrescente.
5. A busca e o modo "Retomar" (`pick`, filtro `canResume`) valem também para as favoritas.
6. Limite: 100 favoritas (descarta as de `lastAt` mais antigo ao passar). `shortcut: sem sincronizar entre navegadores; mover para o servidor se o dono pedir`.

## Current state

`client/src/ui/history.ts` (~420 linhas): funções puras no topo (`parseRecentSessions`, `filterSessions`,
`groupSessionsByDay`, `canResume`), testadas em `client/src/ui/history.test.ts`. A classe `HistoryPopover`:

```ts
// renderList(), ~linha 303
const pool = pick ? this.sessions.filter((s) => canResume(s, pick.sessionId)) : this.sessions;
...
const shown = filterSessions(pool, this.query, (id) => this.accountLabel(id));
const days = groupSessionsByDay(shown, now);
...
const sig = JSON.stringify([pick?.id, days.map((d) => [d.key, d.label]), shown.map((s) => [s.account, s.sessionId, s.title, s.lastAt, s.open, s.agentId && !!this.ctx.agent(s.agentId)])]);
if (sig === this.drawn) return;
...
for (const day of days) {
  const group = h('section', { class: 'ui-hist__day', attrs: { 'aria-label': day.label } }, h('h3', { class: 'ui-hist__day-label', text: day.label }));
  for (const s of day.sessions) group.append(this.row(s, now, pick?.id));
  frag.append(group);
}
```

`row()` devolve o botão `.ui-hist__item` sozinho ou um `div.ui-hist__row` com o botão e "Retomar em…". O
`data-session` do botão é `${account}:${sessionId}`. Teclas (setas) navegam entre `.ui-hist__item`.

Padrão de `localStorage` a copiar: `client/src/ui/agentorder.ts:30-55` (`JSON.parse` em try/catch, valida a forma,
`setItem` em try/catch ou sem quebrar se falhar). Ícones em `client/src/ui/icons.ts` (`ICONS.*`, SVG em string);
botão de ícone: `iconButton(svg, label, onClick, extraClass)` de `./dom`. Textos sempre via `tr('…')` de
`shared/i18n`, com tradução nos arquivos de idioma (o CI roda `npm run i18n:check --strict` se existir; rode-o).

## Commands you will need

- `npm run typecheck` (esperado: sem erros)
- `npx vitest run client/src/ui/history.test.ts`
- `npm test` (tudo verde)
- `npm run i18n:check -- --strict` se o script existir em `package.json`

## Scope

- Em escopo: `client/src/ui/history.ts`, `client/src/ui/history.test.ts`, `client/src/ui/icons.ts` (ícone de
  estrela, se não houver), o CSS do histórico (procure `ui-hist__` em `client/src/**/*.css`), os arquivos de
  tradução onde `tr()` exige, `CHANGELOG.md` (entrada curta).
- Fora: qualquer coisa em `server/`, `shared/types.ts`, outras telas.

## Git workflow

Branch `advisor/024-sessoes-favoritas`, commits pequenos em português, sem push nem merge.

## Steps

### Step 1: Modelo puro em `history.ts`

Exporte, perto de `filterSessions`:

```ts
export const FAVORITES_KEY = 'habblaud.history.favorites';
export const MAX_FAVORITES = 100;
export function sessionKey(s: Pick<RecentSession, 'account' | 'sessionId'>): string; // `${account}:${sessionId}`
/** Retratos válidos do JSON salvo (reusa parseRecentSessions({ sessions: raw })), sem duplicados. */
export function parseFavorites(raw: unknown): RecentSession[];
/** Junta favoritas com a lista do servidor: { favorites, rest, updated }.
 *  favorites: versão do servidor quando existe, senão o retrato com open=false e sem agentId; lastAt decrescente.
 *  rest: sessões do servidor que não são favoritas. updated: retratos novos para salvar (MAX_FAVORITES). */
export function splitFavorites(saved: readonly RecentSession[], sessions: readonly RecentSession[]): { favorites: RecentSession[]; rest: RecentSession[]; updated: RecentSession[] };
/** Liga/desliga: devolve a nova lista salva (adiciona o retrato com open=false, sem agentId; corta em MAX_FAVORITES pelos lastAt mais antigos). */
export function toggleFavorite(saved: readonly RecentSession[], s: RecentSession): RecentSession[];
```

Verificação: `npm run typecheck`.

### Step 2: Testes em `history.test.ts`

Siga o estilo dos testes existentes no mesmo arquivo. Casos: `parseFavorites` ignora lixo (`null`, string,
itens sem `sessionId`) e duplicados; `splitFavorites` usa a versão do servidor quando presente, mantém retrato
antigo ausente do servidor com `open:false`, tira favoritas de `rest`, ordena; `toggleFavorite` adiciona, remove
e respeita `MAX_FAVORITES`. Verificação: `npx vitest run client/src/ui/history.test.ts` verde.

### Step 3: Popover

Em `HistoryPopover`:
- Campo `favorites: RecentSession[]` carregado do `localStorage` no construtor (try/catch → `[]`); método
  `saveFavorites()` com try/catch.
- Em `renderList()`: `const { favorites, rest, updated } = splitFavorites(this.favorites, this.sessions)`; se
  `this.state === 'ready'` e `updated` difere (compare por JSON), salve. Aplique `pick`/`canResume` e
  `filterSessions` a `favorites` e a `rest` separadamente; `days` vem de `rest`. Se houver favoritas visíveis,
  monte antes dos dias uma `section.ui-hist__day.ui-hist__favs` com `h3` `tr('Favoritas')`. Mostre as favoritas
  mesmo enquanto carrega ou com erro (vêm do disco local); o texto "Nenhuma sessão nos últimos 7 dias" só quando
  também não há favoritas. Contagem do rodapé: inclua as favoritas.
- Inclua na assinatura `sig` a lista de chaves favoritas, para redesenhar ao favoritar.
- Em cada linha (`row()`), sempre embrulhe num `div.ui-hist__row` e acrescente um botão estrela
  `ui-hist__fav` (fora do botão `.ui-hist__item`, nunca aninhado), com `aria-pressed`, rótulo
  `tr('Favoritar sessão')` / `tr('Tirar dos favoritos')`. Clique: `toggleFavorite`, salva, `renderList()`, e
  devolve o foco à estrela da mesma sessão. Não pode abrir a sessão nem fechar o popover (use
  `stopPropagation` se o fechamento por clique fora reagir).
- Linha de favorita com sessão antiga: no `item()`, o horário mostra só `formatClock`; para favoritas de outro dia
  use o rótulo do dia (`dayLabel(s.lastAt, now)`) + hora, para não parecer de hoje.
- Ícone: estrela vazia/cheia em `icons.ts` (`ICONS.star`, `ICONS.starFilled`), mesmo estilo de SVG dos vizinhos
  (`currentColor`).
- CSS: estrela discreta (aparece ao passar o mouse/foco na linha; sempre visível quando marcada), cor de destaque
  quando marcada, alinhada à direita da linha sem quebrar o "Retomar em…".

Verificação: `npm run typecheck`, `npm test`.

### Step 4: Traduções, changelog

Traduza os textos novos em todos os idiomas que o projeto tem (procure onde fica, por ex., `'Retomar em…'`).
Entrada curta no `CHANGELOG.md` na seção de não lançados. Rode `npm run i18n:check -- --strict` se existir.

### Step 5: Prova de fumaça

Se houver jeito de subir o cliente localmente (veja `README.md`/`CONTRIBUTING.md`, ex.: `npm run dev`), abra o
popover, favorite uma sessão, recarregue a página: ela continua em "Favoritas". Se o histórico exigir terminal/acesso
local que não dá para obter, registre isso no relatório em vez de inventar.

## Test plan

Os casos do Step 2. Nada de teste de DOM novo se o arquivo não tiver padrão para isso.

## Done criteria

- `npm run typecheck` sem erros; `npm test` verde; `i18n:check --strict` (se existir) verde.
- `git diff --stat 176f867` só toca arquivos do Scope.
- `grep -n "FAVORITES_KEY" client/src/ui/history.ts` acha a chave `habblaud.history.favorites`.

## STOP conditions

- O `resolve` de alguma ferramenta (Claude, Codex, omp em `server/sources/**/history.ts`) recusa sessão por idade:
  pare e relate (a decisão 2 cai).
- Precisar mudar `server/` ou `shared/types.ts`.
- `history.ts` mudou desde `176f867` e os trechos não batem.

## Maintenance notes

- Quem mudar `RecentSession` (`shared/types.ts`) precisa lembrar que retratos antigos ficam no `localStorage`:
  `parseFavorites` passa por `parseRecentSessions`, que descarta o que não reconhece.
- Favoritas não sincronizam entre navegadores/aparelhos (decisão 1).
