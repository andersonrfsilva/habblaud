package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlin.test.*

class FilesTest {
    private val roots = listOf(FileRoot("/work", "geral"), FileRoot("/work/nested", "sala", "room"))
    @Test fun roomWinsThenDeepestProjectThenFirstRoot() {
        assertEquals(roots[1], initialFileRoot(roots, "room", "/work/other"))
        assertEquals(roots[1], initialFileRoot(roots, null, "/work/nested/project"))
        assertEquals(roots[0], initialFileRoot(roots, null, "/work-other"))
        assertNull(initialFileRoot(emptyList(), null, null))
    }
    @Test fun breadcrumbsKeepRootBoundary() {
        assertEquals(listOf("sub" to "/work/sub", "ação" to "/work/sub/ação"), fileBreadcrumbs("/work", "/work/sub/ação"))
        assertEquals(emptyList(), fileBreadcrumbs("/work", "/work-other/sub"))
        assertEquals(emptyList(), fileBreadcrumbs("/work", "/work"))
        assertEquals(listOf("tmp" to "/tmp"), fileBreadcrumbs("/", "/tmp"))
    }
    @Test fun parsesServerListingWithoutOptionalFields() {
        val result = parseFileListing("""{"path":"/work","root":"/work","entries":[{"name":"a","type":"file","size":5000000000,"mtime":1794348237574.0789}],"truncated":true}""")
        assertEquals(5_000_000_000, result.entries.single().size)
        assertEquals(1794348237574L, result.entries.single().mtime)
        assertTrue(result.truncated)
    }
}
