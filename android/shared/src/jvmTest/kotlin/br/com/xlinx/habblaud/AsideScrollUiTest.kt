package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import br.com.xlinx.habblaud.data.AsideController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class AsideScrollUiTest {
    @Test fun growingAnswerDoesNotMoveReaderAwayFromOlderTurns() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val generation = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AsideController({ _, _ ->
            val turns = (0..29).joinToString(",") { index ->
                val answer = if (index == 29) "resposta ${"nova ".repeat(generation.get())}" else "resposta antiga $index"
                """{"id":"t$index","question":"Pergunta $index","answer":"$answer","status":"${if (index == 29) "running" else "complete"}"}"""
            }
            """{"topics":[{"id":"topic","createdAt":1,"turns":[$turns]}]}"""
        }, scope, {})
        try {
            controller.open("agent")
            setContent { MaterialTheme { AsideSheet(controller) {} } }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("aside-turns").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithTag("aside-turns").performScrollToIndex(10)
            onNodeWithTag("aside-turns").performTouchInput { swipeDown(startY = centerY, endY = centerY + 80f, durationMillis = 500) }
            waitForIdle()
            fun position() = onNodeWithTag("aside-turns").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val savedPosition = position()
            val end = onNodeWithTag("aside-turns").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
            assertTrue(savedPosition > 0f && savedPosition < end, "A leitura deve estar no histórico: $savedPosition / $end")
            generation.incrementAndGet()
            waitUntil(timeoutMillis = 15_000) { controller.state.value.topic?.turns?.lastOrNull()?.answer == "resposta nova " }
            waitForIdle()
            assertEquals(savedPosition, position())
            ImageIO.write(onNodeWithTag("aside-turns").captureToImage().toAwtImage(), "png", File("/tmp/zaplaud-align-kotlin-aside.png"))
        } finally {
            controller.close()
            scope.cancel()
        }
    }
}
