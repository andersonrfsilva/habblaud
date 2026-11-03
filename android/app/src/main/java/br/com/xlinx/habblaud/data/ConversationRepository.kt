package br.com.xlinx.habblaud.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal const val FAILURES_BEFORE_NOTICE = 3

internal fun retryAfterMillis(value: String?): Long = value?.let {
    it.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
        (ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86400000)
    }.getOrNull()
} ?: 0L

sealed interface StreamEvent {
    data class Data(val type: String, val data: String, val firstConnection: Boolean) : StreamEvent
    data class Failure(val status: Int?, val retryAfter: String? = null) : StreamEvent
    data object Connected : StreamEvent
}

internal fun sendStreamEvent(channel: SendChannel<StreamEvent>, event: StreamEvent, cancel: () -> Unit) {
    if (channel.trySend(event).isFailure) {
        channel.close(IOException("SSE buffer overflow"))
        cancel()
    }
}

class ConversationRepository(private val origin: String, private val client: () -> okhttp3.OkHttpClient, private val unauthorized: () -> Unit) {
    constructor(api: HabblaudApi) : this(api.origin, { api.client() }, { api.cancelCalls(); api.cookies.clear() })
    private fun once(path: String): Flow<StreamEvent> = callbackFlow {
        val client = client()
        val source = EventSources.createFactory(client).newEventSource(Request.Builder().url(origin + path).build(), object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) { sendStreamEvent(channel, StreamEvent.Connected) { eventSource.cancel() } }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.toByteArray(Charsets.UTF_8).size > ConversationState.MAX_BYTES) {
                    sendStreamEvent(channel, StreamEvent.Failure(-1)) { eventSource.cancel() }; eventSource.cancel(); close(); return
                }
                sendStreamEvent(channel, StreamEvent.Data(type ?: "message", data, false)) { eventSource.cancel() }
            }
            override fun onClosed(eventSource: EventSource) { trySend(StreamEvent.Failure(null)); close() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val status = response?.code
                if (status == 401) unauthorized()
                trySend(StreamEvent.Failure(status, response?.header("Retry-After"))); close()
            }
        })
        awaitClose { source.cancel() }
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
            } catch (_: IOException) {
                failure = StreamEvent.Failure(null)
            }
            val terminal = failure?.status in listOf(-1, 401, 403, 404) || failure?.status?.let { it in 300..399 } == true
            // Falha de rede passageira (troca de rede, limite de streams): só avisa depois de 3 seguidas.
            failure?.let { failures++; if (terminal || failures >= FAILURES_BEFORE_NOTICE) emit(it) }
            if (terminal) return@flow
            val retry = failure?.retryAfter?.let { value ->
                value.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(86400)?.times(1000) ?: runCatching {
                    (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86400000)
                }.getOrNull()
            }
            delay(maxOf(delayMs, retry ?: 0)); delayMs = (delayMs * 2).coerceAtMost(30000)
        }
    }
}
