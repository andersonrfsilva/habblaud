//! Executor do compositor: persistência serial, POST único e tarefas canceláveis.
use crate::{
    model::*,
    rules::composer::{self, Effect, Event, Failure, MessageUpdate, State},
};
use futures_util::future::BoxFuture;
use std::{collections::HashSet, sync::Arc};
use tokio::{
    sync::{mpsc, watch},
    task::JoinHandle,
};

/// Adaptador nativo. `save` deve aplicar a atualização dentro da transação do armazenamento.
pub trait Backend: Send + Sync + 'static {
    fn save(&self, key: &SessionKey, update: &MessageUpdate) -> Result<MessageLocalState, String>;
    fn available(&self, attachment: &Attachment) -> bool;
    fn eligible(&self, key: &SessionKey, agent_id: &str) -> bool;
    fn request(
        &self,
        origin: &str,
        path: String,
        body: Option<serde_json::Value>,
    ) -> BoxFuture<'static, Result<serde_json::Value, Failure>>;
    fn upload(
        &self,
        origin: &str,
        attachment: Attachment,
        progress: mpsc::UnboundedSender<i64>,
    ) -> BoxFuture<'static, Result<String, Failure>>;
    fn release_unused(&self, attachments: &[Attachment], active: &State);
    fn unauthorized(&self, origin: &str);
    fn session_changed(&self, key: &SessionKey);
}

pub struct MessageController {
    pub events: mpsc::UnboundedSender<Event>,
    pub states: watch::Receiver<State>,
    task: JoinHandle<()>,
}
impl Drop for MessageController {
    fn drop(&mut self) {
        let _ = self.events.send(Event::Stop);
    }
}
impl MessageController {
    pub fn start(backend: Arc<dyn Backend>) -> Self {
        let (events, mut receiver) = mpsc::unbounded_channel();
        let (states_tx, states) = watch::channel(State::default());
        let sender = events.clone();
        let task = tokio::spawn(async move {
            let mut state = State::default();
            let mut foreground = Vec::<JoinHandle<()>>::new();
            let mut polls = Vec::<JoinHandle<()>>::new();
            while let Some(event) = receiver.recv().await {
                let changing = matches!(&event, Event::Close)
                    || matches!(&event, Event::Open(key) if state.composer.key.as_ref().is_some_and(|current| current != key));
                let mut cancellation = Vec::new();
                if changing {
                    let visible = state.visible;
                    let (mut stopped, effects) = composer::reduce(state, Event::Stop);
                    stopped.visible = visible;
                    state = stopped;
                    cancellation = effects;
                }
                let (next, mut effects) = composer::reduce(state, event);
                cancellation.append(&mut effects);
                let effects = cancellation;
                state = next;
                states_tx.send_replace(state.clone());
                foreground.retain(|job| !job.is_finished());
                polls.retain(|job| !job.is_finished());
                for effect in effects {
                    match effect {
                        Effect::CancelForeground => {
                            for job in foreground.drain(..) {
                                job.abort();
                            }
                        }
                        Effect::CancelPolls => {
                            for job in polls.drain(..) {
                                job.abort();
                            }
                        }
                        Effect::Save {
                            token,
                            key,
                            update,
                            purpose,
                        } => {
                            // O ator executa as gravações em ordem; cancelamento de rede nunca as interrompe.
                            let event = match backend.save(&key, &update) {
                                Ok(local) => {
                                    let unavailable = local
                                        .draft
                                        .attachments
                                        .iter()
                                        .filter(|a| !backend.available(a))
                                        .map(|a| a.uri.clone())
                                        .collect::<HashSet<_>>();
                                    Event::Saved {
                                        token,
                                        key,
                                        purpose,
                                        local,
                                        unavailable,
                                    }
                                }
                                Err(_) => Event::SaveFailed {
                                    token,
                                    key,
                                    purpose,
                                },
                            };
                            let _ = sender.send(event);
                        }
                        Effect::CheckRecipient {
                            token,
                            key,
                            agent_id,
                        } => {
                            let _ = sender.send(Event::RecipientChecked {
                                token,
                                eligible: backend.eligible(&key, &agent_id),
                            });
                        }
                        Effect::ReleaseUnused(attachments) => {
                            backend.release_unused(&attachments, &state)
                        }
                        Effect::Unauthorized => {
                            if let Some(key) = &state.composer.key {
                                backend.unauthorized(&key.origin);
                            }
                        }
                        Effect::SessionChanged => {
                            if let Some(key) = &state.composer.key {
                                backend.session_changed(key);
                            }
                        }
                        effect => {
                            let origin = match &effect {
                                Effect::PollMessage { key, .. } => Some(key.origin.clone()),
                                _ => state.composer.key.as_ref().map(|key| key.origin.clone()),
                            };
                            let Some(origin) = origin else {
                                continue;
                            };
                            let backend = backend.clone();
                            let sender = sender.clone();
                            let is_poll = matches!(
                                effect,
                                Effect::PollMessage { .. } | Effect::PollCommand { .. }
                            );
                            let job = tokio::spawn(async move {
                                let event = execute(backend, origin, effect, &sender).await;
                                if let Some(event) = event {
                                    let _ = sender.send(event);
                                }
                            });
                            if is_poll {
                                polls.push(job);
                            } else {
                                foreground.push(job);
                            }
                        }
                    }
                }
                if states_tx.is_closed() {
                    break;
                }
            }
            for job in foreground.into_iter().chain(polls) {
                job.abort();
            }
        });
        Self {
            events,
            states,
            task,
        }
    }
    pub fn is_finished(&self) -> bool {
        self.task.is_finished()
    }
}
fn field(value: &serde_json::Value, name: &str) -> Option<String> {
    crate::model::string(value, name)
}
async fn execute(
    backend: Arc<dyn Backend>,
    origin: String,
    effect: Effect,
    sender: &mpsc::UnboundedSender<Event>,
) -> Option<Event> {
    Some(match effect {
        Effect::UploadAttachment {
            token, attachment, ..
        } => {
            let (progress, mut receiver) = mpsc::unbounded_channel();
            let upload = backend.upload(&origin, attachment, progress);
            tokio::pin!(upload);
            loop {
                tokio::select! {
                    result = &mut upload => break match result {
                        Ok(id) => Event::Uploaded { token, id },
                        Err(failure) => Event::SendFailed { token, failure },
                    },
                    Some(bytes) = receiver.recv() => { let _ = sender.send(Event::UploadProgress { token, bytes }); }
                }
            }
        }
        Effect::SendMessage { token, body }
        | Effect::Interrupt { token, body }
        | Effect::OmpControl { token, body } => {
            let control = body.get("stop").is_some();
            let command = body.get("command").is_some()
                || body.get("model").is_some()
                || body.get("thinking").is_some();
            let path = if control || command {
                "/api/messages/control"
            } else {
                "/api/messages"
            };
            let response = backend
                .request(&origin, path.into(), Some(body))
                .await
                .and_then(|value| {
                    if field(&value, "id").is_none()
                        || (!command && field(&value, "status").is_none())
                    {
                        Err(Failure::default())
                    } else {
                        Ok(value)
                    }
                });
            if command {
                match response {
                    Ok(v) => Event::CommandResult {
                        token,
                        id: field(&v, "id"),
                        status: field(&v, "status"),
                        failure: None,
                    },
                    Err(failure) => Event::CommandResult {
                        token,
                        id: None,
                        status: None,
                        failure: Some(failure),
                    },
                }
            } else {
                match response {
                    Ok(v) => {
                        let id = field(&v, "id").unwrap_or_default();
                        let status = field(&v, "status").unwrap_or_default();
                        if control {
                            Event::ControlAccepted { token, id, status }
                        } else {
                            Event::SendAccepted { token, id, status }
                        }
                    }
                    Err(failure) => {
                        if control {
                            Event::ControlFailed { token, failure }
                        } else {
                            Event::SendFailed { token, failure }
                        }
                    }
                }
            }
        }
        Effect::PollMessage {
            key,
            id,
            control,
            delay_ms,
        } => {
            tokio::time::sleep(std::time::Duration::from_millis(delay_ms)).await;
            match backend
                .request(
                    &origin,
                    format!("/api/messages/{}", crate::api::encode_segment(&id)),
                    None,
                )
                .await
            {
                Ok(v) => Event::PollResult {
                    key,
                    id,
                    control,
                    status: field(&v, "status"),
                    error: field(&v, "error"),
                    failure: None,
                },
                Err(failure) => Event::PollResult {
                    key,
                    id,
                    control,
                    status: None,
                    error: None,
                    failure: Some(failure),
                },
            }
        }
        Effect::PollCommand {
            token,
            id,
            delay_ms,
        } => {
            tokio::time::sleep(std::time::Duration::from_millis(delay_ms)).await;
            match backend
                .request(
                    &origin,
                    format!("/api/messages/{}", crate::api::encode_segment(&id)),
                    None,
                )
                .await
            {
                Ok(v) => Event::CommandResult {
                    token,
                    id: Some(id),
                    status: field(&v, "status"),
                    failure: None,
                },
                Err(failure) => Event::CommandResult {
                    token,
                    id: Some(id),
                    status: None,
                    failure: Some(failure),
                },
            }
        }
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::{
        Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    };
    #[derive(Default)]
    struct Fake {
        local: Mutex<MessageLocalState>,
        calls: Mutex<Vec<(String, Option<serde_json::Value>)>>,
        fail: Mutex<Option<Failure>>,
        upload_wait: AtomicBool,
        uploads: AtomicUsize,
        active_uploads: Arc<AtomicUsize>,
        cancelled: AtomicUsize,
        unauthorized: AtomicUsize,
    }
    struct ActiveUpload(Arc<AtomicUsize>);
    impl Drop for ActiveUpload {
        fn drop(&mut self) {
            self.0.fetch_sub(1, Ordering::SeqCst);
        }
    }
    impl Backend for Fake {
        fn save(
            &self,
            _: &SessionKey,
            update: &MessageUpdate,
        ) -> Result<MessageLocalState, String> {
            let mut local = self.local.lock().unwrap();
            *local = composer::update_message(local.clone(), update);
            Ok(local.clone())
        }
        fn available(&self, _: &Attachment) -> bool {
            true
        }
        fn eligible(&self, _: &SessionKey, _: &str) -> bool {
            true
        }
        fn request(
            &self,
            _: &str,
            path: String,
            body: Option<serde_json::Value>,
        ) -> BoxFuture<'static, Result<serde_json::Value, Failure>> {
            self.calls.lock().unwrap().push((path, body.clone()));
            let failure = self.fail.lock().unwrap().take();
            Box::pin(async move {
                if let Some(failure) = failure {
                    Err(failure)
                } else {
                    Ok(if body.is_some() {
                        json!({"id":"receipt","status":"queued"})
                    } else {
                        json!({"status":"delivered"})
                    })
                }
            })
        }
        fn upload(
            &self,
            _: &str,
            _: Attachment,
            progress: mpsc::UnboundedSender<i64>,
        ) -> BoxFuture<'static, Result<String, Failure>> {
            self.uploads.fetch_add(1, Ordering::SeqCst);
            let wait = self.upload_wait.load(Ordering::SeqCst);
            let _ = progress.send(31);
            let active = self.active_uploads.clone();
            Box::pin(async move {
                active.fetch_add(1, Ordering::SeqCst);
                let _guard = ActiveUpload(active);
                if wait {
                    std::future::pending::<()>().await;
                }
                Ok("attachment-id".into())
            })
        }
        fn release_unused(&self, _: &[Attachment], _: &State) {
            self.cancelled.fetch_add(1, Ordering::SeqCst);
        }
        fn unauthorized(&self, _: &str) {
            self.unauthorized.fetch_add(1, Ordering::SeqCst);
        }
        fn session_changed(&self, _: &SessionKey) {}
    }
    fn key(id: &str) -> SessionKey {
        SessionKey {
            origin: "http://localhost".into(),
            provider: "omp".into(),
            account: "test".into(),
            session_id: id.into(),
        }
    }
    async fn settle() {
        for _ in 0..40 {
            tokio::task::yield_now().await;
        }
    }
    async fn ready(fake: Arc<Fake>) -> MessageController {
        let controller = MessageController::start(fake);
        controller.events.send(Event::Start).unwrap();
        controller.events.send(Event::Open(key("first"))).unwrap();
        settle().await;
        controller
            .events
            .send(Event::Edit {
                text: "ação".into(),
                attachments: vec![],
            })
            .unwrap();
        settle().await;
        controller
    }
    fn send(controller: &MessageController) {
        controller
            .events
            .send(Event::Send {
                agent_id: "agent".into(),
                confirmed: false,
                eligible: true,
            })
            .unwrap();
    }
    #[tokio::test(start_paused = true)]
    async fn send_once_and_poll_only_after_delay() {
        let fake = Arc::new(Fake::default());
        let controller = ready(fake.clone()).await;
        send(&controller);
        settle().await;
        assert_eq!(
            controller
                .states
                .borrow()
                .composer
                .operation
                .as_ref()
                .unwrap()
                .status,
            "queued"
        );
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
        assert_eq!(
            fake.calls.lock().unwrap()[0].1.as_ref().unwrap()["deliverAs"],
            "aside"
        );
        tokio::time::advance(std::time::Duration::from_millis(1999)).await;
        settle().await;
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
        tokio::time::advance(std::time::Duration::from_millis(1)).await;
        settle().await;
        assert_eq!(
            controller
                .states
                .borrow()
                .composer
                .operation
                .as_ref()
                .unwrap()
                .status,
            "delivered"
        );
        assert!(controller.states.borrow().composer.draft.text.is_empty());
    }
    #[tokio::test(start_paused = true)]
    async fn definite_failure_allows_explicit_retry_without_replay() {
        let fake = Arc::new(Fake::default());
        let controller = ready(fake.clone()).await;
        *fake.fail.lock().unwrap() = Some(Failure {
            status: Some(403),
            ..Failure::default()
        });
        send(&controller);
        settle().await;
        assert_eq!(controller.states.borrow().composer.draft.text, "ação");
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
        tokio::time::advance(std::time::Duration::from_secs(10)).await;
        settle().await;
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
        send(&controller);
        settle().await;
        assert_eq!(fake.calls.lock().unwrap().len(), 2);
        assert_eq!(
            controller
                .states
                .borrow()
                .composer
                .operation
                .as_ref()
                .unwrap()
                .status,
            "queued"
        );
    }
    #[tokio::test(start_paused = true)]
    async fn unauthorized_notifies_owner() {
        let fake = Arc::new(Fake::default());
        let controller = ready(fake.clone()).await;
        *fake.fail.lock().unwrap() = Some(Failure {
            status: Some(401),
            ..Failure::default()
        });
        send(&controller);
        settle().await;
        assert_eq!(fake.unauthorized.load(Ordering::SeqCst), 1);
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
    }
    #[tokio::test(start_paused = true)]
    async fn upload_progress_and_cancel_before_session_switch() {
        let fake = Arc::new(Fake::default());
        fake.upload_wait.store(true, Ordering::SeqCst);
        let controller = ready(fake.clone()).await;
        controller
            .events
            .send(Event::Edit {
                text: "".into(),
                attachments: vec![Attachment {
                    uri: "file:///test".into(),
                    name: "test".into(),
                    mime: "text/plain".into(),
                    size: Some(31),
                    persistent: true,
                }],
            })
            .unwrap();
        settle().await;
        send(&controller);
        settle().await;
        assert_eq!(fake.uploads.load(Ordering::SeqCst), 1);
        assert_eq!(fake.active_uploads.load(Ordering::SeqCst), 1);
        assert!(controller.states.borrow().composer.progress.is_some());
        controller.events.send(Event::Open(key("second"))).unwrap();
        settle().await;
        assert_eq!(fake.active_uploads.load(Ordering::SeqCst), 0);
        tokio::time::advance(std::time::Duration::from_secs(30)).await;
        settle().await;
        assert!(fake.calls.lock().unwrap().is_empty());
        assert!(!controller.states.borrow().composer.sending);
        assert!(fake.cancelled.load(Ordering::SeqCst) > 0);
    }
    #[tokio::test(start_paused = true)]
    async fn upload_is_sent_before_message() {
        let fake = Arc::new(Fake::default());
        let controller = ready(fake.clone()).await;
        controller
            .events
            .send(Event::Edit {
                text: "".into(),
                attachments: vec![Attachment {
                    uri: "file:///test".into(),
                    name: "test".into(),
                    mime: "text/plain".into(),
                    size: Some(31),
                    persistent: true,
                }],
            })
            .unwrap();
        settle().await;
        send(&controller);
        settle().await;
        assert_eq!(fake.uploads.load(Ordering::SeqCst), 1);
        assert_eq!(
            fake.calls.lock().unwrap()[0].1.as_ref().unwrap()["attachments"],
            json!(["attachment-id"])
        );
    }
    #[tokio::test(start_paused = true)]
    async fn ambiguous_post_requires_confirmation_and_never_replays() {
        let fake = Arc::new(Fake::default());
        let controller = ready(fake.clone()).await;
        *fake.fail.lock().unwrap() = Some(Failure::default());
        send(&controller);
        settle().await;
        assert_eq!(
            controller
                .states
                .borrow()
                .composer
                .operation
                .as_ref()
                .unwrap()
                .status,
            "unknown"
        );
        send(&controller);
        settle().await;
        assert_eq!(
            controller.states.borrow().composer.error.as_deref(),
            Some("confirm")
        );
        assert_eq!(fake.calls.lock().unwrap().len(), 1);
        controller
            .events
            .send(Event::Send {
                agent_id: "agent".into(),
                confirmed: true,
                eligible: true,
            })
            .unwrap();
        settle().await;
        assert_eq!(fake.calls.lock().unwrap().len(), 2);
        controller.events.send(Event::Close).unwrap();
        settle().await;
        tokio::time::advance(std::time::Duration::from_secs(60)).await;
        settle().await;
        assert_eq!(fake.calls.lock().unwrap().len(), 2);
    }
}
