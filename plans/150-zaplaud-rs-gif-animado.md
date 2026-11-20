# Plano 150: Zaplaud Rust: GIF animado toca na conversa

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat eca711a..HEAD -- zaplaud-rs/crates/zaplaud-ui/src/markdown/images.rs zaplaud-rs/crates/zaplaud-ui/src/images.rs zaplaud-rs/crates/zaplaud-ui/ui/markdown`
> Se algum mudou desde `eca711a`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P2 · **Esforço**: M · **Risco**: MED (memória; renderização por software do Slint; rolagem)
- **Depende de**: nenhum (par do 149) · **Categoria**: feature
- **Planejado em**: commit `eca711a`, 2026-11-20 (pedido do dono)

## Por que importa

Par do plano 149. O desktop mostra só o primeiro quadro de GIFs enviados pelos agentes (ciclos de caminhada de sprites).

## Estado atual

- `zaplaud-rs/crates/zaplaud-ui/src/markdown/images.rs:52-80`: `decode(bytes)` devolve `Decoded::Pixels` (um quadro,
  via crate `image` 0.25 com `gif`) ou `Decoded::Svg`; teto `MAX_IMAGE_PIXELS` 16 MP; `raster_bytes` estima memória.
- `zaplaud-rs/crates/zaplaud-ui/src/images.rs:~100-160`: carrega bytes (`spawn_blocking` para decodificar), guarda
  `slint::Image` em `IMAGES` (thread-local, com contabilidade de bytes) e incrementa `MarkdownImages.revision` para a
  interface buscar a imagem pelo destino.
- Toque na imagem abre a tela cheia (`ui/markdown/diagram.slint` ou equivalente; confirme).
- Roteiros gráficos em `zaplaud-rs/scripts/*.py` (sob `xvfb-run`); a rolagem e a posição de leitura (planos 126-134)
  dependem das alturas das linhas — o GIF não pode mudar de tamanho entre quadros.

## Decisões do dono (2026-11-20)

Toca sozinho, em loop, quando visível; para fora da tela. Respeita a preferência de reduzir movimento se o app já tiver
uma (procure; se não houver, não crie configuração nova). Limites: **300 quadros** e **50 MB** decodificados por GIF;
acima, primeiro quadro como hoje.

## Escopo

**Dentro:** `markdown/images.rs` (decodificar quadros e tempos), `images.rs` (guardar a sequência e trocar o quadro),
a parte `.slint` que mostra a imagem (só se precisar de propriedade nova), testes, `CHANGELOG.md`,
`docs/zaplaud-paridade.md`, roteiros só se coordenadas mudarem.

**Fora:** avatares, WebP/APNG animados, nova dependência, mudar a política de imagens remotas.

## Passos

### Passo 1: decodificar quadros (testes primeiro)

`decode` passa a reconhecer GIF com mais de um quadro: `image::codecs::gif::GifDecoder::into_frames()`, compondo cada
quadro no tamanho lógico (a crate já entrega quadros compostos), com atraso por quadro (mínimo de 20 ms; atraso 0 ou
10 ms vira 100 ms, como os navegadores). Para no 301º quadro ou quando a soma de bytes passar de 50 MB → devolve só o
primeiro quadro (comportamento atual). Novo `Decoded::Animated { frames, delays }`. Testes com GIFs gerados no teste:
1 quadro → `Pixels`; 3 quadros → `Animated` com 3 atrasos; 301 quadros → `Pixels`; GIF truncado no meio → o que
decodificou ou `Pixels` do primeiro, nunca pânico.

**Verificação**: `ZAPLAUD_KEYRING=memory cargo test --locked -p zaplaud-ui -j 6 <testes novos>` falham antes e passam depois.

### Passo 2: tocar

Guarde os quadros como `slint::Image` (mesma contabilidade de bytes do cache, somando todos os quadros) e troque o
quadro exibido com um `slint::Timer` por imagem visível. "Visível": só anime imagens cujo destino a interface está
de fato mostrando (as linhas fora da janela não são instanciadas pela lista virtual; use isso — por exemplo, a
interface pede a imagem pelo destino e um contador de uso/quadro avança só enquanto há pedido recente). Nenhum timer
por GIF fora da tela; nenhum trabalho na thread da interface além de trocar a referência da imagem. Tamanho constante
entre quadros. Tela cheia também anima.

**Verificação**: `cargo fmt --check`; clippy do workspace com `-D warnings`; `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6`.

### Passo 3: prova em tela

Fixture: a mensagem com o GIF fictício de 4 quadros do plano 149 (se ainda não existir, crie-a do mesmo jeito; se o
149 já criou, reaproveite). Sob `xvfb-run`: abrir a conversa, capturar duas vezes com intervalo maior que um quadro e
conferir que os pixels da área da imagem mudam; rolar até a imagem sair da tela e conferir (log temporário, não
commitado) que o timer parou. Matriz dos roteiros (2 temas × 3) uma vez; o roteiro de conversa continua passando
(fim da lista e posição de leitura).

**Verificação**: capturas em `~/dados/habblaud/plano-150/`; matriz verde (o roteiro de teclado falhou no plano 147
também na `producao` sem mudança, por causa do seletor de arquivos no Xvfb: se falhar igual, compare com o binário da
`producao` e relate).

### Passo 4: documentação

`CHANGELOG.md`: "Zaplaud desktop: GIF animado toca na conversa." Paridade: linha "GIF animado" — desktop sim.

## Critérios de pronto

- [ ] GIF de vários quadros anima na conversa e em tela cheia; para fora da tela
- [ ] Limites 300 quadros / 50 MB; acima, primeiro quadro
- [ ] fmt, clippy, testes, matriz (com a ressalva do teclado documentada)

## Condições de PARADA

- Animar exigir redesenho que faça a rolagem travar (meça e relate).
- A lista virtual não permitir saber quais imagens estão visíveis sem reestruturar a conversa.
