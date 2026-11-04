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

    @Test fun thinkingAndToolsOfATurnBecomeOneCountedRowEach() {
        val rows = groupTurn(listOf(
            TerminalEntry("t1", "thinking", "um"), TerminalEntry("x", "tool", "ls"), TerminalEntry("rx", "result", "a.txt", toolUseId = "x"),
            TerminalEntry("t2", "thinking", ""), TerminalEntry("y", "tool", "cat", at = 9), TerminalEntry("ry", "result", "conteúdo", toolUseId = "y"),
            TerminalEntry("t3", "thinking", "três"), TerminalEntry("a", "assistant", "ok"), TerminalEntry("ro", "result", "sem ferramenta", toolUseId = "fora"),
            TerminalEntry("u", "user", "próximo"), TerminalEntry("t4", "thinking", "quatro"), TerminalEntry("z", "tool", "pwd"),
        ))
        // O resultado de uma ferramenta da lista não vira linha (fica no grupo); o de uma ferramenta fora da lista fica.
        assertEquals(listOf("t1" to 3, "x" to 2, "a" to 1, "ro" to 1, "u" to 1, "t4" to 1, "z" to 1), rows.map { it.entry.id to it.count })
        assertEquals("um\n\ntrês", rows[0].entry.text)
        // O grupo das ferramentas fica no lugar da primeira, leva a hora da última e lista todas, na ordem.
        assertEquals(listOf("x", "y"), rows[1].tools.map { it.id })
        assertEquals(9L, rows[1].entry.at)
        assertEquals(listOf("z"), rows[6].tools.map { it.id })
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
