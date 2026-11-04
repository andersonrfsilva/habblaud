package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AttachmentControllerTest {
    private fun body(bytes: ByteArray) = AttachmentBody(bytes.size.toLong(), {
        object : AttachmentSource {
            private var offset = 0
            override fun read(buffer: ByteArray): Int {
                if (offset == bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + count)
                offset += count
                return count
            }
            override fun close() = Unit
        }
    })
    private class MemoryStore : MessageStore {
        val values = mutableMapOf<SessionKey, MessageLocalState>()
        override suspend fun message(key: SessionKey) = values[key] ?: MessageLocalState()
        override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState) = update(message(key)).also { values[key] = it }
    }

    @Test fun pickerResultWaitsForReloadAndKeepsDraft() = runTest {
        val key = SessionKey("https://example.test", "omp", "account", "one")
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var local = MessageLocalState(MessageDraft("rascunho", revision = 3))
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
        controller.state.first { it.draft.attachments.isNotEmpty() }
        assertEquals("rascunho", controller.state.value.draft.text)
        assertEquals(listOf(attachment), controller.state.value.draft.attachments)
        runCurrent()
        assertEquals(listOf(attachment), local.draft.attachments)
        controller.stop()
    }

    @Test fun pickerResultDoesNotMoveToAnotherSession() = runTest {
        val a = SessionKey("https://example.test", "omp", "account", "one")
        val b = a.copy(sessionId = "two")
        val store = MemoryStore()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = MessageController({ _, _ -> "{}" }, store, this, { _, _ -> true }, {}, {})
        controller.start(); controller.open(a)
        controller.state.first { !it.loading }
        controller.selectPrepared { before -> started.complete(Unit); finish.await(); before + Attachment("uri", "arquivo", "text/plain", 1, true) }
        started.await()
        controller.open(b)
        finish.complete(Unit)
        controller.state.first { !it.loading }
        assertTrue(controller.state.value.draft.attachments.isEmpty())
        assertTrue(store.message(a).draft.attachments.isEmpty())
        controller.stop()
    }

    @Test fun expiredStagingRequiresExplicitNewAttempt() = runTest {
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
        controller.state.first { !it.loading }
        controller.send("agent")
        controller.state.first { !it.sending }
        assertEquals("attachments_expired", controller.state.value.error)
        assertEquals(1, posts); assertEquals(1, uploads)
        assertEquals(1, controller.state.value.draft.attachments.size)
        controller.stop(); controller.start()
        controller.state.first { !it.loading }
        runCurrent()
        assertEquals(1, posts); assertEquals(1, uploads)
        controller.stop()
    }

    @Test fun switchingSessionDuringUploadPreventsMessagePost() = runTest {
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
        controller.state.first { !it.loading }
        controller.send("agent")
        started.await()
        selected = b
        controller.open(b)
        finish.complete(Unit)
        controller.state.first { !it.loading }
        runCurrent()
        assertEquals(0, posts)
        assertEquals("text", store.values[a]?.draft?.text)
        assertEquals(1, store.values[a]?.draft?.attachments?.size)
        controller.stop()
    }
    @Test fun backgroundCancelsUploadBeforeMessage() = runTest {
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
        controller.state.first { !it.loading }
        controller.send("agent"); started.await()
        controller.stop(); cancelled.await()
        assertEquals(0, posts)
        assertEquals("text", store.values[key]?.draft?.text)
    }
}
