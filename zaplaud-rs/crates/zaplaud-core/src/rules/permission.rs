use crate::model::{Permission, objects, string};
use serde_json::{Value, json};
use std::collections::{BTreeSet, HashMap};

pub fn permission_expired(deadline: i64, now: i64) -> bool {
    deadline <= now
}
pub fn permission_closed(status: u16) -> bool {
    matches!(status, 404 | 409)
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Decision {
    pub selections: HashMap<i32, BTreeSet<i32>>,
    pub others: HashMap<i32, String>,
    pub suggestion: Option<i32>,
    pub interrupt: bool,
    pub message: String,
}
fn index(value: &Value) -> Option<i32> {
    string(value, "index")?.parse().ok()
}
pub fn permission_decision(
    provider: &str,
    behavior: &str,
    questions: &[Value],
    decision: &Decision,
) -> Result<Value, &'static str> {
    if provider != "claude" && (decision.suggestion.is_some() || decision.interrupt)
        || !matches!(behavior, "allow" | "deny" | "terminal" | "answer")
    {
        return Err("invalid permission decision");
    }
    let mut body = json!({ "behavior": behavior });
    if behavior == "allow"
        && let Some(suggestion) = decision.suggestion
    {
        body["suggestion"] = json!(suggestion);
    }
    if behavior == "deny" {
        if !super::kotlin_blank(&decision.message) {
            body["message"] = json!(decision.message);
        }
        if decision.interrupt {
            body["interrupt"] = json!(true);
        }
    }
    if behavior == "answer" {
        if questions.is_empty() {
            return Err("invalid permission answers");
        }
        let mut answers = Vec::with_capacity(questions.len());
        for question in questions {
            let question_index = index(question).ok_or("invalid question index")?;
            let selected = decision
                .selections
                .get(&question_index)
                .cloned()
                .unwrap_or_default();
            let other = decision
                .others
                .get(&question_index)
                .map_or("", |text| super::kotlin_trim(text));
            let valid: BTreeSet<_> = objects(question, "options").filter_map(index).collect();
            if !selected.is_subset(&valid)
                || selected.is_empty() && other.is_empty()
                || question.get("multiSelect").and_then(Value::as_bool) != Some(true)
                    && selected.len() + usize::from(!other.is_empty()) != 1
            {
                return Err("invalid permission answers");
            }
            let mut answer = json!({ "question": question_index });
            if !selected.is_empty() {
                answer["options"] = json!(selected);
            }
            if !other.is_empty() {
                answer["other"] = json!(other);
            }
            answers.push(answer);
        }
        body["answers"] = json!(answers);
    }
    Ok(body)
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct State {
    pub id: Option<String>,
    pub allowed: bool,
    pub connected: bool,
    pub detail: Option<Permission>,
    pub error: Option<String>,
    pub busy: bool,
    pub expired: bool,
    pub ambiguous: bool,
    pub decision: Decision,
    pub show_deny: bool,
    generation: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    Observe {
        id: String,
        allowed: bool,
        connected: bool,
    },
    Loaded {
        token: u64,
        detail: Permission,
        now: i64,
    },
    LoadFailed {
        token: u64,
        status: Option<u16>,
    },
    Deadline {
        token: u64,
    },
    Select {
        question: i32,
        option: i32,
        multi: bool,
        checked: bool,
    },
    Other {
        question: i32,
        multi: bool,
        text: String,
    },
    Suggestion(Option<i32>),
    Interrupt(bool),
    Message(String),
    Deny {
        now: i64,
    },
    Decide {
        behavior: String,
        now: i64,
    },
    Decided {
        token: u64,
    },
    DecisionFailed {
        token: u64,
        status: Option<u16>,
        cancelled: bool,
    },
    Snapshot {
        permission_present: bool,
        connected: bool,
    },
    Close,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Effect {
    CancelLoad,
    GetPermission { token: u64, id: String },
    ExpireAfter { token: u64, millis: u64 },
    DecidePermission { token: u64, id: String, body: Value },
    Unauthorized,
    Close,
    PropagateCancellation,
}
pub fn reduce(mut state: State, event: Event) -> (State, Vec<Effect>) {
    let mut effects = Vec::new();
    match event {
        Event::Observe {
            id,
            allowed,
            connected,
        } => {
            if state.id.as_ref() != Some(&id)
                || state.allowed != allowed
                || state.connected != connected
            {
                let generation = state.generation.wrapping_add(1);
                if state.id.as_ref() != Some(&id) {
                    state = State::default();
                }
                state.generation = generation;
                state.id = Some(id.clone());
                state.allowed = allowed;
                state.connected = connected;
                state.detail = None;
                effects.push(Effect::CancelLoad);
                if allowed && connected {
                    effects.push(Effect::GetPermission {
                        token: generation,
                        id,
                    });
                }
            }
        }
        Event::Loaded { token, detail, now } if token == state.generation => {
            let millis = detail.expires_at.wrapping_sub(now).max(0) as u64;
            state.detail = Some(detail);
            effects.push(Effect::ExpireAfter { token, millis });
        }
        Event::LoadFailed { token, status } if token == state.generation => match status {
            Some(401) => effects.push(Effect::Unauthorized),
            Some(status) if permission_closed(status) => effects.push(Effect::Close),
            _ => state.error = Some("network".into()),
        },
        Event::Deadline { token } if token == state.generation => state.expired = true,
        Event::Select {
            question,
            option,
            multi,
            checked,
        } => {
            let selected = state.decision.selections.entry(question).or_default();
            if !multi {
                selected.clear();
            }
            if checked {
                selected.insert(option);
                if !multi {
                    state.decision.others.insert(question, String::new());
                }
            } else {
                selected.remove(&option);
            }
        }
        Event::Other {
            question,
            multi,
            text,
        } => {
            if !multi && !text.is_empty() {
                state.decision.selections.insert(question, BTreeSet::new());
            }
            state.decision.others.insert(question, text);
        }
        Event::Suggestion(value) => state.decision.suggestion = value,
        Event::Interrupt(value) => state.decision.interrupt = value,
        Event::Message(value) => state.decision.message = value,
        Event::Deny { now } => {
            if state.show_deny {
                return reduce(
                    state,
                    Event::Decide {
                        behavior: "deny".into(),
                        now,
                    },
                );
            }
            state.show_deny = true;
        }
        Event::Decide { behavior, now } => {
            if state.allowed
                && state.connected
                && !state.busy
                && !state.expired
                && !state.ambiguous
                && let Some(detail) = &state.detail
                && !permission_expired(detail.expires_at, now)
            {
                let mut decision = state.decision.clone();
                if behavior != "allow" {
                    decision.suggestion = None;
                }
                decision.interrupt &= behavior == "deny";
                let questions: Vec<_> = objects(&detail.raw, "questions").cloned().collect();
                match permission_decision(&detail.provider, &behavior, &questions, &decision) {
                    Ok(body) => {
                        if let Some(id) = &state.id {
                            state.busy = true;
                            effects.push(Effect::DecidePermission {
                                token: state.generation,
                                id: id.clone(),
                                body,
                            });
                        }
                    }
                    Err(_) => state.error = Some("answers".into()),
                }
            }
        }
        Event::Decided { token } if token == state.generation => {
            state.busy = false;
            effects.push(Effect::Close);
        }
        Event::DecisionFailed {
            token,
            status,
            cancelled,
        } if token == state.generation => {
            state.busy = false;
            if cancelled {
                state.ambiguous = true;
                effects.push(Effect::PropagateCancellation);
            } else {
                match status {
                    Some(401) => effects.push(Effect::Unauthorized),
                    Some(status) if permission_closed(status) => effects.push(Effect::Close),
                    Some(400..500) => state.error = Some("decision".into()),
                    _ => {
                        state.ambiguous = true;
                        state.error = Some("unknown".into());
                    }
                }
            }
        }
        Event::Snapshot {
            permission_present,
            connected,
        } if connected && !permission_present => effects.push(Effect::Close),
        Event::Close => effects.push(Effect::Close),
        _ => {}
    }
    (state, effects)
}
