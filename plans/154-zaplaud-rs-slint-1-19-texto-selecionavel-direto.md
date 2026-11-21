# Plano 154: Zaplaud Rust: Slint 1.19 — texto das mensagens selecionável arrastando, sem modo de seleção

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git log --oneline d983013..HEAD -- zaplaud-rs` — se houver commits novos, leia-os.

## Status

- **Prioridade**: P1 · **Esforço**: M · **Risco**: MED (troca de versão da biblioteca de interface; roteiros por pixel)
- **Depende de**: 153 (mesclado; parte dele sai) · **Categoria**: feature/dependência
- **Planejado em**: commit `d983013`, 2026-11-21 (dono: "pra que essa história de clicar para poder selecionar?")

## Por que importa

O dono quer selecionar texto como em qualquer programa: apertar e arrastar. O plano 153 entregou um "modo de seleção"
(botão, duplo clique, menu) porque o `StyledText` do Slint 1.18.1 não seleciona. O **Slint 1.19.0** (2026-11-16)
traz `StyledText { selectable: true }` (PR slint-ui/slint#13985, incluído na 1.19.0): arrastar seleciona, duplo
clique seleciona palavra, clique triplo o parágrafo, Shift estende, links continuam clicáveis; API `has-selection`,
`copy()`, `select-all()`, `clear-selection()`, `set-selection-offsets()`. Limites da 1.19: sem seleção por teclado,
sem atalho de copiar dentro do elemento, sem alças de toque, e a seleção **não atravessa** elementos (issue #14936
aberta).

A 1.19.0 também corrige "texto que não era repintado com renderização parcial depois de muitas atualizações de outro
texto" — sintoma compatível com o texto sobreposto no Mac (plano 152, causa não provada).

## Estado atual

- `zaplaud-rs/Cargo.toml:20-21`: `slint = "=1.18.1"`, `slint-build = "=1.18.1"` (renderizador `software` em todas as
  plataformas; `src/main.rs:146-160`).
- Mensagens: `zaplaud-rs/crates/zaplaud-ui/ui/markdown/markdown.slint` (`MeasuredText inherits StyledText` ~`:30`;
  `SelectableBlock`; `MarkdownView` com o modo de seleção do plano 153: botão "Selecionar" no rodapé
  (`ui/conversation_view.slint`), duplo clique em blocos sem link, item de menu, campo único por fragmento montado sob
  demanda (`src/markdown/mod.rs`, `src/conversation_view.rs`, `src/aside.rs`, global `MarkdownSelection`).
- Ctrl+C com a mensagem focada e sem seleção copia a mensagem inteira (`markdown.slint`, `capture-key-pressed`).

## Decisões

1. Atualizar para Slint **1.19.0** exato (`=1.19.0` nas duas linhas) e o `Cargo.lock` só no necessário.
2. Todo `StyledText` de mensagem (texto, citação, lista, células de tabela) com `selectable: true`. Blocos de código já
   são `TextInput` só leitura e continuam.
3. **Ctrl+C**: se algum bloco da mensagem focada tem seleção, `copy()` dele; senão, a mensagem inteira (como hoje).
   Clicar em outro bloco/mensagem limpa a seleção anterior (uma seleção visível por vez).
4. **Sai o que virou desnecessário**: botão "Selecionar" do rodapé e o duplo clique de entrada no modo. **Fica** o item
   de menu "Selecionar texto" (renomeie para "Selecionar a mensagem toda") com o campo único do 153, porque a seleção
   da 1.19 não atravessa blocos; é o caminho para copiar parágrafos + lista + código de uma vez. Remova o texto
   `select_message_text` se ficar sem uso.
5. Bate-papo paralelo igual.

## Escopo

**Dentro:** `zaplaud-rs/Cargo.toml`, `zaplaud-rs/Cargo.lock`, `zaplaud-rs/crates/zaplaud-ui/**`, `zaplaud-rs/scripts/*.py`
(coordenadas que a 1.19 deslocar, medindo; verificação nova), `android/shared/src/commonMain/composeResources/values/strings.xml`
(só remover/renomear os textos do 153), `docs/zaplaud-paridade.md`, `CHANGELOG.md`.

**Fora:** Kotlin; trocar o renderizador; outros upgrades de dependência.

## Passos

### Passo 1: atualizar o Slint e provar que nada regrediu

Troque a versão, `cargo update -p slint -p slint-build` (e só o que o resolvedor exigir), compile. Corrija o que a 1.19
quebrar na compilação (leia o CHANGELOG da 1.19 em `https://github.com/slint-ui/slint/blob/master/CHANGELOG.md`).

**Verificação**: `cargo fmt --check`; clippy `-D warnings`; `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`;
matriz dos roteiros (2 temas × 3) com a fonte padrão e com DejaVu, **antes de qualquer outra mudança**. Para cada
roteiro que falhar, compare com o binário da `producao` (1.18.1): falhas iguais nos dois (o roteiro de teclado já falha
no seletor de anexos no Xvfb) ficam registradas; falha nova por coordenada → corrija medindo; falha nova de
comportamento → PARE.

### Passo 2: texto selecionável e Ctrl+C

Decisões 2, 3 e 5. Testes de unidade onde houver regra pura (escolha entre `copy()` do bloco e mensagem inteira).

**Verificação**: os comandos do passo 1.

### Passo 3: retirar o modo como entrada principal

Decisão 4. A seleção da mensagem toda continua só pelo menu.

**Verificação**: os comandos do passo 1; `grep -rn 'select_message_text\|double-clicked' zaplaud-rs/crates/zaplaud-ui/ui` sem restos do caminho removido.

### Passo 4: prova em tela e verificação permanente

Sob `xvfb-run` (uma instância por vez, chaveiro em memória): numa mensagem da fixture com parágrafo com **negrito e
link**, arrastar do meio de uma palavra ao meio de outra (xdotool), Ctrl+C, ler a área de transferência: contém
exatamente o trecho, sem marcação. Clique simples no link continua abrindo (o roteiro já registra abertura de link?
se não, confira pelo comportamento existente). Duplo clique seleciona uma palavra. Menu "Selecionar a mensagem toda"
ainda copia parágrafo + lista + código. Acrescente ao roteiro de conversa (ou de acessibilidade) como verificação
permanente; rode a matriz.

**Verificação**: capturas em `~/dados/habblaud/plano-154/`; verificação nova passa nos dois temas.

### Passo 5: documentação

Paridade (linha da conversa): "Rust: arrastar seleciona dentro de cada bloco (Slint 1.19); 'Selecionar a mensagem
toda' no menu para vários blocos". `CHANGELOG.md`: "Zaplaud desktop: o texto das mensagens se seleciona arrastando,
como em qualquer programa; Ctrl+C copia a seleção. Atualizado para Slint 1.19."

## Critérios de pronto

- [ ] Slint 1.19.0; fmt, clippy, testes; matriz sem falha nova em relação à `producao`
- [ ] Arrastar seleciona texto formatado; links abrem com clique; Ctrl+C copia a seleção ou a mensagem
- [ ] Botão e duplo clique de entrada no modo removidos; menu mantém a seleção da mensagem toda
- [ ] Verificação permanente nova; paridade e changelog

## Condições de PARADA

- A 1.19.0 mudar comportamento (não só coordenadas) que os roteiros verificam: fim da lista, posição de leitura,
  folha de permissão, teclado.
- `selectable` não funcionar com o renderizador `software` (confira na PR/CHANGELOG; prove em tela).

## Notas de manutenção

- Depois do merge: instalar no Mac; o dono confere a seleção e também o texto sobreposto ao trocar de conversa (a 1.19
  corrige um defeito de repintura parcial de texto).
- Quando o Slint permitir seleção atravessando elementos (#14936), o menu "Selecionar a mensagem toda" pode sair.
