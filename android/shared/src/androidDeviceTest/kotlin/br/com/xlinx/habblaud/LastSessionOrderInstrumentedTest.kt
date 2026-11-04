package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

@RunWith(AndroidJUnit4::class)
class LastSessionOrderInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { initializeStorage(it) }
    @After fun cleanup() = runBlocking { LocalStateStore(context).clear() }

    private class ControlledDispatcher : CoroutineDispatcher() {
        private var capturing = false
        private val captured = mutableListOf<Pair<CoroutineContext, Runnable>>()
        @Synchronized fun pause(action: () -> Unit) {
            check(captured.isEmpty()) { "Previous action has not been released" }
            capturing = true
            try { action() } finally { capturing = false }
        }
        suspend fun release() {
            val pending = synchronized(this) { captured.toList().also { captured.clear() } }
            check(pending.isNotEmpty()) { "Action did not leave pending work" }
            pending.forEach { (context, block) -> Dispatchers.Main.dispatch(context, block) }
            pending.mapNotNull { it.first[Job] }.distinct().joinAll()
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(this) {
                if (capturing) { captured.add(context to block); return }
            }
            Dispatchers.Main.dispatch(context, block)
        }
    }

    private fun snapshot() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("event: snapshot\ndata: {\"agents\":[{\"kind\":\"main\",\"account\":\"account\",\"sessionId\":\"a\"},{\"kind\":\"main\",\"account\":\"account\",\"sessionId\":\"b\"}]}\n\n")

    private fun exercise(close: Boolean) = runBlocking {
        withTimeout(15000) {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":true}""")
                    "/api/sessions/recent" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/cast" -> MockResponse().setBody("""{"members":[]}""")
                    "/api/stream" -> snapshot()
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val dispatcher = ControlledDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val origin = server.url("/").toString().removeSuffix("/")
            val a = SessionKey(origin, "claude", "account", "a")
            val b = a.copy(sessionId = "b")
            val store = LocalStateStore(context)
            val api = HabblaudApi(origin)
            try {
                store.clear()
                store.setConnection(origin)
                store.setLastSession(a.copy(sessionId = "initial"))
                val controller = ReadingController(api, store, scope) {}
                controller.start()
                controller.state.first { state -> state.sessions.any { it.key == a } }
                withContext(Dispatchers.Main) {
                    dispatcher.pause { controller.open(SessionItem(a, null, "a", null, null, null)) }
                    assertEquals(a.copy(sessionId = "initial"), LocalStateStore(context).lastSession())
                    if (close) controller.close() else controller.open(SessionItem(b, null, "b", null, null, null))
                }
                if (close) while (LocalStateStore(context).lastSession() != null) yield()
                else while (LocalStateStore(context).lastSession() != b) yield()
                dispatcher.release()
                val observed = LocalStateStore(context).lastSession()
                if (close) assertNull("closed session restored", observed) else assertEquals(b, observed)
                if (!close) {
                    withContext(Dispatchers.Main) {
                        controller.open(SessionItem(a, null, "sub", null, null, null, sub = true))
                        controller.stop()
                    }
                    assertEquals(b, LocalStateStore(context).lastSession())
                    val recreated = ReadingController(api, LocalStateStore(context), scope) {}
                    withContext(Dispatchers.Main) {
                        recreated.open(SessionItem(a, null, "inactive", null, null, null))
                    }
                    assertEquals(b, LocalStateStore(context).lastSession())
                }
            } finally {
                scope.cancel()
                api.close()
                server.shutdown()
            }
        }
    }
    @Test fun openThenClose() = exercise(true)
    @Test fun openThenOpen() = exercise(false)
    @Test fun stopAndRecreationRestoreSelection() = runBlocking {
        withTimeout(15000) {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":true}""")
                    "/api/sessions/recent" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/cast" -> MockResponse().setBody("""{"members":[]}""")
                    "/api/stream" -> MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("event: snapshot\ndata: {\"agents\":[{\"kind\":\"main\",\"account\":\"account\",\"sessionId\":\"a\"}]}\n\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val origin = server.url("/").toString().removeSuffix("/")
            val key = SessionKey(origin, "claude", "account", "a")
            val store = LocalStateStore(context)
            val api = HabblaudApi(origin)
            try {
                store.clear()
                store.setConnection(origin)
                val first = ReadingController(api, store, scope) {}
                first.start()
                first.state.first { state -> state.sessions.any { it.key == key } }
                withContext(Dispatchers.Main) { first.open(SessionItem(key, null, "a", null, null, null)) }
                while (LocalStateStore(context).lastSession() != key) yield()
                withContext(Dispatchers.Main) { first.stop() }
                assertEquals(key, LocalStateStore(context).lastSession())
                val restored = ReadingController(api, LocalStateStore(context), scope) {}
                restored.start()
                restored.state.first { it.selected?.key == key }
                assertEquals(key, LocalStateStore(context).lastSession())
            } finally {
                scope.cancel()
                api.close()
                server.shutdown()
            }
        }
    }
    private fun controllersAcrossTransition(transition: String, delayedClose: Boolean) = runBlocking {
        withTimeout(15000) {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":true}""")
                    "/api/sessions/recent" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/cast" -> MockResponse().setBody("""{"members":[]}""")
                    "/api/stream" -> snapshot()
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val otherServer = MockWebServer()
            otherServer.dispatcher = server.dispatcher
            otherServer.start()
            val dispatcher = ControlledDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val origin = server.url("/").toString().removeSuffix("/")
            val a = SessionKey(origin, "claude", "account", "a")
            val nextOrigin = if (transition == "origin") otherServer.url("/").toString().removeSuffix("/") else origin
            val b = a.copy(origin = nextOrigin, sessionId = "b")
            val first = LocalStateStore(context)
            val second = LocalStateStore(context)
            val firstApi = HabblaudApi(origin)
            val secondApi = HabblaudApi(nextOrigin)
            try {
                first.clear()
                first.setConnection(origin)
                first.setLastSession(a.copy(sessionId = "initial"))
                val old = ReadingController(firstApi, first, scope) {}
                old.start()
                old.state.first { state -> state.sessions.any { it.key == a } }
                withContext(Dispatchers.Main) {
                    dispatcher.pause {
                        if (delayedClose) old.close()
                        else old.open(SessionItem(a, null, "a", null, null, null))
                    }
                }
                assertEquals(a.copy(sessionId = "initial"), LocalStateStore(context).lastSession())
                when (transition) {
                    "logout" -> { second.clear(); second.setConnection(origin) }
                    "origin" -> second.setConnection(nextOrigin)
                }
                val newer = ReadingController(secondApi, second, scope) {}
                newer.start()
                newer.state.first { state -> state.sessions.any { it.key == b } }
                withContext(Dispatchers.Main) { newer.open(SessionItem(b, null, "b", null, null, null)) }
                while (LocalStateStore(context).lastSession() != b) yield()
                dispatcher.release()
                assertEquals(b, LocalStateStore(context).lastSession())
                withContext(Dispatchers.Main) { newer.close(); newer.close() }
                while (LocalStateStore(context).lastSession() != null) yield()
                assertNull(LocalStateStore(context).lastSession())
            } finally {
                scope.cancel()
                firstApi.close()
                secondApi.close()
                server.shutdown()
                otherServer.shutdown()
            }
        }
    }

    @Test fun oldCloseCannotEraseNewControllerSelection() = controllersAcrossTransition("none", true)
    @Test fun oldOpenCannotOverrideSelectionAfterRelogin() = controllersAcrossTransition("logout", false)
    @Test fun oldCloseCannotEraseSelectionAfterRelogin() = controllersAcrossTransition("logout", true)
    @Test fun oldOpenCannotOverrideSelectionAfterOriginChange() = controllersAcrossTransition("origin", false)
    @Test fun oldCloseCannotEraseSelectionAfterOriginChange() = controllersAcrossTransition("origin", true)

    @Test fun deniedTerminalDoesNotChangeLastSession() = runBlocking {
        withTimeout(15000) {
            val server = MockWebServer()
            val streamRequested = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":false}""")
                    "/api/stream" -> { streamRequested.complete(Unit); MockResponse().setResponseCode(404) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val origin = server.url("/").toString().removeSuffix("/")
            val saved = SessionKey(origin, "claude", "account", "saved")
            val store = LocalStateStore(context)
            val api = HabblaudApi(origin)
            try {
                store.clear()
                store.setConnection(origin)
                store.setLastSession(saved)
                val controller = ReadingController(api, store, scope) {}
                controller.start()
                streamRequested.await()
                withContext(Dispatchers.Main) {
                    controller.open(SessionItem(saved.copy(sessionId = "denied"), null, "denied", null, null, null))
                }
                assertEquals(saved, LocalStateStore(context).lastSession())
            } finally {
                scope.cancel()
                api.close()
                server.shutdown()
            }
        }
    }
    @Test fun orderedWritesRespectEpochAcrossStores() = runBlocking {
        withTimeout(15000) {
            val first = LocalStateStore(context)
            val second = LocalStateStore(context)
            val origin = "https://order.example"
            val a = SessionKey(origin, "claude", "account", "a")
            val b = a.copy(sessionId = "b")
            first.clear()
            first.setConnection(origin)
            val epoch = first.conversationEpoch()
            val oldNull = first.conversationWrite(epoch)
            val newB = second.conversationWrite(epoch)
            second.setLastSession(b, newB)
            first.setLastSession(null, oldNull)
            assertEquals(b, LocalStateStore(context).lastSession())
            val oldA = first.conversationWrite(epoch)
            second.clear()
            second.setConnection(origin)
            val fresh = second.conversationWrite(second.conversationEpoch())
            second.setLastSession(b, fresh)
            first.setLastSession(a, oldA)
            first.setLastSession(null, oldNull)
            assertEquals(b, LocalStateStore(context).lastSession())
            val oldOrigin = first.conversationWrite(second.conversationEpoch())
            second.setConnection("https://other.example")
            second.setLastSession(a, oldOrigin)
            assertNull(LocalStateStore(context).lastSession())
        }
    }
}
