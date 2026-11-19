package br.com.xlinx.habblaud

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

internal fun ComposeTestRule.hideIme(node: SemanticsNodeInteraction) {
    // A folha modal pode usar uma janela diferente da Activity.
    val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
    runOnUiThread {
        view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }
    waitUntil(15_000) {
        runOnUiThread { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
    }
}
