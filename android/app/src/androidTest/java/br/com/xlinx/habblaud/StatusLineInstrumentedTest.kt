@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package br.com.xlinx.habblaud

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class StatusLineInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun textUpdatesOncePerSecondAndPausesWhileStopped() {
        val updates = Collections.synchronizedList(mutableListOf<Long>())
        val session = SessionItem(
            SessionKey("https://fixture.example", "claude", "account", "working"),
            null, "Trabalhando", null, "working", "Lendo",
            statusSince = System.currentTimeMillis() - 10_000,
        )
        compose.mainClock.autoAdvance = false
        fun advanceFrame() {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    StatusLine(session, onTextUpdate = { updates.add(it) })
                }
            }
            // Avance os quadros Compose; o tick do spinner e do cronômetro usa tempo real.
            compose.waitUntil(5_000) {
                advanceFrame()
                updates.isNotEmpty()
            }
            // Descarte a composição inicial e a sincronização imediata de ON_START.
            val initialSecond = updates.last() / 1_000
            compose.waitUntil(5_000) {
                advanceFrame()
                synchronized(updates) { updates.count { it / 1_000 > initialSecond } >= 2 }
            }
            val running = synchronized(updates) { updates.filter { it / 1_000 > initialSecond } }
            assertTrue("No máximo uma atualização por segundo", running.groupBy { it / 1_000 }.values.all { it.size == 1 })

            scenario.moveToState(Lifecycle.State.CREATED)
            advanceFrame()
            val stopped = updates.size
            val observeUntil = SystemClock.uptimeMillis() + 2_100
            compose.waitUntil(5_000) {
                advanceFrame()
                assertEquals("O cronômetro deve parar fora de STARTED", stopped, updates.size)
                SystemClock.uptimeMillis() >= observeUntil
            }

            val resumedAt = System.currentTimeMillis()
            scenario.moveToState(Lifecycle.State.RESUMED)
            advanceFrame()
            assertTrue("ON_START deve atualizar o cronômetro imediatamente", synchronized(updates) { updates.last() } >= resumedAt)
        }
    }
}
