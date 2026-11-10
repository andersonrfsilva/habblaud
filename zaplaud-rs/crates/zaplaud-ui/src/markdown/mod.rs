//! Markdown GFM sem HTML executável e sem cache global.
//!
//! `MarkdownView` recebe `blocks`, `source-markdown` e emite `link-clicked(string)`
//! e `copy(string)`. O aplicativo deve copiar no clipboard e validar/abrir links.
//! Slint 1.18.1 não seleciona `StyledText`: o botão Selecionar troca apenas aquele
//! bloco por `TextInput` somente leitura, com seleção nativa e Ctrl+C. Código já
//! usa esse controle. Não existe seleção entre blocos; copiar mensagem preserva
//! o Markdown original. A lista externa deve virtualizar mensagens e chamar
//! `parse` sob demanda; este módulo não retém mensagens nem cria runtimes.

use pulldown_cmark::{Alignment, CodeBlockKind, Event, Options, Parser, Tag, TagEnd};
use slint::{ModelExt, ModelRc, StyledText, VecModel};
use std::rc::Rc;

include!(concat!(env!("OUT_DIR"), "/markdown.rs"));

fn model<T: Clone + 'static>(items: Vec<T>) -> ModelRc<T> {
    Rc::new(VecModel::from(items)).into()
}

#[derive(Default)]
struct Inline {
    spans: Vec<MdSpan>,
    bold: usize,
    italic: usize,
    strike: usize,
    links: Vec<String>,
}

impl Inline {
    fn push(&mut self, text: &str, code: bool) {
        let span = MdSpan {
            text: text.into(),
            bold: self.bold > 0,
            italic: self.italic > 0,
            strike: self.strike > 0,
            code,
            link: self.links.last().map_or("", String::as_str).into(),
        };
        if let Some(last) = self.spans.last_mut()
            && last.bold == span.bold
            && last.italic == span.italic
            && last.strike == span.strike
            && last.code == span.code
            && last.link == span.link
        {
            last.text = format!("{}{}", last.text, text).into();
        } else {
            self.spans.push(span);
        }
    }

    fn take(&mut self, heading: bool) -> (String, StyledText, Vec<MdSpan>) {
        let spans = std::mem::take(&mut self.spans);
        let mut plain = String::new();
        let mut markup = String::new();
        for span in &spans {
            plain.push_str(&span.text);
            let escaped = escape(&span.text);
            let mut run = escaped;
            if span.code {
                // Os delimitadores crescem para preservar backticks no conteúdo.
                let ticks = "`".repeat(
                    span.text
                        .split(|c| c != '`')
                        .map(str::len)
                        .max()
                        .unwrap_or(0)
                        + 1,
                );
                run = format!("{ticks} {} {ticks}", span.text);
            }
            if span.bold || heading {
                run = format!("**{run}**");
            }
            if span.italic {
                run = format!("*{run}*");
            }
            if span.strike {
                run = format!("~~{run}~~");
            }
            if !span.link.is_empty() {
                let url = span
                    .link
                    .replace('\\', "%5C")
                    .replace('(', "%28")
                    .replace(')', "%29")
                    .replace('<', "%3C")
                    .replace('>', "%3E");
                run = format!("[{run}](<{url}>)");
            }
            markup.push_str(&run);
        }
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

#[derive(Default)]
struct Blocks {
    blocks: Vec<MdBlock>,
    inline: Inline,
    lists: Vec<Option<u64>>,
    marker: String,
    quote: usize,
    level: i32,
    code: Option<(String, String)>,
    cells: Vec<MdCell>,
    alignments: Vec<Alignment>,
    column: usize,
    table_head: bool,
}

impl Blocks {
    fn flush(&mut self) {
        if self.inline.spans.is_empty() {
            return;
        }
        let (text, rich, spans) = self.inline.take(self.level > 0);
        self.blocks.push(MdBlock {
            kind: if self.quote > 0 { "quote" } else { "text" }.into(),
            text: text.into(),
            rich,
            spans: model(spans),
            level: self.level,
            depth: self.lists.len().saturating_sub(1) as i32,
            marker: std::mem::take(&mut self.marker).into(),
            ..Default::default()
        });
    }
}

/// Converte CommonMark/GFM em blocos leves, mantendo estilos inline e texto literal.
/// HTML permanece texto; imagens viram links e nunca fazem acesso à rede.
pub fn parse(markdown: &str) -> Vec<MdBlock> {
    let options =
        Options::ENABLE_TABLES | Options::ENABLE_STRIKETHROUGH | Options::ENABLE_TASKLISTS;
    let mut state = Blocks::default();
    for event in Parser::new_ext(markdown, options) {
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
            Event::Start(Tag::Strong) => state.inline.bold += 1,
            Event::End(TagEnd::Strong) => state.inline.bold -= 1,
            Event::Start(Tag::Emphasis) => state.inline.italic += 1,
            Event::End(TagEnd::Emphasis) => state.inline.italic -= 1,
            Event::Start(Tag::Strikethrough) => state.inline.strike += 1,
            Event::End(TagEnd::Strikethrough) => state.inline.strike -= 1,
            Event::Start(Tag::Link { dest_url, .. } | Tag::Image { dest_url, .. }) => {
                state.inline.links.push(dest_url.to_string())
            }
            Event::End(TagEnd::Link | TagEnd::Image) => {
                state.inline.links.pop();
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
                let language = match kind {
                    CodeBlockKind::Fenced(info) => info.to_string(),
                    CodeBlockKind::Indented => String::new(),
                };
                state.code = Some((language, String::new()));
            }
            Event::End(TagEnd::CodeBlock) => {
                if let Some((language, text)) = state.code.take() {
                    state.blocks.push(MdBlock {
                        kind: "code".into(),
                        text: text.into(),
                        language: language.into(),
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
                let (text, rich, _) = state.inline.take(state.table_head);
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
                if let Some((_, code)) = &mut state.code {
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
    }
}
