package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.IMAGE_MAX_BYTES

private const val GIF_MAX_FRAMES = 300
private const val GIF_MAX_DECODED_BYTES = 50_000_000L

internal data class GifInfo(val width: Int, val height: Int, val frames: Int) {
    val canAnimate: Boolean
        get() = frames in 2..GIF_MAX_FRAMES && width.toLong() * height * 4 * frames <= GIF_MAX_DECODED_BYTES
}

internal fun gifInfo(bytes: ByteArray): GifInfo? {
    if (bytes.size !in 14..IMAGE_MAX_BYTES) return null
    fun byte(index: Int) = bytes[index].toInt() and 0xff
    fun word(index: Int) = byte(index) or (byte(index + 1) shl 8)
    if (byte(0) != 0x47 || byte(1) != 0x49 || byte(2) != 0x46 || byte(3) != 0x38 ||
        (byte(4) != 0x37 && byte(4) != 0x39) || byte(5) != 0x61
    ) return null
    val width = word(6)
    val height = word(8)
    if (width == 0 || height == 0) return null
    var offset = 13
    fun skip(count: Int): Boolean {
        if (count > bytes.size - offset) return false
        offset += count
        return true
    }
    fun colorTable(packed: Int) = packed and 0x80 == 0 || skip(3 shl ((packed and 7) + 1))
    fun subBlocks(): Boolean {
        while (offset < bytes.size) {
            val length = byte(offset++)
            if (length == 0) return true
            if (!skip(length)) return false
        }
        return false
    }
    if (!colorTable(byte(10))) return null
    var frames = 0
    while (offset < bytes.size) {
        when (byte(offset++)) {
            0x2c -> {
                val descriptor = offset
                if (!skip(9)) return null
                val frameWidth = word(descriptor + 4)
                val frameHeight = word(descriptor + 6)
                if (frameWidth == 0 || frameHeight == 0 || word(descriptor) + frameWidth > width ||
                    word(descriptor + 2) + frameHeight > height
                ) return null
                if (!colorTable(byte(descriptor + 8)) || !skip(1) || !subBlocks()) return null
                frames++
            }
            0x21 -> if (!skip(1) || !subBlocks()) return null
            0x3b -> return if (frames == 0) null else GifInfo(width, height, frames)
            else -> return null
        }
    }
    return null
}
