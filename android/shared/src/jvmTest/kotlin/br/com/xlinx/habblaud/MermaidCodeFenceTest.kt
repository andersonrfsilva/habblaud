package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.AnnotatedString
import br.com.xlinx.habblaud.data.HabblaudApi
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MermaidCodeFenceTest {
    @Test fun closedMermaidLoadsSvgAndOtherFencesStayCode() = runDesktopComposeUiTest {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val source = "graph TD\nA --> B"
        val clipboard = object : ClipboardManager {
            var content: AnnotatedString? = null
            override fun getText() = content
            override fun setText(annotatedString: AnnotatedString) { content = annotatedString }
        }
        server.createContext("/api/mermaid") { exchange ->
            calls.incrementAndGet()
            val body = exchange.requestBody.readBytes().decodeToString()
            if (body.contains("invalid diagram")) {
                exchange.sendResponseHeaders(422, -1)
                exchange.close()
                return@createContext
            }
            assertTrue(body.contains("graph TD\\nA --> B"))
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="white"/><text x="10" y="40" fill="black">A → B</text></svg>""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "image/svg+xml")
            exchange.sendResponseHeaders(200, svg.size.toLong())
            exchange.responseBody.use { it.write(svg) }
        }
        server.start()
        val api = HabblaudApi("http://127.0.0.1:${server.address.port}")
        try {
            setContent {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    MaterialTheme {
                        Markdown("    val indented = true\n\n```mermaid\n$source\n```\n\n```kotlin\nval answer = 42\n```\n\n```mermaid\ninvalid diagram\n```\n\n```mermaid\ngraph TD\nC -->", components = markdownComponents(codeFence = { MermaidCodeFence(it, api) }, codeBlock = { CopyableCodeBlock(it) }))
                    }
                }
            }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription(source).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(source).assertExists()
            onNodeWithText("val answer = 42").assertExists()
            onAllNodesWithText("Copiar")[0].performClick()
            runOnIdle { assertEquals("val indented = true", clipboard.getText()?.text) }
            onAllNodesWithText("Copiar")[2].performClick()
            runOnIdle { assertEquals("val answer = 42", clipboard.getText()?.text) }
            onNodeWithText("invalid diagram").assertExists()
            onNodeWithText("graph TD", substring = true).assertExists()
            waitUntil(timeoutMillis = 15_000) { calls.get() == 2 }
            assertEquals(2, calls.get())
        } finally {
            api.close()
            server.stop(0)
        }
    }
    @Test fun markdownImagesRemainVisibleAsLinksWithoutFetchingThem() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Markdown(markdownImageLinks("![Preview](https://example.invalid/private.png)"))
            }
        }
        waitUntil(timeoutMillis = 15_000) { onAllNodesWithText("Preview").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Preview").assertExists()
    }
}
