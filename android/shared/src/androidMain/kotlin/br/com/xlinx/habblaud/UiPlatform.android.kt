package br.com.xlinx.habblaud

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
@Composable actual fun rememberAttachmentPicker(onResult: (List<String>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { urls -> callback(urls.map { it.toString() }) }
    return { picker.launch(arrayOf("*/*")) }
}
@Composable actual fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
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
