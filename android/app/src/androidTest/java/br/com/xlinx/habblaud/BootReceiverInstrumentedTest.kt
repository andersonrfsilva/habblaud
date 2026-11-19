package br.com.xlinx.habblaud

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.LocalStateStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootReceiverInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = LocalStateStore(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var previousPreference: Boolean? = null

    @Before fun prepare() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        NotificationService.stop(context)
        compose.waitUntil(5_000) { !hasServiceNotification() }
        previousPreference = compose.withFixture { store.notificationsEnabled().also { store.clear() } }
    }

    @After fun cleanUp() {
        NotificationService.stop(context)
        compose.waitUntil(5_000) { !hasServiceNotification() }
        compose.withFixture {
            store.clear()
            previousPreference?.let { store.setNotificationsEnabled(it) }
        }
    }

    @Test fun bootStartsForegroundServiceWhenConfigured() = assertRestarts(Intent.ACTION_BOOT_COMPLETED)

    @Test fun updateStartsForegroundServiceWhenConfigured() = assertRestarts(Intent.ACTION_MY_PACKAGE_REPLACED)

    @Test fun bootDoesNotStartWhenAlertsAreDisabled() = assertBootDoesNotStart(enabled = false, hasServer = true)

    @Test fun bootDoesNotStartWithoutServers() = assertBootDoesNotStart(enabled = true, hasServer = false)

    private fun assertRestarts(action: String) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            compose.withFixture {
                store.addServer(server.url("/").toString().removeSuffix("/"))
                store.setNotificationsEnabled(true)
            }
            // The empty activity allows foreground service starts without running MainActivity's startup path.
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { BootReceiver().onReceive(context, Intent(action)) }
                // Android can defer the first foreground-service notification for 10 seconds.
                compose.waitUntil(20_000) { hasServiceNotification() }
                NotificationService.stop(context)
            }
        }
    }

    private fun assertBootDoesNotStart(enabled: Boolean, hasServer: Boolean) {
        compose.withFixture {
            if (hasServer) store.addServer("http://127.0.0.1:14748")
            store.setNotificationsEnabled(enabled)
        }
        val attempted = AtomicBoolean()
        val observingContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                attempted.set(true)
                return super.startForegroundService(service)
            }
        }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { BootReceiver().onReceive(observingContext, Intent(Intent.ACTION_BOOT_COMPLETED)) }
            // Observe through the receiver's timeout, catching even a service that would stop itself immediately.
            compose.withFixture { delay(6_000) }
            assertFalse(attempted.get())
            assertFalse(hasServiceNotification())
        }
    }

    private fun hasServiceNotification(): Boolean = manager.activeNotifications.any {
        it.notification.channelId == "connection" &&
            (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
    }
}
