package br.com.xlinx.habblaud.data

import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
val wireJson = Json { ignoreUnknownKeys = true }

data class SessionKey(val origin: String, val provider: String, val account: String, val sessionId: String) {
    fun serialize(): String = buildJsonObject { put("origin", origin); put("provider", provider); put("account", account); put("sessionId", sessionId) }.toString()
    companion object {
        fun deserialize(value: String): SessionKey {
            val o = wireJson.parseToJsonElement(value).jsonObject
            return SessionKey(requireNotNull(o.string("origin")), requireNotNull(o.string("provider")), requireNotNull(o.string("account")), requireNotNull(o.string("sessionId")))
        }
    }
}

internal fun JsonObject.string(name: String): String? = this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
internal fun JsonObject.flag(name: String): Boolean = this[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
internal fun JsonObject.objects(name: String): List<JsonObject> = this[name]?.let { runCatching { it.jsonArray.mapNotNull { item -> item as? JsonObject } }.getOrNull() } ?: emptyList()

data class Avatar(val seed: Long, val look: String, val kind: String = "main", val parts: String? = null)

data class SessionItem(val key: SessionKey, val agentId: String?, val title: String, val project: String?, val status: String?, val activity: String?, val permission: Boolean = false, val agentName: String? = null, val canMessage: Boolean = false, val permissionId: String? = null, val lastAt: Long? = null, val avatar: Avatar? = null, val cast: String? = null, val castOpened: Boolean = false, val permissionQuestion: Boolean = false, val characterName: String? = null, val contextTokens: Long? = null, val contextWindow: Long? = null, val model: String? = null, val thinking: String? = null, val models: List<String> = emptyList())

data class TerminalEntry(val id: String, val kind: String, val text: String, val title: String? = null, val toolUseId: String? = null, val truncated: Boolean = false, val at: Long? = null) {
    val bytes: Int get() = (id + kind + text + (title ?: "") + (toolUseId ?: "")).toByteArray(Charsets.UTF_8).size
    companion object {
        fun parse(o: JsonObject): TerminalEntry? {
            val id = o.string("id") ?: return null
            val kind = o.string("kind") ?: "unknown"
            val known = kind in setOf("user", "assistant", "thinking", "tool", "result", "system")
            return TerminalEntry(id, if (known) kind else "unknown", if (known) listOfNotNull(o.string("text"), o.string("input"), o.string("detail")).joinToString("\n") else "", o.string("title"), o.string("toolUseId"), o.flag("truncated"), (o["at"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 })
        }
    }
}

class ConversationState {
    private val entries = mutableStateListOf<TerminalEntry>()
    private val ids = HashSet<String>()
    private var bytes = 0
    var truncated = false; private set
    var reduced = false; private set
    fun snapshot(): List<TerminalEntry> = entries
    fun init(items: List<TerminalEntry>, truncated: Boolean) {
        require(items.sumOf { it.bytes.toLong() } <= MAX_BYTES && items.all { it.bytes <= MAX_BYTES }) { "event-too-large" }
        entries.clear(); ids.clear(); bytes = 0; reduced = false; this.truncated = truncated; append(items)
    }
    fun append(items: List<TerminalEntry>) {
        require(items.sumOf { it.bytes.toLong() } <= MAX_BYTES && items.all { it.bytes <= MAX_BYTES }) { "event-too-large" }
        for (item in items) {
            if (!ids.add(item.id)) continue
            entries.add(item); bytes += item.bytes
            while (entries.size > 1000 || bytes > MAX_BYTES) {
                val removed = entries.removeAt(0); ids.remove(removed.id); bytes -= removed.bytes; reduced = true
            }
        }
    }
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
