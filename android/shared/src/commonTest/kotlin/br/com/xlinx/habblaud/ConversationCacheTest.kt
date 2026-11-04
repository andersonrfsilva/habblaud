package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.TerminalEntry
import br.com.xlinx.habblaud.data.decodeConversation
import br.com.xlinx.habblaud.data.encodeConversation
import br.com.xlinx.habblaud.data.summaryKey
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
}
