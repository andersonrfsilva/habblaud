package br.com.xlinx.habblaud.data
import androidx.compose.ui.graphics.ImageBitmap
internal actual class NativeAvatarCache actual constructor() {
    actual fun read(key: String): ImageBitmap? = TODO()
    actual fun save(key: String, bytes: ByteArray): ImageBitmap? = TODO()
}
