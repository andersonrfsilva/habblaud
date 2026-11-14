package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class AntigravityResumeTest {
    private val key = SessionKey("https://example.invalid", "antigravity", "default", "a1234567")
    private val closed = SessionItem(key, null, "Conversa", "/project", "offline", null)
    private fun member(body: String) = wireJson.parseToJsonElement(body).jsonObject

    @Test fun ownerMatchesSessionAndProviderButIgnoresAccount() {
        val owner = member("""{"id":"owner","provider":"antigravity","account":"another","last":{"/project":"a1234567"}}""")
        assertEquals(owner, castOwner(listOf(owner), key))
        assertNull(castOwner(listOf(owner), key.copy(sessionId = "b1234567")))
        assertNull(castOwner(listOf(owner), key.copy(provider = "omp")))
        assertNull(castOwner(listOf(owner, owner), key))
        assertNull(castOwner(listOf(member("""{"provider":"antigravity"}""")), key))
        assertNull(castOwner(listOf(member("""{"provider":"omp","last":{"/project":"a1234567"}}""")), key))
    }

    @Test fun catalogReportsAntigravityAvailability() {
        assertTrue(parseCastModelCatalog("""{"provider":"omp","agyModels":["agy/default"]}""").agy)
        assertFalse(parseCastModelCatalog("""{"agyModels":[]}""").agy)
        assertFalse(parseCastModelCatalog("{}").agy)
    }

    @Test fun resumeUsesAntigravityAvailabilityRegardlessOfRuntime() {
        listOf("omp", "zeroclaw", null).forEach { runtime ->
            assertTrue(canResumeCast(closed, runtime, agy = true))
            assertFalse(canResumeCast(closed, runtime))
        }
        assertFalse(canResumeCast(closed.copy(agentId = "agent"), "omp", true))
        assertFalse(canResumeCast(closed.copy(project = "relative"), "omp", true))
        assertFalse(canResumeCast(closed.copy(key = key.copy(sessionId = "invalid")), "omp", true))
        assertFalse(canResumeCast(null, "omp", true))
        listOf("omp", "zeroclaw").forEach { provider ->
            val old = closed.copy(key = key.copy(provider = provider))
            assertTrue(canResumeCast(old, provider))
            assertTrue(canResumeCast(old, provider, true))
            assertFalse(canResumeCast(old, "other", true))
        }
    }
}
