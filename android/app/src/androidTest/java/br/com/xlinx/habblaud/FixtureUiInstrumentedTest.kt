package br.com.xlinx.habblaud

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.ConnectionSettings
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.MessageDraft
import br.com.xlinx.habblaud.data.MessageOperation
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
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
    private fun awaitDescription(id: Int) = compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(text(id))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    private fun await(id: Int) = compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(id))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }

    @Before fun prepare() {
        check(InstrumentationRegistry.getArguments().getString("fixture") == "true") {
            "Informe fixture=true e configure adb reverse tcp:14748 tcp:14748 para executar a instrumentação."
        }
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        prepared = true
        br.com.xlinx.habblaud.data.initializeStorage(context)
        runBlocking {
            val store = LocalStateStore(context)
            store.clear()
            store.setConnection(origin)
            store.setLastSession(key)
            try {
                HabblaudApi(origin).use { it.login("android-test", "android-test") }
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

    @Test fun connectionIsPersistedPerOrigin() = runBlocking {
        val store = LocalStateStore(context)
        val remote = "http://100.83.131.11:4747"
        store.setConnection(remote)
        assertEquals(ConnectionSettings(remote), LocalStateStore(context).connection())
    }

    @Test fun systemBackReturnsToSessionsThenExits() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        fun pressBack() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        }
        pressBack()
        await(R.string.open_sessions)
        compose.onNodeWithText(text(R.string.open_sessions)).assertIsDisplayed()
        pressBack()
        compose.waitUntil(15_000) { scenario?.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
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

    @Test fun thinkingExpandsAllLinesAndCollapses() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.thinking), substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(text(R.string.thinking), substring = true)).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Terceira linha do pensamento.", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Primeira linha do pensamento.", substring = true)
            .assertTextContains("Segunda linha do pensamento.", substring = true)
            .assertTextContains("Terceira linha do pensamento.", substring = true)
        compose.onNode(hasText(text(R.string.thinking), substring = true)).performClick()
        compose.onNodeWithText("Terceira linha do pensamento.", substring = true).assertDoesNotExist()
    }

    @Test fun ompShowsStopWithoutDeliveryModeSelector() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.stop_agent)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("caderno · trabalhando")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("caderno · trabalhando").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(text(R.string.stop_agent)) and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(text(R.string.stop_agent)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Acrescentar agora").assertDoesNotExist()
        compose.onNodeWithText("Limites da entrega").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.window_truncated)).assertDoesNotExist()
    }

    @Test fun deliveryBannerDisappearsOnlyAfterDelivery() {
        val statuses = listOf(
            "queued" to R.string.message_queued,
            "sent" to R.string.message_sent,
            "failed" to R.string.message_failed,
            "unknown" to R.string.message_unknown,
            "delivered" to null,
        )
        for ((status, banner) in statuses) {
            runBlocking {
                LocalStateStore(context).updateMessage(key) {
                    it.copy(draft = MessageDraft("rascunho preservado", revision = 1), operation = MessageOperation(status = status))
                }
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("rascunho preservado", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            if (banner != null) {
                await(banner)
                compose.onNodeWithText(text(banner)).assertIsDisplayed()
            } else {
                compose.onNodeWithText("ⓘ").assertDoesNotExist()
                for (other in listOf(R.string.message_queued, R.string.message_sent, R.string.message_failed, R.string.message_unknown)) {
                    compose.onNodeWithText(text(other)).assertDoesNotExist()
                }
                compose.onNodeWithText("Entregue à sessão", substring = true).assertDoesNotExist()
            }
            compose.onNodeWithText(text(R.string.message_text)).assertTextContains("rascunho preservado")
            scenario!!.close()
            scenario = null
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
        awaitDescription(R.string.permission_approve)
        compose.onNodeWithContentDescription(text(R.string.permission_approve)).performClick()
        await(R.string.permission_allow)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("echo fixture", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.permission_allow))).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.message_text)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.permission_approve)).performClick()
        await(R.string.permission_allow)
        compose.onNodeWithText(text(R.string.permission_allow)).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        val result = fixtureRequest("/api/permissions/$id/wait?timeout=5")
        assertEquals("A fixture não recebeu decisão", "decided", result.optString("status"))
        assertEquals("A fixture recebeu decisão diferente", "allow", result.optString("behavior"))
        compose.onNodeWithText(text(R.string.message_text)).assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(text(R.string.permission_approve))).fetchSemanticsNodes().isEmpty() }
        val denial = fixtureRequest("/api/permissions", JSONObject()
            .put("session_id", key.sessionId)
            .put("tool_name", "Bash")
            .put("tool_input", JSONObject().put("command", "echo recusa")))
        val denialId = denial.optString("id")
        assertTrue("Fixture não registrou a recusa: $denial", denialId.isNotEmpty())
        awaitDescription(R.string.permission_approve)
        compose.onNodeWithContentDescription(text(R.string.permission_approve)).performClick()
        await(R.string.permission_deny)
        compose.onNodeWithText(text(R.string.permission_reason)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.permission_deny)).performClick()
        await(R.string.permission_reason)
        compose.onNodeWithText(text(R.string.permission_reason)).performTextInput("Não autorizado")
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithText(text(R.string.permission_reason)).assertTextContains("Não autorizado")
        compose.onNodeWithText(text(R.string.permission_deny)).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        val denied = fixtureRequest("/api/permissions/$denialId/wait?timeout=5")
        assertEquals("decided", denied.optString("status"))
        assertEquals("deny", denied.optString("behavior"))
    }
    @Test fun answersOmpQuestionWithOriginalPositions() {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureLocalPort")?.toIntOrNull())
        fun request(path: String, body: JSONObject? = null): JSONObject {
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 10_000
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                assertTrue("Fixture HTTP ${connection.responseCode}", connection.responseCode in 200..299)
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
        val input = JSONObject("""{"questions":[{"question":"Cor?","options":[{"label":"Azul"},{"label":"Laranja"}]},{"question":"Itens?","multiSelect":true,"options":[{"label":"Caderno"},{"label":"Lápis"},{"label":"Livro"}]},{"question":"Observação?","options":[{"label":"Nenhuma"},{"label":"Outra"}]}]}""")
        val registered = request("/api/permissions", JSONObject().put("session_id", "00000000-0000-4000-8000-000000000002").put("provider", "omp").put("tool_name", "AskUserQuestion").put("tool_input", input))
        val id = registered.optString("id")
        assertTrue("Pedido não registrado: $registered", id.isNotEmpty())
        runBlocking { LocalStateStore(context).setLastSession(SessionKey(origin, "omp", ".omp", "00000000-0000-4000-8000-000000000002")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitDescription(R.string.permission_answer)
        compose.onNodeWithText("Davi Fictício").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.cast_dismiss)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.view_menu)).performClick()
        compose.onNodeWithText(text(R.string.view_no_tools)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.view_all)).performClick()
        compose.onNodeWithContentDescription(text(R.string.permission_answer)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Cor?")).fetchSemanticsNodes().isNotEmpty() }
        val toggles = compose.onAllNodes(isToggleable())
        toggles[1].performScrollTo().performClick()
        toggles[2].performScrollTo().performClick()
        toggles[4].performScrollTo().performClick()
        toggles[1].assertIsOn()
        toggles[2].assertIsOn()
        toggles[4].assertIsOn()
        compose.onAllNodes(hasText(text(R.string.permission_other)))[2].performScrollTo().performTextInput("texto livre")
        compose.onNodeWithText(text(R.string.permission_answer)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.permission_answers)).assertDoesNotExist()
        val result = request("/api/permissions/$id/wait?timeout=5")
        assertEquals("decided", result.getString("status"))
        assertEquals("answer", result.getString("behavior"))
        assertEquals(JSONArray("""[{"question":0,"options":[1]},{"question":1,"options":[0,2]},{"question":2,"other":"texto livre"}]""").toString(), result.getJSONArray("answers").toString())
    }
}
