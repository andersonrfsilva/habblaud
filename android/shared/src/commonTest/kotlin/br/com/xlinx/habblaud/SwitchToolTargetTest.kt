package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwitchToolTargetTest {
    private val previous = SessionKey("https://example.test", "omp", "account", "old")
    private val successor = SessionItem(
        key = previous.copy(provider = "antigravity", account = "other", sessionId = "new"),
        agentId = "new-agent", title = "Contact", project = null, status = "idle", activity = null,
        cast = "contact",
    )

    @Test fun followsContactAcrossProvidersAndAccountsInBothDirections() {
        assertEquals(successor, switchToolTarget(listOf(successor), previous, "contact"))
        val returning = successor.copy(key = previous, agentId = "returning-agent")
        assertEquals(returning, switchToolTarget(listOf(returning), successor.key, "contact"))
    }

    @Test fun followsNewContactOutsideTheCast() {
        val newContact = successor.copy(cast = "novo-123")
        assertEquals(newContact, switchToolTarget(listOf(newContact), previous, "novo-123"))
    }

    @Test fun rejectsOtherOriginsContactsSubagentsClosedAndUnlinkedSessions() {
        val rejected = listOf(
            successor.copy(key = successor.key.copy(origin = "https://other.test")),
            successor.copy(cast = "other"),
            successor.copy(cast = null),
            successor.copy(sub = true),
            successor.copy(agentId = null),
            successor.copy(key = previous),
            successor.copy(status = "offline"),
            successor.copy(status = "done"),
        )
        for (item in rejected) assertNull(switchToolTarget(listOf(item), previous, "contact"))
        assertEquals(successor, switchToolTarget(rejected + successor, previous, "contact"))
    }

    @Test fun doesNotFollowWithoutPreviousCast() {
        assertNull(switchToolTarget(listOf(successor, successor.copy(cast = null)), previous, null))
        assertNull(switchToolTarget(emptyList(), previous, "contact"))
    }
}
