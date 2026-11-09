package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.joinRole
import br.com.xlinx.habblaud.data.modelBrand
import br.com.xlinx.habblaud.data.roleModelGroups
import br.com.xlinx.habblaud.data.snapshotModels
import br.com.xlinx.habblaud.data.splitRole
import kotlin.test.*

class RolesTest {
    @Test fun modelBrandByFamilyOnlyForKnownNames() {
        assertEquals("claude", modelBrand("claude-opus-5-5"))
        assertEquals("claude", modelBrand("anthropic/claude-sonnet-5-5"))
        assertEquals("openai", modelBrand("gpt-5.5"))
        assertEquals("openai", modelBrand("o3-mini"))
        assertEquals("openai", modelBrand("codex-mini-latest"))
        assertEquals("gemini", modelBrand("gemini-3-pro"))
        assertNull(modelBrand("ollama-o1x"))
        assertNull(modelBrand("llama3"))
        assertNull(modelBrand(null))
    }

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

    @Test fun pickerGroupsByProviderNewestFirstWithoutBatchOrDatedCopies() {
        val models = listOf(
            "openrouter/google/gemini-2.5-flash:batch", "anthropic/claude-opus-4-8", "openrouter/google/gemini-2.5-flash",
            "anthropic/claude-haiku-4-5-20251001", "anthropic/claude-opus-5", "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5-5",
            "anthropic/claude-opus-4-20250514", "google-antigravity/gemini-3.8-flash", "google-antigravity/gemini-3.5-flash-lite",
            "google-antigravity/gemini-3.5-flash",
        )
        assertEquals(listOf(
            "anthropic" to listOf("anthropic/claude-haiku-4-5", "anthropic/claude-opus-5-5", "anthropic/claude-opus-5", "anthropic/claude-opus-4-8", "anthropic/claude-opus-4-20250514"),
            "google-antigravity" to listOf("google-antigravity/gemini-3.8-flash", "google-antigravity/gemini-3.5-flash", "google-antigravity/gemini-3.5-flash-lite"),
            "openrouter" to listOf("openrouter/google/gemini-2.5-flash"),
        ), roleModelGroups(models))
        // O modelo atual do papel nunca some, mesmo sendo uma variante escondida.
        assertEquals(listOf("anthropic/claude-haiku-4-5", "anthropic/claude-haiku-4-5-20251001"), roleModelGroups(listOf("anthropic/claude-haiku-4-5"), keep = "anthropic/claude-haiku-4-5-20251001").single().second)
    }
}
