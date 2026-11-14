package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.byLastMessage
import br.com.xlinx.habblaud.data.unreadCount
import br.com.xlinx.habblaud.data.updatedSeen
import br.com.xlinx.habblaud.data.SessionMarks
import br.com.xlinx.habblaud.data.ReadingState
import br.com.xlinx.habblaud.data.markedSessions
import br.com.xlinx.habblaud.data.mergeSessions
import kotlin.test.*

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

    @Test fun pinsKeepActivityOrderWithinEachPartitionAndFavoritesAreIndependent() {
        fun item(id: String, at: Long) = SessionItem(SessionKey("https://one.invalid", "omp", "a", id), id, id, null, null, null, lastAt = at)
        val newest = item("new", 40)
        val favorite = item("favorite", 30)
        val pinned = item("pin", 10)
        val both = item("both", 20)
        val sessions = listOf(newest, favorite, pinned, both)
        val marks = mapOf(favorite.key to SessionMarks(favorite = true), pinned.key to SessionMarks(pinned = true), both.key to SessionMarks(true, true))
        assertEquals(listOf(both, pinned, newest, favorite), markedSessions(sessions, marks, false, "", false))
        assertEquals(listOf(both, favorite), markedSessions(sessions, marks, false, "", true))
        assertEquals(listOf(favorite), markedSessions(sessions, marks, false, "favorite", false))
        assertEquals(listOf(both), markedSessions(sessions, marks, false, "both", true))
        assertEquals(listOf(newest, favorite, pinned, both), sessions)
    }

    @Test fun pinsNeverEscapeServerSearchOrActiveFilters() {
        val first = SessionItem(SessionKey("https://one.invalid", "omp", "a", "same"), "agent", "Visible", null, null, null)
        val other = first.copy(key = first.key.copy(origin = "https://two.invalid"))
        val history = first.copy(key = first.key.copy(sessionId = "history"), agentId = null, title = "History")
        val marks = mapOf(other.key to SessionMarks(true, true), history.key to SessionMarks(true, true))
        val sessions = mergeSessions(mapOf(first.key.origin to ReadingState(sessions = listOf(first, history)), other.key.origin to ReadingState(sessions = listOf(other))), first.key.origin)
        assertEquals(listOf(first), markedSessions(sessions, marks, false, "", false))
        assertEquals(listOf(history, first), markedSessions(sessions, marks, true, "", false))
        assertTrue(markedSessions(sessions, marks, false, "", true).isEmpty())
        assertTrue(markedSessions(sessions, marks, true, "Visible", true).isEmpty())
    }
}
