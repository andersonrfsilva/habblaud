use std::{
    io,
    os::unix::{
        fs::{DirBuilderExt, MetadataExt, PermissionsExt},
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
        let uid = rustix::process::geteuid().as_raw();
        let base = std::env::var_os("XDG_RUNTIME_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| std::env::temp_dir().join(format!("zaplaud-{uid}")));
        private_directory(&base, uid)?;
        let directory = base.join("zaplaud");
        private_directory(&directory, uid)?;
        Ok(directory.join("instance.sock"))
    }
}
impl Drop for Instance {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn private_directory(path: &Path, uid: u32) -> io::Result<()> {
    match std::fs::DirBuilder::new().mode(0o700).create(path) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {}
        Err(error) => return Err(error),
    }
    let metadata = std::fs::symlink_metadata(path)?;
    if !metadata.file_type().is_dir()
        || metadata.uid() != uid
        || metadata.permissions().mode() & 0o077 != 0
    {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "diretório de socket inseguro",
        ));
    }
    Ok(())
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
    #[test]
    fn socket_directory_is_private_and_owned_by_current_user() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let path = dir.path().join("private");
        let uid = rustix::process::geteuid().as_raw();
        private_directory(&path, uid)?;
        let metadata = std::fs::symlink_metadata(&path)?;
        assert_eq!(metadata.uid(), uid);
        assert_eq!(metadata.permissions().mode() & 0o777, 0o700);
        assert!(private_directory(&path, uid.wrapping_add(1)).is_err());
        Ok(())
    }

    #[test]
    fn socket_directory_rejects_symlinks_files_and_public_directories() -> io::Result<()> {
        let dir = tempfile::tempdir()?;
        let uid = rustix::process::geteuid().as_raw();
        let link = dir.path().join("link");
        std::os::unix::fs::symlink(dir.path(), &link)?;
        assert!(private_directory(&link, uid).is_err());
        let file = dir.path().join("file");
        std::fs::write(&file, b"keep")?;
        assert!(private_directory(&file, uid).is_err());
        let public = dir.path().join("public");
        std::fs::create_dir(&public)?;
        std::fs::set_permissions(&public, std::fs::Permissions::from_mode(0o755))?;
        assert!(private_directory(&public, uid).is_err());
        assert_eq!(
            std::fs::metadata(public)?.permissions().mode() & 0o777,
            0o755
        );
        Ok(())
    }
}
