package br.com.xlinx.habblaud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TwoPaneNarrowUiTest {
    companion object {
        private val fixture = TwoPaneFixture(14761)
        @BeforeClass @JvmStatic fun start() = fixture.start()
        @AfterClass @JvmStatic fun stop() = fixture.close()
    }

    @Test fun conversationReplacesListOnNarrowScreen() = runDesktopComposeUiTest(width = 400, height = 800) {
        setContent {
            CompositionLocalProvider(LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, result -> result(false) })) { ZaplaudApp() }
        }
        loginToFixture(14761)
        onAllNodes(sessionRow).onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("conversation-list").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("conversation-list").assertExists()
        onAllNodes(sessionRow).assertCountEquals(0)
        onNodeWithContentDescription("Chamar contato").assertDoesNotExist()
    }
}
