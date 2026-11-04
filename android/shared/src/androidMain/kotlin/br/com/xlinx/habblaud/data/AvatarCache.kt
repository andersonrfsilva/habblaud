package br.com.xlinx.habblaud.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest

internal actual class NativeAvatarCache actual constructor() {
    private companion object {
        val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    }
    private val directory = File(storageContext.cacheDir, "avatars")
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
    actual fun read(key: String): ImageBitmap? {
        memory.get(key)?.let { return it.asImageBitmap() }
        val file = file(key)
        if (System.currentTimeMillis() - file.lastModified() >= 86_400_000L || file.length() > 1024 * 1024) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.takeIf { it.width == 60 && it.height == 60 } ?: return null
        memory.put(key, bitmap); return bitmap.asImageBitmap()
    }
    actual fun save(key: String, bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth != 60 || bounds.outHeight != 60) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        memory.put(key, bitmap)
        runCatching {
            directory.mkdirs(); file(key).writeBytes(bytes)
            var total = 0L
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { total += it.length(); if (total > 16 * 1024 * 1024) it.delete() }
        }
        return bitmap.asImageBitmap()
    }
}
