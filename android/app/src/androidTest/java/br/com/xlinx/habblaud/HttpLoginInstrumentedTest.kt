package br.com.xlinx.habblaud

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.LocalStateStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HttpLoginInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var notificationsEnabled = false
    private fun text(id: Int) = context.getString(id)
    private fun await(id: Int) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(id))).fetchSemanticsNodes().isNotEmpty() }
    private fun click(id: Int) {
        if (id == R.string.cancel) {
            compose.onNodeWithTag("http-cancel", useUnmergedTree = true).performClick()
            return
        }
        if (id == R.string.login) compose.onNodeWithText(text(id)).performScrollTo()
        val nodes = compose.onAllNodes(hasText(text(id)))
        val index = nodes.fetchSemanticsNodes().indices.first { nodes[it].isDisplayed() }
        nodes[index].performClick()
    }
    private fun setField(id: Int, value: String) {
        compose.onNodeWithText(text(id)).performTextClearance()
        compose.onNodeWithText(text(id)).performTextInput(value)
    }
    private fun origin() = server.url("/").toString().removeSuffix("/")
    private fun savedOrigin() = runBlocking { LocalStateStore(context).servers().singleOrNull() }
    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.login)
    }
    private fun credentials() {
        setField(R.string.user, "teste")
        setField(R.string.password, "senha-ficticia")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
    }
    private fun noRequest() { assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS)) }
    private fun rejectedLogin() {
        server.enqueue(MockResponse().setResponseCode(401))
        click(R.string.confirm)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", request?.method)
        assertEquals("/api/remote/login", request?.path)
        await(R.string.login_error)
        assertEquals(1, server.requestCount)
    }

    @Before fun prepare() {
        runBlocking {
            val store = LocalStateStore(context)
            notificationsEnabled = store.notificationsEnabled()
            store.setNotificationsEnabled(false)
            store.clear()
        }
        context.stopService(android.content.Intent(context, NotificationService::class.java))
        context.filesDir.resolve("remote-session").delete()
        server.start()
    }

    @After fun cleanup() {
        scenario?.close()
        server.shutdown()
        runBlocking {
            val store = LocalStateStore(context)
            store.clear()
            store.setNotificationsEnabled(notificationsEnabled)
        }
        context.filesDir.resolve("remote-session").delete()
    }

    @Test fun httpRequiresConfirmationOnEveryAttempt() {
        launch()
        setField(R.string.server_url, origin())
        credentials()
        click(R.string.login)
        await(R.string.http_login_title)
        noRequest()
        assertNull(savedOrigin())
        click(R.string.cancel)
        noRequest()
        assertNull(savedOrigin())
        click(R.string.login)
        await(R.string.http_login_title)
        // `input keyevent` roda fora do processo: lê a saída para esperar o BACK sair. O primeiro pode só fechar o
        // teclado; o segundo vai apenas se o diálogo ainda estiver aberto depois de esperar (mandado cedo demais, ele
        // fechava a atividade junto).
        fun back() = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK"),
        ).use { it.readBytes() }
        fun dialogGone(ms: Long) = runCatching {
            compose.waitUntil(ms) { compose.onAllNodes(hasText(text(R.string.http_login_title))).fetchSemanticsNodes().isEmpty() }
        }.isSuccess
        back()
        if (!dialogGone(2_000)) { back(); dialogGone(5_000) }
        compose.onNodeWithText(text(R.string.http_login_title)).assertDoesNotExist()
        noRequest()
        assertNull(savedOrigin())
        click(R.string.login)
        await(R.string.http_login_title)
        rejectedLogin()
        credentials()
        click(R.string.login)
        await(R.string.http_login_title)
        assertEquals(1, server.requestCount)
    }

    @Test fun addingServerRequiresOnlyHttpConfirmationAndPreservesPrevious() {
        val previous = "http://127.0.0.1:1"
        runBlocking { LocalStateStore(context).addServer(previous) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.open_sessions)
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithTag("add-server", useUnmergedTree = true).performClick()
        await(R.string.login)
        setField(R.string.server_url, origin())
        credentials()
        click(R.string.login)
        await(R.string.http_login_title)
        compose.onNodeWithText("Trocar servidor?").assertDoesNotExist()
        noRequest()
        assertEquals(previous, savedOrigin())
        click(R.string.cancel)
        noRequest()
        assertEquals(previous, savedOrigin())
        click(R.string.login)
        await(R.string.http_login_title)
        rejectedLogin()
        assertEquals(previous, savedOrigin())
    }

    @Test fun savedHttpOriginStillRequiresConfirmation() {
        runBlocking { LocalStateStore(context).addServer(origin()) }
        server.enqueue(MockResponse().setResponseCode(401))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val tag = "relogin:${origin()}"
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        await(R.string.login)
        assertEquals("/api/access", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        credentials()
        click(R.string.login)
        await(R.string.http_login_title)
        noRequest()
        assertEquals(origin(), savedOrigin())
    }

    @Test fun invalidUrlShowsExistingError() {
        launch()
        setField(R.string.server_url, "invalid")
        credentials()
        click(R.string.login)
        await(R.string.invalid_url)
        compose.onNodeWithText(text(R.string.http_login_title)).assertDoesNotExist()
        noRequest()
        assertNull(savedOrigin())
    }

    @Test fun recreationDoesNotRestorePasswordOrSendLogin() {
        launch()
        setField(R.string.server_url, origin())
        credentials()
        click(R.string.login)
        await(R.string.http_login_title)
        scenario!!.recreate()
        await(R.string.login)
        compose.onNodeWithText(text(R.string.http_login_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.login)).assertIsNotEnabled()
        noRequest()
        assertNull(savedOrigin())
    }

    @Test fun httpsStartsWithoutHttpWarning() {
        launch()
        setField(R.string.server_url, "https://127.0.0.1:1")
        credentials()
        click(R.string.login)
        await(R.string.network_error)
        compose.onNodeWithText(text(R.string.http_login_title)).assertDoesNotExist()
        noRequest()
    }
}
