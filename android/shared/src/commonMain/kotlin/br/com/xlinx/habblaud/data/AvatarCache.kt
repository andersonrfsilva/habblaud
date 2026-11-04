package br.com.xlinx.habblaud.data

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.http.encodeURLQueryComponent

internal expect class NativeAvatarCache() {
    fun read(key: String): ImageBitmap?
    fun save(key: String, bytes: ByteArray): ImageBitmap?
}
class AvatarCache(private val api: HabblaudApi) {
    private val cache = NativeAvatarCache()
    suspend fun load(avatar: Avatar): ImageBitmap? {
        val query = buildList {
            add("seed" to avatar.seed.toString()); add("look" to avatar.look); add("kind" to avatar.kind); add("scale" to "3")
            avatar.parts?.let { add("parts" to it) }
        }.joinToString("&") { (name, value) -> "$name=${value.encodeURLQueryComponent()}" }
        val path = "/api/avatar.png?$query"
        val key = api.origin + path
        cache.read(key)?.let { return it }
        val bytes = api.avatarBytes(path) ?: return null
        if (bytes.size > 1024 * 1024) return null
        return cache.save(key, bytes)
    }
}
