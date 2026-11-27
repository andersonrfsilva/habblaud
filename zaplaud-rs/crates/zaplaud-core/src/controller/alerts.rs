use crate::{
    model::{AgentEvent, AgentWatch, Notice, SessionItem, SessionKey},
    rules::alerts::{agent_events, notice_event, watch_of},
};
use std::collections::HashMap;

#[derive(Clone, Debug)]
pub enum AlertUpdate {
    Fallback(AgentEvent),
    Notice(AgentEvent),
    Withdraw(SessionKey),
    WithdrawDone(SessionKey),
}

/// Baseline conservada nas quedas; foco e preferências nunca atrasam eventos antigos.
#[derive(Debug, Default)]
pub struct AlertController {
    previous: Option<HashMap<SessionKey, AgentWatch>>,
    seen_kind: bool,
    first_snapshot: bool,
    updates: Vec<AlertUpdate>,
}
impl AlertController {
    pub fn connected(&mut self) {
        self.seen_kind = false;
        self.first_snapshot = true;
    }

    pub fn notice(&mut self, notice: Notice, sessions: &[SessionItem]) {
        self.seen_kind |= notice.kind.is_some();
        if let Some(event) = notice_event(notice, sessions) {
            self.updates.push(AlertUpdate::Notice(event));
        }
    }

    pub fn snapshot(&mut self, sessions: &[SessionItem]) {
        let current = watch_of(sessions);
        if let Some(previous) = &self.previous {
            for (key, before) in previous {
                if current.get(key).is_none_or(|after| {
                    (after.status.as_deref() == Some("working")
                        && before.status.as_deref() != Some("working"))
                        || (before.permission_id.is_some() && after.permission_id.is_none())
                }) {
                    self.updates.push(AlertUpdate::Withdraw(key.clone()));
                } else if before.status.as_deref() == Some("idle")
                    && current.get(key).is_some_and(|after| after.status.as_deref() != Some("idle"))
                {
                    self.updates.push(AlertUpdate::WithdrawDone(key.clone()));
                }
            }
            if !self.seen_kind || self.first_snapshot {
                self.updates.extend(
                    agent_events(previous, sessions).into_iter().map(AlertUpdate::Fallback),
                );
            } else {
                // Quando a cabeça muda, um pedido já anunciado passa a aceitar aprovação.
                self.updates.extend(sessions.iter().filter_map(|session| {
                    let before = previous.get(&session.key)?.permission_id.as_ref()?;
                    let after = current.get(&session.key)?.permission_id.as_ref()?;
                    (before != after).then(|| AlertUpdate::Fallback(AgentEvent::Permission(session.clone())))
                }));
            }
        }
        self.previous = Some(current);
        self.first_snapshot = false;
    }

    pub fn take_updates(&mut self) -> Vec<AlertUpdate> {
        std::mem::take(&mut self.updates)
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
    fn snapshot_fallback_waits_and_stops_after_typed_notices() {
        let mut c = AlertController::default();
        c.snapshot(&[item("working", None)]);
        assert!(c.take_updates().is_empty());
        c.snapshot(&[item("waiting", None)]);
        assert!(matches!(
            c.take_updates().as_slice(),
            [AlertUpdate::Fallback(AgentEvent::TerminalWait(_))]
        ));
        let working = item("working", None);
        c.snapshot(std::slice::from_ref(&working));
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::Withdraw(_)]));
        let notice = |json| serde_json::from_str(json).unwrap();
        c.notice(notice(r#"{"kind":"arrive"}"#), std::slice::from_ref(&working));
        c.snapshot(&[item("idle", None)]);
        assert!(c.take_updates().is_empty());
        c.snapshot(std::slice::from_ref(&working));
        c.take_updates();
        c.connected();
        // A reconexão preserva a baseline sem inferir conclusão pelo snapshot.
        c.snapshot(&[item("idle", None)]);
        assert!(c.take_updates().is_empty());
        c.snapshot(std::slice::from_ref(&working));
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::Withdraw(_)]));
        c.snapshot(&[item("waiting", None)]);
        assert!(matches!(
            c.take_updates().as_slice(),
            [AlertUpdate::Fallback(AgentEvent::TerminalWait(_))]
        ));
        c.notice(notice(r#"{"kind":"arrive"}"#), std::slice::from_ref(&working));
        c.snapshot(&[item("idle", None)]);
        assert!(c.take_updates().is_empty());
        c.snapshot(&[item("idle", Some("p"))]);
        assert!(c.take_updates().is_empty());
        c.snapshot(&[item("idle", None)]);
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::Withdraw(_)]));
    }

    #[test]
    fn queued_permission_notifies_before_and_after_reaching_queue_head() {
        let mut c = AlertController::default();
        let first = item("waiting", Some("first"));
        let second = item("waiting", Some("second"));
        c.snapshot(std::slice::from_ref(&first));
        let notice = || serde_json::from_str(
            r#"{"kind":"wait","wait":"permission","agentId":"agent","requestId":"second"}"#,
        ).unwrap();
        c.notice(notice(), std::slice::from_ref(&first));
        let updates = c.take_updates();
        let [AlertUpdate::Notice(event)] = updates.as_slice() else { panic!("{updates:?}") };
        assert_eq!(event.permission_id(), Some("second"));
        assert_eq!(event.session().permission_id.as_deref(), Some("first"));
        c.snapshot(std::slice::from_ref(&second));
        let updates = c.take_updates();
        let [AlertUpdate::Fallback(AgentEvent::Permission(event))] = updates.as_slice()
        else { panic!("{updates:?}") };
        assert_eq!(event.permission_id.as_deref(), Some("second"));
        let cleared = item("waiting", None);
        c.snapshot(std::slice::from_ref(&cleared));
        c.notice(notice(), &[cleared]);
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::Withdraw(_)]));
    }

    #[test]
    fn typed_notices_target_main_sessions_and_apply_preferences() {
        use crate::rules::alerts::{AlertPreferences, alert_reason, alert_text, batch_summary};
        let parse = |json| serde_json::from_str(json).unwrap();
        let item = item("waiting", None);
        let sessions = std::slice::from_ref(&item);
        let wait = notice_event(
            parse(r#"{"kind":"wait","agentId":"agent","wait":"question","reason":"Pode?"}"#),
            sessions,
        ).unwrap();
        assert_eq!(alert_text(&wait, |key| key), "notify_question");
        assert_eq!(alert_reason(&wait).as_deref(), Some("Pode?"));
        assert!(notice_event(parse(r#"{"agentId":"agent"}"#), sessions).is_none());
        assert!(matches!(
            notice_event(parse(r#"{"kind":"wait","agentId":"agent","wait":"terminal","repeat":true}"#), sessions),
            Some(AgentEvent::NoticeWait(_, crate::model::WaitKind::Terminal, ..))
        ));
        let prefs = AlertPreferences { enabled: true, wait: true, done: true, min_turn: true };
        assert!(prefs.allows(&wait, None));
        assert!(!prefs.allows(&wait, Some(&item.key)));
        let mut other = item.key.clone();
        other.origin = "http://other".into();
        assert!(prefs.allows(&wait, Some(&other)));
        assert!(!batch_summary(2));
        assert!(batch_summary(3));
    }

    #[test]
    fn typed_done_requires_idle_final_text_and_respects_duration() {
        use crate::rules::alerts::{AlertPreferences, alert_text};
        let parse = |json| serde_json::from_str(json).unwrap();
        let idle = item("idle", None);
        let sessions = std::slice::from_ref(&idle);
        let valid = r#"{"kind":"done","agentId":"agent","excerpt":"Pronto."}"#;
        for status in ["working", "waiting", "done", "offline"] {
            assert!(notice_event(parse(valid), &[item(status, None)]).is_none());
        }
        let mut sub = idle.clone();
        sub.sub = true;
        assert!(notice_event(parse(valid), &[sub]).is_none());
        assert!(notice_event(
            parse(r#"{"kind":"done","agentId":"other","excerpt":"Pronto."}"#),
            sessions,
        ).is_none());
        for json in [
            r#"{"kind":"done","agentId":"agent"}"#,
            r#"{"kind":"done","agentId":"agent","excerpt":" \n "}"#,
            r#"{"kind":"done","agentId":"agent","excerpt":"Pronto.","interrupted":true}"#,
            r#"{"kind":"activity","agentId":"agent","excerpt":"Pronto."}"#,
        ] {
            assert!(notice_event(parse(json), sessions).is_none(), "{json}");
        }
        let prefs = AlertPreferences { enabled: true, done: true, min_turn: true, ..Default::default() };
        for (duration, allowed) in [(None, true), (Some(29_999), false), (Some(30_000), true)] {
            let done = notice_event(
                serde_json::from_value(serde_json::json!({
                    "kind":"done", "agentId":"agent", "excerpt":"  Pronto.  ", "durationMs":duration, "repeat":true
                })).unwrap(),
                sessions,
            ).unwrap();
            assert_eq!(alert_text(&done, |_| unreachable!("excerpt não é chave")), "Pronto.");
            assert_eq!(prefs.allows(&done, None), allowed);
            assert!((AlertPreferences { min_turn: false, ..prefs }).allows(&done, None));
        }
    }

    #[test]
    fn leaving_idle_discards_pending_done() {
        let mut c = AlertController::default();
        let idle = item("idle", None);
        let sessions = std::slice::from_ref(&idle);
        c.snapshot(sessions);
        c.notice(serde_json::from_str(
            r#"{"kind":"done","agentId":"agent","excerpt":"Pronto."}"#,
        ).unwrap(), sessions);
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::Notice(AgentEvent::NoticeDone(..))]));
        c.snapshot(&[item("waiting", None)]);
        assert!(matches!(c.take_updates().as_slice(), [AlertUpdate::WithdrawDone(key)] if *key == idle.key));
    }
}
