package br.com.xlinx.habblaud.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.prefs.Preferences

private fun desktopDirectory(cache: Boolean): Path {
    val override = System.getProperty("zaplaud.dataDir")
    val home = System.getProperty("user.home")
    val path = when {
        override != null -> Path.of(override, if (cache) "cache" else "data")
        System.getProperty("os.name").startsWith("Mac") -> Path.of(home, "Library", if (cache) "Caches" else "Application Support", "Zaplaud")
        else -> Path.of(System.getenv(if (cache) "XDG_CACHE_HOME" else "XDG_DATA_HOME")?.takeIf { it.isNotEmpty() }
            ?: if (cache) "$home/.cache" else "$home/.local/share", "zaplaud")
    }
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    } else Files.createDirectories(path)
    return path
}
internal val dataDir: Path get() = desktopDirectory(false)
internal val cacheDir: Path get() = desktopDirectory(true)
internal val settingsNode: Preferences get() = Preferences.userRoot().node(System.getProperty("zaplaud.prefsNode") ?: "br/com/xlinx/zaplaud")
