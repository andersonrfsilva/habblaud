use crate::{AppEvents, AppWindow, ListState, SettingsRow, SettingsState, strings, update_ui};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{
    collections::{BTreeMap, HashSet},
    time::Duration,
};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::HabblaudApi,
    model::{
        AccountQuota, EDITABLE_ROLES, MessagingAccess, ModelRoles, ServerAccess, THINKING_LEVELS,
    },
    rules::{
        composer::{
            join_role, latest_model_versions, model_label, model_option_label, provider_label,
            role_model_groups, role_model_groups_filtered, snapshot_models, split_role,
        },
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
    notifications_unset: bool,
    permission: bool,
    done: bool,
    min_turn: bool,
}
impl Preferences {
    fn load(store: &LocalStateStore) -> Result<Self, zaplaud_store::StoreError> {
        let (permission, done) = store.notify_events()?;
        let notifications = store.notifications_choice()?;
        Ok(Self {
            theme: store.theme()?,
            view: store.default_view()?,
            deliver: store.deliver_as()?,
            summarize: store.summarize()?,
            notifications: notifications == Some(true),
            notifications_unset: notifications.is_none(),
            permission,
            done,
            min_turn: store.notify_min_turn()?,
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
            "min-turn" => store.set_notify_min_turn(on)?,
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
    Filter(String),
    Model(String),
    Thinking(String),
    Loaded(u64, Loaded),
    Access(u64, Result<ServerAccess, zaplaud_core::Error>),
    Saved(String, Result<(), zaplaud_core::Error>),
}
type SnapshotData = (Vec<String>, Vec<AccountQuota>);
enum Loaded {
    Snapshot(Result<SnapshotData, zaplaud_core::Error>),
    Runtime(Result<Option<String>, zaplaud_core::Error>),
    Summary(Result<bool, zaplaud_core::Error>),
    Roles(Result<ModelRoles, zaplaud_core::Error>),
}
#[derive(Default)]
struct State {
    visible: bool,
    active: bool,
    origin: String,
    servers: Vec<(HabblaudApi, bool)>,
    preferences: Preferences,
    roles: ModelRoles,
    runtime: Option<String>,
    summary_http: bool,
    models: Vec<String>,
    quotas: Option<Vec<AccountQuota>>,
    quota_error: bool,
    access: Option<Result<ServerAccess, ()>>,
    status: String,
    error: Option<&'static str>,
    editing: String,
    model: String,
    filter: String,
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
        "default" => strings::ROLE_MAIN,
        "task" => strings::ROLE_TASK,
        "plan" => strings::ROLE_PLAN,
        "tiny" => strings::ROLE_TINY,
        _ => strings::ROLE_ADVISOR,
    }
}
pub(crate) fn thinking_label(level: &str) -> String {
    strings::lookup(&format!("thinking_{level}"))
        .unwrap_or(strings::ROLE_DEFAULT)
        .into()
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
    fn reset_server(&mut self, status: &str) {
        self.generation += 1;
        self.status = status.into();
        self.runtime = None;
        self.summary_http = false;
        self.roles = ModelRoles::default();
        self.editing.clear();
        self.access = None;
        self.quotas = None;
        self.quota_error = false;
        self.models.clear();
        self.snapshot_at = None;
    }

    fn apply_loaded(&mut self, loaded: Loaded) -> bool {
        let error = match &loaded {
            Loaded::Snapshot(result) => result.as_ref().err(),
            Loaded::Runtime(result) => result.as_ref().err(),
            Loaded::Summary(result) => result.as_ref().err(),
            Loaded::Roles(result) => result.as_ref().err(),
        };
        if error.is_some_and(unauthorized) {
            self.reset_server("unauthorized");
            return true;
        }
        match loaded {
            Loaded::Snapshot(result) => {
                self.snapshot_at = Some(tokio::time::Instant::now());
                self.quota_error = result.is_err();
                if let Ok((models, quotas)) = result {
                    self.models = models;
                    self.quotas = Some(quotas);
                }
            }
            Loaded::Runtime(Ok(runtime)) => {
                self.runtime = runtime;
                if self.runtime.as_deref() != Some("omp") {
                    self.roles = ModelRoles::default();
                    self.editing.clear();
                    self.status = "loading".into();
                }
            }
            Loaded::Summary(result) => self.summary_http = result.unwrap_or(false),
            Loaded::Roles(result) if self.runtime.as_deref() == Some("omp") => match result {
                Ok(mut roles) => {
                    roles.pending |= self.saving.contains(&self.origin);
                    self.roles = roles;
                    self.status = "ready".into();
                    if self.error == Some(strings::ROLES_ERROR) {
                        self.error = None;
                    }
                }
                Err(zaplaud_core::Error::Api { status: 403, .. }) => {
                    self.status = "forbidden".into();
                }
                Err(_) if self.roles.roles.is_none() => self.status = "error".into(),
                Err(_) => {}
            },
            _ => {}
        }
        false
    }

    fn finish_save(&mut self, origin: &str) -> bool {
        self.saving.remove(origin);
        origin == self.origin
    }
    fn access_rows(&self, rows: &mut Vec<SettingsRow>) {
        rows.push(row("section", "", strings::ACCESS_TITLE));
        let needs_login = self.status == "unauthorized"
            || self
                .servers
                .iter()
                .any(|(api, login)| api.origin() == self.origin && *login);
        let access = match (needs_login, self.access) {
            (false, Some(Ok(access))) => access,
            (true, _) => {
                rows.push(row("hint", "", strings::ACCESS_LOGIN_REQUIRED));
                return;
            }
            (false, Some(Err(()))) => {
                rows.push(row("hint", "", strings::ACCESS_UNAVAILABLE));
                return;
            }
            (false, None) => {
                rows.push(row("hint", "", strings::ACCESS_LOADING));
                return;
            }
        };
        let messaging = access.messaging_state();
        let messaging_allowed = match messaging {
            MessagingAccess::NotReported => None,
            MessagingAccess::Allowed => Some(true),
            MessagingAccess::Blocked | MessagingAccess::GuardRequired => Some(false),
        };
        let flags = [
            (strings::ACCESS_READING, access.reading),
            (strings::ACCESS_MESSAGING, messaging_allowed),
            (strings::ACCESS_PERMISSIONS, access.permissions),
            (strings::FILES_TITLE, access.files),
        ];
        for (label, flag) in flags {
            let mut entry = row("access", "", label);
            entry.detail = match flag {
                Some(true) => strings::ACCESS_ALLOWED,
                Some(false) => strings::ACCESS_BLOCKED,
                None => strings::ACCESS_NOT_REPORTED,
            }
            .into();
            rows.push(entry);
            if label == strings::ACCESS_MESSAGING && messaging == MessagingAccess::GuardRequired {
                rows.push(row("hint", "", strings::MESSAGE_GUARD_REQUIRED));
            }
        }
        if flags.iter().any(|(_, flag)| *flag == Some(false)) {
            rows.push(row("hint", "", strings::ACCESS_ALLOW_HINT));
        }
        if access.files == Some(false) && access.reading == Some(true) {
            rows.push(row("hint", "", strings::ACCESS_FILES_HINT));
        }
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
                if key == "deliver"
                    && !zaplaud_core::rules::composer::delivery_modes(
                        self.runtime.as_deref().unwrap_or(""),
                    )
                    .contains(&value)
                {
                    continue;
                }
                let mut r = row("radio", key, label);
                r.value = value.into();
                r.checked = if key == "deliver" {
                    zaplaud_core::rules::composer::deliver_as(
                        self.runtime.as_deref().unwrap_or(""),
                        current,
                    ) == Some(value)
                } else {
                    current == value
                };
                rows.push(r);
            }
        }
        rows.push(row("hint", "", strings::SETTINGS_PER_SESSION));
        rows.push(row("section", "", strings::SETTINGS_SUMMARIES));
        let tiny = zaplaud_core::model::summary_available(
            self.summary_http,
            self.roles
                .roles
                .as_ref()
                .filter(|_| self.runtime.as_deref() == Some("omp")),
        );
        let mut summarize = row("switch", "summarize", strings::SETTINGS_SUMMARIZE);
        summarize.checked = tiny && self.preferences.summarize;
        summarize.enabled = tiny;
        rows.push(summarize);
        rows.push(row(
            "hint",
            "",
            if tiny {
                strings::SETTINGS_SUMMARIZE_HINT
            } else if self.runtime.as_deref() == Some("omp") {
                strings::SETTINGS_SUMMARIZE_NEEDS_TINY
            } else {
                strings::SETTINGS_SUMMARIZE_UNAVAILABLE
            },
        ));
        if self.runtime.as_deref() == Some("omp") {
            rows.push(row("section", "", strings::SETTINGS_ROLES));
            match self.status.as_str() {
                "loading" => rows.push(row("hint", "", strings::QUOTA_LOADING)),
                "forbidden" => rows.push(row("hint", "", strings::CAST_FORBIDDEN)),
                "error" => rows.push(row("hint", "", strings::ROLES_LOAD_ERROR)),
                _ => {
                    for role in EDITABLE_ROLES {
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
                                let label = model_label(model);
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
        }
        rows.push(row("section", "", strings::NOTIFICATIONS));
        for (key, label, on, enabled) in [
            (
                "notifications",
                strings::SETTINGS_NOTIFY_AGENTS,
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
            (
                "min-turn",
                strings::SETTINGS_NOTIFY_MIN_TURN,
                self.preferences.min_turn,
                self.preferences.notifications && self.preferences.done,
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
                if self.quota_error {
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
        if self.quota_error && self.quotas.is_some() {
            let mut r = row("hint", "", strings::QUOTA_ERROR);
            r.severity = 2;
            rows.push(r);
        }
        if let Some(error) = self.error {
            let mut r = row("hint", "", error);
            r.severity = 2;
            rows.push(r);
        }
        self.access_rows(&mut rows);
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
        let all_groups = role_model_groups(&self.models, Some(current_model));
        if choosing {
            let groups = if self.filter.is_empty() {
                all_groups
            } else {
                role_model_groups_filtered(&self.models, Some(current_model), &self.filter)
            };
            for (provider, models) in groups {
                choices.push(row("section", "", &provider_label(&provider)));
                // Com filtro, mostre todas as versões que casam.
                let (latest, older) = if self.filter.is_empty() {
                    latest_model_versions(&models, Some(current_model))
                } else {
                    (models, Vec::new())
                };
                let radio = |model: String, kind: &str| {
                    let mut r = row(kind, &provider, &model_option_label(&model));
                    r.checked = model == current_model;
                    r.value = model.into();
                    r
                };
                choices.extend(latest.into_iter().map(|model| radio(model, "radio")));
                if !older.is_empty() {
                    let text = format!("{} ({})", strings::MODELS_MORE, older.len());
                    choices.push(row("more", &provider, &text));
                }
                choices.extend(older.into_iter().map(|model| radio(model, "older")));
            }
            if self.models.is_empty() {
                choices.push(row("hint", "", strings::ROLE_NO_MODELS));
            } else if choices.is_empty() {
                choices.push(row("hint", "", strings::MODELS_NO_RESULTS));
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
    let (api, _) = state
        .servers
        .iter()
        .find(|(api, needs_login)| api.origin() == state.origin && !needs_login)?;
    if state.status == "unauthorized" {
        return None;
    }
    let access_due = state.access.is_none();
    let api = api.clone();
    let generation = state.generation;
    let send = send.clone();
    let roles_due = state.runtime.as_deref() == Some("omp");
    let snapshot_due = state
        .snapshot_at
        .is_none_or(|at| at.elapsed() >= Duration::from_secs(60));
    Some(runtime.spawn(async move {
        let snapshot = async {
            if snapshot_due {
                let result = async {
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
                    Ok((models, quotas))
                }
                .await;
                let _ = send.send(Command::Loaded(generation, Loaded::Snapshot(result)));
            }
        };
        let roles = async {
            if roles_due {
                let _ = send.send(Command::Loaded(generation, Loaded::Roles(api.roles().await)));
            }
        };
        let runtime = async {
            let result = api.cast_runtime().await;
            let load_roles = !roles_due
                && result.as_ref().is_ok_and(|runtime| runtime.as_deref() == Some("omp"));
            let _ = send.send(Command::Loaded(generation, Loaded::Runtime(result)));
            if load_roles {
                let _ = send.send(Command::Loaded(generation, Loaded::Roles(api.roles().await)));
            }
        };
        let summary = async {
            let _ = send.send(Command::Loaded(generation, Loaded::Summary(api.summary_http().await)));
        };
        let access = async {
            if access_due {
                let _ = send.send(Command::Access(generation, api.server_access().await));
            }
        };
        tokio::join!(access, snapshot, runtime, summary, roles);
    }))
}
pub fn setup(window: &AppWindow, context: &crate::feature::FeatureContext) {
    let ui = window.global::<SettingsState>();
    ui.set_title(strings::SETTINGS.into());
    ui.set_back(strings::BACK.into());
    ui.set_cancel(strings::CANCEL.into());
    ui.set_use_label(strings::ROLE_USE.into());
    ui.set_model_placeholder(strings::ROLE_TYPE_MODEL.into());
    ui.set_filter_placeholder(strings::FILTER_MODELS.into());
    ui.set_models_less(strings::MODELS_LESS.into());
    ui.set_notification_prompt_text(strings::NOTIFY_ENABLE_PROMPT.into());
    ui.set_notification_yes(strings::NOTIFY_ENABLE_YES.into());
    ui.set_notification_no(strings::NOTIFY_ENABLE_NO.into());
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
    ui.on_filter_changed(move |value| {
        let _ = s.send(Command::Filter(value.into()));
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
        let mut configured = store.servers().is_ok_and(|servers| !servers.is_empty());
        let mut prompt_visible = false;
        let mut request: Option<tokio::task::JoinHandle<()>> = None;
        let mut deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            let show_prompt = !cfg!(target_os = "android") && configured && state.preferences.notifications_unset;
            if show_prompt != prompt_visible {
                prompt_visible = show_prompt;
                update_ui(&weak, move |w| w.global::<SettingsState>().set_notification_prompt(show_prompt));
            }
            let was_visible = state.visible;
            let mut reload = false;
            tokio::select! {
                snapshot = snapshots.recv() => {
                    match snapshot {
                        Ok(snapshot) => {
                            configured = !snapshot.servers.is_empty();
                            if state.servers.len() == snapshot.servers.len() && state.servers.iter().zip(&snapshot.servers).all(|((api, login), server)| api.origin() == server.api.origin() && *login == server.needs_login) { continue; }
                            let was_logged_out = state.servers.iter().any(|(api, login)| api.origin() == state.origin && *login);
                            state.servers = snapshot.servers.iter().map(|s| (s.api.clone(), s.needs_login)).collect();
                            if state.status != "unauthorized" && state.servers.iter().any(|(api, login)| api.origin() == state.origin && *login) {
                                state.reset_server("unauthorized");
                                reload = true;
                            }
                            if !state.servers.iter().any(|(api, _)| api.origin() == state.origin) {
                                state.origin = state.servers.first().map_or_else(String::new, |(api, _)| api.origin().to_owned());
                                state.reset_server("loading");
                                reload = state.visible;
                            }
                            if was_logged_out && state.servers.iter().any(|(api, login)| api.origin() == state.origin && !*login) {
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
                            state.reset_server("loading");
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
                        Some(Command::Active(active)) => {
                            if active && !state.active { state.access = None; }
                            state.active = active; reload = active && state.visible;
                        }
                        Some(Command::Origin(origin)) => {
                            state.origin = origin;
                            state.reset_server("loading");
                            reload = true;
                        }
                        Some(Command::Activate(key, value, on)) => {
                            if state.preferences.save(&store, &key, &value, on).is_err() {
                                state.error = Some(strings::STORAGE_ERROR);
                                if key == "notifications" && prompt_visible {
                                    update_ui(&weak, |w| w.global::<crate::ShellState>().set_message(strings::STORAGE_ERROR.into()));
                                }
                            } else {
                                update_ui(&weak, move |w| {
                                    let settings = w.global::<SettingsState>();
                                    if key == "theme" { settings.set_theme_mode(value.into()); }
                                    settings.invoke_preferences_changed();
                                    if matches!(key.as_str(), "notifications" | "permission" | "done" | "min-turn") { settings.invoke_notification_preferences_changed(); }
                                });
                            }
                        }
                        Some(Command::Edit(role)) => {
                            if state.runtime.as_deref() == Some("omp") && state.roles.available && !state.roles.pending && !state.saving.contains(&state.origin) && state.status == "ready" {
                                state.editing = role;
                                state.model.clear();
                                state.filter.clear();
                                update_ui(&weak, |w| {
                                    let ui = w.global::<SettingsState>();
                                    ui.set_typed("".into());
                                    ui.set_typed_valid(false);
                                    ui.set_filter("".into());
                                });
                            }
                        }
                        Some(Command::CancelEdit) => {
                            state.editing.clear();
                            state.filter.clear();
                        }
                        Some(Command::Filter(filter)) => {
                            state.filter = filter;
                        }
                        Some(Command::Model(model)) => {
                            if typed_model_valid(&model) {
                                state.model = model.trim().into();
                            }
                        }
                        Some(Command::Thinking(level)) => {
                            if state.runtime.as_deref() == Some("omp") && !state.model.is_empty() && (level.is_empty() || THINKING_LEVELS.contains(&level.as_str()))
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
                        Some(Command::Access(generation, result)) if generation == state.generation => {
                            if result.as_ref().is_err_and(unauthorized) {
                                let _ = commands.send(crate::state::Event::Unauthorized(state.origin.clone()));
                                state.reset_server("unauthorized");
                                reload = true;
                            } else {
                                state.access = Some(result.map_err(|_| ()));
                            }
                        }
                        Some(Command::Loaded(generation, loaded)) if generation == state.generation => {
                            let roles_loaded = matches!(loaded, Loaded::Roles(_));
                            if state.apply_loaded(loaded) {
                                let _ = commands.send(crate::state::Event::Unauthorized(state.origin.clone()));
                                reload = true;
                            }
                            if roles_loaded {
                                deadline = tokio::time::Instant::now() + Duration::from_millis(if state.roles.pending { 1500 } else { 10000 });
                            }
                        }
                        Some(_) => {}
                        None => break,
                    }
                }
                _ = tokio::time::sleep_until(deadline), if state.visible && state.active && state.status != "unauthorized" => {
                    reload = request.as_ref().is_none_or(|task| task.is_finished());
                    deadline = tokio::time::Instant::now() + Duration::from_millis(if state.roles.pending { 1500 } else { 10000 });
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
    fn settings_refresh_failures_do_not_discard_other_sections() {
        let mut state = State::default();
        let failure = || zaplaud_core::Error::InvalidResponse("teste");
        assert!(!state.apply_loaded(Loaded::Snapshot(Err(failure()))));
        assert!(!state.apply_loaded(Loaded::Runtime(Ok(Some("omp".into())))));
        assert!(!state.apply_loaded(Loaded::Summary(Ok(true))));
        assert!(!state.apply_loaded(Loaded::Roles(Ok(ModelRoles {
            roles: Some(BTreeMap::from([("tiny".into(), "p/model".into())])),
            available: true,
            ..ModelRoles::default()
        }))));
        state.editing = "tiny".into();
        assert!(state.quota_error && state.summary_http && state.roles.available);
        assert!(!state.apply_loaded(Loaded::Snapshot(Ok((vec!["p/model".into()], vec![])))));
        assert!(!state.apply_loaded(Loaded::Runtime(Err(failure()))));
        assert!(!state.apply_loaded(Loaded::Summary(Err(failure()))));
        assert!(!state.apply_loaded(Loaded::Roles(Err(failure()))));
        assert!(!state.quota_error && !state.summary_http && state.roles.available);
        assert_eq!(state.runtime.as_deref(), Some("omp"));
        assert_eq!(state.editing, "tiny");
        assert_eq!(state.status, "ready");
        assert_eq!(state.models, ["p/model"]);
        assert!(state.quotas.is_some());
    }

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
                min_turn: true,
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
        state.runtime = Some("omp".into());
        assert!(state.rows().iter().any(|r| r.text == strings::SETTINGS_SUMMARIZE_NEEDS_TINY));
        state.roles.roles = Some(BTreeMap::from([("tiny".into(), "p/model".into())]));
        let rows = state.rows();
        let summary = rows.iter().find(|r| r.key == "summarize").unwrap();
        assert!(summary.enabled && summary.checked);
        for key in ["permission", "done", "min-turn"] {
            let event = rows.iter().find(|r| r.key == key).unwrap();
            assert!(event.checked);
            assert!(!event.enabled);
        }
        state.preferences.notifications = true;
        assert!(
            state
                .rows()
                .into_iter()
                .filter(|r| matches!(r.key.as_str(), "permission" | "done" | "min-turn"))
                .all(|r| r.enabled)
        );
        state.runtime = Some("zeroclaw".into());
        state.summary_http = true;
        let rows = state.rows();
        assert!(
            rows.iter()
                .any(|row| row.key == "summarize" && row.enabled && row.checked)
        );
        assert!(!rows.iter().any(|row| row.kind == "role"));
        assert_eq!(
            rows.iter()
                .filter(|row| row.key == "deliver")
                .map(|row| row.value.as_str())
                .collect::<Vec<_>>(),
            ["followUp", "steer"]
        );
        state.summary_http = false;
        assert!(
            state
                .rows()
                .iter()
                .any(|row| row.key == "summarize" && !row.enabled)
        );
    }
    #[test]
    fn quota_error_replaces_initial_loading_and_limits_keep_the_card() {
        let mut state = State {
            quota_error: true,
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
        state.quota_error = false;
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
