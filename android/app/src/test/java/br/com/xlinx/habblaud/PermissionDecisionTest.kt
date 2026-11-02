package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PermissionDecisionTest {
    private val questions = wireJson.parseToJsonElement("""[{"index":2,"question":"Uma","options":[{"index":4,"label":"A"},{"index":8,"label":"B"}]},{"index":7,"question":"Várias","multiSelect":true,"options":[{"index":1,"label":"C"},{"index":5,"label":"D"}]}]""").jsonArray.map { it.jsonObject }

    @Test fun answersUseOriginalIndicesAndFreeText() {
        val decision = permissionDecision("claude", "answer", questions, mapOf(2 to setOf(8), 7 to setOf(1, 5)), mapOf(7 to "outra"))
        val answers = decision.getValue("answers").jsonArray
        assertEquals(2, answers[0].jsonObject.getValue("question").jsonPrimitive.int)
        assertEquals(8, answers[0].jsonObject.getValue("options").jsonArray.single().jsonPrimitive.int)
        assertEquals(listOf(1, 5), answers[1].jsonObject.getValue("options").jsonArray.map { it.jsonPrimitive.int })
        assertEquals("outra", answers[1].jsonObject.getValue("other").jsonPrimitive.content)
        val free = permissionDecision("claude", "answer", questions.take(1), emptyMap(), mapOf(2 to "livre"))
        assertEquals("livre", free.getValue("answers").jsonArray.single().jsonObject.getValue("other").jsonPrimitive.content)
    }

    @Test fun ompAnswersUseOriginalIndicesAndFreeText() {
        val decision = permissionDecision("omp", "answer", questions, mapOf(2 to setOf(8), 7 to setOf(1, 5)), mapOf(7 to "outra"))
        assertEquals(wireJson.parseToJsonElement("""{"behavior":"answer","answers":[{"question":2,"options":[8]},{"question":7,"options":[1,5],"other":"outra"}]}"""), decision)
        val free = permissionDecision("omp", "answer", questions.take(1), emptyMap(), mapOf(2 to "livre"))
        assertEquals(wireJson.parseToJsonElement("""{"behavior":"answer","answers":[{"question":2,"other":"livre"}]}"""), free)
    }

    @Test fun rejectsInvalidSingleAndUnsupportedControls() {
        assertThrows(IllegalArgumentException::class.java) { permissionDecision("claude", "answer", questions.take(1), mapOf(2 to setOf(4, 8)), emptyMap()) }
        for (provider in listOf("omp", "codex")) {
            assertThrows(IllegalArgumentException::class.java) { permissionDecision(provider, "allow", emptyList(), emptyMap(), emptyMap(), suggestion = 0) }
            assertThrows(IllegalArgumentException::class.java) { permissionDecision(provider, "deny", emptyList(), emptyMap(), emptyMap(), interrupt = true) }
        }
        for (behavior in listOf("allow", "deny", "terminal")) assertEquals(behavior, permissionDecision("omp", behavior, emptyList(), emptyMap(), emptyMap()).getValue("behavior").jsonPrimitive.content)
    }
    @Test fun expiryAndConflictCloseWithoutDecision() {
        assertTrue(permissionExpired(100, 100))
        assertFalse(permissionExpired(101, 100))
        assertTrue(permissionClosed(404))
        assertTrue(permissionClosed(409))
        assertFalse(permissionClosed(500))
    }
}
