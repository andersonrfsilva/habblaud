package br.com.xlinx.habblaud

import android.database.ContentObserver
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
import java.nio.ByteBuffer

private val mainHandler = Handler(Looper.getMainLooper())

@Composable
fun AndroidMarkdownImages(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < 28) {
        content()
        return
    }
    val resolver = LocalContext.current.contentResolver
    val setting = Settings.Global.ANIMATOR_DURATION_SCALE
    fun readAutoplay() = Settings.Global.getFloat(resolver, setting, 1f) != 0f
    var autoplay by remember(resolver) { mutableStateOf(readAutoplay()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) { autoplay = readAutoplay() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer)
        autoplay = readAutoplay()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(
        LocalMarkdownGifFactory provides ::decodeAnimatedGif,
        LocalMarkdownGifAutoplay provides autoplay,
        content = content,
    )
}

@RequiresApi(28)
private fun decodeAnimatedGif(bytes: ByteArray): MarkdownGifPainter? {
    if (gifInfo(bytes)?.canAnimate != true) return null
    val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    val drawable = try {
        ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable ?: return null
    } catch (_: IOException) {
        return null
    }
    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    return AndroidGifPainter(drawable)
}

@RequiresApi(28)
private class AndroidGifPainter(private val drawable: AnimatedImageDrawable) : MarkdownGifPainter(), Drawable.Callback {
    override val intrinsicSize = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
    private var invalidations by mutableIntStateOf(0)

    override fun DrawScope.onDraw() {
        // Read in draw, not composition: software invalidation should only redraw the image.
        invalidations
        scale(size.width / intrinsicSize.width, size.height / intrinsicSize.height, pivot = Offset.Zero) {
            drawIntoCanvas { drawable.draw(it.nativeCanvas) }
        }
    }

    override fun start() {
        drawable.callback = this
        drawable.start()
    }

    override fun stop() {
        drawable.stop()
        drawable.callback = null
        mainHandler.removeCallbacksAndMessages(this)
    }

    override fun invalidateDrawable(who: Drawable) { invalidations++ }
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        mainHandler.postAtTime(what, this, `when`)
    }
    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        mainHandler.removeCallbacks(what, this)
    }
}
