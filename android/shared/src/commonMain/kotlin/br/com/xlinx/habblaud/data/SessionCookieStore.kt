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
    val generations = mutableMapOf<String, Long>()
    val cookies = mutableMapOf<String, Cookie?>()
}
internal fun encodeCookies(cookies: Map<String, String>): String =
    kotlinx.serialization.json.buildJsonObject {
        put("v", kotlinx.serialization.json.JsonPrimitive(2))
        put("cookies", kotlinx.serialization.json.buildJsonObject {
            cookies.forEach { (origin, cookie) -> put(origin, kotlinx.serialization.json.JsonPrimitive(cookie)) }
        })
    }.toString()

internal fun decodeCookies(value: String): Map<String, String> = runCatching {
    val data = wireJson.parseToJsonElement(value).jsonObject
    if (data["v"]?.jsonPrimitive?.content == "2") {
        data.getValue("cookies").jsonObject.mapValues { it.value.jsonPrimitive.content }
    } else {
        mapOf(data.getValue("origin").jsonPrimitive.content to data.getValue("cookie").jsonPrimitive.content)
    }
}.getOrDefault(emptyMap())

/** Guarda o cookie da sessão remota de cada origem conectada. */
class SessionCookieStore(private val origin: String) {
    private val storage = SecureSessionStorage()
    private val lock = SessionMemory.lock
    private var invalidation: Long
        get() = SessionMemory.generations[origin] ?: 0L
        set(value) { SessionMemory.generations[origin] = value }
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
            val persisted = storage.read()?.let { value ->
                decodeCookies(value)[origin]?.let { runCatching { parseServerSetCookieHeader(it) }.getOrNull() }
            }
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
        val persisted = storage.read()?.let(::decodeCookies).orEmpty().toMutableMap()
        persisted[origin] = renderSetCookieHeader(retained)
        try {
            storage.write(encodeCookies(persisted))
            cookie = retained
        } catch (error: Exception) {
            cookie = null
            persisted.remove(origin)
            try {
                if (persisted.isEmpty()) storage.clear() else storage.write(encodeCookies(persisted))
            } catch (_: Exception) { storage.clear() }
            throw IllegalStateException("Não foi possível guardar a sessão com segurança", error)
        }
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
        try {
            invalidation++
            cookie = null
            val persisted = storage.read()?.let(::decodeCookies).orEmpty().toMutableMap()
            persisted.remove(origin)
            if (persisted.isEmpty()) storage.clear() else storage.write(encodeCookies(persisted))
        } finally { lock.unlock() }
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
