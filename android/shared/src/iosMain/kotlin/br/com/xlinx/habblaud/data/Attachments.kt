package br.com.xlinx.habblaud.data

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UniformTypeIdentifiers.*

private class PickedDocument(val url: NSURL, private val scoped: Boolean) {
    fun close() { if (scoped) url.stopAccessingSecurityScopedResource() }
}
private object PickedDocuments {
    val lock = NSRecursiveLock()
    val pending = mutableMapOf<String, PickedDocument>()
    fun take(token: String): PickedDocument? {
        lock.lock()
        try { return pending.remove(token) } finally { lock.unlock() }
    }
}
/** Acquire the capability on the original picker URL, before crossing the asynchronous UI boundary. */
fun registerPickedDocument(url: NSURL): String {
    val document = PickedDocument(url, url.startAccessingSecurityScopedResource())
    val token = "zaplaud-picked:${NSUUID().UUIDString}"
    PickedDocuments.lock.lock()
    try { PickedDocuments.pending[token] = document } finally { PickedDocuments.lock.unlock() }
    return token
}
fun retainPickedDocuments(urls: List<NSURL>): List<String> {
    val selected = urls.distinctBy { it.absoluteString }
    checkAttachmentCount(0, selected.size)
    val retained = mutableListOf<String>()
    try {
        selected.forEach { retained += registerPickedDocument(it) }
        return retained
    } catch (error: Throwable) { discardPickedDocuments(retained); throw error }
}
/** Releases only selections not yet claimed by a copy; active copies own their capability. */
fun discardPickedDocuments(tokens: List<String>) {
    tokens.forEach { PickedDocuments.take(it)?.close() }
}

@OptIn(ExperimentalForeignApi::class)
actual class Attachments actual constructor() {
    init { initializeAccountStorage() }
    private fun mimeType(name: String): String =
        UTType.typeWithFilenameExtension(name.substringAfterLast('.', ""))?.preferredMIMEType
            ?: "application/octet-stream"
    private fun ownPath(attachment: Attachment): String? = NSURL.URLWithString(attachment.uri)?.path?.takeIf { it.startsWith("$privateDirectory/attachment-") && !it.removePrefix("$privateDirectory/").contains('/') }
    actual suspend fun select(uris: List<String>, existing: List<Attachment>): List<Attachment> = try { withContext(Dispatchers.Default) {
        val selected = uris.distinct().filter { uri -> existing.none { it.uri == uri } }
        checkAttachmentCount(existing.size, selected.size)
        val acquired = mutableListOf<Attachment>()
        try {
            for (value in selected) {
                currentCoroutineContext().ensureActive()
                val path = "$privateDirectory/attachment-${NSUUID().UUIDString}"
                val picked = PickedDocuments.take(value)
                val url = picked?.url ?: if (value.startsWith("zaplaud-picked:")) error("attachment selection expired")
                    else NSURL.URLWithString(value) ?: error("attachment unavailable")
                val scoped = picked == null && url.startAccessingSecurityScopedResource()
                try {
                    val name = url.lastPathComponent?.takeIf { it.isNotBlank() } ?: error("invalid attachment")
                    val attributes = url.path?.let { NSFileManager.defaultManager.attributesOfItemAtPath(it, null) }
                    val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
                    require(size == null || size in 1..MAX_ATTACHMENT_SIZE) { "invalid attachment" }
                    check(NSFileManager.defaultManager.createFileAtPath(path, null, mapOf(NSFileProtectionKey to NSFileProtectionComplete))) { "Could not create protected attachment" }
                    val input = NSInputStream.inputStreamWithURL(url) ?: error("attachment unavailable")
                    val output = NSOutputStream.outputStreamToFileAtPath(path, false) ?: error("attachment unavailable")
                    input.open(); output.open()
                    var count = 0L
                    try {
                        val buffer = ByteArray(8192)
                        buffer.usePinned { pinned ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(pinned.addressOf(0).reinterpret(), buffer.size.toULong())
                                check(read >= 0) { "attachment unavailable: ${input.streamError}" }
                                if (read == 0L) break
                                count += read
                                check(count <= MAX_ATTACHMENT_SIZE && (size == null || count <= size)) { "attachment too large" }
                                var offset = 0L
                                while (offset < read) {
                                    val written = output.write(pinned.addressOf(offset.toInt()).reinterpret(), (read - offset).toULong())
                                    check(written > 0) { "Could not copy attachment: ${output.streamError}" }; offset += written
                                }
                            }
                        }
                        check(count > 0 && (size == null || count == size)) { "invalid attachment size" }
                    } finally { input.close(); output.close() }
                    check(NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), path, null)) { "Could not protect attachment" }
                    val mime = mimeType(name)
                    acquired += Attachment(NSURL.fileURLWithPath(path).absoluteString!!, name, mime, count, true)
                } catch (error: Throwable) { NSFileManager.defaultManager.removeItemAtPath(path, null); throw error }
                finally { if (picked != null) picked.close() else if (scoped) url.stopAccessingSecurityScopedResource() }
            }
            existing + acquired
        } catch (error: Throwable) { acquired.forEach(::release); throw error }
    } } finally { discardPickedDocuments(uris) }
    actual fun available(attachment: Attachment): Boolean = ownPath(attachment)?.let { NSFileManager.defaultManager.isReadableFileAtPath(it) } == true
    actual fun persisted(attachment: Attachment): Boolean = attachment.persistent && available(attachment)
    actual fun release(attachment: Attachment) { ownPath(attachment)?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) } }
    actual fun body(attachment: Attachment, progress: (Long) -> Unit): AttachmentBody = AttachmentBody(attachment.size, {
        val path = ownPath(attachment) ?: error("attachment unavailable")
        val input = NSInputStream.inputStreamWithFileAtPath(path) ?: error("attachment unavailable")
        input.open()
        object : AttachmentSource {
            override fun read(buffer: ByteArray): Int = buffer.usePinned { val count = input.read(it.addressOf(0).reinterpret(), buffer.size.toULong()); check(count >= 0) { "attachment unavailable: ${input.streamError}" }; if (count == 0L) -1 else count.toInt() }
            override fun close() = input.close()
        }
    }, progress)
}
