use crate::{
    AppEvents, AppWindow, AvatarColors, ConversationState, Icons, Labels, ListState, LoginState,
    ServerRow, SessionRow, Theme, avatars, formatted,
    state::{AppState, Event, now_millis},
    strings, update_ui,
};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use zaplaud_core::{
    model::{SessionItem, sub_session},
    rules::composer::model_brand,
    rules::list::{combined_connection, merge_sessions, server_label, visible_sessions},
};

pub fn setup(window: &AppWindow, context: &crate::feature::FeatureContext) {
    let labels = window.global::<Labels>();
    labels.set_reading_connected(strings::READING_CONNECTED.into());
    labels.set_reading_connecting(strings::READING_CONNECTING.into());
    labels.set_reading_offline(strings::READING_OFFLINE.into());
    labels.set_cast_start(strings::CAST_START.into());
    let sender = &context.commands;
    let send = sender.clone();
    window.global::<ListState>().on_open(move |key| {
        let _ = send.send(Event::Open(key.into()));
    });
    let send = sender.clone();
    let weak = window.as_weak();
    window.global::<ListState>().on_changed(move || {
        if let Some(window) = weak.upgrade() {
            let list = window.global::<ListState>();
            let _ = send.send(Event::Filter {
                origin: list.get_filter().into(),
                query: list.get_query().into(),
                recent: list.get_tab() == 1,
            });
        }
    });
    let weak = window.as_weak();
    window.global::<ListState>().on_add_server(move || {
        if let Some(window) = weak.upgrade() {
            let login = window.global::<LoginState>();
            login.set_server_url("".into());
            login.set_message("".into());
            login.set_visible(true);
        }
    });
    let weak = window.as_weak();
    window.global::<ListState>().on_relogin(move |origin| {
        if let Some(window) = weak.upgrade() {
            let login = window.global::<LoginState>();
            login.set_server_url(origin);
            login.set_message("".into());
            login.set_visible(true);
        }
    });
    let weak = window.as_weak();
    window.global::<ListState>().on_settings(move || {
        if let Some(window) = weak.upgrade() {
            window.global::<AppEvents>().invoke_settings();
        }
    });
    let send = sender.clone();
    let weak = window.as_weak();
    window.global::<ListState>().on_avatar(move |key| {
        if let Some(window) = weak.upgrade() {
            let model = window.global::<ListState>().get_rows();
            if model
                .iter()
                .any(|row| row.key == key && row.avatar.size().width > 0)
            {
                return;
            }
        }
        let _ = send.send(Event::Avatar(key.into()));
    });
}

pub fn subtitle(item: &SessionItem, multiple: bool) -> String {
    let status = match item.status.as_deref().or(item.activity.as_deref()) {
        Some("idle") => strings::STATUS_IDLE,
        Some("working" | "busy" | "running") => strings::STATUS_WORKING,
        Some("waiting") => strings::STATUS_WAITING,
        Some("offline" | "closed" | "stopped") => strings::STATUS_CLOSED,
        _ => "",
    };
    let mut parts = Vec::new();
    if let Some(name) = item
        .character_name
        .as_deref()
        .filter(|name| *name != item.title)
    {
        parts.push(name.to_owned());
    }
    if let Some(project) = &item.project {
        parts.push(project.rsplit('/').next().unwrap_or(project).into());
    }
    if !status.is_empty() {
        parts.push(status.into());
    }
    if multiple {
        parts.push(server_label(&item.key.origin));
    }
    parts.join(" · ")
}
fn relative_time(at: i64, now: i64) -> String {
    let value = zaplaud_core::rules::time::relative_time(at, now);
    match value.amount {
        Some(amount) => formatted(value.key, &[&amount.to_string()]),
        None => strings::TIME_NOW.into(),
    }
}
fn activity(item: &SessionItem, now: i64) -> String {
    if item.agent_id.is_none() || item.status.as_deref() != Some("working") {
        return String::new();
    }
    activity_text(item.activity.as_deref(), item.status_since, now)
}
pub(crate) fn activity_text(activity: Option<&str>, since: Option<i64>, now: i64) -> String {
    let text = zaplaud_core::rules::time::status_line_text(activity)
        .unwrap_or_else(|| strings::STATUS_LINE_WORKING.into());
    match since {
        Some(since) => {
            let timer = zaplaud_core::rules::time::stopwatch(now.saturating_sub(since));
            if text.is_empty() {
                timer
            } else {
                format!("{text}… ({timer})")
            }
        }
        None => format!("{text}…"),
    }
}
#[derive(Clone)]
struct Row {
    item: SessionItem,
    unread: usize,
    selected: bool,
    sub_last: bool,
    subtitle: String,
    activity: String,
    time: String,
}

pub fn publish(state: &AppState, weak: &slint::Weak<AppWindow>) {
    let sessions = merge_sessions(
        state
            .servers
            .iter()
            .map(|s| (s.api.origin(), &s.reading.state)),
        state.filter.as_deref(),
    );
    let sessions = visible_sessions(&sessions, state.recent, &state.query);
    let selected = state
        .servers
        .iter()
        .find_map(|s| s.reading.state.selected.clone());
    let now = now_millis();
    let multiple = state.servers.len() > 1 && state.filter.is_none();
    let mut rows = Vec::new();
    for item in sessions {
        let unread = state
            .servers
            .iter()
            .find(|s| s.api.origin() == item.key.origin)
            .and_then(|s| s.reading.state.unread.get(&item.key))
            .copied()
            .unwrap_or(0);
        for (session, is_sub, sub_last) in std::iter::once((item.clone(), false, false)).chain(
            item.subagents.iter().enumerate().map(|(index, sub)| {
                let mut session = sub_session(&item, sub);
                if sub.advisor {
                    session.title = formatted("advisor_name", &[&sub.name]);
                }
                (session, true, index + 1 == item.subagents.len())
            }),
        ) {
            rows.push(Row {
                selected: selected.as_ref().is_some_and(|s| s.key == session.key),
                sub_last,
                unread: if is_sub { 0 } else { unread },
                subtitle: if is_sub {
                    session.activity.clone().unwrap_or_default()
                } else {
                    subtitle(&session, multiple)
                },
                activity: if is_sub {
                    String::new()
                } else {
                    activity(&session, now)
                },
                time: session
                    .replies
                    .last()
                    .copied()
                    .or(session.last_at)
                    .map(|at| relative_time(at, now))
                    .unwrap_or_default(),
                item: session,
            });
        }
    }
    let servers: Vec<_> = state
        .servers
        .iter()
        .map(|s| {
            (
                s.api.origin().to_owned(),
                s.reading.state.connection.clone(),
                s.reading.needs_login,
                s.reading.state.error.clone(),
            )
        })
        .collect();
    let connection = combined_connection(
        state
            .servers
            .iter()
            .map(|s| (&s.reading.state, s.reading.needs_login)),
    )
    .to_owned();
    let can_cancel = !servers.is_empty();
    let loading = state
        .servers
        .iter()
        .any(|s| s.reading.state.connection == "connecting" && s.reading.state.sessions.is_empty());
    let terminal_allowed = state
        .servers
        .iter()
        .any(|s| s.reading.state.terminal_allowed);
    let server_notices = servers
        .iter()
        .any(|(_, _, needs_login, error)| *needs_login || error.is_some());
    update_ui(weak, move |window| {
        let list = window.global::<ListState>();
        list.set_connection(connection.into());
        list.set_loading(loading);
        list.set_server_notices(server_notices);
        list.set_terminal_allowed(terminal_allowed);
        list.set_terminal_blocked_label(strings::TERMINAL_BLOCKED.into());
        let models = rows
            .into_iter()
            .map(|row| row_model(&window, row))
            .collect::<Vec<_>>();
        list.set_rows(ModelRc::new(VecModel::from(models)));
        crate::clock::refresh_list(&window);
        list.set_servers(ModelRc::new(VecModel::from(
            servers
                .into_iter()
                .map(|(origin, connection, needs_login, error)| ServerRow {
                    label: server_label(&origin).into(),
                    origin: origin.into(),
                    connection: connection.into(),
                    needs_login,
                    error: error
                        .map(|error| error_text(&error))
                        .unwrap_or_default()
                        .into(),
                })
                .collect::<Vec<_>>(),
        )));
        window.global::<LoginState>().set_can_cancel(can_cancel);
        crate::conversation::publish(&window, selected);
    });
}
fn error_text(error: &str) -> String {
    match error {
        "changed" => strings::SESSION_CHANGED,
        "forbidden" => strings::TERMINAL_BLOCKED,
        "missing" => strings::READING_MISSING,
        "oversize" => strings::READING_OVERSIZE,
        _ => strings::NETWORK_ERROR,
    }
    .into()
}
fn row_model(window: &AppWindow, row: Row) -> SessionRow {
    let item = row.item;
    let name = item.character_name.as_deref().unwrap_or(&item.title);
    let initials = name
        .chars()
        .next()
        .map(|c| c.to_uppercase().collect::<String>())
        .unwrap_or_default();
    let hash = name
        .encode_utf16()
        .fold(0i32, |a, c| a.wrapping_mul(31).wrapping_add(c as i32));
    let colors = window.global::<AvatarColors>();
    let palette = [
        colors.get_background_0(),
        colors.get_background_1(),
        colors.get_background_2(),
        colors.get_background_3(),
        colors.get_background_4(),
    ];
    let fallback = palette[(hash & i32::MAX) as usize % 5];
    let icons = window.global::<Icons>();
    let (brand, brand_color) = match model_brand(item.model.as_deref()) {
        Some("claude") => (icons.get_ic_model_claude(), colors.get_claude()),
        Some("gemini") => (icons.get_ic_model_gemini(), colors.get_gemini()),
        Some("openai") => (
            icons.get_ic_model_openai(),
            window.global::<Theme>().get_on_surface(),
        ),
        _ => (slint::Image::default(), slint::Color::default()),
    };
    let avatar_key = item
        .avatar
        .as_ref()
        .map(|avatar| avatars::key(&item.key.origin, avatar))
        .unwrap_or_default();
    let avatar = avatars::get(&avatar_key);
    SessionRow {
        key: item.key.serialize().unwrap_or_default().into(),
        title: item.title.into(),
        subtitle: row.subtitle.into(),
        activity: row.activity.into(),
        time: row.time.into(),
        unread: if row.unread == 0 {
            String::new()
        } else if row.unread >= 99 {
            "99+".into()
        } else {
            row.unread.to_string()
        }
        .into(),
        unread_label: if row.unread == 0 {
            String::new()
        } else {
            strings::format_plural(
                "unread_replies",
                row.unread as i64,
                &[&row.unread.to_string()],
            )
            .unwrap_or_default()
        }
        .into(),
        initials: initials.into(),
        avatar,
        avatar_key: avatar_key.into(),
        brand,
        brand_color,
        fallback_color: fallback,
        selected: row.selected,
        sub: item.sub,
        sub_last: row.sub_last,
        permission: if item.permission {
            strings::PERMISSION_PENDING
        } else {
            ""
        }
        .into(),
        codex: item.key.provider == "codex",
        working: item.agent_id.is_some()
            && item.status.as_deref() == Some("working")
            && item.status_since.is_some(),
        since: item
            .status_since
            .map(|s| s.to_string())
            .unwrap_or_default()
            .into(),
        activity_text: item.activity.unwrap_or_default().into(),
    }
}

pub fn apply_avatar(window: &AppWindow, key: &str, cache_key: String, rgba: Vec<u8>) {
    let evicted = avatars::insert(cache_key.clone(), &rgba);
    let list = window.global::<ListState>();
    let model = list.get_rows();
    for i in 0..model.row_count() {
        if let Some(mut row) = model.row_data(i) {
            if row.key == key {
                row.avatar = avatars::get(&cache_key);
                model.set_row_data(i, row);
            } else if evicted.iter().any(|key| key == row.avatar_key.as_str()) {
                row.avatar = slint::Image::default();
                model.set_row_data(i, row);
            }
        }
    }
    if window.global::<ConversationState>().get_key() == key {
        window
            .global::<ConversationState>()
            .set_avatar(avatars::get(&cache_key));
    }
}

#[cfg(test)]
mod tests {
    use super::activity_text;

    #[test]
    fn working_line_normalizes_ellipsis_and_clamps_elapsed_time() {
        assert_eq!(
            activity_text(Some(" Analisando... "), Some(1_000), 2_000),
            "Analisando… (0:01)"
        );
        assert_eq!(
            activity_text(Some("Analisando"), Some(2_000), 1_000),
            "Analisando… (0:00)"
        );
        assert_eq!(activity_text(Some("Analisando…"), None, 0), "Analisando…");
    }
}
