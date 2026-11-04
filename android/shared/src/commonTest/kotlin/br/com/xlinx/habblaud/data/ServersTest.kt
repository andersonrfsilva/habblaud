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
}
