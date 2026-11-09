package br.com.xlinx.habblaud.data

import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Pergunta do bate-papo paralelo (server/asides): `status` = queued | running | complete | failed | cancelled. */
data class AsideTurn(val id: String, val question: String, val answer: String, val status: String, val error: String? = null) {
    val running: Boolean get() = status == "queued" || status == "running"
}

data class AsideTopic(val id: String, val createdAt: Long, val turns: List<AsideTurn>)

/**
 * Estado da folha do bate-papo paralelo de um agente do omp. `topicId` null = a conversa mais recente; `fresh` = o
 * próximo envio abre uma conversa nova. `error` é o texto do servidor (ou `"network"` sem resposta dele).
 */
data class AsideState(
    val agentId: String? = null,
    val topics: List<AsideTopic> = emptyList(),
    val topicId: String? = null,
    val fresh: Boolean = false,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
) {
    val topic: AsideTopic? get() = if (fresh) null else topics.firstOrNull { it.id == topicId } ?: topics.firstOrNull()
    val running: Boolean get() = topics.any { topic -> topic.turns.any { it.running } }
}

/** Limites do servidor (shared/asides.ts). */
const val ASIDE_QUESTION_MAX = 20_000
private const val ASIDE_POLL_MS = 700L

internal fun parseAsideTopic(o: JsonObject): AsideTopic? {
    val id = o.string("id") ?: return null
    val createdAt = o["createdAt"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: 0L
    val turns = o.objects("turns").mapNotNull { t ->
        AsideTurn(t.string("id") ?: return@mapNotNull null, t.string("question").orEmpty(), t.string("answer").orEmpty(), t.string("status") ?: return@mapNotNull null, t.string("error"))
    }
    return AsideTopic(id, createdAt, turns)
}

/**
 * Bate-papo paralelo (/api/asides): perguntas ao agente do omp que não entram na conversa dele. Consulta o servidor
 * ao abrir e a cada 0,7 s enquanto alguma pergunta está em andamento, como a janela lateral da página.
 */
class AsideController(
    private val request: suspend (String, String?) -> String,
    private val scope: CoroutineScope,
    private val unauthorized: () -> Unit,
) {
    private val mutable = MutableStateFlow(AsideState())
    val state: StateFlow<AsideState> = mutable
    private var poller: Job? = null

    fun open(agentId: String) {
        poller?.cancel(); poller = null
        mutable.value = AsideState(agentId = agentId, loading = true)
        poll()
    }

    fun close() {
        poller?.cancel(); poller = null
        mutable.value = AsideState()
    }

    fun choose(topicId: String) { mutable.value = mutable.value.copy(topicId = topicId, fresh = false, error = null) }

    fun startNew() { mutable.value = mutable.value.copy(fresh = true, error = null) }

    fun ask(question: String) {
        val current = mutable.value
        val agentId = current.agentId ?: return
        val text = question.trim()
        if (text.isEmpty() || text.length > ASIDE_QUESTION_MAX || current.sending || current.topic?.turns?.any { it.running } == true) return
        val topicId = current.topic?.id
        mutable.value = current.copy(sending = true, error = null)
        scope.launch {
            val result = call(agentId) { request("/api/asides", buildJsonObject { put("agentId", agentId); put("question", text); topicId?.let { put("topicId", it) } }.toString()) }
            val topic = result?.let { wireJson.parseToJsonElement(it).jsonObject["topic"]?.jsonObject?.let(::parseAsideTopic) }
            val now = mutable.value
            if (now.agentId != agentId) return@launch
            mutable.value = if (topic == null) now.copy(sending = false)
            else now.copy(sending = false, topics = listOf(topic) + now.topics.filter { it.id != topic.id }, topicId = topic.id, fresh = false)
            if (topic != null) poll()
        }
    }

    fun cancel() {
        val current = mutable.value
        val agentId = current.agentId ?: return
        val topic = current.topic ?: return
        scope.launch {
            call(agentId) { request("/api/asides/${topic.id.encodeURLPathPart()}/cancel", buildJsonObject { put("agentId", agentId) }.toString()) } ?: return@launch
            poll()
        }
    }

    /** Consulta agora e repete enquanto houver pergunta em andamento. */
    private fun poll() {
        // Uma consulta já no ar pode ter saído antes da pergunta/parada: `dirty` faz o laço consultar de novo.
        dirty = true
        if (poller?.isActive == true) return
        val agentId = mutable.value.agentId ?: return
        poller = scope.launch {
            while (true) {
                dirty = false
                val body = call(agentId) { request("/api/asides?agentId=${agentId.encodeURLParameter()}", null) }
                val now = mutable.value
                if (now.agentId != agentId) return@launch
                val topics = body?.let { wireJson.parseToJsonElement(it).jsonObject.objects("topics").mapNotNull(::parseAsideTopic) }
                mutable.value = if (topics == null) now.copy(loading = false) else now.copy(loading = false, topics = topics)
                if (dirty) continue
                if (topics == null || !mutable.value.running) break
                delay(ASIDE_POLL_MS)
            }
        }
    }
    private var dirty = false

    /** Uma chamada; falha vira `error` no estado (do mesmo agente) e devolve null. */
    private suspend fun call(agentId: String, block: suspend () -> String): String? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is ApiException && e.status == 401) unauthorized()
        if (mutable.value.agentId == agentId) mutable.value = mutable.value.copy(error = (e as? ApiException)?.detail ?: "network")
        null
    }
}
