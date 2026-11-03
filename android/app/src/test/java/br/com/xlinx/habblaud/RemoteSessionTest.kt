package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.origin
import br.com.xlinx.habblaud.data.httpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertNull
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RemoteSessionTest {
    @Test fun originsIncludeSchemeAndNonDefaultPort() {
        val origin = "https://example.com".toHttpUrl().origin()
        assertEquals(origin, "https://example.com:443".toHttpUrl().origin())
        assertNotEquals(origin, "http://example.com".toHttpUrl().origin())
        assertNotEquals(origin, "https://example.com:444".toHttpUrl().origin())
        assertNotEquals(origin, "https://other.example.com".toHttpUrl().origin())
    }

    @Test fun redirectIsNotFollowedAndOriginIsNotSent() {
        MockWebServer().use { source ->
            MockWebServer().use { destination ->
                destination.start()
                source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/api/access")))
                source.start()
                val response = httpClient().newCall(Request.Builder().url(source.url("/api/access")).build()).execute()
                response.use { assertEquals(302, it.code) }
                val request = source.takeRequest()
                assertNull(request.getHeader("Origin"))
                assertNull(request.getHeader("Referer"))
                assertNull(request.getHeader("X-Forwarded-For"))
                assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
            }
        }
    }
}
