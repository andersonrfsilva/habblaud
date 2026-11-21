# Plano 152: Zaplaud Rust: ao trocar de conversa, o conteúdo aparece sobreposto por alguns segundos

> **Instruções ao executor**: este plano é de **investigação com correção**. Siga os passos; a correção só entra
> quando a causa estiver provada. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git log --oneline 30ddd51..HEAD -- zaplaud-rs/crates/zaplaud-ui` — se
> houver commits novos, leia-os antes de começar.

## Status

- **Prioridade**: P1 · **Esforço**: M · **Risco**: MED (lista virtual da conversa; rolagem e posição de leitura dos planos 126–134)
- **Depende de**: nenhum · **Categoria**: bug
- **Planejado em**: commit `30ddd51`, 2026-11-21 (vídeo do dono no MacBook)

## Por que importa

O dono gravou o desktop no MacBook (versão `640068e`, macOS) trocando de conversa: por uns 3 a 4 segundos depois do
clique, linhas de mensagens diferentes aparecem desenhadas **umas por cima das outras** — tabela de uma mensagem
sobre o texto de outra, balão do usuário sobre uma tabela, botão "Recolher" sobre parágrafos. Depois a tela assenta e
fica correta. Evidência: `~/dados/habblaud/plano-152/video-mac.mp4` e quadros `f01.jpg`…`f16.jpg` (2 por segundo; a
troca acontece entre `f02` e `f04`; `f08` e `f11` mostram a sobreposição; `f16` está limpo). A conversa de destino
tem tabelas Markdown longas, links e blocos "Pensando"/"Ferramentas".

## Estado atual (hipóteses, nenhuma provada)

- A lista da conversa é virtual (`zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint`), com alturas estimadas e
  depois medidas; a troca de conversa mantém a `ConversationView` viva e restaura a posição de leitura com
  `restore-pending`, âncora e `anchor-ticks` (planos 126, 127, 134), ao longo de vários quadros.
- Mudanças recentes no desktop desde a versão que o dono usava sem queixa (`1900936`): tarefas acima do compositor
  (144), divisão de cercas svg/mermaid e cache global de desenhos (146), links de arquivo (147), GIF animado e
  admissão de decodificação (150, 150b), perguntas sem "Outro" (151). Veja `git log --oneline 1900936..30ddd51 -- zaplaud-rs/crates/zaplaud-ui`.
- Possíveis causas: (a) linhas posicionadas com alturas antigas/estimadas enquanto as novas são medidas (sobreposição
  de layout); (b) repintura parcial que deixa pixels velhos (fantasma), por exemplo por um temporizador que redesenha só
  uma região; (c) a restauração da âncora reposicionando a lista vários quadros seguidos. A diferença entre (a) e (b):
  em (a) a árvore de acessibilidade/geometria das linhas mostra retângulos que se sobrepõem; em (b) a geometria está
  certa e só a imagem está suja.

## Escopo

**Dentro:** `zaplaud-rs/crates/zaplaud-ui/**` (código e `.slint`), testes, `zaplaud-rs/scripts/*.py` (verificação
nova), `server/test/android-fixture.ts` (uma conversa longa com tabelas, se a fixture não tiver), `CHANGELOG.md`.

**Fora:** Kotlin; servidor fora da fixture; mudar o renderizador do Slint.

## Passos

### Passo 1: reproduzir

Sob `xvfb-run`, com a fixture: duas conversas longas, a de destino com tabelas Markdown largas, blocos de ferramentas
e pensamento (crie na fixture se faltar, textos fictícios). Troque de conversa clicando na lista e capture a tela a
cada 100 ms por 5 s (`xwd`/a função de captura que os roteiros já usam). Em cada captura, registre também a geometria
das linhas visíveis (pela árvore de acessibilidade que o `accessibility-smoke.py` já lê, ou por log temporário de
`y`/`height` de cada linha). Repita com fonte DejaVu, janela 1100×760 e 1440×900, e com a conversa de destino rolada
para o meio (posição salva no histórico).

**Verificação**: sobreposição reproduzida (diga em quantas de 10 trocas, e em que quadros), classificada como (a), (b)
ou (c) com evidência. Se não reproduzir em Linux, relate as condições testadas e PARE (pode ser específico do
renderizador do macOS; o revisor decide o próximo passo).

### Passo 2: achar o commit

Compile o desktop em `1900936` (sem as mudanças recentes) e repita o passo 1. Se não reproduzir lá, bisseção pelos
commits de `git log --oneline 1900936..30ddd51 -- zaplaud-rs/crates/zaplaud-ui` (worktrees temporárias em `/tmp`,
um `CARGO_TARGET_DIR` por build; apague depois).

**Verificação**: o primeiro commit que reproduz, com a taxa antes/depois; ou "reproduz em todos" (defeito antigo).

### Passo 3: corrigir na causa

Corrija o mecanismo provado, com a menor mudança: por exemplo, não mostrar as linhas da conversa nova até as alturas
da primeira página estarem medidas e a âncora aplicada (sem esconder por tempo fixo); ou invalidar a área inteira da
lista na troca; ou impedir que o temporizador de um componente provoque repintura parcial errada. Não mexa nas regras
de seguir o fim e posição de leitura dos planos 126–134 além do necessário, e mantenha as verificações deles.

Verificação nova e permanente no roteiro de conversa: depois de uma troca, em todas as capturas a cada 100 ms por 2 s,
os retângulos das linhas visíveis não se sobrepõem (geometria), e, se a causa for (b), as capturas não diferem da
captura assentada fora da área que mudou de propósito. A verificação tem de falhar no binário com o defeito.

**Verificação**: verificação nova falha antes e passa depois (10 de 10 trocas); `cargo fmt --check`; clippy
`-D warnings`; `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`; roteiro de conversa nos dois
temas e nas duas fontes; o roteiro de teclado falha também na `producao` sem mudança (seletor de arquivos no Xvfb):
compare com o binário da `producao` e relate.

### Passo 4: documentação

`CHANGELOG.md`: "Zaplaud desktop: trocar de conversa não mostra mais mensagens sobrepostas enquanto a conversa
carrega."

## Critérios de pronto

- [ ] Causa provada (tipo a/b/c, commit de origem ou "antigo") com evidência em `~/dados/habblaud/plano-152/`
- [ ] Verificação nova falha antes e passa 10/10 depois
- [ ] fmt, clippy, testes, roteiro de conversa; nenhuma verificação afrouxada

## Condições de PARADA

- Não reproduzir no Linux (Xvfb) depois das variações do passo 1.
- A correção exigir trocar o componente de lista ou o renderizador.
- A correção mudar o comportamento de fim de lista ou posição de leitura que os roteiros verificam.
