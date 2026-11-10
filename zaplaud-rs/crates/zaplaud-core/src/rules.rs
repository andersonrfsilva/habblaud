use crate::model::{AgentEvent, AgentWatch, SessionItem, SessionKey};
use std::collections::HashMap;

/// A última resposta prevalece sobre o último evento; empates preservam a ordem recebida.
pub fn by_last_message(sessions: &[SessionItem]) -> Vec<SessionItem> {
    let mut ordered: Vec<_> = sessions.iter().collect();
    ordered.sort_by_key(|item| {
        std::cmp::Reverse(item.replies.last().copied().or(item.last_at).unwrap_or(0))
    });
    ordered.into_iter().cloned().collect()
}

pub fn unread_count(replies: &[i64], seen_at: Option<i64>) -> usize {
    seen_at.map_or(0, |seen| replies.iter().filter(|at| **at > seen).count())
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
        next.insert(id.to_owned(), now);
    }
    (next != *seen).then_some(next)
}

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

#[cfg(test)]
mod tests {
    use super::*;

    fn session(title: &str, replies: &[i64], last_at: Option<i64>) -> SessionItem {
        SessionItem {
            title: title.into(),
            replies: replies.to_vec(),
            last_at,
            ..SessionItem::default()
        }
    }

    #[test]
    fn latest_reply_beats_later_events_and_uses_last_not_maximum() {
        let sessions = [
            session("event", &[], Some(70)),
            session("reply", &[100, 80], Some(1000)),
            session("unordered", &[999, 1], None),
        ];
        let titles: Vec<_> = by_last_message(&sessions)
            .into_iter()
            .map(|s| s.title)
            .collect();
        assert_eq!(titles, ["reply", "event", "unordered"]);
        assert_eq!(sessions[0].title, "event");
    }
    #[test]
    fn ties_are_stable_and_missing_time_is_zero() {
        let sessions = [
            session("zero", &[], None),
            session("tie-a", &[10], None),
            session("tie-b", &[], Some(10)),
            session("negative", &[], Some(-1)),
        ];
        let titles: Vec<_> = by_last_message(&sessions)
            .into_iter()
            .map(|s| s.title)
            .collect();
        assert_eq!(titles, ["tie-a", "tie-b", "zero", "negative"]);
        assert!(by_last_message(&[]).is_empty());
    }
    #[test]
    fn read_marks_initialize_without_retroactive_unread() {
        assert_eq!(unread_count(&[1, 2, 3], None), 0);
        assert_eq!(unread_count(&[1, 2, 3], Some(2)), 1);
        let seen = HashMap::from([("old".into(), 10)]);
        let ids = vec!["new".into(), "old".into()];
        let next = updated_seen(&seen, &ids, Some("old"), 20).unwrap();
        assert_eq!(
            next,
            HashMap::from([("old".into(), 20), ("new".into(), 20)])
        );
        assert!(updated_seen(&next, &ids, Some("old"), 20).is_none());
    }
    #[test]
    fn permission_takes_priority_over_turn_done_and_closed_sessions_do_not_notify() {
        let mut s = session("agent", &[], None);
        s.agent_id = Some("a".into());
        s.status = Some("working".into());
        let previous = watch_of(&[s.clone()]);
        s.status = Some("idle".into());
        s.permission_id = Some("p".into());
        assert!(matches!(
            agent_events(&previous, &[s.clone()]).as_slice(),
            [AgentEvent::Permission(_)]
        ));
        let previous = watch_of(&[SessionItem {
            status: Some("working".into()),
            ..s.clone()
        }]);
        assert!(matches!(
            agent_events(&previous, &[s.clone()]).as_slice(),
            [AgentEvent::TurnDone(_)]
        ));
        s.agent_id = None;
        assert!(agent_events(&previous, &[s]).is_empty());
    }
}
