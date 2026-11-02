package br.com.xlinx.habblaud.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

 data class ReadingState(
    val sessions: List<SessionItem> = emptyList(), val selected: SessionItem? = null,
    val entries: List<TerminalEntry> = emptyList(), val truncated: Boolean = false,
    val reduced: Boolean = false, val terminalAllowed: Boolean = false,
    val connection: String = "connecting", val error: String? = null,
    val messagesAllowed: Boolean = false, val messageGuard: Boolean = false
)

class ReadingController(private val api: HabblaudApi, private val store: LocalStateStore, private val scope: CoroutineScope, private val unauthorized: () -> Unit) {
    private val repository = SessionRepository(api)
    private val streams = ConversationRepository(api)
    private val mutable = MutableStateFlow(ReadingState())
    val state: StateFlow<ReadingState> = mutable
    private var stateJob: Job? = null
    private var conversationJob: Job? = null
    private var generation = 0
    private var active = false
    private val conversation = ConversationState()
    val messages = MessageController(api, store, scope, ::canSend, ::changed, unauthorized)
    private fun canSend(key: SessionKey, agentId: String): Boolean {
        val current = mutable.value
        return active && current.connection == "connected" && current.messagesAllowed && current.messageGuard &&
            current.selected?.key == key && current.sessions.any { it.key == key && it.agentId == agentId && it.canMessage }
    }

    private var restore: SessionKey? = null
    private var restoreLoaded = false
    fun start() {
        if (active) return
        active = true
        messages.start()
        stateJob = scope.launch {
            try {
                var retry = 2000L
                while (true) {
                    try { repository.refresh(); break } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (e is ApiException && e.status in listOf(401, 403, 404)) throw e
                        mutable.value = mutable.value.copy(connection = "offline", error = "network")
                        delay(maxOf(retry, retryAfterMillis((e as? ApiException)?.retryAfter))); retry = (retry * 2).coerceAtMost(30000)
                    }
                }
                mutable.value = mutable.value.copy(terminalAllowed = repository.terminalAllowed, messagesAllowed = repository.messagesAllowed, messageGuard = repository.messageGuard)
                if (!restoreLoaded) { restore = store.lastSession()?.takeIf { it.origin == api.origin }; restoreLoaded = true }
                streams.stream("/api/stream").collect { event ->
                    when (event) {
                        is StreamEvent.Connected -> mutable.value = mutable.value.copy(connection = "connected", error = if (mutable.value.error == "network") null else mutable.value.error)
                        is StreamEvent.Failure -> failure(event)
                        is StreamEvent.Data -> if (event.type == "snapshot") {
                            val sessions = repository.snapshot(event.data)
                            val selected = mutable.value.selected
                            if (selected?.agentId != null && sessions.any { it.agentId == selected.agentId && it.key != selected.key }) changed()
                            mutable.value = mutable.value.copy(sessions = sessions, selected = mutable.value.selected?.let { selectedItem -> sessions.find { it.key == selectedItem.key } ?: selectedItem.copy(agentId = null, canMessage = false) })
                            restore?.let { key -> sessions.find { it.key == key }?.let { item -> restore = null; open(item) } }
                        }
                    }
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; if (e is ApiException && e.status == 401) unauthorized() else mutable.value = mutable.value.copy(connection = "offline", error = "network") }
        }
        mutable.value.selected?.let { open(it) }
    }
    fun stop() { active = false; generation++; stateJob?.cancel(); conversationJob?.cancel(); messages.stop(); api.cancelCalls(); mutable.value = mutable.value.copy(connection = "offline") }
    fun close() { generation++; conversationJob?.cancel(); messages.close(); restore = null; scope.launch { store.setLastSession(null) }; mutable.value = mutable.value.copy(selected = null, entries = emptyList(), error = null) }
    private fun changed() { close(); mutable.value = mutable.value.copy(error = "changed") }
    private fun failure(event: StreamEvent.Failure) {
        if (event.status == 401) { unauthorized(); return }
        mutable.value = mutable.value.copy(connection = "offline", error = when (event.status) {
            -1 -> "oversize"
            403 -> "forbidden"
            404 -> "missing"
            else -> "network"
        })
    }
    fun open(item: SessionItem) {
        generation++; val token = generation
        conversationJob?.cancel()
        mutable.value = mutable.value.copy(selected = item, entries = emptyList(), truncated = false, reduced = false, error = null)
        messages.open(item.key)
        if (!active || !repository.terminalAllowed) return
        val path = if (item.agentId != null) "/api/agents/${encode(item.agentId)}/terminal" else "/api/sessions/${encode(item.key.account)}/${encode(item.key.sessionId)}/terminal"
        scope.launch { store.setLastSession(item.key) }
        conversationJob = scope.launch {
            var initialized = false
            var awaitingInit = true
            streams.stream(path).collect { event ->
                if (token != generation) return@collect
                when (event) {
                    is StreamEvent.Connected -> awaitingInit = true
                    is StreamEvent.Failure -> failure(event)
                    is StreamEvent.Data -> try {
                        if (event.type == "init") {
                            if (initialized && !awaitingInit) { changed(); return@collect }
                            val obj = wireJson.parseToJsonElement(event.data).jsonObject
                            conversation.init(obj.objects("entries").mapNotNull(TerminalEntry::parse), obj.flag("truncated"))
                            initialized = true; awaitingInit = false
                        } else if (event.type == "append" && initialized) {
                            conversation.append(wireJson.parseToJsonElement(event.data).jsonArray.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(TerminalEntry::parse) })
                        }
                        mutable.value = mutable.value.copy(entries = conversation.snapshot(), truncated = conversation.truncated, reduced = conversation.reduced)
                    } catch (_: Exception) { mutable.value = mutable.value.copy(error = "oversize"); conversationJob?.cancel() }
                }
            }
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
