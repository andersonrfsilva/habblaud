package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.TerminalEntry
import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class EntryTimeTest {
    private fun parse(at: String) = TerminalEntry.parse(wireJson.parseToJsonElement("""{"id":"e","kind":"user","text":"oi"$at}""").jsonObject)!!

    @Test fun parsesOnlyPositiveEpochMillis() {
        assertEquals(1_793_000_000_000L, parse(""","at":1793000000000""").at)
        assertNull(parse("").at)
        assertNull(parse(""","at":0""").at)
        assertNull(parse(""","at":-5""").at)
        assertNull(parse(""","at":"ontem"""").at)
        assertEquals("oi", parse(""","at":1793000000000""").text)
    }

    @Test fun showsHourTodayAndDateOnOtherDaysInDeviceZone() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"))
            val now = 1_793_052_000_000L // 2026-10-26 19:00 em São Paulo
            assertEquals("18:55", entryTime(now - 5 * 60_000, now))
            assertEquals("25/10 19:00", entryTime(now - 24 * 3_600_000, now))
            assertEquals("25/10 20:20", entryTime(now - 24 * 3_600_000, now))
        } finally { TimeZone.setDefault(previous) }
    }
}
