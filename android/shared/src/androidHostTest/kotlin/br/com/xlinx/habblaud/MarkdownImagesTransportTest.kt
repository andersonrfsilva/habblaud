package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.IMAGE_MAX_BYTES
import br.com.xlinx.habblaud.data.remoteImageBytes
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkdownImagesTransportTest {
    @Test fun rejectsRedirectWithoutRequestingLocation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/other.png"))
            server.enqueue(MockResponse().setBody("redirected"))
            assertNull(remoteImageBytes(server.url("/image.png").toString()))
            assertEquals(1, server.requestCount)
            assertEquals("/image.png", server.takeRequest().path)
        }
    }

    @Test fun doesNotSendCookiesOrAuthorization() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image"))
            assertContentEquals("image".encodeToByteArray(), remoteImageBytes(server.url("/image.png").toString()))
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun preservesSmallResponseBytes() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(20 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertContentEquals(bytes, remoteImageBytes(server.url("/image.png").toString()))
        }
    }

    @Test fun rejectsOversizedBodiesWithAndWithoutContentLength() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(IMAGE_MAX_BYTES + 1)
            val url = server.url("/image.png").toString()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            assertNull(remoteImageBytes(url))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 64 * 1024))
            assertNull(remoteImageBytes(url))
        }
    }
}
