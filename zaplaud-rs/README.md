# Zaplaud desktop nativo

Cliente Rust + Slint para Linux e macOS. O comportamento do cliente Kotlin em `android/shared/src/commonMain` é a referência. A troca do desktop JVM procura reduzir o consumo de memória: metas de menos de 100 MiB PSS parado e menos de 300 ms até a janela mapeada. Não são garantias para qualquer máquina.

Referências para as próximas fases: [rotas e streams](docs/contrato/01-rotas-e-streams.md), [regras e estado](docs/contrato/02-regras-e-estado.md), [telas e fixture](docs/contrato/03-telas-e-fixture.md). São um mapa de consulta; em divergência, o Kotlin vence.

## Construir, testar e executar

Requisitos: Rust 1.96.1 (fixado em `rust-toolchain.toml`), ferramentas nativas de compilação e uma sessão gráfica X11 ou Wayland. No macOS, instale as ferramentas de linha de comando do Xcode. O renderer padrão é software; não é necessário OpenGL.

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

Use usuário e senha fictícios `android-test`. HTTP exige confirmação na tela; HTTPS não. O cliente não segue redirecionamentos. A API aceita persistência de cookies pelo contrato `SessionPersistence`; `SecureSessionStorage` usa o chaveiro do sistema, com fallback em memória quando indisponível. `ZAPLAUD_KEYRING=memory` força o modo sem persistência entre processos.

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

Para atualizar a UI, capture um `Weak` do componente e dados próprios (`String`, `Vec`, tipos do núcleo). Envie a atualização com `slint::invoke_from_event_loop`; somente dentro dessa closure faça `upgrade()` e altere propriedades/modelos Slint. Não envie componentes nem modelos Slint para tarefas Tokio. Se a janela já fechou, descarte a atualização. Encerre a tarefa anterior ao iniciar outra conexão; não deixe dois streams publicarem na mesma lista.

Os redutores `rules::{composer,aside,permission}::reduce(State, Event) -> (State, Vec<Effect>)` não executam I/O. A ponte executa os efeitos tipados e devolve o evento de conclusão correspondente. `composer::Effect::Save` aplica `composer::update_message` dentro da transação serializada de `MessageStore`, sem cancelar a gravação ao sair do primeiro plano. Preserve a ordem dessas gravações; o POST só sai depois da confirmação da operação `unknown` persistida. `CheckRecipient` reavalia `composer::can_send` antes de cada upload e do POST; não reutilize o resultado inicial. `ReleaseUnused` exige consultar outros drafts e `State::active_attachment` antes de liberar uma URI.

Datas recebem `now` explicitamente; os helpers `*_in` aceitam um fuso para testes determinísticos. Horários fora da faixa representável pelo Chrono retornam `None`, nunca consultam o relógio para substituir a entrada. `conversation::summary_text` retorna unidades UTF-16 para preservar exatamente o corte Kotlin de 32768 unidades, inclusive um surrogate final. A ponte deve codificar esse vetor como string JSON com escapes UTF-16, sem corte por bytes.

`attachments::guess_mime` recebe a base MIME da plataforma: o JVM usa `Files.probeContentType`, cujo resultado depende do sistema operacional. Não existe uma allowlist MIME Kotlin para copiar. A regra usa a extensão sem diferenciar maiúsculas e retorna `application/octet-stream` quando desconhecida.

Os goldens em `crates/zaplaud-core/tests/golden/` foram produzidos executando as funções Kotlin reais em um teste JVM temporário, com `./gradlew :shared:jvmTest --tests br.com.xlinx.habblaud.RustRulesGoldenTest --max-workers=4`. Entradas: os três JSON de `tests/fixtures`; saídas: filtros/agrupamento e transições de notificações. `platform-kotlin.json` também fixa o formato pt-BR e amostras MIME do JVM Linux. O teste temporário e `local.properties` não fazem parte do código entregue.

## Recursos gerados

`build.rs` lê `android/shared/src/commonMain/composeResources/values/strings.xml`, os blocos `lightColorScheme(...)`/`darkColorScheme(...)` de `ZaplaudApp.kt` e todos os XMLs de `composeResources/drawable/`. Gera textos Rust, o global Slint `Theme` com as duas paletas e SVGs de todos os vetores. As entradas têm `cargo:rerun-if-changed`; a lista de drawables também é observada para detectar novos arquivos.

Não copie textos, cores ou paths vetoriais à mão para a UI. Preserve escapes XML e placeholders posicionais `%1$s`/`%1$d` na geração. A ausência de cores reconhecidas é erro de build, não motivo para uma paleta substituta silenciosa. Os testes comparam a quantidade de textos, as cores primárias conhecidas e a validade dos SVGs gerados.

## Dados locais e convenções

`ZAPLAUD_DATA_DIR` força uma raiz com os filhos `data` e `cache`, útil para testes isolados. Sem override, Linux respeita `XDG_DATA_HOME`/`XDG_CACHE_HOME` (com os fallbacks de `DesktopDirs.kt`); macOS usa `~/Library/Application Support/Zaplaud` e `~/Library/Caches/Zaplaud`. Diretórios Unix são privados (`0700`). A resolução cria os diretórios e propaga erros de disco.

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
- A integração de armazenamento substitui `Box<dyn AppPersistence>` em `AppState::new`. Métodos são síncronos e executam na thread de fundo. Não grave arquivos em callbacks da UI. `seen_id` é SHA-256 lowercase do JSON de `SessionKey`, igual ao JVM.
- Avatares são solicitados ao instanciar linhas virtuais, com até quatro downloads simultâneos. O cache de imagens decodificadas limita-se a 4 MiB. A integração de disco implementa `avatars::AvatarStorage::{read, save}`; `MemoryOnly` mantém a execução sem disco.
- `AppEvents.settings` é evento para a próxima etapa, não uma tela de configurações. `AppEvents.logout(origin)` abre a confirmação e remove somente aquela origem; `AppEvents.theme(dark)` altera o tema sem recriar a janela.

### Kit compartilhado

Importe componentes de `ui/widgets/kit.slint`; todas as cores vêm dos recursos gerados.

| Componente | Propriedades e callbacks próprios |
| --- | --- |
| `Avatar` | `source`, `initials`, `fallback-color`, `brand`, `brand-color`; aceita filhos para badges adicionais. Tamanho padrão 48 px. |
| `TextButton`, `FilledButton` | `text`, `enabled`, `foreground`, callback `clicked`; mouse, Tab, espaço e Enter. |
| `IconButton`, `Fab` | `icon`, `label`, `enabled`, callback `clicked`; tamanho 40/56 px. |
| `TextField` | `text` bidirecional; `placeholder`, `multiline`, `password`, `enabled`, `outlined`, `autofocus`; callbacks `edited(string)`, `accepted`. Usa `TextInput` nativo para IME e teclas mortas. |
| `Switch` | API do Switch padrão Slint (`checked`, `text`, `toggled`). |
| `SegmentedControl` | `labels: [string]`, `selected: int` bidirecional, callback `chosen(int)`. |
| `Chip` | API de `TextButton` mais `selected`. |
| `Pill`, `Badge` | `text`, `foreground`; Badge aplica primary/onPrimary. |
| `Banner` | `text`, `action`, `severity` (`info`, `warn`, `error`), callback `activated`. |
| `Menu` | Popup: filhos em coluna; métodos `show()`, `close()`. |
| `Dialog` | Scrim modal; `title`, `text`, `confirm`, `cancel`; callbacks `confirmed`, `dismissed`; Esc e clique externo fecham. |
| `Sheet` | Painel direito com scrim; filhos são conteúdo; callback `dismissed`; Esc e clique externo fecham. |
| `Divider`, `ProgressRing` | Divisor e indicador indeterminado. Instancie indicador somente enquanto carregar. |
| `Tooltip` | `text`; envolve filhos e mostra legenda no hover. |
| `ScrollList` | Alias do `ListView` padrão, não subclasse: preserva a virtualização reconhecida pelo compilador Slint. Filhos repetidos precisam ser diretos. |
| `ScrollContainer` | API do ScrollView padrão; use `content-width` para rolagem horizontal. |

Os campos de `Theme` incluem os 48 papéis Material 3 em claro e escuro. O [baseline real](scripts/dump-m3-baseline.md) é sobrescrito ao vivo pelo Kotlin. `AvatarColors` e `StatusColors` também são extraídos dos arquivos Kotlin, sem paletas substitutas.

### Exercício visual e medição

Com Xvfb disponível e dependências npm instaladas, execute:

```sh
LD_LIBRARY_PATH=/caminho/das/bibliotecas/X11 \
python3 zaplaud-rs/scripts/shell-smoke.py \
  --binary /caminho/do/zaplaud --display localhost:85 --port 14785 --measure
```

O script inicia duas fixtures reais, autentica por XTest, confirma HTTP, observa linhas, abre/fecha conversa, revela busca, adiciona servidor e alterna entre 1100 e 600 px. Reinicia uma fixture para invalidar o cookie e capturar relogin. Grava `/tmp/zaplaud-rs-shell-*.png` e JSON com tempo até mapeamento, PSS/RSS e CPU de 30 segundos. Não execute junto com outras janelas Zaplaud no mesmo display.
