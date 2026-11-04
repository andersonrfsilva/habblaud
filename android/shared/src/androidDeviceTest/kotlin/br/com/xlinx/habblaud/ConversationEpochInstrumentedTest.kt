package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.TerminalEntry
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.ReadingController
import br.com.xlinx.habblaud.data.SessionItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@RunWith(AndroidJUnit4::class)
class ConversationEpochInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { br.com.xlinx.habblaud.data.initializeStorage(it) }
    private val origin = "https://fixture.example"
    private val key = SessionKey(origin, "claude", "account", "session")

    @Before fun setUp() = runBlocking { LocalStateStore(context).clear() }
    @After fun tearDown() = runBlocking { LocalStateStore(context).clear() }

    @Test fun lateSeenRepliesCannotRecreateAccountStateAfterLogout() = runBlocking {
        val writer = LocalStateStore(context)
        writer.setConnection(origin)
        val oldEpoch = writer.conversationEpoch()
        LocalStateStore(context).clear()
        writer.setSeenReplies(mapOf(key.serialize() to 123L), oldEpoch)
        assertTrue(LocalStateStore(context).seenReplies().isEmpty())
    }

    private fun staleWrite(change: suspend (LocalStateStore) -> Unit) = runBlocking {
        withTimeout(15000) {
            val writer = LocalStateStore(context)
            val other = LocalStateStore(context)
            writer.setConnection(origin)
            val paused = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            writer.beforeConversationWrite = { paused.complete(Unit); release.await() }
            val write = writer.conversationWrite(writer.conversationEpoch())
            val save = async { writer.setConversation(key, listOf(TerminalEntry("old", "user", "old")), false, write) }
            try {
                paused.await()
                change(other)
            } finally {
                release.complete(Unit)
            }
            save.await()
            assertNull("old conversation reappeared", LocalStateStore(context).conversation(key))
            assertFalse("old conversation file reappeared", context.filesDir.resolve("conversations").listFiles().orEmpty().any())
        }
    }

    @Test fun clearRejectsOldWrite() = staleWrite { it.clear() }
    @Test fun originChangeRejectsOldWrite() = staleWrite { it.setConnection("https://other.example") }

    @Test fun validWritesRemainOrderedAndIsolatedAcrossInstances() = runBlocking {
        withTimeout(15000) {
            val first = LocalStateStore(context)
            val second = LocalStateStore(context)
            first.setConnection(origin)
            val epoch = first.conversationEpoch()
            val older = first.conversationWrite(epoch)
            val newer = second.conversationWrite(epoch)
            val paused = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            first.beforeConversationWrite = { paused.complete(Unit); release.await() }
            val delayed = async { first.setConversation(key, listOf(TerminalEntry("old", "user", "old")), false, older) }
            try {
                paused.await()
                second.setConversation(key, listOf(TerminalEntry("new", "user", "new")), true, newer)
            } finally {
                release.complete(Unit)
            }
            delayed.await()
            val cached = LocalStateStore(context).conversation(key)!!
            assertEquals("new", cached.entries.single().id)
            assertTrue(cached.truncated)
            assertNull(second.conversation(key.copy(origin = "https://other.example")))
            second.setConnection(origin)
            assertEquals(cached, LocalStateStore(context).conversation(key))
            val many = (1..600).map { TerminalEntry("entry$it", "user", "text") }
            val another = key.copy(sessionId = "another")
            second.setConversation(another, many, false, second.conversationWrite(epoch))
            val restored = LocalStateStore(context).conversation(another)!!
            assertEquals(500, restored.entries.size)
            assertEquals("entry101", restored.entries.first().id)
            assertTrue(restored.truncated)
            assertEquals(cached, first.conversation(key))
            second.clear()
            assertNull(first.conversation(key))
            second.setConnection("https://other.example")
            val other = key.copy(origin = "https://other.example")
            second.setConversation(other, listOf(TerminalEntry("b", "user", "b")), false, second.conversationWrite(second.conversationEpoch()))
            assertEquals("b", LocalStateStore(context).conversation(other)!!.entries.single().id)
            second.setConnection(origin)
            assertNull(first.conversation(key))
            assertNull(first.conversation(other))
        }
    }

    @Test fun concurrentTransitionsAndWritesStayIsolated() = runBlocking {
        withTimeout(15000) {
            val first = LocalStateStore(context)
            val second = LocalStateStore(context)
            first.setConnection(origin)
            val epoch = first.conversationEpoch()
            val older = first.conversationWrite(epoch)
            val newer = second.conversationWrite(epoch)
            val start = CompletableDeferred<Unit>()
            val a = async(Dispatchers.IO) {
                start.await()
                first.setConversation(key, listOf(TerminalEntry("a", "user", "a")), false, older)
            }
            val b = async(Dispatchers.IO) {
                start.await()
                second.setConversation(key, listOf(TerminalEntry("b", "user", "b")), true, newer)
            }
            start.complete(Unit)
            a.await()
            b.await()
            assertEquals("b", LocalStateStore(context).conversation(key)!!.entries.single().id)
            val transition = CompletableDeferred<Unit>()
            val logout = async(Dispatchers.IO) { transition.await(); first.clear() }
            val change = async(Dispatchers.IO) { transition.await(); second.setConnection("https://other.example") }
            transition.complete(Unit)
            logout.await()
            change.await()
            assertNull(LocalStateStore(context).conversation(key))
            assertTrue(second.connection()?.origin in listOf(null, "https://other.example"))
        }
    }

    private fun controllerSave(clear: Boolean, close: Boolean) = runBlocking {
        withTimeout(15000) {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":true}""")
                    "/api/sessions/recent" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/cast" -> MockResponse().setBody("""{"members":[]}""")
                    "/api/stream" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("event: snapshot\ndata: {\"agents\":[{\"kind\":\"main\",\"account\":\"account\",\"sessionId\":\"session\"}]}\n\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val address = server.url("/").toString().removeSuffix("/")
            val session = key.copy(origin = address)
            val store = LocalStateStore(context)
            val api = HabblaudApi(address)
            try {
                store.setConnection(address)
                store.setConversation(session, listOf(TerminalEntry("old", "user", "old")), false, store.conversationWrite(store.conversationEpoch()))
                val controller = ReadingController(api, store, scope) {}
                controller.start()
                controller.state.first { state -> state.sessions.any { it.key == session } }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    controller.open(SessionItem(session, null, "fixture", null, null, null))
                }
                controller.state.first { it.entries.isNotEmpty() }
                if (clear) LocalStateStore(context).clear()
                val writing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                store.beforeConversationWrite = {
                    writing.complete(Unit)
                    release.await()
                }
                store.afterConversationWrite = { completed.complete(Unit) }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (close) controller.close() else controller.stop()
                }
                writing.await()
                release.complete(Unit)
                completed.await()
                if (clear) assertNull("old conversation reappeared", LocalStateStore(context).conversation(session))
                else assertEquals("old", LocalStateStore(context).conversation(session)!!.entries.single().id)
            } finally {
                scope.cancel()
                api.close()
                server.shutdown()
            }
        }
    }
    @Test fun stopAfterClearRejectsLoadedConversation() = controllerSave(clear = true, close = false)
    @Test fun stopPersistsLoadedConversation() = controllerSave(clear = false, close = false)
    @Test fun closePersistsLoadedConversation() = controllerSave(clear = false, close = true)
    @Test fun reloginRejectsOldWrite() = staleWrite { it.clear(); it.setConnection(origin) }

    @Test fun failedPhysicalCleanupIsReportedAndOldCacheCannotBeRead() = runBlocking {
        withTimeout(15000) {
            val store = LocalStateStore(context)
            store.setConnection(origin)
            store.setConversation(key, listOf(TerminalEntry("old", "user", "old")), false, store.conversationWrite(store.conversationEpoch()))
            val directory = context.filesDir.resolve("conversations")
            android.system.Os.chmod(directory.absolutePath, 0)
            try {
                assertTrue(runCatching { store.clear() }.isFailure)
            } finally {
                android.system.Os.chmod(directory.absolutePath, 448)
            }
            assertNull(LocalStateStore(context).connection())
            assertNull(LocalStateStore(context).conversation(key))
            assertTrue(directory.walkTopDown().any { it.isFile })
        }
    }

    @Test fun failedOriginCleanupMustBeRetriedForSameOrigin() = runBlocking {
        withTimeout(15000) {
            val store = LocalStateStore(context)
            store.setConnection(origin)
            store.setConversation(key, listOf(TerminalEntry("old", "user", "old")), false, store.conversationWrite(store.conversationEpoch()))
            val directory = context.filesDir.resolve("conversations")
            android.system.Os.chmod(directory.absolutePath, 0)
            try {
                assertTrue(runCatching { store.setConnection("https://other.example") }.isFailure)
                assertTrue(runCatching { store.setConnection("https://other.example") }.isFailure)
            } finally {
                android.system.Os.chmod(directory.absolutePath, 448)
            }
            assertNull(LocalStateStore(context).conversation(key))
            assertTrue(directory.walkTopDown().any { it.isFile })
            store.setConnection("https://other.example")
            assertFalse(directory.walkTopDown().any { it.isFile })
        }
    }

}
