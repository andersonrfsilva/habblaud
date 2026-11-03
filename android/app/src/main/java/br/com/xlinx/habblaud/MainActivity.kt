package br.com.xlinx.habblaud

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFromNotification(intent)
        enableEdgeToEdge()
        val store = LocalStateStore(applicationContext)
        setContent {
            val theme by store.theme.collectAsState(initial = "system")
            val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(
                primary = Color(0xFF9CDCC6), onPrimary = Color(0xFF00382A),
                primaryContainer = Color(0xFF195342), onPrimaryContainer = Color(0xFFB8F5DD),
                secondary = Color(0xFFACD2C4), onSecondary = Color(0xFF17372D),
                secondaryContainer = Color(0xFF294D40), onSecondaryContainer = Color(0xFFC9EEE0),
                surface = Color(0xFF171F1B), onSurface = Color(0xFFE0E9E2),
                background = Color(0xFF101814), onBackground = Color(0xFFE0E9E2),
                surfaceVariant = Color(0xFF3E4A42), onSurfaceVariant = Color(0xFFC1CEC4),
                surfaceContainerLowest = Color(0xFF0B120F), surfaceContainerLow = Color(0xFF171F1B),
                surfaceContainer = Color(0xFF1B241F), surfaceContainerHigh = Color(0xFF253029),
                surfaceContainerHighest = Color(0xFF303B34)
            ) else lightColorScheme(
                primary = Color(0xFF176B58), onPrimary = Color.White,
                primaryContainer = Color(0xFFD3EEE3), onPrimaryContainer = Color(0xFF002117),
                secondary = Color(0xFF416A60), secondaryContainer = Color(0xFFDCEFE6), onSecondaryContainer = Color(0xFF173C31),
                surface = Color.White, background = Color(0xFFF7FAF8),
                surfaceVariant = Color(0xFFE2EEE7), onSurfaceVariant = Color(0xFF3C4A43),
                surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F8F4),
                surfaceContainer = Color(0xFFEBF3EE), surfaceContainerHigh = Color(0xFFE3EFE7),
                surfaceContainerHighest = Color(0xFFDBEAE1)
            )) {
                var url by remember { mutableStateOf("") }
                var user by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var saved by remember { mutableStateOf<ConnectionSettings?>(null) }
                var api by remember { mutableStateOf<HabblaudApi?>(null) }
                var connected by remember { mutableStateOf(false) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                var confirmChange by remember { mutableStateOf(false) }
                var confirmLogout by remember { mutableStateOf(false) }
                var retryUntil by remember { mutableLongStateOf(0L) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    try {
                        saved = store.connection()
                        saved?.let { settings ->
                            url = settings.origin
                            if (UrlPolicy.origin(settings.origin) != settings.origin) {
                                message = getString(R.string.invalid_url)
                            } else {
                                api = withContext(Dispatchers.IO) { HabblaudApi(applicationContext, settings.origin) }
                                try { api?.access(); connected = true } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    message = when (e) {
                                        is RedirectException -> getString(R.string.redirect_error)
                                        is ApiException -> if (e.status == 401) "" else getString(R.string.http_error, e.status)
                                        else -> getString(R.string.network_error)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = getString(R.string.storage_error) }
                }
                DisposableEffect(Unit) { onDispose { api?.close() } }
                fun connect() {
                    val origin = UrlPolicy.origin(url)
                    if (origin == null) { password = ""; message = getString(R.string.invalid_url); return }
                    val secret = password
                    password = ""
                    busy = true
                    scope.launch {
                        try {
                            retryUntil = store.retryUntil(origin)
                            if (System.currentTimeMillis() < retryUntil) {
                                message = getString(R.string.rate_limited, ((retryUntil - System.currentTimeMillis() + 999) / 1000).toString())
                                return@launch
                            }
                            if (saved?.origin != origin) { api?.close(); api?.cookies?.clear(); store.setConnection(origin); saved = ConnectionSettings(origin); api = null }
                            val current = api ?: withContext(Dispatchers.IO) { HabblaudApi(applicationContext, origin) }.also { api = it }
                            current.login(user, secret)
                            current.access()
                            store.setConnection(origin)
                            saved = ConnectionSettings(origin)
                            connected = true; message = ""
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            message = when (e) {
                                is RedirectException -> getString(R.string.redirect_error)
                                is ApiException -> when (e.status) {
                                    401 -> getString(R.string.login_error)
                                    403 -> getString(R.string.forbidden)
                                    429 -> {
                                        val now = System.currentTimeMillis()
                                        val seconds = e.retryAfter?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000)
                                        retryUntil = if (seconds != null) now + (seconds * 1000).coerceAtMost(Long.MAX_VALUE - now) else try {
                                            ZonedDateTime.parse(e.retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now)
                                        } catch (_: Exception) { now + 60000 }
                                        store.setRetryUntil(origin, retryUntil)
                                        getString(R.string.rate_limited, e.retryAfter ?: "60")
                                    }
                                    else -> getString(R.string.http_error, e.status)
                                }
                                is IllegalStateException -> getString(R.string.storage_error)
                                else -> getString(R.string.network_error)
                            }
                        } finally { busy = false }
                    }
                }
                if (confirmChange) AlertDialog(onDismissRequest = { confirmChange = false }, title = { Text(stringResource(R.string.change_server)) }, text = { Text(stringResource(R.string.change_warning)) }, confirmButton = { TextButton(onClick = { confirmChange = false; connect() }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmChange = false }) { Text(stringResource(R.string.cancel)) } })
                if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text(stringResource(R.string.logout)) }, text = { Text(stringResource(R.string.change_warning)) }, confirmButton = { TextButton(onClick = {
                    confirmLogout = false
                    busy = true
                    scope.launch {
                        val revoked = api?.logout() ?: false
                        try { store.clear() } catch (_: Exception) { message = getString(R.string.storage_error) }
                        NotificationService.stop(applicationContext)
                        api?.close(); api = null; saved = null; connected = false; busy = false
                        if (!revoked) message = getString(R.string.logout_unconfirmed)
                    }
                }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } })
                // Sem permissão de notificar, o serviço só gastaria bateria: inicia apenas com ela concedida.
                val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) NotificationService.start(applicationContext) }
                LaunchedEffect(connected) {
                    if (!connected || !store.notificationsEnabled()) return@LaunchedEffect
                    if (NotificationService.allowed(applicationContext)) NotificationService.start(applicationContext)
                    else if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (connected && api != null) {
                    ReadingScreen(api!!, store, onUnauthorized = { connected = false; message = getString(R.string.login_error) }, onLogout = { confirmLogout = true })
                } else {
                Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.connection), style = MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.server_url)) }, singleLine = true)
                        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.user)) }, singleLine = true)
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && user.isNotEmpty() && password.isNotEmpty(), onClick = {
                            val target = UrlPolicy.origin(url)
                            if (target != null && saved != null && saved?.origin != target) confirmChange = true else connect()
                        }) { Text(stringResource(R.string.login)) }
                    if (busy) CircularProgressIndicator()
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    private fun openFromNotification(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SESSION)?.let { value -> runCatching { SessionKey.deserialize(value) }.getOrNull()?.let { pendingSession.value = it } }
        intent?.removeExtra(EXTRA_SESSION)
    }

    companion object {
        const val EXTRA_SESSION = "session"
        /** Com o app à vista, o próprio app mostra o pedido: o serviço não notifica. */
        @Volatile var visible = false
        /** Sessão tocada numa notificação, à espera de a lista carregar para abrir. */
        val pendingSession = kotlinx.coroutines.flow.MutableStateFlow<SessionKey?>(null)
    }
}
