package br.com.xlinx.habblaud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.xlinx.habblaud.data.*
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(api: HabblaudApi, store: LocalStateStore, onUnauthorized: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(api) { ReadingController(api, store, scope, onUnauthorized) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pending by MainActivity.pendingSession.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { controller.openKey(it); MainActivity.pendingSession.value = null } }
    var notificationsOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { notificationsOn = store.notificationsEnabled() }
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) NotificationService.start(context)
        else { notificationsOn = false; scope.launch { store.setNotificationsEnabled(false) } }
    }
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
    // O que a conversa mostra: tudo, só as mensagens (suas e do agente), ou sem ferramentas (e seus resultados).
    var view by rememberSaveable { mutableStateOf("all") }
    BackHandler(enabled = selected != null && permissionSession == null && !menuExpanded) { controller.close() }
    CastActions(api, selected, state.connection == "connected" && (state.terminalAllowed || selected?.permission == true), state.sessions.filter { it.agentId != null }.mapNotNull { it.cast }.toSet(), onUnauthorized, { permissionSession = selected }, menu = { close ->
        listOf("all" to R.string.view_all, "prompts" to R.string.view_prompts, "no_tools" to R.string.view_no_tools).forEach { (value, label) ->
            DropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { RadioButton(selected = view == value, onClick = null) }, onClick = { view = value; close() })
        }
    }) { castActions, castFab, castStatus ->
    Scaffold(floatingActionButton = { castFab() }, topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selected != null) CharacterAvatar(api, selected, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(selected?.title ?: selected?.characterName ?: stringResource(R.string.app_name), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected == null) {
                                val statusLabel = stringResource(when (state.connection) { "connected" -> R.string.reading_connected; "connecting" -> R.string.reading_connecting; else -> R.string.reading_offline })
                                val color = when (state.connection) { "connected" -> Color(0xFF2E7D32); "connecting" -> Color(0xFFEF6C00); else -> Color(0xFFC62828) }
                                Box(Modifier.size(10.dp).background(color, CircleShape).semantics { contentDescription = statusLabel })
                            }
                        }
                        if (selected != null) {
                            val projectStatus = listOfNotNull(selected.project?.substringAfterLast('/'), statusLabel(selected.status ?: selected.activity)).joinToString(" · ")
                            val subtitle = listOfNotNull(selected.characterName.takeIf { it != null && it != selected.title }, projectStatus.takeIf { it.isNotEmpty() }).joinToString(" · ")
                            Text(subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            navigationIcon = {
                if (selected != null) {
                    val backLabel = stringResource(R.string.back_sessions)
                    IconButton(onClick = controller::close, modifier = Modifier.semantics { contentDescription = backLabel }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                }
            },
            actions = {
                if (selected != null) castActions()
                if (selected == null) Box {
                    val menuLabel = stringResource(R.string.menu)
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) { Text("⋮", style = MaterialTheme.typography.headlineMedium) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        notificationsOn?.let { on ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.notifications)) }, trailingIcon = { Switch(checked = on, onCheckedChange = null) }, onClick = {
                                notificationsOn = !on
                                scope.launch { store.setNotificationsEnabled(!on) }
                                when {
                                    on -> NotificationService.stop(context)
                                    NotificationService.allowed(context) -> NotificationService.start(context)
                                    android.os.Build.VERSION.SDK_INT >= 33 -> askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.background_settings)) }, onClick = {
                            menuExpanded = false
                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.logout)) }, onClick = { menuExpanded = false; onLogout() })
                    }
                }
            }
        )
    }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).imePadding().background(if (selected == null) MaterialTheme.colorScheme.background else if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let { error -> Text(stringResource(when (error) { "changed" -> R.string.session_changed; "forbidden" -> R.string.terminal_blocked; "missing" -> R.string.reading_missing; "oversize" -> R.string.reading_oversize; else -> R.string.network_error }), color = MaterialTheme.colorScheme.error) }
        castStatus()
        if (selected == null) {
            var query by remember { mutableStateOf("") }
            var recent by remember { mutableStateOf(false) }
            TabRow(selectedTabIndex = if (recent) 1 else 0) {
                Tab(selected = !recent, onClick = { recent = false }, text = { Text(stringResource(R.string.open_sessions)) })
                Tab(selected = recent, onClick = { recent = true }, text = { Text(stringResource(R.string.recent_sessions)) })
            }
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            val items = state.sessions.filter { (recent || it.agentId != null) && listOfNotNull(it.title, it.characterName, it.agentName, it.project, it.key.provider, it.key.account).any { value -> value.contains(query, true) } }
            // A busca é o primeiro item e começa fora da tela: aparece ao puxar a lista para baixo no topo.
            val sessionsList = rememberLazyListState(initialFirstVisibleItemIndex = 1)
            val density = androidx.compose.ui.platform.LocalDensity.current
            // Lista curta não rola: um espaço no fim completa a altura para a busca caber acima da tela.
            val fillerPx by remember { derivedStateOf {
                val info = sessionsList.layoutInfo
                val content = info.visibleItemsInfo.filter { it.key != "search" && it.key != "filler" }.sumOf { it.size + with(density) { 8.dp.roundToPx() } }
                (info.viewportSize.height - content).coerceAtLeast(0)
            } }
            LaunchedEffect(recent) { if (query.isEmpty()) { withFrameNanos { }; sessionsList.scrollToItem(1) } }
            LazyColumn(state = sessionsList, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "search") { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_sessions)) }, singleLine = true) }
                if (items.isEmpty()) item(key = "empty") { Text(stringResource(R.string.sessions_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge) }
                items(items, key = { it.key.serialize() }) { session ->
                    Row(Modifier.fillMaxWidth().clickable { controller.open(session) }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(api, session, 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (session.characterName != null && session.title != session.characterName) Text(session.characterName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(session.project?.substringAfterLast('/'), statusLabel(session.status ?: session.activity)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            if (session.permission) TextButton(onClick = { permissionSession = session }) { Text(stringResource(R.string.permission_pending), color = MaterialTheme.colorScheme.error) }
                        }
                        session.lastAt?.let { Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item(key = "filler") { Spacer(Modifier.height(with(density) { fillerPx.toDp() })) }
            }
        } else {
            Spacer(Modifier.height(1.dp))
            if (selected.agentId == null) Text(stringResource(R.string.history_read_only))
            if (!state.terminalAllowed) Text(stringResource(R.string.terminal_blocked))
            state.readingError?.let { error ->
                Text(stringResource(when (error) { "forbidden" -> R.string.terminal_blocked; "missing" -> R.string.reading_missing; "oversize" -> R.string.reading_oversize; else -> R.string.reading_offline }), color = MaterialTheme.colorScheme.error)
            }
            if (state.reduced) Text(stringResource(R.string.window_reduced))
            val results = state.entries.filter { it.kind == "result" }.groupBy { it.toolUseId }
            val tools = state.entries.filter { it.kind == "tool" }.map { it.id }.toSet()
            val listState = rememberLazyListState()
            var following by remember { mutableStateOf(true) }
            var userScrolled by remember(selected.key) { mutableStateOf(false) }
            var unread by remember(selected.key) { mutableIntStateOf(0) }
            var previousCount by remember(selected.key) { mutableIntStateOf(0) }
            var previousLastId by remember(selected.key) { mutableStateOf<String?>(null) }
            var initialScroll by remember(selected.key) { mutableStateOf(false) }
            val dragging by listState.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(dragging) { if (dragging && initialScroll) userScrolled = true }
            LaunchedEffect(listState) {
                snapshotFlow {
                    val layout = listState.layoutInfo
                    Triple(layout.visibleItemsInfo.lastOrNull()?.let { it.index to it.offset + it.size }, layout.totalItemsCount, layout.viewportEndOffset)
                }.collect { (last, total, end) ->
                    if (total > 0) {
                        following = last != null && last.first >= total - 1 && last.second <= end
                        if (following && !dragging) { userScrolled = false; unread = 0 }
                        else if (initialScroll && !userScrolled && !dragging && last != null) {
                            withFrameNanos { }
                            if (!userScrolled && !dragging) {
                                val layout = listState.layoutInfo
                                val current = layout.visibleItemsInfo.lastOrNull()
                                if (current != null && current.index < layout.totalItemsCount - 1) listState.scrollToItem(layout.totalItemsCount - 1)
                                else if (current != null && current.offset + current.size > layout.viewportEndOffset) listState.scrollBy((current.offset + current.size - layout.viewportEndOffset).toFloat())
                            }
                        }
                    }
                }
            }
            val shown = visibleEntries(state.entries, view)
            val rows = groupThinking(shown)
            val lastId = shown.lastOrNull()?.id
            // Ao enviar, volta a seguir o fim: o prompt e a resposta que chegam aparecem sem rolar à mão.
            fun stickToEnd() {
                userScrolled = false; unread = 0
                if (rows.isNotEmpty()) scope.launch { listState.scrollToItem(rows.lastIndex + if (state.truncated) 1 else 0) }
            }
            LaunchedEffect(shown.size, lastId, state.truncated) {
                val count = rows.size
                if (count > 0) {
                    if (!initialScroll || !userScrolled || following) {
                        listState.scrollToItem(count - 1 + if (state.truncated) 1 else 0)
                        initialScroll = true
                        unread = 0
                    } else {
                        val previousIndex = shown.indexOfLast { it.id == previousLastId }
                        unread += if (previousIndex >= 0) shown.size - previousIndex - 1 else (shown.size - previousCount).coerceAtLeast(1)
                    }
                } else {
                    initialScroll = false
                    unread = 0
                }
                previousCount = shown.size
                previousLastId = lastId
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                    if (state.truncated) item {
                        Text(stringResource(R.string.window_truncated), Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(rows, key = { it.first.id }) { (entry, thoughts) ->
                        if (entry.kind != "result" || entry.toolUseId !in tools) EntryView(entry, results[entry.id].orEmpty(), thoughts)
                    }
                }
                if (userScrolled && !following && initialScroll && rows.isNotEmpty()) {
                    val label = stringResource(R.string.go_to_end)
                    SmallFloatingActionButton(onClick = { userScrolled = false; unread = 0; scope.launch { listState.scrollToItem(rows.lastIndex + if (state.truncated) 1 else 0) } }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).semantics { contentDescription = label }, shape = CircleShape) {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Text("↓", style = MaterialTheme.typography.titleLarge) }
                    }
                }
            }
            if (composer.key == selected.key && selected.agentId != null) {
                val agentId = selected.agentId
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val enabled = agentId != null && selected.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
                composer.progress?.let { Text(stringResource(R.string.attachment_progress, it), style = MaterialTheme.typography.bodySmall) }
                if (!state.messagesAllowed) ChatBanner(stringResource(R.string.messages_blocked))
                else if (!state.messageGuard) ChatBanner(stringResource(R.string.message_guard_required))
                else if (agentId != null && !selected.canMessage) ChatBanner(stringResource(R.string.message_unavailable))
                if (selected.key.provider == "omp") {
                    composer.control?.takeUnless { it.status == "delivered" }?.let { control -> Text(stringResource(when (control.status) {
                        "queued", "sent" -> R.string.stop_pending
                        "failed" -> R.string.stop_failed
                        else -> R.string.stop_unknown
                    })) }
                    (composer.control?.error ?: composer.controlError)?.takeIf { it != "unknown" && it != "failed" && it != "confirm" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (composer.controlError == "failed") Text(stringResource(R.string.stop_failed), color = MaterialTheme.colorScheme.error)
                    if (composer.controlError == "confirm") AlertDialog(
                        onDismissRequest = { controller.messages.dismissControlConfirmation() },
                        title = { Text(stringResource(R.string.stop_agent)) },
                        text = { Text(stringResource(R.string.stop_unknown)) },
                        confirmButton = { TextButton(onClick = { controller.messages.interrupt(agentId!!, confirmed = true) }) { Text(stringResource(R.string.confirm)) } },
                        dismissButton = { TextButton(onClick = { controller.messages.dismissControlConfirmation() }) { Text(stringResource(R.string.cancel)) } }
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    composer.draft.attachments.forEach { attachment ->
                        val removeLabel = stringResource(R.string.attachment_remove)
                        InputChip(selected = false, onClick = { controller.messages.remove(attachment) }, label = { Text(attachment.name, maxLines = 1) }, trailingIcon = { Text("×", Modifier.semantics { contentDescription = removeLabel }) })
                        if (attachment.uri in composer.unavailable) Text(stringResource(R.string.attachment_reselect), color = MaterialTheme.colorScheme.error)
                        else if (!attachment.persistent) Text(stringResource(R.string.attachment_temporary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                composer.operation?.takeUnless { it.status == "delivered" }?.let { operation -> ChatBanner(stringResource(when (operation.status) {
                    "queued" -> R.string.message_queued
                    "sent" -> R.string.message_sent
                    "failed" -> R.string.message_failed
                    else -> R.string.message_unknown
                })) }
                composer.error?.let { error -> if (error != "confirm") Text(stringResource(when (error) { "storage" -> R.string.storage_error; "unknown" -> R.string.message_unknown; "attachment" -> R.string.attachment_error; "attachments_expired" -> R.string.attachments_expired; else -> R.string.message_send_error }), color = MaterialTheme.colorScheme.error) }
                if (composer.error == "confirm" && agentId != null) AlertDialog(
                    onDismissRequest = { controller.messages.dismissConfirmation() },
                    title = { Text(stringResource(R.string.message_retry_title)) },
                    text = { Text(stringResource(R.string.message_retry_warning)) },
                    confirmButton = { TextButton(onClick = { stickToEnd(); controller.messages.send(agentId, confirmed = true) }) { Text(stringResource(R.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { controller.messages.dismissConfirmation() }) { Text(stringResource(R.string.cancel)) } }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val attachLabel = stringResource(R.string.attach_files)
                    IconButton(onClick = { pickerKey = selected.key; picker.launch(arrayOf("*/*")) }, enabled = !composer.loading && composer.draft.attachments.size < MAX_ATTACHMENTS, modifier = Modifier.semantics { contentDescription = attachLabel }) { Text("📎", style = MaterialTheme.typography.titleLarge) }
                    OutlinedTextField(composer.draft.text, { controller.messages.edit(text = it) }, Modifier.weight(1f), enabled = !composer.loading, label = { Text(stringResource(R.string.message_text)) }, supportingText = if (composer.draft.text.length >= 18000) { { Text("${composer.draft.text.length}/20000") } } else null, maxLines = 6, shape = RoundedCornerShape(24.dp))
                    if (selected.key.provider == "omp" && selected.status in setOf("working", "busy", "shell")) {
                        val stopLabel = stringResource(R.string.stop_agent)
                        FilledIconButton(
                            onClick = { controller.messages.interrupt(agentId) },
                            enabled = enabled && !composer.loading && !composer.sending && composer.control?.status !in setOf("queued", "sent"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.semantics { contentDescription = stopLabel },
                        ) {
                            val color = LocalContentColor.current
                            Canvas(Modifier.size(14.dp)) { drawRect(color) }
                        }
                    }
                    val sendLabel = stringResource(R.string.send_message)
                    FilledIconButton(onClick = { if (agentId != null) { stickToEnd(); controller.messages.send(agentId) } }, enabled = enabled && !composer.loading && !composer.sending && composer.unavailable.isEmpty() && (composer.draft.text.isNotBlank() || composer.draft.attachments.isNotEmpty()), modifier = Modifier.semantics { contentDescription = sendLabel }) { Text("➤") }
                }
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
private fun ChatBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ⓘ", color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun EntryView(entry: TerminalEntry, results: List<TerminalEntry>, thoughts: Int = 1) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    if (entry.kind == "system") {
        SelectionContainer { Text(listOfNotNull(entry.text, entry.at?.let(::entryTime)).joinToString(" · "), Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.kind == "user") Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = if (entry.kind == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(12.dp)) {
                if (entry.kind == "tool" || entry.kind == "thinking") {
                    val label = if (entry.kind == "thinking") "${stringResource(R.string.thinking)} ($thoughts)" else entry.title ?: stringResource(R.string.tool_result)
                    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.kind == "tool") Text("⚙", color = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                    if (expanded) SelectionContainer {
                        if (entry.kind == "thinking") {
                            Text(entry.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                        } else {
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Text(entry.text, fontFamily = FontFamily.Monospace)
                                results.forEach {
                                    Text(stringResource(R.string.tool_result))
                                    Text(it.text, fontFamily = FontFamily.Monospace)
                                    if (it.truncated) Text(stringResource(R.string.result_truncated))
                                }
                            }
                        }
                    }
                } else {
                    SelectionContainer {
                        Markdown(
                            entry.text,
                            modifier = Modifier.fillMaxWidth(),
                            colors = markdownColor(text = if (entry.kind == "user") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface),
                            typography = markdownTypography(
                                h1 = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                h2 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                h3 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                h4 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                h5 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            ),
                            components = markdownComponents(image = {}),
                        )
                    }
                    if (entry.truncated) Text(stringResource(R.string.result_truncated))
                }
                entry.at?.let { Text(entryTime(it), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = (if (entry.kind == "user") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f)) }
            }
        }
    }
}

/**
 * Os pensamentos de um turno (até a próxima mensagem sua) viram uma linha só, no lugar do primeiro, com a contagem;
 * ao expandir, mostra o texto de todos. Os transcripts intercalam pensamento e ferramenta, então juntar só os seguidos não basta.
 */
internal fun groupThinking(entries: List<TerminalEntry>): List<Pair<TerminalEntry, Int>> {
    val rows = mutableListOf<Pair<TerminalEntry, Int>>()
    var turnThinking = -1
    for (entry in entries) {
        if (entry.kind == "user") turnThinking = -1
        if (entry.kind == "thinking" && turnThinking >= 0) {
            val (first, count) = rows[turnThinking]
            rows[turnThinking] = first.copy(text = listOf(first.text, entry.text).filter { it.isNotBlank() }.joinToString("\n\n"), at = entry.at ?: first.at) to count + 1
        } else {
            if (entry.kind == "thinking") turnThinking = rows.size
            rows += entry to 1
        }
    }
    return rows
}

/** "14:05" hoje; "02/11 14:05" em outro dia, no fuso do aparelho. */
internal fun entryTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val sameDay = time.toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return time.format(java.time.format.DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "dd/MM HH:mm"))
}

/** Filtro da conversa: "prompts" = suas mensagens e as respostas do agente; "no_tools" = tudo menos ferramentas e resultados. */
internal fun visibleEntries(entries: List<TerminalEntry>, view: String): List<TerminalEntry> = entries.filter { entry ->
    when (view) {
        "prompts" -> entry.kind == "user" || entry.kind == "assistant"
        "no_tools" -> entry.kind != "tool" && entry.kind != "result"
        else -> true
    }
}
