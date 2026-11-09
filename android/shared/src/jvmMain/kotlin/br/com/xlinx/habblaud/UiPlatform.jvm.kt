package br.com.xlinx.habblaud

import androidx.compose.runtime.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.awt.FileDialog
import java.awt.Frame
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.attach_files
import org.jetbrains.compose.resources.stringResource

private class BackEntry(var enabled: Boolean, var onBack: () -> Unit)
private val backStack = mutableListOf<BackEntry>()
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val entry = remember { BackEntry(enabled, onBack) }
    SideEffect { entry.enabled = enabled; entry.onBack = onBack }
    DisposableEffect(entry) { backStack += entry; onDispose { backStack -= entry } }
}
/** Chamada pelo host ao soltar Esc; true se algum handler consumiu. */
fun dispatchDesktopBack(): Boolean = backStack.lastOrNull { it.enabled }?.let { it.onBack(); true } ?: false
@Composable actual fun rememberAttachmentPicker(onResult: (List<String>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val title = stringResource(Res.string.attach_files)
    return {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        try {
            dialog.isMultipleMode = true
            dialog.isVisible = true
            callback(dialog.files.map { it.toPath().toUri().toString() })
        } finally { dialog.dispose() }
    }
}
@Composable actual fun animationsEnabled(): Boolean = true
actual fun uiNowMillis(): Long = System.currentTimeMillis()
actual fun platformEntryTime(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone)
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) "HH:mm" else "dd/MM HH:mm"))
}
actual fun platformDeadline(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("pt-BR")).format(Date(at))
actual fun retryAfterDeadline(value: String?, now: Long): Long {
    val seconds = value?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000)
    return if (seconds != null) now + (seconds * 1000).coerceAtMost(Long.MAX_VALUE - now)
    else runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrDefault(now + 60_000)
}
