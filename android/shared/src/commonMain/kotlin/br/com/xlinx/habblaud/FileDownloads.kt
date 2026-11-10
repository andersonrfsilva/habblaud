package br.com.xlinx.habblaud

import androidx.compose.runtime.Composable

data class SavedDownload(val location: String, val name: String)

interface DownloadTarget {
    suspend fun write(bytes: ByteArray, count: Int)
    suspend fun complete(): SavedDownload
    fun abort()
}

expect suspend fun createDownload(name: String): DownloadTarget
expect fun openDownload(file: SavedDownload)
expect fun showDownloadFolder(file: SavedDownload)
expect val canShowDownloadFolder: Boolean
@Composable
expect fun DownloadPermissionGate(content: @Composable (allowed: Boolean, request: () -> Unit) -> Unit)

internal fun sanitizedDownloadName(name: String): String {
    val clean = buildString {
        for (char in name) if (!char.isISOControl() && char != '/' && char != '\\') append(char)
    }.trim { it.isWhitespace() || it == '\u0085' }
        .trimStart { it == '.' || it.isWhitespace() || it == '\u0085' }
    return clean.ifEmpty { "arquivo" }
}

internal fun numberedDownloadName(name: String, index: Int): String {
    if (index == 0) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return "${name.substring(0, dot)} ($index)${name.substring(dot)}"
}
