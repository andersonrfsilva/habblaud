package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject

/** O que importa de uma sessão aberta para decidir se avisa. */
data class AgentWatch(val status: String?, val permissionId: String?)

sealed interface AgentEvent {
    val session: SessionItem
    /** Pedido novo de aprovação ou pergunta (cada pedido avisa uma vez). */
    data class Permission(
        override val session: SessionItem,
        val wait: WaitKind = if (session.permissionQuestion) WaitKind.Question else WaitKind.Permission,
        val reason: String? = null,
        val requestId: String? = session.permissionId.takeUnless { wait == WaitKind.Terminal },
    ) : AgentEvent
    /** O agente estava trabalhando e parou: terminou o turno. */
    data class TurnDone(
        override val session: SessionItem,
        val durationMs: Long? = null,
        val interrupted: Boolean = false,
    ) : AgentEvent
}

enum class WaitKind { Permission, Question, Terminal }

data class NotificationRules(val permission: Boolean = true, val done: Boolean = true, val minTurn: Boolean = true) {
    fun allows(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.Permission -> permission
        is AgentEvent.TurnDone -> done && (!minTurn || event.durationMs == null || event.durationMs >= 30_000)
    }
}

internal fun noticeEvent(notice: JsonObject, sessions: List<SessionItem>): AgentEvent? {
    val agentId = notice.string("agentId") ?: return null
    val session = sessions.firstOrNull { !it.sub && it.agentId == agentId } ?: return null
    return when (notice.string("kind")) {
        "wait" -> {
            val wait = when (notice.string("wait")) {
                "permission" -> WaitKind.Permission
                "question" -> WaitKind.Question
                "terminal" -> WaitKind.Terminal
                else -> return null
            }
            val requestId = notice.string("requestId").takeUnless { wait == WaitKind.Terminal }
            if (requestId != null && session.permissionId != requestId) return null
            AgentEvent.Permission(session, wait, notice.string("reason"), requestId)
        }
        "done" -> AgentEvent.TurnDone(session, notice.string("durationMs")?.toLongOrNull(), notice.flag("interrupted"))
        else -> null
    }
}

fun suppressNotification(key: SessionKey, visible: Boolean, open: SessionKey?) = visible && key == open

internal fun notificationPermission(session: SessionItem?, key: SessionKey, requestId: String): SessionItem? =
    session?.takeIf { it.key == key && it.permissionId == requestId }

const val ALERT_BATCH_WINDOW_MS = 3_000L
fun batchAlertCount(sessionCount: Int): Int? = sessionCount.takeIf { it >= 3 }

/** Compara snapshots de servidor antigo ou o primeiro snapshot depois de uma queda da conexão. */
fun agentEvents(previous: Map<SessionKey, AgentWatch>, current: List<SessionItem>): List<AgentEvent> = current.filter { it.agentId != null && !it.sub }.mapNotNull { session ->
    val before = previous[session.key] ?: AgentWatch(null, null)
    val permission = session.permissionId
    when {
        permission != null && permission != before.permissionId -> AgentEvent.Permission(session)
        permission == null && before.status == "working" && session.status == "waiting" -> AgentEvent.Permission(session, WaitKind.Terminal)
        before.status == "working" && session.status in setOf("idle", "done") -> AgentEvent.TurnDone(session)
        else -> null
    }
}

fun watchOf(sessions: List<SessionItem>): Map<SessionKey, AgentWatch> =
    sessions.filter { it.agentId != null && !it.sub }.associate { it.key to AgentWatch(it.status, it.permissionId) }

internal fun useSnapshotDiff(firstSnapshot: Boolean, typedNoticeSeen: Boolean) = firstSnapshot || !typedNoticeSeen

internal fun withdrawnSessions(before: Map<SessionKey, AgentWatch>, after: Map<SessionKey, AgentWatch>): List<SessionKey> =
    before.mapNotNull { (key, old) ->
        val new = after[key]
        key.takeIf { new == null || (old.permissionId != null && new.permissionId != old.permissionId) || (old.status != "working" && new.status == "working") }
    }
