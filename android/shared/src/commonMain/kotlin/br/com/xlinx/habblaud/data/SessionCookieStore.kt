package br.com.xlinx.habblaud.data

import io.ktor.http.*
import kotlin.time.Clock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal expect class SessionLock() {
    fun lock()
    fun unlock()
}
internal expect class SecureSessionStorage() {
    fun read(): String?
    fun write(value: String)
    fun clear()
}
private object SessionMemory {
    val lock = SessionLock()
    var generation = 0L
    val cookies = mutableMapOf<String, Cookie?>()
}
/** Only the remote session cookie for the selected origin is retained. */
class SessionCookieStore(private val origin: String) {
    private val storage = SecureSessionStorage()
    private val lock = SessionMemory.lock
    private var invalidation: Long
        get() = SessionMemory.generation
        set(value) { SessionMemory.generation = value }
    fun generation(): Long {
        lock.lock()
        try { return invalidation } finally { lock.unlock() }
    }
    private var cookie: Cookie?
        get() = SessionMemory.cookies[origin]
        set(value) { SessionMemory.cookies[origin] = value }
    init {
        lock.lock()
        try {
            val previous = cookie
            val persisted = storage.read()?.let { value -> runCatching {
                val data = wireJson.parseToJsonElement(value).jsonObject
                if (data["origin"]?.jsonPrimitive?.content != origin) null else parseServerSetCookieHeader(data["cookie"]!!.jsonPrimitive.content)
            }.getOrNull() }
            if (previous != null && persisted == null) invalidation++
            cookie = persisted
        } finally { lock.unlock() }
    }
    fun saveFromResponse(url: String, headers: List<String>) = saveFromResponse(url, headers, generation())
    fun saveFromResponse(url: String, headers: List<String>, expectedGeneration: Long) {
        lock.lock()
        try {
        if (expectedGeneration != invalidation) return
        if (Url(url).originString() != origin) return
        val value = headers.mapNotNull { runCatching { parseServerSetCookieHeader(it) }.getOrNull() }.lastOrNull { it.name == "habblaud_remote" } ?: return
        val parsed = Url(url)
        if (value.domain != null && !parsed.host.equals(value.domain!!.trimStart('.'), true) && !parsed.host.endsWith("." + value.domain!!.trimStart('.'), true)) return
        val now = Clock.System.now().toEpochMilliseconds()
        val expires = value.maxAge?.let { now + it.toLong() * 1000 } ?: value.expires?.timestamp
        if (expires != null && expires <= now) { clear(); return }
        val retained = value.copy(maxAge = null, expires = expires?.let { io.ktor.util.date.GMTDate(it) }, domain = value.domain ?: parsed.host, path = value.path ?: parsed.encodedPath.substringBeforeLast('/', "").ifEmpty { "/" })
        try {
            storage.write(kotlinx.serialization.json.buildJsonObject { put("origin", kotlinx.serialization.json.JsonPrimitive(origin)); put("cookie", kotlinx.serialization.json.JsonPrimitive(renderSetCookieHeader(retained))) }.toString())
            cookie = retained
        } catch (error: Exception) { cookie = null; storage.clear(); throw IllegalStateException("Não foi possível guardar a sessão com segurança", error) }
        } finally { lock.unlock() }
    }
    fun requestHeader(url: String): String? {
        lock.lock()
        try {
        val value = cookie ?: return null
        if (value.expires?.timestamp?.let { it <= Clock.System.now().toEpochMilliseconds() } == true) { clear(); return null }
        val target = Url(url)
        if (target.originString() != origin || (value.secure && target.protocol != URLProtocol.HTTPS)) return null
        val path = value.path ?: "/"
        val requestPath = target.encodedPath.ifEmpty { "/" }
        if (requestPath != path && !(requestPath.startsWith(path) && (path.endsWith('/') || requestPath.getOrNull(path.length) == '/'))) return null
        return "${value.name}=${value.value}"
        } finally { lock.unlock() }
    }
    fun clear() {
        lock.lock()
        try { invalidation++; SessionMemory.cookies.keys.toList().forEach { SessionMemory.cookies[it] = null }; storage.clear() } finally { lock.unlock() }
    }
    fun clear(expectedGeneration: Long): Boolean {
        lock.lock()
        try {
            if (expectedGeneration != invalidation) return false
            clear()
            return true
        } finally { lock.unlock() }
    }
}
private fun Url.originString(): String {
    val hostPart = if (host.contains(':')) "[$host]" else host
    return "${protocol.name}://$hostPart" + if (port == protocol.defaultPort) "" else ":$port"
}
