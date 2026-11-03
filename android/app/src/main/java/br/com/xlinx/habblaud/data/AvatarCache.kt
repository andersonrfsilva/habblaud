package br.com.xlinx.habblaud.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

class AvatarCache(private val context: Context, private val api: HabblaudApi) {
    companion object {
        private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    suspend fun load(avatar: Avatar): Bitmap? = withContext(Dispatchers.IO) {
        val query = Uri.Builder().appendQueryParameter("seed", avatar.seed.toString())
            .appendQueryParameter("look", avatar.look).appendQueryParameter("kind", avatar.kind)
            .appendQueryParameter("scale", "3").apply { avatar.parts?.let { appendQueryParameter("parts", it) } }.build().toString()
        val url = api.origin + "/api/avatar.png" + query
        memory.get(url)?.let { return@withContext it }
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "avatars")
        val file = File(dir, digest)
        val cached = if (System.currentTimeMillis() - file.lastModified() < 86_400_000L) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
        if (cached != null) { memory.put(url, cached); return@withContext cached }
        try {
            api.client().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 401) { api.cancelCalls(); api.cookies.clear() }
                if (!response.isSuccessful || response.header("Content-Type")?.substringBefore(';') != "image/png") return@withContext null
                val body = response.body ?: return@withContext null
                if (body.contentLength() > 1024 * 1024) return@withContext null
                val source = body.source()
                if (source.request(1024 * 1024 + 1L)) return@withContext null
                val bytes = source.readByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth != 60 || bounds.outHeight != 60) return@withContext null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                memory.put(url, bitmap)
                runCatching {
                    dir.mkdirs()
                    file.writeBytes(bytes)
                    val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                    var total = 0L
                    files.forEach { entry -> total += entry.length(); if (total > 16 * 1024 * 1024) entry.delete() }
                }
                bitmap
            }
        } catch (_: java.io.IOException) { null }
    }
}
