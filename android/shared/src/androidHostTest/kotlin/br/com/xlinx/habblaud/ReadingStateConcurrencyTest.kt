package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ReadingState
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.publishUnread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingStateConcurrencyTest {
    @Test fun refreshingUnreadKeepsConcurrentReadingLoad() {
        val key = SessionKey("https://example.test", "omp", "account", "session")
        val item = SessionItem(key, "agent", "title", null, null, null, replies = listOf(2L))
        val iterations = 200
        val outcomes = runBlocking {
            List(iterations) {
                val state = MutableStateFlow(ReadingState(sessions = listOf(item)))
                val computingUnread = CountDownLatch(1)
                val readingPublished = CountDownLatch(1)
                val refresh = async(Dispatchers.Default) {
                    state.publishUnread(state.value, null, mapOf(key.sessionId to 1L)) {
                        // Pause the computation after it reads state but before it publishes the copy.
                        computingUnread.countDown()
                        if (readingPublished.await(5, SECONDS)) it.sessionId else ""
                    }
                }
                withContext(Dispatchers.Default) {
                    if (computingUnread.await(5, SECONDS)) state.update { it.copy(readingLoaded = true) }
                    readingPublished.countDown()
                }
                refresh.await()
                state.value
            }
        }
        assertEquals(iterations, outcomes.count { it.unread == mapOf(key to 1) }, "unread updates")
        assertEquals(iterations, outcomes.count { it.readingLoaded }, "reading loads preserved")
    }
}
