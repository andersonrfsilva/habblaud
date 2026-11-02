package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionKey
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
}
