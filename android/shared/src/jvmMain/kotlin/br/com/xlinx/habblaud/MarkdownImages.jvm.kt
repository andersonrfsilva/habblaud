package br.com.xlinx.habblaud

import org.jetbrains.skia.Image

internal actual fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    val image = Image.makeFromEncoded(bytes)
    try {
        image.width to image.height
    } finally {
        image.close()
    }
}.getOrNull()
