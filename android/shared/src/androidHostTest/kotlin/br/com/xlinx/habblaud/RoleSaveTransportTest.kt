package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.httpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleSaveTransportTest {
    @Test fun savesOnceAfterTheIdleConnectionExpires() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "GET" -> MockResponse().setHeader("Keep-Alive", "timeout=5").setBody("{}")
                    request.sequenceNumber > 0 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    else -> MockResponse().setResponseCode(202).setBody("accepted")
                }
            }
            server.start()
            val url = server.url("/api/cast/roles").toString()
            httpClient().use { client ->
                client.get(url).bodyAsText()
                delay(5_500)
                val body = buildJsonObject { put("roles", buildJsonObject { put("task", "provider/model:medium") }) }
                assertEquals("accepted", client.post(url) { setBody(body.toString()) }.bodyAsText())
                assertEquals(2, server.requestCount)
            }
        }
    }
}
