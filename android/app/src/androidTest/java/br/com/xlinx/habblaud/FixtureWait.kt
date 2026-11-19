package br.com.xlinx.habblaud

import androidx.compose.ui.test.junit4.ComposeTestRule
import java.util.concurrent.FutureTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

internal fun ComposeTestRule.awaitFixture(check: suspend () -> Boolean) {
    withFixture { while (!check()) delay(20) }
}

internal fun <T> ComposeTestRule.withFixture(work: suspend () -> T): T {
    // A thread de instrumentação precisa ficar livre para avançar o relógio do Compose.
    val pending = FutureTask { runBlocking { work() } }
    Thread(pending, "fixture-worker").start()
    try {
        waitUntil(15_000) { pending.isDone }
        return pending.get()
    } finally {
        pending.cancel(true)
    }
}
