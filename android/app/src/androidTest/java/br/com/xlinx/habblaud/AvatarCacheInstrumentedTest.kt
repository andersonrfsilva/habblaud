package br.com.xlinx.habblaud

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.Avatar
import br.com.xlinx.habblaud.data.AvatarCache
import br.com.xlinx.habblaud.data.HabblaudApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AvatarCacheInstrumentedTest {
    private fun png(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    @Test fun cacheReusesValidAvatarAndRejectsUnexpectedDimensions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().removeSuffix("/").replace("localhost", "127.0.0.1")
            HabblaudApi(context, origin).use { api ->
                val cache = AvatarCache(context, api)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png(60))))
                val avatar = Avatar(71, "f")
                assertNotNull(cache.load(avatar))
                assertNotNull(cache.load(avatar))
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png(120))))
                assertNull(cache.load(Avatar(72, "m")))
                assertEquals(2, server.requestCount)
            }
        }
    }
}
