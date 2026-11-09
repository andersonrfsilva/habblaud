package br.com.xlinx.habblaud.data

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.utils.HttpRequestIsReadyForSending
import io.ktor.client.utils.HttpResponseReceived
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.serialization.json.*

/** `detail` = o texto de `error` que o servidor manda no corpo (para mostrar ao usuário), quando houver. */
class ApiException(val status: Int, val retryAfter: String? = null, val code: String? = null, val attachmentsExpired: Boolean = false, val detail: String? = null) : IOException("HTTP $status")
class RedirectException : IOException("Redirecionamento recusado")
internal expect fun httpClient(): HttpClient
internal fun apiError(status: Int, retryAfter: String?, body: String): ApiException {
    val error = runCatching { wireJson.parseToJsonElement(body).jsonObject }.getOrNull()
    return ApiException(status, retryAfter, error?.string("code")?.ifEmpty { null }, error?.flag("attachmentsExpired") == true, error?.string("error")?.take(500)?.ifBlank { null })
}

class HabblaudApi(val origin: String) : AutoCloseable {
    val attachments = Attachments()
    init { require(UrlPolicy.origin(origin) == origin) }
    val cookies = SessionCookieStore(origin)
    private val base = httpClient()
    private val active = MutableStateFlow(emptySet<Job>())
    private val requestJob = AttributeKey<Job>("habblaud-request-job")
    private val cookieGeneration = AttributeKey<Long>("habblaud-cookie-generation")
    init {
        base.monitor.subscribe(HttpRequestIsReadyForSending) { request ->
            val job = request.executionContext
            request.attributes.put(requestJob, job)
            if (!request.attributes.contains(cookieGeneration)) request.attributes.put(cookieGeneration, cookies.generation())
            active.update { it + job }
            job.invokeOnCompletion { active.update { it - job } }
        }
        base.monitor.subscribe(HttpResponseReceived) { response ->
            val generation = response.request.attributes.getOrNull(cookieGeneration) ?: return@subscribe
            cookies.saveFromResponse(response.request.url.toString(), response.headers.getAll(HttpHeaders.SetCookie).orEmpty(), generation)
        }
    }
    fun client(): HttpClient = base

    internal fun authorize(builder: HttpRequestBuilder) {
        builder.attributes.put(cookieGeneration, cookies.generation())
        cookies.requestHeader(builder.url.buildString())?.let { builder.header(HttpHeaders.Cookie, it) }
    }
    internal fun receive(response: HttpResponse) {
        if (response.status.value in 300..399) throw RedirectException()
        if (response.status.value == 401) unauthorized(response)
    }
    internal fun unauthorized(response: HttpResponse) {
        val generation = response.request.attributes.getOrNull(cookieGeneration) ?: return
        if (cookies.clear(generation)) cancelCalls(response.request.attributes.getOrNull(requestJob))
    }
    private suspend fun text(response: HttpResponse): String {
        receive(response)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw apiError(response.status.value, response.headers[HttpHeaders.RetryAfter], body)
        return body
    }

    suspend fun request(path: String, body: JsonObject? = null): String {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        val json = body?.toString()?.encodeToByteArray()
        // O servidor fecha conexões paradas há 5 s e o pool do OkHttp ainda as reaproveita: um GET (idempotente) que
        // falha antes de qualquer resposta tenta uma vez numa conexão nova. POST não repete (poderia duplicar o pedido).
        var received = false
        suspend fun attempt(): String = base.prepareRequest(origin + path) {
            authorize(this)
            if (json != null) {
                method = HttpMethod.Post
                // A stream has no replacement body on Darwin; engine must not replay an ambiguous POST.
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.Json.withParameter("charset", "utf-8")
                    override val contentLength = json.size.toLong()
                    override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(json) }
                })
            }
        }.execute { received = true; text(it) }
        return try { attempt() } catch (e: IOException) {
            if (body != null || received) throw e
            attempt()
        }
    }

    suspend fun upload(name: String, mime: String, body: AttachmentBody): String = platformUpload(this, name, mime, body)

    internal suspend fun streamedUpload(name: String, mime: String, body: AttachmentBody): String = base.preparePost(origin + "/api/messages/attachments") {
        authorize(this)
        header("X-Habblaud-File-Name", encodeAttachmentName(name))
        header("X-Habblaud-File-Type", mime)
        setBody(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength = body.size
            override suspend fun writeTo(channel: ByteWriteChannel) { body.writeTo { bytes, count -> channel.writeFully(bytes, 0, count) } }
        })
    }.execute { text(it) }

    suspend fun login(user: String, password: String) {
        val result = wireJson.parseToJsonElement(request("/api/remote/login", buildJsonObject { put("user", user); put("password", password) })).jsonObject
        if (!result.flag("ok")) throw IOException("Login recusado")
    }
    suspend fun access(): String = request("/api/access")
    /** Resumo da resposta do agente em até 5 frases (POST /api/summaries; o servidor guarda e reaproveita). */
    suspend fun summary(text: String): String = wireJson.parseToJsonElement(request("/api/summaries", buildJsonObject { put("text", text) })).jsonObject["summary"]!!.jsonPrimitive.content
    suspend fun logout(): Boolean = try { request("/api/remote/logout", buildJsonObject {}); true } catch (_: IOException) { false } finally { cancelCalls(); cookies.clear() }
    suspend fun avatarBytes(path: String): ByteArray? {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        var received = false
        suspend fun attempt(): ByteArray? = base.prepareGet(origin + path) { authorize(this) }.execute { response ->
            received = true
            receive(response)
            if (!response.status.isSuccess()) return@execute null
            if (response.contentType()?.withoutParameters() != ContentType.Image.PNG) return@execute null
            val channel = response.bodyAsChannel()
            val bytes = ByteArray(1024 * 1024 + 1)
            var count = 0
            while (count < bytes.size) {
                val read = channel.readAvailable(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            if (count > 1024 * 1024) null else bytes.copyOf(count)
        }
        return try { attempt() } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            if (received) throw e
            attempt()
        }
    }
    private fun cancelCalls(except: Job?) {
        active.value.forEach { if (it !== except) it.cancel(CancellationException("Account requests cancelled")) }
    }
    fun cancelCalls() { cancelCalls(null) }
    override fun close() { cancelCalls(); base.close() }
}

internal expect suspend fun platformUpload(api: HabblaudApi, name: String, mime: String, body: AttachmentBody): String
