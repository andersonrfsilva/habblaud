package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.snapshotMatchesSession
import org.junit.Assert.*
import org.junit.Test

class SessionIdentityTest {
    @Test fun serializationPreservesSeparatorsAndOrigin() {
        val key = SessionKey("https://example.test:443", "omp", "a:b/\"", "c:d")
        assertEquals(key, SessionKey.deserialize(key.serialize()))
        assertNotEquals(key, key.copy(origin = "http://example.test:443"))
        assertNotEquals(key, key.copy(provider = "claude"))
        assertNotEquals(key, key.copy(account = "a", sessionId = "b:c:d"))
    }
    @Test fun snapshotMustStillBindAgentToOriginalSession() {
        val key = SessionKey("https://example.test", "omp", "account", "old")
        fun snapshot(session: String, provider: String = "omp") =
            """{"agents":[{"id":"agent","kind":"main","account":"account","provider":"$provider","sessionId":"$session"}]}"""
        assertTrue(snapshotMatchesSession(snapshot("old"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("new"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("old", "claude"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("old"), key, "other"))
        assertFalse(snapshotMatchesSession("""{"agents":[]}""", key, "agent"))
    }
}
