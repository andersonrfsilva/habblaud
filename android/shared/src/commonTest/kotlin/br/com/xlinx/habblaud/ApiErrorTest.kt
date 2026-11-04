package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.apiError
import kotlin.test.*

class ApiErrorTest {
    @Test fun realErrorParserDistinguishesSessionConflict() {
        val conflict = apiError(409, null, "{\"error\":\"Sessão mudou\",\"code\":\"session_changed\"}")
        assertEquals("session_changed", conflict.code)
        assertNull(apiError(409, null, "{\"error\":\"session_changed\"}").code)
        assertNull(apiError(409, null, "not json").code)
        assertTrue(apiError(409, null, "{\"attachmentsExpired\":true}").attachmentsExpired)
    }
}
