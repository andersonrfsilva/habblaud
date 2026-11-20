//! Markdown GFM sem HTML executável; o único cache global é o dos desenhos (`diagram`).
//!
//! `MarkdownView` recebe `blocks`, `source-markdown` e emite `link-clicked(string)`
//! e `copy(string)`. O aplicativo deve copiar no clipboard e validar/abrir links.
//! Slint 1.18.1 não seleciona `StyledText`: Selecionar texto troca os blocos da
//! mensagem por `TextInput` somente leitura, com seleção nativa e Ctrl+C.
//! Código já usa esse controle. Não existe seleção entre blocos; copiar mensagem preserva
//! o Markdown original. A lista externa deve virtualizar mensagens e chamar
//! `parse` sob demanda; este módulo não retém mensagens nem cria runtimes.
//!
//! `MdBlock.kind` é `text`, `quote`, `code`, `table` ou `rule`. Blocos ```` ```mermaid ```` e
//! ```` ```svg ```` válidos e fechados seguem `code`, com o desenho renderizado localmente em `diagram`. `level` é 1–6
//! para títulos e zero para parágrafos. `depth` é a indentação de listas a partir
//! de zero, e `marker` conserva a numeração ou o estado da tarefa. `spans` expõe
//! texto, bold, italic, strike, code e link; `rich` mantém o fluxo inline nativo.
//! `links` contém destinos únicos do bloco para o menu contextual. Ações de mensagem
//! aparecem sobre o conteúdo no hover, sem reservar uma linha no layout.
//! Tabelas usam `cells` em ordem de linhas e `columns` colunas. `MdCell.alignment`
//! é 0 (esquerda/padrão), 1 (centro) ou 2 (direita); `header` distingue o cabeçalho.
//! `MdMessage` contém `source` e `blocks`. `message_model` analisa linhas pedidas
//! pelo ListView sem reter resultados. Títulos usam negrito nativo (700): StyledText
//! não expõe peso 600. O fundo inline de código é desenhado pelo próprio Slint.

use pulldown_cmark::{Alignment, CodeBlockKind, Event, Options, Parser, Tag, TagEnd};
use slint::{ModelExt, ModelRc, StyledText, VecModel};
use std::collections::VecDeque;
use std::rc::Rc;
use std::sync::{Arc, Mutex, PoisonError};

pub mod images;

include!(concat!(env!("OUT_DIR"), "/markdown.rs"));

fn model<T: Clone + 'static>(items: Vec<T>) -> ModelRc<T> {
    Rc::new(VecModel::from(items)).into()
}

#[derive(Default)]
struct Inline {
    spans: Vec<(MdSpan, String)>,
    markup: String,
    bold: usize,
    italic: usize,
    strike: usize,
    links: Vec<String>,
}

impl Inline {
    fn push(&mut self, text: &str, code: bool) {
        if code {
            // Os delimitadores crescem para preservar backticks no conteúdo.
            let ticks = "`".repeat(text.split(|c| c != '`').map(str::len).max().unwrap_or(0) + 1);
            self.markup.push_str(&format!("{ticks} {text} {ticks}"));
        } else {
            self.markup.push_str(&escape(text));
        }
        let span = MdSpan {
            text: Default::default(),
            bold: self.bold > 0,
            italic: self.italic > 0,
            strike: self.strike > 0,
            code,
            link: self.links.last().map_or("", String::as_str).into(),
        };
        if let Some((last, content)) = self.spans.last_mut()
            && last.bold == span.bold
            && last.italic == span.italic
            && last.strike == span.strike
            && last.code == span.code
            && last.link == span.link
        {
            content.push_str(text);
        } else {
            self.spans.push((span, text.into()));
        }
    }

    fn take(&mut self, heading: bool, quote: bool) -> (String, StyledText, Vec<MdSpan>) {
        let spans: Vec<_> = std::mem::take(&mut self.spans)
            .into_iter()
            .map(|(mut span, text)| {
                span.text = text.into();
                span
            })
            .collect();
        let plain = spans
            .iter()
            .map(|span| span.text.as_str())
            .collect::<String>();
        let markup = std::mem::take(&mut self.markup);
        let markup = if heading {
            format!("__{markup}__")
        } else if quote {
            format!("_{markup}_")
        } else {
            markup
        };
        let rich = StyledText::from_markdown(&markup)
            .unwrap_or_else(|_| StyledText::from_plain_text(&plain));
        (plain, rich, spans)
    }
}

fn escape(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for c in text.chars() {
        if c.is_ascii_punctuation() {
            escaped.push('\\');
        }
        escaped.push(c);
    }
    escaped
}

/// O desenho fica sobre fundo branco e o resvg não resolve `var()`: as cores do tema viram fixas.
fn svg_theme_colors(svg: &str) -> String {
    let mut out = String::with_capacity(svg.len());
    let mut rest = svg;
    while let Some(start) = rest.find("var(--") {
        let Some(len) = rest[start..].find(')') else {
            break;
        };
        let name = rest[start + 6..start + len]
            .split(',')
            .next()
            .unwrap_or_default()
            .trim();
        out.push_str(&rest[..start]);
        out.push_str(match name {
            "fg" => "#1f2328",
            "muted" => "#656d76",
            "border" => "#d0d7de",
            "surface" => "#f6f8fa",
            "accent" | "c1" => "#0969da",
            "success" | "c2" => "#1a7f37",
            "warning" | "c3" => "#9a6700",
            "error" | "c4" => "#cf222e",
            "c5" => "#8250df",
            "c6" => "#bf3989",
            _ => "currentColor",
        });
        rest = &rest[start + len + 1..];
    }
    out.push_str(rest);
    out
}

/// Fontes maiores ficam como código, como no Android (`MermaidCodeFence.kt`).
const MAX_SVG_BYTES: usize = 200_000;
const MAX_MERMAID_BYTES: usize = 20_000;
/// O bloco nunca exibe o desenho mais alto que a imagem; acima disso o fragmento do transcript
/// passaria do orçamento de coordenadas i16 (`transcript::HEIGHT_BUDGET`), então fica como código.
pub const MAX_DIAGRAM_HEIGHT: u32 = 6_000;
/// Fontes e SVGs guardados; a conversa é reconstruída a cada atualização e não deve renderizar de novo.
const DIAGRAM_CACHE_BYTES: usize = 16 * 1024 * 1024;

pub struct Diagram {
    svg: String,
    /// Altura natural em px lógicos: teto da altura exibida, que limita a largura à da imagem.
    pub height: u32,
}

impl Diagram {
    pub fn image(&self) -> slint::Image {
        slint::Image::load_from_svg_data(self.svg.as_bytes()).unwrap_or_default()
    }
}

type DiagramEntry = (String, String, Option<Arc<Diagram>>);
static DIAGRAMS: Mutex<(VecDeque<DiagramEntry>, usize)> = Mutex::new((VecDeque::new(), 0));

fn entry_bytes((language, source, diagram): &DiagramEntry) -> usize {
    language.len() + source.len() + diagram.as_ref().map_or(0, |d| d.svg.len())
}

/// Desenho de uma cerca `svg`/`mermaid` com o conteúdo `source`; `None` a deixa como código.
/// A divisão do transcript (fora da thread da tela) e a montagem dos blocos perguntam pela
/// mesma cerca: o resultado fica guardado por conteúdo e o Mermaid é renderizado uma vez.
pub fn diagram(language: &str, source: &str) -> Option<Arc<Diagram>> {
    let language = language.trim();
    let limit = match language {
        "mermaid" => MAX_MERMAID_BYTES,
        "svg" => MAX_SVG_BYTES,
        _ => return None,
    };
    if source.len() > limit {
        return None;
    }
    {
        let mut cache = DIAGRAMS.lock().unwrap_or_else(PoisonError::into_inner);
        let entries = &mut cache.0;
        if let Some(index) = entries
            .iter()
            .position(|(l, s, _)| l == language && s == source)
            && let Some(entry) = entries.remove(index)
        {
            let diagram = entry.2.clone();
            entries.push_back(entry);
            return diagram;
        }
    }
    // Desenho inválido cai no bloco de código comum.
    let svg = match language {
        "mermaid" => mermaid_rs_renderer::render(source).ok(),
        _ => Some(svg_theme_colors(source)),
    };
    let diagram = svg.and_then(|svg| {
        let size = slint::Image::load_from_svg_data(svg.as_bytes())
            .ok()?
            .size();
        (size.width > 0 && size.height <= MAX_DIAGRAM_HEIGHT).then(|| {
            Arc::new(Diagram {
                svg,
                height: size.height,
            })
        })
    });
    let entry = (language.to_owned(), source.to_owned(), diagram.clone());
    let mut cache = DIAGRAMS.lock().unwrap_or_else(PoisonError::into_inner);
    let (entries, bytes) = &mut *cache;
    *bytes += entry_bytes(&entry);
    entries.push_back(entry);
    while *bytes > DIAGRAM_CACHE_BYTES
        && entries.len() > 1
        && let Some(old) = entries.pop_front()
    {
        *bytes -= entry_bytes(&old);
    }
    diagram
}

/// Cerca com a linha de fechamento no próprio texto. O parser fecha no fim do texto a cerca que
/// ainda está chegando; desenhá-la faria a altura mudar até o fechamento.
pub fn fence_closed(block: &str) -> bool {
    let opening = block.trim_start();
    let delimiter = opening.chars().next().unwrap_or('`');
    let run = opening.chars().take_while(|&c| c == delimiter).count();
    let mut lines = block.trim_end_matches(['\n', '\r']).lines();
    lines.next();
    lines.next_back().is_some_and(|last| {
        let last = last
            .trim_start_matches(|c: char| c == '>' || c.is_whitespace())
            .trim_end();
        last.len() >= run && last.chars().all(|c| c == delimiter)
    })
}

#[derive(Default)]
struct Blocks {
    blocks: Vec<MdBlock>,
    inline: Inline,
    lists: Vec<Option<u64>>,
    marker: String,
    quote: usize,
    level: i32,
    /// Linguagem, texto e se a linha de fechamento está no texto.
    code: Option<(String, String, bool)>,
    cells: Vec<MdCell>,
    alignments: Vec<Alignment>,
    column: usize,
    table_head: bool,
    images: Vec<String>,
}

impl Blocks {
    fn flush(&mut self) {
        self.flush_text();
        // A imagem vem logo abaixo do parágrafo que a cita; o link continua no texto.
        for dest in std::mem::take(&mut self.images) {
            self.blocks.push(MdBlock {
                kind: "image".into(),
                language: images::kind(&dest).into(),
                text: dest.into(),
                depth: self.lists.len().saturating_sub(1) as i32,
                ..Default::default()
            });
        }
    }
    fn flush_text(&mut self) {
        if self.inline.spans.is_empty() {
            return;
        }
        let (text, rich, spans) = self.inline.take(self.level > 0, self.quote > 0);
        let mut links = Vec::new();
        for span in &spans {
            if !span.link.is_empty() && !links.contains(&span.link) {
                links.push(span.link.clone());
            }
        }
        self.blocks.push(MdBlock {
            kind: if self.quote > 0 { "quote" } else { "text" }.into(),
            text: text.into(),
            rich,
            link_labels: model(
                links
                    .iter()
                    .map(|link| {
                        link.char_indices().nth(60).map_or_else(
                            || link.clone(),
                            |(end, _)| format!("{}…", &link[..end]).into(),
                        )
                    })
                    .collect(),
            ),
            links: model(links),
            spans: model(spans),
            level: self.level,
            depth: self.lists.len().saturating_sub(1) as i32,
            marker: std::mem::take(&mut self.marker).into(),
            ..Default::default()
        });
    }
}

/// Converte CommonMark/GFM em blocos leves, mantendo estilos inline e texto literal.
/// HTML permanece texto. Imagens viram link mais um bloco `image` com o destino em `text` e a
/// origem (`images::kind`) em `language`; o parser nunca acessa a rede.
pub fn parse(markdown: &str) -> Vec<MdBlock> {
    let options =
        Options::ENABLE_TABLES | Options::ENABLE_STRIKETHROUGH | Options::ENABLE_TASKLISTS;
    let mut state = Blocks::default();
    for (event, range) in Parser::new_ext(markdown, options).into_offset_iter() {
        match event {
            Event::Start(Tag::Paragraph | Tag::HtmlBlock) => state.flush(),
            Event::End(TagEnd::Paragraph | TagEnd::HtmlBlock) => state.flush(),
            Event::Start(Tag::Heading { level, .. }) => {
                state.flush();
                state.level = level as i32;
            }
            Event::End(TagEnd::Heading(_)) => {
                state.flush();
                state.level = 0;
            }
            Event::Start(Tag::Strong) => {
                state.inline.bold += 1;
                state.inline.markup.push_str("**");
            }
            Event::End(TagEnd::Strong) => {
                state.inline.bold -= 1;
                state.inline.markup.push_str("**");
            }
            Event::Start(Tag::Emphasis) => {
                state.inline.italic += 1;
                state.inline.markup.push('*');
            }
            Event::End(TagEnd::Emphasis) => {
                state.inline.italic -= 1;
                state.inline.markup.push('*');
            }
            Event::Start(Tag::Strikethrough) => {
                state.inline.strike += 1;
                state.inline.markup.push_str("~~");
            }
            Event::End(TagEnd::Strikethrough) => {
                state.inline.strike -= 1;
                state.inline.markup.push_str("~~");
            }
            Event::Start(tag @ (Tag::Link { .. } | Tag::Image { .. })) => {
                let (image, dest_url) = match tag {
                    Tag::Image { dest_url, .. } => (true, dest_url),
                    Tag::Link { dest_url, .. } => (images::is_image_link(&dest_url), dest_url),
                    _ => unreachable!(),
                };
                if image
                    && !images::kind(&dest_url).is_empty()
                    && !state.images.iter().any(|dest| **dest == *dest_url)
                {
                    state.images.push(dest_url.to_string());
                }
                state.inline.links.push(dest_url.to_string());
                state.inline.markup.push('[');
            }
            Event::End(TagEnd::Link | TagEnd::Image) => {
                if let Some(link) = state.inline.links.pop() {
                    let url = link
                        .replace('\\', "%5C")
                        .replace('(', "%28")
                        .replace(')', "%29")
                        .replace('<', "%3C")
                        .replace('>', "%3E");
                    state.inline.markup.push_str(&format!("](<{url}>)"));
                }
            }
            Event::Start(Tag::List(start)) => {
                state.flush();
                state.lists.push(start);
            }
            Event::End(TagEnd::List(_)) => {
                state.flush();
                state.lists.pop();
            }
            Event::Start(Tag::Item) => {
                state.flush();
                state.marker = match state.lists.last_mut() {
                    Some(Some(number)) => {
                        let value = format!("{number}.");
                        *number = number.saturating_add(1);
                        value
                    }
                    _ => "•".into(),
                };
            }
            Event::End(TagEnd::Item) => {
                state.flush();
                state.marker.clear();
            }
            Event::TaskListMarker(checked) => state.marker = if checked { "☑" } else { "☐" }.into(),
            Event::Start(Tag::BlockQuote(_)) => {
                state.flush();
                state.quote += 1;
            }
            Event::End(TagEnd::BlockQuote(_)) => {
                state.flush();
                state.quote -= 1;
            }
            Event::Start(Tag::CodeBlock(kind)) => {
                state.flush();
                let (language, closed) = match kind {
                    CodeBlockKind::Fenced(info) => {
                        (info.to_string(), fence_closed(&markdown[range]))
                    }
                    CodeBlockKind::Indented => (String::new(), false),
                };
                state.code = Some((language, String::new(), closed));
            }
            Event::End(TagEnd::CodeBlock) => {
                if let Some((language, text, closed)) = state.code.take() {
                    // Desenho inválido, grande demais ou incompleto (streaming) fica como código.
                    let diagram = closed
                        .then(|| diagram(&language, &text))
                        .flatten()
                        .map_or_else(slint::Image::default, |d| d.image());
                    state.blocks.push(MdBlock {
                        kind: "code".into(),
                        code_lines: text.lines().count().max(1) as i32,
                        text: text.into(),
                        language: language.into(),
                        diagram,
                        depth: state.lists.len().saturating_sub(1) as i32,
                        ..Default::default()
                    });
                }
            }
            Event::Start(Tag::Table(alignments)) => {
                state.flush();
                state.alignments = alignments;
                state.cells.clear();
            }
            Event::Start(Tag::TableHead) => {
                state.table_head = true;
                state.column = 0;
            }
            Event::End(TagEnd::TableHead) => state.table_head = false,
            Event::Start(Tag::TableRow) => state.column = 0,
            Event::End(TagEnd::TableCell) => {
                let (text, rich, _) = state.inline.take(state.table_head, false);
                let alignment = match state.alignments.get(state.column) {
                    Some(Alignment::Center) => 1,
                    Some(Alignment::Right) => 2,
                    _ => 0,
                };
                state.cells.push(MdCell {
                    text: text.into(),
                    rich,
                    alignment,
                    header: state.table_head,
                });
                state.column += 1;
            }
            Event::End(TagEnd::Table) => {
                state.blocks.push(MdBlock {
                    kind: "table".into(),
                    columns: state.alignments.len() as i32,
                    cells: model(std::mem::take(&mut state.cells)),
                    ..Default::default()
                });
            }
            Event::Text(text) => {
                if let Some((_, code, _)) = &mut state.code {
                    code.push_str(&text);
                } else {
                    state.inline.push(&text, false);
                }
            }
            Event::Code(text) => state.inline.push(&text, true),
            Event::Html(text) | Event::InlineHtml(text) => state.inline.push(&text, false),
            Event::SoftBreak => state.inline.push(" ", false),
            Event::HardBreak => state.inline.push("\n", false),
            Event::Rule => {
                state.flush();
                state.blocks.push(MdBlock {
                    kind: "rule".into(),
                    ..Default::default()
                });
            }
            _ => {}
        }
    }
    state.flush();
    state.blocks
}

/// Cria um modelo Slint de um documento já analisado, sem cópia dos blocos.
pub fn block_model(blocks: Vec<MdBlock>) -> ModelRc<MdBlock> {
    model(blocks)
}

/// Modelo imutável para ListView: analisa somente as linhas solicitadas, sem cache.
pub fn message_model(sources: Vec<String>) -> ModelRc<MdMessage> {
    ModelRc::new(VecModel::from(sources).map(|source| MdMessage {
        blocks: block_model(parse(&source)),
        source: source.into(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use slint::Model;

    #[test]
    fn headings_paragraphs_and_inline_styles() {
        let blocks = parse(
            "# Título\n\nUm **forte** *ênfase* ~~riscado~~ `código` [link](https://example.com).\n\n---",
        );
        assert_eq!(blocks.len(), 3);
        assert_eq!(blocks[0].level, 1);
        let spans: Vec<_> = blocks[1].spans.iter().collect();
        assert!(spans.iter().any(|s| s.bold && s.text == "forte"));
        assert!(spans.iter().any(|s| s.italic && s.text == "ênfase"));
        assert!(spans.iter().any(|s| s.strike && s.text == "riscado"));
        assert!(spans.iter().any(|s| s.code && s.text == "código"));
        assert!(spans.iter().any(|s| s.link == "https://example.com"));
        assert_eq!(blocks[2].kind, "rule");
    }

    #[test]
    fn context_links_keep_destinations_and_remove_style_duplicates() {
        let blocks =
            parse("[um **link**](https://example.com/a?q=1) e [outro](https://example.org)");
        let links: Vec<_> = blocks[0]
            .links
            .iter()
            .map(|link| link.to_string())
            .collect();
        assert_eq!(links, ["https://example.com/a?q=1", "https://example.org"]);
    }

    #[test]
    fn context_link_labels_are_short_without_changing_the_copied_url() {
        let url = format!("https://example.com/{}", "a".repeat(100));
        let blocks = parse(&format!("[link]({url})"));
        assert_eq!(blocks[0].links.row_data(0).unwrap(), url);
        let label = blocks[0].link_labels.row_data(0).unwrap();
        assert_eq!(label.chars().count(), 61);
        assert!(label.ends_with('…'));
    }

    #[test]
    fn nested_lists_tasks_and_quotes() {
        let blocks = parse("3. Pai\n   - Filho\n     - [x] Feito\n4. Outro\n\n> Citação");
        assert_eq!(blocks[0].marker, "3.");
        assert_eq!(blocks[1].depth, 1);
        assert_eq!(blocks[2].depth, 2);
        assert_eq!(blocks[2].marker, "☑");
        assert_eq!(blocks[3].marker, "4.");
        assert_eq!(blocks[4].kind, "quote");
    }

    #[test]
    fn table_columns_headers_and_alignment() {
        let blocks = parse(
            "| A | B | C | D | E | F |\n| :-- | :-: | --: | -- | -- | -- |\n| 1 | **2** | 3 | 4 | 5 | 6 |",
        );
        assert_eq!(blocks[0].columns, 6);
        assert_eq!(blocks[0].cells.row_count(), 12);
        assert!(blocks[0].cells.row_data(0).is_some_and(|c| c.header));
        assert!(
            blocks[0]
                .cells
                .row_data(1)
                .is_some_and(|c| c.alignment == 1)
        );
        assert!(
            blocks[0]
                .cells
                .row_data(8)
                .is_some_and(|c| c.alignment == 2 && !c.header)
        );
    }

    #[test]
    fn unclosed_fence_and_long_lines_preserve_bytes() {
        let text = "é".repeat(20_000);
        let blocks = parse(&format!("```rust\n{text}"));
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].kind, "code");
        assert_eq!(blocks[0].language, "rust");
        assert_eq!(blocks[0].text, text);
        assert!(parse("").is_empty());
    }

    #[test]
    fn mermaid_fences_render_diagrams_and_invalid_ones_stay_code() {
        let blocks = parse("```mermaid\nflowchart LR\n  A[Início] --> B{Ok?}\n  B -->|sim| C\n```");
        assert_eq!(blocks[0].kind, "code");
        assert!(blocks[0].diagram.size().width > 0);
        assert!(blocks[0].text.contains("flowchart LR"));
        let invalid = parse("```mermaid\nnão é um diagrama\n```");
        assert_eq!(invalid[0].diagram.size().width, 0);
        assert_eq!(
            parse("```rust\nfn main() {}\n```")[0].diagram.size().width,
            0
        );
    }

    #[test]
    fn svg_fences_render_drawings_and_invalid_ones_stay_code() {
        let svg = r#"<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20"/></svg>"#;
        let blocks = parse(&format!("```svg\n{svg}\n```"));
        assert_eq!(blocks[0].diagram.size().width, 40);
        assert!(blocks[0].text.contains("<rect"));
        assert_eq!(parse("```svg\n<svg><rect\n```")[0].diagram.size().width, 0);
        let themed = parse(
            "```svg\n<svg viewBox=\"0 0 90 47\" xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"9\" height=\"9\" fill=\"var(--accent)\" stroke=\"var(--nope, red)\"/></svg>\n```",
        );
        assert_eq!(themed[0].diagram.size().width, 90);
        assert_eq!(
            svg_theme_colors("a var(--fg) b var( --x"),
            "a #1f2328 b var( --x"
        );
    }

    #[test]
    fn drawings_need_the_closing_fence_in_the_text() {
        let svg = r#"<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="40" height="20"/></svg>"#;
        // Streaming: o parser fecha a cerca no fim do texto, mas o desenho espera o fechamento.
        for open in ["```svg", "~~~svg", "````svg"] {
            assert_eq!(
                parse(&format!("{open}\n{svg}\n"))[0].diagram.size().width,
                0
            );
        }
        assert_eq!(
            parse(&format!("````svg\n{svg}\n```"))[0]
                .diagram
                .size()
                .width,
            0
        );
        for (open, close) in [("```svg", "```"), ("~~~svg", "~~~~"), ("````svg", "````")] {
            let blocks = parse(&format!("{open}\n{svg}\n{close}\n\ndepois"));
            assert_eq!(blocks[0].diagram.size().width, 40, "{open}");
        }
        let quoted = parse(&format!("> ```svg\n> {svg}\n> ```\n"));
        assert_eq!(quoted[0].diagram.size().width, 40);
        let listed = parse(&format!("- item\n\n  ```svg\n  {svg}\n  ```\n"));
        assert!(listed.iter().any(|b| b.diagram.size().width == 40));
    }

    #[test]
    fn code_height_omits_only_the_terminal_newline_without_changing_clipboard_text() {
        for (source, text, lines) in [
            ("```sh\n  echo teste\n```", "  echo teste\n", 1),
            ("```sh\n  echo teste\n\n```", "  echo teste\n\n", 2),
            ("```sh\n```", "", 1),
        ] {
            let blocks = parse(source);
            assert_eq!(blocks[0].text, text);
            assert_eq!(blocks[0].code_lines, lines);
        }
    }

    #[test]
    fn html_is_literal_and_images_are_links() {
        let blocks = parse(
            "<script>alert(1)</script>\n\nA <b>literal</b> ![imagem](https://example.com/image.png)",
        );
        assert!(blocks[0].text.contains("<script>"));
        assert!(blocks[1].text.contains("<b>literal</b>"));
        assert!(
            blocks[1]
                .spans
                .iter()
                .any(|s| s.text == "imagem" && s.link == "https://example.com/image.png")
        );
        assert_eq!(blocks[2].kind, "image");
        assert_eq!(blocks[2].language, "remote");
        assert_eq!(blocks[2].text, "https://example.com/image.png");
        assert_eq!(parse("![](/tmp/a.png)")[0].language, "file");
        assert!(
            parse("![x](javascript:alert(1))")
                .iter()
                .all(|block| block.kind != "image")
        );
        let linked = parse(
            "[foto](/tmp/a.PNG) [site](https://example.com/page) <https://example.com/b.jpg?x=1>",
        );
        let drawn: Vec<_> = linked
            .iter()
            .filter(|block| block.kind == "image")
            .map(|block| block.text.as_str())
            .collect();
        assert_eq!(drawn, ["/tmp/a.PNG", "https://example.com/b.jpg?x=1"]);
    }

    #[test]
    fn nested_styles_and_literal_markup_do_not_drop_rich_text() {
        let blocks = parse(
            "**forte e *itálico*** com <b>HTML literal</b> e [link](https://example.com/a%28b%29).",
        );
        assert!(blocks[0].spans.iter().any(|s| s.bold && s.italic));
        assert_ne!(blocks[0].rich, StyledText::from_plain_text(&blocks[0].text));
        let html = parse("Texto <u>literal</u>");
        assert_eq!(html[0].rich, StyledText::from_plain_text(&html[0].text));
        for source in [
            "**forte *itálico* forte**",
            "*itálico **forte** itálico*",
            "a**forte**b e c*itálico*d",
            "~~riscado **forte**~~ e `código`",
        ] {
            assert_eq!(
                parse(source)[0].rich,
                StyledText::from_markdown(source).expect("Markdown de teste válido")
            );
        }
    }
}
