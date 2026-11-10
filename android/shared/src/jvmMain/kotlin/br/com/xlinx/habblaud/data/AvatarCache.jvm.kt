package br.com.xlinx.habblaud.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.ByteBuffer
import java.nio.file.Files
import org.jetbrains.skia.Image

internal actual class NativeAvatarCache actual constructor() {
    private companion object {
        val memory = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
        var memoryBytes = 0
        val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
    private val directory get() = cacheDir.resolve("avatars")
    private fun file(key: String) = directory.resolve(storageHash(key))
    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.size !in 24..1024 * 1024 || pngSignature.indices.any { bytes[it] != pngSignature[it] }) return null
        val header = ByteBuffer.wrap(bytes)
        if (header.getInt(12) != 0x49484452 || header.getInt(16) != 60 || header.getInt(20) != 60) return null
        return runCatching { Image.makeFromEncoded(bytes).takeIf { it.width == 60 && it.height == 60 }?.toComposeImageBitmap() }.getOrNull()
    }
    private fun remember(key: String, bitmap: ImageBitmap) = synchronized(memory) {
        memory.put(key, bitmap)?.let { memoryBytes -= it.width * it.height * 4 }
        memoryBytes += bitmap.width * bitmap.height * 4
        val entries = memory.entries.iterator()
        while (memoryBytes > 4 * 1024 * 1024 && entries.hasNext()) {
            val old = entries.next().value
            memoryBytes -= old.width * old.height * 4
            entries.remove()
        }
    }
    actual fun read(key: String): ImageBitmap? {
        synchronized(memory) { memory[key] }?.let { return it }
        val path = file(key)
        if (!Files.isRegularFile(path) || System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() >= 86_400_000L || Files.size(path) > 1024 * 1024) return null
        val bitmap = decode(Files.readAllBytes(path)) ?: return null
        remember(key, bitmap)
        return bitmap
    }
    actual fun save(key: String, bytes: ByteArray): ImageBitmap? {
        val bitmap = decode(bytes) ?: return null
        remember(key, bitmap)
        runCatching {
            Files.createDirectories(directory)
            Files.write(file(key), bytes)
            Files.list(directory).use { files ->
                var total = 0L
                files.filter { Files.isRegularFile(it) }.sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() }).forEach {
                    total += Files.size(it)
                    if (total > 16 * 1024 * 1024) Files.delete(it)
                }
            }
        }
        return bitmap
    }
}
