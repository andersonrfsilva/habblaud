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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.com.xlinx.habblaud.LocalNotificationPlatform
import br.com.xlinx.habblaud.NotificationPlatform
import br.com.xlinx.habblaud.ZaplaudApp
import br.com.xlinx.habblaud.dispatchDesktopBack
import br.com.xlinx.habblaud.dispatchDesktopSearch
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.ic_zaplaud
import br.com.xlinx.habblaud.resources.tray_open
import br.com.xlinx.habblaud.resources.tray_quit
import dev.nucleusframework.composenativetray.tray.api.Tray
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** No Xwayland o app_id da janela é o WM_CLASS; "zaplaud" casa com zaplaud.desktop e o dock acha o ícone. Só existe no Linux (XToolkit). */
private fun linuxWmClass() = runCatching {
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(toolkit, "zaplaud")
}

fun main() { linuxWmClass(); application {
    val state = rememberWindowState(size = DpSize(1100.dp, 760.dp))
    var alertsToClose by remember { mutableStateOf<DesktopAlerts?>(null) }
    var frame by remember { mutableStateOf<ComposeWindow?>(null) }
    fun quit() { alertsToClose?.close(); exitApplication() }
    fun show() { state.isMinimized = false; frame?.let { it.toFront(); it.requestFocus() } }
    // Ícone na bandeja por StatusNotifierItem (DBus): é o que o waybar e o KDE mostram; a bandeja do AWT (XEmbed) não aparece neles.
    val openLabel = stringResource(Res.string.tray_open)
    val quitLabel = stringResource(Res.string.tray_quit)
    Tray(icon = Res.drawable.ic_zaplaud, tooltip = "Zaplaud", primaryAction = ::show) {
        Item(label = openLabel) { show() }
        Divider()
        Item(label = quitLabel) { quit() }
    }
    Window(onCloseRequest = ::quit, state = state, title = "Zaplaud",
        icon = painterResource(Res.drawable.ic_zaplaud),
        onKeyEvent = { e ->
            when {
                e.type == KeyEventType.KeyUp && e.key == Key.Escape -> dispatchDesktopBack()
                e.type == KeyEventType.KeyDown && e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed) -> dispatchDesktopSearch()
                else -> false
            }
        }) {
        DisposableEffect(window) { frame = window; onDispose { frame = null } }
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
} }
