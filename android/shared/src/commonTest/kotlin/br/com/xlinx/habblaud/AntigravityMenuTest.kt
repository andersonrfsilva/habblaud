package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class AntigravityMenuTest {
    @Test fun menuControlGateKeepsOmpOnlyCommandsPrivate() {
        listOf("model", "thinking", "shake").forEach { assertTrue(supportsMenuControl("omp", it)) }
        assertTrue(supportsMenuControl("antigravity", "model"))
        listOf("thinking", "shake").forEach { assertFalse(supportsMenuControl("antigravity", it)) }
        listOf("claude", "codex", "zeroclaw", null).forEach { assertFalse(supportsMenuControl(it, "model")) }
    }

    @Test fun commandReasonIsOptionalAndPreserved() {
        assertNull(CommandResult("model", "failed").reason)
        val reason = "Destino indisponível"
        assertEquals(reason, CommandResult("model", "failed", reason).reason)
        assertEquals(reason, CommandResult("model", "failed", reason).copy().reason)
    }

    @Test fun providerDisplayKeepsOtherProvidersRaw() {
        assertEquals("Antigravity", providerDisplayName("antigravity"))
        listOf("omp", "zeroclaw", "google-antigravity", "unknown").forEach {
            assertEquals(it, providerDisplayName(it))
        }
    }

    @Test fun snapshotParserReadsPendingHandoffWithoutChangingIdentity() {
        val body = """{"provider":"antigravity","account":"default","sessionId":"a1234567","kind":"main","id":"agent","model":"agy/default","models":["agy/default","openai/x"],"castOpened":true,"handoff":true}"""
        fun parse(value: String) = parseSessionItem(wireJson.parseToJsonElement(value).jsonObject, "/project", "https://example.invalid", 0)
        val item = assertNotNull(parse(body))
        assertTrue(item.handoff)
        assertTrue(item.castOpened)
        assertEquals("antigravity", item.key.provider)
        assertEquals("agy/default", item.model)
        assertEquals(listOf("agy/default", "openai/x"), item.models)
        assertFalse(assertNotNull(parse(body.replace(",\"handoff\":true", ""))).handoff)
        assertFalse(assertNotNull(parse(body.replace("\"handoff\":true", "\"handoff\":false"))).handoff)
        assertNull(parseSessionItem(wireJson.parseToJsonElement("{}").jsonObject, null, "https://example.invalid", 0))
    }
}
