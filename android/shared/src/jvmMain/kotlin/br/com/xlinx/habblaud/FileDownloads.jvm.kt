package br.com.xlinx.habblaud

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
actual fun DownloadPermissionGate(content: @Composable (allowed: Boolean, request: () -> Unit) -> Unit) = content(true, {})
actual val canShowDownloadFolder: Boolean = true

internal fun downloadDirectory(): Path {
    val home = System.getProperty("user.home")
    val config = Paths.get(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "user-dirs.dirs")
    if (Files.isRegularFile(config)) {
        val value = Files.readAllLines(config).firstOrNull { it.trimStart().startsWith("XDG_DOWNLOAD_DIR=") }
            ?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            ?.replace("\${HOME}", home)?.replace("\$HOME", home)
        if (!value.isNullOrBlank() && Paths.get(value).isAbsolute) return Paths.get(value)
    }
    return Paths.get(home, "Downloads")
}

actual suspend fun createDownload(name: String): DownloadTarget {
    var target: JvmDownloadTarget? = null
    try {
        return withContext(Dispatchers.IO) {
            val directory = Files.createDirectories(downloadDirectory())
            JvmDownloadTarget(directory, sanitizedDownloadName(name)).also { target = it }
        }
    } catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { target?.abort() }; throw error }
}

internal class JvmDownloadTarget(private val directory: Path, private val name: String) : DownloadTarget {
    private val temporary = Files.createTempFile(directory, ".zaplaud-", ".part")
    private val output = try { Files.newOutputStream(temporary) } catch (error: Throwable) { Files.deleteIfExists(temporary); throw error }
    private var finished = false
    override suspend fun write(bytes: ByteArray, count: Int) {
        try { withContext(Dispatchers.IO) { check(!finished); require(count in 0..bytes.size); output.write(bytes, 0, count) } }
        catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { abort() }; throw error }
    }
    override suspend fun complete(): SavedDownload {
        var published: Path? = null
        try {
            return withContext(Dispatchers.IO) {
                check(!finished)
                output.close()
                var index = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val destination = directory.resolve(numberedDownloadName(name, index++))
                    try {
                        // O hard link publica o arquivo inteiro atomicamente, sem substituir outro download.
                        Files.createLink(destination, temporary)
                        published = destination
                        break
                    } catch (_: FileAlreadyExistsException) { }
                }
                Files.delete(temporary)
                finished = true
                val path = requireNotNull(published)
                SavedDownload(path.toString(), path.fileName.toString())
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                published?.let { Files.deleteIfExists(it) }
                abort()
            }
            throw error
        }
    }
    override fun abort() {
        if (finished) return
        finished = true
        try { output.close() } finally { Files.deleteIfExists(temporary) }
    }
}

actual fun openDownload(file: SavedDownload) { Desktop.getDesktop().open(Paths.get(file.location).toFile()) }
actual fun showDownloadFolder(file: SavedDownload) {
    val desktop = Desktop.getDesktop()
    val path = Paths.get(file.location).toFile()
    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) desktop.browseFileDirectory(path)
    else desktop.open(path.parentFile)
}
