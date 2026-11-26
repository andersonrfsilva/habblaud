use crate::{
    AppWindow, CastRow, CastState, ConversationState, Icons, ListState, avatars,
    feature::{AppSnapshot, FeatureContext},
    state::Event,
    strings, update_ui,
};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{sync::Arc, time::Duration};
use tokio::sync::{mpsc, watch};
use zaplaud_core::{
    Error,
    controller::cast::{State, confirmation_target},
    model::{CastChoice, SessionItem, SessionKey},
};
#[derive(Clone)]
enum Command {
    Start,
    Resume(SessionKey),
    Dismiss { key: SessionKey, from_list: bool },
    Choose(String),
    Confirm(SessionKey),
    Close,
}
#[derive(Default)]
struct Dialog {
    mode: String,
    choice: Option<CastChoice>,
    target: Option<SessionItem>,
    from_list: bool,
    confirming: bool,
}
impl Dialog {
    fn picking_model(&self, state: &State) -> bool {
        self.mode == "start" && self.choice.is_some() && state.picks_model()
    }
    fn current_target<'a>(&self, snapshot: &'a AppSnapshot, origin: &str) -> Option<&'a SessionItem> {
        let target = self.target.as_ref()?;
        let current = if self.from_list {
            list_target(snapshot, origin, &target.key)
        } else {
            snapshot.selected.as_ref()
        }?;
        confirmation_target(target, current, origin)?;
        Some(current)
    }
    fn close(&mut self, state: &State) {
        if self.picking_model(state) {
            self.choice = None;
        } else {
            *self = Self::default();
        }
    }
}

fn list_target<'a>(
    snapshot: &'a AppSnapshot,
    origin: &str,
    key: &SessionKey,
) -> Option<&'a SessionItem> {
    if key.origin != origin {
        return None;
    }
    snapshot
        .servers
        .iter()
        .find(|server| server.api.origin() == origin)?
        .reading
        .sessions
        .iter()
        .find(|session| session.key == *key)
}

fn dismissible(state: &State, session: &SessionItem) -> bool {
    !session.sub
        && state.can_dismiss(session)
        && !session
            .cast
            .as_deref()
            .is_some_and(|id| state.active_job(id))
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
    let selected_command = |build: fn(SessionKey) -> Command| {
        let weak = window.as_weak();
        let sender = send.clone();
        move || {
            if let Some(window) = weak.upgrade()
                && let Ok(key) =
                    SessionKey::deserialize(window.global::<ConversationState>().get_key().as_str())
            {
                let _ = sender.send(build(key));
            }
        }
    };
    global.on_resume(selected_command(Command::Resume));
    global.on_dismiss(selected_command(|key| Command::Dismiss {
        key,
        from_list: false,
    }));
    let s = send.clone();
    window.global::<ListState>().on_dismiss(move |key| {
        if let Ok(key) = SessionKey::deserialize(key.as_str()) {
            let _ = s.send(Command::Dismiss {
                key,
                from_list: true,
            });
        }
    });
    let s = send.clone();
    global.on_choose(move |id| {
        let _ = s.send(Command::Choose(id.into()));
    });
    let weak = window.as_weak();
    let s = send.clone();
    global.on_confirm(move || {
        if let Some(window) = weak.upgrade()
            && let Ok(key) = SessionKey::deserialize(
                window.global::<ListState>().get_cast_confirmation_key().as_str(),
            )
        {
            let _ = s.send(Command::Confirm(key));
        }
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
                biased;
                value = snapshots.recv() => {
                    let value = match value { Ok(value) => value, Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue, Err(_) => break };
                    let next_origin = value.selected.as_ref().map(|s| s.key.origin.as_str()).or(value.filter.as_deref()).or_else(|| (value.servers.len() == 1).then(|| value.servers[0].api.origin())).unwrap_or_default();
                    if next_origin != origin { origin = next_origin.into(); state = State::default(); dialog = Dialog::default(); refresh = true; loaded = false; avatar_keys.clear(); }
                    if snapshot.as_ref().and_then(|s| s.selected.as_ref()).map(|s| &s.key) != value.selected.as_ref().map(|s| &s.key) { state.result.clear(); dialog = Dialog::default(); }
                    if dialog.target.is_some()
                        && (snapshot.as_ref().is_some_and(|old| old.filter != value.filter)
                            || dialog.current_target(&value, &origin).is_none())
                    {
                        dialog = Dialog::default();
                    }
                    snapshot = Some(value);
                }
                changed = visibility.changed() => { if changed.is_err() { break; } refresh = *visibility.borrow(); }
                _ = tokio::time::sleep_until(next_poll), if !origin.is_empty() && *visibility.borrow() => { refresh = true; }
                command = receive.recv() => {
                    let Some(command) = command else { break }; let Some(snap) = &snapshot else { continue };
                    let Some(server) = snap.servers.iter().find(|s| s.api.origin() == origin) else { continue };
                    let selected = snap.selected.as_ref();
                    if !matches!(command, Command::Close) && (server.needs_login || server.reading.connection != "connected" || state.availability != "available") { continue; }
                    match command {
                        // No desktop a lista fica ao lado da conversa: chamar um personagem não depende de seleção.
                        Command::Start => { dialog = Dialog { mode: "start".into(), ..Dialog::default() }; if let Err(error) = state.load(&server.api).await { unauthorized(&commands, &origin, &error); } }
                        Command::Resume(key) => {
                            if let Some(target) = selected.filter(|s| s.key == key && state.can_resume(s)) {
                                dialog = Dialog { mode: "resume".into(), target: Some(target.clone()), ..Dialog::default() };
                                if let Err(error) = state.load(&server.api).await { unauthorized(&commands, &origin, &error); }
                            }
                        }
                        Command::Dismiss { key, from_list } => {
                            let target = if from_list { list_target(snap, &origin, &key) } else { selected.filter(|s| s.key == key) };
                            if let Some(target) = target.filter(|s| dismissible(&state, s)) {
                                dialog = Dialog { mode: "dismiss".into(), target: Some(target.clone()), from_list, confirming: true, ..Dialog::default() };
                            }
                        }
                        Command::Close => { dialog.close(&state); }
                        Command::Choose(id) if !dialog.mode.is_empty() => {
                            if dialog.choice.is_none() {
                                dialog.choice = if id.is_empty() { Some(CastChoice { id, name: strings::CAST_NEW.into(), avatar: None, model: None }) } else { state.free(&server.reading.sessions, dialog.target.as_ref()).into_iter().find(|c| c.id == id) };
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
                        Command::Confirm(key) if dialog.confirming => {
                            if let Some(current) = dialog.current_target(snap, &origin).filter(|s| s.key == key) {
                                if dialog.mode == "dismiss" {
                                    if dismissible(&state, current) && let Some(id) = current.agent_id.as_deref() && let Err(error) = state.submit(&server.api, "", None, Some(id), None).await { unauthorized(&commands, &origin, &error); }
                                } else if let Some(choice) = &dialog.choice && state.can_resume(current) && let Err(error) = state.submit(&server.api, &choice.id, dialog.target.as_ref(), None, None).await { unauthorized(&commands, &origin, &error); }
                            }
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
                } else { update_ui(&weak, |window| { let list = window.global::<ListState>(); list.set_cast_visible(false); list.set_dismissible_keys(ModelRc::default()); list.set_cast_confirmation_key("".into()); let global = window.global::<CastState>(); global.set_resume_visible(false); global.set_dismiss_visible(false); global.set_visible(false); global.set_confirming(false); global.set_status("".into()); }); }
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
                        dialog.target.as_ref(),
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
        dialog.target.as_ref().map_or_else(
            || strings::CAST_DISMISS_CONFIRM.to_owned(),
            |target| {
                format!(
                    "{}\n{}",
                    target.character_name.as_deref().unwrap_or(&target.title),
                    strings::CAST_DISMISS_CONFIRM
                )
            },
        )
    } else {
        format!(
            "{}\n{}",
            dialog
                .choice
                .as_ref()
                .map(|c| c.name.as_str())
                .unwrap_or_default(),
            dialog
                .target
                .as_ref()
                .and_then(|s| s.project.as_deref())
                .unwrap_or_default()
        )
    };
    let confirming = dialog.confirming;
    let confirmation_key = dialog
        .target
        .as_ref()
        .filter(|_| confirming)
        .and_then(|target| target.key.serialize().ok())
        .unwrap_or_default();
    let dismissible_keys: Vec<slint::SharedString> = server
        .filter(|_| state.availability == "available")
        .into_iter()
        .flat_map(|server| &server.reading.sessions)
        .filter(|session| dismissible(&state, session))
        .filter_map(|session| session.key.serialize().ok().map(Into::into))
        .collect();
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
        let list = window.global::<ListState>();
        list.set_cast_visible(available);
        list.set_dismissible_keys(ModelRc::new(VecModel::from(dismissible_keys)));
        list.set_cast_confirmation_key(confirmation_key.into());
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
    fn list_dismisses_only_open_cast_or_new_main_sessions_without_jobs() {
        let mut state = State {
            characters: vec![CastChoice {
                id: "contact".into(),
                name: "Contact".into(),
                avatar: None,
                model: None,
            }],
            ..State::default()
        };
        let mut item = SessionItem {
            agent_id: Some("live".into()),
            cast_opened: true,
            cast: Some("contact".into()),
            ..SessionItem::default()
        };
        assert!(dismissible(&state, &item));
        for status in ["queued", "sent", "waiting"] {
            state.jobs.jobs.insert("contact".into(), status.into());
            assert!(!dismissible(&state, &item));
        }
        state.jobs.jobs.insert("contact".into(), "done".into());
        assert!(dismissible(&state, &item));
        item.cast = Some("unlisted".into());
        assert!(!dismissible(&state, &item));
        item.cast = Some("novo-1".into());
        assert!(dismissible(&state, &item));
        item.sub = true;
        assert!(!dismissible(&state, &item));
        item.sub = false;
        item.cast_opened = false;
        assert!(!dismissible(&state, &item));
        item.cast_opened = true;
        item.agent_id = None;
        assert!(!dismissible(&state, &item));
    }

    #[test]
    fn list_confirmation_keeps_its_row_without_selecting_it() {
        use crate::feature::ServerSnapshot;
        use zaplaud_core::{api::HabblaudApi, model::ReadingState};

        let origin = "https://example.com";
        let mut item = SessionItem {
            agent_id: Some("original-agent".into()),
            ..SessionItem::default()
        };
        item.key.origin = origin.into();
        let mut selected = item.clone();
        selected.key.session_id = "another-session".into();
        let mut snapshot = AppSnapshot {
            selected: Some(selected),
            filter: None,
            servers: vec![ServerSnapshot {
                api: HabblaudApi::new(origin).unwrap(),
                reading: ReadingState {
                    sessions: vec![item.clone()],
                    ..ReadingState::default()
                },
                needs_login: false,
            }],
        };
        let dialog = Dialog {
            target: Some(item),
            from_list: true,
            ..Dialog::default()
        };
        assert!(dialog.current_target(&snapshot, origin).is_some());
        assert!(dialog.current_target(&snapshot, "https://other.test").is_none());
        snapshot.servers[0].reading.sessions[0].agent_id = Some("replacement".into());
        assert!(dialog.current_target(&snapshot, origin).is_none());
        snapshot.servers[0].reading.sessions.clear();
        assert!(dialog.current_target(&snapshot, origin).is_none());
    }

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
