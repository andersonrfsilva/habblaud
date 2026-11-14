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

internal val Context.localState by preferencesDataStore(name = "local_state")
private val Context.rateState by preferencesDataStore(name = "login_rate")
/** Configurações do aparelho: sobrevivem a sair da conta e a trocar de servidor (local_state é apagado nesses casos). */
private val Context.appSettings by preferencesDataStore(name = "settings")

private class ConversationCoordinator {
    val lock = kotlinx.coroutines.sync.Mutex()
    val epochs = mutableMapOf<String, Long>()
    var sequence = 0L
    var sessionWritten = 0L
    val written = mutableMapOf<SessionKey, Long>()
}
private val conversationCoordinators = mutableMapOf<String, ConversationCoordinator>()
private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
private fun tag(origin: String) = digest(origin).take(16)
private fun hashed(prefix: String, key: SessionKey) = stringPreferencesKey(prefix + if (prefix.isEmpty()) digest(key.serialize()) else tag(key.origin) + "_" + digest(key.serialize()))
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

actual class LocalStateStore(private val context: Context) : MessageStore {
    actual constructor() : this(storageContext)
    actual suspend fun usesAttachment(uri: String): Boolean = context.localState.data.first().asMap().any { (key, value) ->
        key.name.startsWith("message_") && value is String && runCatching { decodeMessage(value).draft.attachments.any { it.uri == uri } }.getOrDefault(false)
    }
    private val originKey = stringPreferencesKey("origin")
    private val serversKey = stringPreferencesKey("servers")
    private fun serverList(data: androidx.datastore.preferences.core.Preferences): List<String> =
        data[serversKey]?.let { value -> org.json.JSONArray(value).let { array -> (0 until array.length()).map { array.getString(it) } } }.orEmpty()
    private suspend fun migrateServers() = coordinator.lock.withLock { migrateServersLocked() }
    private suspend fun migrateServersLocked() {
        val previous = context.localState.data.first()
        val origin = previous[originKey] ?: return
        if (previous[serversKey] != null) return
        withContext(Dispatchers.IO) {
            val directory = previous[cacheGenerationKey]?.let { java.io.File(conversations, it) } ?: conversations
            val files = if (directory.exists()) checkNotNull(directory.listFiles()) { "Could not list legacy conversation cache" } else emptyArray()
            files.filter { it.isFile && (it.extension == "json" || it.extension == "tmp") }.forEach {
                check(it.delete()) { "Could not remove legacy conversation cache" }
            }
        }
        context.localState.edit { data ->
            data[serversKey] = org.json.JSONArray(listOf(origin)).toString()
            previous.asMap().forEach { (key, value) ->
                val prefix = listOf("message_", "view_", "deliver_").firstOrNull { key.name.startsWith(it) && key.name.removePrefix(it).matches(Regex("[0-9a-f]{64}")) }
                if (prefix != null && value is String) {
                    data[stringPreferencesKey(prefix + tag(origin) + "_" + key.name.removePrefix(prefix))] = value
                    data.remove(stringPreferencesKey(key.name))
                }
            }
            data[stringPreferencesKey("seen_replies")]?.let {
                data[seenKey(origin)] = it
                data.remove(stringPreferencesKey("seen_replies"))
            }
            data.remove(originKey)
        }
    }
    // Chaves do antigo consentimento de HTTP pelo Tailscale: só para apagar o que ficou salvo.
    private val legacyKeys = listOf(booleanPreferencesKey("http_consent"), stringPreferencesKey("confirmed_http_host"))
    private val sessionKey = stringPreferencesKey("last_session")

    actual override suspend fun message(key: SessionKey): MessageLocalState {
        migrateServers()
        return decodeMessage(context.localState.data.first()[messageKey(key)])
    }
    actual override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        migrateServers()
        var result = MessageLocalState()
        context.localState.edit { data ->
            check(key.origin in serverList(data)) { "Connection changed" }
            val k = messageKey(key)
            result = update(decodeMessage(data[k]))
            data[k] = encodeMessage(result)
        }
        return result
    }
    actual suspend fun lastSession(): SessionKey? = context.localState.data.first()[sessionKey]?.let { runCatching { SessionKey.deserialize(it) }.getOrNull() }
    actual suspend fun setLastSession(key: SessionKey?) {
        context.localState.edit { if (key == null) it.remove(sessionKey) else it[sessionKey] = key.serialize() }
    }
    internal actual suspend fun setLastSession(key: SessionKey?, write: ConversationWrite) = coordinator.lock.withLock {
        if (write.epoch != conversationEpoch(write.origin) || write.sequence <= coordinator.sessionWritten) return@withLock
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
    internal actual fun conversationEpoch(origin: String): Long = synchronized(coordinator) { coordinator.epochs.getOrPut(origin) { 0L } }
    internal actual fun conversationWrite(origin: String, epoch: Long): ConversationWrite = synchronized(coordinator) {
        ConversationWrite(origin, epoch, ++coordinator.sequence)
    }
    private fun conversationFile(key: SessionKey, generation: String?): java.io.File =
        java.io.File(java.io.File(if (generation == null) conversations else java.io.File(conversations, generation), tag(key.origin)), hashed("", key).name + ".json")
    actual suspend fun conversation(key: SessionKey): CachedConversation? = coordinator.lock.withLock {
        migrateServersLocked()
        val data = context.localState.data.first()
        if (key.origin !in serverList(data)) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching { decodeConversation(conversationFile(key, data[cacheGenerationKey]).readText()) }.getOrNull()
        }
    }
    // Uma gravação por vez: as do fechamento da tela e as adiadas usam o mesmo `.tmp` e não podem se cruzar.
    internal actual var beforeConversationWrite: suspend () -> Unit = {}
    internal actual var afterConversationWrite: () -> Unit = {}
    internal actual suspend fun setConversation(key: SessionKey, entries: List<TerminalEntry>, truncated: Boolean, write: ConversationWrite, summaries: Map<String, String>) {
        try {
            beforeConversationWrite()
            coordinator.lock.withLock {
                migrateServersLocked()
                if (write.origin != key.origin || write.epoch != conversationEpoch(key.origin)) return@withLock
                val data = context.localState.data.first()
                if (key.origin !in serverList(data) || write.sequence <= (coordinator.written[key] ?: 0)) return@withLock
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
        coordinator.epochs.keys.toList().forEach { coordinator.epochs[it] = coordinator.epochs.getValue(it) + 1 }
        coordinator.written.clear()
    }
    private suspend fun removeObsoleteConversations(generation: String?) = withContext(Dispatchers.IO) {
        val files = if (conversations.exists()) checkNotNull(conversations.listFiles()) { "Could not list conversation cache" } else emptyArray()
        val failures = files.filter { if (generation == null) it.isDirectory else it.name != generation }.filterNot { it.deleteRecursively() }
        check(failures.isEmpty()) { "Could not remove old conversation cache" }
    }
    private fun retryKey(origin: String) = longPreferencesKey("retry_" + java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) })

    actual suspend fun retryUntil(origin: String): Long = context.rateState.data.first()[retryKey(origin)] ?: 0

    actual suspend fun setRetryUntil(origin: String, until: Long) {
        context.rateState.edit { data -> data[retryKey(origin)] = until }
    }

    actual suspend fun servers(): List<String> {
        migrateServers()
        return serverList(context.localState.data.first())
    }

    actual suspend fun addServer(origin: String) = withContext(kotlinx.coroutines.NonCancellable) {
        migrateServers()
        coordinator.lock.withLock {
            migrateLegacySettings()
            context.localState.edit { data ->
                val servers = serverList(data)
                if (origin !in servers) data[serversKey] = org.json.JSONArray(servers + origin).toString()
                legacyKeys.forEach { data.remove(it) }
            }
            Unit
        }
    }

    actual suspend fun sessionMarks(origin: String): Map<String, SessionMarks> {
        migrateServers()
        val prefix = "marks_${tag(origin)}_"
        return context.localState.data.first().asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(prefix) || value !is String) null
            else key.name.removePrefix(prefix) to decodeSessionMarks(wireJson.parseToJsonElement(value))
        }.toMap()
    }

    actual suspend fun setSessionMarks(key: SessionKey, marks: SessionMarks) {
        migrateServers()
        context.localState.edit { data ->
            check(key.origin in serverList(data)) { "Connection changed" }
            val name = hashed("marks_", key)
            if (marks == SessionMarks()) data.remove(name) else data[name] = encodeSessionMarks(marks).toString()
        }
    }

    actual suspend fun removeServer(origin: String) = withContext(kotlinx.coroutines.NonCancellable) {
        migrateServers()
        coordinator.lock.withLock {
            val previous = context.localState.data.first()
            context.localState.edit { data ->
                data[serversKey] = org.json.JSONArray(serverList(data) - origin).toString()
                data.asMap().keys.toList().forEach { key ->
                    if (listOf("message_", "view_", "deliver_", "marks_").any { key.name.startsWith(it + tag(origin) + "_") } || key.name == seenKey(origin).name) {
                        data.remove(stringPreferencesKey(key.name))
                    }
                }
                data[sessionKey]?.let { if (SessionKey.deserialize(it).origin == origin) data.remove(sessionKey) }
            }
            synchronized(coordinator) { coordinator.epochs[origin] = (coordinator.epochs[origin] ?: 0L) + 1 }
            coordinator.written.keys.removeAll { it.origin == origin }
            withContext(Dispatchers.IO) {
                val directory = java.io.File(if (previous[cacheGenerationKey] == null) conversations else java.io.File(conversations, previous[cacheGenerationKey]!!), tag(origin))
                check(!directory.exists() || directory.deleteRecursively()) { "Could not remove conversation cache" }
            }
        }
    }

    // Configurações (tela Configurações). As duas primeiras ficavam em local_state: lidas de lá até serem salvas de novo.
    private val notificationsKey = booleanPreferencesKey("notifications")
    /** Notificações com o app fechado (serviço em primeiro plano); ligadas por padrão. */
    actual suspend fun notificationsEnabled(): Boolean = (context.appSettings.data.first()[notificationsKey] ?: context.localState.data.first()[notificationsKey]) != false
    actual suspend fun setNotificationsEnabled(enabled: Boolean) { context.appSettings.edit { it[notificationsKey] = enabled } }

    private val deliverAsKey = stringPreferencesKey("deliver_as")
    /** Modo de envio padrão das sessões que ainda não têm o seu. */
    actual suspend fun deliverAs(): String = (context.appSettings.data.first()[deliverAsKey] ?: context.localState.data.first()[deliverAsKey])?.takeIf { it in DELIVER_MODES } ?: "aside"
    actual suspend fun setDeliverAs(mode: String) { if (mode in DELIVER_MODES) context.appSettings.edit { it[deliverAsKey] = mode } }

    private val viewKey = stringPreferencesKey("view")
    /** Visualização padrão das sessões que ainda não têm a sua. */
    actual suspend fun defaultView(): String = context.appSettings.data.first()[viewKey]?.takeIf { it in VIEWS } ?: "all"
    actual suspend fun setDefaultView(view: String) { if (view in VIEWS) context.appSettings.edit { it[viewKey] = view } }

    private val themeKey = stringPreferencesKey("theme")
    actual val theme: Flow<String> = context.appSettings.data.map { it[themeKey]?.takeIf { v -> v in THEMES } ?: "system" }
    actual suspend fun setTheme(theme: String) { if (theme in THEMES) context.appSettings.edit { it[themeKey] = theme } }

    private val summarizeKey = booleanPreferencesKey("summarize_replies")
    /** Respostas do agente aparecem resumidas (papel tiny do omp, no servidor); desligado por padrão. */
    actual val summarize: Flow<Boolean> = context.appSettings.data.map { it[summarizeKey] == true }
    actual suspend fun setSummarize(on: Boolean) { context.appSettings.edit { it[summarizeKey] = on } }

    private val notifyPermissionKey = booleanPreferencesKey("notify_permission")
    private val notifyDoneKey = booleanPreferencesKey("notify_done")
    /** Quais eventos viram notificação: pedido/pergunta e turno concluído (os dois ligados por padrão). */
    actual suspend fun notifyEvents(): Pair<Boolean, Boolean> = context.appSettings.data.first().let { (it[notifyPermissionKey] != false) to (it[notifyDoneKey] != false) }
    actual suspend fun setNotifyEvents(permission: Boolean, done: Boolean) { context.appSettings.edit { it[notifyPermissionKey] = permission; it[notifyDoneKey] = done } }

    // Escolhas por sessão: ficam em local_state (pertencem ao servidor atual) e caem no padrão quando ausentes.
    actual suspend fun sessionView(key: SessionKey): String {
        migrateServers()
        return context.localState.data.first()[hashed("view_", key)]?.takeIf { it in VIEWS } ?: defaultView()
    }
    actual suspend fun setSessionView(key: SessionKey, view: String) {
        migrateServers()
        if (view in VIEWS) context.localState.edit {
            check(key.origin in serverList(it)) { "Connection changed" }
            it[hashed("view_", key)] = view
        }
    }
    actual suspend fun sessionDeliverAs(key: SessionKey): String {
        migrateServers()
        return context.localState.data.first()[hashed("deliver_", key)]?.takeIf { it in DELIVER_MODES } ?: deliverAs()
    }
    actual suspend fun setSessionDeliverAs(key: SessionKey, mode: String) {
        migrateServers()
        if (mode in DELIVER_MODES) context.localState.edit {
            check(key.origin in serverList(it)) { "Connection changed" }
            it[hashed("deliver_", key)] = mode
        }
    }

    // Até quando cada conversa foi lida (relógio do servidor), para o balão de não lidas da tela principal. Fica em
    // local_state: pertence ao servidor atual e some ao sair da conta. Chave = hash da sessão.
    private fun seenKey(origin: String) = stringPreferencesKey("seen_replies_" + tag(origin))
    actual fun seenId(key: SessionKey): String = hashed("", key).name
    actual suspend fun seenReplies(origin: String): Map<String, Long> {
        migrateServers()
        return context.localState.data.first()[seenKey(origin)]?.let { value ->
            runCatching { org.json.JSONObject(value).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrNull()
        }.orEmpty()
    }
    actual suspend fun setSeenReplies(origin: String, seen: Map<String, Long>) {
        migrateServers()
        context.localState.edit { if (origin in serverList(it)) it[seenKey(origin)] = org.json.JSONObject(seen).toString() }
    }
    internal actual suspend fun setSeenReplies(origin: String, seen: Map<String, Long>, epoch: Long) = coordinator.lock.withLock {
        if (epoch != conversationEpoch(origin)) return@withLock
        context.localState.edit { if (origin in serverList(it)) it[seenKey(origin)] = org.json.JSONObject(seen).toString() }
    }

    actual suspend fun clear() = withContext(kotlinx.coroutines.NonCancellable) {
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


internal lateinit var storageContext: Context
fun initializeStorage(context: Context) { storageContext = context.applicationContext }
