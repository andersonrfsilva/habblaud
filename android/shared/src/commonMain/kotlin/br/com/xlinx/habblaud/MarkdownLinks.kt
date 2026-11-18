package br.com.xlinx.habblaud

import io.ktor.http.Url
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Mesmo limite do desktop: conteúdo da conversa não pode abrir esquemas de aplicativos ou arquivos locais. */
internal fun isHttpMarkdownLink(uri: String): Boolean {
    if (!uri.startsWith("https://", ignoreCase = true) && !uri.startsWith("http://", ignoreCase = true)) return false
    // Ktor completa uma autoridade vazia com localhost; links do transcript precisam declarar o destino.
    val authority = uri.substringAfter("://").takeWhile { it !in "/?#" }
    if (authority.substringAfterLast('@').substringBefore(':').isBlank()) return false
    return runCatching { Url(uri).host.isNotBlank() }.getOrDefault(false)
}

/** Como no desktop, imagens viram links; o AST preserva código, escapes e referências sem buscar conteúdo remoto. */
internal fun markdownImageLinks(source: String): String {
    if ("![" !in source) return source
    val offsets = mutableListOf<Int>()
    fun collect(node: ASTNode) {
        if (node.type == MarkdownElementTypes.IMAGE && source.getOrNull(node.startOffset) == '!') offsets.add(node.startOffset)
        node.children.forEach(::collect)
    }
    collect(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source))
    if (offsets.isEmpty()) return source
    return buildString(source.length - offsets.size) {
        var start = 0
        for (offset in offsets) {
            append(source, start, offset)
            start = offset + 1
        }
        append(source, start, source.length)
    }
}

internal enum class ImageKind { DATA, HOST_FILE, REMOTE }

internal fun markdownImageKind(dest: String): ImageKind? = when {
    dest.startsWith("data:image/", ignoreCase = true) &&
        dest.substringBefore(',').endsWith(";base64", ignoreCase = true) && ',' in dest -> ImageKind.DATA
    dest.startsWith("/") && !dest.startsWith("//") -> ImageKind.HOST_FILE
    dest.startsWith("file:///", ignoreCase = true) && !dest.substring(7).startsWith("//") -> ImageKind.HOST_FILE
    isHttpMarkdownLink(dest) -> ImageKind.REMOTE
    else -> null
}

/** Link comum cujo destino termina em extensão de imagem também é desenhado. */
internal fun isImageLink(dest: String): Boolean =
    dest.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "svg")

internal fun markdownImages(source: String): List<Pair<String, String>> {
    val images = mutableListOf<Pair<String, String>>()
    fun descendant(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? =
        node.children.firstOrNull { it.type == type } ?: node.children.firstNotNullOfOrNull { descendant(it, type) }
    fun collect(node: ASTNode) {
        val image = node.type == MarkdownElementTypes.IMAGE
        val destination = when (node.type) {
            MarkdownElementTypes.IMAGE, MarkdownElementTypes.INLINE_LINK ->
                descendant(node, MarkdownElementTypes.LINK_DESTINATION) ?: descendant(node, MarkdownElementTypes.AUTOLINK)
            MarkdownElementTypes.AUTOLINK, org.intellij.markdown.flavours.gfm.GFMTokenTypes.GFM_AUTOLINK -> node
            else -> null
        }?.let { source.substring(it.startOffset, it.endOffset).removeSurrounding("<", ">") }
        if (destination != null && (image || isImageLink(destination)) && images.none { it.second == destination }) {
            val alt = descendant(node, MarkdownElementTypes.LINK_TEXT)
            images.add(alt?.let { source.substring(it.startOffset, it.endOffset).removeSurrounding("[", "]") }.orEmpty() to destination)
        }
        // O link pode envolver uma imagem; a imagem não tem mais nada a procurar.
        if (!image) node.children.forEach(::collect)
    }
    collect(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source))
    return images
}
