package br.com.xlinx.habblaud.data

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class SseFrameLimitTest {
    private fun feed(limit: SseFrameLimit, bytes: ByteArray, chunkSize: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            for (index in offset until end) limit.accept(bytes[index])
            offset = end
        }
    }

    @Test fun utf8AndEveryChunkBoundaryPreserveFraming() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val event = "data: ação 🦜${ending}${ending}".encodeToByteArray()
            val maximum = "data: ação 🦜".encodeToByteArray().size
            for (chunk in 1..event.size) feed(SseFrameLimit(maximum), event + event, chunk)
        }
    }

    @Test fun oversizedSingleLineFailsBeforeDelimiter() {
        assertFailsWith<SseLimitException> { feed(SseFrameLimit(8), "data: abc".encodeToByteArray(), 1) }
    }

    @Test fun multilineDataSharesOneBound() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            assertFailsWith<SseLimitException> {
                feed(SseFrameLimit(15), "data: a${ending}data: b${ending}data: c".encodeToByteArray(), 1)
            }
        }
    }

    @Test fun commentsAndUnknownFieldsCannotGrowParserWithoutBound() {
        for (line in listOf(": comment", "unknown: ignored", "data:", "id: retained", "event: kind")) {
            assertFailsWith<SseLimitException> {
                feed(SseFrameLimit(64), ("$line\r\n".repeat(65)).encodeToByteArray(), 3)
            }
        }
    }

    @Test fun splitCrLfCountsOneTerminatorAndBlankLineResets() {
        feed(SseFrameLimit(7), "data: a\r\n\r\ndata: b\n\ndata: c\r\r".encodeToByteArray(), 1)
    }

    @Test fun ktorDataSeparatorMatchesEstablishedLfProtocol() {
        assertEquals("one\ntwo\n\ntrês 🦜", sseData("one\r\ntwo\r\n\r\ntrês 🦜"))
        assertEquals("one\ntwo", sseData("one\ntwo"))
    }

    @Test fun dataSeparatorAdaptationPreservesEscapedJsonContent() {
        val json = """{"text":"one\r\ntwo\nthree 🦜"}"""
        assertEquals(json, sseData(json))
    }
}
