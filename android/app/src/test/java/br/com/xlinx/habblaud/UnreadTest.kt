package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.byLastMessage
import br.com.xlinx.habblaud.data.unreadCount
import br.com.xlinx.habblaud.data.updatedSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnreadTest {
    @Test fun onlyRepliesAfterTheLastReadCount() {
        assertEquals(2, unreadCount(listOf(100, 200, 300), seenAt = 150))
        assertEquals(0, unreadCount(listOf(100, 200), seenAt = 200))
        assertEquals(0, unreadCount(listOf(100), seenAt = null))
    }

    @Test fun firstSightStartsReadAndTheOpenConversationIsReadUntilNow() {
        // Conversa nova entra lida; a aberta avança; a outra, já marcada, fica como estava.
        assertEquals(mapOf("a" to 50L, "b" to 500L, "c" to 500L), updatedSeen(mapOf("a" to 50L, "b" to 10L), listOf("a", "b", "c"), openId = "b", now = 500))
        assertNull(updatedSeen(mapOf("a" to 50L), listOf("a"), openId = null, now = 500))
    }

    @Test fun newestReplyFirstFallingBackToTheLastEvent() {
        fun item(id: String, replies: List<Long> = emptyList(), lastAt: Long? = null) = SessionItem(SessionKey("o", "omp", "a", id), id, id, null, null, null, lastAt = lastAt, replies = replies)
        val sorted = byLastMessage(listOf(item("velha", listOf(10)), item("sem", lastAt = 50), item("nova", listOf(5, 90), lastAt = 95), item("nada")))
        assertEquals(listOf("nova", "sem", "velha", "nada"), sorted.map { it.key.sessionId })
    }
}
