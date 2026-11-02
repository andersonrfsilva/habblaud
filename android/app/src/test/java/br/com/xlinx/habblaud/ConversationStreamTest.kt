package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ConversationRepository
import br.com.xlinx.habblaud.data.StreamEvent
import br.com.xlinx.habblaud.data.VpnRequiredException
import br.com.xlinx.habblaud.data.sendStreamEvent
import kotlinx.coroutines.channels.Channel
import java.io.IOException
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
    @Test fun missingVpnIsReportedByStream() = runBlocking {
        val repo = ConversationRepository("http://100.64.0.1", { throw VpnRequiredException() }, {})
        val event = withTimeout(5000) { repo.stream("/api/stream").first() } as StreamEvent.Failure
        assertTrue(event.vpnRequired)
        assertNull(event.status)
    }

    @Test fun successfulConnectionResetsRetryDelay() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(""))
            server.enqueue(MockResponse().setResponseCode(404))
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { OkHttpClient() }, {})
            var disconnectedAt = 0L
            var lastDelay = 0L
            withTimeout(15000) {
                repo.stream("/api/stream").collect { event ->
                    if (event is StreamEvent.Failure && event.status == null) disconnectedAt = System.nanoTime()
                    if (event is StreamEvent.Failure && event.status == 404) lastDelay = (System.nanoTime() - disconnectedAt) / 1_000_000
                }
            }
            assertTrue(disconnectedAt != 0L)
            assertTrue("retry delay was $lastDelay ms", lastDelay in 1800..3999)
        } finally { server.shutdown() }
    }

    @Test fun overflowClosesChannelAndCancelsSource() = runBlocking {
        val channel = Channel<StreamEvent>(1)
        var cancelled = false
        sendStreamEvent(channel, StreamEvent.Data("init", "{}", true)) { cancelled = true }
        sendStreamEvent(channel, StreamEvent.Data("append", "[]", true)) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(channel.receive() is StreamEvent.Data)
        assertTrue(channel.receiveCatching().exceptionOrNull() is IOException)
    }
    @Test fun parsesMultilineDataAndComments() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": ping\r\nevent: append\r\ndata: one\r\ndata: two\r\n\r\n"))
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { OkHttpClient() }, {})
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
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { OkHttpClient() }, { unauthorized = true })
            val events = withTimeout(5000) { repo.stream("/api/stream").toList() }
            assertTrue(unauthorized)
            assertEquals(401, (events.last() as StreamEvent.Failure).status)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellationStopsCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val repo = ConversationRepository(server.url("/").toString().removeSuffix("/"), { client }, {})
            val job = launch { repo.stream("/api/stream").toList() }
            withTimeout(5000) { while (server.requestCount == 0) delay(10) }
            job.cancelAndJoin()
            withTimeout(5000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        } finally { client.dispatcher.cancelAll(); server.shutdown() }
    }
}
