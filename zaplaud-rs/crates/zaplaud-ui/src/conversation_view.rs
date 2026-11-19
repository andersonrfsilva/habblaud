use crate::{
    AppWindow, ConversationState, ConversationViewState, InfoRow,
    feature::FeatureContext,
    state::{Event, now_millis},
    strings, update_ui,
};
use slint::{ComponentHandle, Model, ModelExt, ModelRc, VecModel};
use std::{
    cell::RefCell,
    collections::{BTreeMap, HashSet},
    rc::Rc,
    time::{Duration, SystemTime},
};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::{HabblaudApi, retry_after},
    controller::conversation::{ConversationController, snapshot_matches_session},
    model::{SessionItem, StreamEvent},
    rules::conversation::{self, SummaryEffect, SummaryEvent, SummaryState},
    rules::time,
};
use zaplaud_store::{LocalStateStore, ReadingState};

use zaplaud_ui::conversation_rows::{RenderRow, build_rows};
enum Command {
    View(usize),
    Toggle(String),
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
            Self::Position(generation, _) | Self::Anchor(generation, ..) => *generation == active,
            _ => true,
        }
    }
}
thread_local! { static ROWS: RefCell<Option<Rc<VecModel<RenderRow>>>> = const { RefCell::new(None) }; }

fn set_rows(window: &AppWindow, rows: Vec<RenderRow>, reset: bool) {
    ROWS.with(|slot| {
        let mut slot = slot.borrow_mut();
        if reset || slot.is_none() {
            let model = Rc::new(VecModel::from(rows));
            window
                .global::<ConversationViewState>()
                .set_rows(ModelRc::new(model.clone().map(RenderRow::into_ui)));
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
                state.set_following(reading.following);
                state.set_capture_pending(false);
                state.set_scan_index(-1);
                state.set_anchor_index(-1);
                state.set_anchor_entry(reading.entry.into());
                state.set_anchor_row(reading.row.into());
                state.set_anchor_offset(reading.offset);
                if reading.following {
                    state.set_end_request(state.get_end_request().wrapping_add(1));
                }
            }
            let anchor = state.get_anchor_row();
            let entry = state.get_anchor_entry();
            let index = zaplaud_ui::conversation_rows::anchor_index(&rows, &entry, &anchor);
            set_rows(&window, rows, reading_generation.is_some());
            state.set_anchor_index(index.map_or(-1, |index| index as i32));
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
fn info_rows(
    item: &SessionItem,
    entries: &[zaplaud_core::model::TerminalEntry],
    partial: bool,
) -> Vec<InfoRow> {
    let mut rows = Vec::new();
    let mut section = |label: &str, mut values: Vec<InfoRow>| {
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
        strings::INFO_CONTEXT_SECTION,
        vec![info_row(strings::INFO_CONTEXT, context)],
    );
    let technical = |label, value| InfoRow {
        copyable: true,
        ..info_row(label, value)
    };
    section(
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
    state.set_info(ModelRc::new(VecModel::from(info_rows(
        item, entries, partial,
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
    let total_models: usize = all_groups.iter().map(|(_, list)| list.len()).sum();
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
            value: slint::SharedString::default(),
            label: zaplaud_core::rules::composer::provider_label(&provider).into(),
            header: true,
        });
        choices.extend(models.into_iter().map(|value| crate::ModelChoice {
            label: zaplaud_core::rules::composer::model_option_label(&value).into(),
            value: value.into(),
            header: false,
        }));
    }
    state.set_model_filter_visible(total_models > 15);
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

#[cfg(test)]
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
                        save_at = Some(tokio::time::Instant::now()+Duration::from_secs(1));
                        if following && run.new_count != 0 { run.new_count = 0; run.render(&weak,None); }
                    }
                    Some(Command::Anchor(_,entry,row,offset,following)) => {
                        run.reading.entry = entry;
                        run.reading.row = row;
                        run.reading.offset = offset;
                        run.reading.following = following;
                        run.following = following;
                        save_at = Some(tokio::time::Instant::now()+Duration::from_secs(1));
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
                if result == Ok(true) { save_at = Some(tokio::time::Instant::now()+Duration::from_secs(3)); }
            }
            Some(result) = summary_jobs.join_next(), if !summary_jobs.is_empty() => {
                if let Ok((key,result)) = result
                    && let Some(state) = run.summaries.remove(&key) {
                        let event = match result {
                            Ok(text) => { run.controller.summaries.insert(key.clone(),text.clone()); save_at = Some(tokio::time::Instant::now()+Duration::from_secs(3)); SummaryEvent::Completed { key:key.clone(),text } },
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

async fn stop_session(task: &mut Option<tokio::task::JoinHandle<()>>) {
    if let Some(task) = task.take() {
        task.abort();
        let _ = task.await;
    }
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let state = window.global::<ConversationViewState>();
    state.set_go_to_end(strings::GO_TO_END.into());
    state.set_view_label(strings::VIEW_MENU.into());
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
    state.on_open_link(move |url| {
        if valid_link(&url) {
            let _ = open_system(std::ffi::OsStr::new(url.as_str()));
        }
    });
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let context = context.clone();
    context.runtime.clone().spawn(async move {
        let mut selected = None;
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
                    if selected == key {
                        if let Some(item) = &snapshot.selected {
                            if let Some(sender) = &current { let _ = sender.send(Command::Metadata(Box::new(item.clone()))); }
                            else { let item = item.clone(); update_ui(&weak, move |w| publish_info(&w, &item, &[], true)); }
                        }
                        continue;
                    }
                    stop_session(&mut task).await;
                    selected = key;
                    current = None;
                    reading_generation = reading_generation.checked_add(1).expect("reading generation exhausted");
                    update_ui(&weak, |w| {
                        let state = w.global::<ConversationViewState>();
                        state.set_reset_request(state.get_reset_request().wrapping_add(1));
                        state.set_info_open(false);
                        state.set_menu_open(false);
                        state.set_model_query("".into());
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
        let rows = info_rows(&item, &[], false);
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
    fn closing_persists_cache_and_reopening_resumes_from_last_dated_entry() {
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
            agent_id: Some("agent".into()),
            ..SessionItem::default()
        };
        let mut controller = ConversationController::default();
        let (generation, _) = controller.open(item.clone(), None).unwrap();
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
        let (_, path) = reopened.open(item.clone(), Some(cache)).unwrap();
        assert!(path.ends_with("?after=first&at=123"));
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
