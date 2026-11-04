package br.com.xlinx.habblaud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.*
import org.jetbrains.compose.resources.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun logoutError(cleaned: Boolean, revoked: Boolean): StringResource? = when {
    !cleaned -> Res.string.storage_error
    !revoked -> Res.string.logout_unconfirmed
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZaplaudApp(store: LocalStateStore = remember { LocalStateStore() }) {
    val notificationsPlatform = LocalNotificationPlatform.current
    val strings = rememberUiStrings() ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val theme by store.theme.collectAsState(initial = "system")
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
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
                var confirmHttpOrigin by remember { mutableStateOf<String?>(null) }
                var confirmLogout by remember { mutableStateOf(false) }
                var retryUntil by remember { mutableLongStateOf(0L) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    try {
                        saved = store.connection()
                        saved?.let { settings ->
                            url = settings.origin
                            if (UrlPolicy.origin(settings.origin) != settings.origin) {
                                message = strings(Res.string.invalid_url)
                            } else {
                                api = withContext(Dispatchers.IO) { HabblaudApi(settings.origin) }
                                try { api?.access(); connected = true } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    message = when (e) {
                                        is RedirectException -> strings(Res.string.redirect_error)
                                        is ApiException -> if (e.status == 401) "" else strings(Res.string.http_error, e.status)
                                        else -> strings(Res.string.network_error)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = strings(Res.string.storage_error) }
                }
                DisposableEffect(Unit) { onDispose { api?.close() } }
                fun connect() {
                    val origin = UrlPolicy.origin(url)
                    if (origin == null) { password = ""; message = strings(Res.string.invalid_url); return }
                    val secret = password
                    password = ""
                    busy = true
                    scope.launch {
                        try {
                            retryUntil = store.retryUntil(origin)
                            if (uiNowMillis() < retryUntil) {
                                message = strings(Res.string.rate_limited, ((retryUntil - uiNowMillis() + 999) / 1000).toString())
                                return@launch
                            }
                            if (saved?.origin != origin) { api?.close(); api?.cookies?.clear(); store.setConnection(origin); saved = ConnectionSettings(origin); api = null }
                            val current = api ?: withContext(Dispatchers.IO) { HabblaudApi(origin) }.also { api = it }
                            current.login(user, secret)
                            current.access()
                            store.setConnection(origin)
                            saved = ConnectionSettings(origin)
                            connected = true; message = ""
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            message = when (e) {
                                is RedirectException -> strings(Res.string.redirect_error)
                                is ApiException -> when (e.status) {
                                    401 -> strings(Res.string.login_error)
                                    403 -> strings(Res.string.forbidden)
                                    429 -> {
                                        val now = uiNowMillis()
                                        retryUntil = retryAfterDeadline(e.retryAfter, now)
                                        store.setRetryUntil(origin, retryUntil)
                                        strings(Res.string.rate_limited, e.retryAfter ?: "60")
                                    }
                                    else -> strings(Res.string.http_error, e.status)
                                }
                                is IllegalStateException -> strings(Res.string.storage_error)
                                else -> strings(Res.string.network_error)
                            }
                        } finally { busy = false }
                    }
                }
                fun requestLogin() {
                    val origin = UrlPolicy.origin(url)
                    if (origin == null) connect()
                    else if (origin.startsWith("http://")) confirmHttpOrigin = origin
                    else connect()
                }
                if (confirmChange) AlertDialog(onDismissRequest = { confirmChange = false }, title = { Text(stringResource(Res.string.change_server)) }, text = { Text(stringResource(Res.string.change_warning)) }, confirmButton = { TextButton(onClick = { confirmChange = false; requestLogin() }) { Text(stringResource(Res.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmChange = false }) { Text(stringResource(Res.string.cancel)) } })
                confirmHttpOrigin?.let { origin ->
                    AlertDialog(
                        onDismissRequest = { confirmHttpOrigin = null },
                        title = { Text(stringResource(Res.string.http_login_title)) },
                        text = { Text(stringResource(Res.string.http_login_warning, origin)) },
                        confirmButton = { TextButton(modifier = Modifier.testTag("http-confirm"), onClick = {
                            confirmHttpOrigin = null
                            if (UrlPolicy.origin(url) == origin) connect()
                        }) { Text(stringResource(Res.string.confirm)) } },
                        dismissButton = { TextButton(onClick = { confirmHttpOrigin = null }) { Text(stringResource(Res.string.cancel)) } }
                    )
                }
                if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text(stringResource(Res.string.logout)) }, text = { Text(stringResource(Res.string.change_warning)) }, confirmButton = { TextButton(onClick = {
                    confirmLogout = false
                    busy = true
                    scope.launch {
                        val revoked = api?.logout() ?: false
                        val cleaned = try { store.clear(); true } catch (_: Exception) { false }
                        notificationsPlatform.setEnabled(false)
                        api?.close(); api = null; saved = null; connected = false; busy = false
                        logoutError(cleaned, revoked)?.let { message = strings(it) }
                    }
                }) { Text(stringResource(Res.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(Res.string.cancel)) } })
                LaunchedEffect(connected) {
                    if (connected && store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
                }
                if (connected && api != null) {
                    ReadingScreen(api!!, store, onUnauthorized = { connected = false; message = strings(Res.string.login_error) }, onLogout = { confirmLogout = true })
                } else {
                Scaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.app_name)) }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.connection), style = MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("server-url"), enabled = !busy, label = { Text(stringResource(Res.string.server_url)) }, singleLine = true)
                        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().testTag("username"), label = { Text(stringResource(Res.string.user)) }, singleLine = true)
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("password"), label = { Text(stringResource(Res.string.password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(modifier = Modifier.fillMaxWidth().testTag("login-submit"), enabled = !busy && user.isNotEmpty() && password.isNotEmpty(), onClick = {
                            val target = UrlPolicy.origin(url)
                            if (target != null && saved != null && saved?.origin != target) confirmChange = true else requestLogin()
                        }) { Text(stringResource(Res.string.login)) }
                    if (busy) CircularProgressIndicator()
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                }
                }
                }
            }
}
