package br.com.xlinx.habblaud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import java.util.Locale
internal fun permissionExpired(deadline: Long, now: Long) = deadline <= now
internal fun permissionClosed(status: Int) = status == 404 || status == 409


internal fun permissionDecision(provider: String, behavior: String, questions: List<JsonObject>, selections: Map<Int, Set<Int>>, others: Map<Int, String>, suggestion: Int? = null, interrupt: Boolean = false, message: String = ""): JsonObject {
    require(provider == "claude" || (suggestion == null && !interrupt))
    require(behavior in setOf("allow", "deny", "terminal", "answer"))
    return buildJsonObject {
        put("behavior", behavior)
        if (behavior == "allow" && suggestion != null) put("suggestion", suggestion)
        if (behavior == "deny") {
            if (message.isNotBlank()) put("message", message)
            if (interrupt) put("interrupt", true)
        }
        if (behavior == "answer") {
            require(provider == "claude" && questions.isNotEmpty())
            put("answers", buildJsonArray {
                questions.forEach { q ->
                    val index = requireNotNull(q["index"]?.jsonPrimitive?.intOrNull)
                    val selected = selections[index].orEmpty()
                    val other = others[index].orEmpty().trim()
                    val valid = q.objects("options").mapNotNull { it["index"]?.jsonPrimitive?.intOrNull }.toSet()
                    require(selected.all { it in valid } && (selected.isNotEmpty() || other.isNotEmpty()))
                    require(q.flag("multiSelect") || selected.size + (if (other.isNotEmpty()) 1 else 0) == 1)
                    add(buildJsonObject {
                        put("question", index)
                        if (selected.isNotEmpty()) put("options", buildJsonArray { selected.sorted().forEach { add(it) } })
                        if (other.isNotEmpty()) put("other", other)
                    })
                }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionSheet(api: HabblaudApi, session: SessionItem, allowed: Boolean, connected: Boolean, onUnauthorized: () -> Unit, onClose: () -> Unit) {
    val id = session.permissionId ?: return
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<JsonObject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var expired by remember(id) { mutableStateOf(false) }
    var ambiguous by remember(id) { mutableStateOf(false) }
    val selections = remember(id) { mutableStateMapOf<Int, Set<Int>>() }
    val others = remember(id) { mutableStateMapOf<Int, String>() }
    var suggestion by remember(id) { mutableStateOf<Int?>(null) }
    var interrupt by remember(id) { mutableStateOf(false) }
    var message by remember(id) { mutableStateOf("") }
    val path = "/api/permissions/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}"
    LaunchedEffect(id, connected, allowed) {
        detail = null
        if (!connected || !allowed) return@LaunchedEffect
        try {
            detail = wireJson.parseToJsonElement(api.request(path)).jsonObject
            val deadline = detail?.get("expiresAt")?.jsonPrimitive?.longOrNull ?: 0L
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
            expired = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ApiException && e.status == 401) onUnauthorized()
            else if (e is ApiException && permissionClosed(e.status)) onClose()
            else error = "network"
        }
    }
    val item = detail
    val provider = item?.string("provider") ?: "claude"
    val questions = item?.objects("questions").orEmpty()
    fun decide(behavior: String) {
        if (!allowed || !connected || busy || expired || ambiguous || item == null || permissionExpired(item["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0L, System.currentTimeMillis())) return
        val body = try { permissionDecision(provider, behavior, questions, selections, others, if (behavior == "allow") suggestion else null, behavior == "deny" && interrupt, message) } catch (_: IllegalArgumentException) { error = "answers"; return }
        busy = true
        scope.launch {
            try { api.request("$path/decision", org.json.JSONObject(body.toString())); onClose() }
            catch (e: Exception) {
                if (e is CancellationException) { ambiguous = true; throw e }
                if (e is ApiException && e.status == 401) onUnauthorized()
                else if (e is ApiException && permissionClosed(e.status)) onClose()
                else if (e is ApiException && e.status in 400..499) error = "decision"
                else { ambiguous = true; error = "unknown" }
            } finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.permission_pending), style = MaterialTheme.typography.titleLarge)
            Text(listOfNotNull(session.project?.substringAfterLast('/'), session.agentName, session.title).distinct().joinToString(" · "))
            if (!allowed) Text(stringResource(R.string.permissions_blocked))
            if (!connected) Text(stringResource(R.string.reading_offline))
            item?.let { request ->
                Text(request.string("tool").orEmpty()); Text(request.string("title").orEmpty())
                SelectionContainer { Text(request.string("input").orEmpty(), fontFamily = FontFamily.Monospace) }
                request["expiresAt"]?.jsonPrimitive?.longOrNull?.let { Text(stringResource(R.string.permission_deadline, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("pt-BR")).format(Date(it)))) }
                questions.forEach { q ->
                    val index = q["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val multi = q.flag("multiSelect")
                    Text(q.string("question").orEmpty())
                    q.objects("options").forEach { option ->
                        val optionIndex = option["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        Row {
                            Checkbox(checked = optionIndex in selections[index].orEmpty(), onCheckedChange = { checked ->
                                selections[index] = if (multi) { if (checked) selections[index].orEmpty() + optionIndex else selections[index].orEmpty() - optionIndex } else if (checked) setOf(optionIndex) else emptySet()
                                if (!multi && checked) others[index] = ""
                            })
                            Column { Text(option.string("label").orEmpty()); option.string("description")?.let { Text(it) } }
                        }
                    }
                    OutlinedTextField(others[index].orEmpty(), { others[index] = it; if (!multi && it.isNotEmpty()) selections[index] = emptySet() }, label = { Text(stringResource(R.string.permission_other)) })
                }
                if (provider == "claude" && questions.isEmpty()) {
                    request.objects("suggestions").forEach { s ->
                        val index = s["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val rules = runCatching { s["rules"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ") }.getOrNull().orEmpty()
                        val destination = stringResource(when (s.string("destination")) {
                            "session" -> R.string.permission_destination_session
                            "localSettings" -> R.string.permission_destination_local
                            "projectSettings" -> R.string.permission_destination_project
                            "userSettings" -> R.string.permission_destination_user
                            else -> R.string.permission_destination_unknown
                        })
                        Row { Checkbox(suggestion == index, { suggestion = if (it) index else null }); Text(stringResource(R.string.permission_suggestion, rules, destination)) }
                    }
                    Row { Checkbox(interrupt, { interrupt = it }); Text(stringResource(R.string.permission_interrupt)) }
                }
                if (questions.isEmpty()) OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.permission_reason)) })
            }
            if (expired) Text(stringResource(R.string.permission_expired))
            error?.let { Text(stringResource(when (it) { "answers" -> R.string.permission_answers; "unknown" -> R.string.permission_unknown; else -> R.string.network_error }), color = MaterialTheme.colorScheme.error) }
            val enabled = allowed && connected && item != null && !expired && !busy && !ambiguous
        Row {
            if (questions.isNotEmpty()) TextButton(onClick = { decide("answer") }, enabled = enabled) { Text(stringResource(R.string.permission_answer)) }
            else {
                TextButton(onClick = { decide("allow") }, enabled = enabled) { Text(stringResource(R.string.permission_allow)) }
                TextButton(onClick = { decide("deny") }, enabled = enabled) { Text(stringResource(R.string.permission_deny)) }
            }
            TextButton(onClick = { decide("terminal") }, enabled = enabled) { Text(stringResource(R.string.permission_terminal)) }
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        }
    }
}
