package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.notify_batch
import br.com.xlinx.habblaud.resources.notify_done
import br.com.xlinx.habblaud.resources.notify_interrupted
import br.com.xlinx.habblaud.resources.notify_permission
import br.com.xlinx.habblaud.resources.notify_public
import br.com.xlinx.habblaud.resources.notify_question
import br.com.xlinx.habblaud.resources.notify_waiting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

suspend fun watchAlerts(
    store: LocalStateStore,
    onServers: (Int) -> Unit,
    notify: (AgentEvent) -> Unit,
    withdraw: (SessionKey) -> Unit = {},
) {
    val origins = store.servers().filter { UrlPolicy.origin(it) == it }
    if (origins.isEmpty() || !store.notificationsEnabled()) return
    onServers(origins.size)
    coroutineScope {
        val rules = MutableStateFlow(store.notificationRules.first())
        val refresh = launch { store.notificationRules.collect { rules.value = it } }
        fun deliver(event: AgentEvent) { if (rules.value.allows(event)) notify(event) }
        try {
            origins.map { origin -> launch {
                val api = HabblaudApi(origin)
                try {
                    val sessions = SessionRepository(api)
                    val streams = ConversationRepository(api)
                    var current = emptyList<SessionItem>()
                    var previous: Map<SessionKey, AgentWatch>? = null
                    var firstSnapshot = true
                    var typedNoticeSeen = false
                    val lock = Mutex()
                    val fallback = linkedMapOf<SessionKey, AgentEvent>()
                    var fallbackJob: Job? = null
                    while (currentCoroutineContext().isActive) {
                        var unauthorized = false
                        streams.stream("/api/stream").collect { event -> lock.withLock {
                            when (event) {
                                is StreamEvent.Failure -> if (event.status == 401) unauthorized = true else Unit
                                is StreamEvent.Connected -> {
                                    firstSnapshot = true
                                    typedNoticeSeen = false
                                }
                                is StreamEvent.Data -> when (event.type) {
                                    "snapshot" -> {
                                        current = withContext(Dispatchers.Default) { sessions.snapshot(event.data) }
                                        val next = watchOf(current)
                                        previous?.let { before ->
                                            withdrawnSessions(before, next).forEach { key -> fallback.remove(key); withdraw(key) }
                                            if (useSnapshotDiff(firstSnapshot, typedNoticeSeen)) {
                                                val changes = agentEvents(before, current)
                                                if (firstSnapshot) changes.forEach(::deliver)
                                                else if (changes.isNotEmpty()) {
                                                    changes.forEach { fallback[it.session.key] = it }
                                                    // O servidor novo manda notice logo depois do snapshot no mesmo lote.
                                                    if (fallbackJob?.isActive != true) fallbackJob = launch {
                                                        delay(250)
                                                        lock.withLock { fallback.values.forEach(::deliver); fallback.clear(); fallbackJob = null }
                                                    }
                                                }
                                            }
                                        }
                                        previous = next
                                        firstSnapshot = false
                                    }
                                    "notice" -> {
                                        val notice = runCatching { wireJson.parseToJsonElement(event.data) }.getOrNull() as? JsonObject
                                        if (notice?.string("kind") != null) {
                                            typedNoticeSeen = true
                                            fallback.clear()
                                            noticeEvent(notice, current)?.let(::deliver)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        } }
                        // Fim do stream: login vencido (401) ou erro definitivo. Sem login, para; senão tenta de novo daqui a pouco.
                        if (unauthorized) return@launch
                        delay(30_000)
                    }
                } finally {
                    api.close()
                }
            } }.joinAll()
        } finally {
            refresh.cancel()
        }
    }
}

fun alertText(event: AgentEvent): StringResource = when (event) {
    is AgentEvent.Permission -> when (event.wait) {
        WaitKind.Permission -> Res.string.notify_permission
        WaitKind.Question -> Res.string.notify_question
        WaitKind.Terminal -> Res.string.notify_waiting
    }
    is AgentEvent.TurnDone -> if (event.interrupted) Res.string.notify_interrupted else Res.string.notify_done
}

fun alertBody(label: String, event: AgentEvent): String {
    val reason = (event as? AgentEvent.Permission)?.reason?.trim()?.takeIf { it.isNotEmpty() } ?: return label
    val text = if (reason.length > 120) reason.take(119).trimEnd() + "…" else reason
    return "$label: $text"
}

suspend fun alertBody(event: AgentEvent): String = alertBody(getString(alertText(event)), event)
suspend fun publicAlertText(): String = getString(Res.string.notify_public)
suspend fun batchAlertText(count: Int): String = getPluralString(Res.plurals.notify_batch, count, count)

fun alertSubtitle(event: AgentEvent, multipleServers: Boolean): String? =
    listOfNotNull(
        event.session.project?.substringAfterLast('/'),
        serverLabel(event.session.key.origin).takeIf { multipleServers },
    ).joinToString(" · ").ifEmpty { null }
