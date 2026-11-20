# Plano 148: imagens que o agente mostra com `<img src="…">` aparecem no Zaplaud

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat e307472..HEAD -- server/sources/links.ts server/sources/terminal.ts server/sources/omp/terminal.ts server/sources/codex/terminal.ts`
> Se algum mudou desde `e307472`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: S
- **Risco**: MED (texto do agente vira Markdown que os clientes interpretam: injeção de Markdown é o risco)
- **Depende de**: 147 (mesclado)
- **Categoria**: bug
- **Planejado em**: commit `e307472`, 2026-11-20 (foto do dono: tag `<img>` aparecendo como texto no desktop)

## Por que importa

O omp instrui o agente a mostrar uma imagem existente com uma tag HTML **sozinha na linha**: `<img src="…" alt="…">`
(opcionalmente `width`/`height`), porque o terminal do omp desenha essa tag. Os clientes Zaplaud só desenham imagem
Markdown (`![alt](destino)`); a tag aparece crua, com o caminho visível, e o dono não vê a arte. Caso real de
2026-11-20: `<img src="pasta-do-projeto/arquivo.png" alt="texto com aspas e espaços">`, com `src` relativo à pasta da
sessão.

## Estado atual

- `server/sources/links.ts` (plano 147): `localLinks(markdown, cwd)` (`:172`) reescreve destinos **relativos** de
  links e imagens Markdown para caminho absoluto quando o arquivo existe e `locate()` o aceitaria; ignora código
  cercado, indentado e inline; deixa intacto qualquer caso duvidoso. Chamado na montagem das entradas `assistant`:
  `server/sources/terminal.ts:676` (Claude), `server/sources/omp/terminal.ts:219` (omp),
  `server/sources/codex/terminal.ts:154` (Codex). Leia o arquivo inteiro antes de começar: as regras de segurança
  (`relativeTarget`, `UNSAFE`, `rewrite`) são a base deste plano.
- Clientes: imagem Markdown com caminho absoluto de host é carregada por `/api/files/raw`; `http(s)` vira imagem
  remota que só carrega depois do toque; qualquer outro destino fica como link (Kotlin `MarkdownLinks.kt:41-48`,
  Rust `markdown/images.rs:9-37`). Esses comportamentos não mudam.

## Decisão de desenho

No mesmo passo de saída de `localLinks`, uma linha que contém **apenas** uma tag `<img …>` (espaços em volta
permitidos; fora de código) vira uma imagem Markdown `![alt](destino)` **construída** pelo servidor, não copiada:

- `src` é decodificado de entidades HTML (`&amp;`, `&quot;`, `&#39;`, `&lt;`, `&gt;`, numéricas) e então validado:
  - relativo → exatamente as regras de `rewrite` do 147 (sem esquema, sem `/ # ? ~ //` inicial, sem caracteres de
    `UNSAFE`, `%`-decodificação válida, arquivo existe, `locate()` aceita) → caminho absoluto;
  - absoluto de host → só se existir e `locate()` aceitar;
  - `http://`/`https://` → só se for uma URL válida com host e sem credenciais (a mesma política dos clientes:
    Kotlin `isHttpMarkdownLink`; replique a regra em TypeScript com teste) — continua imagem remota, carregada só
    depois do toque nos clientes;
  - `data:image/…;base64,…` → aceite só se couber no limite de imagem dos clientes (veja `IMAGE_MAX_BYTES`/
    `MAX_IMAGE_BYTES`) e for um dos tipos de imagem conhecidos;
  - qualquer outro esquema, vazio, malformado ou recusado → **a linha fica como veio** (texto).
- `alt`: decodificado de entidades, espaços e quebras colapsados para um espaço, cortado em 200 caracteres, e
  **escapado para Markdown** (`\`, `[`, `]`, `*`, `_`, `` ` ``, `<`, `>`, `!`, `(`, `)`): não pode abrir link, imagem,
  ênfase, código nem HTML. Sem `alt` → `imagem`.
- Destino escrito como `<abs>` quando contém espaço, parêntese ou qualquer caractere que exija; se contiver `<`, `>`
  ou quebra de linha, recusa (linha fica como veio).
- `width`/`height`/`title` e outros atributos são descartados. Atributos duplicados, atributo sem aspas com espaço,
  aspas desbalanceadas, ou qualquer HTML além de uma única tag `<img>` na linha → linha fica como veio.
- `<iframe>` e outras tags: fora de escopo, ficam como vieram.

## Convenções do repositório (valem para este plano)

- TypeScript com Vitest; `npm ci`; **`npm run typecheck`** e `npm test` em todo passo. Sem CI: prova local.
- Funções puras testáveis; nenhuma dependência nova (sem parser HTML de terceiros: o formato aceito é estreito).
- `CHANGELOG.md` ("Não lançado").

## Escopo

**Dentro:** `server/sources/links.ts` e `server/sources/links.test.ts`; `CHANGELOG.md`.

**Fora:** clientes (nenhuma mudança), página web, transcript gravado, `<iframe>`, tags no meio de uma frase.

## Passos

### Passo 1: testes primeiro

Em `links.test.ts`, com arquivos fictícios em diretório temporário dentro de uma raiz de teste:

- `<img src="sub/a.png" alt="Ânfora">` sozinha → `![Ânfora](/abs/sub/a.png)`; com espaços antes e depois na linha → idem.
- `src` com espaço e parênteses no nome → destino entre `<…>` e a imagem continua única.
- `alt` com `]`, `[`, `(`, `)`, `*`, `` ` ``, `<b>`, `![x](y)`, quebra de linha e entidades (`&quot;`, `&#91;`) →
  uma única imagem; o `alt` escapado não gera link, imagem, ênfase nem HTML (confira passando o resultado pelo
  mesmo leitor Markdown que a página usa, ou por `marked`/a lib já presente no repositório, contando nós de imagem
  e link: exatamente 1 imagem, 0 links).
- `src` com `&amp;` decodificado corretamente; `src` com `javascript:`, `file:`, `//host`, `~`, `..` saindo da
  raiz, arquivo inexistente, vazio → linha inalterada.
- `https://host/x.png` → `![alt](https://host/x.png)`; `https://user:pass@host/x.png` → inalterada.
- `data:image/png;base64,…` dentro do limite → aceita; acima → inalterada.
- Duas tags na mesma linha; tag no meio de uma frase; `<img>` dentro de bloco de código cercado, indentado e inline
  → inalterados. Aspas simples nos atributos funcionam; atributo duplicado → inalterada.
- Sem `cwd` → `src` relativo inalterado, mas `https` ainda convertido.

**Verificação**: `npx vitest run server/sources/links.test.ts` — os casos novos falham antes da implementação.

### Passo 2: implementar

No mesmo percurso por linhas de `resolveLocalLinks` (reaproveite o rastreio de cercas e o mascaramento de código
inline), trate a linha-só-`<img>` antes da reescrita de links. Reaproveite `rewrite`/`relativeTarget`; não duplique as
regras de caminho.

**Verificação**: `npm run typecheck` sai com 0; `npx vitest run server/sources` passa; `npm test` passa.

### Passo 3: prova com a fixture e documentação

Mensagem fictícia na fixture (`server/test/android-fixture.ts`) com uma linha `<img src="…">` relativa a um arquivo
fictício (mesmo mecanismo de `--files` do 147); confirme pelo stream de terminal da fixture que a entrada chega como
`![…](/abs/…)`. `CHANGELOG.md`: "Imagens que o agente mostra com `<img src=\"…\">` aparecem no Zaplaud como imagem."

**Verificação**: `npm run i18n:check -- --strict`, `npm run typecheck` e `npm test` saem com 0.

## Critérios de pronto

- [ ] Linha-só-`<img>` válida vira uma única imagem Markdown construída, com `alt` escapado
- [ ] `src` passa pelas mesmas regras do 147 (local) e pela política `http(s)` dos clientes; o resto fica intacto
- [ ] Nenhum caso de teste gera link, imagem extra, ênfase ou HTML a partir do `alt` ou do `src`
- [ ] typecheck e `npm test` ok

## Condições de PARADA

- Precisar mudar algum cliente.
- O formato real das tags do omp exigir aceitar HTML além de uma tag `<img>` isolada.

## Notas de manutenção

- Depois do merge: publicar o servidor. Clientes não precisam ser reinstalados.
- Se o omp passar a usar `<img>` no meio de frases, revisar o escopo.
