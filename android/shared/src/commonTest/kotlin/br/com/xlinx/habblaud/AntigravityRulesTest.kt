package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlin.test.*

class AntigravityRulesTest {
    @Test fun modelPrefixAndToolCrossing() {
        assertTrue(isAgyModel("agy/x"))
        listOf("google-antigravity/x", "agy/", null).forEach { assertFalse(isAgyModel(it)) }
        assertTrue(crossesTool("omp", "agy/x"))
        assertFalse(crossesTool("omp", "google-antigravity/x"))
        assertFalse(crossesTool("antigravity", "agy/x"))
        assertTrue(crossesTool("antigravity", "google-antigravity/x"))
        listOf("claude", "codex", "zeroclaw", null).forEach { provider ->
            assertFalse(crossesTool(provider, "agy/x"))
            assertFalse(crossesTool(provider, "openai/x"))
            assertFalse(modelChoice(provider))
        }
        assertTrue(modelChoice("omp"))
        assertTrue(modelChoice("antigravity"))
    }

    @Test fun sessionControlsWithoutOmpToolsOrDeliveryModes() {
        listOf("omp", "zeroclaw", "antigravity").forEach { assertTrue(supportsSessionControl(it)) }
        listOf("claude", "codex", null).forEach { assertFalse(supportsSessionControl(it)) }
        listOf("aside", "steer", "followUp").forEach { assertNull(messageDelivery("antigravity", it)) }
        assertEquals(emptyList(), deliveryModes("antigravity"))
        assertFalse(ompTools("antigravity"))
        assertEquals("Antigravity", providerLabel("agy"))
    }
}
