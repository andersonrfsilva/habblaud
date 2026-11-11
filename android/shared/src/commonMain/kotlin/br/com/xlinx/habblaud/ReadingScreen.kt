package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import androidx.compose.ui.platform.testTag

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import br.com.xlinx.habblaud.data.*
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

internal fun readingErrorResource(error: String): StringResource = when (error) {
    "changed" -> Res.string.session_changed
    "forbidden" -> Res.string.terminal_blocked
    "missing" -> Res.string.reading_missing
    "oversize" -> Res.string.reading_oversize
    else -> Res.string.network_error
}

internal fun actionsAvailable(state: ReadingState, loginRequired: Boolean, selectedPermission: Boolean): Boolean =
    !loginRequired && state.connection == "connected" && (state.terminalAllowed || selectedPermission)

internal fun ompTools(provider: String?) = provider == "omp"
internal fun reliableContext(provider: String?, tokens: Long?, window: Long?) =
    (provider == "omp" || provider == "claude" || provider == "codex") && tokens != null && window != null
internal fun deliveryModes(provider: String?): List<String> = when (provider) {
    "omp" -> listOf("aside", "followUp", "steer")
    "zeroclaw" -> listOf("followUp", "steer")
    else -> emptyList()
}
internal fun newSessionTarget(sessions: List<SessionItem>, previous: SessionKey, agentId: String): SessionItem? =
    sessions.firstOrNull { it.agentId == agentId && it.key.origin == previous.origin &&
        it.key.provider == previous.provider && it.key.account == previous.account && it.key != previous }
internal fun sessionControlsAvailable(item: SessionItem?, state: ReadingState): Boolean =
    item != null && supportsSessionControl(item.key.provider) && item.agentId != null && !item.sub &&
        item.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(apis: Map<String, HabblaudApi>, needsLogin: Set<String>, store: LocalStateStore, onUnauthorized: (String) -> Unit, onLogin: (String?) -> Unit, onLogout: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val summarizeReplies by store.summarize.collectAsState(initial = false)
    val controllers = linkedMapOf<String, ReadingController>()
    val states = linkedMapOf<String, ReadingState>()
    val owner = LocalLifecycleOwner.current
    apis.forEach { (origin, api) -> key(origin) {
        val controller = remember(api) { ReadingController(api, store, scope) { onUnauthorized(origin) } }
        controllers[origin] = controller
        val state by controller.state.collectAsStateWithLifecycle()
        states[origin] = state
        val loginRequired = origin in needsLogin
        DisposableEffect(owner, controller, loginRequired) {
            val observer = LifecycleEventObserver { _, event ->
                if (!loginRequired && event == Lifecycle.Event.ON_START) controller.start()
                if (event == Lifecycle.Event.ON_STOP) controller.stop()
            }
            owner.lifecycle.addObserver(observer)
            if (!loginRequired && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start() else controller.stop()
            onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
        }
    } }
    var savedFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = savedFilter?.takeIf { it in apis }
    LaunchedEffect(apis.keys) { if (savedFilter !in apis) savedFilter = null }
    fun open(item: SessionItem) {
        controllers.filterKeys { it != item.key.origin }.values.forEach { if (it.state.value.selected != null) it.close() }
        controllers.getValue(item.key.origin).open(item)
    }
    val pending by pendingSession.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { target ->
        controllers[target.origin]?.let { controller ->
            controllers.filterKeys { it != target.origin }.values.forEach { if (it.state.value.selected != null) it.close() }
            controller.openKey(target)
        }
        pendingSession.value = null
    } }
    val selectedOrigin = states.entries.firstOrNull { it.value.selected != null }?.key
    val actionOrigin = selectedOrigin ?: filter ?: apis.keys.first()
    val api = apis.getValue(actionOrigin)
    val controller = controllers.getValue(actionOrigin)
    val combinedConnection = when {
        needsLogin.isNotEmpty() || states.values.any { it.connection == "offline" } -> "offline"
        states.values.any { it.connection == "connecting" } -> "connecting"
        else -> "connected"
    }
    fun mergedFrom(base: String) = states.getValue(base).copy(
        sessions = mergeSessions(states, filter),
        unread = states.values.flatMap { it.unread.entries }.associate { it.toPair() },
        connection = combinedConnection,
        terminalAllowed = states.values.any { it.terminalAllowed },
        error = null
    )
    val conversationState = selectedOrigin?.let { states.getValue(it) }
    val state = conversationState ?: mergedFrom(actionOrigin)
    val sessionsStateWide = mergedFrom(filter ?: apis.keys.first())
    val composer by controller.messages.state.collectAsStateWithLifecycle()
    var permissionSession by remember { mutableStateOf<SessionItem?>(null) }
    val permissionState = permissionSession?.let { states[it.key.origin] } ?: state
    val permissionLive = permissionSession?.let { requested -> permissionState.sessions.find { it.key == requested.key && it.permissionId == requested.permissionId } }
    LaunchedEffect(permissionSession, permissionLive, permissionState.connection) {
        if (permissionSession != null && permissionLive == null && permissionState.connection == "connected") permissionSession = null
    }
    permissionSession?.let { requested ->
        PermissionSheet(apis.getValue(requested.key.origin), permissionLive ?: requested, permissionState.permissionsAllowed, permissionState.connection == "connected", { onUnauthorized(requested.key.origin) }) { permissionSession = null }
    }
    var pickerKey by remember { mutableStateOf<SessionKey?>(null) }
    val picker = rememberAttachmentPicker { uris ->
        if (pickerKey == controller.messages.state.value.key) controller.messages.select(uris)
        pickerKey = null
    }
    val selected = state.selected
    var previousAgent by remember(selected?.key) { mutableStateOf(selected?.agentId) }
    LaunchedEffect(selected?.key, selected?.agentId) {
        val item = selected
        if (item != null && previousAgent == null && item.agentId != null) open(item)
        previousAgent = item?.agentId
    }
    var confirmNew by remember(selected?.key) { mutableStateOf(false) }
    var newTarget by remember { mutableStateOf<Pair<SessionKey, String>?>(null) }
    val newOriginState = newTarget?.first?.origin?.let(states::get)
    val newOriginComposer = newTarget?.first?.origin?.let { controllers[it]?.messages?.state?.value }
    LaunchedEffect(newOriginState?.sessions, newTarget, newOriginComposer?.controlError, newOriginComposer?.control?.status) {
        newTarget?.let { (previous, agentId) ->
            if (previous.origin !in apis || previous.origin in needsLogin || newOriginComposer?.controlError != null || newOriginComposer?.control?.status == "failed") newTarget = null
            else newSessionTarget(newOriginState?.sessions.orEmpty(), previous, agentId)?.let { target -> newTarget = null; open(target) }
        }
    }
    if (confirmNew && selected?.agentId != null) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text(stringResource(Res.string.session_new)) },
        text = { Text(stringResource(Res.string.session_new_confirm)) },
        confirmButton = { TextButton(onClick = {
            confirmNew = false
            if (sessionControlsAvailable(selected, state) && !composer.loading && !composer.sending && composer.control?.status !in setOf("queued", "sent")) {
                newTarget = selected.key to selected.agentId
                controller.messages.newSession(selected.agentId, confirmed = true)
            }
        }) { Text(stringResource(Res.string.confirm)) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(Res.string.cancel)) } }
    )
    var menuExpanded by remember { mutableStateOf(false) }
    // Estado fora do `if`: voltar de uma conversa mantém a aba, a busca fixa acima da lista e a posição.
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var recent by remember { mutableStateOf(false) }
    // A busca fica acima da lista, oculta até uma puxada longa para baixo no topo.
    val sessionsList = rememberLazyListState()
    /** Conversa que estava no topo na última composição (sem ser estado: mudar não recompõe). */
    val sessionsTop = remember { arrayOfNulls<String>(1) }
    // O que a conversa mostra (tudo, só prompts ou sem ferramentas) e o modo de envio: cada sessão lembra os seus.
    // Cada valor guarda a sessão a que pertence: ao trocar de sessão, o valor da anterior não vale nem é salvo na nova.
    var viewFor by remember { mutableStateOf<Pair<SessionKey, String>?>(null) }
    var deliverFor by remember { mutableStateOf<Pair<SessionKey, String>?>(null) }
    val loaded = selected != null && viewFor?.first == selected.key && deliverFor?.first == selected.key
    val view = viewFor?.takeIf { it.first == selected?.key }?.second ?: "all"
    val deliverMode = deliverFor?.takeIf { it.first == selected?.key }?.second ?: "aside"
    SideEffect { controller.messages.deliverAs = deliverMode }
    LaunchedEffect(selected?.key) {
        val key = selected?.key ?: return@LaunchedEffect
        val savedView = store.sessionView(key)
        val savedDeliver = store.sessionDeliverAs(key)
        if (viewFor?.first != key) viewFor = key to savedView
        if (deliverFor?.first != key) deliverFor = key to savedDeliver
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) {
        SettingsScreen(apis, store, onClose = { settingsOpen = false }, onLogin = { settingsOpen = false; onLogin(it) }, onLogout = { settingsOpen = false; onLogout(it) })
        return
    }
    PlatformBackHandler(enabled = selected != null && permissionSession == null && !menuExpanded) { controller.close() }
    // Bate-papo paralelo do agente aberto: some ao trocar de conversa (o controlador também fecha).
    var asideOpen by remember(selected?.key) { mutableStateOf(false) }
    var filesOpen by remember(api.origin, selected?.key) { mutableStateOf(false) }
    PlatformBackHandler(enabled = selected == null && searchOpen) { query = ""; searchOpen = false }
    CastActions(api, selected, actionsAvailable(states.getValue(actionOrigin), actionOrigin in needsLogin, selected?.permission == true) && (selected != null || filter != null || apis.size == 1), state.sessions.filter { it.agentId != null }.mapNotNull { it.cast }.toSet(), { onUnauthorized(api.origin) }, { permissionSession = selected }, menu = { close, dismissItem ->
        // Menu em páginas: a principal lista os grupos; tocar num grupo troca o conteúdo pela lista dele, com "‹ Voltar".
        var page by remember(selected?.key) { mutableStateOf("") }
        val agentId = selected?.agentId
        val omp = selected?.key?.provider == "omp" && agentId != null && !selected.sub
        val sessionControls = supportsSessionControl(selected?.key?.provider) && agentId != null && selected?.sub == false
        val canControl = sessionControlsAvailable(selected, state)
        @Composable fun Back() = DropdownMenuItem(text = { Text(stringResource(Res.string.back), style = MaterialTheme.typography.labelLarge) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp)) }, onClick = { page = "" })
        @Composable fun Group(label: StringResource, value: String?, target: String, enabled: Boolean = true) = DropdownMenuItem(
            text = { Column { Text(stringResource(label)); value?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } } },
            trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }, enabled = enabled, onClick = { page = target })
        @Composable fun Choice(label: String, checked: Boolean, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label) }, leadingIcon = { RadioButton(selected = checked, onClick = null) }, onClick = onClick)
        val viewLabels = listOf("all" to Res.string.view_all, "prompts" to Res.string.view_prompts, "no_tools" to Res.string.view_no_tools)
        val deliverLabels = listOf("aside" to Res.string.deliver_aside, "followUp" to Res.string.deliver_follow_up, "steer" to Res.string.deliver_steer)
            .filter { it.first in deliveryModes(selected?.key?.provider) }
        // O modelo da sessão pode vir sem o provedor: o da lista que bate com ele, com o provedor.
        val currentModel = selected?.models?.firstOrNull { it == selected.model || it.substringAfter('/') == selected.model } ?: selected?.model?.takeIf { '/' in it }
        when (page) {
            "view" -> { Back(); viewLabels.forEach { (value, label) -> Choice(stringResource(label), view == value) {
                selected?.key?.let { key -> viewFor = key to value; scope.launch { store.setSessionView(key, value) } }
                close()
            } } }
            "deliver" -> { Back(); deliverLabels.forEach { (mode, label) -> Choice(stringResource(label), messageDelivery(selected?.key?.provider, deliverMode) == mode) {
                selected?.key?.let { key -> deliverFor = key to mode; scope.launch { store.setSessionDeliverAs(key, mode) } }
                close()
            } } }
            "model" -> {
                Back()
                // Como no seletor dos papéis: por provedor, a versão mais nova primeiro, sem `:batch` nem cópias datadas.
                roleModelGroups(selected!!.models, keep = currentModel).forEach { (provider, list) ->
                    Text(providerLabel(provider), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    list.forEach { model -> Choice(model.substringAfter('/'), model == currentModel) { controller.messages.setModel(agentId!!, model); close() } }
                }
            }
            "thinking" -> { Back(); THINKING_LEVELS.forEach { level -> Choice(thinkingLabel(level), level == selected!!.thinking) { controller.messages.setThinking(agentId!!, level); close() } } }
            else -> {
                DropdownMenuItem(modifier = Modifier.testTag("files-menu"), text = { Text(stringResource(Res.string.files_title)) }, onClick = { filesOpen = true; close() })
                Group(Res.string.view_menu, viewLabels.firstOrNull { it.first == view }?.let { stringResource(it.second) }, "view", loaded)
                if (sessionControls) {
                    Group(Res.string.deliver_mode, deliverLabels.firstOrNull { it.first == messageDelivery(selected?.key?.provider, deliverMode) }?.let { stringResource(it.second) }, "deliver", loaded)
                    DropdownMenuItem(modifier = Modifier.testTag("session-new"), text = { Text(stringResource(Res.string.session_new)) },
                        enabled = canControl && !composer.loading && !composer.sending && composer.control?.status !in setOf("queued", "sent"),
                        onClick = { confirmNew = true; close() })
                }
                if (omp) {
                    if (selected!!.models.isNotEmpty()) Group(Res.string.model_menu, (currentModel ?: selected.model)?.let(::modelLabel), "model", canControl)
                    Group(Res.string.thinking_menu, selected.thinking?.let { thinkingLabel(it) }, "thinking", canControl)
                    DropdownMenuItem(text = { Text(stringResource(Res.string.shake)) }, enabled = canControl, onClick = { controller.messages.shake(agentId!!); close() })
                    DropdownMenuItem(modifier = Modifier.testTag("aside-menu"), text = { Text(stringResource(Res.string.aside_menu)) }, enabled = canControl, onClick = { controller.asides.open(agentId!!); asideOpen = true; close() })
                }
                dismissItem()
            }
        }
    }) { castActions, castFab, castStatus ->
    var showInfo by remember(selected?.key) { mutableStateOf(false) }
    if (showInfo && selected != null) SessionInfoSheet(api, selected, state.entries, state.truncated) { showInfo = false }
    if (asideOpen && selected?.agentId != null) AsideSheet(controller.asides) { asideOpen = false; controller.asides.close() }
    if (filesOpen) FilesSheet(api, selected, { onUnauthorized(api.origin) }) { filesOpen = false }
    val listTopBar: @Composable (ReadingState) -> Unit = { state ->
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.app_name), Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val statusLabel = stringResource(when (state.connection) { "connected" -> Res.string.reading_connected; "connecting" -> Res.string.reading_connecting; else -> Res.string.reading_offline })
                    Box(Modifier.size(10.dp).background(connectionColor(state.connection), CircleShape).semantics { contentDescription = statusLabel })
                }
            },
            actions = {
                Box {
                    val menuLabel = stringResource(Res.string.menu)
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(modifier = Modifier.testTag("files-menu"), text = { Text(stringResource(Res.string.files_title)) }, onClick = { menuExpanded = false; filesOpen = true })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.settings)) }, onClick = { menuExpanded = false; settingsOpen = true })
                        DropdownMenuItem(modifier = Modifier.testTag("add-server"), text = { Text(stringResource(Res.string.add_server)) }, onClick = { menuExpanded = false; onLogin(null) })
                    }
                }
            }
        )
    }
    val conversationTopBar: @Composable (SessionItem, Boolean) -> Unit = { selected, showBack ->
        TopAppBar(
            title = {
                val infoLabel = stringResource(Res.string.session_info)
                Row(Modifier.clickable(onClickLabel = infoLabel) { showInfo = true }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CharacterAvatar(api, selected, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(selected.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val projectStatus = listOfNotNull(selected.project?.substringAfterLast('/'), statusLabel(selected.status ?: selected.activity)).joinToString(" · ")
                        val subtitle = listOfNotNull(selected.characterName.takeIf { it != null && it != selected.title }, projectStatus.takeIf { it.isNotEmpty() }).joinToString(" · ")
                        Text(subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = {
                if (showBack) {
                    val backLabel = stringResource(Res.string.back_sessions)
                    IconButton(onClick = controller::close, modifier = Modifier.semantics { contentDescription = backLabel }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                }
            },
            actions = { castActions() }
        )
    }
    val listContent: @Composable ColumnScope.(ReadingState, Boolean) -> Unit = { state, highlight ->
            needsLogin.forEach { origin ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(serverLabel(origin), Modifier.weight(1f))
                    TextButton(modifier = Modifier.testTag("relogin:$origin"), onClick = { onLogin(origin) }) { Text(stringResource(Res.string.relogin)) }
                }
            }
            states.forEach { (origin, serverState) -> serverState.error?.let {
                Text((if (apis.size > 1) serverLabel(origin) + ": " else "") + stringResource(readingErrorResource(it)), color = MaterialTheme.colorScheme.error)
            } }
            if (apis.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(modifier = Modifier.testTag("server-filter:all"), selected = filter == null, onClick = { savedFilter = null }, label = { Text(stringResource(Res.string.filter_all)) })
                apis.keys.forEach { origin ->
                    FilterChip(modifier = Modifier.testTag("server-filter:${serverLabel(origin)}"), selected = filter == origin, onClick = { savedFilter = origin }, label = { Text(serverLabel(origin)) }, leadingIcon = {
                        val connection = if (origin in needsLogin) "offline" else states[origin]?.connection
                        val color = connectionColor(connection)
                        val label = stringResource(when (connection) { "connected" -> Res.string.reading_connected; "connecting" -> Res.string.reading_connecting; else -> Res.string.reading_offline })
                        Box(Modifier.size(10.dp).background(color, CircleShape).semantics { contentDescription = label })
                    })
                }
            }
            // Trocar de aba volta ao topo, com a busca escondida.
            fun showTab(value: Boolean) { recent = value; if (query.isEmpty()) { searchOpen = false; scope.launch { withFrameNanos { }; sessionsList.scrollToItem(0) } } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !recent, onClick = { showTab(false) }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) {
                    Text(stringResource(Res.string.open_sessions))
                }
                SegmentedButton(selected = recent, onClick = { showTab(true) }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) {
                    Text(stringResource(Res.string.recent_sessions))
                }
            }
            if (!state.terminalAllowed) Text(stringResource(Res.string.terminal_blocked))
            // Como num chat: a conversa com a resposta mais nova no topo.
            val items = byLastMessage(state.sessions.filter { (recent || it.agentId != null) && listOfNotNull(it.title, it.characterName, it.agentName, it.project, it.key.provider, it.key.account).any { value -> value.contains(query, true) } })
            // Outra conversa subiu para o topo com a lista no topo: sem isto, a lista segura a que estava em cima (pela
            // chave) e a nova fica escondida acima dela.
            val top = items.firstOrNull()?.key?.serialize()
            if (top != sessionsTop[0]) {
                if (sessionsTop[0] != null && sessionsList.firstVisibleItemIndex == 0) sessionsList.requestScrollToItem(0, sessionsList.firstVisibleItemScrollOffset)
                sessionsTop[0] = top
            }
            if (searchOpen) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
                TextField(
                    query, { query = it },
                    Modifier.fillMaxWidth().focusRequester(focus).testTag("session-search"),
                    placeholder = { Text(stringResource(Res.string.search_sessions)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        val closeLabel = stringResource(Res.string.search_close)
                        IconButton(onClick = { query = ""; searchOpen = false }, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = pillFieldColors(),
                )
            }
            val revealPx = with(LocalDensity.current) { REVEAL_PULL.toPx() }
            val haptics = LocalHapticFeedback.current
            val reveal = remember(revealPx) {
                object : NestedScrollConnection {
                    var pulled = 0f
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        // Só sobra rolagem para baixo quando a lista já está no topo: é a "puxada" além do começo.
                        if (source == NestedScrollSource.UserInput && available.y > 0f && !searchOpen) {
                            pulled += available.y
                            if (pulled >= revealPx) { pulled = 0f; searchOpen = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        } else if (available.y < 0f || consumed.y != 0f) pulled = 0f
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
                }
            }
            LazyColumn(state = sessionsList, modifier = Modifier.fillMaxSize().nestedScroll(reveal), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (query.isNotBlank()) {
                            Text(stringResource(Res.string.search_no_match), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { query = "" }) { Text(stringResource(Res.string.search_clear)) }
                        } else {
                            Text(stringResource(Res.string.sessions_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                items(items, key = { it.key.serialize() }) { session ->
                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                    Row(Modifier.fillMaxWidth().testTag("session-row:${session.key.provider}:${session.key.sessionId}").then(if (highlight && selected?.key == session.key) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)) else Modifier).clickable { open(session) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(apis.getValue(session.key.origin), session, 48.dp)
                        Column(Modifier.weight(1f)) {
                            // Não lida: título, personagem e linha de status em negrito, como num chat.
                            val weight = if (state.unread[session.key] != null) FontWeight.Bold else null
                            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = weight, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(session.characterName?.takeIf { it != session.title }, session.project?.substringAfterLast('/'), statusLabel(session.status ?: session.activity), serverLabel(session.key.origin).takeIf { apis.size > 1 && filter == null }).joinToString(" · "), modifier = if (apis.size > 1 && filter == null) Modifier.testTag("session-server:${serverLabel(session.key.origin)}") else Modifier, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (session.agentId != null && session.status == "working") StatusLine(session)
                            if (session.permission) TextButton(onClick = { permissionSession = session }) { Text(stringResource(Res.string.permission_pending), color = MaterialTheme.colorScheme.error) }
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            (session.replies.lastOrNull() ?: session.lastAt)?.let { Text(relativeTime(it), style = MaterialTheme.typography.labelSmall, color = if (state.unread[session.key] != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                            state.unread[session.key]?.let { count ->
                                val label = pluralStringResource(Res.plurals.unread_replies, count, count)
                                Badge(Modifier.semantics { contentDescription = label }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Text(if (count >= REPLIES_SHOWN) "$REPLIES_SHOWN+" else count.toString(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    // Subagentes e advisors pendurados no principal enquanto trabalham; somem sozinhos quando terminam.
                    session.subagents.forEachIndexed { i, sub -> SubagentBranch(apis.getValue(session.key.origin), sub, last = i == session.subagents.lastIndex) { open(subSession(session, sub)) } }
                    }
                }
            }
    }
    val conversationContent: @Composable ColumnScope.(SessionItem, ReadingState) -> Unit = { selected, state ->
        state.error?.let { error -> Text(stringResource(readingErrorResource(error)), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(1.dp))
            if (selected.agentId == null) Text(stringResource(Res.string.history_read_only))
            if (!state.terminalAllowed) Text(stringResource(Res.string.terminal_blocked))
            state.readingError?.let { error ->
                Text(stringResource(when (error) { "forbidden" -> Res.string.terminal_blocked; "missing" -> Res.string.reading_missing; "oversize" -> Res.string.reading_oversize; else -> Res.string.reading_offline }), color = MaterialTheme.colorScheme.error)
            }
            if (state.reduced) Text(stringResource(Res.string.window_reduced))
            val results = state.entries.filter { it.kind == "result" }.groupBy { it.toolUseId }
            // Lista invertida: o índice 0 é a entrada mais nova. A conversa já abre no fim, sem rolar por todo o histórico.
            val listState = remember(selected.key) { LazyListState() }
            val following by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
            var readingAnchor by remember(selected.key) { mutableStateOf<Pair<Float, () -> Float>?>(null) }
            LaunchedEffect(readingAnchor) {
                val (before, now) = readingAnchor ?: return@LaunchedEffect
                // Aguarda a posição medida, não um número de quadros: o markdown pode terminar depois deles.
                snapshotFlow { now() to listState.isScrollInProgress }
                    .takeWhile { (_, scrolling) -> !scrolling }
                    .collect { (position, _) ->
                        val delta = before - position
                        if (kotlin.math.abs(delta) >= 1f) listState.scrollBy(delta)
                    }
                readingAnchor = null
            }
            var unread by remember(selected.key) { mutableIntStateOf(0) }
            var previousLastId by remember(selected.key) { mutableStateOf<String?>(null) }
            val shown = visibleEntries(state.entries, view)
            val rows = groupTurn(shown)
            val recovered = recoveredErrors(state.entries)
            val lastId = shown.lastOrNull()?.id
            val working = selected.agentId != null && selected.status == "working"
            LaunchedEffect(following) { if (following) unread = 0 }
            // Ao enviar, volta a seguir o fim: o prompt e a resposta que chegam aparecem sem rolar à mão.
            fun stickToEnd() { unread = 0; scope.launch { listState.scrollToItem(0) } }
            // Entrada nova no pé seguindo o fim: a lista seguraria o item que estava no pé, pela chave, e o novo ficaria
            // escondido abaixo. Pedido ainda na composição, antes da medida que o insere: sem um quadro com o botão "↓"
            // piscando.
            val bottom = rows.lastOrNull()?.entry?.id
            val bottomSeen = remember(selected.key) { arrayOfNulls<String>(1) }
            val wasFollowing = following
            if (bottom != bottomSeen[0]) {
                readingAnchor = null
                if (wasFollowing) listState.requestScrollToItem(0)
                bottomSeen[0] = bottom
            }
            LaunchedEffect(lastId) {
                val before = previousLastId
                previousLastId = lastId
                if (before == null || lastId == null || wasFollowing) return@LaunchedEffect
                val index = shown.indexOfLast { it.id == before }
                unread += if (index >= 0) shown.size - index - 1 else 1
            }
            // Puxar para cima além da mensagem mais nova abre o bate-papo paralelo (agente do omp que aceita mensagens).
            val canAside = selected.agentId != null && !selected.sub && selected.key.provider == "omp" && selected.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
            val asideAgent by rememberUpdatedState(if (canAside) selected.agentId else null)
            val asidePx = with(LocalDensity.current) { ASIDE_PULL.toPx() }
            val asideHaptics = LocalHapticFeedback.current
            val asideSwipe = remember(asidePx) {
                object : NestedScrollConnection {
                    var pulled = 0f
                    /** null até o gesto andar; true se ele já começou no fim da conversa. */
                    var startedAtEnd: Boolean? = null
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        // Lista invertida: no fim da conversa, o dedo subindo sobra como rolagem negativa não consumida.
                        // Só conta o gesto que começou no fim: a sobra de uma rolagem que chega ao fim não abre nada.
                        if (startedAtEnd == null && (consumed.y != 0f || available.y != 0f)) startedAtEnd = consumed.y == 0f && available.y < 0f
                        val agent = asideAgent
                        if (startedAtEnd == true && available.y < 0f && consumed.y == 0f && agent != null && !asideOpen) {
                            pulled -= available.y
                            if (pulled >= asidePx) { pulled = 0f; startedAtEnd = false; controller.asides.open(agent); asideOpen = true; asideHaptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        } else if (available.y > 0f || consumed.y != 0f) pulled = 0f
                        return Offset.Zero
                    }
                    override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; startedAtEnd = null; return Velocity.Zero }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // No desktop a roda do mouse não termina gesto: sem puxada, o bate-papo paralelo fica só no menu.
                LazyColumn(Modifier.fillMaxSize().then(if (platformPullGestures()) Modifier.nestedScroll(asideSwipe) else Modifier).testTag("conversation-list"), state = listState, reverseLayout = true, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
                    // Lista invertida: o primeiro item fica no pé da conversa, logo abaixo da última mensagem.
                    items(rows.asReversed(), key = { it.entry.id }) { row ->
                        val entry = row.entry
                        EntryView(entry, results, row.count, row.tools, recovered = entry.id in recovered, cachedSummary = controller.summary(entry), summarize = if (summarizeReplies) { text -> controller.summarize(entry, text) } else null) { before, now ->
                            // A lista é invertida: o item cresce para cima. Acompanha a posição medida do cabeçalho
                            // até a pessoa rolar ou tocar noutro cabeçalho, inclusive nas medidas tardias do markdown.
                            readingAnchor = before to now
                        }
                    }
                    if (state.truncated) item {
                        Text(stringResource(Res.string.window_truncated), Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!following && rows.isNotEmpty()) {
                    val label = stringResource(Res.string.go_to_end)
                    SmallFloatingActionButton(onClick = { stickToEnd() }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).semantics { contentDescription = label }, shape = CircleShape) {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) }
                    }
                }
            }
            // Avatar parado na linha de estado: animar o "digitando" no pé redesenhava a tela a 60 qps enquanto o agente trabalhava.
            if (working) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CharacterAvatar(api, selected, 20.dp)
                StatusLine(selected, Modifier.weight(1f))
            }
            if (composer.key == selected.key && selected.agentId != null && !selected.sub) {
                val agentId = selected.agentId
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val enabled = agentId != null && selected.canMessage && state.messagesAllowed && state.messageGuard && state.connection == "connected"
                // Toque longo no anel de contexto pede confirmação e manda o shake (o mesmo do menu da sessão).
                var confirmShake by remember(selected.key) { mutableStateOf(false) }
                val canShake = enabled && selected.key.provider == "omp"
                if (confirmShake && canShake) AlertDialog(
                    onDismissRequest = { confirmShake = false },
                    title = { Text(stringResource(Res.string.shake)) },
                    text = { Text(stringResource(Res.string.shake_confirm)) },
                    confirmButton = { TextButton(onClick = { confirmShake = false; controller.messages.shake(agentId!!) }) { Text(stringResource(Res.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { confirmShake = false }) { Text(stringResource(Res.string.cancel)) } }
                )
                composer.progress?.let { Text(stringResource(Res.string.attachment_progress, it), style = MaterialTheme.typography.bodySmall) }
                if (!state.messagesAllowed) ChatBanner(stringResource(Res.string.messages_blocked))
                else if (!state.messageGuard) ChatBanner(stringResource(Res.string.message_guard_required))
                else if (agentId != null && !selected.canMessage) ChatBanner(stringResource(Res.string.message_unavailable))
                if (supportsSessionControl(selected.key.provider)) {
                    composer.control?.takeUnless { it.status == "delivered" }?.let { control -> Text(stringResource(when (control.status) {
                        "queued", "sent" -> Res.string.stop_pending
                        "failed" -> Res.string.stop_failed
                        else -> Res.string.stop_unknown
                    })) }
                    (composer.control?.error ?: composer.controlError)?.takeIf { it != "unknown" && it != "failed" && it != "confirm" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (composer.controlError == "failed") Text(stringResource(Res.string.stop_failed), color = MaterialTheme.colorScheme.error)
                    if (composer.controlError == "confirm") AlertDialog(
                        onDismissRequest = { controller.messages.dismissControlConfirmation() },
                        title = { Text(stringResource(Res.string.stop_agent)) },
                        text = { Text(stringResource(Res.string.stop_unknown)) },
                        confirmButton = { TextButton(onClick = { controller.messages.interrupt(agentId!!, confirmed = true) }) { Text(stringResource(Res.string.confirm)) } },
                        dismissButton = { TextButton(onClick = { controller.messages.dismissControlConfirmation() }) { Text(stringResource(Res.string.cancel)) } }
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    composer.draft.attachments.forEach { attachment ->
                        val removeLabel = stringResource(Res.string.attachment_remove)
                        InputChip(selected = false, onClick = { controller.messages.remove(attachment) }, label = { Text(attachment.name, maxLines = 1) }, trailingIcon = { Icon(Icons.Default.Close, contentDescription = removeLabel, modifier = Modifier.size(18.dp)) })
                        if (attachment.uri in composer.unavailable) Text(stringResource(Res.string.attachment_reselect), color = MaterialTheme.colorScheme.error)
                        else if (!attachment.persistent) Text(stringResource(Res.string.attachment_temporary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                composer.operation?.takeUnless { it.status == "delivered" }?.let { operation -> ChatBanner(stringResource(when (operation.status) {
                    "queued" -> Res.string.message_queued
                    "sent" -> Res.string.message_sent
                    "failed" -> Res.string.message_failed
                    else -> Res.string.message_unknown
                }), Modifier.testTag("message-status-${operation.status}")) }
                composer.commandResult?.let { result ->
                    LaunchedEffect(result) { if (result.status == "sent") { kotlinx.coroutines.delay(4000); controller.messages.dismissCommandResult() } }
                    val what = stringResource(when (result.kind) { "model" -> Res.string.model_menu; "thinking" -> Res.string.thinking_menu; else -> Res.string.shake_short })
                    ChatBanner(stringResource(when (result.status) { "pending" -> Res.string.command_pending; "sent" -> Res.string.command_sent; "failed" -> Res.string.command_failed; else -> Res.string.command_unknown }, what))
                }
                composer.error?.let { error -> if (error != "confirm") Text(stringResource(when (error) { "storage" -> Res.string.storage_error; "unknown" -> Res.string.message_unknown; "attachment" -> Res.string.attachment_error; "attachments_expired" -> Res.string.attachments_expired; else -> Res.string.message_send_error }), color = MaterialTheme.colorScheme.error) }
                if (composer.error == "confirm" && agentId != null) AlertDialog(
                    onDismissRequest = { controller.messages.dismissConfirmation() },
                    title = { Text(stringResource(Res.string.message_retry_title)) },
                    text = { Text(stringResource(Res.string.message_retry_warning)) },
                    confirmButton = { TextButton(onClick = { stickToEnd(); controller.messages.send(agentId, confirmed = true) }) { Text(stringResource(Res.string.confirm)) } },
                    dismissButton = { TextButton(onClick = { controller.messages.dismissConfirmation() }) { Text(stringResource(Res.string.cancel)) } }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val attachLabel = stringResource(Res.string.attach_files)
                    val canSend = enabled && !composer.loading && !composer.sending && composer.unavailable.isEmpty() && (composer.draft.text.isNotBlank() || composer.draft.attachments.isNotEmpty())
                    fun send() { stickToEnd(); controller.messages.send(agentId) }
                    TextField(
                        composer.draft.text,
                        { controller.messages.edit(text = it) },
                        Modifier.weight(1f).testTag("message-input").onPreviewKeyEvent { event ->
                            if (platformSendsOnEnter() && event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                                if (canSend) send()
                                true
                            } else false
                        },
                        enabled = !composer.loading,
                        label = { Text(stringResource(Res.string.message_text)) },
                        supportingText = if (composer.draft.text.length >= 18000) { { Text("${composer.draft.text.length}/20000") } } else null,
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp),
                        colors = pillFieldColors(),
                        leadingIcon = {
                            IconButton(onClick = { pickerKey = selected.key; picker() }, enabled = !composer.loading && composer.draft.attachments.size < MAX_ATTACHMENTS, modifier = Modifier.semantics { contentDescription = attachLabel }) { Icon(Icons.Default.AttachFile, contentDescription = null) }
                        },
                        trailingIcon = if (reliableContext(selected.key.provider, selected.contextTokens, selected.contextWindow)) { { ContextRing(selected.contextTokens!!, selected.contextWindow!!, if (canShake) { { confirmShake = true } } else null) } } else null,
                    )
                    if (supportsSessionControl(selected.key.provider) && (selected.status in setOf("working", "busy", "shell") || selected.permission)) {
                        val stopLabel = stringResource(Res.string.stop_agent)
                        FilledIconButton(
                            onClick = { controller.messages.interrupt(agentId) },
                            enabled = enabled && !composer.loading && !composer.sending && composer.control?.status !in setOf("queued", "sent"),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                            modifier = Modifier.semantics { contentDescription = stopLabel },
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                        }
                    }
                    val sendLabel = stringResource(Res.string.send_message)
                    FilledIconButton(onClick = ::send, enabled = canSend, modifier = Modifier.testTag("message-send").semantics { contentDescription = sendLabel }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }
                }
                }
            }
    }
    BoxWithConstraints {
    if (maxWidth >= 840.dp) {
        PlatformSearchShortcut(enabled = true) { searchOpen = true }
        Row(Modifier.fillMaxSize()) {
            Scaffold(Modifier.width(360.dp).fillMaxHeight(), topBar = { listTopBar(sessionsStateWide) },
                floatingActionButton = { if (selected != null || filter != null || apis.size == 1) castFab() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    castStatus()
                    listContent(sessionsStateWide, true)
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (selected == null) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(stringResource(Res.string.select_conversation), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            } else {
                Scaffold(Modifier.weight(1f).fillMaxHeight(), topBar = { conversationTopBar(selected, false) }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.fillMaxSize().imePadding().background(if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            conversationContent(selected, conversationState!!)
                        }
                    }
                }
            }
        }
    } else {
    Scaffold(floatingActionButton = { if (selected == null && (filter != null || apis.size == 1)) castFab() }, topBar = {
        if (selected == null) listTopBar(state) else conversationTopBar(selected, true)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().background(if (selected == null) MaterialTheme.colorScheme.background else if (MaterialTheme.colorScheme.background.luminance() < 0.5f) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            castStatus()
            if (selected == null) {
                state.error?.let { error -> Text(stringResource(readingErrorResource(error)), color = MaterialTheme.colorScheme.error) }
                listContent(state, false)
            } else conversationContent(selected, state)
        }
    }
    }
    }
    }
}

/** Anel do contexto em uso (tokens / janela do modelo). Com [onLongPress], o toque longo pede o shake. */
@Composable
private fun ContextRing(tokens: Long, window: Long, onLongPress: (() -> Unit)?) {
    val fraction = (tokens.toFloat() / window).coerceIn(0f, 1f)
    val pct = (fraction * 100).toInt()
    val color = when { pct >= 80 -> MaterialTheme.colorScheme.error; pct >= 50 -> warningColor(); else -> MaterialTheme.colorScheme.primary }
    val label = stringResource(Res.string.context_usage, pct, tokens / 1000, window / 1000)
    val shakeLabel = stringResource(Res.string.shake)
    val haptics = LocalHapticFeedback.current
    // Área de toque de 40 dp em volta do anel de 20 dp; o leitor de tela ganha a mesma ação como "ação de toque longo".
    Box(Modifier.size(40.dp).then(if (onLongPress == null) Modifier else Modifier
        .pointerInput(onLongPress) { detectTapGestures(onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }) }
        .semantics { onLongClick(label = shakeLabel) { onLongPress(); true } }), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(20.dp).semantics { contentDescription = label },
            color = color,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeWidth = 2.5.dp,
        )
    }
}

@Composable
private fun statusLabel(value: String?): String? = when (value?.lowercase()) {
    "idle" -> stringResource(Res.string.status_idle)
    "working", "busy", "running" -> stringResource(Res.string.status_working)
    "waiting" -> stringResource(Res.string.status_waiting)
    "offline", "closed", "stopped" -> stringResource(Res.string.status_closed)
    else -> null
}

/** Galho da árvore sob o avatar do principal (48 dp), menor que ele: linha, avatar de 24 dp, nome e atividade. Tocar abre a conversa para ler. */
@Composable
private fun SubagentBranch(api: HabblaudApi, sub: SubAgent, last: Boolean, onOpen: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(36.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(40.dp).fillMaxHeight()) {
            val x = 24.dp.toPx(); val mid = size.height / 2; val stroke = 1.5.dp.toPx()
            drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, if (last) mid else size.height), stroke)
            drawLine(line, androidx.compose.ui.geometry.Offset(x, mid), androidx.compose.ui.geometry.Offset(size.width, mid), stroke)
        }
        CharacterAvatar(api, sub.avatar, sub.name, 24.dp)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(if (sub.advisor) stringResource(Res.string.advisor_name, sub.name) else sub.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub.activity?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Quadros do spinner, os mesmos do rodapé do terminal na web (client/src/ui/terminal.ts). */
private val SPINNER = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
private val CODEX_SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * Linha de estado enquanto o agente trabalha, como o rodapé do terminal na web: spinner, atividade e cronômetro.
 * As cores vêm do MaterialTheme, então seguem o tema claro/escuro escolhido. Com as animações do sistema
 * desligadas, o símbolo fica parado e só o cronômetro anda.
 */
@Composable
internal fun StatusLine(session: SessionItem, modifier: Modifier = Modifier, onTextUpdate: ((Long) -> Unit)? = null) {
    val codex = session.key.provider == "codex"
    val frames = if (codex) CODEX_SPINNER else SPINNER
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val animate = animationsEnabled() && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    var now by remember { mutableLongStateOf(uiNowMillis()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            now = uiNowMillis()
            while (true) {
                kotlinx.coroutines.delay(1000 - uiNowMillis() % 1000)
                now = uiNowMillis()
            }
        }
    }
    SideEffect { onTextUpdate?.invoke(now) }
    val glyph = if (animate) frames[((now / 1000) % frames.size).toInt()] else if (codex) "•" else "✻"
    val text = statusLineText(session.activity) ?: stringResource(Res.string.status_line_working)
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(glyph, Modifier.width(14.dp).clearAndSetSemantics { }, color = colors.primary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text("$text…", Modifier.weight(1f, fill = false), color = colors.primary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        session.statusSince?.let { Text("(${stopwatch(now - it)})", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")) }
    }
}

/** Atividade sem as reticências do fim (a linha põe as dela); vazia vira null. */
internal fun statusLineText(activity: String?): String? = activity?.trim()?.replace(Regex("(?:…|\\.{1,3})$"), "")?.trim()?.takeIf { it.isNotEmpty() }

/** Cronômetro da linha de estado: "0:07", "12:31", "1:02:10". */
internal fun stopwatch(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

@Composable
private fun relativeTime(timestamp: Long): String {
    val minutes = ((uiNowMillis() - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> stringResource(Res.string.time_now)
        minutes < 60 -> stringResource(Res.string.time_minutes, minutes)
        minutes < 1440 -> stringResource(Res.string.time_hours, minutes / 60)
        else -> stringResource(Res.string.time_days, minutes / 1440)
    }
}

@Composable
private fun ChatBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.testTag("chat-banner").padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** O servidor guarda até 99 respostas por agente (REPLIES_LIMIT): daí para cima o balão mostra "99+". */
private const val REPLIES_SHOWN = 99
/** Quanto puxar além do topo da lista para revelar a busca: grande o bastante para não acontecer por acidente. */
private val REVEAL_PULL = 120.dp
/** Puxada para abrir o bate-papo paralelo: maior que a da busca, porque o fim da conversa é onde mais se rola. */
private val ASIDE_PULL = 180.dp
/** Respostas até este tamanho já cabem em poucas frases: ficam inteiras, sem pedir resumo. */
private const val SUMMARY_MIN_CHARS = 500
/** O servidor aceita até 32 Ki caracteres; o resumo de uma resposta maior olha só o começo dela. */
private const val SUMMARY_MAX_CHARS = 32 * 1024
/** O começo sem modelo junta parágrafos até passar deste tamanho e corta neste outro. */
private const val BRIEF_MIN = 160
private const val BRIEF_MAX = 400
private val LIST_MARK = Regex("^([-*+]|\\d+[.)])\\s+")

/**
 * Com "Resumir respostas" desligado, a resposta longa mostra o começo em texto corrido, na hora e sem modelo: os
 * agentes respondem com a conclusão primeiro. Pula blocos de código, títulos e tabelas; começa no primeiro parágrafo
 * em prosa. Vazio quando não sobra texto (resposta só de código): fica a resposta inteira.
 */
internal fun replyBrief(text: String): String {
    val paragraphs = mutableListOf<List<String>>()
    var lines = mutableListOf<String>()
    var fence = false
    fun flush() { if (lines.isNotEmpty()) paragraphs += lines; lines = mutableListOf() }
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("```") -> { fence = !fence; flush() }
            fence -> {}
            line.isEmpty() -> flush()
            else -> lines += line
        }
    }
    flush()
    val start = paragraphs.indexOfFirst { it[0][0] !in "|#>" && !LIST_MARK.containsMatchIn(it[0]) }.coerceAtLeast(0)
    val out = StringBuilder()
    for (paragraph in paragraphs.drop(start)) {
        if (out.length >= BRIEF_MIN) break
        for (line in paragraph) {
            if (line[0] == '|' || line[0] == '#') continue
            val plain = line.removePrefix(">").trim().replace(LIST_MARK, "").replace("**", "").replace("`", "")
            if (plain.isNotBlank()) out.append(' ').append(plain)
        }
    }
    val brief = out.toString().trim()
    if (brief.length <= BRIEF_MAX) return brief
    val head = brief.take(BRIEF_MAX - 1)
    val space = head.lastIndexOf(' ')
    return (if (space > BRIEF_MAX * 0.6) head.take(space) else head).trimEnd(' ', ',', ';', ':', '.', '-') + "…"
}

@Composable
private fun EntryView(entry: TerminalEntry, results: Map<String?, List<TerminalEntry>>, count: Int = 1, tools: List<TerminalEntry> = emptyList(), recovered: Boolean = false, cachedSummary: String? = null, summarize: (suspend (String) -> String)? = null, keepPlace: (before: Float, now: () -> Float) -> Unit = { _, _ -> }) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    var headerY by remember { mutableFloatStateOf(0f) }
    if (entry.kind == "system") {
        // Resposta cortada pelo provedor e retomada logo depois: o texto que veio fica na bolha acima; aqui só um aviso discreto.
        val text = if (recovered) stringResource(Res.string.response_interrupted) else entry.text
        SelectionContainer { Text(listOfNotNull(text, entry.at?.let(::entryTime)).joinToString(" · "), Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = if (recovered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.kind == "user") Arrangement.End else Arrangement.Start) {
        val isGroup = entry.kind == "tool" || entry.kind == "thinking"
        Surface(
            modifier = Modifier.fillMaxWidth(0.85f),
            shape = RoundedCornerShape(if (isGroup) 12.dp else 16.dp),
            color = if (isGroup) Color.Transparent else if (entry.kind == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isGroup) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        ) {
            Column(if (isGroup) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier.padding(12.dp)) {
                if (isGroup) {
                    val label = "${stringResource(if (entry.kind == "thinking") Res.string.thinking else Res.string.tools)} ($count)"
                    // Hora do último evento do grupo no cabeçalho (nas ferramentas, o último resultado também conta): dá
                    // para ver, sem expandir, se o agente segue trabalhando ou parou.
                    val last = if (entry.kind == "tool") (tools.mapNotNull { it.at } + tools.flatMap { results[it.id].orEmpty() }.mapNotNull { it.at }).maxOrNull() ?: entry.at else entry.at
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { headerY = it.positionInWindow().y }.clickable { val before = headerY; expanded = !expanded; keepPlace(before) { headerY } }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (entry.kind == "tool") Icons.Default.Build else Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        last?.let { Text(entryTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (expanded) SelectionContainer(Modifier.padding(top = 8.dp)) {
                        if (entry.kind == "thinking") {
                            Text(entry.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                        } else {
                            // Cada ferramenta do turno: nome, entrada e resultados, na ordem em que rodaram.
                            Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                tools.forEach { tool ->
                                    Column {
                                        tool.title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                                        Text(tool.text, fontFamily = FontFamily.Monospace)
                                        results[tool.id].orEmpty().forEach {
                                            Text(stringResource(Res.string.tool_result))
                                            Text(it.text, fontFamily = FontFamily.Monospace)
                                            if (it.truncated) Text(stringResource(Res.string.result_truncated))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Resposta longa com "Resumir respostas" ligado: mostra o resumo; tocar alterna com a resposta completa.
                    // Desligado: mostra o começo dela (replyBrief), sem modelo. Sem nenhum dos dois (curta, servidor
                    // recusou ou falhou, resposta só de código), fica a resposta completa como sempre.
                    val key = summaryKey(entry)
                    val wantsSummary = summarize != null && entry.kind == "assistant" && entry.text.length > SUMMARY_MIN_CHARS
                    var summary by remember(key) { mutableStateOf(cachedSummary) }
                    var summaryFailed by remember(key) { mutableStateOf(false) }
                    if (wantsSummary && summary == null && !summaryFailed) LaunchedEffect(key) {
                        try { summary = summarize!!(entry.text.take(SUMMARY_MAX_CHARS)) }
                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; summaryFailed = true }
                    }
                    val brief = remember(key, summarize == null) { if (summarize == null && entry.kind == "assistant" && entry.text.length > SUMMARY_MIN_CHARS) replyBrief(entry.text).ifEmpty { null } else null }
                    val summarized = wantsSummary && !summaryFailed || brief != null
                    val shown = summary ?: brief
                    if (summarized && !expanded) {
                        Column(Modifier.fillMaxWidth().onGloballyPositioned { headerY = it.positionInWindow().y }.clickable { val before = headerY; expanded = true; keepPlace(before) { headerY } }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (shown == null) Text(stringResource(Res.string.summary_loading), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                Text(shown, color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(Res.string.summary_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        if (summarized) Text(stringResource(Res.string.summary_collapse), Modifier.onGloballyPositioned { headerY = it.positionInWindow().y }.clickable { val before = headerY; expanded = false; keepPlace(before) { headerY } }.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                        if (entry.truncated) Text(stringResource(Res.string.result_truncated))
                    }
                }
                // Pensamento e ferramentas já mostram a hora no cabeçalho.
                if (entry.kind != "tool" && entry.kind != "thinking") entry.at?.let { Text(entryTime(it), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = (if (entry.kind == "user") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.7f)) }
            }
        }
    }
}

/** Uma linha da conversa. Nos grupos do turno: quantas entradas juntou e, no das ferramentas, quais. */
internal data class EntryRow(val entry: TerminalEntry, val count: Int = 1, val tools: List<TerminalEntry> = emptyList())

/**
 * Os pensamentos de um turno (até a próxima mensagem sua) viram uma linha só, no lugar do primeiro, com a contagem;
 * ao expandir, mostra o texto de todos. As ferramentas do turno idem: uma linha, no lugar da primeira, que ao expandir
 * mostra cada uma com os resultados. Os transcripts intercalam pensamento e ferramenta, então juntar só os seguidos
 * não basta. O resultado de uma ferramenta da lista não vira linha: aparece dentro do grupo (uma linha vazia ainda
 * ganharia o espaçamento da lista e espalharia as mensagens). Resultado sem a ferramenta na lista continua sozinho.
 */
internal fun groupTurn(entries: List<TerminalEntry>): List<EntryRow> {
    val toolIds = entries.filter { it.kind == "tool" }.mapTo(HashSet()) { it.id }
    val rows = mutableListOf<EntryRow?>()
    class Group(val first: TerminalEntry, val index: Int) {
        var count = 1
        var at = first.at
        val text = if (first.kind == "thinking") StringBuilder().apply {
            if (first.text.isNotBlank()) append(first.text)
        } else null
        val tools = if (first.kind == "tool") mutableListOf(first) else null

        fun add(entry: TerminalEntry) {
            count++
            at = entry.at ?: at
            if (text != null && entry.text.isNotBlank()) {
                if (text.isNotEmpty()) text.append("\n\n")
                text.append(entry.text)
            }
            tools?.add(entry)
        }

        fun finish() {
            val entry = if (count == 1) first else first.copy(text = text?.toString() ?: first.text, at = at)
            rows[index] = EntryRow(entry, count, tools ?: emptyList())
        }
    }
    var thinking: Group? = null
    var tools: Group? = null
    for (entry in entries) {
        if (entry.kind == "result" && entry.toolUseId in toolIds) continue
        if (entry.kind == "user") {
            thinking?.finish()
            tools?.finish()
            thinking = null
            tools = null
        }
        when (entry.kind) {
            "thinking" -> {
                val group = thinking
                if (group != null) group.add(entry) else {
                    thinking = Group(entry, rows.size)
                    rows.add(null)
                }
            }
            "tool" -> {
                val group = tools
                if (group != null) group.add(entry) else {
                    tools = Group(entry, rows.size)
                    rows.add(null)
                }
            }
            else -> rows.add(EntryRow(entry))
        }
    }
    thinking?.finish()
    tools?.finish()
    return rows.requireNoNulls()
}

/** "14:05" hoje; "02/11 14:05" em outro dia, no fuso do aparelho. */
internal fun entryTime(at: Long, now: Long = uiNowMillis()): String {
    return platformEntryTime(at, now)
}

/** Filtro da conversa: "prompts" = suas mensagens e as respostas do agente; "no_tools" = tudo menos ferramentas e resultados. */
internal fun visibleEntries(entries: List<TerminalEntry>, view: String): List<TerminalEntry> = entries.filter { entry ->
    when (view) {
        "prompts" -> entry.kind == "user" || entry.kind == "assistant"
        "no_tools" -> entry.kind != "tool" && entry.kind != "result"
        else -> true
    }
}

/**
 * Erros de resposta (`<id>:err`) seguidos de mais trabalho do agente no mesmo turno: a conexão com o provedor caiu
 * e o omp continuou. Erro sem continuação até a próxima mensagem sua não entra (esse fica visível como erro).
 */
internal fun recoveredErrors(entries: List<TerminalEntry>): Set<String> {
    val recovered = mutableSetOf<String>()
    var pending = mutableListOf<String>()
    for (entry in entries) when {
        entry.kind == "user" -> pending = mutableListOf()
        entry.kind == "system" && entry.id.endsWith(":err") -> pending += entry.id
        entry.kind in setOf("assistant", "tool", "thinking") -> { recovered += pending; pending = mutableListOf() }
    }
    return recovered
}

/** Informações da sessão: cabeçalho, um cartão com o essencial e os detalhes técnicos recolhidos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionInfoSheet(api: HabblaudApi, session: SessionItem, entries: List<TerminalEntry>, truncated: Boolean, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CharacterAvatar(api, session, 72.dp)
                Text(session.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(session.characterName?.takeIf { it != session.title }, session.project?.substringAfterLast('/')).joinToString(" · ")
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val state = if (session.agentId == null) stringResource(Res.string.info_closed) else statusLabel(session.status) ?: stringResource(Res.string.info_open)
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(state, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    session.model?.let { InfoLine(Res.string.model_menu, it.substringAfter('/')) }
                    if (ompTools(session.key.provider)) session.thinking?.let { InfoLine(Res.string.thinking_menu, thinkingLabel(it)) }
                    if (reliableContext(session.key.provider, session.contextTokens, session.contextWindow)) {
                        val fraction = (session.contextTokens!!.toFloat() / session.contextWindow!!).coerceIn(0f, 1f)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            InfoLine(Res.string.info_context, "${(fraction * 100).toInt()}% · ${session.contextTokens!! / 1000} mil de ${session.contextWindow!! / 1000} mil")
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                    }
                    session.lastAt?.let { InfoLine(Res.string.info_last_activity, relativeTime(it)) }
                    InfoLine(Res.string.info_loaded, stringResource(Res.string.info_loaded_value, entries.count { it.kind == "user" }, entries.count { it.kind == "assistant" }) + if (truncated) " · " + stringResource(Res.string.info_partial) else "")
                }
            }
            var details by remember { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(stringResource(if (details) Res.string.info_hide_details else Res.string.info_show_details)) }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    session.project?.let { InfoLine(Res.string.info_project, it) }
                    InfoLine(Res.string.info_provider, "${session.key.provider} · ${session.key.account}")
                    InfoLine(Res.string.info_session, session.key.sessionId)
                    InfoLine(Res.string.info_server, session.key.origin)
                }
            }
        }
    }
}

/** Rótulo à esquerda, valor à direita, numa linha. */
@Composable
private fun InfoLine(label: StringResource, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** Cópia de OMP_THINKING_LEVELS em shared/messages.ts: o servidor recusa qualquer outro valor. */
internal val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

@Composable
internal fun thinkingLabel(level: String): String = when (level) {
    "off" -> stringResource(Res.string.thinking_off)
    "minimal" -> stringResource(Res.string.thinking_minimal)
    "low" -> stringResource(Res.string.thinking_low)
    "medium" -> stringResource(Res.string.thinking_medium)
    "high" -> stringResource(Res.string.thinking_high)
    "xhigh" -> stringResource(Res.string.thinking_xhigh)
    "max" -> stringResource(Res.string.thinking_max)
    else -> level
}

/** Campo em pílula preenchida, sem linha de foco: busca e (plano 061) mensagem. */
@Composable
internal fun pillFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
