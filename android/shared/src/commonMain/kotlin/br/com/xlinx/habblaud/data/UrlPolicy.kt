package br.com.xlinx.habblaud.data

import io.ktor.http.Url

object UrlPolicy {
    /** Origem canônica do servidor; aceita HTTP ou HTTPS em qualquer endereço. */
    fun origin(input: String): String? {
        val text = input.trim()
        if (!Regex("^https?://[^/?#]+/?$", RegexOption.IGNORE_CASE).matches(text)) return null
        val authority = text.substringAfter("://").removeSuffix("/")
        if ('@' in authority || '%' in authority || authority.any { it.isWhitespace() }) return null
        val explicitPort = if (authority.startsWith("[")) authority.substringAfter(']', "").removePrefix(":")
            else authority.substringAfter(':', "")
        if (explicitPort.isNotEmpty() && (explicitPort.toIntOrNull()?.let { it in 1..65535 } != true)) return null
        if (authority.endsWith(":")) return null
        val url = runCatching { Url(text) }.getOrNull() ?: return null
        if (url.host.isBlank()) return null
        val scheme = url.protocol.name.lowercase()
        val host = url.host.removeSurrounding("[", "]").lowercase()
        val canonicalHost = if (':' in host) "[$host]" else host
        val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
        return "$scheme://$canonicalHost$port"
    }
}
