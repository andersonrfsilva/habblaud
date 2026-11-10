package br.com.xlinx.habblaud

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.initializeStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class FixtureFilesInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments = InstrumentationRegistry.getArguments()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val name get() = requireNotNull(arguments.getString("filesName"))
    private val expected = "Arquivo fictício de aceitação.\n".toByteArray(Charsets.UTF_8)

    @Before fun prepare() {
        check(arguments.getString("fixture") == "true") { "Execute scripts/android-files-instrumented.py." }
        assumeTrue("Downloads público exige Android 10 ou posterior", Build.VERSION.SDK_INT >= 29)
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
        initializeStorage(context)
        runBlocking { LocalStateStore(context).clear() }
    }

    @After fun cleanup() {
        scenario?.close()
        if (Build.VERSION.SDK_INT >= 29 && arguments.getString("filesName") != null) {
            downloads().forEach { context.contentResolver.delete(it, null, null) }
        }
        runBlocking { LocalStateStore(context).clear() }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun login(argument: String): String {
        val origin = requireNotNull(arguments.getString(argument))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitTag("server-url")
        compose.onNodeWithTag("server-url").performTextReplacement(origin)
        compose.onNodeWithTag("username").performTextInput("android-test")
        compose.onNodeWithTag("password").performTextInput("android-test")
        compose.onNodeWithTag("login-submit").performClick()
        awaitTag("http-confirm")
        compose.onNodeWithTag("http-confirm").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.menu)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.menu)).performClick()
        compose.onNodeWithTag("files-menu").performClick()
        awaitTag("files-sheet")
        return origin
    }

    private fun downloads(): List<android.net.Uri> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return context.contentResolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null
        )!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }
    }

    @Test fun navigatesSubfolderAndSavesExactBytesInPublicDownloads() {
        assertEquals("Nome exclusivo não pode existir antes do teste", emptyList<android.net.Uri>(), downloads())
        login("filesOrigin")
        awaitTag("files-entry:documentos")
        compose.onNodeWithTag("files-entry:documentos").performClick()
        awaitTag("files-download:$name")
        for (theme in listOf("light", "dark")) {
            runBlocking { LocalStateStore(context).setTheme(theme) }
            compose.waitForIdle()
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                requireNotNull(context.getExternalFilesDir(null)).resolve("files-phone-$theme.png").outputStream().use {
                    check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        }
        compose.onNodeWithTag("files-download:$name").performClick()
        awaitTag("files-completed:$name")
        val saved = downloads()
        assertEquals("Um arquivo deve ser publicado em Downloads", 1, saved.size)
        val bytes = context.contentResolver.openInputStream(saved.single())!!.use { it.readBytes() }
        assertArrayEquals(expected, bytes)
        assertEquals(1, context.contentResolver.delete(saved.single(), null, null))
        assertEquals(emptyList<android.net.Uri>(), downloads())
    }

    @Test fun fixtureWithoutTerminalRejectsFilesWith403() {
        val origin = login("filesDeniedOrigin")
        awaitTag("files-error")
        compose.onNodeWithTag("files-error").assertTextContains("arquivos desligados", substring = true)
        runBlocking {
            HabblaudApi(origin).use { api ->
                val connection = URL("$origin/api/files").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("Cookie", requireNotNull(api.cookies.requestHeader("$origin/api/files")))
                    assertEquals(403, connection.responseCode)
                } finally { connection.disconnect() }
            }
        }
    }
}
