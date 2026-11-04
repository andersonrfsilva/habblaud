package br.com.xlinx.habblaud.data

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

internal data class ConversationWrite(val origin: String, val epoch: Long, val sequence: Long)

data class MessageDraft(val text: String = "", val revision: Long = 0, val attachments: List<Attachment> = emptyList())
data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0, val error: String? = null)
data class MessageLocalState(val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null, val control: MessageOperation? = null)
interface MessageStore {
    suspend fun message(key: SessionKey): MessageLocalState
    suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
}

val DELIVER_MODES = listOf("aside", "followUp", "steer")
val VIEWS = listOf("all", "prompts", "no_tools")
val THEMES = listOf("system", "light", "dark")


data class CachedConversation(val entries: List<TerminalEntry>, val truncated: Boolean, val summaries: Map<String, String> = emptyMap())

/** Chave do resumo de uma entrada: o id e o texto (uma entrada reescrita pede outro resumo). */
internal fun summaryKey(entry: TerminalEntry): String = "${entry.id}:${entry.text.hashCode()}"

/**
 * Guarda as últimas 500 entradas (a janela do `init` do servidor): o arquivo não cresce sem limite. Os resumos vão
 * junto, só os das entradas que ficaram.
 */
internal fun encodeConversation(entries: List<TerminalEntry>, truncated: Boolean, summaries: Map<String, String> = emptyMap()): String = buildJsonObject {
    val kept = entries.takeLast(500)
    put("truncated", truncated || entries.size > 500)
    put("entries", buildJsonArray {
        kept.forEach { e ->
            add(buildJsonObject {
                put("id", e.id); put("kind", e.kind); put("text", e.text)
                e.title?.let { put("title", it) }; e.toolUseId?.let { put("toolUseId", it) }
                if (e.truncated) put("truncated", true); e.at?.let { put("at", it) }
            })
        }
    })
    if (summaries.isNotEmpty()) put("summaries", buildJsonObject {
        kept.forEach { e -> val key = summaryKey(e); summaries[key]?.let { put(key, it) } }
    })
}.toString()

internal fun decodeConversation(text: String): CachedConversation {
    val root = wireJson.parseToJsonElement(text).jsonObject
    val summaries = (root["summaries"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content } }?.toMap().orEmpty()
    return CachedConversation(root.objects("entries").mapNotNull(TerminalEntry::parse), root.flag("truncated"), summaries)
}

expect class LocalStateStore() : MessageStore {
    suspend fun usesAttachment(uri: String): Boolean
    override suspend fun message(key: SessionKey): MessageLocalState
    override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
    suspend fun lastSession(): SessionKey?
    suspend fun setLastSession(key: SessionKey?)
    internal suspend fun setLastSession(key: SessionKey?, write: ConversationWrite)
    internal fun conversationEpoch(origin: String): Long
    internal fun conversationWrite(origin: String, epoch: Long): ConversationWrite
    suspend fun conversation(key: SessionKey): CachedConversation?
    internal var beforeConversationWrite: suspend () -> Unit
    internal var afterConversationWrite: () -> Unit
    internal suspend fun setConversation(key: SessionKey, entries: List<TerminalEntry>, truncated: Boolean, write: ConversationWrite, summaries: Map<String, String> = emptyMap())
    suspend fun retryUntil(origin: String): Long
    suspend fun setRetryUntil(origin: String, until: Long)
    suspend fun servers(): List<String>
    suspend fun addServer(origin: String)
    suspend fun removeServer(origin: String)
    suspend fun notificationsEnabled(): Boolean
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun deliverAs(): String
    suspend fun setDeliverAs(mode: String)
    suspend fun defaultView(): String
    suspend fun setDefaultView(view: String)
    val theme: Flow<String>
    suspend fun setTheme(theme: String)
    val summarize: Flow<Boolean>
    suspend fun setSummarize(on: Boolean)
    suspend fun notifyEvents(): Pair<Boolean, Boolean>
    suspend fun setNotifyEvents(permission: Boolean, done: Boolean)
    suspend fun sessionView(key: SessionKey): String
    suspend fun setSessionView(key: SessionKey, view: String)
    suspend fun sessionDeliverAs(key: SessionKey): String
    suspend fun setSessionDeliverAs(key: SessionKey, mode: String)
    fun seenId(key: SessionKey): String
    suspend fun seenReplies(origin: String): Map<String, Long>
    suspend fun setSeenReplies(origin: String, seen: Map<String, Long>)
    internal suspend fun setSeenReplies(origin: String, seen: Map<String, Long>, epoch: Long)
    suspend fun clear()
}
