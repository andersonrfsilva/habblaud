package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MessageSendTest {
    private class MemoryStore : MessageStore {
        val items = mutableMapOf<SessionKey, MessageLocalState>()
        override suspend fun message(key: SessionKey) = items[key] ?: MessageLocalState()
        override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState = update(message(key)).also { items[key] = it }
    }
    private val a = SessionKey("https://example.test", "omp", "a", "one")
    private val b = SessionKey("https://example.test", "omp", "a", "two")
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(6000) { while (!predicate()) delay(10) }
    private fun controller(scope: CoroutineScope, store: MemoryStore, server: MockWebServer, changed: () -> Unit = {}): MessageController {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        return MessageController({ path, body -> withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(server.url(path))
            if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, code = if (text.contains("session_changed")) "session_changed" else null)
                text
            }
        } }, store, scope, { _, _ -> true }, changed, {})
    }
    private suspend fun open(c: MessageController, key: SessionKey) { c.start(); c.open(key); waitFor { !c.state.value.loading } }

    @Test fun acceptedIsNotDeliveredAndNewDraftSurvives() = runBlocking {
        val server = MockWebServer(); server.start()
        val store = MemoryStore()
        try {
            server.enqueue(MockResponse().setBody("{\"id\":\"m1\",\"status\":\"queued\"}").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
            val c = controller(this, store, server); open(c, a)
            c.edit(text = "primeiro"); c.send("agent"); waitFor { server.requestCount == 1 }
            c.edit(text = "seguinte")
            waitFor { c.state.value.operation?.id == "m1" }
            assertEquals("queued", c.state.value.operation?.status)
            assertEquals("seguinte", c.state.value.draft.text)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"expectedSessionId\":\"one\""))
            assertTrue(body.contains("\"deliverAs\":\"aside\""))
            c.stop()
        } finally { server.shutdown() }
    }
    @Test fun ambiguousFailureAndRestartNeverResendWithoutConfirmation() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val c = controller(this, store, server); open(c, a); c.edit(text = "pedido"); c.send("agent")
            waitFor { c.state.value.operation?.status == "unknown" && !c.state.value.sending }
            assertEquals(1, server.requestCount); c.stop()
            val restored = controller(this, store, server); open(restored, a)
            assertEquals("pedido", restored.state.value.draft.text)
            restored.send("agent"); assertEquals("confirm", restored.state.value.error)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("{\"id\":\"m2\",\"status\":\"delivered\"}"))
            restored.send("agent", confirmed = true)
            waitFor { restored.state.value.operation?.id == "m2" }
            assertEquals(2, server.requestCount); restored.stop()
        } finally { server.shutdown() }
    }
    @Test fun missingStatusIsUnknownNotFailure() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            server.enqueue(MockResponse().setBody("{\"id\":\"m1\",\"status\":\"queued\"}"))
            server.enqueue(MockResponse().setResponseCode(404))
            val c = controller(this, store, server); open(c, a); c.edit(text = "pedido"); c.send("agent")
            waitFor { c.state.value.operation?.status == "unknown" && c.state.value.operation?.id == "m1" }
            assertEquals(2, server.requestCount); c.stop()
        } finally { server.shutdown() }
    }
    @Test fun sessionConflictPreservesDraftAndClosesConversation() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore(); var changed = false
        try {
            server.enqueue(MockResponse().setResponseCode(409).setBody("{\"code\":\"session_changed\"}"))
            val c = controller(this, store, server) { changed = true }; open(c, a); c.edit(text = "pedido"); c.send("agent")
            waitFor { changed }
            assertEquals("pedido", store.message(a).draft.text)
            assertNull(store.message(a).operation); assertEquals(1, server.requestCount); c.stop()
        } finally { server.shutdown() }
    }
    @Test fun draftsRemainIsolatedAndUtf16Limited() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            val c = controller(this, store, server); open(c, a); c.edit(text = "A"); c.open(b); waitFor { !c.state.value.loading }
            c.edit(text = "B"); c.open(a); waitFor { !c.state.value.loading }; assertEquals("A", c.state.value.draft.text)
            c.edit(text = "😀".repeat(10001)); assertEquals("A", c.state.value.draft.text)
            c.stop()
            val restored = controller(this, store, server); open(restored, b); assertEquals("B", restored.state.value.draft.text); restored.stop()
        } finally { server.shutdown() }
    }
    @Test fun acceptedOperationRecoversAfterProcessDiesBeforeDraftClear() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            store.items[a] = MessageLocalState(MessageDraft("pedido", "steer", 7), MessageOperation("m1", "delivered", 7))
            val restored = controller(this, store, server); open(restored, a)
            assertEquals("", restored.state.value.draft.text)
            assertEquals("delivered", restored.state.value.operation?.status)
            assertEquals(0, server.requestCount); restored.stop()
        } finally { server.shutdown() }
    }
}
