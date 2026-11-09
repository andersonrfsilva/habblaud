package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.notify_done
import br.com.xlinx.habblaud.resources.notify_permission
import br.com.xlinx.habblaud.resources.notify_question
import org.jetbrains.compose.resources.StringResource

import kotlinx.coroutines.*

suspend fun watchAlerts(store: LocalStateStore, onServers: (Int) -> Unit, notify: (AgentEvent) -> Unit) {
    val origins = store.servers().filter { UrlPolicy.origin(it) == it }
    if (origins.isEmpty() || !store.notificationsEnabled()) return
    onServers(origins.size)
    coroutineScope {
        origins.map { origin -> launch {
            val api = HabblaudApi(origin)
            try {
                val sessions = SessionRepository(api)
                val streams = ConversationRepository(api)
                var previous: Map<SessionKey, AgentWatch>? = null
                while (currentCoroutineContext().isActive) {
                    var unauthorized = false
                    streams.stream("/api/stream").collect { event ->
                        if (event is StreamEvent.Failure && event.status == 401) unauthorized = true
                        // O snapshot depois de uma reconexão compara com o último antes da queda: o que mudou nela também avisa.
                        if (event is StreamEvent.Data && event.type == "snapshot") {
                            val current = sessions.snapshot(event.data)
                            previous?.let { before ->
                                val (permission, done) = store.notifyEvents()
                                agentEvents(before, current).filter { if (it is AgentEvent.Permission) permission else done }.forEach(notify)
                            }
                            previous = watchOf(current)
                        }
                    }
                    // Fim do stream: login vencido (401) ou erro definitivo. Sem login, para; senão tenta de novo daqui a pouco.
                    if (unauthorized) return@launch
                    delay(30_000)
                }
            } finally {
                api.close()
            }
        } }.joinAll()
    }
}

fun alertText(event: AgentEvent): StringResource = when (event) {
    is AgentEvent.Permission -> if (event.session.permissionQuestion) Res.string.notify_question else Res.string.notify_permission
    is AgentEvent.TurnDone -> Res.string.notify_done
}

fun alertSubtitle(event: AgentEvent, multipleServers: Boolean): String? =
    listOfNotNull(
        event.session.project?.substringAfterLast('/'),
        serverLabel(event.session.key.origin).takeIf { multipleServers },
    ).joinToString(" · ").ifEmpty { null }
