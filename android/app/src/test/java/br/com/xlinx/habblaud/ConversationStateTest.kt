package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ConversationState
import br.com.xlinx.habblaud.data.TerminalEntry
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    private fun entry(id: String, text: String = id) = TerminalEntry(id, "assistant", text)
    @Test fun initReplacesAndAppendDeduplicates() {
        val state = ConversationState()
        state.init(listOf(entry("one")), true)
        state.append(listOf(entry("one"), entry("two")))
        assertEquals(listOf("one", "two"), state.snapshot().map { it.id })
        assertTrue(state.truncated)
        state.init(listOf(entry("three")), false)
        assertEquals(listOf("three"), state.snapshot().map { it.id })
        assertFalse(state.truncated)
    }
    @Test fun windowDropsOldEntries() {
        val state = ConversationState()
        state.append((0..1000).map { entry(it.toString()) })
        assertEquals(1000, state.snapshot().size)
        assertEquals("1", state.snapshot().first().id)
        assertTrue(state.reduced)
    }
    @Test fun oversizeEventIsRejectedWithoutChangingState() {
        val state = ConversationState()
        state.append(listOf(entry("ok")))
        try { state.append(listOf(entry("large", "x".repeat(ConversationState.MAX_BYTES)))) ; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(listOf("ok"), state.snapshot().map { it.id })
    }
}
