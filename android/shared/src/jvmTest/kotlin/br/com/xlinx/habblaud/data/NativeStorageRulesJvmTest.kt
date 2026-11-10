package br.com.xlinx.habblaud.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NativeStorageRulesJvmTest {
    @BeforeTest fun reset() = runBlocking {
        LocalStateStore().clear()
        SecureSessionStorage().clear()
        settingsNode.let { it.removeNode(); it.flush() }
        Files.walk(Path.of(System.getProperty("zaplaud.dataDir"))).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    @Test
    fun memoryStoragePersistsOnlySelectedOriginAndClearRemovesSession() {
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
        val key = SessionKey(origin, "claude", "test", "native-${UUID.randomUUID().toString()}")
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
    fun memoryStorageRemovalAndGenerationAreIsolatedByOrigin() {
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
