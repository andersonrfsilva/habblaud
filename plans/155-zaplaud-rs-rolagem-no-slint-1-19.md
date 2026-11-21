# Plano 155: Zaplaud Rust: rolagem estranha depois do Slint 1.19 (salta, volta, velocidade diferente)

> **Instruções ao executor**: investigação com correção. Prove a causa antes de mudar código. Se ocorrer uma condição de
> PARADA, pare e relate. O revisor cuida de `plans/README.md`.
>
> **Verificação de desvio**: `git log --oneline 33eb0bb..HEAD -- zaplaud-rs` — leia commits novos, se houver.

## Status

- **Prioridade**: P1 · **Esforço**: M · **Risco**: MED (lógica de rolagem dos planos 126, 127, 134, 152)
- **Depende de**: 154 (mesclado) · **Categoria**: bug/regressão
- **Planejado em**: commit `33eb0bb`, 2026-11-21

## Por que importa

Depois do Slint 1.19 (plano 154), o dono relata no MacBook (trackpad): (1) a lista **salta sozinha** durante ou logo
depois de rolar; (2) ele rola e ela **volta para onde estava**; (3) **velocidade/inércia** diferentes de antes. Na
versão anterior (`2a90b75`, Slint 1.18.1) não havia queixa.

## Estado atual

- CHANGELOG do Slint 1.19.0 (`https://github.com/slint-ui/slint/blob/master/CHANGELOG.md#1190---2026-11-16`) muda a
  rolagem: "`Flickable`: Fixed several input issues, such as a jump when scrolling starts (#13542, #13614, #13673)";
  "Added support for wheel and touch pad scrolling (#13570)"; "`Flickable`: An unhandled quick click goes to the parent
  elements (#13121)"; "`ListView`: Fixed the position after setting `viewport-y` far from the current one";
  "`Flickable`: Added `scroll-to()`".
- Nossa lógica (`zaplaud-rs/crates/zaplaud-ui/ui/conversation_view.slint`): `following` e o reposicionamento no fim
  (`changed content-y`, `changed content-height`), a suspensão durante o gesto (`scroll-from-end` + temporizador
  `scroll-settle` de 220 ms após o último `scrolled`), o restauro da âncora (`restore-pending`, `anchor-ticks`,
  plano 152), e a gravação da posição (`position`). Tudo foi calibrado na 1.18.1: em particular, a suposição de que
  cada evento de roda muda `content-y` de uma vez e dispara `scrolled`.

Hipóteses: (a) a 1.19 anima/estende a rolagem por vários quadros (inércia do trackpad ou rolagem suave), e o
temporizador de 220 ms termina antes do movimento → reposiciona no fim ("salta", "volta"); (b) `scrolled` não dispara
mais (ou dispara em outro momento) para roda/trackpad, então a suspensão não liga; (c) o restauro ou o `position`
reescreve `content-y` com a posição antiga durante a inércia; (d) a velocidade mudou por causa da nova conversão de
deltas de pixel do trackpad (#13570) — isso é do Slint e só é corrigível se a 1.19 expuser ajuste.

## Escopo

**Dentro:** `zaplaud-rs/crates/zaplaud-ui/**`, testes e `zaplaud-rs/scripts/conversation-smoke.py` (verificação nova),
`CHANGELOG.md`. **Fora:** voltar o Slint para 1.18 (a não ser como comparação local); Kotlin.

## Passos

### Passo 1: o que mudou no Slint

Compare as fontes de rolagem entre `v1.18.1` e `v1.19.0` (Flickable/ListView em `internal/core/items/flickable.rs` e
afins; tratamento de `WindowEvent::MouseWheel`/`PixelDelta`/fases de inércia no backend winit, inclusive o caminho do
macOS): quando `content-y`/`viewport-y` muda depois de um evento de roda, se há animação ou inércia emulada, quando o
callback `scrolled`/`flicked` dispara, e o que a 1.19 faz com `TouchPhase`/momentum no macOS. Cite arquivo:linha.

**Verificação**: tabela "1.18.1 vs 1.19.0" para: atualização de content-y por evento, animação, momento do `scrolled`,
tratamento de deltas de pixel e fases.

### Passo 2: reproduzir no que for possível

Sob `xvfb-run`, com binários de `2a90b75` (1.18.1) e da `producao` (1.19.0): registre `content-y`, `following`,
`scroll-from-end`, `restore-pending` a cada quadro (log temporário) durante: (i) 5 passos de roda para cima a partir
do fim; (ii) a mesma coisa no meio do histórico; (iii) rolagem com deltas de pixel em sequência, simulando trackpad,
se der para injetar (xdotool envia só passos; veja se o backend aceita `XInput2` smooth scroll com `xinput`/
`xdotool` ou um pequeno programa de teste usando a API de teste do Slint para enviar `PointerScrollEvent` com
deltas fracionários em vários quadros). Procure: content-y voltando sem gesto, salto para o fim, posição diferente da
que o gesto deixou.

**Verificação**: para cada caso, se reproduz na 1.19 e não na 1.18 (com os números). Se nada reproduzir, a evidência
do passo 1 ainda precisa explicar o que no macOS difere; relate e siga para o passo 3 só com causa apontada pelo código.

### Passo 3: corrigir

Ajuste a nossa lógica à semântica da 1.19, com a menor mudança: por exemplo, considerar gesto do usuário qualquer
mudança de `content-y` que não foi feita por nós (marcar as nossas escritas em vez de inferir pelo tempo), terminar a
suspensão só quando a lista parar de se mover (sem prazo fixo), e não reescrever `content-y` com posição restaurada
depois que o usuário mexeu. Mantenha intactas as verificações dos planos 126, 127, 134 e 152.

Teste permanente novo que falha antes: uma sequência de vários eventos de rolagem fracionários espalhados por ≥ 500 ms
(como a inércia do trackpad) a partir do fim e do meio do histórico; depois de parar, `content-y` fica onde o gesto
deixou por 2 s (não salta, não volta), e mensagens novas não puxam para o fim quem está no histórico.

**Verificação**: `cargo fmt --check`; clippy `-D warnings`; testes core/store/ui; roteiros de conversa nos dois temas
e nas duas fontes; matriz sem falha nova em relação à `producao`.

### Passo 4: documentação

`CHANGELOG.md`: "Zaplaud desktop: a rolagem não salta nem volta sozinha depois da atualização do Slint 1.19." Se a
velocidade do trackpad for comportamento do Slint sem ajuste exposto, registre nas notas do plano e não invente
multiplicador.

## Critérios de pronto

- [ ] Causa provada pelo código do Slint e, se possível, reproduzida
- [ ] Teste novo de rolagem fracionária falha antes e passa depois
- [ ] Verificações dos planos 126, 127, 134, 152 intactas; fmt, clippy, testes, roteiros

## Condições de PARADA

- A correção exigir voltar para o Slint 1.18 ou mudar o componente de lista.
- A causa só existir no caminho de entrada do macOS e não houver como testar nem apontar pelo código.

## Notas de manutenção

- O dono confere no Mac: rolar com o trackpad para cima a partir do fim e no meio do histórico; nada deve saltar ou voltar.
