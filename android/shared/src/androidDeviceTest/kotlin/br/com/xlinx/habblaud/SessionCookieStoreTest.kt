package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.SessionCookieStore
import br.com.xlinx.habblaud.data.initializeStorage
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.ConversationRepository
import br.com.xlinx.habblaud.data.StreamEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCookieStoreTest {
    @Test fun encryptedPersistenceAndOriginIsolation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        initializeStorage(context)
        val url = "https://example.com:444"
        val store = SessionCookieStore(url)
        store.clear()
        store.saveFromResponse(url, listOf("habblaud_remote=secret; Path=/; Max-Age=3600; Secure; HttpOnly"))
        val bytes = context.filesDir.resolve("remote-session").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret"))
        val restored = SessionCookieStore(url)
        assertEquals("habblaud_remote=secret", restored.requestHeader(url))
        assertNull(restored.requestHeader("https://example.com:445"))
        assertNull(SessionCookieStore("https://example.com:445").requestHeader(url))
        restored.clear()
        assertNull(SessionCookieStore(url).requestHeader(url))
    }

    @Test fun notificationStreamUsesPersistedCookieAndClearsItOnUnauthorized() = runBlocking {
        initializeStorage(ApplicationProvider.getApplicationContext<Context>())
        val server = MockWebServer()
        server.start()
        val origin = server.url("/").toString().removeSuffix("/")
        val saved = SessionCookieStore(origin)
        saved.clear()
        saved.saveFromResponse(origin, listOf("habblaud_remote=notification-session; Path=/; Max-Age=3600; HttpOnly"))
        val api = HabblaudApi(origin)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: snapshot\ndata: {\"agents\":[]}\n\n"))
            server.enqueue(MockResponse().setResponseCode(401))
            // The notification service uses this authenticated shared repository, not an engine cookie jar.
            val events = withTimeout(10_000) { ConversationRepository(api).stream("/api/stream").toList() }
            assertEquals("habblaud_remote=notification-session", server.takeRequest().getHeader("Cookie"))
            assertEquals("habblaud_remote=notification-session", server.takeRequest().getHeader("Cookie"))
            assertTrue(events.any { it is StreamEvent.Data && it.type == "snapshot" })
            assertTrue(events.last() is StreamEvent.Failure && (events.last() as StreamEvent.Failure).status == 401)
            assertNull(api.cookies.requestHeader(origin))
            assertNull(SessionCookieStore(origin).requestHeader(origin))
            assertEquals(2, server.requestCount)
        } finally {
            api.close()
            saved.clear()
            server.shutdown()
        }
    }
}
