package br.com.xlinx.habblaud.data

/** O que importa de uma sessão aberta para decidir se avisa. */
data class AgentWatch(val status: String?, val permissionId: String?)

sealed interface AgentEvent {
    val session: SessionItem
    /** Pedido novo de aprovação ou pergunta (cada pedido avisa uma vez). */
    data class Permission(override val session: SessionItem) : AgentEvent
    /** O agente estava trabalhando e parou: terminou o turno. */
    data class TurnDone(override val session: SessionItem) : AgentEvent
}

/** Compara dois snapshots seguidos das sessões abertas. O primeiro snapshot da conexão não deve passar por aqui. */
fun agentEvents(previous: Map<SessionKey, AgentWatch>, current: List<SessionItem>): List<AgentEvent> = current.filter { it.agentId != null }.mapNotNull { session ->
    val before = previous[session.key] ?: AgentWatch(null, null)
    val permission = session.permissionId
    when {
        permission != null && permission != before.permissionId -> AgentEvent.Permission(session)
        before.status == "working" && session.status in setOf("idle", "done") -> AgentEvent.TurnDone(session)
        else -> null
    }
}

fun watchOf(sessions: List<SessionItem>): Map<SessionKey, AgentWatch> =
    sessions.filter { it.agentId != null }.associate { it.key to AgentWatch(it.status, it.permissionId) }
