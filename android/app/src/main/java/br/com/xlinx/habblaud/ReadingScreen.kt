package br.com.xlinx.habblaud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(api: HabblaudApi, store: LocalStateStore, onUnauthorized: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(api) { ReadingController(api, store, scope, onUnauthorized) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.start()
            if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val composer by controller.messages.state.collectAsStateWithLifecycle()
    var permissionSession by remember { mutableStateOf<SessionItem?>(null) }
    val permissionLive = permissionSession?.let { requested -> state.sessions.find { it.key == requested.key && it.permissionId == requested.permissionId } }
    LaunchedEffect(permissionSession, permissionLive, state.connection) {
        if (permissionSession != null && permissionLive == null && state.connection == "connected") permissionSession = null
    }
    permissionSession?.let { requested ->
        PermissionSheet(api, permissionLive ?: requested, state.permissionsAllowed, state.connection == "connected", onUnauthorized) { permissionSession = null }
    }
    var pickerKey by remember { mutableStateOf<SessionKey?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (pickerKey == controller.messages.state.value.key) controller.messages.select(uris)
        pickerKey = null
    }
    val selected = state.selected
    var menuExpanded by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(selected?.title ?: stringResource(R.string.app_name), maxLines = 1)
                    Text(if (selected == null) stringResource(when (state.connection) { "connected" -> R.string.reading_connected; "connecting" -> R.string.reading_connecting; else -> R.string.reading_offline }) else listOfNotNull(selected.project?.substringAfterLast('/'), statusLabel(selected.status ?: selected.activity)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            },
            navigationIcon = {
                if (selected != null) {
                    val backLabel = stringResource(R.string.back_sessions)
                    IconButton(onClick = controller::close, modifier = Modifier.semantics { contentDescription = backLabel }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                }
            },
            actions = {
                if (selected == null) Box {
                    val menuLabel = stringResource(R.string.menu)
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) { Text("⋮", style = MaterialTheme.typography.headlineMedium) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.logout)) }, onClick = { menuExpanded = false; onLogout() })
                    }
                }
            }
        )
    }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let { error -> Text(stringResource(when (error) { "changed" -> R.string.session_changed; "forbidden" -> R.string.terminal_blocked; "missing" -> R.string.reading_missing; "oversize" -> R.string.reading_oversize; "vpn_required" -> R.string.vpn_required; else -> R.string.network_error }), color = MaterialTheme.colorScheme.error) }
        if (selected == null) {
            var query by remember { mutableStateOf("") }
            var recent by remember { mutableStateOf(false) }
            TabRow(selectedTabIndex = if (recent) 1 else 0) {
                Tab(selected = !recent, onClick = { recent = false }, text = { Text(stringResource(R.string.open_sessions)) })
                Tab(selected = recent, onClick = { recent = true }, text = { Text(stringResource(R.string.recent_sessions)) })
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_sessions)) }, singleLine = true)
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            val items = state.sessions.filter { (recent || it.agentId != null) && listOfNotNull(it.title, it.agentName, it.project, it.key.provider, it.key.account).any { value -> value.contains(query, true) } }
            if (items.isEmpty()) Text(stringResource(R.string.sessions_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.key.serialize() }) { session ->
                    Row(Modifier.fillMaxWidth().clickable { controller.open(session) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val avatarColors = listOf(Color(0xFFD3EEE3), Color(0xFFDCE7F7), Color(0xFFF7E4D6), Color(0xFFEAE0F4), Color(0xFFF5EABF))
                        Box(Modifier.size(48.dp).background(avatarColors[(session.title.hashCode() and Int.MAX_VALUE) % avatarColors.size], CircleShape), contentAlignment = Alignment.Center) {
                            Text(session.title.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(session.project?.substringAfterLast('/'), statusLabel(session.status ?: session.activity)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            if (session.permission) TextButton(onClick = { permissionSession = session }) { Text(stringResource(R.string.permission_pending), color = MaterialTheme.colorScheme.error) }
                        }
                        session.lastAt?.let { Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(1.dp))
            if (selected.permission) TextButton(onClick = { permissionSession = selected }) { Text(stringResource(R.string.permission_pending)) }
            if (selected.agentId == null) Text(stringResource(R.string.history_read_only))
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            state.readingError?.let { error ->
                Text(stringResource(when (error) { "forbidden" -> R.string.terminal_blocked; "missing" -> R.string.reading_missing; "oversize" -> R.string.reading_oversize; "vpn_required" -> R.string.vpn_required; else -> R.string.reading_offline }), color = MaterialTheme.colorScheme.error)
            }
            if (state.truncated) Text(stringResource(R.string.window_truncated))
            if (state.reduced) Text(stringResource(R.string.window_reduced))
            val results = state.entries.filter { it.kind == "result" }.groupBy { it.toolUseId }
            val tools = state.entries.filter { it.kind == "tool" }.map { it.id }.toSet()
            val listState = rememberLazyListState()
            var following by remember { mutableStateOf(true) }
            var newEntries by remember { mutableStateOf(false) }
            var initialScroll by remember(selected.key) { mutableStateOf(false) }
            LaunchedEffect(listState) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }.collect { (last, total) -> if (total > 0) following = last != null && last >= total - 1 } }
            LaunchedEffect(state.entries.size) {
                if (state.entries.isNotEmpty()) {
                    if (!initialScroll || following) {
                        listState.scrollToItem(state.entries.lastIndex)
                        initialScroll = true
                        newEntries = false
                    } else newEntries = true
                } else initialScroll = false
            }
            if (!following && initialScroll && state.entries.isNotEmpty()) TextButton(onClick = { scope.launch { listState.scrollToItem(state.entries.lastIndex); newEntries = false } }) { Text(stringResource(if (newEntries) R.string.new_entries else R.string.go_to_end)) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFF0F5F1), RoundedCornerShape(12.dp)).padding(8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                items(state.entries, key = { it.id }) { entry ->
                    if (entry.kind != "result" || entry.toolUseId !in tools) EntryView(entry, results[entry.id].orEmpty())
                }
            }
            if (composer.key == selected.key && selected.agentId != null) {
                val agentId = selected.agentId
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val enabled = agentId != null && selected.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
                composer.progress?.let { Text(stringResource(R.string.attachment_progress, it), style = MaterialTheme.typography.bodySmall) }
                if (!state.messagesAllowed) Text(stringResource(R.string.messages_blocked))
                else if (!state.messageGuard) Text(stringResource(R.string.message_guard_required))
                else if (agentId != null && !selected.canMessage) Text(stringResource(R.string.message_unavailable))
                if (selected.key.provider == "omp") {
                    if (selected.status in setOf("working", "busy", "shell") && !composer.sending) TextButton(onClick = { controller.messages.interrupt(agentId!!) }, enabled = enabled && !composer.loading && composer.control?.status !in setOf("queued", "sent")) { Text(stringResource(R.string.stop_omp)) }
                    composer.control?.let { control -> Text(stringResource(when (control.status) {
                        "queued", "sent" -> R.string.stop_pending
                        "delivered" -> R.string.stop_ack
                        "failed" -> R.string.stop_failed
                        else -> R.string.stop_unknown
                    })) }
                    (composer.control?.error ?: composer.controlError)?.takeIf { it != "unknown" && it != "failed" && it != "confirm" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (composer.controlError == "failed") Text(stringResource(R.string.stop_failed), color = MaterialTheme.colorScheme.error)
                    if (composer.controlError == "confirm") AlertDialog(
                        onDismissRequest = { controller.messages.dismissControlConfirmation() },
                        title = { Text(stringResource(R.string.stop_omp)) },
                        text = { Text(stringResource(R.string.stop_unknown)) },
                        confirmButton = { TextButton(onClick = { controller.messages.interrupt(agentId!!, confirmed = true) }) { Text(stringResource(R.string.confirm)) } },
                        dismissButton = { TextButton(onClick = { controller.messages.dismissControlConfirmation() }) { Text(stringResource(R.string.cancel)) } }
                    )
                    var expanded by remember { mutableStateOf(false) }
                    var showWarning by remember { mutableStateOf(false) }
                    val modes = listOf("aside" to R.string.mode_aside, "followUp" to R.string.mode_follow_up, "steer" to R.string.mode_steer)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Box {
                            AssistChip(onClick = { expanded = true }, label = { Text(stringResource(modes.first { it.first == composer.draft.mode }.second)) })
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                modes.forEach { (mode, label) ->
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { controller.messages.edit(mode = mode); expanded = false })
                                }
                            }
                        }
                        TextButton(onClick = { showWarning = true }) { Text(stringResource(R.string.omp_warning_title)) }
                    }
                    if (showWarning) AlertDialog(onDismissRequest = { showWarning = false }, title = { Text(stringResource(R.string.omp_warning_title)) }, text = { Text(stringResource(R.string.omp_warning)) }, confirmButton = { TextButton(onClick = { showWarning = false }) { Text(stringResource(R.string.confirm)) } })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    composer.draft.attachments.forEach { attachment ->
                        val removeLabel = stringResource(R.string.attachment_remove)
                        InputChip(selected = false, onClick = { controller.messages.remove(attachment) }, label = { Text(attachment.name, maxLines = 1) }, trailingIcon = { Text("×", Modifier.semantics { contentDescription = removeLabel }) })
                        if (attachment.uri in composer.unavailable) Text(stringResource(R.string.attachment_reselect), color = MaterialTheme.colorScheme.error)
                        else if (!attachment.persistent) Text(stringResource(R.string.attachment_temporary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                composer.operation?.let { operation -> Text(stringResource(when (operation.status) {
                    "queued" -> R.string.message_queued
                    "sent" -> R.string.message_sent
                    "delivered" -> R.string.message_delivered
                    "failed" -> R.string.message_failed
                    else -> R.string.message_unknown
                }), style = MaterialTheme.typography.bodySmall) }
                composer.error?.let { error -> if (error != "confirm") Text(stringResource(when (error) { "storage" -> R.string.storage_error; "unknown" -> R.string.message_unknown; "attachment" -> R.string.attachment_error; "attachments_expired" -> R.string.attachments_expired; else -> R.string.message_send_error }), color = MaterialTheme.colorScheme.error) }
                if (composer.error == "confirm" && agentId != null) AlertDialog(
                    onDismissRequest = { controller.messages.dismissConfirmation() },
                    title = { Text(stringResource(R.string.message_retry_title)) },
                    text = { Text(stringResource(R.string.message_retry_warning)) },
                    confirmButton = { TextButton(onClick = { controller.messages.send(agentId, confirmed = true) }) { Text(stringResource(R.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { controller.messages.dismissConfirmation() }) { Text(stringResource(R.string.cancel)) } }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val attachLabel = stringResource(R.string.attach_files)
                    IconButton(onClick = { pickerKey = selected.key; picker.launch(arrayOf("*/*")) }, enabled = !composer.loading && composer.draft.attachments.size < MAX_ATTACHMENTS, modifier = Modifier.semantics { contentDescription = attachLabel }) { Text("📎", style = MaterialTheme.typography.titleLarge) }
                    OutlinedTextField(composer.draft.text, { controller.messages.edit(text = it) }, Modifier.weight(1f), enabled = !composer.loading, label = { Text(stringResource(R.string.message_text)) }, supportingText = if (composer.draft.text.length >= 18000) { { Text("${composer.draft.text.length}/20000") } } else null, maxLines = 6, shape = RoundedCornerShape(24.dp))
                    val sendLabel = stringResource(R.string.send_message)
                    FilledIconButton(onClick = { if (agentId != null) controller.messages.send(agentId) }, enabled = enabled && !composer.loading && !composer.sending && composer.unavailable.isEmpty() && (composer.draft.text.isNotBlank() || composer.draft.attachments.isNotEmpty()), modifier = Modifier.semantics { contentDescription = sendLabel }) { Text("➤") }
                }
                }
            }
        }
    }
    }
}

@Composable
private fun statusLabel(value: String?): String? = when (value?.lowercase()) {
    "idle" -> stringResource(R.string.status_idle)
    "working", "busy", "running" -> stringResource(R.string.status_working)
    "waiting" -> stringResource(R.string.status_waiting)
    "offline", "closed", "stopped" -> stringResource(R.string.status_closed)
    else -> null
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> stringResource(R.string.time_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes)
        minutes < 1440 -> stringResource(R.string.time_hours, minutes / 60)
        else -> stringResource(R.string.time_days, minutes / 1440)
    }
}

@Composable
private fun EntryView(entry: TerminalEntry, results: List<TerminalEntry>) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    if (entry.kind == "system") {
        SelectionContainer { Text(entry.text, Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.kind == "user") Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = if (entry.kind == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(12.dp)) {
                if (entry.kind == "tool" || entry.kind == "thinking") {
                    val label = if (entry.kind == "thinking") stringResource(R.string.thinking) else entry.title ?: stringResource(R.string.tool_result)
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.kind == "tool") Text("⚙", color = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                    if (expanded) SelectionContainer {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(entry.text, fontFamily = FontFamily.Monospace)
                            results.forEach {
                                Text(stringResource(R.string.tool_result))
                                Text(it.text, fontFamily = FontFamily.Monospace)
                                if (it.truncated) Text(stringResource(R.string.result_truncated))
                            }
                        }
                    }
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            entry.text.split("```").forEachIndexed { index, part ->
                                if (index % 2 == 0) {
                                    if (part.isNotEmpty()) Text(part)
                                } else {
                                    Text(part.substringAfter('\n', part).trimEnd(), Modifier.fillMaxWidth().background(Color(0xFFF0F3F1), RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(8.dp), fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    if (entry.truncated) Text(stringResource(R.string.result_truncated))
                }
            }
        }
    }
}
