package br.com.xlinx.habblaud.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import br.com.xlinx.habblaud.data.AttachmentBody

/** Comando omp pelo menu (shake, modelo, raciocínio): `kind` diz qual, `status` vai de pending a sent/failed/unknown. */
data class CommandResult(val kind: String, val status: String)
data class ComposerState(val key: SessionKey? = null, val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null, val control: MessageOperation? = null, val controlError: String? = null, val loading: Boolean = false, val sending: Boolean = false, val error: String? = null, val progress: String? = null, val unavailable: Set<String> = emptySet(), val commandResult: CommandResult? = null)

class MessageController(
    private val request: suspend (String, String?) -> String,
    private val store: MessageStore,
    private val scope: CoroutineScope,
    private val eligible: (SessionKey, String) -> Boolean,
    private val changed: () -> Unit,
    private val unauthorized: () -> Unit,
    private val attachments: Attachments? = null,
    private val upload: (suspend (String, String, AttachmentBody) -> String)? = null,
    private val inUse: suspend (String) -> Boolean = { false },
    private val attachmentBody: ((Attachment, (Long) -> Unit) -> AttachmentBody)? = null,
) {
    constructor(api: HabblaudApi, store: LocalStateStore, scope: CoroutineScope, eligible: (SessionKey, String) -> Boolean, changed: () -> Unit, unauthorized: () -> Unit) :
        this({ path, body -> api.request(path, body?.let { wireJson.parseToJsonElement(it).jsonObject }) }, store, scope, eligible, changed, unauthorized, api.attachments, api::upload, store::usesAttachment)

    private val mutable = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = mutable
    /** Modo de entrega no omp: aside (acrescenta agora), followUp (enfileira) ou steer (muda a direção). */
    var deliverAs = "aside"
    private val drafts = mutableMapOf<SessionKey, MessageDraft>()
    private val sending = mutableSetOf<SessionKey>()
    private var poll: Job? = null
    private var controlPoll: Job? = null
    private val controlling = mutableSetOf<SessionKey>()
    private var visible = false
    private var generation = 0
    private val uploadJobs = mutableMapOf<SessionKey, Job>()
    private val commandJobs = mutableSetOf<Job>()
    private fun trackCommand(job: Job) {
        commandJobs.add(job)
        job.invokeOnCompletion { commandJobs.remove(job) }
    }
    private val activeUploads = mutableMapOf<String, Int>()
    private val pendingRelease = mutableSetOf<Attachment>()
    private val transientUris = mutableSetOf<String>()

    private suspend fun releaseUnused(items: List<Attachment>) {
        items.forEach { item ->
            if ((activeUploads[item.uri] ?: 0) > 0) pendingRelease.add(item)
            else if (!inUse(item.uri)) withContext(Dispatchers.IO) { attachments?.release(item) }
        }
    }

    fun start() { if (visible) return; visible = true; state.value.key?.let { open(it) } }
    fun stop() {
        visible = false
        poll?.cancel(); controlPoll?.cancel(); poll = null; controlPoll = null
        uploadJobs.values.toList().forEach { it.cancel() }
        commandJobs.toList().forEach { it.cancel() }
        if (state.value.commandResult?.status == "pending") {
            mutable.value = state.value.copy(commandResult = state.value.commandResult?.copy(status = "unknown"))
        }
    }
    fun open(key: SessionKey) {
        generation++
        val token = generation
        poll?.cancel()
        controlPoll?.cancel()
        mutable.value = ComposerState(key = key, draft = drafts[key] ?: MessageDraft(), loading = true, sending = key in sending)
        scope.launch {
            try {
                var cleared = emptyList<Attachment>()
                val saved = store.updateMessage(key) { local ->
                    val latest = drafts[key]?.takeIf { it.revision > local.draft.revision } ?: local.draft
                    val op = local.operation
                    val draft = if (op?.id != null && latest.revision == op.revision) {
                        cleared = latest.attachments
                        latest.copy(text = "", attachments = emptyList(), revision = latest.revision + 1)
                    } else latest
                    local.copy(draft = draft)
                }
                releaseUnused(cleared)
                val draft = saved.draft.also { drafts[key] = it }
                val unavailable = withContext(Dispatchers.IO) { draft.attachments.filter { attachments != null && ((!attachments.persisted(it) && it.uri !in transientUris) || !attachments.available(it)) }.map { it.uri }.toSet() }
                if (token != generation) return@launch
                mutable.value = mutable.value.copy(draft = draft, operation = saved.operation, control = saved.control, loading = false, unavailable = unavailable)
                if (visible) { poll(key, saved.operation); pollControl(key, saved.control) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation) mutable.value = mutable.value.copy(loading = false, error = "storage")
            }
        }
    }
    fun dismissConfirmation() { mutable.value = mutable.value.copy(error = null) }
    fun close() { generation++; poll?.cancel(); controlPoll?.cancel(); mutable.value = ComposerState() }
    fun edit(text: String = state.value.draft.text, files: List<Attachment> = state.value.draft.attachments) {
        val key = state.value.key ?: return
        if (state.value.loading || text.length > 20000) return
        val draft = MessageDraft(text, state.value.draft.revision + 1, files)
        drafts[key] = draft
        mutable.value = mutable.value.copy(draft = draft, error = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) { store.updateMessage(key) { if (it.draft.revision < draft.revision) it.copy(draft = draft) else it } } }
            catch (e: Exception) { if (e is CancellationException) throw e; if (state.value.key == key) mutable.value = mutable.value.copy(error = "storage") }
        }
    }
    fun select(uris: List<String>) {
        if (uris.isEmpty()) return
        val manager = attachments ?: return
        selectPrepared { before -> manager.select(uris, before) }
    }

    internal fun selectPrepared(prepare: suspend (List<Attachment>) -> List<Attachment>) {
        val key = state.value.key ?: return
        scope.launch {
            try {
                val ready = state.first { it.key != key || !it.loading }
                if (ready.key != key || ready.error == "storage") return@launch
                val before = ready.draft.attachments
                val selected = prepare(before)
                val added = selected.filterNot { item -> before.any { it.uri == item.uri } }
                val currentState = state.first { it.key != key || !it.loading }
                if (currentState.key == key && currentState.error != "storage") {
                    val current = state.value.draft.attachments
                    if (current.size + added.count { item -> current.none { it.uri == item.uri } } > MAX_ATTACHMENTS) {
                        releaseUnused(added)
                        mutable.value = state.value.copy(error = "attachment")
                    } else {
                        transientUris.addAll(added.map { it.uri })
                        edit(files = current + added.filter { item -> current.none { it.uri == item.uri } })
                    }
                } else releaseUnused(added)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (state.value.key == key) mutable.value = state.value.copy(error = "attachment")
            }
        }
    }
    fun remove(item: Attachment) {
        val key = state.value.key ?: return
        val remaining = state.value.draft.attachments.filterNot { it == item }
        edit(files = remaining)
        mutable.value = mutable.value.copy(unavailable = state.value.unavailable - item.uri)
        val draft = state.value.draft
        scope.launch {
            withContext(NonCancellable) {
                store.updateMessage(key) { if (it.draft.revision < draft.revision) it.copy(draft = draft) else it }
                releaseUnused(listOf(item))
            }
        }
    }
    fun send(agentId: String, confirmed: Boolean = false) {
        val current = state.value
        val key = current.key ?: return
        val draft = current.draft
        if (!visible || current.loading || key in sending || (draft.text.isBlank() && draft.attachments.isEmpty()) || draft.text.length > 20000 || current.unavailable.isNotEmpty() || !eligible(key, agentId)) return
        if (current.operation?.status == "unknown" && !confirmed) { mutable.value = current.copy(error = "confirm"); return }
        sending.add(key)
        mutable.value = current.copy(sending = true, error = null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var dispatched = false
            var marked = false
            var previous = current.operation
            var preparing = draft.attachments.isNotEmpty()
            draft.attachments.forEach { activeUploads[it.uri] = (activeUploads[it.uri] ?: 0) + 1 }
            try {
                val ids = mutableListOf<String>()
                for ((index, item) in draft.attachments.withIndex()) {
                    if (!visible || !eligible(key, agentId)) throw CancellationException("Recipient changed")
                    val makeBody = attachmentBody ?: requireNotNull(attachments)::body
                    val sender = requireNotNull(upload)
                    if (state.value.key == key) mutable.value = state.value.copy(progress = "${index + 1}/${draft.attachments.size}")
                    var lastUpdate = 0L
                    val result = wireJson.parseToJsonElement(sender(item.name, item.mime, makeBody(item) { bytes ->
                        if (bytes - lastUpdate >= 262144 || bytes == item.size) {
                            lastUpdate = bytes
                            scope.launch { if (state.value.key == key) mutable.value = state.value.copy(progress = "${index + 1}/${draft.attachments.size} · $bytes bytes") }
                        }
                    })).jsonObject
                    ids.add(requireNotNull(result.string("id")))
                }
                preparing = false
                if (!visible || !eligible(key, agentId)) return@launch
                store.updateMessage(key) { local ->
                    val latest = if (local.draft.revision < draft.revision) draft else local.draft
                    previous = local.operation
                    local.copy(draft = latest, operation = MessageOperation(status = "unknown", revision = draft.revision))
                }
                marked = true
                if (!visible || !eligible(key, agentId)) {
                    store.updateMessage(key) { it.copy(operation = previous) }
                    return@launch
                }
                val body = buildJsonObject {
                    put("agentId", agentId); put("text", draft.text); put("expectedSessionId", key.sessionId)
                    if (key.provider == "omp") put("deliverAs", deliverAs)
                    if (ids.isNotEmpty()) put("attachments", buildJsonArray { ids.forEach { add(it) } })
                }
                dispatched = true
                val response = wireJson.parseToJsonElement(request("/api/messages", body.toString())).jsonObject
                val id = requireNotNull(response.string("id"))
                val status = requireNotNull(response.string("status"))
                val op = MessageOperation(id, status, draft.revision)
                val saved = withContext(NonCancellable) {
                    store.updateMessage(key) { it.copy(operation = op) }
                    store.updateMessage(key) { local ->
                        val latest = drafts[key]?.takeIf { it.revision > local.draft.revision } ?: local.draft
                        val next = if (latest.revision == draft.revision) latest.copy(text = "", attachments = emptyList(), revision = latest.revision + 1) else latest
                        local.copy(draft = next)
                    }
                }
                drafts[key] = saved.draft
                if (saved.draft.attachments.isEmpty()) releaseUnused(draft.attachments)
                if (state.value.key == key) mutable.value = state.value.copy(draft = saved.draft, operation = op)
                if (visible && state.value.key == key) poll(key, op)
            } catch (e: Exception) {
                val conflict = e is ApiException && e.code == "session_changed"
                val definite = e is ApiException && e.status in 400..499
                withContext(NonCancellable) {
                    if (marked && (!dispatched || definite)) runCatching { store.updateMessage(key) { it.copy(operation = previous) } }
                }
                if (state.value.key == key) mutable.value = state.value.copy(operation = if (dispatched && !definite) MessageOperation(status = "unknown", revision = draft.revision) else previous, error = if (dispatched && !definite) "unknown" else if (preparing) "attachment" else if (e is ApiException && e.attachmentsExpired) "attachments_expired" else "send")
                if (conflict) changed() else if (e is ApiException && e.status == 401) unauthorized()
                if (e is CancellationException) throw e
            } finally {
                draft.attachments.forEach { item ->
                    val count = (activeUploads[item.uri] ?: 1) - 1
                    if (count == 0) activeUploads.remove(item.uri) else activeUploads[item.uri] = count
                }
                withContext(NonCancellable) { val pending = pendingRelease.toList(); pendingRelease.removeAll(pending.toSet()); releaseUnused(pending) }
                uploadJobs.remove(key)
                sending.remove(key)
                if (state.value.key == key) mutable.value = state.value.copy(sending = false, progress = null)
            }
        }
        uploadJobs[key] = job
        job.start()
    }
    fun interrupt(agentId: String, confirmed: Boolean = false) {
        val current = state.value
        val key = current.key ?: return
        if (key.provider != "omp" || !visible || current.loading || key in controlling || !eligible(key, agentId) || current.control?.status in setOf("queued", "sent")) return
        if (current.control?.status == "unknown" && !confirmed) { mutable.value = current.copy(controlError = "confirm"); return }
        controlling.add(key)
        scope.launch {
            var dispatched = false
            var marked = false
            val previous = current.control
            try {
                store.updateMessage(key) { it.copy(control = MessageOperation()) }
                marked = true
                if (!visible || !eligible(key, agentId)) {
                    store.updateMessage(key) { it.copy(control = previous) }
                    return@launch
                }
                if (state.value.key == key) mutable.value = state.value.copy(control = MessageOperation(), controlError = null)
                val body = buildJsonObject { put("agentId", agentId); put("stop", true); put("expectedSessionId", key.sessionId) }
                dispatched = true
                val response = wireJson.parseToJsonElement(request("/api/messages/control", body.toString())).jsonObject
                val op = MessageOperation(requireNotNull(response.string("id")), requireNotNull(response.string("status")))
                withContext(NonCancellable) { store.updateMessage(key) { it.copy(control = op) } }
                if (state.value.key == key) mutable.value = state.value.copy(control = op)
                if (visible && state.value.key == key) pollControl(key, op)
            } catch (e: Exception) {
                val definite = e is ApiException && e.status in 400..499
                if (marked && (!dispatched || definite)) withContext(NonCancellable) { store.updateMessage(key) { it.copy(control = previous) } }
                if (state.value.key == key) mutable.value = state.value.copy(control = if (dispatched && !definite) MessageOperation() else previous, controlError = if (dispatched && !definite) "unknown" else "failed")
                if (e is ApiException && e.code == "session_changed") changed() else if (e is ApiException && e.status == 401) unauthorized()
                if (e is CancellationException) throw e
            } finally { controlling.remove(key) }
        }.also(::trackCommand)
    }
    fun dismissControlConfirmation() { mutable.value = mutable.value.copy(controlError = null) }

    /** /shake do omp (elide e thinking): libera contexto trocando blocos antigos por referências. */
    fun shake(agentId: String) = ompControl(agentId, "shake") { put("command", "shake") }
    /** Troca o modelo da sessão omp (um dos `models` que ela aceita). */
    fun setModel(agentId: String, model: String) = ompControl(agentId, "model") { put("model", model) }
    /** Troca o nível de raciocínio da sessão omp. */
    fun setThinking(agentId: String, level: String) = ompControl(agentId, "thinking") { put("thinking", level) }

    /** Pedido de controle à sessão omp, acompanhado até ela confirmar ou recusar. Sem retry automático. */
    private fun ompControl(agentId: String, kind: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val key = state.value.key ?: return
        if (key.provider != "omp" || !visible || !eligible(key, agentId)) return
        mutable.value = state.value.copy(commandResult = CommandResult(kind, "pending"))
        scope.launch {
            val result = try {
                val response = wireJson.parseToJsonElement(request("/api/messages/control", buildJsonObject { put("agentId", agentId); fields(); put("expectedSessionId", key.sessionId) }.toString())).jsonObject
                // O servidor só enfileira: acompanha o pedido até a sessão confirmar (delivered) ou recusar (failed).
                val id = requireNotNull(response.string("id"))
                var status = response.string("status")
                var tries = 0
                while (status !in setOf("delivered", "failed") && tries++ < 15 && visible && state.value.key == key) {
                    delay(2000)
                    status = try {
                        wireJson.parseToJsonElement(request("/api/messages/${id.encodeURLPathPart()}", null)).jsonObject.string("status")
                    } catch (e: ApiException) { if (e.status == 404) "unknown" else throw e }
                    if (status == "unknown") break
                }
                when (status) { "delivered" -> "sent"; "failed" -> "failed"; else -> "unknown" }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code == "session_changed") changed() else if (e is ApiException && e.status == 401) unauthorized()
                if (e is ApiException && e.status in 400..499) "failed" else "unknown"
            }
            if (state.value.key == key) mutable.value = state.value.copy(commandResult = CommandResult(kind, result))
        }.also(::trackCommand)
    }
    fun dismissCommandResult() { mutable.value = mutable.value.copy(commandResult = null) }
    private fun pollControl(key: SessionKey, operation: MessageOperation?) {
        controlPoll?.cancel()
        if (!visible || operation?.id == null || operation.status in setOf("delivered", "failed")) return
        controlPoll = scope.launch {
            var op: MessageOperation = operation
            while (visible && state.value.key == key) {
                delay(2000)
                try {
                    val id = op.id ?: break
                    val response = wireJson.parseToJsonElement(request("/api/messages/${id.encodeURLPathPart()}", null)).jsonObject
                    op = op.copy(status = requireNotNull(response.string("status")), error = response.string("error"))
                    store.updateMessage(key) { if (it.control?.id == id) it.copy(control = op) else it }
                    if (state.value.key == key) mutable.value = state.value.copy(control = op, controlError = response.string("error"))
                    if (op.status in setOf("delivered", "failed", "unknown")) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.status == 401) { unauthorized(); break }
                    if (e is ApiException && e.status == 404) {
                        op = op.copy(status = "unknown")
                        store.updateMessage(key) { if (it.control?.id == op.id) it.copy(control = op) else it }
                        if (state.value.key == key) mutable.value = state.value.copy(control = op)
                        break
                    }
                }
            }
        }
    }
    private fun poll(key: SessionKey, operation: MessageOperation?) {
        poll?.cancel()
        if (!visible || operation?.id == null || operation.status in setOf("delivered", "failed")) return
        poll = scope.launch {
            var op: MessageOperation = operation
            while (visible && state.value.key == key) {
                delay(2000)
                try {
                    val id = op.id ?: break
                    val encoded = id.encodeURLPathPart()
                    val response = wireJson.parseToJsonElement(request("/api/messages/$encoded", null)).jsonObject
                    op = op.copy(status = requireNotNull(response.string("status")))
                    store.updateMessage(key) { if (it.operation?.id == id) it.copy(operation = op) else it }
                    if (state.value.key == key) mutable.value = state.value.copy(operation = op)
                    if (op.status in setOf("delivered", "failed", "unknown")) break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ApiException && e.status == 401) { unauthorized(); break }
                    if (e is ApiException && e.status == 404) {
                        op = op.copy(status = "unknown")
                        store.updateMessage(key) { if (it.operation?.id == op.id) it.copy(operation = op) else it }
                        if (state.value.key == key) mutable.value = state.value.copy(operation = op)
                        break
                    }
                }
            }
        }
    }
}
