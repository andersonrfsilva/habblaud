use std::{collections::BTreeMap, sync::Mutex};
pub use zaplaud_core::api::SessionPersistence;
static LOCK: Mutex<()> = Mutex::new(());

#[derive(Default)]
pub struct SecureSessionStorage;
impl SecureSessionStorage {
    pub fn new() -> Self {
        Self
    }
    pub fn memory() -> Self {
        Self
    }
    fn path() -> Option<std::path::PathBuf> {
        Some(
            crate::DesktopDirs::resolve()
                .ok()?
                .data
                .join("probe-cookies.json"),
        )
    }
    fn read() -> BTreeMap<String, String> {
        Self::path()
            .and_then(|path| std::fs::read(path).ok())
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }
    fn write(cookies: &BTreeMap<String, String>) {
        let result = (|| -> Result<(), Box<dyn std::error::Error>> {
            let path = Self::path().ok_or("Android cookie path unavailable")?;
            let temporary = path.with_extension("tmp");
            std::fs::write(&temporary, serde_json::to_vec(cookies)?)?;
            std::fs::rename(temporary, path)?;
            Ok(())
        })();
        if result.is_err() {
            eprintln!("Zaplaud RS probe: cookie persistence failed");
        }
    }
    pub fn load(&self, origin: &str) -> Option<String> {
        let _lock = LOCK.lock().unwrap_or_else(|error| error.into_inner());
        Self::read().remove(origin)
    }
    pub fn save(&self, origin: &str, cookie: &str) {
        let _lock = LOCK.lock().unwrap_or_else(|error| error.into_inner());
        let mut cookies = Self::read();
        cookies.insert(origin.into(), cookie.into());
        Self::write(&cookies);
    }
    pub fn clear(&self, origin: &str) {
        let _lock = LOCK.lock().unwrap_or_else(|error| error.into_inner());
        let mut cookies = Self::read();
        cookies.remove(origin);
        Self::write(&cookies);
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
