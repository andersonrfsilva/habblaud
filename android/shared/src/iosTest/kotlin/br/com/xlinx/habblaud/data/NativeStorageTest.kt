package br.com.xlinx.habblaud.data

import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.*
import kotlin.test.*

/** Runs in the native test runner's sandbox, never against a deployed app container. */
@OptIn(ExperimentalForeignApi::class)
class NativeStorageTest {
    @Test
    fun startupCleanupRemovesOrphansButRetainsDraftAttachments() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        store.setConnection(origin)
        val key = SessionKey(origin, "claude", "test", "orphan-test")
        val retainedPath = "$privateDirectory/attachment-${NSUUID().UUIDString}"
        val orphanPath = "$privateDirectory/attachment-${NSUUID().UUIDString}"
        protectedWrite(retainedPath, "draft")
        protectedWrite(orphanPath, "orphan")
        val retained = Attachment(NSURL.fileURLWithPath(retainedPath).absoluteString!!, "draft.txt", "text/plain", 5, true)
        try {
            store.updateMessage(key) { it.copy(draft = MessageDraft(attachments = listOf(retained))) }
            val references = store.message(key).draft.attachments.mapTo(mutableSetOf()) { it.uri }
            removeOrphanAttachments(references)
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(retainedPath))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(orphanPath))
            assertTrue(store.usesAttachment(retained.uri))
        } finally { store.clear() }
    }

    @Test
    fun keychainPersistsOnlySelectedOriginAndClearRemovesSession() {
        val origin = "https://native-storage.invalid"
        val store = SessionCookieStore(origin)
        store.clear()
        try {
            store.saveFromResponse("$origin/login", listOf("habblaud_remote=native-test; Path=/; Secure; HttpOnly; Max-Age=3600", "ignored=value; Path=/"))
            assertTrue(checkNotNull(SecureSessionStorage().read()).contains("habblaud_remote=native-test"))
            val relaunched = SessionCookieStore(origin)
            assertEquals("habblaud_remote=native-test", relaunched.requestHeader("$origin/api/sessions"))
            assertNull(relaunched.requestHeader("https://other.invalid/api/sessions"))
            assertNull(relaunched.requestHeader("http://native-storage.invalid/api/sessions"))
            relaunched.saveFromResponse("https://other.invalid/login", listOf("habblaud_remote=wrong; Path=/"))
            assertEquals("habblaud_remote=native-test", relaunched.requestHeader("$origin/api/sessions"))
            relaunched.clear()
            assertNull(SecureSessionStorage().read())
            assertNull(SessionCookieStore(origin).requestHeader("$origin/api/sessions"))
        } finally { store.clear() }
    }

    @Test
    fun delayedCookiesCannotRestoreLogoutOrClearFreshLogin() {
        val origin = "https://native-storage.invalid"
        val first = SessionCookieStore(origin)
        val second = SessionCookieStore(origin)
        first.clear()
        try {
            val old = first.generation()
            second.clear()
            first.saveFromResponse("$origin/login", listOf("habblaud_remote=stale; Path=/; Secure"), old)
            assertNull(second.requestHeader("$origin/api/sessions"))
            second.saveFromResponse("$origin/login", listOf("habblaud_remote=fresh; Path=/; Secure"), second.generation())
            assertFalse(first.clear(old))
            assertEquals("habblaud_remote=fresh", first.requestHeader("$origin/api/sessions"))
        } finally { first.clear() }
    }

    @Test
    fun logoutRejectsDelayedCacheAndSeenWrites() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        val key = SessionKey(origin, "claude", "test", "native-${NSUUID().UUIDString}")
        store.setConnection(origin)
        val epoch = store.conversationEpoch()
        val write = store.conversationWrite(epoch)
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.beforeConversationWrite = { reached.complete(Unit); resume.await() }
        try {
            val pending = async { store.setConversation(key, listOf(TerminalEntry("old", "assistant", "old account")), false, write) }
            reached.await()
            store.clear()
            store.setConnection(origin)
            resume.complete(Unit)
            pending.await()
            store.setSeenReplies(mapOf(store.seenId(key) to 123L), epoch)
            assertNull(store.conversation(key))
            assertTrue(store.seenReplies().isEmpty())
            val current = store.conversationWrite(store.conversationEpoch())
            store.beforeConversationWrite = {}
            store.setConversation(key, listOf(TerminalEntry("new", "assistant", "new account")), false, current)
            assertEquals("new", LocalStateStore().conversation(key)?.entries?.single()?.id)
        } finally {
            resume.complete(Unit)
            store.beforeConversationWrite = {}
            store.clear()
        }
    }

    @Test
    fun documentCopyRejectsEmptyOversizeAndSixthSelection() = runBlocking {
        val directory = NSTemporaryDirectory() + "zaplaud-native-${NSUUID().UUIDString}"
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val attachments = Attachments()
        var copied = emptyList<Attachment>()
        try {
            val source = "$directory/file.txt"
            protectedWrite(source, "native bytes")
            val tokens = retainPickedDocuments(listOf(NSURL.fileURLWithPath(source)))
            copied = attachments.select(tokens, emptyList())
            discardPickedDocuments(tokens)
            val selected = copied.single()
            assertEquals(12L, selected.size)
            assertEquals("text/plain", selected.mime)
            assertTrue(attachments.persisted(selected))
            val received = mutableListOf<Byte>()
            attachments.body(selected) {}.writeTo { buffer, count -> repeat(count) { received.add(buffer[it]) } }
            assertEquals("native bytes", received.toByteArray().decodeToString())
            val empty = "$directory/empty.txt"
            protectedWrite(empty, "")
            try { attachments.select(listOf(NSURL.fileURLWithPath(empty).absoluteString!!), emptyList()); fail("Empty document accepted") } catch (_: IllegalArgumentException) { }
            val large = "$directory/large.bin"
            val output = NSOutputStream.outputStreamToFileAtPath(large, false)!!
            output.open()
            try {
                val bytes = ByteArray(8192)
                bytes.usePinned { pinned ->
                    repeat((MAX_ATTACHMENT_SIZE / bytes.size).toInt() + 1) {
                        var written = 0
                        while (written < bytes.size) {
                            val count = output.write(pinned.addressOf(written).reinterpret(), (bytes.size - written).toULong())
                            check(count > 0); written += count.toInt()
                        }
                    }
                }
            } finally { output.close() }
            try { attachments.select(listOf(NSURL.fileURLWithPath(large).absoluteString!!), emptyList()); fail("Oversize document accepted") } catch (_: IllegalArgumentException) { }
            try { attachments.select(listOf(NSURL.fileURLWithPath(source).absoluteString!!), List(5) { selected.copy(uri = "existing-$it") }); fail("Sixth attachment accepted") } catch (_: IllegalArgumentException) { }
        } finally {
            copied.forEach(attachments::release)
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }

    @Test
    fun uploadCancellationClosesSourceAndNeverReadsWholeFile() = runBlocking {
        var reads = 0
        var closed = false
        val writing = CompletableDeferred<Unit>()
        val body = AttachmentBody(MAX_ATTACHMENT_SIZE, {
            object : AttachmentSource {
                override fun read(buffer: ByteArray): Int { assertEquals(8192, buffer.size); reads++; return buffer.size }
                override fun close() { closed = true }
            }
        })
        val job = launch {
            body.writeTo { _, _ -> writing.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        writing.await()
        job.cancelAndJoin()
        assertEquals(1, reads)
        assertTrue(closed)
    }
}
