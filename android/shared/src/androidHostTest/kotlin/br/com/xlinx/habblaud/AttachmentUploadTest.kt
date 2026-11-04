package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AttachmentUploadTest {
    private fun body(bytes: ByteArray, size: Long? = bytes.size.toLong()) = AttachmentBody(size, {
        val input = ByteArrayInputStream(bytes)
        object : AttachmentSource {
            override fun read(buffer: ByteArray) = input.read(buffer)
            override fun close() = input.close()
        }
    })

    @Test fun binaryContentAndNameAreExact() = runBlocking {
        val bytes = byteArrayOf(0, 1, 127, -1, 43, 32)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val name = encodeAttachmentName("ação + (1).png")
            assertEquals("a%C3%A7%C3%A3o%20%2B%20(1).png", name)
            val client = httpClient()
            try {
                val attachment = body(bytes)
                val response = client.post(server.url("/api/messages/attachments").toString()) {
                    header("X-Habblaud-File-Name", name)
                    header("X-Habblaud-File-Type", "image/png")
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.OctetStream
                        override val contentLength = attachment.size
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            attachment.writeTo { chunk, count -> channel.writeFully(chunk, 0, count) }
                        }
                    })
                }
                assertEquals(201, response.status.value)
            } finally { client.close() }
            val recorded = server.takeRequest()
            assertArrayEquals(bytes, recorded.body.readByteArray())
            assertEquals(name, recorded.getHeader("X-Habblaud-File-Name"))
            assertEquals(bytes.size.toString(), recorded.getHeader("Content-Length"))
        } finally { server.shutdown() }
    }

}
