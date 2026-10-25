# Plano 013 (spike) — Resultado: móveis e props do lado de fora desenhados em lote

> Executado em 2026-10-25 no worktree `/tmp/hb-013`, branch descartável `spike/013-lote`, a partir de `31368cb`
> (30 qps). Nada foi enviado (`push`) nem tocou a `producao` nem `~/apps/habblaud`. O relatório vai para
> `plans/013-resultado.md` por decisão do revisor.

## Resumo

- O limite superior se confirmou: tirar móveis **e** props do quadro baixa a CPU em **−22,2** e **−18,8 pontos**
  (duas séries). Passa do critério do passo 2 (≥ 15).
- Nenhuma variante sozinha chega perto do limite superior. **B (props em camadas)** dá −6,5 e −7,4 (abaixo dos 8
  pontos: ruído). **C (móveis em camada)** dá −9,6 e −8,2 (passa raspando). **A (atlas)** dá −11,0 e −7,2:
  inconsistente, e o script de teste nem é entregável (sprite repintado fica velho).
- **B + C juntas** (medida extra, fora do plano) dá **−14,1** com 5 pares totalmente separados (o pior BC, 60,8%,
  fica abaixo do melhor base, 66,5%). É ≈ 70% do limite superior.
- **Recomendação**: implementar **B + C** como um plano só (015), com C na versão de **camada única do prédio**
  (não por área). Ganho esperado: **≈ −14 pontos a 30 quadros/s** e **≈ −4,5 pontos a 10 quadros/s** (um terço, porque
  o custo é por quadro). O ganho a 10 qps é pequeno; ver "Recomendação" para o que isso significa na decisão.

## Método

- Máquina: Intel UHD 630, Linux, Chromium do sistema, 1440×900 em densidade 2, GPU de verdade (nenhuma saída
  `GPU indisponível`), `/tmp/habblaud-cpu.mjs` = script do plano 010 + linha `ATLAS`. Escritório real em
  `127.0.0.1:4747`, com agentes.
- Cada variante foi compilada num `outDir` próprio e servida em paralelo com a base (`vite preview`, portas
  4790/4791/4793/4794/4795). 5 pares alternados (base, variante, …) de 20 s, `quadros/s` ≈ 30,0 em todas as
  rodadas (faixa 29,5–30,6).
- Typecheck (`npm run typecheck`) → exit 0 em todos os protótipos; `npm test` → exit 0 (176 arquivos, 2108 testes
  passaram, 2 ignorados) rodado em B e em C. **BC**: só typecheck; `npm test` não foi rodado nessa combinação.
- **Ruído grande e de fora**: a máquina é compartilhada com outros agentes. A linha de base foi de 37% a 104% em
  rodadas diferentes; numa série (B, execução 3) ela caiu de ~81% para ~37% no meio da série, sem mudança minha.
  Por isso há duas séries completas e as diferenças são sempre **dentro do par/série**. Valores absolutos entre
  séries não são comparáveis.

## Valores brutos (CPU %, todas as rodadas, ordem de execução)

Mediana entre colchetes. "Dif" = mediana variante − mediana base na mesma série.

### Série 1 (a oficial do plano, passos 1 a 4)

| Variante | Base (5 rodadas) | Variante (5 rodadas) | Dif |
|---|---|---|---|
| A (atlas) | 84,9 · 103,6 · 85,9 · 81,8 · 73,0 [84,9] | 73,9 · 106,6 · 61,2 · 72,4 · 76,6 [73,9] | **−11,0** |
| Limite superior | 80,6 · 78,7 · 80,5 · 87,0 · 89,7 [80,6] | 55,9 · 58,4 · 55,5 · 92,7 · 62,5 [58,4] | **−22,2** |
| C (móveis em camada) | 79,8 · 79,0 · 74,8 · 76,7 · 80,2 [79,0] | 67,2 · 71,8 · 69,4 · 72,5 · 68,7 [69,4] | **−9,6** |

### Variante B (props em camadas) — três tentativas, uma descartada

| Execução | Base | B | Dif | Situação |
|---|---|---|---|---|
| 1 | 86,4 · 87,5 · 81,4 · 82,3 · 86,1 [86,1] | 61,9 · 70,2 · 63,5 · 60,8 · 63,0 [63,0] | (−23,1) | **DESCARTADA**: por erro meu (um `git revert` falhou), o build "B" ainda continha o limite superior (sem móveis e sem props). A captura mostrou escritório sem móveis. |
| 2 | 54,7 · 48,8 · 83,2 · 92,1 · 86,7 [83,2] | 45,0 · 80,7 · 75,8 · 105,8 · 72,7 [75,8] | −7,4 | Válida, mas eu rodei `tsc` no worktree durante a série e a base oscilou muito (48,8 a 92,1). |
| 3 | 80,9 · 82,3 · 37,3 · 37,7 · 39,3 [39,3] | 70,1 · 75,3 · 36,4 · 34,8 · 36,8 [36,8] | −2,5 | Válida, sem nada meu rodando, mas a base mudou de regime no par 3 (de ~81 para ~37). Pares: −10,8; −7,0; −0,9; −2,9; −2,5. |

### Série 2 (todas as variantes de novo, sem nada meu rodando junto)

| Variante | Base | Variante | Dif |
|---|---|---|---|
| A | 38,4 · 70,5 · 75,8 · 71,5 · 71,5 [71,5] | 64,0 · 69,0 · 65,2 · 64,3 · 63,3 [64,3] | −7,2 |
| Limite superior | 73,8 · 71,7 · 67,9 · 72,7 · 69,3 [71,7] | 56,4 · 50,8 · 49,5 · 52,9 · 53,4 [52,9] | −18,8 |
| B | 73,0 · 77,1 · 71,8 · 72,3 · 76,2 [73,0] | 65,0 · 66,5 · 66,4 · 68,0 · 67,8 [66,5] | −6,5 |
| C | 72,7 · 75,7 · 73,6 · 74,7 · 65,4 [73,6] | 64,4 · 65,4 · 66,5 · 63,9 · 66,4 [65,4] | −8,2 |
| B + C (extra) | 73,9 · 73,6 · 75,8 · 71,0 · 66,5 [73,6] | 59,0 · 60,8 · 60,3 · 59,5 · 58,6 [59,5] | **−14,1** |

(O primeiro valor da base na série 2 / A, 38,4, é um valor atípico (provavelmente a mesma mudança de regime, [INFERÊNCIA]); a mediana o ignora.)

### Tabela pedida pelo plano (série 2, a mais limpa; série 1 entre parênteses)

| Variante | Mediana de CPU (5 rodadas) | Dif contra a base | Quadros/s |
|---|---|---|---|
| Base | 71,5 – 73,6 (série 1: 79,0 – 84,9) | — | 30,0 |
| A — atlas | 64,3 (73,9) | −7,2 (−11,0) | 30,0 |
| Limite superior | 52,9 (58,4) | −18,8 (−22,2) | 30,0 |
| B — props em camadas | 66,5 (execs. 2 e 3: 75,8 e 36,8) | −6,5 (−7,4 e −2,5) | 30,0 |
| C — móveis em camada | 65,4 (69,4) | −8,2 (−9,6) | 30,0 |
| B + C (extra) | 59,5 | −14,1 | 30,0 |

Critério do plano (separação ≥ 8 pontos entre as medianas alternadas): **só C (raspando) e B+C passam em todas as
séries**; A passou só na série 1; B não passa sozinha.

## Capturas (em `/tmp`, conferidas)

- `hb-013-base.png` × `hb-013-A.png`: mesmo escritório (móveis, árvores, personagens no lugar).
- `hb-013-UB.png`: limite superior; móveis, árvores, cercas e postes somem; personagens e salas continuam (como esperado).
- `hb-013-B.png` (build limpo da execução 3): árvores e cercas no mesmo lugar e na mesma ordem
  em relação às paredes, aos postes e aos carros, nas capturas feitas (comparação visual, sem diff de pixels para B).
- `hb-013-base-sala.png` × `hb-013-C-sala.png` (`lookAtRoom('habblaud', 3)`, agentes sentados atrás das mesas):
  quem está atrás da mesa continua coberto pela mesa; as cadeiras continuam na frente.
- Comparação camada ligada × desligada (mesmo build C com uma flag temporária só num build de diagnóstico, não no
  commit), dois quadros seguidos, diff pixel a pixel e imagens de diff: conferida nas salas `habblaud`,
  `nuvem-xlinx` e `Lounge` (versão final); só diferem personagens que andam entre os dois quadros, o piscar do
  monitor e ≈ 20 px do vidro do bebedouro (ver limitações). Contagens de pixels das demais salas (`Copa`, `Recepção`,
  `Banheiros`, `Desenvolvimento`, `omp-tenant`) não foram inspecionadas visualmente na versão final. Essas imagens
  de diff foram apagadas na limpeza e não estão em `/tmp`.
- `hb-013-BC.png`: B+C juntas, escritório normal.

## O que quebrou ou quase quebrou na ordem de profundidade

### B — props em camadas (`+43` linhas)

- Duas camadas do tamanho da base (norte/laterais pintadas **antes** das áreas; fachada sul e além **depois** das
  entidades, logo antes do ping-pong), cada uma desenhada com um `drawImage` recortado à parte visível, como a base.
- Corte por `y`: `prop.y < (BUILDING_H + 1) * TILE` vai atrás; o resto, na frente. Dentro de cada camada, ordenado
  por `y` (a ordenação do JS é estável, como a da lista de entidades).
- **Ficam na lista de profundidade** (poucos): props com `whenRoom` (cerca viva que surge com a sala), `lamp`
  (carro passa na frente do poste: `y` do poste < `y` do carro nas duas pistas), `bench`, `totem` e `paving` (perto
  do caminho do visitante), e os props dos `shells` (jardim dos slots vazios, com fade).
- Quase quebrou: o poste, se fosse para a camada da frente, passaria por cima do carro. Cercas/flores e árvores do
  sul não cruzam com nenhum carro (alturas dos sprites contra `y` das pistas).
- Cuidado de custo: a camada atrás cobre a área inteira (como a base); se a memória de GPU apertar, trocar por um
  recorte só do norte e das laterais.

### C — móveis em camada (`+176 / −8` linhas), três problemas reais

1. **Mistura dupla de pixels translúcidos.** O móvel redesenhado por cima do personagem somava sua sombra à sombra já
   pintada na camada (sob a mesa o canal R foi de 101 para 75, visível como mancha escura). Correção do protótipo:
   o redesenho usa uma **cópia só com pixels totalmente opacos** (cacheada por sprite). **Efeito colateral**: pixels
   translúcidos de móvel que deveria estar na frente de um personagem (vidro do bebedouro, sombra de cadeira) não
   são redesenhados; sobra uma diferença de dezenas de pixels por cena (≈ 20 px no bebedouro, alguns pixels perto
   das cadeiras das mesas ocupadas).
2. **Ordem entre áreas.** Camadas por área, blitadas na ordem das áreas, erraram a ordem entre móveis de áreas
   vizinhas (bebedouro do corredor × abóbora da sala: o `ay` do bebedouro é maior, mas a camada da sala vinha depois).
   Uma correção por redesenho nas sobreposições falhou pelo mesmo motivo do item 1. Solução do protótipo e que o
   plano 015 deve adotar: **uma camada única para todo o prédio**, com os móveis das áreas "estáveis" ordenados
   globalmente por `ay` (estável: empate fica na ordem das áreas e dos móveis).
3. **Redesenho local por personagem.** Depois de cada personagem (e de cada `FurnFront`), redesenha os móveis com
   `ay` maior que o `depth` dele cujo retângulo cruza o dele, mais, em cascata, os de `ay` ainda maior que cruzam
   algum já redesenhado (senão o redesenho cobriria o móvel que está na frente dele). Com 5–7 personagens é
   dezenas de comparações de retângulo por quadro, sem alocação.
- **Partes animadas**: tela do monitor (`desk`), fliperama e fumaça do café continuam por quadro, num novo tipo
  `K.FurnAnim` (só a parte animada, sem o `drawImage` do sprite), na posição de profundidade do móvel. Quando um
  móvel é redesenhado por cima de um personagem, ele volta inteiro (tela inclusa).
- **Invalidação**: a camada é refeita quando muda o conjunto de áreas estáveis (sala montando/desmontando sai da
  camada e usa o caminho antigo, `anim.mode !== 'none'`) ou o `furnState` de `coffee_machine`/`toilet_stall`. Tema
  e `assetsVersion` recriam os `AreaVis` e portanto o conjunto muda. O custo de refazer a camada não foi medido.
- **Não precisou** mexer em `client/src/art/**` nem em `client/src/world/sim/**`.

## Linhas de código do protótipo (`git diff --shortstat 31368cb <commit>`)

| Variante | Mudança |
|---|---|
| Limite superior | −3 linhas |
| B | +43 |
| C (versão final, camada única) | +176 / −8 |
| B + C | +219 / −8 |

Todas só em `client/src/world/render/renderer.ts` (nem `exterior.ts` precisou mudar).

Commits no branch `spike/013-lote` (todos só nele; o primeiro e o segundo/terceiro foram refeitos por mim antes de
qualquer push, porque um `git revert` tinha falhado em silêncio):

```
08f9673 spike: B e C combinadas (props em camadas + móveis em camada)
f97a76a spike: camada única de móveis para todo o prédio (profundidade global)
a68f625 spike: ordem de profundidade entre camadas de áreas vizinhas
6460597 spike: redesenho só com pixels opacos (sem mistura dupla da sombra)
db555d7 spike: variante C, móveis em camada por área com redesenho local
e006d1a spike: reverte variante B
4e2def8 spike: variante B, props estáticos em duas camadas
2ac9033 spike: reverte limite superior sem móveis e props
e4d9844 spike: limite superior sem móveis e props
```

(`git diff --stat fork/main spike/013-lote` não faz sentido aqui: o branch parte de `31368cb`, anterior ao `fork/main`
atual. Rebase para o `HEAD` atual toca `frame()`, mas as mudanças dos planos 010/011 — `motion` — não colidem
com as de B/C; só o `if (anim.mode !== 'none') this.motion = true;` e a contagem de partículas.)

## Recomendação

**Implementar B + C como plano 015**, nesta ordem: B primeiro (43 linhas, risco visual quase nulo, mexe só em
props), C depois (camada única do prédio, com a lista de ressalvas abaixo). **Não implementar A**: ganho incerto
(−7 a −11), exige um atlas que invalida quando um sprite é repintado, e o experimento já mostrou ruído do tamanho do
efeito.

Ganho esperado, medido: **−14 pontos de CPU a 30 quadros/s** (73,6% → 59,5%, ≈ −19%). Estimativa a **10
quadros/s**, o ritmo de produção do escritório parado: **≈ −4,5 pontos** (um terço, porque o custo é por quadro e
o restante do quadro não muda). Essa estimativa **não foi medida**: o branch parte de `31368cb`, que ainda não tem o
laço a 10 qps.

Leitura honesta para a decisão do operador: a 10 qps o ganho absoluto é pequeno (alguns pontos de um núcleo) e C
traz complexidade real (cascata de redesenho, ressalva dos pixels translúcidos, invalidação por estado). Se a
produção quase sempre roda a 10 qps, B sozinha (−6,5/−7,4 a 30 qps, ≈ −2 a 10 qps) já cobre quase nada e o ganho
principal vem de C. "Nenhuma" é defensável se a meta for só o escritório parado; B+C vale a pena se a meta incluir
o escritório em movimento (30 qps, GPU de Mac).

### Esboço do plano 015

- **Arquivos**: `client/src/world/render/renderer.ts` (única fonte); testes unitários novos em
  `client/src/world/render/` para a parte pura (ordenação global por `ay`, cascata de redesenho, escolha das
  camadas de props: extrair para funções sem canvas); `CHANGELOG.md`.
- **Passos**:
  1. B: `isStaticProp` + `buildPropLayers()` ao fim de `buildBase()`, `blitLayer` depois da base e depois do laço
     de entidades; props estáticos saem da lista de profundidade. Teste: partição dos props (poste, bench, totem,
     paving, `whenRoom` e props de shell ficam dinâmicos) e corte por `y`.
  2. C: `furnLayerFor(now)` (camada única, chave = áreas estáveis + `furnState` de `coffee_machine`/`toilet_stall`),
     `K.FurnAnim`, `redrawInFront` + `redrawCascade`, `opaqueOnly`. Manter o caminho antigo para áreas em
     montagem/desmontagem.
  3. Verificar com a captura em sala com agentes (diff pixel a pixel contra o caminho antigo, ligando/desligando a
     camada numa flag de teste) e 5 pares alternados de 20 s, B+C × base, aceitando só ≥ 8 pontos.
- **Riscos**:
  - Pixels translúcidos de móvel em frente de personagem (vidro, sombra): decidir se vale uma segunda camada
    "sem o móvel" para redesenho exato, ou aceitar a diferença.
  - Todo móvel novo cujo sprite depende de estado (`furnState`) precisa entrar na lista `dyn` da camada; esquecer
    congela o sprite velho (nota de manutenção do plano).
  - Rebuild da camada quando uma sala monta/desmonta: não medido; pode dar um soluço de quadro.
  - Memória de GPU: dois canvases do tamanho da base (props) + um canvas do bbox dos móveis.
  - `seasonalFurniture` (tema) já cai em recriar `AreaVis`; manter assim.
- **Fora de escopo**: `client/src/art/**`, `client/src/world/sim/**`, ritmo do laço (`pacing.ts`).

## Limitações e o que não foi verificado

- Hardware único (UHD 630/Linux); num Mac a proporção pode ser outra (nota de manutenção do plano).
- O escritório mudou durante a sessão (3–4 salas ocupadas nas séries 1 e 2, 2 na série B+C); cada par base × variante foi
  medido no mesmo estado, mas as séries não são comparáveis entre si em valor absoluto.
- Sem medição a 10 qps (ver acima).
- B e C não foram testadas em fase de montagem/desmontagem de sala (o caminho antigo é usado, mas não foi capturado)
  nem com tema sazonal diferente do atual (o atual é o de abóboras).
- A conferência final de profundidade em C é por captura e contagem de pixels num mesmo build, não por teste
  automatizado.
