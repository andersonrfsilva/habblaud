package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlPolicyTest {
    @Test fun acceptsAnyHttpOrHttpsServer() {
        assertEquals("http://100.83.131.11:4747", UrlPolicy.origin("http://100.83.131.11:4747"))
        assertEquals("http://192.168.1.10:4747", UrlPolicy.origin(" http://192.168.1.10:4747/ "))
        assertEquals("http://example.com", UrlPolicy.origin("http://Example.com:80"))
        assertEquals("https://example.com", UrlPolicy.origin("https://example.com/"))
        assertEquals("http://localhost:14748", UrlPolicy.origin("http://localhost:14748"))
    }

    @Test fun rejectsPathsCredentialsAndOtherSchemes() {
        assertNull(UrlPolicy.origin("https://example.com/subpath"))
        assertNull(UrlPolicy.origin("https://user@example.com"))
        assertNull(UrlPolicy.origin("https://example.com/?a=1"))
        assertNull(UrlPolicy.origin("100.83.131.11:4747"))
        assertNull(UrlPolicy.origin(""))
    }
}
