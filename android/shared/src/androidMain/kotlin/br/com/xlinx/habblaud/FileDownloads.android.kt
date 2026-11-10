package br.com.xlinx.habblaud

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import br.com.xlinx.habblaud.data.storageContext
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private fun storageAllowed() = Build.VERSION.SDK_INT >= 29 ||
    storageContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
actual fun DownloadPermissionGate(content: @Composable (allowed: Boolean, request: () -> Unit) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(storageAllowed()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LifecycleResumeEffect(context) {
        allowed = storageAllowed()
        onPauseOrDispose { }
    }
    content(allowed) { launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
}
actual val canShowDownloadFolder: Boolean = false

private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

actual suspend fun createDownload(name: String): DownloadTarget {
    var target: DownloadTarget? = null
    try {
        return withContext(Dispatchers.IO) {
            check(storageAllowed()) { "Permita acesso ao armazenamento para salvar em Downloads." }
            val clean = sanitizedDownloadName(name)
            (if (Build.VERSION.SDK_INT >= 29) mediaDownload(clean) else legacyDownload(clean)).also { target = it }
        }
    } catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { target?.abort() }; throw error }
}

private fun mediaDownload(name: String): DownloadTarget {
    val resolver = storageContext.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime(name))
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Não foi possível criar o download.")
    val output = try { resolver.openOutputStream(uri, "w") ?: error("Não foi possível abrir o download.") }
        catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    return AndroidDownloadTarget(output, publish = {
        val displayName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) { "Não foi possível concluir o download." }
        SavedDownload(uri.toString(), displayName)
    }, remove = { resolver.delete(uri, null, null) })
}

@Suppress("DEPRECATION")
private fun legacyDownload(name: String): DownloadTarget {
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    check(directory.isDirectory || directory.mkdirs()) { "Não foi possível criar a pasta Downloads." }
    var index = 0
    var destination: File
    do { destination = File(directory, numberedDownloadName(name, index++)) } while (!destination.createNewFile())
    var temporary: File? = null
    try {
        val pending = File.createTempFile(".zaplaud-", ".part", directory)
        temporary = pending
        val output = pending.outputStream()
        return AndroidDownloadTarget(output, publish = {
            // A reserva exclusiva impede sobrescrever arquivos; a troca só expõe o conteúdo completo.
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val uri = FileProvider.getUriForFile(storageContext, "${storageContext.packageName}.downloads", destination)
            SavedDownload(uri.toString(), destination.name)
        }, remove = { pending.delete(); destination.delete() })
    } catch (error: Throwable) { temporary?.delete(); destination.delete(); throw error }
}

private class AndroidDownloadTarget(
    private val output: OutputStream,
    private val publish: () -> SavedDownload,
    private val remove: () -> Unit,
) : DownloadTarget {
    private var finished = false
    override suspend fun write(bytes: ByteArray, count: Int) {
        try { withContext(Dispatchers.IO) { check(!finished); require(count in 0..bytes.size); output.write(bytes, 0, count) } }
        catch (error: Throwable) { withContext(NonCancellable + Dispatchers.IO) { abort() }; throw error }
    }
    override suspend fun complete(): SavedDownload {
        check(!finished)
        try {
            return withContext(Dispatchers.IO) {
                check(!finished)
                output.close()
                publish().also { finished = true }
            }
        } catch (error: Throwable) {
            // O retorno ao chamador também pode ser cancelado depois da publicação.
            withContext(NonCancellable + Dispatchers.IO) { try { remove() } finally { abort() } }
            throw error
        }
    }
    override fun abort() {
        if (finished) return
        finished = true
        try { output.close() } finally { remove() }
    }
}

actual fun openDownload(file: SavedDownload) {
    val uri = Uri.parse(file.location)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime(file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    storageContext.startActivity(intent)
}
actual fun showDownloadFolder(file: SavedDownload) {
    error("A pasta Downloads não pode ser aberta nesta plataforma.")
}
