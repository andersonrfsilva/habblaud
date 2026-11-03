package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.AgentEvent
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.agentEvents
import br.com.xlinx.habblaud.data.watchOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {
    private val key = SessionKey("http://servidor:4747", "omp", ".omp", "s1")
    private fun session(status: String?, permission: String? = null) =
        SessionItem(key, "agente", "Título", "/p", status, null, permission != null, permissionId = permission)

    @Test fun newPermissionNotifiesOnce() {
        val before = watchOf(listOf(session("working")))
        val asked = listOf(session("waiting", "p1"))
        assertTrue(agentEvents(before, asked).single() is AgentEvent.Permission)
        assertTrue(agentEvents(watchOf(asked), asked).isEmpty())
        assertTrue(agentEvents(watchOf(asked), listOf(session("waiting", "p2"))).single() is AgentEvent.Permission)
    }

    @Test fun workingToIdleIsTurnDoneButOtherChangesAreNot() {
        assertTrue(agentEvents(watchOf(listOf(session("working"))), listOf(session("idle"))).single() is AgentEvent.TurnDone)
        assertTrue(agentEvents(watchOf(listOf(session("working"))), listOf(session("shell"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("idle"))), listOf(session("idle"))).isEmpty())
        assertTrue(agentEvents(watchOf(listOf(session("waiting", "p1"))), listOf(session("working"))).isEmpty())
    }

    @Test fun newSessionAskingPermissionNotifiesButNotAsTurnDone() {
        assertTrue(agentEvents(emptyMap(), listOf(session("waiting", "p1"))).single() is AgentEvent.Permission)
        assertEquals(emptyList<AgentEvent>(), agentEvents(emptyMap(), listOf(session("idle"))))
    }
}
