@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package br.com.xlinx.habblaud

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    StatusLine(session, onTextUpdate = { updates.add(it) })
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            // O relógio Compose move o spinner; delay e uiNowMillis usam tempo real.
            // Descarte a composição inicial e a sincronização imediata de ON_START.
            SystemClock.sleep(100)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            updates.clear()
            repeat(45) {
                SystemClock.sleep(50)
                compose.mainClock.advanceTimeBy(50)
                compose.waitForIdle()
            }
            val running = synchronized(updates) { updates.toList() }
            assertTrue("O cronômetro deve continuar andando", running.size >= 2)
            assertTrue("O spinner não deve recompor o texto a cada quadro", running.size <= 4)
            assertTrue("No máximo uma atualização por segundo", running.groupBy { it / 1000 }.values.all { it.size == 1 })

            scenario.moveToState(Lifecycle.State.CREATED)
            compose.mainClock.advanceTimeByFrame()
            val stopped = synchronized(updates) { updates.last() }
            SystemClock.sleep(2_100)
            compose.mainClock.advanceTimeBy(2_100)
            assertEquals("O cronômetro deve parar fora de STARTED", stopped, synchronized(updates) { updates.last() })

            val resumedAt = System.currentTimeMillis()
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertTrue("ON_START deve atualizar o cronômetro imediatamente", synchronized(updates) { updates.last() } >= resumedAt)
        }
    }
}
