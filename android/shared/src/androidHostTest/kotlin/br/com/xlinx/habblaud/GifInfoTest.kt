package br.com.xlinx.habblaud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifInfoTest {
    private fun gif(frames: Int, version: String = "GIF89a") = buildList<Byte> {
        addAll(version.encodeToByteArray().toList())
        addAll(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1).toList())
        repeat(frames) {
            addAll(byteArrayOf(0x21, -7, 4, 0, 5, 0, 0, 0).toList())
            addAll(byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0).toList())
        }
        add(0x3b)
    }.toByteArray()

    @Test fun countsSingleFrame() {
        assertEquals(GifInfo(1, 1, 1), gifInfo(gif(1, "GIF87a")))
        assertFalse(GifInfo(1, 1, 1).canAnimate)
    }

    @Test fun countsMultipleFramesAndEnforcesLimits() {
        assertEquals(GifInfo(1, 1, 3), gifInfo(gif(3)))
        assertTrue(GifInfo(1, 1, 300).canAnimate)
        assertFalse(gifInfo(gif(301))!!.canAnimate)
        assertTrue(GifInfo(2500, 2500, 2).canAnimate)
        assertFalse(GifInfo(2501, 2500, 2).canAnimate)
        assertFalse(GifInfo(65535, 65535, 300).canAnimate)
    }

    @Test fun rejectsEveryTruncatedPrefix() {
        val image = gif(3)
        for (length in image.indices) assertNull("length $length", gifInfo(image.copyOf(length)))
    }

    @Test fun rejectsNonGif() {
        assertNull(gifInfo(gif(3, "NOTGIF")))
    }
}
