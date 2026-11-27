use crate::{
    AppWindow, LoginState, error_message, formatted, list,
    feature::AlertInput,
    state::{AppState, Event, ServerState, now_millis},
    strings, update_ui,
};
use slint::ComponentHandle;
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, Ordering},
    },
};
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
async fn refresh(
    api: &HabblaudApi,
    step: &AtomicU8,
) -> Result<(Access, SessionCollection, u8), Error> {
    let mut access = api.access().await?;
    let mut repository = SessionCollection::default();
    let step = step.load(Ordering::Relaxed);
    if access.terminal {
        match api.recent_sessions_data(step).await {
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
    Ok((access, repository, step))
}
#[derive(Default)]
struct LatestSnapshots {
    pending: Mutex<HashMap<String, (u64, String)>>,
    ready: tokio::sync::Notify,
}
impl LatestSnapshots {
    fn replace(&self, origin: String, generation: u64, data: String) {
        if let Ok(mut pending) = self.pending.lock() {
            pending.insert(origin, (generation, data));
            self.ready.notify_one();
        }
    }
    fn take(&self, origin: &str, generation: u64) -> Option<Event> {
        let mut pending = self.pending.lock().ok()?;
        if pending.get(origin)?.0 != generation {
            return None;
        }
        let (origin, (_, data)) = pending.remove_entry(origin)?;
        Some(Event::SnapshotData { origin, generation, data })
    }

    async fn next_after_commands(&self, receiver: &mut UnboundedReceiver<Event>) -> Option<Event> {
        tokio::select! {
            biased;
            event = receiver.recv() => event,
            snapshot = self.next() => Some(snapshot),
        }
    }
    async fn next(&self) -> Event {
        loop {
            let ready = self.ready.notified();
            if let Ok(mut pending) = self.pending.lock()
                && let Some(origin) = pending.keys().next().cloned()
                && let Some((generation, data)) = pending.remove(&origin)
            {
                return Event::SnapshotData {
                    origin,
                    generation,
                    data,
                };
            }
            ready.await;
        }
    }
}

async fn stream(
    api: HabblaudApi,
    generation: u64,
    sender: UnboundedSender<Event>,
    latest: Arc<LatestSnapshots>,
    recent_step: Arc<AtomicU8>,
) {
    let origin = api.origin().to_owned();
    let mut delay = std::time::Duration::from_secs(2);
    loop {
        match refresh(&api, &recent_step).await {
            Ok((access, repository, recent_step)) => {
                let _ = sender.send(Event::Refreshed {
                    origin: origin.clone(),
                    generation,
                    access,
                    repository,
                    recent_step,
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
    let mut first_snapshot = true;
    while let Some(event) = stream.next_event().await {
        let event = match event {
            StreamEvent::Connected => {
                first_snapshot = true;
                Some(Event::Connection {
                    origin: origin.clone(),
                    generation,
                    status: Some(200),
                })
            }
            StreamEvent::Failure { status, .. } => Some(Event::Connection {
                origin: origin.clone(),
                generation,
                status,
            }),
            StreamEvent::Data {
                event_type, data, ..
            } if event_type == "snapshot" => {
                if std::mem::take(&mut first_snapshot) {
                    if sender.send(Event::SnapshotData { origin: origin.clone(), generation, data }).is_err() {
                        return;
                    }
                    continue;
                }
                latest.replace(origin.clone(), generation, data);
                None
            }
            StreamEvent::Data { event_type, data, first_connection } if event_type == "notice" => {
                Some(Event::NoticeData {
                    origin: origin.clone(),
                    generation,
                    event: StreamEvent::Data { event_type, data, first_connection },
                })
            }
            StreamEvent::Data { event_type, data, .. } if event_type == "marks" => {
                Some(Event::MarksData { origin: origin.clone(), generation, data })
            }
            _ => None,
        };
        // O servidor publica o snapshot que identifica a sessão antes dos notices.
        if event.is_some()
            && let Some(snapshot) = latest.take(&origin, generation)
            && sender.send(snapshot).is_err()
        {
            return;
        }
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
    alerts: UnboundedSender<AlertInput>,
) {
    let mut login_task: Option<tokio::task::JoinHandle<()>> = None;
    let mut avatar_tasks = tokio::task::JoinSet::new();
    let avatar_limit = Arc::new(tokio::sync::Semaphore::new(4));
    let mut attempted = crate::avatars::Requests::default();
    let latest = Arc::new(LatestSnapshots::default());
    let mut seen_writes = HashMap::new();
    let mut alert_selected = None;
    let recent_step = Arc::new(AtomicU8::new(state.recent_paging.step()));
    let mut recent_more_task = None;
    let mut alert_origins = Vec::<String>::new();
    match state.persistence.call(|store| store.servers()).await {
        Ok(servers) => {
            #[cfg(target_os = "android")]
            if servers.is_empty()
                && let Some(origin) = option_env!("ZAPLAUD_PROBE_SERVER")
            {
                let _ = sender.send(Event::Login {
                    origin: origin.into(),
                    user: option_env!("ZAPLAUD_PROBE_USER")
                        .unwrap_or("android-test")
                        .into(),
                    password: option_env!("ZAPLAUD_PROBE_PASSWORD")
                        .unwrap_or("android-test")
                        .into(),
                });
            }
            for origin in servers {
                if let Ok(api) = HabblaudApi::with_persistence(
                    &origin,
                    Arc::new(zaplaud_store::SecureSessionStorage::new()),
                )
                .await
                {
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
            event=latest.next_after_commands(&mut receiver)=>event,
            Some(_)=avatar_tasks.join_next(),if !avatar_tasks.is_empty()=>continue,
            _=tokio::time::sleep_until(next_seen_write(&seen_writes)), if !seen_writes.is_empty()=>{
                flush_seen(&mut state, &mut seen_writes, false).await;
                continue;
            },
        };
        let Some(event) = event else { break };
        match event {
            Event::Shutdown => break,
            Event::Theme(dark) => {
                if state
                    .persistence
                    .call(move |store| store.set_theme(if dark { "dark" } else { "light" }))
                    .await
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
                )
                .await
                {
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
                let retry_origin = api.origin().to_owned();
                let deadline = match state
                    .persistence
                    .call(move |store| store.retry_until(&retry_origin))
                    .await
                {
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
                    Ok(()) => {
                        let origin = api.origin().to_owned();
                        state
                            .persistence
                            .call(move |store| store.add_server(&origin))
                            .await
                            .map_err(|_| strings::STORAGE_ERROR.to_owned())
                    }
                    Err(error) => {
                        if let Error::Api {
                            status: 429,
                            retry_after,
                            ..
                        } = &error
                        {
                            let deadline = retry_deadline(retry_after.as_deref(), now_millis());
                            let origin = api.origin().to_owned();
                            if state
                                .persistence
                                .call(move |store| store.set_retry_until(&origin, deadline))
                                .await
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
                let origin = api.origin().to_owned();
                let marks = match state
                    .persistence
                    .call(move |store| store.session_marks(&origin))
                    .await
                {
                    Ok(marks) => marks,
                    Err(_) => {
                        login_error(&weak, strings::STORAGE_ERROR.into());
                        continue;
                    }
                };
                flush_seen(&mut state, &mut seen_writes, true).await;
                seen_writes.remove(api.origin());
                if let Some(index) = state
                    .servers
                    .iter()
                    .position(|s| s.api.origin() == api.origin())
                {
                    state.servers.remove(index);
                }
                let origin = api.origin().to_owned();
                let seen = state
                    .persistence
                    .call(move |store| store.seen(&origin))
                    .await
                    .unwrap_or_default();
                let restore = state
                    .persistence
                    .call(|store| store.last_session())
                    .await
                    .unwrap_or_default()
                    .filter(|key| key.origin == api.origin());
                let mut reading = ReadingController::new(api.origin().into(), seen, restore);
                reading.needs_login = !api.has_session_cookie();
                state.next_generation += 1;
                let generation = state.next_generation;
                let task = tokio::spawn(stream(
                    api.clone(),
                    generation,
                    sender.clone(),
                    latest.clone(),
                    recent_step.clone(),
                ));
                state.servers.push(ServerState {
                    api,
                    reading,
                    generation,
                    task,
                    recent_task: None,
                    recent_step: None,
                    marks,
                    remote_marks: HashMap::new(),
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
                recent_step: step,
            } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                {
                    let reading = &mut server.reading;
                    // A consulta inicial pode autorizar sem cookie, por exemplo em acesso local.
                    reading.needs_login = false;
                    if server.recent_step.is_some() {
                        // A ampliação pode terminar antes da consulta inicial.
                        reading.repository.cast = repository.cast;
                    } else {
                        server.recent_step = repository.recent_window.map(|_| step);
                        reading.repository = repository;
                    }
                    reading.repository.recent_stale |= server.recent_step.is_some()
                        && server.recent_step != Some(state.recent_paging.step());
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
                    let reading = &mut server.reading;
                    if reading.snapshot_data(&data, now_millis()).is_err() {
                        reading.failure(Some(-1));
                    } else if reading.repository.recent_stale
                        && reading.state.terminal_allowed
                        && !reading.needs_login
                        && server.recent_task.is_none()
                        && !state.recent_paging.loading()
                    {
                        let api = server.api.clone();
                        let send = sender.clone();
                        let step = state.recent_paging.step();
                        server.recent_task = Some(tokio::spawn(async move {
                            let result = api.recent_sessions_data(step).await;
                            let _ = send.send(Event::RecentRefreshed {
                                origin,
                                generation,
                                result,
                                step,
                            });
                        }));
                    }
                }
            }
            Event::NoticeData { origin, generation, event } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation && !s.reading.needs_login)
                {
                    let _ = server.reading.apply_stream_event(event, now_millis());
                    for update in server.reading.alerts.take_updates() {
                        let _ = alerts.send(AlertInput::Update(update));
                    }
                }
                continue;
            }
            Event::RecentRefreshed {
                origin,
                generation,
                result,
                step,
            } => {
                if state.recent_paging.loading() || step != state.recent_paging.step() {
                    continue;
                }
                let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                else {
                    continue;
                };
                server.recent_task = None;
                apply_recent(server, step, result);
            }
            Event::LoadMoreRecent => {
                if !state.recent || !state.recent_footer().can_load_more {
                    continue;
                }
                let Some(step) = state.recent_paging.start() else {
                    continue;
                };
                recent_step.store(step, Ordering::Relaxed);
                let requests = state
                    .servers
                    .iter_mut()
                    .map(|server| {
                        if let Some(task) = server.recent_task.take() {
                            task.abort();
                        }
                        let api = server.api.clone();
                        let generation = server.generation;
                        async move { (generation, api.recent_sessions_data(step).await) }
                    })
                    .collect::<Vec<_>>();
                let send = sender.clone();
                recent_more_task = Some(tokio::spawn(async move {
                    let results = futures_util::future::join_all(requests).await;
                    let _ = send.send(Event::RecentMoreFinished(results));
                }));
            }
            Event::RecentMoreFinished(results) => {
                recent_more_task = None;
                let step = state.recent_paging.step();
                let mut any_success = false;
                for (generation, result) in results {
                    if let Some(server) = state.servers.iter_mut().find(|s| s.generation == generation)
                    {
                        any_success |= apply_recent(server, step, result);
                    }
                }
                state.recent_paging.finish(any_success);
                recent_step.store(state.recent_paging.step(), Ordering::Relaxed);
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
                    let event = if status == Some(200) {
                        StreamEvent::Connected
                    } else {
                        StreamEvent::Failure {
                            status,
                            retry_after: None,
                        }
                    };
                    let _ = server.reading.apply_stream_event(event, now_millis());
                }
            }
            Event::Unauthorized(origin) => {
                if let Some(server) = state.servers.iter_mut().find(|s| s.api.origin() == origin) {
                    server.reading.needs_login = true;
                }
            }
            Event::ReadingPosition { origin, visible } => {
                if let Some(server) = state.servers.iter_mut().find(|s| s.api.origin() == origin) {
                    server.reading.set_reading_visible(visible);
                }
            }
            Event::Filter {
                origin,
                query,
                recent,
                favorites,
            } => {
                state.filter = (!origin.is_empty()).then_some(origin);
                state.query = query;
                state.recent = recent;
                state.favorites = favorites;
            }
            Event::ToggleMark { key, pin } => {
                let Ok(key) = SessionKey::deserialize(&key) else {
                    continue;
                };
                toggle_mark(&state, key, pin, &sender);
            }
            Event::MarksData { origin, generation, data } => {
                if let Some(server) = state
                    .servers
                    .iter_mut()
                    .find(|s| s.api.origin() == origin && s.generation == generation)
                    && let Some(marks) = parse_marks(&origin, &data)
                {
                    server.remote_marks = marks;
                    upload_local_marks(server, &sender);
                }
            }
            Event::MarksSent { key, pinned, favorite, toggled, result } => {
                if let Err(message) = marks_sent(&mut state, key, (pinned, favorite), toggled, result).await {
                    update_ui(&weak, move |window| {
                        window
                            .global::<crate::ShellState>()
                            .set_message(message.into());
                    });
                }
            }
            Event::Open(key) => {
                if let Ok(key) = SessionKey::deserialize(&key) {
                    for server in &mut state.servers {
                        if server.api.origin() == key.origin {
                            if let Some(item) =
                                find_session(&server.reading, &key).filter(|item| item.sub)
                            {
                                server.reading.open(item);
                            } else {
                                let remembered = key.clone();
                                let _ = state
                                    .persistence
                                    .call(move |store| store.set_last_session(Some(&remembered)))
                                    .await;
                                server.reading.open_key(key.clone());
                            }
                        } else {
                            server.reading.close();
                        }
                    }
                }
            }
            Event::Close | Event::CloseSession(_) => {
                let target = if let Event::CloseSession(key) = event {
                    let selected = state
                        .servers
                        .iter()
                        .find_map(|server| server.reading.state.selected.as_ref());
                    if selected.is_none_or(|item| item.key != key) {
                        continue;
                    }
                    Some(key)
                } else {
                    None
                };
                for server in &mut state.servers {
                    if target
                        .as_ref()
                        .is_none_or(|key| key.origin == server.api.origin())
                    {
                        server.reading.close();
                    }
                }
                let _ = state
                    .persistence
                    .call(|store| store.set_last_session(None))
                    .await;
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
                let stored = remove_logged_out(&mut state, &origin).await;
                seen_writes.remove(&origin);
                let empty = state.servers.is_empty();
                update_ui(&weak, move |window| {
                    window.global::<LoginState>().set_visible(empty);
                    let shell = window.global::<crate::ShellState>();
                    shell.set_logout_busy(false);
                    shell.set_logout_origin("".into());
                    if !stored {
                        shell.set_message(strings::STORAGE_ERROR.into());
                    } else if !revoked {
                        shell.set_message(strings::LOGOUT_UNCONFIRMED.into());
                    }
                });
            }
            Event::Avatar(key) => {
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
                let cache_key = crate::avatars::key(server.api.origin(), &avatar);
                if !attempted.start(&cache_key) {
                    continue;
                }
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
                    let disk = storage.clone();
                    let disk_key = cache_key.clone();
                    let cached = tokio::task::spawn_blocking(move || {
                        disk.read(&disk_key)
                            .ok()
                            .flatten()
                            .as_deref()
                            .and_then(crate::avatars::decode)
                    })
                    .await
                    .ok()
                    .flatten();
                    let rgba = match cached {
                        Some(rgba) => Some(rgba),
                        None => {
                            // A API valida Content-Type e limita bytes antes de chegar ao decoder.
                            match api.avatar_bytes(&avatar).await {
                                Ok(Some(bytes)) => {
                                    let disk_key = cache_key.clone();
                                    tokio::task::spawn_blocking(move || {
                                        crate::avatars::save_decoded(
                                            storage.as_ref(),
                                            &disk_key,
                                            &bytes,
                                        )
                                    })
                                    .await
                                    .ok()
                                    .flatten()
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
                    let failed = rgba.is_none();
                    let finished_key = cache_key.clone();
                    let response_key = key.clone();
                    if let Some(rgba) = rgba {
                        update_ui(&weak, move |window| {
                            list::apply_avatar(&window, &response_key, cache_key, rgba)
                        });
                    }
                    let _ = send.send(Event::AvatarFinished {
                        key: finished_key,
                        failed,
                    });
                });
                continue;
            }
            Event::AvatarFinished { key, failed } => {
                attempted.finish(key, failed);
                continue;
            }
        }
        schedule_seen(&state, &mut seen_writes);
        list::publish(&state, &weak);
        let selected = state
            .servers
            .iter()
            .find_map(|server| server.reading.state.selected.as_ref().map(|item| &item.key));
        let origins = || {
            state.servers.iter()
                .filter(|server| !server.reading.needs_login)
                .map(|server| server.api.origin())
        };
        if alert_selected.as_ref() != selected
            || alert_origins.iter().map(String::as_str).ne(origins())
        {
            alert_selected = selected.cloned();
            alert_origins = origins().map(str::to_owned).collect();
            let _ = alerts.send(AlertInput::Context {
                selected: alert_selected.clone(),
                origins: alert_origins.clone(),
            });
        }
        for update in state.servers.iter_mut().flat_map(|server| {
            let updates = server.reading.alerts.take_updates();
            (!server.reading.needs_login).then_some(updates).into_iter().flatten()
        }) {
            let _ = alerts.send(AlertInput::Update(update));
        }
        let _ = snapshots.send(Arc::new(crate::feature::AppSnapshot::from_state(&state)));
    }
    if let Some(task) = login_task {
        task.abort();
    }
    if let Some(task) = recent_more_task {
        task.abort();
    }
    avatar_tasks.abort_all();
    flush_seen(&mut state, &mut seen_writes, true).await;
    if seen_writes.values().any(|write| write.deadline.is_some()) {
        eprintln!("{}", strings::STORAGE_ERROR);
    }
    if crate::composer::flush().await.is_err() {
        eprintln!("{}", strings::STORAGE_ERROR);
    }
}
fn apply_recent(server: &mut ServerState, step: u8, result: Result<String, Error>) -> bool {
    let reading = &mut server.reading;
    match result {
        Ok(data) => {
            if reading.refresh_recent(Some(&data)).is_ok() {
                server.recent_step = Some(step);
                return true;
            }
        }
        Err(Error::Api { status: 403, .. }) => {
            let _ = reading.refresh_recent(None);
            server.recent_step = None;
        }
        Err(Error::Unauthorized) => reading.needs_login = true,
        Err(_) => {}
    }
    false
}

fn mark_id(key: &SessionKey) -> Option<String> {
    key.serialize()
        .ok()
        .map(|serialized| zaplaud_store::storage_hash(&serialized))
}

/// Lista do evento `marks`, pela mesma chave das marcas locais. `None` = dado ilegível: fica a lista anterior.
fn parse_marks(origin: &str, data: &str) -> Option<HashMap<String, zaplaud_store::SessionMarks>> {
    let serde_json::Value::Array(items) = serde_json::from_str(data).ok()? else {
        return None;
    };
    Some(
        items
            .iter()
            .filter_map(|item| {
                let text = |name: &str| item.get(name)?.as_str().map(str::to_owned);
                let flag = |name: &str| item.get(name).and_then(|v| v.as_bool()) == Some(true);
                let key = SessionKey {
                    origin: origin.into(),
                    provider: text("provider")?,
                    account: text("account")?,
                    session_id: text("sessionId")?,
                };
                let marks = zaplaud_store::SessionMarks {
                    pinned: flag("pinned"),
                    favorite: flag("favorite"),
                };
                Some((mark_id(&key)?, marks))
            })
            .collect(),
    )
}

fn send_marks(
    api: &HabblaudApi,
    sender: &UnboundedSender<Event>,
    key: SessionKey,
    (pinned, favorite): (Option<bool>, Option<bool>),
    toggled: bool,
) {
    let (api, sender) = (api.clone(), sender.clone());
    tokio::spawn(async move {
        let result = api
            .set_marks(&key, pinned, favorite)
            .await
            .map_err(|error| status(&error));
        let _ = sender.send(Event::MarksSent { key, pinned, favorite, toggled, result });
    });
}

fn toggle_mark(state: &AppState, key: SessionKey, pin: bool, sender: &UnboundedSender<Event>) {
    let Some(server) = state
        .servers
        .iter()
        .find(|server| server.api.origin() == key.origin)
    else {
        return;
    };
    if find_session(&server.reading, &key).is_none() {
        return;
    }
    let Some(id) = mark_id(&key) else {
        return;
    };
    let local = server.marks.get(&id).copied().unwrap_or_default();
    let marks = server.marks_of(&id);
    // Vai o campo tocado; o outro só se este aparelho ainda guarda a marca dele, que sobe junto.
    let patch = if pin {
        (Some(!marks.pinned), local.favorite.then_some(true))
    } else {
        (local.pinned.then_some(true), Some(!marks.favorite))
    };
    send_marks(&server.api, sender, key, patch, true);
}

/// Sobe as marcas que só este aparelho tem, somadas às do servidor. Só das sessões na lista: a chave local é um hash.
fn upload_local_marks(server: &ServerState, sender: &UnboundedSender<Event>) {
    for item in &server.reading.state.sessions {
        if let Some(local) = mark_id(&item.key).and_then(|id| server.marks.get(&id)) {
            let patch = (local.pinned.then_some(true), local.favorite.then_some(true));
            send_marks(&server.api, sender, item.key.clone(), patch, false);
        }
    }
}

/// Aplica a resposta do servidor. Gravada lá, a cópia local deixa de valer; `Err` = aviso a mostrar.
async fn marks_sent(
    state: &mut AppState,
    key: SessionKey,
    (pinned, favorite): (Option<bool>, Option<bool>),
    toggled: bool,
    result: Result<(), Option<i32>>,
) -> Result<(), &'static str> {
    let Some(server) = state
        .servers
        .iter_mut()
        .find(|server| server.api.origin() == key.origin)
    else {
        return Ok(());
    };
    let Some(id) = mark_id(&key) else {
        return Ok(());
    };
    let patched = |old: zaplaud_store::SessionMarks| zaplaud_store::SessionMarks {
        pinned: pinned.unwrap_or(old.pinned),
        favorite: favorite.unwrap_or(old.favorite),
    };
    let local = match result {
        Ok(()) => {
            let marks = patched(server.remote_marks.get(&id).copied().unwrap_or_default());
            if marks == zaplaud_store::SessionMarks::default() {
                server.remote_marks.remove(&id);
            } else {
                server.remote_marks.insert(id.clone(), marks);
            }
            zaplaud_store::SessionMarks::default()
        }
        // Servidor anterior à sincronização: a marca fica só neste aparelho, como antes.
        Err(Some(404)) if toggled => patched(server.marks_of(&id)),
        Err(Some(401)) => {
            server.reading.needs_login = true;
            return Ok(());
        }
        Err(_) if toggled => return Err(strings::NETWORK_ERROR),
        Err(_) => return Ok(()),
    };
    if server.marks.get(&id).copied().unwrap_or_default() == local {
        return Ok(());
    }
    state
        .persistence
        .call(move |store| store.set_session_marks(&key, local))
        .await
        .map_err(|_| strings::STORAGE_ERROR)?;
    if local == zaplaud_store::SessionMarks::default() {
        server.marks.remove(&id);
    } else {
        server.marks.insert(id, local);
    }
    Ok(())
}

#[derive(Default)]
struct SeenWrite {
    revision: u64,
    deadline: Option<tokio::time::Instant>,
}
fn schedule_seen(state: &AppState, writes: &mut HashMap<String, SeenWrite>) {
    for server in &state.servers {
        let pending = writes.entry(server.api.origin().into()).or_default();
        if pending.revision != server.reading.seen_revision {
            pending.revision = server.reading.seen_revision;
            pending.deadline =
                Some(tokio::time::Instant::now() + std::time::Duration::from_secs(2));
        }
    }
}
fn next_seen_write(writes: &HashMap<String, SeenWrite>) -> tokio::time::Instant {
    writes
        .values()
        .filter_map(|write| write.deadline)
        .min()
        .unwrap_or_else(|| tokio::time::Instant::now() + std::time::Duration::from_secs(86_400))
}
async fn flush_seen(state: &mut AppState, writes: &mut HashMap<String, SeenWrite>, all: bool) {
    for server in &state.servers {
        let Some(pending) = writes.get_mut(server.api.origin()) else {
            continue;
        };
        if pending
            .deadline
            .is_some_and(|deadline| all || deadline <= tokio::time::Instant::now())
        {
            let origin = server.api.origin().to_owned();
            let seen = server.reading.seen.clone();
            pending.deadline = if state
                .persistence
                .call(move |store| store.save_seen(&origin, &seen))
                .await
                .is_ok()
            {
                None
            } else {
                Some(tokio::time::Instant::now() + std::time::Duration::from_secs(2))
            };
        }
    }
}

async fn remove_logged_out(state: &mut AppState, origin: &str) -> bool {
    let removed_origin = origin.to_owned();
    let stored = state
        .persistence
        .call(move |store| store.remove_server(&removed_origin))
        .await
        .is_ok();
    if let Some(index) = state
        .servers
        .iter()
        .position(|server| server.api.origin() == origin)
    {
        state.servers.remove(index);
    }
    if state.filter.as_deref() == Some(origin) {
        state.filter = None;
    }
    stored
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
        return now.saturating_add(seconds.clamp(0, 86_400).saturating_mul(1000));
    }
    if let Some(date) = value.and_then(|value| httpdate::parse_http_date(value).ok()) {
        return date.duration_since(std::time::UNIX_EPOCH).map_or(now, |d| {
            (d.as_millis().min(i64::MAX as u128) as i64)
                .max(now)
                .min(now.saturating_add(86_400_000))
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
    #[tokio::test]
    async fn marks_sent_moves_local_marks_to_server_and_keeps_them_on_old_servers() {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("data");
        let store = zaplaud_store::LocalStateStore::with_dirs(zaplaud_store::DesktopDirs {
            data: data.clone(),
            cache: root.path().join("cache"),
        })
        .unwrap();
        let key = SessionKey {
            origin: "https://a".into(),
            provider: "omp".into(),
            account: "account".into(),
            session_id: "session".into(),
        };
        store.add_server(&key.origin).unwrap();
        let mut reading = ReadingController::new(key.origin.clone(), HashMap::new(), None);
        reading
            .state
            .sessions
            .push(zaplaud_core::model::SessionItem {
                key: key.clone(),
                ..Default::default()
            });
        let mut state = AppState::new(Box::new(store));
        state.servers.push(ServerState {
            api: HabblaudApi::new(&key.origin).unwrap(),
            reading,
            generation: 1,
            task: tokio::spawn(async {}),
            recent_task: None,
            recent_step: None,
            marks: HashMap::new(),
            remote_marks: HashMap::new(),
        });
        let id = zaplaud_store::storage_hash(&key.serialize().unwrap());
        let pinned = zaplaud_store::SessionMarks {
            pinned: true,
            favorite: false,
        };
        let pin = (Some(true), None);
        marks_sent(&mut state, key.clone(), pin, true, Err(Some(404)))
            .await
            .unwrap();
        assert_eq!(state.servers[0].marks[&id], pinned);
        assert_eq!(state.servers[0].marks_of(&id), pinned);
        assert!(
            marks_sent(&mut state, key.clone(), pin, true, Err(None))
                .await
                .is_err()
        );
        marks_sent(&mut state, key.clone(), pin, false, Ok(()))
            .await
            .unwrap();
        assert!(state.servers[0].marks.is_empty());
        assert_eq!(state.servers[0].remote_marks[&id], pinned);
        // Outro aparelho favoritou sem este saber: a resposta do toque daqui não apaga a marca dele.
        let both = zaplaud_store::SessionMarks {
            pinned: true,
            favorite: true,
        };
        state.servers[0].remote_marks.insert(id.clone(), both);
        marks_sent(&mut state, key.clone(), (Some(false), None), true, Ok(()))
            .await
            .unwrap();
        let favorite = zaplaud_store::SessionMarks {
            pinned: false,
            favorite: true,
        };
        assert_eq!(state.servers[0].remote_marks[&id], favorite);
        std::fs::write(data.join("local_state.json"), b"malformed").unwrap();
        assert!(
            marks_sent(&mut state, key, (Some(true), None), true, Err(Some(404)))
                .await
                .is_err()
        );
        assert_eq!(state.servers[0].marks_of(&id), favorite);
        state.servers.remove(0);
    }

    #[tokio::test]
    async fn snapshot_mailbox_keeps_only_latest_per_origin() {
        let latest = LatestSnapshots::default();
        for generation in 0..1000 {
            latest.replace("https://a".into(), generation, generation.to_string());
        }
        latest.replace("https://b".into(), 5, "b".into());
        assert_eq!(latest.pending.lock().unwrap().len(), 2);
        let mut received = HashMap::new();
        for _ in 0..2 {
            if let Event::SnapshotData {
                origin,
                generation,
                data,
            } = latest.next().await
            {
                received.insert(origin, (generation, data));
            }
        }
        assert_eq!(received["https://a"], (999, "999".into()));
        assert_eq!(received["https://b"], (5, "b".into()));
        assert!(latest.pending.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn logout_removes_ui_server_even_when_persistence_fails() {
        let root = tempfile::tempdir().unwrap();
        let data = root.path().join("data");
        let store = zaplaud_store::LocalStateStore::with_dirs(zaplaud_store::DesktopDirs {
            data: data.clone(),
            cache: root.path().join("cache"),
        })
        .unwrap();
        store.add_server("https://a").unwrap();
        let mut state = AppState::new(Box::new(store));
        state.filter = Some("https://a".into());
        state.servers.push(ServerState {
            api: HabblaudApi::new("https://a").unwrap(),
            reading: ReadingController::new("https://a".into(), HashMap::new(), None),
            generation: 1,
            task: tokio::spawn(async {}),
            recent_task: None,
            recent_step: None,
            marks: HashMap::new(),
            remote_marks: HashMap::new(),
        });
        std::fs::write(data.join("local_state.json"), b"malformed").unwrap();
        assert!(!remove_logged_out(&mut state, "https://a").await);
        assert!(state.servers.is_empty());
        assert!(state.filter.is_none());
    }

    #[tokio::test]
    async fn seen_writes_debounce_changes_and_flush_latest_on_shutdown() {
        let root = tempfile::tempdir().unwrap();
        let store = zaplaud_store::LocalStateStore::with_dirs(zaplaud_store::DesktopDirs {
            data: root.path().join("data"),
            cache: root.path().join("cache"),
        })
        .unwrap();
        store.add_server("https://a").unwrap();
        let mut state = AppState::new(Box::new(store));
        let mut reading = ReadingController::new("https://a".into(), HashMap::new(), None);
        reading.seen.insert("session".into(), 1);
        reading.seen_revision = 1;
        state.servers.push(ServerState {
            api: HabblaudApi::new("https://a").unwrap(),
            reading,
            generation: 1,
            task: tokio::spawn(async {}),
            recent_task: None,
            recent_step: None,
            marks: HashMap::new(),
            remote_marks: HashMap::new(),
        });
        let mut writes = HashMap::new();
        schedule_seen(&state, &mut writes);
        let deadline = writes["https://a"].deadline.unwrap();
        schedule_seen(&state, &mut writes);
        assert_eq!(writes["https://a"].deadline, Some(deadline));
        flush_seen(&mut state, &mut writes, false).await;
        assert!(
            state
                .persistence
                .call(|store| store.seen("https://a"))
                .await
                .unwrap()
                .is_empty()
        );
        state.servers[0].reading.seen.insert("session".into(), 2);
        state.servers[0].reading.seen_revision = 2;
        schedule_seen(&state, &mut writes);
        flush_seen(&mut state, &mut writes, true).await;
        assert_eq!(
            state
                .persistence
                .call(|store| store.seen("https://a"))
                .await
                .unwrap()["session"],
            2
        );
        assert!(writes["https://a"].deadline.is_none());
        schedule_seen(&state, &mut writes);
        assert!(writes["https://a"].deadline.is_none());
    }

    #[test]
    fn login_retry_deadline_clamps_seconds_and_dates_to_one_day() {
        assert_eq!(retry_deadline(Some("-5"), 1000), 1000);
        assert_eq!(retry_deadline(Some("5"), 1000), 6000);
        assert_eq!(retry_deadline(Some("invalid"), 1000), 61000);
        assert_eq!(
            retry_deadline(Some("Sun, 06 Nov 1994 08:49:37 GMT"), 1000),
            86_401_000
        );
        assert_eq!(
            retry_deadline(Some("9223372036854775807"), 1000),
            86_401_000
        );
    }
}
