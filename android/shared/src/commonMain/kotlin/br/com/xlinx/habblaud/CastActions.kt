package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import androidx.compose.ui.platform.testTag

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import io.ktor.http.encodeURLPathPart

private data class CastChoice(val id: String, val name: String, val avatar: Avatar?, val model: String? = null)
internal data class CastModel(val alias: String, val model: String)
internal data class CastModelCatalog(val runtime: String?, val models: List<CastModel>, val defaultModel: String?) {
    val canChoose: Boolean get() = runtime == "zeroclaw" && models.size > 1
}
internal fun parseCastModelCatalog(body: String): CastModelCatalog {
    val catalog = wireJson.parseToJsonElement(body).jsonObject
    val runtime = catalog.string("provider")?.takeIf { it == "omp" || it == "zeroclaw" }
    val models = if (runtime == "zeroclaw") catalog.objects("models").mapNotNull { entry ->
        val alias = entry.string("alias")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val model = entry.string("model")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CastModel(alias, model)
    } else emptyList()
    return CastModelCatalog(runtime, models, catalog.string("defaultModel"))
}
internal fun preselectedCastModel(catalog: CastModelCatalog, characterModel: String?): String? =
    catalog.models.firstOrNull { it.alias == characterModel || it.model == characterModel }?.alias
        ?: catalog.defaultModel?.takeIf { alias -> catalog.models.any { it.alias == alias } }

internal fun newCastBody(catalog: CastModelCatalog, character: Boolean, model: String? = null): JsonObject = buildJsonObject {
    if (character) put("mode", "new")
    if (catalog.canChoose && model != null && catalog.models.any { it.alias == model }) put("model", model)
}
private val sessionIdPattern = Regex("^[0-9a-fA-F][0-9a-fA-F-]{7,199}$")
internal fun canResumeCast(selected: SessionItem?, runtime: String?): Boolean =
    selected != null && selected.agentId == null && supportsSessionControl(selected.key.provider) &&
        selected.key.provider == runtime && selected.key.sessionId.matches(sessionIdPattern) && selected.project?.startsWith('/') == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastActions(api: HabblaudApi, selected: SessionItem?, connected: Boolean, openCast: Set<String>, onUnauthorized: () -> Unit, onPermission: () -> Unit, menu: @Composable (close: () -> Unit, dismissItem: @Composable () -> Unit) -> Unit, content: @Composable (@Composable () -> Unit, @Composable () -> Unit, @Composable () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var availability by remember(api) { mutableStateOf("loading") }
    var characters by remember(api) { mutableStateOf(emptyList<CastChoice>()) }
    var jobs by remember(api) { mutableStateOf(emptyMap<String, String>()) }
    var catalog by remember(api) { mutableStateOf(CastModelCatalog(null, emptyList(), null)) }
    val runtime = catalog.runtime
    var dialog by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<CastChoice?>(null) }
    var result by remember { mutableStateOf("") }
    // Motivo que o servidor mandou ao recusar (por exemplo "só 1 personagem trabalha por vez: dispense o atual…").
    var resultDetail by remember { mutableStateOf<String?>(null) }
    var submittedCast by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected?.key) { result = "" }
    // O elenco muda pouco: carrega ao conectar, depois de cada pedido e ao abrir a escolha; os pedidos, a cada 5 s.
    suspend fun loadCast() {
        catalog = parseCastModelCatalog(api.request("/api/cast/models"))
        characters = wireJson.parseToJsonElement(api.request("/api/cast")).jsonObject.objects("members")
            .filter { it.string("kind") == "main" }.mapNotNull { member ->
                val id = member.string("id") ?: return@mapNotNull null
                val name = member.string("name") ?: return@mapNotNull null
                CastChoice(id, name, parseAvatar(member), member.string("model"))
            }
    }
    LaunchedEffect(api, connected, refresh, lifecycleOwner) {
        if (!connected) { availability = "offline"; catalog = CastModelCatalog(null, emptyList(), null); return@LaunchedEffect }
        var castLoaded = false
        var unauthorized = false
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (unauthorized) return@repeatOnLifecycle
        while (true) {
            try {
                val jobResponse = wireJson.parseToJsonElement(api.request("/api/cast/jobs")).jsonObject
                availability = if (jobResponse.flag("available")) "available" else "unavailable"
                jobs = jobResponse.objects("jobs").mapNotNull { job ->
                    val id = job.string("cast") ?: return@mapNotNull null
                    val status = job.string("status") ?: return@mapNotNull null
                    id to status
                }.toMap()
                if (result == "pending") when (jobs[submittedCast]) {
                    "done" -> result = "done"
                    "failed" -> result = "failed"
                }
                if (availability == "available" && !castLoaded) { loadCast(); castLoaded = true }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) { unauthorized = true; catalog = CastModelCatalog(null, emptyList(), null); characters = emptyList(); onUnauthorized(); return@repeatOnLifecycle }
                availability = if (e is ApiException && e.status == 403) "forbidden" else "offline"
            }
            delay(5_000)
        }
        }
    }
    LaunchedEffect(dialog) {
        if (dialog != "start" && dialog != "resume-select") return@LaunchedEffect
        try { loadCast() } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ApiException && e.status == 401) onUnauthorized()
        }
    }
    fun submit(path: String, body: JsonObject) {
        if (sending) return
        sending = true; result = ""; resultDetail = null
        scope.launch {
            try {
                val response = wireJson.parseToJsonElement(api.request(path, body)).jsonObject
                submittedCast = (response["job"] as? JsonObject)?.string("cast")
                result = "pending"; dialog = ""; refresh++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) onUnauthorized()
                resultDetail = (e as? ApiException)?.takeIf { it.status in 400..499 && it.status != 401 }?.detail?.takeIf { it.isNotBlank() }
                result = when {
                    e is ApiException && e.status == 409 -> "busy"
                    e is ApiException && e.status == 403 -> "forbidden"
                    e is ApiException && e.status in 400..499 -> "failed"
                    else -> "unknown"
                }
                dialog = ""; refresh++
            } finally { sending = false }
        }
    }
    fun start(member: CastChoice?, model: String? = null) {
        submit(if (member == null) "/api/cast/new" else "/api/cast/${member.id.encodeURLPathPart()}/start", newCastBody(catalog, member != null, model))
    }
    fun chooseStart(member: CastChoice?) {
        choice = member
        if (catalog.canChoose) dialog = "start-model" else start(member)
    }
    val current = selected?.cast?.let { id -> characters.find { it.id == id } }
    // Quem foi chamado como "Novo" não está no elenco: o pedido e o Dispensar seguem pelo id `novo-…` do agente.
    val newAgent = selected?.cast?.startsWith(NEW_CAST_PREFIX) == true
    val activeJob = selected?.cast?.takeIf { current != null || newAgent }?.let { jobs[it] in listOf("queued", "sent", "waiting") } == true
    // Só quem está livre (sem agente aberto nem pedido em andamento), como a reserva da web: o servidor recusaria os outros.
    val free = characters.filter { it.id !in openCast && jobs[it.id] !in listOf("queued", "sent", "waiting") }
    // Qualquer personagem livre pode continuar a conversa omp encerrada; o dono vem primeiro.
    val resumeChoices = free.sortedByDescending { it == current }
    // "Novo" também retoma: o ▶ não depende de haver personagem livre.
    val resume = canResumeCast(selected, runtime)
    val dismiss = selected?.agentId != null && selected.castOpened && (current != null || newAgent)
    val actions: @Composable () -> Unit = {
        if (availability == "available" && resume) ActionIcon(Res.string.cast_resume, Icons.Default.PlayArrow, !sending) { choice = null; dialog = "resume-select" }
        if (selected?.permission == true) PermissionAttentionButton(selected.permissionQuestion, onPermission)
        if (selected != null) Box {
            var open by remember { mutableStateOf(false) }
            val menuLabel = stringResource(Res.string.menu)
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = menuLabel) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                // A tela decide onde o "Dispensar" entra: só na página principal do menu, não nos submenus.
                menu({ open = false }) {
                    if (availability == "available" && dismiss) DropdownMenuItem(
                        text = { Text(stringResource(Res.string.cast_dismiss)) },
                        leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                        enabled = !sending && !activeJob,
                        onClick = { open = false; dialog = "dismiss" },
                    )
                }
            }
        }
    }
    val fab: @Composable () -> Unit = {
        if (availability == "available") {
            val label = stringResource(Res.string.cast_start)
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
                FloatingActionButton(onClick = { if (!sending) { choice = null; dialog = "start" } }) { Icon(painterResource(Res.drawable.ic_person_add), contentDescription = label) }
            }
        }
    }
    val status: @Composable () -> Unit = {
        if (availability == "available" && activeJob) Text(stringResource(Res.string.cast_pending), style = MaterialTheme.typography.bodySmall)
        else if (availability == "unavailable" || availability == "forbidden") Text(stringResource(if (availability == "forbidden") Res.string.cast_forbidden else Res.string.cast_unavailable), style = MaterialTheme.typography.bodySmall)
        if (result.isNotEmpty()) Text(resultDetail?.takeIf { result == "busy" || result == "failed" || result == "forbidden" } ?: stringResource(when (result) { "pending" -> Res.string.cast_pending; "done" -> Res.string.cast_done; "busy" -> Res.string.cast_busy; "forbidden" -> Res.string.cast_forbidden; "failed" -> Res.string.cast_failed; else -> Res.string.cast_unknown }), style = MaterialTheme.typography.bodySmall)
    }
    content(actions, fab, status)
    if (dialog == "start") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(Res.string.cast_select_character)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            // "Novo": agente fora do elenco, com nome e visual sorteados pelo servidor.
            val newLabel = stringResource(Res.string.cast_new)
            Row(Modifier.fillMaxWidth().clickable(enabled = !sending) { chooseStart(null) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.ic_person_add), contentDescription = null) }
                Text(newLabel)
            }
            free.forEach { member ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !sending) {
                    chooseStart(member)
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterAvatar(api, member.avatar, member.name, 40.dp)
                    Text(member.name)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(Res.string.cancel)) } })
    if (dialog == "start-model") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(Res.string.role_pick_model)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            val preselected = preselectedCastModel(catalog, choice?.model)
            catalog.models.forEach { model ->
                val selectedModel = model.alias == preselected
                Row(Modifier.fillMaxWidth().selectable(selected = selectedModel, enabled = !sending, role = Role.RadioButton, onClick = {
                    start(choice, model.alias)
                }).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = selectedModel, onClick = null, enabled = !sending)
                    Text(modelLabel(model.model))
                }
            }
        }
    }, confirmButton = {}, dismissButton = {
        TextButton(onClick = { dialog = "start" }) { Text(stringResource(Res.string.back)) }
        TextButton(onClick = { dialog = "" }) { Text(stringResource(Res.string.cancel)) }
    })
    if (dialog == "resume-select") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(Res.string.cast_select_character)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            // "Novo": agente fora do elenco, com nome e visual sorteados pelo servidor, retoma a conversa.
            val newLabel = stringResource(Res.string.cast_new)
            Row(Modifier.fillMaxWidth().clickable { choice = CastChoice("", newLabel, null); dialog = "resume" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(painterResource(Res.drawable.ic_person_add), contentDescription = null) }
                Text(newLabel)
            }
            resumeChoices.forEach { member ->
                Row(Modifier.fillMaxWidth().clickable { choice = member; dialog = "resume" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterAvatar(api, member.avatar, member.name, 40.dp)
                    Text(member.name)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(Res.string.cancel)) } })
    if (dialog == "dismiss" || dialog == "resume") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(if (dialog == "dismiss") Res.string.cast_dismiss else Res.string.cast_resume)) }, text = {
        Text(if (dialog == "dismiss") stringResource(Res.string.cast_dismiss_confirm) else listOfNotNull(choice?.name, selected?.project).joinToString("\n"))
    }, confirmButton = {
        TextButton(enabled = !sending, onClick = {
            if (dialog == "dismiss") selected?.agentId?.let { submit("/api/agents/${it.encodeURLPathPart()}/dismiss", buildJsonObject {}) }
            else if (choice!!.id.isEmpty()) submit("/api/cast/new", buildJsonObject { put("room", selected!!.project); put("session", selected.key.sessionId); put("account", selected.key.account) })
            else submit("/api/cast/${choice!!.id.encodeURLPathPart()}/start", buildJsonObject { put("mode", "resume"); put("room", selected!!.project); put("session", selected.key.sessionId); put("account", selected.key.account) })
        }) { Text(stringResource(Res.string.cast_confirm)) }
    }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(Res.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionIcon(labelId: StringResource, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(labelId)
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionAttentionButton(question: Boolean, onClick: () -> Unit) {
    val color = if (animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "permission")
        val animated by transition.animateColor(Color(0xFF984000), Color(0xFFB44C00), infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "permission color")
        animated
    } else Color(0xFF984000)
    val label = stringResource(if (question) Res.string.permission_answer else Res.string.permission_approve)
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        FilledIconButton(onClick = onClick, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(painterResource(if (question) Res.drawable.ic_help else Res.drawable.ic_verified_user), contentDescription = label)
        }
    }
}
