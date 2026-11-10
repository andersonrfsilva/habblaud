use super::{kotlin_blank, kotlin_hash, utf16_len};
use crate::model::{EntryRow, TerminalEntry, VIEWS};
use std::collections::HashSet;

pub const VIEW_MODES: [&str; 3] = VIEWS;
pub const SUMMARY_MIN_CHARS: usize = 500;
pub const SUMMARY_MAX_CHARS: usize = 32768;

pub fn visible_entries(entries: &[TerminalEntry], view: &str) -> Vec<TerminalEntry> {
    entries
        .iter()
        .filter(|entry| match view {
            "prompts" => matches!(entry.kind.as_str(), "user" | "assistant"),
            "no_tools" => !matches!(entry.kind.as_str(), "tool" | "result"),
            _ => true,
        })
        .cloned()
        .collect()
}

pub fn group_turn(entries: &[TerminalEntry]) -> Vec<EntryRow> {
    let tool_ids: HashSet<_> = entries
        .iter()
        .filter(|entry| entry.kind == "tool")
        .map(|entry| entry.id.as_str())
        .collect();
    let mut rows: Vec<EntryRow> = Vec::new();
    let (mut thinking, mut tools): (Option<usize>, Option<usize>) = (None, None);
    for entry in entries {
        if entry.kind == "result"
            && entry
                .tool_use_id
                .as_deref()
                .is_some_and(|id| tool_ids.contains(id))
        {
            continue;
        }
        if entry.kind == "user" {
            thinking = None;
            tools = None;
        }
        let group = match entry.kind.as_str() {
            "thinking" => Some(&mut thinking),
            "tool" => Some(&mut tools),
            _ => None,
        };
        if let Some(group) = group {
            if let Some(index) = *group {
                let row = &mut rows[index];
                if entry.kind == "thinking" {
                    if row.count == 1 && kotlin_blank(&row.entry.text) {
                        row.entry.text.clear();
                    }
                    if !kotlin_blank(&entry.text) {
                        if !row.entry.text.is_empty() {
                            row.entry.text.push_str("\n\n");
                        }
                        row.entry.text.push_str(&entry.text);
                    }
                } else {
                    row.tools.push(entry.clone());
                }
                row.count += 1;
                row.entry.at = entry.at.or(row.entry.at);
            } else {
                *group = Some(rows.len());
                let mut row = EntryRow::new(entry.clone());
                if entry.kind == "tool" {
                    row.tools.push(entry.clone());
                }
                rows.push(row);
            }
        } else {
            rows.push(EntryRow::new(entry.clone()));
        }
    }
    rows
}

pub fn tool_group_time(row: &EntryRow, entries: &[TerminalEntry]) -> Option<i64> {
    row.tools
        .iter()
        .filter_map(|tool| tool.at)
        .chain(
            entries
                .iter()
                .filter(|entry| {
                    entry.kind == "result"
                        && row
                            .tools
                            .iter()
                            .any(|tool| entry.tool_use_id.as_ref() == Some(&tool.id))
                })
                .filter_map(|entry| entry.at),
        )
        .max()
        .or(row.entry.at)
}

pub fn recovered_errors(entries: &[TerminalEntry]) -> HashSet<String> {
    let mut recovered = HashSet::new();
    let mut pending = Vec::new();
    for entry in entries {
        match entry.kind.as_str() {
            "user" => pending.clear(),
            "system" if entry.id.ends_with(":err") => pending.push(entry.id.clone()),
            "assistant" | "tool" | "thinking" => recovered.extend(pending.drain(..)),
            _ => {}
        }
    }
    recovered
}

pub fn summary_key(entry: &TerminalEntry) -> String {
    format!("{}:{}", entry.id, kotlin_hash(&entry.text))
}
pub fn wants_summary(entry: &TerminalEntry, enabled: bool) -> bool {
    enabled && entry.kind == "assistant" && utf16_len(&entry.text) > SUMMARY_MIN_CHARS
}
/// Vec<u16> preserva inclusive um surrogate final cortado pelo take do Kotlin.
pub fn summary_text(entry: &TerminalEntry) -> Vec<u16> {
    entry.text.encode_utf16().take(SUMMARY_MAX_CHARS).collect()
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SummaryState {
    pub entry_id: String,
    pub key: String,
    pub text: Option<String>,
    pub failed: bool,
    pub pending: bool,
    pub expanded: bool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SummaryEvent {
    Observe {
        entry: TerminalEntry,
        enabled: bool,
        cached: Option<String>,
    },
    Completed {
        key: String,
        text: String,
    },
    Failed {
        key: String,
        cancelled: bool,
    },
    Toggle,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SummaryEffect {
    Summarize { key: String, text: Vec<u16> },
    PropagateCancellation,
}
pub fn reduce_summary(
    mut state: SummaryState,
    event: SummaryEvent,
) -> (SummaryState, Vec<SummaryEffect>) {
    let mut effects = Vec::new();
    match event {
        SummaryEvent::Observe {
            entry,
            enabled,
            cached,
        } => {
            let key = summary_key(&entry);
            if state.key != key {
                let expanded = state.entry_id == entry.id && state.expanded;
                state = SummaryState {
                    entry_id: entry.id.clone(),
                    key,
                    text: cached,
                    expanded,
                    ..SummaryState::default()
                };
            }
            if wants_summary(&entry, enabled)
                && state.text.is_none()
                && !state.failed
                && !state.pending
            {
                state.pending = true;
                effects.push(SummaryEffect::Summarize {
                    key: state.key.clone(),
                    text: summary_text(&entry),
                });
            }
        }
        SummaryEvent::Completed { key, text } if key == state.key => {
            state.pending = false;
            state.text = Some(text);
        }
        SummaryEvent::Failed { key, cancelled } if key == state.key => {
            state.pending = false;
            if cancelled {
                effects.push(SummaryEffect::PropagateCancellation);
            } else {
                state.failed = true;
            }
        }
        SummaryEvent::Toggle => state.expanded = !state.expanded,
        _ => {}
    }
    (state, effects)
}
