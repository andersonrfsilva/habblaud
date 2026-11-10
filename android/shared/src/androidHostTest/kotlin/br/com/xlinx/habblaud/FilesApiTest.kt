package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class FilesApiTest {
    @Test fun streamsExactBytesWithProgress() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val client = httpClient()
            try {
                val result = ByteArrayOutputStream()
                val counts = mutableListOf<Long>()
                client.prepareGet(server.url("/api/files/raw").toString()).execute { response ->
                    streamFileDownload(response, { block, count -> assertTrue(count <= 65536); result.write(block, 0, count) }) { received, total ->
                        assertEquals(bytes.size.toLong(), total)
                        counts += received
                    }
                }
                assertContentEquals(bytes, result.toByteArray())
                assertEquals(0L, counts.first())
                assertEquals(bytes.size.toLong(), counts.last())
                assertTrue(counts.size > 2)
            } finally { client.close() }
        }
    }
    @Test fun deniedResponseNeverWritesBytes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"terminal desligado"}"""))
            val client = httpClient()
            try {
                val error = assertFailsWith<ApiException> {
                    client.prepareGet(server.url("/api/files/raw").toString()).execute { response ->
                        streamFileDownload(response, { _, _ -> fail("Erro não pode virar arquivo") })
                    }
                }
                assertEquals(403, error.status)
                assertEquals("terminal desligado", error.detail)
            } finally { client.close() }
        }
    }
    @Test fun cancellationStopsStreamImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1024 * 1024))))
            val client = httpClient()
            try {
                var writes = 0
                val job = launch {
                    client.prepareGet(server.url("/api/files/raw").toString()).execute { response ->
                        streamFileDownload(response, { _, _ -> writes++; currentCoroutineContext().cancel(); yield() })
                    }
                }
                job.join()
                assertTrue(job.isCancelled)
                assertEquals(1, writes)
            } finally { client.close() }
        }
    }
}
