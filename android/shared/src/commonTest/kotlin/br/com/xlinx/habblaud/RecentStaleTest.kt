package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.recentIsStale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentStaleTest {
    private val session = SessionItem(SessionKey("https://example.test", "omp", "account", "session"),
        "agent", "Conversa", "/before", "working", null)
    private val other = session.copy(key = session.key.copy(sessionId = "other"))

    @Test fun missingSessionMakesRecentStale() {
        assertTrue(recentIsStale(listOf(session), listOf(other), emptyList()))
    }

    @Test fun sessionAlreadyInRecentDoesNotMakeItStale() {
        assertFalse(recentIsStale(listOf(session), listOf(other), listOf(session.copy(agentId = null))))
    }

    @Test fun unchangedSnapshotDoesNotMakeRecentStale() {
        assertFalse(recentIsStale(listOf(session), listOf(session), emptyList()))
    }

    @Test fun firstSnapshotDoesNotMakeRecentStale() {
        assertFalse(recentIsStale(emptyList(), listOf(session), emptyList()))
    }

    @Test fun movedSessionDoesNotMakeRecentStale() {
        assertFalse(recentIsStale(listOf(session, other), listOf(other, session.copy(project = "/after")), emptyList()))
    }
}
