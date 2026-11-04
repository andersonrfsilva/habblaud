package br.com.xlinx.habblaud.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.sse.SSE
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun httpClient(): HttpClient = HttpClient(Darwin) {
    followRedirects = false
    expectSuccess = false
    engine {
        configureSession {
            setHTTPCookieStorage(null)
            setHTTPShouldSetCookies(false)
            setURLCredentialStorage(null)
            setURLCache(null)
            setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setTimeoutIntervalForRequest(45.0)
        }
        configureRequest {
            setHTTPShouldHandleCookies(false)
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        }
        handleChallenge { _, _, challenge, complete ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                complete(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else {
                complete(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
    install(SSE) { maxReconnectionAttempts = 0 }
}.also { it.installBoundedSse() }

// Ktor 3.6.1 DarwinUtils.toDataOrStream uses a bounded 4096-byte NSStream pair for WriteChannelContent.
// The native delegate declines redirects and provides no replacement body stream for replay.
internal actual suspend fun platformUpload(api: HabblaudApi, name: String, mime: String, body: AttachmentBody): String = api.streamedUpload(name, mime, body)
