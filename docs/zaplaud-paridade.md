# Paridade entre os clientes Zaplaud

O Zaplaud tem dois códigos de cliente:

- **Kotlin** (`android/shared/src/commonMain`): Android e iPhone/iPad.
- **Rust** (`zaplaud-rs/`): o desktop de Linux e macOS.

O desktop Compose (`android/desktop`, `android/shared/src/jvmMain`) está obsoleto desde 2026-11-10: fica no
repositório, mas não recebe mudanças nem entra na paridade.

O Habblaud web (`client/`) é a referência de comportamento do servidor, mas tem interface própria.

Regra: uma mudança de comportamento em um cliente Zaplaud entra no outro na mesma entrega. Se não der, a pendência
vai para a tabela abaixo, com o motivo. Textos ficam em `android/shared/src/commonMain/composeResources/values/strings.xml`
(o Rust gera os dele desse arquivo na compilação); só textos que existem apenas no desktop ficam em
`zaplaud-rs/crates/zaplaud-ui/ui/native-strings.xml`. Cores e ícones também saem do Kotlin.

## Recursos

| Recurso | Kotlin | Rust | Observação |
|---|---|---|---|
| Login, vários servidores, relogin por servidor | sim | sim | |
| Lista: abertas/recentes, busca, filtro por servidor, não lidas, subagentes | sim | sim | |
| Fixar e favoritar sessões, com filtro Favoritas | sim | sim | marcas independentes e locais; fixadas primeiro dentro dos filtros, por atividade; remoção do servidor limpa só suas marcas |
| Conversa com markdown, pensamentos e ferramentas compactos, resumo, ir para o fim | sim | sim | Rust: seleção dentro de um bloco + "Copiar mensagem" (limite do Slint) |
| Duas colunas a partir de 840 dp, conversa usando a largura toda | sim | sim | celular: uma coluna |
| Compositor: rascunho, anexos, modos de envio, interrupção, anel de contexto, shake | sim | sim | Rust: sem arrastar arquivos para a janela (Slint 1.18) |
| Aprovação e perguntas, com resumo `text` e entrada não textual em JSON | sim | sim | Kotlin rola na horizontal; Rust quebra a linha e mostra "mais N linhas" |
| Chamar personagem (também com conversa aberta em tela larga), dispensar, retomar | sim | sim | pasta da conversa aberta vem primeiro |
| Bate-papo paralelo, sem pular para o fim quando se lê acima | sim | sim | puxada para abrir só no toque; no desktop, pelo menu |
| Arquivos da pasta de trabalho: navegar e baixar | sim | sim | Android: Downloads (MediaStore); iOS: pasta do app; desktop: Downloads |
| Configurações, papéis, resumos por HTTP | sim | sim | |
| Seletores de modelo: nomes normalizados, busca, OpenRouter por fabricante e preservação do modelo atual | sim | sim | sessão e papéis; datas e variantes distinguem rótulos sem alterar o ID escolhido |
| ZeroClaw: controles permitidos, sem anel de contexto, elenco pelo runtime | sim | sim | |
| Espera de `Retry-After` no login limitada a 24 h | sim | sim | |
| Avisos de aprovação, pergunta e fim de turno | sim | sim | Android: serviço em segundo plano; desktop: só com o app aberto |
| Ícone na bandeja | não se aplica | sim | |
| Chaveiro destrancado quando o padrão está trancado (Linux) | não se aplica | sim | |

## Verificação

- Kotlin: `./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :shared:jvmTest`
  e os instrumentados no emulador. iOS só compila num Mac.
- Rust: `cargo test --workspace --locked` em `zaplaud-rs/` e os roteiros `zaplaud-rs/scripts/*-smoke.py`.
- Os dois usam o mesmo servidor de teste, `scripts/android-smoke.ts`.
