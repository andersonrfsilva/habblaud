//! Execute os efeitos do redutor; descartar o runner cancela chamadas e esperas.
use crate::{
    Error,
    api::HabblaudApi,
    rules::aside::{self, Call, Effect, Event, State},
};
use futures_util::future::BoxFuture;
use std::{sync::Arc, time::Duration};
use tokio::{
    sync::{mpsc, watch},
    task::JoinSet,
};

type Dispatch = Arc<dyn Fn(Effect) -> BoxFuture<'static, Event> + Send + Sync>;

pub async fn run(
    api: HabblaudApi,
    input: mpsc::UnboundedReceiver<Event>,
    output: watch::Sender<State>,
    unauthorized: mpsc::UnboundedSender<()>,
    visibility: watch::Receiver<bool>,
) {
    let dispatch: Dispatch = Arc::new(move |effect| {
        let api = api.clone();
        Box::pin(async move { execute(&api, effect).await })
    });
    drive(input, output, unauthorized, dispatch, visibility).await;
}

async fn execute(api: &HabblaudApi, effect: Effect) -> Event {
    let (agent_id, call, result) = match effect {
        Effect::GetAsides { agent_id } => {
            let result = api.asides(&agent_id).await.map(|topics| Event::Polled {
                agent_id: agent_id.clone(),
                topics: Some(topics),
            });
            (agent_id, Call::Poll, result)
        }
        Effect::Ask {
            agent_id,
            question,
            topic_id,
        } => {
            let result = api
                .ask_aside(&agent_id, &question, topic_id.as_deref())
                .await
                .map(|topic| Event::Asked {
                    agent_id: agent_id.clone(),
                    topic,
                });
            (agent_id, Call::Ask, result)
        }
        Effect::Cancel { agent_id, topic_id } => {
            let result = api
                .cancel_aside(&agent_id, &topic_id)
                .await
                .map(|()| Event::Cancelled {
                    agent_id: agent_id.clone(),
                });
            (agent_id, Call::Cancel, result)
        }
        _ => unreachable!("efeito local executado pelo runner"),
    };
    result.unwrap_or_else(|error| {
        let (status, detail) = match error {
            Error::Unauthorized | Error::SessionEnded => (Some(401), None),
            Error::Api { status, detail, .. } => {
                (Some(status), (!detail.is_empty()).then_some(detail))
            }
            _ => (None, None),
        };
        Event::Failed {
            agent_id,
            status,
            detail,
            call,
        }
    })
}

async fn drive(
    mut input: mpsc::UnboundedReceiver<Event>,
    output: watch::Sender<State>,
    unauthorized: mpsc::UnboundedSender<()>,
    dispatch: Dispatch,
    mut visibility: watch::Receiver<bool>,
) {
    let mut state = State::default();
    let mut calls = JoinSet::new();
    let mut delay = JoinSet::new();
    loop {
        let event = tokio::select! {
            changed = visibility.changed() => {
                if changed.is_err() { break; }
                calls.abort_all(); delay.abort_all();
                state.polling = false; state.dirty = false; state.aside.sending = false;
                if !*visibility.borrow() { output.send_replace(state.clone()); continue; }
                Event::Poll
            }
            event = input.recv() => match event { Some(event) => event, None => break },
            Some(result) = calls.join_next(), if !calls.is_empty() => match result { Ok(event) => event, Err(_) => continue },
            Some(result) = delay.join_next(), if !delay.is_empty() => match result { Ok(event) => event, Err(_) => continue },
        };
        let (next, effects) = aside::reduce(state, event);
        state = next;
        output.send_replace(state.clone());
        for effect in effects {
            match effect {
                Effect::CancelPoll => {
                    calls.abort_all();
                    delay.abort_all();
                }
                Effect::Unauthorized => {
                    let _ = unauthorized.send(());
                }
                Effect::DelayPoll { agent_id, millis } => {
                    // A meta desktop permite no máximo uma atualização periódica por segundo.
                    delay.spawn(async move {
                        tokio::time::sleep(Duration::from_millis(millis.max(1000))).await;
                        Event::PollDue { agent_id }
                    });
                }
                effect => {
                    calls.spawn(dispatch(effect));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{AsideTopic, AsideTurn};
    use std::sync::Mutex;
    #[tokio::test(start_paused = true)]
    async fn ask_cancel_poll_and_close() {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let capture = seen.clone();
        let dispatch: Dispatch = Arc::new(move |effect| {
            capture.lock().unwrap().push(effect.clone());
            Box::pin(async move {
                let topic = AsideTopic {
                    id: "topic".into(),
                    created_at: 0,
                    turns: vec![AsideTurn {
                        id: "turn".into(),
                        question: "q".into(),
                        answer: String::new(),
                        status: "running".into(),
                        error: None,
                    }],
                };
                match effect {
                    Effect::GetAsides { agent_id } => Event::Polled {
                        agent_id,
                        topics: Some(vec![topic]),
                    },
                    Effect::Ask { agent_id, .. } => Event::Asked {
                        agent_id,
                        topic: Some(topic),
                    },
                    Effect::Cancel { agent_id, .. } => Event::Cancelled { agent_id },
                    _ => unreachable!(),
                }
            })
        });
        let (send, receive) = mpsc::unbounded_channel();
        let (out, mut state) = watch::channel(State::default());
        let (unauth, _) = mpsc::unbounded_channel();
        let (visible, visibility) = watch::channel(true);
        let runner = tokio::spawn(drive(receive, out, unauth, dispatch, visibility));
        send.send(Event::Open("agent".into())).unwrap();
        while state.borrow().aside.loading || state.borrow().aside.agent_id.is_none() {
            state.changed().await.unwrap();
        }
        send.send(Event::StartNew).unwrap();
        send.send(Event::Ask(" q ".into())).unwrap();
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        assert!(
            seen.lock()
                .unwrap()
                .iter()
                .any(|e| matches!(e, Effect::Ask { question, .. } if question == "q"))
        );
        send.send(Event::Cancel).unwrap();
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        assert!(
            seen.lock()
                .unwrap()
                .iter()
                .any(|e| matches!(e, Effect::Cancel { .. }))
        );
        let before = seen.lock().unwrap().len();
        tokio::time::advance(Duration::from_millis(999)).await;
        tokio::task::yield_now().await;
        assert_eq!(seen.lock().unwrap().len(), before);
        tokio::time::advance(Duration::from_millis(1)).await;
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        assert!(seen.lock().unwrap().len() > before);
        visible.send_replace(false);
        for _ in 0..10 { tokio::task::yield_now().await; }
        let before = seen.lock().unwrap().len();
        tokio::time::advance(Duration::from_secs(10)).await;
        tokio::task::yield_now().await;
        assert_eq!(seen.lock().unwrap().len(), before);
        visible.send_replace(true);
        for _ in 0..10 { tokio::task::yield_now().await; }
        assert!(seen.lock().unwrap().len() > before);
        send.send(Event::Close).unwrap();
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
        let before = seen.lock().unwrap().len();
        tokio::time::advance(Duration::from_secs(10)).await;
        tokio::task::yield_now().await;
        assert_eq!(seen.lock().unwrap().len(), before);
        runner.abort();
    }
    #[tokio::test]
    async fn unauthorized_is_reported() {
        let dispatch: Dispatch = Arc::new(|_| {
            Box::pin(async {
                Event::Failed {
                    agent_id: "a".into(),
                    status: Some(401),
                    detail: None,
                    call: Call::Poll,
                }
            })
        });
        let (send, receive) = mpsc::unbounded_channel();
        let (out, _) = watch::channel(State::default());
        let (unauth, mut notice) = mpsc::unbounded_channel();
        let (_visible, visibility) = watch::channel(true);
        let runner = tokio::spawn(drive(receive, out, unauth, dispatch, visibility));
        send.send(Event::Open("a".into())).unwrap();
        assert_eq!(notice.recv().await, Some(()));
        runner.abort();
    }
}
