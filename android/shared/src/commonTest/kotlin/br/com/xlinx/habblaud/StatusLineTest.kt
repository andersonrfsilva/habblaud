package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.localTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class StatusLineTest {
    @Test fun stopwatchCountsSecondsMinutesAndHours() {
        assertEquals("0:00", stopwatch(-500))
        assertEquals("0:07", stopwatch(7_999))
        assertEquals("12:31", stopwatch(751_000))
        assertEquals("1:02:10", stopwatch(3_730_000))
    }

    @Test fun activityLosesTrailingEllipsisAndBlankFallsBack() {
        assertEquals("Lendo arquivo", statusLineText("  Lendo arquivo... "))
        assertEquals("Pensando", statusLineText("Pensando…"))
        assertNull(statusLineText("  "))
        assertNull(statusLineText("…"))
        assertNull(statusLineText(null))
    }

    @Test fun serverTimeMovesByClockSkewRoundedToSecond() {
        // Celular 5 s adiantado, com 80 ms de rede: o início do estado anda 5 s, não 5,08 s.
        assertEquals(1_005_000L, localTime(1_000_000, serverNow = 2_000_000, now = 2_005_080))
        assertEquals(1_001_000L, localTime(1_000_000, serverNow = 2_000_000, now = 2_000_500))
        assertEquals(1_000_000L, localTime(1_000_000, serverNow = 2_000_000, now = 1_999_500))
        assertEquals(1_000_000L, localTime(1_000_000, serverNow = 0, now = 9_999_999))
    }
}
