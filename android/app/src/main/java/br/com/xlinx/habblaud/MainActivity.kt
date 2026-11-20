package br.com.xlinx.habblaud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: LocalStateStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeStorage(applicationContext)
        openFromNotification(intent)
        enableEdgeToEdge()
        store = LocalStateStore()
        setContent {
            val theme by store.theme.collectAsState(initial = "system")
            val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            var permissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val result = permissionResult
                permissionResult = null
                val started = granted && NotificationService.start(applicationContext)
                result?.invoke(started)
            }
            fun changeNotifications(enabled: Boolean, result: ((Boolean) -> Unit)?) {
                val started = try {
                    when {
                        !enabled -> { NotificationService.stop(applicationContext); false }
                        NotificationService.allowed(applicationContext) -> NotificationService.start(applicationContext)
                        Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                            permissionResult = result
                            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                            null
                        }
                        else -> false
                    }
                } catch (e: RuntimeException) {
                    permissionResult = null
                    Log.w(TAG, "Could not change notification delivery", e)
                    false
                }
                if (started != null) result?.invoke(started)
            }
            val notifications = NotificationPlatform(
                available = true,
                setEnabled = { changeNotifications(it, null) },
                openBackgroundSettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                setEnabledFromSettings = { enabled, result -> changeNotifications(enabled, result) },
                restart = { NotificationService.start(applicationContext, restart = true) },
                deliverable = { NotificationService.allowed(applicationContext) },
            )
            CompositionLocalProvider(LocalNotificationPlatform provides notifications) {
                AndroidMarkdownImages { ZaplaudApp(store) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openFromNotification(intent) }
    override fun onStart() {
        super.onStart()
        visible = true
        lifecycleScope.launch {
            try {
                NotificationService.startIfConfigured(applicationContext, store) { visible }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Could not read notification settings on start", e)
            }
        }
    }
    override fun onStop() { visible = false; super.onStop() }
    private fun openFromNotification(intent: Intent?) {
        intent?.getStringExtra(EXTRA_SESSION)?.let { value -> runCatching { SessionKey.deserialize(value) }.getOrNull()?.let { pendingSession.value = it } }
        intent?.removeExtra(EXTRA_SESSION)
    }
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_SESSION = "session"
        /** Com o app à vista, o próprio app mostra o pedido: o serviço não notifica. */
        @Volatile var visible = false
        /** Sessão tocada numa notificação, à espera de a lista carregar para abrir. */
        val pendingSession get() = br.com.xlinx.habblaud.pendingSession
    }
}
