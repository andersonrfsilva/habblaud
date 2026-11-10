package br.com.xlinx.habblaud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

private class FileDownload(val path: String, val name: String) {
    var received by mutableStateOf(0L)
    var total by mutableStateOf<Long?>(null)
    var saved by mutableStateOf<SavedDownload?>(null)
    var error by mutableStateOf<String?>(null)
    var cancelled by mutableStateOf(false)
    var job: Job? = null
    val running get() = saved == null && error == null && !cancelled
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesSheet(api: HabblaudApi, selected: SessionItem?, onUnauthorized: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<FileRoot>>(emptyList()) }
    var listing by remember { mutableStateOf<FileListing?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var forbidden by remember { mutableStateOf(false) }
    var rootMenu by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val downloads = remember { mutableStateListOf<FileDownload>() }
    fun failed(e: Exception) {
        if (e is ApiException && e.status == 401) onUnauthorized()
        forbidden = e is ApiException && e.status == 403
        error = (e as? ApiException)?.detail ?: e.message.orEmpty()
    }
    LaunchedEffect(api, retry) {
        loading = true; error = null; forbidden = false
        try {
            roots = api.filesRoots()
            if (path == null) path = initialFileRoot(roots, selected?.roomId, selected?.project)?.path
        } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
        finally { loading = false }
    }
    LaunchedEffect(path, retry) {
        val target = path ?: return@LaunchedEffect
        loading = true; error = null; forbidden = false; listing = null
        try { listing = api.filesList(target) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { failed(e) }
        finally { loading = false }
    }
    fun download(entry: FileEntry, parent: String) {
        val item = FileDownload(parent.trimEnd('/') + "/" + entry.name, entry.name)
        downloads.add(0, item)
        item.job = scope.launch {
            var destination: DownloadTarget? = null
            try {
                val target = createDownload(entry.name)
                destination = target
                api.downloadFile(item.path, target::write) { received, total -> item.received = received; item.total = total }
                currentCoroutineContext().ensureActive()
                item.saved = target.complete()
                destination = null
            } catch (e: CancellationException) { item.cancelled = true; throw e }
            catch (e: Exception) {
                if (e is ApiException && e.status == 401) onUnauthorized()
                item.error = (e as? ApiException)?.detail ?: e.message.orEmpty()
            } finally { destination?.let { withContext(NonCancellable + Dispatchers.Default) { it.abort() } } }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DownloadPermissionGate { allowed, request ->
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().padding(horizontal = 16.dp).testTag("files-sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.files_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text(stringResource(Res.string.files_close)) }
                }
                Box {
                    TextButton(onClick = { rootMenu = true }, enabled = roots.isNotEmpty()) {
                        Text(roots.firstOrNull { it.path == listing?.root || it.path == path }?.label ?: stringResource(Res.string.files_root))
                    }
                    DropdownMenu(expanded = rootMenu, onDismissRequest = { rootMenu = false }) {
                        roots.forEach { root -> DropdownMenuItem(text = { Column { Text(root.label); Text(root.path, style = MaterialTheme.typography.bodySmall) } }, onClick = { path = root.path; rootMenu = false }) }
                    }
                }
                listing?.let { current ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { path = current.root }) { Text(roots.firstOrNull { it.path == current.root }?.label ?: current.root) }
                        fileBreadcrumbs(current.root, current.path).forEach { (name, target) ->
                            Text("/")
                            TextButton(onClick = { path = target }) { Text(name) }
                        }
                    }
                    if (current.path != current.root) TextButton(onClick = { path = current.path.substringBeforeLast('/').ifEmpty { "/" } }, modifier = Modifier.testTag("files-parent")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        Text(stringResource(Res.string.files_parent))
                    }
                }
                if (!allowed) TextButton(onClick = request) { Text(stringResource(Res.string.files_download)) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text(stringResource(Res.string.files_loading)) }
                        error != null -> Column(Modifier.align(Alignment.Center)) {
                            Text(if (forbidden) stringResource(Res.string.files_disabled) else stringResource(Res.string.files_error, error.orEmpty()), Modifier.testTag("files-error"), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { retry++ }) { Text(stringResource(Res.string.files_open)) }
                        }
                        roots.isEmpty() -> Text(stringResource(Res.string.files_no_roots), Modifier.align(Alignment.Center))
                        listing?.entries?.isEmpty() == true -> Text(stringResource(Res.string.files_empty), Modifier.align(Alignment.Center))
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(listing?.entries.orEmpty().sortedWith(compareBy<FileEntry> { it.type != "dir" }.thenBy { it.name.lowercase() }), key = { it.name }) { entry ->
                                val directory = entry.type == "dir"
                                Row(Modifier.fillMaxWidth().testTag("files-entry:${entry.name}").then(if (directory) Modifier.clickable { path = listing!!.path.trimEnd('/') + "/" + entry.name } else Modifier).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (directory) Icon(Icons.Default.Folder, stringResource(Res.string.files_root))
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(if (directory) null else fileSize(entry.size), entryTime(entry.mtime)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (!directory) TextButton(onClick = { download(entry, listing!!.path) }, enabled = allowed && downloads.none { it.path == listing!!.path.trimEnd('/') + "/" + entry.name && it.running }, modifier = Modifier.testTag("files-download:${entry.name}")) { Text(stringResource(Res.string.files_download)) }
                                }
                            }
                            if (listing?.truncated == true) item { Text(stringResource(Res.string.files_truncated, listing!!.entries.size)) }
                        }
                    }
                }
                if (downloads.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(Res.string.files_downloads), style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(downloads) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                when {
                                    item.saved != null -> {
                                        Text(stringResource(Res.string.files_completed), Modifier.testTag("files-completed:${item.name}"), style = MaterialTheme.typography.bodySmall)
                                        Row {
                                            TextButton(onClick = { try { openDownload(item.saved!!) } catch (e: Exception) { item.error = e.message.orEmpty() } }) { Text(stringResource(Res.string.files_open)) }
                                            if (canShowDownloadFolder) TextButton(onClick = { try { showDownloadFolder(item.saved!!) } catch (e: Exception) { item.error = e.message.orEmpty() } }) { Text(stringResource(Res.string.files_show_folder)) }
                                        }
                                    }
                                    item.cancelled -> Text(stringResource(Res.string.files_cancelled))
                                    item.error == null -> {
                                        val total = item.total
                                        if (total != null && total > 0) LinearProgressIndicator(progress = { (item.received.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${stringResource(Res.string.files_downloading)} ${fileSize(item.received)}${total?.let { " / ${fileSize(it)}" }.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = { item.job?.cancel() }) { Text(stringResource(Res.string.cancel)) }
                                        }
                                    }
                                }
                                item.error?.let { Text(stringResource(Res.string.files_error, it), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
