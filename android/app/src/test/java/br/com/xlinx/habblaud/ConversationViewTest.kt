package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.TerminalEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationViewTest {
    private val entries = listOf("user", "thinking", "tool", "result", "assistant", "system").map { TerminalEntry(it, it, it) }
    private fun kinds(view: String) = visibleEntries(entries, view).map { it.kind }

    @Test fun promptsShowsOnlyYourMessagesAndAgentReplies() = assertEquals(listOf("user", "assistant"), kinds("prompts"))
    @Test fun noToolsKeepsThinkingAndSystem() = assertEquals(listOf("user", "thinking", "assistant", "system"), kinds("no_tools"))
    @Test fun allShowsEverything() = assertEquals(entries.map { it.kind }, kinds("all"))

    @Test fun thinkingOfATurnBecomesOneCountedRowWithAllText() {
        val rows = groupThinking(listOf(
            TerminalEntry("t1", "thinking", "um"), TerminalEntry("x", "tool", "ls"), TerminalEntry("t2", "thinking", ""),
            TerminalEntry("t3", "thinking", "três"), TerminalEntry("a", "assistant", "ok"),
            TerminalEntry("u", "user", "próximo"), TerminalEntry("t4", "thinking", "quatro"),
        ))
        assertEquals(listOf("t1" to 3, "x" to 1, "a" to 1, "u" to 1, "t4" to 1), rows.map { it.first.id to it.second })
        assertEquals("um\n\ntrês", rows[0].first.text)
    }
}
