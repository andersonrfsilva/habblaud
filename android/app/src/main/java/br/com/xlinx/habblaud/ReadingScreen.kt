package br.com.xlinx.habblaud

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.launch

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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (state.connection) { "connected" -> R.string.reading_connected; "connecting" -> R.string.reading_connecting; else -> R.string.reading_offline }))
        state.error?.let { error -> Text(stringResource(when (error) { "changed" -> R.string.session_changed; "forbidden" -> R.string.terminal_blocked; "missing" -> R.string.reading_missing; "oversize" -> R.string.reading_oversize; else -> R.string.network_error }), color = MaterialTheme.colorScheme.error) }
        val selected = state.selected
        if (selected == null) {
            var query by remember { mutableStateOf("") }
            var recent by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { recent = false }) { Text(stringResource(R.string.open_sessions)) }
                Button(onClick = { recent = true }) { Text(stringResource(R.string.recent_sessions)) }
                TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_sessions)) }, singleLine = true)
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            val items = state.sessions.filter { (recent || it.agentId != null) && listOfNotNull(it.title, it.agentName, it.project, it.key.provider, it.key.account).any { value -> value.contains(query, true) } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.key.serialize() }) { session ->
                    Card(Modifier.fillMaxWidth().clickable { controller.open(session) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(session.project, session.key.provider, session.status, session.activity).joinToString(" · "))
                            if (session.permission) Text(stringResource(R.string.permission_pending), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::close) { Text(stringResource(R.string.back_sessions)) }
                Text(selected.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(listOfNotNull(selected.project, selected.key.provider, selected.status, selected.activity).joinToString(" · "))
            if (selected.agentId == null) Text(stringResource(R.string.history_read_only))
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            if (state.truncated) Text(stringResource(R.string.window_truncated))
            if (state.reduced) Text(stringResource(R.string.window_reduced))
            val results = state.entries.filter { it.kind == "result" }.groupBy { it.toolUseId }
            val tools = state.entries.filter { it.kind == "tool" }.map { it.id }.toSet()
            val listState = rememberLazyListState()
            var following by remember { mutableStateOf(true) }
            var newEntries by remember { mutableStateOf(false) }
            LaunchedEffect(listState) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }.collect { (last, total) -> if (total > 0) following = last != null && last >= total - 1 } }
            LaunchedEffect(state.entries.size) {
                if (state.entries.isNotEmpty()) {
                    if (following) listState.scrollToItem(state.entries.lastIndex) else newEntries = true
                }
            }
            if (newEntries && state.entries.isNotEmpty()) TextButton(onClick = { scope.launch { listState.scrollToItem(state.entries.lastIndex); newEntries = false } }) { Text(stringResource(R.string.new_entries)) }
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.entries, key = { it.id }) { entry ->
                    if (entry.kind != "result" || entry.toolUseId !in tools) EntryView(entry, results[entry.id].orEmpty())
                }
            }
            if (composer.key == selected.key && selected.agentId != null) {
                val agentId = selected.agentId
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val enabled = agentId != null && selected.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
                if (!state.messagesAllowed) Text(stringResource(R.string.messages_blocked))
                else if (!state.messageGuard) Text(stringResource(R.string.message_guard_required))
                else if (agentId != null && !selected.canMessage) Text(stringResource(R.string.message_unavailable))
                if (selected.key.provider == "omp") {
                    var expanded by remember { mutableStateOf(false) }
                    var showWarning by remember { mutableStateOf(false) }
                    val modes = listOf("aside" to R.string.mode_aside, "followUp" to R.string.mode_follow_up, "steer" to R.string.mode_steer)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Box {
                            TextButton(onClick = { expanded = true }) { Text(stringResource(modes.first { it.first == composer.draft.mode }.second)) }
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(composer.draft.text, { controller.messages.edit(text = it) }, Modifier.weight(1f), enabled = !composer.loading, label = { Text(stringResource(R.string.message_text)) }, supportingText = { Text("${composer.draft.text.length}/20000") }, maxLines = 3)
                    Button(onClick = { if (agentId != null) controller.messages.send(agentId) }, enabled = enabled && !composer.loading && !composer.sending && composer.draft.text.isNotBlank()) { Text(stringResource(R.string.send_message)) }
                }
                composer.operation?.let { operation -> Text(stringResource(when (operation.status) {
                    "queued" -> R.string.message_queued
                    "sent" -> R.string.message_sent
                    "delivered" -> R.string.message_delivered
                    "failed" -> R.string.message_failed
                    else -> R.string.message_unknown
                })) }
                composer.error?.let { error -> if (error != "confirm") Text(stringResource(when (error) { "storage" -> R.string.storage_error; "unknown" -> R.string.message_unknown; else -> R.string.message_send_error }), color = MaterialTheme.colorScheme.error) }
                if (composer.error == "confirm" && agentId != null) AlertDialog(
                    onDismissRequest = { controller.messages.dismissConfirmation() },
                    title = { Text(stringResource(R.string.message_retry_title)) },
                    text = { Text(stringResource(R.string.message_retry_warning)) },
                    confirmButton = { TextButton(onClick = { controller.messages.send(agentId, confirmed = true) }) { Text(stringResource(R.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { controller.messages.dismissConfirmation() }) { Text(stringResource(R.string.cancel)) } }
                )
                }
            }
        }
    }
}

@Composable
private fun EntryView(entry: TerminalEntry, results: List<TerminalEntry>) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val label = when (entry.kind) { "unknown" -> stringResource(R.string.unknown_entry); "thinking" -> stringResource(R.string.thinking); else -> entry.title ?: entry.kind }
            if (entry.kind == "tool" || entry.kind == "thinking") {
                TextButton(onClick = { expanded = !expanded }) { Text(label) }
                if (expanded) SelectionContainer { Column { Text(entry.text, fontFamily = FontFamily.Monospace); results.forEach { Text(stringResource(R.string.tool_result)); Text(it.text, fontFamily = FontFamily.Monospace); if (it.truncated) Text(stringResource(R.string.result_truncated)) } } }
            } else {
                Text(label, style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(entry.text, fontFamily = if (entry.kind == "result") FontFamily.Monospace else FontFamily.Default) }
                if (entry.truncated) Text(stringResource(R.string.result_truncated))
            }
        }
    }
}
