package br.com.xlinx.habblaud.data
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
actual class Attachments(private val resolver: ContentResolver) {
    actual constructor() : this(storageContext.contentResolver)
    actual suspend fun select(uris: List<String>, existing: List<Attachment>): List<Attachment> = withContext(Dispatchers.IO) {
        val selected = uris.map(Uri::parse).distinct().filter { uri -> existing.none { it.uri == uri.toString() } }
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
    actual fun available(attachment: Attachment): Boolean = try {
        resolver.openInputStream(Uri.parse(attachment.uri))?.use { true } ?: false
    } catch (_: Exception) { false }
    actual fun persisted(attachment: Attachment): Boolean = attachment.persistent && resolver.persistedUriPermissions.any { it.uri.toString() == attachment.uri && it.isReadPermission }
    actual fun release(attachment: Attachment) {
        if (attachment.persistent) runCatching { resolver.releasePersistableUriPermission(Uri.parse(attachment.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    actual fun body(attachment: Attachment, progress: (Long) -> Unit) = AttachmentBody(attachment.size, { val stream = resolver.openInputStream(Uri.parse(attachment.uri)) ?: throw IOException("attachment unavailable"); object : AttachmentSource { override fun read(buffer: ByteArray) = stream.read(buffer); override fun close() = stream.close() } }, progress)
}
