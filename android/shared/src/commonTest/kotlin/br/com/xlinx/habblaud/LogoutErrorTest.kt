package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.resources.*
import kotlin.test.*

class LogoutErrorTest {
    @Test fun logoutReportsStorageFailureEvenWhenRevocationFails() {
        assertEquals(Res.string.storage_error, logoutError(cleaned = false, revoked = false))
        assertEquals(Res.string.storage_error, logoutError(cleaned = false, revoked = true))
        assertEquals(Res.string.logout_unconfirmed, logoutError(cleaned = true, revoked = false))
        assertNull(logoutError(cleaned = true, revoked = true))
    }
}
