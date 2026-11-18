package br.com.xlinx.habblaud

import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import com.caverock.androidsvg.SVG

// Compose Resources só oferece o leitor SVG nos alvos Skia, não no Android.
internal actual fun mermaidPainter(bytes: ByteArray, density: Density): Painter {
    val text = bytes.decodeToString()
    // Conteúdo da conversa: sem DOCTYPE não há entidade para expandir (e o leitor não as resolve).
    require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true)) { "SVG com DOCTYPE" }
    SVG.setInternalEntitiesEnabled(false)
    val svg = SVG.getFromString(text)
    // SVG só com viewBox não tem tamanho de documento (-1); sem isso a proporção sai errada.
    val box = svg.documentViewBox
    val intrinsic = if (svg.documentWidth > 0 && svg.documentHeight > 0) Size(svg.documentWidth, svg.documentHeight)
        else Size(box?.width() ?: 300f, box?.height() ?: 150f)
    // Com width/height fixos o AndroidSVG desenha no tamanho do documento e corta; 100% faz caber no viewBox.
    svg.setDocumentWidth("100%")
    svg.setDocumentHeight("100%")
    return object : Painter() {
        override val intrinsicSize = intrinsic
        override fun DrawScope.onDraw() {
            svg.renderToCanvas(drawContext.canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height))
        }
    }
}
