# Zaplaud desktop nativo

Cliente Rust + Slint para Linux e macOS. O comportamento do cliente Kotlin em `android/shared/src/commonMain` é a referência. A troca do desktop JVM procura reduzir o consumo de memória: metas de menos de 100 MiB PSS parado e menos de 300 ms até a janela mapeada. Não são garantias para qualquer máquina.

Referências para as próximas fases: [rotas e streams](docs/contrato/01-rotas-e-streams.md), [regras e estado](docs/contrato/02-regras-e-estado.md), [telas e fixture](docs/contrato/03-telas-e-fixture.md). São um mapa de consulta; em divergência, o Kotlin vence.

## Construir, testar e executar

Requisitos: Rust 1.96.1 (fixado em `rust-toolchain.toml`), ferramentas nativas de compilação e uma sessão gráfica X11 ou Wayland. No macOS, instale as ferramentas de linha de comando do Xcode. O renderer padrão é `software` do Slint, com emoji monocromático embutido; não exige OpenGL nem fonte emoji instalada.

O perfil release usa `opt-level = "s"`, LTO `fat`, uma unidade de geração de código e `panic = "abort"`. O transporte HTTPS preserva raízes nativas e webpki. `notify-rust ~4.18` compartilha zbus 5; a bandeja mantém `ksni` com `async-io`.

Na raiz do repositório, `npm ci` instala o servidor de contrato usado pelos testes. Depois:

```sh
cd zaplaud-rs
cargo fmt --check
cargo clippy --workspace --all-targets -j 4 -- -D warnings
cargo test --workspace -j 4
cargo build --release -j 4
cargo run --release -p zaplaud-ui --bin zaplaud
```

Em Linux sem tela, use `xvfb-run -a cargo test --workspace -j 4`. Os testes de contrato abrem uma porta livre e iniciam `scripts/android-smoke.ts`; quando Node não está disponível, mostram explicitamente a razão da omissão. Sem as dependências Node instaladas, o teste falha em vez de simular o servidor.

O servidor local de desenvolvimento pode ser iniciado da raiz:

```sh
node --import tsx scripts/android-smoke.ts --port 14780 --allow terminal,messages,permissions
```

Use usuário e senha fictícios `android-test`. HTTP exige confirmação na tela; HTTPS não. O cliente não segue redirecionamentos. HTTPS confia nas CAs do sistema e nas raízes webpki, inclusive CAs privadas instaladas localmente. A API aceita persistência de cookies pelo contrato `SessionPersistence`; `SecureSessionStorage` usa o chaveiro do sistema, com fallback em memória quando indisponível. `ZAPLAUD_KEYRING=memory` força o modo sem persistência entre processos. Entradas existentes malformadas ou de versão desconhecida no chaveiro impedem gravações, sem apagar cookies de outras origens.
Respostas `429` bloqueiam novas tentativas por no máximo 24 h, tanto para segundos quanto para datas HTTP. Essa proteção difere do Kotlin atual, que ainda aceita prazos sem esse limite; o cliente Kotlin não foi alterado.

## Distribuição Linux

O pacote de usuário `zaplaud-linux-x86_64.tar.gz` contém o executável release, o instalador, os ícones hicolor gerados do SVG canônico e as licenças de Noto Sans Mono, Noto Emoji e DejaVu. Extraia e execute `bash install-linux.sh`, sem `sudo`. Consulte [distribuição desktop](packaging/README.md) para instalação, remoção e bibliotecas de execução.

O pacote usa `software` do Slint e não inclui Skia. Para emoji colorido, compile com `--features skia` e execute com `ZAPLAUD_RENDERER=skia-software`; a cor exige fonte emoji do sistema. O tarball final local requer glibc 2.43 ou posterior; isso difere da base Ubuntu 24.04 do CI.


## Arquitetura e propriedade dos módulos

| Arquivo ou módulo | Responsabilidade e limite de alteração |
| --- | --- |
| `crates/zaplaud-core/src/model.rs` | Modelo compartilhado, tipos de eventos e conversão do contrato Kotlin/servidor. Não deve depender de Slint. |
| `crates/zaplaud-core/src/api.rs` | HTTP, política de origem, autenticação, SSE e contrato `SessionPersistence`. Recebe a persistência por injeção; não toca widgets nem disco diretamente. |
| `crates/zaplaud-core/src/error.rs` | Erro compartilhado do núcleo. |
| `crates/zaplaud-core/src/rules/` | Regras puras Kotlin, redutores e efeitos tipados do compositor, asides e permissões; `list.rs` exporta ordenação e marcadores de leitura para o controlador da lista. |
| `crates/zaplaud-core/tests/` | Contrato com servidor real e payloads capturados sem segredos. |
| `crates/zaplaud-store/src/` | `LocalStateStore` persiste servidores, sessões, mensagens e preferências; `SecureSessionStorage` implementa `SessionPersistence`; `AvatarDiskCache` limita o cache de avatares. Veja [o contrato de armazenamento](crates/zaplaud-store/README.md). |
| `crates/zaplaud-ui/src/` e `ui/` | Janela, apresentação e ponte assíncrona; dependem dos contratos de core, não dos detalhes do servidor. |
| `crates/zaplaud-ui/src/markdown/` e `ui/markdown/` | Parser e componente Slint de Markdown nativo, sem WebView. |
| `crates/zaplaud-ui/build.rs` e `resource_gen.rs` | Geração de recursos Kotlin; não edite os resultados em `OUT_DIR`. |
| `.github/workflows/zaplaud-rs.yml` | Gates de formatação, clippy e testes de contrato. |

Coordenadores futuros devem dividir trabalho por esses limites. Mudanças na API de `model` ou `api` precisam atualizar todos os consumidores no mesmo corte. Não mantenha aliases de compatibilidade nem versões paralelas da mesma regra.

## Ponte assíncrona

Slint pertence à thread principal. A rede roda em um runtime Tokio mantido por uma thread de fundo. Callbacks da UI enviam comandos para esse runtime, sem bloquear o event loop. A tarefa de login abre o stream e consome snapshots reais.

A janela é mostrada antes de abrir `LocalStateStore`, `AvatarDiskCache` e carregar o tema salvo na thread de fundo. `ZAPLAUD_THEME` define o tema inicial sem ler disco; depois a thread publica o tema persistido no event loop. O encerramento descarrega `window_state::flush`, envia `Event::Shutdown` e aguarda a thread; `app::run` mantém a descarga assíncrona dos marcadores e de `composer::flush`.

Para atualizar a UI, capture um `Weak` do componente e dados próprios (`String`, `Vec`, tipos do núcleo). Envie a atualização com `slint::invoke_from_event_loop`; somente dentro dessa closure faça `upgrade()` e altere propriedades/modelos Slint. Não envie componentes nem modelos Slint para tarefas Tokio. Se a janela já fechou, descarte a atualização. Encerre a tarefa anterior ao iniciar outra conexão; não deixe dois streams publicarem na mesma lista.

Os redutores `rules::{composer,aside,permission}::reduce(State, Event) -> (State, Vec<Effect>)` não executam I/O. A ponte executa os efeitos tipados e devolve o evento de conclusão correspondente. `composer::Effect::Save` aplica `composer::update_message` dentro da transação serializada de `MessageStore`, sem cancelar a gravação ao sair do primeiro plano. Rascunhos usam debounce de 300 ms e são descarregados ao encerrar; preserve a ordem dessas gravações. O POST só sai depois da confirmação da operação `unknown` persistida. `CheckRecipient` reavalia `composer::can_send` antes de cada upload e do POST; não reutilize o resultado inicial. `ReleaseUnused` não remove arquivos originais nem exige leituras do armazenamento.

Datas recebem `now` explicitamente; os helpers `*_in` aceitam um fuso para testes determinísticos. Horários fora da faixa representável pelo Chrono retornam `None`, nunca consultam o relógio para substituir a entrada. `conversation::summary_text` retorna unidades UTF-16 para preservar exatamente o corte Kotlin de 32768 unidades, inclusive um surrogate final. A ponte deve codificar esse vetor como string JSON com escapes UTF-16, sem corte por bytes.

`attachments::guess_mime` recebe a base MIME da plataforma: o JVM usa `Files.probeContentType`, cujo resultado depende do sistema operacional. Não existe uma allowlist MIME Kotlin para copiar. A regra usa a extensão sem diferenciar maiúsculas e retorna `application/octet-stream` quando desconhecida.

Os goldens em `crates/zaplaud-core/tests/golden/` foram produzidos executando as funções Kotlin reais em um teste JVM temporário, com `./gradlew :shared:jvmTest --tests br.com.xlinx.habblaud.RustRulesGoldenTest --max-workers=4`. Entradas: os três JSON de `tests/fixtures`; saídas: filtros/agrupamento e transições de notificações. `platform-kotlin.json` também fixa o formato pt-BR e amostras MIME do JVM Linux. O teste temporário e `local.properties` não fazem parte do código entregue.

## Recursos gerados

`build.rs` lê `android/shared/src/commonMain/composeResources/values/strings.xml`, os blocos `lightColorScheme(...)`/`darkColorScheme(...)` de `ZaplaudApp.kt` e todos os XMLs de `composeResources/drawable/`. Gera textos Rust, o global Slint `Theme` com as duas paletas e SVGs de todos os vetores. As entradas têm `cargo:rerun-if-changed`; a lista de drawables também é observada para detectar novos arquivos.

Não copie textos, cores ou paths vetoriais à mão para a UI. Preserve escapes XML e placeholders posicionais `%1$s`/`%1$d` na geração. A ausência de cores reconhecidas é erro de build, não motivo para uma paleta substituta silenciosa. Os testes comparam a quantidade de textos, as cores primárias conhecidas e a validade dos SVGs gerados.

A fonte `ui/fonts/NotoSansMono-Regular.ttf` é importada pelo Slint para o Markdown monoespaçado; sua licença acompanha o arquivo. A geração conserva `ui/native-strings.xml`, os recursos do compositor e o ícone ARGB da bandeja; fontes e textos nativos permanecem sob a entrada `ui` observada pelo build.

Os ícones `Icons.Default` não existem nos XML Kotlin. `scripts/DumpMaterialIcons.java` extrai os `ImageVector` da dependência Compose resolvida e gera `ui/widgets/material-icons.slint` (`MaterialIcons`) e seus SVGs. Não redesenhe paths: execute o extrator com o classpath `android/desktop/build/compose/binaries/main/app/Zaplaud/lib/app/*`, diretório de saída `zaplaud-rs/crates/zaplaud-ui/ui/widgets` e os nomes PascalCase dos ícones. Os vetores Material mantêm a licença Apache 2.0 em `material-icons/LICENSE.txt`.

## Dados locais e convenções

`ZAPLAUD_DATA_DIR` força uma raiz com os filhos `data` e `cache`, útil para testes isolados. Sem override, Linux respeita `XDG_DATA_HOME`/`XDG_CACHE_HOME` (com os fallbacks de `DesktopDirs.kt`); macOS usa `~/Library/Application Support/Zaplaud` e `~/Library/Caches/Zaplaud`. Diretórios Unix são privados (`0700`). A resolução cria os diretórios e propaga erros de disco.

O shell usa `LocalStateStore` pelo adaptador `AppPersistence`: servidores, última sessão, marcadores de leitura, bloqueio de tentativas, tema e preferências de visualização compartilham o formato desktop. Clientes HTTP restauram e atualizam cookies com `SecureSessionStorage`. O cache `AvatarDiskCache` alimenta o LRU de imagens decodificadas do shell (4 MiB); imagens permanecem fora dos snapshots. O tema salvo é aplicado na abertura; `ZAPLAUD_THEME` pode sobrescrevê-lo nesta execução.

Configurações substituem a lista pelo evento `AppEvents.settings`. O recurso `SettingsState` mantém tema, visualização e envio padrão, resumos, notificações, papéis e cotas por servidor. O tema `system` acompanha `Palette.color-scheme` do Slint; quando o backend não informa o esquema, a paleta padrão é clara. As gravações usam `LocalStateStore` fora do event loop. Papéis consultam a API a cada 10 s (1,5 s enquanto pendentes), cotas e modelos a cada 60 s, somente com a tela aberta e `ListState.app-visible`. Fechar cancela consultas, mas não cancela o salvamento de papéis; logout e adicionar/reautenticar servidor reutilizam o shell.

Após salvar preferências, `SettingsState.preferences-changed()` avisa o recurso de conversa. Alterações de notificações também emitem `SettingsState.notification-preferences-changed()`, reservado ao recurso de sistema. Cada callback tem um único consumidor; nenhum exige polling do arquivo de preferências.

Para exercitar papéis na fixture real, envie `roles-seed {"tiny":"anthropic/claude-haiku-4-5","task":"openai-codex/gpt-5"}` ao stdin. O comando só semeia o snapshot do auxiliar; `cast-consume` confirma os trabalhos reais criados por `POST /api/cast/roles`. Sem esses comandos, o comportamento anterior da fixture permanece intacto.

Identificadores permanecem em inglês; portas de regras preservam o nome Kotlin convertido para snake_case. Comentários e documentação ficam em português. Não use `unwrap()`/`expect()` em caminhos de rede, disco ou entrada do usuário. Testes devem cobrir comportamento e erros, não detalhes textuais da implementação. Novos recursos devem reutilizar o modelo e as regras existentes, sem introduzir um segundo contrato.

O lint compartilhado usa `unsafe_code = "deny"`, não `forbid`: o Rust gerado pelo Slint contém `unsafe` autorizado internamente. Lints adicionais contra macros de código incompleto também precisam distinguir código próprio do gerado pelo Slint; não altere os arquivos em `OUT_DIR`.

## Decisão de migração

Premissa de trabalho, pendente de confirmação do proprietário: **quando o cliente Rust atingir paridade (fase 6), o desktop Compose (`android/desktop`, `android/shared/src/jvmMain`) será removido**. Esta fase não remove nem altera o cliente Kotlin. Os recursos ainda são gerados da árvore Kotlin; a remoção precisará preservar uma fonte canônica equivalente.

## Extensão paralela de recursos Slint

- Cada recurso mantém `ui/<recurso>.slint`, um global `<Recurso>State` e `src/<recurso>.rs`. Não acrescente propriedades de recursos a `AppWindow`.
- A função de entrada é `setup(&AppWindow, &feature::FeatureContext)`. Somente o integrador acrescenta essa função à lista `FEATURES` em `main.rs` e monta o componente no host correspondente.
- O host `conversation-content` de `ConversationPane` aceita filhos. Transcript, avisos, compositor e folhas devem ficar em arquivos próprios. `ConversationState` fornece seleção, cabeçalho, fechar, informações e menu; `ListState` fornece o ponto de montagem do FAB (`cast-visible`, `cast-icon`, callback `cast`).
- Assine `context.snapshots.subscribe()` uma vez por recurso. O canal broadcast guarda no máximo 16 snapshots `Arc<AppSnapshot>`; trate `Lagged` continuando pelo estado mais novo. Não registre vários consumidores no mesmo callback Slint: o último substituiria os anteriores.
- `AppSnapshot` contém APIs isoladas por origem, `ReadingState`, necessidade de relogin e sessão selecionada. Use `context.runtime.spawn` para rede; mantenha o `JoinHandle` do recurso e cancele pedidos ao mudar a seleção.
- `context.commands` aceita seleção, fechamento, filtro e logout. Ações exclusivas de outro recurso usam um canal privado dentro do seu `setup`; não acrescente variantes genéricas ao evento central.
- Publique modelos Slint somente no event loop por `update_ui`. Não atravesse threads com `Image`, `VecModel` ou componentes fortes.
- `AppState::new` recebe `LocalStateStore` como `Box<dyn AppPersistence>`. `Persistence::call` executa os métodos síncronos em `spawn_blocking`, sem bloquear streams no runtime de rede. Não grave arquivos em callbacks da UI. `seen_id` é SHA-256 lowercase do JSON de `SessionKey`, igual ao JVM. Marcadores só são gravados quando mudam, após debounce de 2 s, com descarga ao encerrar.
- Snapshots aguardam em uma caixa que mantém somente o mais recente por origem. Avatares são solicitados ao instanciar linhas virtuais, com até quatro downloads simultâneos. Falhas ficam em cache negativo por origem e avatar durante a execução; PNGs só entram no disco após decodificação válida. O cache de imagens decodificadas limita-se a 4 MiB. `AvatarDiskCache` implementa `avatars::AvatarStorage::{read, save}` e preserva os PNG entre execuções.
- `AppEvents.settings` abre a tela de configurações. `AppEvents.logout(origin)` abre a confirmação e remove somente aquela origem; `AppEvents.theme(dark)` persiste e altera o tema sem recriar a janela.
- O compositor usa o mesmo `local_state.json` dos clientes Kotlin; não execute os dois clientes como escritores simultâneos. Uploads verificam tamanho e identidade registrados na seleção e leem o mesmo descritor aberto, sem seguir um link simbólico substituído. Anexos antigos sem identidade precisam ser selecionados novamente. Arquivos originais nunca são removidos ao liberar anexos.
- Os callbacks `ComposerState.command(kind,value)` são exclusivos de `composer::setup`: menus de conversa devem invocá-los, não registrar um segundo handler. A confirmação do anel usa `ComposerDialogs` na raiz da janela, fora do slot inferior.
- No Linux, o seletor tenta o portal XDG (`rfd`, recomendado em Wayland), depois `zenity` em subprocesso, por fim o diálogo de caminhos completos, um por linha. O fallback `kdialog` foi removido porque sua saída não distingue nomes com quebras de linha. Não há GTK ligado ao binário; portal e utilitários são opcionais. No macOS, `rfd` usa o seletor nativo.
- `ui/native-strings.xml` contém somente textos exclusivos do desktop, sem equivalente Kotlin; o mesmo gerador produz `native_strings.rs`, sem alterar o inventário de strings Kotlin.

### Montagem da conversa

`conversation_view::setup` acompanha uma única seleção pelo broadcast e cancela/aguarda o stream anterior antes de abrir outro.
`ConversationView` contém a lista virtualizada e ocupa o espaço restante, sem incluir o compositor.
O integrador monta um `VerticalLayout` em `ConversationPane.conversation-content`, nesta ordem:

1. Avisos de envio; a atenção de permissão fica na barra da conversa, como no Compose.
2. `ConversationView { vertical-stretch: 1; }`: avisos de leitura e transcript.
3. Linha de estado/cast (`RsCastAside`).
4. `ComposerPane` (`RsComposer`), com altura preferida e sem esticar verticalmente.

`ConversationMenu` e `SessionInfoSheet` ficam como overlays da janela, condicionados a
`ConversationViewState.menu-open` e `info-open`. O menu aceita filhos para as ações de compositor,
aside e cast. A ação de envio pode incrementar `ConversationViewState.end-request` para acompanhar o fim.
`ConversationViewState.copy(string)` preserva o Markdown original; links aceitam somente HTTP/HTTPS.
O cache é salvo com `ConversationWrite` e epoch da origem; fechar/trocar a seleção também salva, sem esperar o debounce.

### Permissões

`permission::setup` registra o botão `ListState.permission(key)` e os callbacks do global `PermissionState`.
`PermissionState.open-session(key)` recebe a serialização JSON de `SessionKey`, seleciona a conversa e abre
somente a permissão ainda presente no snapshot daquela origem. Esse é também o evento de ativação de
notificação «abrir sessão + permissão». `PermissionState.open-selected()` abre a permissão da conversa atual.
O integrador mantém `PermissionSheet { visible: PermissionState.visible; }`
sobre a janela. A montagem persistente permite transferir o foco na abertura; a folha contém o ciclo Tab/Shift+Tab.
Enquanto uma folha ou diálogo externo está aberto, `keys.enabled` desabilita o shell. Ao fechar, `changed enabled`
restaura seu foco e solicita foco da lista. Fechar configurações ou busca também restaura os atalhos.
Ativações anteriores ao primeiro snapshot aguardam a sessão correspondente sem polling.
Um único hook winit distribui foco para leitura/alertas e visibilidade para lista, elenco e aside.
As preferências de conversa são recarregadas pelo callback das configurações; alertas consultam as preferências
salvas a cada snapshot real, sem listener vazio nem stream adicional.
O executor `controller::permission::run(api, commands, updates)` possui os pedidos e o timer de prazo;
fechar a folha, trocar de pedido ou abandonar o consumidor cancela essas tarefas, sem repetir decisões POST.
As decisões levam o ID exibido na folha e rejeitam IDs divergentes; uma oscilação da conexão não invalida a conclusão de um POST em andamento. Comandos e entradas completos quebram por caractere e permanecem acessíveis pela rolagem da folha; o indicador localizado de linhas adicionais sinaliza conteúdo fora do visor. Entradas JSON não textuais usam serialização compacta. Notificações não são fechadas à força e permanecem disponíveis conforme a política do centro de notificações.



### Kit compartilhado

Importe componentes de `ui/widgets/kit.slint`; todas as cores vêm dos recursos gerados.

| Componente | Propriedades e callbacks próprios |
| --- | --- |
| `Avatar` | `source`, `initials`, `fallback-color`, `brand`, `brand-color`, `label`; aceita filhos para badges adicionais. Tamanho padrão 48 px. |
| `TextButton`, `FilledButton` | `text`, `text-size` (14 px), `text-weight` (500), `horizontal-alignment` (center), `trailing-icon` opcional (18 px), `enabled`, `foreground`, callback `clicked`; mouse, Tab, espaço e Enter. |
| `IconButton`, `Fab` | `icon`, `label`, `foreground`, `enabled`, callback `clicked`; tamanho 40/56 px. |
| `TextField` | `text` bidirecional; `placeholder`, `multiline`, `password`, `enabled`, `outlined`, `autofocus`; `max-lines` (0 mantém altura multilinha padrão), `sends-on-enter` (Enter aceita, Shift+Enter insere linha); `leading-icon`, `trailing-icon`, `trailing-label`, `trailing-space` (reserva espaço para ações filhas), callback `trailing-clicked()`; callbacks `edited(string)`, `accepted`; aceita filhos. Usa `TextInput` nativo para IME e teclas mortas. |
| `Switch`, `RadioButton`, `CheckBox` | `checked` bidirecional, `text`, `enabled`, callback `toggled()`. Desenho Material com cores de `Theme`, sem animação periódica. |
| `CheckBox.description` | Descrição opcional sob o rótulo; ambos quebram linha. Toda a linha alterna a seleção. |
| `SegmentedControl` | `labels: [string]`, `selected: int` bidirecional, callback `chosen(int)`. |
| `Chip` | API de `TextButton` mais `selected`. |
| `Pill`, `Badge` | `text`, `text-size` (16/12 px), `foreground`; Badge aplica primary/onPrimary. |
| `Banner` | `text`, `action`, `severity` (`info`, `warn`, `error`), callback `activated`. |
| `Menu` | Popup: filhos em coluna; métodos `show()`, `close()`. |
| `Dialog` | Scrim modal; `title`, `text`, `confirm`, `cancel`; callbacks `confirmed`, `dismissed`; filhos antes dos botões; texto de botão vazio o oculta. Esc dos filhos e clique externo fecham. `focus-dialog()` restaura foco quando um modelo substitui o controle focado sem fechar o diálogo. |
| `Sheet` | Painel direito com scrim; `title` nomeia o grupo acessível; filhos são conteúdo; callback `dismissed`; Esc e clique externo fecham. |
| `Divider`, `ProgressRing` | Divisor e indicador indeterminado. Instancie indicador somente enquanto carregar. |
| `Tooltip` | `text`, callback `clicked()` para encaminhar clique ao controle envolvido; mostra legenda no hover. |
| `ScrollList` | Alias do `ListView` padrão, não subclasse: preserva a virtualização reconhecida pelo compilador Slint. Filhos repetidos precisam ser diretos. |
| `ScrollContainer` | API do ScrollView padrão; use `content-width` para rolagem horizontal. |

Os campos de `Theme` incluem os 48 papéis Material 3 em claro e escuro. O [baseline real](scripts/dump-m3-baseline.md) é sobrescrito ao vivo pelo Kotlin. `AvatarColors` e `StatusColors` também são extraídos dos arquivos Kotlin, sem paletas substitutas.

- `ListState.app-visible` começa `true`; o adaptador de janela deve atualizá-lo ao ocultar/restaurar. `clock.rs` possui o único timer de cronômetros, a 1 Hz, ativo somente com texto de tempo decorrido visível. A lista informa geometria das linhas virtuais, sem publicar outro modelo a cada segundo. Login, configurações, layout estreito e janela oculta suspendem consumidores fora da tela. O spinner usa o mesmo tick e fica estático sem foco; o anel de contexto e `ProgressRing` não animam continuamente. Foco e visibilidade chegam pelo mesmo hook winit de `window_state`, sem instalar outro hook.
  No macOS, sem foco, o cronômetro atualiza a cada 15 s; em foco mantém segundos e spinner a 1 Hz. Oculta/minimizada, a janela suspende o timer. O foco reinicia a cadência sem registrar outro timer.
- O renderer software não recorta imagens pelo raio do pai. `avatars::decode` aplica máscara circular no RGBA antes de entrar no cache; preserve essa etapa.
- `Sheet` e `Dialog` contêm os filhos no `FocusScope` de Esc. Formulários dinâmicos devem cercar sua ordem Tab com sentinelas de foco; o kit não inventa travessia entre descendentes.

### Elenco e bate-papo paralelo

`controller::cast::State` executa as rotas tipadas de elenco sem repetir POSTs. A origem das ações segue a seleção, depois o filtro, depois o servidor único. `CastState` publica retomar/dispensar e o texto de estado para o host da conversa; `ListState.cast` abre a escolha de contato e pasta.

`controller::aside::run` recebe eventos por `mpsc`, publica o redutor por `watch` e sinaliza 401 por um canal separado. Descartar o runner cancela chamadas e esperas. Fechar ou trocar de sessão limpa a folha e encerra o polling. O runner limita a espera periódica de 700 ms do Kotlin a 1 s, conforme a meta desktop; não cria SSE adicional. Perguntas, cancelamentos e a consulta inicial continuam imediatos. `AsideState.open()` é a entrada do menu da conversa.

### Exercício visual e medição

Com Xvfb disponível e dependências npm instaladas, execute:

```sh
LD_LIBRARY_PATH=/caminho/das/bibliotecas/X11 \
python3 zaplaud-rs/scripts/shell-smoke.py \
  --binary /caminho/do/zaplaud --display localhost:85 --port 14785 --measure
```

O script inicia duas fixtures reais, autentica por XTest, confirma HTTP, observa linhas, abre/fecha conversa, revela busca, adiciona servidor e alterna entre 1100 e 600 px. Reinicia uma fixture para invalidar o cookie e capturar relogin. Grava `/tmp/zaplaud-rs-shell-*.png` e JSON com tempo até mapeamento, PSS/RSS e CPU de 30 segundos. Não execute junto com outras janelas Zaplaud no mesmo display.

Os smokes usam os centros dos campos e botões do login Material integrado. A folha de permissões mantém a extensão vertical explícita do comando quebrado em linhas, para que o scroll e o marcador de linhas adicionais concordem.

Para medir recursos, use somente `scripts/measure.py`:

```sh
LD_LIBRARY_PATH=/caminho/das/bibliotecas/X11 \
python3 zaplaud-rs/scripts/measure.py \
  --binary /caminho/do/zaplaud --display localhost:88 --fixture-port 14788 \
  --label candidate --output /tmp/zaplaud-rs-fin-perf-candidate.json
```

São cinco aberturas isoladas; a última mede lista, conversa com pelo menos 200 entradas reais recebidas por SSE, conversa trabalhando e retorno após vinte ciclos com informações/menu. Cada estado usa uma amostra de CPU de 30 s, sem foco. O primeiro lançamento é frio somente para o processo: o script não descarta o page cache do sistema. PSS/RSS estão em KiB, tamanho em bytes e CPU em percentual de um núcleo. A contagem de wakeups é um proxy explicitamente identificado: context switches voluntários de todas as threads.

`--renderer software --profile-render` salva a saída `refresh_lazy,console` do renderer software em `/tmp`, incluindo regiões sujas. Sem `--renderer`, o script mede o padrão do aplicativo e remove um `SLINT_BACKEND` herdado. `--strace /caminho/strace` salva um perfil de syscalls, separado das medições normais: tracing e o contador de FPS alteram os despertares e não devem contaminar a comparação de CPU. `--fixture-url` aceita uma fixture externa, mas não semeia nem certifica 200 entradas.

`--size 1920x1080` define o tamanho lógico após o login; `--scale 2 --size 1440x900`
mede 2880×1800 pixels físicos. A tela Xvfb deve comportar o tamanho físico.
Use socket X11 local e MIT-SHM em resoluções grandes: o transporte TCP sem SHM
excede o tamanho máximo de request ao apresentar um buffer de 2880×1800.
Cada execução preserva o log do cliente em `/tmp/zaplaud-rs-fin-perf-*-render-*.log`, inclusive em erro.

### Renderer e emoji

O padrão em Linux e macOS é `software` do Slint: prioriza memória, mantendo sequências emoji
com a fonte de contorno embutida. Skia é uma feature Cargo não padrão: compile com
`cargo build --release --locked -p zaplaud-ui --features skia --bin zaplaud`.
Nesse binário, `ZAPLAUD_RENDERER=skia-software` habilita emoji colorido por CPU;
`ZAPLAUD_RENDERER=skia` seleciona o backend Skia disponível (Metal no macOS).
`ZAPLAUD_RENDERER=software` volta ao caminho leve. `SLINT_BACKEND=winit-...` tem precedência.
Não confunda `skia-software` com `software`: são rasterizadores diferentes.
Se a seleção de um renderer opcional, criação da janela ou inicialização do event loop falhar, o processo
libera o socket de instância única e faz `exec` de si mesmo com `winit-software`.
Só há uma tentativa de fallback. A ausência de um display continua sendo erro.
Uma verificação única após 2 s também encerra a inicialização sem janela nativa:
Slint/winit 1.18.1 pode guardar o erro em `event_loop.rs:242-244` sem sair do loop.
Essa verificação não cria polling periódico nem mantém um timer de idle.
O caminho OpenGL opcional requer libEGL/libGL e um driver Mesa ou do fabricante.

No macOS, ambos os renderizadores CPU terminam no mesmo `softbuffer 0.4.8`:
`CGImage` em `DeviceRGB` atribuído a `CALayer.contents`. O backend aloca buffer novo
e retorna idade zero a cada frame; Slint precisa redesenhar tudo. `present_with_damage`
ignora os retângulos no backend CoreGraphics. Não há configuração pública de cor,
idade do buffer ou apresentação parcial acessível pelo aplicativo. Metal evita esse
caminho, mas a medição real do dono chegou a 363 MB; não é o padrão de um cliente leve.
Não foi feito fork de Slint/softbuffer. Em segundo plano, a cadência macOS reduz
apresentações sem perder segundos ou animação enquanto a janela está em foco.

No macOS, a família primária é a fonte de interface (`System Font`/SF), não a Helvetica
que o Fontique 0.11.1 escolhe originalmente para sans-serif. `AppWindow` fixa peso regular 400;
o kit preserva 500 para rótulos e 700 para negrito. `cargo run -p zaplaud-ui --example font_probe`
registra as famílias/pesos antes e depois da política. O probe `emoji_probe` aceita
`ZAPLAUD_PROBE_SNAPSHOT=/tmp/fontes.png` e mostra 400/500/700 junto aos controles emoji.
Em Linux, permanece a primeira fonte sans-serif do sistema. O fallback emoji vem
antes dos fallbacks adicionais de símbolos: sem essa ordem, DejaVu pode capturar
`✅`, `⚠️` e partes de `1️⃣` antes da fonte colorida. Fontes monoespaçadas explícitas
do Markdown não mudam. Em Linux, a fonte colorida deste ensaio foi
`/usr/share/fonts/noto/NotoColorEmoji.ttf` (`fc-list :lang=und-zsye` e `fc-match emoji`).
Sem fonte colorida instalada, a Noto Emoji de contorno embutida mantém glifos legíveis.
A fonte colorida do sistema é opcional e usada somente pelo build com Skia.

`ui/fonts/NotoEmoji.ttf` é a variável oficial do
[Google Fonts](https://github.com/google/fonts/tree/main/ofl/notoemoji), sem mescla
com a fonte de texto, com licença `OFL-NotoEmoji.txt`. São 1.982.596 bytes,
SHA-256 `de6c18832938afc99caf132b39d6a30a19bac7f2e812e28db2535b4608d27551`.
No fallback `software`, ela substitui o emoji colorido na coleção compartilhada;
Text, TextInput e StyledText/MarkdownView recebem a mesma política.

O diagnóstico anterior de que Slint 1.18.1 perde necessariamente ZWJ/flags não se
confirmou com a fonte não mesclada. O caminho ativo com `systemfonts` é Parley:
`i-slint-renderer-software-1.18.1/lib.rs:2814,2890,3172` encaminha Text, TextInput
e StyledText para `sharedparley`. `parley-0.11.1/src/shape/mod.rs:311-350` segmenta
clusters de grafemas antes de moldar. `fonts/vectorfont.rs:237-261` é o shaper
simples, mas não é o caminho de texto vetorial com fontes do sistema.
A limitação real permanece em `fonts/vectorfont.rs:207-210`: rasteriza somente
`Source::Outline` com `Format::Alpha`; não pinta COLR/CBDT/sbix.
O ensaio com Noto Emoji não mesclada mostrou ligaturas de pele/ZWJ, ambas as
bandeiras, família e keycap nos três controles. Isso não garante todos os emoji
ou combinações futuras: a cobertura depende da versão da fonte.

Probe reproduzível, executado sob X11/Xvfb:

```sh
SLINT_BACKEND=winit-skia-software ZAPLAUD_PROBE_APP_FONTS=1 \
  cargo run --release -p zaplaud-ui --features skia --example emoji_probe
SLINT_BACKEND=winit-software ZAPLAUD_PROBE_APP_FONTS=1 \
  cargo run --release -p zaplaud-ui --example emoji_probe
cargo test --locked -p zaplaud-ui --test emoji_fonts
```

Sem `ZAPLAUD_PROBE_APP_FONTS`, o probe usa a pilha original do sistema.
`SLINT_FONT_PATH=/caminho/NotoEmoji.ttf` permite repetir o ensaio isolado de contorno.
O teste de regressão usa a coleção Fontique compartilhada pelo Slint e o mesmo
Parley 0.11.1/Swash do renderer. Rejeita `.notdef`, glifo sem bitmap/contorno e
decomposição das sequências do probe. Roda em `cargo test --workspace`, inclusive CI;
não precisa de display nem de FontTools. Não substitui a inspeção dos PNGs coloridos.

#### Histórico: ensaios anteriores em janela 1×

Os números desta subseção são do build anterior, não do pacote leve atual.
A conclusão anterior sobre Skia foi revogada após o primeiro uso Retina real.

Os candidatos exploratórios foram compilados juntos com
`--features slint/renderer-femtovg,slint/renderer-skia-vulkan`.
O build release completo levou 246 s e produziu um binário comum de 28.505.552 bytes.
Skia 0.153.3 baixou o arquivo pré-compilado oficial `ganesh-gl-jpegd-jpege-pdf-vulkan`
de `rust-skia/skia-binaries`; não compilou o C++ do Skia nesta máquina.
Os arquivos extraídos de Skia ocuparam 25 MiB. Sem acesso ao artefato pré-compilado,
um build de Skia a partir das fontes tem custo diferente, não medido aqui.

Medições exploratórias com `scripts/measure.py`, cinco aberturas e CPU por 30 s,
Xvfb `localhost:88`, Mesa llvmpipe nos candidatos OpenGL. Valores de PSS em KiB;
CPU percentual de um núcleo, estados lista / conversa com 252 entradas / trabalhando:

| Candidato | Abertura mediana (ms) | PSS lista / 252 / trabalhando | CPU lista / 252 / trabalhando | Threads lista / 252 / trabalhando | Scroll (ms) | Decisão |
| --- | ---: | --- | --- | --- | ---: | --- |
| Skia CPU | 94,63 | 56.248 / 56.993 / 58.893 | 0,67 / 0,77 / 0,37 | 6 / 7 / 7 | 23,50 | Atende PSS < 100 MiB e abertura < 300 ms |
| FemtoVG OpenGL | 363,56 | 149.899 / 158.514 / 159.928 | 4,07 / 5,80 / 8,50 | 39 / 40 / 40 | 25,37 | Reprovado nas duas metas |
| Skia OpenGL | 271,46 | 155.500 / 160.804 / 196.302 | 0 / 0 / 8,97 | 39 / 40 / 40 | 22,64 | Reprovado em PSS |
| Skia CPU anterior, sem override | 126,93 | 56.817 / 59.581 / 61.725 | 0,63 / 0,63 / 0,87 | 6 / 7 / 7 | 23,10 | Escolha anterior, revogada após uso Retina; binário 30.124.544 bytes |

Esses três candidatos pintaram o probe colorido com a prioridade emoji corrigida.
O binário experimental comum evita atribuir diferenças de código compilado ao renderer
selecionado em runtime; não representa três builds mínimos independentes.
O sistema com `software`, sem fallback, omite foguete, pessoa e bandeiras.
Noto Emoji de contorno corrige a visibilidade, mas não pode produzir cor.
Vulkan não encontrou uma janela utilizável em 30 s neste ambiente sem ICD Vulkan
instalado em `/usr/share/vulkan/icd.d` ou `/etc/vulkan/icd.d`; não há medição válida
nem screenshot de uma janela Vulkan. O probe aceita `ZAPLAUD_PROBE_VULKAN=1` no
build experimental para repetir `BackendSelector::require_vulkan()`.

Evidências locais: `/tmp/zaplaud-rs-emoji-<candidato>.png` e
`/tmp/zaplaud-rs-emoji-<candidato>-metrics.json`.
Os resultados não garantem o mesmo consumo em GPU real, outro driver ou macOS.

O build daquele ensaio habilitava Skia e software, com Noto Emoji embutida. A rodada
mediu o padrão anterior, sem `SLINT_BACKEND`/`SLINT_FONT_PATH`/`SLINT_DEFAULT_FONT`.
O PSS após vinte ciclos foi 59.653 KiB; CPU 0,37%, nove threads. CPU não foi zero:
os estados medidos ficaram entre 0,37% e 0,87% de um núcleo.
O JSON integral está em `/tmp/zaplaud-rs-emoji-final-metrics.json`.

Na lista e nas duas conversas daquele ensaio, os arquivos de fonte mapeados somaram
12.488 KiB PSS em `/proc/<pid>/smaps`:

| Arquivo mapeado | PSS (KiB) |
| --- | ---: |
| `/usr/share/fonts/noto/NotoColorEmoji.ttf` | 10.480 |
| `/usr/share/fonts/noto/NotoSans-Regular.ttf` | 608 |
| `/usr/share/fonts/noto/NotoSans-Medium.ttf` | 608 |
| `/usr/share/fonts/TTF/DejaVuSans.ttf` | 744 |
| `/usr/share/fonts/noto/NotoZnamennyMusicalNotation-Regular.ttf` | 48 |

Noto Emoji de contorno e Noto Sans Mono embutidas pertencem ao mapeamento do
executável, não aparecem como arquivos `.ttf` separados. Skia também pode copiar
dados de fonte para o heap (`font_cache.rs:79-83`); a soma acima não atribui esse
heap às fontes nem deve ser subtraída do PSS total como custo completo delas.
O script agora registra nomes e PSS por arquivo em cada estado.
Nas capturas reais com Markdown e o probe, também foram mapeadas
`/usr/share/fonts/noto/NotoSans-Bold.ttf` (280/236 KiB claro/escuro),
`NotoSans-Italic.ttf` (244/244 KiB) e `NotoSansMono-Regular.ttf` (192/192 KiB),
todas no mesmo diretório. O total mapeado foi 12.678/12.676 KiB.
Os JSONs `/tmp/zaplaud-rs-emoji-app-fonts-{light,dark}.json` registram a lista
completa e o PSS de cada arquivo; páginas compartilhadas mudam o PSS entre amostras.

O fallback foi exercitado com `SLINT_BACKEND=winit-skia-opengl`,
`LIBGL_DRIVERS_PATH=/nonexistent`, `__EGL_VENDOR_LIBRARY_FILENAMES=/nonexistent`
e `__GLX_VENDOR_LIBRARY_NAME=nonexistent`. A janela software abriu após `exec`,
no mesmo PID, com `SLINT_BACKEND=winit-software` confirmado em `/proc/<pid>/environ`.
Evidências: `/tmp/zaplaud-rs-emoji-fallback.png` e `.log`.

As capturas finais do aplicativo ficam em
`/tmp/zaplaud-rs-emoji-app-{list,message}-{light,dark}.png`.
Usam fixture real na porta 14788: título emoji em uma sessão recente e mensagem
com o probe no transcript. Um POST real de `/api/messages` contendo o probe foi
confirmado pelo comando `deliver`, sem alterar o código do servidor.
`cargo fmt --check`, `cargo clippy --workspace --all-targets --locked -- -D warnings`
e `ZAPLAUD_KEYRING=memory cargo test --workspace --locked` passaram: 194 testes,
um teste JVM de interoperabilidade ignorado no gate normal.

#### Símbolos de interface e apresentação de texto

A fonte de emoji não deve pintar os quadros do indicador como imagens coloridas.
A lista separa o glifo do texto de atividade e desenha somente o glifo com
`font-family: "DejaVu Sans"`, tanto na lista quanto na conversa.
A família genérica `monospace` não bastou no Slint: o quadro
`✳` ainda podia cair na fonte colorida. Botões autônomos de voltar, retomar e menu
usam `TextButton.text-font-family` para manter `‹`, `▶` e `⋮` em contorno.
DejaVu Sans foi embutida sem modificação (759.720 bytes, licença Bitstream/DejaVu
em `ui/fonts/LICENSE-DejaVu.txt`) somente para esses símbolos. Noto Emoji não
cobre `⋮`; DejaVu Sans Mono também não cobre `⋮` nem os quadros braille.
A cobertura foi conferida antes da escolha da fonte completa. Não há mescla.
Os rótulos de atividade continuam na família sans-serif original.
Marcas de família de modelo (Claude, Gemini e OpenAI) são SVGs, não glifos.

O probe e o teste incluem `· • ✓ ‹ › ← → ↑ ↓ ↗ ▶ ◉ ○`, todos os seis quadros
distintos `· ✢ ✳ ✶ ✻ ✽` e os dez quadros braille. O teste usa a mesma família
nomeada DejaVu Sans e exige `swash::scale::image::Content::Mask`, além de cobertura e
rasterização. Assim, bitmap colorido para um símbolo da interface falha no teste.
O probe permite `ZAPLAUD_THEME=dark` e mostra a mesma matriz nos dois temas.
As medições release acima precedem esse ajuste de símbolos; não medem o acréscimo
da fonte de 759.720 bytes ao executável.
Evidências do ajuste: `/tmp/zaplaud-rs-symbol-presentation-before-{light,dark}.png`
preserva a apresentação colorida anterior. O probe corrigido está em
`/tmp/zaplaud-rs-emoji-symbols-{skia,software}-{light,dark}.png`.
Ciclos reais de doze amostras por aplicativo/tema, com os seis quadros distintos,
foram capturados do Compose e dos dois renderizadores Rust em
`/tmp/zaplaud-rs-symbol-frames-{compose,skia-software,software}-{light,dark}-sheet.png`.
Cada linha da montagem é um recorte real; os PNGs completos têm sufixos `-00` a `-11`.

Um ponto no estado “Trabalhando…” não significa que `✻` perdeu o glifo: `·` é o
primeiro quadro da animação, tanto em `clock.rs:100` quanto em `ReadingScreen.kt:713`.
Ao remover foco, Rust fixa `✻`; comparações precisam usar o mesmo foco/quadro.
O defeito confirmado era outro quadro, `✳`, capturado pela fonte emoji colorida
quando a lista não fixava a família do glifo. A família nomeada de contorno evita isso.

Limitação aceita para o **conteúdo das mensagens**: caracteres cuja apresentação
padrão é texto podem aparecer coloridos no Skia, como `▶`, `↗` e `✳`.
Parley 0.11.1 ignora seletores de apresentação na cobertura
(`src/shape/mod.rs:256-268`); acrescentar apenas U+FE0E não corrige essa seleção.
Não há patch local nem cópia vendorizada do Parley. A proteção é aplicada aos
símbolos controlados pela interface, sem reescrever mensagens dos agentes.


`--damage` registra os retângulos realmente apresentados ao servidor X11 via XDamage. Use transporte Unix com MIT-SHM, por exemplo `--display :88`: o fallback TCP de `softbuffer` envia a imagem completa mesmo quando o renderer rasteriza somente regiões sujas. No Xvfb isolado por bwrap, habilitar `-listen unix -listen tcp` também permite o socket Unix abstrato do Linux, sem depender do `/tmp` interno.

## Empacotamento e execução Linux

O pacote de usuário contém `zaplaud`, `install-linux.sh`, `packaging/zaplaud.desktop`, `packaging/README.md` e `packaging/icons/hicolor/<tamanho>/apps/zaplaud.png` (16 a 1024 px). Extraia e execute `bash install-linux.sh`, sem sudo. O instalador usa `~/.local/bin` e respeita `XDG_DATA_HOME`. Consulte [distribuição desktop](packaging/README.md) para gerar ícones, DEB, RPM e bundle macOS.

O release local da rodada `fin-cast` fica em `$HOME/dados/zaplaud-rs-dist/fin-cast/zaplaud-linux-x86_64.tar.gz`; a árvore extraída fica ao lado. Esse build usa a glibc da máquina de desenvolvimento, não constitui um binário universal Linux. O CI compila em Ubuntu 24.04; construa numa base tão antiga quanto a distribuição que pretende atender.

Na rodada `fin-cast`, o `ldd` do release local confirmou vínculos diretos com `libfontconfig.so.1`, `libgcc_s.so.1`, `libm.so.6`, `libc.so.6` e o loader ELF. FreeType, Expat, zlib, bzip2, libpng e Brotli aparecem transitivamente. `readelf --version-info` exige símbolos até `GLIBC_2.43`; não distribua esse build local para sistemas mais antigos. `strings` confirmou `libwayland-client.so.0`, `xdg_wm_base`, `set_app_id`, `WAYLAND_DISPLAY` e `WAYLAND_SOCKET`. Essas observações pertencem àquele artefato, não substituem os gates do build integrado.

Também foram gerados `zaplaud_0.1.0-1_amd64.deb` e `zaplaud-0.1.0-1.x86_64.rpm` nessa pasta, com cargo-deb 3.8.0 e cargo-generate-rpm 0.21.1, instalados sem pacotes do sistema. O RPM usa descoberta interna por `ldd`. Nesta máquina, cargo-deb avisa que `dpkg-shlibdeps` está ausente: o DEB local não contém dependências ELF automáticas e serve somente para inspeção, não distribuição. O CI Ubuntu dispõe dessa ferramenta. Os arquivos foram listados com `ar` e `bsdtar`, sem lintian.

Bibliotecas carregadas dinamicamente por winit/softbuffer não aparecem necessariamente no `ldd`. Instale os runtimes da distribuição:

| Distribuição | Pacotes de execução |
| --- | --- |
| Arch / CachyOS | `glibc gcc-libs fontconfig libx11 libxcursor libxrandr libxi libxcb libxkbcommon libxkbcommon-x11 wayland ttf-dejavu` |
| Debian / Ubuntu | `libc6 libgcc-s1 libfontconfig1 libx11-6 libx11-xcb1 libxcursor1 libxrandr2 libxi6 libxcb1 libxcb-render0 libxcb-shm0 libxkbcommon0 libxkbcommon-x11-0 libwayland-client0 fonts-dejavu-core` |
| Fedora | `glibc libgcc fontconfig libX11 libXcursor libXrandr libXi libxcb libxkbcommon libxkbcommon-x11 wayland-libs dejavu-sans-fonts` |

Secret Service desbloqueado permite persistir cookies; D-Bus, notificações, bandeja e portal de arquivos dependem dos serviços da sessão. Não há dependência obrigatória de GTK, Qt, OpenSSL ou JVM.

### Escolha de X11 ou Wayland

O build habilita `backend-winit-x11` e `backend-winit-wayland`, com somente `renderer-software` e sem features padrão do Slint. O programa escolhe explicitamente winit, sem forçar `renderer_name`; `SLINT_BACKEND` não substitui a escolha de backend. Acessibilidade permanece habilitada. Dentro de winit 0.30, `WAYLAND_DISPLAY` ou `WAYLAND_SOCKET` não vazio tem precedência sobre `DISPLAY`. Sem Wayland, usa X11 quando `DISPLAY` existe. Para forçar X11 numa sessão mista, execute `env -u WAYLAND_DISPLAY -u WAYLAND_SOCKET zaplaud`. Para Wayland, mantenha `XDG_RUNTIME_DIR` apontando ao runtime da sessão e `WAYLAND_DISPLAY` ao socket do compositor.

O `app_id` Wayland e `WM_CLASS` X11 são `zaplaud`. A rodada `fin-cast` não dispunha de compositor Wayland; compilação e símbolos daquele binário confirmaram suporte, não abertura de janela Wayland.

### Interoperabilidade contínua com o desktop JVM

O CI executa `bash packaging/jvm-interop.sh` com `ZAPLAUD_JVM_INTEROP_DIR` exclusivo. O runner instala temporariamente um teste JVM que grava pelo `LocalStateStore` real. Depois, `cargo test -p zaplaud-store --test jvm_interop -- --ignored` lê e altera os arquivos. Uma segunda execução JVM com `ZAPLAUD_JVM_INTEROP_VERIFY=1` relê o resultado Rust. O teste temporário é removido inclusive em falha; o runner não cria `local.properties`. Não execute ambos os clientes como escritores simultâneos.

Os novos comandos opt-in da fixture são `seed-closed-omp <UUID>`, `seed-subagents <mainAgentId>` e `seed-usage`. Somente o primeiro habilita contas/histórico OMP no launcher de teste; sem comandos, o comportamento anterior permanece intacto.
