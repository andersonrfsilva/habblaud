package br.com.xlinx.habblaud

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import br.com.xlinx.habblaud.data.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeStorage(applicationContext)
        openFromNotification(intent)
        enableEdgeToEdge()
        val store = LocalStateStore()
        setContent {
            val theme by store.theme.collectAsState(initial = "system")
            val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            SideEffect {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            var permissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) NotificationService.start(applicationContext)
                permissionResult?.invoke(granted)
                permissionResult = null
            }
            fun changeNotifications(enabled: Boolean, result: ((Boolean) -> Unit)?) {
                when {
                    !enabled -> { NotificationService.stop(applicationContext); result?.invoke(false) }
                    NotificationService.allowed(applicationContext) -> { NotificationService.start(applicationContext); result?.invoke(true) }
                    Build.VERSION.SDK_INT >= 33 -> {
                        permissionResult = result
                        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            val notifications = NotificationPlatform(
                available = true,
                setEnabled = { changeNotifications(it, null) },
                openBackgroundSettings = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                setEnabledFromSettings = { enabled, result -> changeNotifications(enabled, result) },
            )
            CompositionLocalProvider(LocalNotificationPlatform provides notifications) { ZaplaudApp(store) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openFromNotification(intent) }
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
        val pendingSession get() = br.com.xlinx.habblaud.pendingSession
    }
}
