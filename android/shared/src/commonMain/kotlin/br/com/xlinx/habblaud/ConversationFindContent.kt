package br.com.xlinx.habblaud

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.FindLocation
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.findCodeText
import br.com.xlinx.habblaud.data.findHeadingContent
import br.com.xlinx.habblaud.data.findRanges
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.utils.EntityConverter
import kotlinx.coroutines.CompletableDeferred
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

internal data class FindRowRender(
    val query: String,
    val blocks: Set<Int>,
    val active: FindLocation?,
    val anchor: CompletableDeferred<FindAnchor>?,
)

@OptIn(ExperimentalFoundationApi::class)
internal class FindAnchor(
    private val requester: BringIntoViewRequester,
    private val position: LayoutCoordinates,
    private val layout: TextLayoutResult,
    private val offset: Int,
) {
    suspend fun revealIn(viewport: LayoutCoordinates, padding: Int): Float? {
        val line = layout.getLineForOffset(offset)
        val tall = layout.size.height > viewport.size.height - 2 * padding
        val top = if (tall) layout.getLineTop(line) else 0f
        val bottom = if (tall) layout.getLineBottom(line) else layout.size.height.toFloat()
        val glyph = layout.getBoundingBox(offset)
        // Mostra também a coluna em tabelas e código com rolagem horizontal.
        requester.bringIntoView(Rect(glyph.left, top, glyph.right, bottom))
        if (!position.isAttached || !viewport.isAttached) return null
        return viewport.localPositionOf(position, Offset(0f, top)).y
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FindMarkedText(
    text: AnnotatedString,
    search: FindRowRender?,
    block: Int,
    modifier: Modifier = Modifier,
    searchableLength: Int = text.length,
    render: @Composable (AnnotatedString, Modifier, (TextLayoutResult) -> Unit) -> Unit,
) {
    if (search == null || block !in search.blocks) {
        render(text, modifier) {}
        return
    }
    val ranges = remember(text.text, searchableLength, search.query) { findRanges(text.text.take(searchableLength), search.query) }
    val active = search.active?.takeIf { it.block == block }?.occurrence
    val highlight = MaterialTheme.colorScheme.primary
    val marked = remember(text, ranges, active, highlight) {
        buildAnnotatedString {
            append(text)
            ranges.forEachIndexed { index, range ->
                addStyle(SpanStyle(background = highlight.copy(alpha = if (index == active) 0.42f else 0.20f)), range.first, range.last + 1)
            }
        }
    }
    val offset = active?.let(ranges::getOrNull)?.first
    if (offset == null || search.anchor == null) {
        render(marked, modifier) {}
        return
    }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var position by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(search.anchor, offset, layout, position) {
        val coordinates = position ?: return@LaunchedEffect
        val measured = layout?.takeIf { it.layoutInput.text.text == marked.text } ?: return@LaunchedEffect
        if (coordinates.isAttached) search.anchor.complete(FindAnchor(requester, coordinates, measured, offset))
    }
    render(marked, modifier.bringIntoViewRequester(requester).onGloballyPositioned { position = it }) { layout = it }
}

internal fun conversationMarkdownComponents(api: HabblaudApi, find: FindRowRender?) = if (find == null) {
    markdownComponents(codeFence = { MermaidCodeFence(it, api) }, codeBlock = { CopyableCodeBlock(it) }, table = wrappingTable)
} else markdownComponents(
    text = { model ->
        val text = EntityConverter.replaceEntities(model.node.getTextInNode(model.content), true, true)
        FindMarkedText(AnnotatedString(text), find, model.node.startOffset) { marked, modifier, layout ->
            MarkdownText(marked, modifier, model.typography.text, onTextLayout = { result, _ -> layout(result) })
        }
    },
    paragraph = { FindMarkdownText(it, it.typography.paragraph, find) },
    heading1 = { FindMarkdownText(it, it.typography.h1, find, heading = true) },
    heading2 = { FindMarkdownText(it, it.typography.h2, find, heading = true) },
    heading3 = { FindMarkdownText(it, it.typography.h3, find, heading = true) },
    heading4 = { FindMarkdownText(it, it.typography.h4, find, heading = true) },
    heading5 = { FindMarkdownText(it, it.typography.h5, find, heading = true) },
    heading6 = { FindMarkdownText(it, it.typography.h6, find, heading = true) },
    setextHeading1 = { FindMarkdownText(it, it.typography.h1, find, heading = true) },
    setextHeading2 = { FindMarkdownText(it, it.typography.h2, find, heading = true) },
    codeFence = { if (it.node.startOffset in find.blocks) FindMarkdownCode(it, find) else MermaidCodeFence(it, api) },
    codeBlock = { if (it.node.startOffset in find.blocks) FindMarkdownCode(it, find) else CopyableCodeBlock(it) },
    table = { model ->
        MarkdownTable(
            content = model.content, node = model.node, style = model.typography.table,
            headerBlock = { content, row, width, style -> FindTableRow(content, row, width, style.copy(fontWeight = FontWeight.Bold), find) },
            rowBlock = { content, row, width, style -> FindTableRow(content, row, width, style, find) },
        )
    },
)

@Composable
private fun FindMarkdownText(model: MarkdownComponentModel, style: TextStyle, find: FindRowRender, heading: Boolean = false) {
    val settings = annotatorSettings()
    val node = if (heading) findHeadingContent(model.node) else model.node
    val text = model.content.buildMarkdownAnnotatedString(node, style, settings)
    val headingModifier = if (heading) Modifier.semantics { heading() } else Modifier
    FindMarkedText(text, find, model.node.startOffset, headingModifier) { marked, modifier, layout ->
        MarkdownText(marked, modifier, style, onTextLayout = { result, _ -> layout(result) })
    }
}

@Composable
private fun FindTableRow(content: String, row: ASTNode, tableWidth: Dp, style: TextStyle, find: FindRowRender) {
    val settings = annotatorSettings()
    val padding = LocalMarkdownDimens.current.tableCellPadding
    Row(modifier = Modifier.widthIn(min = tableWidth), verticalAlignment = Alignment.Top) {
        for (cell in row.children) if (cell.type == CELL) {
            val text = content.buildMarkdownAnnotatedString(cell, style, settings)
            Column(Modifier.weight(1f).padding(padding)) {
                FindMarkedText(text, find, cell.startOffset) { marked, modifier, layout ->
                    MarkdownBasicText(marked, style, modifier, onTextLayout = layout)
                }
            }
        }
    }
}

@Composable
private fun FindMarkdownCode(model: MarkdownComponentModel, find: FindRowRender) {
    // Um diagrama não tem a linha original; durante a busca, o bloco com resultado mostra o código.
    val source = remember(model.content, model.node) { findCodeText(model.content, model.node) }
    CopyableCode(source) {
        MarkdownCodeBackground(
            color = LocalMarkdownColors.current.codeBackground,
            shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            val modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock)
            FindMarkedText(AnnotatedString(source), find, model.node.startOffset, modifier) { marked, decorated, layout ->
                MarkdownBasicText(marked, model.typography.code, decorated, onTextLayout = layout)
            }
        }
    }
}
