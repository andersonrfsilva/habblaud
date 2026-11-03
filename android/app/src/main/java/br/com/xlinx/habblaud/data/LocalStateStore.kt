package br.com.xlinx.habblaud.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.localState by preferencesDataStore(name = "local_state")
private val Context.rateState by preferencesDataStore(name = "login_rate")
/** Configurações do aparelho: sobrevivem a sair da conta e a trocar de servidor (local_state é apagado nesses casos). */
private val Context.appSettings by preferencesDataStore(name = "settings")

private class ConversationCoordinator {
    val lock = kotlinx.coroutines.sync.Mutex()
    var epoch = 0L
    var sequence = 0L
    var sessionWritten = 0L
    val written = mutableMapOf<SessionKey, Long>()
}
private val conversationCoordinators = mutableMapOf<String, ConversationCoordinator>()
internal data class ConversationWrite(val epoch: Long, val sequence: Long)

data class ConnectionSettings(val origin: String)
data class MessageDraft(val text: String = "", val revision: Long = 0, val attachments: List<Attachment> = emptyList())
data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0, val error: String? = null)
data class MessageLocalState(val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null, val control: MessageOperation? = null)
interface MessageStore {
    suspend fun message(key: SessionKey): MessageLocalState
    suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
}

private fun hashed(prefix: String, key: SessionKey) = stringPreferencesKey(prefix + java.security.MessageDigest.getInstance("SHA-256").digest(key.serialize().toByteArray()).joinToString("") { "%02x".format(it) })
private fun messageKey(key: SessionKey) = hashed("message_", key)
private fun decodeMessage(value: String?): MessageLocalState {
    if (value == null) return MessageLocalState()
    val json = org.json.JSONObject(value)
    val attachments = json.optJSONArray("attachments")?.let { array -> (0 until array.length()).mapNotNull { i ->
        runCatching { array.getJSONObject(i).let { Attachment(it.getString("uri"), it.getString("name"), it.getString("mime"), if (it.isNull("size")) null else it.getLong("size"), it.optBoolean("persistent")) } }.getOrNull()
    } } ?: emptyList()
    val draft = MessageDraft(json.optString("text", ""), json.optLong("revision", 0), attachments)
    val op = json.optJSONObject("operation")?.let { MessageOperation(it.optString("id").ifEmpty { null }, it.optString("status", "unknown"), it.optLong("revision", 0)) }
    val control = json.optJSONObject("control")?.let { MessageOperation(it.optString("id").ifEmpty { null }, it.optString("status", "unknown"), it.optLong("revision", 0), it.optString("error").ifEmpty { null }) }
    return MessageLocalState(draft, op, control)
}
private fun encodeMessage(state: MessageLocalState): String {
    val json = org.json.JSONObject().put("text", state.draft.text).put("revision", state.draft.revision)
    json.put("attachments", org.json.JSONArray().also { array -> state.draft.attachments.forEach { attachment ->
        array.put(org.json.JSONObject().put("uri", attachment.uri).put("name", attachment.name).put("mime", attachment.mime).put("size", attachment.size ?: org.json.JSONObject.NULL).put("persistent", attachment.persistent))
    } })
    state.operation?.let { json.put("operation", org.json.JSONObject().put("id", it.id).put("status", it.status).put("revision", it.revision)) }
    state.control?.let { json.put("control", org.json.JSONObject().put("id", it.id).put("status", it.status).put("revision", it.revision).put("error", it.error)) }
    return json.toString()
}

class LocalStateStore(private val context: Context) : MessageStore {
    suspend fun usesAttachment(uri: String): Boolean = context.localState.data.first().asMap().any { (key, value) ->
        key.name.startsWith("message_") && value is String && runCatching { decodeMessage(value).draft.attachments.any { it.uri == uri } }.getOrDefault(false)
    }
    private val originKey = stringPreferencesKey("origin")
    // Chaves do antigo consentimento de HTTP pelo Tailscale: só para apagar o que ficou salvo.
    private val legacyKeys = listOf(booleanPreferencesKey("http_consent"), stringPreferencesKey("confirmed_http_host"))
    private val sessionKey = stringPreferencesKey("last_session")

    override suspend fun message(key: SessionKey): MessageLocalState = decodeMessage(context.localState.data.first()[messageKey(key)])
    override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        var result = MessageLocalState()
        context.localState.edit { data ->
            check(data[originKey] == key.origin) { "Connection changed" }
            val k = messageKey(key)
            result = update(decodeMessage(data[k]))
            data[k] = encodeMessage(result)
        }
        return result
    }
    suspend fun lastSession(): SessionKey? = context.localState.data.first()[sessionKey]?.let { runCatching { SessionKey.deserialize(it) }.getOrNull() }
    suspend fun setLastSession(key: SessionKey?) {
        context.localState.edit { if (key == null) it.remove(sessionKey) else it[sessionKey] = key.serialize() }
    }
    internal suspend fun setLastSession(key: SessionKey?, write: ConversationWrite) = coordinator.lock.withLock {
        if (synchronized(coordinator) { write.epoch != coordinator.epoch } || write.sequence <= coordinator.sessionWritten) return@withLock
        context.localState.edit { data ->
            if (key == null) data.remove(sessionKey) else data[sessionKey] = key.serialize()
        }
        coordinator.sessionWritten = write.sequence
    }

    // Conversas guardadas no aparelho: abrir uma sessão mostra o que já foi lido na hora, e o servidor só manda o que
    // veio depois (init incremental). Arquivos em filesDir, apagados ao sair da conta ou trocar de servidor.
    private val conversations = java.io.File(context.filesDir, "conversations")
    private val cacheGenerationKey = stringPreferencesKey("conversation_generation")
    private val coordinator = synchronized(conversationCoordinators) {
        conversationCoordinators.getOrPut(conversations.absolutePath) { ConversationCoordinator() }
    }
    internal fun conversationEpoch(): Long = synchronized(coordinator) { coordinator.epoch }
    internal fun conversationWrite(epoch: Long): ConversationWrite = synchronized(coordinator) {
        ConversationWrite(epoch, ++coordinator.sequence)
    }
    private fun conversationFile(key: SessionKey, generation: String?): java.io.File =
        java.io.File(if (generation == null) conversations else java.io.File(conversations, generation), hashed("", key).name + ".json")
    suspend fun conversation(key: SessionKey): CachedConversation? = coordinator.lock.withLock {
        val data = context.localState.data.first()
        if (data[originKey] != key.origin) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching { decodeConversation(conversationFile(key, data[cacheGenerationKey]).readText()) }.getOrNull()
        }
    }
    // Uma gravação por vez: as do fechamento da tela e as adiadas usam o mesmo `.tmp` e não podem se cruzar.
    internal var beforeConversationWrite: suspend () -> Unit = {}
    internal var afterConversationWrite: () -> Unit = {}
    internal suspend fun setConversation(key: SessionKey, entries: List<TerminalEntry>, truncated: Boolean, write: ConversationWrite, summaries: Map<String, String> = emptyMap()) {
        try {
            beforeConversationWrite()
            coordinator.lock.withLock {
                if (synchronized(coordinator) { write.epoch != coordinator.epoch }) return@withLock
                val data = context.localState.data.first()
                if (data[originKey] != key.origin || write.sequence <= (coordinator.written[key] ?: 0)) return@withLock
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = conversationFile(key, data[cacheGenerationKey])
                        file.parentFile!!.mkdirs()
                        val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
                        tmp.writeText(encodeConversation(entries, truncated, summaries))
                        check(tmp.renameTo(file))
                        coordinator.written[key] = write.sequence
                    }
                }
            }
        } finally {
            afterConversationWrite()
        }
    }
    private fun invalidateConversations() = synchronized(coordinator) {
        coordinator.epoch++
        coordinator.written.clear()
    }
    private suspend fun removeObsoleteConversations(generation: String?) = withContext(Dispatchers.IO) {
        val files = if (conversations.exists()) checkNotNull(conversations.listFiles()) { "Could not list conversation cache" } else emptyArray()
        val failures = files.filter { if (generation == null) it.isDirectory else it.name != generation }.filterNot { it.deleteRecursively() }
        check(failures.isEmpty()) { "Could not remove old conversation cache" }
    }
    private fun retryKey(origin: String) = longPreferencesKey("retry_" + java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) })

    suspend fun retryUntil(origin: String): Long = context.rateState.data.first()[retryKey(origin)] ?: 0

    suspend fun setRetryUntil(origin: String, until: Long) {
        context.rateState.edit { data -> data[retryKey(origin)] = until }
    }

    suspend fun connection(): ConnectionSettings? {
        val data = context.localState.data.first()
        return data[originKey]?.let { ConnectionSettings(it) }
    }

    suspend fun setConnection(origin: String) = withContext(kotlinx.coroutines.NonCancellable) {
        coordinator.lock.withLock {
            migrateLegacySettings()
            val previous = context.localState.data.first()
            val changed = previous[originKey] != origin
            val generation = if (changed) java.util.UUID.randomUUID().toString() else previous[cacheGenerationKey]
            context.localState.edit { data ->
                if (changed) {
                    data.clear()
                    data[cacheGenerationKey] = generation!!
                }
                data[originKey] = origin
                legacyKeys.forEach { data.remove(it) }
            }
            if (changed) invalidateConversations()
            removeObsoleteConversations(generation)
        }
    }

    // Configurações (tela Configurações). As duas primeiras ficavam em local_state: lidas de lá até serem salvas de novo.
    private val notificationsKey = booleanPreferencesKey("notifications")
    /** Notificações com o app fechado (serviço em primeiro plano); ligadas por padrão. */
    suspend fun notificationsEnabled(): Boolean = (context.appSettings.data.first()[notificationsKey] ?: context.localState.data.first()[notificationsKey]) != false
    suspend fun setNotificationsEnabled(enabled: Boolean) { context.appSettings.edit { it[notificationsKey] = enabled } }

    private val deliverAsKey = stringPreferencesKey("deliver_as")
    /** Modo de envio padrão das sessões que ainda não têm o seu. */
    suspend fun deliverAs(): String = (context.appSettings.data.first()[deliverAsKey] ?: context.localState.data.first()[deliverAsKey])?.takeIf { it in DELIVER_MODES } ?: "aside"
    suspend fun setDeliverAs(mode: String) { if (mode in DELIVER_MODES) context.appSettings.edit { it[deliverAsKey] = mode } }

    private val viewKey = stringPreferencesKey("view")
    /** Visualização padrão das sessões que ainda não têm a sua. */
    suspend fun defaultView(): String = context.appSettings.data.first()[viewKey]?.takeIf { it in VIEWS } ?: "all"
    suspend fun setDefaultView(view: String) { if (view in VIEWS) context.appSettings.edit { it[viewKey] = view } }

    private val themeKey = stringPreferencesKey("theme")
    val theme: Flow<String> = context.appSettings.data.map { it[themeKey]?.takeIf { v -> v in THEMES } ?: "system" }
    suspend fun setTheme(theme: String) { if (theme in THEMES) context.appSettings.edit { it[themeKey] = theme } }

    private val summarizeKey = booleanPreferencesKey("summarize_replies")
    /** Respostas do agente aparecem resumidas (papel tiny do omp, no servidor); desligado por padrão. */
    val summarize: Flow<Boolean> = context.appSettings.data.map { it[summarizeKey] == true }
    suspend fun setSummarize(on: Boolean) { context.appSettings.edit { it[summarizeKey] = on } }

    private val notifyPermissionKey = booleanPreferencesKey("notify_permission")
    private val notifyDoneKey = booleanPreferencesKey("notify_done")
    /** Quais eventos viram notificação: pedido/pergunta e turno concluído (os dois ligados por padrão). */
    suspend fun notifyEvents(): Pair<Boolean, Boolean> = context.appSettings.data.first().let { (it[notifyPermissionKey] != false) to (it[notifyDoneKey] != false) }
    suspend fun setNotifyEvents(permission: Boolean, done: Boolean) { context.appSettings.edit { it[notifyPermissionKey] = permission; it[notifyDoneKey] = done } }

    // Escolhas por sessão: ficam em local_state (pertencem ao servidor atual) e caem no padrão quando ausentes.
    suspend fun sessionView(key: SessionKey): String = context.localState.data.first()[hashed("view_", key)]?.takeIf { it in VIEWS } ?: defaultView()
    suspend fun setSessionView(key: SessionKey, view: String) { if (view in VIEWS) context.localState.edit { it[hashed("view_", key)] = view } }
    suspend fun sessionDeliverAs(key: SessionKey): String = context.localState.data.first()[hashed("deliver_", key)]?.takeIf { it in DELIVER_MODES } ?: deliverAs()
    suspend fun setSessionDeliverAs(key: SessionKey, mode: String) { if (mode in DELIVER_MODES) context.localState.edit { it[hashed("deliver_", key)] = mode } }

    // Até quando cada conversa foi lida (relógio do servidor), para o balão de não lidas da tela principal. Fica em
    // local_state: pertence ao servidor atual e some ao sair da conta. Chave = hash da sessão.
    private val seenKey = stringPreferencesKey("seen_replies")
    fun seenId(key: SessionKey): String = hashed("", key).name
    suspend fun seenReplies(): Map<String, Long> = context.localState.data.first()[seenKey]?.let { value ->
        runCatching { org.json.JSONObject(value).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrNull()
    }.orEmpty()
    suspend fun setSeenReplies(seen: Map<String, Long>) { context.localState.edit { it[seenKey] = org.json.JSONObject(seen).toString() } }

    suspend fun clear() = withContext(kotlinx.coroutines.NonCancellable) {
        coordinator.lock.withLock {
            migrateLegacySettings()
            val generation = java.util.UUID.randomUUID().toString()
            context.localState.edit { data -> data.clear(); data[cacheGenerationKey] = generation }
            invalidateConversations()
            removeObsoleteConversations(generation)
        }
    }

    /** Leva notificações e modo de envio antigos de local_state para settings antes de local_state ser apagado. */
    private suspend fun migrateLegacySettings() {
        val old = context.localState.data.first()
        context.appSettings.edit { settings ->
            if (settings[notificationsKey] == null) old[notificationsKey]?.let { settings[notificationsKey] = it }
            if (settings[deliverAsKey] == null) old[deliverAsKey]?.let { settings[deliverAsKey] = it }
        }
    }
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
