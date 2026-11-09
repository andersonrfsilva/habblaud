package br.com.xlinx.habblaud.data

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AttachmentsJvmTest {
    private lateinit var directory: Path
    private val attachments = Attachments()
    @BeforeTest fun reset() = runTest {
        LocalStateStore().clear()
        settingsNode.let { it.removeNode(); it.flush() }
        Files.walk(Path.of(System.getProperty("zaplaud.dataDir"))).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        directory = Files.createTempDirectory("zaplaud-attachment-test")
    }
    @AfterTest fun cleanup() {
        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
    private fun file(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): Path = Files.write(directory.resolve(name), bytes)
    @Test fun selectionStreamsOriginalBytesAndIgnoresDuplicates() = runTest {
        val path = file("small.txt")
        val uri = path.toUri().toString()
        val selected = attachments.select(listOf(uri, uri), emptyList()).single()
        assertEquals("small.txt", selected.name)
        assertEquals(3L, selected.size)
        assertTrue(selected.persistent)
        assertTrue(attachments.persisted(selected))
        assertEquals(listOf(selected), attachments.select(listOf(uri), listOf(selected)))
        val bytes = mutableListOf<Byte>()
        val progress = mutableListOf<Long>()
        attachments.body(selected, progress::add).writeTo { buffer, size -> bytes.addAll(buffer.take(size)) }
        assertContentEquals(byteArrayOf(1, 2, 3), bytes.toByteArray())
        assertEquals(3L, progress.last())
        attachments.release(selected)
        assertTrue(Files.exists(path))
    }
    @Test fun rejectsEmptyAndOversizedFiles() = runTest {
        val empty = file("empty", byteArrayOf())
        val large = file("large")
        RandomAccessFile(large.toFile(), "rw").use { it.setLength(MAX_ATTACHMENT_SIZE + 1) }
        assertFailsWith<IllegalStateException> { attachments.select(listOf(empty.toUri().toString()), emptyList()) }
        assertFailsWith<IllegalStateException> { attachments.select(listOf(large.toUri().toString()), emptyList()) }
    }
    @Test fun rejectsSixSelections() = runTest {
        val uris = (1..6).map { file("file-$it").toUri().toString() }
        assertFailsWith<IllegalArgumentException> { attachments.select(uris, emptyList()) }
    }
    @Test fun availabilityTracksDeletionAndSizeChanges() = runTest {
        val path = file("changing")
        val selected = attachments.select(listOf(path.toUri().toString()), emptyList()).single()
        Files.write(path, byteArrayOf(1))
        assertFalse(attachments.available(selected))
        Files.write(path, byteArrayOf(1, 2, 3))
        assertTrue(attachments.available(selected))
        Files.delete(path)
        assertFalse(attachments.available(selected))
        assertFalse(attachments.persisted(selected))
    }
}
