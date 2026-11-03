package br.com.xlinx.habblaud

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.AgentEvent
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPrivacyInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun publicVersionIsGenericAndPrivateClickOpensSession() {
        fun session(number: Int, question: Boolean = false) = SessionItem(
            key = SessionKey("http://127.0.0.1:14748", "claude", ".claude", "session-$number"),
            agentId = "agent-$number", title = "Título secreto $number", project = "/secret/Projeto$number",
            status = "idle", activity = null, permissionQuestion = question, characterName = "Contato secreto $number",
        )
        val events = listOf(
            AgentEvent.Permission(session(1)),
            AgentEvent.Permission(session(2, question = true)),
            AgentEvent.TurnDone(session(3)),
        )
        val texts = listOf(R.string.notify_permission, R.string.notify_question, R.string.notify_done)
        val notifications = events.mapIndexed { index, event ->
            val (id, notification) = alertNotification(context, event)
            val item = event.session
            assertEquals(item.key.serialize().hashCode(), id)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("alerts", notification.channelId)
            assertEquals(item.characterName, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(texts[index]), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(item.project!!.substringAfterLast('/'), notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            assertNotNull(notification.contentIntent)
            val public = notification.publicVersion
            assertNotNull(public)
            assertEquals(context.getString(R.string.app_name), public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(R.string.notify_public), public.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertNull(public.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            assertNull(public.contentIntent)
            val publicText = public.extras.keySet().joinToString(" ") { public.extras.get(it).toString() }
            listOf(item.characterName!!, item.title, item.project!!, item.project!!.substringAfterLast('/'))
                .plus(texts.map(context::getString)).forEach { assertFalse(publicText.contains(it)) }
            id to notification
        }
        assertNotEquals(notifications[0].first, notifications[1].first)

        val previous = MainActivity.pendingSession.value
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            MainActivity.pendingSession.value = null
            notifications[0].second.contentIntent.send()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(events[0].session.key, MainActivity.pendingSession.value)
        } finally {
            scenario?.close()
            MainActivity.pendingSession.value = previous
        }
    }
}
