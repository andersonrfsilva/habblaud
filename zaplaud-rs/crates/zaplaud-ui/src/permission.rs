use crate::{
    AppWindow, ListState, PermissionChoice, PermissionQuestionRow, PermissionState,
    PermissionSuggestionRow,
    feature::{AppSnapshot, FeatureContext},
    state::Event as AppEvent,
    strings, update_ui,
};
use slint::{ComponentHandle, Model, ModelRc, VecModel};
use std::sync::Arc;
use tokio::sync::mpsc;
use zaplaud_core::{
    controller::permission::{Update, now_millis, run},
    model::SessionItem,
    rules::permission::{Event, State},
};

enum Command {
    Open(String),
    Selected,
    Event(Event),
}
pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<PermissionState>();
    global.set_pending_label(strings::PERMISSION_PENDING.into());
    global.set_allow_label(strings::PERMISSION_ALLOW.into());
    global.set_deny_label(strings::PERMISSION_DENY.into());
    global.set_answer_label(strings::PERMISSION_ANSWER.into());
    global.set_terminal_label(strings::PERMISSION_TERMINAL.into());
    global.set_cancel_label(strings::CANCEL.into());
    global.set_tool_label(strings::PERMISSION_TOOL.into());
    global.set_other_label(strings::PERMISSION_OTHER.into());
    global.set_reason_label(strings::PERMISSION_REASON.into());
    global.set_interrupt_label(strings::PERMISSION_INTERRUPT.into());
    global.on_more_lines(|lines| {
        crate::native_strings::PERMISSION_MORE_LINES
            .replace("%1$d", &lines.to_string())
            .into()
    });
    let (sender, mut commands) = mpsc::unbounded_channel();
    let tx = sender.clone();
    global.on_open_session(move |key| {
        let _ = tx.send(Command::Open(key.to_string()));
    });
    let tx = sender.clone();
    window.global::<ListState>().on_permission(move |key| {
        let _ = tx.send(Command::Open(key.to_string()));
    });
    let tx = sender.clone();
    global.on_open_selected(move || {
        let _ = tx.send(Command::Selected);
    });
    let tx = sender.clone();
    global.on_close(move || {
        let _ = tx.send(Command::Event(Event::Close));
    });
    let tx = sender.clone();
    global.on_decide(move |id, behavior| {
        let _ = tx.send(Command::Event(Event::Decide {
            id: id.to_string(),
            behavior: behavior.to_string(),
            now: now_millis(),
        }));
    });
    let tx = sender.clone();
    global.on_deny(move |id| {
        let _ = tx.send(Command::Event(Event::Deny {
            id: id.to_string(),
            now: now_millis(),
        }));
    });
    let tx = sender.clone();
    global.on_select(move |question, option, multi, checked| {
        let _ = tx.send(Command::Event(Event::Select {
            question,
            option,
            multi,
            checked,
        }));
    });
    let tx = sender.clone();
    global.on_other(move |question, multi, text| {
        let _ = tx.send(Command::Event(Event::Other {
            question,
            multi,
            text: text.to_string(),
        }));
    });
    let tx = sender.clone();
    global.on_suggestion(move |index, checked| {
        let _ = tx.send(Command::Event(Event::Suggestion(checked.then_some(index))));
    });
    let tx = sender.clone();
    global.on_set_interrupt(move |value| {
        let _ = tx.send(Command::Event(Event::Interrupt(value)));
    });
    let retry = sender.clone();
    global.on_set_reason(move |text| {
        let _ = sender.send(Command::Event(Event::Message(text.to_string())));
    });
    let mut snapshots = context.snapshots.subscribe();
    let context = context.clone();
    let weak = window.as_weak();
    context.runtime.clone().spawn(async move {
        let mut snapshot: Option<Arc<AppSnapshot>> = None;
        let mut pending_open = None;
        let mut active: Option<(SessionItem, mpsc::UnboundedSender<Event>)> = None;
        let mut runner = None;
        let (updates, mut results) = mpsc::unbounded_channel();
        loop {
            tokio::select! {
                result = snapshots.recv() => match result {
                    Ok(next) => {
                        let pending = next.selected.as_ref().is_some_and(|s| s.permission);
                        let question = next.selected.as_ref().is_some_and(|s| s.permission_question);
                        update_ui(&weak, move |w| {
                            let ui = w.global::<PermissionState>();
                            ui.set_pending(pending);
                            ui.set_pending_label(if question { strings::QUESTION_PENDING } else { strings::PERMISSION_PENDING }.into());
                            ui.set_action_label(if question { strings::PERMISSION_ANSWER } else { strings::PERMISSION_APPROVE }.into());
                        });
                        if let Some((item, tx)) = &active {
                            if let Some(server) = next.servers.iter().find(|s| s.api.origin() == item.key.origin) {
                                let connected = server.reading.connection == "connected";
                                let present = server.reading.sessions.iter().any(|s| s.key == item.key && s.permission_id == item.permission_id);
                                let _ = tx.send(Event::Snapshot { permission_present: present, connected });
                                if present { let _ = tx.send(Event::Observe { id: item.permission_id.clone().unwrap_or_default(), allowed: server.reading.permissions_allowed && !server.needs_login, connected }); }
                            } else { let _ = tx.send(Event::Close); }
                        }
                        snapshot = Some(next);
                        if pending_open.as_ref().is_some_and(|key| snapshot.as_ref().is_some_and(|s| s.servers.iter().any(|server| server.reading.sessions.iter().any(|item| item.key.serialize().ok().as_ref() == Some(key)))))
                            && let Some(key) = pending_open.take()
                        { let _ = retry.send(Command::Open(key)); }
                    },
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                },
                command = commands.recv() => {
                    let Some(command) = command else { break };
                    match command {
                        Command::Event(event) => { if let Some((_, tx)) = &active { let _ = tx.send(event); } },
                        command => {
                            let key = match command {
                                Command::Open(key) => key,
                                _ => snapshot.as_ref().and_then(|s| s.selected.as_ref()).and_then(|s| s.key.serialize().ok()).unwrap_or_default(),
                            };
                            let _ = context.commands.send(AppEvent::Open(key.clone()));
                            let Some(snapshot) = &snapshot else { pending_open = Some(key); continue };
                            let found = snapshot.servers.iter().find_map(|server| server.reading.sessions.iter().find(|s| s.key.serialize().ok().as_deref() == Some(&key)).map(|item| (server, item)));
                            let Some((server, item)) = found else { pending_open = Some(key); continue };
                            if item.permission_id.is_none() { continue; }
                            if let Some(task) = runner.take() { let task: tokio::task::JoinHandle<()> = task; task.abort(); let _ = task.await; }
                            while results.try_recv().is_ok() {}
                            let (tx, rx) = mpsc::unbounded_channel();
                            let _ = tx.send(Event::Observe { id: item.permission_id.clone().unwrap_or_default(), allowed: server.reading.permissions_allowed && !server.needs_login, connected: server.reading.connection == "connected" });
                            runner = Some(tokio::spawn(run(server.api.clone(), rx, updates.clone())));
                            active = Some((item.clone(), tx));
                            update_ui(&weak, |w| w.global::<PermissionState>().set_visible(true));
                        }
                    }
                },
                result = results.recv() => if let Some(result) = result {
                    match result {
                        Update::State(state) => if let Some((item, _)) = &active { publish(&weak, item.clone(), state); },
                        Update::Unauthorized => { if let Some((item, tx)) = &active { let _ = context.commands.send(AppEvent::Unauthorized(item.key.origin.clone())); let _ = tx.send(Event::Close); } },
                        Update::Closed | Update::Cancelled => { active = None; update_ui(&weak, |w| w.global::<PermissionState>().set_visible(false)); },
                    }
                }
            }
        }
        if let Some(task) = runner { task.abort(); let _ = task.await; }
    });
}
// Atualizações de linha preservam foco, cursor e seleção durante a digitação.
fn update_model<T: Clone + PartialEq + 'static>(current: ModelRc<T>, rows: Vec<T>) -> ModelRc<T> {
    if let Some(model) = current.as_any().downcast_ref::<VecModel<T>>()
        && model.row_count() == rows.len()
    {
        for (index, row) in rows.into_iter().enumerate() {
            if model.row_data(index).as_ref() != Some(&row) {
                model.set_row_data(index, row);
            }
        }
        current
    } else {
        ModelRc::new(VecModel::from(rows))
    }
}
fn publish(weak: &slint::Weak<AppWindow>, item: SessionItem, state: State) {
    update_ui(weak, move |window| {
        let ui = window.global::<PermissionState>();
        let detail = state.detail.as_ref();
        ui.set_request_id(state.id.as_deref().unwrap_or_default().into());
        ui.set_title(
            if detail.is_some_and(|d| !d.questions.is_empty()) {
                strings::QUESTION_PENDING
            } else {
                strings::PERMISSION_PENDING
            }
            .into(),
        );
        ui.set_character(item.character_name.clone().unwrap_or_default().into());
        ui.set_avatar(
            item.avatar
                .as_ref()
                .map(|avatar| crate::avatars::get(&crate::avatars::key(&item.key.origin, avatar)))
                .unwrap_or_default(),
        );
        let colors = window.global::<crate::AvatarColors>();
        let palette = [
            colors.get_background_0(),
            colors.get_background_1(),
            colors.get_background_2(),
            colors.get_background_3(),
            colors.get_background_4(),
        ];
        let hash = item
            .character_name
            .as_deref()
            .unwrap_or(&item.title)
            .encode_utf16()
            .fold(0i32, |a, c| a.wrapping_mul(31).wrapping_add(c as i32));
        ui.set_fallback_color(palette[(hash & i32::MAX) as usize % 5]);
        let icons = window.global::<crate::Icons>();
        let (brand, color) = match zaplaud_core::rules::composer::model_brand(item.model.as_deref())
        {
            Some("claude") => (icons.get_ic_model_claude(), colors.get_claude()),
            Some("gemini") => (icons.get_ic_model_gemini(), colors.get_gemini()),
            Some("openai") => (
                icons.get_ic_model_openai(),
                window.global::<crate::Theme>().get_on_surface(),
            ),
            _ => (slint::Image::default(), slint::Color::default()),
        };
        ui.set_brand(brand);
        ui.set_brand_color(color);
        ui.set_initials(
            item.character_name
                .as_ref()
                .unwrap_or(&item.title)
                .chars()
                .next()
                .map(|c| c.to_uppercase().collect::<String>())
                .unwrap_or_default()
                .into(),
        );
        let mut subtitle = Vec::new();
        for text in [
            item.project.as_deref().and_then(|p| p.rsplit('/').next()),
            item.agent_name.as_deref(),
            Some(item.title.as_str()),
        ]
        .into_iter()
        .flatten()
        {
            if Some(text) != item.character_name.as_deref() && !subtitle.contains(&text) {
                subtitle.push(text);
            }
        }
        ui.set_subtitle(subtitle.join(" · ").into());
        ui.set_enabled(
            state.allowed
                && state.connected
                && detail.is_some()
                && !state.expired
                && !state.busy
                && !state.ambiguous,
        );
        ui.set_loading(
            state.allowed && state.connected && detail.is_none() && state.error.is_none(),
        );
        ui.set_busy(state.busy);
        ui.set_has_detail(detail.is_some());
        ui.set_show_deny(state.show_deny);
        ui.set_reason(state.decision.message.clone().into());
        ui.set_interrupt(state.decision.interrupt);
        ui.set_claude(detail.is_some_and(|d| d.provider == "claude"));
        ui.set_blocked(match (state.allowed, state.connected) {
            (false, false) => format!(
                "{}\n{}",
                strings::PERMISSIONS_BLOCKED,
                strings::READING_OFFLINE
            )
            .into(),
            (false, true) => strings::PERMISSIONS_BLOCKED.into(),
            (true, false) => strings::READING_OFFLINE.into(),
            (true, true) => "".into(),
        });
        ui.set_expired(state.expired);
        ui.set_expired_label(strings::PERMISSION_EXPIRED.into());
        ui.set_error(
            match state.error.as_deref() {
                Some("answers") => strings::PERMISSION_ANSWERS,
                Some("unknown") => strings::PERMISSION_UNKNOWN,
                Some(_) => strings::NETWORK_ERROR,
                None => "",
            }
            .into(),
        );
        ui.set_tool(
            detail
                .and_then(|d| d.tool.clone())
                .unwrap_or_default()
                .into(),
        );
        ui.set_summary(detail.map_or("", permission_summary).into());
        ui.set_summary_label(strings::PERMISSION_SUMMARY.into());
        ui.set_input(
            detail
                .and_then(|d| d.input.clone())
                .unwrap_or_default()
                .into(),
        );
        ui.set_input_label(
            if detail
                .and_then(|d| d.tool.as_deref())
                .is_some_and(|t| t.eq_ignore_ascii_case("bash"))
            {
                strings::PERMISSION_COMMAND
            } else {
                strings::PERMISSION_INPUT
            }
            .into(),
        );
        ui.set_deadline(
            detail
                .filter(|d| d.raw.get("expiresAt").is_some())
                .map(|d| {
                    crate::formatted(
                        "permission_deadline",
                        &[&zaplaud_core::rules::time::platform_deadline(d.expires_at)
                            .unwrap_or_default()],
                    )
                })
                .unwrap_or_default()
                .into(),
        );
        let current_questions = ui.get_questions();
        let questions: Vec<_> = detail
            .into_iter()
            .flat_map(|d| &d.questions)
            .filter_map(|q| {
                let index = q.index?;
                let options: Vec<_> = q
                    .options
                    .iter()
                    .filter_map(|o| {
                        Some(PermissionChoice {
                            question: index,
                            index: o.index?,
                            multi: q.multi_select,
                            label: o.label.clone().into(),
                            description: o.description.clone().unwrap_or_default().into(),
                            checked: state
                                .decision
                                .selections
                                .get(&index)
                                .is_some_and(|s| s.contains(&o.index.unwrap_or_default())),
                        })
                    })
                    .collect();
                Some(PermissionQuestionRow {
                    index,
                    multi: q.multi_select,
                    text: q.question.clone().into(),
                    other: state
                        .decision
                        .others
                        .get(&index)
                        .cloned()
                        .unwrap_or_default()
                        .into(),
                    options: update_model(
                        (0..current_questions.row_count())
                            .filter_map(|row| current_questions.row_data(row))
                            .find(|row| row.index == index)
                            .map(|row| row.options)
                            .unwrap_or_default(),
                        options,
                    ),
                })
            })
            .collect();
        ui.set_questions(update_model(current_questions, questions));
        let suggestions: Vec<_> = detail
            .into_iter()
            .filter(|d| d.provider == "claude" && d.questions.is_empty())
            .flat_map(|d| &d.suggestions)
            .filter_map(|s| {
                let index = s.index?;
                let destination = match s.destination.as_deref() {
                    Some("session") => strings::PERMISSION_DESTINATION_SESSION,
                    Some("localSettings") => strings::PERMISSION_DESTINATION_LOCAL,
                    Some("projectSettings") => strings::PERMISSION_DESTINATION_PROJECT,
                    Some("userSettings") => strings::PERMISSION_DESTINATION_USER,
                    _ => strings::PERMISSION_DESTINATION_UNKNOWN,
                };
                Some(PermissionSuggestionRow {
                    index,
                    text: crate::formatted(
                        "permission_suggestion",
                        &[&s.rules.join(", "), destination],
                    )
                    .into(),
                    checked: state.decision.suggestion == Some(index),
                })
            })
            .collect();
        ui.set_suggestions(update_model(ui.get_suggestions(), suggestions));
    });
}

// `text` é o resumo humano do servidor; `description` e `input` não o substituem.
fn permission_summary(permission: &zaplaud_core::model::Permission) -> &str {
    permission
        .raw
        .get("text")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn editing_updates_existing_rows_without_replacing_model() {
        let model = ModelRc::new(VecModel::from(vec![String::new()]));
        let original = model.clone();
        let updated = update_model(model, vec!["resposta completa".into()]);
        assert_eq!(original, updated);
        assert_eq!(original.row_data(0).as_deref(), Some("resposta completa"));
        let expanded = update_model(updated, vec!["a".into(), "b".into()]);
        assert_eq!(expanded.row_count(), 2);
    }
    use serde_json::json;
    use zaplaud_core::model::Permission;

    #[test]
    fn approval_preserves_server_summary_separately_from_technical_input() {
        let permission = Permission::parse(&json!({
            "text":"Executar os testes do projeto", "description":"Descrição técnica",
            "tool":"Bash", "input":{"command":"cargo test"}
        }));
        assert_eq!(
            permission_summary(&permission),
            "Executar os testes do projeto"
        );
        assert_eq!(
            permission.input.as_deref(),
            Some("{\"command\":\"cargo test\"}")
        );
        for value in [
            json!({}),
            json!({"text":null}),
            json!({"text":42}),
            json!({"text":""}),
        ] {
            assert_eq!(permission_summary(&Permission::parse(&value)), "");
        }
    }

    #[test]
    fn question_requests_keep_the_server_summary() {
        let permission = Permission::parse(&json!({
            "text":"Escolher o destino da publicação", "tool":"AskUserQuestion",
            "questions":[{"index":0,"question":"Qual ambiente?","options":[{"index":0,"label":"Teste"}]}]
        }));
        assert_eq!(
            permission_summary(&permission),
            "Escolher o destino da publicação"
        );
        assert_eq!(permission.questions.len(), 1);
    }
}
