package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStateStoreTest {
    @Test fun connectionAndRetrySurviveRecreationAndAreIsolated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = LocalStateStore(context)
        first.setConnection("https://example.com")
        first.setRetryUntil("https://example.com", 123456789L)
        first.setRetryUntil("https://other.example.com", 987654321L)
        val second = LocalStateStore(context)
        assertEquals("https://example.com", second.connection()?.origin)
        assertEquals(123456789L, second.retryUntil("https://example.com"))
        assertEquals(987654321L, second.retryUntil("https://other.example.com"))
        second.clear()
        assertNull(first.connection())
        assertEquals(123456789L, first.retryUntil("https://example.com"))
    }
}
