package br.com.xlinx.habblaud

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
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
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        assumeTrue("Run with -e fixture true and adb reverse tcp:14748 tcp:14748", InstrumentationRegistry.getArguments().getString("fixture") == "true")
        prepared = true
        runBlocking {
            val store = LocalStateStore(context)
            store.clear()
            store.setConnection(origin, false)
            store.setLastSession(key)
            HabblaudApi(context, origin, false).use { it.login("android-test", "android-test") }
        }
    }

    @After fun cleanup() {
        scenario?.close()
        if (prepared) {
            runBlocking { LocalStateStore(context).clear() }
            context.filesDir.resolve("remote-session").delete()
        }
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

    @Test fun opensAndDismissesRealFixturePermission() {
        assumeTrue("Create pending request with fixture stdin: permission fixture:claude bash", InstrumentationRegistry.getArguments().getString("permission") == "true")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await(R.string.permission_pending)
        compose.onNodeWithText(text(R.string.permission_pending)).performClick()
        await(R.string.permission_allow)
        compose.onNodeWithText(text(R.string.permission_allow)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText(text(R.string.message_text)).assertIsDisplayed()
    }
}
