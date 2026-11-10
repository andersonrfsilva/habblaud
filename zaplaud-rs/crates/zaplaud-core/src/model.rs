use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};

pub const NEW_CAST_PREFIX: &str = "novo-";
pub const MAX_ATTACHMENT_SIZE: i64 = 15 * 1024 * 1024;
pub const MAX_ATTACHMENTS: usize = 5;
pub const ASIDE_QUESTION_MAX: usize = 20_000;
pub const DELIVER_MODES: [&str; 3] = ["aside", "followUp", "steer"];
pub const VIEWS: [&str; 3] = ["all", "prompts", "no_tools"];
pub const THEMES: [&str; 3] = ["system", "light", "dark"];
pub const EDITABLE_ROLES: [&str; 4] = ["task", "plan", "advisor", "tiny"];
pub const THINKING_LEVELS: [&str; 7] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];

// O Kotlin aceita o conteúdo de qualquer primitivo, não apenas strings JSON.
pub(crate) fn string(o: &Value, name: &str) -> Option<String> {
    primitive(o.get(name)?)
}
fn primitive(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}
pub(crate) fn integer(o: &Value, name: &str) -> Option<i64> {
    string(o, name)?.parse().ok()
}
pub(crate) fn flag(o: &Value, name: &str) -> bool {
    matches!(o.get(name), Some(Value::Bool(true)))
        || o.get(name).and_then(Value::as_str) == Some("true")
}
pub(crate) fn objects<'a>(o: &'a Value, name: &str) -> impl Iterator<Item = &'a Value> {
    o.get(name)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter(|v| v.is_object())
}
fn strings(o: &Value, name: &str) -> Vec<String> {
    o.get(name)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str().map(str::to_owned))
        .collect()
}
fn root(data: &str) -> Result<Value, serde_json::Error> {
    serde_json::from_str::<Map<String, Value>>(data).map(Value::Object)
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionKey {
    pub origin: String,
    pub provider: String,
    pub account: String,
    pub session_id: String,
}
impl SessionKey {
    pub fn serialize(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
    pub fn deserialize(data: &str) -> Result<Self, serde_json::Error> {
        let o = root(data)?;
        let required = |name| {
            string(&o, name).ok_or_else(|| {
                <serde_json::Error as serde::de::Error>::custom(format!(
                    "campo obrigatório: {name}"
                ))
            })
        };
        Ok(Self {
            origin: required("origin")?,
            provider: required("provider")?,
            account: required("account")?,
            session_id: required("sessionId")?,
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Avatar {
    pub seed: i64,
    pub look: String,
    pub kind: String,
    pub parts: Option<String>,
}
impl Avatar {
    pub fn parse(o: &Value) -> Option<Self> {
        let seed = integer(o, "seed").filter(|n| (0..=0xffff_ffff).contains(n))?;
        let look = string(o, "look").filter(|s| s == "f" || s == "m")?;
        Some(Self {
            seed,
            look,
            kind: if string(o, "kind").as_deref() == Some("sub") {
                "sub"
            } else {
                "main"
            }
            .into(),
            parts: o
                .get("parts")
                .filter(|v| v.is_object())
                .map(Value::to_string),
        })
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SessionItem {
    pub key: SessionKey,
    pub agent_id: Option<String>,
    pub title: String,
    pub project: Option<String>,
    pub status: Option<String>,
    pub activity: Option<String>,
    pub permission: bool,
    pub agent_name: Option<String>,
    pub can_message: bool,
    pub permission_id: Option<String>,
    pub last_at: Option<i64>,
    pub avatar: Option<Avatar>,
    pub cast: Option<String>,
    pub cast_opened: bool,
    pub permission_question: bool,
    pub character_name: Option<String>,
    pub context_tokens: Option<i64>,
    pub context_window: Option<i64>,
    pub model: Option<String>,
    pub thinking: Option<String>,
    pub models: Vec<String>,
    pub subagents: Vec<SubAgent>,
    pub sub: bool,
    pub replies: Vec<i64>,
    pub status_since: Option<i64>,
}
impl SessionItem {
    pub fn parse(
        origin: &str,
        o: &Value,
        project: Option<String>,
        server_now: i64,
        now: i64,
    ) -> Option<Self> {
        let account = string(o, "account")?;
        let session_id = string(o, "sessionId")?;
        let permission = o.get("permission").filter(|v| v.is_object());
        let activity = o.get("activity").filter(|v| v.is_object()).and_then(|v| {
            string(v, "summary")
                .or_else(|| string(v, "text"))
                .or_else(|| string(v, "description"))
        });
        let context = o.get("context");
        Some(Self {
            key: SessionKey {
                origin: origin.into(),
                provider: string(o, "provider").unwrap_or_else(|| "claude".into()),
                account,
                session_id: session_id.clone(),
            },
            agent_id: string(o, "id").or_else(|| string(o, "agentId")),
            title: string(o, "title")
                .or_else(|| string(o, "name"))
                .unwrap_or(session_id),
            project: project.or_else(|| string(o, "project")),
            status: string(o, "status"),
            activity,
            permission: permission.is_some(),
            agent_name: string(o, "name"),
            can_message: flag(o, "canMessage"),
            permission_id: permission.and_then(|v| string(v, "id")),
            last_at: string(o, "lastEventAt")
                .or_else(|| string(o, "lastAt"))
                .and_then(|v| v.parse().ok()),
            avatar: Avatar::parse(o),
            cast: string(o, "cast"),
            cast_opened: flag(o, "castOpened"),
            permission_question: permission
                .is_some_and(|v| objects(v, "questions").next().is_some()),
            character_name: if string(o, "kind").as_deref() == Some("main") {
                string(o, "name")
            } else {
                None
            },
            context_tokens: context
                .and_then(|v| integer(v, "tokens"))
                .filter(|n| *n >= 0),
            context_window: context
                .and_then(|v| integer(v, "window"))
                .filter(|n| *n > 0),
            model: string(o, "model"),
            thinking: string(o, "thinking"),
            models: strings(o, "models"),
            replies: o
                .get("replies")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|v| primitive(v)?.parse().ok())
                .collect(),
            status_since: integer(o, "statusSince")
                .filter(|n| *n > 0)
                .map(|n| local_time(n, server_now, now)),
            ..Self::default()
        })
    }
    pub fn live_or_closed(mut self) -> Self {
        if self.status.as_deref() == Some("offline") {
            self.agent_id = None;
            self.can_message = false;
        }
        self
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SubAgent {
    pub id: String,
    pub name: String,
    pub avatar: Option<Avatar>,
    pub advisor: bool,
    pub activity: Option<String>,
}
pub fn sub_session(parent: &SessionItem, sub: &SubAgent) -> SessionItem {
    let mut key = parent.key.clone();
    key.session_id = format!("{}/{}", key.session_id, sub.id);
    SessionItem {
        key,
        agent_id: Some(sub.id.clone()),
        title: sub.name.clone(),
        project: parent.project.clone(),
        status: Some("working".into()),
        activity: sub.activity.clone(),
        avatar: sub.avatar.clone(),
        character_name: Some(sub.name.clone()),
        sub: true,
        ..SessionItem::default()
    }
}
pub fn local_time(server: i64, server_now: i64, now: i64) -> i64 {
    if server_now <= 0 {
        server
    } else {
        server + (((now as f64 - server_now as f64) / 1000.0 + 0.5).floor() as i64) * 1000
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Snapshot {
    pub sessions: Vec<SessionItem>,
    pub server_time: i64,
    pub accounts: Vec<AccountQuota>,
    pub raw: Value,
}
impl Snapshot {
    pub fn parse(origin: &str, data: &str) -> Result<Self, serde_json::Error> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_millis().min(i64::MAX as u128) as i64);
        Self::parse_at(origin, data, now)
    }
    pub fn parse_at(origin: &str, data: &str, now: i64) -> Result<Self, serde_json::Error> {
        Self::from_value(origin, root(data)?, now, 0)
    }
    fn from_value(
        origin: &str,
        raw: Value,
        now: i64,
        previous_server_time: i64,
    ) -> Result<Self, serde_json::Error> {
        let server_time = integer(&raw, "serverTime").unwrap_or(previous_server_time);
        let rooms: HashMap<Option<String>, Option<String>> = objects(&raw, "rooms")
            .map(|r| {
                (
                    string(r, "id"),
                    string(r, "path").or_else(|| string(r, "name")),
                )
            })
            .collect();
        let mut subs: HashMap<String, Vec<SubAgent>> = HashMap::new();
        for o in objects(&raw, "agents").filter(|v| {
            string(v, "kind").as_deref() == Some("sub")
                && string(v, "status").as_deref() == Some("working")
        }) {
            if let (Some(parent), Some(id)) = (string(o, "parentId"), string(o, "id")) {
                let activity = o
                    .get("activity")
                    .and_then(|v| string(v, "summary").or_else(|| string(v, "text")));
                subs.entry(parent).or_default().push(SubAgent {
                    name: string(o, "name").unwrap_or_else(|| id.clone()),
                    id,
                    avatar: Avatar::parse(o),
                    advisor: string(o, "role").as_deref() == Some("__advisor"),
                    activity,
                });
            }
        }
        let sessions = objects(&raw, "agents")
            .filter(|v| {
                string(v, "kind").as_deref() == Some("main") && string(v, "parentId").is_none()
            })
            .filter_map(|o| {
                let mut item = SessionItem::parse(
                    origin,
                    o,
                    rooms.get(&string(o, "roomId")).cloned().flatten(),
                    server_time,
                    now,
                )?;
                item.subagents = item
                    .agent_id
                    .as_ref()
                    .and_then(|id| subs.get(id))
                    .cloned()
                    .unwrap_or_default();
                Some(item.live_or_closed())
            })
            .collect();
        let accounts = objects(&raw, "accounts")
            .filter_map(AccountQuota::parse)
            .collect();
        Ok(Self {
            sessions,
            server_time,
            accounts,
            raw,
        })
    }
}

#[derive(Clone, Debug, Default)]
pub struct SessionCollection {
    pub recent: Vec<SessionItem>,
    pub open: Vec<SessionItem>,
    pub cast: Vec<Value>,
    pub server_time: i64,
}
impl SessionCollection {
    pub fn parse_snapshot(
        &mut self,
        origin: &str,
        data: &str,
        now: i64,
    ) -> Result<Vec<SessionItem>, serde_json::Error> {
        let snapshot = Snapshot::from_value(origin, root(data)?, now, self.server_time)?;
        Ok(self.snapshot(snapshot))
    }
    pub fn set_recent(&mut self, origin: &str, data: &str) -> Result<(), serde_json::Error> {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_millis().min(i64::MAX as u128) as i64);
        self.recent = objects(&root(data)?, "sessions")
            .filter_map(|v| SessionItem::parse(origin, v, None, self.server_time, now))
            .collect();
        Ok(())
    }
    pub fn set_cast(&mut self, data: &str) -> Result<(), serde_json::Error> {
        self.cast = objects(&root(data)?, "members").cloned().collect();
        Ok(())
    }
    pub fn snapshot(&mut self, snapshot: Snapshot) -> Vec<SessionItem> {
        self.server_time = integer(&snapshot.raw, "serverTime").unwrap_or(self.server_time);
        self.open = snapshot.sessions;
        self.sessions()
    }
    pub fn sessions(&self) -> Vec<SessionItem> {
        let live: HashSet<_> = self.open.iter().map(|v| &v.key).collect();
        let historical: HashMap<_, _> = self.recent.iter().map(|v| (&v.key, v)).collect();
        let mut result: Vec<_> = self
            .open
            .iter()
            .map(|item| {
                let mut next = item.clone();
                if let Some(old) = historical.get(&item.key) {
                    next.project = next.project.or_else(|| old.project.clone());
                    next.last_at = next.last_at.or(old.last_at);
                    if Some(&next.title) == next.character_name.as_ref() {
                        next.title = old.title.clone();
                    }
                }
                next
            })
            .collect();
        result.extend(
            self.recent
                .iter()
                .filter(|v| !live.contains(&v.key))
                .map(|item| {
                    let mut next = item.clone();
                    let owners: Vec<_> = self
                        .cast
                        .iter()
                        .filter(|v| {
                            item.key.provider == "omp"
                                && string(v, "account").is_none_or(|a| a == item.key.account)
                                && v.get("last")
                                    .and_then(Value::as_object)
                                    .is_some_and(|last| {
                                        last.values().any(|id| {
                                            id.to_string().trim_matches('"') == item.key.session_id
                                        })
                                    })
                        })
                        .collect();
                    let owner = if owners.len() == 1 {
                        owners.first().copied()
                    } else {
                        None
                    };
                    next.agent_id = None;
                    next.avatar = owner.and_then(Avatar::parse);
                    next.cast = owner.and_then(|v| string(v, "id"));
                    next.character_name = owner.and_then(|v| string(v, "name"));
                    next
                }),
        );
        result
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TerminalEntry {
    pub id: String,
    pub kind: String,
    pub text: String,
    pub title: Option<String>,
    pub tool_use_id: Option<String>,
    pub truncated: bool,
    pub at: Option<i64>,
}
impl TerminalEntry {
    pub fn parse(o: &Value) -> Option<Self> {
        let id = string(o, "id")?;
        let kind = string(o, "kind").unwrap_or_else(|| "unknown".into());
        let known = matches!(
            kind.as_str(),
            "user" | "assistant" | "thinking" | "tool" | "result" | "system"
        );
        let text = if known {
            ["text", "input", "detail"]
                .into_iter()
                .filter_map(|k| string(o, k))
                .collect::<Vec<_>>()
                .join("\n")
        } else {
            String::new()
        };
        Some(Self {
            id,
            kind: if known { kind } else { "unknown".into() },
            text,
            title: string(o, "title"),
            tool_use_id: string(o, "toolUseId"),
            truncated: flag(o, "truncated"),
            at: integer(o, "at").filter(|n| *n > 0),
        })
    }
    pub fn bytes(&self) -> usize {
        self.id.len()
            + self.kind.len()
            + self.text.len()
            + self.title.as_ref().map_or(0, String::len)
            + self.tool_use_id.as_ref().map_or(0, String::len)
    }
}
#[derive(Clone, Debug, Default)]
pub struct ConversationState {
    entries: VecDeque<TerminalEntry>,
    ids: HashSet<String>,
    bytes: usize,
    pub truncated: bool,
    pub reduced: bool,
}
impl ConversationState {
    pub const MAX_BYTES: usize = 8 * 1024 * 1024;
    fn validate(items: &[TerminalEntry]) -> Result<(), &'static str> {
        if items
            .iter()
            .try_fold(0usize, |n, e| n.checked_add(e.bytes()))
            .is_none_or(|n| n > Self::MAX_BYTES)
        {
            Err("event-too-large")
        } else {
            Ok(())
        }
    }
    pub fn snapshot(&self) -> impl Iterator<Item = &TerminalEntry> {
        self.entries.iter()
    }
    pub fn init(&mut self, items: Vec<TerminalEntry>, truncated: bool) -> Result<(), &'static str> {
        Self::validate(&items)?;
        self.entries.clear();
        self.ids.clear();
        self.bytes = 0;
        self.reduced = false;
        self.truncated = truncated;
        self.append(items)
    }
    pub fn append(&mut self, items: Vec<TerminalEntry>) -> Result<(), &'static str> {
        Self::validate(&items)?;
        for item in items {
            if !self.ids.insert(item.id.clone()) {
                continue;
            }
            self.bytes += item.bytes();
            self.entries.push_back(item);
            while self.entries.len() > 1000 || self.bytes > Self::MAX_BYTES {
                if let Some(old) = self.entries.pop_front() {
                    self.ids.remove(&old.id);
                    self.bytes -= old.bytes();
                    self.reduced = true;
                }
            }
        }
        Ok(())
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConversationInit {
    pub entries: Vec<TerminalEntry>,
    pub truncated: bool,
    pub after: Option<Value>,
    pub after_present: bool,
}
#[derive(Clone, Debug, PartialEq)]
pub enum ServerEvent {
    Snapshot(Snapshot),
    ConversationInit(ConversationInit),
    ConversationAppend(Vec<TerminalEntry>),
    Unknown { name: String, data: String },
}
impl ServerEvent {
    pub fn parse(origin: &str, event: &str, data: &str) -> Result<Self, serde_json::Error> {
        match event {
            "snapshot" => Ok(Self::Snapshot(Snapshot::parse(origin, data)?)),
            "init" => {
                let o = root(data)?;
                Ok(Self::ConversationInit(ConversationInit {
                    entries: objects(&o, "entries")
                        .filter_map(TerminalEntry::parse)
                        .collect(),
                    truncated: flag(&o, "truncated"),
                    after: o.get("after").cloned(),
                    after_present: o.get("after").is_some(),
                }))
            }
            "append" => {
                let values: Vec<Value> = serde_json::from_str(data)?;
                Ok(Self::ConversationAppend(
                    values
                        .iter()
                        .filter(|v| v.is_object())
                        .filter_map(TerminalEntry::parse)
                        .collect(),
                ))
            }
            _ => Ok(Self::Unknown {
                name: event.into(),
                data: data.into(),
            }),
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StreamEvent {
    Data {
        event_type: String,
        data: String,
        first_connection: bool,
    },
    Failure {
        status: Option<i32>,
        retry_after: Option<String>,
    },
    Connected,
    Reconnecting {
        delay: std::time::Duration,
    },
    Warn {
        status: Option<i32>,
    },
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Access {
    pub terminal: bool,
    pub messages: bool,
    pub message_session_guard: bool,
    pub permissions: bool,
}
impl Access {
    pub fn parse(o: &Value) -> Self {
        Self {
            terminal: flag(o, "terminal"),
            messages: flag(o, "messages"),
            message_session_guard: flag(o, "messageSessionGuard"),
            permissions: flag(o, "permissions"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentIdentity {
    pub modified_nanos: u128,
    pub device: u64,
    pub inode: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    pub uri: String,
    pub name: String,
    pub mime: String,
    pub size: Option<i64>,
    pub persistent: bool,
    #[serde(default)]
    pub identity: Option<AttachmentIdentity>,
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct MessageDraft {
    pub text: String,
    pub revision: i64,
    pub attachments: Vec<Attachment>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct MessageOperation {
    pub id: Option<String>,
    pub status: String,
    pub revision: i64,
    pub error: Option<String>,
}
impl Default for MessageOperation {
    fn default() -> Self {
        Self {
            id: None,
            status: "unknown".into(),
            revision: 0,
            error: None,
        }
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct MessageLocalState {
    pub draft: MessageDraft,
    pub operation: Option<MessageOperation>,
    pub control: Option<MessageOperation>,
}
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct CachedConversation {
    pub entries: Vec<TerminalEntry>,
    pub truncated: bool,
    pub summaries: BTreeMap<String, String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CommandResult {
    pub kind: String,
    pub status: String,
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ComposerState {
    pub key: Option<SessionKey>,
    pub draft: MessageDraft,
    pub operation: Option<MessageOperation>,
    pub control: Option<MessageOperation>,
    pub control_error: Option<String>,
    pub loading: bool,
    pub sending: bool,
    pub error: Option<String>,
    pub progress: Option<String>,
    pub unavailable: HashSet<String>,
    pub command_result: Option<CommandResult>,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum SendMode {
    #[default]
    #[serde(rename = "aside")]
    Aside,
    #[serde(rename = "followUp")]
    FollowUp,
    #[serde(rename = "steer")]
    Steer,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Connection {
    #[default]
    Connecting,
    Connected,
    Offline,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReadingState {
    pub sessions: Vec<SessionItem>,
    pub selected: Option<SessionItem>,
    pub entries: Vec<TerminalEntry>,
    pub truncated: bool,
    pub reduced: bool,
    pub terminal_allowed: bool,
    pub connection: String,
    pub error: Option<String>,
    pub messages_allowed: bool,
    pub message_guard: bool,
    pub permissions_allowed: bool,
    pub reading_error: Option<String>,
    pub unread: HashMap<SessionKey, usize>,
}
impl Default for ReadingState {
    fn default() -> Self {
        Self {
            sessions: Vec::new(),
            selected: None,
            entries: Vec::new(),
            truncated: false,
            reduced: false,
            terminal_allowed: false,
            connection: "connecting".into(),
            error: None,
            messages_allowed: false,
            message_guard: false,
            permissions_allowed: false,
            reading_error: None,
            unread: HashMap::new(),
        }
    }
}
impl ReadingState {
    pub fn stream_failure(&mut self, status: Option<i32>, conversation: bool) {
        let reason = match status {
            Some(-1) => "oversize",
            Some(403) => "forbidden",
            Some(404) => "missing",
            _ => "network",
        };
        if conversation {
            self.reading_error = Some(reason.into());
        } else {
            self.connection = "offline".into();
            self.error = Some(reason.into());
        }
    }
    pub fn conversation_connected(&mut self) {
        if self.reading_error.as_deref() == Some("network") {
            self.reading_error = None;
        }
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AgentWatch {
    pub status: Option<String>,
    pub permission_id: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AgentEvent {
    Permission(SessionItem),
    TurnDone(SessionItem),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AsideTurn {
    pub id: String,
    pub question: String,
    pub answer: String,
    pub status: String,
    pub error: Option<String>,
}
impl AsideTurn {
    pub fn running(&self) -> bool {
        matches!(self.status.as_str(), "queued" | "running")
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AsideTopic {
    pub id: String,
    pub created_at: i64,
    pub turns: Vec<AsideTurn>,
}
impl AsideTopic {
    pub fn parse(o: &Value) -> Option<Self> {
        Some(Self {
            id: string(o, "id")?,
            created_at: integer(o, "createdAt").unwrap_or(0),
            turns: objects(o, "turns")
                .filter_map(|t| {
                    Some(AsideTurn {
                        id: string(t, "id")?,
                        question: string(t, "question").unwrap_or_default(),
                        answer: string(t, "answer").unwrap_or_default(),
                        status: string(t, "status")?,
                        error: string(t, "error"),
                    })
                })
                .collect(),
        })
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct AsideState {
    pub agent_id: Option<String>,
    pub topics: Vec<AsideTopic>,
    pub topic_id: Option<String>,
    pub fresh: bool,
    pub loading: bool,
    pub sending: bool,
    pub error: Option<String>,
}
impl AsideState {
    pub fn topic(&self) -> Option<&AsideTopic> {
        if self.fresh {
            None
        } else {
            self.topics
                .iter()
                .find(|t| Some(&t.id) == self.topic_id.as_ref())
                .or_else(|| self.topics.first())
        }
    }
    pub fn running(&self) -> bool {
        self.topics
            .iter()
            .any(|t| t.turns.iter().any(AsideTurn::running))
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PermissionOption {
    pub index: Option<i32>,
    pub label: String,
    pub description: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PermissionQuestion {
    pub index: Option<i32>,
    pub header: Option<String>,
    pub question: String,
    pub multi_select: bool,
    pub options: Vec<PermissionOption>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PermissionSuggestion {
    pub index: Option<i32>,
    pub rules: Vec<String>,
    pub destination: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Permission {
    pub provider: String,
    pub expires_at: i64,
    pub tool: Option<String>,
    pub description: Option<String>,
    pub input: Option<String>,
    pub questions: Vec<PermissionQuestion>,
    pub suggestions: Vec<PermissionSuggestion>,
    pub raw: Value,
}
impl Permission {
    pub fn parse(o: &Value) -> Self {
        let index = |value: &Value| integer(value, "index").and_then(|n| i32::try_from(n).ok());
        Self {
            provider: string(o, "provider").unwrap_or_else(|| "claude".into()),
            expires_at: integer(o, "expiresAt").unwrap_or(0),
            tool: string(o, "tool"),
            description: string(o, "description"),
            input: o.get("input").map(|input| match input {
                Value::String(text) => text.clone(),
                value => value.to_string(),
            }),
            questions: objects(o, "questions")
                .map(|q| PermissionQuestion {
                    index: index(q),
                    header: string(q, "header"),
                    question: string(q, "question").unwrap_or_default(),
                    multi_select: flag(q, "multiSelect"),
                    options: objects(q, "options")
                        .map(|p| PermissionOption {
                            index: index(p),
                            label: string(p, "label").unwrap_or_default(),
                            description: string(p, "description"),
                        })
                        .collect(),
                })
                .collect(),
            suggestions: objects(o, "suggestions")
                .map(|s| PermissionSuggestion {
                    index: index(s),
                    rules: s
                        .get("rules")
                        .and_then(Value::as_array)
                        .into_iter()
                        .flatten()
                        .filter_map(primitive)
                        .collect(),
                    destination: string(s, "destination"),
                })
                .collect(),
            raw: o.clone(),
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CastChoice {
    pub id: String,
    pub name: String,
    pub rooms: Vec<String>,
    pub avatar: Option<Avatar>,
}
impl CastChoice {
    pub fn parse(o: &Value) -> Option<Self> {
        if string(o, "kind").as_deref() != Some("main") {
            return None;
        }
        Some(Self {
            id: string(o, "id")?,
            name: string(o, "name")?,
            rooms: o
                .get("rooms")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(primitive)
                .collect(),
            avatar: Avatar::parse(o),
        })
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CastJobs {
    pub available: bool,
    pub jobs: BTreeMap<String, String>,
}
impl CastJobs {
    pub fn parse(o: &Value) -> Self {
        Self {
            available: flag(o, "available"),
            jobs: objects(o, "jobs")
                .filter_map(|j| Some((string(j, "cast")?, string(j, "status")?)))
                .collect(),
        }
    }
}
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ModelRoles {
    pub roles: Option<BTreeMap<String, String>>,
    pub pending: bool,
    pub available: bool,
}
impl ModelRoles {
    pub fn parse(o: &Value) -> Self {
        Self {
            roles: o.get("roles").filter(|v| v.is_object()).map(|r| {
                EDITABLE_ROLES
                    .into_iter()
                    .filter_map(|k| Some((k.to_owned(), string(r, k)?)))
                    .collect()
            }),
            pending: o.get("pending") == Some(&Value::Bool(true)),
            available: o.get("available") == Some(&Value::Bool(true)),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageLimit {
    pub label: String,
    pub utilization: Option<f64>,
    pub resets_at: Option<i64>,
    pub exhausted: bool,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct UsageGroup {
    pub provider: Option<String>,
    pub plan: Option<String>,
    pub limits: Vec<UsageLimit>,
    pub note: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountQuota {
    pub id: String,
    pub name: String,
    pub provider: String,
    pub plan: Option<String>,
    pub status: String,
    pub no_quota: bool,
    pub groups: Vec<UsageGroup>,
    pub fetched_at: Option<i64>,
}
impl AccountQuota {
    pub fn parse(a: &Value) -> Option<Self> {
        let id = string(a, "id")?;
        let usage = a.get("usage").filter(|v| v.is_object());
        let providers: Vec<_> = usage
            .into_iter()
            .flat_map(|u| objects(u, "ompProviders"))
            .collect();
        let groups = if providers.is_empty() {
            usage
                .and_then(|u| {
                    let limits = ["fiveHour", "sevenDay", "sevenDayOpus", "sevenDaySonnet"]
                        .into_iter()
                        .filter_map(|name| {
                            let w = u.get(name).filter(|v| v.is_object())?;
                            Some(UsageLimit {
                                label: name.into(),
                                utilization: string(w, "utilization").and_then(|n| n.parse().ok()),
                                resets_at: integer(w, "resetsAt"),
                                exhausted: false,
                            })
                        })
                        .collect::<Vec<_>>();
                    let note = string(u, "note");
                    if limits.is_empty() && note.is_none() {
                        None
                    } else {
                        Some(UsageGroup {
                            provider: None,
                            plan: None,
                            limits,
                            note,
                        })
                    }
                })
                .into_iter()
                .collect()
        } else {
            providers
                .into_iter()
                .map(|p| UsageGroup {
                    provider: string(p, "provider"),
                    plan: string(p, "plan"),
                    limits: objects(p, "limits")
                        .filter_map(|l| {
                            Some(UsageLimit {
                                label: string(l, "label")?,
                                utilization: string(l, "utilization").and_then(|n| n.parse().ok()),
                                resets_at: integer(l, "resetsAt"),
                                exhausted: flag(l, "exhausted"),
                            })
                        })
                        .collect(),
                    note: string(p, "note"),
                })
                .collect()
        };
        Some(Self {
            name: string(a, "name").unwrap_or_else(|| id.clone()),
            id,
            provider: string(a, "provider").unwrap_or_else(|| "claude".into()),
            plan: string(a, "plan"),
            status: string(a, "usageStatus").unwrap_or_else(|| "disabled".into()),
            no_quota: usage.is_some_and(|u| flag(u, "noQuota")),
            groups,
            fetched_at: usage.and_then(|u| integer(u, "fetchedAt")),
        })
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConversationWrite {
    pub origin: String,
    pub epoch: i64,
    pub sequence: i64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EntryRow {
    pub entry: TerminalEntry,
    pub count: usize,
    pub tools: Vec<TerminalEntry>,
}
impl EntryRow {
    pub fn new(entry: TerminalEntry) -> Self {
        Self {
            entry,
            count: 1,
            tools: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn keys_avatars_permissions_and_roles_match_primitive_optionality() {
        let key = SessionKey::deserialize(
            r#"{"origin":"https://test","provider":"omp","account":1,"sessionId":false,"extra":0}"#,
        )
        .unwrap();
        assert_eq!(
            (key.account.as_str(), key.session_id.as_str()),
            ("1", "false")
        );
        assert_eq!(
            SessionKey::deserialize(&key.serialize().unwrap()).unwrap(),
            key
        );
        assert!(SessionKey::deserialize(r#"{"origin":"https://test"}"#).is_err());
        assert!(Avatar::parse(&json!({"seed":4294967296_i64,"look":"f"})).is_none());
        assert!(Avatar::parse(&json!({"seed":0,"look":"other"})).is_none());
        let avatar =
            Avatar::parse(&json!({"seed":"4294967295","look":"m","kind":"sub","parts":{"hair":1}}))
                .unwrap();
        assert_eq!((avatar.seed, avatar.kind.as_str()), (4294967295, "sub"));
        let permission = Permission::parse(
            &json!({"input":{"ignored":true},"questions":[{"index":2147483648_i64,"options":[{"index":"2"}],"multiSelect":"true"}]}),
        );
        assert_eq!(permission.provider, "claude");
        assert_eq!(permission.expires_at, 0);
        assert_eq!(permission.input.as_deref(), Some(r#"{"ignored":true}"#));
        assert!(permission.questions[0].index.is_none());
        assert_eq!(permission.questions[0].options[0].index, Some(2));
        assert!(permission.questions[0].multi_select);
        let roles = ModelRoles::parse(
            &json!({"available":true,"pending":"true","roles":{"task":123,"unknown":"ignored"}}),
        );
        assert!(roles.available && !roles.pending);
        assert_eq!(
            roles.roles.unwrap(),
            BTreeMap::from([("task".into(), "123".into())])
        );
    }

    #[test]
    fn quotas_prefer_provider_limits_and_preserve_window_defaults() {
        let account=AccountQuota::parse(&json!({"id":"a","usage":{"ompProviders":[{"provider":"p","limits":[{"label":"daily","utilization":"3.5","exhausted":true},{"utilization":8}]}],"fiveHour":{"utilization":99}}})).unwrap();
        assert_eq!(
            (
                account.name.as_str(),
                account.provider.as_str(),
                account.status.as_str()
            ),
            ("a", "claude", "disabled")
        );
        assert_eq!(account.groups[0].limits.len(), 1);
        assert_eq!(account.groups[0].limits[0].utilization, Some(3.5));
        assert!(account.groups[0].limits[0].exhausted);
        let account =
            AccountQuota::parse(&json!({"id":"b","usage":{"fiveHour":{},"noQuota":true}})).unwrap();
        assert!(account.no_quota);
        assert_eq!(account.groups[0].limits[0].label, "fiveHour");
        assert_eq!(account.groups[0].limits[0].utilization, None);
    }

    #[test]
    fn entries_join_known_fields_and_hide_unknown_payloads() {
        let entry = TerminalEntry::parse(
            &json!({"id":7,"kind":"tool","text":"a","input":false,"detail":"c","at":"12"}),
        )
        .unwrap();
        assert_eq!(
            (entry.id.as_str(), entry.text.as_str(), entry.at),
            ("7", "a\nfalse\nc", Some(12))
        );
        let unknown =
            TerminalEntry::parse(&json!({"id":"x","kind":"future","text":"hidden","at":0}))
                .unwrap();
        assert_eq!(
            (unknown.kind.as_str(), unknown.text.as_str(), unknown.at),
            ("unknown", "", None)
        );
        assert!(TerminalEntry::parse(&json!({"text":"no id"})).is_none());
    }
    #[test]
    fn snapshot_filters_main_sessions_and_attaches_active_subagents() {
        let raw = json!({"serverTime":10000,"rooms":[{"id":"room","path":"/work"}],"agents":[
            {"id":"a","kind":"main","account":"default","sessionId":"s","roomId":"room","status":"offline","canMessage":true,"context":{"tokens":-1,"window":0},"models":["ok",7],"permission":{},"statusSince":9000},
            {"id":"b","kind":"sub","parentId":"a","status":"working","role":"__advisor","activity":{"description":"ignored","text":"work"}},
            {"id":"c","kind":"main","parentId":"a","account":"default","sessionId":"hidden"}]});
        let snapshot = Snapshot::parse_at("https://test", &raw.to_string(), 11500).unwrap();
        assert_eq!(snapshot.sessions.len(), 1);
        let item = &snapshot.sessions[0];
        assert_eq!(item.project.as_deref(), Some("/work"));
        assert!(item.agent_id.is_none() && !item.can_message && item.permission);
        assert_eq!(
            (item.context_tokens, item.context_window, item.status_since),
            (None, None, Some(11000))
        );
        assert_eq!(item.models, ["ok"]);
        assert!(item.subagents[0].advisor);
    }
    #[test]
    fn conversation_deduplicates_evicts_and_rejects_before_mutation() {
        let mut state = ConversationState::default();
        let entries = (0..1001)
            .map(|n| TerminalEntry {
                id: n.to_string(),
                kind: "user".into(),
                ..TerminalEntry::default()
            })
            .collect();
        state.init(entries, true).unwrap();
        assert_eq!(state.snapshot().count(), 1000);
        assert!(state.reduced && state.truncated);
        state
            .append(vec![TerminalEntry {
                id: "1000".into(),
                text: "duplicate".into(),
                ..TerminalEntry::default()
            }])
            .unwrap();
        assert_eq!(state.snapshot().count(), 1000);
        assert_eq!(
            state.init(
                vec![TerminalEntry {
                    text: "x".repeat(ConversationState::MAX_BYTES + 1),
                    ..TerminalEntry::default()
                }],
                false
            ),
            Err("event-too-large")
        );
        assert!(state.truncated);
    }
    #[test]
    fn parsers_preserve_missing_defaults_and_unknown_events() {
        let topic = AsideTopic::parse(
            &json!({"id":"t","turns":[{"id":"q","status":"queued"},{"id":"skip"}]}),
        )
        .unwrap();
        assert_eq!(topic.created_at, 0);
        assert_eq!(topic.turns.len(), 1);
        assert!(topic.turns[0].running());
        let event = ServerEvent::parse(
            "https://test",
            "init",
            r#"{"after":null,"entries":[false,{"id":"1"}]}"#,
        )
        .unwrap();
        assert!(matches!(
            event,
            ServerEvent::ConversationInit(ConversationInit {
                after_present: true,
                after: Some(Value::Null),
                ..
            })
        ));
        assert!(matches!(
            ServerEvent::parse("https://test", "future", "not json").unwrap(),
            ServerEvent::Unknown { .. }
        ));
    }
    #[test]
    fn historical_merge_restores_title_and_unique_cast_owner() {
        let mut collection = SessionCollection::default();
        collection.set_recent("https://test",r#"{"sessions":[{"provider":"omp","account":"a","sessionId":"s","title":"Conversation","project":"/work","lastAt":7}]}"#).unwrap();
        collection
            .set_cast(
                r#"{"members":[{"id":"cast","name":"Name","last":{"a":"s"},"seed":0,"look":"f"}]}"#,
            )
            .unwrap();
        let old = collection.sessions();
        assert_eq!(old[0].character_name.as_deref(), Some("Name"));
        let snapshot=Snapshot::parse_at("https://test",r#"{"agents":[{"id":"agent","kind":"main","provider":"omp","account":"a","sessionId":"s","name":"Name"}]}"#,0).unwrap();
        let merged = collection.snapshot(snapshot);
        assert_eq!(merged.len(), 1);
        assert_eq!(merged[0].title, "Conversation");
        assert_eq!(merged[0].last_at, Some(7));
    }
}
