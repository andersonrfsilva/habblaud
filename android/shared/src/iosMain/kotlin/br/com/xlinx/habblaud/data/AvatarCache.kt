package br.com.xlinx.habblaud.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.*
import org.jetbrains.skia.Image
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
internal actual class NativeAvatarCache actual constructor() {
    private companion object { val memory = linkedMapOf<String, ImageBitmap>() }
    private val directory: String get() {
        val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        val path = "$root/avatars"
        check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, mapOf(NSFileProtectionKey to NSFileProtectionComplete), null))
        return path
    }
    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val image = Image.makeFromEncoded(bytes)
        if (image.width == 60 && image.height == 60) image.toComposeImageBitmap() else { image.close(); null }
    }.getOrNull()
    private fun remember(key: String, image: ImageBitmap): ImageBitmap {
        memory.remove(key); memory[key] = image
        while (memory.size * 60 * 60 * 4 > 4 * 1024 * 1024) memory.remove(memory.keys.first())
        return image
    }
    actual fun read(key: String): ImageBitmap? {
        memory[key]?.let { return remember(key, it) }
        val path = "$directory/${storageHash(key)}"
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return null
        val modified = attributes[NSFileModificationDate] as? NSDate ?: return null
        if (NSDate().timeIntervalSinceDate(modified) >= 86400 || (attributes[NSFileSize] as? NSNumber)?.longLongValue?.let { it > 1024 * 1024 } == true) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) }
        return decode(bytes)?.let { remember(key, it) }
    }
    actual fun save(key: String, bytes: ByteArray): ImageBitmap? {
        val bitmap = decode(bytes) ?: return null
        remember(key, bitmap)
        runCatching {
            bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToFile("$directory/${storageHash(key)}", NSDataWritingAtomic or NSDataWritingFileProtectionComplete, null) }
            val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>().map { name -> name to NSFileManager.defaultManager.attributesOfItemAtPath("$directory/$name", null).orEmpty() }.sortedByDescending { (it.second[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0 }
            var total = 0L
            files.forEach { (name, attributes) -> total += (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0; if (total > 16 * 1024 * 1024) NSFileManager.defaultManager.removeItemAtPath("$directory/$name", null) }
        }
        return bitmap
    }
}
