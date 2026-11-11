//! Construção compartilhada das linhas do transcript e dos modelos Slint.
use slint::{Model, ModelRc, VecModel};
use std::collections::{BTreeMap, HashSet};
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
    kind: String,
    source: String,
    display: String,
    label: String,
    time: String,
    expanded: bool,
    summarized: bool,
    tools: Vec<(String, String)>,
}
pub fn markdown(source: &str) -> ModelRc<MdBlock> {
    let blocks = crate::markdown::parse(source)
        .into_iter()
        .map(|b| MdBlock {
            kind: b.kind,
            rich: b.rich,
            links: b.links,
            link_labels: b.link_labels,
            text: b.text,
            level: b.level,
            depth: b.depth,
            marker: b.marker,
            language: b.language,
            code_lines: b.code_lines,
            columns: b.columns,
            spans: ModelRc::new(VecModel::from(
                b.spans
                    .iter()
                    .map(|s| MdSpan {
                        text: s.text,
                        bold: s.bold,
                        italic: s.italic,
                        strike: s.strike,
                        code: s.code,
                        link: s.link,
                    })
                    .collect::<Vec<_>>(),
            )),
            cells: ModelRc::new(VecModel::from(
                b.cells
                    .iter()
                    .map(|c| MdCell {
                        rich: c.rich,
                        text: c.text,
                        alignment: c.alignment,
                        header: c.header,
                    })
                    .collect::<Vec<_>>(),
            )),
        })
        .collect::<Vec<_>>();
    ModelRc::new(VecModel::from(blocks))
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
            let summarized =
                conversation::wants_summary(entry, enabled) && summary.is_some_and(|s| !s.failed);
            let display = if summarized && !expanded {
                label = strings::SUMMARY_LABEL.into();
                summary
                    .and_then(|s| s.text.clone())
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
                kind: entry.kind.clone(),
                source,
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
    rows
}
impl RenderRow {
    pub fn into_ui(self) -> ConversationRow {
        let row = self;
        ConversationRow {
            id: row.id.into(),
            kind: row.kind.into(),
            source: row.source.into(),
            blocks: if row.summarized && !row.expanded {
                ModelRc::default()
            } else {
                markdown(&row.display)
            },
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
