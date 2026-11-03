package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionCookieStore
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val url = "$origin/api/access".toHttpUrl()
    private val file get() = context.filesDir.resolve("remote-session")

    @Before fun setUp() { runBlocking { LocalStateStore(context).clear(); file.delete() } }
    @After fun tearDown() { runBlocking { LocalStateStore(context).clear(); file.delete() } }

    @Test fun keystoreEncryptsAndReloadsCookieAndRejectsCorruption() {
        val cookie = Cookie.parse(url, "habblaud_remote=instrumentation-secret; Max-Age=3600; Path=/; Secure; HttpOnly")!!
        SessionCookieStore(context, origin).saveFromResponse(url, listOf(cookie))
        val first = file.readBytes()
        assertFalse(String(first, Charsets.ISO_8859_1).contains(cookie.value))
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("habblaud-remote-session"))
        assertEquals(cookie.value, SessionCookieStore(context, origin).loadForRequest(url).single().value)
        assertTrue(SessionCookieStore(context, "https://other.example").loadForRequest("https://other.example/api/access".toHttpUrl()).isEmpty())
        SessionCookieStore(context, origin).saveFromResponse(url, listOf(cookie))
        assertFalse(first.contentEquals(file.readBytes()))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(SessionCookieStore(context, origin).loadForRequest(url).isEmpty())
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
