package br.com.xlinx.habblaud.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ServersTest {
    @Test fun legacyCookies() {
        assertEquals(mapOf("https://a.example" to "habblaud_remote=a"), decodeCookies("""{"origin":"https://a.example","cookie":"habblaud_remote=a"}"""))
    }

    @Test fun cookiesRoundTrip() {
        val cookies = mapOf("https://a.example" to "habblaud_remote=a", "https://b.example" to "habblaud_remote=b")
        assertEquals(cookies, decodeCookies(encodeCookies(cookies)))
        assertEquals(cookies, decodeCookies("""{"v":2,"cookies":{"https://a.example":"habblaud_remote=a","https://b.example":"habblaud_remote=b"}}"""))
        assertEquals(emptyMap(), decodeCookies("invalid"))
    }

    @Test fun mergedSessionsKeepOriginAndFilter() {
        val a = SessionItem(SessionKey("https://a.example", "omp", "account", "same"), null, "A", null, null, null)
        val b = a.copy(key = a.key.copy(origin = "https://b.example"), title = "B")
        val states = linkedMapOf(a.key.origin to ReadingState(sessions = listOf(a)), b.key.origin to ReadingState(sessions = listOf(b)))
        assertEquals(listOf(a, b), mergeSessions(states, null))
        assertEquals(listOf(a), mergeSessions(states, a.key.origin))
        assertEquals(listOf(b), mergeSessions(states, b.key.origin))
        assertEquals(emptyList(), mergeSessions(states, "https://unknown.example"))
    }

    @Test fun labelsOmitOnlyDefaultPorts() {
        assertEquals("a.example", serverLabel("https://a.example:443"))
        assertEquals("a.example", serverLabel("http://a.example:80"))
        assertEquals("a.example:8443", serverLabel("https://a.example:8443"))
        assertEquals("a.example:8080", serverLabel("http://a.example:8080"))
    }
}
