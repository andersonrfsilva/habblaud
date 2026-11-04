package br.com.xlinx.habblaud

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class EntryTimeTest {

    @Test fun showsHourTodayAndDateOnOtherDaysInDeviceZone() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
        try {
            val now = 1_793_052_000_000L // 2026-10-26 19:00 em São Paulo
            assertEquals("18:55", entryTime(now - 5 * 60_000, now))
            assertEquals("25/10 19:00", entryTime(now - 24 * 3_600_000, now))
        } finally { TimeZone.setDefault(previous) }
    }
}
