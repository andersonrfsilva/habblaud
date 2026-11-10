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
