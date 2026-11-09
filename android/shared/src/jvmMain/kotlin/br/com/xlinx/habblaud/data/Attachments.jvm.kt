package br.com.xlinx.habblaud.data
actual class Attachments actual constructor() {
    actual suspend fun select(uris: List<String>, existing: List<Attachment>): List<Attachment> = TODO()
    actual fun available(attachment: Attachment): Boolean = TODO()
    actual fun persisted(attachment: Attachment): Boolean = TODO()
    actual fun release(attachment: Attachment) { TODO() }
    actual fun body(attachment: Attachment, progress: (Long) -> Unit): AttachmentBody = TODO()
}
