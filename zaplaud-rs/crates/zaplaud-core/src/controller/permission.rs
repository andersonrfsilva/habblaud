//! Executor da folha: cada abertura possui tarefas canceláveis próprias.
use crate::{
    api::HabblaudApi,
    rules::permission::{self, Effect, Event, State},
};
use tokio::{sync::mpsc, task::JoinHandle};

fn status(error: &crate::Error) -> Option<u16> {
    match error {
        crate::Error::Unauthorized | crate::Error::SessionEnded => Some(401),
        crate::Error::Api { status, .. } => Some(*status),
        _ => None,
    }
}
#[derive(Clone, Debug)]
// A folha tem um único consumidor; manter o estado inline evita alocação por edição.
#[allow(clippy::large_enum_variant)]
pub enum Update {
    State(State),
    Unauthorized,
    Closed,
    Cancelled,
}

pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}

#[derive(Default)]
struct Tasks {
    load: Option<JoinHandle<()>>,
    deadline: Option<JoinHandle<()>>,
    decision: Option<JoinHandle<()>>,
}
impl Drop for Tasks {
    fn drop(&mut self) {
        for task in [&self.load, &self.deadline, &self.decision]
            .into_iter()
            .flatten()
        {
            task.abort();
        }
    }
}

/// Fecha a folha sem repetir decisões; abandonar o receptor também cancela os pedidos.
pub async fn run(
    api: HabblaudApi,
    mut commands: mpsc::UnboundedReceiver<Event>,
    updates: mpsc::UnboundedSender<Update>,
) {
    let (sender, mut completions) = mpsc::unbounded_channel();
    let mut state = State::default();
    let mut tasks = Tasks::default();
    'events: loop {
        let event = tokio::select! {
            event = commands.recv() => match event { Some(event) => event, None => break },
            event = completions.recv() => match event { Some(event) => event, None => break },
            _ = updates.closed() => break,
        };
        let (next, effects) = permission::reduce(state, event);
        state = next;
        for effect in effects {
            match effect {
                Effect::CancelLoad => {
                    if let Some(task) = tasks.load.take() {
                        task.abort();
                    }
                    if let Some(task) = tasks.deadline.take() {
                        task.abort();
                    }
                }
                Effect::GetPermission { token, id } => {
                    let api = api.clone();
                    let sender = sender.clone();
                    tasks.load = Some(tokio::spawn(async move {
                        let event = match api.permission(&id).await {
                            Ok(detail) => Event::Loaded {
                                token,
                                detail,
                                now: now_millis(),
                            },
                            Err(error) => Event::LoadFailed {
                                token,
                                status: status(&error),
                            },
                        };
                        let _ = sender.send(event);
                    }));
                }
                Effect::ExpireAfter { token, millis } => {
                    if let Some(task) = tasks.deadline.take() {
                        task.abort();
                    }
                    let sender = sender.clone();
                    tasks.deadline = Some(tokio::spawn(async move {
                        tokio::time::sleep(std::time::Duration::from_millis(millis)).await;
                        let _ = sender.send(Event::Deadline { token });
                    }));
                }
                Effect::DecidePermission { token, id, body } => {
                    let api = api.clone();
                    let sender = sender.clone();
                    tasks.decision = Some(tokio::spawn(async move {
                        let event = match api.decide_permission_json(&id, &body).await {
                            Ok(()) => Event::Decided { token },
                            Err(error) => Event::DecisionFailed {
                                token,
                                status: status(&error),
                                cancelled: false,
                            },
                        };
                        let _ = sender.send(event);
                    }));
                }
                Effect::Unauthorized => {
                    let _ = updates.send(Update::Unauthorized);
                }
                Effect::Close => {
                    let _ = updates.send(Update::Closed);
                    break 'events;
                }
                Effect::PropagateCancellation => {
                    let _ = updates.send(Update::Cancelled);
                    break 'events;
                }
            }
        }
        if updates.send(Update::State(state.clone())).is_err() {
            break;
        }
    }
    for task in [
        tasks.load.take(),
        tasks.deadline.take(),
        tasks.decision.take(),
    ]
    .into_iter()
    .flatten()
    {
        task.abort();
        let _ = task.await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    async fn server(
        status: u16,
        expires: i64,
    ) -> (HabblaudApi, mpsc::UnboundedReceiver<String>, JoinHandle<()>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let api = HabblaudApi::new(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
        let (tx, rx) = mpsc::unbounded_channel();
        let task = tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else {
                    break;
                };
                let tx = tx.clone();
                tokio::spawn(async move {
                    let mut request = Vec::new();
                    let mut chunk = [0; 4096];
                    loop {
                        let count = stream.read(&mut chunk).await.unwrap_or(0);
                        if count == 0 {
                            return;
                        }
                        request.extend_from_slice(&chunk[..count]);
                        if let Some(end) = request.windows(4).position(|w| w == b"\r\n\r\n") {
                            let headers = String::from_utf8_lossy(&request[..end]);
                            let length = headers
                                .lines()
                                .find_map(|line| {
                                    line.to_ascii_lowercase()
                                        .strip_prefix("content-length: ")
                                        .and_then(|v| v.parse::<usize>().ok())
                                })
                                .unwrap_or(0);
                            if request.len() >= end + 4 + length {
                                break;
                            }
                        }
                    }
                    let request = String::from_utf8_lossy(&request).into_owned();
                    let post = request.starts_with("POST");
                    let _ = tx.send(request);
                    if status == 0 {
                        let mut byte = [0];
                        let _ = stream.read(&mut byte).await;
                        let _ = tx.send("closed".into());
                        return;
                    }
                    let body = if post {
                        "{}".into()
                    } else {
                        format!(r#"{{"provider":"claude","tool":"bash","expiresAt":{expires}}}"#)
                    };
                    let response = format!(
                        "HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    );
                    let _ = stream.write_all(response.as_bytes()).await;
                });
            }
        });
        (api, rx, task)
    }
    async fn next(rx: &mut mpsc::UnboundedReceiver<Update>) -> Update {
        tokio::time::timeout(std::time::Duration::from_secs(3), rx.recv())
            .await
            .unwrap()
            .unwrap()
    }
    async fn loaded(rx: &mut mpsc::UnboundedReceiver<Update>) -> State {
        loop {
            if let Update::State(state) = next(rx).await
                && state.detail.is_some()
            {
                return state;
            }
        }
    }
    fn observe() -> Event {
        Event::Observe {
            id: "permission".into(),
            allowed: true,
            connected: true,
        }
    }

    #[tokio::test]
    async fn accept_and_deny_reach_server() {
        for behavior in ["allow", "deny"] {
            let (api, mut requests, server) = server(200, now_millis() + 60_000).await;
            let (tx, rx) = mpsc::unbounded_channel();
            let (updates, mut states) = mpsc::unbounded_channel();
            let runner = tokio::spawn(run(api, rx, updates));
            tx.send(observe()).unwrap();
            loaded(&mut states).await;
            tx.send(Event::Decide {
                behavior: behavior.into(),
                now: now_millis(),
            })
            .unwrap();
            loop {
                if matches!(next(&mut states).await, Update::Closed) {
                    break;
                }
            }
            let _ = requests.recv().await;
            assert!(
                requests
                    .recv()
                    .await
                    .unwrap()
                    .contains(&format!(r#""behavior":"{behavior}""#))
            );
            runner.await.unwrap();
            server.abort();
        }
    }
    #[tokio::test]
    async fn expired_never_posts() {
        let (api, mut requests, server) = server(200, 1).await;
        let (tx, rx) = mpsc::unbounded_channel();
        let (updates, mut states) = mpsc::unbounded_channel();
        let runner = tokio::spawn(run(api, rx, updates));
        tx.send(observe()).unwrap();
        loaded(&mut states).await;
        loop {
            if let Update::State(state) = next(&mut states).await
                && state.expired
            {
                break;
            }
        }
        tx.send(Event::Decide {
            behavior: "allow".into(),
            now: now_millis(),
        })
        .unwrap();
        tx.send(Event::Close).unwrap();
        runner.await.unwrap();
        assert!(requests.recv().await.unwrap().starts_with("GET"));
        assert!(requests.try_recv().is_err());
        server.abort();
    }
    #[tokio::test]
    async fn changed_underneath_closes() {
        let (api, _, server) = server(200, now_millis() + 60_000).await;
        let (tx, rx) = mpsc::unbounded_channel();
        let (updates, mut states) = mpsc::unbounded_channel();
        let runner = tokio::spawn(run(api, rx, updates));
        tx.send(observe()).unwrap();
        loaded(&mut states).await;
        tx.send(Event::Snapshot {
            permission_present: false,
            connected: false,
        })
        .unwrap();
        assert!(matches!(next(&mut states).await, Update::State(_)));
        tx.send(Event::Snapshot {
            permission_present: false,
            connected: true,
        })
        .unwrap();
        assert!(matches!(next(&mut states).await, Update::Closed));
        runner.await.unwrap();
        server.abort();
    }
    #[tokio::test]
    async fn unauthorized_propagates() {
        let (api, _, server) = server(401, 0).await;
        let (tx, rx) = mpsc::unbounded_channel();
        let (updates, mut states) = mpsc::unbounded_channel();
        let runner = tokio::spawn(run(api, rx, updates));
        tx.send(observe()).unwrap();
        loop {
            if matches!(next(&mut states).await, Update::Unauthorized) {
                break;
            }
        }
        tx.send(Event::Close).unwrap();
        runner.await.unwrap();
        server.abort();
    }
    #[tokio::test]
    async fn close_while_loading_cancels_request() {
        let (api, mut requests, server) = server(0, 0).await;
        let (tx, rx) = mpsc::unbounded_channel();
        let (updates, mut states) = mpsc::unbounded_channel();
        let runner = tokio::spawn(run(api, rx, updates));
        tx.send(observe()).unwrap();
        requests.recv().await.unwrap();
        tx.send(Event::Close).unwrap();
        loop {
            if matches!(next(&mut states).await, Update::Closed) {
                break;
            }
        }
        runner.await.unwrap();
        assert!(tx.is_closed());
        assert_eq!(
            tokio::time::timeout(std::time::Duration::from_secs(3), requests.recv())
                .await
                .unwrap()
                .as_deref(),
            Some("closed")
        );
        server.abort();
    }
    #[tokio::test]
    async fn closing_releases_deadline_task() {
        let (api, _, server) = server(200, now_millis() + 60_000).await;
        let metrics = tokio::runtime::Handle::current().metrics();
        let baseline = metrics.num_alive_tasks();
        let (tx, rx) = mpsc::unbounded_channel();
        let (updates, mut states) = mpsc::unbounded_channel();
        let runner = tokio::spawn(run(api, rx, updates));
        tx.send(observe()).unwrap();
        loaded(&mut states).await;
        tx.send(Event::Close).unwrap();
        runner.await.unwrap();
        tokio::task::yield_now().await;
        assert_eq!(metrics.num_alive_tasks(), baseline);
        server.abort();
    }
}
