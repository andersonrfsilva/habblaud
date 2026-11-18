package br.com.xlinx.habblaud

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.SecureSessionStorage
import br.com.xlinx.habblaud.data.initializeStorage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStorageAtomicInstrumentedTest {
    private lateinit var file: File
    private lateinit var storage: SecureSessionStorage

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        initializeStorage(context)
        file = context.filesDir.resolve("remote-session")
        storage = SecureSessionStorage()
        storage.clear()
    }

    @After fun tearDown() {
        storage.clear()
    }

    @Test fun roundTripAndOverwrite() {
        storage.write("a")
        assertEquals("a", storage.read())
        storage.write("b")
        assertEquals("b", storage.read())
    }

    @Test fun legacyFileStillReads() {
        storage.write("legacy")
        val bytes = file.readBytes()
        storage.clear()
        file.writeBytes(bytes)
        assertEquals("legacy", SecureSessionStorage().read())
    }

    @Test fun truncatedFileIsDiscarded() {
        file.writeBytes(ByteArray(5))
        assertNull(storage.read())
        assertFalse(file.exists())
    }

    @Test fun readFailureLeavesSessionPathIntact() {
        // Opening a directory as a file fails without relying on permissions.
        assertTrue(file.mkdir())
        assertNull(storage.read())
        assertTrue(file.isDirectory)
    }

    @Test fun interruptedWriteKeepsPreviousValue() {
        storage.write("a")
        // Close the stream without committing or rolling back the pending write.
        AtomicFile(file).startWrite().use { it.write(ByteArray(5)) }
        assertEquals("a", SecureSessionStorage().read())
    }
}
