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
static TARGET: LazyLock<Option<String>> = LazyLock::new(keyring_target);

/// Coleção onde o cookie fica. A padrão pode ter senha própria e ficar trancada depois do login
/// (no miranote, "Chaveiro padrão"); gravar nela pede senha ou falha e o login vira só memória.
/// Preferimos uma coleção persistente já destrancada (a "Login", que o PAM abre na entrada).
#[cfg(target_os = "linux")]
fn keyring_target() -> Option<String> {
    use secret_service::{EncryptionType, blocking::SecretService};
    let ss = SecretService::connect(EncryptionType::Plain).ok()?;
    if let Ok(default) = ss.get_default_collection()
        && matches!(default.is_locked(), Ok(false))
    {
        return None;
    }
    let collections = ss.get_all_collections().ok()?;
    let labels = collections.iter().filter_map(|c| {
        let path = c.collection_path.as_str();
        let unlocked = matches!(c.is_locked(), Ok(false));
        Some((path.to_owned(), c.get_label().ok()?, unlocked))
    });
    choose_unlocked(labels)
}
#[cfg(not(target_os = "linux"))]
fn keyring_target() -> Option<String> {
    None
}

/// Primeira coleção destrancada e persistente com rótulo único; a de sessão some ao sair.
#[cfg(any(target_os = "linux", test))]
fn choose_unlocked(collections: impl Iterator<Item = (String, String, bool)>) -> Option<String> {
    let collections: Vec<_> = collections.collect();
    collections
        .iter()
        .filter(|(path, label, unlocked)| {
            *unlocked
                && !label.is_empty()
                && !path.ends_with("/collection/session")
                && label != "default"
                && collections
                    .iter()
                    .filter(|(_, other, _)| other == label)
                    .count()
                    == 1
        })
        .map(|(_, label, _)| label.clone())
        .next()
}

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
            match keyring::Entry::new_with_target(
                TARGET.as_deref().unwrap_or("default"),
                "br.com.xlinx.zaplaud",
                "remote-session",
            ) {
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
    fn locked_default_falls_back_to_persistent_unlocked_collection() {
        let c = |path: &str, label: &str, unlocked| {
            (
                format!("/org/freedesktop/secrets/collection/{path}"),
                label.to_owned(),
                unlocked,
            )
        };
        let miranote = [
            c("Chaveiro_5fpadr_c3_a3o", "Chaveiro padrão", false),
            c("session", "", true),
            c("login", "Login", true),
        ];
        assert_eq!(
            choose_unlocked(miranote.into_iter()).as_deref(),
            Some("Login")
        );
        // Sem coleção persistente destrancada, fica na padrão (o sistema pede a senha).
        assert_eq!(
            choose_unlocked([c("session", "sessão", true), c("x", "Trancada", false)].into_iter()),
            None
        );
        // Rótulo repetido seria ambíguo para o keyring, que localiza a coleção pelo rótulo.
        assert_eq!(
            choose_unlocked([c("a", "Login", true), c("b", "Login", true)].into_iter()),
            None
        );
    }

    #[test]
    fn valid_keyring_entries_preserve_every_origin() {
        let mut cookies =
            decode_cookies(r#"{"v":2,"cookies":{"one":"first","other":"preserve"}}"#).unwrap();
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
