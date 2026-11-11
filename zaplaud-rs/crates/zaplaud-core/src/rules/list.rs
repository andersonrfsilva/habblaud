use crate::model::{ReadingState, SessionItem};
use std::collections::HashMap;

/// A última resposta prevalece sobre o evento; empates mantêm a ordem original.
pub fn by_last_message(sessions: &[SessionItem]) -> Vec<SessionItem> {
    let mut ordered = sessions.to_vec();
    ordered
        .sort_by_key(|s| std::cmp::Reverse(s.replies.last().copied().or(s.last_at).unwrap_or(0)));
    ordered
}

pub fn unread_count(replies: &[i64], seen_at: Option<i64>) -> usize {
    seen_at.map_or(0, |seen| replies.iter().filter(|at| **at > seen).count())
}

pub fn is_loading(state: &ReadingState, needs_login: bool) -> bool {
    !needs_login && state.connection == "connecting" && state.sessions.is_empty()
}

pub fn updated_seen(
    seen: &HashMap<String, i64>,
    ids: &[String],
    open_id: Option<&str>,
    now: i64,
) -> Option<HashMap<String, i64>> {
    let mut next = seen.clone();
    for id in ids {
        next.entry(id.clone()).or_insert(now);
    }
    if let Some(id) = open_id
        && next.get(id).copied().unwrap_or(0) < now
    {
        next.insert(id.into(), now);
    }
    (next != *seen).then_some(next)
}

/// O iterador mantém a ordem de cadastro das origens, sem deduplicar sessões.
pub fn merge_sessions<'a>(
    states: impl IntoIterator<Item = (&'a str, &'a ReadingState)>,
    filter: Option<&str>,
) -> Vec<SessionItem> {
    states
        .into_iter()
        .filter(|(origin, _)| filter.is_none_or(|f| *origin == f))
        .flat_map(|(_, state)| state.sessions.iter().cloned())
        .collect()
}

pub fn search_matches(session: &SessionItem, query: &str) -> bool {
    let query = query.to_lowercase();
    [
        Some(session.title.as_str()),
        session.character_name.as_deref(),
        session.agent_name.as_deref(),
        session.project.as_deref(),
        Some(&session.key.provider),
        Some(&session.key.account),
    ]
    .into_iter()
    .flatten()
    .any(|s| s.to_lowercase().contains(&query))
}

pub fn visible_sessions(sessions: &[SessionItem], recent: bool, query: &str) -> Vec<SessionItem> {
    by_last_message(
        &sessions
            .iter()
            .filter(|s| (recent || s.agent_id.is_some()) && search_matches(s, query))
            .cloned()
            .collect::<Vec<_>>(),
    )
}

pub fn server_label(origin: &str) -> String {
    url::Url::parse(origin)
        .map(|url| {
            let host = url.host_str().unwrap_or_default();
            match url.port() {
                Some(port) => format!("{host}:{port}"),
                None => host.into(),
            }
        })
        .unwrap_or_else(|_| origin.into())
}

pub fn combined_connection<'a>(
    states: impl IntoIterator<Item = (&'a ReadingState, bool)>,
) -> &'static str {
    let mut connecting = false;
    for (state, needs_login) in states {
        if needs_login || state.connection == "offline" {
            return "offline";
        }
        connecting |= state.connection == "connecting";
    }
    if connecting {
        "connecting"
    } else {
        "connected"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn session(id: &str, last: Option<i64>, replies: &[i64]) -> SessionItem {
        SessionItem {
            title: id.into(),
            last_at: last,
            replies: replies.into(),
            ..Default::default()
        }
    }
    #[test]
    fn loading_requires_an_authenticated_empty_connection() {
        let mut state = ReadingState {
            connection: "connecting".into(),
            ..Default::default()
        };
        assert!(is_loading(&state, false));
        assert!(!is_loading(&state, true));
        state.sessions.push(session("cached", None, &[]));
        assert!(!is_loading(&state, false));
        state.sessions.clear();
        state.connection = "offline".into();
        assert!(!is_loading(&state, false));
    }
    #[test]
    fn unread_is_strict_and_keeps_duplicates() {
        assert_eq!(unread_count(&[10, 20, 20, 30], None), 0);
        assert_eq!(unread_count(&[10, 20, 20, 30], Some(10)), 3);
        assert_eq!(unread_count(&[10, 20], Some(20)), 0);
    }
    #[test]
    fn marks_initialize_without_regressing() {
        let marks = HashMap::from([("old".into(), 100)]);
        let next = updated_seen(&marks, &["new".into()], Some("old"), 90).unwrap();
        assert_eq!(next["new"], 90);
        assert_eq!(next["old"], 100);
        assert!(updated_seen(&next, &["new".into()], Some("old"), 90).is_none());
        assert_eq!(
            updated_seen(&next, &[], Some("absent"), 110).unwrap()["absent"],
            110
        );
    }
    #[test]
    fn ordering_uses_last_reply_not_max_or_event() {
        let ordered = by_last_message(&[
            session("fallback", Some(50), &[]),
            session("reply", Some(95), &[100, 90]),
            session("empty", None, &[]),
            session("tie", Some(90), &[]),
        ]);
        assert_eq!(
            ordered.iter().map(|s| s.title.as_str()).collect::<Vec<_>>(),
            ["reply", "tie", "fallback", "empty"]
        );
    }
    #[test]
    fn servers_keep_identity_and_order() {
        let a = ReadingState {
            sessions: vec![session("a", None, &[])],
            ..Default::default()
        };
        let b = ReadingState {
            sessions: vec![session("b", None, &[])],
            ..Default::default()
        };
        assert_eq!(merge_sessions([("a", &a), ("b", &b)], None).len(), 2);
        assert_eq!(
            merge_sessions([("a", &a), ("b", &b)], Some("b"))[0].title,
            "b"
        );
        assert!(merge_sessions([("a", &a)], Some("unknown")).is_empty());
        assert_eq!(server_label("https://example.com:443"), "example.com");
        assert_eq!(server_label("http://[::1]:14785"), "[::1]:14785");
    }
    #[test]
    fn recent_includes_open_and_search_does_not_trim() {
        let mut open = session("Title", None, &[]);
        open.agent_id = Some("agent".into());
        open.project = Some("/Project/Deep".into());
        let all = [open, session("closed", None, &[])];
        assert_eq!(visible_sessions(&all, false, "").len(), 1);
        assert_eq!(visible_sessions(&all, true, "").len(), 2);
        assert_eq!(visible_sessions(&all, true, "PROJECT").len(), 1);
        assert!(visible_sessions(&all, true, " Title").is_empty());
    }
}
