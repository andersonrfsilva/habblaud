use crate::{DesktopDirs, create_private};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap},
    fs::{self, File, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::{LazyLock, Mutex},
};
use zaplaud_core::model::{
    Attachment, CachedConversation, DELIVER_MODES, MessageDraft, MessageLocalState,
    MessageOperation, SessionKey, THEMES, TerminalEntry, VIEWS,
};

#[derive(Debug, thiserror::Error)]
pub enum StoreError {
    #[error(transparent)]
    Io(#[from] io::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error("Connection changed")]
    ConnectionChanged,
}
type Result<T, E = StoreError> = std::result::Result<T, E>;
#[derive(Clone, Debug)]
pub struct ConversationWrite {
    pub origin: String,
    pub epoch: u64,
    pub sequence: u64,
}
#[derive(Default)]
struct Ordering {
    epochs: HashMap<String, u64>,
    sequence: u64,
    session_written: u64,
    written: HashMap<SessionKey, u64>,
}
static ORDERING: LazyLock<Mutex<HashMap<PathBuf, Ordering>>> = LazyLock::new(Default::default);
fn ordering() -> &'static Mutex<HashMap<PathBuf, Ordering>> {
    &ORDERING
}
pub fn storage_hash(value: &str) -> String {
    format!("{:x}", Sha256::digest(value.as_bytes()))
}
fn tag(origin: &str) -> String {
    storage_hash(origin)[..16].to_owned()
}
fn string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}
fn number(v: &Value) -> Option<i64> {
    string(v)?.parse().ok()
}
fn boolean(v: &Value) -> Option<bool> {
    string(v)?.parse().ok()
}
fn text(o: &Value, name: &str) -> Option<String> {
    o.get(name).and_then(string)
}
fn origins(data: &Map<String, Value>) -> Vec<String> {
    data.get("servers")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(string)
        .collect()
}
fn require(data: &Map<String, Value>, origin: &str) -> Result<()> {
    if origins(data).iter().any(|s| s == origin) {
        Ok(())
    } else {
        Err(StoreError::ConnectionChanged)
    }
}
fn hash(prefix: &str, key: &SessionKey) -> Result<String> {
    Ok(format!(
        "{prefix}{}_{}",
        tag(&key.origin),
        storage_hash(&key.serialize()?)
    ))
}
fn read_object(path: &Path) -> Result<Map<String, Value>> {
    match fs::read(path) {
        Ok(bytes) => Ok(serde_json::from_slice(&bytes)?),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Map::new()),
        Err(e) => Err(e.into()),
    }
}
pub(crate) fn private_open(path: &Path) -> io::Result<File> {
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options.open(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    Ok(file)
}
pub(crate) fn atomic_write(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let temporary = path.with_file_name(format!(
        "{}.tmp",
        path.file_name().unwrap_or_default().to_string_lossy()
    ));
    let result = (|| {
        let mut file = private_open(&temporary)?;
        file.set_len(0)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&temporary, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}
fn message_decode(value: Option<&Value>) -> MessageLocalState {
    let value = value.unwrap_or(&Value::Null);
    let operation = |name| {
        value
            .get(name)
            .filter(|v| v.is_object())
            .map(|o| MessageOperation {
                id: text(o, "id"),
                status: text(o, "status").unwrap_or_else(|| "unknown".into()),
                revision: o.get("revision").and_then(number).unwrap_or(0),
                error: text(o, "error"),
            })
    };
    let attachments = value
        .get("attachments")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(|o| {
            Some(Attachment {
                uri: text(o, "uri")?,
                name: text(o, "name")?,
                mime: text(o, "mime")?,
                size: o.get("size").and_then(number),
                persistent: o.get("persistent").and_then(boolean) == Some(true),
                identity: o.get("identity").cloned().and_then(|v| serde_json::from_value(v).ok()),
            })
        })
        .collect();
    MessageLocalState {
        draft: MessageDraft {
            text: text(value, "text").unwrap_or_default(),
            revision: value.get("revision").and_then(number).unwrap_or(0),
            attachments,
        },
        operation: operation("operation"),
        control: operation("control"),
    }
}
fn message_encode(state: &MessageLocalState) -> Value {
    let mut value = json!({"text":state.draft.text,"revision":state.draft.revision,"attachments":state.draft.attachments});
    for (name, operation) in [("operation", &state.operation), ("control", &state.control)] {
        if let Some(op) = operation {
            let mut v = json!({"id":op.id,"status":op.status,"revision":op.revision});
            if let Some(error) = &op.error {
                v["error"] = json!(error);
            }
            value[name] = v;
        }
    }
    value
}
fn summary_key(entry: &TerminalEntry) -> String {
    let hash = entry
        .text
        .encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32));
    format!("{}:{hash}", entry.id)
}

/// Operações síncronas: execute fora da thread da interface. Cada operação relê o disco sob lock consultivo.
#[derive(Clone, Debug)]
pub struct LocalStateStore {
    dirs: DesktopDirs,
}
impl LocalStateStore {
    pub fn new() -> Result<Self> {
        Self::with_dirs(DesktopDirs::resolve()?)
    }
    pub fn with_dirs(dirs: DesktopDirs) -> Result<Self> {
        create_private(&dirs.data)?;
        create_private(&dirs.cache)?;
        let store = Self { dirs };
        store.locked(|| {
            let path = store.dirs.data.join("settings.json");
            if !path.exists() {
                let prefs = std::env::var_os("HOME")
                    .map(PathBuf::from)
                    .map(|p| p.join(".java/.userPrefs/br/com/xlinx/zaplaud/prefs.xml"));
                let settings = prefs
                    .and_then(|p| fs::read_to_string(p).ok())
                    .map(|s| import_preferences(&s))
                    .unwrap_or_default();
                atomic_write(&path, &serde_json::to_vec(&settings)?)?;
            }
            Ok(())
        })?;
        Ok(store)
    }
    pub fn directories(&self) -> &DesktopDirs {
        &self.dirs
    }
    fn locked<T>(&self, f: impl FnOnce() -> Result<T>) -> Result<T> {
        let lock = private_open(&self.dirs.data.join(".state.lock"))?;
        lock.lock()?;
        f()
    }
    fn state<T>(
        &self,
        f: impl FnOnce(&mut Map<String, Value>) -> Result<T>,
        save: bool,
    ) -> Result<T> {
        self.locked(|| {
            let path = self.dirs.data.join("local_state.json");
            let mut data = read_object(&path)?;
            if !data.contains_key("servers")
                && let Some(origin) = data.get("origin").and_then(string)
            {
                data.insert("servers".into(), json!([origin]));
                for prefix in ["message_", "view_", "deliver_"] {
                    let old: Vec<_> = data
                        .keys()
                        .filter(|k| {
                            k.strip_prefix(prefix).is_some_and(|s| {
                                s.len() == 64
                                    && s.bytes()
                                        .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
                            })
                        })
                        .cloned()
                        .collect();
                    for key in old {
                        if let Some(v) = data.remove(&key) {
                            data.insert(
                                format!("{prefix}{}_{}", tag(&origin), &key[prefix.len()..]),
                                v,
                            );
                        }
                    }
                }
                if let Some(seen) = data.remove("seen_replies") {
                    data.insert(format!("seen_replies_{}", tag(&origin)), seen);
                }
                data.remove("origin");
                atomic_write(&path, &serde_json::to_vec(&data)?)?;
                self.delete_files(|n| n.starts_with("conversation-"))?;
            }
            let result = f(&mut data)?;
            if save {
                atomic_write(&path, &serde_json::to_vec(&data)?)?;
            }
            Ok(result)
        })
    }
    fn delete_files(&self, test: impl Fn(&str) -> bool) -> Result<()> {
        for file in fs::read_dir(&self.dirs.data)? {
            let file = file?;
            if test(&file.file_name().to_string_lossy()) {
                fs::remove_file(file.path())?;
            }
        }
        Ok(())
    }
    pub fn servers(&self) -> Result<Vec<String>> {
        self.state(|d| Ok(origins(d)), false)
    }
    pub fn add_server(&self, origin: &str) -> Result<()> {
        self.state(
            |d| {
                let mut servers = origins(d);
                if !servers.iter().any(|s| s == origin) {
                    servers.push(origin.into());
                    d.insert("servers".into(), json!(servers));
                }
                Ok(())
            },
            true,
        )
    }
    pub fn message(&self, key: &SessionKey) -> Result<MessageLocalState> {
        self.state(
            |d| Ok(message_decode(d.get(&hash("message_", key)?))),
            false,
        )
    }
    pub fn update_message(
        &self,
        key: &SessionKey,
        update: impl FnOnce(MessageLocalState) -> MessageLocalState,
    ) -> Result<MessageLocalState> {
        self.state(
            |d| {
                require(d, &key.origin)?;
                let name = hash("message_", key)?;
                let next = update(message_decode(d.get(&name)));
                d.insert(name, message_encode(&next));
                Ok(next)
            },
            true,
        )
    }
    pub fn uses_attachment(&self, uri: &str) -> Result<bool> {
        self.state(
            |d| {
                Ok(d.iter()
                    .filter(|(k, _)| k.starts_with("message_"))
                    .any(|(_, v)| {
                        message_decode(Some(v))
                            .draft
                            .attachments
                            .iter()
                            .any(|a| a.uri == uri)
                    }))
            },
            false,
        )
    }
    pub fn last_session(&self) -> Result<Option<SessionKey>> {
        self.state(
            |d| {
                Ok(d.get("last_session")
                    .and_then(string)
                    .and_then(|s| SessionKey::deserialize(&s).ok()))
            },
            false,
        )
    }
    pub fn set_last_session(&self, key: Option<&SessionKey>) -> Result<()> {
        self.state(
            |d| {
                if let Some(key) = key {
                    d.insert("last_session".into(), json!(key.serialize()?));
                } else {
                    d.remove("last_session");
                }
                Ok(())
            },
            true,
        )
    }
    pub fn seen_id(&self, key: &SessionKey) -> Result<String> {
        Ok(storage_hash(&key.serialize()?))
    }
    pub fn seen_replies(&self, origin: &str) -> Result<BTreeMap<String, i64>> {
        self.state(
            |d| {
                Ok(d.get(&format!("seen_replies_{}", tag(origin)))
                    .and_then(Value::as_object)
                    .into_iter()
                    .flatten()
                    .filter_map(|(k, v)| Some((k.clone(), number(v)?)))
                    .collect())
            },
            false,
        )
    }
    pub fn set_seen_replies(&self, origin: &str, seen: &BTreeMap<String, i64>) -> Result<()> {
        self.state(
            |d| {
                require(d, origin)?;
                d.insert(format!("seen_replies_{}", tag(origin)), json!(seen));
                Ok(())
            },
            true,
        )
    }
    fn session_setting(
        &self,
        key: &SessionKey,
        prefix: &str,
        values: &[&str],
        fallback: impl FnOnce() -> Result<String>,
    ) -> Result<String> {
        let value = self.state(|d| Ok(d.get(&hash(prefix, key)?).and_then(string)), false)?;
        match value.filter(|s| values.contains(&s.as_str())) {
            Some(s) => Ok(s),
            None => fallback(),
        }
    }
    fn set_session_setting(
        &self,
        key: &SessionKey,
        prefix: &str,
        value: &str,
        values: &[&str],
    ) -> Result<()> {
        if !values.contains(&value) {
            return Ok(());
        }
        self.state(
            |d| {
                require(d, &key.origin)?;
                d.insert(hash(prefix, key)?, json!(value));
                Ok(())
            },
            true,
        )
    }
    pub fn session_view(&self, key: &SessionKey) -> Result<String> {
        self.session_setting(key, "view_", &VIEWS, || self.default_view())
    }
    pub fn set_session_view(&self, key: &SessionKey, view: &str) -> Result<()> {
        self.set_session_setting(key, "view_", view, &VIEWS)
    }
    pub fn session_deliver_as(&self, key: &SessionKey) -> Result<String> {
        self.session_setting(key, "deliver_", &DELIVER_MODES, || self.deliver_as())
    }
    pub fn set_session_deliver_as(&self, key: &SessionKey, mode: &str) -> Result<()> {
        self.set_session_setting(key, "deliver_", mode, &DELIVER_MODES)
    }
    fn settings<T>(
        &self,
        f: impl FnOnce(&mut Map<String, Value>) -> Result<T>,
        save: bool,
    ) -> Result<T> {
        self.locked(|| {
            let path = self.dirs.data.join("settings.json");
            let mut data = read_object(&path)?;
            let result = f(&mut data)?;
            if save {
                atomic_write(&path, &serde_json::to_vec(&data)?)?;
            }
            Ok(result)
        })
    }
    fn setting(&self, key: &str, default: Value) -> Result<Value> {
        self.settings(|d| Ok(d.get(key).cloned().unwrap_or(default)), false)
    }
    fn set_setting(&self, key: &str, value: Value) -> Result<()> {
        self.settings(
            |d| {
                d.insert(key.into(), value);
                Ok(())
            },
            true,
        )
    }
    pub fn theme(&self) -> Result<String> {
        Ok(string(&self.setting("theme", json!("system"))?).unwrap_or_else(|| "system".into()))
    }
    pub fn set_theme(&self, value: &str) -> Result<()> {
        if THEMES.contains(&value) {
            self.set_setting("theme", json!(value))
        } else {
            Ok(())
        }
    }
    pub fn summarize(&self) -> Result<bool> {
        Ok(boolean(&self.setting("summarize_replies", json!(false))?).unwrap_or(false))
    }
    pub fn set_summarize(&self, on: bool) -> Result<()> {
        self.set_setting("summarize_replies", json!(on))
    }
    pub fn notifications_enabled(&self) -> Result<bool> {
        Ok(boolean(&self.setting("notifications", json!(false))?).unwrap_or(false))
    }
    pub fn set_notifications_enabled(&self, on: bool) -> Result<()> {
        self.set_setting("notifications", json!(on))
    }
    pub fn notify_events(&self) -> Result<(bool, bool)> {
        self.settings(
            |d| {
                Ok((
                    d.get("notify_permission").and_then(boolean).unwrap_or(true),
                    d.get("notify_done").and_then(boolean).unwrap_or(true),
                ))
            },
            false,
        )
    }
    pub fn set_notify_events(&self, permission: bool, done: bool) -> Result<()> {
        self.settings(
            |d| {
                d.insert("notify_permission".into(), json!(permission));
                d.insert("notify_done".into(), json!(done));
                Ok(())
            },
            true,
        )
    }
    pub fn retry_until(&self, origin: &str) -> Result<i64> {
        Ok(
            number(&self.setting(&format!("retry_{}", storage_hash(origin)), json!(0))?)
                .unwrap_or(0),
        )
    }
    pub fn set_retry_until(&self, origin: &str, until: i64) -> Result<()> {
        self.set_setting(&format!("retry_{}", storage_hash(origin)), json!(until))
    }
    pub fn default_view(&self) -> Result<String> {
        Ok(string(&self.setting("view", json!("all"))?)
            .filter(|s| VIEWS.contains(&s.as_str()))
            .unwrap_or_else(|| "all".into()))
    }
    pub fn set_default_view(&self, view: &str) -> Result<()> {
        if VIEWS.contains(&view) {
            self.set_setting("view", json!(view))
        } else {
            Ok(())
        }
    }
    pub fn deliver_as(&self) -> Result<String> {
        Ok(string(&self.setting("deliver_as", json!("aside"))?)
            .filter(|s| DELIVER_MODES.contains(&s.as_str()))
            .unwrap_or_else(|| "aside".into()))
    }
    pub fn set_deliver_as(&self, mode: &str) -> Result<()> {
        if DELIVER_MODES.contains(&mode) {
            self.set_setting("deliver_as", json!(mode))
        } else {
            Ok(())
        }
    }
    fn cache_path(&self, key: &SessionKey, data: &Map<String, Value>) -> Result<PathBuf> {
        Ok(self.dirs.data.join(format!(
            "conversation-{}-{}-{}.json",
            data.get("conversation_generation")
                .and_then(string)
                .unwrap_or_else(|| "null".into()),
            tag(&key.origin),
            storage_hash(&key.serialize()?)
        )))
    }
    pub fn conversation(&self, key: &SessionKey) -> Result<Option<CachedConversation>> {
        self.state(
            |d| {
                if require(d, &key.origin).is_err() {
                    return Ok(None);
                }
                let bytes = match fs::read(self.cache_path(key, d)?) {
                    Ok(b) => b,
                    Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
                    Err(e) => return Err(e.into()),
                };
                let Ok(v) = serde_json::from_slice::<Map<String, Value>>(&bytes) else {
                    return Ok(None);
                };
                Ok(Some(CachedConversation {
                    entries: v
                        .get("entries")
                        .and_then(Value::as_array)
                        .into_iter()
                        .flatten()
                        .filter(|v| v.is_object())
                        .filter_map(TerminalEntry::parse)
                        .collect(),
                    truncated: v.get("truncated").and_then(boolean) == Some(true),
                    summaries: v
                        .get("summaries")
                        .and_then(Value::as_object)
                        .into_iter()
                        .flatten()
                        .filter_map(|(k, v)| Some((k.clone(), v.as_str()?.into())))
                        .collect(),
                }))
            },
            false,
        )
    }
    pub fn conversation_epoch(&self, origin: &str) -> u64 {
        let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
        *all.entry(self.dirs.data.clone())
            .or_default()
            .epochs
            .entry(origin.into())
            .or_default()
    }
    pub fn conversation_write(&self, origin: &str, epoch: u64) -> ConversationWrite {
        let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
        let order = all.entry(self.dirs.data.clone()).or_default();
        order.sequence += 1;
        ConversationWrite {
            origin: origin.into(),
            epoch,
            sequence: order.sequence,
        }
    }
    pub fn set_conversation(
        &self,
        key: &SessionKey,
        entries: &[TerminalEntry],
        truncated: bool,
        write: &ConversationWrite,
        summaries: &BTreeMap<String, String>,
    ) -> Result<()> {
        self.state(
            |d| {
                let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
                let order = all.entry(self.dirs.data.clone()).or_default();
                if write.origin != key.origin
                    || write.epoch != *order.epochs.entry(key.origin.clone()).or_default()
                    || require(d, &key.origin).is_err()
                    || write.sequence <= *order.written.get(key).unwrap_or(&0)
                {
                    return Ok(());
                }
                let kept = &entries[entries.len().saturating_sub(500)..];
                let encoded: Vec<_> = kept
                    .iter()
                    .map(|e| {
                        let mut o = json!({"id":e.id,"kind":e.kind,"text":e.text});
                        for (name, value) in [("title", &e.title), ("toolUseId", &e.tool_use_id)] {
                            if let Some(v) = value {
                                o[name] = json!(v);
                            }
                        }
                        if e.truncated {
                            o["truncated"] = json!(true);
                        }
                        if let Some(at) = e.at {
                            o["at"] = json!(at);
                        }
                        o
                    })
                    .collect();
                let mut value = json!({"truncated":truncated||entries.len()>500,"entries":encoded});
                if !summaries.is_empty() {
                    let retained: BTreeMap<_, _> = kept
                        .iter()
                        .filter_map(|e| {
                            let k = summary_key(e);
                            Some((k.clone(), summaries.get(&k)?))
                        })
                        .collect();
                    value["summaries"] = json!(retained);
                }
                atomic_write(&self.cache_path(key, d)?, &serde_json::to_vec(&value)?)?;
                order.written.insert(key.clone(), write.sequence);
                Ok(())
            },
            false,
        )
    }
    pub fn set_last_session_with_write(
        &self,
        key: Option<&SessionKey>,
        write: &ConversationWrite,
    ) -> Result<()> {
        self.state(
            |d| {
                let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
                let order = all.entry(self.dirs.data.clone()).or_default();
                if write.epoch != *order.epochs.entry(write.origin.clone()).or_default()
                    || require(d, &write.origin).is_err()
                    || key.is_some_and(|k| k.origin != write.origin)
                    || write.sequence <= order.session_written
                {
                    return Ok(());
                }
                if let Some(k) = key {
                    d.insert("last_session".into(), json!(k.serialize()?));
                } else {
                    d.remove("last_session");
                }
                atomic_write(
                    &self.dirs.data.join("local_state.json"),
                    &serde_json::to_vec(d)?,
                )?;
                order.session_written = write.sequence;
                Ok(())
            },
            false,
        )
    }
    pub fn remove_server(&self, origin: &str) -> Result<()> {
        self.state(
            |d| {
                d.insert(
                    "servers".into(),
                    json!(
                        origins(d)
                            .into_iter()
                            .filter(|s| s != origin)
                            .collect::<Vec<_>>()
                    ),
                );
                let t = tag(origin);
                d.retain(|k, _| {
                    !(["message_", "view_", "deliver_"]
                        .iter()
                        .any(|p| k.starts_with(&format!("{p}{t}_")))
                        || k == &format!("seen_replies_{t}"))
                });
                if d.get("last_session")
                    .and_then(string)
                    .and_then(|s| SessionKey::deserialize(&s).ok())
                    .is_some_and(|k| k.origin == origin)
                {
                    d.remove("last_session");
                }
                atomic_write(
                    &self.dirs.data.join("local_state.json"),
                    &serde_json::to_vec(d)?,
                )?;
                let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
                let order = all.entry(self.dirs.data.clone()).or_default();
                *order.epochs.entry(origin.into()).or_default() += 1;
                order.written.retain(|k, _| k.origin != origin);
                self.delete_files(|n| {
                    n.starts_with("conversation-")
                        && n.ends_with(".json")
                        && n.rsplit_once('-')
                            .is_some_and(|(p, _)| p.ends_with(&format!("-{t}")))
                })?;
                Ok(())
            },
            false,
        )
    }
    pub fn clear(&self) -> Result<()> {
        self.state(
            |d| {
                let servers = origins(d);
                d.clear();
                d.insert(
                    "conversation_generation".into(),
                    json!(uuid::Uuid::new_v4().to_string()),
                );
                atomic_write(
                    &self.dirs.data.join("local_state.json"),
                    &serde_json::to_vec(d)?,
                )?;
                let mut all = ordering().lock().unwrap_or_else(|e| e.into_inner());
                let order = all.entry(self.dirs.data.clone()).or_default();
                for origin in servers {
                    order.epochs.entry(origin).or_default();
                }
                for epoch in order.epochs.values_mut() {
                    *epoch += 1;
                }
                order.written.clear();
                self.delete_files(|n| {
                    n.starts_with("conversation-") || n.starts_with("attachment-")
                })?;
                Ok(())
            },
            false,
        )
    }
}
fn import_preferences(xml: &str) -> Map<String, Value> {
    let Ok(doc) = roxmltree::Document::parse_with_options(
        xml,
        roxmltree::ParsingOptions {
            allow_dtd: true,
            ..Default::default()
        },
    ) else {
        return Map::new();
    };
    doc.descendants()
        .filter(|n| n.has_tag_name("entry"))
        .filter(|n| {
            matches!(
                n.attribute("key"),
                Some("theme" | "summarize_replies" | "notifications" | "deliver_as" | "view"
                    | "notify_permission" | "notify_done")
            )
        })
        .filter_map(|n| Some((n.attribute("key")?.to_owned(), json!(n.attribute("value")?))))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_preferences_imports_only_kotlin_settings() {
        let settings = import_preferences(
            r#"<map>
                <entry key="theme" value="dark"/>
                <entry key="summarize_replies" value="true"/>
                <entry key="notifications" value="true"/>
                <entry key="deliver_as" value="steer"/>
                <entry key="view" value="prompts"/>
                <entry key="notify_permission" value="false"/>
                <entry key="notify_done" value="false"/>
                <entry key="password" value="fictional"/>
                <entry key="cookie" value="fictional"/>
                <entry key="future_unknown" value="ignored"/>
            </map>"#,
        );
        assert_eq!(settings.len(), 7);
        assert_eq!(settings["theme"], "dark");
        assert_eq!(settings["notify_done"], "false");
        assert!(!settings.contains_key("password"));
        assert!(!settings.contains_key("cookie"));
        assert!(!settings.contains_key("future_unknown"));
    }
}
