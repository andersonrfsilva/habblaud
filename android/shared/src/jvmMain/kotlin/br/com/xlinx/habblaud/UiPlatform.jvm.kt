package br.com.xlinx.habblaud
import androidx.compose.runtime.Composable
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) { TODO() }
@Composable actual fun rememberAttachmentPicker(onResult: (List<String>) -> Unit): () -> Unit = TODO()
@Composable actual fun animationsEnabled(): Boolean = true
actual fun uiNowMillis(): Long = System.currentTimeMillis()
actual fun platformEntryTime(at: Long, now: Long): String = TODO()
actual fun platformDeadline(at: Long): String = TODO()
actual fun retryAfterDeadline(value: String?, now: Long): Long = TODO()
