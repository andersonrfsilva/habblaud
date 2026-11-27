package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    /** Resposta final de um turno que deixou a sessão parada. */
    data class TurnDone(
        override val session: SessionItem,
        val durationMs: Long? = null,
        /** Começo da resposta final enviado pelo servidor. */
        val excerpt: String,
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
    // `repeat` é informativo; o aviso repetido também atualiza a notificação da sessão.
    return when (notice.string("kind")) {
        "wait" -> {
            val wait = when (notice.string("wait")) {
                "permission" -> WaitKind.Permission
                "question" -> WaitKind.Question
                "terminal" -> WaitKind.Terminal
                else -> return null
            }
            val requestId = notice.string("requestId").takeUnless { wait == WaitKind.Terminal }
            // O snapshot só expõe o primeiro pedido; o aviso também pode ser de um pedido na fila.
            if (requestId != null && session.permissionId == null) return null
            AgentEvent.Permission(session, wait, notice.string("reason"), requestId)
        }
        "done" -> {
            if (session.status != "idle" || notice.flag("interrupted")) return null
            val excerpt = (notice["excerpt"] as? JsonPrimitive)?.takeIf { it.isString }
                ?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            AgentEvent.TurnDone(session, notice.string("durationMs")?.toLongOrNull(), excerpt)
        }
        else -> null
    }
}

fun suppressNotification(key: SessionKey, visible: Boolean, open: SessionKey?) = visible && key == open

internal fun notificationPermission(session: SessionItem?, key: SessionKey, requestId: String): SessionItem? =
    session?.takeIf { it.key == key && it.permissionId == requestId }

const val ALERT_BATCH_WINDOW_MS = 3_000L
fun mergeAlertBatch(summarized: MutableSet<SessionKey>, ready: Map<SessionKey, AgentEvent>): Set<SessionKey> {
    val waiting = mutableSetOf<SessionKey>()
    for ((key, event) in ready) if (event is AgentEvent.Permission) waiting.add(key)
    if (waiting.size < 3) return emptySet()
    summarized.addAll(waiting)
    return waiting
}

/** Inclui promoções da fila mesmo quando o servidor já manda avisos tipados. */
internal fun agentEvents(
    previous: Map<SessionKey, AgentWatch>,
    current: List<SessionItem>,
    useFallback: Boolean = true,
): List<AgentEvent> = current.mapNotNull { session ->
    if (session.agentId == null || session.sub) return@mapNotNull null
    val before = previous[session.key] ?: AgentWatch(null, null)
    val permission = session.permissionId
    when {
        permission != null && permission != before.permissionId && (useFallback || before.permissionId != null) -> AgentEvent.Permission(session)
        !useFallback -> null
        permission == null && before.status == "working" && session.status == "waiting" -> AgentEvent.Permission(session, WaitKind.Terminal)
        else -> null
    }
}

fun watchOf(sessions: List<SessionItem>): Map<SessionKey, AgentWatch> =
    sessions.filter { it.agentId != null && !it.sub }.associate { it.key to AgentWatch(it.status, it.permissionId) }

internal fun useSnapshotDiff(firstSnapshot: Boolean, typedNoticeSeen: Boolean) = firstSnapshot || !typedNoticeSeen

internal fun withdrawnSessions(before: Map<SessionKey, AgentWatch>, after: Map<SessionKey, AgentWatch>): List<SessionKey> =
    before.mapNotNull { (key, old) ->
        val new = after[key]
        key.takeIf { new == null || (old.permissionId != null && new.permissionId == null) || (old.status != "working" && new.status == "working") }
    }
