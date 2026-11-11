use crate::{
    api::conversation_path,
    model::{
        CachedConversation, ConversationState, ServerEvent, SessionItem, StreamEvent, TerminalEntry,
    },
};
use std::collections::BTreeMap;

/// Estado de leitura independente de rede, widgets e disco. Cada abertura invalida respostas anteriores.
#[derive(Debug, Default)]
pub struct ConversationController {
    pub selected: Option<SessionItem>,
    pub conversation: ConversationState,
    pub summaries: BTreeMap<String, String>,
    pub connection: String,
    pub error: Option<String>,
    pub unauthorized: bool,
    generation: u64,
    initialized: bool,
    cached: bool,
}
impl ConversationController {
    pub fn open(
        &mut self,
        item: SessionItem,
        cache: Option<CachedConversation>,
    ) -> Result<(u64, String), &'static str> {
        self.close();
        self.connection = "connecting".into();
        if !item.sub
            && let Some(cache) = cache
        {
            self.cached = !cache.entries.is_empty();
            self.conversation.init(cache.entries, cache.truncated)?;
            self.summaries = cache.summaries;
        }
        let entries = self.entries();
        let path = conversation_path(&item, &entries);
        self.selected = Some(item);
        Ok((self.generation, path))
    }
    pub fn close(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        self.selected = None;
        self.conversation = ConversationState::default();
        self.summaries.clear();
        self.initialized = false;
        self.cached = false;
        self.error = None;
        self.unauthorized = false;
        self.connection = "offline".into();
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn entries(&self) -> Vec<TerminalEntry> {
        self.conversation.snapshot().cloned().collect()
    }
    pub fn cache_ready(&self) -> bool {
        self.cached
    }
    /// Retorna true apenas quando o conteúdo mudou. Eventos atrasados nunca contaminam outra sessão.
    pub fn apply(&mut self, generation: u64, event: StreamEvent) -> Result<bool, &'static str> {
        if generation != self.generation || self.selected.is_none() {
            return Ok(false);
        }
        match event {
            StreamEvent::Connected => {
                self.connection = "connected".into();
                if self.error.as_deref() == Some("network") {
                    self.error = None;
                }
            }
            StreamEvent::Reconnecting { .. } => self.connection = "reconnecting".into(),
            StreamEvent::Failure { status, .. } => {
                self.connection = "failed".into();
                self.unauthorized = status == Some(401);
                self.error = Some(
                    match status {
                        Some(-1) => "oversize",
                        Some(403) => "forbidden",
                        Some(404) => "missing",
                        _ => "network",
                    }
                    .into(),
                );
            }
            StreamEvent::Data {
                event_type, data, ..
            } => {
                let origin = self
                    .selected
                    .as_ref()
                    .map(|s| s.key.origin.as_str())
                    .unwrap_or_default();
                let result = match ServerEvent::parse(origin, &event_type, &data) {
                    Ok(ServerEvent::ConversationInit(init)) => {
                        let result = if init.after_present && self.cached {
                            self.conversation.append(init.entries)
                        } else {
                            self.conversation.init(init.entries, init.truncated)
                        };
                        self.initialized = true;
                        self.cached = self.selected.as_ref().is_some_and(|s| !s.sub);
                        result
                    }
                    Ok(ServerEvent::ConversationAppend(entries)) if self.initialized => {
                        self.conversation.append(entries)
                    }
                    Ok(_) => return Ok(false),
                    Err(_) => Err("oversize"),
                };
                if result.is_err() {
                    self.error = Some("oversize".into());
                    self.connection = "failed".into();
                }
                result?;
                return Ok(true);
            }
            _ => {}
        }
        Ok(false)
    }
}

/// Confirma cada init sem confundir uma reescrita do transcript com outra sessão.
pub fn snapshot_matches_session(data: &str, item: &SessionItem) -> Result<bool, serde_json::Error> {
    let snapshot: serde_json::Value = serde_json::from_str(data)?;
    Ok(snapshot
        .get("agents")
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .any(|agent| {
            let parsed = SessionItem::parse(&item.key.origin, agent, None, 0, 0);
            agent.get("kind").and_then(serde_json::Value::as_str) == Some("main")
                && parsed.is_some_and(|s| s.key == item.key && s.agent_id == item.agent_id)
        }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn item(id: &str) -> SessionItem {
        SessionItem {
            key: crate::model::SessionKey {
                origin: "http://localhost".into(),
                account: "a".into(),
                provider: "omp".into(),
                session_id: id.into(),
            },
            agent_id: Some(id.into()),
            ..SessionItem::default()
        }
    }
    fn data(kind: &str, value: serde_json::Value) -> StreamEvent {
        StreamEvent::Data {
            event_type: kind.into(),
            data: value.to_string(),
            first_connection: true,
        }
    }
    #[test]
    fn resume_deduplicates_and_replacement_resets_truncation() {
        let mut controller = ConversationController::default();
        let cache = CachedConversation {
            entries: vec![TerminalEntry {
                id: "a /?".into(),
                kind: "assistant".into(),
                at: Some(42),
                ..TerminalEntry::default()
            }],
            truncated: true,
            summaries: BTreeMap::from([("cached".into(), "summary".into())]),
        };
        let (generation, path) = controller.open(item("one"), Some(cache)).unwrap();
        assert!(path.ends_with("?after=a%20%2F%3F&at=42"));
        assert_eq!(controller.summaries["cached"], "summary");
        controller
            .apply(generation, data("append", json!([{"id":"ignored"}])))
            .unwrap();
        assert_eq!(controller.entries().len(), 1);
        controller
            .apply(
                generation,
                data(
                    "init",
                    json!({"after":null,"entries":[{"id":"b","kind":"user"}]}),
                ),
            )
            .unwrap();
        assert_eq!(controller.entries().len(), 2);
        assert!(controller.conversation.truncated);
        controller
            .apply(
                generation,
                data("init", json!({"entries":[{"id":"c","kind":"user"}]})),
            )
            .unwrap();
        assert_eq!(controller.entries()[0].id, "c");
        assert!(!controller.conversation.truncated);
    }
    #[tokio::test]
    async fn fake_stream_switch_rejects_previous_generation_and_handles_401() {
        let mut controller = ConversationController::default();
        let (old, _) = controller.open(item("one"), None).unwrap();
        let (sender, mut stream) = tokio::sync::mpsc::unbounded_channel();
        sender
            .send((old, data("init", json!({"entries":[{"id":"stale"}]}))))
            .unwrap();
        let (current, _) = controller.open(item("two"), None).unwrap();
        let (token, event) = stream.recv().await.unwrap();
        assert!(!controller.apply(token, event).unwrap());
        assert!(controller.entries().is_empty());
        controller
            .apply(
                current,
                StreamEvent::Failure {
                    status: Some(401),
                    retry_after: None,
                },
            )
            .unwrap();
        assert!(controller.unauthorized);
        controller.close();
        assert!(controller.selected.is_none());
    }
    #[test]
    fn subagents_never_resume_from_persistent_cache_and_invalid_data_stops_reading() {
        let mut controller = ConversationController::default();
        let mut sub = item("sub");
        sub.sub = true;
        let (token, path) = controller
            .open(
                sub,
                Some(CachedConversation {
                    entries: vec![TerminalEntry {
                        id: "old".into(),
                        at: Some(1),
                        ..TerminalEntry::default()
                    }],
                    ..CachedConversation::default()
                }),
            )
            .unwrap();
        assert!(!path.contains('?'));
        assert!(controller.entries().is_empty());
        assert!(
            controller
                .apply(
                    token,
                    StreamEvent::Data {
                        event_type: "init".into(),
                        data: "broken".into(),
                        first_connection: true
                    }
                )
                .is_err()
        );
        assert_eq!(controller.error.as_deref(), Some("oversize"));
    }
}
