package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.notify_done
import br.com.xlinx.habblaud.resources.notify_interrupted
import br.com.xlinx.habblaud.resources.notify_permission
import br.com.xlinx.habblaud.resources.notify_question
import br.com.xlinx.habblaud.resources.notify_waiting
import kotlin.test.*

class NotificationTextTest {
    private val session = SessionItem(SessionKey("http://servidor:4747", "omp", ".omp", "s1"), "agente", "Título", "/projetos/Projeto", "idle", null)

    @Test fun eachEventUsesItsOwnText() {
        assertEquals(Res.string.notify_permission, alertText(AgentEvent.Permission(session)))
        assertEquals(Res.string.notify_question, alertText(AgentEvent.Permission(session.copy(permissionQuestion = true))))
        assertEquals(Res.string.notify_done, alertText(AgentEvent.TurnDone(session)))
        assertEquals(Res.string.notify_waiting, alertText(AgentEvent.Permission(session, WaitKind.Terminal)))
        assertEquals(Res.string.notify_interrupted, alertText(AgentEvent.TurnDone(session, interrupted = true)))
    }

    @Test fun appendsWaitReasonAndLimitsItTo120Characters() {
        assertEquals("Aviso: teste", alertBody("Aviso", AgentEvent.Permission(session, reason = " teste ")))
        assertEquals("Aviso", alertBody("Aviso", AgentEvent.Permission(session, reason = " ")))
        assertEquals("Aviso: " + "x".repeat(119) + "…", alertBody("Aviso", AgentEvent.Permission(session, reason = "x".repeat(121))))
    }

    @Test fun finishedTurnShowsTheStartOfTheReply() {
        assertEquals("Pronto. O deploy terminou.", alertBody("Terminou o turno", AgentEvent.TurnDone(session, excerpt = "Pronto. O deploy terminou.")))
        assertEquals("Terminou o turno", alertBody("Terminou o turno", AgentEvent.TurnDone(session)))
        assertEquals("Turno interrompido", alertBody("Turno interrompido", AgentEvent.TurnDone(session, interrupted = true, excerpt = "x")))
    }

    @Test fun subtitleIncludesServerOnlyWithMultipleServers() {
        val event = AgentEvent.Permission(session)
        assertEquals("Projeto", alertSubtitle(event, false))
        assertEquals("Projeto · servidor:4747", alertSubtitle(event, true))
        val noProject = AgentEvent.TurnDone(session.copy(project = null))
        assertNull(alertSubtitle(noProject, false))
        assertEquals("servidor:4747", alertSubtitle(noProject, true))
    }
}
