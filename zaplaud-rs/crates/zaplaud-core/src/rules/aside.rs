use super::utf16_len;
use crate::model::{ASIDE_QUESTION_MAX, AsideState, AsideTopic};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct State {
    pub aside: AsideState,
    pub polling: bool,
    pub dirty: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Call {
    Poll,
    Ask,
    Cancel,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    Open(String),
    Close,
    Choose(String),
    StartNew,
    Ask(String),
    Cancel,
    Asked {
        agent_id: String,
        topic: Option<AsideTopic>,
    },
    Cancelled {
        agent_id: String,
    },
    Poll,
    PollDue {
        agent_id: String,
    },
    Polled {
        agent_id: String,
        topics: Option<Vec<AsideTopic>>,
    },
    Failed {
        agent_id: String,
        status: Option<u16>,
        detail: Option<String>,
        call: Call,
    },
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Effect {
    CancelPoll,
    GetAsides {
        agent_id: String,
    },
    Ask {
        agent_id: String,
        question: String,
        topic_id: Option<String>,
    },
    Cancel {
        agent_id: String,
        topic_id: String,
    },
    DelayPoll {
        agent_id: String,
        millis: u64,
    },
    Unauthorized,
}
fn poll(state: &mut State, effects: &mut Vec<Effect>) {
    state.dirty = true;
    if !state.polling
        && let Some(agent_id) = &state.aside.agent_id
    {
        state.polling = true;
        state.dirty = false;
        effects.push(Effect::GetAsides {
            agent_id: agent_id.clone(),
        });
    }
}
pub fn reduce(mut state: State, event: Event) -> (State, Vec<Effect>) {
    let mut effects = Vec::new();
    match event {
        Event::Open(agent_id) => {
            effects.push(Effect::CancelPoll);
            state = State {
                aside: AsideState {
                    agent_id: Some(agent_id),
                    loading: true,
                    ..AsideState::default()
                },
                ..State::default()
            };
            poll(&mut state, &mut effects);
        }
        Event::Close => {
            effects.push(Effect::CancelPoll);
            state = State::default();
        }
        Event::Choose(topic_id) => {
            state.aside.topic_id = Some(topic_id);
            state.aside.fresh = false;
            state.aside.error = None;
        }
        Event::StartNew => {
            state.aside.fresh = true;
            state.aside.error = None;
        }
        Event::Ask(question) => {
            let text = super::kotlin_trim(&question);
            if let Some(agent_id) = &state.aside.agent_id
                && !text.is_empty()
                && utf16_len(text) <= ASIDE_QUESTION_MAX
                && !state.aside.sending
                && !state
                    .aside
                    .topic()
                    .is_some_and(|topic| topic.turns.iter().any(|turn| turn.running()))
            {
                effects.push(Effect::Ask {
                    agent_id: agent_id.clone(),
                    question: text.into(),
                    topic_id: state.aside.topic().map(|topic| topic.id.clone()),
                });
                state.aside.sending = true;
                state.aside.error = None;
            }
        }
        Event::Cancel => {
            if let Some(agent_id) = &state.aside.agent_id
                && let Some(topic) = state.aside.topic()
            {
                effects.push(Effect::Cancel {
                    agent_id: agent_id.clone(),
                    topic_id: topic.id.clone(),
                });
            }
        }
        Event::Asked { agent_id, topic } if state.aside.agent_id.as_deref() == Some(&agent_id) => {
            state.aside.sending = false;
            if let Some(topic) = topic {
                state.aside.topics.retain(|old| old.id != topic.id);
                state.aside.topic_id = Some(topic.id.clone());
                state.aside.topics.insert(0, topic);
                state.aside.fresh = false;
                poll(&mut state, &mut effects);
            }
        }
        Event::Cancelled { agent_id } if state.aside.agent_id.as_deref() == Some(&agent_id) => {
            poll(&mut state, &mut effects)
        }
        Event::Poll => poll(&mut state, &mut effects),
        Event::PollDue { agent_id }
            if state.aside.agent_id.as_deref() == Some(&agent_id) && state.polling =>
        {
            state.dirty = false;
            effects.push(Effect::GetAsides { agent_id });
        }
        Event::Polled { agent_id, topics }
            if state.aside.agent_id.as_deref() == Some(&agent_id) =>
        {
            state.aside.loading = false;
            let valid = topics.is_some();
            if let Some(topics) = topics {
                state.aside.topics = topics;
            }
            if state.dirty {
                state.dirty = false;
                effects.push(Effect::GetAsides { agent_id });
            } else if valid && state.aside.running() {
                effects.push(Effect::DelayPoll {
                    agent_id,
                    millis: 700,
                });
            } else {
                state.polling = false;
            }
        }
        Event::Failed {
            agent_id,
            status,
            detail,
            call,
        } => {
            if status == Some(401) {
                effects.push(Effect::Unauthorized);
            }
            if state.aside.agent_id.as_deref() == Some(&agent_id) {
                state.aside.error = Some(detail.unwrap_or_else(|| "network".into()));
                if call == Call::Ask {
                    state.aside.sending = false;
                }
                if call == Call::Poll {
                    let (next, more) = reduce(
                        state,
                        Event::Polled {
                            agent_id,
                            topics: None,
                        },
                    );
                    state = next;
                    effects.extend(more);
                }
            }
        }
        _ => {}
    }
    (state, effects)
}
