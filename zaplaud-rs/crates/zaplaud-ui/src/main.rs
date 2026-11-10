use slint::{ComponentHandle, ModelRc, SharedString, VecModel};
use std::{cell::RefCell, rc::Rc};
use tokio::sync::mpsc;
use zaplaud_core::{api::HabblaudApi, error::Error, model::ServerEvent, rules};

slint::include_modules!();

pub mod strings {
    include!(concat!(env!("OUT_DIR"), "/strings.rs"));
}

enum Command {
    Login {
        api: HabblaudApi,
        user: String,
        password: String,
    },
    Shutdown,
}

fn formatted(key: &str, args: &[&str]) -> String {
    strings::format(key, args).unwrap_or_else(|| strings::NETWORK_ERROR.to_owned())
}

fn error_message(error: &Error) -> String {
    match error {
        Error::InvalidOrigin => strings::INVALID_URL.to_owned(),
        Error::Redirect(_) => strings::REDIRECT_ERROR.to_owned(),
        Error::LoginRefused | Error::SessionEnded => strings::LOGIN_ERROR.to_owned(),
        Error::Api { status: 401, .. } => strings::LOGIN_ERROR.to_owned(),
        Error::Api { status: 403, .. } => strings::FORBIDDEN.to_owned(),
        Error::Api {
            status: 429,
            retry_after,
            ..
        } => formatted("rate_limited", &[retry_after.as_deref().unwrap_or("60")]),
        Error::Api { status, .. } => formatted("http_error", &[&status.to_string()]),
        Error::EventTooLarge => strings::READING_OVERSIZE.to_owned(),
        _ => strings::NETWORK_ERROR.to_owned(),
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

async fn connect(api: HabblaudApi, user: String, password: String, weak: slint::Weak<AppWindow>) {
    let result = async {
        let login = api.login(&user, &password).await;
        drop(password);
        login?;
        let connected = formatted("connected", &[api.origin()]);
        update_ui(&weak, move |window| {
            window.set_busy(false);
            window.set_authenticated(true);
            window.set_status(connected.into());
        });
        let mut stream = api.stream("/api/stream").await?;
        while let Some(event) = stream.next_event().await? {
            if let ServerEvent::Snapshot(snapshot) = event {
                let titles: Vec<String> = rules::by_last_message(&snapshot.sessions)
                    .into_iter()
                    .map(|session| session.title)
                    .collect();
                update_ui(&weak, move |window| {
                    let titles: Vec<SharedString> = titles.into_iter().map(Into::into).collect();
                    window.set_sessions(ModelRc::new(VecModel::from(titles)));
                    window.set_status(strings::READING_CONNECTED.into());
                });
            }
        }
        Ok::<(), Error>(())
    }
    .await;
    let message = match result {
        Ok(()) => strings::READING_OFFLINE.to_owned(),
        Err(error) => error_message(&error),
    };
    update_ui(&weak, move |window| {
        window.set_busy(false);
        window.set_status(message.into());
    });
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name("software".into())
        .select()?;
    slint::set_xdg_app_id("zaplaud")?;
    let window = AppWindow::new()?;
    let labels = window.global::<Labels>();
    labels.set_app_name(strings::APP_NAME.into());
    labels.set_connection(strings::CONNECTION.into());
    labels.set_server_url(strings::SERVER_URL.into());
    labels.set_user(strings::USER.into());
    labels.set_password(strings::PASSWORD.into());
    labels.set_login(strings::LOGIN.into());
    labels.set_sessions(strings::SESSIONS.into());
    labels.set_select_conversation(strings::SELECT_CONVERSATION.into());
    labels.set_http_login_title(strings::HTTP_LOGIN_TITLE.into());
    labels.set_confirm(strings::CONFIRM.into());
    labels.set_cancel(strings::CANCEL.into());

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    let (sender, mut receiver) = mpsc::unbounded_channel();
    let weak = window.as_weak();
    let worker = std::thread::Builder::new()
        .name("zaplaud-network".into())
        .spawn(move || {
            runtime.block_on(async move {
                let mut active: Option<tokio::task::JoinHandle<()>> = None;
                while let Some(command) = receiver.recv().await {
                    match command {
                        Command::Login {
                            api,
                            user,
                            password,
                        } => {
                            if let Some(task) = active.take() {
                                task.abort();
                            }
                            active = Some(tokio::spawn(connect(api, user, password, weak.clone())));
                        }
                        Command::Shutdown => break,
                    }
                }
                if let Some(task) = active {
                    task.abort();
                    let _ = task.await;
                }
            });
        })?;

    let pending_origin = Rc::new(RefCell::new(None::<String>));
    let submit_sender = sender.clone();
    let submit_origin = pending_origin.clone();
    let weak = window.as_weak();
    window.on_submit(move |confirmed| {
        let Some(window) = weak.upgrade() else { return };
        if window.get_busy() || window.get_username().is_empty() || window.get_password().is_empty()
        {
            return;
        }
        let api = match HabblaudApi::new(window.get_server_url().as_str()) {
            Ok(api) => api,
            Err(error) => {
                window.set_password(SharedString::default());
                window.set_status(error_message(&error).into());
                return;
            }
        };
        if api.origin().starts_with("http://") {
            if !confirmed {
                *submit_origin.borrow_mut() = Some(api.origin().to_owned());
                window.set_http_warning(formatted("http_login_warning", &[api.origin()]).into());
                window.set_confirm_http(true);
                return;
            }
            if submit_origin.borrow().as_deref() != Some(api.origin()) {
                window.set_confirm_http(false);
                *submit_origin.borrow_mut() = None;
                return;
            }
        }
        *submit_origin.borrow_mut() = None;
        window.set_confirm_http(false);
        let command = Command::Login {
            api,
            user: window.get_username().to_string(),
            password: window.get_password().to_string(),
        };
        window.set_password(SharedString::default());
        window.set_busy(true);
        window.set_status(strings::READING_CONNECTING.into());
        if submit_sender.send(command).is_err() {
            window.set_busy(false);
            window.set_status(strings::NETWORK_ERROR.into());
        }
    });
    let weak = window.as_weak();
    window.on_cancel_http(move || {
        *pending_origin.borrow_mut() = None;
        if let Some(window) = weak.upgrade() {
            window.set_confirm_http(false);
        }
    });

    let result = window.run();
    let _ = sender.send(Command::Shutdown);
    worker
        .join()
        .map_err(|_| std::io::Error::other(strings::NETWORK_ERROR))?;
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
