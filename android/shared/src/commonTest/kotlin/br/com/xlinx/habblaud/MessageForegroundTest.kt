package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MessageForegroundTest {
    private val key = SessionKey("https://example.test", "omp", "account", "session")
    private class MemoryStore : MessageStore {
        var local = MessageLocalState()
        override suspend fun message(key: SessionKey) = local
        override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState) = update(local).also { local = it }
    }

    @Test fun backgroundAfterPostKeepsUnknownAndNeverReplaysOnReturn() = runTest {
        val store = MemoryStore()
        var posts = 0
        var cancelled = false
        val controller = MessageController({ _, body ->
            assertNotNull(body)
            posts++
            try { awaitCancellation() } finally { cancelled = true }
        }, store, this, { _, _ -> true }, {}, {})
        controller.start(); controller.open(key); controller.state.first { !it.loading }
        controller.edit("pedido"); runCurrent()
        controller.send("agent"); runCurrent()
        assertEquals(1, posts)
        controller.stop(); runCurrent()
        assertTrue(cancelled)
        assertEquals("unknown", store.local.operation?.status)
        assertEquals("pedido", store.local.draft.text)
        controller.start(); controller.start(); controller.state.first { !it.loading }
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, posts)
        assertEquals("unknown", controller.state.value.operation?.status)
        controller.send("agent"); runCurrent()
        assertEquals("confirm", controller.state.value.error)
        assertEquals(1, posts)
        controller.stop()
    }

    @Test fun backgroundCancelsOmpCommandPollingWithoutReplayingCommand() = runTest {
        val store = MemoryStore()
        var posts = 0
        var polls = 0
        val controller = MessageController({ _, body ->
            if (body != null) { posts++; "{\"id\":\"command\",\"status\":\"queued\"}" }
            else { polls++; "{\"id\":\"command\",\"status\":\"queued\"}" }
        }, store, this, { _, _ -> true }, {}, {})
        controller.start(); controller.open(key); controller.state.first { !it.loading }
        controller.shake("agent"); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, posts)
        assertEquals(1, polls)
        controller.stop(); runCurrent()
        assertEquals("unknown", controller.state.value.commandResult?.status)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, polls)
        controller.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, posts)
        assertEquals(1, polls)
        controller.stop()
    }

    @Test fun oldControlPollCannotReplaceTheNextOperation() = runTest {
        for (missing in listOf(false, true)) {
            val old = MessageOperation("old")
            val writing = CompletableDeferred<Unit>()
            val written = CompletableDeferred<Unit>()
            var local = MessageLocalState(control = old)
            var blockOldWrite = false
            val store = object : MessageStore {
                override suspend fun message(key: SessionKey) = local
                override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
                    val next = update(local)
                    local = next
                    if (blockOldWrite && next.control?.id == "old") {
                        blockOldWrite = false
                        writing.complete(Unit)
                        withContext(NonCancellable) { written.await() }
                    }
                    return next
                }
            }
            val controller = MessageController({ path, _ ->
                when (path) {
                    "/api/messages/control" -> """{"id":"new","status":"queued"}"""
                    "/api/messages/old" -> if (missing) throw ApiException(404) else """{"status":"delivered","error":"old error"}"""
                    else -> error("Pedido inesperado: $path")
                }
            }, store, this, { _, _ -> true }, {}, {})
            controller.start(); controller.open(key)
            controller.state.first { !it.loading }
            blockOldWrite = true
            writing.await()
            controller.newSession("agent", confirmed = true)
            controller.state.first { it.control?.id == "new" }
            written.complete(Unit)
            runCurrent()
            assertEquals(MessageOperation("new", "queued"), controller.state.value.control)
            assertEquals(MessageOperation("new", "queued"), local.control)
            assertNull(controller.state.value.controlError)
            controller.stop()
            runCurrent()
        }
    }
}
