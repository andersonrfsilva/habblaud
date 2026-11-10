use crate::{
    AppWindow, ComposerAttachment, ComposerState,
    feature::{AppSnapshot, FeatureContext},
    state, strings, update_ui,
};
use futures_util::future::BoxFuture;
use slint::{ComponentHandle, ModelRc, VecModel};
use std::sync::{Arc, RwLock};
use tokio::sync::mpsc;
use zaplaud_core::{
    api::HabblaudApi,
    controller::message::{Backend, MessageController},
    model::*,
    rules::{
        attachments,
        composer::{self, Event, Failure, MessageUpdate, State},
    },
};
use zaplaud_store::LocalStateStore;

struct NativeBackend {
    store: LocalStateStore,
    snapshot: Arc<RwLock<Option<Arc<AppSnapshot>>>>,
    commands: mpsc::UnboundedSender<state::Event>,
}
fn failure(error: zaplaud_core::Error) -> Failure {
    match error {
        zaplaud_core::Error::Unauthorized => Failure {
            status: Some(401),
            ..Failure::default()
        },
        zaplaud_core::Error::Api {
            status,
            code,
            attachments_expired,
            ..
        } => Failure {
            status: Some(status),
            session_changed: code.as_deref() == Some("session_changed"),
            attachments_expired,
            cancelled: false,
        },
        _ => Failure::default(),
    }
}
fn attachment_path(attachment: &Attachment) -> Option<std::path::PathBuf> {
    url::Url::parse(&attachment.uri).ok()?.to_file_path().ok()
}
fn available(attachment: &Attachment) -> bool {
    attachment_path(attachment)
        .and_then(|path| std::fs::metadata(path).ok())
        .is_some_and(|metadata| {
            metadata.is_file() && attachment.size == i64::try_from(metadata.len()).ok()
        })
}
impl NativeBackend {
    fn api(&self) -> Option<HabblaudApi> {
        let snapshot = self.snapshot.read().ok()?;
        let snapshot = snapshot.as_ref()?;
        let origin = &snapshot.selected.as_ref()?.key.origin;
        snapshot
            .servers
            .iter()
            .find(|s| s.api.origin() == origin)
            .map(|s| s.api.clone())
    }
}
impl Backend for NativeBackend {
    fn save(&self, key: &SessionKey, update: &MessageUpdate) -> Result<MessageLocalState, String> {
        self.store
            .update_message(key, |local| composer::update_message(local, update))
            .map_err(|e| e.to_string())
    }
    fn available(&self, attachment: &Attachment) -> bool {
        available(attachment)
    }
    fn eligible(&self, key: &SessionKey, agent_id: &str) -> bool {
        self.snapshot
            .read()
            .ok()
            .and_then(|s| s.clone())
            .is_some_and(|snapshot| {
                snapshot.servers.iter().any(|s| {
                    s.api.origin() == key.origin
                        && !s.needs_login
                        && composer::can_send(&s.reading, true, true, key, agent_id)
                })
            })
    }
    fn request(
        &self,
        path: String,
        body: Option<serde_json::Value>,
    ) -> BoxFuture<'static, Result<serde_json::Value, Failure>> {
        let api = self.api();
        Box::pin(async move {
            let text = api
                .ok_or_else(Failure::default)?
                .request(&path, body.as_ref())
                .await
                .map_err(failure)?;
            serde_json::from_str(&text).map_err(|_| Failure::default())
        })
    }
    fn upload(
        &self,
        attachment: Attachment,
        progress: mpsc::UnboundedSender<i64>,
    ) -> BoxFuture<'static, Result<String, Failure>> {
        let api = self.api();
        Box::pin(async move {
            let path = attachment_path(&attachment).ok_or_else(Failure::default)?;
            let file = tokio::fs::File::open(path)
                .await
                .map_err(|_| Failure::default())?;
            api.ok_or_else(Failure::default)?
                .upload(
                    &attachment.name,
                    &attachment.mime,
                    file,
                    attachment.size.and_then(|s| u64::try_from(s).ok()),
                    move |bytes| {
                        let _ = progress.send(bytes as i64);
                    },
                )
                .await
                .map_err(failure)
        })
    }
    fn release_unused(&self, attachments: &[Attachment], active: &State) {
        for attachment in attachments {
            if active.active_attachment(&attachment.uri)
                || self.store.uses_attachment(&attachment.uri).unwrap_or(true)
            {
                continue;
            }
            // No desktop a URI aponta para o arquivo do usuário: nunca apague nem altere esse arquivo.
        }
    }
    fn unauthorized(&self) {
        if let Some(api) = self.api() {
            let _ = self
                .commands
                .send(state::Event::Unauthorized(api.origin().into()));
        }
    }
    fn session_changed(&self) {
        let _ = self.commands.send(state::Event::Close);
    }
}
#[derive(Clone)]
enum Action {
    Edit(String),
    Send(bool),
    Stop(bool),
    Command(String, String),
    Remove(String),
    Attach,
    Paths(String),
    PickerDismiss,
    Mode(i32),
    Dismiss,
    Confirm,
    Visibility(bool),
}

pub fn setup(window: &AppWindow, context: &FeatureContext) {
    let global = window.global::<ComposerState>();
    global.set_placeholder(strings::MESSAGE_TEXT.into());
    global.set_attach_label(strings::ATTACH_FILES.into());
    global.set_send_label(strings::SEND_MESSAGE.into());
    global.set_stop_label(strings::STOP_AGENT.into());
    global.set_retry_label(strings::CONFIRM.into());
    global.set_confirm_label(strings::CONFIRM.into());
    global.set_cancel_label(strings::CANCEL.into());
    global.set_mode_labels(ModelRc::new(VecModel::from(vec![
        strings::DELIVER_ASIDE.into(),
        strings::DELIVER_FOLLOW_UP.into(),
        strings::DELIVER_STEER.into(),
    ])));
    global.set_shake_title(strings::SHAKE.into());
    global.set_shake_text(strings::SHAKE_CONFIRM.into());
    global.set_picker_label(crate::native_strings::PICKER_PATHS.into());
    global.set_picker_hint(crate::native_strings::PICKER_PATHS_HINT.into());
    let (actions, mut receiver) = mpsc::unbounded_channel();
    {
        let actions = actions.clone();
        global.on_edited(move |value| {
            let _ = actions.send(Action::Edit(value.into()));
        });
    }
    {
        let actions = actions.clone();
        global.on_remove(move |value| {
            let _ = actions.send(Action::Remove(value.into()));
        });
    }
    {
        let actions = actions.clone();
        global.on_mode(move |value| {
            let _ = actions.send(Action::Mode(value));
        });
    }
    {
        let actions = actions.clone();
        global.on_visibility_changed(move |visible| {
            let _ = actions.send(Action::Visibility(visible));
        });
    }
    {
        let actions = actions.clone();
        global.on_send(move || {
            let _ = actions.send(Action::Send(false));
        });
    }
    {
        let actions = actions.clone();
        global.on_stop(move || {
            let _ = actions.send(Action::Stop(false));
        });
    }
    {
        let actions = actions.clone();
        global.on_attach(move || {
            let _ = actions.send(Action::Attach);
        });
    }
    {
        let actions = actions.clone();
        global.on_picker_submit(move |paths| {
            let _ = actions.send(Action::Paths(paths.into()));
        });
    }
    {
        let actions = actions.clone();
        global.on_picker_dismiss(move || {
            let _ = actions.send(Action::PickerDismiss);
        });
    }
    {
        let actions = actions.clone();
        global.on_command(move |kind, value| {
            let _ = actions.send(Action::Command(kind.into(), value.into()));
        });
    }
    {
        let actions = actions.clone();
        global.on_dismiss(move || {
            let _ = actions.send(Action::Dismiss);
        });
    }
    {
        let actions = actions.clone();
        global.on_confirm(move || {
            let _ = actions.send(Action::Confirm);
        });
    }
    let weak = window.as_weak();
    let mut snapshots = context.snapshots.subscribe();
    let context = context.clone();
    context.runtime.clone().spawn(async move {
        let store = match LocalStateStore::new() { Ok(store) => store, Err(_) => { update_ui(&weak, |w| w.global::<ComposerState>().set_error(strings::STORAGE_ERROR.into())); return; } };
        let snapshot = Arc::new(RwLock::new(None));
        let backend = Arc::new(NativeBackend { store: store.clone(), snapshot: snapshot.clone(), commands: context.commands.clone() });
        let mut controller = MessageController::start(backend.clone());
        let mut selected: Option<SessionItem> = None;
        let mut command_dismiss = None::<tokio::task::JoinHandle<()>>;
        let mut last_command = None;
        let mut picker = None::<tokio::task::JoinHandle<()>>;
        let _ = controller.events.send(Event::Start);
        loop {
            tokio::select! {
                result = snapshots.recv() => match result {
                    Ok(value) => {
                        let next = value.selected.clone().filter(|s| s.agent_id.is_some() && !s.sub);
                        if next.as_ref().map(|s| &s.key) != selected.as_ref().map(|s| &s.key) {
                            let _ = controller.events.send(Event::Stop);
                            let _ = controller.events.send(Event::Close);
                            if let Some(job) = picker.take() { job.abort(); }
                            if let Some(item) = &next {
                                let _ = controller.events.send(Event::Start);
                                let _ = controller.events.send(Event::Open(item.key.clone()));
                                match store.session_deliver_as(&item.key) {
                                    Ok(mode) => { let _ = controller.events.send(Event::SetDeliverAs(mode)); }
                                    Err(_) => { let _ = controller.events.send(Event::AttachmentFailed(item.key.clone())); }
                                }
                            }
                        }
                        selected = next;
                        if let Ok(mut current) = snapshot.write() { *current = Some(value); }
                        publish(&weak, &controller.states.borrow(), selected.as_ref(), backend.as_ref());
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                },
                result = controller.states.changed() => {
                    if result.is_err() { break; }
                    let state = controller.states.borrow_and_update().clone();
                    if state.composer.command_result != last_command {
                        if let Some(job) = command_dismiss.take() { job.abort(); }
                        last_command = state.composer.command_result.clone();
                        if last_command.as_ref().is_some_and(|r| r.status == "sent") {
                            let sender = controller.events.clone();
                            command_dismiss = Some(tokio::spawn(async move { tokio::time::sleep(std::time::Duration::from_secs(4)).await; let _ = sender.send(Event::DismissCommandResult); }));
                        }
                    }
                    publish(&weak, &state, selected.as_ref(), backend.as_ref());
                }
                action = receiver.recv() => {
                    let Some(action) = action else { break; };
                    let Some(item) = &selected else { continue; };
                    let state = controller.states.borrow().clone();
                    let agent_id = item.agent_id.clone().unwrap_or_default();
                    let eligible = backend.eligible(&item.key, &agent_id);
                    let event = match action {
                        Action::Edit(text) => Some(Event::Edit { text, attachments: state.composer.draft.attachments.clone() }),
                        Action::Send(confirmed) => Some(Event::Send { agent_id, confirmed, eligible }),
                        Action::Stop(confirmed) => Some(Event::Interrupt { agent_id, confirmed, eligible }),
                        Action::Command(kind,value) => Some(Event::Command { agent_id, kind, value, eligible }),
                        Action::Remove(uri) => state.composer.draft.attachments.iter().find(|a| a.uri == uri).cloned().map(Event::Remove),
                        Action::Mode(index) => {
                            let mode = ["aside", "followUp", "steer"].get(index as usize).copied();
                            mode.and_then(|mode| match store.set_session_deliver_as(&item.key,mode) { Ok(()) => Some(Event::SetDeliverAs(mode.into())), Err(_) => Some(Event::SaveFailed { token: 0, key: item.key.clone(), purpose: composer::SavePurpose::Edit }) })
                        }
                        Action::Dismiss => {
                            let _ = controller.events.send(Event::DismissControlConfirmation);
                            Some(Event::DismissConfirmation)
                        }
                        Action::Confirm => if state.composer.control_error.as_deref() == Some("confirm") { Some(Event::Interrupt { agent_id, confirmed: true, eligible }) } else { Some(Event::Send { agent_id, confirmed: true, eligible }) },
                        Action::Attach => {
                            if picker.as_ref().is_some_and(|job| !job.is_finished()) { continue; }
                            let sender = controller.events.clone(); let key = item.key.clone(); let before = state.composer.draft.attachments.clone(); let weak = weak.clone();
                            picker = Some(tokio::spawn(async move {
                                match pick_files().await {
                                    Ok(Some(paths)) => {
                                        let uris: Vec<_> = paths.iter().filter_map(|path| url::Url::from_file_path(path).ok().map(|uri| uri.to_string())).collect();
                                        let event = match select_attachments(&uris,&before) { Ok(selected) => Event::AttachmentsSelected { key, before, selected }, Err(_) => Event::AttachmentFailed(key) };
                                        let _ = sender.send(event);
                                    }
                                    Ok(None) => {}
                                    Err(()) => update_ui(&weak, |window| {
                                        let global = window.global::<ComposerState>();
                                        global.set_picker_text("".into());
                                        global.set_picker_visible(true);
                                    }),
                                }
                            })); None
                        }
                        Action::Paths(paths) => {
                            let uris = paths.lines().filter(|line| !line.trim().is_empty()).map(|line| url::Url::from_file_path(line.trim()).map(|uri| uri.to_string())).collect::<Result<Vec<_>,_>>();
                            match uris.ok().and_then(|uris| select_attachments(&uris,&state.composer.draft.attachments).ok()) {
                                Some(selected) => {
                                    update_ui(&weak, |window| window.global::<ComposerState>().set_picker_visible(false));
                                    Some(Event::AttachmentsSelected { key: item.key.clone(), before: state.composer.draft.attachments.clone(), selected })
                                }
                                None => Some(Event::AttachmentFailed(item.key.clone())),
                            }
                        }
                        Action::PickerDismiss => { update_ui(&weak, |window| window.global::<ComposerState>().set_picker_visible(false)); None }
                        Action::Visibility(visible) => Some(if visible { Event::Start } else { Event::Stop }),
                    };
                    if let Some(event) = event { let _ = controller.events.send(event); }
                }
            }
            if weak.upgrade_in_event_loop(|_| {}).is_err() { break; }
        }
        let _ = controller.events.send(Event::Stop);
        if let Some(job) = picker { job.abort(); }
        if let Some(job) = command_dismiss { job.abort(); }
    });
}
async fn pick_files() -> Result<Option<Vec<std::path::PathBuf>>, ()> {
    #[cfg(target_os = "linux")]
    {
        let portal = tokio::process::Command::new("dbus-send")
            .args([
                "--session",
                "--dest=org.freedesktop.DBus",
                "--type=method_call",
                "--print-reply",
                "/org/freedesktop/DBus",
                "org.freedesktop.DBus.ListActivatableNames",
            ])
            .kill_on_drop(true)
            .output()
            .await
            .is_ok_and(|output| {
                output.status.success()
                    && String::from_utf8_lossy(&output.stdout)
                        .contains("org.freedesktop.portal.Desktop")
            });
        if !portal {
            for (program, args, separator) in [
                (
                    "zenity",
                    vec!["--file-selection", "--multiple", "--separator=\u{1f}"],
                    '\u{1f}',
                ),
                (
                    "kdialog",
                    vec![
                        "--getopenfilename",
                        ".",
                        "*",
                        "--multiple",
                        "--separate-output",
                    ],
                    '\n',
                ),
            ] {
                let output = tokio::process::Command::new(program)
                    .args(args)
                    .kill_on_drop(true)
                    .output()
                    .await;
                match output {
                    Ok(output) if output.status.success() => {
                        let text = String::from_utf8(output.stdout).map_err(|_| ())?;
                        return Ok(Some(
                            text.trim_end()
                                .split(separator)
                                .filter(|path| !path.is_empty())
                                .map(std::path::PathBuf::from)
                                .collect(),
                        ));
                    }
                    Ok(output) if output.status.code() == Some(1) => return Ok(None),
                    Ok(_) => continue,
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
                    Err(_) => continue,
                }
            }
            return Err(());
        }
    }
    Ok(rfd::AsyncFileDialog::new().pick_files().await.map(|files| {
        files
            .into_iter()
            .map(|file| file.path().to_owned())
            .collect()
    }))
}

fn select_attachments(uris: &[String], existing: &[Attachment]) -> Result<Vec<Attachment>, String> {
    let added = attachments::new_attachment_uris(uris, existing).map_err(str::to_owned)?;
    let mut selected = existing.to_vec();
    let mime_types = std::fs::read_to_string("/etc/mime.types").unwrap_or_default();
    let mime_types = mime_types
        .lines()
        .filter(|line| !line.starts_with('#'))
        .flat_map(|line| {
            let mut fields = line.split_whitespace();
            let mime = fields.next().unwrap_or_default();
            fields.map(move |extension| (extension.to_lowercase(), mime.to_owned()))
        })
        .collect();
    for uri in added {
        let path = url::Url::parse(&uri)
            .map_err(|e| e.to_string())?
            .to_file_path()
            .map_err(|_| "URI inválida")?;
        let metadata = std::fs::metadata(&path).map_err(|e| e.to_string())?;
        let size = i64::try_from(metadata.len()).map_err(|e| e.to_string())?;
        attachments::validate_attachment_size(Some(size)).map_err(str::to_owned)?;
        if !metadata.is_file() || std::fs::File::open(&path).is_err() {
            return Err("Arquivo indisponível".into());
        }
        let name = path
            .file_name()
            .ok_or("Nome inválido")?
            .to_string_lossy()
            .into_owned();
        let mime = attachments::guess_mime(&name, &mime_types);
        selected.push(Attachment {
            uri,
            name,
            mime,
            size: Some(size),
            persistent: true,
        });
    }
    Ok(selected)
}
fn publish(
    weak: &slint::Weak<AppWindow>,
    state: &State,
    selected: Option<&SessionItem>,
    backend: &NativeBackend,
) {
    let composer = state.composer.clone();
    let selected = selected.cloned();
    let enabled = selected.as_ref().is_some_and(|item| {
        backend.eligible(&item.key, item.agent_id.as_deref().unwrap_or_default())
    });
    let mode = ["aside", "followUp", "steer"]
        .iter()
        .position(|mode| *mode == state.deliver_as)
        .unwrap_or(0) as i32;
    let blocked = backend
        .snapshot
        .read()
        .ok()
        .and_then(|snapshot| snapshot.clone())
        .and_then(|snapshot| {
            let item = selected.as_ref()?;
            let server = snapshot
                .servers
                .iter()
                .find(|server| server.api.origin() == item.key.origin)?;
            Some(if !server.reading.messages_allowed {
                strings::MESSAGES_BLOCKED
            } else if !server.reading.message_guard {
                strings::MESSAGE_GUARD_REQUIRED
            } else if !item.can_message {
                strings::MESSAGE_UNAVAILABLE
            } else {
                ""
            })
        })
        .unwrap_or("");
    update_ui(weak, move |window| {
        let global = window.global::<ComposerState>();
        global.set_visible(selected.is_some());
        global.set_blocked(blocked.into());
        global.set_text(composer.draft.text.clone().into());
        global.set_loading(composer.loading);
        global.set_sending(composer.sending);
        global.set_send_enabled(
            enabled
                && !composer.loading
                && !composer.sending
                && composer.unavailable.is_empty()
                && (!composer.draft.text.trim().is_empty()
                    || !composer.draft.attachments.is_empty()),
        );
        global.set_attach_enabled(
            !composer.loading && composer.draft.attachments.len() < MAX_ATTACHMENTS,
        );
        global.set_mode_index(mode);
        global.set_modes_visible(
            selected
                .as_ref()
                .is_some_and(composer::deliver_modes_available),
        );
        global.set_stop_visible(selected.as_ref().is_some_and(|s| {
            s.key.provider == "omp" && composer::stop_visible(s.status.as_deref())
        }));
        global.set_stop_enabled(
            enabled
                && !composer.loading
                && !composer.sending
                && !composer
                    .control
                    .as_ref()
                    .is_some_and(|c| matches!(c.status.as_str(), "queued" | "sent")),
        );
        global.set_can_shake(enabled && selected.as_ref().is_some_and(|s| s.key.provider == "omp"));
        let ring = selected
            .as_ref()
            .and_then(|s| composer::context_ring(s.context_tokens?, s.context_window?));
        global.set_context_visible(ring.is_some());
        if let Some(ring) = ring {
            global.set_context_fraction(ring.fraction);
            let angle = f64::from(ring.fraction.min(0.99999)) * std::f64::consts::TAU;
            let x = 10.0 + angle.sin() * 10.0;
            let y = 10.0 - angle.cos() * 10.0;
            global.set_context_path(if ring.fraction <= 0.0 {
                "M10 0".into()
            } else {
                format!(
                    "M10 0 A10 10 0 {} 1 {x} {y}",
                    i32::from(ring.fraction > 0.5)
                )
                .into()
            });
            global.set_context_label(
                crate::formatted(
                    "context_usage",
                    &[
                        &ring.percent.to_string(),
                        &ring.tokens_k.to_string(),
                        &ring.window_k.to_string(),
                    ],
                )
                .into(),
            );
        }
        let status = composer
            .operation
            .as_ref()
            .filter(|op| op.status != "delivered")
            .map(|op| match op.status.as_str() {
                "queued" => strings::MESSAGE_QUEUED,
                "sent" => strings::MESSAGE_SENT,
                "failed" => strings::MESSAGE_FAILED,
                _ => strings::MESSAGE_UNKNOWN,
            })
            .unwrap_or("");
        global.set_status(status.into());
        global.set_status_id(
            composer
                .operation
                .as_ref()
                .map(|op| format!("message-status-{}", op.status))
                .unwrap_or_default()
                .into(),
        );
        global.set_control_status(
            composer
                .control
                .as_ref()
                .filter(|op| op.status != "delivered")
                .map(|op| match op.status.as_str() {
                    "queued" | "sent" => strings::STOP_PENDING,
                    "failed" => strings::STOP_FAILED,
                    _ => strings::STOP_UNKNOWN,
                })
                .unwrap_or("")
                .into(),
        );
        global.set_error(
            composer
                .error
                .as_deref()
                .map(|error| match error {
                    "confirm" => "",
                    "storage" => strings::STORAGE_ERROR,
                    "unknown" => strings::MESSAGE_UNKNOWN,
                    "attachment" => strings::ATTACHMENT_ERROR,
                    "attachments_expired" => strings::ATTACHMENTS_EXPIRED,
                    _ => strings::MESSAGE_SEND_ERROR,
                })
                .unwrap_or("")
                .into(),
        );
        global.set_confirm_visible(
            composer.error.as_deref() == Some("confirm")
                || composer.control_error.as_deref() == Some("confirm"),
        );
        global.set_confirm_title(
            if composer.control_error.as_deref() == Some("confirm") {
                strings::STOP_AGENT
            } else {
                strings::MESSAGE_RETRY_TITLE
            }
            .into(),
        );
        global.set_confirm_text(
            if composer.control_error.as_deref() == Some("confirm") {
                strings::STOP_UNKNOWN
            } else {
                strings::MESSAGE_RETRY_WARNING
            }
            .into(),
        );
        global.set_progress(composer.progress.unwrap_or_default().into());
        global.set_attachments(ModelRc::new(VecModel::from(
            composer
                .draft
                .attachments
                .iter()
                .map(|a| ComposerAttachment {
                    uri: a.uri.clone().into(),
                    name: format!("{} · {} B", a.name, a.size.unwrap_or(0)).into(),
                    error: composer.unavailable.contains(&a.uri),
                    label: strings::ATTACHMENT_REMOVE.into(),
                })
                .collect::<Vec<_>>(),
        )));
        global.set_character_count(
            if composer.draft.text.encode_utf16().count() >= 18000 {
                format!("{}/20000", composer.draft.text.encode_utf16().count())
            } else {
                String::new()
            }
            .into(),
        );
        let command = composer
            .command_result
            .map(|result| {
                let what = match result.kind.as_str() {
                    "model" => strings::MODEL_MENU,
                    "thinking" => strings::THINKING_MENU,
                    _ => strings::SHAKE_SHORT,
                };
                crate::formatted(
                    match result.status.as_str() {
                        "pending" => "command_pending",
                        "sent" => "command_sent",
                        "failed" => "command_failed",
                        _ => "command_unknown",
                    },
                    &[what],
                )
            })
            .unwrap_or_default();
        global.set_command_status(command.into());
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn attachment_selection_deduplicates_and_rejects_sixth_and_oversize() {
        let root = tempfile::tempdir().unwrap();
        let mut uris = Vec::new();
        for index in 0..6 {
            let path = root.path().join(format!("{index}.txt"));
            std::fs::write(&path, b"arquivo de exatamente 31 bytes!!").unwrap();
            uris.push(url::Url::from_file_path(path).unwrap().to_string());
        }
        let selected = select_attachments(&uris[..5], &[]).unwrap();
        assert_eq!(selected.len(), 5);
        assert_eq!(select_attachments(&uris[..1], &selected).unwrap(), selected);
        assert!(select_attachments(&uris[5..], &selected).is_err());
        let path = root.path().join("large.bin");
        std::fs::File::create(&path)
            .unwrap()
            .set_len(MAX_ATTACHMENT_SIZE as u64 + 1)
            .unwrap();
        assert!(
            select_attachments(&[url::Url::from_file_path(path).unwrap().to_string()], &[])
                .is_err()
        );
    }
}
