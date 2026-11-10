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

fn decode_cookies(value: &str) -> Result<BTreeMap<String, String>, ()> {
    let value: Value = serde_json::from_str(value).map_err(|_| ())?;
    match value.get("v") {
        Some(version) if version == 2 || version == "2" => {
            serde_json::from_value(value.get("cookies").cloned().ok_or(())?).map_err(|_| ())
        }
        None | Some(Value::Number(_)) | Some(Value::String(_))
            if value.get("v").is_none_or(|v| v == 1 || v == "1") =>
        {
            let origin = value.get("origin").and_then(Value::as_str).ok_or(())?;
            let cookie = value.get("cookie").and_then(Value::as_str).ok_or(())?;
            Ok(BTreeMap::from([(origin.to_owned(), cookie.to_owned())]))
        }
        _ => Err(()),
    }
}

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
    fn read(&self) -> Result<BTreeMap<String, String>, ()> {
        if let Some(entry) = self.entry() {
            match entry.get_password() {
                Ok(value) => return decode_cookies(&value),
                Err(keyring::Error::NoEntry) => return Ok(BTreeMap::new()),
                Err(_) => {
                    UNAVAILABLE.store(true, Ordering::Relaxed);
                }
            }
        }
        Ok(MEMORY.lock().unwrap_or_else(|e| e.into_inner()).clone())
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
        self.read().ok()?.remove(origin)
    }
    pub fn save(&self, origin: &str, cookie: &str) {
        let _lock = KEYRING_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let Ok(mut cookies) = self.read() else {
            return;
        };
        cookies.insert(origin.into(), cookie.into());
        self.write(cookies);
    }
    pub fn clear(&self, origin: &str) {
        let _lock = KEYRING_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let Ok(mut cookies) = self.read() else {
            return;
        };
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
    fn malformed_or_unknown_keyring_entries_refuse_mutation() {
        for value in [
            "{",
            "null",
            r#"{"v":3,"origin":"other","cookie":"preserve"}"#,
            r#"{"v":2,"cookies":{"other":"preserve","bad":12}}"#,
            r#"{"v":2,"cookies":null}"#,
            r#"{"v":2}"#,
        ] {
            assert!(decode_cookies(value).is_err(), "{value}");
        }
    }

    #[test]
    fn valid_keyring_entries_preserve_every_origin() {
        let mut cookies = decode_cookies(
            r#"{"v":2,"cookies":{"one":"first","other":"preserve"}}"#,
        ).unwrap();
        cookies.insert("one".into(), "updated".into());
        assert_eq!(cookies["other"], "preserve");
        let roundtrip = decode_cookies(&json!({"v":2,"cookies":cookies}).to_string()).unwrap();
        assert_eq!(roundtrip, cookies);
        for version in ["", r#""v":1,"#, r#""v":"1","#] {
            let legacy = format!(r#"{{{version}"origin":"other","cookie":"preserve"}}"#);
            assert_eq!(decode_cookies(&legacy).unwrap()["other"], "preserve");
        }
    }

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
