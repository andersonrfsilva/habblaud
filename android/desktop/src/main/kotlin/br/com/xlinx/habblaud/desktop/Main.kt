package br.com.xlinx.habblaud.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.com.xlinx.habblaud.LocalNotificationPlatform
import br.com.xlinx.habblaud.NotificationPlatform
import br.com.xlinx.habblaud.ZaplaudApp
import br.com.xlinx.habblaud.dispatchDesktopBack
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.ic_zaplaud
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    val state = rememberWindowState(size = DpSize(1100.dp, 760.dp))
    var alertsToClose by remember { mutableStateOf<DesktopAlerts?>(null) }
    Window(onCloseRequest = { alertsToClose?.close(); exitApplication() }, state = state, title = "Zaplaud",
        icon = painterResource(Res.drawable.ic_zaplaud),
        onKeyEvent = { e -> e.type == KeyEventType.KeyUp && e.key == Key.Escape && dispatchDesktopBack() }) {
        val alerts = remember(window) {
            DesktopAlerts({ window.isFocused }, { state.isMinimized = false; window.toFront() })
        }
        DisposableEffect(alerts) {
            alertsToClose = alerts
            onDispose { alerts.close() }
        }
        CompositionLocalProvider(
            LocalNotificationPlatform provides remember(alerts) {
                NotificationPlatform(available = true, setEnabled = alerts::setEnabled, openBackgroundSettings = {},
                    setEnabledFromSettings = { on, result -> alerts.setEnabled(on); result(on) }, whileOpenOnly = true)
            },
        ) { ZaplaudApp() }
    }
}
