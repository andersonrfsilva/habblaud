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
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

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
                var servers by remember { mutableStateOf<List<String>>(emptyList()) }
                val apis = remember { mutableStateMapOf<String, HabblaudApi>() }
                var needsLogin by remember { mutableStateOf<Set<String>>(emptySet()) }
                var loaded by remember { mutableStateOf(false) }
                var showLogin by remember { mutableStateOf(false) }
                var loginOrigin by remember { mutableStateOf<String?>(null) }
                var confirmLogout by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                var message by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                DisposableEffect(Unit) {
                    onDispose { apis.values.forEach { it.close() } }
                }
                LaunchedEffect(Unit) {
                    try {
                        servers = store.servers()
                        for (origin in servers) {
                            val api = withContext(Dispatchers.IO) { HabblaudApi(origin) }
                            apis[origin] = api
                            if (api.cookies.requestHeader("$origin/api/access") == null) {
                                needsLogin = needsLogin + origin
                            }
                            try {
                                api.access()
                                needsLogin = needsLogin - origin
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                if (e is ApiException && e.status == 401) needsLogin = needsLogin + origin
                            }
                        }
                        if (servers.isNotEmpty() && store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        message = strings(Res.string.storage_error)
                    } finally { loaded = true }
                }
                confirmLogout?.let { origin ->
                    AlertDialog(
                        onDismissRequest = { confirmLogout = null },
                        title = { Text(stringResource(Res.string.logout)) },
                        text = { Text(stringResource(Res.string.logout_server_warning, serverLabel(origin))) },
                        confirmButton = { TextButton(enabled = !busy, onClick = {
                            confirmLogout = null
                            busy = true
                            scope.launch {
                                try {
                                    withContext(NonCancellable) {
                                        val api = apis[origin]
                                        val revoked = try { api?.logout() ?: false } catch (_: Exception) { false }
                                        val cleaned = try { store.removeServer(origin); true } catch (_: Exception) { false }
                                        api?.close()
                                        apis.remove(origin)
                                        needsLogin = needsLogin - origin
                                        servers = servers - origin
                                        notificationsPlatform.setEnabled(false)
                                        if (servers.isNotEmpty() && store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
                                        message = logoutError(cleaned, revoked)?.let { strings(it) }.orEmpty()
                                    }
                                } finally { busy = false }
                            }
                        }) { Text(stringResource(Res.string.confirm)) } },
                        dismissButton = { TextButton(onClick = { confirmLogout = null }) { Text(stringResource(Res.string.cancel)) } }
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    Box(Modifier.weight(1f)) {
                        when {
                            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                            servers.isEmpty() || showLogin -> key(loginOrigin) {
                                LoginForm(
                                    initialUrl = loginOrigin.orEmpty(),
                                    store = store,
                                    onDone = { origin, api ->
                                        apis.put(origin, api)?.close()
                                        if (origin !in servers) servers = servers + origin
                                        needsLogin = needsLogin - origin
                                        showLogin = false
                                        loginOrigin = null
                                        message = ""
                                        scope.launch {
                                            if (store.notificationsEnabled()) notificationsPlatform.setEnabled(true)
                                        }
                                    },
                                    onCancel = if (servers.isEmpty()) null else ({
                                        showLogin = false
                                        loginOrigin = null
                                    })
                                )
                            }
                            else -> ReadingScreen(
                                apis = apis.toMap(),
                                needsLogin = needsLogin,
                                store = store,
                                onUnauthorized = { origin -> needsLogin = needsLogin + origin },
                                onLogin = { origin -> loginOrigin = origin; showLogin = true },
                                onLogout = { origin -> if (!busy) confirmLogout = origin }
                            )
                        }
                    }
                }
            }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginForm(
    initialUrl: String,
    store: LocalStateStore,
    onDone: (String, HabblaudApi) -> Unit,
    onCancel: (() -> Unit)?
) {
    val strings = rememberUiStrings() ?: return
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmHttpOrigin by remember { mutableStateOf<String?>(null) }
    var retryUntil by remember { mutableLongStateOf(0L) }
    var pendingApi by remember { mutableStateOf<HabblaudApi?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { pendingApi?.close() } }
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
                pendingApi?.close()
                pendingApi = null
                val current = withContext(Dispatchers.IO) { HabblaudApi(origin) }
                pendingApi = current
                current.login(user, secret)
                current.access()
                withContext(NonCancellable) {
                    store.addServer(origin)
                    onDone(origin, current)
                    pendingApi = null
                }
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
            } finally {
                pendingApi?.close()
                pendingApi = null
                busy = false
            }
        }
    }
    fun requestLogin() {
        val origin = UrlPolicy.origin(url)
        if (origin == null) connect()
        else if (origin.startsWith("http://")) confirmHttpOrigin = origin
        else connect()
    }
    confirmHttpOrigin?.let { origin ->
        AlertDialog(
            onDismissRequest = { confirmHttpOrigin = null },
            title = { Text(stringResource(Res.string.http_login_title)) },
            text = { Text(stringResource(Res.string.http_login_warning, origin)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("http-confirm"), onClick = {
                confirmHttpOrigin = null
                if (UrlPolicy.origin(url) == origin) connect()
            }) { Text(stringResource(Res.string.confirm)) } },
            dismissButton = { TextButton(modifier = Modifier.testTag("http-cancel"), onClick = { confirmHttpOrigin = null }) { Text(stringResource(Res.string.cancel)) } }
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(Res.string.app_name)) },
            navigationIcon = {
                if (onCancel != null) TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(Res.string.cancel)) }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.connection), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("server-url"), enabled = !busy, label = { Text(stringResource(Res.string.server_url)) }, singleLine = true)
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().testTag("username"), label = { Text(stringResource(Res.string.user)) }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("password"), label = { Text(stringResource(Res.string.password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(modifier = Modifier.fillMaxWidth().testTag("login-submit"), enabled = !busy && user.isNotEmpty() && password.isNotEmpty(), onClick = { requestLogin() }) { Text(stringResource(Res.string.login)) }
            if (busy) CircularProgressIndicator()
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
