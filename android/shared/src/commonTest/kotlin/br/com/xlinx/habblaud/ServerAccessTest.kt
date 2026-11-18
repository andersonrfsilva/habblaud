package br.com.xlinx.habblaud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerAccessTest {
    @Test fun allFlagsAllowed() {
        val access = serverAccess("""{"terminal":true,"messages":true,"messageSessionGuard":true,"permissions":true,"files":true}""")
        assertEquals(ServerAccess(true, true, true, true, true), access)
        assertEquals("allowed", messagingState(access))
    }

    @Test fun messagingRequiresGuard() {
        assertEquals("guard", messagingState(serverAccess("""{"messages":true}""")))
        assertEquals("guard", messagingState(serverAccess("""{"messages":true,"messageSessionGuard":false}""")))
        assertEquals("blocked", messagingState(serverAccess("""{"messages":false,"messageSessionGuard":true}""")))
    }

    @Test fun absentFlagsAreUnknown() {
        val access = serverAccess("""{"terminal":true}""")
        assertNull(access.files)
        assertEquals("unknown", messagingState(access))
    }

    @Test fun terminalAndPermissionsAreIndependent() {
        val access = serverAccess("""{"terminal":false,"permissions":true}""")
        assertEquals(false, access.reading)
        assertEquals(true, access.permissions)
    }

    @Test fun rejectsNonObjectJson() {
        assertFailsWith<IllegalArgumentException> { serverAccess("[]") }
    }
}
