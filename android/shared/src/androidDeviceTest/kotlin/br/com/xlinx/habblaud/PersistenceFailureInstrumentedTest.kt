package br.com.xlinx.habblaud

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.launchPersist
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceFailureInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val origin = "https://persistence-failure.example"

    @Before fun setUp() = runBlocking { LocalStateStore(context).clear() }
    @After fun tearDown() = runBlocking { LocalStateStore(context).clear() }

    private fun failedWrite(retry: Boolean) = runBlocking {
        withTimeout(15_000) {
            val store = LocalStateStore(context)
            store.addServer(origin)
            val epoch = store.conversationEpoch(origin)
            val marks = mapOf("x" to 1L)
            val directory = File(context.filesDir, "datastore")
            val files = listOf(directory) + checkNotNull(directory.listFiles()).toList()
            val modes = files.associateWith { Os.stat(it.path).st_mode and 0xFFF }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val uncaught = AtomicReference<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.compareAndSet(null, error) }
            try {
                try {
                    files.reversed().forEach { file ->
                        assertTrue("Could not deny writes to $file", file.setWritable(false, false))
                        assertFalse("Writes remain allowed for $file", file.canWrite())
                    }
                    val failure = AtomicReference<IOException>()
                    val job = launchPersist(scope) {
                        try {
                            store.setSeenReplies(origin, marks, epoch)
                        } catch (e: IOException) {
                            failure.set(e)
                            throw e
                        }
                    }
                    job.join()
                    assertNotNull("Real DataStore write must fail without write permission", failure.get())
                    assertFalse("Persistence failure escaped containment", job.isCancelled)
                    assertNull("Persistence failure reached uncaught handler", uncaught.get())
                    assertTrue("Failed write changed persisted marks", store.seenReplies(origin).isEmpty())
                } finally {
                    modes.forEach { (file, mode) -> Os.chmod(file.path, mode) }
                }
                if (retry) {
                    val job = launchPersist(scope) { store.setSeenReplies(origin, marks, epoch) }
                    job.join()
                    assertFalse(job.isCancelled)
                    assertNull(uncaught.get())
                    assertEquals(marks, LocalStateStore(context).seenReplies(origin))
                }
            } finally {
                scope.cancel()
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }
    }

    @Test fun realWriteFailureDoesNotReachUncaughtHandler() = failedWrite(retry = false)

    @Test fun nextWritePersistsAfterPermissionsAreRestored() = failedWrite(retry = true)

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = launchPersist(scope) { throw CancellationException("fixture cancellation") }
            withTimeout(15_000) { job.join() }
            assertTrue("Cancellation must remain visible on the Job", job.isCancelled)
        } finally {
            scope.cancel()
        }
    }
}
