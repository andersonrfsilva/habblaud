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

static COMPOSER_ACTIONS: std::sync::OnceLock<mpsc::UnboundedSender<Action>> =
    std::sync::OnceLock::new();

pub async fn flush() -> Result<(), String> {
    let Some(actions) = COMPOSER_ACTIONS.get() else {
        return Ok(());
    };
    let (reply, result) = tokio::sync::oneshot::channel();
    actions
        .send(Action::Flush(reply))
        .map_err(|_| "Compositor encerrado".to_owned())?;
    result
        .await
        .map_err(|_| "Compositor encerrado".to_owned())?
}

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
fn file_identity(metadata: &std::fs::Metadata) -> std::io::Result<AttachmentIdentity> {
    let modified_nanos = metadata
        .modified()?
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(std::io::Error::other)?
        .as_nanos();
    #[cfg(unix)]
    let (device, inode) = {
        use std::os::unix::fs::MetadataExt;
        (metadata.dev(), metadata.ino())
    };
    #[cfg(not(unix))]
    let (device, inode) = (0, 0);
    Ok(AttachmentIdentity {
        modified_nanos,
        device,
        inode,
    })
}
fn open_regular(path: &std::path::Path) -> std::io::Result<(std::fs::File, std::fs::Metadata)> {
    let mut options = std::fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    if std::fs::symlink_metadata(path)?.file_type().is_symlink() {
        return Err(std::io::Error::other("Arquivo simbólico"));
    }
    let file = options.open(path)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() {
        return Err(std::io::Error::other("Arquivo não regular"));
    }
    Ok((file, metadata))
}
fn open_attachment(attachment: &Attachment) -> std::io::Result<std::fs::File> {
    let path = attachment_path(attachment).ok_or_else(|| std::io::Error::other("URI inválida"))?;
    let (file, metadata) = open_regular(&path)?;
    let size = i64::try_from(metadata.len()).map_err(std::io::Error::other)?;
    attachments::validate_attachment_size(Some(size)).map_err(std::io::Error::other)?;
    if attachment.size != Some(size)
        || attachment.identity.as_ref() != Some(&file_identity(&metadata)?)
    {
        return Err(std::io::Error::other("Arquivo alterado desde a seleção"));
    }
    Ok(file)
}
fn available(attachment: &Attachment) -> bool {
    open_attachment(attachment).is_ok()
}
impl NativeBackend {
    fn api(&self, origin: &str) -> Option<HabblaudApi> {
        let snapshot = self.snapshot.read().ok()?;
        let snapshot = snapshot.as_ref()?;
        snapshot
            .servers
            .iter()
            .find(|s| s.api.origin() == origin)
            .map(|s| s.api.clone())
    }
}
impl Backend for NativeBackend {
    fn save(
        &self,
        key: &SessionKey,
        update: &MessageUpdate,
    ) -> BoxFuture<'static, Result<MessageLocalState, String>> {
        let store = self.store.clone();
        let key = key.clone();
        let update = update.clone();
        Box::pin(async move {
            tokio::task::spawn_blocking(move || {
                store
                    .update_message(&key, |local| composer::update_message(local, &update))
                    .map_err(|e| e.to_string())
            })
            .await
            .map_err(|e| e.to_string())?
        })
    }
    fn available(&self, attachment: &Attachment) -> BoxFuture<'static, bool> {
        let attachment = attachment.clone();
        Box::pin(async move {
            tokio::task::spawn_blocking(move || available(&attachment))
                .await
                .unwrap_or(false)
        })
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
        origin: &str,
        path: String,
        body: Option<serde_json::Value>,
    ) -> BoxFuture<'static, Result<serde_json::Value, Failure>> {
        let api = self.api(origin);
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
        origin: &str,
        attachment: Attachment,
        progress: mpsc::UnboundedSender<i64>,
    ) -> BoxFuture<'static, Result<String, Failure>> {
        let api = self.api(origin);
        Box::pin(async move {
            let (file, attachment) = tokio::task::spawn_blocking(move || {
                open_attachment(&attachment).map(|file| (file, attachment))
            })
            .await
            .map_err(|_| Failure::default())?
            .map_err(|_| Failure::default())?;
            let file = tokio::fs::File::from_std(file);
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
    fn release_unused(&self, _: &[Attachment], _: &State) {
        // No desktop a URI aponta para o arquivo do usuário: nunca apague nem altere esse arquivo.
    }
    fn unauthorized(&self, origin: &str) {
        let _ = self
            .commands
            .send(state::Event::Unauthorized(origin.into()));
    }
    fn session_changed(&self, key: &SessionKey) {
        let same = self
            .snapshot
            .read()
            .ok()
            .and_then(|snapshot| snapshot.clone())
            .is_some_and(|snapshot| {
                snapshot
                    .selected
                    .as_ref()
                    .is_some_and(|item| &item.key == key)
            });
        if same {
            let _ = self.commands.send(state::Event::Close);
        }
    }
}
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
    Flush(tokio::sync::oneshot::Sender<Result<(), String>>),
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
    let _ = COMPOSER_ACTIONS.set(actions.clone());
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
    let mut app_visible = global.get_app_visible();
    let mut snapshots = context.snapshots.subscribe();
    let context = context.clone();
    context.runtime.clone().spawn(async move {
        let store = match tokio::task::spawn_blocking(LocalStateStore::new).await { Ok(Ok(store)) => store, _ => { update_ui(&weak, |w| w.global::<ComposerState>().set_error(strings::STORAGE_ERROR.into())); return; } };
        let snapshot = Arc::new(RwLock::new(None));
        let backend = Arc::new(NativeBackend { store: store.clone(), snapshot: snapshot.clone(), commands: context.commands.clone() });
        let mut controller = MessageController::start(backend.clone());
        let mut selected: Option<SessionItem> = None;
        let mut command_dismiss = None::<tokio::task::JoinHandle<()>>;
        let mut last_command = None;
        let mut picker = None::<tokio::task::JoinHandle<()>>;
        if app_visible { let _ = controller.events.send(Event::Start); }
        loop {
            tokio::select! {
                result = snapshots.recv() => match result {
                    Ok(value) => {
                        let next = value.selected.clone().filter(|s| s.agent_id.is_some() && !s.sub);
                        if next.as_ref().map(|s| &s.key) != selected.as_ref().map(|s| &s.key) {
                            let _ = controller.events.send(Event::Stop);
                            let _ = controller.events.send(Event::Close);
                            if let Some(job) = picker.take() { job.abort(); }
                            update_ui(&weak, |window| {
                                let global = window.global::<ComposerState>();
                                global.set_picker_visible(false);
                                global.set_shake_confirm(false);
                            });
                            if let Some(item) = &next {
                                if app_visible { let _ = controller.events.send(Event::Start); }
                                let _ = controller.events.send(Event::Open(item.key.clone()));
                                let store = store.clone();
                                let key = item.key.clone();
                                match tokio::task::spawn_blocking(move || store.session_deliver_as(&key)).await {
                                    Ok(Ok(mode)) => { let _ = controller.events.send(Event::SetDeliverAs(mode)); }
                                    _ => { let _ = controller.events.send(Event::AttachmentFailed(item.key.clone())); }
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
                    let action = match action {
                        Action::Flush(reply) => {
                            let _ = reply.send(controller.flush_handle().flush().await);
                            continue;
                        }
                        action => action,
                    };
                    if let Action::Visibility(visible) = &action {
                        app_visible = *visible;
                        if selected.is_none() {
                            let _ = controller.events.send(if *visible { Event::Start } else { Event::Stop });
                        }
                    }
                    let Some(item) = &selected else { continue; };
                    let state = controller.states.borrow().clone();
                    let agent_id = item.agent_id.clone().unwrap_or_default();
                    let eligible = backend.eligible(&item.key, &agent_id);
                    let event = match action {
                        Action::Edit(text) => Some(Event::Edit { text, attachments: state.composer.draft.attachments.clone() }),
                        Action::Flush(_) => unreachable!(),
                        Action::Send(confirmed) => Some(Event::Send { agent_id, confirmed, eligible }),
                        Action::Stop(confirmed) => Some(Event::Interrupt { agent_id, confirmed, eligible }),
                        Action::Command(kind,value) => Some(Event::Command { agent_id, kind, value, eligible }),
                        Action::Remove(uri) => state.composer.draft.attachments.iter().find(|a| a.uri == uri).cloned().map(Event::Remove),
                        Action::Mode(index) => {
                            let mode = ["aside", "followUp", "steer"].get(index as usize).copied();
                            if let Some(mode) = mode {
                                let store = store.clone();
                                let key = item.key.clone();
                                Some(match tokio::task::spawn_blocking(move || store.set_session_deliver_as(&key, mode)).await {
                                    Ok(Ok(())) => Event::SetDeliverAs(mode.into()),
                                    _ => Event::SaveFailed { token: 0, key: item.key.clone(), purpose: composer::SavePurpose::Edit },
                                })
                            } else { None }
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
                                        let selected = select_attachments_async(uris, before.clone()).await;
                                        let event = match selected { Ok(selected) => Event::AttachmentsSelected { key, before, selected }, Err(_) => Event::AttachmentFailed(key) };
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
                            let selected = match uris {
                                Ok(uris) => select_attachments_async(uris, state.composer.draft.attachments.clone()).await,
                                Err(_) => Err("URI inválida".into()),
                            };
                            match selected {
                                Ok(selected) => {
                                    update_ui(&weak, |window| window.global::<ComposerState>().set_picker_visible(false));
                                    Some(Event::AttachmentsSelected { key: item.key.clone(), before: state.composer.draft.attachments.clone(), selected })
                                }
                                Err(_) => Some(Event::AttachmentFailed(item.key.clone())),
                            }
                        }
                        Action::PickerDismiss => { update_ui(&weak, |window| window.global::<ComposerState>().set_picker_visible(false)); None }
                        Action::Visibility(visible) => Some(if visible { Event::Start } else { Event::Stop }),
                    };
                    if matches!(&event, Some(Event::Send { .. })) { update_ui(&weak, |window| window.global::<ComposerState>().invoke_scroll_to_end()); }
                    if let Some(event) = event { let _ = controller.events.send(event); }
                }
            }
            if receiver.is_empty() && weak.upgrade_in_event_loop(|_| {}).is_err() { break; }
        }
        let _ = controller.events.send(Event::Stop);
        let _ = controller.flush_handle().flush().await;
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
            // kdialog separa caminhos por newline sem escape; o portal/fallback evita ambiguidade.
            let output = tokio::process::Command::new("zenity")
                .args(["--file-selection", "--multiple", "--separator=\u{1f}"])
                .kill_on_drop(true)
                .output()
                .await;
            match output {
                Ok(output) if output.status.success() => {
                    return parse_picker_paths(output.stdout).map(Some);
                }
                Ok(output) if output.status.code() == Some(1) => return Ok(None),
                _ => {}
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

#[cfg(any(target_os = "linux", test))]
fn parse_picker_paths(output: Vec<u8>) -> Result<Vec<std::path::PathBuf>, ()> {
    let text = String::from_utf8(output).map_err(|_| ())?;
    Ok(text
        .strip_suffix('\n')
        .unwrap_or(&text)
        .split('\u{1f}')
        .filter(|path| !path.is_empty())
        .map(std::path::PathBuf::from)
        .collect())
}

async fn select_attachments_async(
    uris: Vec<String>,
    existing: Vec<Attachment>,
) -> Result<Vec<Attachment>, String> {
    tokio::task::spawn_blocking(move || select_attachments(&uris, &existing))
        .await
        .map_err(|e| e.to_string())?
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
        let (_file, metadata) = open_regular(&path).map_err(|e| e.to_string())?;
        let size = i64::try_from(metadata.len()).map_err(|e| e.to_string())?;
        attachments::validate_attachment_size(Some(size)).map_err(str::to_owned)?;
        let identity = file_identity(&metadata).map_err(|e| e.to_string())?;
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
            identity: Some(identity),
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
        let key = composer
            .key
            .as_ref()
            .and_then(|key| key.serialize().ok())
            .unwrap_or_default();
        let revision = composer.draft.revision as i32;
        // Uma resposta de disco anterior não pode reposicionar o cursor nem apagar teclas recém-digitadas.
        if global.get_draft_key().as_str() != key
            || revision_is_current(revision, global.get_input_revision())
            || global.get_text().as_str().encode_utf16().count() > 20000
        {
            global.set_draft_key(key.into());
            global.set_input_revision(revision);
            if global.get_text().as_str() != composer.draft.text {
                global.set_text(composer.draft.text.clone().into());
            }
        }
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
                .or_else(|| {
                    composer.control_error.as_deref().map(|error| match error {
                        "confirm" | "unknown" => "",
                        "failed" => strings::STOP_FAILED,
                        other => other,
                    })
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
    #[test]
    fn attachment_upload_rejects_same_size_replacement() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("selected.txt");
        std::fs::write(&path, b"original").unwrap();
        let uri = url::Url::from_file_path(&path).unwrap().to_string();
        let selected = select_attachments(&[uri], &[]).unwrap().remove(0);
        let replacement = root.path().join("replacement.txt");
        std::fs::write(&replacement, b"replaced").unwrap();
        std::fs::rename(replacement, &path).unwrap();
        assert!(open_attachment(&selected).is_err());
    }

    #[test]
    fn attachment_upload_rejects_modified_content_and_missing_identity() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("selected.txt");
        std::fs::write(&path, b"original").unwrap();
        let uri = url::Url::from_file_path(&path).unwrap().to_string();
        let mut selected = select_attachments(&[uri], &[]).unwrap().remove(0);
        let file = std::fs::OpenOptions::new().write(true).open(&path).unwrap();
        file.set_modified(std::time::UNIX_EPOCH + std::time::Duration::from_secs(1))
            .unwrap();
        assert!(open_attachment(&selected).is_err());
        selected.identity = None;
        assert!(open_attachment(&selected).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn attachment_selection_and_upload_reject_symlink_target() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("selected.txt");
        std::fs::write(&path, b"original").unwrap();
        let uri = url::Url::from_file_path(&path).unwrap().to_string();
        let selected = select_attachments(std::slice::from_ref(&uri), &[])
            .unwrap()
            .remove(0);
        let other = root.path().join("other.txt");
        std::fs::write(&other, b"replaced").unwrap();
        std::fs::remove_file(&path).unwrap();
        std::os::unix::fs::symlink(other, &path).unwrap();
        assert!(select_attachments(&[uri], &[]).is_err());
        assert!(open_attachment(&selected).is_err());
    }

    #[test]
    fn attachment_upload_keeps_validated_file_descriptor_after_path_swap() {
        use std::io::Read;
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("selected.txt");
        std::fs::write(&path, b"original").unwrap();
        let uri = url::Url::from_file_path(&path).unwrap().to_string();
        let selected = select_attachments(&[uri], &[]).unwrap().remove(0);
        let mut file = open_attachment(&selected).unwrap();
        let replacement = root.path().join("replacement.txt");
        std::fs::write(&replacement, b"replaced").unwrap();
        std::fs::rename(replacement, path).unwrap();
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).unwrap();
        assert_eq!(bytes, b"original");
    }

    #[test]
    fn picker_separator_preserves_newlines_and_trailing_whitespace() {
        let paths =
            parse_picker_paths(b"/tmp/line\nname \x1f/tmp/ends-with-newline\n\n".to_vec()).unwrap();
        assert_eq!(
            paths,
            vec![
                std::path::PathBuf::from("/tmp/line\nname "),
                std::path::PathBuf::from("/tmp/ends-with-newline\n"),
            ]
        );
    }
}

fn revision_is_current(remote: i32, local: i32) -> bool {
    remote.wrapping_sub(local) >= 0
}

#[test]
fn stale_draft_updates_do_not_overwrite_newer_keystrokes() {
    assert!(!revision_is_current(9, 10));
    assert!(revision_is_current(10, 10));
    assert!(revision_is_current(11, 10));
    assert!(revision_is_current(i32::MIN, i32::MAX));
    assert!(!revision_is_current(i32::MAX, i32::MIN));
}
