package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.junit.After
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class MessageSendTest {
    private val clients = mutableListOf<HttpClient>()
    @After fun closeClients() { clients.forEach { it.close() } }
    private class MemoryStore : MessageStore {
        val items = mutableMapOf<SessionKey, MessageLocalState>()
        override suspend fun message(key: SessionKey) = items[key] ?: MessageLocalState()
        override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState = update(message(key)).also { items[key] = it }
    }
    private val a = SessionKey("https://example.test", "omp", "a", "one")
    private val b = SessionKey("https://example.test", "omp", "a", "two")
    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(6000) { while (!predicate()) delay(10) }
    private fun controller(scope: CoroutineScope, store: MemoryStore, server: MockWebServer, changed: () -> Unit = {}): MessageController {
        val client = httpClient().also { clients += it }
        return MessageController({ path, body ->
            val response = client.request(server.url(path).toString()) {
                if (body != null) {
                    method = HttpMethod.Post
                    val bytes = body.encodeToByteArray()
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.Json
                        override val contentLength = bytes.size.toLong()
                        override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
                    })
                }
            }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) throw apiError(response.status.value, response.headers["Retry-After"], text)
            text
        }, store, scope, { _, _ -> true }, changed, {})
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
    @Test fun nonOmpMessagesOmitDeliverAs() = runBlocking {
        for (provider in listOf("claude", "codex")) {
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(MockResponse().setBody("{\"id\":\"m1\",\"status\":\"queued\"}"))
                val key = a.copy(provider = provider)
                val c = controller(this, MemoryStore(), server); open(c, key)
                c.edit(text = "pedido"); c.send("agent")
                waitFor { c.state.value.operation?.id == "m1" }
                val body = server.takeRequest().body.readUtf8()
                assertTrue(body.contains("\"expectedSessionId\":\"one\""))
                assertFalse(body.contains("\"deliverAs\""))
                c.stop()
            } finally { server.shutdown() }
        }
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
    @Test fun uncertainStopRequiresExplicitConfirmationAfterRestart() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val c = controller(this, store, server); open(c, a); c.interrupt("agent")
            waitFor { c.state.value.controlError == "unknown" }
            assertEquals(1, server.requestCount); c.stop()
            val restored = controller(this, store, server); open(restored, a)
            assertEquals("unknown", restored.state.value.control?.status)
            restored.interrupt("agent")
            assertEquals("confirm", restored.state.value.controlError)
            assertEquals(1, server.requestCount)
            restored.dismissControlConfirmation()
            assertNull(restored.state.value.controlError)
            restored.interrupt("agent")
            server.enqueue(MockResponse().setBody("{\"id\":\"stop2\",\"status\":\"queued\"}"))
            restored.interrupt("agent", confirmed = true)
            waitFor { restored.state.value.control?.id == "stop2" }
            restored.interrupt("agent", confirmed = true)
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/api/messages/control", first.path)
            assertTrue(second.body.readUtf8().contains("\"expectedSessionId\":\"one\""))
            restored.stop()
        } finally { server.shutdown() }
    }
    @Test fun expiredStopStatusAlsoRequiresConfirmation() = runBlocking {
        val server = MockWebServer(); server.start(); val store = MemoryStore()
        try {
            server.enqueue(MockResponse().setBody("{\"id\":\"stop1\",\"status\":\"queued\"}"))
            server.enqueue(MockResponse().setResponseCode(404))
            val c = controller(this, store, server); open(c, a); c.interrupt("agent")
            waitFor { c.state.value.control?.id == "stop1" }
            waitFor { c.state.value.control?.id == "stop1" && c.state.value.control?.status == "unknown" }
            c.interrupt("agent")
            assertEquals("confirm", c.state.value.controlError)
            assertEquals(2, server.requestCount)
            c.stop()
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
            store.items[a] = MessageLocalState(MessageDraft("pedido", 7), MessageOperation("m1", "delivered", 7))
            val restored = controller(this, store, server); open(restored, a)
            assertEquals("", restored.state.value.draft.text)
            assertEquals("delivered", restored.state.value.operation?.status)
            assertEquals(0, server.requestCount); restored.stop()
        } finally { server.shutdown() }
    }
}
