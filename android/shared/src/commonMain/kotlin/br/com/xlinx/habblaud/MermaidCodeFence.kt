package br.com.xlinx.habblaud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.HabblaudApi
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import kotlinx.coroutines.CancellationException
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

// Só acessado pela composição e seus efeitos, na thread da interface; separa as instâncias da API e não guarda falhas.
private val mermaidCache = mutableMapOf<Pair<HabblaudApi, String>, ByteArray>()

@Composable
internal fun MermaidCodeFence(model: MarkdownComponentModel, api: HabblaudApi) {
    val language = model.node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(model.content)?.toString()?.trim()
    val closed = model.node.children.lastOrNull()?.type == MarkdownTokenTypes.CODE_FENCE_END
    val source = remember(model.content, model.node) {
        model.node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            .joinToString("\n") { it.getTextInNode(model.content).toString() }
    }
    if (!language.equals("mermaid", ignoreCase = true) || !closed || source.isBlank() || source.length > 20_000) {
        MarkdownCodeFence(model.content, model.node, model.typography.code)
        return
    }
    val key = api to source
    var bytes by remember(key) { mutableStateOf(mermaidCache[key]) }
    LaunchedEffect(key) {
        if (bytes == null) api.mermaid(source)?.let {
            if (mermaidCache.size >= 32) mermaidCache.remove(mermaidCache.keys.first())
            mermaidCache[key] = it
            bytes = it
        }
    }
    val density = LocalDensity.current
    val painter = remember(bytes, density) {
        try { bytes?.let { mermaidPainter(it, density) } }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            println("Zaplaud mermaid: SVG inválido: $e")
            // Sem isso o SVG ruim ficaria no cache até o processo morrer, mesmo com o servidor corrigido.
            mermaidCache.remove(key)
            null
        }
    }
    if (painter == null) MarkdownCodeFence(model.content, model.node, model.typography.code)
    else {
        var fullscreen by remember { mutableStateOf(false) }
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable { fullscreen = true }) {
            Image(painter, contentDescription = source, contentScale = ContentScale.Fit,
                modifier = Modifier.padding(8.dp).fillMaxWidth().aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height))
        }
        if (fullscreen) MermaidFullscreen(painter, source) { fullscreen = false }
    }
}

/** Diagrama em tela cheia: pinça amplia, arrastar move, toque duplo volta ao tamanho inicial. */
@Composable
private fun MermaidFullscreen(painter: Painter, source: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.White)) {
            Image(painter, contentDescription = source, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.files_close), tint = Color.Black)
            }
        }
    }
}

internal expect fun mermaidPainter(bytes: ByteArray, density: Density): Painter
