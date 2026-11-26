use crate::{
    AppWindow, ConversationState, ConversationViewState, FindCodeMark, FindCodePreview, FindMark,
    FindPaint, InfoRow, MarkdownFind, MdSpan, TaskRow,
    feature::FeatureContext,
    state::{Event, now_millis},
    strings, update_ui,
};
use slint::{Color, ComponentHandle, Model, ModelExt, ModelRc, SharedString, StyledText, VecModel};
use std::{
    borrow::Borrow,
    cell::RefCell,
    collections::{BTreeMap, HashMap, HashSet},
    rc::Rc,
    ops::Range,
    time::{Duration, SystemTime},
};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::{HabblaudApi, retry_after},
    controller::conversation::{ConversationController, snapshot_matches_session},
    model::{AgentTask, SessionItem, StreamEvent, TaskStatus},
    rules::conversation::{self, SummaryEffect, SummaryEvent, SummaryState},
    rules::tasks::{TaskProgress, task_phases, task_progress},
    rules::time,
};
use zaplaud_store::{LocalStateStore, ReadingState};

use zaplaud_ui::conversation_rows::{FindRun, RenderRow, build_rows, find_row_id};
enum Command {
    View(usize),
    Toggle(String),
    ExpandForFind(i32, String),
    Observe(String),
    Position(i32, bool),
    Anchor(i32, String, String, f32, bool),
    Metadata(Box<SessionItem>),
    Preferences,
    ModelQuery,
}
impl Command {
    fn matches_reading_generation(&self, active: i32) -> bool {
        match self {
            Self::Position(generation, _) | Self::Anchor(generation, ..)
            | Self::ExpandForFind(generation, _) => *generation == active,
            _ => true,
        }
    }
}
thread_local! { static ROWS: RefCell<Option<Rc<VecModel<RenderRow>>>> = const { RefCell::new(None) }; }

#[derive(Default)]
struct FindMessage {
    source: SharedString,
    runs: Vec<FindRun>,
    first: usize,
    last: usize,
    collapsed: bool,
    markdown: bool,
    revision: usize,
}

#[derive(Clone, PartialEq, Eq)]
struct FindHit {
    entry: SharedString,
    piece: usize,
    block: i32,
    cell: i32,
    range: Range<usize>,
}

type BlockMarks = HashMap<(i32, i32), ModelRc<FindMark>>;

#[derive(Default)]
struct FindState {
    term: String,
    revision: usize,
    messages: HashMap<SharedString, FindMessage>,
    matches: Vec<FindHit>,
    marks: HashMap<SharedString, BlockMarks>,
    paint_revision: i32,
    current: Option<usize>,
    pick_top: Option<usize>,
    expanding: Option<SharedString>,
}

thread_local! { static FIND: RefCell<FindState> = RefCell::new(FindState::default()); }

impl FindState {
    fn index(&mut self, rows: impl IntoIterator<Item = impl Borrow<RenderRow>>) {
        self.revision = self.revision.wrapping_add(1);
        let revision = self.revision;
        for (index, row) in rows.into_iter().enumerate() {
            let Some(source) = row.borrow().find_source() else {
                continue;
            };
            let message = if let Some(message) = self.messages.get_mut(source.id) {
                message
            } else {
                self.messages.entry(source.id.into()).or_default()
            };
            if message.revision != revision {
                message.first = index;
                message.collapsed = source.collapsed;
                if message.source != *source.source || message.markdown != source.markdown {
                    message.runs = source.runs();
                    message.source = source.source.clone();
                    message.markdown = source.markdown;
                }
                message.revision = revision;
            }
            message.last = index;
        }
        self.messages.retain(|_, message| message.revision == revision);
    }

    fn current_hit(&self) -> Option<&FindHit> {
        self.current.and_then(|index| self.matches.get(index))
    }

    fn visual_row(&self, hit: &FindHit) -> usize {
        let message = &self.messages[&hit.entry];
        message.first + hit.piece.min(message.last - message.first)
    }

    fn refresh(&mut self, state: &ConversationViewState, paint: &MarkdownFind, top: i32, edited: bool) {
        let old = self.current_hit().cloned();
        self.matches.clear();
        self.marks.clear();
        if !self.term.is_empty() {
            let mut messages: Vec<_> = self.messages.iter().collect();
            messages.sort_unstable_by_key(|(_, message)| message.first);
            for (entry, message) in messages {
                for run in &message.runs {
                    let marks: Vec<_> = run.normalized.occurrences(&self.term).map(|range| {
                        let mark = FindMark {
                            start: range.start as i32,
                            end: range.end as i32,
                            index: self.matches.len() as i32,
                        };
                        self.matches.push(FindHit {
                            entry: entry.clone(),
                            piece: run.piece,
                            block: run.block,
                            cell: run.cell,
                            range,
                        });
                        mark
                    }).collect();
                    if !marks.is_empty() {
                        self.marks.entry(find_row_id(entry, run.piece)).or_default()
                            .insert((run.block, run.cell), ModelRc::new(VecModel::from(marks)));
                    }
                }
            }
        }
        let top = if edited { top.max(0) as usize } else { self.pick_top.unwrap_or(top.max(0) as usize) };
        self.current = if edited || self.pick_top.is_some() {
            None
        } else {
            old.as_ref().and_then(|hit| self.matches.iter().position(|candidate| candidate == hit))
        }
        .or_else(|| conversation::find_initial(&self.matches, top, |hit| self.visual_row(hit)));
        self.paint_revision = self.paint_revision.wrapping_add(1).max(1);
        paint.set_revision(if self.marks.is_empty() { 0 } else { self.paint_revision });
        if (edited || self.pick_top.is_some())
            && let Some(hit) = self.current_hit()
            && self.visual_row(hit) == top
            && !self.messages[&hit.entry].collapsed
        {
            let row = find_row_id(&hit.entry, hit.piece);
            self.pick_top = Some(top);
            Self::stop_navigation(state);
            paint.set_probe_row(row);
            return;
        }
        self.pick_top = None;
        paint.set_probe_row("".into());
        self.present(state, paint, edited || self.current_hit() != old.as_ref());
    }

    fn picked(&mut self, state: &ConversationViewState, paint: &MarkdownFind, index: i32) {
        let Some(top) = self.pick_top.take() else { return };
        if index >= 0 {
            self.current = Some(index as usize);
        } else if !self.matches.is_empty() {
            let after = self.matches.partition_point(|hit| self.visual_row(hit) <= top);
            self.current = Some(after.min(self.matches.len() - 1));
        }
        self.present(state, paint, true);
    }

    fn present(&mut self, state: &ConversationViewState, paint: &MarkdownFind, navigate: bool) {
        let counter = if let Some(index) = self.current {
            crate::formatted(
                "find_count",
                &[&(index + 1).to_string(), &self.matches.len().to_string()],
            )
        } else if self.term.is_empty() {
            String::new()
        } else {
            strings::FIND_NONE.into()
        };
        state.set_find_counter(counter.into());
        let Some(hit) = self.current_hit().cloned() else {
            if !state.get_find_current().is_empty() {
                Self::stop_navigation(state);
            }
            state.set_find_current("".into());
            paint.set_current(-1);
            self.expanding = None;
            return;
        };
        let id = hit.entry;
        let row = find_row_id(&id, hit.piece);
        paint.set_current(self.current.unwrap_or(0) as i32);
        state.set_find_current(id.clone());
        let waiting = self.expanding.as_ref() == Some(&id);
        if !navigate && !waiting {
            return;
        }
        let message = &self.messages[&id];
        if message.collapsed {
            if navigate {
                Self::stop_navigation(state);
            }
            if !waiting {
                self.expanding = Some(id.clone());
                state.invoke_find_expand(state.get_reading_generation(), id);
            }
            return;
        }
        self.expanding = None;
        state.set_reset_request(state.get_reset_request().wrapping_add(1));
        state.set_capture_pending(false);
        state.set_restore_pending(true);
        state.set_stable_ticks(0);
        state.set_measured_height(-1.);
        state.set_scan_index(-1);
        state.set_anchor_entry(id);
        state.set_anchor_row(row);
        state.set_anchor_offset(0.);
        state.set_anchor_index((message.first + hit.piece) as i32);
        state.set_find_jump(true);
        state.set_anchor_ticks(46);
    }

    fn stop_navigation(state: &ConversationViewState) {
        state.set_reset_request(state.get_reset_request().wrapping_add(1));
        state.set_restore_pending(false);
        state.set_find_jump(false);
        state.set_anchor_ticks(0);
        state.set_pending_capture_generation(state.get_reading_generation());
        state.set_capture_pending(true);
    }
}

fn find_changed(state: &ConversationViewState, paint: &MarkdownFind, top: i32) {
    if !state.get_find_open() {
        return;
    }
    FIND.with(|find| {
        let mut find = find.borrow_mut();
        let term = conversation::find_term(&state.get_find_query());
        if term == find.term {
            return;
        }
        if find.term.is_empty() {
            ROWS.with(|slot| {
                if let Some(rows) = slot.borrow().as_ref() {
                    find.index(rows.iter());
                }
            });
        }
        find.term = term;
        find.refresh(state, paint, top, true);
    });
}

fn reset_find(state: &ConversationViewState, paint: &MarkdownFind) {
    state.set_find_open(false);
    state.set_find_query("".into());
    state.set_find_counter("".into());
    state.set_find_top_index(-1);
    state.set_find_current("".into());
    paint.set_current(-1);
    paint.set_probe_row("".into());
    paint.set_revision(0);
    FIND.with(|find| *find.borrow_mut() = FindState::default());
}

fn find_viewport_top(state: &ConversationViewState) -> i32 {
    let top = state.get_find_top_index();
    if top >= 0 {
        top
    } else {
        state.get_anchor_index()
    }
}

fn set_rows(window: &AppWindow, rows: Vec<RenderRow>, reset: bool) {
    let state = window.global::<ConversationViewState>();
    let searching = state.get_find_open() && FIND.with(|find| !find.borrow().term.is_empty());
    if searching {
        FIND.with(|find| find.borrow_mut().index(&rows));
    }
    ROWS.with(|slot| {
        let mut slot = slot.borrow_mut();
        if reset || slot.is_none() {
            let model = Rc::new(VecModel::from(rows));
            state.set_rows(ModelRc::new(model.clone().map(RenderRow::into_ui)));
            *slot = Some(model);
        } else if let Some(model) = slot.as_ref() {
            let old = model.row_count();
            for (index, row) in rows.iter().enumerate().take(old.min(rows.len())) {
                if model.row_data(index).as_ref() != Some(row) {
                    model.set_row_data(index, row.clone());
                }
            }
            for _ in rows.len()..old {
                model.remove(rows.len());
            }
            for row in rows.into_iter().skip(old) {
                model.push(row);
            }
        }
    });
    if searching {
        let top = find_viewport_top(&state);
        let paint = window.global::<MarkdownFind>();
        FIND.with(|find| find.borrow_mut().refresh(&state, &paint, top, false));
    }
}

fn code_marks(text: &str, marks: &ModelRc<FindMark>) -> ModelRc<FindCodeMark> {
    ModelRc::new(VecModel::from(code_mark_rows(text, marks.iter())))
}

fn code_mark_rows(text: &str, marks: impl IntoIterator<Item = FindMark>) -> Vec<FindCodeMark> {
    let mut result = Vec::new();
    let mut line = 0;
    let mut line_start = 0;
    let mut scanned = 0;
    for mark in marks {
        let start = mark.start as usize;
        let end = (mark.end as usize).min(text.len());
        let Some(between) = text.get(scanned..start) else { continue };
        line += between.bytes().filter(|&ch| ch == b'\n').count() as i32;
        if let Some(last) = between.rfind('\n') {
            line_start = scanned + last + 1;
        }
        scanned = start;
        let Some(matched) = text.get(start..end) else { continue };
        for (offset, part) in matched.split('\n').enumerate() {
            if !part.is_empty() {
                result.push(FindCodeMark {
                    line: line + offset as i32,
                    prefix: if offset == 0 { text[line_start..start].into() } else { "".into() },
                    text: part.into(),
                    index: mark.index,
                    first: offset == 0,
                });
            }
        }
    }
    result
}

fn diagram_source(text: &str, marks: &ModelRc<FindMark>, current: i32) -> FindCodePreview {
    let Some(hit) = marks.iter().find(|mark| mark.index == current).or_else(|| marks.row_data(0))
    else {
        return FindCodePreview::default();
    };
    // A imagem é uma única linha virtual. Mostre um trecho curto para manter as coordenadas do código no orçamento.
    let hit_start = hit.start as usize;
    let hit_end = hit.end as usize;
    let start = text.floor_char_boundary(hit_start.saturating_sub(192));
    let end = text.floor_char_boundary(text.len().min(hit_end + 192).min(start + 768));
    let start = text[start..hit_start].find('\n').map_or(start, |offset| start + offset + 1);
    let end = text[hit_end.min(end)..end].rfind('\n').map_or(end, |offset| hit_end + offset);
    let preview = &text[start..end];
    let marks = marks.iter().filter_map(|mark| {
        let low = start.max(mark.start as usize);
        let high = end.min(mark.end as usize);
        (low < high).then(|| FindMark {
            start: (low - start) as i32,
            end: (high - start) as i32,
            index: mark.index,
        })
    });
    FindCodePreview {
        text: preview.into(),
        lines: preview.split('\n').count() as i32,
        marks: ModelRc::new(VecModel::from(code_mark_rows(preview, marks))),
    }
}

// O Slint só permite cor e sublinhado em vários trechos independentes do StyledText.
fn paint_text(
    rich: StyledText,
    text: SharedString,
    spans: ModelRc<MdSpan>,
    marks: ModelRc<FindMark>,
    current: i32,
    ink: Color,
    active_ink: Color,
    bold: bool,
    italic: bool,
) -> FindPaint {
    use i_slint_core::styled_text::{get_raw_text, parse_markdown};
    const ARG: &str = "\u{e541}";
    let active = marks.iter().find(|mark| mark.index == current);
    let paint = |limit: usize| {
        let mut format = String::new();
        let mut args = Vec::new();
        let mut offset = 0;
        let mut marks = marks.iter().peekable();
        let plain = MdSpan { text: text.clone(), ..Default::default() };
        let spans = spans.iter().chain((spans.row_count() == 0).then_some(plain));
        for span in spans {
            if offset >= limit { break; }
            let Some(source) = span.text.get(..(limit - offset).min(span.text.len())) else { break };
            let end = offset + source.len();
            let mut subformat = String::new();
            let mut subargs = Vec::new();
            let mut at = offset;
            while at < end {
                while marks.peek().is_some_and(|mark| mark.end as usize <= at) {
                    marks.next();
                }
                let next_mark = marks.peek();
                let matched = next_mark.filter(|mark| mark.start as usize <= at);
                let next = matched.map_or_else(
                    || next_mark.map_or(end, |mark| end.min(mark.start as usize)),
                    |mark| end.min(mark.end as usize),
                );
                let Some(piece) = source.get(at - offset..next - offset) else { break };
                if let Some(mark) = matched {
                    let chosen = mark.index == current;
                    let color = if chosen { active_ink } else { ink };
                    let (r, g, b) = (color.red(), color.green(), color.blue());
                    subformat.push_str(&format!("<u><font color=\"#{r:02x}{g:02x}{b:02x}\">"));
                    if chosen && !bold && !span.bold { subformat.push_str("**"); }
                    subformat.push_str(ARG);
                    if chosen && !bold && !span.bold { subformat.push_str("**"); }
                    subformat.push_str("</font></u>");
                } else {
                    subformat.push_str(ARG);
                }
                subargs.push(StyledText::from_plain_text(piece));
                at = next;
            }
            if !subargs.is_empty() {
                let sub = parse_markdown(&subformat, &subargs);
                let mut wrapper = ARG.to_owned();
                if span.code { wrapper = format!("`{wrapper}`"); }
                if span.strike { wrapper = format!("~~{wrapper}~~"); }
                if italic || span.italic { wrapper = format!("_{wrapper}_"); }
                if bold || span.bold { wrapper = format!("**{wrapper}**"); }
                if !span.link.is_empty() {
                    let link = span.link.replace('\\', "%5C").replace('<', "%3C").replace('>', "%3E");
                    wrapper = format!("[{wrapper}](<{link}>)");
                }
                args.push(if wrapper == ARG { sub } else { parse_markdown(&wrapper, &[sub]) });
                format.push_str(ARG);
            }
            offset = end;
        }
        parse_markdown(&format, &args)
    };
    let painted = paint(text.len());
    // A reconstrução nunca substitui o texto original por uma versão truncada.
    let rich = if get_raw_text(&painted).as_ref() == text.as_str() { painted } else { rich };
    FindPaint {
        rich,
        prefix: active.as_ref().map_or_else(StyledText::default, |mark| paint(mark.end as usize)),
        active: active.is_some(),
    }
}

fn setup_find_paint(paint: &MarkdownFind) {
    paint.on_marks(|row, block, cell, _| {
        FIND.with(|find| {
            find.borrow().marks.get(&row)
                .and_then(|blocks| blocks.get(&(block, cell)).cloned())
                .unwrap_or_default()
        })
    });
    paint.on_paint(|rich, text, spans, marks, current, ink, active, bold, italic| {
        paint_text(rich, text, spans, marks, current, ink, active, bold, italic)
    });
    paint.on_code_marks(|text, marks| code_marks(&text, &marks));
    paint.on_diagram_source(|text, marks, current| diagram_source(&text, &marks, current));
    paint.on_prefix(|text, end| text.get(..end as usize).unwrap_or_default().into());
}
fn notice(error: Option<&str>, connection: &str) -> String {
    match error {
        Some("oversize") => strings::READING_OVERSIZE,
        Some("forbidden") => strings::TERMINAL_BLOCKED,
        Some("missing") => strings::READING_MISSING,
        Some(_) => strings::READING_OFFLINE,
        None if connection == "connecting" || connection == "reconnecting" => {
            strings::READING_CONNECTING
        }
        _ => "",
    }
    .into()
}

struct SessionRun {
    controller: ConversationController,
    store: LocalStateStore,
    epoch: u64,
    view: String,
    enabled: bool,
    expanded: HashSet<String>,
    reading: ReadingState,
    summaries: BTreeMap<String, SummaryState>,
    following: bool,
    new_count: usize,
    last_visible: Option<String>,
}
impl Drop for SessionRun {
    fn drop(&mut self) {
        self.save();
    }
}
impl SessionRun {
    fn save(&self) {
        if let Some(item) = self.controller.selected.as_ref() {
            let _ = self.store.set_reading_state(&item.key, &self.reading);
        }
        if !self.controller.cache_ready() {
            return;
        }
        if let Some(item) = self.controller.selected.as_ref().filter(|s| !s.sub) {
            let write = self.store.conversation_write(&item.key.origin, self.epoch);
            let _ = self.store.set_conversation(
                &item.key,
                &self.controller.entries(),
                self.controller.conversation.truncated,
                &write,
                &self.controller.summaries,
            );
        }
    }
    fn render(&mut self, weak: &slint::Weak<AppWindow>, reading_generation: Option<i32>) {
        let entries = self.controller.entries();
        let visible = conversation::visible_entries(&entries, &self.view);
        if let Some(last) = visible.last() {
            if !self.following
                && self
                    .last_visible
                    .as_ref()
                    .is_some_and(|old| old != &last.id)
            {
                self.new_count += visible
                    .iter()
                    .position(|e| Some(&e.id) == self.last_visible.as_ref())
                    .map_or(1, |index| visible.len() - index - 1);
            }
            self.last_visible = Some(last.id.clone());
        }
        if self.following {
            self.new_count = 0;
        }
        let rows = build_rows(
            &entries,
            &self.view,
            &self.expanded,
            &self.summaries,
            self.enabled,
            self.controller.conversation.truncated,
            now_millis(),
        );
        let notice = notice(
            self.controller.error.as_deref(),
            &self.controller.connection,
        );
        let reduced = if self.controller.conversation.reduced {
            strings::WINDOW_REDUCED
        } else {
            ""
        };
        let truncated = if self.controller.conversation.truncated {
            strings::WINDOW_TRUNCATED
        } else {
            ""
        };
        let view = self.view.clone();
        let count = self.new_count.min(i32::MAX as usize) as i32;
        let item = self.controller.selected.clone();
        let partial = self.controller.conversation.truncated;
        let reading = self.reading.clone();
        update_ui(weak, move |window| {
            let state = window.global::<ConversationViewState>();
            state.set_notice(notice.into());
            state.set_reduced(reduced.into());
            state.set_truncated(truncated.into());
            state.set_view(view.into());
            state.set_new_count(count);
            if let Some(generation) = reading_generation {
                state.set_reading_generation(generation);
                state.set_reset_request(state.get_reset_request().wrapping_add(1));
                state.set_restore_pending(!reading.following && !reading.entry.is_empty());
                state.set_anchor_ticks(46);
                state.set_stable_ticks(0);
                state.set_measured_height(-1.);
                state.set_following(reading.following && !state.get_find_open());
                state.set_capture_pending(false);
                state.set_scan_index(-1);
                state.set_anchor_index(-1);
                state.set_anchor_entry(reading.entry.into());
                state.set_anchor_row(reading.row.into());
                state.set_anchor_offset(reading.offset);
                if reading.following {
                    if state.get_find_open() {
                        state.invoke_position(generation, false);
                    } else {
                        state.set_end_request(state.get_end_request().wrapping_add(1));
                    }
                }
            }
            let anchor = state.get_anchor_row();
            let entry = state.get_anchor_entry();
            let index = zaplaud_ui::conversation_rows::anchor_index(&rows, &entry, &anchor);
            state.set_anchor_index(index.map_or(-1, |index| index as i32));
            set_rows(&window, rows, reading_generation.is_some());
            if let Some(item) = item {
                publish_info(&window, &item, &entries, partial);
            }
        });
    }
}

fn info_row(label: &str, value: String) -> InfoRow {
    InfoRow {
        label: label.into(),
        value: value.into(),
        ..Default::default()
    }
}

fn task_rows(tasks: &[AgentTask]) -> Vec<TaskRow> {
    let mut rows = Vec::with_capacity(tasks.len());
    for (phase, tasks) in task_phases(tasks) {
        if let Some(phase) = phase {
            rows.push(TaskRow {
                label: phase.into(),
                ..Default::default()
            });
        }
        rows.extend(tasks.iter().map(|task| {
            let (marker, state) = match task.status {
                TaskStatus::Pending => ("○", strings::TASKS_STATE_PENDING),
                TaskStatus::InProgress => ("◐", strings::TASKS_STATE_IN_PROGRESS),
                TaskStatus::Completed => ("✓", strings::TASKS_STATE_COMPLETED),
                TaskStatus::Abandoned => ("–", strings::TASKS_STATE_ABANDONED),
            };
            TaskRow {
                label: task.display_title().into(),
                marker: marker.into(),
                state: state.into(),
            }
        }));
    }
    rows
}

fn task_labels(progress: Option<TaskProgress<'_>>) -> (slint::SharedString, slint::SharedString) {
    let Some(progress) = progress else {
        return (strings::TASKS_TITLE.into(), "".into());
    };
    let done = progress.done.to_string();
    let total = progress.total.to_string();
    let count = crate::formatted("tasks_progress_count", &[&done, &total]);
    let title = format!("{} · {count}", strings::TASKS_TITLE);
    let line = if progress.done == progress.total {
        String::new()
    } else if let Some(current) = progress.current {
        crate::formatted("tasks_progress_current", &[&done, &total, current])
    } else {
        count
    };
    (title.into(), line.into())
}

fn info_rows(
    item: &SessionItem,
    entries: &[zaplaud_core::model::TerminalEntry],
    partial: bool,
    progress: Option<TaskProgress<'_>>,
) -> Vec<InfoRow> {
    let mut rows = Vec::new();
    let section = |rows: &mut Vec<InfoRow>, label: &str, mut values: Vec<InfoRow>| {
        values.retain(|row| !row.value.trim().is_empty());
        if !values.is_empty() {
            rows.push(InfoRow {
                label: label.into(),
                section: true,
                ..Default::default()
            });
            rows.extend(values);
        }
    };
    let status = if item.agent_id.is_none() {
        strings::INFO_CLOSED
    } else {
        match item.status.as_deref() {
            Some("working" | "busy" | "running") => strings::STATUS_WORKING,
            Some("idle") => strings::STATUS_IDLE,
            Some("waiting") => strings::STATUS_WAITING,
            Some(status) => status,
            None => strings::INFO_OPEN,
        }
    };
    let last = item
        .last_at
        .map(|at| {
            let relative = time::relative_time(at, now_millis());
            let amount = relative.amount.map(|amount| amount.to_string());
            crate::formatted(
                relative.key,
                &amount.as_deref().into_iter().collect::<Vec<_>>(),
            )
        })
        .unwrap_or_default();
    let prompts = entries
        .iter()
        .filter(|entry| entry.kind == "user")
        .count()
        .to_string();
    let replies = entries
        .iter()
        .filter(|entry| entry.kind == "assistant")
        .count()
        .to_string();
    let mut loaded = crate::formatted("info_loaded_value", &[&prompts, &replies]);
    if partial {
        loaded.push_str(&format!(" · {}", strings::INFO_PARTIAL));
    }
    section(
        &mut rows,
        strings::INFO_STATUS_SECTION,
        vec![
            info_row(strings::INFO_ROLE, item.role.clone().unwrap_or_default()),
            info_row(strings::INFO_TASK, item.task.clone().unwrap_or_default()),
            info_row(strings::INFO_STATUS, status.into()),
            InfoRow {
                elapsed: true,
                ..info_row(
                    strings::INFO_ELAPSED,
                    item.status_since
                        .map(|at| at.to_string())
                        .unwrap_or_default(),
                )
            },
            info_row(
                strings::INFO_ACTIVITY,
                item.activity.clone().unwrap_or_default(),
            ),
            info_row(strings::INFO_LAST_ACTIVITY, last),
            info_row(strings::INFO_LOADED, loaded),
        ],
    );
    section(
        &mut rows,
        strings::INFO_MODEL_SECTION,
        vec![
            info_row(strings::MODEL_MENU, item.model.clone().unwrap_or_default()),
            info_row(
                strings::THINKING_MENU,
                item.thinking
                    .as_ref()
                    .map(|thinking| crate::formatted(&format!("thinking_{thinking}"), &[]))
                    .unwrap_or_default(),
            ),
        ],
    );
    let context = zaplaud_core::rules::composer::reliable_context(&item.key.provider)
        .then(|| item.context_tokens.zip(item.context_window))
        .flatten()
        .filter(|(tokens, window)| *tokens >= 0 && *window > 0)
        .map(|(tokens, window)| {
            format!(
                "{tokens} / {window} ({}%)",
                (tokens as f64 / window as f64 * 100.).clamp(0., 100.) as i32
            )
        })
        .unwrap_or_default();
    section(
        &mut rows,
        strings::INFO_CONTEXT_SECTION,
        vec![info_row(strings::INFO_CONTEXT, context)],
    );
    if !item.tasks.is_empty() {
        let (done, total) = progress.map_or((0, 0), |progress| (progress.done, progress.total));
        rows.push(InfoRow {
            section: true,
            tasks_section: true,
            fraction: if total == 0 {
                0.
            } else {
                done as f32 / total as f32
            },
            ..info_row(
                strings::TASKS_TITLE,
                crate::formatted(
                    "tasks_progress_summary",
                    &[&done.to_string(), &total.to_string()],
                ),
            )
        });
    }
    let technical = |label, value| InfoRow {
        copyable: true,
        ..info_row(label, value)
    };
    section(
        &mut rows,
        strings::INFO_TECHNICAL_SECTION,
        vec![
            technical(
                strings::INFO_PROJECT,
                item.project.clone().unwrap_or_default(),
            ),
            technical(strings::INFO_PROVIDER, item.key.provider.clone()),
            technical(strings::INFO_ACCOUNT, item.key.account.clone()),
            InfoRow {
                parent_key: item
                    .parent_key
                    .as_ref()
                    .and_then(|key| key.serialize().ok())
                    .unwrap_or_default()
                    .into(),
                ..technical(
                    strings::INFO_PARENT,
                    item.parent_key
                        .as_ref()
                        .map(|key| key.session_id.clone())
                        .unwrap_or_default(),
                )
            },
            technical(
                strings::INFO_AGENT,
                item.agent_id.clone().unwrap_or_default(),
            ),
            technical(
                strings::INFO_SESSION,
                if item.sub {
                    item.server_session_id.clone().unwrap_or_default()
                } else {
                    item.key.session_id.clone()
                },
            ),
            technical(strings::INFO_SERVER, item.key.origin.clone()),
        ],
    );
    rows
}

fn publish_info(
    window: &AppWindow,
    item: &SessionItem,
    entries: &[zaplaud_core::model::TerminalEntry],
    partial: bool,
) {
    let state = window.global::<ConversationViewState>();
    let progress = task_progress(&item.tasks);
    let (tasks_title, progress_label) = task_labels(progress);
    state.set_tasks_title(tasks_title);
    state.set_task_progress(progress_label);
    state.set_tasks(ModelRc::new(VecModel::from(task_rows(&item.tasks))));
    state.set_info(ModelRc::new(VecModel::from(info_rows(
        item, entries, partial, progress,
    ))));
    let current_model = item.model.as_deref().and_then(|cur| {
        if cur.contains('/') {
            return Some(cur);
        }
        item.models
            .iter()
            .find(|m| m.as_str() == cur || m.split_once('/').is_some_and(|(_, name)| name == cur))
            .map(String::as_str)
    });
    let all_groups = zaplaud_core::rules::composer::role_model_groups(&item.models, current_model);
    let has_models = !all_groups.is_empty();
    let query = state.get_model_query();
    let groups = if query.is_empty() {
        all_groups
    } else {
        zaplaud_core::rules::composer::role_model_groups_filtered(
            &item.models,
            current_model,
            &query,
        )
    };
    let mut choices = Vec::new();
    for (provider, models) in groups {
        choices.push(crate::ModelChoice {
            label: zaplaud_core::rules::composer::provider_label(&provider).into(),
            header: true,
            ..Default::default()
        });
        // Com filtro, mostre todas as versões que casam.
        let (latest, older) = if query.is_empty() {
            zaplaud_core::rules::composer::latest_model_versions(&models, current_model)
        } else {
            (models, Vec::new())
        };
        let option = |value: String, older: bool| crate::ModelChoice {
            label: zaplaud_core::rules::composer::model_option_label(&value).into(),
            value: value.into(),
            group: provider.as_str().into(),
            older,
            ..Default::default()
        };
        choices.extend(latest.into_iter().map(|value| option(value, false)));
        if !older.is_empty() {
            choices.push(crate::ModelChoice {
                label: format!("{} ({})", strings::MODELS_MORE, older.len()).into(),
                group: provider.as_str().into(),
                more: true,
                ..Default::default()
            });
        }
        choices.extend(older.into_iter().map(|value| option(value, true)));
    }
    state.set_has_models(has_models);
    state.set_models(ModelRc::new(VecModel::from(choices)));
    state.set_model(current_model.unwrap_or_default().into());
    state.set_model_detail(
        current_model
            .or(item.model.as_deref())
            .map(zaplaud_core::rules::composer::model_label)
            .unwrap_or_default()
            .into(),
    );
    state.set_thinking(item.thinking.clone().unwrap_or_default().into());
    state.set_working(item.agent_id.is_some() && item.status.as_deref() == Some("working"));
    state.set_codex(item.key.provider == "codex");
    state.set_activity(
        time::status_line_text(item.activity.as_deref())
            .unwrap_or_else(|| strings::STATUS_LINE_WORKING.into())
            .into(),
    );
    state.set_status_since(
        item.status_since
            .map(|at| at.to_string())
            .unwrap_or_default()
            .into(),
    );
}

async fn confirm(api: &HabblaudApi, item: &SessionItem) -> Result<bool, zaplaud_core::Error> {
    for attempt in 1..=3 {
        match api.request("/api/snapshot", None).await {
            Ok(data) => return Ok(snapshot_matches_session(&data, item)?),
            Err(error) => {
                let transient = matches!(
                    &error,
                    zaplaud_core::Error::Network(_)
                        | zaplaud_core::Error::Api {
                            status: 408 | 429 | 500..=599,
                            ..
                        }
                );
                if !transient || attempt == 3 {
                    return Err(error);
                }
                let retry = match &error {
                    zaplaud_core::Error::Api {
                        retry_after: header,
                        ..
                    } => retry_after(header.as_deref(), SystemTime::now()),
                    _ => Duration::ZERO,
                };
                tokio::time::sleep(Duration::from_secs(attempt).max(retry)).await;
            }
        }
    }
    Ok(false)
}
fn unauthorized(error: &zaplaud_core::Error) -> bool {
    matches!(
        error,
        zaplaud_core::Error::Unauthorized | zaplaud_core::Error::Api { status: 401, .. }
    )
}

fn cache_save_deadline(
    pending: Option<tokio::time::Instant>,
    now: tokio::time::Instant,
    delay: Duration,
) -> tokio::time::Instant {
    let candidate = now + delay;
    pending.map_or(candidate, |deadline| deadline.min(candidate))
}

async fn run_session(
    item: SessionItem,
    api: HabblaudApi,
    weak: slint::Weak<AppWindow>,
    commands: mpsc::UnboundedSender<Event>,
    reading_generation: i32,
    mut receiver: mpsc::UnboundedReceiver<Command>,
) {
    let store = match LocalStateStore::new() {
        Ok(store) => store,
        Err(_) => {
            update_ui(&weak, |w| {
                w.global::<ConversationViewState>()
                    .set_notice(strings::STORAGE_ERROR.into())
            });
            return;
        }
    };
    let epoch = store.conversation_epoch(&item.key.origin);
    let cache = if item.sub {
        None
    } else {
        store.conversation(&item.key).ok().flatten()
    };
    let view = store
        .session_view(&item.key)
        .unwrap_or_else(|_| "all".into());
    let enabled = store.summarize().unwrap_or(false);
    let reading = store.reading_state(&item.key).unwrap_or_default();
    let mut run = SessionRun {
        controller: ConversationController::default(),
        store,
        epoch,
        view,
        enabled,
        expanded: reading.expanded.iter().cloned().collect(),
        reading: reading.clone(),
        summaries: BTreeMap::new(),
        following: reading.following,
        new_count: 0,
        last_visible: None,
    };
    let (generation, path) = match run.controller.open(item.clone(), cache) {
        Ok(open) => open,
        Err(_) => return,
    };
    run.render(&weak, Some(reading_generation));
    let mut stream = match api.resilient_stream(&path) {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let mut summary_jobs = tokio::task::JoinSet::new();
    let mut save_at: Option<tokio::time::Instant> = None;
    loop {
        tokio::select! {
            command = receiver.recv() => {
                match command {
                    Some(Command::View(index)) => if let Some(view) = conversation::VIEW_MODES.get(index) {
                        run.view = (*view).into();
                        run.last_visible = None;
                        run.new_count = 0;
                        if run.store.set_session_view(&item.key,view).is_err() { update_ui(&weak,|w| w.global::<ConversationViewState>().set_notice(strings::STORAGE_ERROR.into())); }
                        run.render(&weak,None);
                    },
                    Some(Command::Toggle(id)) => {
                        run.reading.toggle(id);
                        run.expanded = run.reading.expanded.iter().cloned().collect();
                        run.save();
                        run.render(&weak,None);
                    }
                    Some(Command::ExpandForFind(_, id)) => {
                        if !run.expanded.contains(&id) {
                            run.reading.toggle(id);
                            run.expanded = run.reading.expanded.iter().cloned().collect();
                            run.save();
                            run.render(&weak,None);
                        }
                    }
                    Some(Command::Observe(id)) => {
                        let entry = run.controller.conversation.snapshot().find(|entry| entry.id == id).cloned();
                        if let Some(entry) = entry
                            && conversation::wants_summary(&entry, run.enabled) {
                            let key = conversation::summary_key(&entry);
                            if !run.summaries.contains_key(&key) {
                                let cached = run.controller.summaries.get(&key).cloned();
                                let (state,effects) = conversation::reduce_summary(SummaryState::default(),SummaryEvent::Observe { entry,enabled:run.enabled,cached });
                                run.summaries.insert(key,state);
                                for effect in effects { if let SummaryEffect::Summarize { key,text } = effect { let api = api.clone(); summary_jobs.spawn(async move { (key,api.summary_utf16(&text).await) }); } }
                                run.render(&weak,None);
                            }
                        }
                    }
                    Some(Command::Position(_, following)) => {
                        run.following = following;
                        run.reading.following = following;
                        save_at = Some(cache_save_deadline(save_at, tokio::time::Instant::now(), Duration::from_secs(1)));
                        if following && run.new_count != 0 { run.new_count = 0; run.render(&weak,None); }
                    }
                    Some(Command::Anchor(_,entry,row,offset,following)) => {
                        run.reading.entry = entry;
                        run.reading.row = row;
                        run.reading.offset = offset;
                        run.reading.following = following;
                        run.following = following;
                        save_at = Some(cache_save_deadline(save_at, tokio::time::Instant::now(), Duration::from_secs(1)));
                    }
                    Some(Command::Preferences) => {
                        run.enabled = run.store.summarize().unwrap_or(false);
                        run.view = run.store.session_view(&item.key).unwrap_or_else(|_| "all".into());
                        run.last_visible = None;
                        run.render(&weak,None);
                    }
                    Some(Command::Metadata(item)) => {
                        let changed = run.controller.selected.as_ref() != Some(item.as_ref());
                        run.controller.selected = Some(*item);
                        let enabled = run.store.summarize().unwrap_or(false);
                        if run.enabled != enabled { run.enabled = enabled; run.render(&weak,None); }
                        else if changed {
                            let item = run.controller.selected.clone();
                            let entries = run.controller.entries();
                            let partial = run.controller.conversation.truncated;
                            update_ui(&weak,move |window| { if let Some(item) = item { publish_info(&window,&item,&entries,partial); } });
                        }
                    }
                    Some(Command::ModelQuery) => {
                        let item = run.controller.selected.clone();
                        let entries = run.controller.entries();
                        let partial = run.controller.conversation.truncated;
                        update_ui(&weak, move |window| {
                            if let Some(item) = item {
                                publish_info(&window, &item, &entries, partial);
                            }
                        });
                    }
                    None => break,
                }
            }
            event = stream.next_event() => {
                let Some(event) = event else { break };
                if matches!(&event,StreamEvent::Data { event_type,.. } if event_type == "init") && item.agent_id.is_some() && !item.sub {
                    match confirm(&api,&item).await {
                        Ok(true) => {},
                        Ok(false) => { let _ = commands.send(Event::Close); break; },
                        Err(error) => { if unauthorized(&error) { let _ = commands.send(Event::Unauthorized(item.key.origin.clone())); } else { let _ = commands.send(Event::Close); } break; }
                    }
                }
                let result = run.controller.apply(generation,event);
                if run.controller.unauthorized { let _ = commands.send(Event::Unauthorized(item.key.origin.clone())); break; }
                run.render(&weak,None);
                if result.is_err() { break; }
                if result == Ok(true) {
                    save_at = Some(cache_save_deadline(save_at, tokio::time::Instant::now(), Duration::from_secs(3)));
                }
            }
            Some(result) = summary_jobs.join_next(), if !summary_jobs.is_empty() => {
                if let Ok((key,result)) = result
                    && let Some(state) = run.summaries.remove(&key) {
                        let event = match result {
                            Ok(text) => {
                                run.controller.summaries.insert(key.clone(),text.clone());
                                save_at = Some(cache_save_deadline(save_at, tokio::time::Instant::now(), Duration::from_secs(3)));
                                SummaryEvent::Completed { key:key.clone(),text }
                            }
                            Err(error) => { if unauthorized(&error) { let _ = commands.send(Event::Unauthorized(item.key.origin.clone())); } SummaryEvent::Failed { key:key.clone(),cancelled:false } }
                        };
                        run.summaries.insert(key,conversation::reduce_summary(state,event).0);
                        run.render(&weak,None);
                    }
            }
            _ = tokio::time::sleep_until(save_at.unwrap_or_else(|| tokio::time::Instant::now()+Duration::from_secs(86400))), if save_at.is_some() => { run.save(); save_at = None; }
        }
    }
}

fn valid_link(url: &str) -> bool {
    url::Url::parse(url)
        .is_ok_and(|url| matches!(url.scheme(), "http" | "https") && url.host_str().is_some())
}

#[derive(Debug, PartialEq)]
enum LinkAction {
    Browser,
    /// Arquivo do servidor que não é imagem: baixado pelo fluxo da janela de arquivos.
    Download(String),
    Nothing,
}

/// Toque num link da conversa. A imagem do servidor já aparece desenhada; nada abre caminhos locais.
fn link_action(url: &str) -> LinkAction {
    if valid_link(url) {
        return LinkAction::Browser;
    }
    // markdown/mod.rs troca estes caracteres por %XX no texto do Slint; aqui o caminho volta a ser o do servidor.
    let url = url
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%3C", "<")
        .replace("%3E", ">")
        .replace("%5C", "\\");
    match zaplaud_ui::markdown::images::host_path(&url) {
        Some(path) if !zaplaud_ui::markdown::images::is_image_link(path) => {
            LinkAction::Download(path.to_owned())
        }
        _ => LinkAction::Nothing,
    }
}

async fn stop_session(task: &mut Option<tokio::task::JoinHandle<()>>) {
    if let Some(task) = task.take() {
        task.abort();
        let _ = task.await;
    }
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let state = window.global::<ConversationViewState>();
    setup_find_paint(&window.global::<MarkdownFind>());
    let selection = window.global::<crate::MarkdownSelection>();
    selection.on_format(|blocks| zaplaud_ui::markdown::selection_text(&blocks));
    state.set_go_to_end(strings::GO_TO_END.into());
    state.set_view_label(strings::VIEW_MENU.into());
    state.set_find_label(strings::FIND_IN_CONVERSATION.into());
    state.set_find_placeholder(strings::FIND_PLACEHOLDER.into());
    state.set_find_previous(strings::FIND_PREVIOUS.into());
    state.set_find_next(strings::FIND_NEXT.into());
    state.set_find_close_label(strings::SEARCH_CLOSE.into());
    let weak = window.as_weak();
    state.on_find_start(move || {
        let Some(window) = weak.upgrade() else { return };
        if !window.global::<ConversationState>().get_selected()
            || window.global::<crate::AsideState>().get_visible()
        {
            return;
        }
        let state = window.global::<ConversationViewState>();
        if !state.get_find_open() {
            state.set_following(false);
            state.invoke_position(state.get_reading_generation(), false);
            state.set_find_open(true);
            FindState::stop_navigation(&state);
        }
        state.set_find_focus_request(state.get_find_focus_request().wrapping_add(1));
    });
    let weak = window.as_weak();
    state.on_find_close(move || {
        if let Some(window) = weak.upgrade() {
            let state = window.global::<ConversationViewState>();
            FindState::stop_navigation(&state);
            reset_find(&state, &window.global::<MarkdownFind>());
        }
    });
    let weak = window.as_weak();
    state.on_find_changed(move || {
        if let Some(window) = weak.upgrade() {
            let state = window.global::<ConversationViewState>();
            find_changed(&state, &window.global::<MarkdownFind>(), find_viewport_top(&state));
        }
    });
    let weak = window.as_weak();
    state.on_find_picked(move |index| {
        if let Some(window) = weak.upgrade() {
            let state = window.global::<ConversationViewState>();
            let paint = window.global::<MarkdownFind>();
            FIND.with(|find| find.borrow_mut().picked(&state, &paint, index));
        }
    });
    let weak = window.as_weak();
    state.on_find_move(move |forward| {
        let Some(window) = weak.upgrade() else { return };
        let state = window.global::<ConversationViewState>();
        if !state.get_find_open() {
            return;
        }
        let paint = window.global::<MarkdownFind>();
        find_changed(&state, &paint, find_viewport_top(&state));
        FIND.with(|find| {
            let mut find = find.borrow_mut();
            find.pick_top = None;
            paint.set_probe_row("".into());
            if let Some(current) = find.current {
                find.current = conversation::find_step(current, find.matches.len(), forward);
                find.present(&state, &paint, true);
            }
        });
    });
    let weak = window.as_weak();
    crate::window_state::on_focus(move |focused| {
        if let Some(window) = weak.upgrade() {
            let state = window.global::<ConversationViewState>();
            state.set_window_focused(focused);
            state.invoke_position(state.get_reading_generation(), state.get_following());
        }
    });
    state.set_back(strings::BACK.into());
    state.set_view_labels(ModelRc::new(VecModel::from(vec![
        strings::VIEW_ALL.into(),
        strings::VIEW_PROMPTS.into(),
        strings::VIEW_NO_TOOLS.into(),
    ])));
    state.set_info_label(strings::SESSION_INFO.into());
    state.set_copy_label(strings::COPY.into());
    state.set_deliver_label(strings::DELIVER_MODE.into());
    state.set_model_label(strings::MODEL_MENU.into());
    state.set_thinking_label(strings::THINKING_MENU.into());
    state.set_model_filter_placeholder(strings::FILTER_MODELS.into());
    state.set_models_no_results(strings::MODELS_NO_RESULTS.into());
    state.set_models_less(strings::MODELS_LESS.into());
    state.set_shake_label(strings::SHAKE.into());
    state.set_aside_label(strings::ASIDE_MENU.into());
    state.set_thinking_labels(ModelRc::new(VecModel::from(
        zaplaud_core::model::THINKING_LEVELS
            .iter()
            .map(|level| crate::formatted(&format!("thinking_{level}"), &[]).into())
            .collect::<Vec<_>>(),
    )));
    let commands = context.commands.clone();
    state.on_open_parent(move |key| {
        let _ = commands.send(Event::Open(key.into()));
    });
    let weak = window.as_weak();
    window.global::<ConversationState>().on_info(move || {
        if let Some(w) = weak.upgrade() {
            w.global::<ConversationViewState>().set_info_open(true);
        }
    });
    let weak = window.as_weak();
    window.global::<ConversationState>().on_menu(move || {
        if let Some(w) = weak.upgrade() {
            w.global::<ConversationViewState>().set_menu_open(true);
        }
    });
    let (sender, mut receiver) = mpsc::unbounded_channel();
    let send = sender.clone();
    state.on_find_expand(move |generation, id| {
        let _ = send.send(Command::ExpandForFind(generation, id.into()));
    });
    let send = sender.clone();
    window
        .global::<crate::SettingsState>()
        .on_preferences_changed(move || {
            let _ = send.send(Command::Preferences);
        });
    let weak = window.as_weak();
    window
        .global::<crate::ComposerState>()
        .on_scroll_to_end(move || {
            if let Some(window) = weak.upgrade() {
                let state = window.global::<ConversationViewState>();
                state.set_end_request(state.get_end_request().wrapping_add(1));
            }
        });
    let send = sender.clone();
    state.on_choose_view(move |index| {
        if let Ok(index) = usize::try_from(index) {
            let _ = send.send(Command::View(index));
        }
    });
    let send = sender.clone();
    state.on_toggle(move |id| {
        let _ = send.send(Command::Toggle(id.into()));
    });
    let send = sender.clone();
    state.on_observe(move |id| {
        let _ = send.send(Command::Observe(id.into()));
    });
    let send = sender.clone();
    state.on_model_query_changed(move |_| {
        let _ = send.send(Command::ModelQuery);
    });
    let send = sender.clone();
    let weak = window.as_weak();
    state.on_reading_position(move |generation, entry, row, offset, following| {
        if let Some(window) = weak.upgrade() {
            let state = window.global::<ConversationViewState>();
            let index = state
                .get_rows()
                .iter()
                .position(|candidate| candidate.id == row);
            state.set_anchor_index(index.map_or(-1, |index| index as i32));
        }
        let _ = send.send(Command::Anchor(
            generation,
            entry.into(),
            row.into(),
            offset,
            following,
        ));
    });
    let weak = window.as_weak();
    let commands = context.commands.clone();
    state.on_position(move |generation, following| {
        let _ = sender.send(Command::Position(generation, following));
        if let Some(window) = weak.upgrade()
            && let Ok(key) = zaplaud_core::model::SessionKey::deserialize(
                &window.global::<ConversationState>().get_key(),
            )
        {
            let visible = following
                && window
                    .global::<ConversationViewState>()
                    .get_window_focused();
            let _ = commands.send(Event::ReadingPosition {
                origin: key.origin,
                visible,
            });
        }
    });
    let weak = window.as_weak();
    state.on_copy(move |text| {
        if let Some(window) = weak.upgrade() {
            window.invoke_copy_conversation(text);
        }
    });
    let weak = window.as_weak();
    state.on_open_link(move |url| match link_action(&url) {
        LinkAction::Browser => {
            let _ = open_system(std::ffi::OsStr::new(url.as_str()));
        }
        LinkAction::Download(path) => {
            if let Some(window) = weak.upgrade() {
                window
                    .global::<crate::FilesState>()
                    .invoke_download_link(path.into());
            }
        }
        LinkAction::Nothing => {}
    });
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let context = context.clone();
    context.runtime.clone().spawn(async move {
        let mut selected = None;
        let mut was_live = false;
        let mut reading_generation = 0i32;
        let mut task:Option<tokio::task::JoinHandle<()>> = None;
        let mut current:Option<mpsc::UnboundedSender<Command>> = None;
        loop {
            tokio::select! {
                snapshot = snapshots.recv() => {
                    let snapshot = match snapshot { Ok(snapshot) => snapshot,Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,Err(_) => break };
                    let key = snapshot.selected.as_ref().map(|item| {
                        let server = snapshot.servers.iter().find(|server| server.api.origin() == item.key.origin);
                        (item.key.clone(), server.is_some_and(|s| s.reading.terminal_allowed), server.is_some_and(|s| s.needs_login))
                    });
                    let live = snapshot.selected.as_ref().is_some_and(|item| item.agent_id.is_some());
                    let resumed = !was_live && live;
                    was_live = live;
                    if selected == key && !resumed {
                        if let Some(item) = &snapshot.selected {
                            if let Some(sender) = &current { let _ = sender.send(Command::Metadata(Box::new(item.clone()))); }
                            else { let item = item.clone(); update_ui(&weak, move |w| publish_info(&w, &item, &[], true)); }
                        }
                        continue;
                    }
                    // A parada grava a âncora e o cache antes de reabrir a mesma sessão ao vivo.
                    stop_session(&mut task).await;
                    selected = key;
                    current = None;
                    reading_generation = reading_generation.checked_add(1).expect("reading generation exhausted");
                    update_ui(&weak, |w| {
                        let state = w.global::<ConversationViewState>();
                        reset_find(&state, &w.global::<MarkdownFind>());
                        state.set_reset_request(state.get_reset_request().wrapping_add(1));
                        state.set_info_open(false);
                        state.set_menu_open(false);
                        state.set_model_query("".into());
                        state.set_task_progress("".into());
                        state.set_tasks(ModelRc::default());
                        set_rows(&w, Vec::new(), true);
                    });
                    if let Some(item) = &snapshot.selected && let Some(server) = snapshot.servers.iter().find(|s| s.api.origin() == item.key.origin) {
                        let metadata = item.clone();
                        update_ui(&weak, move |w| publish_info(&w, &metadata, &[], true));
                        if !server.reading.terminal_allowed || server.needs_login { update_ui(&weak,|w| w.global::<ConversationViewState>().set_notice(strings::TERMINAL_BLOCKED.into()));continue; }
                        let (sender,receiver) = mpsc::unbounded_channel(); current = Some(sender);
                        task = Some(context.runtime.spawn(run_session(item.clone(),server.api.clone(),weak.clone(),context.commands.clone(),reading_generation,receiver)));
                    }
                }
                command = receiver.recv() => {
                    let Some(command) = command else { break };
                    if let Some(sender) = &current && command.matches_reading_generation(reading_generation) {
                        let _ = sender.send(command);
                    }
                }
            }
        }
        stop_session(&mut task).await;
    });
}

#[cfg(target_os = "android")]
pub(crate) fn open_system(_: &std::ffi::OsStr) -> std::io::Result<()> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "External applications are unavailable in the Android probe",
    ))
}

#[cfg(not(target_os = "android"))]
pub(crate) fn open_system(target: &std::ffi::OsStr) -> std::io::Result<()> {
    let program = if cfg!(target_os = "macos") {
        "open"
    } else {
        "xdg-open"
    };
    // O aplicativo externo pode continuar aberto depois de fechar o cliente.
    std::process::Command::new(program)
        .arg(target)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use zaplaud_core::model::SessionKey;

    #[test]
    fn find_reveals_code_line_and_paragraph_at_end_of_tall_answer() {
        use slint::platform::{
            Platform, WindowAdapter, update_timers_and_animations,
            software_renderer::{MinimalSoftwareWindow, RepaintBufferType},
        };
        struct Headless(Rc<MinimalSoftwareWindow>);
        impl Platform for Headless {
            fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, slint::PlatformError> {
                Ok(self.0.clone())
            }
        }
        let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
        slint::platform::set_platform(Box::new(Headless(window.clone()))).unwrap();
        let probe = crate::TranscriptProbe::new().unwrap();
        probe.set_requested_width(800.);
        probe.set_requested_height(600.);
        probe.show().unwrap();
        window.set_size(slint::PhysicalSize::new(800, 600));
        let state = probe.global::<ConversationViewState>();
        let paint = probe.global::<MarkdownFind>();
        setup_find_paint(&paint);
        let reply = zaplaud_core::model::TerminalEntry {
            id: "long-reply".into(),
            kind: "assistant".into(),
            text: format!("```text\n{} localizador\n```\n\n**Outro localizador.**", "linha\n".repeat(140)),
            ..Default::default()
        };
        let rows = build_rows(&[reply], "all", &HashSet::new(), &BTreeMap::new(), true, false, 0);
        assert!(rows.len() > 2);
        FIND.with(|find| find.borrow_mut().index(&rows));
        state.set_rows(ModelRc::new(VecModel::from(rows).map(RenderRow::into_ui)));
        state.set_following(false);
        state.set_find_open(true);
        let mut pixels = vec![slint::Rgb8Pixel::default(); 800 * 600];
        let mut frame = || {
            update_timers_and_animations();
            window.request_redraw();
            window.draw_if_needed(|renderer| { renderer.render(&mut pixels, 800); });
        };
        frame();
        FIND.with(|find| {
            let mut find = find.borrow_mut();
            find.term = conversation::find_term("localizador");
            find.refresh(&state, &paint, 0, true);
            assert_eq!(find.matches.len(), 2);
            assert!(find.matches[0].piece > 0);
        });
        for index in 0..2 {
            if index == 1 {
                FIND.with(|find| {
                    let mut find = find.borrow_mut();
                    find.current = Some(1);
                    find.present(&state, &paint, true);
                });
            }
            for _ in 0..110 {
                frame();
                if !state.get_restore_pending() { break; }
                std::thread::sleep(Duration::from_millis(17));
            }
            // A última medida anterior ao fim da rolagem pode ser de um frame antigo.
            paint.set_measured(false);
            paint.set_request(paint.get_request() + 1);
            frame();
            assert!(paint.get_measured(), "ocorrência {index} não materializada");
            let y = paint.get_measured_y();
            let height = paint.get_measured_height();
            assert!(
                y >= probe.invoke_viewport_top() - 1. && y + height <= probe.invoke_viewport_bottom() + 1.,
                "ocorrência {index} em {y}..{}, viewport {}..{}",
                y + height, probe.invoke_viewport_top(), probe.invoke_viewport_bottom(),
            );
        }
        reset_find(&state, &paint);
        let weak = probe.as_weak();
        state.on_find_picked(move |index| {
            let probe = weak.unwrap();
            FIND.with(|find| {
                find.borrow_mut().picked(
                    &probe.global::<ConversationViewState>(), &probe.global::<MarkdownFind>(), index,
                );
            });
        });
        let reply = zaplaud_core::model::TerminalEntry {
            id: "same-piece".into(),
            kind: "assistant".into(),
            text: format!("```text\nalvo\n{}alvo\n{}alvo\n```\n", "x\n".repeat(42), "x\n".repeat(22)),
            ..Default::default()
        };
        let rows = build_rows(&[reply], "all", &HashSet::new(), &BTreeMap::new(), true, false, 0);
        assert_eq!(rows.len(), 1);
        FIND.with(|find| find.borrow_mut().index(&rows));
        state.set_rows(ModelRc::new(VecModel::from(rows).map(RenderRow::into_ui)));
        state.set_find_open(true);
        frame();
        probe.invoke_scroll_to(0.65);
        frame();
        FIND.with(|find| {
            let mut find = find.borrow_mut();
            find.term = "alvo".into();
            find.refresh(&state, &paint, 0, true);
            assert_eq!(find.pick_top, Some(0));
        });
        for _ in 0..30 {
            std::thread::sleep(Duration::from_millis(17));
            frame();
            if paint.get_current() >= 0 { break; }
        }
        assert_eq!(paint.get_current(), 1, "deve pular a ocorrência acima da viewport");
        reset_find(&state, &paint);
    }

    #[test]
    fn find_paint_keeps_text_and_formats_crossing_inline_styles() {
        let plain = "uma ação";
        let initial = StyledText::from_markdown("**uma** [ação](<https://example.test>)").unwrap();
        let spans = [
            MdSpan { text: "uma".into(), bold: true, ..Default::default() },
            MdSpan { text: " ".into(), ..Default::default() },
            MdSpan { text: "ação".into(), link: "https://example.test".into(), ..Default::default() },
        ];
        let marks = [FindMark { start: 2, end: plain.len() as i32, index: 0 }];
        let painted = paint_text(
            initial.clone(), plain.into(), ModelRc::new(VecModel::from(spans.to_vec())),
            ModelRc::new(VecModel::from(marks.to_vec())), 0, Color::from_rgb_u8(40, 60, 80),
            Color::from_rgb_u8(60, 40, 80), false, false,
        );
        assert!(painted.active);
        assert_eq!(i_slint_core::styled_text::get_raw_text(&painted.rich), plain);
        assert_ne!(painted.rich, initial);
    }

    #[test]
    fn find_indexes_one_message_across_fragments_and_updates_streamed_text() {
        use zaplaud_core::model::TerminalEntry;
        let entry = TerminalEntry {
            id: "reply".into(),
            kind: "assistant".into(),
            text: "```text\n".to_owned() + &"linha no código\n".repeat(100) + "```",
            ..Default::default()
        };
        let mut entries = vec![entry];
        let rows = |entries: &[TerminalEntry]| {
            build_rows(entries, "all", &HashSet::new(), &BTreeMap::new(), true, false, 0)
        };
        let mut find = FindState::default();
        find.index(rows(&entries));
        assert_eq!(find.messages.len(), 1);
        assert!(find.messages["reply"].last > 0);
        assert_eq!(find.messages["reply"].runs.iter().map(|run| run.normalized.occurrences("codigo").count()).sum::<usize>(), 100);
        entries[0].text.push_str("\n\n**Resultado final.**");
        find.index(rows(&entries));
        assert!(find.messages["reply"].runs.iter().any(|run| {
            run.normalized.occurrences("resultado final.").next().is_some()
        }));
        find.index(rows(&[]));
        assert!(find.messages.is_empty());
    }

    #[test]
    fn cache_save_deadline_survives_continuous_updates() {
        let start = tokio::time::Instant::now();
        let delay = Duration::from_secs(3);
        let mut pending = None;
        let mut saved_at = Vec::new();
        for millis in (0..=10_000).step_by(200) {
            let now = start + Duration::from_millis(millis);
            if pending.is_some_and(|deadline| deadline <= now) {
                saved_at.push(millis);
                pending = None;
            }
            pending = Some(cache_save_deadline(pending, now, delay));
        }
        assert_eq!(saved_at, [3_000, 6_000, 9_000]);
    }

    #[test]
    fn cache_save_deadline_for_an_isolated_update_uses_its_delay() {
        let now = tokio::time::Instant::now();
        for delay in [Duration::from_secs(1), Duration::from_secs(3)] {
            assert_eq!(cache_save_deadline(None, now, delay), now + delay);
        }
    }

    #[test]
    fn cache_save_deadline_only_moves_earlier() {
        let now = tokio::time::Instant::now();
        let short = Duration::from_secs(1);
        let long = Duration::from_secs(3);
        let later = now + Duration::from_millis(200);
        let pending = cache_save_deadline(None, now, long);
        let advanced = cache_save_deadline(Some(pending), later, short);
        assert_eq!(advanced, later + short);
        assert_eq!(cache_save_deadline(Some(advanced), later, long), advanced);
    }

    #[test]
    fn reading_commands_do_not_survive_reopening_the_same_conversation() {
        let (a, b, reopened_a) = (1, 2, 3);
        for command in [
            Command::Position(a, false),
            Command::Anchor(a, "entry".into(), "row".into(), -10., false),
        ] {
            assert!(command.matches_reading_generation(a));
            assert!(!command.matches_reading_generation(b));
            assert!(!command.matches_reading_generation(reopened_a));
        }
        assert!(Command::Position(reopened_a, true).matches_reading_generation(reopened_a));
    }

    #[test]
    fn completed_tasks_hide_the_progress_line_but_keep_panel_rows() {
        let tasks = [
            AgentTask {
                id: "done".into(),
                title: "Completed work".into(),
                status: TaskStatus::Completed,
                active_form: None,
                phase: Some("First phase".into()),
            },
            AgentTask {
                id: "abandoned".into(),
                title: "Abandoned work".into(),
                status: TaskStatus::Abandoned,
                active_form: None,
                phase: None,
            },
        ];
        let (title, line) = task_labels(task_progress(&tasks));
        let count = crate::formatted("tasks_progress_count", &["1", "1"]);
        assert_eq!(title, format!("{} · {count}", strings::TASKS_TITLE));
        assert!(line.is_empty());
        let rows = task_rows(&tasks);
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].label, "First phase");
        assert!(rows[0].marker.is_empty());
        assert_eq!(rows[1].marker, "✓");
        assert_eq!(rows[2].marker, "–");
    }

    #[test]
    fn details_group_metadata_hide_empty_values_and_link_parent() {
        let parent = SessionKey {
            origin: "https://test".into(),
            provider: "omp".into(),
            account: "default".into(),
            session_id: "parent".into(),
        };
        let item = SessionItem {
            key: SessionKey {
                session_id: "parent/sub".into(),
                ..parent.clone()
            },
            agent_id: Some("sub-agent".into()),
            sub: true,
            role: Some("researcher".into()),
            task: Some("Inspect logs".into()),
            parent_key: Some(parent.clone()),
            server_session_id: Some("actual-sub-session".into()),
            status_since: Some(1000),
            model: Some("provider/model".into()),
            context_tokens: Some(20),
            context_window: Some(100),
            ..Default::default()
        };
        let rows = info_rows(&item, &[], false, None);
        assert_eq!(
            rows.iter()
                .filter(|row| row.section)
                .map(|row| row.label.as_str())
                .collect::<Vec<_>>(),
            [
                strings::INFO_STATUS_SECTION,
                strings::INFO_MODEL_SECTION,
                strings::INFO_CONTEXT_SECTION,
                strings::INFO_TECHNICAL_SECTION,
            ]
        );
        assert!(
            rows.iter()
                .filter(|row| !row.section)
                .all(|row| !row.value.trim().is_empty())
        );
        assert!(
            rows.iter()
                .any(|row| row.label == strings::MODEL_MENU && row.value == "provider/model")
        );
        assert!(rows.iter().any(|row| row.label == strings::INFO_SESSION
            && row.value == "actual-sub-session"
            && row.copyable));
        let link = rows
            .iter()
            .find(|row| row.label == strings::INFO_PARENT)
            .unwrap();
        assert_eq!(SessionKey::deserialize(&link.parent_key).unwrap(), parent);
        assert!(rows.iter().any(|row| row.elapsed && row.value == "1000"));
        assert!(!rows.iter().any(|row| row.label == strings::INFO_PROJECT));
        let missing = info_rows(
            &SessionItem {
                sub: true,
                ..Default::default()
            },
            &[],
            false,
            None,
        );
        assert!(!missing.iter().any(|row| row.label == strings::INFO_SESSION
            || row.label == strings::INFO_CONTEXT_SECTION
            || row.label == strings::INFO_MODEL_SECTION));
    }
    #[test]
    fn links_allow_only_web_origins() {
        for url in ["https://example.org/a?q=1", "http://localhost:123/a"] {
            assert!(valid_link(url));
        }
        for url in [
            "file:///etc/passwd",
            "javascript:alert(1)",
            "--help",
            "https:",
        ] {
            assert!(!valid_link(url));
        }
    }

    #[test]
    fn tapping_a_host_file_that_is_not_an_image_downloads_it() {
        for (url, path) in [
            ("/proj/sub/fonte.aseprite", "/proj/sub/fonte.aseprite"),
            ("file:///proj/notas.txt", "/proj/notas.txt"),
            ("/proj/Arte Final/a%28b%29.zip", "/proj/Arte Final/a(b).zip"),
        ] {
            assert_eq!(link_action(url), LinkAction::Download(path.into()), "{url}");
        }
        for url in ["https://example.org/a.zip", "http://localhost:4747/x"] {
            assert_eq!(link_action(url), LinkAction::Browser, "{url}");
        }
        // Imagem já aparece desenhada; relativo, // e esquemas de aplicativo não fazem nada.
        for url in [
            "/proj/arte.png",
            "/proj/anim.GIF",
            "sub/fonte.aseprite",
            "//host/x.bin",
            "javascript:alert(1)",
            "mailto:a@b.c",
            "",
        ] {
            assert_eq!(link_action(url), LinkAction::Nothing, "{url}");
        }
    }

    #[test]
    fn resuming_history_reopens_live_from_the_saved_anchor_and_cache() {
        let temporary = tempfile::tempdir().unwrap();
        let store = LocalStateStore::with_dirs(zaplaud_store::DesktopDirs {
            data: temporary.path().join("data"),
            cache: temporary.path().join("cache"),
        })
        .unwrap();
        store.add_server("http://localhost").unwrap();
        let item = SessionItem {
            key: SessionKey {
                origin: "http://localhost".into(),
                provider: "omp".into(),
                account: "test".into(),
                session_id: "session".into(),
            },
            agent_id: None,
            ..SessionItem::default()
        };
        let mut controller = ConversationController::default();
        let (generation, path) = controller.open(item.clone(), None).unwrap();
        assert_eq!(path, "/api/sessions/test/session/terminal");
        controller.apply(generation,StreamEvent::Data { event_type:"init".into(), data:r#"{"entries":[{"id":"first","kind":"assistant","text":"persisted","at":123}]}"#.into(),first_connection:true }).unwrap();
        let run = SessionRun {
            controller,
            epoch: store.conversation_epoch(&item.key.origin),
            store: store.clone(),
            view: "all".into(),
            enabled: false,
            expanded: HashSet::new(),
            reading: ReadingState {
                expanded: vec!["first".into()],
                following: false,
                entry: "first".into(),
                row: "first".into(),
                offset: -42.0,
            },
            summaries: BTreeMap::new(),
            following: true,
            new_count: 0,
            last_visible: None,
        };
        drop(run);
        let reading = store.reading_state(&item.key).unwrap();
        assert_eq!(reading.expanded, vec!["first"]);
        assert!(!reading.following);
        assert_eq!(reading.offset, -42.0);
        let cache = store.conversation(&item.key).unwrap().unwrap();
        assert_eq!(cache.entries[0].text, "persisted");
        let mut reopened = ConversationController::default();
        let (_, path) = reopened
            .open(
                SessionItem {
                    agent_id: Some("agent".into()),
                    ..item
                },
                Some(cache),
            )
            .unwrap();
        assert_eq!(path, "/api/agents/agent/terminal?after=first&at=123");
        assert_eq!(reopened.entries().len(), 1);
        assert_eq!(reopened.entries()[0].at, Some(123));
    }

    #[tokio::test]
    async fn switching_sessions_drops_previous_fake_stream_before_starting_next() {
        use std::sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        };
        struct Connection(Arc<AtomicUsize>);
        impl Drop for Connection {
            fn drop(&mut self) {
                self.0.fetch_sub(1, Ordering::SeqCst);
            }
        }
        let connections = Arc::new(AtomicUsize::new(0));
        let mut task = None;
        for _ in 0..20 {
            stop_session(&mut task).await;
            assert_eq!(connections.load(Ordering::SeqCst), 0);
            let (ready, receiver) = tokio::sync::oneshot::channel();
            let count = connections.clone();
            task = Some(tokio::spawn(async move {
                count.fetch_add(1, Ordering::SeqCst);
                let _connection = Connection(count);
                let _ = ready.send(());
                std::future::pending::<()>().await;
            }));
            receiver.await.unwrap();
            assert_eq!(connections.load(Ordering::SeqCst), 1);
        }
        stop_session(&mut task).await;
        assert_eq!(connections.load(Ordering::SeqCst), 0);
    }
}
