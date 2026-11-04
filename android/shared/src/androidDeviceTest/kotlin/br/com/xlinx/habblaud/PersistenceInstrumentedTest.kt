package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionCookieStore
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import br.com.xlinx.habblaud.data.initializeStorage
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = "https://fixture.example"
    private val url = "$origin/api/access"
    private val file get() = context.filesDir.resolve("remote-session")

    @Before fun setUp() { initializeStorage(context); runBlocking { LocalStateStore(context).clear(); file.delete() } }
    @After fun tearDown() { runBlocking { LocalStateStore(context).clear(); file.delete() } }


    @Test fun delayedSetCookieCannotRestoreClearedSession() = runBlocking {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("{}")
                        .setHeader("Set-Cookie", "habblaud_remote=late-secret; Max-Age=3600; Path=/; HttpOnly")
                }
            }
            server.start()
            val address = server.url("/").toString().removeSuffix("/")
            HabblaudApi(address).use { api ->
                val response = async { api.request("/api/access") }
                try {
                    withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
                    api.cookies.clear()
                } finally { release.countDown() }
                response.await()
                assertNull(api.cookies.requestHeader("$address/api/access"))
                assertNull(SessionCookieStore(address).requestHeader("$address/api/access"))
                assertFalse(file.exists())
            }
        }
    }

    @Test fun realApiNeverReplaysPostForServiceUnavailableRetryAfterZero() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{\"id\":\"replayed\",\"status\":\"delivered\"}"))
            server.start()
            HabblaudApi(server.url("/").toString().removeSuffix("/")).use { api ->
                try {
                    api.request("/api/messages", buildJsonObject { put("text", "pedido") })
                    fail("503 accepted")
                } catch (error: ApiException) { assertEquals(503, error.status) }
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun keystoreEncryptsAndReloadsCookieAndRejectsCorruption() {
        val cookie = "habblaud_remote=instrumentation-secret; Max-Age=3600; Path=/; Secure; HttpOnly"
        SessionCookieStore(origin).saveFromResponse(url, listOf(cookie))
        val first = file.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains("instrumentation-secret"))
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("habblaud-remote-session"))
        assertEquals("habblaud_remote=instrumentation-secret", SessionCookieStore(origin).requestHeader(url))
        assertNull(SessionCookieStore("https://other.example").requestHeader("https://other.example/api/access"))
        SessionCookieStore(origin).saveFromResponse(url, listOf(cookie))
        assertFalse(first.contentEquals(file.readBytes()))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(SessionCookieStore(origin).requestHeader(url))
        assertFalse(file.exists())
    }

    @Test fun draftsPersistSeparatelyAcrossStoreInstancesAndOrigins() = runBlocking {
        val a = SessionKey(origin, "claude", "account", "a")
        val b = a.copy(sessionId = "b")
        val store = LocalStateStore(context)
        store.setConnection(origin)
        store.updateMessage(a) { it.copy(draft = it.draft.copy(text = "draft A", revision = 1)) }
        store.updateMessage(b) { it.copy(draft = it.draft.copy(text = "draft B", revision = 2)) }
        val reopened = LocalStateStore(context)
        assertEquals("draft A", reopened.message(a).draft.text)
        assertEquals("draft B", reopened.message(b).draft.text)
        assertEquals("", reopened.message(a.copy(provider = "omp")).draft.text)
        assertEquals("", reopened.message(a.copy(account = "other")).draft.text)
        reopened.setConnection("https://other.example")
        assertEquals("", reopened.message(a).draft.text)
    }
}
