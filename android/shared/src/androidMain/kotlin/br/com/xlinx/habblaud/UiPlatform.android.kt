package br.com.xlinx.habblaud

import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
@Composable actual fun PlatformSearchShortcut(enabled: Boolean, onSearch: () -> Unit) { }
actual fun platformSendsOnEnter(): Boolean = false
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
@Composable actual fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@remember ""
        }
        val name = info.versionName ?: return@remember ""
        "$name (${PackageInfoCompat.getLongVersionCode(info)})"
    }
}
actual fun uiNowMillis(): Long = System.currentTimeMillis()
actual fun platformEntryTime(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone)
    return time.format(DateTimeFormatter.ofPattern(if (time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) "HH:mm" else "dd/MM HH:mm"))
}
actual fun platformDeadline(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.forLanguageTag("pt-BR")).format(Date(at))
actual fun retryAfterDeadline(value: String?, now: Long): Long {
    val maximum = now + 86_400_000L
    val seconds = value?.toLongOrNull()?.coerceIn(0, 86_400)
    return if (seconds != null) now + seconds * 1000
    else runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceIn(now, maximum) }.getOrDefault(now + 60_000)
}
