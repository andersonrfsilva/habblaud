package br.com.xlinx.habblaud.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/** Gravações do cache de conversas: duram o processo, não a tela (o descarte cancela o escopo da tela). */
private val persistScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

 data class ReadingState(
    val sessions: List<SessionItem> = emptyList(), val selected: SessionItem? = null,
    val entries: List<TerminalEntry> = emptyList(), val truncated: Boolean = false,
    val reduced: Boolean = false, val terminalAllowed: Boolean = false,
    val connection: String = "connecting", val error: String? = null,
    val messagesAllowed: Boolean = false, val messageGuard: Boolean = false, val permissionsAllowed: Boolean = false,
    val readingError: String? = null,
    /** Respostas não lidas por sessão (só as com alguma), para o balão da tela principal. */
    val unread: Map<SessionKey, Int> = emptyMap(),
)

internal fun ReadingState.streamFailure(status: Int?, conversation: Boolean): ReadingState {
    val reason = when (status) { -1 -> "oversize"; 403 -> "forbidden"; 404 -> "missing"; else -> "network" }
    return if (conversation) copy(readingError = reason) else copy(connection = "offline", error = reason)
}

internal fun ReadingState.conversationConnected(): ReadingState =
    copy(readingError = if (readingError == "network") null else readingError)

internal fun snapshotMatchesSession(snapshot: String, key: SessionKey, agentId: String): Boolean {
    val agents = wireJson.parseToJsonElement(snapshot).jsonObject.objects("agents")
    return agents.any {
        it.string("id") == agentId && it.string("kind") == "main" &&
            it.string("account") == key.account && (it.string("provider") ?: "claude") == key.provider &&
            it.string("sessionId") == key.sessionId
    }
}

/**
 * Decide, a cada `init` da conversa de um agente aberto, se a tela continua na sessão. Vale igual para o primeiro
 * `init` e para os repetidos (reconexão, /shake, arquivo reescrito): só sai se o servidor mostra o agente noutra sessão.
 */
internal fun initKeepsSession(snapshotMatches: Boolean, sessions: List<SessionItem>, item: SessionItem): Boolean =
    snapshotMatches && sessions.none { it.agentId == item.agentId && it.key != item.key }

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
    private val conversationEpoch = store.conversationEpoch()
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
                mutable.value = mutable.value.copy(terminalAllowed = repository.terminalAllowed, messagesAllowed = repository.messagesAllowed, messageGuard = repository.messageGuard, permissionsAllowed = repository.permissionsAllowed)
                if (!restoreLoaded) { restore = store.lastSession()?.takeIf { it.origin == api.origin }; restoreLoaded = true }
                if (seen == null) seen = store.seenReplies()
                streams.stream("/api/stream").collect { event ->
                    when (event) {
                        is StreamEvent.Connected -> mutable.value = mutable.value.copy(connection = "connected", error = if (mutable.value.error == "network") null else mutable.value.error)
                        is StreamEvent.Failure -> failure(event, conversation = false)
                        is StreamEvent.Data -> if (event.type == "snapshot") {
                            val sessions = repository.snapshot(event.data)
                            val selected = mutable.value.selected
                            if (selected?.agentId != null && sessions.any { it.agentId == selected.agentId && it.key != selected.key }) changed()
                            // A conversa de um subagente não está na lista: segue aberta como está até o leitor fechar.
                            mutable.value = mutable.value.copy(sessions = sessions, selected = mutable.value.selected?.let { selectedItem ->
                                if (selectedItem.sub) sessions.firstNotNullOfOrNull { s -> s.subagents.find { it.id == selectedItem.agentId } }
                                    .let { live -> selectedItem.copy(status = if (live != null) "working" else "done", activity = live?.activity ?: selectedItem.activity) }
                                else sessions.find { it.key == selectedItem.key } ?: selectedItem.copy(agentId = null, canMessage = false)
                            })
                            restore?.let { key -> sessions.find { it.key == key }?.let { item -> restore = null; open(item) } }
                            refreshUnread()
                        }
                    }
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; if (e is ApiException && e.status == 401) unauthorized() else mutable.value = mutable.value.copy(connection = "offline", error = "network") }
        }
        mutable.value.selected?.let { open(it) }
    }
    fun stop() { persist(); saveSeen(0); active = false; generation++; stateJob?.cancel(); conversationJob?.cancel(); messages.stop(); api.cancelCalls(); mutable.value = mutable.value.copy(connection = "offline", readingError = if (mutable.value.selected != null && mutable.value.readingError == null) "network" else mutable.value.readingError) }
    fun close() { persist(); cachedKey = null; dropSummaries(); generation++; conversationJob?.cancel(); messages.close(); restore = null; val write = store.conversationWrite(conversationEpoch); scope.launch { store.setLastSession(null, write) }; mutable.value = mutable.value.copy(selected = null, entries = emptyList(), error = null, readingError = null); refreshUnread() }
    /** Até quando cada conversa foi lida (`LocalStateStore.seenId` → hora do servidor); null até ler do aparelho. */
    private var seen: Map<String, Long>? = null
    private var seenJob: Job? = null
    /** Marca a conversa aberta como lida até o último snapshot e recalcula os balões de não lidas. */
    private fun refreshUnread() {
        val current = seen ?: return
        val state = mutable.value
        val now = repository.serverTime
        val open = state.selected?.takeUnless { it.sub }?.key
        val next = if (now > 0) updatedSeen(current, state.sessions.filter { it.agentId != null }.map { store.seenId(it.key) }, open?.let(store::seenId), now) else null
        if (next != null) { seen = next; saveSeen(2_000) }
        val marks = next ?: current
        mutable.value = state.copy(unread = state.sessions.filter { it.key != open }.associate { it.key to unreadCount(it.replies, marks[store.seenId(it.key)]) }.filterValues { it > 0 })
    }
    private fun saveSeen(delayMs: Long) {
        val marks = seen ?: return
        seenJob?.cancel()
        // Escopo próprio, como as conversas: stop() roda no descarte da tela.
        seenJob = persistScope.launch { delay(delayMs); store.setSeenReplies(marks) }
    }
    /** Sessão cuja conversa está em `conversation` (veio do aparelho ou do servidor) e pode ser guardada. */
    private var cachedKey: SessionKey? = null
    private var saveJob: Job? = null
    /** Resumos da conversa aberta (`summaryKey` → resumo): gravados no mesmo arquivo dela, para não pedir de novo. */
    private val summaries = HashMap<String, String>()
    fun summary(entry: TerminalEntry): String? = summaries[summaryKey(entry)]
    /** Resumos pedidos e ainda sem resposta: o mesmo item não pede duas vezes. */
    private val pendingSummaries = HashMap<String, Deferred<Result<String>>>()
    /**
     * Pede o resumo ao servidor e guarda na conversa aberta, se ela ainda for a mesma quando a resposta chegar. O
     * pedido roda no escopo do controlador: o item sair da tela ao rolar não cancela, e o resumo fica guardado.
     */
    suspend fun summarize(entry: TerminalEntry, text: String): String {
        val key = summaryKey(entry)
        summaries[key]?.let { return it }
        val token = generation
        // runCatching: a falha volta para quem espera sem derrubar o escopo do controlador.
        val job = pendingSummaries[key] ?: scope.async {
            runCatching { api.summary(text) }.onSuccess { if (token == generation) { summaries[key] = it; persist(3_000) } }
        }.also { job -> pendingSummaries[key] = job; job.invokeOnCompletion { pendingSummaries.remove(key, job) } }
        return job.await().getOrThrow()
    }
    private fun dropSummaries() { summaries.clear(); pendingSummaries.values.forEach { it.cancel() }; pendingSummaries.clear() }
    /** Guarda a conversa aberta no aparelho: na hora, ou alguns segundos depois da última novidade. */
    private fun persist(delayMs: Long = 0) {
        val key = cachedKey ?: return
        saveJob?.cancel()
        if (delayMs == 0L) save(key) else saveJob = scope.launch { delay(delayMs); if (cachedKey == key) save(key) }
    }
    private fun save(key: SessionKey) {
        val entries = conversation.snapshot().toList()
        val truncated = conversation.truncated
        val kept = HashMap(summaries)
        val write = store.conversationWrite(conversationEpoch)
        // Escopo próprio: stop()/close() rodam no descarte da tela, quando `scope` já foi cancelado.
        persistScope.launch { store.setConversation(key, entries, truncated, write, kept) }
    }
    private fun changed() { close(); mutable.value = mutable.value.copy(error = "changed") }
    private fun failure(event: StreamEvent.Failure, conversation: Boolean) {
        if (event.status == 401) { unauthorized(); return }
        mutable.value = mutable.value.streamFailure(event.status, conversation)
    }
    /** Abre a sessão tocada numa notificação; se a lista ainda não chegou, abre quando ela chegar. */
    fun openKey(key: SessionKey) {
        if (key.origin != api.origin) return
        val item = mutable.value.sessions.find { it.key == key }
        if (item != null) open(item) else { restore = key; restoreLoaded = true }
    }
    fun open(item: SessionItem) {
        persist(); cachedKey = null; dropSummaries()
        generation++; val token = generation
        conversationJob?.cancel()
        mutable.value = mutable.value.copy(selected = item, entries = emptyList(), truncated = false, reduced = false, error = null, readingError = null)
        // Abrir lê tudo o que já chegou: sem isso, uma conversa parada (sem snapshot novo) voltaria com o balão.
        refreshUnread()
        messages.open(item.key)
        if (!active || !repository.terminalAllowed) return
        val base = if (item.agentId != null) "/api/agents/${encode(item.agentId)}/terminal" else "/api/sessions/${encode(item.key.account)}/${encode(item.key.sessionId)}/terminal"
        if (!item.sub) { val write = store.conversationWrite(conversationEpoch); scope.launch { store.setLastSession(item.key, write) } }
        conversationJob = scope.launch {
            // O que já foi lido aparece na hora; o servidor manda só o que veio depois da última entrada guardada.
            // Subagentes não ficam guardados: são conversas curtas, e cada uma viraria um arquivo a mais no aparelho.
            val cached = if (item.sub) null else store.conversation(item.key)
            if (token != generation) return@launch
            val last = cached?.entries?.lastOrNull { it.at != null }
            if (cached != null && cached.entries.isNotEmpty()) {
                conversation.init(cached.entries, cached.truncated)
                summaries.putAll(cached.summaries)
                cachedKey = item.key
                mutable.value = mutable.value.copy(entries = conversation.snapshot(), truncated = conversation.truncated, reduced = conversation.reduced)
            }
            val path = if (last == null) base else "$base?after=${encode(last.id)}&at=${last.at}"
            var initialized = false
            streams.stream(path).collect { event ->
                if (token != generation) return@collect
                when (event) {
                    is StreamEvent.Connected -> mutable.value = mutable.value.conversationConnected()
                    is StreamEvent.Failure -> failure(event, conversation = true)
                    is StreamEvent.Data -> try {
                        if (event.type == "init") {
                            // Reenvio da conversa (reconexão, /shake ou arquivo reescrito) não é troca de sessão: a
                            // conferência abaixo pergunta ao servidor e só fecha se o agente estiver em outra sessão.
                            if (item.agentId != null && !item.sub) {
                                // A conferência da sessão também tolera falha passageira: até 3 tentativas antes de desistir.
                                var matches: Boolean? = null
                                var attempt = 0
                                while (matches == null) {
                                    try { matches = snapshotMatchesSession(api.request("/api/snapshot"), item.key, item.agentId) } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        if (e is ApiException && e.status == 401) { unauthorized(); return@collect }
                                        val transient = if (e is ApiException) e.status >= 500 || e.status == 408 || e.status == 429 else e is java.io.IOException
                                        if (!transient || ++attempt >= FAILURES_BEFORE_NOTICE) {
                                            close(); mutable.value = mutable.value.copy(error = "network")
                                            return@collect
                                        }
                                        delay(maxOf(1000L * attempt, retryAfterMillis((e as? ApiException)?.retryAfter)))
                                        if (token != generation) return@collect
                                    }
                                }
                                if (token != generation) return@collect
                                if (!initKeepsSession(matches == true, mutable.value.sessions, item)) {
                                    changed(); return@collect
                                }
                            }
                            val obj = wireJson.parseToJsonElement(event.data).jsonObject
                            val items = obj.objects("entries").mapNotNull(TerminalEntry::parse)
                            // `after` = o servidor achou a última entrada guardada e mandou só o resto: soma ao que já está na tela.
                            if (obj["after"] != null && cachedKey == item.key) conversation.append(items)
                            else conversation.init(items, obj.flag("truncated"))
                            if (!item.sub) cachedKey = item.key
                            initialized = true
                        } else if (event.type == "append" && initialized) {
                            conversation.append(wireJson.parseToJsonElement(event.data).jsonArray.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(TerminalEntry::parse) })
                        }
                        persist(3_000)
                        mutable.value = mutable.value.copy(entries = conversation.snapshot(), truncated = conversation.truncated, reduced = conversation.reduced)
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; mutable.value = mutable.value.copy(readingError = "oversize"); conversationJob?.cancel() }
                }
            }
        }
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
