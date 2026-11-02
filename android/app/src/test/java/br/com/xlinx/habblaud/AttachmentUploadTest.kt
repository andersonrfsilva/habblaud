package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AttachmentUploadTest {
    private fun body(bytes: ByteArray, size: Long? = bytes.size.toLong()) = AttachmentBody(size, { ByteArrayInputStream(bytes) })
    private fun invalid(block: () -> Unit) {
        try { block(); fail("expected failure") } catch (_: IOException) { }
    }

    @Test fun binaryContentAndNameAreExact() {
        val bytes = byteArrayOf(0, 1, 127, -1, 43, 32)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val name = encodeAttachmentName("ação + (1).png")
            assertEquals("a%C3%A7%C3%A3o%20%2B%20(1).png", name)
            val request = Request.Builder().url(server.url("/api/messages/attachments"))
                .header("X-Habblaud-File-Name", name).header("X-Habblaud-File-Type", "image/png")
                .post(body(bytes)).build()
            OkHttpClient().newCall(request).execute().close()
            val recorded = server.takeRequest()
            assertArrayEquals(bytes, recorded.body.readByteArray())
            assertEquals(name, recorded.getHeader("X-Habblaud-File-Name"))
            assertEquals(bytes.size.toString(), recorded.getHeader("Content-Length"))
        } finally { server.shutdown() }
    }

    @Test fun sizeBoundariesAndUnknownLength() {
        val one = byteArrayOf(42)
        assertEquals(1L, Buffer().also { body(one).writeTo(it) }.size)
        val max = ByteArray(MAX_ATTACHMENT_SIZE.toInt()) { (it % 251).toByte() }
        val output = Buffer()
        body(max).writeTo(output)
        assertArrayEquals(max, output.readByteArray())
        invalid { body(ByteArray(0)).writeTo(Buffer()) }
        invalid { body(ByteArray(MAX_ATTACHMENT_SIZE.toInt() + 1)).writeTo(Buffer()) }
        invalid { body(ByteArray(0), null).writeTo(Buffer()) }
        invalid { body(ByteArray(MAX_ATTACHMENT_SIZE.toInt() + 1), null).writeTo(Buffer()) }
        assertEquals(-1L, body(one, null).contentLength())
        assertArrayEquals(one, Buffer().also { body(one, null).writeTo(it) }.readByteArray())
        invalid { body(one, 2).writeTo(Buffer()) }
    }

    @Test fun revokedAccessFailsAndSelectionLimitIsEnforced() {
        invalid { AttachmentBody(null, { throw IOException("revoked") }).writeTo(Buffer()) }
        checkAttachmentCount(4, 1)
        try { checkAttachmentCount(0, 6); fail("six files accepted") } catch (_: IllegalArgumentException) { }
    }
    private class MemoryStore : MessageStore {
        val values = mutableMapOf<SessionKey, MessageLocalState>()
        override suspend fun message(key: SessionKey) = values[key] ?: MessageLocalState()
        override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState) = update(message(key)).also { values[key] = it }
    }

    @Test fun pickerResultWaitsForReloadAndKeepsDraft() = runBlocking {
        val key = SessionKey("https://example.test", "omp", "account", "one")
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var local = MessageLocalState(MessageDraft("rascunho", mode = "steer", revision = 3))
        val store = object : MessageStore {
            override suspend fun message(key: SessionKey) = local
            override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
                if (!resume.isCompleted) { entered.complete(Unit); resume.await() }
                local = update(local)
                return local
            }
        }
        val controller = MessageController({ _, _ -> "{}" }, store, this, { _, _ -> true }, {}, {})
        val attachment = Attachment("uri", "arquivo", "text/plain", 1, true)
        controller.start(); controller.open(key)
        entered.await()
        controller.selectPrepared { before -> before + attachment }
        resume.complete(Unit)
        withTimeout(3000) { while (controller.state.value.draft.attachments.isEmpty()) delay(1) }
        assertEquals("rascunho", controller.state.value.draft.text)
        assertEquals("steer", controller.state.value.draft.mode)
        assertEquals(listOf(attachment), controller.state.value.draft.attachments)
        withTimeout(3000) { while (local.draft.attachments.isEmpty()) delay(1) }
        controller.stop()
    }

    @Test fun pickerResultDoesNotMoveToAnotherSession() = runBlocking {
        val a = SessionKey("https://example.test", "omp", "account", "one")
        val b = a.copy(sessionId = "two")
        val store = MemoryStore()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = MessageController({ _, _ -> "{}" }, store, this, { _, _ -> true }, {}, {})
        controller.start(); controller.open(a)
        withTimeout(3000) { while (controller.state.value.loading) delay(1) }
        controller.selectPrepared { before -> started.complete(Unit); finish.await(); before + Attachment("uri", "arquivo", "text/plain", 1, true) }
        started.await()
        controller.open(b)
        finish.complete(Unit)
        withTimeout(3000) { while (controller.state.value.loading) delay(1) }
        assertTrue(controller.state.value.draft.attachments.isEmpty())
        assertTrue(store.message(a).draft.attachments.isEmpty())
        controller.stop()
    }

    @Test fun expiredStagingRequiresExplicitNewAttempt() = runBlocking {
        val key = SessionKey("https://example.test", "omp", "account", "one")
        val store = MemoryStore()
        store.values[key] = MessageLocalState(MessageDraft("text", attachments = listOf(Attachment("uri", "name", "text/plain", 1, true))))
        var posts = 0
        var uploads = 0
        val controller = MessageController(
            request = { _, _ -> posts++; throw ApiException(400, attachmentsExpired = true) },
            store = store, scope = this, eligible = { _, _ -> true }, changed = {}, unauthorized = {},
            upload = { _, _, _ -> uploads++; "{\"id\":\"upload-$uploads\"}" },
            attachmentBody = { _, _ -> body(byteArrayOf(1)) }
        )
        controller.start(); controller.open(key)
        withTimeout(3000) { while (controller.state.value.loading) delay(1) }
        controller.send("agent")
        withTimeout(3000) { while (controller.state.value.sending) delay(1) }
        assertEquals("attachments_expired", controller.state.value.error)
        assertEquals(1, posts); assertEquals(1, uploads)
        assertEquals(1, controller.state.value.draft.attachments.size)
        controller.stop(); controller.start()
        delay(20)
        assertEquals(1, posts); assertEquals(1, uploads)
        controller.stop()
    }

    @Test fun switchingSessionDuringUploadPreventsMessagePost() = runBlocking {
        val a = SessionKey("https://example.test", "omp", "account", "one")
        val b = a.copy(sessionId = "two")
        val store = MemoryStore()
        store.values[a] = MessageLocalState(MessageDraft("text", attachments = listOf(Attachment("uri", "name", "text/plain", 1, true))))
        var selected = a
        var posts = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = MessageController(
            request = { _, _ -> posts++; "{}" }, store = store, scope = this,
            eligible = { key, _ -> key == selected }, changed = {}, unauthorized = {},
            upload = { _, _, _ -> started.complete(Unit); finish.await(); "{\"id\":\"id\"}" },
            attachmentBody = { _, _ -> body(byteArrayOf(1)) }
        )
        controller.start(); controller.open(a)
        withTimeout(3000) { while (controller.state.value.loading) delay(1) }
        controller.send("agent")
        started.await()
        selected = b
        controller.open(b)
        finish.complete(Unit)
        delay(50)
        assertEquals(0, posts)
        assertEquals("text", store.values[a]?.draft?.text)
        assertEquals(1, store.values[a]?.draft?.attachments?.size)
        controller.stop()
    }
    @Test fun backgroundCancelsUploadBeforeMessage() = runBlocking {
        val key = SessionKey("https://example.test", "omp", "account", "one")
        val store = MemoryStore()
        store.values[key] = MessageLocalState(MessageDraft("text", attachments = listOf(Attachment("uri", "name", "text/plain", 1, true))))
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var posts = 0
        val controller = MessageController(
            request = { _, _ -> posts++; "{}" }, store = store, scope = this,
            eligible = { _, _ -> true }, changed = {}, unauthorized = {},
            upload = { _, _, _ -> try { started.complete(Unit); awaitCancellation() } finally { cancelled.complete(Unit) } },
            attachmentBody = { _, _ -> body(byteArrayOf(1)) }
        )
        controller.start(); controller.open(key)
        withTimeout(3000) { while (controller.state.value.loading) delay(1) }
        controller.send("agent"); started.await()
        controller.stop(); withTimeout(3000) { cancelled.await() }
        assertEquals(0, posts)
        assertEquals("text", store.values[key]?.draft?.text)
    }
}
