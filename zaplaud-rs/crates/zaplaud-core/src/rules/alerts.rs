use crate::model::{AgentEvent, AgentWatch, Notice, SessionItem, SessionKey, WaitKind};
use std::collections::HashMap;

pub fn watch_of(sessions: &[SessionItem]) -> HashMap<SessionKey, AgentWatch> {
    sessions
        .iter()
        .filter(|s| s.agent_id.is_some() && !s.sub)
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
        .filter(|s| s.agent_id.is_some() && !s.sub)
        .filter_map(|session| {
            let before = previous.get(&session.key);
            if session.permission_id.is_some()
                && session.permission_id.as_ref() != before.and_then(|s| s.permission_id.as_ref())
            {
                Some(AgentEvent::Permission(session.clone()))
            } else if before.and_then(|s| s.status.as_deref()) == Some("working")
                && session.status.as_deref() == Some("waiting")
                && session.permission_id.is_none()
            {
                Some(AgentEvent::TerminalWait(session.clone()))
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

pub fn notice_event(notice: Notice, sessions: &[SessionItem]) -> Option<AgentEvent> {
    let agent_id = notice.agent_id.as_deref()?;
    let session = sessions.iter().find(|session| {
        !session.sub && session.agent_id.as_deref() == Some(agent_id)
    })?;
    if let Some(request_id) = notice.request_id.as_deref()
        && session.permission_id.as_deref() != Some(request_id)
    {
        return None;
    }
    match notice.kind.as_deref() {
        Some("wait") => Some(AgentEvent::NoticeWait(
            session.clone(),
            notice.wait?,
            notice.reason,
        )),
        Some("done") => Some(AgentEvent::NoticeDone(
            session.clone(),
            notice.duration_ms,
            notice.interrupted,
        )),
        _ => None,
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct AlertPreferences {
    pub enabled: bool,
    pub wait: bool,
    pub done: bool,
    pub min_turn: bool,
}

impl AlertPreferences {
    pub fn allows(self, event: &AgentEvent, focused_session: Option<&SessionKey>) -> bool {
        self.enabled
            && focused_session != Some(&event.session().key)
            && if event.is_wait() {
                self.wait
            } else {
                self.done && !(self.min_turn && matches!(
                    event, AgentEvent::NoticeDone(_, Some(ms), _) if *ms < 30_000
                ))
            }
    }
}

pub fn batch_summary(count: usize) -> bool {
    count >= 3
}

pub fn alert_reason(event: &AgentEvent) -> Option<String> {
    let AgentEvent::NoticeWait(_, _, Some(reason)) = event else {
        return None;
    };
    let reason = reason.trim();
    if reason.is_empty() {
        return None;
    }
    Some(match reason.char_indices().nth(120) {
        Some((end, _)) => format!("{}…", &reason[..end]),
        None => reason.to_owned(),
    })
}
pub fn alert_text(event: &AgentEvent) -> &'static str {
    match event {
        AgentEvent::Permission(session) if session.permission_question => "notify_question",
        AgentEvent::NoticeWait(_, WaitKind::Question, _) => "notify_question",
        AgentEvent::Permission(_) | AgentEvent::NoticeWait(_, WaitKind::Permission, _) => "notify_permission",
        AgentEvent::TerminalWait(_) | AgentEvent::NoticeWait(_, WaitKind::Terminal, _) => "notify_waiting",
        AgentEvent::NoticeDone(_, _, true) => "notify_interrupted",
        AgentEvent::TurnDone(_) | AgentEvent::NoticeDone(..) => "notify_done",
    }
}
pub fn alert_subtitle(event: &AgentEvent, multiple_servers: bool) -> Option<String> {
    let session = event.session();
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
