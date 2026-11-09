package br.com.xlinx.habblaud.data
import io.ktor.client.HttpClient
internal actual fun httpClient(): HttpClient = TODO()
internal actual suspend fun platformUpload(api: HabblaudApi, name: String, mime: String, body: AttachmentBody): String = TODO()
