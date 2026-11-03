package br.com.xlinx.habblaud

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    private fun text(id: Int) = context.getString(id)
    private fun await(id: Int) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(id))).fetchSemanticsNodes().isNotEmpty() }
    private fun click(id: Int) {
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
    private fun savedOrigin() = runBlocking { LocalStateStore(context).connection()?.origin }
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
        runBlocking { LocalStateStore(context).clear() }
        context.filesDir.resolve("remote-session").delete()
        server.start()
    }

    @After fun cleanup() {
        scenario?.close()
        server.shutdown()
        runBlocking { LocalStateStore(context).clear() }
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        if (compose.onAllNodes(hasText(text(R.string.http_login_title))).fetchSemanticsNodes().isNotEmpty()) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        }
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

    @Test fun serverChangeRequiresBothConfirmations() {
        val previous = "http://127.0.0.1:1"
        runBlocking { LocalStateStore(context).setConnection(previous) }
        launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(previous, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        setField(R.string.server_url, origin())
        credentials()
        click(R.string.login)
        await(R.string.change_server)
        click(R.string.cancel)
        noRequest()
        assertEquals(previous, savedOrigin())
        click(R.string.login)
        await(R.string.change_server)
        click(R.string.confirm)
        await(R.string.http_login_title)
        noRequest()
        assertEquals(previous, savedOrigin())
        click(R.string.cancel)
        noRequest()
        assertEquals(previous, savedOrigin())
        click(R.string.login)
        await(R.string.change_server)
        click(R.string.confirm)
        await(R.string.http_login_title)
        rejectedLogin()
    }

    @Test fun savedHttpOriginStillRequiresConfirmation() {
        runBlocking { LocalStateStore(context).setConnection(origin()) }
        server.enqueue(MockResponse().setResponseCode(401))
        launch()
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
