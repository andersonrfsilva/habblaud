package br.com.xlinx.habblaud.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.date.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException

internal const val FAILURES_BEFORE_NOTICE = 3
internal fun retryAfterMillis(value: String?): Long = value?.let {
    it.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
        (it.fromHttpToGmtDate().timestamp - getTimeMillis()).coerceIn(0, 86400000)
    }.getOrNull()
} ?: 0L

sealed interface StreamEvent {
    data class Data(val type: String, val data: String, val firstConnection: Boolean) : StreamEvent
    data class Failure(val status: Int?, val retryAfter: String? = null) : StreamEvent
    data object Connected : StreamEvent
}

class ConversationRepository(private val origin: String, private val client: () -> HttpClient, private val unauthorized: () -> Unit) {
    private var api: HabblaudApi? = null
    constructor(api: HabblaudApi) : this(api.origin, { api.client() }, { api.cancelCalls(); api.cookies.clear() }) { this.api = api }
    private fun once(path: String): Flow<StreamEvent> = flow {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        try {
            client().sse(request = { url(origin + path); api?.authorize(this) }) {
                emit(StreamEvent.Connected)
                incoming.collect { event -> event.data?.let { emit(StreamEvent.Data(event.event ?: "message", it, false)) } }
            }
            emit(StreamEvent.Failure(null))
        } catch (cause: CancellationException) { throw cause }
        catch (cause: SSEClientException) {
            val response = cause.response
            val status = response?.status?.value
            if (status == 401) {
                val owner = api
                if (owner != null && response != null) owner.unauthorized(response) else unauthorized()
            }
            var root: Throwable? = cause
            var oversized = false
            while (root != null) { if (root is SseLimitException) oversized = true; root = root.cause }
            emit(StreamEvent.Failure(if (oversized) -1 else status, response?.headers?.get(HttpHeaders.RetryAfter)))
        } catch (_: SseLimitException) { emit(StreamEvent.Failure(-1)) }
        catch (_: IOException) { emit(StreamEvent.Failure(null)) }
    }

    fun stream(path: String): Flow<StreamEvent> = flow {
        var delayMs = 2000L
        var connection = 0
        var failures = 0
        while (true) {
            var failure: StreamEvent.Failure? = null
            try {
                once(path).collect { event ->
                    when (event) {
                        is StreamEvent.Connected -> { connection++; failures = 0; delayMs = 2000L; emit(event) }
                        is StreamEvent.Data -> emit(event.copy(firstConnection = connection == 1))
                        is StreamEvent.Failure -> failure = event
                    }
                }
            } catch (_: IOException) { failure = StreamEvent.Failure(null) }
            val terminal = failure?.status in listOf(-1, 401, 403, 404) || failure?.status?.let { it in 300..399 } == true
            // Falha de rede passageira (troca de rede, limite de streams): só avisa depois de 3 seguidas.
            failure?.let { failures++; if (terminal || failures >= FAILURES_BEFORE_NOTICE) emit(it) }
            if (terminal) return@flow
            delay(maxOf(delayMs, retryAfterMillis(failure?.retryAfter)))
            delayMs = (delayMs * 2).coerceAtMost(30000)
        }
    }
}
