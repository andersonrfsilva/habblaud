package br.com.xlinx.habblaud.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import io.ktor.http.encodeURLPathPart
import kotlinx.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Gravações do cache de conversas: duram o processo, não a tela (o descarte cancela o escopo da tela). */
private val persistScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

internal fun launchPersist(scope: CoroutineScope, block: suspend () -> Unit): Job = scope.launch {
    try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // O estado em memória permanece; a próxima gravação tenta persistir de novo.
    }
}

/** A primeira alteração abre a janela de gravação; as demais não adiam o prazo. */
internal class ConversationCacheSchedule(private val scope: CoroutineScope, private val save: () -> Job?) {
    private var pending: Job? = null
    private var write: Job? = null

    fun schedule() {
        if (pending != null) return
        pending = launchPersist(scope) {
            delay(3_000)
            pending = null
            save()?.let { write = it }
        }
    }

    fun flush(force: Boolean = true) {
        if (pending == null && !force) return
        pending?.cancel()
        pending = null
        save()?.let { write = it }
    }

    suspend fun awaitWrite() { write?.join() }
}

 data class ReadingState(
    val sessions: List<SessionItem> = emptyList(), val selected: SessionItem? = null,
    val entries: List<TerminalEntry> = emptyList(), val truncated: Boolean = false,
    val reduced: Boolean = false, val terminalAllowed: Boolean = false,
    val connection: String = "connecting", val error: String? = null,
    val messagesAllowed: Boolean = false, val messageGuard: Boolean = false, val permissionsAllowed: Boolean = false,
    val readingError: String? = null,
    /** Respostas não lidas por sessão (só as com alguma), para o balão da tela principal. */
    val unread: Map<SessionKey, Int> = emptyMap(),
    val marks: Map<SessionKey, SessionMarks> = emptyMap(),
    val reading: ConversationReading = ConversationReading(),
    val readingLoaded: Boolean = false,
    val accounts: List<AccountQuota> = emptyList(),
    val snapshotReady: Boolean = false,
    val recentWindow: RecentWindow? = null,
    val recentRefreshFailed: Boolean = false,
)

internal fun ReadingState.readingSession(visibleKey: SessionKey?): SessionKey? =
    selected?.takeIf { !it.sub && it.key == visibleKey }?.key

internal inline fun MutableStateFlow<ReadingState>.publishUnread(
    marks: Map<String, Long>, visibleKey: SessionKey? = null, seenId: (SessionKey) -> String,
) {
    update { state ->
        val open = state.readingSession(visibleKey)
        state.copy(unread = state.sessions.filter { it.key != open }
            .associate { it.key to unreadCount(it.replies, marks[seenId(it.key)]) }.filterValues { it > 0 })
    }
}

internal fun ReadingState.streamFailure(status: Int?, conversation: Boolean): ReadingState {
    val reason = when (status) { -1 -> "oversize"; 403 -> "forbidden"; 404 -> "missing"; else -> "network" }
    return if (conversation) copy(readingError = reason) else copy(connection = "offline", error = reason, snapshotReady = false)
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
    private var recentJob: Job? = null
    private val recentLock = Mutex()
    private var conversationJob: Job? = null
    private var generation = 0
    private var active = false
    private var accessReady = false
    private var readingVisibleKey: SessionKey? = null
    private val conversation = ConversationState()
    private val conversationEpoch = store.conversationEpoch(api.origin)
    val messages = MessageController(api, store, scope, ::canSend, ::changed, unauthorized)
    /** Bate-papo paralelo do agente aberto; fecha junto com a conversa. */
    val asides = AsideController({ path, body -> api.request(path, body?.let { wireJson.parseToJsonElement(it).jsonObject }) }, scope, unauthorized)
    private fun canSend(key: SessionKey, agentId: String): Boolean {
        val current = mutable.value
        return active && accessReady && current.connection == "connected" && current.messagesAllowed && current.messageGuard &&
            current.selected?.key == key && current.sessions.any { it.key == key && it.agentId == agentId && it.canMessage }
    }

    private val marksLock = Mutex()
    /** Marcas que só este aparelho tem (de antes da sincronização, ou de um servidor sem `/api/marks`); sobem em [uploadLocalMarks]. */
    private var savedMarks: Map<String, SessionMarks>? = null
    /** Marcas guardadas no servidor (evento `marks` do stream), iguais em todos os aparelhos. */
    private var serverMarks: Map<SessionKey, SessionMarks> = emptyMap()
    private suspend fun loadMarks() {
        if (savedMarks == null) savedMarks = store.sessionMarks(api.origin)
        publishMarks()
    }
    private fun marksOf(key: SessionKey): SessionMarks {
        val local = savedMarks?.get(store.seenId(key))
        val remote = serverMarks[key]
        return SessionMarks(local?.pinned == true || remote?.pinned == true, local?.favorite == true || remote?.favorite == true)
    }
    private fun publishMarks() {
        mutable.update { state -> state.copy(marks = state.sessions.associate { it.key to marksOf(it.key) }) }
    }
    private suspend fun setLocalMarks(key: SessionKey, marks: SessionMarks) {
        val id = store.seenId(key)
        if (marks == (savedMarks?.get(id) ?: SessionMarks())) return
        store.setSessionMarks(key, marks)
        savedMarks = savedMarks.orEmpty().toMutableMap().apply { if (marks == SessionMarks()) remove(id) else put(id, marks) }
    }
    /**
     * Grava no servidor, que avisa os outros aparelhos; a cópia local deixa de valer. Só o campo mandado muda lá
     * (null = fica como está): outro aparelho marcando a mesma sessão ao mesmo tempo não perde a marca dele.
     */
    private suspend fun sendMarks(key: SessionKey, pinned: Boolean?, favorite: Boolean?) {
        api.request("/api/marks", buildJsonObject {
            put("provider", key.provider); put("account", key.account); put("sessionId", key.sessionId)
            pinned?.let { put("pinned", it) }; favorite?.let { put("favorite", it) }
        })
        val old = serverMarks[key] ?: SessionMarks()
        serverMarks = serverMarks + (key to SessionMarks(pinned ?: old.pinned, favorite ?: old.favorite))
        setLocalMarks(key, SessionMarks())
    }
    /** Sobe as marcas que só este aparelho tem, somadas às do servidor. Só das sessões na lista: a chave local é um hash. */
    private suspend fun uploadLocalMarks() {
        for (session in mutable.value.sessions) {
            val local = savedMarks?.get(store.seenId(session.key)) ?: continue
            sendMarks(session.key, true.takeIf { local.pinned }, true.takeIf { local.favorite })
        }
    }
    private fun marksFailed(e: Exception) {
        if (e is CancellationException) throw e
        if (e is ApiException && e.status == 401) unauthorized()
        else mutable.update { it.copy(error = if (e is IOException) "network" else "storage") }
    }
    private fun serverMarksChanged(data: String) {
        val marks = wireJson.parseToJsonElement(data).jsonArray.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val key = SessionKey(api.origin, o.string("provider") ?: return@mapNotNull null, o.string("account") ?: return@mapNotNull null, o.string("sessionId") ?: return@mapNotNull null)
            key to SessionMarks(o.flag("pinned"), o.flag("favorite"))
        }.toMap()
        scope.launch {
            try {
                marksLock.withLock {
                    serverMarks = marks
                    loadMarks()
                    try { uploadLocalMarks() } finally { publishMarks() }
                }
            } catch (e: Exception) { marksFailed(e) }
        }
    }
    init {
        scope.launch {
            try { marksLock.withLock { loadMarks() } }
            catch (e: Exception) { marksFailed(e) }
        }
    }
    fun toggleMark(key: SessionKey, pin: Boolean) {
        if (key.origin != api.origin) return
        scope.launch {
            try {
                marksLock.withLock {
                    loadMarks()
                    val local = savedMarks?.get(store.seenId(key)) ?: SessionMarks()
                    val previous = marksOf(key)
                    val next = if (pin) previous.copy(pinned = !previous.pinned) else previous.copy(favorite = !previous.favorite)
                    // Vai o campo tocado; o outro só se este aparelho ainda guarda a marca dele, que sobe junto.
                    try { sendMarks(key, if (pin) next.pinned else true.takeIf { local.pinned }, if (pin) true.takeIf { local.favorite } else next.favorite) }
                    catch (e: ApiException) {
                        // Servidor anterior à sincronização: a marca fica só neste aparelho, como antes.
                        if (e.status != 404) throw e
                        setLocalMarks(key, next)
                    }
                    publishMarks()
                    mutable.update { if (it.error == "storage" || (it.error == "network" && it.connection == "connected")) it.copy(error = null) else it }
                }
            } catch (e: Exception) { marksFailed(e) }
        }
    }
    private var restore: SessionKey? = null
    private var restoreLoaded = false
    fun start() {
        if (active) return
        active = true
        accessReady = false
        messages.start()
        asides.resume()
        stateJob = scope.launch {
            try {
                var retry = 2000L
                while (true) {
                    try {
                        recentLock.withLock { repository.refresh(recentLoad.value.step) }
                        break
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (e is ApiException && e.status in listOf(401, 403, 404)) throw e
                        mutable.update { it.copy(connection = "offline", error = "network") }
                        delay(maxOf(retry, retryAfterMillis((e as? ApiException)?.retryAfter))); retry = (retry * 2).coerceAtMost(30000)
                    }
                }
                val terminalAllowed = repository.terminalAllowed
                val messagesAllowed = repository.messagesAllowed
                val messageGuard = repository.messageGuard
                val permissionsAllowed = repository.permissionsAllowed
                mutable.update {
                    it.copy(terminalAllowed = terminalAllowed, messagesAllowed = messagesAllowed, messageGuard = messageGuard,
                        permissionsAllowed = permissionsAllowed, recentWindow = repository.recentWindow, recentRefreshFailed = false)
                }
                if (!restoreLoaded) {
                    val saved = store.lastSession()?.takeIf { it.origin == api.origin }
                    // A leitura pode suspender enquanto uma notificação decide qual sessão abrir.
                    if (!restoreLoaded) {
                        restore = saved
                        restoreLoaded = true
                    }
                }
                if (seen == null) seen = store.seenReplies(api.origin)
                streams.stream("/api/stream").collect { event ->
                    when (event) {
                        is StreamEvent.Connected -> mutable.update {
                            it.copy(connection = "connected", error = it.error.takeUnless { error -> error == "network" }, snapshotReady = false)
                        }
                        is StreamEvent.Failure -> failure(event, conversation = false)
                        is StreamEvent.Data -> if (event.type == "snapshot") {
                            val snapshot = wireJson.parseToJsonElement(event.data).jsonObject
                            val sessions = repository.snapshot(snapshot)
                            val accounts = parseAccounts(snapshot)
                            reconcileSessions(sessions, accounts)
                            if (repository.recentStale && !recentLoad.value.loading && recentJob?.isActive != true) {
                                recentJob = scope.launch { refreshRecent(manual = false) }
                            }
                        } else if (event.type == "marks") serverMarksChanged(event.data)
                    }
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; if (e is ApiException && e.status == 401) unauthorized() else mutable.update { it.copy(connection = "offline", error = "network") } }
        }
    }

    internal suspend fun refreshRecent(manual: Boolean): Boolean = recentLock.withLock {
        if (!active || (!manual && recentLoad.value.loading)) return@withLock false
        val loaded = try {
            repository.refreshRecent(recentLoad.value.step)
        } catch (e: Exception) {
            // A troca de login ou ida ao fundo pode cancelar só a requisição, não o lote.
            currentCoroutineContext().ensureActive()
            if (e is ApiException && e.status == 401) unauthorized()
            else if (e !is CancellationException && manual && active) mutable.update { it.copy(recentRefreshFailed = true) }
            return@withLock false
        }
        if (!active) return@withLock false
        reconcileSessions(repository.sessions())
        if (mutable.value.recentRefreshFailed) mutable.update { it.copy(recentRefreshFailed = false) }
        loaded
    }

    private fun reconcileSessions(sessions: List<SessionItem>, accounts: List<AccountQuota>? = null) {
        val firstSnapshot = accounts != null && !accessReady
        val terminalAllowed = repository.terminalAllowed
        val previous = mutable.value
        val selected = previous.selected
        if (!terminalAllowed && previous.terminalAllowed && selected != null) close()
        else if (selected?.agentId != null && sessions.any { it.agentId == selected.agentId && it.key != selected.key }) changed()
        // A conversa de um subagente não está na lista: segue aberta como está até o leitor fechar.
        mutable.update { state ->
            state.copy(
                sessions = sessions, accounts = accounts ?: state.accounts, terminalAllowed = terminalAllowed,
                recentWindow = repository.recentWindow,
                snapshotReady = accounts != null || state.snapshotReady,
                selected = state.selected?.let { selectedItem ->
                    if (selectedItem.sub) sessions.firstNotNullOfOrNull { parent ->
                        parent.subagents.find { it.id == selectedItem.agentId }?.let { subSession(parent, it) }
                    } ?: selectedItem.copy(status = "done")
                    else sessions.find { it.key == selectedItem.key } ?: selectedItem.copy(agentId = null, canMessage = false)
                },
            )
        }
        publishMarks()
        if (accounts != null) accessReady = true
        resolveRestore(sessions)
        if (firstSnapshot && conversationJob?.isActive != true) mutable.value.selected?.let(::open)
        refreshUnread()
    }

    private fun resolveRestore(sessions: List<SessionItem>) {
        val key = restore ?: return
        val item = sessions.find { it.key == key } ?: return
        restore = null
        open(item)
    }

    fun stop() {
        cacheSave.flush(force = active)
        if (!active) return
        saveSeen(0); active = false; accessReady = false; generation++
        readingVisibleKey = null
        stateJob?.cancel(); conversationJob?.cancel(); recentJob?.cancel()
        pendingSummaries.values.toList().forEach { it.cancel() }; pendingSummaries.clear()
        messages.stop(); asides.pause(); api.cancelCalls()
        mutable.update {
            it.copy(connection = "offline", snapshotReady = false, readingError = if (it.selected != null && it.readingError == null) "network" else it.readingError)
        }
    }
    fun close() { cacheSave.flush(); cachedKey = null; dropSummaries(); generation++; conversationJob?.cancel(); messages.close(); asides.close(); restore = null; readingVisibleKey = null; val write = store.conversationWrite(api.origin, conversationEpoch); launchPersist(scope) { store.setLastSession(null, write) }; mutable.update { it.copy(selected = null, entries = emptyList(), error = null, readingError = null) }; refreshUnread() }
    /** Até quando cada conversa foi lida (`LocalStateStore.seenId` → hora do servidor); null até ler do aparelho. */
    private var seen: Map<String, Long>? = null
    private var seenJob: Job? = null
    fun setReadingVisible(key: SessionKey, visible: Boolean) {
        val next = when {
            visible && active && mutable.value.selected?.key == key -> key
            !visible && readingVisibleKey == key -> null
            else -> return
        }
        if (readingVisibleKey != next) {
            readingVisibleKey = next
            refreshUnread()
        }
    }

    /** Atualiza as marcas só quando a conversa está visível e seguindo o fim. */
    private fun refreshUnread() {
        val current = seen ?: return
        val state = mutable.value
        val now = repository.serverTime
        val open = state.readingSession(readingVisibleKey)
        val next = if (now > 0) updatedSeen(current, state.sessions.filter { it.agentId != null }.map { store.seenId(it.key) }, open?.let(store::seenId), now) else null
        if (next != null) { seen = next; saveSeen(2_000) }
        mutable.publishUnread(next ?: current, readingVisibleKey, store::seenId)
    }
    private fun saveSeen(delayMs: Long) {
        val marks = seen ?: return
        seenJob?.cancel()
        // Escopo próprio, como as conversas: stop() roda no descarte da tela.
        seenJob = launchPersist(persistScope) { delay(delayMs); store.setSeenReplies(api.origin, marks, conversationEpoch) }
    }
    /** Sessão cuja conversa está em `conversation` (veio do aparelho ou do servidor) e pode ser guardada. */
    private var cachedKey: SessionKey? = null
    private val cacheSave = ConversationCacheSchedule(scope, ::save)
    /** Resumos da conversa aberta (`summaryKey` → resumo): gravados no mesmo arquivo dela, para não pedir de novo. */
    private val summaries = HashMap<String, String>()
    fun summary(entry: TerminalEntry): String? = summaries[summaryKey(entry)]
    private var reading = ConversationReading()
    fun readingPosition(): ReadingPosition = reading.position
    fun expand(key: SessionKey, id: String, open: Boolean) {
        if (mutable.value.selected?.key != key || !mutable.value.readingLoaded) return
        val expanded = reading.expand(id, open)
        reading = expanded
        mutable.update { it.copy(reading = expanded) }
        scheduleCacheSave()
    }
    fun position(key: SessionKey, position: ReadingPosition) {
        if (mutable.value.selected?.key != key || !mutable.value.readingLoaded || reading.position == position) return
        reading = reading.copy(position = position)
        scheduleCacheSave()
    }
    /** Resumos pedidos e ainda sem resposta: o mesmo item não pede duas vezes. */
    private val pendingSummaries = HashMap<String, Deferred<Result<String>>>()
    /**
     * Pede o resumo ao servidor e guarda na conversa aberta, se ela ainda for a mesma quando a resposta chegar. O
     * pedido roda no escopo do controlador: o item sair da tela ao rolar não cancela, e o resumo fica guardado.
     */
    suspend fun summarize(entry: TerminalEntry, text: String): String {
        val key = summaryKey(entry)
        summaries[key]?.let { return it }
        if (!active) throw kotlinx.coroutines.CancellationException("Application is not active")
        val token = generation
        // runCatching: a falha volta para quem espera sem derrubar o escopo do controlador.
        val job = pendingSummaries[key] ?: scope.async {
            runCatching { api.summary(text) }.onSuccess { if (token == generation) { summaries[key] = it; scheduleCacheSave() } }
        }.also { job -> pendingSummaries[key] = job; job.invokeOnCompletion { if (pendingSummaries[key] === job) pendingSummaries.remove(key) } }
        return job.await().getOrThrow()
    }
    private fun dropSummaries() { summaries.clear(); pendingSummaries.values.forEach { it.cancel() }; pendingSummaries.clear() }
    private fun scheduleCacheSave() {
        if (cachedKey != null) cacheSave.schedule()
    }
    private fun save(): Job? {
        val key = cachedKey ?: return null
        val entries = conversation.snapshot().toList()
        val truncated = conversation.truncated
        val kept = HashMap(summaries)
        val savedReading = reading
        val write = store.conversationWrite(api.origin, conversationEpoch)
        // Escopo próprio: stop()/close() rodam no descarte da tela, quando `scope` já foi cancelado.
        return launchPersist(persistScope) { store.setConversation(key, entries, truncated, write, kept, savedReading) }
    }
    private fun publishConversation() {
        val entries = conversation.snapshot()
        val truncated = conversation.truncated
        val reduced = conversation.reduced
        mutable.update { it.copy(entries = entries, truncated = truncated, reduced = reduced) }
    }
    private fun changed() { close(); mutable.update { it.copy(error = "changed") } }
    private fun failure(event: StreamEvent.Failure, conversation: Boolean) {
        if (event.status == 401) { unauthorized(); return }
        mutable.update { it.streamFailure(event.status, conversation) }
    }
    /** Outra conversa foi pedida em outro servidor: este não restaura a última dele. */
    fun cancelRestore() { restore = null; restoreLoaded = true }

    /** Abre a sessão tocada numa notificação; se a lista ainda não chegou, abre quando ela chegar. */
    fun openKey(key: SessionKey) {
        if (key.origin != api.origin) return
        val item = mutable.value.sessions.find { it.key == key }
        if (item != null) open(item) else { restore = key; restoreLoaded = true }
    }
    fun open(item: SessionItem) {
        val sameSession = mutable.value.selected?.key == item.key
        if (!sameSession) readingVisibleKey = null
        cacheSave.flush(); cachedKey = null; dropSummaries()
        val emptyReading = ConversationReading()
        reading = emptyReading
        generation++; val token = generation
        conversationJob?.cancel()
        mutable.update { it.copy(selected = item, entries = emptyList(), truncated = false, reduced = false, error = null, readingError = null, reading = emptyReading, readingLoaded = false) }
        refreshUnread()
        messages.open(item.key); if (!sameSession) asides.close()
        if (!active || !accessReady || !repository.terminalAllowed) return
        val base = if (item.agentId != null) "/api/agents/${encode(item.agentId)}/terminal" else "/api/sessions/${encode(item.key.account)}/${encode(item.key.sessionId)}/terminal"
        if (!item.sub) { val write = store.conversationWrite(api.origin, conversationEpoch); launchPersist(scope) { store.setLastSession(item.key, write) } }
        conversationJob = scope.launch {
            // O que já foi lido aparece na hora; o servidor manda só o que veio depois da última entrada guardada.
            // Subagentes não ficam guardados: são conversas curtas, e cada uma viraria um arquivo a mais no aparelho.
            // Voltar imediatamente não pode ler o arquivo anterior à gravação feita no fechamento.
            cacheSave.awaitWrite()
            if (token != generation) return@launch
            val cached = if (item.sub) null else store.conversation(item.key)
            if (token != generation) return@launch
            val loadedReading = cached?.reading ?: ConversationReading()
            reading = loadedReading
            mutable.update { it.copy(reading = loadedReading, readingLoaded = true) }
            val last = cached?.entries?.lastOrNull { it.at != null }
            if (cached != null && cached.entries.isNotEmpty()) {
                conversation.init(cached.entries, cached.truncated)
                summaries.putAll(cached.summaries)
                cachedKey = item.key
                publishConversation()
            }
            val path = if (last == null) base else "$base?after=${encode(last.id)}&at=${last.at}"
            var initialized = false
            streams.stream(path).collect { event ->
                if (token != generation) return@collect
                when (event) {
                    is StreamEvent.Connected -> mutable.update { it.conversationConnected() }
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
                                        val transient = if (e is ApiException) e.status >= 500 || e.status == 408 || e.status == 429 else e is IOException
                                        if (!transient || ++attempt >= FAILURES_BEFORE_NOTICE) {
                                            close(); mutable.update { it.copy(error = "network") }
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
                        scheduleCacheSave()
                        publishConversation()
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; mutable.update { it.copy(readingError = "oversize") }; conversationJob?.cancel() }
                }
            }
        }
    }
    private fun encode(value: String) = value.encodeURLPathPart()
}
