use crate::{AppWindow, feature::FeatureContext, state::Event, strings, update_ui};
use slint::{ComponentHandle, winit_030::WinitWindowAccessor};
use std::{
    cell::RefCell,
    sync::atomic::{AtomicBool, Ordering},
};
use zaplaud_core::{
    controller::alerts::AlertController,
    model::AgentEvent,
    rules::alerts::{alert_subtitle, alert_text},
};

type PermissionHandler = Box<dyn Fn(String)>;
thread_local! { static PERMISSION_OPEN: RefCell<Option<PermissionHandler>> = RefCell::new(None); }
static FOCUSED: AtomicBool = AtomicBool::new(true);
pub fn focused(value: bool) {
    FOCUSED.store(value, Ordering::Relaxed);
}
pub fn on_permission_open(handler: impl Fn(String) + 'static) {
    PERMISSION_OPEN.with(|slot| *slot.borrow_mut() = Some(Box::new(handler)));
}
pub fn raise(window: &AppWindow) {
    let _ = window.show();
    window.window().with_winit_window(|native| {
        native.set_minimized(false);
        native.focus_window();
    });
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    crate::window_state::on_focus(focused);
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let commands = context.commands.clone();
    context.runtime.spawn(async move {
        let store = match tokio::task::spawn_blocking(zaplaud_store::LocalStateStore::new).await {
            Ok(Ok(store)) => std::sync::Arc::new(store),
            Ok(Err(error)) => {
                eprintln!("alertas: {error}");
                return;
            }
            Err(error) => {
                eprintln!("alertas: {error}");
                return;
            }
        };
        let mut revisions = std::collections::HashMap::<String, u64>::new();
        let mut controller = AlertController::default();
        loop {
            let snapshot = match snapshots.recv().await {
                Ok(value) => value,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            };
            let preferences = store.clone();
            let Ok((enabled, (permission, done))) = tokio::task::spawn_blocking(move || {
                (
                    preferences.notifications_enabled().unwrap_or(false),
                    preferences.notify_events().unwrap_or((false, false)),
                )
            }).await else { continue };
            let origins: Vec<&str> = snapshot.servers.iter().map(|s| s.api.origin()).collect();
            controller.retain_origins(&origins);
            revisions.retain(|origin, _| origins.contains(&origin.as_str()));
            for server in &snapshot.servers {
                if server.snapshot_revision == 0
                    || revisions.get(server.api.origin()) == Some(&server.snapshot_revision)
                {
                    continue;
                }
                revisions.insert(server.api.origin().to_owned(), server.snapshot_revision);
                if server.needs_login || server.reading.connection != "connected" {
                    continue;
                }
                for event in controller.snapshot(
                    server.api.origin(),
                    &server.reading.sessions,
                    FOCUSED.load(Ordering::Relaxed),
                    enabled,
                    permission,
                    done,
                ) {
                    let text = match alert_text(&event) {
                        "notify_question" => strings::NOTIFY_QUESTION,
                        "notify_permission" => strings::NOTIFY_PERMISSION,
                        _ => strings::NOTIFY_DONE,
                    }
                    .to_owned();
                    let subtitle =
                        alert_subtitle(&event, snapshot.servers.len() > 1).unwrap_or_default();
                    let (AgentEvent::Permission(session) | AgentEvent::TurnDone(session)) = &event;
                    let summary = session
                        .character_name
                        .as_ref()
                        .unwrap_or(&session.title)
                        .clone();
                    let body = if subtitle.is_empty() {
                        text
                    } else {
                        format!("{text} · {subtitle}")
                    };
                    let key = match serde_json::to_string(&session.key) {
                        Ok(key) => key,
                        Err(_) => continue,
                    };
                    let permission = matches!(event, AgentEvent::Permission(_));
                    let weak = weak.clone();
                    let commands = commands.clone();
                    tokio::spawn(async move {
                        let mut notification = notify_rust::Notification::new();
                        notification
                            .appname(strings::APP_NAME)
                            .summary(&summary)
                            .body(&body)
                            .icon("zaplaud")
                            .action("open", "Abrir");
                        #[cfg(target_os = "macos")]
                        {
                            // Cocoa entrega o clique pela sua thread principal já ativa.
                            let _ = std::thread::Builder::new().name("zaplaud-notification".into()).spawn(move || {
                                match notification.show() {
                                    Ok(handle) => handle.wait_for_action(|action| {
                                        if action == "open" || action == "default" {
                                            let _ = commands.send(Event::Open(key.clone()));
                                            update_ui(&weak, move |window| {
                                                raise(&window);
                                                if permission { PERMISSION_OPEN.with(|slot| { if let Some(handler) = slot.borrow().as_ref() { handler(key); } }); }
                                            });
                                        }
                                    }),
                                    Err(error) => eprintln!("notificação: {error}"),
                                }
                            });
                        }
                        #[cfg(not(target_os = "macos"))]
                        match notification.show_async().await {
                            Ok(handle) => {
                              handle.wait_for_action_async(|response| {
                                if matches!(response, notify_rust::NotificationResponse::Action(action) if action == "open") {
                                    let _ = commands.send(Event::Open(key.clone()));
                                    update_ui(&weak, move |window| {
                                        raise(&window);
                                        if permission {
                                            PERMISSION_OPEN.with(|slot| {
                                                if let Some(handler) = slot.borrow().as_ref() {
                                                    handler(key);
                                                }
                                            });
                                        }
                                    });
                                }
                              }).await;
                            },
                            Err(error) => eprintln!("notificação: {error}"),
                        }
                    });
                }
            }
        }
    });
}
