package br.com.xlinx.habblaud

import android.Manifest
import android.os.Build
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.MessageDraft
import br.com.xlinx.habblaud.data.MessageOperation
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
    private var zeroClawFixture = false
    private val zeroClawKey = SessionKey(origin, "zeroclaw", "zeroclaw", "00000000-0000-4000-8000-000000000008")
    private fun fixture(path: String, body: JSONObject? = null): JSONObject {
        val port = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureLocalPort")?.toIntOrNull())
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000; connection.readTimeout = 10_000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            assertTrue("Fixture HTTP ${connection.responseCode}", connection.responseCode in 200..299)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun fixtureCommand(command: String) = fixture("/api/fixture/command", JSONObject().put("command", command))
    private fun startZeroClaw() {
        zeroClawFixture = true
        fixtureCommand("zc-reset"); fixtureCommand("runtime zeroclaw")
        runBlocking { LocalStateStore(context).setLastSession(zeroClawKey) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
    }
    private fun assertNoZeroClawContext() {
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Contexto em uso", substring = true)).assertCountEquals(0)
        compose.onNode(SemanticsMatcher("abrir informações da sessão") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == text(R.string.session_info)
        }).performClick()
        compose.onNodeWithText(text(R.string.info_context)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.thinking_menu)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.info_hide_details)).assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        await(R.string.message_text)
    }
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
            store.addServer(origin)
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
        if (zeroClawFixture) fixtureCommand("runtime omp")
        if (prepared) {
            runBlocking { LocalStateStore(context).clear() }
            context.filesDir.resolve("remote-session").delete()
        }
    }

    @Test fun connectionIsPersistedPerOrigin() = runBlocking {
        val store = LocalStateStore(context)
        val remote = "http://100.83.131.11:4747"
        store.addServer(remote)
        assertEquals(listOf(origin, remote), LocalStateStore(context).servers())
    }

    @Test fun mergedSessionsShowBothOriginsAndServerChipsFilterThem() {
        val other = "http://localhost:14748"
        runBlocking {
            HabblaudApi(other).use { it.login("android-test", "android-test") }
            LocalStateStore(context).addServer(other)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("server-filter:all", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("server-filter:all", useUnmergedTree = true).assertIsSelected()
        compose.onNodeWithText("127.0.0.1:14748").assertIsDisplayed()
        compose.onNodeWithText("localhost:14748").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performTouchInput {
            swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600)
        }
        compose.onNodeWithTag("session-search").performTextInput("Ana Fictícia")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ).use { it.readBytes() }
        val rowTag = "session-row:${key.provider}:${key.sessionId}"
        fun awaitRows(count: Int) = compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().size == count
        }
        awaitRows(2)
        compose.onAllNodesWithTag(rowTag).assertCountEquals(2)
        compose.onNodeWithTag("session-server:127.0.0.1:14748", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("session-server:localhost:14748", useUnmergedTree = true).assertIsDisplayed()
        for (label in listOf("127.0.0.1:14748", "localhost:14748")) {
            compose.onNodeWithTag("server-filter:$label", useUnmergedTree = true).performClick().assertIsSelected()
            awaitRows(1)
            compose.onAllNodesWithTag(rowTag).assertCountEquals(1)
            compose.onNodeWithTag("server-filter:all", useUnmergedTree = true).performClick().assertIsSelected()
            awaitRows(2)
        }
    }

    @Test fun searchRevealsOnlyAfterLongPullAndStaysPinned() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        await(R.string.open_sessions)
        compose.onAllNodesWithTag("session-search").assertCountEquals(0)
        compose.onNode(hasScrollToNodeAction()).performTouchInput {
            swipeDown(startY = top + 20f, endY = top + 20f + 40.dp.toPx(), durationMillis = 400)
        }
        compose.onAllNodesWithTag("session-search").assertCountEquals(0)
        compose.onNode(hasScrollToNodeAction()).performTouchInput {
            swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600)
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("session-search").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("session-search").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp() }
        compose.onNodeWithTag("session-search").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.search_close)).performClick()
        compose.onAllNodesWithTag("session-search").assertCountEquals(0)
    }

    @Test fun searchWithoutMatchesOffersClear() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        await(R.string.open_sessions)
        compose.onNode(hasScrollToNodeAction()).performTouchInput {
            swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 600)
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("session-search").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("session-search").performTextInput("busca-impossivel")
        compose.onNodeWithText(text(R.string.search_no_match)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.sessions_empty)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.search_clear)).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(SemanticsMatcher("row") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("session-row:") == true
            }).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun logoutFromSettingsRemovesOnlySelectedServer() {
        val other = "http://localhost:14748"
        runBlocking {
            HabblaudApi(other).use { it.login("android-test", "android-test") }
            LocalStateStore(context).addServer(other)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.onNodeWithTag("server-filter:127.0.0.1:14748", useUnmergedTree = true).performClick()
        awaitDescription(R.string.menu)
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.logout)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings)).performClick()
        await(R.string.settings_server)
        compose.onAllNodes(hasText(text(R.string.logout)))[0].performScrollTo().performClick()
        await(R.string.confirm)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { LocalStateStore(context).servers() == listOf(other) }
        }
        assertEquals(listOf(other), runBlocking { LocalStateStore(context).servers() })
        await(R.string.open_sessions)
        compose.onNodeWithText(text(R.string.open_sessions)).assertIsDisplayed()
        runBlocking { HabblaudApi(other).use { it.access() } }
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

    @Test fun longPressOnContextRingAsksBeforeShake() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("caderno · trabalhando")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("caderno · trabalhando").performClick()
        val ring = androidx.compose.ui.test.hasContentDescription("Contexto em uso", substring = true)
        compose.waitUntil(15_000) { compose.onAllNodes(ring).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(ring).performTouchInput { click() }
        compose.onNodeWithText(text(R.string.shake_confirm)).assertDoesNotExist()
        compose.onNode(ring).performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.shake_confirm)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText(text(R.string.shake_confirm)).assertDoesNotExist()
        compose.onNode(ring).performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Shake:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun parallelChatAnswersWithoutTouchingTheConversation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("caderno · trabalhando")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("caderno · trabalhando").performClick()
        awaitDescription(R.string.menu)
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithTag("aside-menu").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.aside_empty))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("aside-input").performTextInput("o que você está fazendo?")
        compose.onNodeWithTag("aside-send").performClick()
        // A resposta chega em partes: primeiro em andamento (com o botão de parar), depois completa.
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(text(R.string.aside_stop))).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Resposta paralela fictícia: o que você está fazendo?", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("aside-send").fetchSemanticsNodes().isNotEmpty() }
        // A pergunta não vira mensagem da conversa do agente. O primeiro Voltar pode só fechar o teclado.
        repeat(2) {
            if (compose.onAllNodesWithTag("aside-input").fetchSemanticsNodes().isEmpty()) return@repeat
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
            ).use { it.readBytes() }
            Thread.sleep(1_000)
            compose.waitForIdle()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("aside-input").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).assertExists()
        compose.onAllNodes(hasText("o que você está fazendo?", substring = true)).assertCountEquals(0)
    }

    @Test fun longSwipeUpAtConversationEndOpensParallelChat() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("caderno · trabalhando")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("caderno · trabalhando").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("conversation-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Curto: só rola (nada além do fim para revelar).
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(startY = bottom - 20f, endY = bottom - 20f - 40.dp.toPx(), durationMillis = 400) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("aside-input").assertCountEquals(0)
        // Longo, de baixo para cima no fim da conversa: abre o bate-papo paralelo.
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(startY = bottom - 20f, endY = top + 20f, durationMillis = 600) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("aside-input").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun swipeThatReturnsToConversationEndDoesNotOpenParallelChat() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("caderno · trabalhando")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("caderno · trabalhando").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("conversation-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        // Um só gesto: desce no histórico, volta ao fim e segue além dele. A sobra não abre o bate-papo paralelo.
        compose.onNodeWithTag("conversation-list").performTouchInput {
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, 30.dp.toPx())) }
            repeat(30) { moveBy(androidx.compose.ui.geometry.Offset(0f, -30.dp.toPx())) }
            up()
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("aside-input").assertCountEquals(0)
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
                compose.onAllNodesWithTag("chat-banner", useUnmergedTree = true).assertCountEquals(0)
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
            .put("tool_input", JSONObject().put("command", "echo fixture").put("description", "Descrição segura da fixture")))
        val id = registered.optString("id")
        assertTrue("Fixture não registrou a permissão: $registered", id.isNotEmpty())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitDescription(R.string.permission_approve)
        compose.onNodeWithContentDescription(text(R.string.permission_approve)).performClick()
        await(R.string.permission_allow)
        val summary = fixtureRequest("/api/permissions/$id").getString("text")
        compose.onNodeWithText(summary).assertExists()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("echo fixture", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        context.getExternalFilesDir(null)!!.resolve("zaplaud-align-kotlin-permission.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
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
        compose.onNodeWithText(request("/api/permissions/$id").getString("text")).assertExists()
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

    @Test fun zeroClawControlsSendStopAndNewKeepSessionDraftsSeparate() {
        startZeroClaw()
        fixtureCommand("zc-context 120000")
        assertNoZeroClawContext()
        val before = fixture("/api/fixture/stats")
        val messageCount = before.getJSONArray("messageRequests").length()
        val controlCount = before.getJSONArray("controlRequests").length()
        for (mode in listOf(R.string.deliver_steer, R.string.deliver_follow_up)) {
            compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
            for (forbidden in listOf(R.string.shake, R.string.thinking_menu, R.string.model_menu, R.string.aside_menu)) {
                compose.onNodeWithText(text(forbidden)).assertDoesNotExist()
            }
            compose.onNodeWithText(text(R.string.deliver_mode)).performClick()
            compose.onNodeWithText(text(R.string.deliver_aside)).assertDoesNotExist()
            compose.onNodeWithText(text(mode)).performClick()
            compose.onNodeWithTag("message-input").performTextInput("pedido-$mode")
            compose.onNodeWithTag("message-send").performClick()
            compose.waitUntil(15_000) { runBlocking { LocalStateStore(context).message(zeroClawKey).draft.text.isEmpty() } }
        }
        val messages = fixture("/api/fixture/stats").getJSONArray("messageRequests")
        assertEquals(messageCount + 2, messages.length())
        for ((index, mode) in listOf("steer", "followUp").withIndex()) {
            val body = messages.getJSONObject(messageCount + index)
            assertEquals("fixture:zeroclaw", body.getString("agentId"))
            assertEquals(zeroClawKey.sessionId, body.getString("expectedSessionId"))
            assertEquals(mode, body.getString("deliverAs"))
        }
        fixtureCommand("zc-working")
        awaitDescription(R.string.stop_agent)
        compose.onNodeWithContentDescription(text(R.string.stop_agent)).performClick()
        compose.waitUntil(15_000) { fixture("/api/fixture/stats").getJSONArray("controlRequests").length() > controlCount }
        val stop = fixture("/api/fixture/stats").getJSONArray("controlRequests").getJSONObject(controlCount)
        assertEquals("fixture:zeroclaw", stop.getString("agentId"))
        assertTrue(stop.getBoolean("stop"))
        assertEquals(zeroClawKey.sessionId, stop.getString("expectedSessionId"))
        compose.onNodeWithTag("message-input").performTextInput("rascunho anterior")
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("session-new") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("session-new").performClick()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(15_000) {
            runBlocking { LocalStateStore(context).lastSession()?.let { it.provider == "zeroclaw" && it.sessionId != zeroClawKey.sessionId } == true }
        }
        val next = runBlocking { requireNotNull(LocalStateStore(context).lastSession()) }
        assertEquals(zeroClawKey.account, next.account)
        assertEquals("rascunho anterior", runBlocking { LocalStateStore(context).message(zeroClawKey).draft.text })
        assertEquals("", runBlocking { LocalStateStore(context).message(next).draft.text })
        val controls = fixture("/api/fixture/stats").getJSONArray("controlRequests")
        assertEquals(controlCount + 2, controls.length())
        val new = controls.getJSONObject(controlCount + 1)
        assertEquals("new", new.getString("command"))
        assertEquals(zeroClawKey.sessionId, new.getString("expectedSessionId"))
    }

    @Test fun zeroClawSettingsHideRolesAndAsideAndAllowHttpSummaryWithoutTiny() {
        startZeroClaw()
        val before = fixture("/api/fixture/stats")
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.settings)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(text(R.string.deliver_follow_up))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.deliver_aside)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_roles)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.settings_summarize)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { runBlocking { LocalStateStore(context).summarize.first() } }
        val after = fixture("/api/fixture/stats")
        assertEquals(before.getJSONObject("gets").optInt("/api/cast/roles"), after.getJSONObject("gets").optInt("/api/cast/roles"))
        assertEquals(before.getJSONObject("posts").optInt("/api/cast/roles"), after.getJSONObject("posts").optInt("/api/cast/roles"))
    }

    @Test fun zeroClawClosedHistoryResumesOnlyThroughCast() {
        startZeroClaw()
        val closed = zeroClawKey.copy(sessionId = "00000000-0000-4000-8000-000000000009")
        scenario!!.close(); scenario = null
        runBlocking { LocalStateStore(context).setLastSession(closed) }
        val before = fixture("/api/fixture/stats")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitDescription(R.string.cast_resume)
        compose.onNodeWithContentDescription(text(R.string.cast_resume)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Zora Fictícia")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Zora Fictícia").performClick()
        compose.onNodeWithText(text(R.string.cast_confirm)).performClick()
        compose.waitUntil(15_000) { fixture("/api/fixture/stats").getJSONArray("castRequests").length() > before.getJSONArray("castRequests").length() }
        val requests = fixture("/api/fixture/stats").getJSONArray("castRequests")
        val request = requests.getJSONObject(requests.length() - 1)
        val body = request
        assertEquals("resume", body.getString("mode"))
        assertEquals(closed.sessionId, body.getString("session"))
        assertEquals(closed.account, body.getString("account"))
        assertEquals("/fixture/projeto", body.getString("room"))
        assertEquals(before.getJSONArray("controlRequests").length(), fixture("/api/fixture/stats").getJSONArray("controlRequests").length())
        compose.waitUntil(15_000) {
            fixture("/api/fixture/stats").getJSONArray("castRequests").let { it.length() > 0 } &&
                compose.onAllNodesWithTag("message-input").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun switchingFromOmpToZeroClawHidesBothContextPositionsAndOldSubmenus() {
        zeroClawFixture = true
        fixtureCommand("zc-reset"); fixtureCommand("runtime zeroclaw")
        runBlocking { LocalStateStore(context).setLastSession(SessionKey(origin, "omp", ".omp", "00000000-0000-4000-8000-000000000002")) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.message_text)
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Contexto em uso", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.thinking_menu)).performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.onNodeWithContentDescription(text(R.string.back_sessions)).performClick()
        val row = "session-row:zeroclaw:${zeroClawKey.sessionId}"
        await(R.string.open_sessions)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        await(R.string.message_text)
        for (context in listOf("zc-context 0", "zc-context 120000")) {
            fixtureCommand(context)
            assertNoZeroClawContext()
        }
        compose.onNodeWithContentDescription(text(R.string.menu)).performClick()
        compose.onNodeWithText(text(R.string.thinking_menu)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.deliver_mode)).assertIsDisplayed()
    }

    @Test fun tabletKeepsCastAndUsesFullConversationWidth() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString().trim() }
        val rotation = shell("wm user-rotation")
        val angle = shell("settings get system user_rotation").toInt()
        try {
            shell("wm size 2560x1600")
            shell("wm density 240")
            // O AVD 320×640 limita o resultado a 1920×1600; ainda exerce a coluna larga a 1280 dp.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await(R.string.message_text)
            awaitDescription(R.string.cast_start)
            compose.onNodeWithContentDescription(text(R.string.cast_start)).assertIsDisplayed()
            val width = compose.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot.width
            assertTrue("A conversa ainda está limitada a 840 dp: $width px", width > 840 * context.resources.displayMetrics.density)
            for (theme in listOf("light", "dark")) {
                runBlocking { LocalStateStore(context).setTheme(theme) }
                compose.waitForIdle()
                val screenshot = requireNotNull(automation.takeScreenshot())
                context.getExternalFilesDir(null)!!.resolve("zaplaud-align-kotlin-tablet-$theme.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            val before = fixture("/api/fixture/stats").getJSONArray("castRequests").length()
            compose.onNodeWithContentDescription(text(R.string.cast_start)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("Bia Fictícia")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Bia Fictícia").performClick()
            compose.onNodeWithText("/fixture/projeto").performClick()
            compose.waitUntil(15_000) { fixture("/api/fixture/stats").getJSONArray("castRequests").length() > before }
            val requests = fixture("/api/fixture/stats").getJSONArray("castRequests")
            val request = requests.getJSONObject(requests.length() - 1)
            assertEquals("new", request.getString("mode"))
            assertEquals("/fixture/projeto", request.getString("room"))
        } finally {
            scenario?.close(); scenario = null
            shell("wm size reset")
            shell("wm density reset")
            shell("wm user-rotation lock $angle")
            shell("wm user-rotation $rotation")
        }
    }
}
