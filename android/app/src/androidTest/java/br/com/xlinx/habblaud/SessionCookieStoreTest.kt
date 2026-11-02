package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.SessionCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionCookieStoreTest {
    @Test fun encryptedPersistenceAndOriginIsolation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://example.com:444".toHttpUrl()
        val store = SessionCookieStore(context, "https://example.com:444")
        store.clear()
        val cookie = Cookie.parse(url, "habblaud_remote=secret; Path=/; Max-Age=3600; Secure; HttpOnly")!!
        store.saveFromResponse(url, listOf(cookie))
        val bytes = context.filesDir.resolve("remote-session").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret"))
        val restored = SessionCookieStore(context, "https://example.com:444")
        assertEquals("secret", restored.loadForRequest(url).single().value)
        assertTrue(restored.loadForRequest("https://example.com:445".toHttpUrl()).isEmpty())
        assertTrue(SessionCookieStore(context, "https://example.com:445").loadForRequest(url).isEmpty())
        restored.clear()
        assertTrue(SessionCookieStore(context, "https://example.com:444").loadForRequest(url).isEmpty())
    }
}
