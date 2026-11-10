// Porta de iosMain/.../LocalStateStore.kt (mesmo formato em disco). Mudanças de regra valem nos dois.
package br.com.xlinx.habblaud.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

internal fun storageHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal val privateDirectory: String get() = dataDir.toString()
internal fun protectedWrite(path: String, text: String) {
    val target = Path.of(path)
    val temporary = Path.of("$path.tmp")
    Files.writeString(temporary, text)
    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
}
internal fun privateRead(path: String): String? = Path.of(path).let { if (Files.exists(it)) Files.readString(it) else null }
internal fun initializeAccountStorage() { StateDisk.ensureInitialized() }
private object StateDisk {
    val lock = Mutex()
    private val epochLock = ReentrantLock()
    fun <T> synchronizedEpoch(block: () -> T): T {
        epochLock.lock()
        try { return block() } finally { epochLock.unlock() }
    }
    val epochs = mutableMapOf<String, Long>()
    var sequence = 0L
    var sessionWritten = 0L
    val written = mutableMapOf<SessionKey, Long>()
    val settings get() = settingsNode
    val theme = MutableStateFlow(settings.get("theme", null) ?: "system")
    val summarize = MutableStateFlow(settings.getBoolean("summarize_replies", false))
    fun read(): MutableMap<String, JsonElement> = privateRead("$privateDirectory/local_state.json")?.let { wireJson.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
    fun save(data: Map<String, JsonElement>) = protectedWrite("$privateDirectory/local_state.json", JsonObject(data).toString())
    fun ensureInitialized() = Unit
}
private fun Map<String, JsonElement>.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun messageDecode(value: JsonElement?): MessageLocalState {
    val o = value as? JsonObject ?: return MessageLocalState()
    fun operation(name: String): MessageOperation? = (o[name] as? JsonObject)?.let { MessageOperation(it.string("id"), it.string("status") ?: "unknown", (it["revision"] as? JsonPrimitive)?.longOrNull ?: 0, it.string("error")) }
    val attachments = (o["attachments"] as? JsonArray).orEmpty().mapNotNull { item -> runCatching { item.jsonObject.let { Attachment(it.string("uri")!!, it.string("name")!!, it.string("mime")!!, (it["size"] as? JsonPrimitive)?.longOrNull, (it["persistent"] as? JsonPrimitive)?.booleanOrNull == true) } }.getOrNull() }
    return MessageLocalState(MessageDraft(o.string("text") ?: "", (o["revision"] as? JsonPrimitive)?.longOrNull ?: 0, attachments), operation("operation"), operation("control"))
}
private fun messageEncode(state: MessageLocalState) = buildJsonObject {
    put("text", state.draft.text); put("revision", state.draft.revision)
    put("attachments", buildJsonArray { state.draft.attachments.forEach { a -> add(buildJsonObject { put("uri", a.uri); put("name", a.name); put("mime", a.mime); put("size", a.size?.let(::JsonPrimitive) ?: JsonNull); put("persistent", a.persistent) }) } })
    fun op(name: String, value: MessageOperation?) { value?.let { put(name, buildJsonObject { put("id", it.id?.let(::JsonPrimitive) ?: JsonNull); put("status", it.status); put("revision", it.revision); it.error?.let { error -> put("error", error) } }) } }
    op("operation", state.operation); op("control", state.control)
}
actual class LocalStateStore actual constructor() : MessageStore {
    private fun tag(origin: String) = storageHash(origin).take(16)
    private fun origins(data: Map<String, JsonElement>): List<String> =
        (data["servers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun readState(): MutableMap<String, JsonElement> {
        val data = StateDisk.read()
        val legacyOrigin = data.string("origin")
        if ("servers" !in data && legacyOrigin != null) {
            val tag = tag(legacyOrigin)
            data["servers"] = JsonArray(listOf(JsonPrimitive(legacyOrigin)))
            for (prefix in listOf("message_", "view_", "deliver_")) {
                data.keys.filter { it.startsWith(prefix) && it.removePrefix(prefix).matches(Regex("[0-9a-f]{64}")) }.forEach { key ->
                    data[prefix + tag + "_" + key.removePrefix(prefix)] = data.remove(key)!!
                }
            }
            data.remove("seen_replies")?.let { data["seen_replies_$tag"] = it }
            data.remove("origin")
            StateDisk.save(data)
            Files.list(dataDir).use { files -> files.filter { it.fileName.toString().startsWith("conversation-") }.forEach { Files.delete(it) } }
        }
        return data
    }
    private suspend fun <T> read(block: (MutableMap<String, JsonElement>) -> T): T = StateDisk.lock.withLock { block(readState()) }
    private suspend fun change(block: (MutableMap<String, JsonElement>) -> Unit) = StateDisk.lock.withLock { val data = readState(); block(data); StateDisk.save(data) }
    private fun hash(prefix: String, key: SessionKey) = prefix + tag(key.origin) + "_" + storageHash(key.serialize())
    actual suspend fun usesAttachment(uri: String): Boolean = read { data -> data.filterKeys { it.startsWith("message_") }.values.any { messageDecode(it).draft.attachments.any { a -> a.uri == uri } } }
    actual override suspend fun message(key: SessionKey): MessageLocalState = read { messageDecode(it[hash("message_", key)]) }
    actual override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        var result = MessageLocalState()
        change { check(key.origin in origins(it)) { "Connection changed" }; result = update(messageDecode(it[hash("message_", key)])); it[hash("message_", key)] = messageEncode(result) }
        return result
    }
    actual suspend fun lastSession(): SessionKey? = read { it.string("last_session")?.let { value -> runCatching { SessionKey.deserialize(value) }.getOrNull() } }
    actual suspend fun setLastSession(key: SessionKey?) = change { if (key == null) it.remove("last_session") else it["last_session"] = JsonPrimitive(key.serialize()) }
    internal actual suspend fun setLastSession(key: SessionKey?, write: ConversationWrite) = StateDisk.lock.withLock {
        val data = readState()
        if (write.epoch != conversationEpoch(write.origin) || write.origin !in origins(data) || (key != null && key.origin != write.origin) || write.sequence <= StateDisk.sessionWritten) return@withLock
        if (key == null) data.remove("last_session") else data["last_session"] = JsonPrimitive(key.serialize())
        StateDisk.save(data); StateDisk.sessionWritten = write.sequence
    }
    internal actual fun conversationEpoch(origin: String): Long = StateDisk.synchronizedEpoch { StateDisk.epochs.getOrPut(origin) { 0L } }
    internal actual fun conversationWrite(origin: String, epoch: Long): ConversationWrite = StateDisk.synchronizedEpoch { ConversationWrite(origin, epoch, ++StateDisk.sequence) }
    private fun conversationPath(key: SessionKey, data: Map<String, JsonElement>) = "$privateDirectory/conversation-${data.string("conversation_generation")}-${tag(key.origin)}-${storageHash(key.serialize())}.json"
    actual suspend fun conversation(key: SessionKey): CachedConversation? = read { if (key.origin !in origins(it)) null else privateRead(conversationPath(key, it))?.let { text -> runCatching { decodeConversation(text) }.getOrNull() } }
    internal actual var beforeConversationWrite: suspend () -> Unit = {}
    internal actual var afterConversationWrite: () -> Unit = {}
    internal actual suspend fun setConversation(key: SessionKey, entries: List<TerminalEntry>, truncated: Boolean, write: ConversationWrite, summaries: Map<String, String>) {
        try { beforeConversationWrite(); StateDisk.lock.withLock {
            val data = readState()
            if (write.origin != key.origin || write.epoch != conversationEpoch(key.origin) || key.origin !in origins(data) || write.sequence <= (StateDisk.written[key] ?: 0)) return@withLock
            protectedWrite(conversationPath(key, data), encodeConversation(entries, truncated, summaries)); StateDisk.written[key] = write.sequence
        } } finally { afterConversationWrite() }
    }
    actual suspend fun retryUntil(origin: String): Long = StateDisk.settings.getLong("retry_" + storageHash(origin), 0)
    actual suspend fun setRetryUntil(origin: String, until: Long) { StateDisk.settings.putLong("retry_" + storageHash(origin), until); StateDisk.settings.flush() }
    actual suspend fun servers(): List<String> = read { origins(it) }
    actual suspend fun addServer(origin: String) = withContext(NonCancellable) {
        StateDisk.lock.withLock {
            val data = readState()
            val servers = origins(data)
            if (origin !in servers) {
                data["servers"] = JsonArray((servers + origin).map(::JsonPrimitive))
                StateDisk.save(data)
            }
        }
    }
    actual suspend fun removeServer(origin: String) = withContext(NonCancellable) {
        StateDisk.lock.withLock {
            val data = readState()
            val tag = tag(origin)
            data["servers"] = JsonArray(origins(data).filterNot { it == origin }.map(::JsonPrimitive))
            data.keys.filter { key -> listOf("message_", "view_", "deliver_").any { key.startsWith("${it}${tag}_") } || key == "seen_replies_$tag" }.forEach { data.remove(it) }
            val last = data.string("last_session")?.let { runCatching { SessionKey.deserialize(it) }.getOrNull() }
            if (last?.origin == origin) data.remove("last_session")
            StateDisk.save(data)
            StateDisk.synchronizedEpoch { StateDisk.epochs[origin] = (StateDisk.epochs[origin] ?: 0L) + 1 }
            StateDisk.written.keys.filter { it.origin == origin }.forEach { StateDisk.written.remove(it) }
            Files.list(dataDir).use { files -> files.filter {
                val name = it.fileName.toString()
                name.startsWith("conversation-") && name.endsWith(".json") && name.substringBeforeLast('-').endsWith("-$tag")
            }.forEach { Files.delete(it) } }
        }
    }
    actual suspend fun clear() = withContext(NonCancellable) {
        StateDisk.lock.withLock {
            val data = readState()
            val servers = origins(data)
            data.clear(); data["conversation_generation"] = JsonPrimitive(UUID.randomUUID().toString())
            StateDisk.save(data)
            StateDisk.synchronizedEpoch {
                (StateDisk.epochs.keys.toSet() + servers).forEach { origin -> StateDisk.epochs[origin] = (StateDisk.epochs[origin] ?: 0L) + 1 }
            }
            StateDisk.written.clear()
            Files.list(dataDir).use { files -> files.filter { it.fileName.toString().startsWith("conversation-") || it.fileName.toString().startsWith("attachment-") }.forEach { Files.delete(it) } }
        }
    }
    actual suspend fun notificationsEnabled(): Boolean = StateDisk.settings.getBoolean("notifications", false)
    actual suspend fun setNotificationsEnabled(enabled: Boolean) { StateDisk.settings.putBoolean("notifications", enabled); StateDisk.settings.flush() }
    actual suspend fun deliverAs(): String = StateDisk.settings.get("deliver_as", null)?.takeIf { it in DELIVER_MODES } ?: "aside"
    actual suspend fun setDeliverAs(mode: String) { if (mode in DELIVER_MODES) { StateDisk.settings.put("deliver_as", mode); StateDisk.settings.flush() } }
    actual suspend fun defaultView(): String = StateDisk.settings.get("view", null)?.takeIf { it in VIEWS } ?: "all"
    actual suspend fun setDefaultView(view: String) { if (view in VIEWS) { StateDisk.settings.put("view", view); StateDisk.settings.flush() } }
    actual val theme: Flow<String> = StateDisk.theme
    actual suspend fun setTheme(theme: String) { if (theme in THEMES) { StateDisk.settings.put("theme", theme); StateDisk.settings.flush(); StateDisk.theme.value = theme } }
    actual val summarize: Flow<Boolean> = StateDisk.summarize
    actual suspend fun setSummarize(on: Boolean) { StateDisk.settings.putBoolean("summarize_replies", on); StateDisk.settings.flush(); StateDisk.summarize.value = on }
    actual suspend fun notifyEvents(): Pair<Boolean, Boolean> = StateDisk.settings.getBoolean("notify_permission", true) to StateDisk.settings.getBoolean("notify_done", true)
    actual suspend fun setNotifyEvents(permission: Boolean, done: Boolean) { StateDisk.settings.putBoolean("notify_permission", permission); StateDisk.settings.flush(); StateDisk.settings.putBoolean("notify_done", done); StateDisk.settings.flush() }
    actual suspend fun sessionView(key: SessionKey): String = read { it.string(hash("view_", key)) }?.takeIf { it in VIEWS } ?: defaultView()
    actual suspend fun setSessionView(key: SessionKey, view: String) { if (view in VIEWS) change { check(key.origin in origins(it)) { "Connection changed" }; it[hash("view_", key)] = JsonPrimitive(view) } }
    actual suspend fun sessionDeliverAs(key: SessionKey): String = read { it.string(hash("deliver_", key)) }?.takeIf { it in DELIVER_MODES } ?: deliverAs()
    actual suspend fun setSessionDeliverAs(key: SessionKey, mode: String) { if (mode in DELIVER_MODES) change { check(key.origin in origins(it)) { "Connection changed" }; it[hash("deliver_", key)] = JsonPrimitive(mode) } }
    actual fun seenId(key: SessionKey): String = storageHash(key.serialize())
    actual suspend fun seenReplies(origin: String): Map<String, Long> = read { (it["seen_replies_${tag(origin)}"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }?.toMap().orEmpty() }
    actual suspend fun setSeenReplies(origin: String, seen: Map<String, Long>) = change {
        check(origin in origins(it)) { "Connection changed" }
        it["seen_replies_${tag(origin)}"] = JsonObject(seen.mapValues { JsonPrimitive(it.value) })
    }
    internal actual suspend fun setSeenReplies(origin: String, seen: Map<String, Long>, epoch: Long) = StateDisk.lock.withLock {
        val data = readState()
        if (epoch != conversationEpoch(origin) || origin !in origins(data)) return@withLock
        data["seen_replies_${tag(origin)}"] = JsonObject(seen.mapValues { JsonPrimitive(it.value) })
        StateDisk.save(data)
    }
}
