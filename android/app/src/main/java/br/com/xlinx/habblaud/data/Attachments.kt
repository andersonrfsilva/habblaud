package br.com.xlinx.habblaud.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

const val MAX_ATTACHMENT_SIZE = 15L * 1024 * 1024
const val MAX_ATTACHMENTS = 5
fun checkAttachmentCount(current: Int, added: Int) { require(current + added <= MAX_ATTACHMENTS) { "too many attachments" } }

data class Attachment(val uri: String, val name: String, val mime: String, val size: Long?, val persistent: Boolean)

fun encodeAttachmentName(name: String): String = buildString {
    for (b in name.toByteArray(Charsets.UTF_8)) {
        val n = b.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-_.!~*'()") append(n.toChar())
        else append("%%%02X".format(n))
    }
}

class AttachmentBody(private val size: Long?, private val open: () -> InputStream, private val progress: (Long) -> Unit = {}) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = size ?: -1L
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        if (size != null && (size < 1 || size > MAX_ATTACHMENT_SIZE)) throw IOException("invalid attachment size")
        var count = 0L
        open().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > MAX_ATTACHMENT_SIZE || (size != null && count > size)) throw IOException("attachment too large")
                sink.write(buffer, 0, read)
                progress(count)
            }
        }
        if (count == 0L || (size != null && count != size)) throw IOException("invalid attachment size")
    }
}

class Attachments(private val resolver: ContentResolver) {
    suspend fun select(uris: List<Uri>, existing: List<Attachment>): List<Attachment> = withContext(Dispatchers.IO) {
        val selected = uris.distinct().filter { uri -> existing.none { it.uri == uri.toString() } }
        checkAttachmentCount(existing.size, selected.size)
        val acquired = mutableListOf<Attachment>()
        val newlyGranted = mutableListOf<Uri>()
        try {
            selected.forEach { uri ->
                var name: String? = null
                var size: Long? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                        if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
                    }
                }
                if (name.isNullOrBlank() || size?.let { it < 1 || it > MAX_ATTACHMENT_SIZE } == true) throw IOException("invalid attachment")
                resolver.openInputStream(uri)?.close() ?: throw IOException("attachment unavailable")
                val alreadyPersisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                val persistent = alreadyPersisted || try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true } catch (_: SecurityException) { false }
                if (persistent && !alreadyPersisted) newlyGranted.add(uri)
                acquired += Attachment(uri.toString(), name!!, resolver.getType(uri) ?: "application/octet-stream", size, persistent)
            }
            existing + acquired
        } catch (e: Exception) {
            newlyGranted.forEach { uri -> runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            throw e
        }
    }
    fun available(attachment: Attachment): Boolean = try {
        resolver.openInputStream(Uri.parse(attachment.uri))?.use { true } ?: false
    } catch (_: Exception) { false }
    fun persisted(attachment: Attachment): Boolean = attachment.persistent && resolver.persistedUriPermissions.any { it.uri.toString() == attachment.uri && it.isReadPermission }
    fun release(attachment: Attachment) {
        if (attachment.persistent) runCatching { resolver.releasePersistableUriPermission(Uri.parse(attachment.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun body(attachment: Attachment, progress: (Long) -> Unit) = AttachmentBody(attachment.size, { resolver.openInputStream(Uri.parse(attachment.uri)) ?: throw IOException("attachment unavailable") }, progress)
}
