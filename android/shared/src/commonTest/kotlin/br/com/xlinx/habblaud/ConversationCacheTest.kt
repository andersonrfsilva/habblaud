package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.TerminalEntry
import br.com.xlinx.habblaud.data.decodeConversation
import br.com.xlinx.habblaud.data.encodeConversation
import br.com.xlinx.habblaud.data.summaryKey
import br.com.xlinx.habblaud.data.ConversationReading
import br.com.xlinx.habblaud.data.ReadingPosition
import br.com.xlinx.habblaud.data.readingIndex
import kotlin.test.*

class ConversationCacheTest {
    @Test fun savedConversationComesBackWithTheFieldsTheIncrementalInitNeeds() {
        val entries = listOf(
            TerminalEntry("u1:u", "user", "oi", at = 1000),
            TerminalEntry("tc1", "tool", "ls", title = "bash", truncated = true, at = 2000),
            TerminalEntry("tc1:r", "result", "ok", toolUseId = "tc1", at = 3000),
        )
        val back = decodeConversation(encodeConversation(entries, truncated = false))
        assertEquals(entries, back.entries)
        assertFalse(back.truncated)
    }

    @Test fun onlyTheLast500EntriesAreKeptAndTheCutIsMarked() {
        val entries = (1..600).map { TerminalEntry("e$it", "assistant", "t", at = it.toLong()) }
        val back = decodeConversation(encodeConversation(entries, truncated = false))
        assertEquals(500, back.entries.size)
        assertEquals("e101", back.entries.first().id)
        assertTrue(back.truncated)
    }

    @Test fun summariesComeBackOnlyForTheEntriesThatWereKept() {
        val entries = (1..501).map { TerminalEntry("e$it", "assistant", "texto $it", at = it.toLong()) }
        val summaries = mapOf(summaryKey(entries[0]) to "cortada", summaryKey(entries[500]) to "fica", "e501:outro-texto" to "velha")
        val back = decodeConversation(encodeConversation(entries, truncated = false, summaries = summaries))
        assertEquals(mapOf(summaryKey(entries[500]) to "fica"), back.summaries)
    }

    @Test fun readingStateSurvivesRestartAndExplicitCollapse() {
        val entries = listOf(TerminalEntry("reply", "assistant", "texto"))
        val reading = ConversationReading(position = ReadingPosition("reply", 137, false)).expand("reply", true)
        val back = decodeConversation(encodeConversation(entries, false, reading = reading)).reading
        assertEquals(reading, back)
        assertTrue("reply" in back.expanded)
        assertFalse("reply" in back.expand("reply", false).expanded)
        assertEquals(ConversationReading(), decodeConversation("""{"entries":[]}""").reading)
    }

    @Test fun expandedEntriesDropOldestAndReopeningRefreshesTheirOrder() {
        var reading = ConversationReading()
        repeat(500) { reading = reading.expand("e$it", true) }
        reading = reading.expand("e0", true).expand("new", true)
        assertEquals(500, reading.expanded.size)
        assertTrue("e0" in reading.expanded)
        assertFalse("e1" in reading.expanded)
        assertEquals(reading, decodeConversation(encodeConversation(emptyList(), false, reading = reading)).reading)
    }

    @Test fun restoreResolvesStableEntryAfterNewEntriesAndGroupUpdates() {
        val position = ReadingPosition("reply", 137, false)
        assertEquals(1, readingIndex(listOf("new", "reply", "tools"), position))
        assertEquals(3, readingIndex(listOf("newer", "new", "thinking", "reply", "tools"), position))
        assertEquals(137, position.offset)
        assertNull(readingIndex(emptyList(), position))
        assertNull(readingIndex(listOf("new"), position))
        assertEquals(0, readingIndex(listOf("new", "reply"), position.copy(following = true)))
    }
}
