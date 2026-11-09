// Cópia de HttpTransport.android.kt: mantenha as duas iguais.
package br.com.xlinx.habblaud.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import java.util.concurrent.TimeUnit

internal actual fun httpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    expectSuccess = false
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            readTimeout(45, TimeUnit.SECONDS)
            authenticator(okhttp3.Authenticator.NONE)
            proxyAuthenticator(okhttp3.Authenticator.NONE)
            addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (body == null) chain.proceed(request) else {
                    val oneShot = object : okhttp3.RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isDuplex() = body.isDuplex()
                        override fun isOneShot() = true
                        override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
                    }
                    chain.proceed(request.newBuilder().method(request.method, oneShot).build())
                }
            }
            cookieJar(okhttp3.CookieJar.NO_COOKIES)
        }
    }
    install(SSE) { maxReconnectionAttempts = 0 }
}.also { it.installBoundedSse() }

internal actual suspend fun platformUpload(api: HabblaudApi, name: String, mime: String, body: AttachmentBody): String = api.streamedUpload(name, mime, body)
