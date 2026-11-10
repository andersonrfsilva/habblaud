use crate::{
    AppWindow, AsideState, AsideTopicRow, AsideTurnRow, feature::FeatureContext,
    state::Event as AppEvent, strings, update_ui,
};
use slint::{ComponentHandle, ModelRc, VecModel};
use tokio::sync::{mpsc, watch};
use zaplaud_core::{
    controller::aside,
    model::SessionItem,
    rules::aside::{Event, State},
};

pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<AsideState>();
    global.on_valid_draft(|text| valid_draft(&text));
    global.set_title(strings::ASIDE_TITLE.into());
    global.set_hint(strings::ASIDE_HINT.into());
    global.set_empty(strings::ASIDE_EMPTY.into());
    global.set_input_label(strings::ASIDE_INPUT.into());
    global.set_send_label(strings::ASIDE_SEND.into());
    global.set_stop_label(strings::ASIDE_STOP.into());
    global.set_new_label(strings::ASIDE_NEW.into());
    let (send, mut receive) = mpsc::unbounded_channel::<Event>();
    for (kind, setter) in [
        (0, send.clone()),
        (1, send.clone()),
        (2, send.clone()),
        (3, send.clone()),
    ] {
        match kind {
            0 => global.on_open(move || {
                let _ = setter.send(Event::Open(String::new()));
            }),
            1 => global.on_close(move || {
                let _ = setter.send(Event::Close);
            }),
            2 => global.on_stop(move || {
                let _ = setter.send(Event::Cancel);
            }),
            _ => global.on_new_topic(move || {
                let _ = setter.send(Event::StartNew);
            }),
        }
    }
    let sender = send.clone();
    global.on_ask(move |text| {
        let _ = sender.send(Event::Ask(text.into()));
    });
    global.on_choose(move |id| {
        let _ = send.send(Event::Choose(id.into()));
    });
    let weak = window.as_weak();
    global.on_copy(move |text| {
        if let Some(window) = weak.upgrade() {
            window.invoke_copy_conversation(text);
        }
    });
    let weak = window.as_weak();
    global.on_link(move |url| {
        if let Some(window) = weak.upgrade() {
            window
                .global::<crate::ConversationViewState>()
                .invoke_open_link(url);
        }
    });
    let (visible, visibility) = watch::channel(true);
    global.on_app_visibility(move |value| {
        visible.send_replace(value);
    });
    let weak = window.as_weak();
    crate::window_state::on_visibility(move |value| {
        if let Some(window) = weak.upgrade() {
            window.global::<AsideState>().invoke_app_visibility(value);
        }
    });
    let mut snapshots = context.snapshots.subscribe();
    let weak = window.as_weak();
    let commands = context.commands.clone();
    context.runtime.spawn(async move {
        let mut selected: Option<SessionItem> = None;
        let mut api = None;
        let mut eligible = false;
        let mut runner: Option<tokio::task::JoinHandle<()>> = None;
        let mut input = None;
        let (initial, mut state) = watch::channel(State::default());
        drop(initial);
        let (unauth, mut notices) = mpsc::unbounded_channel();
        loop {
            tokio::select! {
                snapshot = snapshots.recv() => {
                    let snapshot = match snapshot { Ok(s) => s, Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue, Err(_) => break };
                    let changed = selected.as_ref().map(|s| (&s.key, &s.agent_id)) != snapshot.selected.as_ref().map(|s| (&s.key, &s.agent_id));
                    selected = snapshot.selected.clone();
                    api = selected.as_ref().and_then(|s| snapshot.servers.iter().find(|server| server.api.origin() == s.key.origin)).map(|server| server.api.clone());
                    let available = selected.as_ref().is_some_and(|s| s.key.provider == "omp" && s.agent_id.is_some() && !s.sub);
                    eligible = available && selected.as_ref().is_some_and(|s| s.can_message) && snapshot.servers.iter().find(|server| selected.as_ref().is_some_and(|s| s.key.origin == server.api.origin())).is_some_and(|server| !server.needs_login && server.reading.messages_allowed && server.reading.message_guard && server.reading.connection == "connected");
                    let enabled = eligible;
                    update_ui(&weak, move |window| { let global = window.global::<AsideState>(); global.set_available(available); global.set_enabled(enabled); });
                    if changed { if let Some(task) = runner.take() { task.abort(); } input = None; publish(&weak, State::default()); }
                }
                event = receive.recv() => {
                    let Some(event) = event else { break };
                    if matches!(event, Event::Open(_)) {
                        if !eligible { continue; }
                        if let (Some(api), Some(agent)) = (api.clone(), selected.as_ref().and_then(|s| s.agent_id.clone())) {
                            if let Some(task) = runner.take() { task.abort(); }
                            let (send, receive) = mpsc::unbounded_channel(); let (out, changes) = watch::channel(State::default()); state = changes;
                            runner = Some(tokio::spawn(aside::run(api, receive, out, unauth.clone(), visibility.clone())));
                            let _ = send.send(Event::Open(agent)); input = Some(send);
                        }
                    } else if matches!(event, Event::Close) {
                        if let Some(task) = runner.take() { task.abort(); }
                        input = None; publish(&weak, State::default());
                    } else if let Some(send) = &input { let _ = send.send(event); }
                }
                changed = state.changed(), if runner.is_some() => { if changed.is_ok() { publish(&weak, state.borrow().clone()); } }
                Some(()) = notices.recv() => { if let Some(api) = &api { let _ = commands.send(AppEvent::Unauthorized(api.origin().into())); } }
            }
        }
        if let Some(task) = runner { task.abort(); }
    });
}
fn publish(weak: &slint::Weak<AppWindow>, state: State) {
    update_ui(weak, move |window| {
        let global = window.global::<AsideState>();
        let aside = state.aside;
        global.set_visible(aside.agent_id.is_some());
        if should_clear_draft(
            global.get_sending(),
            aside.sending,
            aside.error.is_some(),
            &global.get_draft(),
            &global.get_submitted_draft(),
        ) {
            global.set_draft("".into());
        }
        global.set_loading(aside.loading);
        global.set_sending(aside.sending);
        global.set_running(
            aside
                .topic()
                .is_some_and(|topic| topic.turns.iter().any(|turn| turn.running())),
        );
        global.set_error(
            aside
                .error
                .as_deref()
                .map(|e| {
                    if e == "network" {
                        strings::READING_OFFLINE
                    } else {
                        e
                    }
                })
                .unwrap_or_default()
                .into(),
        );
        global.set_topics_label(
            crate::formatted("aside_topics_count", &[&aside.topics.len().to_string()]).into(),
        );
        global.set_topics(ModelRc::new(VecModel::from(
            aside
                .topics
                .iter()
                .map(|topic| AsideTopicRow {
                    id: topic.id.clone().into(),
                    selected: aside.topic().is_some_and(|current| current.id == topic.id),
                    title: format!(
                        "{} · {}",
                        topic
                            .turns
                            .first()
                            .map(|turn| turn
                                .question
                                .split_whitespace()
                                .collect::<Vec<_>>()
                                .join(" "))
                            .unwrap_or_default(),
                        zaplaud_core::rules::time::entry_time(topic.created_at, now())
                            .unwrap_or_default()
                    )
                    .into(),
                })
                .collect::<Vec<_>>(),
        )));
        global.set_turns(ModelRc::new(VecModel::from(
            aside
                .topic()
                .map(|topic| {
                    topic
                        .turns
                        .iter()
                        .map(|turn| AsideTurnRow {
                            question: turn.question.clone().into(),
                            answer: turn.answer.clone().into(),
                            blocks: crate::conversation_view::markdown(&turn.answer),
                            status: match turn.status.as_str() {
                                "queued" => strings::ASIDE_QUEUED.into(),
                                "running" if turn.answer.is_empty() => {
                                    strings::ASIDE_THINKING.into()
                                }
                                "cancelled" => strings::ASIDE_CANCELLED.into(),
                                "failed" => crate::formatted(
                                    "aside_failed",
                                    &[turn.error.as_deref().unwrap_or_default()],
                                ),
                                _ => String::new(),
                            }
                            .into(),
                            failed: turn.status == "failed",
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default(),
        )));
        if aside.agent_id.is_none() {
            global.set_draft("".into());
        }
    });
}
fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

fn valid_draft(text: &str) -> bool {
    text.encode_utf16().take(20_001).count() <= 20_000
}

fn should_clear_draft(
    was_sending: bool,
    sending: bool,
    failed: bool,
    draft: &str,
    submitted: &str,
) -> bool {
    was_sending && !sending && !failed && draft == submitted
}

#[cfg(test)]
mod tests {
    use super::{should_clear_draft, valid_draft};

    #[test]
    fn aside_input_counts_utf16_units() {
        assert!(valid_draft(&"a".repeat(20_000)));
        assert!(!valid_draft(&"a".repeat(20_001)));
        assert!(valid_draft(&"🚀".repeat(10_000)));
        assert!(!valid_draft(&"🚀".repeat(10_001)));
    }

    #[test]
    fn aside_draft_survives_failure_and_concurrent_edit() {
        assert!(should_clear_draft(
            true, false, false, "pergunta", "pergunta"
        ));
        assert!(!should_clear_draft(
            true, false, true, "pergunta", "pergunta"
        ));
        assert!(!should_clear_draft(
            true,
            false,
            false,
            "nova pergunta",
            "pergunta"
        ));
        assert!(!should_clear_draft(
            true, true, false, "pergunta", "pergunta"
        ));
        assert!(!should_clear_draft(
            false, false, false, "pergunta", "pergunta"
        ));
    }
}
