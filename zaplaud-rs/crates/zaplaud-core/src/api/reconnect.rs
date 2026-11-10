use super::*;
use crate::model::{SessionItem, StreamEvent, TerminalEntry};
use std::time::{Duration, SystemTime};

pub fn retry_after(value: Option<&str>, now: SystemTime) -> Duration {
    let Some(value) = value else {
        return Duration::ZERO;
    };
    if let Ok(seconds) = value.parse::<i64>() {
        return Duration::from_secs(seconds.clamp(0, 86_400) as u64);
    }
    httpdate::parse_http_date(value)
        .ok()
        .and_then(|date| date.duration_since(now).ok())
        .unwrap_or_default()
        .min(Duration::from_secs(86_400))
}
pub fn terminal_stream_status(status: Option<i32>) -> bool {
    matches!(status, Some(-1 | 401 | 403 | 404))
        || status.is_some_and(|status| (300..400).contains(&status))
}
fn failure(error: &Error) -> (Option<i32>, Option<String>) {
    match error {
        Error::Unauthorized => (Some(401), None),
        Error::Redirect(status) => (Some(i32::from(*status)), None),
        Error::EventTooLarge => (Some(-1), None),
        Error::Api {
            status,
            retry_after,
            ..
        } => (Some(i32::from(*status)), retry_after.clone()),
        _ => (None, None),
    }
}

pub struct ResilientStream {
    api: HabblaudApi,
    path: String,
    current: Option<EventStream>,
    connections: u64,
    failures: u64,
    backoff: Duration,
    pending: VecDeque<StreamEvent>,
    sleep: Option<std::pin::Pin<Box<tokio::time::Sleep>>>,
    ended: bool,
    cancellation: tokio::sync::watch::Receiver<u64>,
}
impl HabblaudApi {
    pub fn resilient_stream(&self, path: &str) -> Result<ResilientStream, Error> {
        self.url(path)?;
        Ok(ResilientStream {
            api: self.clone(),
            path: path.to_owned(),
            current: None,
            connections: 0,
            failures: 0,
            backoff: Duration::from_secs(2),
            pending: VecDeque::new(),
            sleep: None,
            ended: false,
            cancellation: self.cancellation.subscribe(),
        })
    }
    pub fn snapshot_stream(&self) -> Result<ResilientStream, Error> {
        self.resilient_stream("/api/stream")
    }
    /// A âncora é calculada uma vez; reconexões reutilizam a mesma URL, como ReadingController.
    pub fn conversation_stream(
        &self,
        session: &SessionItem,
        cached: &[TerminalEntry],
    ) -> Result<ResilientStream, Error> {
        let path = conversation_path(session, cached);
        self.resilient_stream(&path)
    }
}
pub fn conversation_path(session: &SessionItem, cached: &[TerminalEntry]) -> String {
    let mut path = match &session.agent_id {
        Some(id) => format!("/api/agents/{}/terminal", encode_segment(id)),
        None => format!(
            "/api/sessions/{}/{}/terminal",
            encode_segment(&session.key.account),
            encode_segment(&session.key.session_id)
        ),
    };
    if !session.sub
        && let Some(entry) = cached.iter().rev().find(|entry| entry.at.is_some())
        && let Some(at) = entry.at
    {
        path.push_str(&format!("?after={}&at={at}", encode_segment(&entry.id)));
    }
    path
}
impl ResilientStream {
    /// Sem tarefa de fundo: descartar o fluxo cancela inclusive o handshake e o backoff.
    pub fn into_stream(self) -> impl futures_util::Stream<Item = StreamEvent> + Send {
        futures_util::stream::unfold(self, |mut stream| async move {
            stream.next_event().await.map(|event| (event, stream))
        })
    }
    pub async fn next_event(&mut self) -> Option<StreamEvent> {
        loop {
            if let Some(event) = self.pending.pop_front() {
                return Some(event);
            }
            if self.ended {
                return None;
            }
            if self.cancellation.has_changed().unwrap_or(true) {
                self.current = None;
                return None;
            }
            if let Some(sleep) = self.sleep.as_mut() {
                tokio::select! { _ = self.cancellation.changed() => { self.ended = true; return None; }, _ = sleep => {} }
                self.sleep = None;
            }
            let result = if let Some(current) = &mut self.current {
                match current.next_raw().await {
                    Ok(Some((event_type, data))) => {
                        return Some(StreamEvent::Data {
                            event_type,
                            data,
                            first_connection: self.connections == 1,
                        });
                    }
                    Ok(None) => (None, None),
                    Err(Error::SessionEnded) => {
                        self.current = None;
                        self.ended = true;
                        return None;
                    }
                    Err(error) => failure(&error),
                }
            } else {
                match self.api.stream(&self.path).await {
                    Ok(stream) => {
                        self.current = Some(stream);
                        self.connections += 1;
                        self.failures = 0;
                        self.backoff = Duration::from_secs(2);
                        return Some(StreamEvent::Connected);
                    }
                    Err(Error::SessionEnded) => {
                        self.ended = true;
                        return None;
                    }
                    Err(error) => failure(&error),
                }
            };
            self.current = None;
            let (status, retry_after_header) = result;
            self.failures += 1;
            let terminal = terminal_stream_status(status);
            if terminal || self.failures >= 3 {
                self.pending.push_back(StreamEvent::Failure {
                    status,
                    retry_after: retry_after_header.clone(),
                });
                if !terminal {
                    self.pending.push_back(StreamEvent::Warn { status });
                }
            }
            if terminal {
                self.ended = true;
            } else {
                let delay = self.backoff.max(retry_after(
                    retry_after_header.as_deref(),
                    SystemTime::now(),
                ));
                self.pending.push_back(StreamEvent::Reconnecting { delay });
                self.sleep = Some(Box::pin(tokio::time::sleep(delay)));
                self.backoff = (self.backoff * 2).min(Duration::from_secs(30));
            }
        }
    }
}
