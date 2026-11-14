use crate::{
    AppWindow, CastRow, CastState, Icons, ListState, avatars,
    feature::{AppSnapshot, FeatureContext},
    state::Event,
    strings, update_ui,
};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{sync::Arc, time::Duration};
use tokio::sync::{mpsc, watch};
use zaplaud_core::{Error, controller::cast::State, model::CastChoice};
#[derive(Clone)]
enum Command {
    Start,
    Resume,
    Dismiss,
    Choose(String),
    Confirm,
    Close,
}
#[derive(Default)]
struct Dialog {
    mode: String,
    choice: Option<CastChoice>,
    confirming: bool,
}
impl Dialog {
    fn picking_model(&self, state: &State) -> bool {
        self.mode == "start" && self.choice.is_some() && state.picks_model()
    }
    fn close(&mut self, state: &State) {
        if self.picking_model(state) {
            self.choice = None;
        } else {
            *self = Self::default();
        }
    }
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<CastState>();
    global.set_resume_label(strings::CAST_RESUME.into());
    global.set_dismiss_label(strings::CAST_DISMISS.into());
    global.set_confirm_label(strings::CAST_CONFIRM.into());
    window
        .global::<ListState>()
        .set_cast_icon(window.global::<Icons>().get_ic_person_add());
    let (send, mut receive) = mpsc::unbounded_channel();
    let s = send.clone();
    window.global::<ListState>().on_cast(move || {
        let _ = s.send(Command::Start);
    });
    let s = send.clone();
    global.on_start(move || {
        let _ = s.send(Command::Start);
    });
    let s = send.clone();
    global.on_resume(move || {
        let _ = s.send(Command::Resume);
    });
    let s = send.clone();
    global.on_dismiss(move || {
        let _ = s.send(Command::Dismiss);
    });
    let s = send.clone();
    global.on_choose(move |id| {
        let _ = s.send(Command::Choose(id.into()));
    });
    let s = send.clone();
    global.on_confirm(move || {
        let _ = s.send(Command::Confirm);
    });
    global.on_close(move || {
        let _ = send.send(Command::Close);
    });
    let (visible, mut visibility) = watch::channel(true);
    global.on_app_visibility(move |value| {
        visible.send_replace(value);
    });
    let weak = window.as_weak();
    crate::window_state::on_visibility(move |value| {
        if let Some(window) = weak.upgrade() {
            window.global::<CastState>().invoke_app_visibility(value);
        }
    });
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let commands = context.commands.clone();
    context.runtime.spawn(async move {
        let mut snapshot: Option<Arc<AppSnapshot>> = None; let mut state = State::default(); let mut dialog = Dialog::default(); let mut origin = String::new();
        let mut next_poll = tokio::time::Instant::now();
        let mut loaded = false;
        let mut avatar_keys = std::collections::HashSet::new();
        loop {
            let mut refresh = false;
            tokio::select! {
                changed = visibility.changed() => { if changed.is_err() { break; } refresh = *visibility.borrow(); }
                value = snapshots.recv() => {
                    let value = match value { Ok(value) => value, Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue, Err(_) => break };
                    let next_origin = value.selected.as_ref().map(|s| s.key.origin.as_str()).or(value.filter.as_deref()).or_else(|| (value.servers.len() == 1).then(|| value.servers[0].api.origin())).unwrap_or_default();
                    if next_origin != origin { origin = next_origin.into(); state = State::default(); dialog = Dialog::default(); refresh = true; loaded = false; avatar_keys.clear(); }
                    if snapshot.as_ref().and_then(|s| s.selected.as_ref()).map(|s| &s.key) != value.selected.as_ref().map(|s| &s.key) { state.result.clear(); dialog = Dialog::default(); }
                    snapshot = Some(value);
                }
                _ = tokio::time::sleep_until(next_poll), if !origin.is_empty() && *visibility.borrow() => { refresh = true; }
                command = receive.recv() => {
                    let Some(command) = command else { break }; let Some(snap) = &snapshot else { continue };
                    let Some(server) = snap.servers.iter().find(|s| s.api.origin() == origin) else { continue };
                    let selected = snap.selected.as_ref();
                    if !matches!(command, Command::Close) && (server.needs_login || server.reading.connection != "connected" || state.availability != "available") { continue; }
                    match command {
                        // No desktop a lista fica ao lado da conversa: chamar um personagem não depende de seleção.
                        Command::Start => { dialog = Dialog { mode: "start".into(), ..Dialog::default() }; if let Err(error) = state.load(&server.api).await { unauthorized(&commands, &origin, &error); } }
                        Command::Resume if selected.is_some_and(|s| state.can_resume(s)) => { dialog = Dialog { mode: "resume".into(), ..Dialog::default() }; if let Err(error) = state.load(&server.api).await { unauthorized(&commands, &origin, &error); } }
                        Command::Dismiss if selected.is_some_and(|s| state.can_dismiss(s)) => { dialog = Dialog { mode: "dismiss".into(), confirming: true, ..Dialog::default() }; }
                        Command::Close => { dialog.close(&state); }
                        Command::Choose(id) if !dialog.mode.is_empty() => {
                            if dialog.choice.is_none() {
                                dialog.choice = if id.is_empty() { Some(CastChoice { id, name: strings::CAST_NEW.into(), avatar: None, model: None }) } else { state.free(&server.reading.sessions, if dialog.mode == "resume" { selected } else { None }).into_iter().find(|c| c.id == id) };
                                dialog.confirming = dialog.mode == "resume" && dialog.choice.is_some();
                                if dialog.mode == "start" && !state.picks_model() && let Some(choice) = &dialog.choice {
                                    if let Err(error) = state.submit(&server.api, &choice.id, None, None, None).await { unauthorized(&commands, &origin, &error); }
                                    dialog = Dialog::default(); refresh = true; loaded = false;
                                }
                            } else if dialog.picking_model(&state)
                                && state.catalog.models.iter().any(|m| m.alias == id)
                                && let Some(choice) = &dialog.choice {
                                if let Err(error) = state.submit(&server.api, &choice.id, None, None, Some(&id)).await { unauthorized(&commands, &origin, &error); }
                                dialog = Dialog::default(); refresh = true; loaded = false;
                            }
                        }
                        Command::Confirm if dialog.confirming => {
                            if dialog.mode == "dismiss" {
                                if let Some(s) = selected && state.can_dismiss(s) && !s.cast.as_ref().is_some_and(|id| state.active_job(id)) && let Some(id) = s.agent_id.as_deref() && let Err(error) = state.submit(&server.api, "", None, Some(id), None).await { unauthorized(&commands, &origin, &error); }
                            } else if let (Some(choice), Some(s)) = (&dialog.choice, selected) && state.can_resume(s) && let Err(error) = state.submit(&server.api, &choice.id, Some(s), None, None).await { unauthorized(&commands, &origin, &error); }
                            dialog = Dialog::default(); refresh = true; loaded = false;
                        }
                        _ => {}
                    }
                }
            }
            if let Some(snap) = &snapshot {
                if let Some(server) = snap.servers.iter().find(|s| s.api.origin() == origin) {
                    if *visibility.borrow() && !server.needs_login && server.reading.connection == "connected" && (server.reading.terminal_allowed || snap.selected.as_ref().is_some_and(|s| s.permission)) {
                        if refresh { if let Err(error) = state.refresh(&server.api, !loaded).await { unauthorized(&commands, &origin, &error); } else { loaded = true; } next_poll = tokio::time::Instant::now() + Duration::from_secs(5); }
                    } else { state.availability = "offline".into(); next_poll = tokio::time::Instant::now() + Duration::from_secs(5); }
                    if *visibility.borrow() && !dialog.mode.is_empty() {
                        for character in &state.characters {
                            if let Some(avatar) = &character.avatar {
                                let key = avatars::key(&origin, avatar);
                                if avatar_keys.insert(key.clone()) && let Ok(Some(png)) = server.api.avatar_bytes(avatar).await && let Some(rgba) = avatars::decode(&png) {
                                    update_ui(&weak, move |_| { avatars::insert(key, &rgba); });
                                }
                            }
                        }
                    }
                    publish(&weak, snap.clone(), state.clone(), &dialog, &origin);
                } else { update_ui(&weak, |window| { window.global::<ListState>().set_cast_visible(false); let global = window.global::<CastState>(); global.set_resume_visible(false); global.set_dismiss_visible(false); global.set_visible(false); global.set_confirming(false); global.set_status("".into()); }); }
            }
        }
    });
}
fn unauthorized(commands: &mpsc::UnboundedSender<Event>, origin: &str, error: &Error) {
    if matches!(
        error,
        Error::Unauthorized | Error::SessionEnded | Error::Api { status: 401, .. }
    ) {
        let _ = commands.send(Event::Unauthorized(origin.into()));
    }
}
fn status_text(value: &str) -> &'static str {
    match value {
        "pending" => strings::CAST_PENDING,
        "done" => strings::CAST_DONE,
        "busy" => strings::CAST_BUSY,
        "forbidden" => strings::CAST_FORBIDDEN,
        "failed" => strings::CAST_FAILED,
        "unavailable" => strings::CAST_UNAVAILABLE,
        "unknown" => strings::CAST_UNKNOWN,
        _ => "",
    }
}
fn publish(
    weak: &slint::Weak<AppWindow>,
    snapshot: Arc<AppSnapshot>,
    state: State,
    dialog: &Dialog,
    origin: &str,
) {
    let selected = snapshot.selected.as_ref();
    let server = snapshot.servers.iter().find(|s| s.api.origin() == origin);
    let picking_model = dialog.picking_model(&state);
    let default_model = dialog
        .choice
        .as_ref()
        .and_then(|choice| state.default_model(choice));
    let rows: Vec<_> = if picking_model {
        state
            .catalog
            .models
            .iter()
            .map(|model| {
                (
                    model.alias.clone(),
                    model.model.clone(),
                    None,
                    default_model == Some(model.alias.as_str()),
                )
            })
            .collect()
    } else {
        std::iter::once((String::new(), strings::CAST_NEW.into(), None, false))
            .chain(
                state
                    .free(
                        server
                            .map(|s| s.reading.sessions.as_slice())
                            .unwrap_or_default(),
                        if dialog.mode == "resume" {
                            selected
                        } else {
                            None
                        },
                    )
                    .into_iter()
                    .map(|c| (c.id, c.name, c.avatar, false)),
            )
            .collect()
    };
    let title = if picking_model {
        strings::ROLE_PICK_MODEL
    } else if dialog.confirming {
        if dialog.mode == "dismiss" {
            strings::CAST_DISMISS
        } else {
            strings::CAST_RESUME
        }
    } else {
        strings::CAST_SELECT_CHARACTER
    };
    let warning = if dialog.mode == "dismiss" {
        strings::CAST_DISMISS_CONFIRM.into()
    } else {
        format!(
            "{}\n{}",
            dialog
                .choice
                .as_ref()
                .map(|c| c.name.as_str())
                .unwrap_or_default(),
            selected
                .and_then(|s| s.project.as_deref())
                .unwrap_or_default()
        )
    };
    let confirming = dialog.confirming;
    let visible = !dialog.mode.is_empty() && !confirming;
    let origin = origin.to_owned();
    let mut status = if state.availability == "available"
        && selected
            .and_then(|s| s.cast.as_ref())
            .is_some_and(|id| state.active_job(id))
    {
        strings::CAST_PENDING
    } else {
        status_text(&state.availability)
    }
    .to_owned();
    if !state.result.is_empty() {
        if !status.is_empty() {
            status.push('\n');
        }
        if !state.result_detail.is_empty()
            && matches!(state.result.as_str(), "busy" | "failed" | "forbidden")
        {
            status.push_str(&state.result_detail);
        } else {
            status.push_str(status_text(&state.result));
        }
    }
    update_ui(weak, move |window| {
        let global = window.global::<CastState>();
        let available = state.availability == "available";
        window.global::<ListState>().set_cast_visible(available);
        global.set_resume_visible(
            available
                && snapshot
                    .selected
                    .as_ref()
                    .is_some_and(|s| state.can_resume(s)),
        );
        global.set_dismiss_visible(
            available
                && snapshot
                    .selected
                    .as_ref()
                    .is_some_and(|s| state.can_dismiss(s)),
        );
        global.set_enabled(!state.sending);
        global.set_dismiss_enabled(
            !state.sending
                && !snapshot
                    .selected
                    .as_ref()
                    .and_then(|s| s.cast.as_ref())
                    .is_some_and(|id| state.active_job(id)),
        );
        global.set_status(status.into());
        global.set_visible(visible);
        global.set_confirming(confirming);
        global.set_picking_model(picking_model);
        global.set_title(title.into());
        global.set_warning(warning.into());
        global.set_rows(ModelRc::new(VecModel::from(
            rows.into_iter()
                .map(|(id, name, avatar, selected)| CastRow {
                    id: id.into(),
                    selected,
                    initials: name
                        .chars()
                        .next()
                        .map(|c| c.to_uppercase().collect::<String>())
                        .unwrap_or_default()
                        .into(),
                    name: name.into(),
                    avatar: avatar
                        .map(|a| avatars::get(&avatars::key(&origin, &a)))
                        .unwrap_or_default(),
                })
                .collect::<Vec<_>>(),
        )));
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use zaplaud_core::model::CastCatalog;

    #[test]
    fn model_step_only_for_new_zeroclaw_and_cancel_returns_to_contacts() {
        let mut state = State {
            runtime: Some("zeroclaw".into()),
            catalog: CastCatalog::parse(&serde_json::json!({
                "provider": "zeroclaw",
                "defaultModel": "m_default",
                "models": [
                    {"alias": "m_default", "model": "provider/default"},
                    {"alias": "m_other", "model": "provider/other"}
                ]
            })),
            ..State::default()
        };
        let mut dialog = Dialog {
            mode: "start".into(),
            ..Dialog::default()
        };
        assert!(!dialog.picking_model(&state));
        dialog.choice = Some(CastChoice {
            id: "contact".into(),
            name: "Contact".into(),
            avatar: None,
            model: Some("provider/other".into()),
        });
        assert!(dialog.picking_model(&state));
        assert_eq!(
            state.default_model(dialog.choice.as_ref().unwrap()),
            Some("m_other")
        );
        dialog.choice.as_mut().unwrap().model = Some("m_other".into());
        assert_eq!(
            state.default_model(dialog.choice.as_ref().unwrap()),
            Some("m_other")
        );
        dialog.choice.as_mut().unwrap().model = Some("unknown".into());
        assert_eq!(
            state.default_model(dialog.choice.as_ref().unwrap()),
            Some("m_default")
        );
        dialog.choice.as_mut().unwrap().model = None;
        assert_eq!(
            state.default_model(dialog.choice.as_ref().unwrap()),
            Some("m_default")
        );
        dialog.mode = "resume".into();
        assert!(!dialog.picking_model(&state));
        dialog.mode = "start".into();
        state.runtime = Some("omp".into());
        assert!(!dialog.picking_model(&state));
        state.runtime = Some("zeroclaw".into());
        let models = state.catalog.models.clone();
        state.catalog.models.truncate(1);
        assert!(!dialog.picking_model(&state));
        state.catalog.models.clear();
        assert!(!dialog.picking_model(&state));
        state.catalog.models = models;
        dialog.close(&state);
        assert_eq!(dialog.mode, "start");
        assert!(dialog.choice.is_none());
        dialog.close(&state);
        assert!(dialog.mode.is_empty());
    }
}
