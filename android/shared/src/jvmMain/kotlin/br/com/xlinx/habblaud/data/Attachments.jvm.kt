package br.com.xlinx.habblaud.data

import java.io.FileInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

actual class Attachments actual constructor() {
    actual suspend fun select(uris: List<String>, existing: List<Attachment>): List<Attachment> {
        val added = uris.distinct().filter { uri -> existing.none { it.uri == uri } }
        checkAttachmentCount(existing.size, added.size)
        return existing + added.map { uri ->
            val path = Path.of(URI(uri))
            check(Files.isRegularFile(path) && Files.isReadable(path)) { "invalid attachment" }
            val size = Files.size(path)
            check(size in 1..MAX_ATTACHMENT_SIZE) { "invalid attachment" }
            Attachment(uri, path.fileName.toString(), Files.probeContentType(path) ?: "application/octet-stream", size, persistent = true)
        }
    }
    actual fun available(attachment: Attachment): Boolean = runCatching {
        val path = Path.of(URI(attachment.uri))
        Files.isRegularFile(path) && Files.isReadable(path) && Files.size(path) == attachment.size
    }.getOrDefault(false)
    actual fun persisted(attachment: Attachment): Boolean = attachment.persistent && available(attachment)
    actual fun release(attachment: Attachment) {
        // O arquivo pertence ao usuário; não deve ser removido pelo aplicativo.
    }
    actual fun body(attachment: Attachment, progress: (Long) -> Unit): AttachmentBody = AttachmentBody(attachment.size, {
        val input = FileInputStream(Path.of(URI(attachment.uri)).toFile())
        object : AttachmentSource {
            override fun read(buffer: ByteArray): Int = input.read(buffer)
            override fun close() = input.close()
        }
    }, progress)
}
