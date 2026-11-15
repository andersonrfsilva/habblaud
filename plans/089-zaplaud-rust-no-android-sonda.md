# Plan 089: Cliente Rust (Zaplaud desktop) no Android — sonda concluída, projeto BLOQUEADO

> **BLOQUEADO por decisão do dono em 2026-11-14. Não execute.** Este arquivo é o registro da sonda e das condições
> para retomar. O código da sonda está em `producao` (opcional, só compila para Android com `cargo-apk`); o cliente
> Android oficial continua sendo o Kotlin (`android/`). Nenhum executor deve continuar o porte, publicar o APK,
> trocar o cliente dos aparelhos ou remover código Kotlin sem o dono desbloquear este plano.

## Status

- **Priority**: P3
- **Effort**: L (para virar produto)
- **Risk**: MED
- **Depends on**: decisão do dono
- **Category**: direction (spike)
- **Planned at**: commit `554eb75`, 2026-11-14

## Pergunta que a sonda respondeu

Vale a pena compilar o cliente Rust + Slint (`zaplaud-rs/`) para Android no lugar do cliente Kotlin, para manter um
cliente só em todas as plataformas?

## O que foi feito

- O cliente Rust ganhou um ponto de entrada Android (`cdylib` com `android_main`, backend `android-activity` do Slint
  1.18.1, renderizador Skia/OpenGL: o renderizador por software do desktop não existe no Android).
- Partes só de desktop ficaram atrás de `cfg(not(target_os = "android"))` com substitutos vazios: bandeja,
  notificações, seletor de arquivos, abrir/revelar arquivos externos, instância única, chaveiro do sistema (trocado
  por um arquivo privado do app). O bloqueio de arquivo do armazenamento usa `flock` no Android.
- A tela de login própria funciona no Android; o layout respeita as áreas seguras e o teclado virtual; o botão/gesto
  de voltar retorna à lista.
- Como construir, assinar, instalar e abrir: `zaplaud-rs/android-probe-notes.txt`. Id do aplicativo:
  `br.com.xlinx.zaplaud.rsprobe` (convive com o app Kotlin `br.com.xlinx.habblaud`).

## Medição (Samsung M54, Android 16, no carregador, servidor de teste do repositório, 45 s por fase, uma rodada)

| Fase | CPU Kotlin | CPU Rust | Memória (PSS) Kotlin | Memória (PSS) Rust |
|---|---|---|---|---|
| Lista de conversas parada | 5,4% | 3,6% | 90 MB | 133 MB |
| Conversa aberta, parada | 0,7% | 0,04% | 91 MB | 151 MB |
| Conversa recebendo 2 mensagens/s | 7,8% | 5,3% | 100 MB | 152 MB |

| | Kotlin | Rust |
|---|---|---|
| APK | 2,37 MB | 11,6 MB |
| Espaço instalado | 2,3 MB | 35,8 MB |
| Abertura até o primeiro quadro | ~165 ms | 240–415 ms |

CPU é a fração de um núcleo usada pelo processo. Limites: uma rodada de cada; a sonda não tem notificações nem
serviço em segundo plano, que o Kotlin mantém; o trabalho da GPU (Skia) não aparece no tempo de CPU; bateria não foi
medida.

## Conferido à mão por automação (Samsung M35, servidor de teste)

Teclado aparece e digita; caixa de mensagem acima do teclado; login; lista; abrir conversa por toque; rolar; enviar
mensagem; voltar pela seta e pelo botão do Android; menu de três pontos; sair e voltar ao app.

## O que falta para ser um cliente de verdade

- Notificações e serviço em segundo plano (exigem uma casca Kotlin/Java).
- Anexos (seletor de arquivos) e downloads; abrir arquivo ou link fora do app.
- Credenciais no cofre do Android (hoje: arquivo privado do app).
- Não testado: HTTPS, giro de tela, morte do processo em segundo plano, leitor de tela (acessibilidade), fluidez de
  rolagem, iOS (o Slint 1.18.1 declara dependências para iOS; nada foi compilado).
- Publicação: assinatura de release própria, ícone, nome, `versionCode`.

## Conclusão registrada

- Por **recursos**, não há ganho que justifique: menos CPU, porém mais memória, mais espaço e abertura mais lenta.
- O argumento que pesa é **manutenção** (um cliente em vez de dois: nos 60 dias anteriores, 77 commits no Kotlin
  compartilhado e 95 no Rust). Contra: o Kotlin é o cliente menor (~6,6 mil linhas, Android e iOS, contra ~21 mil do
  Rust) e a parte de plataforma continuaria existindo como ponte.

## Condições para desbloquear

O dono decide. Se retomar, a próxima etapa é uma sonda de sim ou não, antes de qualquer migração:

1. Notificação e serviço em segundo plano funcionam por uma casca Kotlin fina sobre a biblioteca Rust?
2. O mesmo código compila e abre no iOS?
3. Uso real por alguns dias no aparelho do dono sem defeito de teclado, rolagem ou ciclo de vida?

Alternativa mais barata, se a primeira falhar: compartilhar só as regras (o `zaplaud-core` chamado pelo Kotlin),
mantendo a interface nativa.

## Maintenance notes

- O ponto de entrada Android e os `cfg(target_os = "android")` não afetam o build de desktop; os portões de desktop
  (clippy, testes do núcleo e do armazenamento, build release) passaram depois da sonda.
- Ferramentas instaladas no computador de desenvolvimento para a sonda: NDK do Android, alvo Rust
  `aarch64-linux-android` e `cargo-apk`. Não são necessárias para o desktop.
- Regra de paridade (`AGENTS.md`): enquanto este plano estiver bloqueado, mudança de comportamento continua sendo
  feita no Kotlin **e** no Rust desktop; a sonda Android não é um terceiro cliente a manter.
