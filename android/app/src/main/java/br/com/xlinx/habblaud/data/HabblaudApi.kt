package br.com.xlinx.habblaud.data

import android.content.Context
import br.com.xlinx.habblaud.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val retryAfter: String? = null, val code: String? = null) : IOException("HTTP $status")
class VpnRequiredException : IOException("Conecte o Tailscale")
class RedirectException : IOException("Redirecionamento recusado")
internal fun httpClient(): OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).readTimeout(45, TimeUnit.SECONDS).build()


class HabblaudApi(context: Context, val origin: String, consent: Boolean) : AutoCloseable {
    private val host = URI(origin).host
    init { require(UrlPolicy.origin(origin, host, consent, BuildConfig.ALLOW_LOOPBACK_HTTP) == origin) }
    val cookies = SessionCookieStore(context, origin)
    private val vpn = VpnGuard(context)
    private val base = httpClient().newBuilder().cookieJar(cookies).build()
    private val loopback = host == "127.0.0.1" || host.equals("localhost", true)

    fun client(): OkHttpClient = if (origin.startsWith("http://") && !loopback) vpn.client(base) ?: throw VpnRequiredException() else base

    suspend fun request(path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/") && !path.startsWith("//"))
        val builder = Request.Builder().url(origin + path)
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client().newCall(builder.build()).execute().use { response ->
            if (response.code in 300..399) throw RedirectException()
            if (response.code == 401) { cancelCalls(); cookies.clear() }
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(response.body?.string() ?: "").optString("code").ifEmpty { null } }.getOrNull()
                throw ApiException(response.code, response.header("Retry-After"), code)
            }
            response.body?.string() ?: ""
        }
    }

    suspend fun login(user: String, password: String) {
        val result = JSONObject(request("/api/remote/login", JSONObject().put("user", user).put("password", password)))
        if (!result.optBoolean("ok")) throw IOException("Login recusado")
    }
    suspend fun access(): String = request("/api/access")
    suspend fun logout(): Boolean = try { request("/api/remote/logout", JSONObject()); true } catch (_: IOException) { false } finally { cancelCalls(); cookies.clear() }
    fun cancelCalls() { base.dispatcher.cancelAll(); base.connectionPool.evictAll(); vpn.cancelCalls() }
    override fun close() { cancelCalls(); vpn.close() }
}
