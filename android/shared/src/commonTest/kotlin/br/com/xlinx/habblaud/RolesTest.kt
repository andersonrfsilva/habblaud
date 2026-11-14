package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.joinRole
import br.com.xlinx.habblaud.data.modelBrand
import br.com.xlinx.habblaud.data.modelLabel
import br.com.xlinx.habblaud.data.modelOptionLabel
import br.com.xlinx.habblaud.data.providerLabel
import br.com.xlinx.habblaud.data.roleModelGroups
import br.com.xlinx.habblaud.data.snapshotModels
import br.com.xlinx.habblaud.data.splitRole
import kotlin.test.*

class RolesTest {
    @Test fun modelNamesAreReadableWithoutLosingVersionsDatesOrVariants() {
        val examples = listOf(
            "openrouter/anthropic/claude-opus-5.5" to "Claude Opus 5.5",
            "anthropic/claude-sonnet-4-5-20250929" to "Claude Sonnet 4.5 · 20250929",
            "claude-3-5-sonnet-20241022" to "Claude Sonnet 3.5 · 20241022",
            "openai-codex/gpt-6.1-sol" to "GPT-6.1 Sol",
            "openrouter/google/gemini-3.8-flash" to "Gemini 3.8 Flash",
            "openrouter/deepseek/deepseek-v4-flash:free" to "DeepSeek V4 Flash · Free",
            "openrouter/meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B Instruct",
            "openrouter/z-ai/glm-4.6" to "GLM 4.6",
            "openrouter/minimax/minimax-m2.5" to "MiniMax M2.5",
            "openrouter/qwen/qwen3-vl-235b-a22b-instruct" to "Qwen3 VL 235B A22b Instruct",
            "vendor/custom_model-v2:exact" to "Custom Model V2 · Exact",
            "vendor/custom_model-v2-20260101:thinking" to "Custom Model V2 · 20260101 · Thinking",
        )
        examples.forEach { (raw, label) ->
            assertEquals(label, modelOptionLabel(raw), raw)
            assertEquals(label, modelLabel(raw.substringAfterLast('/')), raw)
        }
        assertEquals("Anthropic · Claude Sonnet 4.5 · 20250929", modelLabel("anthropic/claude-sonnet-4-5-20250929"))
    }

    @Test fun normalizedSearchKeepsExactModelIdsAndRawVersionOrder() {
        val models = listOf(
            "anthropic/claude-opus-4-20250514",
            "anthropic/claude-opus-4-8",
            "anthropic/claude-opus-5-5",
            "anthropic/claude-opus-5",
        )
        val expected = listOf(models[2], models[3], models[1], models[0])
        assertEquals(listOf("anthropic" to expected), roleModelGroups(models, query = "Claude Opus"))
        assertEquals(listOf(models[2]), roleModelGroups(models, query = "Opus 5.5").single().second)
        assertEquals(listOf(models[0]), roleModelGroups(models, query = "20250514").single().second)
        assertEquals(listOf(models[1]), roleModelGroups(models, query = "claude-opus-4-8").single().second)
        val variant = "local/custom_model-v2:exact"
        assertEquals(listOf("local" to listOf(variant)), roleModelGroups(models + variant, query = "Custom Model Exact"))
    }

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

    @Test fun pickerGroupsByProviderAndOpenRouterVendorNewestFirstWithoutNoisyVariantsOrDatedCopies() {
        val models = listOf(
            "openrouter/~anthropic/claude-opus-latest", "openrouter/google/gemini-2.5-flash:batch", "openrouter/google/gemini-2.5-flash:free",
            "anthropic/claude-opus-4-8", "openrouter/google/gemini-2.5-flash", "openrouter/deepseek/deepseek-v4-pro",
            "anthropic/claude-haiku-4-5-20251001", "anthropic/claude-opus-5", "anthropic/claude-haiku-4-5", "anthropic/claude-opus-5-5",
            "anthropic/claude-opus-4-20250514", "google-antigravity/gemini-3.8-flash", "google-antigravity/gemini-3.5-flash-lite",
            "google-antigravity/gemini-3.5-flash",
        )
        assertEquals(listOf(
            "anthropic" to listOf("anthropic/claude-haiku-4-5", "anthropic/claude-opus-5-5", "anthropic/claude-opus-5", "anthropic/claude-opus-4-8", "anthropic/claude-opus-4-20250514"),
            "google-antigravity" to listOf("google-antigravity/gemini-3.8-flash", "google-antigravity/gemini-3.5-flash", "google-antigravity/gemini-3.5-flash-lite"),
            "openrouter/deepseek" to listOf("openrouter/deepseek/deepseek-v4-pro"),
            "openrouter/google" to listOf("openrouter/google/gemini-2.5-flash"),
        ), roleModelGroups(models))
        assertEquals("OpenRouter · DeepSeek", providerLabel("openrouter/deepseek"))
        assertEquals("DeepSeek V4 Pro", modelOptionLabel("openrouter/deepseek/deepseek-v4-pro"))
        assertEquals("OpenRouter · DeepSeek · DeepSeek V4 Pro", modelLabel("openrouter/deepseek/deepseek-v4-pro"))
        // Filtro por busca mantém o modelo atual.
        assertEquals(listOf(
            "anthropic" to listOf("anthropic/claude-opus-5-5"),
            "openrouter/deepseek" to listOf("openrouter/deepseek/deepseek-v4-pro"),
        ), roleModelGroups(models, keep = "anthropic/claude-opus-5-5", query = "deepseek"))
        // O modelo atual do papel nunca some, mesmo sendo uma variante escondida.
        assertEquals(listOf("anthropic/claude-haiku-4-5", "anthropic/claude-haiku-4-5-20251001"), roleModelGroups(listOf("anthropic/claude-haiku-4-5"), keep = "anthropic/claude-haiku-4-5-20251001").single().second)
    }

    @Test fun searchMatchesModelProviderAndManufacturerWithAllTermsIgnoringCase() {
        val model = "openrouter/mistralai/mistral-large"
        val models = listOf(model, "anthropic/claude-opus-5", "openai/gpt-5")
        listOf("mistral-large", "OPENROUTER", "Mistral", "  openrouter \t MISTRAL\nlarge  ").forEach { query ->
            assertEquals(listOf("openrouter/mistralai" to listOf(model)), roleModelGroups(models, query = query))
        }
        assertTrue(roleModelGroups(models, query = "openrouter claude").isEmpty())
        assertTrue(roleModelGroups(models, query = "missing-model").isEmpty())
        assertEquals(roleModelGroups(models), roleModelGroups(models, query = " \t\n"))
    }

    @Test fun newNoisySuffixesOnlyHideOpenRouterVariantsAndNeverRewriteIds() {
        val visible = listOf(
            "local/model:free", "local/model:extended",
            "openrouter/vendor/model", "openrouter/vendor/model:thinking", "openrouter/vendor/model:exact",
            "openrouter/other/model", "openrouter/vendor/only-dated-20260101",
        )
        val hidden = listOf(
            "local/model:batch",
            "openrouter/vendor/model:free", "openrouter/vendor/model:batch", "openrouter/vendor/model:extended",
            "openrouter/~vendor/model", "openrouter/vendor/model-20260101",
        )
        assertEquals(visible.toSet(), roleModelGroups(visible + hidden + visible).flatMap { it.second }.toSet())
        hidden.forEach { current ->
            val groups = roleModelGroups(visible + hidden, keep = current, query = "no-match")
            assertEquals(listOf(current), groups.flatMap { it.second })
        }
        assertEquals("Model", modelOptionLabel("openrouter/~vendor/model"))
        assertEquals("OpenRouter · vendor · Model", modelLabel("openrouter/~vendor/model"))
    }

    @Test fun currentModelStaysEvenWhenAbsentFromCatalogAndQueryChanges() {
        val current = "openrouter/~vendor/current:free"
        val models = listOf("openrouter/vendor/model")
        listOf("", "m", "model", "no-match", "").forEach { query ->
            val shown = roleModelGroups(models, keep = current, query = query).flatMap { it.second }
            assertTrue(current in shown)
            assertEquals(shown.size, shown.toSet().size)
        }
        assertEquals(listOf(current), roleModelGroups(emptyList(), keep = current, query = "no-match").single().second)
    }

    @Test fun numericVersionsAndAlphanumericSuffixesHaveConsistentOrder() {
        val expected = listOf("openrouter/vendor/model-2", "openrouter/vendor/model-1", "openrouter/vendor/model-1b")
        expected.indices.forEach { offset ->
            val input = expected.drop(offset) + expected.take(offset)
            listOf(input, input.reversed()).forEach { models ->
                assertEquals(expected, roleModelGroups(models).single().second)
            }
        }
    }
}
