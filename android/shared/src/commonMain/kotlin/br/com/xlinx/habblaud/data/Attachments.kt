package br.com.xlinx.habblaud.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val MAX_ATTACHMENT_SIZE = 15L * 1024 * 1024
const val MAX_ATTACHMENTS = 5
fun checkAttachmentCount(current: Int, added: Int) { require(current >= 0 && added >= 0 && current + added <= MAX_ATTACHMENTS) { "too many attachments" } }
data class Attachment(val uri: String, val name: String, val mime: String, val size: Long?, val persistent: Boolean)
fun encodeAttachmentName(name: String): String = buildString {
    for (b in name.encodeToByteArray()) {
        val n = b.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n.toChar() in "-_.!~*'()") append(n.toChar())
        else { append('%'); append("0123456789ABCDEF"[n shr 4]); append("0123456789ABCDEF"[n and 15]) }
    }
}
interface AttachmentSource { fun read(buffer: ByteArray): Int; fun close() }
class AttachmentBody(val size: Long?, private val open: () -> AttachmentSource, private val progress: (Long) -> Unit = {}) {
    suspend fun writeTo(write: suspend (ByteArray, Int) -> Unit) {
        require(size == null || size in 1..MAX_ATTACHMENT_SIZE) { "invalid attachment size" }
        var count = 0L
        val input = open()
        try {
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                check(count <= MAX_ATTACHMENT_SIZE && (size == null || count <= size)) { "attachment too large" }
                write(buffer, read); progress(count)
            }
            check(count > 0 && (size == null || count == size)) { "invalid attachment size" }
        } finally { input.close() }
    }
}
expect class Attachments() {
    suspend fun select(uris: List<String>, existing: List<Attachment>): List<Attachment>
    fun available(attachment: Attachment): Boolean
    fun persisted(attachment: Attachment): Boolean
    fun release(attachment: Attachment)
    fun body(attachment: Attachment, progress: (Long) -> Unit): AttachmentBody
}
