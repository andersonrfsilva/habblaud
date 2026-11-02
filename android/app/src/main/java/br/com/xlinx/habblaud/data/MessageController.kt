package br.com.xlinx.habblaud.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

data class ComposerState(val key: SessionKey? = null, val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null, val loading: Boolean = false, val sending: Boolean = false, val error: String? = null)

class MessageController(
    private val request: suspend (String, String?) -> String,
    private val store: MessageStore,
    private val scope: CoroutineScope,
    private val eligible: (SessionKey, String) -> Boolean,
    private val changed: () -> Unit,
    private val unauthorized: () -> Unit,
) {
    constructor(api: HabblaudApi, store: LocalStateStore, scope: CoroutineScope, eligible: (SessionKey, String) -> Boolean, changed: () -> Unit, unauthorized: () -> Unit) :
        this({ path, body -> api.request(path, body?.let { org.json.JSONObject(it) }) }, store, scope, eligible, changed, unauthorized)

    private val mutable = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = mutable
    private val drafts = mutableMapOf<SessionKey, MessageDraft>()
    private val sending = mutableSetOf<SessionKey>()
    private var poll: Job? = null
    private var visible = false
    private var generation = 0

    fun start() { visible = true; state.value.key?.let { open(it) } }
    fun stop() { visible = false; poll?.cancel(); poll = null }
    fun open(key: SessionKey) {
        generation++
        val token = generation
        poll?.cancel()
        mutable.value = ComposerState(key = key, draft = drafts[key] ?: MessageDraft(), loading = true, sending = key in sending)
        scope.launch {
            try {
                val saved = store.updateMessage(key) { local ->
                    val latest = drafts[key]?.takeIf { it.revision > local.draft.revision } ?: local.draft
                    val op = local.operation
                    val draft = if (op?.id != null && latest.revision == op.revision) latest.copy(text = "", revision = latest.revision + 1) else latest
                    local.copy(draft = draft)
                }
                val draft = saved.draft.also { drafts[key] = it }
                if (token != generation) return@launch
                mutable.value = mutable.value.copy(draft = draft, operation = saved.operation, loading = false)
                if (visible) poll(key, saved.operation)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (token == generation) mutable.value = mutable.value.copy(loading = false, error = "storage")
            }
        }
    }
    fun dismissConfirmation() { mutable.value = mutable.value.copy(error = null) }
    fun close() { generation++; poll?.cancel(); mutable.value = ComposerState() }
    fun edit(text: String = state.value.draft.text, mode: String = state.value.draft.mode) {
        val key = state.value.key ?: return
        if (state.value.loading || text.length > 20000 || mode !in setOf("aside", "followUp", "steer")) return
        val draft = MessageDraft(text, mode, state.value.draft.revision + 1)
        drafts[key] = draft
        mutable.value = mutable.value.copy(draft = draft, error = null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable) { store.updateMessage(key) { if (it.draft.revision < draft.revision) it.copy(draft = draft) else it } } }
            catch (e: Exception) { if (e is CancellationException) throw e; if (state.value.key == key) mutable.value = mutable.value.copy(error = "storage") }
        }
    }
    fun send(agentId: String, confirmed: Boolean = false) {
        val current = state.value
        val key = current.key ?: return
        val draft = current.draft
        if (!visible || current.loading || key in sending || draft.text.isBlank() || draft.text.length > 20000 || !eligible(key, agentId)) return
        if (current.operation?.status == "unknown" && !confirmed) { mutable.value = current.copy(error = "confirm"); return }
        sending.add(key)
        mutable.value = current.copy(sending = true, error = null)
        scope.launch {
            var dispatched = false
            try {
                store.updateMessage(key) { local ->
                    val latest = if (local.draft.revision < draft.revision) draft else local.draft
                    local.copy(draft = latest, operation = MessageOperation(status = "unknown", revision = draft.revision))
                }
                if (!visible || !eligible(key, agentId)) {
                    store.updateMessage(key) { it.copy(operation = null) }
                    return@launch
                }
                val body = buildJsonObject {
                    put("agentId", agentId); put("text", draft.text); put("expectedSessionId", key.sessionId)
                    if (key.provider == "omp") put("deliverAs", draft.mode)
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
                        val next = if (latest.revision == draft.revision) latest.copy(text = "", revision = latest.revision + 1) else latest
                        local.copy(draft = next)
                    }
                }
                drafts[key] = saved.draft
                if (state.value.key == key) mutable.value = state.value.copy(draft = saved.draft, operation = op)
                if (visible && state.value.key == key) poll(key, op)
            } catch (e: Exception) {
                val conflict = e is ApiException && e.code == "session_changed"
                val definite = e is ApiException && e.status in 400..499
                withContext(NonCancellable) {
                    if (!dispatched || definite) runCatching { store.updateMessage(key) { it.copy(operation = null) } }
                }
                if (state.value.key == key) mutable.value = state.value.copy(operation = if (dispatched && !definite) MessageOperation(status = "unknown", revision = draft.revision) else null, error = if (dispatched && !definite) "unknown" else "send")
                if (conflict) changed() else if (e is ApiException && e.status == 401) unauthorized()
                if (e is CancellationException) throw e
            } finally {
                sending.remove(key)
                if (state.value.key == key) mutable.value = state.value.copy(sending = false)
            }
        }
    }
    private fun poll(key: SessionKey, operation: MessageOperation?) {
        poll?.cancel()
        if (!visible || operation?.id == null || operation.status in setOf("delivered", "failed", "unknown")) return
        poll = scope.launch {
            var op: MessageOperation = operation
            while (visible && state.value.key == key) {
                delay(2000)
                try {
                    val id = op.id ?: break
                    val encoded = URLEncoder.encode(id, "UTF-8").replace("+", "%20")
                    val response = wireJson.parseToJsonElement(request("/api/messages/$encoded", null)).jsonObject
                    op = op.copy(status = requireNotNull(response.string("status")))
                    store.updateMessage(key) { if (it.operation?.id == id) it.copy(operation = op) else it }
                    if (state.value.key == key) mutable.value = state.value.copy(operation = op)
                    if (op.status in setOf("delivered", "failed")) break
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
