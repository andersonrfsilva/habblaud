use std::{
    io,
    os::unix::{
        fs::PermissionsExt,
        net::{UnixListener, UnixStream},
    },
    path::{Path, PathBuf},
};

pub struct Instance {
    pub listener: UnixListener,
    path: PathBuf,
}
impl Instance {
    pub fn acquire(path: &Path) -> io::Result<Option<Self>> {
        // Serializa apenas a aquisição e remoção de sockets obsoletos.
        let lock = std::fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(path.with_extension("lock"))?;
        lock.lock()?;
        match UnixStream::connect(path) {
            Ok(_) => return Ok(None),
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::NotFound | io::ErrorKind::ConnectionRefused
                ) => {}
            Err(error) => return Err(error),
        }
        match UnixListener::bind(path) {
            Ok(listener) => Ok(Some(Self {
                listener,
                path: path.into(),
            })),
            Err(error) if error.kind() == io::ErrorKind::AddrInUse => {
                // Uma corrida com outro processo nunca remove um socket que já atende.
                if UnixStream::connect(path).is_ok() {
                    return Ok(None);
                }
                let metadata = std::fs::symlink_metadata(path)?;
                use std::os::unix::fs::FileTypeExt;
                if !metadata.file_type().is_socket() {
                    return Err(error);
                }
                std::fs::remove_file(path)?;
                Ok(Some(Self {
                    listener: UnixListener::bind(path)?,
                    path: path.into(),
                }))
            }
            Err(error) => Err(error),
        }
    }
    pub fn runtime_path() -> io::Result<PathBuf> {
        let base = std::env::var_os("XDG_RUNTIME_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                std::env::temp_dir().join(format!(
                    "zaplaud-{}",
                    std::env::var("USER").unwrap_or_else(|_| "desktop".into())
                ))
            });
        let directory = base.join("zaplaud");
        std::fs::create_dir_all(&directory)?;
        std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))?;
        Ok(directory.join("instance.sock"))
    }
}
impl Drop for Instance {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn second_launch_and_stale_socket() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("socket");
        let first = Instance::acquire(&path)?.ok_or_else(|| io::Error::other("first"))?;
        assert!(Instance::acquire(&path)?.is_none());
        first.listener.accept()?;
        drop(first);
        let stale = UnixListener::bind(&path)?;
        drop(stale);
        assert!(Instance::acquire(&path)?.is_some());
        Ok(())
    }
    #[test]
    fn never_remove_regular_file() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("socket");
        std::fs::write(&path, b"keep")?;
        assert!(Instance::acquire(&path).is_err());
        assert_eq!(std::fs::read(path)?, b"keep");
        Ok(())
    }
}
