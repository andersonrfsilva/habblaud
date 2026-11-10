use serde_json::{Value, json};
use std::collections::{HashMap, HashSet, VecDeque};
use zaplaud_core::{
    model::*,
    rules::{aside as a, composer as c, permission as p},
};
fn key(session: &str) -> SessionKey {
    SessionKey {
        origin: "https://example.test".into(),
        provider: "omp".into(),
        account: "a".into(),
        session_id: session.into(),
    }
}
fn file(uri: &str) -> Attachment {
    Attachment {
        uri: uri.into(),
        name: "arquivo".into(),
        mime: "text/plain".into(),
        size: Some(1),
        persistent: true,
        identity: None,
    }
}
#[derive(Default)]
struct Harness {
    state: c::State,
    locals: HashMap<SessionKey, MessageLocalState>,
    eligible: bool,
}
impl Harness {
    fn event(&mut self, event: c::Event) -> Vec<c::Effect> {
        let mut queue = VecDeque::from([event]);
        let mut outward = Vec::new();
        while let Some(event) = queue.pop_front() {
            let (state, effects) = c::reduce(std::mem::take(&mut self.state), event);
            self.state = state;
            for effect in effects {
                match effect {
                    c::Effect::Save {
                        token,
                        key,
                        update,
                        purpose,
                    } => {
                        let local = c::update_message(
                            self.locals.remove(&key).unwrap_or_default(),
                            &update,
                        );
                        self.locals.insert(key.clone(), local.clone());
                        queue.push_back(c::Event::Saved {
                            token,
                            key,
                            purpose,
                            local,
                            unavailable: HashSet::new(),
                        });
                    }
                    c::Effect::CheckRecipient { token, .. } => {
                        queue.push_back(c::Event::RecipientChecked {
                            token,
                            eligible: self.eligible,
                        })
                    }
                    effect => outward.push(effect),
                }
            }
        }
        outward
    }
    fn open(&mut self, key: SessionKey) {
        self.eligible = true;
        self.event(c::Event::Start);
        self.event(c::Event::Open(key));
    }
    fn edit(&mut self, text: &str) {
        let attachments = self.state.composer.draft.attachments.clone();
        self.event(c::Event::Edit {
            text: text.into(),
            attachments,
        });
    }
    fn send(&mut self, confirmed: bool) -> (u64, Value) {
        self.event(c::Event::Send {
            agent_id: "agent".into(),
            confirmed,
            eligible: self.eligible,
        })
        .into_iter()
        .find_map(|effect| {
            if let c::Effect::SendMessage { token, body } = effect {
                Some((token, body))
            } else {
                None
            }
        })
        .unwrap()
    }
    fn accept(&mut self, token: u64, id: &str, status: &str) -> Vec<c::Effect> {
        self.event(c::Event::SendAccepted {
            token,
            id: id.into(),
            status: status.into(),
        })
    }
}
#[test]
fn kotlin_accepted_is_not_delivered_and_new_draft_survives() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("primeiro");
    let (token, body) = h.send(false);
    h.edit("seguinte");
    let effects = h.accept(token, "m1", "queued");
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().status,
        "queued"
    );
    assert_eq!(h.state.composer.draft.text, "seguinte");
    assert_eq!(body["expectedSessionId"], "one");
    assert_eq!(body["deliverAs"], "aside");
    assert!(
        effects
            .iter()
            .any(|effect| matches!(effect, c::Effect::PollMessage { delay_ms: 2000, .. }))
    );
}
#[test]
fn kotlin_nonomp_omits_deliver_as_and_modes_have_no_status_filter() {
    for provider in ["claude", "codex"] {
        let mut h = Harness::default();
        let mut k = key("one");
        k.provider = provider.into();
        h.open(k);
        h.edit("pedido");
        let (_, body) = h.send(false);
        assert_eq!(body["expectedSessionId"], "one");
        assert!(body.get("deliverAs").is_none());
    }
    for status in ["working", "idle", "done"] {
        let session = SessionItem {
            key: key("one"),
            agent_id: Some("a".into()),
            status: Some(status.into()),
            ..SessionItem::default()
        };
        assert!(c::deliver_modes_available(&session));
        assert!(!c::deliver_modes_available(&SessionItem {
            sub: true,
            ..session
        }));
    }
}
#[test]
fn kotlin_ambiguous_restart_never_replays_without_confirmation() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("pedido");
    let (token, _) = h.send(false);
    h.event(c::Event::SendFailed {
        token,
        failure: c::Failure::default(),
    });
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().status,
        "unknown"
    );
    assert_eq!(h.state.composer.draft.text, "pedido");
    h.event(c::Event::Stop);
    let effects = h.event(c::Event::Start);
    assert!(
        !effects
            .iter()
            .any(|e| matches!(e, c::Effect::SendMessage { .. }))
    );
    assert!(
        h.event(c::Event::Send {
            agent_id: "agent".into(),
            confirmed: false,
            eligible: true
        })
        .is_empty()
    );
    assert_eq!(h.state.composer.error.as_deref(), Some("confirm"));
    let (token, _) = h.send(true);
    h.accept(token, "m2", "delivered");
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().id.as_deref(),
        Some("m2")
    );
}
#[test]
fn kotlin_missing_status_unknown_and_session_conflict_preserves_draft() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("pedido");
    let (token, _) = h.send(false);
    h.accept(token, "m1", "queued");
    h.event(c::Event::PollResult {
        key: key("one"),
        id: "m1".into(),
        control: false,
        status: None,
        error: None,
        failure: Some(c::Failure {
            status: Some(404),
            ..c::Failure::default()
        }),
    });
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().status,
        "unknown"
    );
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().id.as_deref(),
        Some("m1")
    );
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("pedido");
    let (token, _) = h.send(false);
    let effects = h.event(c::Event::SendFailed {
        token,
        failure: c::Failure {
            status: Some(409),
            session_changed: true,
            ..c::Failure::default()
        },
    });
    assert!(effects.contains(&c::Effect::SessionChanged));
    assert_eq!(h.locals[&key("one")].draft.text, "pedido");
    assert!(h.locals[&key("one")].operation.is_none());
}
fn interrupt(h: &mut Harness, confirmed: bool) -> Vec<c::Effect> {
    h.event(c::Event::Interrupt {
        agent_id: "agent".into(),
        confirmed,
        eligible: true,
    })
}
#[test]
fn kotlin_uncertain_stop_requires_explicit_confirmation_after_restart() {
    let mut h = Harness::default();
    h.open(key("one"));
    let token = interrupt(&mut h, false)
        .into_iter()
        .find_map(|e| {
            if let c::Effect::Interrupt { token, .. } = e {
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    h.event(c::Event::ControlFailed {
        token,
        failure: c::Failure::default(),
    });
    assert_eq!(h.state.composer.control_error.as_deref(), Some("unknown"));
    h.event(c::Event::Stop);
    h.event(c::Event::Start);
    assert!(interrupt(&mut h, false).is_empty());
    assert_eq!(h.state.composer.control_error.as_deref(), Some("confirm"));
    h.event(c::Event::DismissControlConfirmation);
    assert!(h.state.composer.control_error.is_none());
    assert!(interrupt(&mut h, false).is_empty());
    let token = interrupt(&mut h, true)
        .into_iter()
        .find_map(|e| {
            if let c::Effect::Interrupt { token, body } = e {
                assert_eq!(body["expectedSessionId"], "one");
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    h.event(c::Event::ControlAccepted {
        token,
        id: "stop2".into(),
        status: "queued".into(),
    });
    assert!(interrupt(&mut h, true).is_empty());
    h.event(c::Event::PollResult {
        key: key("one"),
        id: "stop2".into(),
        control: true,
        status: None,
        error: None,
        failure: Some(c::Failure {
            status: Some(404),
            ..c::Failure::default()
        }),
    });
    assert!(interrupt(&mut h, false).is_empty());
    assert_eq!(h.state.composer.control_error.as_deref(), Some("confirm"));
}
#[test]
fn kotlin_drafts_are_isolated_utf16_limited_and_crash_recovery_clears_accepted_revision() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("A");
    h.event(c::Event::Open(key("two")));
    h.edit("B");
    h.event(c::Event::Open(key("one")));
    assert_eq!(h.state.composer.draft.text, "A");
    h.edit(&"😀".repeat(10001));
    assert_eq!(h.state.composer.draft.text, "A");
    h.event(c::Event::Stop);
    h.event(c::Event::Open(key("two")));
    assert_eq!(h.state.composer.draft.text, "B");
    let mut h = Harness::default();
    h.locals.insert(
        key("one"),
        MessageLocalState {
            draft: MessageDraft {
                text: "pedido".into(),
                revision: 7,
                attachments: vec![],
            },
            operation: Some(MessageOperation {
                id: Some("m1".into()),
                status: "delivered".into(),
                revision: 7,
                error: None,
            }),
            control: None,
        },
    );
    h.open(key("one"));
    assert_eq!(h.state.composer.draft.text, "");
    assert_eq!(h.state.composer.draft.revision, 8);
    assert_eq!(
        h.state.composer.operation.as_ref().unwrap().status,
        "delivered"
    );
}
#[test]
fn kotlin_background_post_and_commands_cancel_without_replay() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.edit("pedido");
    h.send(false);
    let effects = h.event(c::Event::Stop);
    assert!(effects.contains(&c::Effect::CancelForeground));
    assert_eq!(
        h.locals[&key("one")].operation.as_ref().unwrap().status,
        "unknown"
    );
    assert_eq!(h.locals[&key("one")].draft.text, "pedido");
    assert!(
        !h.event(c::Event::Start)
            .iter()
            .any(|e| matches!(e, c::Effect::SendMessage { .. }))
    );
    assert!(h.event(c::Event::Start).is_empty());
    let effects = h.event(c::Event::Command {
        agent_id: "agent".into(),
        kind: "shake".into(),
        value: String::new(),
        eligible: true,
    });
    let token = effects
        .into_iter()
        .find_map(|e| {
            if let c::Effect::OmpControl { token, body } = e {
                assert_eq!(body["command"], "shake");
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    let effects = h.event(c::Event::CommandResult {
        token,
        id: Some("command".into()),
        status: Some("queued".into()),
        failure: None,
    });
    assert_eq!(
        effects,
        vec![c::Effect::PollCommand {
            token,
            id: "command".into(),
            delay_ms: 2000
        }]
    );
    h.event(c::Event::Stop);
    assert_eq!(
        h.state.composer.command_result.as_ref().unwrap().status,
        "unknown"
    );
    assert!(
        h.event(c::Event::Start)
            .iter()
            .all(|e| !matches!(e, c::Effect::OmpControl { .. }))
    );
    assert!(
        h.event(c::Event::CommandResult {
            token,
            id: None,
            status: Some("delivered".into()),
            failure: None
        })
        .is_empty()
    );
}
#[test]
fn kotlin_picker_merges_current_draft_and_rejects_session_switch() {
    let mut h = Harness::default();
    h.locals.insert(
        key("one"),
        MessageLocalState {
            draft: MessageDraft {
                text: "rascunho".into(),
                revision: 3,
                attachments: vec![],
            },
            ..MessageLocalState::default()
        },
    );
    h.open(key("one"));
    h.event(c::Event::AttachmentsSelected {
        key: key("one"),
        before: vec![],
        selected: vec![file("uri")],
    });
    assert_eq!(h.state.composer.draft.text, "rascunho");
    assert_eq!(h.state.composer.draft.attachments, vec![file("uri")]);
    assert_eq!(h.locals[&key("one")].draft.attachments, vec![file("uri")]);
    h.event(c::Event::Open(key("two")));
    let effects = h.event(c::Event::AttachmentsSelected {
        key: key("one"),
        before: vec![file("uri")],
        selected: vec![file("uri"), file("new")],
    });
    assert!(h.state.composer.draft.attachments.is_empty());
    assert!(effects.contains(&c::Effect::ReleaseUnused(vec![file("new")])));
    h.edit("concurrent");
    h.event(c::Event::AttachmentsSelected {
        key: key("two"),
        before: vec![],
        selected: vec![file("other")],
    });
    assert_eq!(h.state.composer.draft.text, "concurrent");
}
#[test]
fn kotlin_expired_staging_requires_explicit_retry_and_upload_ids_not_reused() {
    let mut h = Harness::default();
    h.open(key("one"));
    h.event(c::Event::Edit {
        text: "text".into(),
        attachments: vec![file("uri")],
    });
    let effects = h.event(c::Event::Send {
        agent_id: "agent".into(),
        confirmed: false,
        eligible: true,
    });
    let token = effects
        .into_iter()
        .find_map(|e| {
            if let c::Effect::UploadAttachment { token, .. } = e {
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    let effects = h.event(c::Event::Uploaded {
        token,
        id: "upload-1".into(),
    });
    assert!(effects.iter().any(
        |e| matches!(e,c::Effect::SendMessage{body,..} if body["attachments"]==json!(["upload-1"]))
    ));
    h.event(c::Event::SendFailed {
        token,
        failure: c::Failure {
            status: Some(400),
            attachments_expired: true,
            ..c::Failure::default()
        },
    });
    assert_eq!(
        h.state.composer.error.as_deref(),
        Some("attachments_expired")
    );
    assert_eq!(h.state.composer.draft.attachments.len(), 1);
    h.event(c::Event::Stop);
    assert!(!h.event(c::Event::Start).iter().any(|e| matches!(
        e,
        c::Effect::UploadAttachment { .. } | c::Effect::SendMessage { .. }
    )));
    assert!(
        h.event(c::Event::Send {
            agent_id: "agent".into(),
            confirmed: false,
            eligible: true
        })
        .iter()
        .any(|e| matches!(e, c::Effect::UploadAttachment { .. }))
    );
}
#[test]
fn kotlin_switch_or_background_during_upload_prevents_post() {
    for stop in [false, true] {
        let mut h = Harness::default();
        h.open(key("one"));
        h.event(c::Event::Edit {
            text: "text".into(),
            attachments: vec![file("uri")],
        });
        let token = h
            .event(c::Event::Send {
                agent_id: "agent".into(),
                confirmed: false,
                eligible: true,
            })
            .into_iter()
            .find_map(|e| {
                if let c::Effect::UploadAttachment { token, .. } = e {
                    Some(token)
                } else {
                    None
                }
            })
            .unwrap();
        if stop {
            h.event(c::Event::Stop);
        } else {
            h.eligible = false;
            h.event(c::Event::Open(key("two")));
        }
        assert!(
            !h.event(c::Event::Uploaded {
                token,
                id: "id".into()
            })
            .iter()
            .any(|e| matches!(e, c::Effect::SendMessage { .. }))
        );
        assert_eq!(h.locals[&key("one")].draft.text, "text");
        assert_eq!(h.locals[&key("one")].draft.attachments.len(), 1);
    }
}
fn topic(id: &str, status: &str) -> AsideTopic {
    AsideTopic {
        id: id.into(),
        created_at: 0,
        turns: vec![AsideTurn {
            id: "turn".into(),
            question: "oi".into(),
            answer: "".into(),
            status: status.into(),
            error: None,
        }],
    }
}
#[test]
fn kotlin_aside_polls_until_complete_and_keeps_trim_topic_new_semantics() {
    let (state, effects) = a::reduce(a::State::default(), a::Event::Open("agent".into()));
    assert!(state.aside.loading);
    assert!(effects.contains(&a::Effect::GetAsides {
        agent_id: "agent".into()
    }));
    let (state, _) = a::reduce(
        state,
        a::Event::Polled {
            agent_id: "agent".into(),
            topics: Some(vec![]),
        },
    );
    let (state, effects) = a::reduce(state, a::Event::Ask("  oi  ".into()));
    assert!(state.aside.sending);
    assert!(effects.contains(&a::Effect::Ask {
        agent_id: "agent".into(),
        question: "oi".into(),
        topic_id: None
    }));
    let (state, _) = a::reduce(
        state,
        a::Event::Asked {
            agent_id: "agent".into(),
            topic: Some(topic("t", "queued")),
        },
    );
    let (state, effects) = a::reduce(
        state,
        a::Event::Polled {
            agent_id: "agent".into(),
            topics: Some(vec![topic("t", "running")]),
        },
    );
    assert!(effects.contains(&a::Effect::DelayPoll {
        agent_id: "agent".into(),
        millis: 700
    }));
    let (state, effects) = a::reduce(state, a::Event::Ask("bloqueado".into()));
    assert!(effects.is_empty());
    let (state, _) = a::reduce(
        state,
        a::Event::Polled {
            agent_id: "agent".into(),
            topics: Some(vec![topic("t", "complete")]),
        },
    );
    assert!(!state.polling);
    let (state, effects) = a::reduce(state, a::Event::Ask("dois".into()));
    assert!(effects.contains(&a::Effect::Ask {
        agent_id: "agent".into(),
        question: "dois".into(),
        topic_id: Some("t".into())
    }));
    let (state, _) = a::reduce(
        state,
        a::Event::Asked {
            agent_id: "agent".into(),
            topic: Some(topic("t", "running")),
        },
    );
    let (state, _) = a::reduce(state, a::Event::StartNew);
    let (_, effects) = a::reduce(state, a::Event::Ask("novo".into()));
    assert!(effects.contains(&a::Effect::Ask {
        agent_id: "agent".into(),
        question: "novo".into(),
        topic_id: None
    }));
}
#[test]
fn aside_dirty_poll_does_not_stop_on_pre_send_snapshot() {
    let (state, _) = a::reduce(a::State::default(), a::Event::Open("agent".into()));
    let (state, _) = a::reduce(
        state,
        a::Event::Asked {
            agent_id: "agent".into(),
            topic: Some(topic("new", "queued")),
        },
    );
    assert!(state.dirty);
    let (state, effects) = a::reduce(
        state,
        a::Event::Polled {
            agent_id: "agent".into(),
            topics: Some(vec![]),
        },
    );
    assert!(state.polling);
    assert!(effects.contains(&a::Effect::GetAsides {
        agent_id: "agent".into()
    }));
    let (state, effects) = a::reduce(
        state,
        a::Event::Failed {
            agent_id: "agent".into(),
            status: Some(401),
            detail: Some("não".into()),
            call: a::Call::Poll,
        },
    );
    assert_eq!(state.aside.error.as_deref(), Some("não"));
    assert!(effects.contains(&a::Effect::Unauthorized));
    assert!(!state.polling);
}
fn questions() -> Vec<Value> {
    serde_json::from_str(r#"[{"index":2,"question":"Uma","options":[{"index":4,"label":"A"},{"index":8,"label":"B"}]},{"index":7,"question":"Várias","multiSelect":true,"options":[{"index":1,"label":"C"},{"index":5,"label":"D"}]}]"#).unwrap()
}
#[test]
fn kotlin_permission_original_indices_free_text_and_invalid_single() {
    let questions = questions();
    let decision = p::Decision {
        selections: HashMap::from([(2, [8].into()), (7, [1, 5].into())]),
        others: HashMap::from([(7, "outra".into())]),
        ..p::Decision::default()
    };
    for provider in ["claude", "omp"] {
        assert_eq!(
            p::permission_decision(provider, "answer", &questions, &decision).unwrap(),
            json!({"behavior":"answer","answers":[{"question":2,"options":[8]},{"question":7,"options":[1,5],"other":"outra"}]})
        );
        let free = p::Decision {
            others: HashMap::from([(2, "livre".into())]),
            ..p::Decision::default()
        };
        assert_eq!(
            p::permission_decision(provider, "answer", &questions[..1], &free).unwrap(),
            json!({"behavior":"answer","answers":[{"question":2,"other":"livre"}]})
        );
    }
    let invalid = p::Decision {
        selections: HashMap::from([(2, [4, 8].into())]),
        ..p::Decision::default()
    };
    assert!(p::permission_decision("claude", "answer", &questions[..1], &invalid).is_err());
    for provider in ["omp", "codex"] {
        assert!(
            p::permission_decision(
                provider,
                "allow",
                &[],
                &p::Decision {
                    suggestion: Some(0),
                    ..p::Decision::default()
                }
            )
            .is_err()
        );
        assert!(
            p::permission_decision(
                provider,
                "deny",
                &[],
                &p::Decision {
                    interrupt: true,
                    ..p::Decision::default()
                }
            )
            .is_err()
        );
    }
    for behavior in ["allow", "deny", "terminal"] {
        assert_eq!(
            p::permission_decision("omp", behavior, &[], &p::Decision::default()).unwrap()["behavior"],
            behavior
        );
    }
    assert!(p::permission_expired(100, 100));
    assert!(!p::permission_expired(101, 100));
    assert!(p::permission_closed(404));
    assert!(p::permission_closed(409));
    assert!(!p::permission_closed(500));
}
fn permission(expires: i64) -> Permission {
    Permission::parse(&json!({"provider":"claude","expiresAt":expires}))
}
#[test]
fn permission_guard_rechecks_deadline_and_ambiguity_blocks_resend() {
    let (state, effects) = p::reduce(
        p::State::default(),
        p::Event::Observe {
            id: "p".into(),
            allowed: true,
            connected: true,
        },
    );
    let token = effects
        .into_iter()
        .find_map(|e| {
            if let p::Effect::GetPermission { token, .. } = e {
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    let (state, _) = p::reduce(
        state,
        p::Event::Loaded {
            token,
            detail: permission(100),
            now: 0,
        },
    );
    let (_, effects) = p::reduce(
        state.clone(),
        p::Event::Decide {
            id: "p".into(),
            behavior: "allow".into(),
            now: 100,
        },
    );
    assert!(effects.is_empty());
    let (state, effects) = p::reduce(
        state,
        p::Event::Decide {
            id: "p".into(),
            behavior: "allow".into(),
            now: 99,
        },
    );
    assert!(state.busy);
    assert!(
        effects
            .iter()
            .any(|e| matches!(e, p::Effect::DecidePermission { .. }))
    );
    let (state, _) = p::reduce(
        state,
        p::Event::DecisionFailed {
            token,
            status: Some(503),
            cancelled: false,
        },
    );
    assert!(state.ambiguous);
    let (_, effects) = p::reduce(
        state,
        p::Event::Decide {
            id: "p".into(),
            behavior: "allow".into(),
            now: 99,
        },
    );
    assert!(effects.is_empty());
}
#[test]
fn permission_choice_controls_and_error_classes() {
    let (state, _) = p::reduce(
        p::State::default(),
        p::Event::Other {
            question: 2,
            multi: false,
            text: "livre".into(),
        },
    );
    let (state, _) = p::reduce(
        state,
        p::Event::Select {
            question: 2,
            option: 8,
            multi: false,
            checked: true,
        },
    );
    assert_eq!(state.decision.others[&2], "");
    let (state, _) = p::reduce(
        state,
        p::Event::Other {
            question: 2,
            multi: false,
            text: "x".into(),
        },
    );
    assert!(state.decision.selections[&2].is_empty());
    let (state, _) = p::reduce(
        state,
        p::Event::Observe {
            id: "p".into(),
            allowed: true,
            connected: true,
        },
    );
    let (state, _) = p::reduce(
        state,
        p::Event::Deny {
            id: "p".into(),
            now: 0,
        },
    );
    assert!(state.show_deny);
    for (status, expected) in [
        (Some(400), "decision"),
        (Some(500), "unknown"),
        (None, "unknown"),
    ] {
        let (state, _) = p::reduce(
            p::State::default(),
            p::Event::DecisionFailed {
                token: 0,
                status,
                cancelled: false,
            },
        );
        assert_eq!(state.error.as_deref(), Some(expected));
        assert_eq!(state.ambiguous, expected == "unknown");
    }
    let (state, effects) = p::reduce(
        p::State::default(),
        p::Event::DecisionFailed {
            token: 0,
            status: None,
            cancelled: true,
        },
    );
    assert!(state.ambiguous);
    assert!(effects.contains(&p::Effect::PropagateCancellation));
    let (_, effects) = p::reduce(
        p::State::default(),
        p::Event::Snapshot {
            permission_present: false,
            connected: false,
        },
    );
    assert!(effects.is_empty());
    let (_, effects) = p::reduce(
        p::State::default(),
        p::Event::Snapshot {
            permission_present: false,
            connected: true,
        },
    );
    assert!(effects.contains(&p::Effect::Close));
}

#[test]
fn permission_input_preserves_strings_and_compact_json_without_truncation() {
    let command = format!("echo início\n{}fim", "x".repeat(32_768));
    for input in [
        json!(command),
        json!({"command": command, "args": ["a", 2], "nested": {"allow": false}}),
        json!([true, "texto", 3]),
        json!(42),
        json!(false),
        Value::Null,
    ] {
        let detail = Permission::parse(&json!({"input": input, "description": "Detalhes completos"}));
        let expected = input.as_str().map(str::to_owned).unwrap_or_else(|| input.to_string());
        assert_eq!(detail.input.as_deref(), Some(expected.as_str()));
        assert_eq!(detail.raw["input"], input);
        assert_eq!(detail.description.as_deref(), Some("Detalhes completos"));
    }
    assert_eq!(Permission::parse(&json!({})).input, None);
}

fn deciding_permission() -> (p::State, u64) {
    let (state, effects) = p::reduce(
        p::State::default(),
        p::Event::Observe {
            id: "p".into(),
            allowed: true,
            connected: true,
        },
    );
    let token = effects
        .into_iter()
        .find_map(|effect| match effect {
            p::Effect::GetPermission { token, .. } => Some(token),
            _ => None,
        })
        .unwrap();
    let (state, _) = p::reduce(
        state,
        p::Event::Loaded {
            token,
            detail: permission(100),
            now: 0,
        },
    );
    let (state, effects) = p::reduce(
        state,
        p::Event::Decide {
            id: "p".into(),
            behavior: "allow".into(),
            now: 1,
        },
    );
    assert!(state.busy);
    assert!(effects.iter().any(|effect| matches!(
        effect,
        p::Effect::DecidePermission { token: actual, .. } if *actual == token
    )));
    (state, token)
}

#[test]
fn permission_decided_survives_stream_flap_and_releases_busy() {
    let (mut state, token) = deciding_permission();
    for connected in [false, true] {
        (state, _) = p::reduce(
            state,
            p::Event::Observe {
                id: "p".into(),
                allowed: true,
                connected,
            },
        );
        assert!(state.busy);
    }
    let (state, effects) = p::reduce(state, p::Event::Decided { token });
    assert!(!state.busy);
    assert_eq!(effects, vec![p::Effect::Close]);
}

#[test]
fn permission_decision_failure_survives_stream_flap_and_releases_busy() {
    for (status, cancelled, error, ambiguous, effect) in [
        (Some(400), false, Some("decision"), false, None),
        (Some(503), false, Some("unknown"), true, None),
        (None, false, Some("unknown"), true, None),
        (Some(401), false, None, false, Some(p::Effect::Unauthorized)),
        (Some(409), false, None, false, Some(p::Effect::Close)),
        (None, true, None, true, Some(p::Effect::PropagateCancellation)),
    ] {
        let (mut state, token) = deciding_permission();
        for connected in [false, true] {
            (state, _) = p::reduce(
                state,
                p::Event::Observe {
                    id: "p".into(),
                    allowed: true,
                    connected,
                },
            );
        }
        let (state, effects) = p::reduce(
            state,
            p::Event::DecisionFailed { token, status, cancelled },
        );
        assert!(!state.busy);
        assert_eq!(state.error.as_deref(), error);
        assert_eq!(state.ambiguous, ambiguous);
        assert_eq!(effects, effect.into_iter().collect::<Vec<_>>());
    }
}

#[test]
fn permission_replacement_drops_old_decision_completions_and_displayed_id_actions() {
    let (state, token) = deciding_permission();
    let (state, effects) = p::reduce(
        state,
        p::Event::Observe {
            id: "replacement".into(),
            allowed: true,
            connected: true,
        },
    );
    let load_token = effects
        .into_iter()
        .find_map(|effect| match effect {
            p::Effect::GetPermission { token, .. } => Some(token),
            _ => None,
        })
        .unwrap();
    let (state, _) = p::reduce(
        state,
        p::Event::Loaded {
            token: load_token,
            detail: permission(100),
            now: 0,
        },
    );
    for event in [
        p::Event::Decided { token },
        p::Event::DecisionFailed { token, status: Some(503), cancelled: false },
        p::Event::Decide { id: "p".into(), behavior: "allow".into(), now: 1 },
        p::Event::Deny { id: "p".into(), now: 1 },
    ] {
        let (next, effects) = p::reduce(state.clone(), event);
        assert_eq!(next, state);
        assert!(effects.is_empty());
    }
    let (state, effects) = p::reduce(
        state,
        p::Event::Decide {
            id: "replacement".into(),
            behavior: "allow".into(),
            now: 1,
        },
    );
    assert!(state.busy);
    assert!(effects.iter().any(|effect| matches!(
        effect,
        p::Effect::DecidePermission { id, .. } if id == "replacement"
    )));
}

#[test]
fn kotlin_reading_connection_actions_and_session_guard_cases() {
    let connected = ReadingState {
        connection: "connected".into(),
        terminal_allowed: true,
        ..ReadingState::default()
    };
    let mut snapshot_failure = connected.clone();
    snapshot_failure.stream_failure(None, false);
    assert_eq!(snapshot_failure.connection, "offline");
    assert_eq!(snapshot_failure.error.as_deref(), Some("network"));
    let mut state = ReadingState {
        messages_allowed: true,
        message_guard: true,
        ..connected.clone()
    };
    state.stream_failure(None, true);
    assert_eq!(state.connection, "connected");
    assert_eq!(state.reading_error.as_deref(), Some("network"));
    assert!(state.messages_allowed && state.message_guard);
    state.conversation_connected();
    assert!(state.reading_error.is_none());
    state.stream_failure(Some(403), true);
    state.conversation_connected();
    assert_eq!(state.reading_error.as_deref(), Some("forbidden"));
    state.stream_failure(None, false);
    assert_eq!(state.reading_error.as_deref(), Some("forbidden"));
    assert_eq!(state.error.as_deref(), Some("network"));
    assert!(c::actions_available(&connected, false, false));
    assert!(!c::actions_available(&state, false, true));
    assert!(!c::actions_available(&connected, true, true));
    let blocked = ReadingState {
        terminal_allowed: false,
        ..connected.clone()
    };
    assert!(!c::actions_available(&blocked, false, false));
    assert!(c::actions_available(&blocked, false, true));
    let k = SessionKey {
        account: "account".into(),
        session_id: "old".into(),
        ..key("old")
    };
    let snapshot = |session: &str, provider: &str| {
        json!({"agents":[{"id":"agent","kind":"main","account":"account","provider":provider,"sessionId":session}]}).to_string()
    };
    assert!(c::snapshot_matches_session(&snapshot("old", "omp"), &k, "agent").unwrap());
    assert!(!c::snapshot_matches_session(&snapshot("new", "omp"), &k, "agent").unwrap());
    assert!(!c::snapshot_matches_session(&snapshot("old", "claude"), &k, "agent").unwrap());
    assert!(!c::snapshot_matches_session(&snapshot("old", "omp"), &k, "other").unwrap());
    assert!(!c::snapshot_matches_session("{\"agents\":[]}", &k, "agent").unwrap());
    let item = SessionItem {
        key: k.clone(),
        agent_id: Some("agent".into()),
        title: "t".into(),
        project: Some("/p".into()),
        status: Some("idle".into()),
        can_message: true,
        ..SessionItem::default()
    };
    assert!(c::init_keeps_session(
        true,
        std::slice::from_ref(&item),
        &item
    ));
    assert!(!c::init_keeps_session(
        false,
        std::slice::from_ref(&item),
        &item
    ));
    assert!(!c::init_keeps_session(
        true,
        &[SessionItem {
            key: SessionKey {
                session_id: "new".into(),
                ..k.clone()
            },
            ..item.clone()
        }],
        &item
    ));
    let state = ReadingState {
        selected: Some(item.clone()),
        sessions: vec![item],
        messages_allowed: true,
        message_guard: true,
        ..connected
    };
    assert!(c::can_send(&state, true, true, &k, "agent"));
    assert!(!c::can_send(&state, false, true, &k, "agent"));
    assert!(!c::can_send(&state, true, false, &k, "agent"));
    assert!(!c::can_send(&state, true, true, &k, "other"));
}

#[test]
fn selection_waits_for_loading_and_preserves_memory_revision() {
    let (state, _) = c::reduce(c::State::default(), c::Event::Start);
    let (state, effects) = c::reduce(state, c::Event::Open(key("one")));
    let token = effects
        .into_iter()
        .find_map(|e| {
            if let c::Effect::Save { token, .. } = e {
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    let (state, effects) = c::reduce(
        state,
        c::Event::AttachmentsSelected {
            key: key("one"),
            before: vec![],
            selected: vec![file("uri")],
        },
    );
    assert!(effects.is_empty());
    let (state, effects) = c::reduce(
        state,
        c::Event::Saved {
            token,
            key: key("one"),
            purpose: c::SavePurpose::Open,
            local: MessageLocalState {
                draft: MessageDraft {
                    text: "rascunho".into(),
                    revision: 3,
                    attachments: vec![],
                },
                ..MessageLocalState::default()
            },
            unavailable: HashSet::new(),
        },
    );
    assert_eq!(state.composer.draft.text, "rascunho");
    assert_eq!(state.composer.draft.attachments, vec![file("uri")]);
    assert!(effects.iter().any(|e| matches!(
        e,
        c::Effect::Save {
            purpose: c::SavePurpose::Edit,
            ..
        }
    )));
    let local = MessageLocalState {
        draft: MessageDraft {
            text: "disco".into(),
            revision: 3,
            attachments: vec![],
        },
        ..MessageLocalState::default()
    };
    let saved = c::update_message(
        local,
        &c::MessageUpdate::Reconcile(Some(MessageDraft {
            text: "memória".into(),
            revision: 3,
            attachments: vec![],
        })),
    );
    assert_eq!(saved.draft.text, "disco");
}

#[test]
fn permission_uncovered_serialization_boundaries() {
    assert!(p::permission_decision("omp", "unknown", &[], &p::Decision::default()).is_err());
    assert!(p::permission_decision("omp", "answer", &[], &p::Decision::default()).is_err());
    let q = questions();
    for decision in [
        p::Decision::default(),
        p::Decision {
            selections: HashMap::from([(2, [99].into())]),
            ..p::Decision::default()
        },
        p::Decision {
            selections: HashMap::from([(2, [4].into())]),
            others: HashMap::from([(2, "x".into())]),
            ..p::Decision::default()
        },
    ] {
        assert!(p::permission_decision("omp", "answer", &q[..1], &decision).is_err());
    }
    let decision = p::Decision {
        selections: HashMap::from([(99, [99].into())]),
        others: HashMap::from([(2, "  livre  ".into()), (99, "ignored".into())]),
        ..p::Decision::default()
    };
    assert_eq!(
        p::permission_decision("omp", "answer", &q[..1], &decision).unwrap(),
        json!({"behavior":"answer","answers":[{"question":2,"other":"livre"}]})
    );
    let decision = p::Decision {
        message: "  razão  ".into(),
        interrupt: true,
        ..p::Decision::default()
    };
    assert_eq!(
        p::permission_decision("claude", "deny", &[], &decision).unwrap(),
        json!({"behavior":"deny","message":"  razão  ","interrupt":true})
    );
}

#[test]
fn cast_resume_free_owner_and_identity_boundaries() {
    let character = |id: &str, rooms: &[&str]| CastChoice {
        id: id.into(),
        name: id.into(),
        rooms: rooms.iter().map(|s| (*s).into()).collect(),
        avatar: None,
    };
    let characters = vec![
        character("livre", &[]),
        character("dono", &["/p"]),
        character("outra", &["/outro"]),
        character("ocupado", &[]),
    ];
    let session = SessionItem {
        key: key("01a1-abcd"),
        project: Some("/p".into()),
        cast: Some("dono".into()),
        ..SessionItem::default()
    };
    let available = c::cast_availability(
        Some(&session),
        &characters,
        &HashSet::from(["ocupado".into()]),
        &HashMap::new(),
    );
    assert!(available.resume);
    assert_eq!(
        available
            .resume_choices
            .iter()
            .map(|c| c.id.as_str())
            .collect::<Vec<_>>(),
        ["dono", "livre"]
    );
    assert!(!available.dismiss);
    let open = SessionItem {
        agent_id: Some("agent".into()),
        cast_opened: true,
        ..session.clone()
    };
    let available = c::cast_availability(
        Some(&open),
        &characters,
        &HashSet::new(),
        &HashMap::from([("dono".into(), "waiting".into())]),
    );
    assert!(available.active_job && available.dismiss);
    assert!(!available.resume);
    assert!(available.free.iter().all(|c| c.id != "dono"));
    for id in ["short", "zzzzzzzz", "01a1/abcd"] {
        assert!(
            !c::cast_availability(
                Some(&SessionItem {
                    key: key(id),
                    ..session.clone()
                }),
                &[],
                &HashSet::new(),
                &HashMap::new()
            )
            .resume
        );
    }
    assert!(c::cast_availability(Some(&session), &[], &HashSet::new(), &HashMap::new()).resume);
}

#[test]
fn command_poll_limit_and_unauthorized_are_not_retry_posts() {
    let mut h = Harness::default();
    h.open(key("one"));
    let token = h
        .event(c::Event::Command {
            agent_id: "agent".into(),
            kind: "model".into(),
            value: "p/model".into(),
            eligible: true,
        })
        .into_iter()
        .find_map(|e| {
            if let c::Effect::OmpControl { token, body } = e {
                assert_eq!(body["model"], "p/model");
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    for index in 0..=15 {
        let effects = h.event(c::Event::CommandResult {
            token,
            id: Some("c".into()),
            status: Some("queued".into()),
            failure: None,
        });
        assert_eq!(
            effects
                .iter()
                .filter(|e| matches!(e, c::Effect::PollCommand { .. }))
                .count(),
            usize::from(index < 15)
        );
    }
    assert_eq!(
        h.state.composer.command_result.as_ref().unwrap().status,
        "unknown"
    );
    h.edit("pedido");
    let (token, _) = h.send(false);
    let effects = h.event(c::Event::SendFailed {
        token,
        failure: c::Failure {
            status: Some(401),
            ..c::Failure::default()
        },
    });
    assert!(effects.contains(&c::Effect::Unauthorized));
    assert!(h.state.composer.operation.is_none());
}

#[test]
fn upload_progress_throttles_and_accepted_save_survives_background() {
    let mut h = Harness::default();
    h.open(key("one"));
    let attachment = Attachment {
        size: Some(300000),
        ..file("uri")
    };
    h.event(c::Event::Edit {
        text: "pedido".into(),
        attachments: vec![attachment],
    });
    let token = h
        .event(c::Event::Send {
            agent_id: "agent".into(),
            confirmed: false,
            eligible: true,
        })
        .into_iter()
        .find_map(|e| {
            if let c::Effect::UploadAttachment { token, .. } = e {
                Some(token)
            } else {
                None
            }
        })
        .unwrap();
    h.event(c::Event::UploadProgress {
        token,
        bytes: 262143,
    });
    assert_eq!(h.state.composer.progress.as_deref(), Some("1/1"));
    h.event(c::Event::UploadProgress {
        token,
        bytes: 262144,
    });
    assert_eq!(
        h.state.composer.progress.as_deref(),
        Some("1/1 · 262144 bytes")
    );
    h.event(c::Event::UploadProgress {
        token,
        bytes: 300000,
    });
    assert_eq!(
        h.state.composer.progress.as_deref(),
        Some("1/1 · 300000 bytes")
    );
    h.event(c::Event::Uploaded {
        token,
        id: "upload".into(),
    });
    let (state, effects) = c::reduce(
        h.state,
        c::Event::SendAccepted {
            token,
            id: "m".into(),
            status: "queued".into(),
        },
    );
    let update = effects
        .into_iter()
        .find_map(|e| {
            if let c::Effect::Save {
                update,
                purpose: c::SavePurpose::AcceptSend,
                ..
            } = e
            {
                Some(update)
            } else {
                None
            }
        })
        .unwrap();
    let (state, _) = c::reduce(state, c::Event::Stop);
    assert!(state.composer.sending);
    let local = c::update_message(h.locals.remove(&key("one")).unwrap(), &update);
    let (state, _) = c::reduce(
        state,
        c::Event::Saved {
            token,
            key: key("one"),
            purpose: c::SavePurpose::AcceptSend,
            local,
            unavailable: HashSet::new(),
        },
    );
    assert!(!state.composer.sending);
    assert_eq!(state.composer.draft.text, "");
    assert_eq!(
        state.composer.operation.as_ref().unwrap().id.as_deref(),
        Some("m")
    );
}

#[test]
fn kotlin_whitespace_differs_from_rust_unicode_whitespace() {
    use zaplaud_core::rules::{kotlin_blank, kotlin_trim};
    assert!(kotlin_blank("\u{001c}\u{00a0}\u{202f}"));
    assert!(!kotlin_blank("\u{0085}"));
    assert_eq!(kotlin_trim("\u{001c}texto\u{202f}"), "texto");
    assert_eq!(
        kotlin_trim("\u{0085}texto\u{0085}"),
        "\u{0085}texto\u{0085}"
    );
}

#[test]
fn polling_storage_failure_does_not_change_composer_error() {
    let mut h = Harness::default();
    h.open(key("one"));
    for purpose in [c::SavePurpose::Poll, c::SavePurpose::Restore] {
        h.event(c::Event::SaveFailed {
            token: 0,
            key: key("one"),
            purpose,
        });
        assert!(h.state.composer.error.is_none());
    }
    h.event(c::Event::SaveFailed {
        token: 0,
        key: key("one"),
        purpose: c::SavePurpose::Edit,
    });
    assert_eq!(h.state.composer.error.as_deref(), Some("storage"));
}
