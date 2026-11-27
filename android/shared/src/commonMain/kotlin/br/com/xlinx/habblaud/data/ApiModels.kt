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

/** Id de quem foi chamado como "Novo" (fora do elenco), igual a NEW_CAST_PREFIX de shared/types.ts no servidor. */
const val NEW_CAST_PREFIX = "novo-"

data class SessionItem(val key: SessionKey, val agentId: String?, val title: String, val project: String?, val status: String?, val activity: String?, val permission: Boolean = false, val agentName: String? = null, val canMessage: Boolean = false, val permissionId: String? = null, val lastAt: Long? = null, val avatar: Avatar? = null, val cast: String? = null, val castOpened: Boolean = false, val permissionQuestion: Boolean = false, val characterName: String? = null, val contextTokens: Long? = null, val contextWindow: Long? = null, val model: String? = null, val thinking: String? = null, val models: List<String> = emptyList(), val subagents: List<SubAgent> = emptyList(), val sub: Boolean = false, val replies: List<Long> = emptyList(), val statusSince: Long? = null, val roomId: String? = null, val role: String? = null, val task: String? = null, val parentKey: SessionKey? = null, val serverSessionId: String? = null, val tasks: List<AgentTask> = emptyList())

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, ABANDONED }

data class AgentTask(val id: String, val title: String, val status: TaskStatus, val activeForm: String? = null, val phase: String? = null) {
    val displayTitle: String get() = if (status == TaskStatus.IN_PROGRESS) activeForm ?: title else title
}

/** Subagente ou advisor trabalhando agora sob um agente principal (some do snapshot ao terminar). */
data class SubAgent(val id: String, val name: String, val avatar: Avatar?, val advisor: Boolean, val activity: String?, val role: String? = null, val task: String? = null, val status: String? = null, val statusSince: Long? = null, val lastAt: Long? = null, val model: String? = null, val thinking: String? = null, val contextTokens: Long? = null, val contextWindow: Long? = null, val sessionId: String? = null)

/**
 * Conversa de um subagente para ler (sem mensagens nem controles): a chave é a do principal com o id do subagente,
 * para não se confundir com a sessão dele; o stream é o do terminal do subagente (`/api/agents/<id>/terminal`).
 */
fun subSession(parent: SessionItem, sub: SubAgent): SessionItem = SessionItem(
    parent.key.copy(sessionId = "${parent.key.sessionId}/${sub.id}"), sub.id, sub.name, parent.project, sub.status, sub.activity,
    avatar = sub.avatar, characterName = sub.name, sub = true, role = sub.role, task = sub.task,
    statusSince = sub.statusSince, lastAt = sub.lastAt, model = sub.model, thinking = sub.thinking,
    contextTokens = sub.contextTokens, contextWindow = sub.contextWindow, parentKey = parent.key, serverSessionId = sub.sessionId,
)

data class TerminalEntry(val id: String, val kind: String, val text: String, val title: String? = null, val toolUseId: String? = null, val truncated: Boolean = false, val at: Long? = null) {
    val bytes: Int get() = (id + kind + text + (title ?: "") + (toolUseId ?: "")).encodeToByteArray().size
    companion object {
        fun parse(o: JsonObject): TerminalEntry? {
            val id = o.string("id") ?: return null
            val kind = o.string("kind") ?: "unknown"
            val known = when (kind) {
                "user", "assistant", "thinking", "advisor", "tool", "result", "system" -> true
                else -> false
            }
            val text = when {
                kind == "advisor" -> o.string("text").orEmpty()
                known -> listOfNotNull(o.string("text"), o.string("input"), o.string("detail")).joinToString("\n")
                else -> ""
            }
            return TerminalEntry(id, if (known) kind else "unknown", text, o.string("title"), o.string("toolUseId"), o.flag("truncated"), (o["at"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 })
        }
    }
}

class ConversationState {
    private val entries = mutableStateListOf<TerminalEntry>()
    private val ids = HashSet<String>()
    private var bytes = 0
    var truncated = false; private set
    var reduced = false; private set
    /** Cópia: a tela memoriza pelas entradas publicadas; a lista viva nunca mudaria de identidade e o cache ficaria velho. */
    fun snapshot(): List<TerminalEntry> = entries.toList()
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
