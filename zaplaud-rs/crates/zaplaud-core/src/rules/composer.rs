use super::{kotlin_blank, kotlin_hash, utf16_len};
use crate::model::*;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

pub fn supports_session_control(provider: &str) -> bool {
    matches!(provider, "omp" | "zeroclaw")
}
pub fn delivery_modes(provider: &str) -> &'static [&'static str] {
    match provider {
        "omp" => &["aside", "followUp", "steer"],
        "zeroclaw" => &["followUp", "steer"],
        _ => &[],
    }
}
pub fn reliable_context(provider: &str) -> bool {
    matches!(provider, "omp" | "claude" | "codex")
}
pub fn deliver_modes_available(session: &SessionItem) -> bool {
    supports_session_control(&session.key.provider) && session.agent_id.is_some() && !session.sub
}
pub fn deliver_as<'a>(provider: &str, mode: &'a str) -> Option<&'a str> {
    match provider {
        "omp" => Some(mode),
        "zeroclaw" => Some(if mode == "steer" { "steer" } else { "followUp" }),
        _ => None,
    }
}
pub fn new_session_target<'a>(
    sessions: &'a [SessionItem],
    previous: &SessionKey,
    agent_id: &str,
) -> Option<&'a SessionItem> {
    sessions.iter().find(|item| {
        item.key.origin == previous.origin
            && item.key.provider == previous.provider
            && item.key.account == previous.account
            && item.agent_id.as_deref() == Some(agent_id)
            && item.key.session_id != previous.session_id
    })
}
pub fn session_controls_available(selected: &SessionItem, state: &ReadingState) -> bool {
    deliver_modes_available(selected)
        && can_send(
            state,
            true,
            true,
            &selected.key,
            selected.agent_id.as_deref().unwrap_or(""),
        )
}
pub fn stop_visible(status: Option<&str>) -> bool {
    matches!(status, Some("working" | "busy" | "shell"))
}
pub fn shake_requires_confirmation(from_context_ring: bool) -> bool {
    from_context_ring
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ContextRing {
    pub fraction: f32,
    pub percent: i32,
    pub tone: &'static str,
    pub tokens_k: i64,
    pub window_k: i64,
}
pub fn context_ring(tokens: i64, window: i64) -> Option<ContextRing> {
    if tokens < 0 || window <= 0 {
        return None;
    }
    let fraction = (tokens as f32 / window as f32).clamp(0.0, 1.0);
    let percent = (fraction * 100.0) as i32;
    Some(ContextRing {
        fraction,
        percent,
        tone: if percent >= 80 {
            "error"
        } else if percent >= 50 {
            "warning"
        } else {
            "primary"
        },
        tokens_k: tokens / 1000,
        window_k: window / 1000,
    })
}
pub fn model_brand(model: Option<&str>) -> Option<&'static str> {
    let model = model?.rsplit('/').next()?.to_lowercase();
    if model.starts_with("claude") {
        Some("claude")
    } else if model.starts_with("gpt")
        || model.starts_with("codex")
        || model
            .strip_prefix('o')
            .is_some_and(|suffix| suffix.starts_with(|c: char| c.is_ascii_digit()))
    {
        Some("openai")
    } else if model.starts_with("gemini") {
        Some("gemini")
    } else {
        None
    }
}
#[derive(Clone, Debug, PartialEq)]
pub struct AvatarSelection {
    pub name: String,
    pub initial: String,
    pub background: &'static str,
    pub brand: Option<&'static str>,
    pub badge_size: f32,
    pub symbol_size: f32,
}
pub fn avatar_selection(session: &SessionItem, size: f32) -> AvatarSelection {
    const COLORS: [&str; 5] = ["#D3EEE3", "#DCE7F7", "#F7E4D6", "#EAE0F4", "#F5EABF"];
    let name = session.character_name.as_deref().unwrap_or(&session.title);
    let initial = name
        .encode_utf16()
        .next()
        .map(|unit| String::from_utf16_lossy(&[unit]).to_uppercase())
        .unwrap_or_default();
    let brand = (size >= 32.0)
        .then(|| model_brand(session.model.as_deref()))
        .flatten();
    AvatarSelection {
        name: name.into(),
        initial,
        background: COLORS[(kotlin_hash(name) & i32::MAX) as usize % 5],
        brand,
        badge_size: size * 0.42,
        symbol_size: size * 0.42 * 0.7,
    }
}
pub fn split_role(value: &str) -> (&str, Option<&str>) {
    match value.rsplit_once(':') {
        Some((model, level)) if !level.contains('/') && THINKING_LEVELS.contains(&level) => {
            (model, Some(level))
        }
        _ => (value, None),
    }
}
pub fn join_role(model: &str, level: Option<&str>) -> String {
    level.map_or_else(|| model.to_owned(), |level| format!("{model}:{level}"))
}
pub fn snapshot_models(snapshot: &str) -> Result<Vec<String>, serde_json::Error> {
    let snapshot = Value::Object(serde_json::from_str::<serde_json::Map<String, Value>>(
        snapshot,
    )?);
    let mut models: Vec<_> = crate::model::objects(&snapshot, "agents")
        .flat_map(|agent| {
            agent
                .get("models")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
                .map(str::to_owned)
        })
        .collect();
    models.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
    models.dedup();
    Ok(models)
}
fn undated(model: &str) -> &str {
    if model.len() >= 9 {
        let offset = model.len() - 9;
        if model.is_char_boundary(offset)
            && model.as_bytes()[offset] == b'-'
            && model.as_bytes()[offset + 1..]
                .iter()
                .all(u8::is_ascii_digit)
        {
            return &model[..offset];
        }
    }
    model
}
fn compare_models(
    a: &str,
    b: &str,
    x: &[(String, Option<i64>)],
    y: &[(String, Option<i64>)],
) -> std::cmp::Ordering {
    for ((x, p), (y, q)) in x.iter().zip(y) {
        let order = match (p, q) {
            (Some(p), Some(q)) => q.cmp(p),
            _ => x.cmp(y),
        };
        if !order.is_eq() {
            return order;
        }
    }
    if x.len() == y.len() {
        return a.encode_utf16().cmp(b.encode_utf16());
    }
    let next = if x.len() > y.len() {
        &x[y.len()]
    } else {
        &y[x.len()]
    };
    if next.1.is_some() {
        y.len().cmp(&x.len())
    } else {
        x.len().cmp(&y.len())
    }
}
pub fn role_model_groups(models: &[String], keep: Option<&str>) -> Vec<(String, Vec<String>)> {
    let mut seen = HashSet::new();
    let all: Vec<_> = models
        .iter()
        .map(String::as_str)
        .chain(keep)
        .filter(|m| m.contains('/') && seen.insert(*m))
        .collect();
    let mut groups: Vec<(String, Vec<String>)> = Vec::new();
    for model in &all {
        if Some(*model) != keep
            && (model.ends_with(":batch")
                || (undated(model) != *model && seen.contains(undated(model))))
        {
            continue;
        }
        let provider = model.split_once('/').map_or("", |(provider, _)| provider);
        if let Some((_, list)) = groups.iter_mut().find(|(p, _)| p == provider) {
            list.push((*model).into());
        } else {
            groups.push((provider.into(), vec![(*model).into()]));
        }
    }
    groups.sort_by_cached_key(|(p, _)| p.to_lowercase().encode_utf16().collect::<Vec<_>>());
    for (_, list) in &mut groups {
        let mut keyed: Vec<_> = list
            .drain(..)
            .map(|model| {
                let key = undated(
                    model
                        .split_once('/')
                        .map_or(model.as_str(), |(_, name)| name),
                )
                .to_owned();
                let tokens: Vec<_> = key
                    .to_lowercase()
                    .split(|c: char| !c.is_ascii_alphanumeric())
                    .filter(|s| !s.is_empty())
                    .map(|s| (s.to_owned(), s.parse::<i64>().ok()))
                    .collect();
                (model, key, tokens)
            })
            .collect();
        keyed.sort_by(|a, b| {
            compare_models(&a.1, &b.1, &a.2, &b.2)
                .then_with(|| a.0.encode_utf16().cmp(b.0.encode_utf16()))
        });
        list.extend(keyed.into_iter().map(|(model, _, _)| model));
    }
    groups
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Failure {
    pub status: Option<u16>,
    pub session_changed: bool,
    pub attachments_expired: bool,
    pub cancelled: bool,
}
impl Failure {
    fn definite(&self) -> bool {
        self.status.is_some_and(|s| (400..500).contains(&s))
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MessageUpdate {
    Draft(MessageDraft),
    Reconcile(Option<MessageDraft>),
    MarkSend(MessageDraft),
    Operation(Option<MessageOperation>),
    Control(Option<MessageOperation>),
    Accepted {
        operation: MessageOperation,
        sent: MessageDraft,
        memory: Option<MessageDraft>,
    },
    Polled {
        control: bool,
        id: String,
        operation: MessageOperation,
    },
}
/// Aplique dentro da transação serializada de MessageStore, nunca sobre uma leitura anterior.
pub fn update_message(mut local: MessageLocalState, update: &MessageUpdate) -> MessageLocalState {
    match update {
        MessageUpdate::Draft(draft) => {
            if draft.revision > local.draft.revision {
                local.draft = draft.clone();
            }
        }
        MessageUpdate::Reconcile(memory) => {
            if let Some(draft) = memory
                .as_ref()
                .filter(|draft| draft.revision > local.draft.revision)
            {
                local.draft = draft.clone();
            }
            if local
                .operation
                .as_ref()
                .is_some_and(|op| op.id.is_some() && op.revision == local.draft.revision)
            {
                local.draft.text.clear();
                local.draft.attachments.clear();
                local.draft.revision = local.draft.revision.wrapping_add(1);
            }
        }
        MessageUpdate::MarkSend(draft) => {
            if local.draft.revision < draft.revision {
                local.draft = draft.clone();
            }
            local.operation = Some(MessageOperation {
                revision: draft.revision,
                ..MessageOperation::default()
            });
        }
        MessageUpdate::Operation(op) => local.operation = op.clone(),
        MessageUpdate::Control(op) => local.control = op.clone(),
        MessageUpdate::Accepted {
            operation,
            sent,
            memory,
        } => {
            local.operation = Some(operation.clone());
            if let Some(draft) = memory
                .as_ref()
                .filter(|draft| draft.revision > local.draft.revision)
            {
                local.draft = draft.clone();
            }
            if local.draft.revision == sent.revision {
                local.draft.text.clear();
                local.draft.attachments.clear();
                local.draft.revision = local.draft.revision.wrapping_add(1);
            }
        }
        MessageUpdate::Polled {
            control,
            id,
            operation,
        } => {
            let target = if *control {
                &mut local.control
            } else {
                &mut local.operation
            };
            if target.as_ref().and_then(|op| op.id.as_ref()) == Some(id) {
                *target = Some(operation.clone());
            }
        }
    }
    local
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SavePurpose {
    Open,
    Edit,
    MarkSend,
    AcceptSend,
    MarkControl,
    AcceptControl,
    Poll,
    Restore,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Effect {
    CancelPolls,
    CancelForeground,
    Save {
        token: u64,
        key: SessionKey,
        update: MessageUpdate,
        purpose: SavePurpose,
    },
    CheckRecipient {
        token: u64,
        key: SessionKey,
        agent_id: String,
    },
    UploadAttachment {
        token: u64,
        attachment: Attachment,
        index: usize,
        total: usize,
    },
    SendMessage {
        token: u64,
        body: Value,
    },
    Interrupt {
        token: u64,
        body: Value,
    },
    OmpControl {
        token: u64,
        body: Value,
    },
    PollMessage {
        key: SessionKey,
        id: String,
        control: bool,
        delay_ms: u64,
    },
    PollCommand {
        token: u64,
        id: String,
        delay_ms: u64,
    },
    ReleaseUnused(Vec<Attachment>),
    Unauthorized,
    SessionChanged,
}
#[derive(Clone, Debug, PartialEq, Eq)]
enum SendStage {
    CheckingUpload,
    Uploading,
    Marking,
    CheckingDispatch,
    Dispatched,
    Accepting,
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct PendingSend {
    token: u64,
    key: SessionKey,
    agent_id: String,
    draft: MessageDraft,
    previous: Option<MessageOperation>,
    ids: Vec<String>,
    last_progress: i64,
    mode: String,
    stage: SendStage,
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct PendingControl {
    token: u64,
    key: SessionKey,
    agent_id: String,
    new_session: bool,
    previous: Option<MessageOperation>,
    dispatched: bool,
    accepting: bool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct PendingCommand {
    token: u64,
    key: SessionKey,
    kind: String,
    id: Option<String>,
    polls: u8,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct State {
    pub composer: ComposerState,
    pub visible: bool,
    pub deliver_as: String,
    pub drafts: HashMap<SessionKey, MessageDraft>,
    pub transient_uris: HashSet<String>,
    deferred_selections: Vec<(SessionKey, Vec<Attachment>, Vec<Attachment>)>,
    generation: u64,
    sequence: u64,
    sends: HashMap<u64, PendingSend>,
    controls: HashMap<u64, PendingControl>,
    commands: HashMap<u64, PendingCommand>,
}
impl Default for State {
    fn default() -> Self {
        Self {
            composer: ComposerState::default(),
            visible: false,
            deliver_as: "aside".into(),
            drafts: HashMap::new(),
            transient_uris: HashSet::new(),
            deferred_selections: Vec::new(),
            generation: 0,
            sequence: 0,
            sends: HashMap::new(),
            controls: HashMap::new(),
            commands: HashMap::new(),
        }
    }
}
impl State {
    pub fn active_attachment(&self, uri: &str) -> bool {
        self.sends
            .values()
            .any(|send| send.draft.attachments.iter().any(|item| item.uri == uri))
    }
    fn token(&mut self) -> u64 {
        self.sequence = self.sequence.wrapping_add(1);
        self.sequence
    }
    fn local_draft(&mut self, key: &SessionKey, draft: MessageDraft) {
        if self
            .drafts
            .get(key)
            .is_none_or(|current| current.revision <= draft.revision)
        {
            self.drafts.insert(key.clone(), draft.clone());
        }
        if self.composer.key.as_ref() == Some(key) && self.composer.draft.revision <= draft.revision
        {
            self.composer.draft = draft;
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Event {
    Start,
    Stop,
    Close,
    Open(SessionKey),
    SetDeliverAs(String),
    Saved {
        token: u64,
        key: SessionKey,
        purpose: SavePurpose,
        local: MessageLocalState,
        unavailable: HashSet<String>,
    },
    SaveFailed {
        token: u64,
        key: SessionKey,
        purpose: SavePurpose,
    },
    Edit {
        text: String,
        attachments: Vec<Attachment>,
    },
    AttachmentsSelected {
        key: SessionKey,
        before: Vec<Attachment>,
        selected: Vec<Attachment>,
    },
    AttachmentFailed(SessionKey),
    Remove(Attachment),
    Send {
        agent_id: String,
        confirmed: bool,
        eligible: bool,
    },
    RecipientChecked {
        token: u64,
        eligible: bool,
    },
    Uploaded {
        token: u64,
        id: String,
    },
    UploadProgress {
        token: u64,
        bytes: i64,
    },
    SendAccepted {
        token: u64,
        id: String,
        status: String,
    },
    SendFailed {
        token: u64,
        failure: Failure,
    },
    Interrupt {
        agent_id: String,
        confirmed: bool,
        eligible: bool,
    },
    NewSession {
        agent_id: String,
        confirmed: bool,
        eligible: bool,
    },
    ControlAccepted {
        token: u64,
        id: String,
        status: String,
    },
    ControlFailed {
        token: u64,
        failure: Failure,
    },
    PollResult {
        key: SessionKey,
        id: String,
        control: bool,
        status: Option<String>,
        error: Option<String>,
        failure: Option<Failure>,
    },
    Command {
        agent_id: String,
        kind: String,
        value: String,
        eligible: bool,
    },
    CommandResult {
        token: u64,
        id: Option<String>,
        status: Option<String>,
        failure: Option<Failure>,
    },
    DismissConfirmation,
    DismissControlConfirmation,
    DismissCommandResult,
}
fn callbacks(failure: &Failure, effects: &mut Vec<Effect>) {
    if failure.session_changed {
        effects.push(Effect::SessionChanged);
    } else if failure.status == Some(401) {
        effects.push(Effect::Unauthorized);
    }
}
fn poll(effects: &mut Vec<Effect>, key: &SessionKey, op: Option<&MessageOperation>, control: bool) {
    if let Some(op) = op.filter(|op| !matches!(op.status.as_str(), "delivered" | "failed"))
        && let Some(id) = &op.id
    {
        effects.push(Effect::PollMessage {
            key: key.clone(),
            id: id.clone(),
            control,
            delay_ms: 2000,
        });
    }
}
fn finish_send(state: &mut State, token: u64, effects: &mut Vec<Effect>) {
    if let Some(send) = state.sends.remove(&token) {
        if state.composer.key.as_ref() == Some(&send.key) {
            state.composer.sending = false;
            state.composer.progress = None;
        }
        effects.push(Effect::ReleaseUnused(send.draft.attachments));
    }
}
fn fail_send(state: &mut State, token: u64, failure: Failure, effects: &mut Vec<Effect>) {
    if let Some(send) = state.sends.get(&token) {
        let dispatched = matches!(send.stage, SendStage::Dispatched | SendStage::Accepting);
        let unknown = dispatched && !failure.definite();
        let op = if unknown {
            Some(MessageOperation {
                revision: send.draft.revision,
                ..MessageOperation::default()
            })
        } else {
            send.previous.clone()
        };
        if matches!(
            send.stage,
            SendStage::Marking
                | SendStage::CheckingDispatch
                | SendStage::Dispatched
                | SendStage::Accepting
        ) && !unknown
        {
            effects.push(Effect::Save {
                token,
                key: send.key.clone(),
                update: MessageUpdate::Operation(op.clone()),
                purpose: SavePurpose::Restore,
            });
        }
        if state.composer.key.as_ref() == Some(&send.key) {
            state.composer.operation = op;
            state.composer.error = Some(
                if unknown {
                    "unknown"
                } else if matches!(send.stage, SendStage::CheckingUpload | SendStage::Uploading) {
                    "attachment"
                } else if failure.attachments_expired {
                    "attachments_expired"
                } else {
                    "send"
                }
                .into(),
            );
        }
        callbacks(&failure, effects);
    }
    finish_send(state, token, effects);
}
pub fn reduce(mut state: State, event: Event) -> (State, Vec<Effect>) {
    let mut effects = Vec::new();
    let new_session = matches!(&event, Event::NewSession { .. });
    match event {
        Event::Start => {
            if !state.visible {
                state.visible = true;
                if let Some(key) = state.composer.key.clone() {
                    return reduce(state, Event::Open(key));
                }
            }
        }
        Event::Stop => {
            state.visible = false;
            effects.push(Effect::CancelForeground);
            effects.push(Effect::CancelPolls);
            let tokens: Vec<_> = state
                .sends
                .values()
                .filter(|send| send.stage != SendStage::Accepting)
                .map(|send| send.token)
                .collect();
            for token in tokens {
                fail_send(
                    &mut state,
                    token,
                    Failure {
                        cancelled: true,
                        ..Failure::default()
                    },
                    &mut effects,
                );
            }
            let tokens: Vec<_> = state
                .controls
                .values()
                .filter(|control| !control.accepting)
                .map(|control| control.token)
                .collect();
            for token in tokens {
                let (next, more) = reduce(
                    state,
                    Event::ControlFailed {
                        token,
                        failure: Failure {
                            cancelled: true,
                            ..Failure::default()
                        },
                    },
                );
                state = next;
                effects.extend(more);
            }
            state.commands.clear();
            if let Some(result) = &mut state.composer.command_result
                && result.status == "pending"
            {
                result.status = "unknown".into();
            }
        }
        Event::Close => {
            state.generation = state.token();
            state.composer = ComposerState::default();
            effects.push(Effect::CancelPolls);
        }
        Event::Open(key) => {
            state.generation = state.token();
            state.composer = ComposerState {
                key: Some(key.clone()),
                draft: state.drafts.get(&key).cloned().unwrap_or_default(),
                loading: true,
                sending: state.sends.values().any(|send| send.key == key),
                ..ComposerState::default()
            };
            effects.push(Effect::CancelPolls);
            effects.push(Effect::Save {
                token: state.generation,
                key: key.clone(),
                update: MessageUpdate::Reconcile(state.drafts.get(&key).cloned()),
                purpose: SavePurpose::Open,
            });
        }
        Event::SetDeliverAs(mode) => state.deliver_as = mode,
        Event::Saved {
            token,
            key,
            purpose,
            local,
            unavailable,
        } => match purpose {
            SavePurpose::Open
                if token == state.generation && state.composer.key.as_ref() == Some(&key) =>
            {
                state.local_draft(&key, local.draft);
                state.composer.operation = local.operation;
                state.composer.control = local.control;
                state.composer.loading = false;
                state.composer.unavailable = unavailable;
                if state.visible {
                    poll(&mut effects, &key, state.composer.operation.as_ref(), false);
                    poll(&mut effects, &key, state.composer.control.as_ref(), true);
                }
            }
            SavePurpose::MarkSend => {
                if let Some(send) = state.sends.get_mut(&token) {
                    send.stage = SendStage::CheckingDispatch;
                    effects.push(Effect::CheckRecipient {
                        token,
                        key: send.key.clone(),
                        agent_id: send.agent_id.clone(),
                    });
                }
            }
            SavePurpose::AcceptSend => {
                state.local_draft(&key, local.draft);
                if state.composer.key.as_ref() == Some(&key) {
                    state.composer.operation = local.operation.clone();
                    if state.visible {
                        poll(&mut effects, &key, local.operation.as_ref(), false);
                    }
                }
                finish_send(&mut state, token, &mut effects);
            }
            SavePurpose::MarkControl => {
                if let Some(control) = state.controls.get(&token) {
                    effects.push(Effect::CheckRecipient {
                        token,
                        key: control.key.clone(),
                        agent_id: control.agent_id.clone(),
                    });
                }
            }
            SavePurpose::AcceptControl => {
                state.controls.remove(&token);
                if state.composer.key.as_ref() == Some(&key) {
                    state.composer.control = local.control.clone();
                    if state.visible {
                        poll(&mut effects, &key, local.control.as_ref(), true);
                    }
                }
            }
            _ => {}
        },
        Event::SaveFailed {
            token,
            key,
            purpose,
        } => match purpose {
            SavePurpose::MarkSend | SavePurpose::AcceptSend => {
                fail_send(&mut state, token, Failure::default(), &mut effects)
            }
            SavePurpose::MarkControl | SavePurpose::AcceptControl => {
                let (next, more) = reduce(
                    state,
                    Event::ControlFailed {
                        token,
                        failure: Failure::default(),
                    },
                );
                state = next;
                effects.extend(more);
            }
            SavePurpose::Poll | SavePurpose::Restore => {}
            _ => {
                if state.composer.key.as_ref() == Some(&key)
                    && (purpose != SavePurpose::Open || token == state.generation)
                {
                    state.composer.loading = false;
                    state.composer.error = Some("storage".into());
                }
            }
        },
        Event::Edit { text, attachments } => {
            if !state.composer.loading
                && utf16_len(&text) <= 20_000
                && let Some(key) = state.composer.key.clone()
            {
                let draft = MessageDraft {
                    text,
                    revision: state.composer.draft.revision.wrapping_add(1),
                    attachments,
                };
                state.local_draft(&key, draft.clone());
                state.composer.error = None;
                let token = state.token();
                effects.push(Effect::Save {
                    token,
                    key,
                    update: MessageUpdate::Draft(draft),
                    purpose: SavePurpose::Edit,
                });
            }
        }
        Event::AttachmentsSelected {
            key,
            before,
            selected,
        } => {
            if state.composer.key.as_ref() == Some(&key) && state.composer.loading {
                state.deferred_selections.push((key, before, selected));
                return (state, effects);
            }
            let added: Vec<_> = selected
                .into_iter()
                .filter(|item| !before.iter().any(|old| old.uri == item.uri))
                .collect();
            if state.composer.key.as_ref() == Some(&key)
                && !state.composer.loading
                && state.composer.error.as_deref() != Some("storage")
            {
                let mut current = state.composer.draft.attachments.clone();
                for item in &added {
                    if !current.iter().any(|old| old.uri == item.uri) {
                        current.push(item.clone());
                    }
                }
                if current.len() > MAX_ATTACHMENTS {
                    state.composer.error = Some("attachment".into());
                    effects.push(Effect::ReleaseUnused(added));
                } else {
                    state
                        .transient_uris
                        .extend(added.iter().map(|item| item.uri.clone()));
                    let text = state.composer.draft.text.clone();
                    let (next, more) = reduce(
                        state,
                        Event::Edit {
                            text,
                            attachments: current,
                        },
                    );
                    state = next;
                    effects.extend(more);
                }
            } else {
                effects.push(Effect::ReleaseUnused(added));
            }
        }
        Event::AttachmentFailed(key) => {
            if state.composer.key.as_ref() == Some(&key) {
                state.composer.error = Some("attachment".into());
            }
        }
        Event::Remove(item) => {
            let attachments = state
                .composer
                .draft
                .attachments
                .iter()
                .filter(|old| **old != item)
                .cloned()
                .collect();
            let text = state.composer.draft.text.clone();
            let (next, more) = reduce(state, Event::Edit { text, attachments });
            state = next;
            effects.extend(more);
            state.composer.unavailable.remove(&item.uri);
            effects.push(Effect::ReleaseUnused(vec![item]));
        }
        Event::Send {
            agent_id,
            confirmed,
            eligible,
        } => {
            if let Some(key) = state.composer.key.clone() {
                let draft = &state.composer.draft;
                if state.visible
                    && !state.composer.loading
                    && !state.composer.sending
                    && eligible
                    && !(kotlin_blank(&draft.text) && draft.attachments.is_empty())
                    && utf16_len(&draft.text) <= 20_000
                    && state.composer.unavailable.is_empty()
                {
                    if state
                        .composer
                        .operation
                        .as_ref()
                        .is_some_and(|op| op.status == "unknown")
                        && !confirmed
                    {
                        state.composer.error = Some("confirm".into());
                    } else {
                        let token = state.token();
                        state.sends.insert(
                            token,
                            PendingSend {
                                token,
                                key: key.clone(),
                                agent_id: agent_id.clone(),
                                draft: state.composer.draft.clone(),
                                previous: state.composer.operation.clone(),
                                ids: Vec::new(),
                                last_progress: 0,
                                mode: if state.deliver_as.is_empty() {
                                    "aside".into()
                                } else {
                                    state.deliver_as.clone()
                                },
                                stage: SendStage::CheckingUpload,
                            },
                        );
                        state.composer.sending = true;
                        state.composer.error = None;
                        effects.push(Effect::CheckRecipient {
                            token,
                            key,
                            agent_id,
                        });
                    }
                }
            }
        }
        Event::RecipientChecked { token, eligible } => {
            if let Some(send) = state.sends.get_mut(&token) {
                if !state.visible || !eligible {
                    if send.stage == SendStage::CheckingDispatch
                        || send.stage == SendStage::CheckingUpload
                            && send.ids.len() == send.draft.attachments.len()
                    {
                        if send.stage == SendStage::CheckingDispatch {
                            effects.push(Effect::Save {
                                token,
                                key: send.key.clone(),
                                update: MessageUpdate::Operation(send.previous.clone()),
                                purpose: SavePurpose::Restore,
                            });
                        }
                        finish_send(&mut state, token, &mut effects);
                    } else {
                        fail_send(
                            &mut state,
                            token,
                            Failure {
                                cancelled: true,
                                ..Failure::default()
                            },
                            &mut effects,
                        );
                    }
                } else if send.stage == SendStage::CheckingUpload {
                    if let Some(attachment) = send.draft.attachments.get(send.ids.len()) {
                        send.stage = SendStage::Uploading;
                        send.last_progress = 0;
                        if state.composer.key.as_ref() == Some(&send.key) {
                            state.composer.progress = Some(format!(
                                "{}/{}",
                                send.ids.len() + 1,
                                send.draft.attachments.len()
                            ));
                        }
                        effects.push(Effect::UploadAttachment {
                            token,
                            attachment: attachment.clone(),
                            index: send.ids.len(),
                            total: send.draft.attachments.len(),
                        });
                    } else {
                        send.stage = SendStage::Marking;
                        effects.push(Effect::Save {
                            token,
                            key: send.key.clone(),
                            update: MessageUpdate::MarkSend(send.draft.clone()),
                            purpose: SavePurpose::MarkSend,
                        });
                    }
                } else if send.stage == SendStage::CheckingDispatch {
                    send.stage = SendStage::Dispatched;
                    let mut body = json!({ "agentId": send.agent_id, "text": send.draft.text, "expectedSessionId": send.key.session_id });
                    if let Some(mode) = deliver_as(&send.key.provider, &send.mode) {
                        body["deliverAs"] = json!(mode);
                    }
                    if !send.ids.is_empty() {
                        body["attachments"] = json!(send.ids);
                    }
                    effects.push(Effect::SendMessage { token, body });
                }
            } else if let Some(control) = state.controls.get_mut(&token) {
                if state.visible && eligible {
                    control.dispatched = true;
                    if state.composer.key.as_ref() == Some(&control.key) {
                        state.composer.control = Some(MessageOperation::default());
                        state.composer.control_error = None;
                    }
                    let mut body = json!({ "agentId": control.agent_id, "expectedSessionId": control.key.session_id });
                    if control.new_session {
                        body["command"] = json!("new");
                    } else {
                        body["stop"] = json!(true);
                    }
                    effects.push(Effect::Interrupt { token, body });
                } else {
                    let control = state.controls.remove(&token);
                    if let Some(control) = control {
                        effects.push(Effect::Save {
                            token,
                            key: control.key,
                            update: MessageUpdate::Control(control.previous),
                            purpose: SavePurpose::Restore,
                        });
                    }
                }
            }
        }
        Event::Uploaded { token, id } => {
            if let Some(send) = state.sends.get_mut(&token)
                && send.stage == SendStage::Uploading
            {
                send.ids.push(id);
                send.stage = SendStage::CheckingUpload;
                effects.push(Effect::CheckRecipient {
                    token,
                    key: send.key.clone(),
                    agent_id: send.agent_id.clone(),
                });
            }
        }
        Event::UploadProgress { token, bytes } => {
            if let Some(send) = state.sends.get_mut(&token)
                && send.stage == SendStage::Uploading
                && (bytes.wrapping_sub(send.last_progress) >= 262144
                    || send
                        .draft
                        .attachments
                        .get(send.ids.len())
                        .and_then(|item| item.size)
                        == Some(bytes))
            {
                send.last_progress = bytes;
                if state.composer.key.as_ref() != Some(&send.key) {
                    return (state, effects);
                }
                state.composer.progress = Some(format!(
                    "{}/{} · {bytes} bytes",
                    send.ids.len() + 1,
                    send.draft.attachments.len()
                ));
            }
        }
        Event::SendAccepted { token, id, status } => {
            if let Some(send) = state.sends.get_mut(&token)
                && send.stage == SendStage::Dispatched
            {
                send.stage = SendStage::Accepting;
                let operation = MessageOperation {
                    id: Some(id),
                    status,
                    revision: send.draft.revision,
                    error: None,
                };
                effects.push(Effect::Save {
                    token,
                    key: send.key.clone(),
                    update: MessageUpdate::Accepted {
                        operation,
                        sent: send.draft.clone(),
                        memory: state.drafts.get(&send.key).cloned(),
                    },
                    purpose: SavePurpose::AcceptSend,
                });
            }
        }
        Event::SendFailed { token, failure } => fail_send(&mut state, token, failure, &mut effects),
        Event::Interrupt {
            agent_id,
            confirmed,
            eligible,
        }
        | Event::NewSession {
            agent_id,
            confirmed,
            eligible,
        } => {
            if let Some(key) = state.composer.key.clone()
                && supports_session_control(&key.provider)
                && state.visible
                && !state.composer.loading
                && !state.composer.sending
                && eligible
                && !state.controls.values().any(|control| control.key == key)
                && !state
                    .composer
                    .control
                    .as_ref()
                    .is_some_and(|op| matches!(op.status.as_str(), "queued" | "sent"))
            {
                if state
                    .composer
                    .control
                    .as_ref()
                    .is_some_and(|op| op.status == "unknown")
                    && !confirmed
                {
                    state.composer.control_error = Some("confirm".into());
                } else {
                    let token = state.token();
                    state.controls.insert(
                        token,
                        PendingControl {
                            token,
                            key: key.clone(),
                            agent_id,
                            new_session,
                            previous: state.composer.control.clone(),
                            dispatched: false,
                            accepting: false,
                        },
                    );
                    effects.push(Effect::Save {
                        token,
                        key,
                        update: MessageUpdate::Control(Some(MessageOperation::default())),
                        purpose: SavePurpose::MarkControl,
                    });
                }
            }
        }
        Event::ControlAccepted { token, id, status } => {
            if let Some(control) = state.controls.get_mut(&token) {
                control.accepting = true;
                effects.push(Effect::Save {
                    token,
                    key: control.key.clone(),
                    update: MessageUpdate::Control(Some(MessageOperation {
                        id: Some(id),
                        status,
                        ..MessageOperation::default()
                    })),
                    purpose: SavePurpose::AcceptControl,
                });
            }
        }
        Event::ControlFailed { token, failure } => {
            if let Some(control) = state.controls.remove(&token) {
                let unknown = control.dispatched && !failure.definite();
                let op = if unknown {
                    Some(MessageOperation::default())
                } else {
                    control.previous
                };
                if !unknown {
                    effects.push(Effect::Save {
                        token,
                        key: control.key.clone(),
                        update: MessageUpdate::Control(op.clone()),
                        purpose: SavePurpose::Restore,
                    });
                }
                if state.composer.key.as_ref() == Some(&control.key) {
                    state.composer.control = op;
                    state.composer.control_error =
                        Some(if unknown { "unknown" } else { "failed" }.into());
                }
                callbacks(&failure, &mut effects);
            }
        }
        Event::PollResult {
            key,
            id,
            control,
            status,
            error,
            failure,
        } => {
            if !state.visible || state.composer.key.as_ref() != Some(&key) {
                return (state, effects);
            }
            let target = if control {
                &mut state.composer.control
            } else {
                &mut state.composer.operation
            };
            if let Some(op) = target.as_mut().filter(|op| op.id.as_deref() == Some(&id)) {
                let unauthorized = failure
                    .as_ref()
                    .is_some_and(|failure| failure.status == Some(401));
                let missing = failure
                    .as_ref()
                    .is_some_and(|failure| failure.status == Some(404));
                if unauthorized {
                    effects.push(Effect::Unauthorized);
                } else if missing || status.is_some() && failure.is_none() {
                    op.status = if missing {
                        "unknown".into()
                    } else {
                        status.unwrap_or_default()
                    };
                    if control && !missing {
                        op.error = error.clone();
                        state.composer.control_error = error;
                    }
                    let update = MessageUpdate::Polled {
                        control,
                        id: id.clone(),
                        operation: op.clone(),
                    };
                    effects.push(Effect::Save {
                        token: 0,
                        key: key.clone(),
                        update,
                        purpose: SavePurpose::Poll,
                    });
                }
                if !unauthorized
                    && !matches!(op.status.as_str(), "delivered" | "failed" | "unknown")
                {
                    effects.push(Effect::PollMessage {
                        key,
                        id,
                        control,
                        delay_ms: 2000,
                    });
                }
            }
        }
        Event::Command {
            agent_id,
            kind,
            value,
            eligible,
        } => {
            if let Some(key) = state.composer.key.clone()
                && key.provider == "omp"
                && state.visible
                && !state.composer.loading
                && !state.composer.sending
                && eligible
            {
                let token = state.token();
                let mut body = json!({ "agentId": agent_id, "expectedSessionId": key.session_id });
                let field = match kind.as_str() {
                    "shake" => "command",
                    "model" => "model",
                    "thinking" => "thinking",
                    _ => return (state, effects),
                };
                body[field] = json!(if kind == "shake" { "shake" } else { &value });
                state.composer.command_result = Some(CommandResult {
                    kind: kind.clone(),
                    status: "pending".into(),
                });
                state.commands.insert(
                    token,
                    PendingCommand {
                        token,
                        key,
                        kind,
                        id: None,
                        polls: 0,
                    },
                );
                effects.push(Effect::OmpControl { token, body });
            }
        }
        Event::CommandResult {
            token,
            id,
            status,
            failure,
        } => {
            if let Some(command) = state.commands.get_mut(&token) {
                if let Some(id) = id {
                    command.id = Some(id);
                }
                let mut result = None;
                if let Some(failure) = &failure {
                    callbacks(failure, &mut effects);
                    result = Some(if failure.status == Some(404) && command.polls > 0 {
                        "unknown"
                    } else if failure.definite() {
                        "failed"
                    } else {
                        "unknown"
                    });
                } else if status.as_deref() == Some("delivered") {
                    result = Some("sent");
                } else if status.as_deref() == Some("failed") {
                    result = Some("failed");
                } else if status.as_deref() == Some("unknown") && command.polls > 0
                    || command.polls >= 15
                    || command.id.is_none()
                    || !state.visible
                    || state.composer.key.as_ref() != Some(&command.key)
                {
                    result = Some("unknown");
                }
                if let Some(result) = result {
                    if state.composer.key.as_ref() == Some(&command.key) {
                        state.composer.command_result = Some(CommandResult {
                            kind: command.kind.clone(),
                            status: result.into(),
                        });
                    }
                    state.commands.remove(&token);
                } else if let Some(id) = &command.id {
                    command.polls += 1;
                    effects.push(Effect::PollCommand {
                        token,
                        id: id.clone(),
                        delay_ms: 2000,
                    });
                }
            }
        }
        Event::DismissConfirmation => state.composer.error = None,
        Event::DismissControlConfirmation => state.composer.control_error = None,
        Event::DismissCommandResult => state.composer.command_result = None,
    }
    let pending = std::mem::take(&mut state.deferred_selections);
    for (key, before, selected) in pending {
        if state.composer.key.as_ref() == Some(&key) && state.composer.loading {
            state.deferred_selections.push((key, before, selected));
        } else {
            let (next, more) = reduce(
                state,
                Event::AttachmentsSelected {
                    key,
                    before,
                    selected,
                },
            );
            state = next;
            effects.extend(more);
        }
    }
    (state, effects)
}

pub fn actions_available(
    state: &ReadingState,
    login_required: bool,
    selected_permission: bool,
) -> bool {
    !login_required
        && state.connection == "connected"
        && (state.terminal_allowed || selected_permission)
}

pub fn can_send(
    state: &ReadingState,
    active: bool,
    access_ready: bool,
    key: &SessionKey,
    agent_id: &str,
) -> bool {
    active
        && access_ready
        && state.connection == "connected"
        && state.messages_allowed
        && state.message_guard
        && state
            .selected
            .as_ref()
            .is_some_and(|selected| selected.key == *key)
        && state.sessions.iter().any(|item| {
            item.key == *key && item.agent_id.as_deref() == Some(agent_id) && item.can_message
        })
}

pub fn snapshot_matches_session(
    snapshot: &str,
    key: &SessionKey,
    agent_id: &str,
) -> Result<bool, serde_json::Error> {
    let root: Value =
        serde_json::from_str::<serde_json::Map<String, Value>>(snapshot).map(Value::Object)?;
    Ok(crate::model::objects(&root, "agents").any(|agent| {
        crate::model::string(agent, "id").as_deref() == Some(agent_id)
            && crate::model::string(agent, "kind").as_deref() == Some("main")
            && crate::model::string(agent, "account").as_deref() == Some(&key.account)
            && crate::model::string(agent, "provider")
                .as_deref()
                .unwrap_or("claude")
                == key.provider
            && crate::model::string(agent, "sessionId").as_deref() == Some(&key.session_id)
    }))
}

pub fn init_keeps_session(
    snapshot_matches: bool,
    sessions: &[SessionItem],
    item: &SessionItem,
) -> bool {
    snapshot_matches
        && !sessions
            .iter()
            .any(|current| current.agent_id == item.agent_id && current.key != item.key)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CastAvailability {
    pub active_job: bool,
    pub resume: bool,
    pub dismiss: bool,
    pub free: Vec<CastChoice>,
    pub resume_choices: Vec<CastChoice>,
}
pub fn can_resume_cast(selected: &SessionItem, runtime: Option<&str>) -> bool {
    let id = selected.key.session_id.as_bytes();
    selected.agent_id.is_none()
        && supports_session_control(&selected.key.provider)
        && runtime == Some(selected.key.provider.as_str())
        && selected
            .project
            .as_deref()
            .is_some_and(|p| p.starts_with('/'))
        && (8..=200).contains(&id.len())
        && id.first().is_some_and(u8::is_ascii_hexdigit)
        && id.iter().all(|b| b.is_ascii_hexdigit() || *b == b'-')
}
pub fn cast_availability(
    selected: Option<&SessionItem>,
    characters: &[CastChoice],
    open_cast: &HashSet<String>,
    jobs: &HashMap<String, String>,
    runtime: Option<&str>,
) -> CastAvailability {
    let current = selected.and_then(|selected| {
        characters
            .iter()
            .find(|character| Some(&character.id) == selected.cast.as_ref())
    });
    let new_agent = selected
        .and_then(|selected| selected.cast.as_deref())
        .is_some_and(|id| id.starts_with(NEW_CAST_PREFIX));
    let job_active = |id: &str| {
        jobs.get(id)
            .is_some_and(|status| matches!(status.as_str(), "queued" | "sent" | "waiting"))
    };
    let active_job = (current.is_some() || new_agent)
        && selected
            .and_then(|selected| selected.cast.as_deref())
            .is_some_and(job_active);
    let free: Vec<_> = characters
        .iter()
        .filter(|character| !open_cast.contains(&character.id) && !job_active(&character.id))
        .cloned()
        .collect();
    let mut resume_choices = free.clone();
    resume_choices.sort_by_key(|character| std::cmp::Reverse(Some(character) == current));
    let resume = selected.is_some_and(|selected| can_resume_cast(selected, runtime));
    let dismiss = selected.is_some_and(|selected| {
        selected.agent_id.is_some() && selected.cast_opened && (current.is_some() || new_agent)
    });
    CastAvailability {
        active_job,
        resume,
        dismiss,
        free,
        resume_choices,
    }
}
