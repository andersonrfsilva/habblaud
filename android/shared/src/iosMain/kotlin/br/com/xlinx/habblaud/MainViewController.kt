@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package br.com.xlinx.habblaud

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import br.com.xlinx.habblaud.data.retainPickedDocuments
import br.com.xlinx.habblaud.data.discardPickedDocuments

private val LocalPresenter = staticCompositionLocalOf<UIViewController> { error("Compose host controller is missing") }

fun MainViewController(): UIViewController {
    lateinit var controller: UIViewController
    controller = ComposeUIViewController {
    var foreground by remember { mutableStateOf(UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val stopped = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { foreground = false }
        val started = center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { foreground = true }
        onDispose { center.removeObserver(stopped); center.removeObserver(started) }
    }
    CompositionLocalProvider(
        LocalPresenter provides controller,
        LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, _ -> error("Notifications are unavailable on iOS") }),
    ) {
        if (foreground) ZaplaudApp()
    }
    }
    return controller
}

// Navigation always has an explicit accessible back control on iPad.
@Composable actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) { }
@Composable actual fun PlatformSearchShortcut(enabled: Boolean, onSearch: () -> Unit) { }
actual fun platformSendsOnEnter(): Boolean = false
actual fun platformPullGestures(): Boolean = true
@Composable actual fun animationsEnabled(): Boolean {
    var enabled by remember { mutableStateOf(!UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val token = NSNotificationCenter.defaultCenter.addObserverForName(UIAccessibilityReduceMotionStatusDidChangeNotification, null, NSOperationQueue.mainQueue) {
            enabled = !UIAccessibilityIsReduceMotionEnabled()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(token) }
    }
    return enabled
}
@Composable actual fun rememberAttachmentPicker(onResult: (List<String>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val presenter = LocalPresenter.current
    val pendingTokens = remember { mutableListOf<String>() }
    DisposableEffect(Unit) {
        onDispose { discardPickedDocuments(pendingTokens); pendingTokens.clear() }
    }
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val tokens = retainPickedDocuments(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
                pendingTokens.addAll(tokens)
                try { callback(tokens) }
                catch (error: Throwable) { discardPickedDocuments(tokens); pendingTokens.removeAll(tokens.toSet()); throw error }
            }
            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { callback(emptyList()) }
        }
    }
    return {
        discardPickedDocuments(pendingTokens)
        pendingTokens.clear()
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = false)
        picker.allowsMultipleSelection = true
        picker.delegate = delegate
        check(presenter.view.window != null) { "The document picker requires a visible application window" }
        var visiblePresenter = presenter
        while (visiblePresenter.presentedViewController != null) visiblePresenter = requireNotNull(visiblePresenter.presentedViewController)
        visiblePresenter.presentViewController(picker, animated = true, completion = null)
    }
}
actual fun uiNowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
private fun date(at: Long) = NSDate.dateWithTimeIntervalSince1970(at.toDouble() / 1000)
actual fun platformEntryTime(at: Long, now: Long): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.localeWithLocaleIdentifier("pt_BR")
    formatter.dateFormat = if (NSCalendar.currentCalendar.isDate(date(at), inSameDayAsDate = date(now))) "HH:mm" else "dd/MM HH:mm"
    return formatter.stringFromDate(date(at))
}
actual fun platformDeadline(at: Long): String {
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.localeWithLocaleIdentifier("pt_BR")
    formatter.dateStyle = NSDateFormatterShortStyle
    formatter.timeStyle = NSDateFormatterShortStyle
    return formatter.stringFromDate(date(at))
}
actual fun retryAfterDeadline(value: String?, now: Long): Long {
    val maximum = now + 86_400_000L
    val seconds = value?.toLongOrNull()?.coerceIn(0, 86_400)
    if (seconds != null) return now + seconds * 1000
    val formatter = NSDateFormatter()
    formatter.locale = NSLocale.localeWithLocaleIdentifier("en_US_POSIX")
    formatter.timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    formatter.dateFormat = "EEE',' dd MMM yyyy HH':'mm':'ss z"
    return value?.let { formatter.dateFromString(it) }?.let { (it.timeIntervalSince1970 * 1000).toLong().coerceIn(now, maximum) } ?: (now + 60_000)
}
