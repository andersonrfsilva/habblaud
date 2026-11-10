use std::collections::HashMap;
use zaplaud_core::{
    api::HabblaudApi,
    controller::reading::ReadingController,
    model::{Access, SessionCollection, SessionKey},
};

/// Adaptador síncrono: chamado somente pela thread de fundo, nunca pelo event loop.
pub trait AppPersistence: Send {
    fn servers(&self) -> Result<Vec<String>, String>;
    fn add_server(&mut self, origin: &str) -> Result<(), String>;
    fn remove_server(&mut self, origin: &str) -> Result<(), String>;
    fn seen(&self, origin: &str) -> Result<HashMap<String, i64>, String>;
    fn save_seen(&mut self, origin: &str, seen: &HashMap<String, i64>) -> Result<(), String>;
    fn last_session(&self) -> Result<Option<SessionKey>, String>;
    fn set_last_session(&mut self, key: Option<&SessionKey>) -> Result<(), String>;
    fn retry_until(&self, origin: &str) -> Result<i64, String>;
    fn set_retry_until(&mut self, origin: &str, deadline: i64) -> Result<(), String>;
}
#[derive(Default)]
pub struct MemoryPersistence {
    servers: Vec<String>,
    seen: HashMap<String, HashMap<String, i64>>,
    last: Option<SessionKey>,
    retry: HashMap<String, i64>,
}
impl AppPersistence for MemoryPersistence {
    fn servers(&self) -> Result<Vec<String>, String> {
        Ok(self.servers.clone())
    }
    fn add_server(&mut self, origin: &str) -> Result<(), String> {
        if !self.servers.iter().any(|s| s == origin) {
            self.servers.push(origin.into());
        }
        Ok(())
    }
    fn remove_server(&mut self, origin: &str) -> Result<(), String> {
        self.servers.retain(|s| s != origin);
        self.seen.remove(origin);
        self.retry.remove(origin);
        if self.last.as_ref().is_some_and(|k| k.origin == origin) {
            self.last = None;
        }
        Ok(())
    }
    fn seen(&self, origin: &str) -> Result<HashMap<String, i64>, String> {
        Ok(self.seen.get(origin).cloned().unwrap_or_default())
    }
    fn save_seen(&mut self, origin: &str, seen: &HashMap<String, i64>) -> Result<(), String> {
        self.seen.insert(origin.into(), seen.clone());
        Ok(())
    }
    fn last_session(&self) -> Result<Option<SessionKey>, String> {
        Ok(self.last.clone())
    }
    fn set_last_session(&mut self, key: Option<&SessionKey>) -> Result<(), String> {
        self.last = key.cloned();
        Ok(())
    }
    fn retry_until(&self, origin: &str) -> Result<i64, String> {
        Ok(self.retry.get(origin).copied().unwrap_or(0))
    }
    fn set_retry_until(&mut self, origin: &str, deadline: i64) -> Result<(), String> {
        self.retry.insert(origin.into(), deadline);
        Ok(())
    }
}

pub enum Event {
    Login {
        api: HabblaudApi,
        user: String,
        password: String,
    },
    LoginFinished {
        api: HabblaudApi,
        result: Result<(), zaplaud_core::Error>,
    },
    Refreshed {
        origin: String,
        generation: u64,
        access: Access,
        repository: SessionCollection,
    },
    SnapshotData {
        origin: String,
        generation: u64,
        data: String,
    },
    Connection {
        origin: String,
        generation: u64,
        status: Option<i32>,
    },
    Open(String),
    Close,
    Filter {
        origin: String,
        query: String,
        recent: bool,
    },
    Avatar(String),
    AvatarFinished(String),
    LogoutFinished {
        origin: String,
        revoked: bool,
    },
    Logout(String),
    Shutdown,
}
pub struct ServerState {
    pub api: HabblaudApi,
    pub reading: ReadingController,
    pub generation: u64,
    pub task: tokio::task::JoinHandle<()>,
}
pub struct AppState {
    pub servers: Vec<ServerState>,
    pub filter: Option<String>,
    pub query: String,
    pub recent: bool,
    pub persistence: Box<dyn AppPersistence>,
    pub next_generation: u64,
}
impl AppState {
    pub fn new(persistence: Box<dyn AppPersistence>) -> Self {
        Self {
            servers: Vec::new(),
            filter: None,
            query: String::new(),
            recent: false,
            persistence,
            next_generation: 0,
        }
    }
}
pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis().min(i64::MAX as u128) as i64)
}
