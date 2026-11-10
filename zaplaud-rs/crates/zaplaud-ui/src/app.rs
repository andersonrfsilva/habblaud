use crate::{
    AppWindow, LoginState, error_message, formatted, list,
    state::{AppState, Event, ServerState, now_millis},
    strings, update_ui,
};
use slint::ComponentHandle;
use std::{collections::HashSet, sync::Arc};
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender};
use zaplaud_core::{
    Error,
    api::HabblaudApi,
    controller::reading::ReadingController,
    model::{Access, SessionCollection, SessionKey, StreamEvent},
};

fn status(error: &Error) -> Option<i32> {
    match error {
        Error::Unauthorized => Some(401),
        Error::Api { status, .. } => Some(i32::from(*status)),
        Error::EventTooLarge => Some(-1),
        Error::Redirect(status) => Some(i32::from(*status)),
        _ => None,
    }
}
async fn refresh(api: &HabblaudApi) -> Result<(Access, SessionCollection), Error> {
    let mut access = api.access().await?;
    let mut repository = SessionCollection::default();
    if access.terminal {
        match api.request("/api/sessions/recent", None).await {
            Ok(data) => repository.set_recent(api.origin(), &data)?,
            Err(Error::Api { status: 403, .. }) => access.terminal = false,
            Err(error) => return Err(error),
        }
        match api.request("/api/cast", None).await {
            Ok(data) => repository.set_cast(&data)?,
            Err(Error::Api {
                status: 403 | 404, ..
            }) => {}
            Err(error) => return Err(error),
        }
    }
    Ok((access, repository))
}
async fn stream(api: HabblaudApi, generation: u64, sender: UnboundedSender<Event>) {
    let origin = api.origin().to_owned();
    let mut delay = std::time::Duration::from_secs(2);
    loop {
        match refresh(&api).await {
            Ok((access, repository)) => {
                let _ = sender.send(Event::Refreshed {
                    origin: origin.clone(),
                    generation,
                    access,
                    repository,
                });
                break;
            }
            Err(error) => {
                let code = status(&error);
                let _ = sender.send(Event::Connection {
                    origin: origin.clone(),
                    generation,
                    status: code,
                });
                if matches!(code, Some(401 | 403 | 404)) {
                    return;
                }
                let retry = match &error {
                    Error::Api { retry_after, .. } => zaplaud_core::api::retry_after(
                        retry_after.as_deref(),
                        std::time::SystemTime::now(),
                    ),
                    _ => std::time::Duration::ZERO,
                };
                tokio::time::sleep(delay.max(retry)).await;
                delay = (delay * 2).min(std::time::Duration::from_secs(30));
            }
        }
    }
    let mut stream = match api.resilient_stream("/api/stream") {
        Ok(stream) => stream,
        Err(error) => {
            let _ = sender.send(Event::Connection {
                origin,
                generation,
                status: status(&error),
            });
            return;
        }
    };
    while let Some(event) = stream.next_event().await {
        let event = match event {
            StreamEvent::Connected => Some(Event::Connection {
                origin: origin.clone(),
                generation,
                status: Some(200),
            }),
            StreamEvent::Failure { status, .. } => Some(Event::Connection {
                origin: origin.clone(),
                generation,
                status,
            }),
            StreamEvent::Data {
                event_type, data, ..
            } if event_type == "snapshot" => Some(Event::SnapshotData {
                origin: origin.clone(),
                generation,
                data,
            }),
            _ => None,
        };
        if let Some(event) = event
            && sender.send(event).is_err()
        {
            return;
        }
    }
}

pub async fn run(
    mut state: AppState,
    mut receiver: UnboundedReceiver<Event>,
    sender: UnboundedSender<Event>,
    weak: slint::Weak<AppWindow>,
    avatar_storage: Arc<dyn crate::avatars::AvatarStorage>,
    snapshots: tokio::sync::broadcast::Sender<Arc<crate::feature::AppSnapshot>>,
) {
    let mut login_task: Option<tokio::task::JoinHandle<()>> = None;
    let mut avatar_tasks = tokio::task::JoinSet::new();
    let avatar_limit = Arc::new(tokio::sync::Semaphore::new(4));
    let mut attempted = HashSet::new();
    match state.persistence.servers() {
        Ok(servers) => {
            for origin in servers {
                if let Ok(api) = HabblaudApi::with_persistence(
                    &origin,
                    Arc::new(zaplaud_store::SecureSessionStorage::new()),
                ) {
                    let _ = sender.send(Event::LoginFinished {
                        api,
                        result: Ok(()),
                    });
                }
            }
        }
        Err(_) => update_ui(&weak, |window| {
            window
                .global::<LoginState>()
                .set_message(strings::STORAGE_ERROR.into())
        }),
    }
    loop {
        let event = tokio::select! {
            event=receiver.recv()=>event,
            Some(_)=avatar_tasks.join_next(),if !avatar_tasks.is_empty()=>continue,
        };
        let Some(event) = event else { break };
        match event {
            Event::Shutdown => break,
            Event::Theme(dark) => {
                if state
                    .persistence
                    .set_theme(if dark { "dark" } else { "light" })
                    .is_ok()
                {
                    update_ui(&weak, move |window| {
                        window.global::<crate::Theme>().set_dark(dark)
                    });
                } else {
                    update_ui(&weak, |window| {
                        window
                            .global::<crate::ShellState>()
                            .set_message(strings::STORAGE_ERROR.into());
                    });
                }
            }
            Event::Login {
                origin,
                user,
                password,
            } => {
                let api = match HabblaudApi::with_persistence(
                    &origin,
                    Arc::new(zaplaud_store::SecureSessionStorage::new()),
                ) {
                    Ok(api) => api,
                    Err(error) => {
                        login_error(&weak, error_message(&error));
                        continue;
                    }
                };
                if let Some(task) = login_task.take() {
                    task.abort();
                }
                let now = now_millis();
                let deadline = match state.persistence.retry_until(api.origin()) {
                    Ok(deadline) => deadline,
                    Err(_) => {
                        login_error(&weak, strings::STORAGE_ERROR.into());
                        continue;
                    }
                };
                if deadline > now {
                    login_error(
                        &weak,
                        formatted(
                            "rate_limited",
                            &[&((deadline - now + 999) / 1000).to_string()],
                        ),
                    );
                    continue;
                }
                let send = sender.clone();
                login_task = Some(tokio::spawn(async move {
                    let result = async {
                        let result = api.login(&user, &password).await;
                        drop(password);
                        result?;
                        api.access().await?;
                        Ok(())
                    }
                    .await;
                    let _ = send.send(Event::LoginFinished { api, result });
                }));
            }
            Event::LoginFinished { api, result } => {
                let result = match result {
                    Ok(()) => state
                        .persistence
                        .add_server(api.origin())
                        .map_err(|_| strings::STORAGE_ERROR.to_owned()),
                    Err(error) => {
                        if let Error::Api {
                            status: 429,
                            retry_after,
                            ..
                        } = &error
                        {
                            let deadline = retry_deadline(retry_after.as_deref(), now_millis());
                            if state
                                .persistence
                                .set_retry_until(api.origin(), deadline)
                                .is_err()
                            {
                                login_error(&weak, strings::STORAGE_ERROR.into());
                                continue;
                            }
                        }
                        Err(error_message(&error))
                    }
                };
                if let Err(message) = result {
                    login_error(&weak, message);
                    continue;
                }
                if let Some(index) = state
                    .servers
                    .iter()
                    .position(|s| s.api.origin() == api.origin())
                {
                    state.servers.remove(index).task.abort();
                }
                let seen = state.persistence.seen(api.origin()).unwrap_or_default();
                let restore = state
                    .persistence
                    .last_session()
                    .unwrap_or_default()
                    .filter(|key| key.origin == api.origin());
                let reading = ReadingController::new(api.origin().into(), seen, restore);
                state.next_generation += 1;
                let generation = state.next_generation;
                let task = tokio::spawn(stream(api.clone(), generation, sender.clone()));
                state.servers.push(ServerState {
                    api,
                    reading,
                    generation,
                    task,
                });
                update_ui(&weak, |window| {
                    let login = window.global::<LoginState>();
                    login.set_busy(false);
                    login.set_visible(false);
                    login.set_message("".into());
                });
            }
            Event::Refreshed {
                origin,
                generation,
                access,
                repository,
            } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                {
                    let reading = &mut server.reading;
                    reading.repository = repository;
                    reading.state.terminal_allowed = access.terminal;
                    reading.state.messages_allowed = access.messages;
                    reading.state.message_guard = access.message_session_guard;
                    reading.state.permissions_allowed = access.permissions;
                }
            }
            Event::SnapshotData {
                origin,
                generation,
                data,
            } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                {
                    if server.reading.snapshot_data(&data, now_millis()).is_err() {
                        server.reading.failure(Some(-1));
                    }
                    let _ = state.persistence.save_seen(&origin, &server.reading.seen);
                }
            }
            Event::Connection {
                origin,
                generation,
                status,
            } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                {
                    if status == Some(200) {
                        server.reading.connected();
                    } else {
                        server.reading.failure(status);
                    }
                }
            }
            Event::Unauthorized(origin) => {
                if let Some(server) = state.servers.iter_mut().find(|s| s.api.origin() == origin) {
                    server.reading.needs_login = true;
                }
            }
            Event::Filter {
                origin,
                query,
                recent,
            } => {
                state.filter = (!origin.is_empty()).then_some(origin);
                state.query = query;
                state.recent = recent;
            }
            Event::Open(key) => {
                if let Ok(key) = SessionKey::deserialize(&key) {
                    for server in &mut state.servers {
                        if server.api.origin() == key.origin {
                            if let Some(item) = find_session(&server.reading, &key) {
                                if !item.sub {
                                    let _ = state.persistence.set_last_session(Some(&key));
                                }
                                server.reading.open(item);
                            }
                        } else {
                            server.reading.close();
                        }
                    }
                }
            }
            Event::Close => {
                for server in &mut state.servers {
                    server.reading.close();
                }
                let _ = state.persistence.set_last_session(None);
            }
            Event::Logout(origin) => {
                if let Some(index) = state.servers.iter().position(|s| s.api.origin() == origin) {
                    let api = state.servers[index].api.clone();
                    let send = sender.clone();
                    tokio::spawn(async move {
                        let revoked = api.logout().await.is_ok();
                        let _ = send.send(Event::LogoutFinished { origin, revoked });
                    });
                }
            }
            Event::LogoutFinished { origin, revoked } => {
                if state.persistence.remove_server(&origin).is_ok() {
                    if let Some(index) = state.servers.iter().position(|s| s.api.origin() == origin)
                    {
                        state.servers.remove(index).task.abort();
                    }
                    if state.filter.as_deref() == Some(&origin) {
                        state.filter = None;
                    }
                    let empty = state.servers.is_empty();
                    update_ui(&weak, move |window| {
                        window.global::<LoginState>().set_visible(empty);
                        let shell = window.global::<crate::ShellState>();
                        shell.set_logout_busy(false);
                        shell.set_logout_origin("".into());
                        if !revoked {
                            shell.set_message(strings::LOGOUT_UNCONFIRMED.into());
                        }
                    });
                } else {
                    update_ui(&weak, |window| {
                        let shell = window.global::<crate::ShellState>();
                        shell.set_logout_busy(false);
                        shell.set_message(strings::STORAGE_ERROR.into());
                    });
                }
            }
            Event::Avatar(key) => {
                if attempted.contains(&key) {
                    continue;
                }
                let Some(session_key) = SessionKey::deserialize(&key).ok() else {
                    continue;
                };
                let Some(server) = state
                    .servers
                    .iter()
                    .find(|s| s.api.origin() == session_key.origin)
                else {
                    continue;
                };
                let Some(item) = find_session(&server.reading, &session_key) else {
                    continue;
                };
                let Some(avatar) = item.avatar else { continue };
                attempted.insert(key.clone());
                let generation = server.generation;
                let api = server.api.clone();
                let weak = weak.clone();
                let storage = avatar_storage.clone();
                let limit = avatar_limit.clone();
                let send = sender.clone();
                avatar_tasks.spawn(async move {
                    let Ok(_permit) = limit.acquire_owned().await else {
                        return;
                    };
                    let cache_key = crate::avatars::key(api.origin(), &avatar);
                    let png = match storage.read(&cache_key).ok().flatten() {
                        Some(bytes) => Some(bytes),
                        None => {
                            // A API valida Content-Type e limita bytes antes de chegar ao decoder.
                            match api.avatar_bytes(&avatar).await {
                                Ok(Some(bytes)) => {
                                    let _ = storage.save(&cache_key, &bytes);
                                    Some(bytes)
                                }
                                Err(Error::Unauthorized) => {
                                    let _ = send.send(Event::Connection {
                                        origin: api.origin().into(),
                                        generation,
                                        status: Some(401),
                                    });
                                    None
                                }
                                _ => None,
                            }
                        }
                    };
                    let response_key = key.clone();
                    if let Some(rgba) = png.as_deref().and_then(crate::avatars::decode) {
                        update_ui(&weak, move |window| {
                            list::apply_avatar(&window, &response_key, cache_key, rgba)
                        });
                    }
                    let _ = send.send(Event::AvatarFinished(key));
                });
                continue;
            }
            Event::AvatarFinished(key) => {
                attempted.remove(&key);
                continue;
            }
        }
        list::publish(&state, &weak);
        let _ = snapshots.send(Arc::new(crate::feature::AppSnapshot::from_state(&state)));
    }
    if let Some(task) = login_task {
        task.abort();
    }
    avatar_tasks.abort_all();
    for server in state.servers {
        let _ = state
            .persistence
            .save_seen(server.api.origin(), &server.reading.seen);
        server.task.abort();
    }
}
fn login_error(weak: &slint::Weak<AppWindow>, message: String) {
    update_ui(weak, move |window| {
        let login = window.global::<LoginState>();
        login.set_busy(false);
        login.set_message(message.into());
    });
}
fn retry_deadline(value: Option<&str>, now: i64) -> i64 {
    if let Some(seconds) = value.and_then(|value| value.parse::<i64>().ok()) {
        return now.saturating_add(seconds.max(0).saturating_mul(1000));
    }
    if let Some(date) = value.and_then(|value| httpdate::parse_http_date(value).ok()) {
        return date.duration_since(std::time::UNIX_EPOCH).map_or(now, |d| {
            (d.as_millis().min(i64::MAX as u128) as i64).max(now)
        });
    }
    now.saturating_add(60_000)
}
fn find_session(
    reading: &ReadingController,
    key: &SessionKey,
) -> Option<zaplaud_core::model::SessionItem> {
    reading
        .state
        .sessions
        .iter()
        .find(|s| s.key == *key)
        .cloned()
        .or_else(|| {
            reading
                .state
                .sessions
                .iter()
                .flat_map(|s| {
                    s.subagents
                        .iter()
                        .map(move |sub| zaplaud_core::model::sub_session(s, sub))
                })
                .find(|s| s.key == *key)
        })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn login_retry_deadline_matches_jvm_rules() {
        assert_eq!(retry_deadline(Some("-5"), 1000), 1000);
        assert_eq!(retry_deadline(Some("5"), 1000), 6000);
        assert_eq!(retry_deadline(Some("invalid"), 1000), 61000);
        assert_eq!(
            retry_deadline(Some("Sun, 06 Nov 1994 08:49:37 GMT"), 1000),
            784111777000
        );
        assert_eq!(retry_deadline(Some("9223372036854775807"), 1000), i64::MAX);
    }
}
