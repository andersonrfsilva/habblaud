package br.com.xlinx.habblaud.data

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.EntityConverter
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes as Element
import org.intellij.markdown.MarkdownTokenTypes as Token
import org.intellij.markdown.IElementType
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes as GfmToken
import org.intellij.markdown.parser.MarkdownParser

internal expect fun decomposeSearchText(text: String): String

private val combiningMarks = Regex("\\p{M}+")

internal fun normalizeFindText(text: String): String = combiningMarks.replace(decomposeSearchText(text.lowercase()), "")
internal fun normalizeFindQuery(term: String): String = normalizeFindText(term.trim())

internal class FindBlock(val key: Int, text: String) {
    val normalized = normalizeFindText(text)
}

internal data class FindLocation(val block: Int, val occurrence: Int)
internal data class FindOccurrence(val message: Int, val location: FindLocation)

private inline fun String.findEach(query: String, match: (start: Int, end: Int) -> Unit) {
    if (query.isEmpty()) return
    var start = indexOf(query)
    while (start >= 0) {
        val end = start + query.length
        match(start, end)
        start = indexOf(query, end)
    }
}

internal fun conversationMatches(messages: List<List<FindBlock>>, query: String): List<FindOccurrence> = buildList {
    if (query.isEmpty()) return@buildList
    messages.forEachIndexed { message, blocks ->
        for (block in blocks) {
            var occurrence = 0
            block.normalized.findEach(query) { _, _ -> add(FindOccurrence(message, FindLocation(block.key, occurrence++))) }
        }
    }
}

/** Mapeia os índices normalizados de volta ao UTF-16 usado por AnnotatedString. */
internal fun findRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val normalized = normalizeFindText(text)
    if (query !in normalized) return emptyList()
    val offsets = IntArray(normalized.length + 1)
    var raw = 0
    var index = 0
    while (raw < text.length) {
        val char = text[raw]
        val end = raw + if (char.isHighSurrogate() && text.getOrNull(raw + 1)?.isLowSurrogate() == true) 2 else 1
        val size = if (char.code < 128) 1 else normalizeFindText(text.substring(raw, end)).length
        repeat(size) { offsets[index++] = raw }
        raw = end
    }
    offsets[index] = text.length
    return buildList {
        normalized.findEach(query) { start, end ->
            // Uma sílaba decomposta ainda é um único caractere no texto renderizado.
            var after = end
            while (after < normalized.length && offsets[after] == offsets[end - 1]) after++
            add(offsets[start] until offsets[after])
        }
    }
}

internal fun initialFindIndex(matches: List<FindOccurrence>, topVisible: Int): Int =
    matches.indexOfFirst { it.message >= topVisible }.takeIf { it >= 0 } ?: matches.lastIndex

internal fun stepFindIndex(current: Int, count: Int, direction: Int): Int =
    if (count == 0) -1 else (current + direction + count) % count

private val atxHeadings = setOf(Element.ATX_1, Element.ATX_2, Element.ATX_3, Element.ATX_4, Element.ATX_5, Element.ATX_6)

internal fun findHeadingContent(node: ASTNode): ASTNode =
    node.findChildOfType(if (node.type in atxHeadings) Token.ATX_CONTENT else Token.SETEXT_CONTENT) ?: node

/** O padrão da biblioteca, numa instância só, para as linhas sem resultado de busca. */
internal val defaultMarkdownAnnotator = markdownAnnotator()

internal val conversationMarkdownAnnotator = markdownAnnotator { source, node ->
    if (node.type == Token.TEXT && node.parent?.type != Element.CODE_SPAN) {
        append(EntityConverter.replaceEntities(node.getTextInNode(source), true, true))
        true
    } else false
}

internal fun findCodeText(source: String, node: ASTNode): String {
    if (node.type != Element.CODE_FENCE) return node.getTextInNode(source).toString().replaceIndent()
    return buildString {
        var opened = false
        for (child in node.children) {
            when (child.type) {
                Token.EOL -> if (opened) append('\n') else opened = true
                Token.CODE_FENCE_CONTENT -> append(child.getTextInNode(source))
            }
        }
    }.replaceIndent()
}

/** Reusa o anotador da conversa: o texto indexado tem as mesmas fronteiras que os blocos renderizados. */
internal fun findMarkdownBlocks(source: String): List<FindBlock> {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    val references = ReferenceLinkHandlerImpl()
    fun collectLinks(node: ASTNode) {
        val label = when (node.type) {
            Element.LINK_DEFINITION -> node.findChildOfType(Element.LINK_LABEL)
            Element.INLINE_LINK -> node.findChildOfType(Element.LINK_TEXT)
            else -> null
        }
        if (label != null) references.store(
            label.getUnescapedTextInNode(source),
            node.findChildOfType(Element.LINK_DESTINATION)?.getUnescapedTextInNode(source),
        )
        node.children.forEach(::collectLinks)
    }
    collectLinks(tree)
    val settings = DefaultAnnotatorSettings(TextLinkStyles(), SpanStyle(), conversationMarkdownAnnotator, references)
    return buildList {
        fun visit(node: ASTNode) {
            val type: IElementType = node.type
            val text = when {
                type == Element.PARAGRAPH || type == GfmToken.CELL ->
                    source.buildMarkdownAnnotatedString(node, TextStyle.Default, settings).text
                type in atxHeadings || type == Element.SETEXT_1 || type == Element.SETEXT_2 ->
                    source.buildMarkdownAnnotatedString(findHeadingContent(node), TextStyle.Default, settings).text
                type == Element.CODE_BLOCK || type == Element.CODE_FENCE -> findCodeText(source, node)
                type == Token.TEXT -> EntityConverter.replaceEntities(node.getTextInNode(source), true, true)
                type == Element.LINK_DEFINITION || type == Element.HTML_BLOCK -> return
                else -> {
                    node.children.forEach(::visit)
                    return
                }
            }
            if (text.isNotEmpty()) add(FindBlock(node.startOffset, text))
        }
        visit(tree)
    }
}
