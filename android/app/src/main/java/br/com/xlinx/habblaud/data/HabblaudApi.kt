package br.com.xlinx.habblaud.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val retryAfter: String? = null, val code: String? = null, val attachmentsExpired: Boolean = false) : IOException("HTTP $status")
class RedirectException : IOException("Redirecionamento recusado")
internal fun httpClient(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).readTimeout(45, TimeUnit.SECONDS).build()
internal fun apiError(status: Int, retryAfter: String?, body: String): ApiException {
    val error = runCatching { wireJson.parseToJsonElement(body).jsonObject }.getOrNull()
    return ApiException(status, retryAfter, error?.string("code")?.ifEmpty { null }, error?.flag("attachmentsExpired") == true)
}


class HabblaudApi(context: Context, val origin: String) : AutoCloseable {
    val attachments = Attachments(context.contentResolver)
    init { require(UrlPolicy.origin(origin) == origin) }
    val cookies = SessionCookieStore(context, origin)
    private val base = httpClient().newBuilder().cookieJar(cookies).build()

    fun client(): OkHttpClient = base

    suspend fun request(path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        val builder = Request.Builder().url(origin + path)
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        // O servidor fecha conexões paradas há 5 s e o pool do OkHttp ainda as reaproveita: um GET (idempotente) que
        // falha antes de qualquer resposta tenta uma vez numa conexão nova. POST não repete (poderia duplicar o pedido).
        val firstCall = client().newCall(builder.build())
        val first = try { firstCall.execute() } catch (e: IOException) { if (body != null || firstCall.isCanceled()) throw e; null }
        val call = { client().newCall(builder.build()).execute() }
        (first ?: call()).use { response ->
            if (response.code in 300..399) throw RedirectException()
            if (response.code == 401) { cancelCalls(); cookies.clear() }
            if (!response.isSuccessful) {
                throw apiError(response.code, response.header("Retry-After"), response.body?.string().orEmpty())
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun upload(name: String, mime: String, body: RequestBody): String = suspendCancellableCoroutine { continuation ->
        try {
            val request = Request.Builder().url(origin + "/api/messages/attachments")
                .header("X-Habblaud-File-Name", encodeAttachmentName(name))
                .header("X-Habblaud-File-Type", mime)
                .post(body).build()
            val call = client().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (it.code in 300..399) throw RedirectException()
                            if (it.code == 401) { cancelCalls(); cookies.clear() }
                            val text = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw apiError(it.code, it.header("Retry-After"), text)
                            if (continuation.isActive) continuation.resume(text)
                        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                }
            })
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }

    suspend fun login(user: String, password: String) {
        val result = JSONObject(request("/api/remote/login", JSONObject().put("user", user).put("password", password)))
        if (!result.optBoolean("ok")) throw IOException("Login recusado")
    }
    suspend fun access(): String = request("/api/access")
    suspend fun logout(): Boolean = try { request("/api/remote/logout", JSONObject()); true } catch (_: IOException) { false } finally { cancelCalls(); cookies.clear() }
    fun cancelCalls() { base.dispatcher.cancelAll(); base.connectionPool.evictAll() }
    override fun close() { cancelCalls() }
}
