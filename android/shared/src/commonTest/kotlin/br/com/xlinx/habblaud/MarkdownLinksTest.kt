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
    @Test fun imageSourcesRequireConsentOnlyForRemoteImagesAndIgnoreCode() {
        val destinations = listOf(
            "data:image/png;base64,aGVsbG8=" to ImageKind.DATA,
            "/abs/path.png" to ImageKind.HOST_FILE,
            "file:///abs.png" to ImageKind.HOST_FILE,
            "//host/x.png" to null,
            "https://example.test/image.png" to ImageKind.REMOTE,
            "javascript:alert(1)" to null,
        )
        destinations.forEach { (dest, kind) -> assertEquals(kind, markdownImageKind(dest), dest) }
        val images = destinations.mapIndexed { index, (dest, _) -> "alt $index" to dest }
        val source = images.joinToString("\n\n") { (alt, dest) -> "![$alt](<$dest>)" } +
            "\n\n```md\n![hidden](https://example.test/hidden.png)\n```\n\n![reference][asset]\n\n[asset]: /ref.png"
        assertEquals(images, markdownImages(source))
        assertEquals(
            listOf("foto" to "/tmp/a.PNG", "" to "https://example.test/b.jpg?x=1", "" to "https://example.test/c.webp"),
            markdownImages("[foto](/tmp/a.PNG) [site](https://example.test/page) <https://example.test/b.jpg?x=1> https://example.test/c.webp `[x](/d.png)`"),
        )
    }
}
