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

    @Test fun responseErrorIsRecoveredOnlyWhenTheAgentContinuesInTheSameTurn() {
        // 12:14: resposta cortada (texto já veio) → erro → continuação; depois um erro final sem continuação antes da sua mensagem.
        val recovered = recoveredErrors(listOf(
            TerminalEntry("u1", "user", "eae"), TerminalEntry("a1:t0", "assistant", "Ainda há três"), TerminalEntry("a1:err", "system", "Erro: socket"),
            TerminalEntry("a2:t0", "assistant", "mais campos"), TerminalEntry("a3:err", "system", "Erro: socket"), TerminalEntry("a4:k0", "thinking", ""),
            TerminalEntry("a5:err", "system", "Erro: limite"), TerminalEntry("u2", "user", "sim"), TerminalEntry("x:int", "system", "Interrompido"),
        ))
        assertEquals(setOf("a1:err", "a3:err"), recovered)
    }
}
