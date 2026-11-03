package br.com.xlinx.habblaud

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.net.URLEncoder

private data class CastChoice(val id: String, val name: String, val rooms: List<String>, val avatar: Avatar?)
private val sessionIdPattern = Regex("^[0-9a-fA-F][0-9a-fA-F-]{7,199}$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastActions(api: HabblaudApi, selected: SessionItem?, connected: Boolean, onUnauthorized: () -> Unit, onPermission: () -> Unit, content: @Composable (@Composable () -> Unit, @Composable () -> Unit, @Composable () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var availability by remember(api) { mutableStateOf("loading") }
    var characters by remember(api) { mutableStateOf(emptyList<CastChoice>()) }
    var folders by remember(api) { mutableStateOf(emptyList<String>()) }
    var jobs by remember(api) { mutableStateOf(emptyMap<String, String>()) }
    var dialog by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<CastChoice?>(null) }
    var result by remember { mutableStateOf("") }
    var submittedCast by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected?.key) { result = "" }
    LaunchedEffect(api, connected, refresh) {
        if (!connected) { availability = "offline"; return@LaunchedEffect }
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
                if (availability == "available") {
                    characters = wireJson.parseToJsonElement(api.request("/api/cast")).jsonObject.objects("members")
                        .filter { it.string("kind") == "main" }.mapNotNull { member ->
                            val id = member.string("id") ?: return@mapNotNull null
                            val name = member.string("name") ?: return@mapNotNull null
                            val rooms = (member["rooms"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                            CastChoice(id, name, rooms, parseAvatar(member))
                        }
                    folders = wireJson.parseToJsonElement(api.request("/api/cast/folders")).jsonObject.objects("folders").mapNotNull { it.string("path") }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) { onUnauthorized(); return@LaunchedEffect }
                availability = if (e is ApiException && e.status == 403) "forbidden" else "offline"
            }
            delay(5_000)
        }
    }
    fun submit(path: String, body: JSONObject) {
        if (sending) return
        sending = true; result = ""
        scope.launch {
            try {
                val response = wireJson.parseToJsonElement(api.request(path, body)).jsonObject
                submittedCast = (response["job"] as? JsonObject)?.string("cast")
                result = "pending"; dialog = ""; refresh++
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) onUnauthorized()
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
    val current = selected?.cast?.let { id -> characters.find { it.id == id } }
    val activeJob = current?.let { jobs[it.id] in listOf("queued", "sent", "waiting") } == true
    // Como no histórico da web: qualquer personagem do elenco que trabalha na pasta pode continuar a conversa omp encerrada; o dono vem primeiro.
    val eligible = if (selected?.project?.startsWith('/') == true) characters.filter { it.rooms.isEmpty() || selected.project in it.rooms } else emptyList()
    val resumeChoices = eligible.sortedByDescending { it == current }
    val resume = selected != null && selected.agentId == null && selected.key.provider == "omp" &&
        selected.key.sessionId.matches(sessionIdPattern) && resumeChoices.isNotEmpty()
    val dismiss = selected?.agentId != null && selected.castOpened && current != null
    val actions: @Composable () -> Unit = {
        if (availability == "available") {
            if (resume) ActionIcon(R.string.cast_resume, Icons.Default.PlayArrow, !sending && resumeChoices.any { jobs[it.id] !in listOf("queued", "sent", "waiting") }) { choice = null; dialog = "resume-select" }
            if (dismiss) ActionIcon(R.string.cast_dismiss, Icons.Default.ExitToApp, !sending && !activeJob) { dialog = "dismiss" }
        }
        if (selected?.permission == true) PermissionAttentionButton(selected.permissionQuestion, onPermission)
    }
    val fab: @Composable () -> Unit = {
        if (availability == "available" && selected == null) {
            val label = stringResource(R.string.cast_start)
            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
                FloatingActionButton(onClick = { if (!sending) { choice = null; dialog = "start" } }) { Icon(painterResource(R.drawable.ic_person_add), contentDescription = label) }
            }
        }
    }
    val status: @Composable () -> Unit = {
        if (availability == "available" && activeJob) Text(stringResource(R.string.cast_pending), style = MaterialTheme.typography.bodySmall)
        else if (availability == "unavailable" || availability == "forbidden") Text(stringResource(if (availability == "forbidden") R.string.cast_forbidden else R.string.cast_unavailable), style = MaterialTheme.typography.bodySmall)
        if (result.isNotEmpty()) Text(stringResource(when (result) { "pending" -> R.string.cast_pending; "done" -> R.string.cast_done; "busy" -> R.string.cast_busy; "forbidden" -> R.string.cast_forbidden; "failed" -> R.string.cast_failed; else -> R.string.cast_unknown }), style = MaterialTheme.typography.bodySmall)
    }
    content(actions, fab, status)
    if (dialog == "start") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(if (choice == null) R.string.cast_select_character else R.string.cast_select_folder)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (choice == null) characters.filter { jobs[it.id] !in listOf("queued", "sent", "waiting") }.forEach { member ->
                Row(Modifier.fillMaxWidth().clickable { choice = member }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterAvatar(api, member.avatar, member.name, 40.dp)
                    Text(member.name)
                }
            } else {
                val availableFolders = (folders + choice!!.rooms).distinct().filter { choice!!.rooms.isEmpty() || it in choice!!.rooms }
                if (availableFolders.isEmpty()) Text(stringResource(R.string.cast_no_folders))
                availableFolders.forEach { folder -> Text(folder, Modifier.fillMaxWidth().clickable {
                    val id = URLEncoder.encode(choice!!.id, "UTF-8")
                    submit("/api/cast/$id/start", JSONObject().put("mode", "new").put("room", folder))
                }.padding(12.dp)) }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel)) } })
    if (dialog == "resume-select") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(R.string.cast_select_character)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            resumeChoices.filter { jobs[it.id] !in listOf("queued", "sent", "waiting") }.forEach { member ->
                Row(Modifier.fillMaxWidth().clickable { choice = member; dialog = "resume" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CharacterAvatar(api, member.avatar, member.name, 40.dp)
                    Text(member.name)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel)) } })
    if (dialog == "dismiss" || dialog == "resume") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(stringResource(if (dialog == "dismiss") R.string.cast_dismiss else R.string.cast_resume)) }, text = {
        Text(if (dialog == "dismiss") stringResource(R.string.cast_dismiss_confirm) else listOfNotNull(choice?.name, selected?.project).joinToString("\n"))
    }, confirmButton = {
        TextButton(enabled = !sending, onClick = {
            if (dialog == "dismiss") submit("/api/agents/${URLEncoder.encode(selected!!.agentId, "UTF-8").replace("+", "%20")}/dismiss", JSONObject())
            else submit("/api/cast/${URLEncoder.encode(choice!!.id, "UTF-8").replace("+", "%20")}/start", JSONObject().put("mode", "resume").put("room", selected!!.project).put("session", selected.key.sessionId).put("account", selected.key.account))
        }) { Text(stringResource(R.string.cast_confirm)) }
    }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionIcon(labelId: Int, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(labelId)
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionAttentionButton(question: Boolean, onClick: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    var animationsEnabled by remember { mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    val color = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "permission")
        val animated by transition.animateColor(Color(0xFF984000), Color(0xFFB44C00), infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "permission color")
        animated
    } else Color(0xFF984000)
    val label = stringResource(if (question) R.string.permission_answer else R.string.permission_approve)
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        FilledIconButton(onClick = onClick, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = Color.White)) {
            Icon(painterResource(if (question) R.drawable.ic_help else R.drawable.ic_verified_user), contentDescription = label)
        }
    }
}
