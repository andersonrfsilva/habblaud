package br.com.xlinx.habblaud

import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileDownloadsJvmTest {
    @Test fun sanitizesUntrustedNames() {
        assertEquals("ab.txt", sanitizedDownloadName("../.a/\\b\n.txt"))
        assertEquals("arquivo", sanitizedDownloadName("..."))
        assertEquals("hidden", sanitizedDownloadName(".. .hidden"))
        assertEquals("abc", sanitizedDownloadName("a\u0000/b\\c\u009f"))
        assertEquals("report?.txt", sanitizedDownloadName("../report?.txt"))
        assertEquals("CON.txt", sanitizedDownloadName("CON.txt"))
        assertEquals("a".repeat(300), sanitizedDownloadName("a".repeat(300)))
        assertEquals("report (2).txt", numberedDownloadName("report.txt", 2))
    }

    @Test fun publishesUniqueCompleteFilesAndRemovesTemps() = runBlocking {
        val directory = Files.createTempDirectory("zaplaud-download-test-")
        try {
            Files.writeString(directory.resolve("report.txt"), "original")
            val target = JvmDownloadTarget(directory, "report.txt")
            target.write("complete".toByteArray(), 8)
            assertFalse(Files.exists(directory.resolve("report (1).txt")))
            val saved = target.complete()
            assertEquals("report (1).txt", saved.name)
            assertEquals("original", Files.readString(directory.resolve("report.txt")))
            assertEquals("complete", Files.readString(directory.resolve(saved.name)))
            target.abort()
            Files.list(directory).use { assertEquals(2, it.count().toInt()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun abortAndWriteFailureRemoveTemporaryFiles() = runBlocking {
        val directory = Files.createTempDirectory("zaplaud-download-test-")
        try {
            val aborted = JvmDownloadTarget(directory, "report.txt")
            aborted.write(byteArrayOf(1, 2), 2)
            aborted.abort()
            aborted.abort()
            Files.list(directory).use { assertEquals(0, it.count().toInt()) }
            val failed = JvmDownloadTarget(directory, "report.txt")
            assertFailsWith<IllegalArgumentException> { failed.write(byteArrayOf(1), 2) }
            Files.list(directory).use { assertEquals(0, it.count().toInt()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cancelledWriteRemovesTemporaryFile() = runBlocking {
        val directory = Files.createTempDirectory("zaplaud-download-test-")
        try {
            val target = JvmDownloadTarget(directory, "report.txt")
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                target.write(byteArrayOf(1), 1)
            }
            job.join()
            Files.list(directory).use { assertEquals(0, it.count().toInt()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
