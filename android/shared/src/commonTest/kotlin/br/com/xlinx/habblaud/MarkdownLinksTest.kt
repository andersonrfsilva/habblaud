package br.com.xlinx.habblaud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownLinksTest {
    @Test fun onlyAbsoluteHttpLinksCanLeaveTheConversation() {
        listOf("https://example.test/image.png", "http://localhost:4747/path", "HTTPS://example.test").forEach {
            assertTrue(isHttpMarkdownLink(it), it)
        }
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://host", "mailto:a@example.test",
            "/relative.png", "//example.test/image.png", "https://", "https:///path", "").forEach {
            assertFalse(isHttpMarkdownLink(it), it)
        }
    }
    @Test fun imageLinksPreserveMarkdownAndNeverRewriteCodeOrEscapes() {
        val source = "Before ![preview](https://example.test/a.png) after.\n\n![ref][asset]\n\n[asset]: https://example.test/b.png"
        assertEquals(source.replace("![preview]", "[preview]").replace("![ref]", "[ref]"), markdownImageLinks(source))
        val code = "`![inline](https://example.test)`\n\n```md\n![fenced](https://example.test)\n```\n\n    ![indented](https://example.test)\n\n\\![escaped](https://example.test)"
        assertEquals(code, markdownImageLinks(code))
        val nested = "[![alt](https://example.test/image)](https://example.test/page)"
        assertEquals("[[alt](https://example.test/image)](https://example.test/page)", markdownImageLinks(nested))
    }
}
