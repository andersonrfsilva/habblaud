use crate::model::{AgentEvent, AgentWatch, SessionItem, SessionKey};
use std::collections::HashMap;

pub fn watch_of(sessions: &[SessionItem]) -> HashMap<SessionKey, AgentWatch> {
    sessions
        .iter()
        .filter(|s| s.agent_id.is_some())
        .map(|s| {
            (
                s.key.clone(),
                AgentWatch {
                    status: s.status.clone(),
                    permission_id: s.permission_id.clone(),
                },
            )
        })
        .collect()
}
pub fn agent_events(
    previous: &HashMap<SessionKey, AgentWatch>,
    current: &[SessionItem],
) -> Vec<AgentEvent> {
    current
        .iter()
        .filter(|s| s.agent_id.is_some())
        .filter_map(|session| {
            let before = previous.get(&session.key);
            if session.permission_id.is_some()
                && session.permission_id.as_ref() != before.and_then(|s| s.permission_id.as_ref())
            {
                Some(AgentEvent::Permission(session.clone()))
            } else if before.and_then(|s| s.status.as_deref()) == Some("working")
                && matches!(session.status.as_deref(), Some("idle" | "done"))
            {
                Some(AgentEvent::TurnDone(session.clone()))
            } else {
                None
            }
        })
        .collect()
}
pub fn alert_text(event: &AgentEvent) -> &'static str {
    match event {
        AgentEvent::Permission(session) if session.permission_question => "notify_question",
        AgentEvent::Permission(_) => "notify_permission",
        AgentEvent::TurnDone(_) => "notify_done",
    }
}
pub fn alert_subtitle(event: &AgentEvent, multiple_servers: bool) -> Option<String> {
    let session = match event {
        AgentEvent::Permission(session) | AgentEvent::TurnDone(session) => session,
    };
    let mut parts = Vec::with_capacity(2);
    if let Some(project) = session.project.as_deref() {
        parts.push(project.rsplit('/').next().unwrap_or(project).to_owned());
    }
    if multiple_servers {
        parts.push(server_label(&session.key.origin));
    }
    let text = parts.join(" · ");
    (!text.is_empty()).then_some(text)
}
pub use super::list::server_label;
