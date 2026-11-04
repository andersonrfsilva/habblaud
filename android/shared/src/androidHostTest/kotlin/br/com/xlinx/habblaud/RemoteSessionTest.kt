package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.httpClient
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteSessionTest {

    @Test fun redirectIsNotFollowedAndOriginIsNotSent() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { destination ->
                destination.start()
                source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/api/access")))
                source.start()
                val client = httpClient()
                try {
                    assertEquals(302, client.get(source.url("/api/access").toString()).status.value)
                    val request = source.takeRequest()
                    assertNull(request.getHeader("Origin"))
                    assertNull(request.getHeader("Referer"))
                    assertNull(request.getHeader("X-Forwarded-For"))
                    assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
                } finally { client.close() }
            }
        }
    }
}
