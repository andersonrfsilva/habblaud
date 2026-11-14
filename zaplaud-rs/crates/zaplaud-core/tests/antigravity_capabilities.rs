use zaplaud_core::{model::*, rules::composer::*};

fn item() -> SessionItem {
    SessionItem {
        key: SessionKey {
            origin: "https://fixture.test".into(),
            provider: "antigravity".into(),
            account: "account".into(),
            session_id: "00000000-0000-4000-8000-000000000008".into(),
        },
        agent_id: Some("agent".into()),
        project: Some("/fixture/project".into()),
        can_message: true,
        ..SessionItem::default()
    }
}
fn command(provider: &str, kind: &str, value: &str) -> (State, Vec<Effect>) {
    let mut selected = item();
    selected.key.provider = provider.into();
    let mut state = State::default();
    state.visible = true;
    state.composer.key = Some(selected.key);
    reduce(state, Event::Command { agent_id: "agent".into(), kind: kind.into(), value: value.into(), eligible: true })
}
#[test]
fn models_and_tool_boundaries() {
    assert!(is_agy_model("agy/default"));
    for model in ["agy/", "agy", "google-antigravity/x"] { assert!(!is_agy_model(model)); }
    for (provider, model, crosses) in [("omp", "agy/default", true), ("omp", "google-antigravity/x", false), ("antigravity", "agy/default", false), ("antigravity", "google-antigravity/x", true)] {
        assert_eq!(crosses_tool(provider, model), crosses);
        assert!(model_choice(provider));
    }
    for provider in ["claude", "codex", "zeroclaw"] {
        assert!(!crosses_tool(provider, "agy/default"));
        assert!(!crosses_tool(provider, "omp/model"));
        assert!(!model_choice(provider));
    }
    assert_eq!(provider_label("agy"), "Antigravity");
}
#[test]
fn controls_without_delivery_modes() {
    let selected = item();
    assert!(supports_session_control("antigravity"));
    assert!(delivery_modes("antigravity").is_empty());
    assert!(!deliver_modes_available(&selected));
    for mode in ["aside", "followUp", "steer"] { assert_eq!(deliver_as("antigravity", mode), None); }
    for kind in ["model", "thinking", "shake"] {
        assert_eq!(!command("antigravity", kind, "agy/default").1.is_empty(), kind == "model");
        assert!(!command("omp", kind, "model").1.is_empty());
        assert!(command("zeroclaw", kind, "model").1.is_empty());
    }
    assert_eq!(delivery_modes("omp"), ["aside", "followUp", "steer"]);
    assert_eq!(delivery_modes("zeroclaw"), ["followUp", "steer"]);
}
#[test]
fn command_reasons_come_from_server_and_switch_disappearance_is_silent() {
    for polled in [true, false] {
        let (state, effects) = command("antigravity", "model", "agy/default");
        let Effect::OmpControl { token, .. } = effects[0] else { panic!("missing command") };
        let (state, _) = reduce(state, Event::CommandResult {
            token, id: Some("message".into()), status: polled.then(|| "failed".into()),
            error: polled.then(|| "server refusal".into()),
            failure: (!polled).then(|| Failure { status: Some(409), reason: Some("server refusal".into()), ..Failure::default() }),
        });
        let result = state.composer.command_result.unwrap();
        assert_eq!(result.status, "failed");
        assert_eq!(result.reason.as_deref(), Some("server refusal"));
    }
    let (mut state, effects) = command("omp", "model", "agy/default");
    let Effect::OmpControl { token, .. } = effects[0] else { panic!("missing command") };
    state.composer.key = None;
    state.composer.command_result = None;
    let (state, effects) = reduce(state, Event::CommandResult { token, id: Some("message".into()), status: Some("queued".into()), error: None, failure: None });
    assert!(effects.is_empty());
    assert!(state.composer.command_result.is_none());
}

#[test]
fn historical_owner_requires_unique_matching_last_but_not_account() {
    use serde_json::json;
    let session = item();
    let owner = json!({"id":"owner", "provider":"antigravity", "account":"different", "last":{"/fixture/project":session.key.session_id}});
    let mut collection = SessionCollection {
        recent: vec![session],
        cast: vec![owner.clone()],
        ..SessionCollection::default()
    };
    assert_eq!(collection.sessions()[0].cast.as_deref(), Some("owner"));
    collection.cast.push(owner);
    assert!(collection.sessions()[0].cast.is_none());
    collection.cast.pop();
    collection.cast[0]["provider"] = json!("omp");
    assert!(collection.sessions()[0].cast.is_none());
    collection.cast[0]["provider"] = json!("antigravity");
    collection.cast[0]["last"] = json!({});
    assert!(collection.sessions()[0].cast.is_none());
}
#[test]
fn resume_uses_antigravity_catalog_independently_of_runtime() {
    use serde_json::json;
    use zaplaud_core::controller::cast;
    let mut closed = item();
    closed.agent_id = None;
    for runtime in [None, Some("omp"), Some("zeroclaw")] {
        let mut state = cast::State {
            runtime: runtime.map(str::to_owned),
            catalog: CastCatalog::parse(&json!({"agyModels":["agy/default"]})),
            ..cast::State::default()
        };
        assert!(state.can_resume(&closed));
        assert!(!state.can_resume(&item()));
        state.catalog = CastCatalog::parse(&json!({"agyModels":[]}));
        assert!(!state.can_resume(&closed));
    }
    for value in [json!({}), json!({"agyModels":null}), json!({"agyModels":"agy/default"})] {
        assert!(!CastCatalog::parse(&value).agy);
    }
    for provider in ["omp", "zeroclaw"] {
        closed.key.provider = provider.into();
        assert!(can_resume_cast(&closed, Some(provider), false));
        assert!(!can_resume_cast(&closed, None, true));
    }
    closed.key.provider = "antigravity".into();
    closed.project = Some("relative".into());
    assert!(!can_resume_cast(&closed, None, true));
    closed.project = Some("/fixture/project".into());
    closed.key.session_id = "invalid".into();
    assert!(!can_resume_cast(&closed, None, true));
}

#[test]
fn cast_availability_uses_catalog_agy_flag_for_resume() {
    use std::collections::{HashMap, HashSet};
    let mut closed = item();
    closed.agent_id = None;
    for agy in [false, true] {
        for runtime in [None, Some("omp"), Some("zeroclaw")] {
            let available = cast_availability(
                Some(&closed), &[], &HashSet::new(), &HashMap::new(), runtime, agy,
            );
            assert_eq!(available.resume, agy);
        }
        for provider in ["omp", "zeroclaw"] {
            let mut legacy = closed.clone();
            legacy.key.provider = provider.into();
            assert!(cast_availability(
                Some(&legacy), &[], &HashSet::new(), &HashMap::new(), Some(provider), agy,
            ).resume);
            assert!(!cast_availability(
                Some(&legacy), &[], &HashSet::new(), &HashMap::new(), None, agy,
            ).resume);
        }
    }
}
