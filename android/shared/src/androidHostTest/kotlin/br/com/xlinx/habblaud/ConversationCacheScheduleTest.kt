package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.scheduleConversationSave
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCacheScheduleTest {
    @Test fun countsWritesDuringOneMinuteOfReading() = runTest {
        var pending: Job? = null
        var writes = 0
        readingMinute { delayMs ->
            pending = scheduleConversationSave(this, pending, delayMs) { writes++ }
        }
        println("Conversation cache writes (baseline, 60 s): $writes")
        assertEquals(24, writes)
    }

    private fun TestScope.readingMinute(persist: (Long) -> Unit) {
        repeat(60) { second ->
            persist(3_000) // One append to the open conversation.
            advanceTimeBy(250)
            if (second % 3 == 0) persist(500) // Twenty reading positions.
            advanceTimeBy(250)
            if (second == 10 || second == 31 || second == 50) persist(0) // Three summary expansions.
            advanceTimeBy(500)
        }
        persist(0) // Close the conversation.
    }
}
