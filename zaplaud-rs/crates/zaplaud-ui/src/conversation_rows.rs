//! Construção compartilhada das linhas do transcript e dos modelos Slint.
pub use crate::transcript::{split_transcript, transcript_pieces};
use slint::{Model, ModelExt, ModelRc, VecModel};
use std::collections::{BTreeMap, HashSet};
use std::{ops::Range, sync::Arc};
use zaplaud_core::{
    model::TerminalEntry,
    rules::{
        conversation::{self, SummaryState},
        time,
    },
};
include!(concat!(env!("OUT_DIR"), "/app.rs"));
pub mod strings {
    include!(concat!(env!("OUT_DIR"), "/strings.rs"));
}
#[derive(Clone, PartialEq)]
pub struct RenderRow {
    id: String,
    entry_id: String,
    first: bool,
    last: bool,
    fragment: String,
    range: Range<usize>,
    markdown_source: MarkdownSource,
    kind: String,
    source: slint::SharedString,
    display: String,
    label: String,
    time: String,
    expanded: bool,
    summarized: bool,
    tools: Vec<(String, String)>,
}

pub struct FindSource<'a> {
    pub id: &'a str,
    pub source: &'a slint::SharedString,
    pub markdown: bool,
    pub collapsed: bool,
}

pub struct FindRun {
    pub piece: usize,
    pub block: i32,
    pub cell: i32,
    pub normalized: conversation::FindText,
}

impl FindSource<'_> {
    pub fn runs(&self) -> Vec<FindRun> {
        let mut runs = Vec::new();
        let mut push = |piece, block, cell, text: slint::SharedString| {
            if !text.is_empty() {
                let normalized = conversation::FindText::new(&text);
                runs.push(FindRun { piece, block, cell, normalized });
            }
        };
        for (piece, fragment) in transcript_pieces(self.source).into_iter().enumerate() {
            if !self.markdown {
                push(piece, -1, -1, fragment.source.into());
                continue;
            }
            let synthetic_header = fragment
                .display
                .strip_suffix(fragment.source.as_str())
                .is_some_and(|prefix| !prefix.is_empty());
            for (block, parsed) in crate::markdown::find_blocks(&fragment.display)
                .into_iter()
                .enumerate()
            {
                let block = block as i32;
                match parsed.kind.as_str() {
                    "text" | "quote" | "code" => push(piece, block, -1, parsed.text),
                    "table" => {
                        for (cell, content) in parsed.cells.iter().enumerate() {
                            if !(synthetic_header && block == 0 && content.header) {
                                push(piece, block, cell as i32, content.text);
                            }
                        }
                    }
                    _ => {}
                }
            }
        }
        runs
    }
}

pub fn find_row_id(entry_id: &slint::SharedString, piece: usize) -> slint::SharedString {
    if piece == 0 {
        entry_id.clone()
    } else {
        format!("{entry_id}:piece:{piece}").into()
    }
}

fn ui_spans(spans: ModelRc<crate::markdown::MdSpan>) -> ModelRc<MdSpan> {
    ModelRc::new(spans.map(|span| MdSpan {
        text: span.text,
        bold: span.bold,
        italic: span.italic,
        strike: span.strike,
        code: span.code,
        link: span.link,
    }))
}
pub fn anchor_index(rows: &[RenderRow], entry: &str, fragment: &str) -> Option<usize> {
    rows.iter()
        .position(|row| row.entry_id == entry && row.id == fragment)
        .or_else(|| rows.iter().position(|row| row.entry_id == entry))
}
#[derive(Clone, Default, PartialEq)]
pub struct MarkdownSource {
    codes: Arc<Vec<(Range<usize>, slint::SharedString)>>,
}
impl MarkdownSource {
    pub fn new(source: &str) -> Self {
        use pulldown_cmark::{Event, Parser, Tag, TagEnd};
        let mut codes = Vec::new();
        let mut code = None;
        for (event, range) in Parser::new(source).into_offset_iter() {
            match event {
                Event::Start(Tag::CodeBlock(_)) => code = Some((range, String::new())),
                Event::Text(text) => {
                    if let Some((_, content)) = &mut code {
                        content.push_str(&text);
                    }
                }
                Event::End(TagEnd::CodeBlock) => {
                    if let Some((range, content)) = code.take() {
                        codes.push((range, content.into()));
                    }
                }
                _ => {}
            }
        }
        Self {
            codes: Arc::new(codes),
        }
    }
    pub fn model(&self, display: &str, range: Range<usize>) -> ModelRc<MdBlock> {
        let mut originals = self
            .codes
            .iter()
            .filter(|(code, _)| code.start < range.end && range.start < code.end);
        let blocks = crate::markdown::parse(display)
            .into_iter()
            .map(|mut b| {
                let original = if b.kind == "code" {
                    originals.next()
                } else {
                    None
                };
                let continued_after = original.is_some_and(|(code, _)| code.end > range.end);
                if continued_after && let Some(text) = b.text.strip_suffix('\n') {
                    b.text = text.into();
                    b.layout_lines -= 1;
                }
                MdBlock {
                    kind: b.kind.clone(),
                    rich: b.rich,
                    copy_text: original.map_or_else(|| b.text.clone(), |(_, text)| text.clone()),
                    continued_before: original.is_some_and(|(code, _)| code.start < range.start),
                    continued_after,
                    links: b.links,
                    link_labels: b.link_labels,
                    text: b.text,
                    level: b.level,
                    depth: b.depth,
                    marker: b.marker,
                    language: b.language,
                    // Trecho parcial de um diagrama dividido não representa o diagrama inteiro.
                    diagram: if original
                        .is_some_and(|(code, _)| code.start < range.start || code.end > range.end)
                    {
                        slint::Image::default()
                    } else {
                        b.diagram
                    },
                    code_lines: b.code_lines,
                    layout_lines: b.layout_lines,
                    columns: b.columns,
                    spans: ui_spans(b.spans),
                    cells: ModelRc::new(VecModel::from(
                        b.cells
                            .iter()
                            .map(|c| MdCell {
                                rich: c.rich,
                                spans: ui_spans(c.spans),
                                text: c.text,
                                alignment: c.alignment,
                                header: c.header,
                            })
                            .collect::<Vec<_>>(),
                    )),
                }
            })
            .collect::<Vec<_>>();
        ModelRc::new(VecModel::from(blocks))
    }
}
pub fn build_rows(
    entries: &[TerminalEntry],
    view: &str,
    expanded: &HashSet<String>,
    summaries: &BTreeMap<String, SummaryState>,
    enabled: bool,
    truncated: bool,
    now: i64,
) -> Vec<RenderRow> {
    let visible = conversation::visible_entries(entries, view);
    let recovered = conversation::recovered_errors(entries);
    let mut rows = conversation::group_turn(&visible)
        .into_iter()
        .map(|row| {
            let entry = &row.entry;
            let expanded = expanded.contains(&entry.id);
            let at = if entry.kind == "tool" {
                conversation::tool_group_time(&row, entries)
            } else {
                entry.at
            };
            let mut source = entry.text.clone();
            let mut label = String::new();
            let mut tools = Vec::new();
            match entry.kind.as_str() {
                "system" if recovered.contains(&entry.id) => {
                    source = strings::RESPONSE_INTERRUPTED.into()
                }
                "thinking" => label = format!("{} ({})", strings::THINKING, row.count),
                "advisor" => label = format!("{} ({})", strings::ROLE_ADVISOR, row.count),
                "tool" => {
                    label = format!("{} ({})", strings::TOOLS, row.count);
                    tools = row
                        .tools
                        .iter()
                        .map(|tool| {
                            let mut text = tool.text.clone();
                            for result in entries.iter().filter(|e| {
                                e.kind == "result" && e.tool_use_id.as_ref() == Some(&tool.id)
                            }) {
                                text.push_str(&format!(
                                    "\n{}\n{}",
                                    strings::TOOL_RESULT,
                                    result.text
                                ));
                                if result.truncated {
                                    text.push_str(&format!("\n{}", strings::RESULT_TRUNCATED));
                                }
                            }
                            (tool.title.clone().unwrap_or_default(), text)
                        })
                        .collect();
                }
                _ => {}
            }
            let summary = summaries.get(&conversation::summary_key(entry));
            // Resumo desligado: o começo da resposta, sem modelo; vazio (só código) mostra a resposta inteira.
            let brief = conversation::wants_brief(entry, enabled)
                .then(|| conversation::reply_brief(&entry.text))
                .filter(|brief| !brief.is_empty());
            let summarized = brief.is_some()
                || conversation::wants_summary(entry, enabled)
                    && summary.is_some_and(|s| !s.failed);
            let display = if summarized && !expanded {
                label = strings::SUMMARY_LABEL.into();
                brief
                    .or_else(|| summary.and_then(|s| s.text.clone()))
                    .unwrap_or_else(|| strings::SUMMARY_LOADING.into())
            } else {
                if summarized {
                    label = strings::SUMMARY_COLLAPSE.into();
                }
                if entry.truncated {
                    format!("{}\n\n{}", source, strings::RESULT_TRUNCATED)
                } else {
                    source.clone()
                }
            };
            RenderRow {
                id: entry.id.clone(),
                entry_id: entry.id.clone(),
                first: true,
                last: true,
                fragment: source.clone(),
                range: 0..0,
                markdown_source: MarkdownSource::default(),
                kind: entry.kind.clone(),
                source: source.into(),
                display,
                label,
                time: at
                    .and_then(|at| time::entry_time(at, now))
                    .unwrap_or_default(),
                expanded,
                summarized,
                tools,
            }
        })
        .collect::<Vec<_>>();
    if truncated {
        rows.insert(
            0,
            RenderRow {
                id: "local:window-truncated".into(),
                kind: "system".into(),
                entry_id: "local:window-truncated".into(),
                first: true,
                last: true,
                fragment: strings::WINDOW_TRUNCATED.into(),
                range: 0..0,
                markdown_source: MarkdownSource::default(),
                source: strings::WINDOW_TRUNCATED.into(),
                display: String::new(),
                label: String::new(),
                time: String::new(),
                expanded: false,
                summarized: false,
                tools: Vec::new(),
            },
        );
    }
    rows.into_iter().flat_map(RenderRow::split).collect()
}
impl RenderRow {
    fn split(mut self) -> Vec<Self> {
        let mut parts = Vec::new();
        self.fragment.clear();
        let text_group = matches!(self.kind.as_str(), "thinking" | "advisor");
        if !text_group
            && !matches!(self.kind.as_str(), "tool" | "system")
            && (!self.summarized || self.expanded)
        {
            self.markdown_source = MarkdownSource::new(&self.source);
        }
        if self.kind == "tool" && self.expanded {
            self.display.clear();
            for (title, text) in std::mem::take(&mut self.tools) {
                for piece in transcript_pieces(&text) {
                    let mut row = self.clone();
                    row.tools = vec![(title.clone(), piece.source)];
                    parts.push(row);
                }
            }
        } else if !(text_group || self.kind == "tool") || self.expanded {
            let display = std::mem::take(&mut self.display);
            let text = if matches!(self.kind.as_str(), "thinking" | "system") {
                self.source.as_str()
            } else {
                &display
            };
            for piece in transcript_pieces(text) {
                let mut row = self.clone();
                row.fragment = piece.source;
                row.range = piece.range;
                row.display = if row.summarized && !row.expanded {
                    row.fragment.clone()
                } else {
                    piece.display
                };
                parts.push(row);
            }
        }
        if parts.is_empty() {
            parts.push(self);
        }
        let count = parts.len();
        for (index, row) in parts.iter_mut().enumerate() {
            row.first = index == 0;
            row.last = index + 1 == count;
            if index > 0 {
                row.id = format!("{}:piece:{index}", row.entry_id);
            }
            if !row.last {
                row.time.clear();
            }
        }
        parts
    }
    pub fn find_source(&self) -> Option<FindSource<'_>> {
        if !matches!(self.kind.as_str(), "user" | "assistant" | "system") {
            return None;
        }
        Some(FindSource {
            id: &self.entry_id,
            source: &self.source,
            markdown: self.kind != "system",
            collapsed: self.summarized && !self.expanded,
        })
    }

    pub fn into_ui(self) -> ConversationRow {
        let row = self;
        let blocks = if row.summarized && !row.expanded
            || matches!(row.kind.as_str(), "tool" | "thinking" | "advisor" | "system")
        {
            ModelRc::default()
        } else {
            row.markdown_source.model(&row.display, row.range)
        };
        ConversationRow {
            id: row.id.into(),
            entry_id: row.entry_id.into(),
            first: row.first,
            last: row.last,
            fragment: row.fragment.into(),
            kind: row.kind.into(),
            source: row.source,
            blocks,
            display: row.display.into(),
            label: row.label.into(),
            time: row.time.into(),
            expanded: row.expanded,
            summarized: row.summarized,
            tools: ModelRc::new(VecModel::from(
                row.tools
                    .into_iter()
                    .map(|(title, text)| ToolDetail {
                        title: title.into(),
                        text: text.into(),
                    })
                    .collect::<Vec<_>>(),
            )),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn advisor_disclosure_preserves_comments_and_respects_view_filters() {
        use zaplaud_core::model::ServerEvent;

        let ServerEvent::ConversationAppend(entries) = ServerEvent::parse(
            "",
            "append",
            r#"[
                {"id":"a1","kind":"advisor","text":"Primeiro comentário"},
                {"id":"t1","kind":"thinking","text":"Raciocínio"},
                {"id":"x1","kind":"tool","text":"ls"},
                {"id":"a2","kind":"advisor","text":"Segundo comentário","truncated":true},
                {"id":"r1","kind":"assistant","text":"Resposta"},
                {"id":"u2","kind":"user","text":"Novo turno"},
                {"id":"a3","kind":"advisor","text":"Outro comentário"}
            ]"#,
        )
        .unwrap() else {
            panic!("expected conversation append");
        };
        let render = |view, expanded: &HashSet<String>| {
            build_rows(&entries, view, expanded, &BTreeMap::new(), true, false, 0)
                .into_iter()
                .map(RenderRow::into_ui)
                .collect::<Vec<_>>()
        };

        for (view, expected) in [
            ("all", &["a1", "t1", "x1", "r1", "u2", "a3"][..]),
            ("no_tools", &["a1", "t1", "r1", "u2", "a3"][..]),
            ("prompts", &["r1", "u2"][..]),
        ] {
            let rows = render(view, &HashSet::new());
            assert_eq!(
                rows.iter().map(|row| row.id.as_str()).collect::<Vec<_>>(),
                expected
            );
            for row in rows.iter().filter(|row| row.kind == "advisor") {
                assert!(!row.expanded);
                assert!(row.fragment.is_empty());
            }
        }

        let rows = render("all", &HashSet::from(["a1".to_owned()]));
        let advisor = &rows[0];
        assert_eq!(advisor.kind, "advisor");
        assert_eq!(
            advisor.label.as_str(),
            format!("{} (2)", strings::ROLE_ADVISOR)
        );
        assert!(advisor.expanded);
        assert_eq!(
            advisor.fragment.as_str(),
            format!(
                "Primeiro comentário\n\nSegundo comentário\n\n{}",
                strings::RESULT_TRUNCATED
            )
        );
        assert_eq!(rows[1].kind, "thinking");
        assert_eq!(rows[3].kind, "assistant");
        assert!(!rows[5].expanded);
    }

    #[test]
    fn find_indexes_reply_under_summary_and_significant_markdown_text() {
        let reply = TerminalEntry {
            id: "reply".into(),
            kind: "assistant".into(),
            text: format!(
                "Conclusão primeiro.\n\n{}\n\n**Uma** [ação][ref] em `linha`.\n\n\
                 ```rust\nlet café = 1;\n```\n\n[ref]: https://destino.test",
                "detalhe ".repeat(100)
            ),
            ..Default::default()
        };
        let summaries = BTreeMap::from([(
            conversation::summary_key(&reply),
            SummaryState {
                text: Some("Resumo gerado.".into()),
                ..Default::default()
            },
        )]);
        let entries = [
            reply,
            TerminalEntry {
                id: "warning".into(),
                kind: "system".into(),
                text: "Aviso!".into(),
                ..Default::default()
            },
            TerminalEntry {
                id: "thought".into(),
                kind: "thinking".into(),
                text: "Oculto".into(),
                ..Default::default()
            },
        ];
        let rows = build_rows(&entries, "all", &HashSet::new(), &summaries, true, false, 0);
        let reply = rows[0].find_source().unwrap();
        assert!(reply.collapsed);
        let runs = reply.runs();
        let matches = |term: &str| runs.iter().any(|run| run.normalized.occurrences(term).next().is_some());
        assert!(matches("uma acao em linha."));
        assert!(matches("let cafe = 1;"));
        assert!(!matches("destino.test") && !matches("resumo gerado"));
        let runs = rows[1].find_source().unwrap().runs();
        assert_eq!(runs[0].normalized.occurrences("aviso!").next(), Some(0..6));
        assert!(rows[2].find_source().is_none());
    }

    #[test]
    fn reading_anchor_survives_insertions_and_summary_replacement() {
        let reply = TerminalEntry {
            id: "reply".into(),
            kind: "assistant".into(),
            text: "linha longa com detalhes\n".repeat(2000),
            ..Default::default()
        };
        let entries = vec![reply.clone()];
        let expanded = HashSet::from(["reply".to_owned()]);
        let rows = build_rows(
            &entries,
            "all",
            &expanded,
            &BTreeMap::new(),
            false,
            false,
            0,
        );
        let fragment = rows[1].id.clone();
        let mut entries = vec![
            TerminalEntry {
                id: "earlier".into(),
                kind: "user".into(),
                text: "Antes".into(),
                ..Default::default()
            },
            reply,
        ];
        let rows = build_rows(
            &entries,
            "all",
            &expanded,
            &BTreeMap::new(),
            false,
            false,
            0,
        );
        assert_eq!(anchor_index(&rows, "reply", &fragment), Some(2));
        let rows = build_rows(
            &entries,
            "all",
            &HashSet::new(),
            &BTreeMap::new(),
            false,
            false,
            0,
        );
        assert_eq!(anchor_index(&rows, "reply", &fragment), Some(1));
        entries.remove(1);
        let rows = build_rows(
            &entries,
            "all",
            &expanded,
            &BTreeMap::new(),
            false,
            false,
            0,
        );
        assert_eq!(anchor_index(&rows, "reply", &fragment), None);
    }

    #[test]
    fn disabled_summary_shows_reply_start_until_expanded() {
        let entry = TerminalEntry {
            id: "r".into(),
            kind: "assistant".into(),
            text: format!("Conclusão primeiro.\n\n{}", "detalhe ".repeat(100)),
            ..Default::default()
        };
        // Resumo do modelo guardado de quando estava ligado.
        let cached = BTreeMap::from([(
            conversation::summary_key(&entry),
            SummaryState {
                text: Some("Resumo do modelo.".into()),
                ..Default::default()
            },
        )]);
        let build = |expanded: &HashSet<String>, enabled| {
            build_rows(
                std::slice::from_ref(&entry),
                "all",
                expanded,
                &cached,
                enabled,
                false,
                0,
            )
        };
        let closed = build(&HashSet::new(), false);
        assert!(
            closed[0].summarized && closed[0].display.starts_with("Conclusão primeiro. detalhe")
        );
        assert_eq!(closed[0].label, strings::SUMMARY_LABEL);
        let open = build(&HashSet::from(["r".to_owned()]), false);
        assert_eq!(
            open.iter().map(|r| r.fragment.as_str()).collect::<String>(),
            entry.text
        );
        // Ligado: o resumo do modelo volta.
        assert_eq!(build(&HashSet::new(), true)[0].display, "Resumo do modelo.");
    }

    #[test]
    fn continued_rows_keep_entry_identity_source_and_last_time() {
        let text = "linha de uma resposta extensa\n".repeat(2000);
        let entry = TerminalEntry {
            id: "original".into(),
            kind: "assistant".into(),
            text: text.clone(),
            at: Some(1_000),
            ..Default::default()
        };
        // Aberta: com resumo desligado, a resposta longa fechada mostraria só o começo.
        let rows = build_rows(
            &[entry],
            "all",
            &HashSet::from(["original".to_owned()]),
            &BTreeMap::new(),
            false,
            false,
            2_000,
        );
        assert!(rows.len() > 1);
        assert!(
            rows.first()
                .is_some_and(|row| row.first && row.id == "original")
        );
        assert!(
            rows.last()
                .is_some_and(|row| row.last && !row.time.is_empty())
        );
        let mut ids = HashSet::new();
        for (index, row) in rows.iter().enumerate() {
            assert!(ids.insert(&row.id));
            assert_eq!(row.entry_id, "original");
            assert_eq!(row.source, text);
            assert_eq!(row.last, index + 1 == rows.len());
            if !row.last {
                assert!(row.time.is_empty());
            }
        }
        assert_eq!(
            rows.iter()
                .map(|row| row.fragment.as_str())
                .collect::<String>(),
            text
        );
    }

    #[test]
    fn copy_code_uses_original_block_position_not_matching_text() {
        let code = "igual\n".repeat(2000);
        let source = format!("```text\nigual\n```\n\n```rust\n{code}```\n");
        let markdown = MarkdownSource::new(&source);
        let mut copied_large = 0;
        for piece in transcript_pieces(&source) {
            for block in markdown.model(&piece.display, piece.range).iter() {
                if block.kind == "code" && block.language == "rust" {
                    assert_eq!(block.copy_text, code);
                    copied_large += 1;
                }
            }
        }
        assert!(copied_large > 1);
    }
}
