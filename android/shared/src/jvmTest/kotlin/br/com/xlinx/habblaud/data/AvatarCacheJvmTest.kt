package br.com.xlinx.habblaud.data

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class AvatarCacheJvmTest {
    private fun image(width: Int, format: String = "png"): ByteArray = ByteArrayOutputStream().use {
        ImageIO.write(BufferedImage(width, 60, BufferedImage.TYPE_INT_RGB), format, it)
        it.toByteArray()
    }
    @Test fun acceptsOnlyBoundedSixtyPixelPng() {
        val cache = NativeAvatarCache()
        assertNull(cache.save("wrong-size", image(61)))
        assertNull(cache.save("jpeg", image(60, "jpg")))
        assertNull(cache.save("oversized", ByteArray(1024 * 1024 + 1)))
        assertNull(cache.save("invalid", byteArrayOf(1, 2, 3)))
        val bitmap = assertNotNull(cache.save("valid-avatar", image(60)))
        assertEquals(60, bitmap.width)
        assertEquals(60, bitmap.height)
        assertNotNull(cache.read("valid-avatar"))
        assertTrue(Files.exists(cacheDir.resolve("avatars").resolve(storageHash("valid-avatar"))))
    }
}
