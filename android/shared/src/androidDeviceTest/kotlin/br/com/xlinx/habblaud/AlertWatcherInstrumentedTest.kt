package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.AgentEvent
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.initializeStorage
import br.com.xlinx.habblaud.data.watchAlerts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AlertWatcherInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun firstSnapshotIsBaselineOnly() {
        assertTrue(watch(stream(snapshot(permission = true))).isEmpty())
    }

    @Test fun permissionAppearingNotifies() {
        assertPermission(watch(stream(snapshot(permission = false) + snapshot(permission = true))))
    }

    @Test fun changeDuringReconnectNotifies() {
        val first = stream(snapshot(permission = false)).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
        assertPermission(watch(first, stream(snapshot(permission = true))))
    }

    @Test fun unauthorizedStopsWatching() {
        assertTrue(watch().isEmpty())
    }

    private fun watch(vararg responses: MockResponse): List<AgentEvent> = runBlocking {
        initializeStorage(context)
        val store = LocalStateStore(context)
        store.clear()
        val notificationsEnabled = store.notificationsEnabled()
        val (permission, done) = store.notifyEvents()
        try {
            MockWebServer().use { server ->
                server.start()
                responses.forEach(server::enqueue)
                // A final 401 lets the watcher finish after processing the queued snapshots.
                server.enqueue(MockResponse().setResponseCode(401))
                store.addServer(server.url("/").toString().removeSuffix("/"))
                store.setNotificationsEnabled(true)
                store.setNotifyEvents(permission = true, done = true)
                val events = mutableListOf<AgentEvent>()
                withTimeout(15_000) { watchAlerts(store, { assertEquals(1, it) }, { events.add(it) }) }
                assertEquals(responses.size + 1, server.requestCount)
                repeat(responses.size + 1) {
                    assertEquals("/api/stream", server.takeRequest(0, TimeUnit.MILLISECONDS)?.path)
                }
                events
            }
        } finally {
            store.clear()
            store.setNotificationsEnabled(notificationsEnabled)
            store.setNotifyEvents(permission, done)
        }
    }

    private fun assertPermission(events: List<AgentEvent>) {
        assertEquals(1, events.size)
        val event = events.single()
        assertTrue("Expected a permission event, got $event", event is AgentEvent.Permission)
        assertEquals("agent", event.session.agentId)
        assertEquals("approval", event.session.permissionId)
    }

    private fun snapshot(permission: Boolean): String {
        val approval = if (permission) ""","permission":{"id":"approval"}""" else ""
        return "event: snapshot\ndata: " +
            """{"agents":[{"id":"agent","kind":"main","account":"account","sessionId":"session"$approval}]}""" + "\n\n"
    }

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}
