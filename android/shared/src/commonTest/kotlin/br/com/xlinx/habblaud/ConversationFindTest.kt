package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.FindBlock
import br.com.xlinx.habblaud.data.FindLocation
import br.com.xlinx.habblaud.data.FindOccurrence
import br.com.xlinx.habblaud.data.conversationMatches
import br.com.xlinx.habblaud.data.findMarkdownBlocks
import br.com.xlinx.habblaud.data.findRanges
import br.com.xlinx.habblaud.data.initialFindIndex
import br.com.xlinx.habblaud.data.normalizeFindQuery
import br.com.xlinx.habblaud.data.normalizeFindText
import br.com.xlinx.habblaud.data.stepFindIndex
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConversationFindTest {
    @Test fun normalizaCaixaEAcentosCompostosOuDecompostos() {
        assertEquals("acao", normalizeFindQuery("  AÇÃO \t"))
        assertEquals("acao", normalizeFindText("AC\u0327A\u0303O"))
        assertEquals("α", normalizeFindText("Ά"))
    }

    @Test fun contaOcorrenciasNaOrdemDaMensagemDoBlocoEDaPosicao() {
        val messages = listOf(
            listOf(FindBlock(0, "Reação e ação"), FindBlock(23, "AÇÃO")),
            emptyList(),
            listOf(FindBlock(0, "Ocorrência")),
            listOf(FindBlock(0, "AÇÕES e ação")),
        )
        assertEquals(listOf(
            FindOccurrence(0, FindLocation(0, 0)),
            FindOccurrence(0, FindLocation(0, 1)),
            FindOccurrence(0, FindLocation(23, 0)),
            FindOccurrence(3, FindLocation(0, 0)),
        ), conversationMatches(messages, normalizeFindQuery(" ação ")))
        assertEquals(emptyList(), conversationMatches(messages, normalizeFindQuery(" \n\t ")))
        assertEquals(emptyList(), conversationMatches(messages, normalizeFindQuery("\u0301")))
    }

    @Test fun escolheAPartirDoTopoOuVoltaAoUltimoAcima() {
        val matches = listOf(1, 4, 7).map { FindOccurrence(it, FindLocation(0, 0)) }
        assertEquals(0, initialFindIndex(matches, 0))
        assertEquals(1, initialFindIndex(matches, 4))
        assertEquals(2, initialFindIndex(matches, 5))
        assertEquals(2, initialFindIndex(matches, 10))
        assertEquals(-1, initialFindIndex(emptyList(), 0))
    }

    @Test fun navegacaoCircular() {
        assertEquals(1, stepFindIndex(0, 3, 1))
        assertEquals(0, stepFindIndex(2, 3, 1))
        assertEquals(2, stepFindIndex(0, 3, -1))
        assertEquals(0, stepFindIndex(0, 1, -1))
        assertEquals(-1, stepFindIndex(-1, 0, 1))
    }

    @Test fun extraiTextoECodigoSemMarcacaoOuDestinosDeLinks() {
        val markdown = """
            # Olá **mundo**, ~~riscado~~ e [rótulo][ref].

            ![Imagem](https://imagem.invalid) &amp; `**literal**`

            ```javascript
            alert("&amp;")
            ```

            [ref]: https://referencia.invalid
        """.trimIndent()
        val blocks = findMarkdownBlocks(markdownImageLinks(markdown))
        val text = blocks.joinToString("\n") { it.normalized }
        assertContains(text, "ola mundo, riscado e rotulo.")
        assertContains(text, "imagem &")
        assertContains(text, "**literal**")
        assertContains(text, "alert(\"&amp;\")")
        assertFalse("https://" in text)
        assertFalse("javascript" in text)
    }

    @Test fun marcaTextoOriginalSemSobreporOcorrencias() {
        val text = "Ação AC\u0327A\u0303O"
        val ranges = findRanges(text, normalizeFindQuery("acao"))
        assertEquals(listOf("Ação", "AC\u0327A\u0303O"), ranges.map { text.substring(it) })
        assertEquals(listOf(0..1, 2..3), findRanges("AAAAA", normalizeFindQuery("aa")))
        assertEquals(listOf(0..0), findRanges("각", normalizeFindQuery("ᄀ")))
    }

    @Test fun buscaFimDeRespostaLongaSemCasarEntreBlocos() {
        val prefix = (1..100).joinToString("\n\n") { "Parágrafo $it" }
        val source = "$prefix\n\nUma **palavra** rara\n\nOutro parágrafo"
        val blocks = findMarkdownBlocks(source)
        val match = conversationMatches(listOf(blocks), normalizeFindQuery("palavra rara")).single()
        assertEquals(FindLocation(prefix.length + 2, 0), match.location)
        assertEquals(emptyList(), conversationMatches(listOf(blocks), normalizeFindQuery("rara\nOutro")))
        val table = findMarkdownBlocks("| esquerda | direita |\n| --- | --- |\n| sol | lua |")
        assertEquals(1, conversationMatches(listOf(table), normalizeFindQuery("direita")).size)
        assertEquals(emptyList(), conversationMatches(listOf(table), normalizeFindQuery("sol lua")))
    }
}
