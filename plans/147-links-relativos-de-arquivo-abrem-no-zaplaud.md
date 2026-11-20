# Plano 147: links para arquivos do projeto (caminho relativo) abrem no Zaplaud: imagens desenhadas, outros arquivos baixáveis

> **Instruções ao executor**: siga o plano passo a passo. Rode cada verificação e confirme o resultado esperado
> antes do passo seguinte. Se ocorrer uma condição de PARADA, pare e relate; não improvise. O revisor cuida de
> `plans/README.md`.
>
> **Verificação de desvio (rode primeiro)**: `git diff --stat dad6d69..HEAD -- server/sources android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownLinks.kt android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/ZaplaudApp.kt zaplaud-rs/crates/zaplaud-ui/src/markdown zaplaud-rs/crates/zaplaud-ui/src/conversation_view.rs`
> Se algum mudou desde `dad6d69`, compare os trechos de "Estado atual" com o código vivo; se não baterem, é PARADA.

## Status

- **Prioridade**: P1
- **Esforço**: M
- **Risco**: MED (reescreve texto do agente antes de chegar aos clientes; acesso a arquivos do servidor)
- **Depende de**: nenhum
- **Categoria**: bug
- **Planejado em**: commit `dad6d69`, 2026-11-20 (relato do dono com captura do Android)

## Por que importa

Os agentes entregam trabalho como links Markdown para arquivos do projeto, com caminho **relativo à pasta da
sessão**: `[Quatro vistas](pasta/sub/arquivo.png)`, `[Caminhadas em GIF](…/x.gif)`, `[Aseprite editável](…/x.aseprite)`.
Caso real de 2026-11-20 (sessão omp "Pietra"): quatro links, dois `.png`, um `.gif`, um sem extensão de imagem; todos
sem esquema e sem `/` inicial. No Zaplaud (Android e desktop) aparecem sublinhados e **tocar não faz nada**. O dono
não consegue ver a arte que o agente acabou de fazer.

## Estado atual

- **Os dois clientes já desenham imagens de arquivos do servidor**, mas só com caminho **absoluto**:
  - Kotlin `android/shared/src/commonMain/kotlin/br/com/xlinx/habblaud/MarkdownLinks.kt:41-48`
    (`markdownImageKind`: `/…` ou `file:///…` → `HOST_FILE`) e `:50-52` (`isImageLink`: link comum com extensão
    png/jpg/jpeg/gif/webp/svg também é desenhado); bytes por `HabblaudApi.imageBytes` → `GET /api/files/raw?path=`
    (`data/Files.kt:145-160`).
  - Rust `zaplaud-rs/crates/zaplaud-ui/src/markdown/images.rs:9-37` (`kind`, `host_path` só com `/`,
    `is_image_link`) e `markdown/mod.rs:361-365`.
- **Toque em link**: Kotlin abre só `http(s)` (`ZaplaudApp.kt:42-50`, `isHttpMarkdownLink`); Rust abre só o que
  `valid_link` aceita (`conversation_view.rs:844-848`). Um caminho de arquivo não faz nada.
- **Servidor**: `GET /api/files/raw?path=<absoluto>` (`server/http/files.ts`) serve só dentro das raízes permitidas
  (salas reais e `~/projetos`, `server/files/browse.ts` `locate`), exige a permissão `terminal` da conexão, mostra só
  tipos de `fileType` e baixa qualquer arquivo com `&download=1`. Os clientes já têm fluxo de download com progresso
  e cancelamento (janela de arquivos; Kotlin `downloadFile`, Rust `api/files.rs:95`).
- **O snapshot não informa a pasta da sessão aos clientes** (só `roomId`). O servidor sabe: o leitor do transcript
  do omp guarda `cwd` (`server/sources/omp/terminal.ts:141,178`); o do Claude usa `cwd` em `server/sources/terminal.ts`
  (`relPath`, `:178`); veja o do Codex em `server/sources/codex/terminal.ts`.

## Decisão de desenho

Resolver no **servidor**, uma vez para todos os clientes: ao montar as entradas `assistant` do terminal (texto
Markdown do agente), reescrever o **destino** de links e imagens Markdown que sejam caminhos relativos para o caminho
absoluto `cwd/relativo` normalizado, **somente se** o arquivo resultante existir **e** estiver dentro de uma raiz que
`locate()` aceitaria. Com isso as imagens passam a ser desenhadas pelo código que os clientes já têm. Nos clientes,
falta só uma coisa: tocar num link para arquivo do servidor que não é imagem oferece o download existente.

## Convenções do repositório (valem para este plano)

- TypeScript com Vitest; `npm ci`; **`npm run typecheck`** e `npm test` em todo passo que toque TypeScript.
- Kotlin: regras puras testáveis em `androidHostTest`; textos em `strings.xml`; nada lança em escopo de corrotina.
- Rust: `export PATH="$HOME/.cargo/bin:$PATH"`; `cargo fmt --check`; clippy do workspace `-D warnings`; testes com
  `ZAPLAUD_KEYRING=memory` e `npm ci` antes; janela só em `xvfb-run`.
- **Não há CI**: prova local. Paridade e `CHANGELOG.md`.

## Escopo

**Dentro:** o ponto do servidor que monta o texto `assistant` das entradas de terminal para omp, Claude Code e Codex
(um helper compartilhado novo em `server/sources/`), testes; Kotlin `MarkdownLinks.kt`, `ZaplaudApp.kt` (ou onde o
toque é tratado) e o fluxo de download existente, `strings.xml`, testes de host; Rust `markdown/images.rs`,
`conversation_view.rs` (toque) e o fluxo de download existente, testes; `CHANGELOG.md`, `docs/zaplaud-paridade.md`.

**Fora:** mudar `/api/files/*`, as raízes, a permissão `terminal`, a política de imagens remotas (`http(s)` continua
só depois do toque) e o texto salvo no transcript (a reescrita é só na saída). Página web: conferir por leitura se
passa a desenhar; mudar só se for uma linha.

## Passos

### Passo 1: servidor resolve destinos relativos (testes primeiro)

Função pura `resolveLocalLinks(markdown, cwd, exists, allowed)`: percorre os links e imagens Markdown (inline
`[t](d)` e `![a](d)`, com título opcional e destino entre `<…>`), **fora de blocos e trechos de código**, e troca o
destino quando: não tem esquema (`x:`), não começa com `/`, `#`, `?` nem `//`; normalizado contra `cwd` (resolve `.`
e `..`) continua dentro de `cwd` **ou** de uma raiz permitida; o arquivo existe (`exists`) e `allowed` (a mesma regra
de `locate`) aceita. Destinos com `%`-codificação: decodifique para testar e escreva o caminho absoluto codificado de
forma que o Markdown continue válido (espaços → `%20` ou `<…>`). Qualquer dúvida → mantém o destino original.

Aplique onde as entradas `assistant` são montadas para os três runtimes, com o `cwd` da sessão; `exists`/`allowed`
reais (com cache curto por caminho, para não fazer `stat` a cada atualização do stream).

Testes: relativo simples e com subpastas; `./` e `../` dentro do projeto; `../` que sai do projeto e das raízes →
mantém; arquivo inexistente → mantém; `http://`, `mailto:`, `#ancora`, absoluto → intactos; link dentro de bloco de
código e de código inline → intacto; imagem `![…](rel.png)` → absoluto; espaços e acentos no nome; sem `cwd` → nada muda.

**Verificação**: `npm run typecheck` e `npx vitest run server/sources` saem com 0; os testes novos falham antes.

### Passo 2: toque em arquivo do servidor que não é imagem → baixar

Kotlin e Rust: tocar num link cujo destino é caminho absoluto de host (as mesmas regras de `HOST_FILE` / `host_path`)
abre o fluxo de download que a janela de arquivos já usa (nome do arquivo, progresso, cancelar, pasta de destino de
cada plataforma). Imagens continuam sendo desenhadas como hoje (o toque nelas abre a visualização que já existe).
Sem permissão `terminal` ou erro do servidor → a mensagem de erro que o download já mostra. Nada de abrir caminhos
locais do aparelho.

Testes de regra (host Kotlin; unidade Rust): destino absoluto não imagem → ação "baixar"; imagem → ação existente;
`http(s)` → navegador como hoje; relativo (não deveria chegar mais) → nada.

**Verificação**: `ANDROID_HOME=/home/anderson/Android/Sdk android/gradlew -p android :shared:testAndroidHostTest :app:assembleDebug --max-workers=3`
→ `BUILD SUCCESSFUL`; `cargo fmt --check`, clippy e `cargo test --locked -p zaplaud-core -p zaplaud-store -p zaplaud-ui -j 6` saem com 0.

### Passo 3: prova ponta a ponta com a fixture

Na fixture (`server/test/android-fixture.ts`), uma mensagem fictícia com `![…](arquivo.png)`, `[…](sub/anim.gif)` e
`[…](sub/fonte.aseprite)` relativos a uma pasta temporária dentro de uma raiz permitida, com arquivos fictícios
criados pelo teste. Rust sob `xvfb-run`: abrir a conversa, capturar (dois temas) e confirmar que as imagens aparecem
desenhadas; a matriz dos roteiros (2 temas × 3) passa. Kotlin: teste instrumentado **só se o emulador estiver livre**;
senão, teste de host do caminho completo (texto da fixture → `markdownImages` com o destino já absoluto).

**Verificação**: capturas em `~/dados/habblaud/plano-147/`; matriz 6/6; `npm test` passa.

### Passo 4: documentação

`CHANGELOG.md`: "Zaplaud: links para arquivos do projeto citados pelo agente (caminho relativo) passam a abrir:
imagens aparecem na conversa e os demais arquivos podem ser baixados." Paridade: linha "Links para arquivos do projeto".

**Verificação**: `npm run i18n:check -- --strict`, `npm run typecheck` e `npm test` saem com 0.

## Critérios de pronto

- [ ] Destinos relativos existentes dentro das raízes viram absolutos na saída do servidor; os demais intactos
- [ ] Imagens relativas aparecem desenhadas no desktop (captura) e no Android (teste)
- [ ] Toque em arquivo não imagem baixa pelo fluxo existente nos dois clientes
- [ ] typecheck, testes do servidor, host Kotlin, Rust e matriz dos roteiros ok

## Condições de PARADA

- O `cwd` da sessão não estiver disponível no ponto onde o texto `assistant` é montado para algum runtime (relate qual).
- Reescrever o destino exigir mudar o texto gravado no transcript ou o contrato do snapshot.
- O fluxo de download existente não puder receber um caminho vindo de link sem reestruturar a janela de arquivos.

## Notas de manutenção

- Depois do merge: publicar o servidor; instalar APK no M35 e desktop no Mac.
- Imagens remotas (`http(s)`) seguem a política atual (só depois do toque).
