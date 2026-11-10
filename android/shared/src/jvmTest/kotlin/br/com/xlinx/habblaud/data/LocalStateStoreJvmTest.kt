package br.com.xlinx.habblaud.data

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalStateStoreJvmTest {
    @BeforeTest fun reset() = runTest {
        LocalStateStore().clear()
        settingsNode.let { it.removeNode(); it.flush() }
        Files.walk(Path.of(System.getProperty("zaplaud.dataDir"))).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
    @Test fun serverPersistsInNewStore() = runTest {
        LocalStateStore().addServer("https://one.invalid")
        assertEquals(listOf("https://one.invalid"), LocalStateStore().servers())
    }
    @Test fun draftPersistsInNewStore() = runTest {
        val store = LocalStateStore()
        val key = SessionKey("https://one.invalid", "omp", "test", "draft")
        store.addServer(key.origin)
        store.updateMessage(key) { it.copy(draft = MessageDraft("rascunho", 3)) }
        assertEquals(MessageDraft("rascunho", 3), LocalStateStore().message(key).draft)
    }
    @Test fun removalPreservesOtherServerState() = runTest {
        val store = LocalStateStore()
        val first = SessionKey("https://one.invalid", "omp", "test", "same")
        val second = first.copy(origin = "https://two.invalid")
        for (key in listOf(first, second)) {
            store.addServer(key.origin)
            store.updateMessage(key) { it.copy(draft = MessageDraft(key.origin)) }
            store.setSessionView(key, "prompts")
            store.setSeenReplies(key.origin, mapOf(store.seenId(key) to 123L))
            store.setConversation(key, listOf(TerminalEntry("cached", "assistant", key.origin)), false,
                store.conversationWrite(key.origin, store.conversationEpoch(key.origin)))
        }
        store.removeServer(first.origin)
        val reopened = LocalStateStore()
        assertEquals(listOf(second.origin), reopened.servers())
        assertEquals(MessageLocalState(), reopened.message(first))
        assertEquals(second.origin, reopened.message(second).draft.text)
        assertNull(reopened.conversation(first))
        assertEquals(second.origin, reopened.conversation(second)?.entries?.single()?.text)
        assertEquals("prompts", reopened.sessionView(second))
        assertTrue(reopened.seenReplies(first.origin).isEmpty())
        assertEquals(123L, reopened.seenReplies(second.origin)[reopened.seenId(second)])
    }
    @Test fun clearPreservesDeviceSettings() = runTest {
        val store = LocalStateStore()
        store.addServer("https://one.invalid")
        store.setTheme("dark")
        store.setSummarize(true)
        store.setRetryUntil("https://one.invalid", 456L)
        store.clear()
        assertTrue(LocalStateStore().servers().isEmpty())
        assertEquals("dark", settingsNode.get("theme", null))
        assertEquals("dark", LocalStateStore().theme.first())
        assertTrue(settingsNode.getBoolean("summarize_replies", false))
        assertEquals(456L, store.retryUntil("https://one.invalid"))
    }
    @Test fun writesLeaveNoTemporaryFiles() = runTest {
        val store = LocalStateStore()
        store.addServer("https://one.invalid")
        store.setTheme("light")
        assertTrue(Files.exists(dataDir.resolve("local_state.json")))
        Files.list(dataDir).use { files -> assertFalse(files.anyMatch { it.toString().endsWith(".tmp") }) }
    }
    @Test fun hashAndMigratedSeenRepliesKeepNativeFormat() = runTest {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", storageHash(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", storageHash("abc"))
        val key = SessionKey("https://one.invalid", "omp", "test", "legacy")
        val hash = storageHash(key.serialize())
        protectedWrite("$privateDirectory/local_state.json", """{"origin":"${key.origin}","seen_replies":{"$hash":123}}""")
        val store = LocalStateStore()
        assertEquals(listOf(key.origin), store.servers())
        assertEquals(hash, store.seenId(key))
        assertEquals(123L, store.seenReplies(key.origin)[hash])
    }
}
