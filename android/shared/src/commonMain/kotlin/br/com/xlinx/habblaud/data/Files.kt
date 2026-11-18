package br.com.xlinx.habblaud.data

import br.com.xlinx.habblaud.isHttpMarkdownLink
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException
import kotlinx.serialization.json.*

data class FileRoot(val path: String, val label: String, val roomId: String? = null)
data class FileEntry(val name: String, val type: String, val size: Long, val mtime: Long, val kind: String? = null)
data class FileListing(val path: String, val root: String, val entries: List<FileEntry>, val truncated: Boolean = false)

suspend fun HabblaudApi.filesRoots(): List<FileRoot> = wireJson.parseToJsonElement(request("/api/files")).jsonObject.objects("roots").map {
    FileRoot(requireNotNull(it.string("path")), requireNotNull(it.string("label")), it.string("roomId"))
}
suspend fun HabblaudApi.filesList(path: String): FileListing = parseFileListing(request("/api/files/list?path=${path.encodeURLParameter()}"))

internal fun parseFileListing(json: String): FileListing {
    val listing = wireJson.parseToJsonElement(json).jsonObject
    return FileListing(requireNotNull(listing.string("path")), requireNotNull(listing.string("root")), listing.objects("entries").map {
        FileEntry(requireNotNull(it.string("name")), requireNotNull(it.string("type")), requireNotNull(it["size"]?.jsonPrimitive?.longOrNull), requireNotNull(it["mtime"]?.jsonPrimitive?.doubleOrNull).toLong(), it.string("kind"))
    }, listing.flag("truncated"))
}

/** Mantém só um bloco em memória; o destino publica o arquivo apenas após o retorno. */
suspend fun HabblaudApi.downloadFile(path: String, write: suspend (ByteArray, Int) -> Unit, progress: (Long, Long?) -> Unit = { _, _ -> }) {
    client().prepareGet(origin + "/api/files/raw") {
        parameter("path", path)
        parameter("download", "1")
        authorize(this)
    }.execute { response ->
        receive(response)
        streamFileDownload(response, write, progress)
    }
}

internal suspend fun streamFileDownload(response: HttpResponse, write: suspend (ByteArray, Int) -> Unit, progress: (Long, Long?) -> Unit = { _, _ -> }) {
        if (!response.status.isSuccess()) throw apiError(response.status.value, response.headers[HttpHeaders.RetryAfter], response.bodyAsText())
        val total = response.contentLength()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        progress(received, total)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) continue
            write(buffer, count)
            received += count
            progress(received, total)
        }
        currentCoroutineContext().ensureActive()
        if (total != null && received != total) throw IOException("Download incompleto")
}

internal fun initialFileRoot(roots: List<FileRoot>, roomId: String?, project: String?): FileRoot? =
    roots.firstOrNull { roomId != null && it.roomId == roomId }
        ?: roots.filter { project != null && (project == it.path || project.startsWith(it.path.trimEnd('/') + "/")) }.maxByOrNull { it.path.length }
        ?: roots.firstOrNull()

internal fun fileBreadcrumbs(root: String, path: String): List<Pair<String, String>> {
    if (path != root && !path.startsWith(root.trimEnd('/') + "/")) return emptyList()
    var current = root.trimEnd('/')
    return path.removePrefix(root).split('/').filter { it.isNotEmpty() }.map { name ->
        current += "/$name"
        name to current
    }
}

internal const val IMAGE_MAX_BYTES = 10 * 1024 * 1024

// Cliente separado: URLs da conversa nunca recebem os cookies ou a autorização da API.
private val imageClient by lazy { HttpClient { followRedirects = false } }

suspend fun HabblaudApi.imageBytes(path: String): ByteArray? = try {
    if (!path.startsWith("/") || path.startsWith("//")) null
    else client().prepareGet(origin + "/api/files/raw") {
        parameter("path", path)
        authorize(this)
    }.execute { response ->
        receive(response)
        boundedImageBytes(response)
    }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    currentCoroutineContext().ensureActive()
    null
}

internal suspend fun remoteImageBytes(url: String): ByteArray? = try {
    if (!isHttpMarkdownLink(url)) null
    else imageClient.prepareGet(url).execute { boundedImageBytes(it) }
} catch (e: Exception) {
    if (e is CancellationException) throw e
    currentCoroutineContext().ensureActive()
    null
}

private suspend fun boundedImageBytes(response: HttpResponse): ByteArray? {
    val length = response.contentLength()
    if (!response.status.isSuccess() || (length ?: 0) > IMAGE_MAX_BYTES) return null
    val channel = response.bodyAsChannel()
    var bytes = ByteArray(length?.takeIf { it > 0 }?.toInt() ?: 64 * 1024)
    var count = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!channel.awaitContent(1)) {
            channel.closedCause?.let { throw it }
            break
        }
        if (count == IMAGE_MAX_BYTES) return null
        if (count == bytes.size) bytes = bytes.copyOf((bytes.size * 2).coerceAtMost(IMAGE_MAX_BYTES))
        val read = channel.readAvailable(bytes, count, bytes.size - count)
        if (read < 0) break
        count += read
    }
    return if (count == 0) null else if (count == bytes.size) bytes else bytes.copyOf(count)
}
