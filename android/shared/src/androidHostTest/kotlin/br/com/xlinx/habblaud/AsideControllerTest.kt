package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.ApiException
import br.com.xlinx.habblaud.data.AsideController
import br.com.xlinx.habblaud.data.wireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsideControllerTest {
    private fun topic(id: String, status: String, answer: String = "") =
        """{"id":"$id","createdAt":1,"updatedAt":1,"turns":[{"id":"$id-q","question":"o que faz?","answer":"$answer","status":"$status","createdAt":1,"updatedAt":1}]}"""

    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }

    @Test fun pollsWhileRunningThenStops() = runBlocking {
        val gets = mutableListOf<String>()
        val replies = ArrayDeque(listOf(topic("t1", "running"), topic("t1", "running", "meio"), topic("t1", "complete", "fim")))
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = AsideController({ path, body -> assertNull(body); gets += path; """{"topics":[${replies.removeFirst()}]}""" }, scope, {})
        try {
            c.open("agent/1")
            waitFor { c.state.value.topic?.turns?.single()?.status == "complete" }
            assertEquals("/api/asides?agentId=agent%2F1", gets.first())
            assertEquals("fim", c.state.value.topic!!.turns.single().answer)
            delay(1000)
            assertEquals(3, gets.size)
            assertFalse(c.state.value.running)
        } finally { scope.cancel() }
    }

    @Test fun askContinuesCurrentTopicOrOpensNew() = runBlocking {
        val posts = mutableListOf<Pair<String, String?>>()
        // Como o servidor: a pergunta guarda a conversa, e a consulta seguinte a devolve (mais nova primeiro).
        val stored = mutableListOf(topic("t1", "complete", "ok"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = AsideController({ path, body ->
            if (body == null) """{"topics":[${stored.joinToString(",")}]}"""
            else {
                posts += path to body
                if (posts.size == 1) """{"topic":${stored[0]}}""" else topic("t2", "complete", "r").also { stored.add(0, it) }.let { """{"topic":$it}""" }
            }
        }, scope, {})
        try {
            c.open("a")
            waitFor { c.state.value.topics.isNotEmpty() }
            c.ask("  segue?  ")
            waitFor { posts.size == 1 && !c.state.value.sending }
            val first = wireJson.parseToJsonElement(posts[0].second!!).jsonObject
            assertEquals("/api/asides", posts[0].first)
            assertEquals("segue?", first["question"]!!.jsonPrimitive.content)
            assertEquals("t1", first["topicId"]!!.jsonPrimitive.content)
            c.startNew()
            assertNull(c.state.value.topic)
            c.ask("outra")
            waitFor { posts.size == 2 && !c.state.value.sending }
            assertNull(wireJson.parseToJsonElement(posts[1].second!!).jsonObject["topicId"])
            assertEquals("t2", c.state.value.topic!!.id)
            assertEquals(listOf("t2", "t1"), c.state.value.topics.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun serverErrorTextAndUnauthorized() = runBlocking {
        var unauthorized = 0
        var status = 409
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val c = AsideController({ _, body ->
            if (body == null) """{"topics":[]}""" else throw ApiException(status, detail = "já há uma pergunta em andamento")
        }, scope, { unauthorized++ })
        try {
            c.open("a")
            waitFor { !c.state.value.loading }
            c.ask("x")
            waitFor { c.state.value.error != null }
            assertEquals("já há uma pergunta em andamento", c.state.value.error)
            assertEquals(0, unauthorized)
            status = 401
            c.ask("y")
            waitFor { unauthorized == 1 }
            assertTrue(c.state.value.topics.isEmpty())
        } finally { scope.cancel() }
    }
}
