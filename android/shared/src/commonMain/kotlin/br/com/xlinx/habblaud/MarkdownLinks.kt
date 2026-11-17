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
