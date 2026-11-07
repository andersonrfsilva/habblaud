package br.com.xlinx.habblaud

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.Avatar
import br.com.xlinx.habblaud.data.AvatarCache
import br.com.xlinx.habblaud.data.HabblaudApi
import android.os.StrictMode
import android.os.Looper
import kotlinx.coroutines.*
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Before
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AvatarCacheInstrumentedTest {
    @Before fun initialize() {
        br.com.xlinx.habblaud.data.initializeStorage(ApplicationProvider.getApplicationContext<Context>())
    }
    private suspend fun fixture(block: suspend (MockWebServer, HabblaudApi, AvatarCache) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().removeSuffix("/").replace("localhost", "127.0.0.1")
            HabblaudApi(origin).use { api -> withTimeout(15_000) { block(server, api, AvatarCache(api)) } }
        }
    }
    private fun response(bytes: ByteArray = png(60)) =
        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
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
        br.com.xlinx.habblaud.data.initializeStorage(context)
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().removeSuffix("/").replace("localhost", "127.0.0.1")
            HabblaudApi(origin).use { api ->
                val cache = AvatarCache(api)
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

    @Test fun partsRoundTripAndCacheReuse() = runBlocking {
        fixture { server, _, cache ->
            val parts = """{"skin":"#ffeeaa","label":"&+%\"雪"}"""
            server.enqueue(response())
            val avatar = Avatar(81, "f", parts = parts)
            assertNotNull(cache.load(avatar))
            assertNotNull(cache.load(avatar))
            val url = server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!
            assertEquals(parts, url.queryParameter("parts"))
            assertEquals("81", url.queryParameter("seed"))
            assertEquals("f", url.queryParameter("look"))
            assertEquals("3", url.queryParameter("scale"))
            assertNull(url.fragment)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun socketFailureRecoversOnFreshConnectionAndPersistentFailureStaysOptional() = runBlocking {
        fixture { server, _, cache ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(response())
            assertNotNull(withContext(Dispatchers.Main) { cache.load(Avatar(91, "f")) })
            assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
            assertEquals(0, server.takeRequest(2, TimeUnit.SECONDS)!!.sequenceNumber)
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            assertNull(withContext(Dispatchers.Main) { cache.load(Avatar(92, "f")) })
            assertEquals(4, server.requestCount)
            server.enqueue(response())
            assertNotNull(withContext(Dispatchers.Main) { cache.load(Avatar(92, "f")) })
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun statusErrorsRedirectAndInvalidImagesNeverPopulateCache() = runBlocking {
        fixture { server, api, cache ->
            for ((index, status) in listOf(400, 401, 403, 404, 500, 302).withIndex()) {
                val generation = api.cookies.generation()
                server.enqueue(MockResponse().setResponseCode(status))
                assertNull(cache.load(Avatar(100L + index, "f")))
                if (status == 401) assertTrue(api.cookies.generation() > generation)
                assertEquals(index + 1, server.requestCount)
            }
            val invalid = listOf(
                response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
                response(ByteArray(1024 * 1024 + 1)),
                response().setHeader("Content-Type", "text/plain"),
                response("invalid png".toByteArray()),
                response(png(120))
            )
            for ((index, bad) in invalid.withIndex()) {
                val avatar = Avatar(120L + index, "m")
                val before = server.requestCount
                server.enqueue(bad)
                assertNull(cache.load(avatar))
                assertEquals(before + 1, server.requestCount)
                server.enqueue(response())
                assertNotNull(cache.load(avatar))
                assertNotNull(cache.load(avatar))
                assertEquals(before + 2, server.requestCount)
            }
        }
    }

    @Test fun cancellationNeverPublishesOrRetriesAndNewAvatarStillLoads() = runBlocking {
        fixture { server, _, cache ->
            server.enqueue(response().setBodyDelay(5, TimeUnit.SECONDS))
            var published = false
            val old = launch(Dispatchers.Main) { cache.load(Avatar(150, "f")); published = true }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            delay(100)
            old.cancelAndJoin()
            assertTrue(old.isCancelled)
            assertFalse(published)
            assertEquals(1, server.requestCount)
            server.enqueue(response())
            assertNotNull(withContext(Dispatchers.Main) { cache.load(Avatar(151, "f")) })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun mainThreadColdWarmAndConcurrentLoadsAvoidDiskIo() = runBlocking {
        fixture { server, api, cache ->
            val bytes = png(60)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = response(bytes)
            }
            val violations = CopyOnWriteArrayList<String>()
            withContext(Dispatchers.Main) {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previous)
                    .detectDiskReads().detectDiskWrites()
                    .penaltyListener(Executor { it.run() }) { violation ->
                        if (violation.stackTrace.any { it.className.contains("NativeAvatarCache") }) violations += violation.toString()
                    }.build())
                try {
                    assertNotNull(cache.load(Avatar(170, "f")))
                    val before = server.requestCount
                    assertNotNull(cache.load(Avatar(170, "f")))
                    assertEquals(before, server.requestCount)
                    coroutineScope {
                        (1..12).map { async { AvatarCache(api).load(Avatar(171, "m")) } }.awaitAll().forEach { assertNotNull(it) }
                    }
                } finally { StrictMode.setThreadPolicy(previous) }
            }
            assertTrue("Avatar disk access on main: $violations", violations.isEmpty())
            assertNotNull(cache.load(Avatar(171, "m")))
        }
    }

    @Test fun ambiguousApiPostIsNeverReplayed() = runBlocking {
        fixture { server, api, _ ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            try {
                api.request("/api/messages", kotlinx.serialization.json.buildJsonObject {})
                fail("Expected ambiguous POST failure")
            } catch (_: kotlinx.io.IOException) { }
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertEquals(1, server.requestCount)
        }
    }
}
