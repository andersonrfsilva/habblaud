package br.com.xlinx.habblaud.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Personagem dono de uma sessão omp encerrada: o único membro do elenco cujo `last` aponta para ela. */
internal fun castOwner(cast: List<JsonObject>, key: SessionKey): JsonObject? = cast.filter { member ->
    key.provider == "omp" && (member.string("account") == null || member.string("account") == key.account) &&
        (member["last"] as? JsonObject)?.values?.any { value -> value.toString().trim('"') == key.sessionId } == true
}.singleOrNull()

class SessionRepository(private val api: HabblaudApi) {
    private var recent = emptyList<SessionItem>()
    private var open = emptyList<SessionItem>()
    private var cast = emptyList<JsonObject>()
    var terminalAllowed = false
        private set
    var messagesAllowed = false
        private set
    var messageGuard = false
        private set
    var permissionsAllowed = false
        private set

    suspend fun refresh() {
        val access = wireJson.parseToJsonElement(api.access()).jsonObject
        terminalAllowed = access.flag("terminal")
        messagesAllowed = access.flag("messages")
        messageGuard = access.flag("messageSessionGuard")
        permissionsAllowed = access.flag("permissions")
        recent = if (terminalAllowed) {
            try {
                wireJson.parseToJsonElement(api.request("/api/sessions/recent")).jsonObject.objects("sessions").mapNotNull { item(it, null) }
            } catch (e: ApiException) { if (e.status == 403) { terminalAllowed = false; emptyList() } else throw e }
        } else emptyList()
        cast = if (terminalAllowed) try {
            wireJson.parseToJsonElement(api.request("/api/cast")).jsonObject.objects("members")
        } catch (e: ApiException) { if (e.status == 403 || e.status == 404) emptyList() else throw e } else emptyList()
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
        return open.map { item -> item.copy(project = item.project ?: historical[item.key]?.project, lastAt = item.lastAt ?: historical[item.key]?.lastAt, title = if (item.title == item.characterName) historical[item.key]?.title ?: item.title else item.title) } + recent.filterNot { live.containsKey(it.key) }.map { item ->
            val match = castOwner(cast, item.key)
            item.copy(agentId = null, avatar = match?.let(::parseAvatar), cast = match?.string("id"), characterName = match?.string("name"))
        }
    }

    private fun item(o: JsonObject, project: String?): SessionItem? {
        val account = o.string("account") ?: return null
        val id = o.string("sessionId") ?: return null
        val key = SessionKey(api.origin, o.string("provider") ?: "claude", account, id)
        val agent = o.string("id") ?: o.string("agentId")
        val activity = (o["activity"] as? JsonObject)?.let { it.string("summary") ?: it.string("text") ?: it.string("description") }
        val permission = o["permission"] as? JsonObject
        val context = o["context"] as? JsonObject
        return SessionItem(key, agent, o.string("title") ?: o.string("name") ?: id, project ?: o.string("project"), o.string("status"), activity, permission != null, o.string("name"), o.flag("canMessage"), permission?.string("id"), (o.string("lastEventAt") ?: o.string("lastAt"))?.toLongOrNull(), parseAvatar(o), o.string("cast"), o.flag("castOpened"), permission?.objects("questions")?.isNotEmpty() == true, o.string("name")?.takeIf { o.string("kind") == "main" },
            contextTokens = context?.string("tokens")?.toLongOrNull()?.takeIf { it >= 0 }, contextWindow = context?.string("window")?.toLongOrNull()?.takeIf { it > 0 },
            model = o.string("model"), thinking = o.string("thinking"),
            models = (o["models"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty())
    }

}

internal fun parseAvatar(o: JsonObject): Avatar? {
    val seed = o.string("seed")?.toLongOrNull()?.takeIf { it in 0..0xffffffffL } ?: return null
    val look = o.string("look")?.takeIf { it == "f" || it == "m" } ?: return null
    return Avatar(seed, look, if (o.string("kind") == "sub") "sub" else "main", (o["parts"] as? JsonObject)?.toString())
}
