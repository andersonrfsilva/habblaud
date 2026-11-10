use serde_json::json;
use std::collections::BTreeMap;
use zaplaud_core::{controller::cast, model::*, rules::composer::*};

fn item() -> SessionItem {
    SessionItem {
        key: SessionKey {
            origin: "https://fixture.test".into(),
            provider: "zeroclaw".into(),
            account: "zeroclaw".into(),
            session_id: "00000000-0000-4000-8000-000000000008".into(),
        },
        agent_id: Some("agent".into()),
        title: "Zélia".into(),
        project: Some("/fixture/projeto".into()),
        status: Some("working".into()),
        can_message: true,
        ..SessionItem::default()
    }
}
fn connected(selected: &SessionItem) -> ReadingState {
    ReadingState {
        connection: "connected".into(),
        messages_allowed: true,
        message_guard: true,
        selected: Some(selected.clone()),
        sessions: vec![selected.clone()],
        ..ReadingState::default()
    }
}
#[test]
fn resource_gates_preserve_omp_only_tools_and_access() {
    for provider in ["claude", "codex", "omp", "zeroclaw", "unknown"] {
        let mut selected = item();
        selected.key.provider = provider.into();
        let state = connected(&selected);
        assert_eq!(
            session_controls_available(&selected, &state),
            matches!(provider, "omp" | "zeroclaw")
        );
        selected.sub = true;
        assert!(!session_controls_available(&selected, &state));
        selected.sub = false;
        selected.agent_id = None;
        assert!(!session_controls_available(&selected, &state));
        selected.agent_id = Some("agent".into());
        selected.can_message = false;
        assert!(!session_controls_available(
            &selected,
            &connected(&selected)
        ));
        selected.can_message = true;
        for field in ["connection", "messages", "guard"] {
            let mut state = connected(&selected);
            match field {
                "connection" => state.connection = "offline".into(),
                "messages" => state.messages_allowed = false,
                _ => state.message_guard = false,
            }
            assert!(!session_controls_available(&selected, &state));
        }
    }
    assert!(!supports_session_control(""));
    assert!(delivery_modes("").is_empty());
}
#[test]
fn zero_claw_delivery_normalizes_only_at_send() {
    assert_eq!(delivery_modes("zeroclaw"), ["followUp", "steer"]);
    for preference in ["aside", "invalid", "followUp"] {
        assert_eq!(deliver_as("zeroclaw", preference), Some("followUp"));
    }
    assert_eq!(deliver_as("zeroclaw", "steer"), Some("steer"));
    assert_eq!(deliver_as("omp", "aside"), Some("aside"));
    assert_eq!(deliver_as("claude", "aside"), None);
}
#[test]
fn both_context_positions_reject_zero_and_positive_zero_claw_values() {
    for tokens in [None, Some(0), Some(120000)] {
        assert!(
            tokens
                .zip(Some(200000))
                .filter(|_| reliable_context("zeroclaw"))
                .is_none()
        );
    }
    assert!(reliable_context("omp"));
    assert!(context_ring(0, 200000).is_some());
    assert!(context_ring(120000, 200000).is_some());
}
#[test]
fn runtime_and_http_summary_fail_closed_without_tiny() {
    assert_eq!(
        catalog_runtime(&json!({"provider":"zeroclaw","models":[],"defaultModel":""})),
        Some("zeroclaw")
    );
    assert_eq!(catalog_runtime(&json!({})), None);
    assert_eq!(catalog_runtime(&json!({"provider":"unknown"})), None);
    assert!(summary_available(true, None));
    assert!(!summary_available(false, None));
    assert!(summary_available(
        false,
        Some(&BTreeMap::from([("tiny".into(), "legacy/model".into())]))
    ));
}
#[test]
fn resume_never_crosses_runtimes_and_preserves_identity() {
    let mut closed = item();
    closed.agent_id = None;
    let mut state = cast::State {
        runtime: Some("zeroclaw".into()),
        ..cast::State::default()
    };
    assert!(state.can_resume(&closed));
    assert!(!state.can_resume(&item()));
    state.runtime = Some("omp".into());
    assert!(!state.can_resume(&closed));
    state.runtime = None;
    assert!(!state.can_resume(&closed));
    let key = &closed.key;
    assert_eq!(
        SessionKey::deserialize(&key.serialize().unwrap()).unwrap(),
        *key
    );
}
#[test]
fn new_navigation_uses_snapshot_without_old_session_and_rejects_other_identity() {
    let previous = item();
    let mut next = previous.clone();
    next.key.session_id = "new".into();
    assert_eq!(
        new_session_target(&[next.clone()], &previous.key, "agent"),
        Some(&next)
    );
    for field in ["provider", "account", "origin"] {
        let mut other = next.clone();
        match field {
            "provider" => other.key.provider = "omp".into(),
            "account" => other.key.account = "other".into(),
            _ => other.key.origin = "other".into(),
        }
        assert!(new_session_target(&[other], &previous.key, "agent").is_none());
    }
}

#[test]
fn historical_owner_preserves_provider_and_account_boundaries() {
    let session = item();
    let owner = json!({"id":"zelia","name":"Zélia","account":"zeroclaw","last":{"/fixture/projeto":session.key.session_id}});
    let mut collection = SessionCollection {
        recent: vec![session],
        cast: vec![owner.clone()],
        ..SessionCollection::default()
    };
    assert_eq!(collection.sessions()[0].cast.as_deref(), Some("zelia"));
    collection.recent[0].key.account = "other".into();
    assert!(collection.sessions()[0].cast.is_none());
    collection.recent[0].key.account = "zeroclaw".into();
    collection.cast[0]["provider"] = json!("omp");
    assert!(collection.sessions()[0].cast.is_none());
}

#[test]
fn http_tenant_quota_keeps_snapshot_windows_without_inventing_provider_limits() {
    let account = AccountQuota::parse(&json!({"id":"zeroclaw","provider":"zeroclaw","usageStatus":"ok","usage":{"source":"omp","quota":true,"fetchedAt":1000,"fiveHour":{"utilization":60,"resetsAt":2000},"sevenDay":{"utilization":100,"resetsAt":3000}}})).unwrap();
    assert_eq!(account.provider, "zeroclaw");
    assert!(!account.no_quota);
    assert_eq!(account.groups.len(), 1);
    assert_eq!(account.groups[0].limits[0].utilization, Some(60.0));
    assert_eq!(account.groups[0].limits[1].utilization, Some(100.0));
    assert_eq!(account.groups[0].limits[1].resets_at, Some(3000));
}
