use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    sync::{
        LazyLock, Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

pub use zaplaud_core::api::SessionPersistence;
static MEMORY: LazyLock<Mutex<BTreeMap<String, String>>> = LazyLock::new(Default::default);
static KEYRING_LOCK: Mutex<()> = Mutex::new(());
static UNAVAILABLE: AtomicBool = AtomicBool::new(false);

/// Sem Debug deliberadamente: o armazenamento pode conter cookies autenticados.
pub struct SecureSessionStorage {
    memory_only: bool,
}
impl Default for SecureSessionStorage {
    fn default() -> Self {
        Self::new()
    }
}
impl SecureSessionStorage {
    pub fn new() -> Self {
        Self {
            memory_only: std::env::var("ZAPLAUD_KEYRING").as_deref() == Ok("memory"),
        }
    }
    pub fn memory() -> Self {
        Self { memory_only: true }
    }
    fn entry(&self) -> Option<keyring::Entry> {
        if self.memory_only || UNAVAILABLE.load(Ordering::Relaxed) {
            None
        } else {
            match keyring::Entry::new("br.com.xlinx.zaplaud", "remote-session") {
                Ok(entry) => Some(entry),
                Err(_) => {
                    UNAVAILABLE.store(true, Ordering::Relaxed);
                    None
                }
            }
        }
    }
    fn read(&self) -> BTreeMap<String, String> {
        if let Some(entry) = self.entry() {
            match entry.get_password() {
                Ok(value) => {
                    if let Ok(value) = serde_json::from_str::<Value>(&value) {
                        if value.get("v").is_some_and(|v| v == 2 || v == "2") {
                            if let Some(cookies) = value.get("cookies").and_then(Value::as_object) {
                                return cookies
                                    .iter()
                                    .filter_map(|(k, v)| Some((k.clone(), v.as_str()?.to_owned())))
                                    .collect();
                            }
                        } else if let (Some(origin), Some(cookie)) = (
                            value.get("origin").and_then(Value::as_str),
                            value.get("cookie").and_then(Value::as_str),
                        ) {
                            return BTreeMap::from([(origin.to_owned(), cookie.to_owned())]);
                        }
                    }
                    return BTreeMap::new();
                }
                Err(keyring::Error::NoEntry) => return BTreeMap::new(),
                Err(_) => {
                    UNAVAILABLE.store(true, Ordering::Relaxed);
                }
            }
        }
        MEMORY.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
    fn write(&self, cookies: BTreeMap<String, String>) {
        if let Some(entry) = self.entry() {
            let result = if cookies.is_empty() {
                entry.delete_credential()
            } else {
                entry.set_password(&json!({"v":2,"cookies":cookies}).to_string())
            };
            if result.is_err() {
                UNAVAILABLE.store(true, Ordering::Relaxed);
            }
        }
        *MEMORY.lock().unwrap_or_else(|e| e.into_inner()) = cookies;
    }
    pub fn load(&self, origin: &str) -> Option<String> {
        let _lock = KEYRING_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        self.read().remove(origin)
    }
    pub fn save(&self, origin: &str, cookie: &str) {
        let _lock = KEYRING_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut cookies = self.read();
        cookies.insert(origin.into(), cookie.into());
        self.write(cookies);
    }
    pub fn clear(&self, origin: &str) {
        let _lock = KEYRING_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut cookies = self.read();
        cookies.remove(origin);
        self.write(cookies);
    }
}
impl SessionPersistence for SecureSessionStorage {
    fn load(&self, origin: &str) -> Option<String> {
        self.load(origin)
    }
    fn save(&self, origin: &str, cookie: &str) {
        self.save(origin, cookie)
    }
    fn clear(&self, origin: &str) {
        self.clear(origin)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn memory_is_shared_and_isolated_by_origin() {
        let a = SecureSessionStorage::memory();
        let b = SecureSessionStorage::memory();
        a.save(
            "https://store-test.invalid",
            "habblaud_remote=fictitious; Path=/",
        );
        a.save(
            "https://other-store-test.invalid",
            "habblaud_remote=other; Path=/",
        );
        assert!(b.load("https://store-test.invalid").is_some());
        b.clear("https://store-test.invalid");
        assert!(a.load("https://store-test.invalid").is_none());
        assert!(a.load("https://other-store-test.invalid").is_some());
        a.clear("https://other-store-test.invalid");
    }
}
