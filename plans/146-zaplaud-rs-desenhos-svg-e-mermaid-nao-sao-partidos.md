# Plano 146: Zaplaud Rust: blocos `svg` e `mermaid` de tamanho real são desenhados, em vez de aparecerem como código

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat 2ee42b6..HEAD -- zaplaud-rs/crates/zaplaud-ui/src/transcript.rs zaplaud-rs/crates/zaplaud-ui/src/conversation_rows.rs zaplaud-rs/crates/zaplaud-ui/src/markdown`
> Se algum mudou desde `2ee42b6`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é
> condição de PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (divisão do transcript em fragmentos: mexe com altura máxima de linha da lista e com a rolagem)
- **Depende de**: nenhum
- **Categoria**: bug
- **Planejado em**: commit `2ee42b6`, 2026-11-20 (relato do dono com foto: "svg não está aparecendo no Mac")

## Por que importa

O desktop desenha blocos ```` ```svg ```` e ```` ```mermaid ```` na conversa desde 2026-11-18. Na prática, quase
nenhum desenho real aparece: o dono vê o código-fonte do SVG. Os agentes mandam diagramas de 20 a 40 linhas; só os
minúsculos (como os dos testes) são desenhados. O Android/iPad não tem o problema.

## Estado atual

Causa medida em 2026-11-20 com a mensagem real (o conteúdo não está no repositório):

- O leitor funciona: `markdown::parse` devolveu o bloco com `diagram` de 900 px de largura para a mensagem inteira.
- O que apaga o desenho é a divisão do transcript em fragmentos. `zaplaud-rs/crates/zaplaud-ui/src/transcript.rs`:
  - `HEIGHT_BUDGET = 160` (`:8`) e `estimate()` (`:17-22`): custo de cada linha = `ceil(caracteres / 4) + 1`. O
    comentário (`:1,5-7`) explica o motivo: o renderizador por software usa coordenadas físicas `i16`, então cada
    fragmento tem de ficar abaixo de ~10.000 px lógicos.
  - `transcript_pieces()` (`:25-151`) corta quando o custo acumulado passa do orçamento, **inclusive no meio de uma
    cerca de código**; nesse caso repete a abertura e o fechamento da cerca em cada fragmento (`:121-143`).
- `zaplaud-rs/crates/zaplaud-ui/src/conversation_rows.rs:100-107`: se o bloco de código de um fragmento é só parte
  da cerca original, o `diagram` é trocado por imagem vazia ("Trecho parcial de um diagrama dividido não representa
  o diagrama inteiro"), e `ui/markdown/markdown.slint:212,242` mostra código quando `diagram.width == 0`.
- O SVG do caso: 30 linhas de ~80 caracteres → custo **649** (4× o orçamento) → partido em vários fragmentos →
  código. O SVG dos testes existentes custa 31 (`markdown/mod.rs:516-527`), por isso nenhum teste acusa.
- Um desenho ocupa pouca altura quando desenhado (`markdown.slint:215-217`: largura limitada à do bloco, altura
  proporcional), muito menos que o código-fonte; o orçamento por texto não descreve a altura real dele.
- Rolagem: os planos 126 a 134 corrigiram defeitos de fim de lista e posição de leitura que dependem das alturas
  das linhas; os roteiros `zaplaud-rs/scripts/conversation-smoke.py` verificam isso.

## Convenções do repositório (valem para este plano)

- Regra testável em função pura com testes de unidade no próprio arquivo (`transcript.rs` já tem `mod tests`).
- `export PATH="$HOME/.cargo/bin:$PATH"`; `cargo fmt --check`; `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings`;
  testes com `ZAPLAUD_KEYRING=memory` e `npm ci` na raiz antes (testes de contrato sobem o servidor Node).
- A janela só roda em tela virtual: `xvfb-run -a -s '-screen 0 1280x1024x24'`, uma instância por vez; nunca a tela real.
- **O CI do GitHub está desativado** (decisão do dono): a prova é toda local.
- `CHANGELOG.md` ("Não lançado") e `docs/zaplaud-paridade.md` quando o comportamento muda.

## Escopo

**Dentro:** `zaplaud-rs/crates/zaplaud-ui/src/transcript.rs`, `src/conversation_rows.rs`, `src/markdown/mod.rs`
(só se precisar expor uma função para saber se a cerca desenha), testes desses arquivos, `server/test/android-fixture.ts`
(uma mensagem fictícia com um SVG de tamanho real, se a verificação em tela precisar), `zaplaud-rs/scripts/*.py` (só
para a verificação nova ou coordenadas deslocadas), `CHANGELOG.md`, `docs/zaplaud-paridade.md`.

**Fora:** o leitor de SVG e as cores do tema (`svg_theme_colors`); Kotlin; mudar `HEIGHT_BUDGET` para texto e
código comuns; a lógica de rolagem dos planos 126 a 134.

## Passos

### Passo 1: teste que falha hoje

Em `transcript.rs` (ou `conversation_rows.rs`, onde o modelo é montado): uma mensagem com um parágrafo, uma cerca
```` ```svg ```` válida de 30 linhas de ~80 caracteres (gere o SVG no teste: `<rect>`/`<text>` fictícios, com
`viewBox`) e outro parágrafo. Afirme que, depois da divisão e da montagem dos blocos (o caminho que a tela usa:
`transcript_pieces` + `model`), **existe exatamente um bloco de código com `diagram.size().width > 0`** e que
nenhum fragmento contém parte do SVG sem o desenho. Um segundo teste igual para ```` ```mermaid ```` com um
diagrama de ~30 linhas que o renderizador aceite.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-ui -j 6 <nome do teste>` — falha na base.

### Passo 2: não partir cercas que desenham

Em `transcript_pieces`: uma cerca cuja linguagem é `svg` ou `mermaid` **e que desenha** é atômica — fica inteira
num fragmento — e seu custo no orçamento é o da altura desenhada (estime pela proporção da imagem na largura máxima
do bloco, convertida para as mesmas unidades de `estimate`, com um mínimo e um teto), não o do texto. Requisitos:

- **Só é atômica se desenhar.** Cerca `svg`/`mermaid` inválida, incompleta (streaming) ou grande demais para
  desenhar continua sendo partida como código, exatamente como hoje: um bloco de código de 600 linhas num fragmento
  só estouraria o limite de coordenadas que o orçamento existe para proteger.
- **Teto de tamanho da fonte**: acima de um limite de bytes (use o mesmo espírito do Kotlin: `MermaidCodeFence.kt`
  recusa fontes grandes; veja o valor lá e em `data/HabblaudApi.kt` `MERMAID_SVG_MAX_BYTES`), a cerca não é
  atômica.
- **Altura desenhada com teto**: se a imagem desenhada na largura do bloco passar do equivalente ao orçamento de um
  fragmento, limite a altura exibida (o desenho já abre em tela cheia ao clicar) ou trate como não atômica — escolha
  a que mantém todo fragmento abaixo do limite de ~10.000 px lógicos em escala 3, e prove com um teste (SVG com
  `viewBox="0 0 100 4000"`).
- **Sem desenhar duas vezes por quadro**: decidir "desenha?" exige carregar o SVG (e, para Mermaid, renderizar).
  Reaproveite o resultado entre a divisão e a montagem do bloco, ou guarde por conteúdo; meça que abrir a conversa
  da fixture não fica perceptivelmente mais lento (o roteiro de conversa mede memória e tempos de reabertura).
- O fragmento anterior e o seguinte continuam obedecendo ao orçamento; a cerca atômica pode dividir um fragmento
  com blocos pequenos vizinhos se a soma couber.
- `conversation_rows.rs:100-107` continua valendo para cercas realmente partidas.

**Verificação**: os testes do passo 1 passam; os testes existentes de `transcript.rs` (cercas continuadas, tabelas,
orçamento) passam sem alteração; teste novo: SVG inválido de 30 linhas continua partido e exibido como código;
teste novo: todo fragmento com desenho tem altura estimada dentro do limite.

### Passo 3: prova em tela

Acrescente à fixture (`server/test/android-fixture.ts`) uma mensagem fictícia do agente omp com um SVG de ~30 linhas
(textos fictícios), numa posição que não desloque o que os roteiros já medem (prefira uma sessão que os roteiros
abrem mas não medem por coordenada; se não houver, corrija as coordenadas medindo). Sob `xvfb-run`: abra a conversa,
capture a tela nos dois temas (`~/dados/habblaud/plano-146/`) e confirme pela árvore de acessibilidade ou por pixel
que o desenho aparece (o bloco tem nome/ação acessível: `markdown.slint:223`). Rode a matriz dos roteiros (2 temas ×
3) com a fonte padrão e com DejaVu (`FONTCONFIG_FILE=~/dados/habblaud/plano-127b/fonts-127b.conf`).

**Verificação**: capturas mostram o desenho; matriz 12/12; nenhuma verificação afrouxada; o fim da conversa e a
posição de leitura continuam passando.

### Passo 4: fechar

`cargo fmt --check`; clippy do workspace; `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`;
`npm run typecheck` e `npm test` se a fixture mudou; `python3 -m py_compile zaplaud-rs/scripts/*.py`.
`CHANGELOG.md`: "Zaplaud desktop: desenhos `svg` e diagramas Mermaid de tamanho real aparecem desenhados; antes, só
os muito pequenos, e os demais apareciam como código." Paridade: ajuste a linha dos blocos `svg` se citar limite.

**Verificação**: tudo sai com 0.

## Plano de testes

Passo 1 (SVG e Mermaid de tamanho real), passo 2 (inválido continua código; teto de altura; orçamento dos vizinhos).

## Critérios de pronto

- [ ] SVG e Mermaid de ~30 linhas aparecem desenhados (teste que falhava; captura em tela)
- [ ] Cerca inválida ou grande demais continua partida como código; nenhum fragmento passa do limite de altura
- [ ] Matriz dos roteiros 12/12 nas duas fontes; fmt, clippy e testes ok

## Condições de PARADA

- Manter o desenho inteiro exigir um fragmento acima do limite de coordenadas do renderizador e não houver como
  limitar a altura exibida.
- A mudança alterar o comportamento de fim de lista ou de posição de leitura que os roteiros verificam.
- Decidir "desenha?" na divisão custar tempo perceptível ao abrir conversas longas (relate os números).

## Notas de manutenção

- Depois do merge: reinstalar o desktop no Mac (e no miranote).
- Durante o streaming a cerca ainda não fechou e aparece como código até fechar; isso não muda.
- O Kotlin não divide o transcript e não tem este defeito.

## Comandos de verificação

| O quê | Comando | Esperado |
|---|---|---|
| Testes | `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` | todos passam |
| Lint | `cargo clippy --locked --workspace --all-targets -j 6 -- -D warnings` | sai com 0 |
