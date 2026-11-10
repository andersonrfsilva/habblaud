package br.com.xlinx.habblaud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertTrue
import androidx.compose.ui.graphics.toAwtImage
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO
import org.junit.Before

@OptIn(ExperimentalTestApi::class)
class TwoPaneWideUiTest {
    companion object {
        private val fixture = TwoPaneFixture(14760)
        @BeforeClass @JvmStatic fun start() = fixture.start()
        @AfterClass @JvmStatic fun stop() = fixture.close()
    }

    @Before fun resetLogin() = runBlocking { LocalStateStore().clear() }

    @Test fun listRemainsBesideConversationAndEscapeClosesIt() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent {
            CompositionLocalProvider(LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, result -> result(false) })) { ZaplaudApp() }
        }
        loginToFixture(14760)
        onAllNodes(sessionRow).onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("conversation-list").fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(sessionRow).onFirst().assertExists()
        onNodeWithTag("conversation-list").assertExists()
        runOnIdle { assertTrue(dispatchDesktopSearch()) }
        onNodeWithTag("session-search").assertIsFocused()
        runOnIdle { assertTrue(dispatchDesktopBack()) }
        onNodeWithTag("conversation-list").assertDoesNotExist()
        onNodeWithText("Selecione uma conversa").assertExists()
    }

    @Test fun openConversationKeepsCastAndFillsRightPane() = runDesktopComposeUiTest(width = 1800, height = 1000) {
        setContent {
            CompositionLocalProvider(LocalNotificationPlatform provides NotificationPlatform(false, {}, {}, { _, result -> result(false) })) { ZaplaudApp() }
        }
        loginToFixture(14760)
        onAllNodes(sessionRow).onFirst().performClick()
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("conversation-list").fetchSemanticsNodes().isNotEmpty() && onAllNodesWithContentDescription("Chamar contato").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Chamar contato").assertIsDisplayed()
        assertTrue(onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot.width > 1000f)
        for (theme in listOf("light", "dark")) {
            runBlocking { LocalStateStore().setTheme(theme) }
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("/tmp/zaplaud-align-kotlin-desktop-$theme.png"))
        }
    }
}

internal val sessionRow = SemanticsMatcher("session-row tag") {
    it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-row:") == true
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.loginToFixture(port: Int) {
    waitUntil(timeoutMillis = 15_000) { onAllNodesWithTag("server-url").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("server-url").performTextReplacement("http://127.0.0.1:$port")
    onNodeWithTag("username").performTextInput("android-test")
    onNodeWithTag("password").performTextInput("android-test")
    onNodeWithTag("login-submit").performClick()
    onNodeWithTag("http-confirm").performClick()
    waitUntil(timeoutMillis = 15_000) { onAllNodes(sessionRow).fetchSemanticsNodes().isNotEmpty() }
}

internal class TwoPaneFixture(private val port: Int) : AutoCloseable {
    private var process: Process? = null
    private var directory: File? = null
    private var prefsNode: String? = null

    fun start() {
        Assume.assumeTrue("node ausente", runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false))
        check(File("../../package.json").exists()) { "jvmTest must run from android/shared" }
        directory = Files.createTempDirectory("zaplaud-two-pane-$port-").toFile()
        prefsNode = "br/com/xlinx/zaplaud-two-pane-${directory!!.name}"
        System.setProperty("zaplaud.dataDir", directory!!.absolutePath)
        System.setProperty("zaplaud.prefsNode", prefsNode!!)
        val child = ProcessBuilder("node", "--import", "tsx", "scripts/android-smoke.ts", "--port", port.toString(), "--allow", "terminal,messages,permissions")
            .directory(File("../..")).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process = child
        try {
            CompletableFuture.supplyAsync {
                val reader = child.inputStream.bufferedReader()
                check(generateSequence { reader.readLine() }.any { it.contains("\"ready\":true") }) { "Fixture exited before readiness" }
                Thread { reader.forEachLine { } }.apply { isDaemon = true; start() }
            }.get(60, TimeUnit.SECONDS)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        process?.let { child ->
            child.destroy()
            if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
        }
        process = null
        directory?.deleteRecursively()
        prefsNode?.let { Preferences.userRoot().node(it).removeNode(); Preferences.userRoot().flush() }
    }
}
