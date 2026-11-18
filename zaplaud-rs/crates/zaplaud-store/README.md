# Persistência desktop

`LocalStateStore` fornece operações síncronas e falíveis. Execute-as fora da thread Slint. `new()` respeita `ZAPLAUD_DATA_DIR`; `with_dirs()` permite isolamento sem alterar variáveis globais.

## Formato legado do desktop Compose

`local_state.json` e `conversation-<generation|null>-<tag>-<hash>.json` usam os codecs Kotlin: SHA-256 UTF-8, SessionKey JSON com ordem origin/provider/account/sessionId, mensagens sem envelope draft, referências de anexos sem copiar ou apagar arquivos do usuário. O cache conserva as últimas 500 entradas e apenas seus resumos; a chave de resumo usa hash signed i32 de unidades UTF-16. A migração de origin legado conserva os hashes de sessão e elimina caches antigos, como a JVM.

Campos desconhecidos do objeto raiz são preservados em read-modify-write. Atualizar uma mensagem ou conversa substitui seu objeto, descartando campos desconhecidos internos, como Kotlin. `clear()` apaga estado de conta, caches conversation-* e attachment-*, gera conversation_generation e preserva configurações e cookies. `remove_server()` elimina somente dados da origem, não configurações, cookies nem arquivos de anexos. O chamador encerra a sessão autenticada separadamente. JSON de estado inválido produz erro sem sobrescrever; JSON de cache inválido retorna ausência.

## Segurança e concorrência

Diretórios são 0700; arquivos gravados, temporários e locks são 0600 em Unix. Escritas usam `<nome>.tmp`, sincronização do arquivo e rename atômico no mesmo diretório. Falhas removem o temporário quando possível. `.state.lock` é um lock consultivo do sistema operacional, adquirido em cada leitura/modificação/gravação e compartilhado pelas configurações. Não há estado de conta em cache na memória. O cache de avatar usa seu próprio `.lock`.

O desktop Compose legado não adquire esses locks. Não o execute junto ao cliente Rust sobre os mesmos dados. Tokens ConversationWrite e epochs invalidam gravações atrasadas entre instâncias Rust no mesmo processo. Tokens são efêmeros; a exclusão mútua entre processos protege read-modify-write, não transfere epochs entre processos.

## Configurações e chaveiro

`settings.json` guarda as mesmas chaves e defaults de Java Preferences. Somente quando ausente, importa melhor esforço de `~/.java/.userPrefs/br/com/xlinx/zaplaud/prefs.xml`, sem modificar o XML. O importador ignora XML inválido e nunca bloqueia inicialização por falha de leitura/importação; falhas de escrita do próprio armazenamento são propagadas. As configurações Rust posteriores não são exportadas para Java Preferences.

`SecureSessionStorage` oferece load/save/clear por origem. Kotlin usa um único account `remote-session`, service `br.com.xlinx.zaplaud`, contendo `{v:2,cookies:{origin:Set-Cookie}}`; este formato é preservado, incluindo leitura do formato legado origin/cookie. Não existem contas individuais por origem no código Kotlin atual. Secret Service Linux usa backend Rust e criptografia Rust; macOS usa Keychain. Sem chaveiro funcional, cookies ficam apenas na memória do processo. `ZAPLAUD_KEYRING=memory` força esse modo. Cookies nunca são gravados em arquivos ou incluídos em Debug/logs.

Quando não há uma coleção explícita, o backend nativo escolhe seu armazenamento padrão. No macOS isso seleciona
o domínio `User` (chaveiro de login); o nome `default` do Secret Service não é um domínio válido do Keychain.
Limpar uma origem que não está salva é uma operação sem efeito. A persistência macOS foi exercitada na sessão
gráfica com uma credencial fictícia isolada, incluindo restauração e exclusão entre processos; o SSH não oferece
o mesmo acesso interativo ao Keychain.

As marcações locais de sessão usam `marks_<sha256(origin)[:16]>_<sha256(SessionKey.serialize())>` em
`local_state.json`, com objeto `{"pinned":true,"favorite":true}`. Cada flag é independente; ausente equivale a
`false`, e ambas falsas removem a entrada. Kotlin e Rust usam o mesmo formato. A lista lê as marcas por origem
em lote; gravar exige servidor cadastrado, e remover um servidor apaga somente suas marcas. Não há sincronização
dessas preferências entre aparelhos.

## Avatar

`AvatarDiskCache` retorna bytes PNG validados por decodificação completa, dimensões 60x60, até 1 MiB, validade de 24 horas e nomes SHA-256 hex lowercase da chave exata. Após cada gravação, mantém no máximo 16 MiB, conservando arquivos mais novos primeiro como JVM. Não mantém cache de imagens decodificadas: o LRU de 4 MiB pertence à interface. Erros de disco são retornados para que o chamador decida continuar exibindo os bytes recebidos da rede.

## Testes

Em 09/11/2026, um teste de ida e volta verificou que o Rust lia e regravava o estado do desktop Compose. O teste foi removido com o cliente legado.

`cargo test -p zaplaud-store` cobre persistência, permissões, XML Java Preferences, concorrência real entre processos, migração, invalidação de escritas atrasadas, retenção de resumos e cache de avatares.
