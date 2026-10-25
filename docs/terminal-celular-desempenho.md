# Terminal no celular: desempenho

Registro da investigação de outubro de 2026 sobre o terminal lento no celular (digitação com atraso, rolagem
travando com histórico grande): o que causava, o que mudou, quanto cada mudança rendeu e como medir de novo.

**Situação hoje:** digitar e rolar o histórico estão leves no celular. Duas mudanças explicam quase toda a melhora:
pausar o escritório atrás do terminal e tirar o `backdrop-filter` do terminal. O `contain: content` nas linhas não
mostrou ganho claro (veja [Pontos em aberto](#pontos-em-aberto)).

## Causas

| Sintoma | Causa | Onde |
| --- | --- | --- |
| Teclado com atraso | O escritório (canvas do mundo) continuava sendo desenhado a cada quadro atrás do terminal em tela cheia, numa imagem de 1179×2553 px (tela do celular com densidade 3×). Ocupava a CPU quase inteira | `client/src/ui/index.ts` |
| Rolagem pesada | O terminal tinha `backdrop-filter: blur(14px) saturate(1.25)` sobre a tela inteira. Com o fundo 96% opaco o desfoque quase não aparecia, mas o navegador refazia o desfoque de tudo o que estava atrás a cada quadro da rolagem | `client/src/ui/styles.css` (`.ui-term`) |

Suspeitas descartadas com medição: a caixa de texto (menos de 1 ms por tecla), o spinner, as animações de CSS e o
tempo para abrir o terminal (~650 ms com 1500 linhas, igual em todas as versões).

## O que mudou

| Commit | Mudança |
| --- | --- |
| `4fdf1ec` | Com o terminal aberto numa tela de até 600 px, `world.setSuspended` pausa o desenho do escritório (o mesmo mecanismo que a cidade já usa). Volta ao fechar o terminal; ao girar o celular a regra é reavaliada. Entre 601 e 900 px (tablet) o escritório continua rodando, porque aparece nas bordas do terminal |
| `88bfdc9` → `6831087` | Primeiro `content-visibility: auto` nas linhas; desfeito porque o celular passava a montar e medir cada linha que chegava perto da tela durante a rolagem (layout de 16 ms para 258 ms na mesma rolagem). Ficou `contain: content` em `.ui-term__row` |
| `ba2a1ca` | `.ui-term` sem `backdrop-filter`. No celular (até 600 px), fundo opaco `#0a0c12` e sem sombra |

## Medições

Pixel 5 emulado no Chromium sem tela, CPU 4× mais lenta, 1500 linhas de histórico (a conversa real repetida). Cada
versão rodou 3 vezes, em ordem alternada; os valores são a faixa entre as rodadas. As etapas são cumulativas.

| Versão | CPU com terminal parado | Teclas com espera >16 ms (de 33) | Mensagem nova até aparecer (mediana) | Custo de desenhar cada quadro da rolagem |
| --- | --- | --- | --- | --- |
| Antes (`a8916cb`) | 92–101% | 19–32 | 20–52 ms | 9,6–11,7 ms |
| + escritório pausado (`4fdf1ec`) | 8–10% | 0–32 | 17–55 ms | 9,9–12,3 ms |
| + `contain: content` (`6831087`) | 4–7% | 0–31 | 17–39 ms | 9,9–12,2 ms |
| + sem desfoque (`ba2a1ca`) | 6–11% | 0–2 | 34–36 ms | 1,2–1,5 ms |

O navegador do teste desenha sem placa de vídeo: os números servem para comparar as versões entre si, não como tempo
real no aparelho.

## Pontos em aberto

- **`contain: content`:** nenhuma diferença na rolagem e na digitação; nas mensagens novas, o pior caso caiu de
  74–87 ms para 38–42 ms em duas das três rodadas, dentro da variação entre rodadas. Ele corta o que sai dos limites
  da linha (contornos de foco, por exemplo). Candidato a reverter se aparecer algum corte.
- **Thread principal na rolagem:** sem o desfoque, ela trabalha mais durante a rolagem (970–1130 ms contra
  630–1000 ms na mesma rolagem de ~3,7 s). Não chega a perder quadros; causa não investigada.
- **Aparelho de verdade:** nada foi medido num celular. O próximo passo, se voltar a travar, é medir no próprio
  aparelho pelo DevTools remoto (`chrome://inspect` com o celular ligado por USB).
- **iPhone/Safari:** não testado.

## Como medir de novo

1. Gere a interface de cada versão a comparar (`git worktree add`, `npx vite build --config vite.config.ts`) e sirva
   cada uma com `npx vite preview --config vite.config.ts --port <porta>`. O preview repassa `/api` para o servidor
   em `127.0.0.1:4747`.
2. Abra no Chromium emulando um celular (Pixel 5) com a CPU 4× mais lenta.
3. Para ter histórico grande sem esperar uma conversa longa, troque o `EventSource` do terminal na página por um falso
   que entrega um evento `init` com as entradas repetidas até 1500 linhas e expõe uma função para disparar `append`.
4. Meça, com o terminal aberto:
   - CPU parado: `TaskDuration` das métricas da página em 4 s.
   - Digitação: `PerformanceObserver` do tipo `event` (limite de 16 ms) nos `keydown`, olhando
     `processingStart - startTime`.
   - Mensagem nova: tempo do `append` até o quadro seguinte.
   - Rolagem: trace durante uma rolagem longa com a rodinha; o custo por quadro é a média de `Display::DrawAndSwap`.
5. Feche toda outra página do navegador de teste antes do trace: o compositor é compartilhado e uma página esquecida
   aberta distorce os números.
