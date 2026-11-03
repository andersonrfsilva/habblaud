package br.com.xlinx.habblaud.data

import java.net.URI

object UrlPolicy {
    /** Origem canônica do servidor; aceita HTTP ou HTTPS em qualquer endereço. */
    fun origin(input: String): String? {
        val uri = try { URI(input.trim()) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || (uri.rawPath != "" && uri.rawPath != "/")) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        if (scheme != "https" && scheme != "http") return null
        val canonicalHost = if (host.contains(':')) "[$host]" else host.lowercase()
        val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
        return "$scheme://$canonicalHost$port"
    }
}
