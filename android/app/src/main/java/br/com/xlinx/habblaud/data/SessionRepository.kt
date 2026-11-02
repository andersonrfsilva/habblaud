package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SessionRepository(private val api: HabblaudApi) {
    private var recent = emptyList<SessionItem>()
    private var open = emptyList<SessionItem>()
    var terminalAllowed = false
        private set
    var messagesAllowed = false
        private set
    var messageGuard = false
        private set

    suspend fun refresh() {
        val access = wireJson.parseToJsonElement(api.access()).jsonObject
        terminalAllowed = access.flag("terminal")
        messagesAllowed = access.flag("messages")
        messageGuard = access.flag("messageSessionGuard")
        recent = if (terminalAllowed) {
            try {
                wireJson.parseToJsonElement(api.request("/api/sessions/recent")).jsonObject.objects("sessions").mapNotNull { item(it, null) }
            } catch (e: ApiException) { if (e.status == 403) { terminalAllowed = false; emptyList() } else throw e }
        } else emptyList()
    }

    fun snapshot(data: String): List<SessionItem> {
        val root = wireJson.parseToJsonElement(data).jsonObject
        val rooms = root.objects("rooms").associate { it.string("id") to (it.string("path") ?: it.string("name")) }
        open = root.objects("agents").filter { it.string("kind") == "main" && it.string("parentId") == null }.mapNotNull { agent ->
            item(agent, rooms[agent.string("roomId")])
        }
        return sessions()
    }

    fun sessions(): List<SessionItem> {
        val live = open.associateBy { it.key }
        val historical = recent.associateBy { it.key }
        return open.map { item -> item.copy(project = item.project ?: historical[item.key]?.project) } + recent.filterNot { live.containsKey(it.key) }.map { it.copy(agentId = null) }
    }

    private fun item(o: JsonObject, project: String?): SessionItem? {
        val account = o.string("account") ?: return null
        val id = o.string("sessionId") ?: return null
        val key = SessionKey(api.origin, o.string("provider") ?: "claude", account, id)
        val agent = o.string("id") ?: o.string("agentId")
        val activity = (o["activity"] as? JsonObject)?.let { it.string("summary") ?: it.string("text") ?: it.string("description") }
        return SessionItem(key, agent, o.string("title") ?: o.string("name") ?: id, project ?: o.string("project"), o.string("status"), activity, o["permission"] is JsonObject, o.string("name"), o.flag("canMessage"))
    }
}
