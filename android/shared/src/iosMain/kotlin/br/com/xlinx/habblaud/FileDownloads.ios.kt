@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package br.com.xlinx.habblaud

import androidx.compose.runtime.Composable
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.*

@Composable
actual fun DownloadPermissionGate(content: @Composable (allowed: Boolean, request: () -> Unit) -> Unit) = content(true, {})
actual val canShowDownloadFolder: Boolean = false

actual suspend fun createDownload(name: String): DownloadTarget {
    var target: IosDownloadTarget? = null
    try {
        return withContext(Dispatchers.IO) {
            val directory = "${NSHomeDirectory()}/Documents/Downloads"
            check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, mapOf(NSFileProtectionKey to NSFileProtectionComplete), null)) { "Não foi possível criar a pasta Downloads." }
            IosDownloadTarget(directory, sanitizedDownloadName(name)).also { target = it }
        }
    } catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { target?.abort() }; throw error }
}

private class IosDownloadTarget(private val directory: String, private val name: String) : DownloadTarget {
    private val temporary = "$directory/.zaplaud-${NSUUID().UUIDString}.part"
    private val output: NSOutputStream
    private var finished = false
    init {
        check(NSFileManager.defaultManager.createFileAtPath(temporary, null, mapOf(NSFileProtectionKey to NSFileProtectionComplete))) { "Não foi possível criar o download." }
        try {
            output = NSOutputStream.outputStreamToFileAtPath(temporary, false) ?: error("Não foi possível abrir o download.")
            output.open()
            check(output.streamError == null) { "Não foi possível abrir o download: ${output.streamError}" }
        } catch (error: Throwable) { NSFileManager.defaultManager.removeItemAtPath(temporary, null); throw error }
    }
    override suspend fun write(bytes: ByteArray, count: Int) {
        try {
            withContext(Dispatchers.IO) {
                check(!finished)
                require(count in 0..bytes.size)
                if (count > 0) bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < count) {
                        currentCoroutineContext().ensureActive()
                        val written = output.write(pinned.addressOf(offset).reinterpret(), (count - offset).toULong())
                        check(written > 0) { "Não foi possível salvar o download: ${output.streamError}" }
                        offset += written.toInt()
                    }
                }
            }
        } catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { abort() }; throw error }
    }
    override suspend fun complete(): SavedDownload {
        var published: String? = null
        try {
            return withContext(Dispatchers.IO) {
                check(!finished)
                check(output.streamError == null) { "Não foi possível salvar o download: ${output.streamError}" }
                output.close()
                var index = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val destination = "$directory/${numberedDownloadName(name, index++)}"
                    // O gerenciador recusa destinos existentes; nunca substitui um download anterior.
                    if (NSFileManager.defaultManager.moveItemAtPath(temporary, destination, null)) {
                        published = destination
                        break
                    }
                    check(NSFileManager.defaultManager.fileExistsAtPath(destination)) { "Não foi possível concluir o download." }
                }
                finished = true
                val path = requireNotNull(published)
                SavedDownload(NSURL.fileURLWithPath(path).absoluteString!!, path.substringAfterLast('/'))
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                published?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
                abort()
            }
            throw error
        }
    }
    override fun abort() {
        if (finished) return
        finished = true
        output.close()
        NSFileManager.defaultManager.removeItemAtPath(temporary, null)
    }
}

actual fun openDownload(file: SavedDownload) {
    check(NSThread.isMainThread) { "Abra o download na thread principal." }
    val url = NSURL.URLWithString(file.location) ?: error("Download indisponível.")
    var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController ?: error("Nenhuma janela disponível.")
    while (presenter.presentedViewController != null) presenter = requireNotNull(presenter.presentedViewController)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    sheet.popoverPresentationController?.let { it.sourceView = presenter.view; it.sourceRect = presenter.view.bounds }
    presenter.presentViewController(sheet, animated = true, completion = null)
}
actual fun showDownloadFolder(file: SavedDownload) {
    error("A pasta Downloads não pode ser aberta nesta plataforma.")
}
