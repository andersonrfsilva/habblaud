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
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.launch

/** Configurações do aparelho. Visualização e modo de envio aqui são o padrão; cada sessão lembra a própria escolha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: LocalStateStore, origin: String, onClose: () -> Unit, onLogout: () -> Unit) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val theme by store.theme.collectAsState(initial = null)
    var view by remember { mutableStateOf<String?>(null) }
    var deliver by remember { mutableStateOf<String?>(null) }
    var notifications by remember { mutableStateOf<Boolean?>(null) }
    var events by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        view = store.defaultView(); deliver = store.deliverAs()
        notifications = store.notificationsEnabled(); events = store.notifyEvents()
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
