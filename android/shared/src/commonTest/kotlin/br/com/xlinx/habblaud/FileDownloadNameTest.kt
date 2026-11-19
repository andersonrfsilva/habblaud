package br.com.xlinx.habblaud

import kotlin.test.Test
import kotlin.test.assertEquals

class FileDownloadNameTest {
    @Test fun sanitizesUntrustedNames() {
        assertEquals("ab.txt", sanitizedDownloadName("../.a/\\b\n.txt"))
        assertEquals("arquivo", sanitizedDownloadName("..."))
        assertEquals("hidden", sanitizedDownloadName(".. .hidden"))
        assertEquals("abc", sanitizedDownloadName("a\u0000/b\\c\u009f"))
        assertEquals("report?.txt", sanitizedDownloadName("../report?.txt"))
        assertEquals("CON.txt", sanitizedDownloadName("CON.txt"))
        assertEquals("a".repeat(300), sanitizedDownloadName("a".repeat(300)))
    }
}
