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
    fun storageHashPreservesSha256KeysIncludingEmptyInput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", storageHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", storageHash("abc"))
    }

    @Test
    fun migratedSeenRepliesKeepOriginalSessionHash() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        val key = SessionKey(origin, "claude", "test", "legacy-seen")
        val hash = storageHash(key.serialize())
        store.clear()
        try {
            protectedWrite("$privateDirectory/local_state.json", """{"origin":"$origin","seen_replies":{"$hash":123}}""")
            assertEquals(listOf(origin), store.servers())
            assertEquals(hash, store.seenId(key))
            assertEquals(123L, store.seenReplies(origin)[store.seenId(key)])
        } finally { store.clear() }
    }

    @Test
    fun startupCleanupRemovesOrphansButRetainsDraftAttachments() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        store.addServer(origin)
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
    fun removingServerRejectsDelayedCacheAndSeenWrites() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        val key = SessionKey(origin, "claude", "test", "native-${NSUUID().UUIDString}")
        store.addServer(origin)
        val epoch = store.conversationEpoch(origin)
        val write = store.conversationWrite(origin, epoch)
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.beforeConversationWrite = { reached.complete(Unit); resume.await() }
        try {
            val pending = async { store.setConversation(key, listOf(TerminalEntry("old", "assistant", "old account")), false, write) }
            reached.await()
            store.removeServer(origin)
            store.addServer(origin)
            resume.complete(Unit)
            pending.await()
            store.setSeenReplies(origin, mapOf(store.seenId(key) to 123L), epoch)
            store.setLastSession(key, write)
            assertNull(store.conversation(key))
            assertNull(store.lastSession())
            assertTrue(store.seenReplies(origin).isEmpty())
            val current = store.conversationWrite(origin, store.conversationEpoch(origin))
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
    fun addingServerPreservesPendingWritesFromExistingServer() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        val other = "https://other-native-storage.invalid"
        val key = SessionKey(origin, "claude", "test", "pending")
        store.addServer(origin)
        val epoch = store.conversationEpoch(origin)
        val write = store.conversationWrite(origin, epoch)
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        store.beforeConversationWrite = { reached.complete(Unit); resume.await() }
        try {
            val pending = async { store.setConversation(key, listOf(TerminalEntry("kept", "assistant", "kept")), false, write) }
            reached.await()
            store.addServer(other)
            store.addServer(origin)
            resume.complete(Unit)
            pending.await()
            store.setSeenReplies(origin, mapOf(store.seenId(key) to 123L), epoch)
            store.setLastSession(key, write)
            assertEquals(listOf(origin, other), store.servers())
            assertEquals(epoch, store.conversationEpoch(origin))
            assertEquals("kept", LocalStateStore().conversation(key)?.entries?.single()?.id)
            assertEquals(mapOf(store.seenId(key) to 123L), store.seenReplies(origin))
            assertEquals(key, store.lastSession())
        } finally {
            resume.complete(Unit)
            store.beforeConversationWrite = {}
            store.clear()
        }
    }

    @Test
    fun keychainRemovalAndGenerationAreIsolatedByOrigin() {
        val origin = "https://native-storage.invalid"
        val other = "https://other-native-storage.invalid"
        val first = SessionCookieStore(origin)
        val second = SessionCookieStore(other)
        first.clear()
        second.clear()
        try {
            first.saveFromResponse("$origin/login", listOf("habblaud_remote=first; Path=/; Secure"))
            val generation = second.generation()
            second.saveFromResponse("$other/login", listOf("habblaud_remote=second; Path=/; Secure"), generation)
            assertTrue(first.clear(first.generation()))
            assertNull(first.requestHeader("$origin/api/sessions"))
            assertNull(SessionCookieStore(origin).requestHeader("$origin/api/sessions"))
            assertEquals(generation, second.generation())
            assertEquals("habblaud_remote=second", second.requestHeader("$other/api/sessions"))
            assertEquals("habblaud_remote=second", SessionCookieStore(other).requestHeader("$other/api/sessions"))
            assertEquals(setOf(other), decodeCookies(checkNotNull(SecureSessionStorage().read())).keys)
            second.saveFromResponse("$other/login", listOf("habblaud_remote=fresh; Path=/; Secure"), generation)
            assertEquals("habblaud_remote=fresh", second.requestHeader("$other/api/sessions"))
        } finally {
            first.clear()
            second.clear()
        }
    }

    @Test
    fun removingServerPreservesOtherServerLocalState() = runBlocking {
        val store = LocalStateStore()
        val origin = "https://native-storage.invalid"
        val other = "https://other-native-storage.invalid"
        val first = SessionKey(origin, "claude", "test", "same-session")
        val second = first.copy(origin = other)
        store.clear()
        try {
            store.addServer(origin)
            store.addServer(other)
            val view = VIEWS.first { it != store.defaultView() }
            val mode = DELIVER_MODES.first { it != store.deliverAs() }
            for (key in listOf(first, second)) {
                store.updateMessage(key) { it.copy(draft = MessageDraft(text = key.origin)) }
                store.setSessionView(key, view)
                store.setSessionDeliverAs(key, mode)
                store.setSeenReplies(key.origin, mapOf(store.seenId(key) to 123L))
                val write = store.conversationWrite(key.origin, store.conversationEpoch(key.origin))
                store.setConversation(key, listOf(TerminalEntry("cached", "assistant", key.origin)), false, write)
            }
            store.setLastSession(second)
            val epoch = store.conversationEpoch(other)
            val write = store.conversationWrite(other, epoch)
            store.removeServer(origin)
            val reopened = LocalStateStore()
            assertEquals(listOf(other), reopened.servers())
            assertEquals(MessageLocalState(), reopened.message(first))
            assertEquals(reopened.defaultView(), reopened.sessionView(first))
            assertEquals(reopened.deliverAs(), reopened.sessionDeliverAs(first))
            assertTrue(reopened.seenReplies(origin).isEmpty())
            assertNull(reopened.conversation(first))
            assertEquals(other, reopened.message(second).draft.text)
            assertEquals(view, reopened.sessionView(second))
            assertEquals(mode, reopened.sessionDeliverAs(second))
            assertEquals(mapOf(store.seenId(second) to 123L), reopened.seenReplies(other))
            assertEquals(other, reopened.conversation(second)?.entries?.single()?.text)
            assertEquals(second, reopened.lastSession())
            assertEquals(epoch, reopened.conversationEpoch(other))
            reopened.setConversation(second, listOf(TerminalEntry("after", "assistant", "after removal")), false, write)
            reopened.setSeenReplies(other, mapOf(store.seenId(second) to 456L), epoch)
            assertEquals("after", reopened.conversation(second)?.entries?.single()?.id)
            assertEquals(mapOf(store.seenId(second) to 456L), reopened.seenReplies(other))
        } finally { store.clear() }
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
            val unknown = "$directory/file.zaplaud-unknown-extension"
            protectedWrite(unknown, "unknown type")
            copied = copied + attachments.select(listOf(NSURL.fileURLWithPath(unknown).absoluteString!!), emptyList())
            assertEquals("application/octet-stream", copied.last().mime)
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
