package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.*
import br.com.xlinx.habblaud.resources.Res
import br.com.xlinx.habblaud.resources.notify_done
import br.com.xlinx.habblaud.resources.notify_permission
import br.com.xlinx.habblaud.resources.notify_question
import kotlin.test.*

class NotificationTextTest {
    private val session = SessionItem(SessionKey("http://servidor:4747", "omp", ".omp", "s1"), "agente", "Título", "/projetos/Projeto", "idle", null)

    @Test fun eachEventUsesItsOwnText() {
        assertEquals(Res.string.notify_permission, alertText(AgentEvent.Permission(session)))
        assertEquals(Res.string.notify_question, alertText(AgentEvent.Permission(session.copy(permissionQuestion = true))))
        assertEquals(Res.string.notify_done, alertText(AgentEvent.TurnDone(session)))
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
