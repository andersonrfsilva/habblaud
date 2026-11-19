package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ConversationCacheSchedule
import br.com.xlinx.habblaud.data.launchPersist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCacheScheduleTest {
    @Test fun countsWritesDuringOneMinuteOfReading() = runTest {
        var writes = 0
        val cache = ConversationCacheSchedule(this) { writes++; null }
        readingMinute(cache::schedule)
        cache.flush()
        println("Conversation cache writes (batched, 60 s): $writes")
        assertEquals(17, writes) // Sixteen timer writes, plus the close flush.
    }

    @Test fun continuousEventsDoNotPushBackTheDeadline() = runTest {
        val writes = mutableListOf<Long>()
        val cache = ConversationCacheSchedule(this) { writes.add(currentTime); null }
        repeat(30) {
            cache.schedule()
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals((3_000L..30_000L step 3_000).toList(), writes)
    }

    @Test fun closingFlushesLatestStateEvenIfScreenScopeIsCanceled() = runTest {
        val screen = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val writes = mutableListOf<String>()
        var reading = "top"
        val cache = ConversationCacheSchedule(screen) { writes.add(reading); null }
        cache.schedule()
        advanceTimeBy(1_000)
        reading = "bottom"
        cache.schedule()
        screen.cancel()

        cache.flush()

        assertEquals(listOf("bottom"), writes)
        assertEquals(1_000L, currentTime)
        advanceTimeBy(4_000)
        assertEquals(listOf("bottom"), writes)
    }

    @Test fun reopeningWaitsForTheCloseWriteAndReadsLatestState() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        var stored = "old"
        var reading = "new"
        val cache = ConversationCacheSchedule(this) {
            val snapshot = reading
            launchPersist(this) { releaseWrite.await(); stored = snapshot }
        }
        cache.schedule()
        cache.flush()
        reading = ""
        var reopened: String? = null
        launch { cache.awaitWrite(); reopened = stored }

        runCurrent()
        assertEquals("old", stored)
        assertNull(reopened)
        releaseWrite.complete(Unit)
        runCurrent()
        assertEquals("new", reopened)
    }

    private fun TestScope.readingMinute(change: () -> Unit) {
        repeat(60) { second ->
            change() // One append to the open conversation.
            advanceTimeBy(250)
            if (second % 3 == 0) change() // Twenty reading positions.
            advanceTimeBy(250)
            if (second == 10 || second == 31 || second == 50) change() // Three summary expansions.
            advanceTimeBy(500)
        }
    }
}
