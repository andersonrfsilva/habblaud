package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class DeliveriesApiTest {
    @Test fun readsListAndPreservesErrorStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"deliveries":[{"id":"d","account":"a","sessionId":"s","headline":"Ação"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"entregas desligadas"}"""))
            httpClient().use { client ->
                val request = client.prepareGet(server.url("/api/deliveries").toString())
                assertEquals("Ação", request.execute { readDeliveries(it) }.single().headline)
                val error = assertFailsWith<ApiException> { request.execute { readDeliveries(it) } }
                assertEquals(403, error.status)
                assertEquals("entregas desligadas", error.detail)
            }
        }
    }

    @Test fun rejectsOversizedBodiesWithOrWithoutContentLength() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(8 * 1024 * 1024 + 1)
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes), 64 * 1024))
            httpClient().use { client ->
                val request = client.prepareGet(server.url("/api/deliveries").toString())
                repeat(2) {
                    val error = assertFailsWith<IOException> { request.execute { readDeliveries(it) } }
                    assertEquals("Resposta grande demais", error.message)
                }
            }
        }
    }

    @Test fun downloadsExactBytesFromEncodedAttachmentPath() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(130 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            httpClient().use { client ->
                val output = ByteArrayOutputStream()
                client.downloadDelivery(server.url("/").toString().trimEnd('/'), "a/b ação?", { block, count -> output.write(block, 0, count) })
                assertContentEquals(bytes, output.toByteArray())
                assertEquals("/api/deliveries/file/a%2Fb%20a%C3%A7%C3%A3o%3F", server.takeRequest().path)
            }
        }
    }

    @Test fun missingAttachmentNeverWritesBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"anexo desconhecido"}"""))
            httpClient().use { client ->
                val error = assertFailsWith<ApiException> {
                    client.downloadDelivery(server.url("/").toString().trimEnd('/'), "missing", { _, _ -> fail("Erro não pode virar arquivo") })
                }
                assertEquals(404, error.status)
            }
        }
    }
}
