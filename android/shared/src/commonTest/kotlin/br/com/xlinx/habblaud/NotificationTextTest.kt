package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.notify_permission
import br.com.xlinx.habblaud.resources.notify_question
import br.com.xlinx.habblaud.resources.notify_waiting
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NotificationTextTest {
    private val session = SessionItem(SessionKey("http://servidor:4747", "omp", ".omp", "s1"), "agente", "Título", "/projetos/Projeto", "idle", null)

    @Test fun eachWaitUsesItsOwnText() {
        assertEquals(Res.string.notify_permission, alertText(AgentEvent.Permission(session)))
        assertEquals(Res.string.notify_question, alertText(AgentEvent.Permission(session.copy(permissionQuestion = true))))
        assertEquals(Res.string.notify_waiting, alertText(AgentEvent.Permission(session, WaitKind.Terminal)))
    }

    @Test fun appendsWaitReasonAndLimitsItTo120Characters() {
        assertEquals("Aviso: teste", alertBody("Aviso", AgentEvent.Permission(session, reason = " teste ")))
        assertEquals("Aviso", alertBody("Aviso", AgentEvent.Permission(session, reason = " ")))
        assertEquals("Aviso: " + "x".repeat(119) + "…", alertBody("Aviso", AgentEvent.Permission(session, reason = "x".repeat(121))))
    }

    @Test fun finishedTurnShowsTheStartOfTheReply() = runTest {
        val event = AgentEvent.TurnDone(session, excerpt = "Pronto. O deploy terminou.")
        assertEquals(event.excerpt, alertBody(event))
    }

    @Test fun subtitleIncludesServerOnlyWithMultipleServers() {
        val event = AgentEvent.Permission(session)
        assertEquals("Projeto", alertSubtitle(event, false))
        assertEquals("Projeto · servidor:4747", alertSubtitle(event, true))
        val noProject = AgentEvent.TurnDone(session.copy(project = null), excerpt = "Resposta final")
        assertNull(alertSubtitle(noProject, false))
        assertEquals("servidor:4747", alertSubtitle(noProject, true))
    }
}
