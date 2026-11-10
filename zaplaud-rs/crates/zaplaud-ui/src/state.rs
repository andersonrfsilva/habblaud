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
    fn theme(&self) -> Result<String, String>;
    fn set_theme(&mut self, theme: &str) -> Result<(), String>;
    fn default_view(&self) -> Result<String, String>;
    fn set_default_view(&mut self, view: &str) -> Result<(), String>;
    fn session_view(&self, key: &SessionKey) -> Result<String, String>;
    fn set_session_view(&mut self, key: &SessionKey, view: &str) -> Result<(), String>;
}
impl AppPersistence for zaplaud_store::LocalStateStore {
    fn servers(&self) -> Result<Vec<String>, String> {
        self.servers().map_err(|error| error.to_string())
    }
    fn add_server(&mut self, origin: &str) -> Result<(), String> {
        zaplaud_store::LocalStateStore::add_server(self, origin).map_err(|error| error.to_string())
    }
    fn remove_server(&mut self, origin: &str) -> Result<(), String> {
        zaplaud_store::LocalStateStore::remove_server(self, origin)
            .map_err(|error| error.to_string())
    }
    fn seen(&self, origin: &str) -> Result<HashMap<String, i64>, String> {
        self.seen_replies(origin)
            .map(|seen| seen.into_iter().collect())
            .map_err(|error| error.to_string())
    }
    fn save_seen(&mut self, origin: &str, seen: &HashMap<String, i64>) -> Result<(), String> {
        self.set_seen_replies(
            origin,
            &seen
                .iter()
                .map(|(key, value)| (key.clone(), *value))
                .collect(),
        )
        .map_err(|error| error.to_string())
    }
    fn last_session(&self) -> Result<Option<SessionKey>, String> {
        self.last_session().map_err(|error| error.to_string())
    }
    fn set_last_session(&mut self, key: Option<&SessionKey>) -> Result<(), String> {
        zaplaud_store::LocalStateStore::set_last_session(self, key)
            .map_err(|error| error.to_string())
    }
    fn retry_until(&self, origin: &str) -> Result<i64, String> {
        self.retry_until(origin).map_err(|error| error.to_string())
    }
    fn set_retry_until(&mut self, origin: &str, deadline: i64) -> Result<(), String> {
        zaplaud_store::LocalStateStore::set_retry_until(self, origin, deadline)
            .map_err(|error| error.to_string())
    }
    fn theme(&self) -> Result<String, String> {
        self.theme().map_err(|error| error.to_string())
    }
    fn set_theme(&mut self, theme: &str) -> Result<(), String> {
        zaplaud_store::LocalStateStore::set_theme(self, theme).map_err(|error| error.to_string())
    }
    fn default_view(&self) -> Result<String, String> {
        self.default_view().map_err(|error| error.to_string())
    }
    fn set_default_view(&mut self, view: &str) -> Result<(), String> {
        zaplaud_store::LocalStateStore::set_default_view(self, view)
            .map_err(|error| error.to_string())
    }
    fn session_view(&self, key: &SessionKey) -> Result<String, String> {
        self.session_view(key).map_err(|error| error.to_string())
    }
    fn set_session_view(&mut self, key: &SessionKey, view: &str) -> Result<(), String> {
        zaplaud_store::LocalStateStore::set_session_view(self, key, view)
            .map_err(|error| error.to_string())
    }
}

pub enum Event {
    Login {
        origin: String,
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
    Unauthorized(String),
    Theme(bool),
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

#[cfg(test)]
mod tests {
    use super::*;
    use zaplaud_store::{DesktopDirs, LocalStateStore};

    #[test]
    fn persistence_restores_servers_markers_preferences_and_removes_only_one_origin() {
        let root = tempfile::tempdir().unwrap();
        let dirs = DesktopDirs {
            data: root.path().join("data"),
            cache: root.path().join("cache"),
        };
        let mut store: Box<dyn AppPersistence> =
            Box::new(LocalStateStore::with_dirs(dirs.clone()).unwrap());
        let origin = "https://one.invalid";
        let other = "https://two.invalid";
        let key = SessionKey {
            origin: origin.into(),
            ..Default::default()
        };
        store.add_server(origin).unwrap();
        store.add_server(other).unwrap();
        store
            .save_seen(origin, &HashMap::from([("session".into(), 42)]))
            .unwrap();
        store.set_last_session(Some(&key)).unwrap();
        store.set_theme("dark").unwrap();
        store.set_default_view("all").unwrap();
        store.set_session_view(&key, "all").unwrap();
        store.set_retry_until(origin, 123).unwrap();
        drop(store);
        let mut store: Box<dyn AppPersistence> =
            Box::new(LocalStateStore::with_dirs(dirs).unwrap());
        assert_eq!(store.servers().unwrap(), [origin, other]);
        assert_eq!(store.seen(origin).unwrap()["session"], 42);
        assert_eq!(store.last_session().unwrap(), Some(key.clone()));
        assert_eq!(store.theme().unwrap(), "dark");
        assert_eq!(store.default_view().unwrap(), "all");
        assert_eq!(store.session_view(&key).unwrap(), "all");
        assert_eq!(store.retry_until(origin).unwrap(), 123);
        store.remove_server(origin).unwrap();
        assert_eq!(store.servers().unwrap(), [other]);
        assert!(store.last_session().unwrap().is_none());
        assert!(store.seen(origin).unwrap().is_empty());
    }
}
