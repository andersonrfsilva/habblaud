package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.AgentEvent
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.agentEvents
import br.com.xlinx.habblaud.data.watchOf
import kotlin.test.*

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

    @Test fun changesDuringADisconnectNotifyAgainstTheLastBaselineWithoutRepeatingOldRequests() {
        // Antes da queda: um agente trabalhando e outro já com o pedido p1 avisado.
        val other = SessionKey("http://servidor:4747", "omp", ".omp", "s2")
        val before = watchOf(listOf(session("working"), session("waiting", "p1").copy(key = other)))
        // Depois da reconexão: o primeiro terminou; o segundo segue no mesmo p1.
        val events = agentEvents(before, listOf(session("idle"), session("waiting", "p1").copy(key = other)))
        assertEquals(listOf(key), events.map { it.session.key })
        assertTrue(events.single() is AgentEvent.TurnDone)
    }
}
