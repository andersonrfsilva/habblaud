use crate::model::{ReadingState, SessionCollection, SessionItem, SessionKey, Snapshot};
use crate::rules::list::{unread_count, updated_seen};
use std::collections::HashMap;

/// Estado de uma origem. Rede e armazenamento ficam nas bordas do controlador.
#[derive(Debug)]
pub struct ReadingController {
    pub origin: String,
    pub state: ReadingState,
    pub repository: SessionCollection,
    pub seen: HashMap<String, i64>,
    pub needs_login: bool,
    restore: Option<SessionKey>,
}
impl ReadingController {
    pub fn new(origin: String, seen: HashMap<String, i64>, restore: Option<SessionKey>) -> Self {
        Self {
            origin,
            state: ReadingState::default(),
            repository: SessionCollection::default(),
            seen,
            needs_login: false,
            restore,
        }
    }
    pub fn apply_stream_event(
        &mut self,
        event: crate::model::StreamEvent,
        now: i64,
    ) -> Result<(), serde_json::Error> {
        use crate::model::StreamEvent;
        match event {
            StreamEvent::Connected => self.connected(),
            StreamEvent::Failure { status, .. } => self.failure(status),
            StreamEvent::Data {
                event_type, data, ..
            } if event_type == "snapshot" => self.snapshot_data(&data, now)?,
            // Feed, notice e notificações de backoff não alteram ReadingState no Kotlin.
            _ => {}
        }
        Ok(())
    }
    pub fn connected(&mut self) {
        self.state.connection = "connected".into();
        if self.state.error.as_deref() == Some("network") {
            self.state.error = None;
        }
    }
    pub fn failure(&mut self, status: Option<i32>) {
        if status == Some(401) {
            self.needs_login = true;
            return;
        }
        self.state.stream_failure(status, false);
    }
    pub fn snapshot(&mut self, snapshot: Snapshot) {
        let sessions = self.repository.snapshot(snapshot);
        self.update_sessions(sessions);
    }
    pub fn snapshot_data(&mut self, data: &str, now: i64) -> Result<(), serde_json::Error> {
        let sessions = self.repository.parse_snapshot(&self.origin, data, now)?;
        self.update_sessions(sessions);
        Ok(())
    }
    fn update_sessions(&mut self, sessions: Vec<SessionItem>) {
        if let Some(selected) = &self.state.selected
            && selected.agent_id.is_some()
            && sessions
                .iter()
                .any(|s| s.agent_id == selected.agent_id && s.key != selected.key)
        {
            self.close();
            self.state.error = Some("changed".into());
        }
        if let Some(selected) = self.state.selected.as_mut() {
            if selected.sub {
                let live = sessions
                    .iter()
                    .flat_map(|s| &s.subagents)
                    .find(|sub| Some(&sub.id) == selected.agent_id.as_ref());
                selected.status = Some(if live.is_some() { "working" } else { "done" }.into());
                if let Some(activity) = live.and_then(|s| s.activity.clone()) {
                    selected.activity = Some(activity);
                }
            } else if let Some(current) = sessions.iter().find(|s| s.key == selected.key) {
                *selected = current.clone();
            } else {
                selected.agent_id = None;
                selected.can_message = false;
            }
        }
        self.state.sessions = sessions;
        if let Some(key) = self.restore.clone()
            && let Some(item) = self.state.sessions.iter().find(|s| s.key == key).cloned()
        {
            self.restore = None;
            self.open(item);
        }
        self.refresh_unread();
    }
    pub fn open_key(&mut self, key: SessionKey) {
        if key.origin != self.origin {
            return;
        }
        if let Some(item) = self.state.sessions.iter().find(|s| s.key == key).cloned() {
            self.open(item);
        } else {
            self.restore = Some(key);
        }
    }
    pub fn open(&mut self, item: SessionItem) {
        if item.key.origin != self.origin {
            return;
        }
        self.state.selected = Some(item);
        self.state.entries.clear();
        self.state.truncated = false;
        self.state.reduced = false;
        self.state.error = None;
        self.state.reading_error = None;
        self.refresh_unread();
    }
    pub fn close(&mut self) {
        self.restore = None;
        self.state.selected = None;
        self.state.entries.clear();
        self.state.error = None;
        self.state.reading_error = None;
        self.refresh_unread();
    }
    pub fn refresh_unread(&mut self) {
        let open = self
            .state
            .selected
            .as_ref()
            .filter(|s| !s.sub)
            .map(|s| &s.key);
        let open_id = open.and_then(|key| seen_id(key).ok());
        if self.repository.server_time > 0 {
            let ids = self
                .state
                .sessions
                .iter()
                .filter(|s| s.agent_id.is_some())
                .filter_map(|s| seen_id(&s.key).ok())
                .collect::<Vec<_>>();
            if let Some(next) = updated_seen(
                &self.seen,
                &ids,
                open_id.as_deref(),
                self.repository.server_time,
            ) {
                self.seen = next;
            }
        }
        self.state.unread = self
            .state
            .sessions
            .iter()
            .filter(|s| Some(&s.key) != open)
            .filter_map(|s| {
                let count = unread_count(
                    &s.replies,
                    seen_id(&s.key)
                        .ok()
                        .and_then(|key| self.seen.get(&key).copied()),
                );
                (count > 0).then(|| (s.key.clone(), count))
            })
            .collect();
    }
}

pub fn seen_id(key: &SessionKey) -> Result<String, serde_json::Error> {
    use sha2::{Digest, Sha256};
    Ok(format!("{:x}", Sha256::digest(key.serialize()?.as_bytes())))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn snapshot(origin: &str, time: i64, reply: i64) -> Snapshot {
        Snapshot::parse_at(origin, &format!(r#"{{"serverTime":{time},"agents":[{{"kind":"main","id":"a","account":"test","sessionId":"s","title":"Title","replies":[{reply}]}}]}}"#),time).unwrap()
    }
    #[test]
    fn unauthorized_refresh_and_stream_request_login_without_changing_reading_state() {
        for stream in [false, true] {
            for connection in ["connecting", "connected"] {
                for error in [None, Some("changed"), Some("network")] {
                    let mut controller =
                        ReadingController::new("https://a".into(), HashMap::new(), None);
                    controller.state.connection = connection.into();
                    controller.state.error = error.map(str::to_owned);
                    controller.state.reading_error = Some("forbidden".into());
                    if stream {
                        controller
                            .apply_stream_event(
                                crate::model::StreamEvent::Failure {
                                    status: Some(401),
                                    retry_after: None,
                                },
                                0,
                            )
                            .unwrap();
                    } else {
                        controller.failure(Some(401));
                    }
                    assert!(controller.needs_login);
                    assert_eq!(controller.state.connection, connection);
                    assert_eq!(controller.state.error.as_deref(), error);
                    assert_eq!(controller.state.reading_error.as_deref(), Some("forbidden"));
                }
            }
        }
    }
    #[test]
    fn non_auth_failures_keep_error_classification_without_requesting_login() {
        for (status, error) in [
            (None, "network"),
            (Some(500), "network"),
            (Some(-1), "oversize"),
            (Some(403), "forbidden"),
            (Some(404), "missing"),
        ] {
            let mut controller =
                ReadingController::new("https://a".into(), HashMap::new(), None);
            controller.connected();
            controller.state.reading_error = Some("changed".into());
            controller
                .apply_stream_event(
                    crate::model::StreamEvent::Failure {
                        status,
                        retry_after: None,
                    },
                    0,
                )
                .unwrap();
            assert!(!controller.needs_login);
            assert_eq!(controller.state.connection, "offline");
            assert_eq!(controller.state.error.as_deref(), Some(error));
            assert_eq!(controller.state.reading_error.as_deref(), Some("changed"));
        }
    }
    #[test]
    fn fake_snapshots_mark_open_session_and_preserve_other_origin() {
        let mut a = ReadingController::new("http://a".into(), HashMap::new(), None);
        let mut b = ReadingController::new("http://b".into(), HashMap::new(), None);
        a.snapshot(snapshot("http://a", 100, 90));
        b.snapshot(snapshot("http://b", 100, 90));
        a.snapshot(snapshot("http://a", 120, 110));
        b.snapshot(snapshot("http://b", 120, 110));
        assert_eq!(a.state.unread.values().sum::<usize>(), 1);
        a.open(a.state.sessions[0].clone());
        assert!(a.state.unread.is_empty());
        assert_eq!(b.state.unread.len(), 1);
        a.snapshot(snapshot("http://a", 140, 130));
        assert!(a.state.unread.is_empty());
        a.close();
        a.snapshot(snapshot("http://a", 160, 150));
        assert_eq!(a.state.unread.len(), 1);
        a.failure(Some(401));
        assert!(a.needs_login);
        assert!(!b.needs_login);
        let merged = crate::rules::list::merge_sessions(
            [("http://a", &a.state), ("http://b", &b.state)],
            Some("http://b"),
        );
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].key.origin, "http://b");
    }
    #[test]
    fn deferred_restore_and_agent_identity_change() {
        let mut controller = ReadingController::new("http://a".into(), HashMap::new(), None);
        let item = snapshot("http://a", 100, 90).sessions.remove(0);
        controller.open_key(item.key.clone());
        assert!(controller.state.selected.is_none());
        controller.snapshot(snapshot("http://a", 100, 90));
        assert!(controller.state.selected.is_some());
        let mut changed = snapshot("http://a", 120, 110);
        changed.sessions[0].key.session_id = "different".into();
        controller.snapshot(changed);
        assert!(controller.state.selected.is_none());
        assert_eq!(controller.state.error.as_deref(), Some("changed"));
    }
}

#[cfg(test)]
mod fake_stream_tests {
    use super::*;
    use crate::model::StreamEvent;
    #[tokio::test]
    async fn stream_selection_unread_and_unauthorized_are_origin_scoped() {
        let (sender, mut stream) = tokio::sync::mpsc::channel(8);
        let snapshot = |time, reply| StreamEvent::Data {
            event_type: "snapshot".into(),
            first_connection: false,
            data: format!(
                r#"{{"serverTime":{time},"agents":[{{"kind":"main","id":"agent","account":"test","sessionId":"s","replies":[{reply}]}}]}}"#
            ),
        };
        sender.send(StreamEvent::Connected).await.unwrap();
        sender.send(snapshot(100, 90)).await.unwrap();
        sender.send(snapshot(120, 110)).await.unwrap();
        sender
            .send(StreamEvent::Failure {
                status: Some(401),
                retry_after: None,
            })
            .await
            .unwrap();
        drop(sender);
        let mut controller = ReadingController::new("https://a".into(), HashMap::new(), None);
        let other = ReadingController::new("https://b".into(), HashMap::new(), None);
        while let Some(event) = stream.recv().await {
            controller.apply_stream_event(event, 120).unwrap();
            if !controller.state.unread.is_empty() {
                controller.open(controller.state.sessions[0].clone());
                assert!(controller.state.unread.is_empty());
            }
        }
        assert!(controller.needs_login);
        assert!(!other.needs_login);
        assert!(controller.state.selected.is_some());
        controller.close();
        assert!(controller.state.selected.is_none());
    }
    #[test]
    fn subagents_only_include_working_and_keep_advisor_role() {
        let mut controller = ReadingController::new("https://a".into(), HashMap::new(), None);
        controller.snapshot_data(r#"{"agents":[
            {"id":"m","kind":"main","account":"a","sessionId":"s"},
            {"id":"adv","kind":"sub","parentId":"m","name":"Gandalf","status":"working","role":"__advisor","activity":{"text":"Usando advise"}},
            {"id":"wait","kind":"sub","parentId":"m","status":"waiting"},
            {"id":"done","kind":"sub","parentId":"m","status":"done"}
        ]}"#,100).unwrap();
        let subs = &controller.state.sessions[0].subagents;
        assert_eq!(subs.len(), 1);
        assert!(subs[0].advisor);
        assert_eq!(subs[0].name, "Gandalf");
        let sub = crate::model::sub_session(&controller.state.sessions[0], &subs[0]);
        controller.open(sub);
        controller.snapshot_data(r#"{"agents":[]}"#, 200).unwrap();
        assert_eq!(
            controller
                .state
                .selected
                .as_ref()
                .and_then(|s| s.status.as_deref()),
            Some("done")
        );
    }
}
