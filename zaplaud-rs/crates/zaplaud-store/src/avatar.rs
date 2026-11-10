use crate::{
    DesktopDirs, create_private,
    state::{atomic_write, private_open},
    storage_hash,
};
use std::{
    fs,
    io::{self, Cursor, Read},
    path::PathBuf,
    time::{Duration, SystemTime},
};
const MAX_FILE: u64 = 1024 * 1024;
const MAX_DISK: u64 = 16 * 1024 * 1024;
const VALIDITY: Duration = Duration::from_secs(24 * 60 * 60);

/// Apenas disco e validação PNG. O cache de imagens decodificadas pertence à interface.
pub struct AvatarDiskCache {
    directory: PathBuf,
}
impl AvatarDiskCache {
    pub fn new(dirs: &DesktopDirs) -> io::Result<Self> {
        let directory = dirs.cache.join("avatars");
        create_private(&directory)?;
        Ok(Self { directory })
    }
    pub fn validate(bytes: &[u8]) -> bool {
        if !(24..=MAX_FILE as usize).contains(&bytes.len())
            || bytes[..8] != [137, 80, 78, 71, 13, 10, 26, 10]
            || &bytes[12..16] != b"IHDR"
            || bytes[16..20] != 60u32.to_be_bytes()
            || bytes[20..24] != 60u32.to_be_bytes()
        {
            return false;
        }
        let decoder = png::Decoder::new(Cursor::new(bytes));
        let Ok(mut reader) = decoder.read_info() else {
            return false;
        };
        if reader.info().width != 60 || reader.info().height != 60 {
            return false;
        }
        let Some(size) = reader.output_buffer_size() else {
            return false;
        };
        let mut buffer = vec![0; size];
        reader.next_frame(&mut buffer).is_ok() && reader.finish().is_ok()
    }
    pub fn read(&self, key: &str) -> io::Result<Option<Vec<u8>>> {
        let lock = private_open(&self.directory.join(".lock"))?;
        lock.lock()?;
        let path = self.directory.join(storage_hash(key));
        let file = match fs::File::open(path) {
            Ok(file) => file,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e),
        };
        let meta = file.metadata()?;
        if !meta.is_file()
            || meta.len() > MAX_FILE
            || SystemTime::now()
                .duration_since(meta.modified()?)
                .unwrap_or_default()
                >= VALIDITY
        {
            return Ok(None);
        }
        let mut bytes = Vec::with_capacity(meta.len() as usize);
        file.take(MAX_FILE + 1).read_to_end(&mut bytes)?;
        Ok(Self::validate(&bytes).then_some(bytes))
    }
    /// Bytes válidos são aceitos; erros de disco são propagados.
    pub fn save(&self, key: &str, bytes: &[u8]) -> io::Result<bool> {
        if !Self::validate(bytes) {
            return Ok(false);
        }
        let lock = private_open(&self.directory.join(".lock"))?;
        lock.lock()?;
        atomic_write(&self.directory.join(storage_hash(key)), bytes)?;
        self.prune()?;
        Ok(true)
    }
    fn prune(&self) -> io::Result<()> {
        let mut files = Vec::new();
        for item in fs::read_dir(&self.directory)? {
            let item = item?;
            let name = item.file_name();
            if name.to_string_lossy().starts_with('.') {
                continue;
            }
            let meta = item.metadata()?;
            if meta.is_file() {
                files.push((meta.modified()?, meta.len(), item.path()));
            }
        }
        files.sort_by_key(|file| std::cmp::Reverse(file.0));
        let mut total = 0;
        for (_, size, path) in files {
            total += size;
            if total > MAX_DISK {
                fs::remove_file(path)?;
            }
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn png() -> Vec<u8> {
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, 60, 60);
            encoder.set_color(png::ColorType::Rgba);
            let mut writer = encoder.write_header().unwrap();
            writer.write_image_data(&vec![42; 60 * 60 * 4]).unwrap();
        }
        bytes
    }
    #[test]
    fn validates_and_roundtrips() -> io::Result<()> {
        let root = tempfile::tempdir()?;
        let dirs = DesktopDirs {
            data: root.path().join("data"),
            cache: root.path().join("cache"),
        };
        let cache = AvatarDiskCache::new(&dirs)?;
        let bytes = png();
        assert!(AvatarDiskCache::validate(&bytes));
        assert!(cache.save("key", &bytes)?);
        assert_eq!(cache.read("key")?, Some(bytes.clone()));
        let mut wrong = bytes.clone();
        wrong[19] = 59;
        assert!(!AvatarDiskCache::validate(&wrong));
        assert!(!AvatarDiskCache::validate(&bytes[..24]));
        assert!(!AvatarDiskCache::validate(&vec![0; MAX_FILE as usize + 1]));
        let path = cache.directory.join(storage_hash("key"));
        fs::File::open(&path)?.set_modified(SystemTime::now() - VALIDITY)?;
        assert!(cache.read("key")?.is_none());
        Ok(())
    }
    #[test]
    fn pruning_keeps_newest_under_cap() -> io::Result<()> {
        let root = tempfile::tempdir()?;
        let cache = AvatarDiskCache {
            directory: root.path().into(),
        };
        for i in 0..18 {
            let path = root.path().join(format!("{i:064}"));
            let file = private_open(&path)?;
            file.set_len(MAX_FILE)?;
            file.set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(i))?;
        }
        cache.prune()?;
        assert_eq!(fs::read_dir(root.path())?.count(), 16);
        assert!(!root.path().join(format!("{:064}", 0)).exists());
        Ok(())
    }
}
