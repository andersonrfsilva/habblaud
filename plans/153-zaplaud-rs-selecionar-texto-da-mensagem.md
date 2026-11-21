# Plano 153: Zaplaud Rust: selecionar e copiar parte do texto de uma mensagem sem precisar do menu escondido

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat a5f49fd..HEAD -- zaplaud-rs/crates/zaplaud-ui/ui/markdown zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint`
> Se algum mudou desde `a5f49fd`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P2 · **Esforço**: S/M · **Risco**: LOW-MED (linha da conversa; links e menus existentes)
- **Depende de**: nenhum · **Categoria**: feature/UX
- **Planejado em**: commit `a5f49fd`, 2026-11-21 (relato do dono: "o cliente Rust não deixa selecionar texto para copiar")

## Por que importa

O dono quer copiar um trecho de uma resposta do agente no desktop. O texto formatado (`StyledText`, Slint 1.18.1)
não é selecionável. A seleção já existe, mas escondida: clique direito → "Selecionar texto", que troca **cada bloco**
por um campo de texto simples; a seleção não atravessa parágrafos, listas e código. O dono não achou o recurso.
No Android a seleção é direta (pressão longa, `SelectionContainer` em `ReadingScreen.kt:1044`).

## Estado atual

- `zaplaud-rs/crates/zaplaud-ui/ui/markdown/markdown.slint`: `MarkdownView` (`~:137-200`) tem `selecting` por mensagem;
  menu de contexto com "Copiar mensagem" e "Selecionar texto"/"Texto formatado"; Ctrl+C sem seleção copia a mensagem
  inteira. Cada bloco (`SelectableBlock`, `~:93-135`; tabelas `~:319-336`; código) troca `MeasuredText` por
  `MeasuredInput` (`TextInput` só leitura) quando `selecting`.
- Uma mensagem longa pode estar dividida em vários fragmentos (linhas da lista, `src/transcript.rs`); cada fragmento é
  uma `MarkdownView` com seu próprio `selecting`.
- Paridade: `docs/zaplaud-paridade.md:25` registra "Rust: seleção dentro de um bloco + 'Copiar mensagem' (limite do Slint)".

## Decisões

1. **Seleção da mensagem inteira num campo só**: no modo de seleção, a `MarkdownView` mostra **um** `MeasuredInput`
   só leitura com o texto simples da mensagem (blocos unidos por linha em branco; listas com o marcador; código como
   está; tabelas como linhas de células separadas por tabulação), em vez de um campo por bloco. A seleção atravessa
   parágrafos, listas e código do fragmento. A formatação some só enquanto seleciona (já é assim hoje).
2. **Entrar no modo sem menu escondido**: duplo clique no texto da mensagem entra no modo de seleção (o clique simples
   continua abrindo links); e um botão discreto "Selecionar" ao lado do botão de copiar do rodapé da mensagem.
   Mantenha o item do menu de contexto.
3. **Sair**: Esc, clicar fora da mensagem, trocar de conversa, ou o item "Texto formatado". Ctrl+A dentro do campo
   seleciona a mensagem; Ctrl+C copia a seleção (sem seleção, copia a mensagem inteira, como hoje).
4. Entre fragmentos de uma mesma mensagem dividida, a seleção não atravessa (cada fragmento é uma linha); diga isso na
   paridade.

## Escopo

**Dentro:** `ui/markdown/markdown.slint`, `ui/conversation_view.slint` (botão no rodapé), `src/markdown/*.rs` (texto
simples da mensagem para seleção, se não existir), textos em `android/shared/src/commonMain/composeResources/values/strings.xml`
só se precisar de texto novo (o Rust gera os seus a partir dele; não mude textos existentes), testes, roteiro de
acessibilidade/conversa (verificação nova), `docs/zaplaud-paridade.md`, `CHANGELOG.md`.

**Fora:** Kotlin; atualizar o Slint; seleção atravessando mensagens ou fragmentos.

## Passos

### Passo 1: texto simples da mensagem (teste primeiro)

Função pura que gera o texto de seleção a partir dos blocos (`MdBlock`) já montados: parágrafos, títulos, citações,
listas aninhadas com marcador, código, tabelas (células separadas por tabulação), regra horizontal como linha vazia.
Testes de unidade com uma mensagem que tem tudo isso.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-ui -j 6 <teste>` passa.

### Passo 2: modo de seleção com campo único, duplo clique e botão

Implemente as decisões 1–3. Duplo clique não pode quebrar o clique em link, o menu de contexto nem a rolagem da lista
(o campo de seleção não pode capturar a roda do mouse da lista). Ao sair do modo, a altura da linha volta à do texto
formatado sem mexer na posição de leitura.

**Verificação**: `cargo fmt --check`; clippy `-D warnings`; testes de core/store/ui.

### Passo 3: prova em tela

Sob `xvfb-run` (uma instância por vez, chaveiro em memória): abrir uma mensagem da fixture com parágrafo, lista e
código; duplo clique → modo de seleção; arrastar do primeiro parágrafo até o código (xdotool, que os roteiros já usam)
→ Ctrl+C → ler a área de transferência (`xclip -o -selection clipboard` ou o utilitário que os roteiros usam) e
conferir que contém texto dos três blocos; Esc volta ao formatado; um link continua abrindo com clique simples.
Acrescente isso ao roteiro adequado como verificação permanente. Rode o roteiro de conversa nos dois temas.

**Verificação**: capturas em `~/dados/habblaud/plano-153/`; verificação nova passa; roteiro de conversa passa.

### Passo 4: documentação

Paridade (linha 25): "Rust: seleção da mensagem (ou do fragmento) por duplo clique ou botão 'Selecionar'; formatação
some durante a seleção". `CHANGELOG.md`: "Zaplaud desktop: duplo clique (ou o botão 'Selecionar') permite selecionar e
copiar qualquer trecho de uma mensagem, atravessando parágrafos, listas e código."

## Critérios de pronto

- [ ] Duplo clique e botão entram no modo; seleção atravessa blocos do fragmento; Ctrl+C copia a seleção
- [ ] Links, menu de contexto e rolagem intactos; sair do modo não move a leitura
- [ ] fmt, clippy, testes, verificação nova e roteiro de conversa ok

## Condições de PARADA

- Duplo clique não puder ser distinguido do clique em link sem atrasar a abertura dos links de forma perceptível.
- O campo único quebrar a medição de altura das linhas (planos 126–134).
