package br.com.xlinx.habblaud.data

import kotlinx.coroutines.channels.awaitClose
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

class ConversationRepository(private val origin: String, private val client: () -> okhttp3.OkHttpClient, private val unauthorized: () -> Unit) {
    constructor(api: HabblaudApi) : this(api.origin, { api.client() }, { api.cancelCalls(); api.cookies.clear() })
    private fun once(path: String): Flow<StreamEvent> = callbackFlow {
        val client = try { client() } catch (e: IOException) { trySend(StreamEvent.Failure(null)); close(); return@callbackFlow }
        val source = EventSources.createFactory(client).newEventSource(Request.Builder().url(origin + path).build(), object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) { trySend(StreamEvent.Connected) }
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.toByteArray(Charsets.UTF_8).size > ConversationState.MAX_BYTES) {
                    trySend(StreamEvent.Failure(-1)); eventSource.cancel(); close(); return
                }
                trySend(StreamEvent.Data(type ?: "message", data, false))
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
        while (true) {
            var failure: StreamEvent.Failure? = null
            once(path).collect { event ->
                when (event) {
                    is StreamEvent.Connected -> { connection++; emit(event) }
                    is StreamEvent.Data -> emit(event.copy(firstConnection = connection == 1))
                    is StreamEvent.Failure -> { failure = event; emit(event) }
                }
            }
            if (failure?.status in listOf(-1, 401, 403, 404) || failure?.status?.let { it in 300..399 } == true) return@flow
            val retry = failure?.retryAfter?.let { value ->
                value.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(86400)?.times(1000) ?: runCatching {
                    (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86400000)
                }.getOrNull()
            }
            delay(maxOf(delayMs, retry ?: 0)); delayMs = (delayMs * 2).coerceAtMost(30000)
        }
    }
}
