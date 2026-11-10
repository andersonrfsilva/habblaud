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
use zaplaud_store::LocalStateStore;

pub(crate) use zaplaud_ui::conversation_rows::markdown;
use zaplaud_ui::conversation_rows::{RenderRow, build_rows};
enum Command {
    View(usize),
    Toggle(String),
    Observe(String),
    Position(bool),
    Metadata(Box<SessionItem>),
    Preferences,
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
    fn render(&mut self, weak: &slint::Weak<AppWindow>, reset: bool) {
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
        update_ui(weak, move |window| {
            set_rows(&window, rows, reset);
            let state = window.global::<ConversationViewState>();
            state.set_notice(notice.into());
            state.set_reduced(reduced.into());
            state.set_truncated(truncated.into());
            state.set_view(view.into());
            state.set_new_count(count);
            if reset {
                state.set_following(true);
                state.set_end_request(state.get_end_request().wrapping_add(1));
            }
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
    }
}
fn publish_info(
    window: &AppWindow,
    item: &SessionItem,
    entries: &[zaplaud_core::model::TerminalEntry],
    partial: bool,
) {
    let state = window.global::<ConversationViewState>();
    let mut rows = Vec::new();
    if let Some(model) = &item.model {
        rows.push(info_row(
            strings::MODEL_MENU,
            model
                .split_once('/')
                .map_or(model.as_str(), |(_, model)| model)
                .into(),
        ));
    }
    if let Some(thinking) = &item.thinking {
        rows.push(info_row(
            strings::THINKING_MENU,
            crate::formatted(&format!("thinking_{thinking}"), &[]),
        ));
    }
    let fraction = item
        .context_tokens
        .zip(item.context_window)
        .map(|(tokens, window)| (tokens as f32 / window as f32).clamp(0., 1.));
    if let (Some(fraction), Some(tokens), Some(size)) =
        (fraction, item.context_tokens, item.context_window)
    {
        rows.push(info_row(
            strings::INFO_CONTEXT,
            format!(
                "{}% · {} mil de {} mil",
                (fraction * 100.) as i32,
                tokens / 1000,
                size / 1000
            ),
        ));
    }
    state.set_context_fraction(fraction.unwrap_or(-1.));
    if let Some(at) = item.last_at {
        let relative = time::relative_time(at, now_millis());
        let amount = relative.amount.map(|amount| amount.to_string());
        rows.push(info_row(
            strings::INFO_LAST_ACTIVITY,
            crate::formatted(
                relative.key,
                &amount.as_deref().into_iter().collect::<Vec<_>>(),
            ),
        ));
    }
    let prompts = entries
        .iter()
        .filter(|e| e.kind == "user")
        .count()
        .to_string();
    let replies = entries
        .iter()
        .filter(|e| e.kind == "assistant")
        .count()
        .to_string();
    let mut loaded = crate::formatted("info_loaded_value", &[&prompts, &replies]);
    if partial {
        loaded.push_str(&format!(" · {}", strings::INFO_PARTIAL));
    }
    rows.push(info_row(strings::INFO_LOADED, loaded));
    state.set_info(ModelRc::new(VecModel::from(rows)));
    state.set_details(ModelRc::new(VecModel::from(vec![
        info_row(
            strings::INFO_PROJECT,
            item.project.clone().unwrap_or_default(),
        ),
        info_row(
            strings::INFO_PROVIDER,
            format!("{} · {}", item.key.provider, item.key.account),
        ),
        info_row(strings::INFO_SESSION, item.key.session_id.clone()),
        info_row(strings::INFO_SERVER, item.key.origin.clone()),
    ])));
    state.set_info_status(
        if item.agent_id.is_none() {
            strings::INFO_CLOSED
        } else {
            match item.status.as_deref() {
                Some("working" | "busy" | "running") => strings::STATUS_WORKING,
                Some("idle") => strings::STATUS_IDLE,
                Some("waiting") => strings::STATUS_WAITING,
                _ => strings::INFO_OPEN,
            }
        }
        .into(),
    );
    let choices =
        zaplaud_core::rules::composer::role_model_groups(&item.models, item.model.as_deref())
            .into_iter()
            .flat_map(|(provider, models)| {
                let mut choices = vec![crate::ModelChoice {
                    value: String::new().into(),
                    label: provider.into(),
                    header: true,
                }];
                choices.extend(models.into_iter().map(|value| {
                    crate::ModelChoice {
                        label: value
                            .split_once('/')
                            .map_or(value.as_str(), |(_, name)| name)
                            .into(),
                        value: value.into(),
                        header: false,
                    }
                }));
                choices
            })
            .collect::<Vec<_>>();
    state.set_models(ModelRc::new(VecModel::from(choices)));
    state.set_model(item.model.clone().unwrap_or_default().into());
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

async fn run_session(
    item: SessionItem,
    api: HabblaudApi,
    weak: slint::Weak<AppWindow>,
    commands: mpsc::UnboundedSender<Event>,
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
    let mut run = SessionRun {
        controller: ConversationController::default(),
        store,
        epoch,
        view,
        enabled,
        expanded: HashSet::new(),
        summaries: BTreeMap::new(),
        following: true,
        new_count: 0,
        last_visible: None,
    };
    let (generation, path) = match run.controller.open(item.clone(), cache) {
        Ok(open) => open,
        Err(_) => return,
    };
    run.render(&weak, true);
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
                        run.render(&weak,false);
                    },
                    Some(Command::Toggle(id)) => { if !run.expanded.remove(&id) { run.expanded.insert(id); } run.render(&weak,false); }
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
                                run.render(&weak,false);
                            }
                        }
                    }
                    Some(Command::Position(following)) => { run.following = following; if following && run.new_count != 0 { run.new_count = 0; run.render(&weak,false); } }
                    Some(Command::Preferences) => {
                        run.enabled = run.store.summarize().unwrap_or(false);
                        run.view = run.store.session_view(&item.key).unwrap_or_else(|_| "all".into());
                        run.last_visible = None;
                        run.render(&weak,false);
                    }
                    Some(Command::Metadata(item)) => {
                        let changed = run.controller.selected.as_ref() != Some(item.as_ref());
                        run.controller.selected = Some(*item);
                        let enabled = run.store.summarize().unwrap_or(false);
                        if run.enabled != enabled { run.enabled = enabled; run.render(&weak,true); }
                        else if changed {
                            let item = run.controller.selected.clone();
                            let entries = run.controller.entries();
                            let partial = run.controller.conversation.truncated;
                            update_ui(&weak,move |window| { if let Some(item) = item { publish_info(&window,&item,&entries,partial); } });
                        }
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
                run.render(&weak,false);
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
                        run.render(&weak,false);
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
            state.invoke_position(state.get_following());
        }
    });
    state.set_back(strings::BACK.into());
    state.set_view_labels(ModelRc::new(VecModel::from(vec![
        strings::VIEW_ALL.into(),
        strings::VIEW_PROMPTS.into(),
        strings::VIEW_NO_TOOLS.into(),
    ])));
    state.set_info_label(strings::SESSION_INFO.into());
    state.set_details_label(strings::INFO_SHOW_DETAILS.into());
    state.set_hide_details_label(strings::INFO_HIDE_DETAILS.into());
    state.set_deliver_label(strings::DELIVER_MODE.into());
    state.set_model_label(strings::MODEL_MENU.into());
    state.set_thinking_label(strings::THINKING_MENU.into());
    state.set_shake_label(strings::SHAKE.into());
    state.set_aside_label(strings::ASIDE_MENU.into());
    state.set_thinking_labels(ModelRc::new(VecModel::from(
        zaplaud_core::model::THINKING_LEVELS
            .iter()
            .map(|level| crate::formatted(&format!("thinking_{level}"), &[]).into())
            .collect::<Vec<_>>(),
    )));
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
    let weak = window.as_weak();
    let commands = context.commands.clone();
    state.on_position(move |following| {
        let _ = sender.send(Command::Position(following));
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
                    if selected == key { if let (Some(sender),Some(item)) = (&current,&snapshot.selected) { let _ = sender.send(Command::Metadata(Box::new(item.clone()))); } continue; }
                    stop_session(&mut task).await;
                    selected = key;
                    current = None;
                    update_ui(&weak,|w| { let state = w.global::<ConversationViewState>(); state.set_info_open(false);state.set_menu_open(false);state.set_details_open(false); set_rows(&w,Vec::new(),true); });
                    if let Some(item) = &snapshot.selected && let Some(server) = snapshot.servers.iter().find(|s| s.api.origin() == item.key.origin) {
                        if !server.reading.terminal_allowed || server.needs_login { update_ui(&weak,|w| w.global::<ConversationViewState>().set_notice(strings::TERMINAL_BLOCKED.into()));continue; }
                        let (sender,receiver) = mpsc::unbounded_channel(); current = Some(sender);
                        task = Some(context.runtime.spawn(run_session(item.clone(),server.api.clone(),weak.clone(),context.commands.clone(),receiver)));
                    }
                }
                command = receiver.recv() => { match command { Some(command) => if let Some(sender) = &current { let _ = sender.send(command); },None => break } }
            }
        }
        stop_session(&mut task).await;
    });
}

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
            summaries: BTreeMap::new(),
            following: true,
            new_count: 0,
            last_visible: None,
        };
        drop(run);
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
