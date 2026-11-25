use crate::{
    AppWindow, SettingsState,
    feature::{AlertInput, FeatureContext},
    state::Event,
    strings, update_ui,
};
use notify_rust::Notification;
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
use std::{
    cell::RefCell,
    collections::HashMap,
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
#[cfg(not(target_os = "macos"))]
use std::collections::HashSet;
use tokio::{
    sync::{Notify, mpsc},
    time::Instant,
};
use zaplaud_core::{
    controller::alerts::AlertUpdate,
    model::{AgentEvent, SessionKey},
    rules::alerts::{AlertPreferences, alert_excerpt, alert_reason, alert_subtitle, alert_text, batch_summary},
};

type PermissionHandler = Box<dyn Fn(String, String)>;
thread_local! { static PERMISSION_OPEN: RefCell<Option<PermissionHandler>> = RefCell::new(None); }
static FOCUSED: AtomicBool = AtomicBool::new(true);
static VISIBLE: AtomicBool = AtomicBool::new(true);
static CONVERSATION_VISIBLE: AtomicBool = AtomicBool::new(false);
static FOCUS_CHANGED: Notify = Notify::const_new();
static PREFERENCES_CHANGED: Notify = Notify::const_new();

fn track(flag: &AtomicBool, value: bool) {
    flag.store(value, Ordering::Relaxed);
    FOCUS_CHANGED.notify_one();
}

fn focused_session(selected: Option<&SessionKey>) -> Option<&SessionKey> {
    if FOCUSED.load(Ordering::Relaxed)
        && VISIBLE.load(Ordering::Relaxed)
        && CONVERSATION_VISIBLE.load(Ordering::Relaxed)
    {
        selected
    } else {
        None
    }
}

pub fn on_permission_open(handler: impl Fn(String, String) + 'static) {
    PERMISSION_OPEN.with(|slot| *slot.borrow_mut() = Some(Box::new(handler)));
}
pub fn raise(window: &AppWindow) {
    let _ = window.show();
    window.window().with_winit_window(|native| {
        native.set_minimized(false);
        native.focus_window();
    });
}
pub fn setup(
    window: &AppWindow,
    context: &FeatureContext,
    mut input: mpsc::UnboundedReceiver<AlertInput>,
) {
    #[cfg(target_os = "macos")]
    if let Err(error) = notify_rust::set_application("br.com.xlinx.zaplaud") {
        eprintln!("notificação: {error}");
    }
    crate::window_state::on_focus(|value| track(&FOCUSED, value));
    crate::window_state::on_visibility(|value| track(&VISIBLE, value));
    window.on_conversation_visibility_changed(|value| track(&CONVERSATION_VISIBLE, value));
    window
        .global::<SettingsState>()
        .on_notification_preferences_changed(|| PREFERENCES_CHANGED.notify_one());
    let weak = window.as_weak();
    let commands = context.commands.clone();
    context.runtime.spawn(async move {
        let mut prefs = load_preferences().await.unwrap_or_default();
        let mut selected = None;
        let mut multiple_servers = false;
        let mut pending: HashMap<SessionKey, (AgentEvent, bool)> = HashMap::new();
        let mut deadline = None;
        let mut native = Native::new(weak.clone(), commands.clone());
        loop {
            tokio::select! {
                biased;
                _ = PREFERENCES_CHANGED.notified() => {
                    if let Some(current) = load_preferences().await {
                        prefs = current;
                    }
                    pending.retain(|_, (event, _)| prefs.allows(event, focused_session(selected.as_ref())));
                    if !prefs.enabled {
                        native.retain_origins(&[]).await;
                    }
                }
                _ = FOCUS_CHANGED.notified() => {
                    if let Some(key) = focused_session(selected.as_ref()) {
                        pending.remove(key);
                        native.withdraw(key).await;
                    }
                }
                message = input.recv() => match message {
                    Some(AlertInput::Context { selected: next, origins }) => {
                        if next != selected && let Some(key) = &next {
                            pending.remove(key);
                            native.withdraw(key).await;
                        }
                        multiple_servers = origins.len() > 1;
                        pending.retain(|key, _| origins.contains(&key.origin));
                        native.retain_origins(&origins).await;
                        selected = next;
                    }
                    Some(AlertInput::Update(AlertUpdate::Withdraw(key))) => {
                        pending.remove(&key);
                        native.withdraw(&key).await;
                    }
                    Some(AlertInput::Update(update)) => {
                        let (event, fallback) = match update {
                            AlertUpdate::Fallback(event) => (event, true),
                            AlertUpdate::Notice(event) => (event, false),
                            AlertUpdate::Withdraw(_) => unreachable!(),
                        };
                        let key = &event.session().key;
                        // O notice chega depois do snapshot do mesmo lote e substitui o diff.
                        if !fallback && pending.get(key).is_some_and(|(old, tentative)| {
                            *tentative && old.is_wait() == event.is_wait()
                        }) {
                            pending.remove(key);
                        }
                        if prefs.allows(&event, focused_session(selected.as_ref())) {
                            pending.insert(key.clone(), (event, fallback));
                            deadline.get_or_insert_with(|| Instant::now() + Duration::from_secs(3));
                        }
                    }
                    None => break,
                },
                _ = tokio::time::sleep_until(deadline.unwrap_or_else(Instant::now)), if deadline.is_some() => {
                    pending.retain(|_, (event, _)| prefs.allows(event, focused_session(selected.as_ref())));
                    if batch_summary(pending.len()) {
                        native.summarize(pending.drain().map(|(key, _)| key)).await;
                    } else {
                        for (key, (event, _)) in pending.drain() {
                            let session = event.session();
                            let title = session.character_name.as_deref().unwrap_or(&session.title);
                            let text = strings::lookup(alert_text(&event)).expect("recurso da notificação");
                            let mut body = alert_excerpt(&event).unwrap_or(text).to_owned();
                            if let Some(reason) = alert_reason(&event) {
                                body.push_str(": ");
                                body.push_str(&reason);
                            }
                            if let Some(subtitle) = alert_subtitle(&event, multiple_servers) {
                                body.push_str(" · ");
                                body.push_str(&subtitle);
                            }
                            if let Ok(serialized) = key.serialize() {
                                let notification = notification(title, &body);
                                #[cfg(not(target_os = "macos"))]
                                let notification = {
                                    use std::hash::{DefaultHasher, Hash, Hasher};
                                    let mut hash = DefaultHasher::new();
                                    serialized.hash(&mut hash);
                                    let mut notification = notification;
                                    notification.id((hash.finish() as u32).max(1));
                                    notification
                                };
                                let target = (serialized, event.permission_id().map(str::to_owned));
                                let action = open(weak.clone(), commands.clone(), Some(target));
                                native.show_session(notification, key, action).await;
                            }
                        }
                    }
                    deadline = None;
                }
            }
            if pending.is_empty() {
                deadline = None;
            }
        }
    });
}

async fn load_preferences() -> Option<AlertPreferences> {
    tokio::task::spawn_blocking(|| {
        let store = zaplaud_store::LocalStateStore::new()?;
        let (wait, done) = store.notify_events()?;
        Ok::<_, zaplaud_store::StoreError>(AlertPreferences {
            enabled: store.notifications_enabled()?,
            wait,
            done,
            min_turn: store.notify_min_turn()?,
        })
    })
    .await
    .ok()?
    .ok()
}

fn notification(title: &str, body: &str) -> Notification {
    let mut notification = Notification::new();
    notification
        .appname(strings::APP_NAME)
        .summary(title)
        .body(body)
        .icon("zaplaud");
    notification
}

fn summary_notification(count: usize) -> Notification {
    let text = strings::format_plural("notify_batch", count as i64, &[&count.to_string()])
        .expect("recurso notify_batch");
    notification(&text, "")
}

type AppCommands = mpsc::UnboundedSender<Event>;

fn open(
    weak: slint::Weak<AppWindow>,
    commands: AppCommands,
    target: Option<(String, Option<String>)>,
) -> impl FnOnce() + Send + 'static {
    move || {
        let _ = commands.send(match &target {
            Some((key, _)) => Event::Open(key.clone()),
            None => Event::Close,
        });
        update_ui(&weak, move |window| {
            raise(&window);
            window.global::<SettingsState>().invoke_close();
            if let Some((key, Some(request_id))) = target {
                PERMISSION_OPEN.with(|slot| {
                    if let Some(handler) = slot.borrow().as_ref() {
                        handler(key, request_id);
                    }
                });
            }
        });
    }
}

#[cfg(not(target_os = "macos"))]
struct Native {
    sessions: HashMap<SessionKey, Shown>,
    summary: Option<Shown>,
    summary_members: HashSet<SessionKey>,
    weak: slint::Weak<AppWindow>,
    commands: AppCommands,
}

#[cfg(not(target_os = "macos"))]
struct Shown {
    handle: std::sync::Arc<notify_rust::NotificationHandle>,
    listener: tokio::task::JoinHandle<()>,
}

#[cfg(not(target_os = "macos"))]
impl Shown {
    async fn close(self) {
        let active = !self.listener.is_finished();
        self.listener.abort();
        if active {
            self.handle.close_async().await;
        }
    }
}

#[cfg(not(target_os = "macos"))]
impl Native {
    fn new(weak: slint::Weak<AppWindow>, commands: AppCommands) -> Self {
        Self { sessions: HashMap::new(), summary: None, summary_members: HashSet::new(), weak, commands }
    }

    fn forget_dismissed_summary(&mut self) {
        if self.summary.as_ref().is_some_and(|shown| shown.listener.is_finished()) {
            self.summary = None;
            self.summary_members.clear();
        }
    }

    async fn refresh_summary(&mut self) {
        if self.summary_members.is_empty() {
            if let Some(summary) = self.summary.take() {
                summary.close().await;
            }
        } else {
            let click = open(self.weak.clone(), self.commands.clone(), None);
            self.show(summary_notification(self.summary_members.len()), None, click).await;
        }
    }

    async fn withdraw(&mut self, key: &SessionKey) {
        if let Some(shown) = self.sessions.remove(key) {
            shown.close().await;
        }
        self.forget_dismissed_summary();
        if self.summary_members.remove(key) {
            self.refresh_summary().await;
        }
    }

    async fn retain_origins(&mut self, origins: &[String]) {
        for (_, shown) in self.sessions.extract_if(|key, shown| {
            !origins.contains(&key.origin) || shown.listener.is_finished()
        }) {
            shown.close().await;
        }
        self.forget_dismissed_summary();
        let before = self.summary_members.len();
        self.summary_members.retain(|key| origins.contains(&key.origin));
        if self.summary_members.len() != before {
            self.refresh_summary().await;
        }
    }

    async fn summarize(&mut self, members: impl Iterator<Item = SessionKey>) {
        self.forget_dismissed_summary();
        for key in members {
            if let Some(shown) = self.sessions.remove(&key) {
                shown.close().await;
            }
            self.summary_members.insert(key);
        }
        self.refresh_summary().await;
    }

    async fn show_session(
        &mut self,
        notification: Notification,
        key: SessionKey,
        click: impl FnOnce() + Send + 'static,
    ) {
        self.forget_dismissed_summary();
        if self.summary_members.remove(&key) {
            self.refresh_summary().await;
        }
        self.show(notification, Some(key), click).await;
    }

    async fn show(
        &mut self,
        mut notification: Notification,
        key: Option<SessionKey>,
        click: impl FnOnce() + Send + 'static,
    ) {
        self.sessions.retain(|_, shown| !shown.listener.is_finished());
        let previous = match &key {
            Some(key) => self.sessions.get(key),
            None => self.summary.as_ref().filter(|shown| !shown.listener.is_finished()),
        };
        if let Some(previous) = previous {
            // O daemon XDG pode atribuir outro ID na primeira entrega.
            notification.id(previous.handle.id());
        }
        notification.action("default", strings::FILES_OPEN);
        match notification.show_async().await {
            Ok(handle) => {
                let delivered_id = handle.id();
                let handle = std::sync::Arc::new(handle);
                let listener_handle = handle.clone();
                let listener = tokio::spawn(async move {
                    use notify_rust::NotificationResponse::{Action, Default};
                    listener_handle.wait_for_action_async(|response| {
                        if matches!(response, Default)
                            || matches!(response, Action(action) if action == "default")
                        {
                            click();
                        }
                    }).await;
                });
                let shown = Shown { handle, listener };
                let previous = match key {
                    Some(key) => self.sessions.insert(key, shown),
                    None => self.summary.replace(shown),
                };
                if let Some(previous) = previous {
                    if previous.handle.id() == delivered_id {
                        previous.listener.abort();
                    } else {
                        previous.close().await;
                    }
                }
            }
            Err(error) => eprintln!("notificação: {error}"),
        }
    }
}

// shortcut: NSUserNotificationCenter não substitui nem retira por ID; migrar para UNUserNotificationCenter.
#[cfg(target_os = "macos")]
struct Native {
    weak: slint::Weak<AppWindow>,
    commands: AppCommands,
}

#[cfg(target_os = "macos")]
impl Native {
    fn new(weak: slint::Weak<AppWindow>, commands: AppCommands) -> Self {
        Self { weak, commands }
    }

    async fn withdraw(&mut self, _: &SessionKey) {}
    async fn retain_origins(&mut self, _: &[String]) {}
    async fn summarize(&mut self, members: impl Iterator<Item = SessionKey>) {
        let click = open(self.weak.clone(), self.commands.clone(), None);
        self.show(summary_notification(members.count()), click);
    }

    async fn show_session(
        &mut self,
        notification: Notification,
        _: SessionKey,
        click: impl FnOnce() + Send + 'static,
    ) {
        self.show(notification, click);
    }

    fn show(
        &mut self,
        mut notification: Notification,
        click: impl FnOnce() + Send + 'static,
    ) {
        static WAITERS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(6);
        let Ok(slot) = WAITERS.try_acquire() else {
            // O Drop de NotificationHandle entrega sem bloquear pelo clique.
            drop(notification.show());
            return;
        };
        notification.action("open", strings::FILES_OPEN);
        // Cocoa entrega o clique pela sua thread principal já ativa.
        let _ = std::thread::Builder::new().name("zaplaud-notification".into()).spawn(move || {
            let _slot = slot;
            match notification.show() {
                Ok(handle) => handle.wait_for_action(|action| {
                    if action == "open" || action == "default" {
                        click();
                    }
                }),
                Err(error) => eprintln!("notificação: {error}"),
            }
        });
    }
}
