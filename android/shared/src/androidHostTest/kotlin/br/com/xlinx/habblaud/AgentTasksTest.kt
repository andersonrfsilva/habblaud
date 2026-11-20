package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.AgentTask
import br.com.xlinx.habblaud.data.TaskStatus
import br.com.xlinx.habblaud.data.parseAgentTasks
import br.com.xlinx.habblaud.data.taskPhases
import br.com.xlinx.habblaud.data.taskProgress
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTasksTest {
    @Test fun toleratesMissingOrNonArrayTasks() {
        assertTrue(parseAgentTasks(null).isEmpty())
        for (value in listOf("null", "{}", "true", "\"tasks\"")) {
            assertTrue(parseAgentTasks(Json.parseToJsonElement(value)).isEmpty(), value)
        }
    }

    @Test fun dropsMalformedTasksWithoutLosingValidOnes() {
        val result = parseAgentTasks(Json.parseToJsonElement("""[
            null, [], true,
            {"title":"Sem id","status":"pending"},
            {"id":"sem-titulo","status":"pending"},
            {"id":"sem-estado","title":"Sem estado"},
            {"id":"desconhecido","title":"Desconhecido","status":"unknown"},
            {"id":25,"title":"Id inválido","status":"pending"},
            {"id":"titulo-objeto","title":{},"status":"pending"},
            {"id":"1","title":"Válida","status":"in_progress","activeForm":"Fazendo","phase":"Teste"},
            {"id":"2","title":"Ignora opcionais inválidos","status":"pending","activeForm":[],"phase":true}
        ]"""))
        assertEquals(listOf(
            AgentTask("1", "Válida", TaskStatus.IN_PROGRESS, "Fazendo", "Teste"),
            AgentTask("2", "Ignora opcionais inválidos", TaskStatus.PENDING),
        ), result)
    }

    @Test fun excludesAbandonedAndUsesFirstActiveTask() {
        val tasks = listOf(
            AgentTask("1", "Feita", TaskStatus.COMPLETED),
            AgentTask("2", "Abandonada", TaskStatus.ABANDONED),
            AgentTask("3", "Primeira", TaskStatus.IN_PROGRESS, "Fazendo a primeira"),
            AgentTask("4", "Segunda", TaskStatus.IN_PROGRESS),
            AgentTask("5", "Pendente", TaskStatus.PENDING),
        )
        val progress = taskProgress(tasks)
        assertEquals(1, progress?.done)
        assertEquals(4, progress?.total)
        assertEquals("Fazendo a primeira", progress?.current)
        assertEquals("Segunda", taskProgress(listOf(tasks[3]))?.current)
    }

    @Test fun countsTasksWithoutAnActiveOne() {
        val progress = taskProgress(listOf(AgentTask("1", "Feita", TaskStatus.COMPLETED)))
        assertEquals(1, progress?.done)
        assertEquals(1, progress?.total)
        assertNull(progress?.current)
    }

    @Test fun noProgressWhenNothingCounts() {
        assertNull(taskProgress(emptyList()))
        assertNull(taskProgress(listOf(AgentTask("1", "Abandonada", TaskStatus.ABANDONED))))
    }

    @Test fun groupsAdjacentPhasesWithoutReordering() {
        val tasks = listOf("Z", "Z", null, "A", "Z").mapIndexed { index, phase ->
            AgentTask(index.toString(), "Tarefa", TaskStatus.PENDING, phase = phase)
        }
        assertEquals(listOf("Z", null, "A", "Z"), taskPhases(tasks).map { it.name })
        assertEquals(listOf(2, 1, 1, 1), taskPhases(tasks).map { it.tasks.size })
        assertEquals(tasks, taskPhases(tasks).flatMap { it.tasks })
    }

    @Test fun noPhaseIsOneUnnamedGroup() {
        val tasks = listOf(AgentTask("1", "Tarefa", TaskStatus.PENDING), AgentTask("2", "Outra", TaskStatus.COMPLETED))
        val phase = taskPhases(tasks).single()
        assertNull(phase.name)
        assertEquals(tasks, phase.tasks)
        assertTrue(taskPhases(emptyList()).isEmpty())
    }
}
