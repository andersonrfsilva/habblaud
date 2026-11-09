@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
package br.com.xlinx.habblaud
import br.com.xlinx.habblaud.resources.allStringResources

import androidx.compose.runtime.*
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.StringResource

val pendingSession = MutableStateFlow<SessionKey?>(null)

class NotificationPlatform(
    val available: Boolean,
    val setEnabled: (Boolean) -> Unit,
    val openBackgroundSettings: () -> Unit,
    val setEnabledFromSettings: (Boolean, (Boolean) -> Unit) -> Unit,
    val whileOpenOnly: Boolean = false,
)
val LocalNotificationPlatform = staticCompositionLocalOf<NotificationPlatform> {
    error("Android notification integration must be installed by the host")
}

@Composable expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
@Composable expect fun rememberAttachmentPicker(onResult: (List<String>) -> Unit): () -> Unit
@Composable expect fun animationsEnabled(): Boolean
expect fun uiNowMillis(): Long
expect fun platformEntryTime(at: Long, now: Long): String
expect fun platformDeadline(at: Long): String
expect fun retryAfterDeadline(value: String?, now: Long): Long
internal class UiStrings(private val values: Map<StringResource, String>) {
    operator fun invoke(resource: StringResource, vararg arguments: Any): String {
        val source = values.getValue(resource)
        if (arguments.isEmpty()) return source
        return FORMAT_TOKEN.replace(source) { match ->
            if (match.value == "%%") "%" else arguments[match.groupValues[1].toInt() - 1].toString()
        }
    }

    private companion object {
        val FORMAT_TOKEN = Regex("%%|%([0-9]+)\\$[sd]")
    }
}

@Composable
internal fun rememberUiStrings(): UiStrings? {
    val environment = org.jetbrains.compose.resources.rememberResourceEnvironment()
    var strings by remember { mutableStateOf<UiStrings?>(null) }
    LaunchedEffect(environment) {
        val resources = br.com.xlinx.habblaud.resources.Res.allStringResources.values
        val values = HashMap<StringResource, String>(resources.size)
        for (resource in resources) values[resource] = org.jetbrains.compose.resources.getString(environment, resource)
        strings = UiStrings(values)
    }
    return strings
}
