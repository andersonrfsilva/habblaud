package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.SessionRepository
import br.com.xlinx.habblaud.data.initializeStorage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentRefreshTest {
    @Test fun disappearingSessionReturnsAsRecent() = runBlocking {
        initializeStorage(ApplicationProvider.getApplicationContext<Context>())
        MockWebServer().use { server ->
            val closed = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/access" -> MockResponse().setBody("""{"terminal":true}""")
                    "/api/cast" -> MockResponse().setBody("""{"members":[]}""")
                    "/api/sessions/recent" -> MockResponse().setBody(if (closed.get()) {
                        """{"sessions":[{"account":".claude","sessionId":"session","title":"Conversa"}]}"""
                    } else """{"sessions":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            HabblaudApi(server.url("/").toString().removeSuffix("/")).use { api ->
                val repository = SessionRepository(api)
                repository.refresh()
                val live = """{"agents":[{"kind":"main","id":"agent","account":".claude","sessionId":"session","status":"working"}]}"""
                val session = repository.snapshot(live).single()
                assertEquals("agent", session.agentId)
                assertFalse(repository.recentStale)

                val offline = repository.snapshot(live.replace("working", "offline")).single()
                assertEquals(session.key, offline.key)
                assertNull(offline.agentId)
                assertFalse(repository.recentStale)

                repository.snapshot("""{"agents":[]}""")
                assertTrue(repository.recentStale)
                assertTrue(repository.sessions().isEmpty())

                closed.set(true)
                repository.refreshRecent()
                val recent = repository.sessions().single()
                assertEquals(session.key, recent.key)
                assertNull(recent.agentId)
                assertFalse(repository.recentStale)
                assertEquals(listOf("/api/access", "/api/sessions/recent", "/api/cast", "/api/sessions/recent"),
                    List(server.requestCount) { server.takeRequest().path })
            }
        }
    }
}
