//! Diretórios privados do desktop, compatíveis com DesktopDirs.kt.

use std::{env, ffi::OsString, fs, io, path::PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DesktopDirs {
    pub data: PathBuf,
    pub cache: PathBuf,
}

impl DesktopDirs {
    /// Resolve e cria os diretórios. ZAPLAUD_DATA_DIR contém os filhos data e cache.
    pub fn resolve() -> io::Result<Self> {
        let directories = resolve_with(cfg!(target_os = "macos"), |key| env::var_os(key))?;
        create_private(&directories.data)?;
        create_private(&directories.cache)?;
        Ok(directories)
    }
}

fn resolve_with(macos: bool, get: impl Fn(&str) -> Option<OsString>) -> io::Result<DesktopDirs> {
    if let Some(root) = get("ZAPLAUD_DATA_DIR") {
        let root = PathBuf::from(root);
        return Ok(DesktopDirs {
            data: root.join("data"),
            cache: root.join("cache"),
        });
    }
    let home = get("HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "HOME não definido"))?;
    if macos {
        return Ok(DesktopDirs {
            data: home.join("Library/Application Support/Zaplaud"),
            cache: home.join("Library/Caches/Zaplaud"),
        });
    }
    let base = |variable, fallback| {
        get(variable)
            .filter(|value| !value.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(fallback))
            .join("zaplaud")
    };
    Ok(DesktopDirs {
        data: base("XDG_DATA_HOME", ".local/share"),
        cache: base("XDG_CACHE_HOME", ".cache"),
    })
}

fn create_private(path: &std::path::Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
        fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(path)?;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))
    }
    #[cfg(not(unix))]
    fs::create_dir_all(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn resolve(macos: bool, values: &[(&str, &str)]) -> DesktopDirs {
        resolve_with(macos, |key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, value)| OsString::from(value))
        })
        .expect("diretórios de teste válidos")
    }

    #[test]
    fn explicit_override_precedes_platform_and_home() {
        assert_eq!(
            resolve(true, &[("ZAPLAUD_DATA_DIR", "/test")]),
            DesktopDirs {
                data: "/test/data".into(),
                cache: "/test/cache".into(),
            }
        );
    }

    #[test]
    fn linux_uses_xdg_and_ignores_empty_values() {
        let paths = resolve(
            false,
            &[
                ("HOME", "/home/test"),
                ("XDG_DATA_HOME", "/data"),
                ("XDG_CACHE_HOME", ""),
            ],
        );
        assert_eq!(paths.data, PathBuf::from("/data/zaplaud"));
        assert_eq!(paths.cache, PathBuf::from("/home/test/.cache/zaplaud"));
    }

    #[test]
    fn macos_matches_desktop_dirs() {
        let paths = resolve(
            true,
            &[("HOME", "/Users/test"), ("XDG_DATA_HOME", "/ignored")],
        );
        assert_eq!(
            paths.data,
            PathBuf::from("/Users/test/Library/Application Support/Zaplaud")
        );
        assert_eq!(
            paths.cache,
            PathBuf::from("/Users/test/Library/Caches/Zaplaud")
        );
    }

    #[test]
    fn missing_home_is_an_error_without_override() {
        assert_eq!(
            resolve_with(false, |_| None).unwrap_err().kind(),
            io::ErrorKind::NotFound
        );
    }

    #[test]
    #[cfg(unix)]
    fn directories_are_private_even_when_already_present() -> io::Result<()> {
        use std::os::unix::fs::PermissionsExt;
        let path = env::temp_dir().join(format!("zaplaud-store-dirs-{}", std::process::id()));
        fs::create_dir_all(&path)?;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755))?;
        create_private(&path)?;
        assert_eq!(fs::metadata(&path)?.permissions().mode() & 0o777, 0o700);
        fs::remove_dir(path)
    }
}
