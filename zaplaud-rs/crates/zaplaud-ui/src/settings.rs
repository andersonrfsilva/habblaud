use crate::{AppEvents, AppWindow, ListState, SettingsRow, SettingsState, strings, update_ui};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{
    collections::{BTreeMap, HashSet},
    time::Duration,
};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::HabblaudApi,
    model::{AccountQuota, ModelRoles, THINKING_LEVELS},
    rules::{
        composer::{join_role, role_model_groups, snapshot_models, split_role},
        list::server_label,
        time::entry_time,
    },
};
use zaplaud_store::LocalStateStore;

#[derive(Default)]
struct Preferences {
    theme: String,
    view: String,
    deliver: String,
    summarize: bool,
    notifications: bool,
    permission: bool,
    done: bool,
}
impl Preferences {
    fn load(store: &LocalStateStore) -> Result<Self, zaplaud_store::StoreError> {
        let (permission, done) = store.notify_events()?;
        Ok(Self {
            theme: store.theme()?,
            view: store.default_view()?,
            deliver: store.deliver_as()?,
            summarize: store.summarize()?,
            notifications: store.notifications_enabled()?,
            permission,
            done,
        })
    }
    fn save(
        &mut self,
        store: &LocalStateStore,
        key: &str,
        value: &str,
        on: bool,
    ) -> Result<(), zaplaud_store::StoreError> {
        match key {
            "theme" => store.set_theme(value)?,
            "view" => store.set_default_view(value)?,
            "deliver" => store.set_deliver_as(value)?,
            "summarize" => store.set_summarize(on)?,
            "notifications" => store.set_notifications_enabled(on)?,
            "permission" => store.set_notify_events(on, self.done)?,
            "done" => store.set_notify_events(self.permission, on)?,
            _ => return Ok(()),
        }
        *self = Self::load(store)?;
        Ok(())
    }
}
enum Command {
    Open,
    Close,
    Active(bool),
    Origin(String),
    Activate(String, String, bool),
    Edit(String),
    CancelEdit,
    Model(String),
    Thinking(String),
    Loaded(u64, Result<Loaded, zaplaud_core::Error>),
    Saved(String, Result<(), zaplaud_core::Error>),
}
struct Loaded {
    roles: Result<ModelRoles, zaplaud_core::Error>,
    snapshot: Option<(Vec<String>, Vec<AccountQuota>)>,
}
#[derive(Default)]
struct State {
    visible: bool,
    active: bool,
    origin: String,
    servers: Vec<(HabblaudApi, bool)>,
    preferences: Preferences,
    roles: ModelRoles,
    models: Vec<String>,
    quotas: Option<Vec<AccountQuota>>,
    status: String,
    error: Option<&'static str>,
    editing: String,
    model: String,
    generation: u64,
    snapshot_at: Option<tokio::time::Instant>,
    saving: HashSet<String>,
}
fn row(kind: &str, key: &str, text: &str) -> SettingsRow {
    SettingsRow {
        kind: kind.into(),
        key: key.into(),
        text: text.into(),
        enabled: true,
        ..Default::default()
    }
}
fn role_label(role: &str) -> &'static str {
    match role {
        "task" => strings::ROLE_TASK,
        "plan" => strings::ROLE_PLAN,
        "tiny" => strings::ROLE_TINY,
        _ => strings::ROLE_ADVISOR,
    }
}
fn thinking_label(level: &str) -> String {
    strings::lookup(&format!("thinking_{level}"))
        .unwrap_or(strings::ROLE_DEFAULT)
        .into()
}
fn provider_label(provider: &str) -> &str {
    match provider {
        "anthropic" => "Anthropic",
        "openai-codex" => "OpenAI Codex",
        "google-antigravity" => "Google Antigravity",
        "openrouter" => "OpenRouter",
        _ => provider,
    }
}
fn typed_model_valid(value: &str) -> bool {
    let value = value.trim();
    let Some((provider, model)) = value.split_once('/') else {
        return false;
    };
    provider.starts_with(|c: char| c.is_ascii_alphanumeric())
        && provider
            .chars()
            .all(|c| c.is_alphanumeric() || matches!(c, '_' | '.' | '-'))
        && (1..=200).contains(&model.chars().count())
        && !model.chars().any(|c| c.is_whitespace() || c.is_control())
        && split_role(value).1.is_none()
}
fn unauthorized(error: &zaplaud_core::Error) -> bool {
    matches!(
        error,
        zaplaud_core::Error::Unauthorized
            | zaplaud_core::Error::SessionEnded
            | zaplaud_core::Error::Api { status: 401, .. }
    )
}
impl State {
    fn finish_save(&mut self, origin: &str) -> bool {
        self.saving.remove(origin);
        origin == self.origin
    }
    fn rows(&self) -> Vec<SettingsRow> {
        let mut rows = Vec::new();
        for (title, key, values, labels, current) in [
            (
                strings::SETTINGS_APPEARANCE,
                "theme",
                ["system", "light", "dark"],
                [
                    strings::THEME_SYSTEM,
                    strings::THEME_LIGHT,
                    strings::THEME_DARK,
                ],
                self.preferences.theme.as_str(),
            ),
            (
                strings::SETTINGS_VIEW_DEFAULT,
                "view",
                ["all", "prompts", "no_tools"],
                [
                    strings::VIEW_ALL,
                    strings::VIEW_PROMPTS,
                    strings::VIEW_NO_TOOLS,
                ],
                self.preferences.view.as_str(),
            ),
            (
                strings::SETTINGS_DELIVER_DEFAULT,
                "deliver",
                ["aside", "followUp", "steer"],
                [
                    strings::DELIVER_ASIDE,
                    strings::DELIVER_FOLLOW_UP,
                    strings::DELIVER_STEER,
                ],
                self.preferences.deliver.as_str(),
            ),
        ] {
            rows.push(row("section", "", title));
            for (value, label) in values.into_iter().zip(labels) {
                let mut r = row("radio", key, label);
                r.value = value.into();
                r.checked = current == value;
                rows.push(r);
            }
        }
        rows.push(row("hint", "", strings::SETTINGS_PER_SESSION));
        rows.push(row("section", "", strings::SETTINGS_SUMMARIES));
        let tiny = self
            .roles
            .roles
            .as_ref()
            .is_some_and(|r| r.contains_key("tiny"));
        let mut summarize = row("switch", "summarize", strings::SETTINGS_SUMMARIZE);
        summarize.checked = tiny && self.preferences.summarize;
        summarize.enabled = tiny;
        rows.push(summarize);
        rows.push(row(
            "hint",
            "",
            if tiny {
                strings::SETTINGS_SUMMARIZE_HINT
            } else {
                strings::SETTINGS_SUMMARIZE_NEEDS_TINY
            },
        ));
        rows.push(row("section", "", strings::SETTINGS_ROLES));
        match self.status.as_str() {
            "loading" => rows.push(row("hint", "", strings::QUOTA_LOADING)),
            "forbidden" => rows.push(row("hint", "", strings::CAST_FORBIDDEN)),
            "error" => rows.push(row("hint", "", strings::ROLES_LOAD_ERROR)),
            _ => {
                for role in ["task", "plan", "advisor", "tiny"] {
                    let mut r = row("role", role, role_label(role));
                    r.enabled =
                        self.roles.available && !self.roles.pending && self.status == "ready";
                    r.detail = self
                        .roles
                        .roles
                        .as_ref()
                        .and_then(|m| m.get(role))
                        .map(|v| {
                            let (model, level) = split_role(v);
                            let label = model.split_once('/').map_or_else(
                                || model.to_owned(),
                                |(provider, model)| {
                                    format!("{} · {model}", provider_label(provider))
                                },
                            );
                            level.map_or(label.clone(), |l| {
                                format!("{label} · {}", thinking_label(l))
                            })
                        })
                        .unwrap_or_else(|| strings::ROLE_DEFAULT.into())
                        .into();
                    rows.push(r);
                }
                if !self.roles.available {
                    rows.push(row("hint", "", strings::CAST_UNAVAILABLE));
                }
                if self.roles.pending {
                    rows.push(row("hint", "", strings::ROLES_SAVING));
                }
                rows.push(row("hint", "", strings::ROLES_HINT));
            }
        }
        rows.push(row("section", "", strings::NOTIFICATIONS));
        for (key, label, on, enabled) in [
            (
                "notifications",
                strings::SETTINGS_NOTIFICATIONS_OPEN,
                self.preferences.notifications,
                true,
            ),
            (
                "permission",
                strings::SETTINGS_NOTIFY_PERMISSION,
                self.preferences.permission,
                self.preferences.notifications,
            ),
            (
                "done",
                strings::SETTINGS_NOTIFY_DONE,
                self.preferences.done,
                self.preferences.notifications,
            ),
        ] {
            let mut r = row("switch", key, label);
            r.checked = on;
            r.enabled = enabled;
            rows.push(r);
        }
        rows.push(row("section", "", strings::SETTINGS_QUOTAS));
        match &self.quotas {
            None => rows.push(row(
                "hint",
                "",
                if self.error == Some(strings::QUOTA_ERROR) {
                    strings::QUOTA_ERROR
                } else {
                    strings::QUOTA_LOADING
                },
            )),
            Some(quotas) if quotas.is_empty() => rows.push(row("hint", "", strings::QUOTA_NONE)),
            Some(quotas) => {
                for account in quotas {
                    let card_start = rows.len();
                    let mut card = row(
                        "quota",
                        "",
                        &[Some(account.name.as_str()), account.plan.as_deref()]
                            .into_iter()
                            .flatten()
                            .collect::<Vec<_>>()
                            .join(" · "),
                    );
                    card.detail = if account.status == "stale" {
                        card.severity = 1;
                        strings::QUOTA_STALE
                    } else if account.status == "disabled" {
                        strings::QUOTA_DISABLED
                    } else {
                        ""
                    }
                    .into();
                    rows.push(card);
                    if account.no_quota {
                        let mut note = row("hint", "no-quota", strings::QUOTA_NO_QUOTA);
                        note.severity = 2;
                        rows.push(note);
                    }
                    for group in &account.groups {
                        if let Some(provider) = &group.provider {
                            rows.push(row(
                                "hint",
                                "",
                                &[Some(provider.as_str()), group.plan.as_deref()]
                                    .into_iter()
                                    .flatten()
                                    .collect::<Vec<_>>()
                                    .join(" · "),
                            ));
                        }
                        for limit in &group.limits {
                            let label = match limit.label.as_str() {
                                "fiveHour" => strings::QUOTA_FIVE_HOUR,
                                "sevenDay" => strings::QUOTA_SEVEN_DAY,
                                "sevenDayOpus" => strings::QUOTA_SEVEN_DAY_OPUS,
                                "sevenDaySonnet" => strings::QUOTA_SEVEN_DAY_SONNET,
                                _ => &limit.label,
                            };
                            let mut r = row("limit", "", label);
                            r.detail = limit
                                .utilization
                                .map_or_else(|| "—".into(), |v| format!("{}%", v.round() as i64))
                                .into();
                            if let Some(used) = limit.utilization {
                                r.value = "progress".into();
                                r.fraction = (used / 100.0).clamp(0.0, 1.0) as f32;
                                r.severity = if limit.exhausted || used >= 90.0 {
                                    2
                                } else if used >= 70.0 {
                                    1
                                } else {
                                    0
                                };
                            }
                            rows.push(r);
                            if let Some(at) = limit.resets_at {
                                rows.push(row(
                                    "hint",
                                    "",
                                    &crate::formatted(
                                        "quota_resets",
                                        &[&entry_time(at, crate::state::now_millis())
                                            .unwrap_or_default()],
                                    ),
                                ));
                            }
                        }
                        if let Some(note) = &group.note {
                            rows.push(row("hint", "", note));
                        }
                    }
                    if let Some(at) = account.fetched_at {
                        rows.push(row(
                            "hint",
                            "",
                            &crate::formatted(
                                "quota_fetched",
                                &[&entry_time(at, crate::state::now_millis()).unwrap_or_default()],
                            ),
                        ));
                    }
                    for row in &mut rows[card_start..] {
                        row.card = true;
                    }
                    let mut end = row("quota-end", "", "");
                    end.card = true;
                    rows.push(end);
                }
            }
        }
        if let Some(error) = self
            .error
            .filter(|error| *error != strings::QUOTA_ERROR || self.quotas.is_some())
        {
            let mut r = row("hint", "", error);
            r.severity = 2;
            rows.push(r);
        }
        rows.push(row("section", "", strings::SETTINGS_SERVER));
        for (api, login) in &self.servers {
            let mut r = row("server", api.origin(), &server_label(api.origin()));
            r.detail = strings::LOGOUT.into();
            rows.push(r);
            if *login {
                let mut r = row("button", "relogin", strings::RELOGIN);
                r.value = api.origin().into();
                rows.push(r);
            }
        }
        rows.push(row("button", "add", strings::ADD_SERVER));
        rows
    }
    fn publish(&self, weak: &slint::Weak<AppWindow>) {
        let rows = self.rows();
        let servers = self
            .servers
            .iter()
            .map(|(api, _)| row("server", api.origin(), &server_label(api.origin())))
            .collect::<Vec<_>>();
        let origin = self.origin.clone();
        let editing = self.editing.clone();
        let title = role_label(&editing);
        let choosing = self.model.is_empty();
        let mut choices = Vec::new();
        let current = self
            .roles
            .roles
            .as_ref()
            .and_then(|m| m.get(&editing))
            .map(String::as_str)
            .unwrap_or("");
        let (current_model, current_level) = split_role(current);
        if choosing {
            for (provider, models) in role_model_groups(&self.models, Some(current_model)) {
                choices.push(row("section", "", provider_label(&provider)));
                for model in models {
                    let mut r = row(
                        "radio",
                        "",
                        model.split_once('/').map_or(model.as_str(), |(_, m)| m),
                    );
                    r.checked = model == current_model;
                    r.value = model.into();
                    choices.push(r);
                }
            }
            if self.models.is_empty() {
                choices.push(row("hint", "", strings::ROLE_NO_MODELS));
            }
        } else {
            for level in std::iter::once("").chain(THINKING_LEVELS) {
                let mut r = row("radio", "", &thinking_label(level));
                r.value = level.into();
                r.checked = self.model == current_model && current_level.unwrap_or("") == level;
                choices.push(r);
            }
        }
        let visible = self.visible;
        update_ui(weak, move |window| {
            let ui = window.global::<SettingsState>();
            ui.set_visible(visible);
            ui.set_rows(ModelRc::new(VecModel::from(rows)));
            ui.set_servers(ModelRc::new(VecModel::from(servers)));
            ui.set_origin(origin.into());
            ui.set_editing_role(editing.into());
            ui.set_role_title(title.into());
            ui.set_role_prompt(
                if choosing {
                    strings::ROLE_PICK_MODEL
                } else {
                    strings::ROLE_PICK_THINKING
                }
                .into(),
            );
            ui.set_choosing_model(choosing);
            ui.set_choices(ModelRc::new(VecModel::from(choices)));
        });
    }
}
fn refresh(
    state: &mut State,
    runtime: &tokio::runtime::Handle,
    send: &mpsc::UnboundedSender<Command>,
) -> Option<tokio::task::JoinHandle<()>> {
    let api = state
        .servers
        .iter()
        .find(|(a, _)| a.origin() == state.origin)?
        .0
        .clone();
    let generation = state.generation;
    let send = send.clone();
    let snapshot_due = state.quotas.is_none()
        || state
            .snapshot_at
            .is_none_or(|at| at.elapsed() >= Duration::from_secs(60));
    Some(runtime.spawn(async move {
        let result = async {
            let snapshot = if snapshot_due {
                let data = api.request("/api/snapshot", None).await?;
                let value: serde_json::Value = serde_json::from_str(&data)?;
                let models = snapshot_models(&data)?;
                let quotas = value
                    .get("accounts")
                    .and_then(serde_json::Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(AccountQuota::parse)
                    .collect();
                Some((models, quotas))
            } else {
                None
            };
            let roles = api.roles().await;
            Ok(Loaded { roles, snapshot })
        }
        .await;
        let _ = send.send(Command::Loaded(generation, result));
    }))
}
pub fn setup(window: &AppWindow, context: &crate::feature::FeatureContext) {
    let ui = window.global::<SettingsState>();
    ui.set_title(strings::SETTINGS.into());
    ui.set_back(strings::BACK.into());
    ui.set_cancel(strings::CANCEL.into());
    ui.set_use_label(strings::ROLE_USE.into());
    ui.set_model_placeholder(strings::ROLE_TYPE_MODEL.into());
    let (send, mut receive) = mpsc::unbounded_channel();
    let s = send.clone();
    window.global::<AppEvents>().on_settings(move || {
        let _ = s.send(Command::Open);
    });
    let s = send.clone();
    ui.on_close(move || {
        let _ = s.send(Command::Close);
    });
    let s = send.clone();
    ui.on_choose(move |origin| {
        let _ = s.send(Command::Origin(origin.into()));
    });
    let s = send.clone();
    let weak = window.as_weak();
    ui.on_activate(move |key, value, on| {
        if let Some(w) = weak.upgrade() {
            match key.as_str() {
                "logout" => w.global::<AppEvents>().invoke_logout(value),
                "add" => {
                    w.global::<SettingsState>().invoke_close();
                    w.global::<ListState>().invoke_add_server();
                }
                "relogin" => {
                    w.global::<SettingsState>().invoke_close();
                    w.global::<ListState>().invoke_relogin(value);
                }
                _ => {
                    let _ = s.send(Command::Activate(key.into(), value.into(), on));
                }
            }
        }
    });
    let s = send.clone();
    ui.on_edit_role(move |role| {
        let _ = s.send(Command::Edit(role.into()));
    });
    let s = send.clone();
    ui.on_cancel_edit(move || {
        let _ = s.send(Command::CancelEdit);
    });
    let s = send.clone();
    ui.on_model(move |model| {
        let _ = s.send(Command::Model(model.into()));
    });
    let s = send.clone();
    ui.on_thinking(move |level| {
        let _ = s.send(Command::Thinking(level.into()));
    });
    let s = send.clone();
    ui.on_active_changed(move |active| {
        let _ = s.send(Command::Active(active));
    });
    let weak = window.as_weak();
    ui.on_typed_changed(move |value| {
        if let Some(w) = weak.upgrade() {
            w.global::<SettingsState>()
                .set_typed_valid(typed_model_valid(&value));
        }
    });
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let runtime = context.runtime.clone();
    let commands = context.commands.clone();
    let active = window.global::<ListState>().get_app_visible();
    context.runtime.spawn(async move {
        let store = match LocalStateStore::new() {
            Ok(store) => store,
            Err(_) => {
                update_ui(&weak, |w| w.global::<crate::ShellState>().set_message(strings::STORAGE_ERROR.into()));
                return;
            }
        };
        let mut state = State { active, ..Default::default() };
        match Preferences::load(&store) {
            Ok(preferences) => state.preferences = preferences,
            Err(_) => state.error = Some(strings::STORAGE_ERROR),
        }
        let initial_theme = std::env::var("ZAPLAUD_THEME").unwrap_or_else(|_| state.preferences.theme.clone());
        update_ui(&weak, move |w| {
            let settings = w.global::<SettingsState>();
            settings.set_theme_mode(initial_theme.into());
            w.global::<crate::Theme>().set_dark(settings.get_resolved_dark());
        });
        let mut request: Option<tokio::task::JoinHandle<()>> = None;
        let mut deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            let was_visible = state.visible;
            let mut reload = false;
            tokio::select! {
                snapshot = snapshots.recv() => {
                    match snapshot {
                        Ok(snapshot) => {
                            if state.servers.len() == snapshot.servers.len() && state.servers.iter().zip(&snapshot.servers).all(|((api, login), server)| api.origin() == server.api.origin() && *login == server.needs_login) { continue; }
                            state.servers = snapshot.servers.iter().map(|s| (s.api.clone(), s.needs_login)).collect();
                            if !state.servers.iter().any(|(api, _)| api.origin() == state.origin) {
                                state.origin = state.servers.first().map_or_else(String::new, |(api, _)| api.origin().to_owned());
                                state.generation += 1;
                                state.roles = ModelRoles::default();
                                state.quotas = None;
                                state.status = "loading".into();
                                reload = state.visible;
                            }
                            if state.servers.is_empty() { state.visible = false; }
                        }
                        Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                        Err(_) => break,
                    }
                }
                command = receive.recv() => {
                    match command {
                        Some(Command::Open) => {
                            state.visible = true;
                            state.status = "loading".into();
                            state.generation += 1;
                            state.editing.clear();
                            state.roles = ModelRoles::default();
                            state.quotas = None;
                            state.models.clear();
                            state.error = None;
                            match Preferences::load(&store) {
                                Ok(preferences) => state.preferences = preferences,
                                Err(_) => state.error = Some(strings::STORAGE_ERROR),
                            }
                            reload = true;
                        }
                        Some(Command::Close) => {
                            state.visible = false;
                            state.editing.clear();
                            state.generation += 1;
                        }
                        Some(Command::Active(active)) => { state.active = active; reload = active && state.visible; }
                        Some(Command::Origin(origin)) => {
                            state.origin = origin;
                            state.generation += 1;
                            state.roles = ModelRoles::default();
                            state.quotas = None;
                            state.status = "loading".into();
                            state.editing.clear();
                            reload = true;
                        }
                        Some(Command::Activate(key, value, on)) => {
                            if state.preferences.save(&store, &key, &value, on).is_err() {
                                state.error = Some(strings::STORAGE_ERROR);
                            } else {
                                update_ui(&weak, move |w| {
                                    let settings = w.global::<SettingsState>();
                                    if key == "theme" { settings.set_theme_mode(value.into()); }
                                    settings.invoke_preferences_changed();
                                    if matches!(key.as_str(), "notifications" | "permission" | "done") { settings.invoke_notification_preferences_changed(); }
                                });
                            }
                        }
                        Some(Command::Edit(role)) => {
                            if state.roles.available && !state.roles.pending && !state.saving.contains(&state.origin) && state.status == "ready" {
                                state.editing = role;
                                state.model.clear();
                                update_ui(&weak, |w| {
                                    w.global::<SettingsState>().set_typed("".into());
                                    w.global::<SettingsState>().set_typed_valid(false);
                                });
                            }
                        }
                        Some(Command::CancelEdit) => state.editing.clear(),
                        Some(Command::Model(model)) => {
                            if typed_model_valid(&model) { state.model = model.trim().into(); }
                        }
                        Some(Command::Thinking(level)) => {
                            if !state.model.is_empty() && (level.is_empty() || THINKING_LEVELS.contains(&level.as_str()))
                                && let Some((api, _)) = state.servers.iter().find(|(a, _)| a.origin() == state.origin)
                            {
                                let api = api.clone();
                                let roles = BTreeMap::from([(state.editing.clone(), join_role(&state.model, (!level.is_empty()).then_some(level.as_str())))]);
                                let send = send.clone();
                                let origin = state.origin.clone();
                                state.saving.insert(origin.clone());
                                state.roles.pending = true;
                                state.editing.clear();
                                runtime.spawn(async move {
                                    let result = api.set_roles(&roles).await;
                                    let _ = send.send(Command::Saved(origin, result));
                                });
                            }
                        }
                        Some(Command::Saved(origin, result)) => {
                            let current = state.finish_save(&origin);
                            match result {
                                Ok(()) => reload = current,
                                Err(error) => {
                                    if unauthorized(&error) { let _ = commands.send(crate::state::Event::Unauthorized(origin)); }
                                    if current {
                                        state.roles.pending = false;
                                        state.error = Some(strings::ROLES_ERROR);
                                    }
                                }
                            }
                        }
                        Some(Command::Loaded(generation, result)) if generation == state.generation => {
                            match result {
                                Ok(loaded) => {
                                    if let Some((models, quotas)) = loaded.snapshot {
                                        state.models = models;
                                        state.quotas = Some(quotas);
                                        state.snapshot_at = Some(tokio::time::Instant::now());
                                    }
                                    match loaded.roles {
                                        Ok(mut roles) => {
                                            roles.pending |= state.saving.contains(&state.origin);
                                            state.roles = roles; state.status = "ready".into(); state.error = None;
                                        }
                                        Err(error) => {
                                            if unauthorized(&error) { let _ = commands.send(crate::state::Event::Unauthorized(state.origin.clone())); state.status = "unauthorized".into(); }
                                            else if matches!(error, zaplaud_core::Error::Api { status: 403, .. }) { state.status = "forbidden".into(); }
                                            else if state.roles.roles.is_none() { state.status = "error".into(); }
                                        }
                                    }
                                }
                                Err(error) => {
                                    state.error = Some(strings::QUOTA_ERROR);
                                    if unauthorized(&error) { let _ = commands.send(crate::state::Event::Unauthorized(state.origin.clone())); state.status = "unauthorized".into(); }
                                    else if state.roles.roles.is_none() { state.status = "error".into(); }
                                }
                            }
                            deadline = tokio::time::Instant::now() + Duration::from_millis(if state.roles.pending { 1500 } else { 10000 });
                        }
                        Some(_) => {}
                        None => break,
                    }
                }
                _ = tokio::time::sleep_until(deadline), if state.visible && state.active && state.status != "unauthorized" && request.as_ref().is_none_or(|task| task.is_finished()) => {
                    reload = true;
                    deadline = tokio::time::Instant::now() + Duration::from_secs(10);
                }
            }
            if !state.visible || !state.active || reload {
                if let Some(task) = request.take() { task.abort(); }
                if state.visible && state.active { request = refresh(&mut state, &runtime, &send); }
            }
            if state.visible || was_visible { state.publish(&weak); }
        }
        if let Some(task) = request { task.abort(); }
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn role_save_survives_visibility_and_generation_changes() {
        let mut state = State {
            origin: "https://one.test".into(),
            visible: true,
            active: true,
            ..Default::default()
        };
        state.saving.insert(state.origin.clone());
        state.visible = false;
        state.active = false;
        state.generation += 1;
        state.generation += 1;
        state.visible = true;
        assert!(state.saving.contains(&state.origin));
        let origin = state.origin.clone();
        assert!(state.finish_save(&origin));
        assert!(!state.saving.contains(&origin));
    }

    #[test]
    fn old_origin_save_does_not_refresh_or_clear_other_origin_save() {
        let mut state = State {
            origin: "https://two.test".into(),
            ..Default::default()
        };
        state
            .saving
            .extend(["https://one.test".into(), state.origin.clone()]);
        assert!(!state.finish_save("https://one.test"));
        assert!(state.saving.contains(&state.origin));
    }
    #[test]
    fn manual_model_requires_provider_and_no_reasoning_suffix() {
        for invalid in [
            "",
            "model",
            "/model",
            "provider/",
            "p/model:high",
            "p/model name",
            "-provider/model",
        ] {
            assert!(!typed_model_valid(invalid), "{invalid}");
        }
        for valid in [
            "anthropic/claude-opus-4",
            " p/gpt-5 ",
            "p/model:batch",
            "p/a/b",
        ] {
            assert!(typed_model_valid(valid), "{valid}");
        }
    }
    #[test]
    fn preferences_survive_reload_without_changing_other_event() {
        let dir = tempfile::tempdir().unwrap();
        let store = LocalStateStore::with_dirs(zaplaud_store::DesktopDirs {
            data: dir.path().join("data"),
            cache: dir.path().join("cache"),
        })
        .unwrap();
        let mut p = Preferences::load(&store).unwrap();
        p.save(&store, "summarize", "", true).unwrap();
        p.save(&store, "permission", "", false).unwrap();
        p.save(&store, "theme", "dark", false).unwrap();
        let p = Preferences::load(&store).unwrap();
        assert!(p.summarize);
        assert!(!p.permission);
        assert!(p.done);
        assert_eq!(p.theme, "dark");
    }
    #[test]
    fn summary_requires_tiny_and_notification_events_require_enabled() {
        let mut state = State {
            preferences: Preferences {
                summarize: true,
                permission: true,
                done: true,
                ..Default::default()
            },
            ..Default::default()
        };
        let summary = state
            .rows()
            .into_iter()
            .find(|r| r.key == "summarize")
            .unwrap();
        assert!(!summary.enabled);
        assert!(!summary.checked);
        state.roles.roles = Some(BTreeMap::from([("tiny".into(), "p/model".into())]));
        let rows = state.rows();
        let summary = rows.iter().find(|r| r.key == "summarize").unwrap();
        assert!(summary.enabled && summary.checked);
        for key in ["permission", "done"] {
            let event = rows.iter().find(|r| r.key == key).unwrap();
            assert!(event.checked);
            assert!(!event.enabled);
        }
        state.preferences.notifications = true;
        assert!(
            state
                .rows()
                .into_iter()
                .filter(|r| r.key == "permission" || r.key == "done")
                .all(|r| r.enabled)
        );
    }
    #[test]
    fn quota_error_replaces_initial_loading_and_limits_keep_the_card() {
        let mut state = State {
            error: Some(strings::QUOTA_ERROR),
            ..Default::default()
        };
        let rows = state.rows();
        assert!(!rows.iter().any(|r| r.text == strings::QUOTA_LOADING));
        assert_eq!(
            rows.iter()
                .filter(|r| r.text == strings::QUOTA_ERROR)
                .count(),
            1
        );
        state.error = None;
        state.quotas = Some(vec![
            AccountQuota::parse(&serde_json::json!({
                "id": "test", "usage": {"fiveHour": {"utilization": 90.5}}
            }))
            .unwrap(),
        ]);
        let rows = state.rows();
        let limit = rows.iter().find(|r| r.kind == "limit").unwrap();
        assert!(limit.card);
        assert_eq!(limit.detail, "91%");
        assert_eq!(limit.severity, 2);
        assert!((limit.fraction - 0.905).abs() < 0.0001);
        assert!(rows.iter().any(|r| r.kind == "quota-end" && r.card));
    }
    #[test]
    fn quota_status_is_separate_from_the_no_quota_note() {
        let mut account = AccountQuota::parse(&serde_json::json!({"id": "test"})).unwrap();
        account.status = "disabled".into();
        account.no_quota = true;
        let state = State {
            quotas: Some(vec![account]),
            ..Default::default()
        };
        let rows = state.rows();
        let header = rows.iter().find(|row| row.kind == "quota").unwrap();
        assert_eq!(header.detail, strings::QUOTA_DISABLED);
        assert_eq!(header.severity, 0);
        let note = rows.iter().find(|row| row.key == "no-quota").unwrap();
        assert_eq!(note.text, strings::QUOTA_NO_QUOTA);
        assert_eq!(note.severity, 2);
        assert!(note.card);
    }
}
