package br.com.xlinx.habblaud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStateStore(applicationContext)
        setContent {
            MaterialTheme {
                var url by remember { mutableStateOf("") }
                var user by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                var consent by remember { mutableStateOf(false) }
                var confirmedHost by remember { mutableStateOf<String?>(null) }
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
                            url = settings.origin; consent = settings.consent; confirmedHost = settings.confirmedHost
                            if (UrlPolicy.origin(settings.origin, settings.confirmedHost, settings.consent, BuildConfig.ALLOW_LOOPBACK_HTTP) != settings.origin) {
                                consent = false; confirmedHost = null
                                message = getString(R.string.invalid_url)
                            } else {
                                api = withContext(Dispatchers.IO) { HabblaudApi(applicationContext, settings.origin, settings.consent, settings.confirmedHost) }
                                try { api?.access(); connected = true } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    message = when (e) {
                                        is VpnRequiredException -> getString(R.string.vpn_required)
                                        is RedirectException -> getString(R.string.redirect_error)
                                        is ApiException -> if (e.status == 401) getString(R.string.login_error) else getString(R.string.http_error, e.status)
                                        else -> getString(R.string.network_error)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = getString(R.string.storage_error) }
                }
                DisposableEffect(Unit) { onDispose { api?.close() } }
                fun connect() {
                    val origin = UrlPolicy.origin(url, confirmedHost, consent, BuildConfig.ALLOW_LOOPBACK_HTTP)
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
                            if (saved?.origin != origin) { api?.close(); api?.cookies?.clear(); store.setConnection(origin, consent, confirmedHost); saved = ConnectionSettings(origin, consent, confirmedHost); api = null }
                            val current = api ?: withContext(Dispatchers.IO) { HabblaudApi(applicationContext, origin, consent, confirmedHost) }.also { api = it }
                            current.login(user, secret)
                            current.access()
                            store.setConnection(origin, consent, confirmedHost)
                            saved = ConnectionSettings(origin, consent, confirmedHost)
                            connected = true; message = ""
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            message = when (e) {
                                is VpnRequiredException -> getString(R.string.vpn_required)
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
                        api?.close(); api = null; saved = null; connected = false; busy = false
                        if (!revoked) message = getString(R.string.logout_unconfirmed)
                    }
                }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } })
                if (connected && api != null) {
                    ReadingScreen(api!!, store, onUnauthorized = { connected = false; message = getString(R.string.login_error) }, onLogout = { confirmLogout = true })
                } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.connection), style = MaterialTheme.typography.headlineMedium)
                    if (connected) {
                        Text(stringResource(R.string.connected, saved?.origin ?: ""))
                        Button(enabled = !busy, onClick = { confirmLogout = true }) { Text(stringResource(R.string.logout)) }
                    } else {
                        OutlinedTextField(url, { value ->
                            if (value != url) {
                                url = value; consent = false; confirmedHost = null
                                saved?.takeIf { it.consent || it.confirmedHost != null }?.let { settings ->
                                    saved = settings.copy(consent = false, confirmedHost = null)
                                    busy = true
                                    scope.launch {
                                        try { store.setConnection(settings.origin, false) }
                                        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = getString(R.string.storage_error) }
                                        finally { busy = false }
                                    }
                                }
                            }
                        }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(stringResource(R.string.server_url)) }, singleLine = true)
                        if (url.startsWith("http://", true) && !url.startsWith("http://localhost", true) && !url.startsWith("http://127.0.0.1")) {
                            Text(stringResource(R.string.http_warning))
                            Row { Checkbox(consent, { checked -> consent = checked; confirmedHost = if (checked) try { URI(url).host } catch (_: Exception) { null } else null }); Text(stringResource(R.string.accept_risk)) }
                        }
                        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.user)) }, singleLine = true)
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        Button(enabled = !busy && user.isNotEmpty() && password.isNotEmpty(), onClick = {
                            val target = UrlPolicy.origin(url, confirmedHost, consent, BuildConfig.ALLOW_LOOPBACK_HTTP)
                            if (target != null && saved != null && saved?.origin != target) confirmChange = true else connect()
                        }) { Text(stringResource(R.string.login)) }
                    }
                    if (busy) CircularProgressIndicator()
                    if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
                }
                }
            }
        }
    }
}
