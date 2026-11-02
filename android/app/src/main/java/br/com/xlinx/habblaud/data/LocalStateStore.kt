package br.com.xlinx.habblaud.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.localState by preferencesDataStore(name = "local_state")
private val Context.rateState by preferencesDataStore(name = "login_rate")

data class ConnectionSettings(val origin: String, val consent: Boolean)
data class MessageDraft(val text: String = "", val mode: String = "aside", val revision: Long = 0)
data class MessageOperation(val id: String? = null, val status: String = "unknown", val revision: Long = 0)
data class MessageLocalState(val draft: MessageDraft = MessageDraft(), val operation: MessageOperation? = null)
interface MessageStore {
    suspend fun message(key: SessionKey): MessageLocalState
    suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState
}

private fun messageKey(key: SessionKey) = stringPreferencesKey("message_" + java.security.MessageDigest.getInstance("SHA-256").digest(key.serialize().toByteArray()).joinToString("") { "%02x".format(it) })
private fun decodeMessage(value: String?): MessageLocalState {
    if (value == null) return MessageLocalState()
    val json = org.json.JSONObject(value)
    val draft = MessageDraft(json.optString("text", ""), json.optString("mode", "aside"), json.optLong("revision", 0))
    val op = json.optJSONObject("operation")?.let { MessageOperation(it.optString("id").ifEmpty { null }, it.optString("status", "unknown"), it.optLong("revision", 0)) }
    return MessageLocalState(draft, op)
}
private fun encodeMessage(state: MessageLocalState): String {
    val json = org.json.JSONObject().put("text", state.draft.text).put("mode", state.draft.mode).put("revision", state.draft.revision)
    state.operation?.let { json.put("operation", org.json.JSONObject().put("id", it.id).put("status", it.status).put("revision", it.revision)) }
    return json.toString()
}

class LocalStateStore(private val context: Context) : MessageStore {
    private val originKey = stringPreferencesKey("origin")
    private val consentKey = booleanPreferencesKey("http_consent")
    private val sessionKey = stringPreferencesKey("last_session")

    override suspend fun message(key: SessionKey): MessageLocalState = decodeMessage(context.localState.data.first()[messageKey(key)])
    override suspend fun updateMessage(key: SessionKey, update: (MessageLocalState) -> MessageLocalState): MessageLocalState {
        var result = MessageLocalState()
        context.localState.edit { data ->
            check(data[originKey] == key.origin) { "Connection changed" }
            val k = messageKey(key)
            result = update(decodeMessage(data[k]))
            data[k] = encodeMessage(result)
        }
        return result
    }
    suspend fun lastSession(): SessionKey? = context.localState.data.first()[sessionKey]?.let { runCatching { SessionKey.deserialize(it) }.getOrNull() }
    suspend fun setLastSession(key: SessionKey?) {
        context.localState.edit { if (key == null) it.remove(sessionKey) else it[sessionKey] = key.serialize() }
    }
    private fun retryKey(origin: String) = longPreferencesKey("retry_" + java.security.MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) })

    suspend fun retryUntil(origin: String): Long = context.rateState.data.first()[retryKey(origin)] ?: 0

    suspend fun setRetryUntil(origin: String, until: Long) {
        context.rateState.edit { data -> data[retryKey(origin)] = until }
    }

    suspend fun connection(): ConnectionSettings? {
        val data = context.localState.data.first()
        return data[originKey]?.let { ConnectionSettings(it, data[consentKey] == true) }
    }

    suspend fun setConnection(origin: String, consent: Boolean) {
        context.localState.edit { data ->
            if (data[originKey] != origin) data.clear()
            data[originKey] = origin
            data[consentKey] = consent
        }
    }

    suspend fun clear() { context.localState.edit { it.clear() } }
}
