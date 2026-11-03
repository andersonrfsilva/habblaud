package br.com.xlinx.habblaud

import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.castOwner
import br.com.xlinx.habblaud.data.SessionItem
import br.com.xlinx.habblaud.data.initKeepsSession
import br.com.xlinx.habblaud.data.snapshotMatchesSession
import br.com.xlinx.habblaud.data.wireJson
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SessionIdentityTest {
    @Test fun serializationPreservesSeparatorsAndOrigin() {
        val key = SessionKey("https://example.test:443", "omp", "a:b/\"", "c:d")
        assertEquals(key, SessionKey.deserialize(key.serialize()))
        assertNotEquals(key, key.copy(origin = "http://example.test:443"))
        assertNotEquals(key, key.copy(provider = "claude"))
        assertNotEquals(key, key.copy(account = "a", sessionId = "b:c:d"))
    }
    @Test fun snapshotMustStillBindAgentToOriginalSession() {
        val key = SessionKey("https://example.test", "omp", "account", "old")
        fun snapshot(session: String, provider: String = "omp") =
            """{"agents":[{"id":"agent","kind":"main","account":"account","provider":"$provider","sessionId":"$session"}]}"""
        assertTrue(snapshotMatchesSession(snapshot("old"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("new"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("old", "claude"), key, "agent"))
        assertFalse(snapshotMatchesSession(snapshot("old"), key, "other"))
        assertFalse(snapshotMatchesSession("""{"agents":[]}""", key, "agent"))
    }
    @Test fun repeatedInitOfTheSameSessionKeepsItButAMovedAgentLeaves() {
        // O /shake reescreve o arquivo e o servidor manda um segundo init da MESMA sessão: a tela continua.
        val key = SessionKey("https://example.test", "omp", "account", "old")
        val item = SessionItem(key, "agent", "t", "/p", "idle", null)
        val sameSession = """{"agents":[{"id":"agent","kind":"main","account":"account","provider":"omp","sessionId":"old"}]}"""
        assertTrue(initKeepsSession(snapshotMatchesSession(sameSession, key, "agent"), listOf(item), item))
        // /clear: o agente foi para outra sessão, no snapshot e na lista.
        val moved = """{"agents":[{"id":"agent","kind":"main","account":"account","provider":"omp","sessionId":"new"}]}"""
        assertFalse(initKeepsSession(snapshotMatchesSession(moved, key, "agent"), listOf(item), item))
        assertFalse(initKeepsSession(true, listOf(item.copy(key = key.copy(sessionId = "new"))), item))
    }
    @Test fun closedOmpSessionBelongsOnlyToTheSingleCastMemberThatLastUsedIt() {
        fun member(id: String, vararg last: Pair<String, String>) = wireJson.parseToJsonElement(
            """{"id":"$id","name":"$id","last":{${last.joinToString(",") { (room, s) -> "\"$room\":\"$s\"" }}}}""").jsonObject
        val key = SessionKey("http://100.64.0.1:4747", "omp", ".omp", "01a1-sessao")
        val didiz = member("didiz", "/a" to "01a1-sessao", "/b" to "01a1-sessao")
        val mira = member("mira", "/a" to "01a1-outra")
        assertEquals("didiz", castOwner(listOf(mira, didiz), key)?.get("id")?.toString()?.trim('"'))
        assertNull(castOwner(listOf(mira), key))
        assertNull(castOwner(listOf(didiz, member("dup", "/c" to "01a1-sessao")), key))
        assertNull(castOwner(listOf(didiz), key.copy(provider = "claude")))
    }
}
