package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ConversationRepository
import br.com.xlinx.habblaud.data.FAILURES_BEFORE_NOTICE
import br.com.xlinx.habblaud.data.StreamEvent
import br.com.xlinx.habblaud.data.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import org.junit.After
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    @Test fun successfulConnectionResetsRetryDelay() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
            server.enqueue(MockResponse().setResponseCode(404))
            val client = client()
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, {})
            var connectedAt = 0L
            var lastDelay = 0L
            val failures = mutableListOf<Int?>()
            withTimeout(15000) {
                repo.stream("/api/stream").collect { event ->
                    // A conexão vazia cai logo depois de abrir: o próximo pedido mede o atraso, que volta a 2 s.
                    if (event is StreamEvent.Connected) connectedAt = System.nanoTime()
                    if (event is StreamEvent.Failure) failures += event.status
                    if (event is StreamEvent.Failure && event.status == 404) lastDelay = (System.nanoTime() - connectedAt) / 1_000_000
                }
            }
            assertTrue(connectedAt != 0L)
            assertTrue("retry delay was $lastDelay ms", lastDelay in 1800..3999)
            // As duas falhas 500 antes da conexão e a queda depois dela não viram aviso; o 404 é definitivo e aparece.
            assertEquals(listOf<Int?>(404), failures)
        } finally { server.shutdown() }
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
