package br.com.xlinx.habblaud

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.ConnectionSettings
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class FixtureUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = "http://127.0.0.1:14748"
    private val key = SessionKey(origin, "claude", ".claude", "00000000-0000-4000-8000-000000000001")
    private var scenario: ActivityScenario<MainActivity>? = null
    private var prepared = false
    private fun text(id: Int) = context.getString(id)
    private fun await(id: Int) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(id))).fetchSemanticsNodes().isNotEmpty() }

    @Before fun prepare() {
        check(InstrumentationRegistry.getArguments().getString("fixture") == "true") {
            "Informe fixture=true e configure adb reverse tcp:14748 tcp:14748 para executar a instrumentação."
        }
        prepared = true
        runBlocking {
            val store = LocalStateStore(context)
            store.clear()
            store.setConnection(origin, false)
            store.setLastSession(key)
            try {
                HabblaudApi(context, origin, false).use { it.login("android-test", "android-test") }
            } catch (e: Exception) {
                throw AssertionError("Fixture indisponível ou login falhou; inicie android-smoke e configure adb reverse tcp:14748 tcp:14748.", e)
            }
        }
    }

    @After fun cleanup() {
        scenario?.close()
        if (prepared) {
            runBlocking { LocalStateStore(context).clear() }
            context.filesDir.resolve("remote-session").delete()
        }
    }

    @Test fun connectionConsentAndConfirmedHostArePersistedAndRevoked() = runBlocking {
        val store = LocalStateStore(context)
        val vpnOrigin = "http://100.83.131.11:4747"
        store.setConnection(vpnOrigin, true, "100.83.131.11")
        assertEquals(ConnectionSettings(vpnOrigin, true, "100.83.131.11"), LocalStateStore(context).connection())
        store.setConnection(vpnOrigin, false)
        assertEquals(ConnectionSettings(vpnOrigin, false, null), LocalStateStore(context).connection())
    }

    @Test fun imeDraftSurvivesRecreationRotationAndLargeFont() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithText(text(R.string.message_text)).performTextInput("rascunho IME")
        compose.waitUntil(15_000) { runBlocking { LocalStateStore(context).message(key).draft.text == "rascunho IME" } }
        scenario!!.recreate()
        await(R.string.message_text)
        compose.onNodeWithText(text(R.string.message_text)).assertTextContains("rascunho IME")
        scenario!!.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await(R.string.message_text)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        val original = shell("settings get system font_scale")
        try {
            shell("settings put system font_scale 1.5")
            scenario!!.recreate()
            await(R.string.message_text)
            compose.onNodeWithText(text(R.string.message_text)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.message_text)).assertTextContains("rascunho IME")
        } finally {
            if (original == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $original")
        }
    }

    @Test fun opensAndDecidesRealFixturePermission() {
        val port = InstrumentationRegistry.getArguments().getString("fixtureLocalPort")?.toIntOrNull()
        require(port != null && port in 1..65535) {
            "Informe fixtureLocalPort com a porta localUrl da fixture e configure adb reverse para essa porta."
        }
        fun fixtureRequest(path: String, body: JSONObject? = null): JSONObject {
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                assertTrue("Fixture local retornou HTTP $status em $path", status in 200..299)
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        val registered = fixtureRequest("/api/permissions", JSONObject()
            .put("session_id", key.sessionId)
            .put("tool_name", "Bash")
            .put("tool_input", JSONObject().put("command", "echo fixture")))
        val id = registered.optString("id")
        assertTrue("Fixture não registrou a permissão: $registered", id.isNotEmpty())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.permission_pending)
        compose.onNodeWithText(text(R.string.permission_pending)).performClick()
        await(R.string.permission_allow)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("echo fixture", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.permission_allow)).assertIsEnabled().performClick()
        val result = fixtureRequest("/api/permissions/$id/wait?timeout=5")
        assertEquals("A fixture não recebeu decisão", "decided", result.optString("status"))
        assertEquals("A fixture recebeu decisão diferente", "allow", result.optString("behavior"))
        compose.onNodeWithText(text(R.string.message_text)).assertIsDisplayed()
    }
}
