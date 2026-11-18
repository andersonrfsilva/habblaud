package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.AsideController
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.initializeStorage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsideScrollInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun growingAnswerDoesNotMoveReaderAwayFromOlderTurns() {
        initializeStorage(ApplicationProvider.getApplicationContext())
        val generation = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = HabblaudApi("http://127.0.0.1:1")
        val controller = AsideController({ _, _ ->
            val turns = (0..29).joinToString(",") { index ->
                val answer = if (index == 29) "resposta ${"nova ".repeat(generation.get())}" else "resposta antiga $index"
                """{"id":"t$index","question":"Pergunta $index","answer":"$answer","status":"${if (index == 29) "running" else "complete"}"}"""
            }
            """{"topics":[{"id":"topic","createdAt":1,"turns":[$turns]}]}"""
        }, scope, {})
        try {
            controller.open("agent")
            compose.setContent { MaterialTheme { AsideSheet(controller, api) {} } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("aside-turns").fetchSemanticsNodes().isNotEmpty() }
            val turns = compose.onNodeWithTag("aside-turns")
            turns.performScrollToIndex(10)
            turns.performTouchInput { swipeDown(startY = centerY, endY = centerY + 80f, durationMillis = 500) }
            fun axis() = turns.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val axisRange = axis()
            val savedPosition = compose.runOnIdle { axisRange.value() }
            val end = compose.runOnIdle { axisRange.maxValue() }
            assertTrue("A leitura deve estar no histórico: $savedPosition / $end", savedPosition > 0f && savedPosition < end)
            generation.incrementAndGet()
            compose.waitUntil(15_000) { controller.state.value.topic?.turns?.lastOrNull()?.answer == "resposta nova " }
            val after = axis()
            assertEquals(savedPosition, compose.runOnIdle { after.value() }, 0f)
        } finally {
            controller.close()
            scope.cancel()
            api.close()
        }
    }
}
