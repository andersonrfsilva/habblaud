package br.com.xlinx.habblaud.data

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

internal expect class NativeAvatarCache() {
    fun read(key: String): ImageBitmap?
    fun save(key: String, bytes: ByteArray): ImageBitmap?
}
class AvatarCache(private val api: HabblaudApi) {
    private companion object {
        // Native caches share memory and disk across loader instances on both platforms.
        val storageLock = Mutex()
    }
    private val cache = NativeAvatarCache()
    suspend fun load(avatar: Avatar): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val query = buildList {
                add("seed" to avatar.seed.toString()); add("look" to avatar.look); add("kind" to avatar.kind); add("scale" to "3")
                avatar.parts?.let { add("parts" to it) }
            }.joinToString("&") { (name, value) -> "$name=${value.encodeURLParameter()}" }
            val path = "/api/avatar.png?$query"
            val key = api.origin + path
            storageLock.withLock { cache.read(key) }?.let { return@withContext it }
            val bytes = api.avatarBytes(path) ?: return@withContext null
            if (bytes.size > 1024 * 1024) return@withContext null
            storageLock.withLock { cache.save(key, bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }
}
