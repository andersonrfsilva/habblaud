package br.com.xlinx.habblaud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeliveriesSheet(api: HabblaudApi, selected: SessionItem, onUnauthorized: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val downloads = remember { mutableStateListOf<FileDownload>() }
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    LaunchedEffect(api, selected.key, refresh) {
        loading = true
        error = null
        try {
            deliveries = deliveriesOf(api.deliveries(), selected.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = (e as? ApiException)?.status
            if (status == 401) onUnauthorized()
            error = if (status == 403) Res.string.deliveries_blocked else Res.string.deliveries_error
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DownloadPermissionGate { allowed, request ->
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().padding(horizontal = 16.dp).testTag("deliveries-sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.deliveries_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text(stringResource(Res.string.files_close)) }
                }
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(Res.string.deliveries_refresh)) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val failure = error
                    val statusModifier = Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }
                    when {
                        loading -> Column(statusModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(stringResource(Res.string.deliveries_loading))
                        }
                        failure != null -> Text(stringResource(failure), statusModifier, color = MaterialTheme.colorScheme.error)
                        deliveries.isEmpty() -> Text(stringResource(Res.string.deliveries_empty), statusModifier)
                        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(deliveries, key = { it.id }) { delivery ->
                                val expanded = expandedId == delivery.id
                                DeliveryRow(delivery, expanded, { expandedId = if (expanded) null else delivery.id }) { attachment ->
                                    TextButton(
                                        enabled = downloads.none { it.key == attachment.id && it.running },
                                        onClick = {
                                            if (!allowed) {
                                                request()
                                            } else {
                                                val item = FileDownload(attachment.id, attachment.name)
                                                downloads.add(0, item)
                                                scope.startFileDownload(item, onUnauthorized) { write, progress ->
                                                    api.downloadDelivery(attachment.id, write, progress)
                                                }
                                            }
                                        },
                                    ) { Text(stringResource(Res.string.deliveries_download)) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                FileDownloads(downloads)
            }
        }
    }
}

@Composable
private fun DeliveryRow(
    delivery: Delivery,
    expanded: Boolean,
    onToggle: () -> Unit,
    downloadButton: @Composable (DeliveryAttachment) -> Unit,
) {
    val action = stringResource(if (expanded) Res.string.collapse else Res.string.view_all)
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = action, onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(delivery.headline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(platformEntryTime(delivery.finishedAt, uiNowMillis()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            delivery.summary?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            val count = delivery.attachments.size
            if (count > 0) Text(pluralStringResource(Res.plurals.deliveries_files, count, count), style = MaterialTheme.typography.bodySmall)
        }
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
    if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.deliveries_request), style = MaterialTheme.typography.titleSmall)
        Text(delivery.request)
        SelectionContainer { Text(delivery.text) }
        delivery.attachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(attachment.name)
                    attachment.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                downloadButton(attachment)
            }
        }
    }
}
