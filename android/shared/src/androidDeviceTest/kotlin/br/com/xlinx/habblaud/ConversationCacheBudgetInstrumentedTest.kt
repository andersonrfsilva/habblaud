package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.TerminalEntry
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationCacheBudgetInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = context.filesDir.resolve("conversations")

    @Before fun setUp() = runBlocking { LocalStateStore(context).clear() }
    @After fun tearDown() = runBlocking { LocalStateStore(context).clear() }

    private fun cacheFiles() = root.walkTopDown().filter { it.isFile && it.extension == "json" }

    @Test fun trimsAcrossServersWithoutRemovingTheWrittenFileOrAnotherTemporaryFile() = runBlocking {
        val budget = 200L * 1024
        val store = LocalStateStore(context).apply {
            conversationCacheMaxBytes = budget
            conversationCacheTrimInterval = 1
        }
        val origins = listOf("https://first.example", "https://second.example")
        for (origin in origins) store.addServer(origin)
        val keys = (0 until 10).map { SessionKey(origins[it % 2], "claude", "account", "session$it") }
        val entries = listOf(TerminalEntry("entry", "user", "x".repeat(50 * 1024)))

        suspend fun write(key: SessionKey) {
            store.setConversation(key, entries, false, store.conversationWrite(key.origin, store.conversationEpoch(key.origin)))
        }

        write(keys.first())
        val first = cacheFiles().single()
        assertTrue(first.setLastModified(1_000L))
        val temporary = first.resolveSibling("${first.name}.tmp")
        RandomAccessFile(temporary, "rw").use { it.setLength(budget) }

        for (key in keys.subList(1, keys.lastIndex)) write(key)
        assertNull(store.conversation(keys.first()))

        // Faça os arquivos existentes parecerem mais novos que o próximo arquivo gravado.
        val future = System.currentTimeMillis() + 60 * 60 * 1000
        for (file in cacheFiles()) assertTrue(file.setLastModified(future))
        write(keys.last())

        assertEquals(entries, store.conversation(keys.last())?.entries)
        assertEquals(3, cacheFiles().count())
        assertTrue(cacheFiles().sumOf { it.length() } <= budget)
        assertEquals(budget, temporary.length())
    }
}
