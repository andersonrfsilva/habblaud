package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ConversationRepository
import br.com.xlinx.habblaud.data.FAILURES_BEFORE_NOTICE
import br.com.xlinx.habblaud.data.StreamEvent
import br.com.xlinx.habblaud.data.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.sse.SSESession
import io.ktor.client.request.HttpResponseData
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.plugins.sse.SSE
import org.junit.After
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ConversationStreamTest {
    private val clients = mutableListOf<HttpClient>()
    private fun client() = httpClient().also { clients += it }
    @After fun closeClients() { clients.forEach { it.close() } }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun successfulConnectionResetsRetryDelay() = runTest {
        var requests = 0
        val engine = object : MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                requests++
                when (requests) {
                    1, 2 -> respond("", HttpStatusCode.InternalServerError)
                    3 -> {
                        val response = respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                        HttpResponseData(
                            response.statusCode, response.requestTime, response.headers, response.version,
                            object : SSESession {
                                override val coroutineContext = response.callContext
                                override val incoming = emptyFlow<ServerSentEvent>()
                            },
                            response.callContext,
                        )
                    }
                    4 -> respond("", HttpStatusCode.NotFound)
                    else -> error("Unexpected stream request $requests")
                }
            }
        }) {
            // MockEngine does not adapt byte channels into SSE sessions.
            override val supportedCapabilities = super.supportedCapabilities + SSECapability
        }
        val client = HttpClient(engine) {
            install(SSE) { maxReconnectionAttempts = 0 }
        }.also { clients += it }
        val repo = ConversationRepository("https://example.test", { client }, {})
        var connectedAt: Long? = null
        var lastDelay: Long? = null
        val failures = mutableListOf<Int?>()
        repo.stream("/api/stream").collect { event ->
            // A conexão vazia cai logo depois de abrir: o próximo pedido mede o atraso, que volta a 2 s.
            if (event is StreamEvent.Connected) connectedAt = currentTime
            if (event is StreamEvent.Failure) failures += event.status
            if (event is StreamEvent.Failure && event.status == 404) lastDelay = currentTime - checkNotNull(connectedAt)
        }
        assertEquals(6_000L, connectedAt)
        assertEquals(2_000L, lastDelay)
        assertEquals(8_000L, currentTime)
        assertEquals(4, requests)
        // As duas falhas 500 antes da conexão e a queda depois dela não viram aviso; o 404 é definitivo e aparece.
        assertEquals(listOf<Int?>(404), failures)
    }

    @Test fun transientFailuresBecomeNoticeOnlyOnTheThirdInARow() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            repeat(FAILURES_BEFORE_NOTICE) { server.enqueue(MockResponse().setResponseCode(500)) }
            val client = client()
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, {})
            val first = withTimeout(15000) { repo.stream("/api/stream").first { it is StreamEvent.Failure } as StreamEvent.Failure }
            assertEquals(500, first.status)
            assertEquals(FAILURES_BEFORE_NOTICE, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun parsesMultilineDataAndComments() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\r\nevent: append\r\ndata: one\r\ndata: two\r\n\r\n"))
            val client = client()
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, {})
            val event = withTimeout(5000) { repo.stream("/api/stream").first { it is StreamEvent.Data } } as StreamEvent.Data
            assertEquals("append", event.type)
            assertEquals("one\ntwo", event.data)
        } finally { server.shutdown() }
    }

    @Test fun unauthorizedStopsWithoutRetry() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            var unauthorized = false
            val client = client()
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, { unauthorized = true })
            val events = withTimeout(5000) { repo.stream("/api/stream").toList() }
            assertTrue(unauthorized)
            assertEquals(401, (events.last() as StreamEvent.Failure).status)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellationStopsCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        val socketClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val client = HttpClient(OkHttp) {
            engine { preconfigured = socketClient }
            install(SSE) { maxReconnectionAttempts = 0 }
        }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, {})
            val job = launch { repo.stream("/api/stream").toList() }
            withTimeout(5000) { while (server.requestCount == 0) delay(10) }
            job.cancelAndJoin()
            withTimeout(5000) { while (socketClient.dispatcher.runningCallsCount() != 0) delay(10) }
        } finally { client.close(); socketClient.dispatcher.cancelAll(); server.shutdown() }
    }
}
