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
            "/relative.png", "//example.test/image.png", "https://", "https:///path", "",
            "https://user:pw@example.com/a.png", "https://example.com\\@evil/a.png",
            "https://example.com/a\\b.png").forEach {
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
        assertEquals(images + ("reference" to "/ref.png"), markdownImages(source))
        assertEquals(
            listOf("foto" to "/tmp/a.PNG", "" to "https://example.test/b.jpg?x=1", "" to "https://example.test/c.webp"),
            markdownImages("[foto](/tmp/a.PNG) [site](https://example.test/page) <https://example.test/b.jpg?x=1> https://example.test/c.webp `[x](/d.png)`"),
        )
    }
    @Test fun imageReferencesResolveNormalizedLabelsAndShareTheExistingLoadPolicy() {
        val source = """
            ![remote][ ASSet  One ] ![logo][] ![inline-data] ![missing]

            [plain link][another] ![scheme][bad] ![repeat][asset one]

            ~~~md
            [asset one]: /hidden.png
            [missing]: /also-hidden.png
            ~~~

            [asset one]: https://example.test/image?x=1&amp;y=2
            [logo]: </proj/Arte Final/logo.svg> "Logotipo"
            [inline-data]: data:image/png;base64,aGVsbG8=
            [another]: /proj/other.jpg
            [bad]: javascript:alert(1)
            [asset one]: https://example.test/duplicate
        """.trimIndent()
        val images = markdownImages(source)
        assertEquals(listOf(
            "remote" to "https://example.test/image?x=1&y=2",
            "logo" to "/proj/Arte Final/logo.svg",
            "inline-data" to "data:image/png;base64,aGVsbG8=",
            "plain link" to "/proj/other.jpg",
            "scheme" to "javascript:alert(1)",
        ), images)
        assertEquals(listOf(ImageKind.REMOTE, ImageKind.HOST_FILE, ImageKind.DATA, ImageKind.HOST_FILE, null), images.map { markdownImageKind(it.second) })
    }

    @Test fun tappingAHostFileThatIsNotAnImageDownloadsIt() {
        listOf("/proj/sub/fonte.aseprite", "</proj/Arte Final/fonte.aseprite>", "file:///proj/notas.txt").forEach {
            assertEquals(LinkAction.DOWNLOAD, markdownLinkAction(it), it)
        }
        assertEquals("/proj/Arte Final/fonte.aseprite", hostFilePath("</proj/Arte Final/fonte.aseprite>".removeSurrounding("<", ">")))
        assertEquals("/proj/notas.txt", hostFilePath("file:///proj/notas.txt"))
        listOf("https://example.test/a.zip", "http://localhost:4747/x").forEach { assertEquals(LinkAction.BROWSER, markdownLinkAction(it), it) }
        // Imagem já aparece desenhada; relativo, // e esquemas de aplicativo não fazem nada.
        listOf("/proj/arte.png", "/proj/anim.GIF", "sub/fonte.aseprite", "fonte.aseprite", "//host/x.bin", "javascript:alert(1)", "mailto:a@b.c", "").forEach {
            assertEquals(LinkAction.NONE, markdownLinkAction(it), it)
        }
    }
    @Test fun linksTheServerResolvedAreDrawnAsHostImages() {
        // Saída do servidor (server/sources/links.ts) para ![…](arte.png), […](sub/anim.gif) e […](sub/fonte.aseprite).
        val source = "![Quatro vistas](/tmp/proj/arte.png)\n[Caminhada](</tmp/proj/Arte Final/anim.gif>)\n[Aseprite](/tmp/proj/sub/fonte.aseprite)"
        val images = markdownImages(source)
        assertEquals(listOf("Quatro vistas" to "/tmp/proj/arte.png", "Caminhada" to "/tmp/proj/Arte Final/anim.gif"), images)
        images.forEach { (_, dest) -> assertEquals(ImageKind.HOST_FILE, markdownImageKind(dest), dest) }
        assertEquals(LinkAction.DOWNLOAD, markdownLinkAction("/tmp/proj/sub/fonte.aseprite"))
    }
}
