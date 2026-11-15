package br.com.xlinx.habblaud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
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
                MaterialTheme {
                    Markdown("```mermaid\n$source\n```\n\n```kotlin\nval answer = 42\n```\n\n```mermaid\ninvalid diagram\n```\n\n```mermaid\ngraph TD\nC -->", components = markdownComponents(codeFence = { MermaidCodeFence(it, api) }))
                }
            }
            waitUntil(timeoutMillis = 15_000) { onAllNodesWithContentDescription(source).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription(source).assertExists()
            onNodeWithText("val answer = 42").assertExists()
            onNodeWithText("invalid diagram").assertExists()
            onNodeWithText("graph TD", substring = true).assertExists()
            waitUntil(timeoutMillis = 15_000) { calls.get() == 2 }
            assertEquals(2, calls.get())
        } finally {
            api.close()
            server.stop(0)
        }
    }
}
