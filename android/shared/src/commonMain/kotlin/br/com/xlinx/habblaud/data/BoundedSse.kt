package br.com.xlinx.habblaud.data

import io.ktor.client.HttpClient
import io.ktor.client.utils.HttpRequestIsReadyForSending
import io.ktor.client.request.ResponseAdapter
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class SseLimitException : IllegalStateException("SSE event too large")

/** Counts raw bytes only. Ktor remains the sole SSE decoder and dispatcher. */
internal class SseFrameLimit(private val maximum: Int) {
    private var eventBytes = 0
    private var lineBytes = 0
    private var carriageReturn = false
    fun accept(byte: Byte) {
        val value = byte.toInt() and 255
        if (carriageReturn && value == 10) { carriageReturn = false; return }
        carriageReturn = false
        if (value == 13 || value == 10) {
            if (lineBytes == 0) eventBytes = 0
            lineBytes = 0
            carriageReturn = value == 13
        } else {
            if (++eventBytes > maximum) throw SseLimitException()
            lineBytes++
        }
    }
}

@OptIn(InternalAPI::class)
internal fun HttpClient.installBoundedSse() {
    monitor.subscribe(HttpRequestIsReadyForSending) { request ->
        val adapter = request.attributes.getOrNull(ResponseAdapterAttributeKey) ?: return@subscribe
        request.attributes.put(ResponseAdapterAttributeKey, ResponseAdapter { data, status, headers, input, content, context ->
            val output = ByteChannel(autoFlush = true)
            CoroutineScope(context).launch {
                val limit = SseFrameLimit(ConversationState.MAX_BYTES)
                val buffer = ByteArray(8192)
                try {
                    while (true) {
                        val count = input.readAvailable(buffer)
                        if (count < 0) break
                        for (index in 0 until count) limit.accept(buffer[index])
                        output.writeFully(buffer, 0, count)
                    }
                    output.close()
                } catch (cause: Throwable) { output.close(cause) }
                finally { input.cancel() }
            }
            adapter.adapt(data, status, headers, output, content, context)
        })
    }
}
