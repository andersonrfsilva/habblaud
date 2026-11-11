package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AlignmentRulesTest {
    @Test fun retryAfterNeverExceedsOneDay() {
        val now = 1_700_000_000_000L
        val maximum = now + 86_400_000L
        for (value in listOf("86400", "86401", Long.MAX_VALUE.toString(), "Fri, 01 Jan 2100 00:00:00 GMT")) {
            assertEquals(maximum, retryAfterDeadline(value, now), value)
        }
        assertEquals(now + 30_000, retryAfterDeadline("30", now))
        assertEquals(now, retryAfterDeadline("-1", now))
        assertEquals(now, retryAfterDeadline("Wed, 01 Jan 2020 00:00:00 GMT", now))
        assertEquals(now + 60_000, retryAfterDeadline(null, now))
        assertEquals(now + 60_000, retryAfterDeadline("invalid", now))
    }

    @Test fun permissionInputPreservesStringsAndCompactsStructuredJson() {
        fun input(json: String) = permissionInput(wireJson.parseToJsonElement(json).jsonObject)
        assertEquals("echo teste", input("""{"input":"echo teste"}"""))
        assertEquals("""{"path":"/tmp","args":[1,true]}""", input("""{"input": { "path": "/tmp", "args": [1, true] }}"""))
        assertEquals("[1,\"dois\"]", input("""{"input":[1,"dois"]}"""))
        assertEquals("", input("{}"))
        assertEquals("", input("""{"input":null}"""))
    }
}
