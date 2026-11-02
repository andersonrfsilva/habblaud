package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {
    @Test fun acceptsOnlyConfirmedHttpHostWithConsent() {
        assertEquals(BuildConfig.DEBUG, BuildConfig.ALLOW_LOOPBACK_HTTP)
        assertEquals("http://100.64.0.1:4747", UrlPolicy.origin("http://100.64.0.1:4747/", "100.64.0.1", true, false))
        assertNull(UrlPolicy.origin("http://100.64.0.2:4747", "100.64.0.1", true, false))
        assertNull(UrlPolicy.origin("http://100.64.0.1", null, true, false))
        assertNull(UrlPolicy.origin("http://100.64.0.1", "100.64.0.2", true, false))
        assertNull(UrlPolicy.origin("http://100.64.0.1", "100.64.0.1", false, false))
        assertNull(UrlPolicy.origin("http://192.168.1.1", "192.168.1.1", true, false))
        assertNull(UrlPolicy.origin("http://[::1]", "::1", true, false))
        assertNull(UrlPolicy.origin("http://localhost", null, false, false))
        assertEquals("http://localhost", UrlPolicy.origin("http://localhost/", null, false, true))
        assertNull(UrlPolicy.origin("https://example.com/subpath", null, false, false))
        assertEquals("https://example.com", UrlPolicy.origin("https://example.com/", null, false, false))
    }
}
