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
}
