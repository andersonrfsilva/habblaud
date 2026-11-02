package br.com.xlinx.habblaud.data

import java.net.URI

object UrlPolicy {
    fun origin(input: String, confirmedHost: String?, consent: Boolean, allowLoopback: Boolean): String? {
        val uri = try { URI(input) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || (uri.rawPath != "" && uri.rawPath != "/")) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        if (scheme != "https" && scheme != "http") return null
        if (scheme == "http") {
            val loopback = allowLoopback && (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true))
            if (!loopback) {
                val parts = host.split('.')
                if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return null
                val numbers = parts.map { it.toInt() }
                if (numbers.any { it > 255 } || numbers[0] != 100 || numbers[1] !in 64..127 || host != confirmedHost || !consent) return null
            }
        }
        val canonicalHost = if (host.contains(':')) "[$host]" else host.lowercase()
        val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
        return "$scheme://$canonicalHost$port"
    }
}
