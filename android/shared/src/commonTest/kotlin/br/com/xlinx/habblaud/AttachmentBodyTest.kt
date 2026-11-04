package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

class AttachmentBodyTest {
    private fun body(bytes: ByteArray, size: Long? = bytes.size.toLong()) = AttachmentBody(size, {
        object : AttachmentSource {
            private var offset = 0
            override fun read(buffer: ByteArray): Int {
                if (offset == bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + count)
                offset += count
                return count
            }
            override fun close() = Unit
        }
    })
    private suspend fun collect(body: AttachmentBody, capacity: Int): ByteArray {
        val output = ByteArray(capacity)
        var offset = 0
        body.writeTo { bytes, count -> bytes.copyInto(output, offset, 0, count); offset += count }
        assertEquals(capacity, offset)
        return output
    }
    private suspend fun discard(body: AttachmentBody) { body.writeTo { _, _ -> } }

    @Test fun sizeBoundariesAndUnknownLength() = runTest {
        val one = byteArrayOf(42)
        assertContentEquals(one, collect(body(one), 1))
        val max = ByteArray(MAX_ATTACHMENT_SIZE.toInt()) { (it % 251).toByte() }
        assertContentEquals(max, collect(body(max), max.size))
        assertFailsWith<IllegalArgumentException> { discard(body(ByteArray(0))) }
        assertFailsWith<IllegalArgumentException> { discard(body(ByteArray(MAX_ATTACHMENT_SIZE.toInt() + 1))) }
        assertFailsWith<IllegalStateException> { discard(body(ByteArray(0), null)) }
        assertFailsWith<IllegalStateException> { discard(body(ByteArray(MAX_ATTACHMENT_SIZE.toInt() + 1), null)) }
        assertNull(body(one, null).size)
        assertContentEquals(one, collect(body(one, null), 1))
        assertFailsWith<IllegalStateException> { discard(body(one, 2)) }
    }

    @Test fun revokedAccessFailsAndSelectionLimitIsEnforced() = runTest {
        assertFailsWith<IOException> { discard(AttachmentBody(null, { throw IOException("revoked") })) }
        checkAttachmentCount(4, 1)
        assertFailsWith<IllegalArgumentException> { checkAttachmentCount(0, 6) }
    }
}
