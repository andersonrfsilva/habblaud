use slint::ComponentHandle;
use tokio::sync::mpsc;
use zaplaud_core::error::Error;

pub mod alerts;
mod app;
mod aside;
mod avatars;
mod cast;
mod clock;
mod composer;
mod conversation;
mod conversation_view;
pub mod feature;
mod instance;
mod list;
mod login;
mod permission;
mod settings;
pub mod state;
mod tray;
pub mod window_state;

slint::include_modules!();
pub mod strings {
    include!(concat!(env!("OUT_DIR"), "/strings.rs"));
}
pub mod native_strings {
    include!(concat!(env!("OUT_DIR"), "/native_strings.rs"));
}

type Feature = fn(&AppWindow, &feature::FeatureContext);
const FEATURES: &[Feature] = &[
    login::setup,
    list::setup,
    conversation::setup,
    conversation_view::setup,
    cast::setup,
    aside::setup,
    composer::setup,
    permission::setup,
    alerts::setup,
    tray::setup,
    window_state::setup,
    settings::setup,
];

fn formatted(key: &str, args: &[&str]) -> String {
    strings::format(key, args).unwrap_or_else(|| strings::NETWORK_ERROR.into())
}
fn error_message(error: &Error) -> String {
    match error {
        Error::InvalidOrigin => strings::INVALID_URL.into(),
        Error::Redirect(_) => strings::REDIRECT_ERROR.into(),
        Error::LoginRefused
        | Error::SessionEnded
        | Error::Unauthorized
        | Error::Api { status: 401, .. } => strings::LOGIN_ERROR.into(),
        Error::Api { status: 403, .. } => strings::FORBIDDEN.into(),
        Error::Api {
            status: 429,
            retry_after,
            ..
        } => formatted("rate_limited", &[retry_after.as_deref().unwrap_or("60")]),
        Error::Api { status, .. } => formatted("http_error", &[&status.to_string()]),
        Error::EventTooLarge => strings::READING_OVERSIZE.into(),
        _ => strings::NETWORK_ERROR.into(),
    }
}
// Só dados Send atravessam a thread; modelos Slint nascem no event loop.
fn update_ui(weak: &slint::Weak<AppWindow>, apply: impl FnOnce(AppWindow) + Send + 'static) {
    let weak = weak.clone();
    let _ = slint::invoke_from_event_loop(move || {
        if let Some(window) = weak.upgrade() {
            apply(window);
        }
    });
}
fn labels(window: &AppWindow) {
    let labels = window.global::<Labels>();
    labels.set_app_name(strings::APP_NAME.into());
    labels.set_connection(strings::CONNECTION.into());
    labels.set_server_url(strings::SERVER_URL.into());
    labels.set_user(strings::USER.into());
    labels.set_password(strings::PASSWORD.into());
    labels.set_login(strings::LOGIN.into());
    labels.set_select_conversation(strings::SELECT_CONVERSATION.into());
    labels.set_http_login_title(strings::HTTP_LOGIN_TITLE.into());
    labels.set_confirm(strings::CONFIRM.into());
    labels.set_cancel(strings::CANCEL.into());
    labels.set_settings(strings::SETTINGS.into());
    labels.set_add_server(strings::ADD_SERVER.into());
    labels.set_open_sessions(strings::OPEN_SESSIONS.into());
    labels.set_recent_sessions(strings::RECENT_SESSIONS.into());
    labels.set_search_sessions(strings::SEARCH_SESSIONS.into());
    labels.set_search_close(strings::SEARCH_CLOSE.into());
    labels.set_sessions_empty(strings::SESSIONS_EMPTY.into());
    labels.set_search_no_match(strings::SEARCH_NO_MATCH.into());
    labels.set_search_clear(strings::SEARCH_CLEAR.into());
    labels.set_relogin(strings::RELOGIN.into());
    labels.set_filter_all(strings::FILTER_ALL.into());
    labels.set_back_sessions(strings::BACK_SESSIONS.into());
    labels.set_menu(strings::MENU.into());
}
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let Some(instance) = instance::Instance::acquire(&instance::Instance::runtime_path()?)? else {
        return Ok(());
    };
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .with_winit_window_attributes_hook(window_state::attributes)
        .select()?;
    let window = AppWindow::new()?;
    labels(&window);
    clock::setup(&window);
    window
        .global::<Theme>()
        .set_dark(std::env::var("ZAPLAUD_THEME").is_ok_and(|theme| theme == "dark"));
    window.show()?;
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let (sender, receiver) = mpsc::unbounded_channel();
    let (snapshots, _) = tokio::sync::broadcast::channel(16);
    let theme_sender = sender.clone();
    window.global::<AppEvents>().on_theme(move |dark| {
        let _ = theme_sender.send(state::Event::Theme(dark));
    });
    let context = feature::FeatureContext {
        commands: sender.clone(),
        snapshots: snapshots.clone(),
        runtime: runtime.handle().clone(),
    };
    instance.listener.set_nonblocking(true)?;
    let listener = instance.listener.try_clone()?;
    let weak = window.as_weak();
    context.runtime.spawn(async move {
        let listener = match tokio::net::UnixListener::from_std(listener) {
            Ok(listener) => listener,
            Err(error) => {
                eprintln!("instância: {error}");
                return;
            }
        };
        while let Ok((_, _)) = listener.accept().await {
            update_ui(&weak, |window| alerts::raise(&window));
        }
    });
    for setup in FEATURES {
        setup(&window, &context);
    }
    let weak = window.as_weak();
    window_state::on_visibility(move |visible| {
        if let Some(window) = weak.upgrade() {
            window.global::<ListState>().set_app_visible(visible);
            window.global::<Clock>().set_visible(visible);
        }
    });
    let weak = window.as_weak();
    window_state::on_focus(move |focused| {
        if let Some(window) = weak.upgrade() {
            window.global::<Clock>().set_focused(focused);
        }
    });
    let weak = window.as_weak();
    alerts::on_permission_open(move |key| {
        if let Some(window) = weak.upgrade() {
            window
                .global::<PermissionState>()
                .invoke_open_session(key.into());
        }
    });
    let weak = window.as_weak();
    window.global::<AppEvents>().on_logout(move |origin| {
        if let Some(window) = weak.upgrade() {
            let shell = window.global::<ShellState>();
            if shell.get_logout_busy() {
                return;
            }
            shell.set_logout_title(strings::LOGOUT.into());
            shell.set_logout_warning(
                formatted(
                    "logout_server_warning",
                    &[&zaplaud_core::rules::list::server_label(origin.as_str())],
                )
                .into(),
            );
            shell.set_logout_origin(origin);
        }
    });
    let send = sender.clone();
    let weak = window.as_weak();
    window.global::<ShellState>().on_confirm_logout(move || {
        if let Some(window) = weak.upgrade() {
            let _ = send.send(state::Event::Logout(
                window.global::<ShellState>().get_logout_origin().into(),
            ));
        }
    });
    let weak = window.as_weak();
    let network_sender = sender.clone();
    let worker = std::thread::Builder::new()
        .name("zaplaud-network".into())
        .spawn(move || {
            let result = (move || -> Result<(), String> {
                let store =
                    zaplaud_store::LocalStateStore::new().map_err(|error| error.to_string())?;
                let avatar_storage = zaplaud_store::AvatarDiskCache::new(store.directories())
                    .map_err(|error| error.to_string())?;
                let theme = std::env::var("ZAPLAUD_THEME")
                    .or_else(|_| store.theme().map_err(|error| error.to_string()))?;
                update_ui(&weak, move |window| {
                    window.global::<Theme>().set_dark(theme == "dark")
                });
                runtime.block_on(app::run(
                    state::AppState::new(Box::new(store)),
                    receiver,
                    network_sender,
                    weak,
                    std::sync::Arc::new(avatar_storage),
                    snapshots,
                ));
                Ok(())
            })();
            if result.is_err() {
                let _ = slint::invoke_from_event_loop(|| {
                    let _ = slint::quit_event_loop();
                });
            }
            result
        })?;
    let result = window.run();
    let window_flush = window_state::flush();
    let _ = sender.send(state::Event::Shutdown);
    worker
        .join()
        .map_err(|_| std::io::Error::other(strings::NETWORK_ERROR))??;
    window_flush?;
    result?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn errors_use_generated_user_messages_without_server_details() {
        let error = Error::Api {
            status: 403,
            retry_after: None,
            code: None,
            attachments_expired: false,
            detail: "conteúdo privado do servidor".into(),
        };
        assert_eq!(error_message(&error), strings::FORBIDDEN);
        assert_eq!(error_message(&Error::InvalidOrigin), strings::INVALID_URL);
        assert_eq!(
            error_message(&Error::Redirect(302)),
            strings::REDIRECT_ERROR
        );
    }
}
