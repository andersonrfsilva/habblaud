package br.com.xlinx.habblaud

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.AccountQuota
import br.com.xlinx.habblaud.data.ApiException
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.UsageLimit
import br.com.xlinx.habblaud.data.WINDOW_FIVE_HOUR
import br.com.xlinx.habblaud.data.WINDOW_SEVEN_DAY
import br.com.xlinx.habblaud.data.WINDOW_SEVEN_DAY_OPUS
import br.com.xlinx.habblaud.data.WINDOW_SEVEN_DAY_SONNET
import br.com.xlinx.habblaud.data.EDITABLE_ROLES
import br.com.xlinx.habblaud.data.ROLE_MODEL
import br.com.xlinx.habblaud.data.joinRole
import br.com.xlinx.habblaud.data.parseAccounts
import br.com.xlinx.habblaud.data.snapshotModels
import br.com.xlinx.habblaud.data.splitRole
import br.com.xlinx.habblaud.data.string
import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Configurações do aparelho. Visualização e modo de envio aqui são o padrão; cada sessão lembra a própria escolha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(api: HabblaudApi, store: LocalStateStore, onClose: () -> Unit, onLogout: () -> Unit) {
    val origin = api.origin
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val theme by store.theme.collectAsState(initial = null)
    val summarize by store.summarize.collectAsState(initial = null)
    var view by remember { mutableStateOf<String?>(null) }
    var deliver by remember { mutableStateOf<String?>(null) }
    var notifications by remember { mutableStateOf<Boolean?>(null) }
    var events by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        view = store.defaultView(); deliver = store.deliverAs()
        notifications = store.notificationsEnabled(); events = store.notifyEvents()
    }
    // Cotas: o servidor manda o uso das contas no snapshot; recarrega a cada minuto enquanto a tela está aberta.
    var quotas by remember { mutableStateOf<List<AccountQuota>?>(null) }
    var quotaError by remember { mutableStateOf(false) }
    // Modelos que os agentes omp abertos anunciam: são as opções do seletor dos papéis.
    var models by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        while (true) {
            try { val snapshot = api.request("/api/snapshot"); quotas = parseAccounts(snapshot); models = snapshotModels(snapshot); quotaError = false }
            catch (e: Exception) { if (e is CancellationException) throw e; if (e is ApiException && e.status == 401) return@LaunchedEffect; quotaError = true }
            delay(60_000)
        }
    }
    // Papéis do omp (global): o auxiliar do host lê e grava o modelRoles; recarrega enquanto a tela está aberta.
    var roles by remember { mutableStateOf<Map<String, String>?>(null) }
    var rolesStatus by remember { mutableStateOf("loading") }
    var rolesPending by remember { mutableStateOf(false) }
    var rolesRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(rolesRefresh) {
        while (true) {
            try {
                val r = wireJson.parseToJsonElement(api.request("/api/cast/roles")).jsonObject
                roles = (r["roles"] as? JsonObject)?.let { o -> EDITABLE_ROLES.mapNotNull { role -> o.string(role)?.let { role to it } }.toMap() }
                rolesPending = r["pending"]?.toString() == "true"
                rolesStatus = if (r["available"]?.toString() == "true") "available" else "unavailable"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) return@LaunchedEffect
                // Falha passageira com papéis já lidos: mantém a lista na tela e tenta de novo na próxima rodada.
                if (e is ApiException && e.status == 403) rolesStatus = "forbidden" else if (roles == null) rolesStatus = "error"
            }
            delay(if (rolesPending) 1_500 else 10_000)
        }
    }
    var editingRole by remember { mutableStateOf<String?>(null) }
    var rolesError by remember { mutableStateOf(false) }
    fun saveRole(role: String, value: String) {
        rolesError = false; rolesPending = true
        scope.launch {
            try { api.request("/api/cast/roles", JSONObject().put("roles", JSONObject().put(role, value))); rolesRefresh++ }
            catch (e: Exception) { if (e is CancellationException) throw e; rolesPending = false; rolesError = true }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) NotificationService.start(context)
        else { notifications = false; scope.launch { store.setNotificationsEnabled(false) } }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { val label = stringResource(R.string.back); IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = label }) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Section(R.string.settings_appearance)
            theme?.let { current ->
                listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark).forEach { (value, label) ->
                    RadioRow(stringResource(label), current == value) { scope.launch { store.setTheme(value) } }
                }
            }

            Section(R.string.settings_view_default)
            view?.let { current ->
                listOf("all" to R.string.view_all, "prompts" to R.string.view_prompts, "no_tools" to R.string.view_no_tools).forEach { (value, label) ->
                    RadioRow(stringResource(label), current == value) { view = value; scope.launch { store.setDefaultView(value) } }
                }
            }

            Section(R.string.settings_deliver_default)
            deliver?.let { current ->
                listOf("aside" to R.string.deliver_aside, "followUp" to R.string.deliver_follow_up, "steer" to R.string.deliver_steer).forEach { (value, label) ->
                    RadioRow(stringResource(label), current == value) { deliver = value; scope.launch { store.setDeliverAs(value) } }
                }
            }
            Hint(R.string.settings_per_session)

            Section(R.string.settings_summaries)
            summarize?.let { on -> SwitchRow(stringResource(R.string.settings_summarize), on) { scope.launch { store.setSummarize(!on) } } }
            Hint(R.string.settings_summarize_hint)

            Section(R.string.settings_roles)
            when (rolesStatus) {
                "loading" -> Hint(R.string.quota_loading)
                "forbidden" -> Hint(R.string.cast_forbidden)
                "error" -> Hint(R.string.roles_load_error)
                else -> {
                    EDITABLE_ROLES.forEach { role ->
                        val current = roles?.get(role)
                        val (model, level) = current?.let(::splitRole) ?: (null to null)
                        Row(Modifier.fillMaxWidth().clickable(enabled = rolesStatus == "available" && !rolesPending) { editingRole = role }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(roleLabel(role)), style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(model?.substringAfter('/') ?: stringResource(R.string.role_default), level?.let { thinkingLabel(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (rolesStatus == "unavailable") Hint(R.string.cast_unavailable)
                    if (rolesPending) Hint(R.string.roles_saving)
                    if (rolesError) Hint(R.string.roles_error)
                    Hint(R.string.roles_hint)
                }
            }
            editingRole?.let { role ->
                RoleDialog(role, roles?.get(role), models, onDismiss = { editingRole = null }) { value -> editingRole = null; saveRole(role, value) }
            }

            Section(R.string.notifications)
            notifications?.let { on ->
                SwitchRow(stringResource(R.string.settings_notifications_on), on) {
                    notifications = !on
                    scope.launch { store.setNotificationsEnabled(!on) }
                    when {
                        on -> NotificationService.stop(context)
                        NotificationService.allowed(context) -> NotificationService.start(context)
                        Build.VERSION.SDK_INT >= 33 -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                events?.let { (permission, done) ->
                    SwitchRow(stringResource(R.string.settings_notify_permission), permission, enabled = on) { events = !permission to done; scope.launch { store.setNotifyEvents(!permission, done) } }
                    SwitchRow(stringResource(R.string.settings_notify_done), done, enabled = on) { events = permission to !done; scope.launch { store.setNotifyEvents(permission, !done) } }
                }
            }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }, Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.background_settings))
            }

            Section(R.string.settings_quotas)
            when {
                quotas == null && !quotaError -> Hint(R.string.quota_loading)
                quotas == null -> Hint(R.string.quota_error)
                quotas!!.isEmpty() -> Hint(R.string.quota_none)
                else -> quotas!!.forEach { QuotaCard(it) }
            }
            if (quotas != null && quotaError) Hint(R.string.quota_error)

            Section(R.string.settings_server)
            Text(origin, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onLogout, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.logout), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Section(label: Int) = Text(stringResource(label), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(label: Int) = Text(stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun roleLabel(role: String) = when (role) {
    "task" -> R.string.role_task
    "plan" -> R.string.role_plan
    else -> R.string.role_advisor
}

/** Escolhe o modelo (os anunciados pelos agentes, o atual ou um `provedor/modelo` digitado) e depois o raciocínio do papel. */
@Composable
private fun RoleDialog(role: String, current: String?, models: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val (currentModel, currentLevel) = current?.let(::splitRole) ?: (null to null)
    var model by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(roleLabel(role))) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (model == null) {
                Text(stringResource(R.string.role_pick_model), style = MaterialTheme.typography.titleSmall)
                (listOfNotNull(currentModel) + models).distinct().forEach { m -> RadioRow(m.substringAfter('/'), m == currentModel) { model = m } }
                if (models.isEmpty()) Text(stringResource(R.string.role_no_models), style = MaterialTheme.typography.bodySmall)
                // Sem agente aberto não há lista: o modelo também pode ser digitado (o servidor confere o formato).
                // Só `provedor/modelo`: o raciocínio vem no passo seguinte, sem duplicar o `:nível`.
                val valid = ROLE_MODEL.matches(typed.trim()) && splitRole(typed.trim()).second == null
                OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(R.string.role_type_model)) }, singleLine = true, isError = typed.isNotBlank() && !valid,
                    trailingIcon = { TextButton(onClick = { model = typed.trim() }, enabled = valid) { Text(stringResource(R.string.role_use)) } })
            } else {
                Text(stringResource(R.string.role_pick_thinking), style = MaterialTheme.typography.titleSmall)
                RadioRow(stringResource(R.string.role_default), model == currentModel && currentLevel == null) { onSave(joinRole(model!!, null)) }
                THINKING_LEVELS.forEach { level -> RadioRow(thinkingLabel(level), model == currentModel && level == currentLevel) { onSave(joinRole(model!!, level)) } }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Uma conta do servidor: nome, plano e situação; abaixo, cada provedor (omp) ou as janelas fixas, com barra e renovação. */
@Composable
private fun QuotaCard(account: AccountQuota) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOfNotNull(account.name, account.plan).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when (account.status) {
                    "stale" -> Text(stringResource(R.string.quota_stale), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF6C00))
                    "disabled" -> Text(stringResource(R.string.quota_disabled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (account.noQuota) Text(stringResource(R.string.quota_no_quota), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            account.groups.forEach { group ->
                group.provider?.let { provider ->
                    Text(listOfNotNull(providerLabel(provider), group.plan).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                group.limits.forEach { LimitRow(it) }
                group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            account.fetchedAt?.let { Text(stringResource(R.string.quota_fetched, entryTime(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun LimitRow(limit: UsageLimit) {
    val label = when (limit.label) {
        WINDOW_FIVE_HOUR -> stringResource(R.string.quota_five_hour)
        WINDOW_SEVEN_DAY -> stringResource(R.string.quota_seven_day)
        WINDOW_SEVEN_DAY_OPUS -> stringResource(R.string.quota_seven_day_opus)
        WINDOW_SEVEN_DAY_SONNET -> stringResource(R.string.quota_seven_day_sonnet)
        else -> limit.label
    }
    val used = limit.utilization
    val fraction = ((used ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    val color = when { limit.exhausted || (used ?: 0.0) >= 90 -> MaterialTheme.colorScheme.error; (used ?: 0.0) >= 70 -> Color(0xFFEF6C00); else -> MaterialTheme.colorScheme.primary }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (used == null) "—" else "${"%.0f".format(used)}%", style = MaterialTheme.typography.bodyMedium, color = if (limit.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (used != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = color, drawStopIndicator = {})
        limit.resetsAt?.let { Text(stringResource(R.string.quota_resets, entryTime(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun providerLabel(id: String) = when (id) {
    "anthropic" -> "Anthropic"
    "openai-codex" -> "OpenAI Codex"
    "google-antigravity" -> "Google Antigravity"
    else -> id
}
