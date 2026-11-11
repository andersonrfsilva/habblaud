use crate::{AppWindow, LoginState, error_message, formatted, state::Event, strings};
use slint::ComponentHandle;
use std::{cell::RefCell, rc::Rc};
use zaplaud_core::api::UrlPolicy;

pub fn setup(window: &AppWindow, context: &crate::feature::FeatureContext) {
    let sender = &context.commands;
    let pending = Rc::new(RefCell::new(None::<String>));
    let submitted = pending.clone();
    let sender = sender.clone();
    let weak = window.as_weak();
    window.global::<LoginState>().on_submit(move |confirmed| {
        let Some(window) = weak.upgrade() else { return };
        let login = window.global::<LoginState>();
        if login.get_busy() || login.get_username().is_empty() || login.get_password().is_empty() {
            return;
        }
        let Some(origin) = UrlPolicy::origin(login.get_server_url().as_str()) else {
            login.set_password("".into());
            login.set_message(error_message(&zaplaud_core::Error::InvalidOrigin).into());
            return;
        };
        if origin.starts_with("http://") {
            if !confirmed {
                *submitted.borrow_mut() = Some(origin.clone());
                login.set_http_warning(formatted("http_login_warning", &[&origin]).into());
                login.set_confirm_http(true);
                return;
            }
            if submitted.borrow().as_deref() != Some(origin.as_str()) {
                login.set_confirm_http(false);
                *submitted.borrow_mut() = None;
                return;
            }
        }
        *submitted.borrow_mut() = None;
        login.set_confirm_http(false);
        let command = Event::Login {
            origin,
            user: login.get_username().into(),
            password: login.get_password().into(),
        };
        login.set_password("".into());
        login.set_busy(true);
        login.set_message("".into());
        if sender.send(command).is_err() {
            login.set_busy(false);
            login.set_message(strings::NETWORK_ERROR.into());
        }
    });
    let weak = window.as_weak();
    window.global::<LoginState>().on_cancel_http(move || {
        *pending.borrow_mut() = None;
        if let Some(window) = weak.upgrade() {
            window.global::<LoginState>().set_confirm_http(false);
        }
    });
    let weak = window.as_weak();
    window.global::<LoginState>().on_cancel(move || {
        if let Some(window) = weak.upgrade() {
            let login = window.global::<LoginState>();
            if !login.get_busy() {
                login.set_visible(false);
                login.set_password("".into());
                login.set_message("".into());
            }
        }
    });
}
