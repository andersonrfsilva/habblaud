package br.com.xlinx.habblaud.data

import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.CommonCrypto.CC_SHA256

@OptIn(ExperimentalForeignApi::class)
internal fun storageHash(value: String): String {
    val bytes = value.encodeToByteArray()
    val digest = UByteArray(32)
    bytes.usePinned { input -> digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0)) } }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
internal val privateDirectory: String get() {
    val root = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
    val path = "$root/Zaplaud"
    check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, mapOf(NSFileProtectionKey to NSFileProtectionComplete), null)) { "Could not create private storage" }
    return path
}
internal fun protectedWrite(path: String, text: String) {
    val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
    check(data.writeToFile(path, NSDataWritingAtomic or NSDataWritingFileProtectionComplete, null)) { "Could not save private storage" }
}
internal fun privateRead(path: String): String? {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
    return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
        ?: error("Could not read private storage: $path")
}
internal fun removeOrphanAttachments(referencedUris: Set<String>) {
    val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(privateDirectory, null)
        ?: error("Could not list private attachments")
    files.filterIsInstance<String>().filter { it.startsWith("attachment-") }.forEach { name ->
        val path = "$privateDirectory/$name"
        if (NSURL.fileURLWithPath(path).absoluteString !in referencedUris) {
            check(NSFileManager.defaultManager.removeItemAtPath(path, null)) { "Could not remove orphan attachment" }
        }
    }
}
internal fun initializeAccountStorage() { StateDisk.ensureInitialized() }
private object StateDisk {
    val lock = Mutex()
    private val epochLock = NSRecursiveLock()
    fun <T> synchronizedEpoch(block: () -> T): T {
        epochLock.lock()
        try { return block() } finally { epochLock.unlock() }
    }
    var epoch = 0L
    var sequence = 0L
    var sessionWritten = 0L
    val written = mutableMapOf<SessionKey, Long>()
    val settings = NSUserDefaults.standardUserDefaults
    val theme = MutableStateFlow(settings.stringForKey("theme") ?: "system")
    val summarize = MutableStateFlow(settings.boolForKey("summarize_replies"))
    fun read(): MutableMap<String, JsonElement> = privateRead("$privateDirectory/local_state.json")?.let { wireJson.parseToJsonElement(it).jsonObject.toMutableMap() } ?: mutableMapOf()
    fun save(data: Map<String, JsonElement>) = protectedWrite("$privateDirectory/local_state.json", JsonObject(data).toString())
    init {
        val referenced = read().filterKeys { it.startsWith("message_") }.values
            .flatMap { messageDecode(it).draft.attachments }.mapTo(mutableSetOf()) { it.uri }
        removeOrphanAttachments(referenced)
    }
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
    private suspend fun <T> read(block: (MutableMap<String, JsonElement>) -> T): T = StateDisk.lock.withLock { block(StateDisk.read()) }
    private suspend fun change(block: (MutableMap<String, JsonElement>) -> Unit) = StateDisk.lock.withLock { val data = StateDisk.read(); block(data); StateDisk.save(data) }
    private fun hash(prefix: String, key: SessionKey) = prefix + storageHash(key.serialize())
    actual suspend fun usesAttachment(uri: String): Boolean = read { data -> data.filterKeys { it.startsWith("message_") }.values.any { messageDecode(it).draft.attachments.any { a -> a.uri == uri } } }
    actual override suspend fun message(key: SessionKey): MessageLocalState = read { messageDecode(it[hash("message_", key)]) }
    actual override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        var result = MessageLocalState()
        change { check(it.string("origin") == key.origin) { "Connection changed" }; result = update(messageDecode(it[hash("message_", key)])); it[hash("message_", key)] = messageEncode(result) }
        return result
    }
    actual suspend fun lastSession(): SessionKey? = read { it.string("last_session")?.let { value -> runCatching { SessionKey.deserialize(value) }.getOrNull() } }
    actual suspend fun setLastSession(key: SessionKey?) = change { if (key == null) it.remove("last_session") else it["last_session"] = JsonPrimitive(key.serialize()) }
    internal actual suspend fun setLastSession(key: SessionKey?, write: ConversationWrite) = StateDisk.lock.withLock {
        if (write.epoch != StateDisk.synchronizedEpoch { StateDisk.epoch } || write.sequence <= StateDisk.sessionWritten) return@withLock
        val data = StateDisk.read(); if (key == null) data.remove("last_session") else data["last_session"] = JsonPrimitive(key.serialize()); StateDisk.save(data); StateDisk.sessionWritten = write.sequence
    }
    internal actual fun conversationEpoch(): Long = StateDisk.synchronizedEpoch { StateDisk.epoch }
    internal actual fun conversationWrite(epoch: Long): ConversationWrite = StateDisk.synchronizedEpoch { ConversationWrite(epoch, ++StateDisk.sequence) }
    private fun conversationPath(key: SessionKey, data: Map<String, JsonElement>) = "$privateDirectory/conversation-${data.string("conversation_generation")}-${hash("", key)}.json"
    actual suspend fun conversation(key: SessionKey): CachedConversation? = read { if (it.string("origin") != key.origin) null else privateRead(conversationPath(key, it))?.let { text -> runCatching { decodeConversation(text) }.getOrNull() } }
    internal actual var beforeConversationWrite: suspend () -> Unit = {}
    internal actual var afterConversationWrite: () -> Unit = {}
    internal actual suspend fun setConversation(key: SessionKey, entries: List<TerminalEntry>, truncated: Boolean, write: ConversationWrite, summaries: Map<String, String>) {
        try { beforeConversationWrite(); StateDisk.lock.withLock {
            val data = StateDisk.read()
            if (write.epoch != StateDisk.synchronizedEpoch { StateDisk.epoch } || data.string("origin") != key.origin || write.sequence <= (StateDisk.written[key] ?: 0)) return@withLock
            protectedWrite(conversationPath(key, data), encodeConversation(entries, truncated, summaries)); StateDisk.written[key] = write.sequence
        } } finally { afterConversationWrite() }
    }
    actual suspend fun retryUntil(origin: String): Long = (StateDisk.settings.objectForKey("retry_" + storageHash(origin)) as? NSNumber)?.longLongValue ?: 0
    actual suspend fun setRetryUntil(origin: String, until: Long) { StateDisk.settings.setObject(NSNumber(longLong = until), "retry_" + storageHash(origin)) }
    actual suspend fun connection(): ConnectionSettings? = read { it.string("origin")?.let(::ConnectionSettings) }
    private fun invalidate(data: MutableMap<String, JsonElement>, origin: String?) {
        data.clear(); data["conversation_generation"] = JsonPrimitive(NSUUID().UUIDString); origin?.let { data["origin"] = JsonPrimitive(it) }
        StateDisk.save(data); StateDisk.synchronizedEpoch { StateDisk.epoch++ }; StateDisk.written.clear()
        val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(privateDirectory, null) ?: error("Could not list private storage")
        files.filterIsInstance<String>().filter { it.startsWith("conversation-") || it.startsWith("attachment-") }.forEach { check(NSFileManager.defaultManager.removeItemAtPath("$privateDirectory/$it", null)) { "Could not remove old account files" } }
    }
    actual suspend fun setConnection(origin: String) = withContext(NonCancellable) { StateDisk.lock.withLock { val data = StateDisk.read(); if (data.string("origin") != origin) invalidate(data, origin) } }
    actual suspend fun clear() = withContext(NonCancellable) { StateDisk.lock.withLock { invalidate(StateDisk.read(), null) } }
    actual suspend fun notificationsEnabled(): Boolean = StateDisk.settings.objectForKey("notifications") != null && StateDisk.settings.boolForKey("notifications")
    actual suspend fun setNotificationsEnabled(enabled: Boolean) { StateDisk.settings.setBool(enabled, "notifications") }
    actual suspend fun deliverAs(): String = StateDisk.settings.stringForKey("deliver_as")?.takeIf { it in DELIVER_MODES } ?: "aside"
    actual suspend fun setDeliverAs(mode: String) { if (mode in DELIVER_MODES) StateDisk.settings.setObject(mode, "deliver_as") }
    actual suspend fun defaultView(): String = StateDisk.settings.stringForKey("view")?.takeIf { it in VIEWS } ?: "all"
    actual suspend fun setDefaultView(view: String) { if (view in VIEWS) StateDisk.settings.setObject(view, "view") }
    actual val theme: Flow<String> = StateDisk.theme
    actual suspend fun setTheme(theme: String) { if (theme in THEMES) { StateDisk.settings.setObject(theme, "theme"); StateDisk.theme.value = theme } }
    actual val summarize: Flow<Boolean> = StateDisk.summarize
    actual suspend fun setSummarize(on: Boolean) { StateDisk.settings.setBool(on, "summarize_replies"); StateDisk.summarize.value = on }
    actual suspend fun notifyEvents(): Pair<Boolean, Boolean> = (StateDisk.settings.objectForKey("notify_permission") == null || StateDisk.settings.boolForKey("notify_permission")) to (StateDisk.settings.objectForKey("notify_done") == null || StateDisk.settings.boolForKey("notify_done"))
    actual suspend fun setNotifyEvents(permission: Boolean, done: Boolean) { StateDisk.settings.setBool(permission, "notify_permission"); StateDisk.settings.setBool(done, "notify_done") }
    actual suspend fun sessionView(key: SessionKey): String = read { it.string(hash("view_", key)) }?.takeIf { it in VIEWS } ?: defaultView()
    actual suspend fun setSessionView(key: SessionKey, view: String) { if (view in VIEWS) change { check(it.string("origin") == key.origin); it[hash("view_", key)] = JsonPrimitive(view) } }
    actual suspend fun sessionDeliverAs(key: SessionKey): String = read { it.string(hash("deliver_", key)) }?.takeIf { it in DELIVER_MODES } ?: deliverAs()
    actual suspend fun setSessionDeliverAs(key: SessionKey, mode: String) { if (mode in DELIVER_MODES) change { check(it.string("origin") == key.origin); it[hash("deliver_", key)] = JsonPrimitive(mode) } }
    actual fun seenId(key: SessionKey): String = hash("", key)
    actual suspend fun seenReplies(): Map<String, Long> = read { (it["seen_replies"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }?.toMap().orEmpty() }
    actual suspend fun setSeenReplies(seen: Map<String, Long>) = change { it["seen_replies"] = JsonObject(seen.mapValues { JsonPrimitive(it.value) }) }
    internal actual suspend fun setSeenReplies(seen: Map<String, Long>, epoch: Long) = StateDisk.lock.withLock {
        if (StateDisk.synchronizedEpoch { epoch != StateDisk.epoch }) return@withLock
        val data = StateDisk.read()
        data["seen_replies"] = JsonObject(seen.mapValues { JsonPrimitive(it.value) })
        StateDisk.save(data)
    }
}
