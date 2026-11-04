package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.TerminalEntry
import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class EntryTimestampTest {
    private fun parse(at: String) = TerminalEntry.parse(wireJson.parseToJsonElement("""{"id":"e","kind":"user","text":"oi"$at}""").jsonObject)!!

    @Test fun parsesOnlyPositiveEpochMillis() {
        assertEquals(1_793_000_000_000L, parse(""","at":1793000000000""").at)
        assertNull(parse("").at)
        assertNull(parse(""","at":0""").at)
        assertNull(parse(""","at":-5""").at)
        assertNull(parse(""","at":"ontem"""").at)
        assertEquals("oi", parse(""","at":1793000000000""").text)
    }
}
