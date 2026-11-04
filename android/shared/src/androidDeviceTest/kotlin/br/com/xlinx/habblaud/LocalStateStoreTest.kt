package br.com.xlinx.habblaud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.xlinx.habblaud.data.LocalStateStore
import br.com.xlinx.habblaud.data.SessionKey
import br.com.xlinx.habblaud.data.localState
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStateStoreTest {
    @Test fun connectionAndRetrySurviveRecreationAndAreIsolated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = LocalStateStore(context)
        first.clear()
        first.addServer("https://example.com")
        first.setRetryUntil("https://example.com", 123456789L)
        first.setRetryUntil("https://other.example.com", 987654321L)
        val second = LocalStateStore(context)
        assertEquals(listOf("https://example.com"), second.servers())
        assertEquals(123456789L, second.retryUntil("https://example.com"))
        assertEquals(987654321L, second.retryUntil("https://other.example.com"))
        second.clear()
        assertTrue(first.servers().isEmpty())
        assertEquals(123456789L, first.retryUntil("https://example.com"))
    }

    @Test fun removingOneServerPreservesOtherState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStateStore(context)
        store.clear()
        val a = SessionKey("https://a.example", "omp", "account", "session")
        val b = a.copy(origin = "https://b.example")
        try {
            store.addServer(a.origin); store.addServer(b.origin); store.addServer(a.origin)
            assertEquals(listOf(a.origin, b.origin), store.servers())
            listOf(a, b).forEach { key ->
                store.updateMessage(key) { it.copy(draft = it.draft.copy(text = key.origin)) }
                store.setSessionView(key, "prompts")
                store.setSessionDeliverAs(key, "steer")
                store.setSeenReplies(key.origin, mapOf(store.seenId(key) to 123L))
            }
            store.setLastSession(b)
            store.removeServer(a.origin)
            val reopened = LocalStateStore(context)
            assertEquals(listOf(b.origin), reopened.servers())
            assertEquals("", reopened.message(a).draft.text)
            assertTrue(reopened.seenReplies(a.origin).isEmpty())
            assertEquals(b.origin, reopened.message(b).draft.text)
            assertEquals("prompts", reopened.sessionView(b))
            assertEquals("steer", reopened.sessionDeliverAs(b))
            assertEquals(mapOf(store.seenId(b) to 123L), reopened.seenReplies(b.origin))
            assertEquals(b, reopened.lastSession())
            val names = context.localState.data.first().asMap().keys.map { it.name }
            val tag = hash(a.origin).take(16)
            assertFalse(names.any { it.startsWith("message_${tag}_") || it.startsWith("view_${tag}_") || it.startsWith("deliver_${tag}_") || it == "seen_replies_$tag" })
        } finally { store.clear() }
    }

    @Test fun legacyServerMigrationPreservesDraftAndPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalStateStore(context)
        store.clear()
        val origin = "https://legacy.example"
        val key = SessionKey(origin, "omp", "account", "session")
        val hash = hash(key.serialize())
        try {
            val directory = context.filesDir.resolve("conversations/legacy-generation").apply { mkdirs() }
            val cached = directory.resolve("$hash.json").apply { writeText("conversa antiga") }
            val pending = directory.resolve("$hash.json.tmp").apply { writeText("conversa pendente") }
            val tagged = directory.resolve("other-tag/other.json").apply { parentFile!!.mkdirs(); writeText("outro cache") }
            context.localState.edit {
                it.clear()
                it[stringPreferencesKey("origin")] = origin
                it[stringPreferencesKey("conversation_generation")] = "legacy-generation"
                it[stringPreferencesKey("message_$hash")] = """{"text":"rascunho antigo","revision":7}"""
                it[stringPreferencesKey("view_$hash")] = "prompts"
                it[stringPreferencesKey("deliver_$hash")] = "steer"
                it[stringPreferencesKey("seen_replies")] = """{"$hash":123}"""
            }
            assertEquals(listOf(origin), store.servers())
            assertEquals("rascunho antigo", store.message(key).draft.text)
            assertEquals(7L, store.message(key).draft.revision)
            assertEquals("prompts", store.sessionView(key))
            assertEquals("steer", store.sessionDeliverAs(key))
            assertEquals(mapOf(hash to 123L), store.seenReplies(origin))
            assertFalse(context.localState.data.first().asMap().keys.any { it.name == "origin" || it.name == "message_$hash" })
            assertFalse(cached.exists())
            assertFalse(pending.exists())
            assertTrue(tagged.exists())
        } finally { store.clear() }
    }

    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
