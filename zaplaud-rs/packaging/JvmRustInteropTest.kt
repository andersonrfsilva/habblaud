package br.com.xlinx.habblaud.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Exerce o store desktop real; o runner instala este teste apenas durante a verificação. */
class JvmRustInteropTest {
    @Test fun roundTrip() = runBlocking {
        val root = Path.of(requireNotNull(System.getenv("ZAPLAUD_JVM_INTEROP_DIR")))
        System.setProperty("zaplaud.dataDir", root.toString())
        System.setProperty("zaplaud.prefsNode", "br/com/xlinx/zaplaud/interop-${root.fileName}")
        val key = SessionKey("https://interop.invalid", "omp", "account/é😀", "session/\"😀")
        val store = LocalStateStore()
        if (System.getenv("ZAPLAUD_JVM_INTEROP_VERIFY") == "1") {
            assertTrue(Files.exists(root.resolve("data/rust-written")))
            assertEquals("Rust reescreveu 😀", store.message(key).draft.text)
            assertEquals(8L, store.message(key).draft.revision)
            assertEquals("no_tools", store.sessionView(key))
            assertEquals("Rust cache", store.conversation(key)?.entries?.single()?.text)
        } else {
            store.addServer(key.origin)
            store.updateMessage(key) {
                MessageLocalState(
                    MessageDraft("JVM criou 😀", 7, listOf(Attachment("file:///fixture", "é😀", "text/plain", 42, true))),
                    MessageOperation("job", "queued", 7), MessageOperation()
                )
            }
            store.setSessionView(key, "prompts")
            store.setSessionDeliverAs(key, "followUp")
            store.setSeenReplies(key.origin, mapOf(store.seenId(key) to 123L))
            store.setLastSession(key)
            store.setConversation(key, listOf(TerminalEntry("jvm", "assistant", "JVM cache")), false,
                store.conversationWrite(key.origin, store.conversationEpoch(key.origin)), emptyMap())
        }
    }
}
