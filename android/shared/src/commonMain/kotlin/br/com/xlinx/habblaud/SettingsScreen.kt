package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import androidx.compose.ui.platform.testTag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import br.com.xlinx.habblaud.data.roleModelGroups
import br.com.xlinx.habblaud.data.providerLabel
import br.com.xlinx.habblaud.data.snapshotModels
import br.com.xlinx.habblaud.data.splitRole
import br.com.xlinx.habblaud.data.string
import br.com.xlinx.habblaud.data.wireJson
import br.com.xlinx.habblaud.data.serverLabel
import br.com.xlinx.habblaud.data.messageDelivery
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun catalogRuntime(body: String): String? =
    wireJson.parseToJsonElement(body).jsonObject.string("provider")?.takeIf { it == "omp" || it == "zeroclaw" }
internal fun summaryAvailable(summaryHttp: Boolean, roles: Map<String, String>?) = summaryHttp || roles?.get("tiny") != null
internal fun httpSummaryAvailable(body: String): Boolean =
    wireJson.parseToJsonElement(body).jsonObject["summaryHttp"]?.toString() == "true"
/** Configurações do aparelho. Visualização e modo de envio aqui são o padrão; cada sessão lembra a própria escolha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(apis: Map<String, HabblaudApi>, store: LocalStateStore, onClose: () -> Unit, onLogin: (String?) -> Unit, onLogout: (String) -> Unit) {
    val notificationsPlatform = LocalNotificationPlatform.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var origin by remember { mutableStateOf(apis.keys.first()) }
    LaunchedEffect(apis.keys) { if (origin !in apis) origin = apis.keys.first() }
    val api = apis[origin] ?: apis.values.first()
    PlatformBackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
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
    var quotas by remember(origin) { mutableStateOf<List<AccountQuota>?>(null) }
    var quotaError by remember(origin) { mutableStateOf(false) }
    // Modelos que os agentes omp abertos anunciam: são as opções do seletor dos papéis.
    var models by remember(origin) { mutableStateOf(emptyList<String>()) }
    var runtime by remember(api) { mutableStateOf<String?>(null) }
    var summaryHttp by remember(api) { mutableStateOf(false) }
    var authenticated by remember(api) { mutableStateOf(true) }
    LaunchedEffect(api, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!authenticated) return@repeatOnLifecycle
            while (true) {
                try {
                    runtime = catalogRuntime(api.request("/api/cast/models"))
                    summaryHttp = httpSummaryAvailable(api.request("/api/health"))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    runtime = null; summaryHttp = false
                    if (e is ApiException && e.status == 401) { authenticated = false; quotas = null; models = emptyList(); return@repeatOnLifecycle }
                }
                delay(10_000)
            }
        }
    }
    LaunchedEffect(api, authenticated, lifecycleOwner) {
        var unauthorized = false
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (unauthorized) return@repeatOnLifecycle
        if (!authenticated) return@repeatOnLifecycle
        while (true) {
            try { val snapshot = api.request("/api/snapshot"); quotas = parseAccounts(snapshot); models = snapshotModels(snapshot); quotaError = false }
            catch (e: Exception) { if (e is CancellationException) throw e; if (e is ApiException && e.status == 401) { unauthorized = true; authenticated = false; runtime = null; summaryHttp = false; quotas = null; models = emptyList(); return@repeatOnLifecycle }; quotaError = true }
            delay(60_000)
        }
        }
    }
    // Papéis do omp (global): o auxiliar do host lê e grava o modelRoles; recarrega enquanto a tela está aberta.
    var roles by remember(origin) { mutableStateOf<Map<String, String>?>(null) }
    var rolesStatus by remember(origin) { mutableStateOf("loading") }
    var rolesPending by remember(origin) { mutableStateOf(false) }
    var rolesRefresh by remember(origin) { mutableIntStateOf(0) }
    LaunchedEffect(api, authenticated, runtime, rolesRefresh, lifecycleOwner) {
        if (!ompTools(runtime) || !authenticated) { roles = null; rolesPending = false; return@LaunchedEffect }
        var unauthorized = false
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (unauthorized) return@repeatOnLifecycle
        while (true) {
            try {
                val r = wireJson.parseToJsonElement(api.request("/api/cast/roles")).jsonObject
                roles = (r["roles"] as? JsonObject)?.let { o -> EDITABLE_ROLES.mapNotNull { role -> o.string(role)?.let { role to it } }.toMap() }
                rolesPending = r["pending"]?.toString() == "true"
                rolesStatus = if (r["available"]?.toString() == "true") "available" else "unavailable"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.status == 401) { unauthorized = true; authenticated = false; runtime = null; summaryHttp = false; roles = null; return@repeatOnLifecycle }
                // Falha passageira com papéis já lidos: mantém a lista na tela e tenta de novo na próxima rodada.
                if (e is ApiException && e.status == 403) rolesStatus = "forbidden" else if (roles == null) rolesStatus = "error"
            }
            delay(if (rolesPending) 1_500 else 10_000)
        }
        }
    }
    var editingRole by remember(origin) { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime, authenticated) { if (!ompTools(runtime) || !authenticated) editingRole = null }
    var rolesError by remember(origin) { mutableStateOf(false) }
    fun saveRole(role: String, value: String) {
        if (!ompTools(runtime) || !authenticated) return
        rolesError = false; rolesPending = true
        scope.launch {
            try { api.request("/api/cast/roles", buildJsonObject { put("roles", buildJsonObject { put(role, value) }) }); rolesRefresh++ }
            catch (e: Exception) { if (e is CancellationException) throw e; rolesPending = false; rolesError = true; if (e is ApiException && e.status == 401) { authenticated = false; runtime = null; summaryHttp = false; roles = null } }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(Res.string.settings)) },
            navigationIcon = { val label = stringResource(Res.string.back); IconButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = label }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (apis.size > 1) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apis.keys.forEach { server ->
                    FilterChip(selected = origin == server, onClick = { origin = server }, label = { Text(serverLabel(server)) })
                }
            }
            Section(Res.string.settings_appearance)
            theme?.let { current ->
                listOf("system" to Res.string.theme_system, "light" to Res.string.theme_light, "dark" to Res.string.theme_dark).forEach { (value, label) ->
                    RadioRow(stringResource(label), current == value) { scope.launch { store.setTheme(value) } }
                }
            }

            Section(Res.string.settings_view_default)
            view?.let { current ->
                listOf("all" to Res.string.view_all, "prompts" to Res.string.view_prompts, "no_tools" to Res.string.view_no_tools).forEach { (value, label) ->
                    RadioRow(stringResource(label), current == value) { view = value; scope.launch { store.setDefaultView(value) } }
                }
            }

            Section(Res.string.settings_deliver_default)
            deliver?.let { current ->
                listOf("aside" to Res.string.deliver_aside, "followUp" to Res.string.deliver_follow_up, "steer" to Res.string.deliver_steer).filter { it.first in deliveryModes(runtime) }.forEach { (value, label) ->
                    RadioRow(stringResource(label), messageDelivery(runtime, current) == value) { deliver = value; scope.launch { store.setDeliverAs(value) } }
                }
            }
            Hint(Res.string.settings_per_session)

            Section(Res.string.settings_summaries)
            // Sem modelo escolhido para o papel Tiny não há resumo (o servidor recusa): o interruptor fica desligado.
            val hasTiny = authenticated && summaryAvailable(summaryHttp, roles.takeIf { ompTools(runtime) })
            summarize?.let { on -> SwitchRow(stringResource(Res.string.settings_summarize), on && hasTiny, enabled = hasTiny) { scope.launch { store.setSummarize(!on) } } }
            Hint(if (hasTiny) Res.string.settings_summarize_hint else if (ompTools(runtime)) Res.string.settings_summarize_needs_tiny else Res.string.settings_summarize_unavailable)

            if (ompTools(runtime) && authenticated) {
            Section(Res.string.settings_roles)
            when (rolesStatus) {
                "loading" -> Hint(Res.string.quota_loading)
                "forbidden" -> Hint(Res.string.cast_forbidden)
                "error" -> Hint(Res.string.roles_load_error)
                else -> {
                    EDITABLE_ROLES.forEach { role ->
                        val current = roles?.get(role)
                        val (model, level) = current?.let(::splitRole) ?: (null to null)
                        Row(Modifier.fillMaxWidth().clickable(enabled = rolesStatus == "available" && !rolesPending) { editingRole = role }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(roleLabel(role)), style = MaterialTheme.typography.bodyLarge)
                                Text(listOfNotNull(model?.let { "${providerLabel(it.substringBefore('/'))} · ${it.substringAfter('/')}" } ?: stringResource(Res.string.role_default), level?.let { thinkingLabel(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (rolesStatus == "unavailable") Hint(Res.string.cast_unavailable)
                    if (rolesPending) Hint(Res.string.roles_saving)
                    if (rolesError) Hint(Res.string.roles_error)
                    Hint(Res.string.roles_hint)
                }
            }
            editingRole?.let { role ->
                RoleDialog(role, roles?.get(role), models, onDismiss = { editingRole = null }) { value -> editingRole = null; saveRole(role, value) }
            }
            }

            if (notificationsPlatform.available) {
            Section(Res.string.notifications)
            notifications?.let { on ->
                SwitchRow(stringResource(if (notificationsPlatform.whileOpenOnly) Res.string.settings_notifications_open else Res.string.settings_notifications_on), on) {
                    notifications = !on
                    scope.launch { store.setNotificationsEnabled(!on) }
                    notificationsPlatform.setEnabledFromSettings(!on) { granted ->
                        notifications = granted
                        if (!granted && !on) scope.launch { store.setNotificationsEnabled(false) }
                    }
                }
                events?.let { (permission, done) ->
                    SwitchRow(stringResource(Res.string.settings_notify_permission), permission, enabled = on) { events = !permission to done; scope.launch { store.setNotifyEvents(!permission, done) } }
                    SwitchRow(stringResource(Res.string.settings_notify_done), done, enabled = on) { events = permission to !done; scope.launch { store.setNotifyEvents(permission, !done) } }
                }
            }
            if (!notificationsPlatform.whileOpenOnly) {
                TextButton(onClick = notificationsPlatform.openBackgroundSettings, Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.background_settings))
                }
            }

            }
            Section(Res.string.settings_quotas)
            when {
                quotas == null && !quotaError -> Hint(Res.string.quota_loading)
                quotas == null -> Hint(Res.string.quota_error)
                quotas!!.isEmpty() -> Hint(Res.string.quota_none)
                else -> quotas!!.forEach { QuotaCard(it) }
            }
            if (quotas != null && quotaError) Hint(Res.string.quota_error)

            Section(Res.string.settings_server)
            apis.keys.forEach { server ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(serverLabel(server), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onLogout(server) }) { Text(stringResource(Res.string.logout), color = MaterialTheme.colorScheme.error) }
                }
            }
            TextButton(onClick = { onLogin(null) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(Res.string.add_server)) }
        }
    }
}

@Composable
private fun Section(label: StringResource) = Text(stringResource(label), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(label: StringResource) = Text(stringResource(label), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
    "default" -> Res.string.role_main
    "task" -> Res.string.role_task
    "plan" -> Res.string.role_plan
    "tiny" -> Res.string.role_tiny
    else -> Res.string.role_advisor
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
                Text(stringResource(Res.string.role_pick_model), style = MaterialTheme.typography.titleSmall)
                roleModelGroups(models, keep = currentModel).forEach { (provider, list) ->
                    Text(providerLabel(provider), Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    list.forEach { m -> RadioRow(m.substringAfter('/'), m == currentModel) { model = m } }
                }
                if (models.isEmpty()) Text(stringResource(Res.string.role_no_models), style = MaterialTheme.typography.bodySmall)
                // Sem agente aberto não há lista: o modelo também pode ser digitado (o servidor confere o formato).
                // Só `provedor/modelo`: o raciocínio vem no passo seguinte, sem duplicar o `:nível`.
                val valid = ROLE_MODEL.matches(typed.trim()) && splitRole(typed.trim()).second == null
                OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text(stringResource(Res.string.role_type_model)) }, singleLine = true, isError = typed.isNotBlank() && !valid,
                    trailingIcon = { TextButton(onClick = { model = typed.trim() }, enabled = valid) { Text(stringResource(Res.string.role_use)) } })
            } else {
                Text(stringResource(Res.string.role_pick_thinking), style = MaterialTheme.typography.titleSmall)
                RadioRow(stringResource(Res.string.role_default), model == currentModel && currentLevel == null) { onSave(joinRole(model!!, null)) }
                THINKING_LEVELS.forEach { level -> RadioRow(thinkingLabel(level), model == currentModel && level == currentLevel) { onSave(joinRole(model!!, level)) } }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } })
}

/** Uma conta do servidor: nome, plano e situação; abaixo, cada provedor (omp) ou as janelas fixas, com barra e renovação. */
@Composable
private fun QuotaCard(account: AccountQuota) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOfNotNull(account.name, account.plan).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when (account.status) {
                    "stale" -> Text(stringResource(Res.string.quota_stale), style = MaterialTheme.typography.labelSmall, color = warningColor())
                    "disabled" -> Text(stringResource(Res.string.quota_disabled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (account.noQuota) Text(stringResource(Res.string.quota_no_quota), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            account.groups.forEach { group ->
                group.provider?.let { provider ->
                    Text(listOfNotNull(providerLabel(provider), group.plan).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                group.limits.forEach { LimitRow(it) }
                group.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            account.fetchedAt?.let { Text(stringResource(Res.string.quota_fetched, entryTime(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun LimitRow(limit: UsageLimit) {
    val label = when (limit.label) {
        WINDOW_FIVE_HOUR -> stringResource(Res.string.quota_five_hour)
        WINDOW_SEVEN_DAY -> stringResource(Res.string.quota_seven_day)
        WINDOW_SEVEN_DAY_OPUS -> stringResource(Res.string.quota_seven_day_opus)
        WINDOW_SEVEN_DAY_SONNET -> stringResource(Res.string.quota_seven_day_sonnet)
        else -> limit.label
    }
    val used = limit.utilization
    val fraction = ((used ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    val color = when { limit.exhausted || (used ?: 0.0) >= 90 -> MaterialTheme.colorScheme.error; (used ?: 0.0) >= 70 -> warningColor(); else -> MaterialTheme.colorScheme.primary }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(if (used == null) "—" else "${if (used >= 0) kotlin.math.floor(used + 0.5).toLong() else kotlin.math.ceil(used - 0.5).toLong()}%", style = MaterialTheme.typography.bodyMedium, color = if (limit.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        if (used != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = color, drawStopIndicator = {})
        limit.resetsAt?.let { Text(stringResource(Res.string.quota_resets, entryTime(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
