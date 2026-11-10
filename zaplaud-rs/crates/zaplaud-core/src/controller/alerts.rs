use crate::{
    model::{AgentEvent, AgentWatch, SessionItem, SessionKey},
    rules::alerts::{agent_events, watch_of},
};
use std::collections::HashMap;

/// Baseline conservada nas quedas; foco e preferências nunca atrasam eventos antigos.
#[derive(Default)]
pub struct AlertController {
    previous: HashMap<String, HashMap<SessionKey, AgentWatch>>,
}
impl AlertController {
    pub fn snapshot(
        &mut self,
        origin: &str,
        sessions: &[SessionItem],
        focused: bool,
        enabled: bool,
        permission: bool,
        done: bool,
    ) -> Vec<AgentEvent> {
        let events = if enabled && !focused {
            self.previous
                .get(origin)
                .map(|previous| agent_events(previous, sessions))
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        self.previous.insert(origin.to_owned(), watch_of(sessions));
        events
            .into_iter()
            .filter(|event| match event {
                AgentEvent::Permission(_) => permission,
                AgentEvent::TurnDone(_) => done,
            })
            .collect()
    }
    pub fn retain_origins(&mut self, origins: &[&str]) {
        self.previous
            .retain(|origin, _| origins.contains(&origin.as_str()));
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn item(status: &str, permission: Option<&str>) -> SessionItem {
        let mut item: SessionItem = serde_json::from_value(serde_json::json!({"key":{"origin":"http://localhost","provider":"omp","account":"a","sessionId":"s"},"title":"test"})).expect("fixture");
        item.agent_id = Some("agent".into());
        item.status = Some(status.into());
        item.permission_id = permission.map(str::to_owned);
        item
    }
    #[test]
    fn baseline_reconnect_focus_and_preferences() {
        let mut c = AlertController::default();
        assert!(
            c.snapshot("o", &[item("working", None)], false, true, true, true)
                .is_empty()
        );
        // Nenhum reset entre desconexão e novo snapshot.
        assert!(matches!(
            c.snapshot("o", &[item("idle", None)], false, true, true, true)
                .as_slice(),
            [AgentEvent::TurnDone(_)]
        ));
        assert!(
            c.snapshot("o", &[item("idle", Some("p"))], true, true, true, true)
                .is_empty()
        );
        assert!(
            c.snapshot("o", &[item("idle", Some("p"))], false, true, true, true)
                .is_empty()
        );
        assert!(
            c.snapshot("o", &[item("idle", Some("q"))], false, false, true, true)
                .is_empty()
        );
        assert!(
            c.snapshot("o", &[item("idle", Some("r"))], false, true, false, true)
                .is_empty()
        );
        c.snapshot("o", &[item("working", None)], false, true, true, true);
        assert!(
            c.snapshot("o", &[item("idle", None)], false, true, true, false)
                .is_empty()
        );
        assert!(matches!(
            c.snapshot("o", &[item("idle", Some("s"))], false, true, true, true)
                .as_slice(),
            [AgentEvent::Permission(_)]
        ));
    }
}
