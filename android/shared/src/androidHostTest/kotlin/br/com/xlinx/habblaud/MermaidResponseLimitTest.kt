package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.MERMAID_SVG_MAX_BYTES
import br.com.xlinx.habblaud.data.httpClient
import br.com.xlinx.habblaud.data.mermaidResponseBytes
import io.ktor.client.request.preparePost
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MermaidResponseLimitTest {
    private val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 10"/>"""

    private suspend fun mermaid(response: MockResponse): ByteArray? = MockWebServer().use { server ->
        server.enqueue(response)
        httpClient().use { client ->
            client.preparePost(server.url("/api/mermaid").toString()).execute { mermaidResponseBytes(it) }
        }
    }

    @Test fun returnsExactSvgBytes() = runBlocking {
        val bytes = mermaid(MockResponse().setHeader("Content-Type", "image/svg+xml; charset=utf-8").setBody(svg))
        assertContentEquals(svg.encodeToByteArray(), bytes)
    }

    @Test fun rejectsOversizedChunkedSvg() = runBlocking {
        val body = Buffer().write(ByteArray(MERMAID_SVG_MAX_BYTES + 1))
        val response = MockResponse().setHeader("Content-Type", "image/svg+xml").setChunkedBody(body, 8192)
        assertNull(mermaid(response))
    }

    @Test fun rejectsUnexpectedMediaType() = runBlocking {
        assertNull(mermaid(MockResponse().setHeader("Content-Type", "text/html").setBody(svg)))
    }

    @Test fun rejectsErrorEvenWithSvgType() = runBlocking {
        assertNull(mermaid(MockResponse().setResponseCode(422).setHeader("Content-Type", "image/svg+xml").setBody(svg)))
    }
}
