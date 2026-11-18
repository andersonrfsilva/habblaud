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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.IMAGE_MAX_BYTES
import br.com.xlinx.habblaud.data.imageBytes
import br.com.xlinx.habblaud.data.remoteImageBytes
import br.com.xlinx.habblaud.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64

private const val IMAGE_CACHE_MAX_BYTES = 24 * 1024 * 1024
private const val IMAGE_MAX_PIXELS = 4096L * 4096

// A composição e seus efeitos acessam o cache na thread da interface, como nos diagramas.
private val imageCache = mutableMapOf<Pair<HabblaudApi, String>, ByteArray>()
private var imageCacheBytes = 0

private fun removeCachedImage(key: Pair<HabblaudApi, String>) {
    imageCache.remove(key)?.let { imageCacheBytes -= it.size }
}

private fun cacheImage(key: Pair<HabblaudApi, String>, bytes: ByteArray) {
    if (bytes.size > IMAGE_CACHE_MAX_BYTES) return
    removeCachedImage(key)
    while (imageCacheBytes + bytes.size > IMAGE_CACHE_MAX_BYTES) removeCachedImage(imageCache.keys.first())
    imageCache[key] = bytes
    imageCacheBytes += bytes.size
}

internal expect fun imageDimensions(bytes: ByteArray): Pair<Int, Int>?

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
    var bytes by remember(key) { mutableStateOf(if (kind == ImageKind.DATA) null else imageCache[key]) }
    var loading by remember(key) { mutableStateOf(false) }
    suspend fun load() {
        if (loading || bytes != null) return
        loading = true
        try {
            val loaded = when (kind) {
                ImageKind.DATA -> withContext(Dispatchers.Default) {
                    val start = dest.indexOf(',') + 1
                    if (dest.length - start > ((IMAGE_MAX_BYTES + 2) / 3) * 4) null
                    else Base64.Default.decode(dest.substring(start)).takeIf { it.isNotEmpty() && it.size <= IMAGE_MAX_BYTES }
                }
                ImageKind.HOST_FILE -> api.imageBytes(if (dest.startsWith("file:", ignoreCase = true)) dest.substring(7) else dest)
                ImageKind.REMOTE -> remoteImageBytes(dest)
            }
            if (loaded != null) {
                if (kind != ImageKind.DATA) cacheImage(key, loaded)
                bytes = loaded
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally { loading = false }
    }
    LaunchedEffect(key) { if (bytes == null && kind != ImageKind.REMOTE) load() }
    val density = LocalDensity.current
    var decodedPainter by remember(key) { mutableStateOf<Painter?>(null) }
    LaunchedEffect(key, bytes, density) {
        val encoded = bytes ?: return@LaunchedEffect
        decodedPainter = try {
            withContext(Dispatchers.Default) {
                val svg = if (kind == ImageKind.DATA) dest.startsWith("data:image/svg+xml;", ignoreCase = true)
                    else dest.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)
                val decoded = if (svg) mermaidPainter(encoded, density) else {
                    val (width, height) = imageDimensions(encoded) ?: return@withContext null
                    // shortcut: acima de 16 MP a imagem não é desenhada; reduzir na decodificação se usuários reclamarem
                    if (width.toLong() * height > IMAGE_MAX_PIXELS) return@withContext null
                    BitmapPainter(encoded.decodeToImageBitmap())
                }
                decoded.takeIf { painter ->
                    painter.intrinsicSize.width.isFinite() && painter.intrinsicSize.width > 0 &&
                        painter.intrinsicSize.height.isFinite() && painter.intrinsicSize.height > 0
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (decodedPainter == null) {
            if (kind != ImageKind.DATA && imageCache[key] === encoded) removeCachedImage(key)
            bytes = null
        }
    }
    val painter = decodedPainter
    if (painter != null) {
        var fullscreen by remember(key) { mutableStateOf(false) }
        Image(painter, contentDescription = alt, contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = with(density) { painter.intrinsicSize.width.toDp() }).fillMaxWidth()
                .aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height).clickable { fullscreen = true })
        if (fullscreen) MermaidFullscreen(painter, alt) { fullscreen = false }
    } else if (kind == ImageKind.REMOTE) {
        val scope = rememberCoroutineScope()
        TextButton(onClick = { scope.launch { load() } }, enabled = !loading && bytes == null) {
            Text(stringResource(Res.string.image_load))
        }
    }
}
