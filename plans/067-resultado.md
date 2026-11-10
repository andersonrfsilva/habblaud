# Plano 067 — resultado dos passos 1–3

## Decisão

**Portão ABERTO por PSS.** O app empacotado, autenticado e com a conversa mais longa da fixture aberta, ficou em **338,9 MiB de PSS parado**. Mesmo com AppCDS dinâmico, ficou em **328,2 MiB**, acima do limite de **300 MiB**.

Os passos 1–3 estão concluídos. **O passo 4 não foi executado:** a atribuição limita esta execução aos passos 1–3; o coordenador confirmou que disparará o spike separadamente. Abrir o portão autoriza esse experimento delimitado, não uma reescrita.

Nenhuma flag permanente foi alterada. O coordenador aprovou manter `android/desktop/build.gradle.kts` intacto: os ajustes de heap/GC não atingiram os limiares; AppCDS atingiu o limiar de abertura, mas o arquivo treinado neste experimento depende deste pacote/runtime e está em `/tmp`. Não foi gravada uma flag apontando para um arquivo transitório ou inexistente na distribuição.

## Ambiente e escopo

- Worktree: `/tmp/habblaud-067`; branch: `advisor/067-zaplaud-desktop-medicao`.
- Base: `advisor/zaplaud-desktop-063-066`, commit `3255ff4` (`merge: integrate desktop distributable packages`). A verificação de desvio está satisfeita; o Gradle contém `nativeDistributions` e `jvmArgs`.
- Linux x86_64, kernel `7.2.11-arch1-1`; Intel Core i7-10700, 8 núcleos/16 threads; OpenJDK `21.0.13.0.2+1`.
- Xvfb `:67`, tela 1280×800, janela do app 1100×760; `LIBGL_ALWAYS_SOFTWARE=1`. Não é uma medição em uma sessão gráfica física do dono.
- Pacote real: `android/desktop/build/compose/binaries/main-release/app/Zaplaud/bin/Zaplaud`.
- Flags originais do pacote: `-XX:+UseSerialGC -Xmx256m`.
- Tamanho da árvore do pacote: `195872 KiB` por `du -sk`, equivalente a 191,3 MiB de disco alocado. Não é tamanho de instalador comprimido.
- Servidores isolados: portas **14767** e **14768**, com `--allow terminal,messages,permissions`. Cada servidor recebeu um HOME temporário próprio.
- Sem Secret Service: cookies somente em memória, como esperado neste ambiente. Login real pela interface em cada novo processo; nenhum endpoint de autenticação foi desativado.

## Método

Foi lido e reaproveitado `/tmp/zaplaud-desktop-spike/measure.py`: consulta X11 de `IsViewable`, leitura de `/proc/<pid>/smaps_rollup`, CPU por deltas de `/proc/<pid>/stat`, XTest e processo novo por execução. O adaptador e os dados brutos estão em `/tmp/zaplaud067`.

1. Três processos novos por configuração; mediana de cada coluna, sem forçar descarte do cache de arquivos do sistema.
2. Abertura: lançamento até a primeira janela `Zaplaud` ficar `IsViewable`. Não mede primeiro quadro útil, autenticação ou carregamento da conversa.
3. Cada processo fez login pela interface e abriu **Davi Fictício / `fixture:omp`**, sessão `00000000-0000-4000-8000-000000000002`, origem `http://127.0.0.1:14767`, conta `.omp`.
4. Essa é a maior sessão da fixture: 64 linhas JSONL, das quais 62 são mensagens. As outras têm 39–41 linhas. O endpoint de terminal entrega as últimas **50 entradas**; a tela informa que há histórico anterior não carregado e não há paginação. Não se trata de uma conversa grande de produção.
5. RSS/PSS parado: 10 s depois de abrir a conversa autenticada. O login impede usar os 10 s após a primeira janela como instante equivalente de memória; esse desvio é explícito.
6. CPU parada: amostras de 30 s, sem foco de teclado, ponteiro fora da janela; percentual de **um núcleo**, não dividido pelos 16 processadores lógicos.
7. Rolagem: 80 eventos XTest de roda **para cima**, espaçados em 25 ms, sobre a conversa; memória 3 s depois. O protótipo rolava para baixo; aqui a conversa abre no fim, portanto rolar para cima exercita a lista de fato.
8. Os flags experimentais entraram por `_JAVA_OPTIONS`, que prevalece sobre `-Xmx256m` do launcher. `jcmd <pid> VM.flags` registrou os valores efetivos; por exemplo, o teste de 192 MiB teve `MaxHeapSize=201326592`.
9. CPU iconificada: medição adicional no processo de dois servidores após o idle de 10 min, com três janelas temporais de 30 s. Não são três reinicializações do processo.

Um primeiro ensaio do adaptador foi descartado: ele guardava o identificador de uma janela inicial que o Compose substituiu, causando `BadWindow` no XTest. O adaptador passou a consultar novamente a janela antes das interações. Nenhum número desse ensaio entrou nas tabelas.

## Passo 1 — app empacotado real

Todos os valores abaixo são medianas de três processos novos. Memória em MiB, dividida por 1024 a partir dos KiB do kernel.

| Configuração | Abertura (ms) | RSS parado | PSS parado | RSS após rolar | PSS após rolar | CPU parada sem foco (%) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Original: SerialGC, Xmx256m | 1020,3 | 359,3 | 338,9 | 373,5 | 356,2 | 3,10 |
| Xmx192m | 932,2 | 338,9 | 321,4 | 345,5 | 327,9 | 3,50 |
| SerialGC + razões de heap 20/10 | 862,3 | 385,1 | 367,9 | 393,1 | 376,0 | 3,77 |
| Xmx192m + SerialGC + razões 20/10 | 1037,6 | 378,3 | 360,9 | 385,2 | 364,6 | 3,53 |
| CDS base, sem AppCDS dinâmico | 838,7 | 388,3 | 372,9 | 403,3 | 388,1 | 3,67 |
| AppCDS dinâmico, Xmx256m/SerialGC originais | 524,3 | 343,5 | 328,2 | 363,1 | 348,0 | 3,03 |

### Idle prolongado com dois servidores — fixture, não uso do dono

O processo `326953`, com as flags originais e **ambos os servidores autenticados simultaneamente**, permaneceu sem interação por **600,0005 s**. A interface mostrou os dois servidores conectados e a conversa OMP selecionada.

| Cenário | RSS (MiB) | PSS (MiB) | CPU sem foco (%) |
| --- | ---: | ---: | ---: |
| Dois servidores fixture, antes do idle | 441,4 | 426,2 | — |
| Dois servidores fixture, após 10 min | 355,4 | 340,3 | 1,90 |
| Mesmo processo, depois das amostras iconificadas | 356,0 | 340,7 | ver abaixo |
| Uso real do dono por 1 h com dois servidores | **não realizado** | **não realizado** | **não realizado** |

A redução de memória durante esse idle não prova ausência de vazamentos no uso real. Não houve envio de mensagens, carga de produção ou uma hora de uso humano.

Para iconificar de verdade, foi extraído `twm` e suas bibliotecas somente sob `/tmp/zaplaud067/twm`; nenhum pacote do sistema foi instalado. O gerenciador foi iniciado **depois** do idle prolongado, sem alterar as comparações anteriores. `XIconifyWindow` retornou 1 e `XGetWindowAttributes` confirmou `map_state=0` (janela não mapeada); a captura mostrou apenas o ícone.

CPU iconificada, três amostras consecutivas de 30 s: **0,1000%; 0,1000%; 0,0333%**. Mediana: **0,10%**, abaixo de 1%. A CPU sem foco da janela visível não foi confundida com CPU minimizada. A fixture mantém agentes em estado `working`, com animação na interface visível.

## Passo 2 — ajustes baratos

| Ajuste | Variação de PSS parado | Redução da abertura | Resultado |
| --- | ---: | ---: | --- |
| `-Xmx192m` | −5,16% | 8,63% | Não atinge PSS ≥10% nem abertura ≥20%; não manter. |
| `-XX:+UseSerialGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10` | +8,58% | 15,49% | Piora PSS e não atinge abertura ≥20%; não manter. |
| Combinação dos dois ajustes | +6,49% | −1,69% | Piora ambos; não manter. |
| CDS base | +10,05% | 17,79% | Não atinge os limiares; não manter. |
| AppCDS dinâmico | −3,15% | **48,62%** | Passa no limiar de abertura; ganho **experimental**, não integrado ao pacote. |

Não foi encontrado `OutOfMemoryError` nos logs das três execuções de cada variante de heap/GC, das execuções AppCDS e do processo prolongado. Isso vale para a maior conversa **desta fixture**, não para qualquer histórico real.

### AppCDS: geração e evidência

A imagem jlink original não traz `classes.jsa`. Foi necessário gerar primeiro uma base estática pelo próprio launcher com:

```sh
_JAVA_OPTIONS='-Xshare:dump -XX:SharedArchiveFile=/tmp/zaplaud067/base.jsa -Xlog:cds=info' \
  android/desktop/build/compose/binaries/main-release/app/Zaplaud/bin/Zaplaud
```

Depois, uma execução autenticada, com a conversa aberta e rolagem, treinou o arquivo dinâmico:

```sh
python3 /tmp/zaplaud067/run.py cds-train \
  '-XX:SharedArchiveFile=/tmp/zaplaud067/base.jsa -XX:ArchiveClassesAtExit=/tmp/zaplaud067/app.jsa -Xlog:cds=info' 1
python3 /tmp/zaplaud067/run.py appcds \
  '-XX:SharedArchiveFile=/tmp/zaplaud067/base.jsa:/tmp/zaplaud067/app.jsa -Xlog:cds=info'
```

O dump dinâmico registrou **8496 classes**, incluindo **6454 classes do app**. O log de medição confirmou `Mapped static region` e `Mapped dynamic region`, portanto não foi uma execução com fallback silencioso para o modo sem AppCDS.

**Decisão de integração aprovada pelo coordenador:** deixar o Gradle intacto. Uma flag sozinha apontando para `/tmp` não distribui os arquivos necessários; gerar, invalidar e empacotar o arquivo por runtime/build ultrapassa o escopo de alteração de flags. O ganho fica registrado sem criar um pacote quebrado.

Caminho futuro, **não validado neste trabalho**: em JDK 19+, considerar `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$APPDIR/...` apenas para uma instalação gravável pelo usuário. É necessário também resolver a base CDS ausente da imagem atual e a invalidação por versão do pacote/runtime. Uma instalação de sistema não gravável pelo usuário não serve para esse caminho.

## Diagnóstico adicional — quanto é heap Java e quanto é nativo

Foi feita uma execução adicional com `-XX:NativeMemoryTracking=detail` para obter os intervalos de endereços. Foram coletados `jcmd <pid> VM.native_memory summary`, `VM.native_memory detail`, `GC.heap_info`, `VM.flags`, `smaps` e `smaps_rollup`. Esse processo diagnóstico não entrou nas medianas nem recebeu AppCDS.

NMT mede memória reservada/comprometida da JVM, **não PSS**. Para não confundir as duas medidas, os intervalos de NMT foram cruzados com os mapas de `smaps`:

| Componente | PSS observado (MiB) |
| --- | ---: |
| Total do processo diagnóstico | 394,6 |
| Intervalo reservado ao heap Java | **90,7** |
| Fora do heap Java, por diferença | **303,9** |
| Metaspace: mapas inteiramente contidos no intervalo NMT | 53,9 |
| Class space: mapas inteiramente contidos no intervalo NMT | 6,4 |
| Código: mapas inteiramente contidos nos intervalos NMT | pelo menos 20,4 |
| Outros mapas e alocações não atribuídos acima | 223,3 |

Um mapa de 300 KiB de PSS cruza parcialmente um intervalo de código; ele ficou no restante, sem rateio inventado. Os mapas de Skiko (`libskiko-linux-x64.so`) representam **8,3 MiB** de PSS; isso **não** mede todas as alocações de Skia. LLVM e Gallium mapeados representam, respectivamente, **18,4 MiB** e **9,9 MiB**; esses mapas pertencem ao restante da tabela, não são parcelas adicionais.

A JVM informou heap **reservado de 256 MiB**, **comprometido de 248,1 MiB** e **ocupado de 30,7 MiB**. Portanto, chamar os 248,1 MiB de heap comprometido de “PSS do heap” seria incorreto: o PSS residente do intervalo era 90,7 MiB.

NMT reportou 372,0 MiB comprometidos no total e 3,2 MiB de custo próprio de rastreamento. Bibliotecas nativas de terceiros, inclusive alocações internas de Skia, não são integralmente rastreadas por NMT. Não é possível atribuir os 223,3 MiB restantes a Skia com essa coleta. O total diagnóstico maior que a mediana também não deve ser apresentado como uma nova mediana: é outro processo, com instrumentação e uma única amostra.

## Passo 3 — avaliação do portão

| Condição do plano | Observado | Abre? |
| --- | --- | --- |
| PSS parado >300 MiB após os ajustes | Original 338,9 MiB; até o AppCDS experimental fica em 328,2 MiB | **Sim** |
| Abertura >2 s | Original 1,020 s; AppCDS 0,524 s neste host/Xvfb | Não nesta medição; sessão gráfica real do dono não medida |
| CPU parada >1% minimizada | 0,10%, mediana de três amostras de 30 s no processo de dois servidores iconificado | Não |
| Dono diz que o consumo incomoda | Informação desconhecida; não presumida | Não usada |

**Resultado: portão ABERTO somente pelo critério medido de PSS.** Não é correto encerrar com “Rust não justificado”, pois uma condição numérica foi satisfeita. O spike Rust/Slint deve ser uma execução separada, limitada às três dúvidas e ao orçamento do passo 4.

## Comandos e evidências

```sh
npm ci
cd android
./gradlew :desktop:createReleaseDistributable --max-workers=4
# BUILD SUCCESSFUL, 23 tarefas executadas, 14 s.
```

Servidores, na raiz da worktree:

```sh
node --import tsx scripts/android-smoke.ts --port 14767 --allow terminal,messages,permissions
node --import tsx scripts/android-smoke.ts --port 14768 --allow terminal,messages,permissions
```

Medições, depois da correção do adaptador:

```sh
python3 /tmp/zaplaud067/run.py baseline-clean
python3 /tmp/zaplaud067/run.py heap192 '-Xmx192m'
python3 /tmp/zaplaud067/run.py ratios '-XX:+UseSerialGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10'
python3 /tmp/zaplaud067/run.py combined '-Xmx192m -XX:+UseSerialGC -XX:MaxHeapFreeRatio=20 -XX:MinHeapFreeRatio=10'
python3 /tmp/zaplaud067/run.py basecds '-XX:SharedArchiveFile=/tmp/zaplaud067/base.jsa -Xlog:cds=info'
python3 /tmp/zaplaud067/nmt.py
python3 /tmp/zaplaud067/soak-sample.py 326953
```

Evidências temporárias: `/tmp/zaplaud067/{baseline-clean,heap192,ratios,combined,basecds,appcds}.json`, `medians.json`, logs e capturas por execução; `soak/result.json`, `soak/smaps_rollup`, `soak/iconified.json`; `native-memory/{summary,detail,heap,flags}.txt`, `smaps`, `smaps_rollup` e `pss-attribution.json`. Esses arquivos são temporários; os números e limites relevantes estão preservados neste relatório.

Os logs mostram fallback de Skiko após `Cannot create Linux GL context`, além das mensagens `pure virtual method called` / `terminate called without an active exception` do lançamento. Apesar disso, os processos medidos abriram, autenticaram, renderizaram a conversa e permitiram rolagem. Não se declara uma sessão gráfica nativa sem avisos.

## Limites e arquivos permanentes

- Não houve 1 h de uso real do dono, nem avaliação de Wayland/macOS, GPU física, envio/streaming contínuo ou históricos de produção.
- Nenhuma condição subjetiva do dono foi inferida.
- Nenhuma mudança permanente em Gradle, código, testes ou fixtures. Apenas este resultado é adicionado na worktree e copiado para o repositório principal, conforme autorizado.
- `plans/README.md` não foi alterado: o índice pertence ao revisor.
- Nenhum push ou merge. O passo 4 permanece fora desta entrega por instrução explícita.

## Passo 4: spike Rust + Slint

Executado separadamente após os passos 1–3, com autorização específica para este apêndice. As declarações anteriores de que o passo 4 não foi executado descrevem a entrega original, não este complemento. O experimento encerrou após responder às três dúvidas e coletar as três medições; não virou implementação de cliente.

### Ambiente, versões e escopo

- Código, harness, capturas e resultados em `/tmp/zaplaud-rust-spike`; nenhum código do projeto principal foi alterado.
- Fonte da fixture: `/tmp/habblaud-desktop`, branch `advisor/zaplaud-desktop-063-066`, commit `3255ff4`.
- Rust `1.96.1`, Cargo `1.96.1`; release com `strip = true` e LTO `thin`.
- Crates diretas resolvidas: `slint 1.18.1`, `pulldown-cmark 0.13.6`, `reqwest 0.12.28` e `serde_json 1.0.154`. Versões transitivas preservadas em `Cargo.lock`.
- Slint com `std`, `backend-winit-x11`, `renderer-software` e `compat-1-2`, sem features padrão. Backend efetivo: `winit-software`, X11, Xvfb `localhost:69`, tela 1280×800 e janela 1100×760.
- Servidor real de teste: `node --import tsx scripts/android-smoke.ts --port 14769 --allow terminal,messages,permissions`, iniciado na worktree integrada. O próprio servidor criou HOME temporário isolado.
- O primeiro lançamento revelou a dependência dinâmica `libxkbcommon-x11.so`; os lançamentos seguintes usaram `LD_LIBRARY_PATH=/tmp/zaplaud-desktop-spike/tools/usr/lib`. Nenhum pacote de sistema foi instalado.
- Markdown extraído do JSONL real de `fixture:claude`, sessão `00000000-0000-4000-8000-000000000001`: 39 registros, 34 trechos de texto, 3.082 bytes de Markdown, 90 blocos renderizados, 16 blocos de código e 2 linhas de tabela. Não foi necessário inventar uma tabela. O conteúdo foi extraído antes do lançamento; somente a lista usa rede ao vivo.

### Respostas às três dúvidas

| Dúvida | Resposta | Evidência observada |
| --- | --- | --- |
| Código e tabela Markdown com seleção e cópia | **Sim, por bloco/célula; renderizador completo ainda parcial.** | Componentes Slint próprios, alimentados por `pulldown-cmark`, mostram título, listas, texto com código inline, bloco monoespaçado e tabela de duas colunas. XTest fez Ctrl+A/C no bloco e Ctrl+V no compositor; o log recebeu a linha `printf`. A mesma sequência na célula recebeu `Revisão`. Capturas: `initial.png`, `code-selection.png`, `code-copy-paste.png`, `table-selection.png`, `table-copy-paste.png`. |
| Teclas mortas pt-BR em Slint TextInput | **Sim em X11 neste ambiente. Wayland: NOT TESTED. macOS: NOT TESTED.** | `xkbcomp` carregou `us(intl)`; XTest enviou tecla morta aguda seguida de `a`, Shift+tecla morta til seguida de `a` e AltGr+vírgula. O callback registrou exatamente `INPUT "á ã ç"`. Captura: `deadkeys.png`. Não houve inserção direta de Unicode nem uso do clipboard para esse teste. |
| Lista de sessões via login/cookie/SSE | **Sim.** | POST `/api/remote/login` com JSON `user`/`password` da conta de teste retornou `ok` e cookie `habblaud_remote`. GET `/api/stream` com esse cookie retornou HTTP 200 e `text/event-stream`. O primeiro `snapshot` mostrou seis sessões. `permission fixture:claude bash` no stdin retornou `ok`; o segundo snapshot mudou Ana Fictícia de `idle` para `waiting · aprovação pendente`, visível em `sse-permission.png`. |

Os títulos vêm de `title`, com fallback para `name`, e a seleção de agentes segue o recorte de `SessionRepository.snapshot`: `kind=main`, sem `parentId`, com `account` e `sessionId`. O experimento prova atualização do estado da linha mantendo seu título, não uma operação de renomeação. O parser SSE lê eventos delimitados por linha vazia e concatena campos `data:`; não trata um chunk HTTP como um evento inteiro. O valor do cookie não foi registrado nem persistido.

Limites concretos do renderizador: seleção e cópia são locais a cada TextInput, não atravessam blocos ou células; a tabela tem duas colunas fixas. Código longo fica recortado visualmente, embora Ctrl+A/C copie a linha completa. A quebra final de linha vira espaço ao colar no compositor de linha única. O estilo do código inline é aplicado ao parágrafo inteiro; negrito/itálico não foram preservados, listas ordenadas viraram marcadores e uma lista aninhada ficou concatenada. Links e citações aparecem como texto. Portanto, o spike responde à viabilidade dos componentes selecionáveis, não prova fidelidade Markdown ou acessibilidade de um cliente pronto. Não há reconexão SSE, histórico via HTTP, envio de mensagem, autenticação multi-servidor ou persistência.

### Medição — mediana de três processos novos

Método reaproveitado de `/tmp/zaplaud-desktop-spike/measure.py`: lançamento até janela X11 `IsViewable`, RSS/PSS em `/proc/<pid>/smaps_rollup`, CPU por deltas de `/proc/<pid>/stat` durante 30 s, sem foco, e 80 eventos de roda XTest. Cada processo abriu a conversa completa extraída e autenticou a lista automaticamente. Memória foi amostrada 10 s após o mapeamento, com o primeiro snapshot já recebido; depois da rolagem, esperou-se 3 s. Não é tempo até o primeiro quadro útil, nem tempo de login.

| Configuração | Abertura (ms) | RSS parado (MiB) | PSS parado (MiB) | RSS após rolar (MiB) | PSS após rolar (MiB) | CPU parada, um núcleo (%) | Artefato (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Compose real, original — passos 1–3 | 1020,3 | 359,3 | 338,9 | 373,5 | 356,2 | 3,10 | 191,3, árvore do pacote |
| Compose real, AppCDS experimental — passos 1–3 | 524,3 | 343,5 | 328,2 | 363,1 | 348,0 | 3,03 | 191,3, sem contar arquivos CDS |
| Rust + Slint, este recorte | **113,6** | **27,25** | **22,39** | **27,43** | **22,53** | **0,00** | **16,74, ELF stripped** |

Execuções Rust individuais:

| Execução | Abertura (ms) | RSS parado (KiB) | PSS parado (KiB) | CPU parada (%) |
| --- | ---: | ---: | ---: | ---: |
| 1 | 96,56 | 27804 | 22896 | 0,00 |
| 2 | 113,60 | 27900 | 22924 | 0,00 |
| 3 | 151,73 | 28076 | 23058 | 0,00 |

O binário tem exatamente **17.554.320 bytes**; `file` confirmou ELF x86_64 dinamicamente ligado e `stripped`. O tamanho não inclui bibliotecas de sistema, fontes, fixture nem `conversation.md`; não equivale a um pacote autossuficiente comparável à árvore Compose. CPU 0,00 significa nenhum tick contabilizado nas janelas amostradas, não custo absoluto nulo.

O PSS deste recorte ficou 93,4% abaixo do Compose original e 93,2% abaixo do Compose com AppCDS. Essa diferença **não estima o cliente Rust em paridade**: o spike não tem animações de agentes ativos, avatares, controladores, armazenamento ou as demais telas. A CPU visível do Compose inclui animações; sua mediana minimizada já era 0,10%. As conversas também diferem: este teste usa Claude com Markdown; o teste Compose usou a maior sessão OMP da fixture.

### Evidências e reprodução

Todos os caminhos abaixo são relativos a `/tmp/zaplaud-rust-spike` e temporários:

- Código: `Cargo.toml`, `Cargo.lock`, `src/main.rs`; texto original: `conversation.md` e sua origem em `fixture-source.json`.
- Provas interativas: `events.log`, `interaction-evidence.json`, `logs/interactive.log` e as sete capturas citadas acima.
- Medições: `exercise.py`, `measurements.json`, `medians.json`, `logs/measure-{1,2,3}.log`, `measure-{1,2,3}.png`.
- Comandos executados: `cargo build --release -j 4`; `python3 /tmp/zaplaud-rust-spike/exercise.py measure`; `file target/release/zaplaud-rust-spike`; `stat -c '%s bytes' target/release/zaplaud-rust-spike`; `cargo tree -e normal --depth 1`.
- Layout: `/tmp/zaplaud-desktop-spike/tools/usr/bin/xkbcomp -I/usr/share/X11/xkb /tmp/zaplaud-rust-spike/us-intl.xkb localhost:69`. Houve avisos sobre teclas multimídia acima do limite do X11; as três sequências alvo funcionaram.
- O harness reutiliza o leitor X11 de `measure.py`; os helpers interativos foram chamados pelo executor com XTest, conforme registrado em `interaction-evidence.json`.

### Custo de paridade pelo inventário do plano

Estimativa de engenharia, não medição nem prazo contratado: **27 unidades de comportamento, 7 S / 14 M / 6 L**. A unidade é cada capacidade nomeada abaixo, não cada arquivo; as cerca de 25 rotas formam uma unidade L, não 25 itens S.

| Tamanho | Itens a refazer | Contagem |
| --- | --- | ---: |
| S | Login; aviso de HTTP; busca; filtro por servidor; resumo; anel de contexto; shake | 7 |
| M | Vários servidores; abertas/recentes; não lidas; subagentes; pensamentos; ferramentas compactas; ir para o fim; rascunho persistente; modos de envio; interrupção; configurações; papéis; avatares/cache; regras de aviso | 14 |
| L | Markdown fiel com seleção/rolagem; anexos; aprovação; elenco; bate-papo paralelo; contrato HTTP/SSE de cerca de 25 rotas e respectivos estados de erro | 6 |

Essas unidades continuam exigindo integração e testes de regressão; os três experimentos não encerram esses itens. Em especial, seleção global, tabela genérica, texto rico fiel e validação de entrada nas outras plataformas permanecem custos abertos. As 4.118 linhas citadas no inventário não são uma estimativa de linhas Rust.

**Recomendação: não reescrever agora — o ganho do recorte é grande, mas 27 capacidades sem paridade e Wayland/macOS não testados não justificam duplicar o cliente.**
