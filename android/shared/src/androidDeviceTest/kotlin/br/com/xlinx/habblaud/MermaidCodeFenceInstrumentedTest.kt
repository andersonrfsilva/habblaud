package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.HabblaudApi
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.copy
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MermaidCodeFenceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closedMermaidLoadsSvgAndOtherFencesStayCode() {
        val source = "graph TD\nA --> B"
        val invalid = "invalid diagram"
        val clipboard = object : ClipboardManager {
            var content: AnnotatedString? = null
            override fun getText() = content
            override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
        }
        val copy = runBlocking { getString(Res.string.copy) }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/api/mermaid" && request.body.clone().readUtf8().contains("graph TD\\nA --> B")) {
                        MockResponse().setHeader("Content-Type", "image/svg+xml").setBody(
                            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="white"/></svg>""",
                        )
                    } else MockResponse().setResponseCode(422)
            }
            HabblaudApi(server.url("/").toString().trimEnd('/')).use { api ->
                compose.setContent {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        MaterialTheme {
                            Markdown(
                                "    val indented = true\n\n```mermaid\n$source\n```\n\n```kotlin\nval answer = 42\n```\n\n```mermaid\n$invalid\n```\n\n```mermaid\ngraph TD\nC -->",
                                components = markdownComponents(codeFence = { MermaidCodeFence(it, api) }, codeBlock = { CopyableCodeBlock(it) }),
                            )
                        }
                    }
                }
                compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription(source).fetchSemanticsNodes().isNotEmpty() }
                val requests = List(2) { requireNotNull(server.takeRequest(15, TimeUnit.SECONDS)) }
                assertEquals(listOf("/api/mermaid", "/api/mermaid"), requests.map { it.path })
                assertEquals(setOf(source, invalid), requests.map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject.getValue("source").jsonPrimitive.content }.toSet())
                compose.onNodeWithText("val answer = 42").assertExists()
                compose.onAllNodesWithText(copy)[0].performClick()
                compose.runOnIdle { assertEquals("val indented = true", clipboard.getText()?.text) }
                compose.onAllNodesWithText(copy)[2].performClick()
                compose.runOnIdle { assertEquals("val answer = 42", clipboard.getText()?.text) }
                compose.onNodeWithText(invalid).assertExists()
                compose.onNodeWithText("graph TD", substring = true).assertExists()
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun markdownImagesRemainVisibleAsLinksWithoutFetchingThem() {
        compose.setContent {
            MaterialTheme { Markdown(markdownImageLinks("![Preview](https://example.invalid/private.png)")) }
        }
        compose.onNodeWithText("Preview").assertExists()
    }
}
