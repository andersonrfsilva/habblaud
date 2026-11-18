package br.com.xlinx.habblaud

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.IMAGE_MAX_BYTES
import br.com.xlinx.habblaud.data.imageBytes
import br.com.xlinx.habblaud.data.remoteImageBytes
import br.com.xlinx.habblaud.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

// A composição e seus efeitos acessam o cache na thread da interface, como nos diagramas.
private val imageCache = mutableMapOf<Pair<HabblaudApi, String>, ByteArray>()

@Composable
internal fun MarkdownImages(text: String, api: HabblaudApi) {
    val images = remember(text) { markdownImages(text) }
    Column {
        images.forEach { (alt, dest) ->
            val kind = markdownImageKind(dest)
            if (kind != null) MarkdownImage(alt, dest, kind, api)
        }
    }
}

@Composable
private fun MarkdownImage(alt: String, dest: String, kind: ImageKind, api: HabblaudApi) {
    val key = api to dest
    var bytes by remember(key) { mutableStateOf(imageCache[key]) }
    var loading by remember(key) { mutableStateOf(false) }
    suspend fun load() {
        loading = true
        try {
            val loaded = when (kind) {
                ImageKind.DATA -> {
                    val encoded = dest.substringAfter(',')
                    if (encoded.length > ((IMAGE_MAX_BYTES + 2) / 3) * 4) null
                    else Base64.Default.decode(encoded).takeIf { it.isNotEmpty() && it.size <= IMAGE_MAX_BYTES }
                }
                ImageKind.HOST_FILE -> api.imageBytes(if (dest.startsWith("file:", ignoreCase = true)) dest.substring(7) else dest)
                ImageKind.REMOTE -> remoteImageBytes(dest)
            }
            if (loaded != null) {
                imageCache.remove(key)
                if (imageCache.size >= 32) imageCache.remove(imageCache.keys.first())
                imageCache[key] = loaded
                bytes = loaded
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally { loading = false }
    }
    LaunchedEffect(key) { if (bytes == null && kind != ImageKind.REMOTE) load() }
    val density = LocalDensity.current
    val painter = remember(bytes, density) {
        try {
            bytes?.let {
                val svg = if (kind == ImageKind.DATA) dest.substringBefore(',').startsWith("data:image/svg+xml;", ignoreCase = true)
                    else dest.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)
                (if (svg) mermaidPainter(it, density) else BitmapPainter(it.decodeToImageBitmap())).takeIf { painter ->
                    painter.intrinsicSize.width.isFinite() && painter.intrinsicSize.width > 0 &&
                        painter.intrinsicSize.height.isFinite() && painter.intrinsicSize.height > 0
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            imageCache.remove(key)
            null
        }
    }
    if (painter != null) {
        var fullscreen by remember(key) { mutableStateOf(false) }
        Image(painter, contentDescription = alt, contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = with(density) { painter.intrinsicSize.width.toDp() }).fillMaxWidth()
                .aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height).clickable { fullscreen = true })
        if (fullscreen) MermaidFullscreen(painter, alt) { fullscreen = false }
    } else if (kind == ImageKind.REMOTE) {
        val scope = rememberCoroutineScope()
        TextButton(onClick = { scope.launch { load() } }, enabled = !loading) {
            Text(stringResource(Res.string.image_load))
        }
    }
}
