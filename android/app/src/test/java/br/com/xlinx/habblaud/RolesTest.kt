package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.joinRole
import br.com.xlinx.habblaud.data.snapshotModels
import br.com.xlinx.habblaud.data.splitRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RolesTest {
    @Test fun thinkingSuffixOnlyWhenItIsAKnownLevel() {
        assertEquals("openai-codex/gpt-6-luna" to "high", splitRole("openai-codex/gpt-6-luna:high"))
        assertEquals("ollama/llama3:8b" to null, splitRole("ollama/llama3:8b"))
        assertEquals("ollama/llama3:8b" to "low", splitRole("ollama/llama3:8b:low"))
        assertEquals("typesafe/jev-latest" to null, splitRole("typesafe/jev-latest"))
        assertEquals("a/b:max", joinRole("a/b", "max"))
        assertEquals("a/b", joinRole("a/b", null))
    }

    @Test fun modelsAnnouncedByOpenAgentsWithoutRepeats() {
        val snapshot = """{"agents":[{"models":["b/y","a/x"]},{"models":["a/x",3]},{}]}"""
        assertEquals(listOf("a/x", "b/y"), snapshotModels(snapshot))
    }
}
